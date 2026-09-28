package com.viameowts.vialogium.commands.parameters

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.viameowts.vialogium.utility.ServerIdentity
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import java.util.concurrent.CompletableFuture

/** `server:<id>` / `server:all` for databases shared by several servers. */
class ServerParameter : SimpleParameter<String>() {
    override fun parse(stringReader: StringReader): String {
        val i: Int = stringReader.cursor

        while (stringReader.canRead() && stringReader.peek() != ' ') {
            stringReader.skip()
        }

        return stringReader.string.substring(i, stringReader.cursor).lowercase()
    }

    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> = SharedSuggestionProvider.suggest(
        ServerIdentity.known + ServerIdentity.id + ServerIdentity.ALL,
        builder,
    )
}
