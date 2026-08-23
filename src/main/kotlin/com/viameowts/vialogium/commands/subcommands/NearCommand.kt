package com.viameowts.vialogium.commands.subcommands

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.config.SearchSpec
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.utility.Context
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.MessageUtils
import com.viameowts.vialogium.utility.Negatable
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.literal
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.structure.BoundingBox

object NearCommand : BuildableCommand {
    override fun build(): LiteralNode = literal("near")
        .requires(Permissions.require("vialogium.commands.near", CommandConsts.PERMISSION_LEVEL))
        .executes { near(it) }
        .build()

    private fun near(context: Context): Int {
        val source = context.source
        val player = source.playerOrException
        val radius = ViaLogium.config[SearchSpec.nearRadius]
        val pos = player.blockPosition()

        val params = ActionSearchParams.build {
            bounds = BoundingBox.fromCorners(
                pos.offset(-radius, -radius, -radius),
                pos.offset(radius, radius, radius),
            )
            worlds = mutableSetOf(Negatable.allow(player.level().dimension().identifier()))
        }

        ViaLogium.launch {
            ViaLogium.searchCache[source.textName] = params

            MessageUtils.warnBusy(source)
            val results = DatabaseManager.searchActions(params, 1)

            if (results.actions.isEmpty()) {
                source.sendFailure(
                    Component.translatable(
                        "error.vialogium.command.no_results",
                    ).setStyle(TextColorPallet.actionNegative),
                )
                return@launch
            }

            MessageUtils.sendSearchResults(
                source,
                results,
                Component.translatable("text.vialogium.header.search").setStyle(TextColorPallet.primary)
                    .append(" (r=$radius)".literal().setStyle(TextColorPallet.secondary)),
            )
        }

        return 1
    }
}
