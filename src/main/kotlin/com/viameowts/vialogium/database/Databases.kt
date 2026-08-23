package com.viameowts.vialogium.database

import com.viameowts.vialogium.database.databases.H2Database
import com.viameowts.vialogium.database.databases.MariaDB
import com.viameowts.vialogium.database.databases.MySQL
import com.viameowts.vialogium.database.databases.PostgreSQL
import com.viameowts.vialogium.database.databases.SQLite
import com.viameowts.vialogium.database.databases.ViaLogiumDatabase

enum class Databases(val database: ViaLogiumDatabase) {
    MYSQL(MySQL),
    H2(H2Database),
    POSTGRESQL(PostgreSQL),
    SQLITE(SQLite),
    MARIADB(MariaDB),
}
