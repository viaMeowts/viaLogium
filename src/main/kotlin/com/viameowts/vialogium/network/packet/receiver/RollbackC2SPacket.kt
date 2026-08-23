package com.viameowts.vialogium.network.packet.receiver

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.RollbackBlockTracker
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.commands.arguments.SearchParamArgument
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.database.RollbackExecutionGuard
import com.viameowts.vialogium.logInfo
import com.viameowts.vialogium.network.packet.ViaLogiumPacketTypes
import com.viameowts.vialogium.network.packet.response.ResponseCodes
import com.viameowts.vialogium.network.packet.response.ResponseContent
import com.viameowts.vialogium.network.packet.response.ResponseS2CPacket
import com.viameowts.vialogium.utility.launchMain
import com.viameowts.vialogium.utility.ticks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class RollbackC2SPacket(val input: String) : CustomPacketPayload {

    override fun type() = ID

    companion object : ServerPlayNetworking.PlayPayloadHandler<RollbackC2SPacket> {
        private const val MAX_INPUT_LENGTH = 4096

        val ID: CustomPacketPayload.Type<RollbackC2SPacket> = CustomPacketPayload.Type(ViaLogiumPacketTypes.ROLLBACK.id)
        val CODEC: StreamCodec<FriendlyByteBuf, RollbackC2SPacket> = CustomPacketPayload.codec({ packet, buf ->
            buf.writeUtf(packet.input, MAX_INPUT_LENGTH)
        }, {
            RollbackC2SPacket(it.readUtf(MAX_INPUT_LENGTH))
        })

        override fun receive(payload: RollbackC2SPacket, context: ServerPlayNetworking.Context) {
            val player = context.player()
            val sender = context.responseSender()
            if (!Permissions.check(player, "vialogium.networking", CommandConsts.PERMISSION_LEVEL) ||
                !Permissions.check(player, "vialogium.commands.rollback", CommandConsts.PERMISSION_LEVEL)
            ) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(ViaLogiumPacketTypes.ROLLBACK.id, ResponseCodes.NO_PERMISSION.code),
                    sender,
                )
                return
            }

            val params = runCatching {
                SearchParamArgument.get(payload.input, player.createCommandSourceStack()).also { it.ensureSpecific() }
            }.getOrElse {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(ViaLogiumPacketTypes.ROLLBACK.id, ResponseCodes.ERROR.code),
                    sender,
                )
                return
            }

            ResponseS2CPacket.sendResponse(
                ResponseContent(ViaLogiumPacketTypes.ROLLBACK.id, ResponseCodes.EXECUTING.code),
                sender,
            )

            ViaLogium.launch {
                val totalActions = DatabaseManager.countRollbackActions(params)
                if (totalActions == 0L) {
                    ResponseS2CPacket.sendResponse(
                        ResponseContent(ViaLogiumPacketTypes.ROLLBACK.id, ResponseCodes.COMPLETED.code),
                        sender,
                    )
                    return@launch
                }

                // Pre-scan container block-break positions (off the main thread) so item-drop/pickup
                // of their spilled contents are skipped during rollback.
                val containerBreakPositions = DatabaseManager.selectContainerBreakPositions(params)

                player.level().launchMain {
                    val selectBatchSize = ViaLogium.config[DatabaseSpec.emergencyBatchSize].coerceAtLeast(500)
                    val updateBatchSize = ViaLogium.config[DatabaseSpec.batchSize].coerceAtLeast(250)
                    val actionsPerTick = ViaLogium.config[DatabaseSpec.rollbackActionsPerTick].coerceAtLeast(1)
                    val server = player.level().server
                    var processed = 0L
                    var actionsSinceYield = 0
                    var cursorId: Int? = null
                    val startedAtMs = System.currentTimeMillis()
                    var lastProgressLogMs = startedAtMs
                    // Lives for the whole run: actions are global id DESC across batches, so a
                    // block-break can precede the older block-place / item actions it supersedes.
                    val blockTracker = RollbackBlockTracker(containerBreakPositions)

                    logInfo(
                        "rollback_sla stage=start source=${player.name.string} mode=network total=$totalActions " +
                            "selectBatch=$selectBatchSize updateBatch=$updateBatchSize actionsPerTick=$actionsPerTick",
                    )

                    while (true) {
                        val actions = DatabaseManager.selectRollbackPreviewBatch(params, cursorId, selectBatchSize)
                        if (actions.isEmpty()) break

                        val successfulIds = HashSet<Int>(updateBatchSize)
                        val updateJobs = mutableListOf<Job>()

                        fun flushBatch() {
                            if (successfulIds.isEmpty()) return
                            val ids = successfulIds.toSet()
                            successfulIds.clear()
                            updateJobs += ViaLogium.launch {
                                DatabaseManager.rollbackActions(ids)
                            }
                        }

                        for (action in actions) {
                            if (blockTracker.shouldSkip(action)) {
                                // Already restored (with full state and contents) by a block-break that
                                // rolled back first. Skip execution, still mark rolled_back.
                                successfulIds.add(action.id)
                                if (successfulIds.size >= updateBatchSize) {
                                    flushBatch()
                                }
                            } else if (RollbackExecutionGuard.runWithoutLogging { action.rollback(server) }) {
                                blockTracker.recordRolledBack(action)
                                successfulIds.add(action.id)
                                if (successfulIds.size >= updateBatchSize) {
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
                                "rollback_sla stage=progress source=${player.name.string} mode=network processed=$processed " +
                                    "total=$totalActions elapsedMs=$elapsedMs actionsPerSec=$aps",
                            )
                        }
                        if (processed >= totalActions) break
                    }

                    val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
                    val actionsPerSec = (processed * 1000L) / durationMs
                    logInfo(
                        "rollback_sla stage=done source=${player.name.string} mode=network processed=$processed total=$totalActions " +
                            "durationMs=$durationMs actionsPerSec=$actionsPerSec",
                    )

                    ResponseS2CPacket.sendResponse(
                        ResponseContent(ViaLogiumPacketTypes.ROLLBACK.id, ResponseCodes.COMPLETED.code),
                        sender,
                    )
                }
            }
        }
    }
}
