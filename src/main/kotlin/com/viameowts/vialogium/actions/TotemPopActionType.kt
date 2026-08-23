package com.viameowts.vialogium.actions

import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.literal
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

class TotemPopActionType : AbstractActionType() {
    override val identifier = "totem-pop"

    override fun getTranslationType() = "item"

    override fun rollback(server: MinecraftServer): Boolean = false

    override fun restore(server: MinecraftServer): Boolean = false

    override fun getObjectMessage(source: CommandSourceStack): Component {
        if (!oldObjectState.isNullOrBlank()) {
            return oldObjectState!!.literal().setStyle(TextColorPallet.secondaryVariant)
        }

        return Component.translatable("item.minecraft.totem_of_undying")
    }
}
