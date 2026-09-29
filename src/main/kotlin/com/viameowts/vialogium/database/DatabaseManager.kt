package com.viameowts.vialogium.database

import com.google.common.collect.BiMap
import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.actionutils.Preview
import com.viameowts.vialogium.actionutils.SearchResults
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.config.SearchSpec
import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.logInfo
import com.viameowts.vialogium.logWarn
import com.viameowts.vialogium.registry.ActionRegistry
import com.viameowts.vialogium.utility.Negatable
import com.viameowts.vialogium.utility.PlayerResult
import com.viameowts.vialogium.utility.ServerIdentity
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.players.NameAndId
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.expandArgs
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.orWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.sql.DataSource
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil

const val MAX_QUERY_RETRIES = 5
const val MIN_RETRY_DELAY = 200L

// Capped low on purpose: the database runs on a single thread, so a failing query that keeps
// retrying with a long backoff blocks every other query (status, search, logging) behind it.
// The old 300s cap could freeze the database thread for minutes on a single bad query.
const val MAX_RETRY_DELAY = 3_000L
private const val ACTION_UPDATE_CHUNK_SIZE = 900

/** MySQL/MariaDB `TEXT` holds 64 KiB. */
const val MAX_EXTRA_DATA_BYTES = 65_535

/** Other backends store unbounded text; this only guards the heap against pathological NBT. */
private const val MAX_EXTRA_DATA_BYTES_UNBOUNDED = 8 * 1024 * 1024
private const val MAX_PLAYER_NAME_LENGTH = 16
private const val MAX_SOURCE_NAME_LENGTH = 30
private const val MAX_IDENTIFIER_LENGTH = 191
private const val PURGE_CHUNK_SIZE = 5_000
private const val COUNT_CACHE_TTL_MS = 60_000L
private const val COUNT_CACHE_MAX_ENTRIES = 256
private const val FILE_DB_READ_THREADS = 2
private const val MAX_READ_THREADS = 8
private const val MAX_UTF8_BYTES_PER_CHAR = 3
internal const val BYTES_PER_MB = 1024L * 1024L

// One place for every query; split only if it keeps growing.
@Suppress("LargeClass")
object DatabaseManager {

    // These values are initialised late to allow the database to be created at server start,
    // which means the database file is located in the world folder and allows for per-world databases.
    private lateinit var database: Database

    // Read once inside a transaction: H2's dialect needs a live connection to report its name.
    var databaseType: String = ""
        private set

    private val cache = DatabaseCacheService

    // Set during the shutdown drain: lets queued writes through even if the worlds are no longer
    // saveable (overworld() null / noSave true), so we don't stall until the drain timeout.
    @Volatile
    var bypassSaveStateWait: Boolean = false

    @Volatile
    var serverStarted: Boolean = false

    // Writes (logging, rollback flags, purge) run on one thread, in order. Reads (search, inspect,
    // previews, status) run beside them, so a big batch insert no longer holds up an inspect click.
    private var writeDispatcher: ExecutorCoroutineDispatcher? = null
    private var writeContext: CoroutineContext = Dispatchers.IO + CoroutineName("viaLogium DB write")
    private var readContext: CoroutineContext = Dispatchers.IO + CoroutineName("viaLogium DB read")
    private var dataSource: DataSource? = null

    /** SQLite/H2 live in the world folder, so their writes pause during /save-off backups. */
    private var fileBased = false

    // H2 reports itself as "H2 (MySQL Mode)", but it is not MySQL.
    private val isMysqlFamily: Boolean
        get() = !databaseType.startsWith("H2", ignoreCase = true) &&
            (databaseType.contains("mysql", ignoreCase = true) || databaseType.contains("mariadb", ignoreCase = true))
    private val isPostgres: Boolean
        get() = databaseType.contains("postgres", ignoreCase = true)

    private data class CachedCount(val value: Long, val atMs: Long)
    private val countCache = ConcurrentHashMap<ActionSearchParams, CachedCount>()
    private val vialogiumLogger = object : SqlLogger {
        override fun log(context: StatementContext, transaction: Transaction) {
            // debug level: requires BOTH the logSQL config flag and a debug-enabled logger, so a
            // stray flag in production can't flood logs / stall the DB thread on I/O on its own.
            ViaLogium.logger.debug("SQL: ${context.expandArgs(transaction)}")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun setup(dataSource: DataSource) {
        this.dataSource = dataSource
        database = Database.connect(dataSource)
        databaseType = transaction(database) { database.dialect.name }
        fileBased = databaseType.contains("sqlite", ignoreCase = true) || databaseType.contains("h2", ignoreCase = true)
        applySqlitePragmasIfNeeded(dataSource)
        writeDispatcher = newSingleThreadContext("viaLogium DB write").also {
            writeContext = it + CoroutineName("viaLogium DB write")
        }
        val readThreads = if (fileBased) {
            FILE_DB_READ_THREADS
        } else {
            // Leave one pooled connection to the writer.
            (config[DatabaseExtensionSpec.maxPoolSize] - 1).coerceIn(1, MAX_READ_THREADS)
        }
        readContext = Dispatchers.IO.limitedParallelism(readThreads) + CoroutineName("viaLogium DB read")
        logInfo("Database: $databaseType, 1 write thread, $readThreads read threads")
        if (config[DatabaseSpec.logSQL]) {
            logWarn(
                "logSQL is enabled: every SQL statement is logged on the database thread. " +
                    "This is for short-term debugging only — disable it in production.",
            )
        }
    }

    /** Stops the write thread and closes the connection pool. Called once the queue is drained. */
    fun close() {
        writeDispatcher?.close()
        writeDispatcher = null
        (dataSource as? AutoCloseable)?.let { closeable ->
            runCatching { closeable.close() }.onFailure { logWarn("Closing the database pool failed", it) }
        }
        dataSource = null
    }

    private fun applySqlitePragmasIfNeeded(dataSource: DataSource) {
        if (!databaseType.contains("sqlite", ignoreCase = true)) return

        runCatching {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                if (!previousAutoCommit) {
                    connection.autoCommit = true
                }

                connection.createStatement().use { statement ->
                    if (config[DatabaseSpec.sqliteUseWal]) {
                        statement.execute("PRAGMA journal_mode=WAL")
                    }

                    val syncMode = if (config[DatabaseSpec.sqliteSynchronousNormal]) "NORMAL" else "FULL"
                    statement.execute("PRAGMA synchronous=$syncMode")

                    if (config[DatabaseSpec.sqliteTempStoreMemory]) {
                        statement.execute("PRAGMA temp_store=MEMORY")
                    }

                    val cacheSizeKb = config[DatabaseSpec.sqliteCacheSizeKb].coerceAtLeast(0)
                    if (cacheSizeKb > 0) {
                        statement.execute("PRAGMA cache_size=-$cacheSizeKb")
                    }

                    val mmapBytes = config[DatabaseSpec.sqliteMmapSizeMb].coerceAtLeast(0).toLong() * BYTES_PER_MB
                    if (mmapBytes > 0L) {
                        statement.execute("PRAGMA mmap_size=$mmapBytes")
                    }

                    val busyTimeoutMs = config[DatabaseSpec.sqliteBusyTimeoutMs].coerceAtLeast(0)
                    if (busyTimeoutMs > 0) {
                        statement.execute("PRAGMA busy_timeout=$busyTimeoutMs")
                    }
                }

                if (!previousAutoCommit) {
                    connection.autoCommit = false
                }
            }

            logInfo("Applied SQLite PRAGMA optimizations")
        }.onFailure {
            logWarn("Failed to apply SQLite PRAGMA optimizations", it)
        }
    }

    fun ensureTables() = transaction(database) {
        addLogger(vialogiumLogger)
        SchemaUtils.create(
            Tables.Players,
            Tables.Actions,
            Tables.ActionIdentifiers,
            Tables.ObjectIdentifiers,
            Tables.Sources,
            Tables.Worlds,
        )
        addServerColumn()
        migrateIndexes()
        logInfo("Tables ready")
    }

    /**
     * Databases from before 1.1.0 have no `server` column. They were written by one server, so the
     * existing rows are assigned to this one.
     */
    private fun JdbcTransaction.addServerColumn() {
        val columns = mutableSetOf<String>()
        forEachActionsTableName { meta, table ->
            meta.getColumns(null, null, table, null).use { rs ->
                while (rs.next()) columns.add(rs.getString("COLUMN_NAME").lowercase())
            }
        }
        if (columns.isEmpty() || "server" in columns) return

        logInfo("Adding server column to actions, existing rows belong to '${ServerIdentity.id}'")
        exec("ALTER TABLE actions ADD COLUMN server VARCHAR($MAX_SERVER_ID_LENGTH) DEFAULT '' NOT NULL")
        Tables.Actions.update({ Tables.Actions.server eq "" }) { it[server] = ServerIdentity.id }
    }

    /**
     * Brings the indexes of databases created by older versions in line with [Tables.Actions]:
     * searches, purges and rollbacks filter by server and time, and `(x, y, z)` alone duplicated the
     * leading columns of `actions_by_location`, costing every insert for nothing.
     */
    private fun JdbcTransaction.migrateIndexes() {
        val existing = actionsIndexNames()
        if (existing.isEmpty()) return
        fun create(name: String, columns: String) {
            if (name in existing) return
            logInfo("Creating index $name, this can take a while on a large database")
            runCatching { exec("CREATE INDEX $name ON actions ($columns)") }
                .onFailure { logWarn("Could not create index $name: ${it.message}") }
        }
        fun drop(name: String) {
            if (name !in existing) return
            val ddl = if (isMysqlFamily) "DROP INDEX $name ON actions" else "DROP INDEX $name"
            runCatching { exec(ddl) }.onFailure { logWarn("Could not drop index $name: ${it.message}") }
        }
        create("actions_server_time_idx", "server, time")
        create("actions_by_location", "x, y, z, world_id")
        drop("actions_xyz_idx")
        drop("actions_server_idx")
        if (config[DatabaseSpec.updateSchema]) {
            create("actions_time_idx", "time")
            create("actions_rolled_back_idx", "rolled_back")
        }
    }

    private fun JdbcTransaction.actionsIndexNames(): Set<String> {
        val names = mutableSetOf<String>()
        forEachActionsTableName { meta, table ->
            meta.getIndexInfo(null, null, table, false, true).use { rs ->
                while (rs.next()) rs.getString("INDEX_NAME")?.let { names.add(it.lowercase()) }
            }
        }
        return names
    }

    // Unquoted names are stored lower case by PostgreSQL/SQLite/MySQL and upper case by H2.
    private fun JdbcTransaction.forEachActionsTableName(block: (java.sql.DatabaseMetaData, String) -> Unit) {
        val meta = (connection.connection as java.sql.Connection).metaData
        for (table in listOf("actions", "ACTIONS")) block(meta, table)
    }

    suspend fun setupCache() {
        read { loadCaches() }
    }

    /**
     * (Re)reads the id caches. With a shared database other servers add identifiers, sources and
     * players too, so a query can reference ids this server has not seen yet.
     */
    private fun loadCaches() {
        Tables.ActionIdentifier.all().forEach {
            cache.actionIdentifierKeys.forcePut(it.identifier, it.id.value)
        }
        Tables.World.all().forEach {
            cache.worldIdentifierKeys.forcePut(it.identifier, it.id.value)
        }
        Tables.ObjectIdentifier.all().forEach {
            cache.objectIdentifierKeys.forcePut(it.identifier, it.id.value)
        }
        Tables.Source.all().forEach {
            cache.sourceKeys.forcePut(it.name, it.id.value)
        }
        Tables.Player.all().forEach {
            cache.playerKeys.forcePut(it.playerId, it.id.value)
            cache.playernameKeys.forcePut(it.playerName, it.id.value)
        }
    }

    private fun isCached(row: ResultRow): Boolean =
        cache.actionIdentifierKeys.containsValue(row[Tables.Actions.actionIdentifier].value) &&
            cache.worldIdentifierKeys.containsValue(row[Tables.Actions.world].value) &&
            cache.objectIdentifierKeys.containsValue(row[Tables.Actions.objectId].value) &&
            cache.objectIdentifierKeys.containsValue(row[Tables.Actions.oldObjectId].value) &&
            cache.sourceKeys.containsValue(row[Tables.Actions.sourceName].value) &&
            row.getOrNull(Tables.Actions.sourcePlayer).let { it == null || cache.playerKeys.containsValue(it.value) }

    suspend fun autoPurge() {
        val days = config[DatabaseSpec.autoPurgeDays]
        if (days <= 0) return
        logInfo("Purging actions older than $days days")
        // Each server purges its own rows: servers sharing a database may keep them for different times.
        val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val deleted = deleteInChunks {
            (Tables.Actions.timestamp lessEq cutoff) and
                (Tables.Actions.server eq ServerIdentity.id)
        }
        logInfo("Successfully purged $deleted actions")
    }

    suspend fun searchActions(params: ActionSearchParams, page: Int): SearchResults = read {
        return@read selectActionsSearch(params, page)
    }

    suspend fun countActions(params: ActionSearchParams): Long = read {
        return@read countActions(params)
    }

    suspend fun countAllActions(): Long = read {
        return@read Tables.Actions.selectAll().count()
    }

    /**
     * Row count for `/vl status`. PostgreSQL and MySQL answer from table statistics (second = true,
     * shown with `~`) because an exact `COUNT(*)` over a network's history is a full scan.
     */
    suspend fun estimateAllActions(): Pair<Long, Boolean> = read {
        val estimate: Long? = runCatching {
            when {
                isPostgres -> exec("SELECT reltuples::bigint FROM pg_class WHERE relname = 'actions'") { rs ->
                    if (rs.next()) rs.getLong(1) else null
                }

                isMysqlFamily -> exec(
                    "SELECT TABLE_ROWS FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'actions'",
                ) { rs -> if (rs.next()) rs.getLong(1) else null }

                else -> null
            }
        }.getOrNull()
        if (estimate != null && estimate >= 0) {
            estimate to true
        } else {
            Tables.Actions.selectAll().count() to false
        }
    }

    suspend fun rollbackActions(params: ActionSearchParams): List<ActionType> {
        val actions = selectRollback(params)
        val actionIds = actions.map { it.id }.toSet()
        rollbackActions(actionIds)
        return actions
    }

    suspend fun rollbackActions(actionIds: Set<Int>) = execute {
        return@execute rollbackActions(actionIds)
    }

    suspend fun restoreActions(params: ActionSearchParams): List<ActionType> {
        val actions = selectRestore(params)
        val actionIds = actions.map { it.id }.toSet()
        restoreActions(actionIds)
        return actions
    }

    suspend fun restoreActions(actionIds: Set<Int>) = execute {
        return@execute restoreActions(actionIds)
    }

    suspend fun countRollbackActions(params: ActionSearchParams): Long = read {
        return@read Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false))
            .count()
    }

    suspend fun countRestoreActions(params: ActionSearchParams): Long = read {
        return@read Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true))
            .count()
    }

    suspend fun selectRollbackBatch(params: ActionSearchParams, limit: Int): List<ActionType> = read {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false))
            .orderBy(Tables.Actions.id, SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
        return@read getActionsFromQuery(query)
    }

    suspend fun selectRestoreBatch(params: ActionSearchParams, limit: Int): List<ActionType> = read {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true))
            .orderBy(Tables.Actions.id, SortOrder.ASC)
            .limit(limit.coerceAtLeast(1))
        return@read getActionsFromQuery(query)
    }

    suspend fun selectRollbackPreviewBatch(
        params: ActionSearchParams,
        beforeIdExclusive: Int?,
        limit: Int,
    ): List<ActionType> = read {
        var conditions: Op<Boolean> = buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false)
        if (beforeIdExclusive != null) {
            conditions = conditions and (Tables.Actions.id lessEq (beforeIdExclusive - 1))
        }

        val query = Tables.Actions
            .selectAll()
            .where(conditions)
            .orderBy(Tables.Actions.id, SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))

        return@read getActionsFromQuery(query)
    }

    suspend fun selectRestorePreviewBatch(
        params: ActionSearchParams,
        afterIdExclusive: Int?,
        limit: Int,
    ): List<ActionType> = read {
        var conditions: Op<Boolean> = buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true)
        if (afterIdExclusive != null) {
            conditions = conditions and (Tables.Actions.id greaterEq (afterIdExclusive + 1))
        }

        val query = Tables.Actions
            .selectAll()
            .where(conditions)
            .orderBy(Tables.Actions.id, SortOrder.ASC)
            .limit(limit.coerceAtLeast(1))

        return@read getActionsFromQuery(query)
    }

    suspend fun selectRollback(params: ActionSearchParams): List<ActionType> = read {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false))
            .orderBy(Tables.Actions.id, SortOrder.DESC)
        return@read getActionsFromQuery(query)
    }

    suspend fun selectRestore(params: ActionSearchParams): List<ActionType> = read {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true))
            .orderBy(Tables.Actions.id, SortOrder.ASC)
        return@read getActionsFromQuery(query)
    }

    /**
     * Positions ("world:x:y:z") of not-yet-rolled-back container block-breaks in [params]. Used to
     * pre-seed the rollback tracker so item-drop / item-pick-up of items that spilled from those
     * containers are skipped (their contents are restored from the block-break NBT snapshot instead,
     * avoiding duplication). A pre-scan is required because those item actions are newer than the
     * break and would otherwise be processed before it in id-DESC order.
     */
    suspend fun selectContainerBreakPositions(params: ActionSearchParams): Set<String> = read {
        val blockBreakId = cache.actionIdentifierKeys["block-break"] ?: return@read emptySet<String>()
        val worldInverse = cache.worldIdentifierKeys.inverse()
        val positions = HashSet<String>()
        Tables.Actions
            .selectAll()
            .where(
                buildQueryParams(params.localOnly()) and
                    (Tables.Actions.rolledBack eq false) and
                    (Tables.Actions.actionIdentifier eq blockBreakId),
            )
            .forEach { row ->
                val extra = row[Tables.Actions.extraData]
                if (extra == null || !extra.contains("Items")) return@forEach
                val worldIdentifier = worldInverse[row[Tables.Actions.world].value] ?: return@forEach
                positions.add(
                    "$worldIdentifier:${row[Tables.Actions.x]}:${row[Tables.Actions.y]}:${row[Tables.Actions.z]}",
                )
            }
        return@read positions
    }

    suspend fun previewActions(params: ActionSearchParams, type: Preview.Type): List<ActionType> = when (type) {
        Preview.Type.ROLLBACK -> selectRollback(params)
        Preview.Type.RESTORE -> selectRestore(params)
    }

    private fun getActionsFromQuery(query: Query): List<ActionType> {
        val actions = mutableListOf<ActionType>()
        val rows = query.toList()
        if (rows.any { !isCached(it) }) loadCaches()

        val actionIdentifierCache = DatabaseCacheService.actionIdentifierKeys.inverse()
        val worldCache = DatabaseCacheService.worldIdentifierKeys.inverse()
        val objectIdentifierCache = DatabaseCacheService.objectIdentifierKeys.inverse()
        val sourceCache = DatabaseCacheService.sourceKeys.inverse()
        val playerCache = DatabaseCacheService.playerKeys.inverse()
        val playerNameCache = DatabaseCacheService.playernameKeys.inverse()

        for (action in rows) {
            val actionName = actionIdentifierCache[action[Tables.Actions.actionIdentifier].value]
            val objectIdentifier = objectIdentifierCache[action[Tables.Actions.objectId].value]
            val oldObjectIdentifier = objectIdentifierCache[action[Tables.Actions.oldObjectId].value]
            val sourceName = sourceCache[action[Tables.Actions.sourceName].value]
            if (actionName == null || objectIdentifier == null || oldObjectIdentifier == null || sourceName == null) {
                // Another server added the id after our reload; the row shows up on the next query.
                logWarn("Skipping action ${action[Tables.Actions.id].value}: unknown identifier ids")
                continue
            }
            // Types registered by an extension on another server are unknown here.
            val typeSupplier = ActionRegistry.getType(actionName) ?: continue

            val type = typeSupplier.get()
            type.id = action[Tables.Actions.id].value
            type.timestamp = action[Tables.Actions.timestamp]
            type.pos = BlockPos(action[Tables.Actions.x], action[Tables.Actions.y], action[Tables.Actions.z])
            type.world = worldCache[action[Tables.Actions.world].value]
            type.objectIdentifier = objectIdentifier
            type.oldObjectIdentifier = oldObjectIdentifier
            type.objectState = action[Tables.Actions.blockState]
            type.oldObjectState = action[Tables.Actions.oldBlockState]
            type.sourceName = sourceName
            type.sourceProfile = action.getOrNull(Tables.Actions.sourcePlayer)?.let {
                val id = it.value
                val uuid = playerCache[id]
                val name = playerNameCache[id]
                if (uuid != null && name != null) NameAndId(uuid, name) else null
            }
            type.extraData = action[Tables.Actions.extraData]
            type.rolledBack = action[Tables.Actions.rolledBack]
            type.serverId = action[Tables.Actions.server]
            if (type.serverId.isNotEmpty()) ServerIdentity.known.add(type.serverId)

            actions.add(type)
        }

        return actions
    }

    private fun buildQueryParams(params: ActionSearchParams): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE

        if (params.bounds != null && params.bounds != ActionSearchParams.GLOBAL) {
            op = op.and { Tables.Actions.x.between(params.bounds.minX(), params.bounds.maxX()) }
            op = op.and { Tables.Actions.y.between(params.bounds.minY(), params.bounds.maxY()) }
            op = op.and { Tables.Actions.z.between(params.bounds.minZ(), params.bounds.maxZ()) }
        }

        if (params.before != null && params.after != null) {
            op = op.and {
                Tables.Actions.timestamp.greaterEq(params.after) and Tables.Actions.timestamp.lessEq(params.before)
            }
        } else if (params.before != null) {
            op = op.and { Tables.Actions.timestamp.lessEq(params.before) }
        } else if (params.after != null) {
            op = op.and { Tables.Actions.timestamp.greaterEq(params.after) }
        }

        if (params.rolledBack != null) {
            op = op.and { Tables.Actions.rolledBack.eq(params.rolledBack) }
        }

        op = addServerParameters(op, params.servers)

        op = addParameters(
            op,
            params.sourceNames,
            DatabaseManager::getSourceId,
            Tables.Actions.sourceName,
        )

        op = addParameters(
            op,
            params.actions,
            DatabaseManager::getActionId,
            Tables.Actions.actionIdentifier,
        )

        op = addParameters(
            op,
            params.worlds,
            DatabaseManager::getWorldId,
            Tables.Actions.world,
        )

        op = addParameters(
            op,
            params.objects,
            DatabaseManager::getRegistryKeyId,
            Tables.Actions.objectId,
            Tables.Actions.oldObjectId,
        )

        op = addParameters(
            op,
            params.sourcePlayerIds,
            DatabaseManager::getPlayerId,
            Tables.Actions.sourcePlayer,
        )

        return op
    }

    /** No `server:` given means this server only; `server:all` removes the filter. */
    private fun addServerParameters(op: Op<Boolean>, servers: Collection<Negatable<String>>?): Op<Boolean> {
        if (servers.isNullOrEmpty()) return op.and { Tables.Actions.server eq ServerIdentity.id }
        if (servers.any { it.allowed && it.property == ServerIdentity.ALL }) return op

        val allowed = servers.filter { it.allowed }.map { it.property }
        val denied = servers.filterNot { it.allowed }.map { it.property }
        var newOp = op
        if (allowed.isNotEmpty()) newOp = newOp.and { Tables.Actions.server inList allowed }
        if (denied.isNotEmpty()) newOp = newOp.and { Tables.Actions.server notInList denied }
        return newOp
    }

    private fun <E : Comparable<E>, C : EntityID<E>?, T> addParameters(
        op: Op<Boolean>,
        paramSet: Collection<Negatable<T>>?,
        objectToId: Function<T, E?>,
        column: Column<C>,
        orColumn: Column<C>? = null,
    ): Op<Boolean> {
        val idParamSet = mutableSetOf<Negatable<E>>()
        paramSet?.forEach {
            val paramId = objectToId.apply(it.property)
            if (paramId != null) {
                idParamSet.add(Negatable(paramId, it.allowed))
            } else {
                // Unknown source name
                return Op.FALSE
            }
        }
        return addParameters(op, idParamSet, column, orColumn)
    }

    private fun <E : Comparable<E>, C : EntityID<E>?> addParameters(
        op: Op<Boolean>,
        paramSet: Collection<Negatable<E>>?,
        column: Column<C>,
        orColumn: Column<C>? = null,
    ): Op<Boolean> {
        fun addAllowedParameters(allowed: Collection<E>, op: Op<Boolean>): Op<Boolean> {
            if (allowed.isEmpty()) return op

            var operator = if (orColumn != null) {
                column eq allowed.first() or (orColumn eq allowed.first())
            } else {
                column eq allowed.first()
            }

            allowed.stream().skip(1).forEach { param ->
                operator = if (orColumn != null) {
                    operator.or { column eq param or (orColumn eq param) }
                } else {
                    operator.or { column eq param }
                }
            }

            return op.and { operator }
        }

        fun addDeniedParameters(denied: Collection<E>, op: Op<Boolean>): Op<Boolean> {
            if (denied.isEmpty()) return op

            var operator = if (orColumn != null) {
                column neq denied.first() and (orColumn neq denied.first())
            } else {
                column neq denied.first() or column.isNull()
            }

            denied.stream().skip(1).forEach { param ->
                operator = if (orColumn != null) {
                    operator.and { column neq param and (orColumn neq param) }
                } else {
                    operator.and { column neq param or column.isNull() }
                }
            }

            return op.and { operator }
        }

        if (paramSet.isNullOrEmpty()) return op

        var newOp = op
        newOp = addAllowedParameters(paramSet.filter { it.allowed }.map { it.property }, newOp)
        newOp = addDeniedParameters(paramSet.filterNot { it.allowed }.map { it.property }, newOp)

        return newOp
    }

    /**
     * Writes one batch. Ids for new players, sources, worlds and identifiers are created first in
     * their own committed transaction and only then cached, so a failed batch can never leave ids in
     * the cache that were rolled back (which used to break every later batch with a foreign key error).
     */
    suspend fun logActionBatch(actions: List<ActionType>) {
        val storable = actions.filter(::isStorable)
        if (storable.isEmpty()) return

        val missing = MissingKeys.of(storable)
        if (!missing.isEmpty()) {
            val resolved = execute { resolveKeys(missing) }
            resolved.applyToCache()
        }
        // One attempt: ActionQueueService owns retries and can split a batch the database rejects.
        execute(attempts = 1) { insertActions(storable) }
    }

    suspend fun registerWorld(identifier: Identifier) = execute {
        insertWorld(identifier)
    }

    suspend fun registerActionType(id: String) = execute {
        insertActionType(id)
    }

    suspend fun logPlayer(uuid: UUID, name: String) {
        val playerName = name.take(MAX_PLAYER_NAME_LENGTH)
        val id = execute { upsertPlayer(uuid, playerName) }
        cache.playerKeys.forcePut(uuid, id)
        cache.playernameKeys.forcePut(playerName, id)
    }

    suspend fun insertIdentifiers(identifiers: Collection<Identifier>) = execute {
        insertRegKeys(identifiers.filter { it.toString().length <= MAX_IDENTIFIER_LENGTH })
    }

    /** Queries that change the database: one thread, in order. */
    private suspend fun <T> execute(attempts: Int = MAX_QUERY_RETRIES, body: suspend JdbcTransaction.() -> T): T {
        // Only file databases in the world folder need to hold still for a /save-off backup; a
        // PostgreSQL/MySQL server shared by the network has nothing to do with this world's files.
        if (fileBased && !bypassSaveStateWait) awaitSaveEnabled()
        return transactionOn(writeContext, attempts, body)
    }

    /** Queries that only read: run beside the writer so searches don't wait behind batch inserts. */
    private suspend fun <T> read(body: suspend JdbcTransaction.() -> T): T =
        transactionOn(readContext, MAX_QUERY_RETRIES, body)

    private suspend fun <T> transactionOn(
        context: CoroutineContext,
        attempts: Int,
        body: suspend JdbcTransaction.() -> T,
    ): T = newSuspendedTransaction(context = context, db = database) {
        maxAttempts = attempts
        minRetryDelay = MIN_RETRY_DELAY
        maxRetryDelay = MAX_RETRY_DELAY

        if (ViaLogium.config[DatabaseSpec.logSQL]) {
            addLogger(vialogiumLogger)
        }
        body(this)
    }

    private suspend fun awaitSaveEnabled() {
        var warnedSavePause = false
        while (!bypassSaveStateWait && ViaLogium.server.overworld()?.noSave != false) {
            // Worlds report noSave while the server is still starting: wait without a false alarm.
            if (!warnedSavePause && serverStarted) {
                logWarn("DB writes paused: vanilla save-off is active (/save-off? backup in progress?)")
                warnedSavePause = true
            }
            delay(timeMillis = 1000)
        }
    }

    suspend fun purgeActions(params: ActionSearchParams): Int = deleteInChunks { buildQueryParams(params) }

    /**
     * Deletes matching rows [PURGE_CHUNK_SIZE] at a time, each chunk in its own short transaction, so
     * a purge of millions of rows neither locks the table for minutes nor stalls logging meanwhile.
     */
    private suspend fun deleteInChunks(condition: () -> Op<Boolean>): Int {
        var total = 0
        while (true) {
            val (deleted, done) = execute {
                val op = condition()
                val lastId = Tables.Actions
                    .select(Tables.Actions.id)
                    .where(op)
                    .orderBy(Tables.Actions.id, SortOrder.ASC)
                    .limit(1)
                    .offset((PURGE_CHUNK_SIZE - 1).toLong())
                    .firstOrNull()
                    ?.get(Tables.Actions.id)
                    ?.value
                if (lastId == null) {
                    Tables.Actions.deleteWhere { op } to true
                } else {
                    Tables.Actions.deleteWhere { op and (Tables.Actions.id lessEq lastId) } to false
                }
            }
            total += deleted
            if (done) break
        }
        countCache.clear()
        return total
    }

    suspend fun searchPlayers(players: Set<NameAndId>): List<PlayerResult> = read {
        return@read selectPlayers(players)
    }

    private fun Transaction.insertActionType(id: String) {
        Tables.ActionIdentifiers.insertIgnore {
            it[actionIdentifier] = id
        }
    }

    private fun Transaction.insertWorld(identifier: Identifier) {
        Tables.Worlds.insertIgnore {
            it[this.identifier] = identifier.toString()
        }
    }

    private fun Transaction.insertRegKeys(identifiers: Collection<Identifier>) {
        Tables.ObjectIdentifiers.batchInsert(identifiers, true) { identifier ->
            this[Tables.ObjectIdentifiers.identifier] = identifier.toString()
        }
    }

    private fun extraDataLimit(): Int = if (isMysqlFamily) MAX_EXTRA_DATA_BYTES else MAX_EXTRA_DATA_BYTES_UNBOUNDED

    private fun utf8Length(text: String): Int {
        // Every char is at most 3 UTF-8 bytes; skip encoding when even that fits.
        if (text.length.toLong() * MAX_UTF8_BYTES_PER_CHAR <= extraDataLimit()) return text.length
        return text.toByteArray(Charsets.UTF_8).size
    }

    /** Rows the database would reject no matter how often they are retried. */
    private fun isStorable(action: ActionType): Boolean {
        val extra = action.extraData
        val problem = when {
            extra != null && utf8Length(extra) > extraDataLimit() -> "extra_data too large (${extra.length} chars)"

            action.identifier.length > MAX_ACTION_NAME_LENGTH -> "action type name too long"

            action.objectIdentifier.toString().length > MAX_IDENTIFIER_LENGTH ||
                action.oldObjectIdentifier.toString().length > MAX_IDENTIFIER_LENGTH -> "identifier too long"

            else -> null
        } ?: return true
        logWarn(
            "Skipping action log: $problem for action ${action.identifier} at " +
                "[${action.world} ${action.pos.x} ${action.pos.y} ${action.pos.z}] " +
                "by ${action.sourceProfile?.name ?: action.sourceName}",
        )
        return false
    }

    private fun worldOf(action: ActionType): Identifier =
        action.world ?: ViaLogium.server.overworld().dimension().identifier()

    private fun sourceKey(action: ActionType): String = action.sourceName.take(MAX_SOURCE_NAME_LENGTH)

    /** Lookup values of a batch that have no cached id yet. */
    private class MissingKeys {
        val actions = HashSet<String>()
        val objects = HashSet<Identifier>()
        val worlds = HashSet<Identifier>()
        val sources = HashSet<String>()
        val players = HashMap<UUID, String>()

        fun isEmpty() = actions.isEmpty() && objects.isEmpty() && worlds.isEmpty() &&
            sources.isEmpty() && players.isEmpty()

        companion object {
            fun of(actions: List<ActionType>): MissingKeys {
                val missing = MissingKeys()
                for (action in actions) {
                    if (!cache.actionIdentifierKeys.containsKey(action.identifier)) missing.actions += action.identifier
                    if (!cache.objectIdentifierKeys.containsKey(action.objectIdentifier)) {
                        missing.objects += action.objectIdentifier
                    }
                    if (!cache.objectIdentifierKeys.containsKey(action.oldObjectIdentifier)) {
                        missing.objects += action.oldObjectIdentifier
                    }
                    val world = worldOf(action)
                    if (!cache.worldIdentifierKeys.containsKey(world)) missing.worlds += world
                    val source = sourceKey(action)
                    if (!cache.sourceKeys.containsKey(source)) missing.sources += source
                    action.sourceProfile?.let { profile ->
                        if (!cache.playerKeys.containsKey(profile.id())) {
                            missing.players[profile.id()] = profile.name().take(MAX_PLAYER_NAME_LENGTH)
                        }
                    }
                }
                return missing
            }
        }
    }

    /** Ids created or found by [resolveKeys]; put into the cache only after the transaction committed. */
    private class ResolvedKeys {
        val actions = HashMap<String, Int>()
        val objects = HashMap<Identifier, Int>()
        val worlds = HashMap<Identifier, Int>()
        val sources = HashMap<String, Int>()
        val players = HashMap<UUID, Pair<String, Int>>()

        fun applyToCache() {
            actions.forEach { (key, id) -> cache.actionIdentifierKeys.forcePut(key, id) }
            objects.forEach { (key, id) -> cache.objectIdentifierKeys.forcePut(key, id) }
            worlds.forEach { (key, id) -> cache.worldIdentifierKeys.forcePut(key, id) }
            sources.forEach { (key, id) -> cache.sourceKeys.forcePut(key, id) }
            players.forEach { (uuid, value) ->
                cache.playerKeys.forcePut(uuid, value.second)
                cache.playernameKeys.forcePut(value.first, value.second)
            }
        }
    }

    // insertIgnore + select instead of insertAndGetId: with a shared database another server may
    // insert the same name at the same moment, and that must not fail the batch.
    private fun Transaction.resolveKeys(missing: MissingKeys): ResolvedKeys {
        val resolved = ResolvedKeys()
        missing.actions.forEach {
            resolved.actions[it] = insertIgnoreAndGetId(
                Tables.ActionIdentifiers,
                Tables.ActionIdentifiers.actionIdentifier,
                it,
            )
        }
        missing.objects.forEach {
            resolved.objects[it] =
                insertIgnoreAndGetId(Tables.ObjectIdentifiers, Tables.ObjectIdentifiers.identifier, it.toString())
        }
        missing.worlds.forEach {
            resolved.worlds[it] = insertIgnoreAndGetId(Tables.Worlds, Tables.Worlds.identifier, it.toString())
        }
        missing.sources.forEach {
            resolved.sources[it] = insertIgnoreAndGetId(Tables.Sources, Tables.Sources.name, it)
        }
        missing.players.forEach { (uuid, name) ->
            Tables.Players.insertIgnore {
                it[playerId] = uuid
                it[playerName] = name
            }
            val row = Tables.Players
                .select(Tables.Players.id, Tables.Players.playerName)
                .where { Tables.Players.playerId eq uuid }
                .single()
            resolved.players[uuid] = row[Tables.Players.playerName] to row[Tables.Players.id].value
        }
        return resolved
    }

    private fun <T : Any> insertIgnoreAndGetId(table: IntIdTable, column: Column<T>, value: T): Int {
        table.insertIgnore { it[column] = value }
        return table.select(table.id).where { column eq value }.single()[table.id].value
    }

    private fun Transaction.insertActions(actions: List<ActionType>) {
        fun <K> idOf(map: BiMap<K, Int>, key: K): Int = map[key] ?: throw IllegalStateException("No cached id for $key")

        Tables.Actions.batchInsert(actions, shouldReturnGeneratedValues = false) { action ->
            this[Tables.Actions.actionIdentifier] = idOf(cache.actionIdentifierKeys, action.identifier)
            this[Tables.Actions.timestamp] = action.timestamp
            this[Tables.Actions.x] = action.pos.x
            this[Tables.Actions.y] = action.pos.y
            this[Tables.Actions.z] = action.pos.z
            this[Tables.Actions.objectId] = idOf(cache.objectIdentifierKeys, action.objectIdentifier)
            this[Tables.Actions.oldObjectId] = idOf(cache.objectIdentifierKeys, action.oldObjectIdentifier)
            this[Tables.Actions.world] = idOf(cache.worldIdentifierKeys, worldOf(action))
            this[Tables.Actions.blockState] = action.objectState
            this[Tables.Actions.oldBlockState] = action.oldObjectState
            this[Tables.Actions.sourceName] = idOf(cache.sourceKeys, sourceKey(action))
            this[Tables.Actions.sourcePlayer] = action.sourceProfile?.let { idOf(cache.playerKeys, it.id()) }
            this[Tables.Actions.extraData] = action.extraData
            this[Tables.Actions.server] = ServerIdentity.id
        }
    }

    private fun Transaction.upsertPlayer(uuid: UUID, name: String): Int {
        val updated = Tables.Players.update({ Tables.Players.playerId eq uuid }) {
            it[playerName] = name
            it[lastJoin] = Instant.now()
        }
        if (updated == 0) {
            Tables.Players.insertIgnore {
                it[playerId] = uuid
                it[playerName] = name
            }
        }
        return Tables.Players.select(
            Tables.Players.id,
        ).where { Tables.Players.playerId eq uuid }.single()[Tables.Players.id].value
    }

    private fun Transaction.selectActionsSearch(params: ActionSearchParams, page: Int): SearchResults {
        val pageSize = config[SearchSpec.pageSize].coerceAtLeast(1)
        val totalActions = countForPaging(params, page)
        val totalPages = ceil(totalActions.toDouble() / pageSize.toDouble()).toInt()
        if (totalActions == 0L || page > totalPages) return SearchResults(emptyList(), params, page, totalPages)

        // Offset paging: people stay on the first pages, and the count above is reused between them.
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params))
            .orderBy(Tables.Actions.id, SortOrder.DESC)
            .limit(pageSize)
            .offset((pageSize.toLong() * (page - 1)))

        return SearchResults(getActionsFromQuery(query), params, page, totalPages)
    }

    /**
     * The first page always counts afresh; following pages of the same search reuse that count for
     * a minute instead of scanning every matching row again on each `/vl page`.
     */
    private fun Transaction.countForPaging(params: ActionSearchParams, page: Int): Long {
        val now = System.currentTimeMillis()
        if (page > 1) {
            countCache[params]?.takeIf { now - it.atMs < COUNT_CACHE_TTL_MS }?.let { return it.value }
        }
        val total = countActions(params)
        if (countCache.size >= COUNT_CACHE_MAX_ENTRIES) countCache.clear()
        countCache[params] = CachedCount(total, now)
        return total
    }

    private fun Transaction.countActions(params: ActionSearchParams): Long = Tables.Actions
        .selectAll()
        .andWhere { buildQueryParams(params) }
        .count()

    private fun Transaction.rollbackActions(actionIds: Set<Int>) {
        if (actionIds.isEmpty()) return

        actionIds.chunked(ACTION_UPDATE_CHUNK_SIZE).forEach { chunk ->
            Tables.Actions
                .update({ Tables.Actions.id inList chunk }) {
                    it[rolledBack] = true
                }
        }
    }

    private fun Transaction.restoreActions(actionIds: Set<Int>) {
        if (actionIds.isEmpty()) return

        actionIds.chunked(ACTION_UPDATE_CHUNK_SIZE).forEach { chunk ->
            Tables.Actions
                .update({ Tables.Actions.id inList chunk }) {
                    it[rolledBack] = false
                }
        }
    }

    // Called from the main thread (command suggestions) while the DB thread may be inserting a new
    // source. Return a defensive copy taken under the map's monitor to avoid a data race / CME.
    fun getKnownSources(): Set<String> = synchronized(cache.sourceKeys) { HashSet(cache.sourceKeys.keys) }

    private fun <T> getObjectId(
        obj: T,
        cache: BiMap<T, Int>,
        table: EntityClass<Int, Entity<Int>>,
        column: Column<T>,
    ): Int? = getObjectId(obj, Function.identity(), cache, table, column)

    private fun <T, S> getObjectId(
        obj: T,
        mapper: Function<T, S>,
        cache: BiMap<T, Int>,
        table: EntityClass<Int, Entity<Int>>,
        column: Column<S>,
    ): Int? {
        if (cache.containsKey(obj)) {
            return cache[obj]
        }
        return table.find { column eq mapper.apply(obj) }.firstOrNull()?.id?.value?.also {
            cache.forcePut(obj, it)
        }
    }

    private fun getPlayerId(playerId: UUID): Int? =
        getObjectId(playerId, cache.playerKeys, Tables.Player, Tables.Players.playerId)

    private fun getSourceId(source: String): Int? =
        getObjectId(source, cache.sourceKeys, Tables.Source, Tables.Sources.name)

    private fun getActionId(actionTypeId: String): Int? = getObjectId(
        actionTypeId,
        cache.actionIdentifierKeys,
        Tables.ActionIdentifier,
        Tables.ActionIdentifiers.actionIdentifier,
    )

    private fun getRegistryKeyId(identifier: Identifier): Int? = getObjectId(
        identifier,
        Identifier::toString,
        cache.objectIdentifierKeys,
        Tables.ObjectIdentifier,
        Tables.ObjectIdentifiers.identifier,
    )

    private fun getWorldId(identifier: Identifier): Int? = getObjectId(
        identifier,
        Identifier::toString,
        cache.worldIdentifierKeys,
        Tables.World,
        Tables.Worlds.identifier,
    )

    // Workaround because can't delete from a join in exposed https://kotlinlang.slack.com/archives/C0CG7E0A1/p1605866974117400
    private fun Transaction.purgeActions(params: ActionSearchParams): Int {
        // Direct delete by the same predicate. The previous `id inSubQuery (SELECT id FROM actions ...)`
        // form is a self-referencing delete: illegal on MySQL (error 1093) and prone to locking on
        // SQLite. buildQueryParams only references columns of the actions table, so a plain
        // deleteWhere is equivalent and safe across backends.
        val condition = buildQueryParams(params)
        return Tables.Actions.deleteWhere { condition }
    }

    private fun Transaction.selectPlayers(players: Set<NameAndId>): List<PlayerResult> {
        val query = Tables.Players.selectAll()
        for (player in players) {
            query.orWhere { Tables.Players.playerId eq player.id() }
        }

        return Tables.Player.wrapRows(query).toList().map { PlayerResult.fromRow(it) }
    }
}
