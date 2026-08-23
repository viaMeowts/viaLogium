package com.viameowts.vialogium.database.databases

import net.minecraft.resources.Identifier
import java.nio.file.Path
import javax.sql.DataSource

interface ViaLogiumDatabase {
    fun getDataSource(savePath: Path): DataSource
    fun getDatabaseIdentifier(): Identifier
}
