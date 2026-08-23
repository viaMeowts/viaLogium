package com.viameowts.vialogium.utility

import com.viameowts.vialogium.database.Tables
import net.minecraft.network.chat.Component
import java.time.Instant
import java.util.*

data class PlayerResult(val uuid: UUID, val name: String, val firstJoin: Instant, val lastJoin: Instant) {

    fun toText(): Component = Component.translatable(
        "text.vialogium.player.result",
        name.literal().setStyle(TextColorPallet.light),
        MessageUtils.instantToText(firstJoin).setStyle(TextColorPallet.primaryVariant),
        MessageUtils.instantToText(lastJoin).setStyle(TextColorPallet.primaryVariant),
    ).setStyle(TextColorPallet.secondary)

    companion object {
        fun fromRow(row: Tables.Player): PlayerResult = PlayerResult(
            row.playerId,
            row.playerName,
            row.firstJoin,
            row.lastJoin,
        )
    }
}
