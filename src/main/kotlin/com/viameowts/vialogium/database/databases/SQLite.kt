package com.viameowts.vialogium.database.databases

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.config.DatabaseSpec
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import kotlin.io.path.pathString
import com.viameowts.vialogium.config.config as appConfig

object SQLite : ViaLogiumDatabase {
    override fun getDataSource(savePath: Path) = SQLiteDataSource().apply {
        val baseUrl = "jdbc:sqlite:${savePath.resolve("vialogium.sqlite").pathString}"
        val params = mutableListOf<String>()

        if (appConfig[DatabaseSpec.sqliteUseWal]) {
            params += "journal_mode=WAL"
        }

        params += "synchronous=${if (appConfig[DatabaseSpec.sqliteSynchronousNormal]) "NORMAL" else "FULL"}"

        if (appConfig[DatabaseSpec.sqliteTempStoreMemory]) {
            params += "temp_store=MEMORY"
        }

        val cacheSizeKb = appConfig[DatabaseSpec.sqliteCacheSizeKb].coerceAtLeast(0)
        if (cacheSizeKb > 0) {
            params += "cache_size=-$cacheSizeKb"
        }

        val mmapBytes = appConfig[DatabaseSpec.sqliteMmapSizeMb].coerceAtLeast(0).toLong() * 1024L * 1024L
        if (mmapBytes > 0L) {
            params += "mmap_size=$mmapBytes"
        }

        val busyTimeoutMs = appConfig[DatabaseSpec.sqliteBusyTimeoutMs].coerceAtLeast(0)
        if (busyTimeoutMs > 0) {
            params += "busy_timeout=$busyTimeoutMs"
        }

        url = if (params.isEmpty()) {
            baseUrl
        } else {
            "$baseUrl?${params.joinToString("&")}"
        }
    }

    override fun getDatabaseIdentifier() = ViaLogium.identifier(ViaLogium.DEFAULT_DATABASE)
}
