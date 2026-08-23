package com.viameowts.vialogium.commands.subcommands

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.commands.arguments.SearchParamArgument
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.utility.Context
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.MessageUtils
import com.viameowts.vialogium.utility.TextColorPallet
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component

object SearchCommand : BuildableCommand {
    override fun build(): LiteralNode = literal("search")
        .requires(Permissions.require("vialogium.commands.search", CommandConsts.PERMISSION_LEVEL))
        .then(
            SearchParamArgument.argument("params")
                .executes { search(it, SearchParamArgument.get(it, "params")) },
        )
        .build()

    private fun search(context: Context, params: ActionSearchParams): Int {
        val source = context.source

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
                Component.translatable(
                    "text.vialogium.header.search",
                ).setStyle(TextColorPallet.primary),
            )
        }

        return 1
    }
}
