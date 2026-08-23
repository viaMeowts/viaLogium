package com.viameowts.vialogium.api

import com.viameowts.vialogium.commands.BuildableCommand

interface CommandExtension : ViaLogiumExtension {
    fun registerSubcommands(): List<BuildableCommand>
}
