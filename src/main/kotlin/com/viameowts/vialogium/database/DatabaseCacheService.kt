package com.viameowts.vialogium.database

import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.Maps
import net.minecraft.resources.Identifier
import java.util.UUID

// Mutated on the single database thread, but some entries (e.g. sourceKeys via getKnownSources for
// command suggestions) are also read from the main server thread. HashBiMap is not thread-safe, so
// each is wrapped in a synchronized view. The wrapper's monitor is the map itself, so any caller
// that iterates a view must hold `synchronized(map) { ... }` (see DatabaseManager.getKnownSources).
object DatabaseCacheService {
    val actionIdentifierKeys: BiMap<String, Int> = Maps.synchronizedBiMap(HashBiMap.create())

    val worldIdentifierKeys: BiMap<Identifier, Int> = Maps.synchronizedBiMap(HashBiMap.create())

    val objectIdentifierKeys: BiMap<Identifier, Int> = Maps.synchronizedBiMap(HashBiMap.create())

    val sourceKeys: BiMap<String, Int> = Maps.synchronizedBiMap(HashBiMap.create())

    val playerKeys: BiMap<UUID, Int> = Maps.synchronizedBiMap(HashBiMap.create())

    val playernameKeys: BiMap<String, Int> = Maps.synchronizedBiMap(HashBiMap.create())
}
