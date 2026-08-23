package com.viameowts.vialogium.api

import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.config.getDatabasePath
import com.viameowts.vialogium.logError
import net.minecraft.server.MinecraftServer
import javax.sql.DataSource

object ExtensionManager {
    private val _extensions = mutableListOf<ViaLogiumExtension>()
    val extensions: List<ViaLogiumExtension>
        get() = _extensions

    private var dataSource: DataSource? = null

    val commands = mutableListOf<CommandExtension>()

    fun registerExtension(extension: ViaLogiumExtension) {
        _extensions.add(extension)

        if (extension is CommandExtension) {
            commands.add(extension)
        }
        extension.getConfigSpecs().forEach {
            config.addSpec(it)
        }
    }

    internal fun serverStarting(server: MinecraftServer) {
        extensions.forEach {
            if (it is DatabaseExtension) {
                if (dataSource == null) {
                    dataSource = it.getDataSource(config.getDatabasePath())
                } else {
                    failExtensionRegistration(it)
                }
            }
        }
    }

    private fun failExtensionRegistration(extension: ViaLogiumExtension) {
        logError("Unable to load extension ${extension.getIdentifier()}")
    }

    fun getDataSource() = dataSource
}
