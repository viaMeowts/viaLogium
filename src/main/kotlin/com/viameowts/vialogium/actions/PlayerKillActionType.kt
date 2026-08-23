package com.viameowts.vialogium.actions

import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.literal
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

class PlayerKillActionType : EntityKillActionType() {
    override val identifier: String = "player-kill"

    override fun getObjectMessage(source: CommandSourceStack): Component {
        if (!oldObjectState.isNullOrBlank()) {
            return oldObjectState!!.literal().setStyle(TextColorPallet.secondaryVariant)
        }

        return super.getObjectMessage(source)
    }
}
