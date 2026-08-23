package com.viameowts.vialogium.utility

import com.mojang.serialization.DataResult
import com.viameowts.vialogium.config.ColorSpec
import com.viameowts.vialogium.config.config
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

@Suppress("MagicNumber")
object TextColorPallet {
    val primary: Style
        get() = Style.EMPTY.withColor(TextColor.parseColor(config[ColorSpec.primary]).getOrNull())

    val primaryVariant: Style
        get() = Style.EMPTY.withColor(
            TextColor.parseColor(config[ColorSpec.primaryVariant]).getOrNull(),
        )
    val secondary: Style get() = Style.EMPTY.withColor(TextColor.parseColor(config[ColorSpec.secondary]).getOrNull())
    val secondaryVariant: Style
        get() = Style.EMPTY.withColor(
            TextColor.parseColor(config[ColorSpec.secondaryVariant]).getOrNull(),
        )
    val light: Style get() = Style.EMPTY.withColor(TextColor.parseColor(config[ColorSpec.light]).getOrNull())
    val actionPositive: Style
        get() = Style.EMPTY.withColor(TextColor.parseColor(config[ColorSpec.actionPositive]).getOrNull())
    val actionNegative: Style
        get() = Style.EMPTY.withColor(TextColor.parseColor(config[ColorSpec.actionNegative]).getOrNull())
}

fun DataResult<TextColor>.getOrNull(): TextColor? = this.result().orElse(null)
