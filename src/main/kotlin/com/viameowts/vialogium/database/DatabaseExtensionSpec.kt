package com.viameowts.vialogium.database

import com.uchuhimo.konf.ConfigSpec
import java.util.concurrent.TimeUnit

@Suppress("MagicNumber")
object DatabaseExtensionSpec : ConfigSpec("database_extensions") {
    val database by optional(Databases.SQLITE)
    val userName by optional("root", "username")
    val password by optional("", "password")
    val url by optional("localhost", "url")
    val properties by optional(mapOf<String, String>(), "properties")
    val maxPoolSize by optional(10, "maxPoolSize")
    val connectionTimeout by optional(10000L, "connectionTimeout")
    val maxLifetime by optional(TimeUnit.MINUTES.toMillis(30), "maxLifetime")
}
