package com.viameowts.vialogium.database.databases

import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.database.DatabaseExtensionSpec
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.minecraft.resources.Identifier
import java.nio.file.Path
import javax.sql.DataSource

object MySQL : ViaLogiumDatabase {
    override fun getDataSource(savePath: Path): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://${config[DatabaseExtensionSpec.url]}"
            username = config[DatabaseExtensionSpec.userName]
            password = config[DatabaseExtensionSpec.password]
            maximumPoolSize = config[DatabaseExtensionSpec.maxPoolSize]
            connectionTimeout = config[DatabaseExtensionSpec.connectionTimeout]
            maxLifetime = config[DatabaseExtensionSpec.maxLifetime]
            addDataSourceProperty("rewriteBatchedStatements", "true")
            addDataSourceProperty("cachePrepStmts", true)
            addDataSourceProperty("prepStmtCacheSize", 250)
            addDataSourceProperty("prepStmtCacheSqlLimit", 2048)
            addDataSourceProperty("useServerPrepStmts", true)
            addDataSourceProperty("cacheCallableStmts", true)
            addDataSourceProperty("cacheResultSetMetadata", true)
            addDataSourceProperty("cacheServerConfiguration", true)
            addDataSourceProperty("useLocalSessionState", true)
            addDataSourceProperty("elideSetAutoCommits", true)
            addDataSourceProperty("alwaysSendSetIsolation", false)
            addDataSourceProperty("useJDBCCompliantTimezoneShift", true)
            addDataSourceProperty("useLegacyDatetimeCode", false)
            addDataSourceProperty("serverTimezone", "UTC")
            for ((key, value) in config[DatabaseExtensionSpec.properties]) {
                addDataSourceProperty(key, value)
            }
        },
    )

    override fun getDatabaseIdentifier() = Identifier.fromNamespaceAndPath("vialogium", "mysql")
}
