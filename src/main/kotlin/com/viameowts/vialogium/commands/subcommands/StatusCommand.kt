package com.viameowts.vialogium.commands.subcommands

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.database.ActionQueueService
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.utility.Context
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.literal
import com.viameowts.vialogium.utility.translate
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.SemanticVersion
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import java.net.URI

object StatusCommand : BuildableCommand {
    override fun build(): LiteralNode = Commands.literal("status")
        .requires(Permissions.require("vialogium.commands.status", CommandConsts.PERMISSION_LEVEL))
        .executes { status(it) }
        .build()

    private fun status(context: Context): Int {
        ViaLogium.launch {
            val source = context.source
            val queueSize = ActionQueueService.size
            val healthy = ActionQueueService.healthy
            val autoPurgeDays = ViaLogium.config[DatabaseSpec.autoPurgeDays]
            val autoPurgeText = if (autoPurgeDays > 0) "${autoPurgeDays}d" else "disabled"

            source.sendSystemMessage(
                Component.translatable("text.vialogium.header.status")
                    .setStyle(TextColorPallet.primary),
            )
            source.sendSystemMessage(
                Component.translatable(
                    "text.vialogium.status.queue",
                    queueSize.toString().literal()
                        .setStyle(TextColorPallet.secondaryVariant),
                ).setStyle(TextColorPallet.secondary),
            )
            source.sendSystemMessage(
                Component.translatable(
                    "text.vialogium.status.logging",
                    (
                        if (healthy) {
                            "OK"
                        } else {
                            "DEGRADED - DB writes failing, retrying"
                        }
                        ).literal()
                        .setStyle(
                            if (healthy) TextColorPallet.actionPositive else TextColorPallet.actionNegative,
                        ),
                ).setStyle(TextColorPallet.secondary),
            )
            source.sendSystemMessage(
                Component.translatable(
                    "text.vialogium.status.version",
                    getVersion().friendlyString.literal()
                        .setStyle(TextColorPallet.secondaryVariant),
                ).setStyle(TextColorPallet.secondary),
            )
            // Queue and health are sent first: while the database is down this query waits for it.
            val (count, estimated) = DatabaseManager.estimateAllActions()
            val totalRecords = if (estimated) "~$count" else count.toString()
            source.sendSystemMessage(
                Component.translatable(
                    "text.vialogium.status.db_type",
                    "${DatabaseManager.databaseType} | records=$totalRecords | autoPurge=$autoPurgeText".literal()
                        .setStyle(TextColorPallet.secondaryVariant),
                ).setStyle(TextColorPallet.secondary),
            )
            source.sendSystemMessage(
                Component.translatable(
                    "text.vialogium.status.discord",
                    "text.vialogium.status.discord.join".translate()
                        .setStyle(TextColorPallet.secondaryVariant)
                        .withStyle {
                            it.withClickEvent(
                                ClickEvent.OpenUrl(
                                    URI("https://github.com/viaMeowts/viaLogium"),
                                ),
                            )
                        },
                ).setStyle(TextColorPallet.secondary),
            )
            source.sendSystemMessage(
                Component.translatable(
                    "text.vialogium.status.wiki",
                    "text.vialogium.status.wiki.view".translate()
                        .setStyle(TextColorPallet.secondaryVariant)
                        .withStyle {
                            it.withClickEvent(
                                ClickEvent.OpenUrl(
                                    URI("https://github.com/viaMeowts/viaLogium"),
                                ),
                            )
                        },
                ).setStyle(TextColorPallet.secondary),
            )
        }

        return 1
    }

    private fun getVersion() = SemanticVersion.parse(
        FabricLoader.getInstance().getModContainer(ViaLogium.MOD_ID).get().metadata.version.friendlyString,
    )
}
