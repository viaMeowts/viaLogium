package com.viameowts.vialogium.database.databases

import net.minecraft.resources.Identifier
import org.h2.jdbcx.JdbcDataSource
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.io.path.pathString

object H2Database : ViaLogiumDatabase {
    override fun getDataSource(savePath: Path): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:${savePath.resolve("vialogium.h2").pathString};MODE=MySQL")
    }

    override fun getDatabaseIdentifier() = Identifier.fromNamespaceAndPath("vialogium", "h2")
}
