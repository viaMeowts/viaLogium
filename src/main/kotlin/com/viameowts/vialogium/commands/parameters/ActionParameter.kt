package com.viameowts.vialogium.commands.parameters

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.viameowts.vialogium.registry.ActionRegistry
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import java.util.concurrent.CompletableFuture

class ActionParameter : SimpleParameter<String>() {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val types = ActionRegistry.getTypes()
        // Need to check equality to catch null
        return SharedSuggestionProvider.suggest(
            types,
            builder,
        )
    }

    override fun parse(stringReader: StringReader): String = StringArgumentType.word().parse(stringReader)
}
