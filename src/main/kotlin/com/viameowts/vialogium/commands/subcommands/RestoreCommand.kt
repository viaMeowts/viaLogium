package com.viameowts.vialogium.commands.subcommands

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionSearchParams
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
import com.viameowts.vialogium.utility.launchMain
import com.viameowts.vialogium.utility.literal
import com.viameowts.vialogium.utility.ticks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object RestoreCommand : BuildableCommand {
    override fun build(): LiteralNode = Commands.literal("restore")
        .requires(Permissions.require("vialogium.commands.rollback", CommandConsts.PERMISSION_LEVEL))
        .then(
            SearchParamArgument.argument("params")
                .executes { restore(it, SearchParamArgument.get(it, "params")) },
        )
        .build()

    fun restore(context: Context, params: ActionSearchParams): Int {
        val source = context.source
        params.ensureSpecific()
        ViaLogium.launch {
            MessageUtils.warnBusy(source)
            val totalActions = DatabaseManager.countRestoreActions(params)

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
                        "text.vialogium.restore.start",
                        totalActions.toString().literal().setStyle(TextColorPallet.secondary),
                    ).setStyle(TextColorPallet.primary)
                },
                true,
            )

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

                logInfo(
                    "restore_sla stage=start source=${source.textName} total=$totalActions " +
                        "selectBatch=$selectBatchSize updateBatch=$updateBatchSize actionsPerTick=$actionsPerTick",
                )

                while (true) {
                    val actions = DatabaseManager.selectRestorePreviewBatch(params, cursorId, selectBatchSize)
                    if (actions.isEmpty()) break

                    val actionIdsBatch = HashSet<Int>(updateBatchSize)
                    val updateJobs = mutableListOf<Job>()

                    fun flushBatch() {
                        if (actionIdsBatch.isEmpty()) return

                        val ids = actionIdsBatch.toSet()
                        actionIdsBatch.clear()
                        updateJobs += ViaLogium.launch {
                            DatabaseManager.restoreActions(ids)
                        }
                    }

                    for ((index, action) in actions.withIndex()) {
                        if (!RollbackExecutionGuard.runWithoutLogging { action.restore(context.source.server) }) {
                            fails[action.identifier] = fails.getOrPut(action.identifier) { 0 } + 1
                        } else {
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
                            "restore_sla stage=progress source=${source.textName} processed=$processed total=$totalActions " +
                                "elapsedMs=$elapsedMs actionsPerSec=$aps",
                        )
                    }
                    if (processed % (selectBatchSize * 2L) == 0L) {
                        source.sendSuccess(
                            {
                                Component.translatable(
                                    "text.vialogium.restore.finish",
                                    processed,
                                ).setStyle(TextColorPallet.secondary)
                            },
                            true,
                        )
                    }
                }

                for (entry in fails.entries) {
                    source.sendSuccess(
                        {
                            Component.translatable("text.vialogium.restore.fail", entry.key, entry.value).setStyle(
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
                    "restore_sla stage=done source=${source.textName} processed=$processed total=$totalActions " +
                        "failed=$totalFailed durationMs=$durationMs actionsPerSec=$actionsPerSec",
                )

                source.sendSuccess(
                    {
                        Component.translatable(
                            "text.vialogium.restore.finish",
                            processed,
                        ).setStyle(TextColorPallet.primary)
                    },
                    true,
                )
            }
        }
        return 1
    }
}
