package com.viameowts.vialogium.actions

import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.literal
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.util.Util

class VillagerTradeActionType : AbstractActionType() {
    override val identifier: String = "villager-trade"

    override fun getTranslationType(): String = "entity"

    override fun getObjectMessage(source: CommandSourceStack): Component {
        val base = Component.translatable(
            Util.makeDescriptionId(getTranslationType(), objectIdentifier),
        ).setStyle(TextColorPallet.secondaryVariant)

        if (extraData.isNullOrBlank()) {
            return base
        }

        return base.withStyle {
            it.withHoverEvent(
                HoverEvent.ShowText(
                    extraData!!.literal(),
                ),
            )
        }
    }
}
