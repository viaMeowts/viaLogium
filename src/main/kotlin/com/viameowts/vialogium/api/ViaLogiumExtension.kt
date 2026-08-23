package com.viameowts.vialogium.api

import com.uchuhimo.konf.ConfigSpec
import net.minecraft.resources.Identifier

interface ViaLogiumExtension {
    fun getIdentifier(): Identifier

    // All extension configs should be entirely optional as the default config will not have fallback
    fun getConfigSpecs(): List<ConfigSpec>
}
