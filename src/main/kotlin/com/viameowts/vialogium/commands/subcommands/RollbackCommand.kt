package com.viameowts.vialogium.commands.subcommands

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.actionutils.RollbackBlockTracker
import com.viameowts.vialogium.actionutils.RollbackLock
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.commands.arguments.SearchParamArgument
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.database.RollbackExecutionGuard
import com.viameowts.vialogium.logInfo
import com.viameowts.vialogium.utility.Context
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.MessageUtils
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.getWorld
import com.viameowts.vialogium.utility.launchMain
import com.viameowts.vialogium.utility.literal
import com.viameowts.vialogium.utility.ticks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType

object RollbackCommand : BuildableCommand {
    override fun build(): LiteralNode = Commands.literal("rollback")
        .requires(Permissions.require("vialogium.commands.rollback", CommandConsts.PERMISSION_LEVEL))
        .then(
            SearchParamArgument.argument("params")
                .executes { rollback(it, SearchParamArgument.get(it, "params")) },
        )
        .build()

    fun rollback(context: Context, params: ActionSearchParams): Int {
        val source = context.source
        params.ensureSpecific()
        val lock = RollbackLock.acquire(source) ?: return 0
        ViaLogium.launch {
            MessageUtils.warnBusy(source)
            val totalActions = DatabaseManager.countRollbackActions(params)

            if (totalActions == 0L) {
                source.sendFailure(
                    Component.translatable(
                        "error.vialogium.command.no_results",
                    ).setStyle(TextColorPallet.actionNegative),
                )
                return@launch
            }

            source.sendSuccess(
                {
                    Component.translatable(
                        "text.vialogium.rollback.start",
                        totalActions.toString().literal().setStyle(TextColorPallet.secondary),
                    ).setStyle(TextColorPallet.primary)
                },
                true,
            )

            // Pre-scan container block-break positions so item-drop/pickup of their spilled contents
            // are skipped (contents are restored from the break snapshot instead). Done off the main
            // thread before the rollback loop.
            val containerBreakPositions = DatabaseManager.selectContainerBreakPositions(params)

            lock.handOff(
                context.source.level.launchMain {
                    val selectBatchSize = ViaLogium.config[DatabaseSpec.emergencyBatchSize].coerceAtLeast(500)
                    val updateBatchSize = ViaLogium.config[DatabaseSpec.batchSize].coerceAtLeast(250)
                    val actionsPerTick = ViaLogium.config[DatabaseSpec.rollbackActionsPerTick].coerceAtLeast(1)
                    val fails = HashMap<String, Int>()
                    var processed = 0L
                    var actionsSinceYield = 0
                    var cursorId: Int? = null
                    val startedAtMs = System.currentTimeMillis()
                    var lastProgressLogMs = startedAtMs

                    // Tracks positions restored by a block-break so dependent block-place / item actions
                    // are not re-applied. Lives for the whole run (actions are global id DESC across batches).
                    val blockTracker = RollbackBlockTracker(containerBreakPositions)

                    // Collect chest positions for a second pass that reconnects double chests after
                    // all blocks have been restored (neighbor is guaranteed to exist by then).
                    val chestPositions = mutableMapOf<Identifier, MutableSet<BlockPos>>()

                    logInfo(
                        "rollback_sla stage=start source=${source.textName} total=$totalActions " +
                            "selectBatch=$selectBatchSize updateBatch=$updateBatchSize actionsPerTick=$actionsPerTick",
                    )

                    while (true) {
                        val actions = DatabaseManager.selectRollbackPreviewBatch(params, cursorId, selectBatchSize)
                        if (actions.isEmpty()) break

                        val actionIdsBatch = HashSet<Int>(updateBatchSize)
                        val updateJobs = mutableListOf<Job>()

                        fun flushBatch() {
                            if (actionIdsBatch.isEmpty()) return

                            val ids = actionIdsBatch.toSet()
                            actionIdsBatch.clear()
                            updateJobs += ViaLogium.launch {
                                DatabaseManager.rollbackActions(ids)
                            }
                        }

                        for (action in actions) {
                            if (blockTracker.shouldSkip(action)) {
                                // Position already restored (with its full state and contents) by a
                                // block-break that rolled back first. Re-applying would re-remove the block
                                // or strip the restored inventory: skip execution, still mark rolled_back.
                                actionIdsBatch.add(action.id)
                                if (actionIdsBatch.size >= updateBatchSize) {
                                    flushBatch()
                                }
                            } else if (!RollbackExecutionGuard.runWithoutLogging {
                                    action.rollback(
                                        context.source.server,
                                    )
                                }
                            ) {
                                fails[action.identifier] = fails.getOrPut(action.identifier) { 0 } + 1
                            } else {
                                blockTracker.recordRolledBack(action)
                                val actionWorld = action.world
                                if (action.oldObjectIdentifier != null && actionWorld != null) {
                                    val opt = BuiltInRegistries.BLOCK.getOptional(action.oldObjectIdentifier)
                                    if (opt.isPresent && opt.get() is ChestBlock) {
                                        chestPositions.getOrPut(actionWorld) { mutableSetOf() }.add(action.pos)
                                    }
                                }
                                actionIdsBatch.add(action.id)
                                if (actionIdsBatch.size >= updateBatchSize) {
                                    flushBatch()
                                }
                            }

                            actionsSinceYield++
                            if (actionsSinceYield >= actionsPerTick) {
                                delay(1.ticks)
                                actionsSinceYield = 0
                            }
                        }

                        flushBatch()
                        updateJobs.joinAll()

                        processed += actions.size
                        cursorId = actions.last().id
                        val now = System.currentTimeMillis()
                        if (now - lastProgressLogMs >= 30_000L) {
                            lastProgressLogMs = now
                            val elapsedMs = (now - startedAtMs).coerceAtLeast(1L)
                            val aps = (processed * 1000L) / elapsedMs
                            logInfo(
                                "rollback_sla stage=progress source=${source.textName} processed=$processed total=$totalActions " +
                                    "elapsedMs=$elapsedMs actionsPerSec=$aps",
                            )
                        }
                        if (processed % (selectBatchSize * 2L) == 0L) {
                            source.sendSuccess(
                                {
                                    Component.translatable(
                                        "text.vialogium.rollback.finish",
                                        processed,
                                    ).setStyle(TextColorPallet.secondary)
                                },
                                true,
                            )
                        }
                    }

                    // Second pass: reconnect double chests that were restored as single because their
                    // neighbor hadn't been rolled back yet during the first pass.
                    if (chestPositions.isNotEmpty()) {
                        RollbackExecutionGuard.runWithoutLogging {
                            val server = context.source.server
                            for ((worldId, positions) in chestPositions) {
                                val world = server.getWorld(worldId) ?: continue
                                for (pos in positions) {
                                    val state = world.getBlockState(pos)
                                    if (state.block !is ChestBlock) continue
                                    val facing: Direction = state.getValue(ChestBlock.FACING)
                                    for (dir in listOf(facing.clockWise, facing.counterClockWise)) {
                                        val neighborPos = pos.relative(dir)
                                        val neighborState = world.getBlockState(neighborPos)
                                        if (neighborState.block is ChestBlock && neighborState.getValue(
                                                ChestBlock.FACING,
                                            ) == facing
                                        ) {
                                            val (thisType, neighborType) = if (dir == facing.clockWise) {
                                                ChestType.LEFT to ChestType.RIGHT
                                            } else {
                                                ChestType.RIGHT to ChestType.LEFT
                                            }
                                            world.setBlock(pos, state.setValue(ChestBlock.TYPE, thisType), 3)
                                            world.setBlock(
                                                neighborPos,
                                                neighborState.setValue(ChestBlock.TYPE, neighborType),
                                                3,
                                            )
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        val totalChests = chestPositions.values.sumOf { it.size }
                        logInfo("rollback_sla stage=chest-fix chestPositions=$totalChests")
                    }

                    for (entry in fails.entries) {
                        source.sendSuccess(
                            {
                                Component.translatable("text.vialogium.rollback.fail", entry.key, entry.value).setStyle(
                                    TextColorPallet.secondary,
                                )
                            },
                            true,
                        )
                    }

                    val totalFailed = fails.values.sum()
                    val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
                    val actionsPerSec = (processed * 1000L) / durationMs
                    logInfo(
                        "rollback_sla stage=done source=${source.textName} processed=$processed total=$totalActions " +
                            "failed=$totalFailed durationMs=$durationMs actionsPerSec=$actionsPerSec",
                    )

                    source.sendSuccess(
                        {
                            Component.translatable(
                                "text.vialogium.rollback.finish",
                                processed,
                            ).setStyle(TextColorPallet.primary)
                        },
                        true,
                    )
                },
            )
        }.invokeOnCompletion { lock.releaseUnlessHandedOff() }
        return 1
    }
}
