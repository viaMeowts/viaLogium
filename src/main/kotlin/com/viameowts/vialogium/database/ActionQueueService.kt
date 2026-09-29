package com.viameowts.vialogium.database

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.logError
import com.viameowts.vialogium.logInfo
import com.viameowts.vialogium.logWarn
import com.viameowts.vialogium.utility.Sources
import com.viameowts.vialogium.utility.ticks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.sql.SQLDataException
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ActionQueueService {
    enum class LoggingMode {
        NORMAL,
        CRITICAL,
        EMERGENCY,
    }

    private const val MIN_RETRY_DELAY_MS = 1_000L
    private const val MAX_RETRY_DELAY_MS = 30_000L
    private const val MAX_BACKOFF_DOUBLINGS = 5
    private const val MAX_SHUTDOWN_DRAIN_FAILURES = 3

    /** A dropped-actions warning is logged once per this many drops. */
    private const val DROP_LOG_EVERY = 5_000L

    // SQLite result codes for rows the database will never accept (TOOBIG, CONSTRAINT, MISMATCH).
    @Suppress("MagicNumber")
    private val SQLITE_DATA_ERRORS = setOf(18, 19, 20)

    // MySQL reports a failed CHECK (3819) and an unconvertible value (1366) with the generic HY000 state.
    @Suppress("MagicNumber")
    private val MYSQL_DATA_ERRORS = setOf(1366, 3819)

    private val queue = LinkedBlockingQueue<ActionType>()

    @Volatile
    private var job: Job? = null
    private val droppedActions = AtomicLong(0)
    private val explosionSampleCounter = AtomicLong(0)
    private val dbHealthy = AtomicBoolean(true)

    @Volatile
    private var loggingMode = LoggingMode.NORMAL

    /**
     * Batch held in memory after a failed DB write. It is retried before new actions are drained,
     * so a database outage never loses it: the queue limits below cap memory instead.
     */
    @Volatile
    private var retryBatch: List<ActionType>? = null
    private var retryAttempts = 0

    val size: Int get() = queue.size + (retryBatch?.size ?: 0)
    val dropped: Long get() = droppedActions.get()
    val isCritical: Boolean get() = loggingMode != LoggingMode.NORMAL
    val mode: LoggingMode get() = loggingMode

    /** False while DB writes are failing and a batch is being retried. */
    val healthy: Boolean get() = dbHealthy.get()

    fun start() {
        if (job != null) return
        job = ViaLogium.launch {
            while (isActive) {
                val queueSize = queue.size
                updateLoggingMode(queueSize)

                val batchSize = getBatchSize(queueSize)
                val batchDelay = getBatchDelay(queueSize)

                if (retryBatch == null && queue.size < batchSize && batchDelay > 0) {
                    delay(batchDelay.ticks)
                }

                if (retryBatch != null || queue.isNotEmpty()) {
                    drainBatch(batchSize)
                } else {
                    // Idle: ensure at least one suspension point so an empty queue (especially with
                    // batchDelay == 0) cannot turn this loop into a 100% CPU busy-spin.
                    delay(1.ticks)
                }
            }
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
            if (droppedNow % DROP_LOG_EVERY == 1L) {
                logWarn("Dropping actions: queue hard limit reached ($queueSize/$maxQueueSize), dropped=$droppedNow")
            }
            return false
        }

        if (shouldDropByCurrentMode(action, queueSize)) {
            val droppedNow = droppedActions.incrementAndGet()
            if (droppedNow % DROP_LOG_EVERY == 1L) {
                logWarn("$loggingMode drop active: queue=$queueSize, dropped=$droppedNow")
            }
            return false
        }

        return queue.offer(action)
    }

    /**
     * Writes everything that is left. The flush loop is stopped first and joined, so the two never
     * drain concurrently and a batch it was writing is kept for the retry below instead of lost.
     */
    suspend fun drainAll() {
        job?.cancelAndJoin()
        job = null
        val batchSize = ViaLogium.config[DatabaseSpec.batchSize].coerceAtLeast(1)
        var failuresInARow = 0
        while (queue.isNotEmpty() || retryBatch != null) {
            val before = size
            drainBatch(batchSize, shuttingDown = true)
            failuresInARow = if (size >= before) failuresInARow + 1 else 0
            if (failuresInARow >= MAX_SHUTDOWN_DRAIN_FAILURES) {
                logError("Shutdown drain: database keeps failing, $size queued actions are lost")
                queue.clear()
                retryBatch = null
                return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failure here must not take the server down
    private suspend fun drainBatch(batchSize: Int, shuttingDown: Boolean = false) {
        val batch = retryBatch ?: mutableListOf<ActionType>().also { queue.drainTo(it, batchSize) }
        if (batch.isEmpty()) return
        retryBatch = batch

        try {
            DatabaseManager.logActionBatch(batch)
            onBatchWritten()
        } catch (e: CancellationException) {
            // Stopped mid-write (shutdown): the batch stays in retryBatch for drainAll.
            throw e
        } catch (t: Throwable) {
            dbHealthy.set(false)
            if (isDataError(t)) {
                // The database rejects some row of this batch. Retrying the same batch would fail
                // forever, so write it in halves until the bad rows are isolated and dropped alone.
                retryBatch = null
                retryAttempts = 0
                if (writeIsolatingBadRows(batch, t)) markHealthy()
            } else {
                retryAttempts++
                val backoff = (MIN_RETRY_DELAY_MS shl (retryAttempts - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS))
                    .coerceAtMost(MAX_RETRY_DELAY_MS)
                val message = "DB write failed (attempt $retryAttempts): ${t.message}; ${batch.size} actions held, " +
                    "retrying in ${backoff}ms (queue=${queue.size})"
                // Full stack trace once per outage, then one line per retry.
                if (retryAttempts == 1) logWarn(message, t) else logWarn(message)
                if (!shuttingDown) delay(backoff)
            }
        }
    }

    /** Returns false when the database became unreachable; the unwritten rest is then in retryBatch. */
    @Suppress("TooGenericExceptionCaught") // a failure here must not take the server down
    private suspend fun writeIsolatingBadRows(batch: List<ActionType>, cause: Throwable): Boolean {
        if (batch.size == 1) {
            val action = batch.single()
            droppedActions.incrementAndGet()
            logError(
                "Dropping action the database rejects: ${action.identifier} at " +
                    "[${action.world} ${action.pos.x} ${action.pos.y} ${action.pos.z}] " +
                    "by ${action.sourceProfile?.name ?: action.sourceName}: ${cause.message}",
            )
            return true
        }
        val half = batch.size / 2
        val parts = listOf(batch.subList(0, half), batch.subList(half, batch.size))
        for ((index, part) in parts.withIndex()) {
            val written = try {
                DatabaseManager.logActionBatch(part)
                true
            } catch (e: CancellationException) {
                retryBatch = retryBatch.orEmpty() + parts.drop(index).flatten()
                throw e
            } catch (t: Throwable) {
                if (isDataError(t)) {
                    writeIsolatingBadRows(part, t)
                } else {
                    // The database went away while isolating: hold the rest for the normal retry.
                    retryBatch = retryBatch.orEmpty() + part
                    false
                }
            }
            if (!written) {
                retryBatch = retryBatch.orEmpty() + parts.drop(index + 1).flatten()
                return false
            }
        }
        return true
    }

    private fun onBatchWritten() {
        retryBatch = null
        retryAttempts = 0
        markHealthy()
    }

    private fun markHealthy() {
        if (!dbHealthy.getAndSet(true)) {
            logInfo("Database writes recovered; logging is healthy again (dropped total=${droppedActions.get()})")
        }
    }

    /** True when the database refused the data itself, as opposed to being unreachable or busy. */
    private fun isDataError(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            when (current) {
                is SQLDataException, is SQLIntegrityConstraintViolationException -> return true

                is SQLException -> {
                    val state = current.sqlState
                    if (state != null && (state.startsWith("22") || state.startsWith("23"))) return true
                    if (current.javaClass.name.startsWith("org.sqlite") && current.errorCode in SQLITE_DATA_ERRORS) {
                        return true
                    }
                    if (current.javaClass.name.startsWith("com.mysql") && current.errorCode in MYSQL_DATA_ERRORS) {
                        return true
                    }
                }

                is IllegalArgumentException, is IllegalStateException -> return true
            }
            current = current.cause
        }
        return false
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
