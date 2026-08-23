package com.viameowts.vialogium.integration.viapanel

import com.uchuhimo.konf.source.toml
import com.uchuhimo.konf.source.toml.toToml
import com.viameowts.vialogium.config.CONFIG_PATH
import com.viameowts.vialogium.config.ColorSpec
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.config.NetworkingSpec
import com.viameowts.vialogium.config.SearchSpec
import com.viameowts.vialogium.config.config
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.time.ZoneId

object ViaLogiumPanelConfig {
    @JvmField var pageSize: Int = config[SearchSpec.pageSize]

    @JvmField var purgePermissionLevel: Int = config[SearchSpec.purgePermissionLevel]

    @JvmField var maxRange: Int = config[SearchSpec.maxRange]

    @JvmField var nearRadius: Int = config[SearchSpec.nearRadius]

    @JvmField var timeZone: String = config[SearchSpec.timeZone].id

    @JvmField var networking: Boolean = config[NetworkingSpec.networking]

    @JvmField var queueTimeoutMin: Long = config[DatabaseSpec.queueTimeoutMin]

    @JvmField var queueCheckDelaySec: Long = config[DatabaseSpec.queueCheckDelaySec]

    @JvmField var autoPurgeDays: Int = config[DatabaseSpec.autoPurgeDays]

    @JvmField var batchSize: Int = config[DatabaseSpec.batchSize]

    @JvmField var batchDelay: Int = config[DatabaseSpec.batchDelay]

    @JvmField var criticalQueueSize: Int = config[DatabaseSpec.criticalQueueSize]

    @JvmField var criticalBatchSize: Int = config[DatabaseSpec.criticalBatchSize]

    @JvmField var criticalBatchDelay: Int = config[DatabaseSpec.criticalBatchDelay]

    @JvmField var emergencyQueueSize: Int = config[DatabaseSpec.emergencyQueueSize]

    @JvmField var emergencyBatchSize: Int = config[DatabaseSpec.emergencyBatchSize]

    @JvmField var emergencyBatchDelay: Int = config[DatabaseSpec.emergencyBatchDelay]

    @JvmField var adaptiveQueueTuning: Boolean = config[DatabaseSpec.adaptiveQueueTuning]

    @JvmField var dropNonEssentialInCritical: Boolean = config[DatabaseSpec.dropNonEssentialInCritical]

    @JvmField var criticalExplosionKeepEvery: Int = config[DatabaseSpec.criticalExplosionKeepEvery]

    @JvmField var emergencyExplosionKeepEvery: Int = config[DatabaseSpec.emergencyExplosionKeepEvery]

    @JvmField var emergencyDropNonPlayerBlockActions: Boolean = config[DatabaseSpec.emergencyDropNonPlayerBlockActions]

    @JvmField var sqliteUseWal: Boolean = config[DatabaseSpec.sqliteUseWal]

    @JvmField var sqliteSynchronousNormal: Boolean = config[DatabaseSpec.sqliteSynchronousNormal]

    @JvmField var sqliteTempStoreMemory: Boolean = config[DatabaseSpec.sqliteTempStoreMemory]

    @JvmField var sqliteCacheSizeKb: Int = config[DatabaseSpec.sqliteCacheSizeKb]

    @JvmField var sqliteMmapSizeMb: Int = config[DatabaseSpec.sqliteMmapSizeMb]

    @JvmField var sqliteBusyTimeoutMs: Int = config[DatabaseSpec.sqliteBusyTimeoutMs]

    @JvmField var networkMaxPages: Int = config[DatabaseSpec.networkMaxPages]

    @JvmField var rollbackActionsPerTick: Int = config[DatabaseSpec.rollbackActionsPerTick]

    @JvmField var previewActionsPerTick: Int = config[DatabaseSpec.previewActionsPerTick]

    @JvmField var primary: String = config[ColorSpec.primary]

    @JvmField var primaryVariant: String = config[ColorSpec.primaryVariant]

    @JvmField var secondary: String = config[ColorSpec.secondary]

    @JvmField var secondaryVariant: String = config[ColorSpec.secondaryVariant]

    @JvmField var light: String = config[ColorSpec.light]

    @JvmField var actionPositive: String = config[ColorSpec.actionPositive]

    @JvmField var actionNegative: String = config[ColorSpec.actionNegative]

    private fun syncFromConfig() {
        pageSize = config[SearchSpec.pageSize]
        purgePermissionLevel = config[SearchSpec.purgePermissionLevel]
        maxRange = config[SearchSpec.maxRange]
        nearRadius = config[SearchSpec.nearRadius]
        timeZone = config[SearchSpec.timeZone].id

        networking = config[NetworkingSpec.networking]

        queueTimeoutMin = config[DatabaseSpec.queueTimeoutMin]
        queueCheckDelaySec = config[DatabaseSpec.queueCheckDelaySec]
        autoPurgeDays = config[DatabaseSpec.autoPurgeDays]
        batchSize = config[DatabaseSpec.batchSize]
        batchDelay = config[DatabaseSpec.batchDelay]
        criticalQueueSize = config[DatabaseSpec.criticalQueueSize]
        criticalBatchSize = config[DatabaseSpec.criticalBatchSize]
        criticalBatchDelay = config[DatabaseSpec.criticalBatchDelay]
        emergencyQueueSize = config[DatabaseSpec.emergencyQueueSize]
        emergencyBatchSize = config[DatabaseSpec.emergencyBatchSize]
        emergencyBatchDelay = config[DatabaseSpec.emergencyBatchDelay]
        adaptiveQueueTuning = config[DatabaseSpec.adaptiveQueueTuning]
        dropNonEssentialInCritical = config[DatabaseSpec.dropNonEssentialInCritical]
        criticalExplosionKeepEvery = config[DatabaseSpec.criticalExplosionKeepEvery]
        emergencyExplosionKeepEvery = config[DatabaseSpec.emergencyExplosionKeepEvery]
        emergencyDropNonPlayerBlockActions = config[DatabaseSpec.emergencyDropNonPlayerBlockActions]
        sqliteUseWal = config[DatabaseSpec.sqliteUseWal]
        sqliteSynchronousNormal = config[DatabaseSpec.sqliteSynchronousNormal]
        sqliteTempStoreMemory = config[DatabaseSpec.sqliteTempStoreMemory]
        sqliteCacheSizeKb = config[DatabaseSpec.sqliteCacheSizeKb]
        sqliteMmapSizeMb = config[DatabaseSpec.sqliteMmapSizeMb]
        sqliteBusyTimeoutMs = config[DatabaseSpec.sqliteBusyTimeoutMs]
        networkMaxPages = config[DatabaseSpec.networkMaxPages]
        rollbackActionsPerTick = config[DatabaseSpec.rollbackActionsPerTick]
        previewActionsPerTick = config[DatabaseSpec.previewActionsPerTick]

        primary = config[ColorSpec.primary]
        primaryVariant = config[ColorSpec.primaryVariant]
        secondary = config[ColorSpec.secondary]
        secondaryVariant = config[ColorSpec.secondaryVariant]
        light = config[ColorSpec.light]
        actionPositive = config[ColorSpec.actionPositive]
        actionNegative = config[ColorSpec.actionNegative]
    }

    private fun applyToRuntimeConfig() {
        config[SearchSpec.pageSize] = pageSize
        config[SearchSpec.purgePermissionLevel] = purgePermissionLevel
        config[SearchSpec.maxRange] = maxRange
        config[SearchSpec.nearRadius] = nearRadius
        config[SearchSpec.timeZone] = ZoneId.of(timeZone)

        config[NetworkingSpec.networking] = networking

        config[DatabaseSpec.queueTimeoutMin] = queueTimeoutMin
        config[DatabaseSpec.queueCheckDelaySec] = queueCheckDelaySec
        config[DatabaseSpec.autoPurgeDays] = autoPurgeDays
        config[DatabaseSpec.batchSize] = batchSize
        config[DatabaseSpec.batchDelay] = batchDelay
        config[DatabaseSpec.criticalQueueSize] = criticalQueueSize
        config[DatabaseSpec.criticalBatchSize] = criticalBatchSize
        config[DatabaseSpec.criticalBatchDelay] = criticalBatchDelay
        config[DatabaseSpec.emergencyQueueSize] = emergencyQueueSize
        config[DatabaseSpec.emergencyBatchSize] = emergencyBatchSize
        config[DatabaseSpec.emergencyBatchDelay] = emergencyBatchDelay
        config[DatabaseSpec.adaptiveQueueTuning] = adaptiveQueueTuning
        config[DatabaseSpec.dropNonEssentialInCritical] = dropNonEssentialInCritical
        config[DatabaseSpec.criticalExplosionKeepEvery] = criticalExplosionKeepEvery
        config[DatabaseSpec.emergencyExplosionKeepEvery] = emergencyExplosionKeepEvery
        config[DatabaseSpec.emergencyDropNonPlayerBlockActions] = emergencyDropNonPlayerBlockActions
        config[DatabaseSpec.sqliteUseWal] = sqliteUseWal
        config[DatabaseSpec.sqliteSynchronousNormal] = sqliteSynchronousNormal
        config[DatabaseSpec.sqliteTempStoreMemory] = sqliteTempStoreMemory
        config[DatabaseSpec.sqliteCacheSizeKb] = sqliteCacheSizeKb
        config[DatabaseSpec.sqliteMmapSizeMb] = sqliteMmapSizeMb
        config[DatabaseSpec.sqliteBusyTimeoutMs] = sqliteBusyTimeoutMs
        config[DatabaseSpec.networkMaxPages] = networkMaxPages
        config[DatabaseSpec.rollbackActionsPerTick] = rollbackActionsPerTick
        config[DatabaseSpec.previewActionsPerTick] = previewActionsPerTick

        config[ColorSpec.primary] = primary
        config[ColorSpec.primaryVariant] = primaryVariant
        config[ColorSpec.secondary] = secondary
        config[ColorSpec.secondaryVariant] = secondaryVariant
        config[ColorSpec.light] = light
        config[ColorSpec.actionPositive] = actionPositive
        config[ColorSpec.actionNegative] = actionNegative
    }

    fun reload() {
        val configFile = FabricLoader.getInstance().configDir.resolve(CONFIG_PATH).toFile()
        ensureConfigFileExists(configFile)
        config.from.toml.file(configFile)
        syncFromConfig()
    }

    private fun ensureConfigFileExists(configFile: File) {
        if (configFile.exists()) return

        configFile.parentFile?.mkdirs()
        ViaLogiumPanelConfig::class.java.classLoader.getResourceAsStream(CONFIG_PATH)?.use { input ->
            configFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    @JvmStatic
    fun save() {
        applyToRuntimeConfig()
        val configFile = FabricLoader.getInstance().configDir.resolve(CONFIG_PATH).toFile()
        config.toToml.toFile(configFile)
    }
}
