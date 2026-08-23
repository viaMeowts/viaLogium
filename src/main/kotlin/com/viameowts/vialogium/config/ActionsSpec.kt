package com.viameowts.vialogium.config

import com.uchuhimo.konf.ConfigSpec
import net.minecraft.resources.Identifier

object ActionsSpec : ConfigSpec() {
    val typeBlacklist by required<List<String>>()
    val worldBlacklist by required<List<Identifier>>()
    val objectBlacklist by required<List<Identifier>>()
    val sourceBlacklist by required<List<String>>()
    val logPistons by optional(false)
}
