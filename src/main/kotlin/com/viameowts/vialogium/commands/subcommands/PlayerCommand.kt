package com.viameowts.vialogium.commands.subcommands

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.MessageUtils
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.server.players.NameAndId

object PlayerCommand : BuildableCommand {
    override fun build(): LiteralNode {
        return literal("player")
            .requires(Permissions.require("vialogium.commands.player", CommandConsts.PERMISSION_LEVEL))
            .then(
                argument("player", GameProfileArgument.gameProfile())
                    .executes {
                        return@executes lookupPlayer(GameProfileArgument.getGameProfiles(it, "player"), it.source)
                    },
            )
            .build()
    }

    private fun lookupPlayer(profiles: MutableCollection<NameAndId>, source: CommandSourceStack): Int {
        ViaLogium.launch {
            val players = DatabaseManager.searchPlayers(profiles.toSet())
            MessageUtils.sendPlayerMessage(source, players)
        }

        return 1
    }
}
