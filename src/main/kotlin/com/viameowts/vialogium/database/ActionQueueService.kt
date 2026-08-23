package com.viameowts.vialogium.database

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.logError
import com.viameowts.vialogium.logInfo
import com.viameowts.vialogium.logWarn
import com.viameowts.vialogium.utility.Sources
import com.viameowts.vialogium.utility.ticks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ActionQueueService {
    enum class LoggingMode {
        NORMAL,
        CRITICAL,
        EMERGENCY,
    }

    private const val MAX_BATCH_RETRIES = 5
    private const val RETRY_DELAY_MS = 5000L

    private val queue = LinkedBlockingQueue<ActionType>()
    private lateinit var job: Job
    private val droppedActions = AtomicLong(0)
    private val explosionSampleCounter = AtomicLong(0)
    private val dbHealthy = AtomicBoolean(true)
    private var loggingMode = LoggingMode.NORMAL

    /** Batch held in memory after a failed DB write, retried before draining new actions. */
    private var retryBatch: List<ActionType>? = null
    private var retryAttempts = 0

    val size: Int get() = queue.size
    val dropped: Long get() = droppedActions.get()
    val isCritical: Boolean get() = loggingMode != LoggingMode.NORMAL
    val mode: LoggingMode get() = loggingMode

    /** False while DB writes are failing and a batch is being retried. */
    val healthy: Boolean get() = dbHealthy.get()

    fun start() {
        job = ViaLogium.launch {
            prepareNextBatch()
        }
    }

    fun addToQueue(action: ActionType): Boolean {
        if (RollbackExecutionGuard.isActive()) return false
        if (action.isBlacklisted()) return false

        val queueSize = queue.size
        val maxQueueSize = ViaLogium.config[DatabaseSpec.maxQueueSize].coerceAtLeast(1)

        updateLoggingMode(queueSize)

        if (queueSize >= maxQueueSize) {
            val droppedNow = droppedActions.incrementAndGet()
            if (droppedNow % 5000L == 1L) {
                logWarn("Dropping actions: queue hard limit reached ($queueSize/$maxQueueSize), dropped=$droppedNow")
            }
            return false
        }

        if (shouldDropByCurrentMode(action, queueSize)) {
            val droppedNow = droppedActions.incrementAndGet()
            if (droppedNow % 5000L == 1L) {
                logWarn("$loggingMode drop active: queue=$queueSize, dropped=$droppedNow")
            }
            return false
        }

        return queue.offer(action)
    }

    suspend fun drainAll() {
        job.cancel()
        while (queue.isNotEmpty() || retryBatch != null) {
            try {
                drainBatch(ViaLogium.config[DatabaseSpec.batchSize].coerceAtLeast(1))
            } catch (t: Throwable) {
                logError("Shutdown drain: batch write failed, ${retryBatch?.size ?: 0} actions may be lost", t)
                retryBatch = null
                retryAttempts = 0
            }
        }
    }

    private suspend fun drainBatch(batchSize: Int) {
        val pending = retryBatch
        val batch = if (pending != null) {
            pending
        } else {
            mutableListOf<ActionType>().also { queue.drainTo(it, batchSize) }
        }

        if (batch.isEmpty()) return

        try {
            DatabaseManager.logActionBatch(batch)
            if (!dbHealthy.getAndSet(true)) {
                logInfo("Database writes recovered; logging is healthy again (dropped total=${droppedActions.get()})")
            }
            retryBatch = null
            retryAttempts = 0
        } catch (t: Throwable) {
            // Hold the batch in memory and retry with a bounded budget so transient
            // DB outages never kill the flush loop and never lose data silently.
            dbHealthy.set(false)
            retryBatch = batch
            retryAttempts++
            if (retryAttempts >= MAX_BATCH_RETRIES) {
                logError(
                    "Dropping ${batch.size} actions after $MAX_BATCH_RETRIES failed DB attempts " +
                        "(last error: ${t.message}); identifiers: ${batch.take(10).map { it.identifier }}",
                    t,
                )
                droppedActions.addAndGet(batch.size.toLong())
                retryBatch = null
                retryAttempts = 0
            } else {
                logWarn(
                    "DB write failed (attempt $retryAttempts/$MAX_BATCH_RETRIES): ${t.message}; " +
                        "${batch.size} actions held for retry",
                    t,
                )
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun prepareNextBatch() {
        job = ViaLogium.launch {
            while (true) {
                val queueSize = queue.size
                updateLoggingMode(queueSize)

                val batchSize = getBatchSize(queueSize)
                val batchDelay = getBatchDelay(queueSize)

                if (queue.size < batchSize && batchDelay > 0) {
                    delay(batchDelay.ticks)
                }

                if (queue.isNotEmpty()) {
                    drainBatch(batchSize)
                } else {
                    // Idle: ensure at least one suspension point so an empty queue (especially with
                    // batchDelay == 0) cannot turn this loop into a 100% CPU busy-spin.
                    delay(1.ticks)
                }
            }
        }
    }

    private fun getBatchSize(queueSize: Int): Int {
        val normalBatch = ViaLogium.config[DatabaseSpec.batchSize].coerceAtLeast(1)
        val criticalBatch = ViaLogium.config[DatabaseSpec.criticalBatchSize].coerceAtLeast(normalBatch)
        val emergencyBatch = ViaLogium.config[DatabaseSpec.emergencyBatchSize].coerceAtLeast(criticalBatch)

        if (!ViaLogium.config[DatabaseSpec.adaptiveQueueTuning]) {
            return when (loggingMode) {
                LoggingMode.NORMAL -> normalBatch
                LoggingMode.CRITICAL -> criticalBatch
                LoggingMode.EMERGENCY -> emergencyBatch
            }
        }

        val maxQueueSize = ViaLogium.config[DatabaseSpec.maxQueueSize].coerceAtLeast(1)
        val clamped = queueSize.coerceIn(0, maxQueueSize)
        val ratio = clamped.toDouble() / maxQueueSize.toDouble()

        return (normalBatch + ((emergencyBatch - normalBatch) * ratio)).toInt().coerceIn(normalBatch, emergencyBatch)
    }

    private fun getBatchDelay(queueSize: Int): Int {
        val normalDelay = ViaLogium.config[DatabaseSpec.batchDelay].coerceAtLeast(0)
        val criticalDelay = ViaLogium.config[DatabaseSpec.criticalBatchDelay].coerceAtLeast(0)
        val emergencyDelay = ViaLogium.config[DatabaseSpec.emergencyBatchDelay].coerceAtLeast(0)

        if (!ViaLogium.config[DatabaseSpec.adaptiveQueueTuning]) {
            return when (loggingMode) {
                LoggingMode.NORMAL -> normalDelay
                LoggingMode.CRITICAL -> criticalDelay
                LoggingMode.EMERGENCY -> emergencyDelay
            }
        }

        val maxQueueSize = ViaLogium.config[DatabaseSpec.maxQueueSize].coerceAtLeast(1)
        val clamped = queueSize.coerceIn(0, maxQueueSize)
        val ratio = clamped.toDouble() / maxQueueSize.toDouble()

        return (normalDelay - ((normalDelay - emergencyDelay) * ratio)).toInt().coerceIn(emergencyDelay, normalDelay)
    }

    private fun shouldDropByCurrentMode(action: ActionType, queueSize: Int): Boolean = when (loggingMode) {
        LoggingMode.NORMAL -> false

        LoggingMode.CRITICAL -> {
            shouldDropInCritical(action, queueSize) || shouldSampleExplosion(
                action,
                ViaLogium.config[DatabaseSpec.criticalExplosionKeepEvery].coerceAtLeast(1),
            )
        }

        LoggingMode.EMERGENCY -> {
            shouldDropInEmergency(action, queueSize) || shouldSampleExplosion(
                action,
                ViaLogium.config[DatabaseSpec.emergencyExplosionKeepEvery].coerceAtLeast(1),
            )
        }
    }

    private fun shouldDropInCritical(action: ActionType, queueSize: Int): Boolean {
        val criticalQueueSize = ViaLogium.config[DatabaseSpec.criticalQueueSize].coerceAtLeast(1)
        if (queueSize < criticalQueueSize) return false
        if (!ViaLogium.config[DatabaseSpec.dropNonEssentialInCritical]) return false
        if (action.sourceProfile != null) return false

        return !ViaLogium.config[DatabaseSpec.criticalKeepSources].contains(action.sourceName)
    }

    private fun shouldDropInEmergency(action: ActionType, queueSize: Int): Boolean {
        val emergencyQueueSize = ViaLogium.config[DatabaseSpec.emergencyQueueSize].coerceAtLeast(1)
        if (queueSize < emergencyQueueSize) return false

        if (ViaLogium.config[DatabaseSpec.emergencyDropNonPlayerBlockActions] && isBlockAction(action)) {
            val isTrustedDirectAction = action.sourceName == Sources.PLAYER || action.sourceName == Sources.COMMAND
            if (!isTrustedDirectAction) {
                return true
            }
        }

        return shouldDropInCritical(action, queueSize)
    }

    private fun shouldSampleExplosion(action: ActionType, keepEvery: Int): Boolean {
        if (keepEvery <= 1) return false
        if (!isExplosionBlockAction(action)) return false

        val seen = explosionSampleCounter.incrementAndGet()
        return seen % keepEvery.toLong() != 0L
    }

    private fun isExplosionBlockAction(action: ActionType): Boolean =
        action.sourceName == Sources.EXPLOSION && isBlockAction(action)

    private fun isBlockAction(action: ActionType): Boolean = action.identifier.startsWith("block-")

    private fun updateLoggingMode(queueSize: Int) {
        val criticalQueueSize = ViaLogium.config[DatabaseSpec.criticalQueueSize].coerceAtLeast(1)
        val emergencyQueueSize = ViaLogium.config[DatabaseSpec.emergencyQueueSize].coerceAtLeast(criticalQueueSize + 1)
        val next = when {
            queueSize >= emergencyQueueSize -> LoggingMode.EMERGENCY
            queueSize >= criticalQueueSize -> LoggingMode.CRITICAL
            else -> LoggingMode.NORMAL
        }
        if (next == loggingMode) return

        val previous = loggingMode
        loggingMode = next
        when (loggingMode) {
            LoggingMode.NORMAL -> logInfo(
                "Queue mode switched: $previous -> NORMAL (queue=$queueSize, dropped=${droppedActions.get()})",
            )

            LoggingMode.CRITICAL -> logWarn(
                "Queue mode switched: $previous -> CRITICAL (queue=$queueSize, dropped=${droppedActions.get()})",
            )

            LoggingMode.EMERGENCY -> logWarn(
                "Queue mode switched: $previous -> EMERGENCY (queue=$queueSize, dropped=${droppedActions.get()})",
            )
        }
    }
}
