package com.viameowts.vialogium.database.databases

import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.database.DatabaseExtensionSpec
import com.viameowts.vialogium.utility.ServerIdentity
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val MIN_IDLE = 2
private const val VALIDATION_TIMEOUT_MS = 5_000L
private const val KEEPALIVE_MINUTES = 5L
private val KEEPALIVE_MS = TimeUnit.MINUTES.toMillis(KEEPALIVE_MINUTES)

/**
 * Pool shared by the MySQL, MariaDB and PostgreSQL backends. Driver properties go through
 * [driverProperties] first so the user's `properties` in the config can override every default.
 */
internal fun hikariDataSource(
    jdbcPrefix: String,
    driverClass: String,
    driverProperties: Map<String, Any> = emptyMap(),
): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        // Named explicitly: DriverManager's lookup depends on the thread's class loader, which
        // under Fabric is not always the one the bundled drivers were loaded by.
        driverClassName = driverClass
        poolName = "viaLogium-${ServerIdentity.id}"
        jdbcUrl = jdbcPrefix + config[DatabaseExtensionSpec.url].trim().removePrefix(jdbcPrefix)
        username = resolveSecret(config[DatabaseExtensionSpec.userName])
        password = resolveSecret(config[DatabaseExtensionSpec.password])
        maximumPoolSize = config[DatabaseExtensionSpec.maxPoolSize].coerceAtLeast(2)
        minimumIdle = MIN_IDLE.coerceAtMost(maximumPoolSize)
        connectionTimeout = config[DatabaseExtensionSpec.connectionTimeout]
        validationTimeout = VALIDATION_TIMEOUT_MS
        maxLifetime = config[DatabaseExtensionSpec.maxLifetime]
        // Keeps idle connections alive through firewalls/NAT that silently drop quiet TCP sessions.
        keepaliveTime = KEEPALIVE_MS.coerceAtMost(maxLifetime - 1)
        for ((key, value) in driverProperties) addDataSourceProperty(key, value)
        for ((key, value) in config[DatabaseExtensionSpec.properties]) addDataSourceProperty(key, value)
    },
)

/**
 * Lets the password (or user name) stay out of `vialogium.toml`, which is often kept in git:
 * `env:NAME` reads an environment variable, `file:/path` reads the first line of a file.
 */
internal fun resolveSecret(value: String): String = when {
    value.startsWith("env:") -> {
        val name = value.removePrefix("env:")
        System.getenv(name) ?: error("viaLogium: environment variable $name is not set")
    }

    value.startsWith("file:") -> Files.readAllLines(Path.of(value.removePrefix("file:"))).firstOrNull()?.trim()
        ?: error("viaLogium: ${value.removePrefix("file:")} is empty")

    else -> value
}
