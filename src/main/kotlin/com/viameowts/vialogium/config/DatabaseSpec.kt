package com.viameowts.vialogium.config

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.viameowts.vialogium.ViaLogium
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path

@Suppress("MagicNumber")
object DatabaseSpec : ConfigSpec() {
    val queueTimeoutMin by required<Long>()
    val queueCheckDelaySec by required<Long>()
    val autoPurgeDays by required<Int>()
    val batchSize by optional<Int>(1000)
    val batchDelay by optional<Int>(10)
    val maxQueueSize by optional<Int>(750_000)
    val criticalQueueSize by optional<Int>(150_000)
    val criticalBatchSize by optional<Int>(10_000)
    val criticalBatchDelay by optional<Int>(1)
    val adaptiveQueueTuning by optional<Boolean>(true)
    val dropNonEssentialInCritical by optional<Boolean>(true)
    val criticalKeepSources by optional<List<String>>(
        listOf("player", "command", "hopper", "hopper_minecart", "copper_golem"),
    )
    val emergencyQueueSize by optional<Int>(450_000)
    val emergencyBatchSize by optional<Int>(25_000)
    val emergencyBatchDelay by optional<Int>(0)
    val criticalExplosionKeepEvery by optional<Int>(20)
    val emergencyExplosionKeepEvery by optional<Int>(100)
    val emergencyDropNonPlayerBlockActions by optional<Boolean>(true)
    val sqliteUseWal by optional<Boolean>(true)
    val sqliteSynchronousNormal by optional<Boolean>(true)
    val sqliteTempStoreMemory by optional<Boolean>(true)
    val sqliteCacheSizeKb by optional<Int>(32_000)
    val sqliteMmapSizeMb by optional<Int>(256)
    val sqliteBusyTimeoutMs by optional<Int>(5_000)
    val networkMaxPages by optional<Int>(50)
    val rollbackActionsPerTick by optional<Int>(2_000)
    val previewActionsPerTick by optional<Int>(3_000)
    val logSQL by optional<Boolean>(false)
    val location by optional<String?>(null)
}

fun Config.getDatabasePath(): Path {
    val location = config[DatabaseSpec.location]
    return if (location != null) {
        Path.of(location)
    } else {
        ViaLogium.server.getWorldPath(LevelResource.ROOT)
    }
}
