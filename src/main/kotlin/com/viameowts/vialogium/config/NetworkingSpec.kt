package com.viameowts.vialogium.config

import com.uchuhimo.konf.ConfigSpec

object NetworkingSpec : ConfigSpec() {
    val networking by required<Boolean>()
}
