package com.viameowts.vialogium.actions

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

class BlockBreakActionType : BlockChangeActionType() {
    override val identifier = "block-break"

    // Hover shows the broken block's id plus, for signs, the text it had when broken.
    override fun getObjectMessage(source: CommandSourceStack): Component =
        blockNameWithHover(oldObjectIdentifier, buildSignTextHover(source.server))
}
