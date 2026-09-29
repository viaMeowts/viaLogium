package com.viameowts.vialogium.database.databases

import com.viameowts.vialogium.utility.ServerIdentity
import net.minecraft.resources.Identifier
import java.nio.file.Path
import javax.sql.DataSource

object PostgreSQL : ViaLogiumDatabase {
    override fun getDataSource(savePath: Path): DataSource = hikariDataSource(
        "jdbc:postgresql://",
        "org.postgresql.Driver",
        mapOf(
            "reWriteBatchedInserts" to "true",
            // Shows which backend a connection belongs to in pg_stat_activity.
            "ApplicationName" to "viaLogium-${ServerIdentity.id}",
            "tcpKeepAlive" to "true",
        ),
    )

    override fun getDatabaseIdentifier() = Identifier.fromNamespaceAndPath("vialogium", "postgresql")
}
