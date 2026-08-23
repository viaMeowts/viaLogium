package com.viameowts.vialogium.actions

import com.viameowts.vialogium.utility.TextColorPallet
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

class PlayerLeaveActionType : AbstractActionType() {
    override val identifier: String = "player-leave"

    override fun getTranslationType() = "entity"

    override fun rollback(server: MinecraftServer): Boolean = false

    override fun restore(server: MinecraftServer): Boolean = false

    override fun getObjectMessage(source: CommandSourceStack): Component =
        Component.translatable("text.vialogium.action.object.server").setStyle(TextColorPallet.secondaryVariant)
}
