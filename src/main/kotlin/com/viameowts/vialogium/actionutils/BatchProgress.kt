package com.viameowts.vialogium.actionutils

/** Rollback, restore and preview read at least this many actions per database query. */
const val MIN_SELECT_BATCH_SIZE = 500

/** Rollback and restore mark at least this many actions per rolled_back update. */
const val MIN_UPDATE_BATCH_SIZE = 250

/** How often a long rollback, restore or preview logs its progress. */
const val PROGRESS_LOG_INTERVAL_MS = 30_000L

/** The command source hears how far a rollback or restore got every this many select batches. */
const val PROGRESS_MESSAGE_BATCHES = 2L

private const val MILLIS_PER_SECOND = 1_000L

fun actionsPerSecond(processed: Long, elapsedMs: Long): Long =
    processed * MILLIS_PER_SECOND / elapsedMs.coerceAtLeast(1L)
