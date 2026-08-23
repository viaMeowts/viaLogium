package com.viameowts.vialogium.commands.subcommands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.utility.Context
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.MessageUtils
import com.viameowts.vialogium.utility.TextColorPallet
import kotlinx.coroutines.launch
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component

object PageCommand : BuildableCommand {
    override fun build(): LiteralNode = literal("page")
        .then(
            argument("page", IntegerArgumentType.integer(1))
                .executes { page(it, IntegerArgumentType.getInteger(it, "page")) },
        )
        .build()

    private fun page(context: Context, page: Int): Int {
        val source = context.source

        val params = ViaLogium.searchCache[source.textName]
        if (params != null) {
            ViaLogium.launch {
                MessageUtils.warnBusy(source)
                val results = DatabaseManager.searchActions(params, page)

                if (results.page > results.pages) {
                    source.sendFailure(
                        Component.translatable(
                            "error.vialogium.no_more_pages",
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
        } else {
            source.sendFailure(
                Component.translatable("error.vialogium.no_cached_params").setStyle(TextColorPallet.actionNegative),
            )
            return -1
        }
    }
}
