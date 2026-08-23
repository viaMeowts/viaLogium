package com.viameowts.vialogium.database.databases

import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.database.DatabaseExtensionSpec
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.minecraft.resources.Identifier
import java.nio.file.Path
import javax.sql.DataSource

object PostgreSQL : ViaLogiumDatabase {
    override fun getDataSource(savePath: Path): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://${config[DatabaseExtensionSpec.url]}"
            username = config[DatabaseExtensionSpec.userName]
            password = config[DatabaseExtensionSpec.password]
            maximumPoolSize = config[DatabaseExtensionSpec.maxPoolSize]
            maxLifetime = config[DatabaseExtensionSpec.maxLifetime]
            addDataSourceProperty("reWriteBatchedInserts", "true")
            for ((key, value) in config[DatabaseExtensionSpec.properties]) {
                addDataSourceProperty(key, value)
            }
        },
    )

    override fun getDatabaseIdentifier() = Identifier.fromNamespaceAndPath("vialogium", "postgresql")
}
