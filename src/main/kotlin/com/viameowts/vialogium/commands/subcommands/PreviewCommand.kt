package com.viameowts.vialogium.commands.subcommands

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.actionutils.MIN_SELECT_BATCH_SIZE
import com.viameowts.vialogium.actionutils.PROGRESS_LOG_INTERVAL_MS
import com.viameowts.vialogium.actionutils.Preview
import com.viameowts.vialogium.actionutils.actionsPerSecond
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.commands.arguments.SearchParamArgument
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.logInfo
import com.viameowts.vialogium.utility.Context
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.McDispatcher
import com.viameowts.vialogium.utility.McExecutor
import com.viameowts.vialogium.utility.MessageUtils
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.ticks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

object PreviewCommand : BuildableCommand {
    override fun build(): LiteralNode = Commands.literal("preview")
        .requires(Permissions.require("vialogium.commands.preview", CommandConsts.PERMISSION_LEVEL))
        .then(
            Commands.literal("rollback")
                .then(
                    SearchParamArgument.argument(CommandConsts.PARAMS)
                        .executes {
                            preview(
                                it,
                                SearchParamArgument.get(it, CommandConsts.PARAMS),
                                Preview.Type.ROLLBACK,
                            )
                        },
                ),
        )
        .then(
            Commands.literal("restore")
                .then(
                    SearchParamArgument.argument(CommandConsts.PARAMS)
                        .executes {
                            preview(
                                it,
                                SearchParamArgument.get(it, CommandConsts.PARAMS),
                                Preview.Type.RESTORE,
                            )
                        },
                ),
        )
        .then(Commands.literal("apply").executes { apply(it) })
        .then(Commands.literal("cancel").executes { cancel(it) })
        .build()

    private fun preview(context: Context, params: ActionSearchParams, type: Preview.Type): Int {
        val source = context.source
        val player = source.playerOrException
        params.ensureSpecific()
        ViaLogium.launch {
            MessageUtils.warnBusy(source)
            val totalActions = when (type) {
                Preview.Type.ROLLBACK -> DatabaseManager.countRollbackActions(params)
                Preview.Type.RESTORE -> DatabaseManager.countRestoreActions(params)
            }

            if (totalActions == 0L) {
                source.sendFailure(
                    Component.translatable(
                        "error.vialogium.command.no_results",
                    ).setStyle(TextColorPallet.actionNegative),
                )
                return@launch
            }

            ViaLogium.previewCache[player.uuid]?.cancel(player)

            val preview = Preview(params, totalActions, player, type)
            val mainThread = McDispatcher + McExecutor(player.level().server::execute)
            val selectBatchSize = ViaLogium.config[DatabaseSpec.emergencyBatchSize].coerceAtLeast(MIN_SELECT_BATCH_SIZE)
            val actionsPerTick = ViaLogium.config[DatabaseSpec.previewActionsPerTick].coerceAtLeast(1)
            var actionsSinceYield = 0
            var processed = 0L
            val startedAtMs = System.currentTimeMillis()
            var lastProgressLogMs = startedAtMs

            logInfo(
                "preview_sla stage=start player=${player.name.string} type=${type.name.lowercase()} " +
                    "total=$totalActions selectBatch=$selectBatchSize actionsPerTick=$actionsPerTick",
            )

            when (type) {
                Preview.Type.ROLLBACK -> {
                    var cursorId: Int? = null
                    while (true) {
                        val batch = DatabaseManager.selectRollbackPreviewBatch(params, cursorId, selectBatchSize)
                        if (batch.isEmpty()) break

                        // Previews read block entities and entity trackers: do it on the server thread.
                        withContext(mainThread) { preview.addActions(batch, player) }
                        processed += batch.size
                        cursorId = batch.last().id

                        actionsSinceYield += batch.size
                        if (actionsSinceYield >= actionsPerTick) {
                            delay(1.ticks)
                            actionsSinceYield = 0
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastProgressLogMs >= PROGRESS_LOG_INTERVAL_MS) {
                            lastProgressLogMs = now
                            val elapsedMs = (now - startedAtMs).coerceAtLeast(1L)
                            val aps = actionsPerSecond(processed, elapsedMs)
                            logInfo(
                                "preview_sla stage=progress player=${player.name.string} " +
                                    "type=${type.name.lowercase()} processed=$processed total=$totalActions " +
                                    "elapsedMs=$elapsedMs actionsPerSec=$aps",
                            )
                        }
                    }
                }

                Preview.Type.RESTORE -> {
                    var cursorId: Int? = null
                    while (true) {
                        val batch = DatabaseManager.selectRestorePreviewBatch(params, cursorId, selectBatchSize)
                        if (batch.isEmpty()) break

                        // Previews read block entities and entity trackers: do it on the server thread.
                        withContext(mainThread) { preview.addActions(batch, player) }
                        processed += batch.size
                        cursorId = batch.last().id

                        actionsSinceYield += batch.size
                        if (actionsSinceYield >= actionsPerTick) {
                            delay(1.ticks)
                            actionsSinceYield = 0
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastProgressLogMs >= PROGRESS_LOG_INTERVAL_MS) {
                            lastProgressLogMs = now
                            val elapsedMs = (now - startedAtMs).coerceAtLeast(1L)
                            val aps = actionsPerSecond(processed, elapsedMs)
                            logInfo(
                                "preview_sla stage=progress player=${player.name.string} " +
                                    "type=${type.name.lowercase()} processed=$processed total=$totalActions " +
                                    "elapsedMs=$elapsedMs actionsPerSec=$aps",
                            )
                        }
                    }
                }
            }

            val durationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
            val actionsPerSec = actionsPerSecond(processed, durationMs)
            logInfo(
                "preview_sla stage=done player=${player.name.string} type=${type.name.lowercase()} " +
                    "processed=$processed total=$totalActions durationMs=$durationMs actionsPerSec=$actionsPerSec",
            )

            ViaLogium.previewCache[player.uuid] = preview
        }
        return 1
    }

    private fun apply(context: Context): Int {
        val uuid = context.source.playerOrException.uuid

        if (ViaLogium.previewCache.containsKey(uuid)) {
            ViaLogium.previewCache[uuid]?.apply(context)
            ViaLogium.previewCache.remove(uuid)
        } else {
            context.source.sendFailure(
                Component.translatable("error.vialogium.no_preview").setStyle(TextColorPallet.actionNegative),
            )
            return -1
        }

        return 1
    }

    private fun cancel(context: Context): Int {
        val uuid = context.source.playerOrException.uuid

        if (ViaLogium.previewCache.containsKey(uuid)) {
            ViaLogium.previewCache[uuid]?.cancel(context.source.playerOrException)
            ViaLogium.previewCache.remove(uuid)
        } else {
            context.source.sendFailure(
                Component.translatable("error.vialogium.no_preview").setStyle(TextColorPallet.actionNegative),
            )
            return -1
        }

        return 1
    }
}
