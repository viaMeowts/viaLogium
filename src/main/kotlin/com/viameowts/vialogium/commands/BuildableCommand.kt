package com.viameowts.vialogium.commands

import com.viameowts.vialogium.utility.LiteralNode

interface BuildableCommand {
    fun build(): LiteralNode
}
