package com.viameowts.vialogium.utility

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.viapanel.api.ViaPanelApi

private const val MAX_SERVER_ID_LENGTH = 32

/**
 * Which backend this is when several servers share one database. Taken from
 * `database.server-id` or, when that is empty, from viaPanel's `server_id`.
 */
object ServerIdentity {
    /** Search value that matches every server. */
    const val ALL = "all"

    val id: String by lazy {
        val configured = ViaLogium.config[DatabaseSpec.serverId].trim()
        val raw = configured.ifEmpty { ViaPanelApi.getServerId() }
        raw.lowercase().replace(Regex("[^a-z0-9_.-]"), "_").take(MAX_SERVER_ID_LENGTH).ifEmpty { "server" }
    }

    /** Server ids seen in search results, for `server:` suggestions. */
    val known: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun isLocal(server: String) = server.isEmpty() || server == id
}
