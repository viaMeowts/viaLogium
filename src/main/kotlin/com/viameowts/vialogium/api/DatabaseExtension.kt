package com.viameowts.vialogium.api

import java.nio.file.Path
import javax.sql.DataSource

interface DatabaseExtension : ViaLogiumExtension {
    fun getDataSource(savePath: Path): DataSource
}
