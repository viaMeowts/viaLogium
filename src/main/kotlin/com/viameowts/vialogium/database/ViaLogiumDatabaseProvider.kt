package com.viameowts.vialogium.database

import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.config.getDatabasePath
import javax.sql.DataSource

object ViaLogiumDatabaseProvider {
    fun getDataSource(): DataSource = config[DatabaseExtensionSpec.database].database.getDataSource(
        config.getDatabasePath(),
    )
}
