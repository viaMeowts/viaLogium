package com.viameowts.vialogium.config

import com.uchuhimo.konf.ConfigSpec

object ColorSpec : ConfigSpec() {
    val primary by required<String>()
    val primaryVariant by required<String>()
    val secondary by required<String>()
    val secondaryVariant by required<String>()
    val light by required<String>()
    val actionPositive by optional<String>("#0BDA51")
    val actionNegative by optional<String>("#FF2C2C")
}
