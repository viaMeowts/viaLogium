package com.viameowts.vialogium.commands.parameters

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.concurrent.CompletableFuture

/**
 * Parses an exact block position written as `x,y,z` (integers, comma-separated), used for a
 * point/single-block rollback or search (e.g. `at:100,64,-258`).
 */
class PositionParameter : SimpleParameter<BlockPos>() {
    override fun parse(stringReader: StringReader): BlockPos {
        val start = stringReader.cursor
        while (stringReader.canRead() && stringReader.peek() != ' ') {
            stringReader.skip()
        }
        val arg = stringReader.string.substring(start, stringReader.cursor)
        val parts = arg.split(",")
        if (parts.size != 3) {
            throw SimpleCommandExceptionType(LiteralMessage("Expected coordinates as x,y,z")).create()
        }
        return try {
            BlockPos(parts[0].trim().toInt(), parts[1].trim().toInt(), parts[2].trim().toInt())
        } catch (e: NumberFormatException) {
            throw SimpleCommandExceptionType(
                LiteralMessage("Invalid coordinates '$arg' (expected integers x,y,z)"),
            ).create()
        }
    }

    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        if (builder.remaining.isEmpty()) {
            // Prefer the block the player is looking at; fall back to the source's position.
            val pos = lookedAtBlock(context.source) ?: BlockPos.containing(context.source.position)
            builder.suggest("${pos.x},${pos.y},${pos.z}")
        }
        return builder.buildFuture()
    }

    private fun lookedAtBlock(source: CommandSourceStack): BlockPos? {
        val player = source.player ?: return null
        val hit = player.pick(RAYCAST_DISTANCE, 1.0f, false)
        return if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) hit.blockPos else null
    }

    private companion object {
        const val RAYCAST_DISTANCE = 100.0
    }
}
