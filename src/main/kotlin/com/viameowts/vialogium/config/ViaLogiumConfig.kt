package com.viameowts.vialogium.config

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.toml
import com.viameowts.vialogium.config.util.IdentifierMixin
import com.viameowts.vialogium.database.DatabaseExtensionSpec
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier

const val CONFIG_PATH = "vialogium.toml"

val config: Config = Config {
    addSpec(DatabaseSpec)
    addSpec(SearchSpec)
    addSpec(ActionsSpec)
    addSpec(ColorSpec)
    addSpec(NetworkingSpec)
    addSpec(DatabaseExtensionSpec)
}
    .apply { this.mapper.addMixIn(Identifier::class.java, IdentifierMixin::class.java) }
    .from.toml.resource(CONFIG_PATH)
    .from.toml.watchFile(FabricLoader.getInstance().configDir.resolve(CONFIG_PATH).toFile())
    .from.env()
    .from.systemProperties()
