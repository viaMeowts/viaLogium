package com.viameowts.vialogium.database.databases

import net.minecraft.resources.Identifier
import java.nio.file.Path
import javax.sql.DataSource

object MariaDB : ViaLogiumDatabase {
    override fun getDataSource(savePath: Path): DataSource = hikariDataSource(
        "jdbc:mariadb://",
        "org.mariadb.jdbc.Driver",
        mapOf(
            // Connector/J 3 batches through bulk statements; rewriteBatchedStatements is a MySQL
            // driver option. The rest is the set used before 1.2.0 (stored timestamps rely on it).
            "useBulkStmts" to "true",
            "rewriteBatchedStatements" to "true",
            "cachePrepStmts" to "true",
            "prepStmtCacheSize" to "250",
            "prepStmtCacheSqlLimit" to "2048",
            "useServerPrepStmts" to "true",
            "cacheCallableStmts" to "true",
            "cacheResultSetMetadata" to "true",
            "cacheServerConfiguration" to "true",
            "useLocalSessionState" to "true",
            "elideSetAutoCommits" to "true",
            "alwaysSendSetIsolation" to "false",
            "useJDBCCompliantTimezoneShift" to "true",
            "useLegacyDatetimeCode" to "false",
            "serverTimezone" to "UTC",
        ),
    )

    override fun getDatabaseIdentifier() = Identifier.fromNamespaceAndPath("vialogium", "mariadb")
}
