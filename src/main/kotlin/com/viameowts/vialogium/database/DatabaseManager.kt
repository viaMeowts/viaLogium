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
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.orWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.function.Function
import javax.sql.DataSource
import kotlin.math.ceil

const val MAX_QUERY_RETRIES = 5
const val MIN_RETRY_DELAY = 200L

// Capped low on purpose: the database runs on a single thread, so a failing query that keeps
// retrying with a long backoff blocks every other query (status, search, logging) behind it.
// The old 300s cap could freeze the database thread for minutes on a single bad query.
const val MAX_RETRY_DELAY = 3_000L
private const val ACTION_UPDATE_CHUNK_SIZE = 900
const val MAX_EXTRA_DATA_BYTES = 65_535

object DatabaseManager {

    // These values are initialised late to allow the database to be created at server start,
    // which means the database file is located in the world folder and allows for per-world databases.
    private lateinit var database: Database
    val databaseType: String
        get() = database.dialect.name

    private val cache = DatabaseCacheService

    // Set during the shutdown drain: lets queued writes through even if the worlds are no longer
    // saveable (overworld() null / noSave true), so we don't stall until the drain timeout.
    @Volatile
    var bypassSaveStateWait: Boolean = false

    private var databaseContext = Dispatchers.IO + CoroutineName("ViaLogium Database")
    private val vialogiumLogger = object : SqlLogger {
        override fun log(context: StatementContext, transaction: Transaction) {
            // debug level: requires BOTH the logSQL config flag and a debug-enabled logger, so a
            // stray flag in production can't flood logs / stall the DB thread on I/O on its own.
            ViaLogium.logger.debug("SQL: ${context.expandArgs(transaction)}")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun setup(dataSource: DataSource) {
        database = Database.connect(dataSource)
        applySqlitePragmasIfNeeded(dataSource)
        databaseContext = newSingleThreadContext("viaLogium Database")
        if (config[DatabaseSpec.logSQL]) {
            logWarn(
                "logSQL is enabled: every SQL statement is logged on the database thread. " +
                    "This is for short-term debugging only — disable it in production.",
            )
        }
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

                    val mmapBytes = config[DatabaseSpec.sqliteMmapSizeMb].coerceAtLeast(0).toLong() * 1024L * 1024L
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

    fun ensureTables() = transaction {
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
        if (config[DatabaseSpec.updateSchema]) {
            listOf(
                "CREATE INDEX IF NOT EXISTS actions_rolled_back_idx ON actions (rolled_back)",
                "CREATE INDEX IF NOT EXISTS actions_xyz_idx ON actions (x, y, z)",
                "CREATE INDEX IF NOT EXISTS actions_time_idx ON actions (time)",
            ).forEach { ddl ->
                runCatching {
                    exec(ddl)
                }.onFailure {
                    logWarn("Schema update skipped: $ddl failed: ${it.message}")
                }
            }
        }
        logInfo("Tables created")
    }

    /**
     * Databases from before 1.1.0 have no `server` column. They were written by one server, so the
     * existing rows are assigned to this one.
     */
    private fun JdbcTransaction.addServerColumn() {
        val meta = (connection.connection as java.sql.Connection).metaData
        val columns = mutableSetOf<String>()
        for (table in listOf("actions", "ACTIONS")) {
            meta.getColumns(null, null, table, null).use { rs ->
                while (rs.next()) columns.add(rs.getString("COLUMN_NAME").lowercase())
            }
        }
        if (columns.isEmpty() || "server" in columns) return

        logInfo("Adding server column to actions, existing rows belong to '${ServerIdentity.id}'")
        exec("ALTER TABLE actions ADD COLUMN server VARCHAR($MAX_SERVER_ID_LENGTH) DEFAULT '' NOT NULL")
        Tables.Actions.update({ Tables.Actions.server eq "" }) { it[server] = ServerIdentity.id }
        exec("CREATE INDEX IF NOT EXISTS actions_server_idx ON actions (server)")
    }

    suspend fun setupCache() {
        execute { loadCaches() }
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
        if (config[DatabaseSpec.autoPurgeDays] > 0) {
            execute {
                logInfo("Purging actions older than ${config[DatabaseSpec.autoPurgeDays]} days")
                // Each server purges its own rows: servers sharing a database may keep them for different times.
                val cutoff = Instant.now().minus(config[DatabaseSpec.autoPurgeDays].toLong(), ChronoUnit.DAYS)
                val deleted = Tables.Actions.deleteWhere {
                    (timestamp lessEq cutoff) and (server eq ServerIdentity.id)
                }
                logInfo("Successfully purged $deleted actions")
            }
        }
    }

    suspend fun searchActions(params: ActionSearchParams, page: Int): SearchResults = execute {
        return@execute selectActionsSearch(params, page)
    }

    suspend fun countActions(params: ActionSearchParams): Long = execute {
        return@execute countActions(params)
    }

    suspend fun countAllActions(): Long = execute {
        return@execute Tables.Actions.selectAll().count()
    }

    suspend fun rollbackActions(params: ActionSearchParams): List<ActionType> = execute {
        val actions = selectRollback(params)
        val actionIds = actions.map { it.id }.toSet()
        rollbackActions(actionIds)
        return@execute actions
    }

    suspend fun rollbackActions(actionIds: Set<Int>) = execute {
        return@execute rollbackActions(actionIds)
    }

    suspend fun restoreActions(params: ActionSearchParams): List<ActionType> = execute {
        val actions = selectRestore(params)
        val actionIds = actions.map { it.id }.toSet()
        restoreActions(actionIds)
        return@execute actions
    }

    suspend fun restoreActions(actionIds: Set<Int>) = execute {
        return@execute restoreActions(actionIds)
    }

    suspend fun countRollbackActions(params: ActionSearchParams): Long = execute {
        return@execute Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false))
            .count()
    }

    suspend fun countRestoreActions(params: ActionSearchParams): Long = execute {
        return@execute Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true))
            .count()
    }

    suspend fun selectRollbackBatch(params: ActionSearchParams, limit: Int): List<ActionType> = execute {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false))
            .orderBy(Tables.Actions.id, SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
        return@execute getActionsFromQuery(query)
    }

    suspend fun selectRestoreBatch(params: ActionSearchParams, limit: Int): List<ActionType> = execute {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true))
            .orderBy(Tables.Actions.id, SortOrder.ASC)
            .limit(limit.coerceAtLeast(1))
        return@execute getActionsFromQuery(query)
    }

    suspend fun selectRollbackPreviewBatch(
        params: ActionSearchParams,
        beforeIdExclusive: Int?,
        limit: Int,
    ): List<ActionType> = execute {
        var conditions: Op<Boolean> = buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false)
        if (beforeIdExclusive != null) {
            conditions = conditions and (Tables.Actions.id lessEq (beforeIdExclusive - 1))
        }

        val query = Tables.Actions
            .selectAll()
            .where(conditions)
            .orderBy(Tables.Actions.id, SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))

        return@execute getActionsFromQuery(query)
    }

    suspend fun selectRestorePreviewBatch(
        params: ActionSearchParams,
        afterIdExclusive: Int?,
        limit: Int,
    ): List<ActionType> = execute {
        var conditions: Op<Boolean> = buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true)
        if (afterIdExclusive != null) {
            conditions = conditions and (Tables.Actions.id greaterEq (afterIdExclusive + 1))
        }

        val query = Tables.Actions
            .selectAll()
            .where(conditions)
            .orderBy(Tables.Actions.id, SortOrder.ASC)
            .limit(limit.coerceAtLeast(1))

        return@execute getActionsFromQuery(query)
    }

    suspend fun selectRollback(params: ActionSearchParams): List<ActionType> = execute {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq false))
            .orderBy(Tables.Actions.id, SortOrder.DESC)
        return@execute getActionsFromQuery(query)
    }

    suspend fun selectRestore(params: ActionSearchParams): List<ActionType> = execute {
        val query = Tables.Actions
            .selectAll()
            .where(buildQueryParams(params.localOnly()) and (Tables.Actions.rolledBack eq true))
            .orderBy(Tables.Actions.id, SortOrder.ASC)
        return@execute getActionsFromQuery(query)
    }

    /**
     * Positions ("world:x:y:z") of not-yet-rolled-back container block-breaks in [params]. Used to
     * pre-seed the rollback tracker so item-drop / item-pick-up of items that spilled from those
     * containers are skipped (their contents are restored from the block-break NBT snapshot instead,
     * avoiding duplication). A pre-scan is required because those item actions are newer than the
     * break and would otherwise be processed before it in id-DESC order.
     */
    suspend fun selectContainerBreakPositions(params: ActionSearchParams): Set<String> = execute {
        val blockBreakId = cache.actionIdentifierKeys["block-break"] ?: return@execute emptySet<String>()
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
        return@execute positions
    }

    suspend fun previewActions(params: ActionSearchParams, type: Preview.Type): List<ActionType> = execute {
        when (type) {
            Preview.Type.ROLLBACK -> return@execute selectRollback(params)
            Preview.Type.RESTORE -> return@execute selectRestore(params)
        }
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
            val typeSupplier = ActionRegistry.getType(
                actionIdentifierCache[action[Tables.Actions.actionIdentifier].value]!!,
            )
            if (typeSupplier == null) {
                logWarn("Unknown action type ${actionIdentifierCache[action[Tables.Actions.actionIdentifier].value]}")
                continue
            }

            val type = typeSupplier.get()
            type.id = action[Tables.Actions.id].value
            type.timestamp = action[Tables.Actions.timestamp]
            type.pos = BlockPos(action[Tables.Actions.x], action[Tables.Actions.y], action[Tables.Actions.z])
            type.world = worldCache[action[Tables.Actions.world].value]
            type.objectIdentifier = objectIdentifierCache[action[Tables.Actions.objectId].value]!!
            type.oldObjectIdentifier = objectIdentifierCache[action[Tables.Actions.oldObjectId].value]!!
            type.objectState = action[Tables.Actions.blockState]
            type.oldObjectState = action[Tables.Actions.oldBlockState]
            type.sourceName = sourceCache[action[Tables.Actions.sourceName].value]!!
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

    suspend fun logActionBatch(actions: List<ActionType>) {
        execute {
            insertActions(actions)
        }
    }

    suspend fun registerWorld(identifier: Identifier) = execute {
        insertWorld(identifier)
    }

    suspend fun registerActionType(id: String) = execute {
        insertActionType(id)
    }

    suspend fun logPlayer(uuid: UUID, name: String) = execute {
        insertOrUpdatePlayer(uuid, name)
    }

    suspend fun insertIdentifiers(identifiers: Collection<Identifier>) = execute {
        insertRegKeys(identifiers)
    }

    private suspend fun <T : Any?> execute(body: suspend Transaction.() -> T): T {
        if (!bypassSaveStateWait) {
            var warnedSavePause = false
            while (ViaLogium.server.overworld()?.noSave != false) {
                if (!warnedSavePause) {
                    logWarn("DB writes paused: vanilla save-off is active (/save-off? backup in progress?)")
                    warnedSavePause = true
                }
                delay(timeMillis = 1000)
            }
        }

        return newSuspendedTransaction(context = databaseContext, db = database) {
            maxAttempts = MAX_QUERY_RETRIES
            minRetryDelay = MIN_RETRY_DELAY
            maxRetryDelay = MAX_RETRY_DELAY

            if (ViaLogium.config[DatabaseSpec.logSQL]) {
                addLogger(vialogiumLogger)
            }
            body(this)
        }
    }

    suspend fun purgeActions(params: ActionSearchParams) {
        execute {
            purgeActions(params)
        }
    }

    suspend fun searchPlayers(players: Set<NameAndId>): List<PlayerResult> = execute {
        return@execute selectPlayers(players)
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

    private fun Transaction.insertActions(actions: List<ActionType>) {
        val (safe, oversized) = actions.partition {
            it.extraData == null || it.extraData!!.length <= MAX_EXTRA_DATA_BYTES
        }
        oversized.forEach { action ->
            logWarn(
                "Skipping action log: extra_data too large (${action.extraData!!.length} chars) " +
                    "for action ${action.identifier} at " +
                    "[${action.world} ${action.pos.x} ${action.pos.y} ${action.pos.z}] " +
                    "by ${action.sourceProfile?.name ?: action.sourceName}",
            )
        }
        if (safe.isEmpty()) return
        Tables.Actions.batchInsert(safe, shouldReturnGeneratedValues = false) { action ->
            this[Tables.Actions.actionIdentifier] = getOrCreateActionId(action.identifier)
            this[Tables.Actions.timestamp] = action.timestamp
            this[Tables.Actions.x] = action.pos.x
            this[Tables.Actions.y] = action.pos.y
            this[Tables.Actions.z] = action.pos.z
            this[Tables.Actions.objectId] = getOrCreateRegistryKeyId(action.objectIdentifier)
            this[Tables.Actions.oldObjectId] = getOrCreateRegistryKeyId(action.oldObjectIdentifier)
            this[Tables.Actions.world] = getOrCreateWorldId(
                action.world ?: ViaLogium.server.overworld().dimension()
                    .identifier(),
            )
            this[Tables.Actions.blockState] = action.objectState
            this[Tables.Actions.oldBlockState] = action.oldObjectState
            this[Tables.Actions.sourceName] = getOrCreateSourceId(action.sourceName)
            this[Tables.Actions.sourcePlayer] = action.sourceProfile?.let { getOrCreatePlayerId(it.id) }
            this[Tables.Actions.extraData] = action.extraData
            this[Tables.Actions.server] = ServerIdentity.id
        }
    }

    private fun Transaction.insertOrUpdatePlayer(uuid: UUID, name: String) {
        val player = Tables.Player.find { Tables.Players.playerId eq uuid }.firstOrNull()

        if (player != null) {
            player.lastJoin = Instant.now()
            player.playerName = name
            cache.playernameKeys.forcePut(name, player.id.value)
        } else {
            val entity = Tables.Player.new {
                this.playerId = uuid
                this.playerName = name
            }
            cache.playerKeys[uuid] = entity.id.value
            cache.playernameKeys.forcePut(name, entity.id.value)
        }
    }

    private fun Transaction.selectActionsSearch(params: ActionSearchParams, page: Int): SearchResults {
        val actions = mutableListOf<ActionType>()

        var query = Tables.Actions
            .selectAll()
            .andWhere { buildQueryParams(params) }

        val totalActions: Long = countActions(params)
        if (totalActions == 0L) return SearchResults(actions, params, page, 0)

        query = query.orderBy(Tables.Actions.id, SortOrder.DESC)
        query = query.limit(config[SearchSpec.pageSize]).offset(
            (config[SearchSpec.pageSize] * (page - 1)).toLong(),
        ) // TODO better pagination without offset - probably doesn't matter as most people stay on first few pages

        actions.addAll(getActionsFromQuery(query))

        val totalPages = ceil(totalActions.toDouble() / config[SearchSpec.pageSize].toDouble()).toInt()

        return SearchResults(actions, params, page, totalPages)
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
            cache.put(obj, it)
        }
    }

    private fun <T> getOrCreateObjectId(
        obj: T,
        cache: BiMap<T, Int>,
        entity: IntEntityClass<*>,
        table: IntIdTable,
        column: Column<T>,
    ): Int = getOrCreateObjectId(obj, Function.identity(), cache, entity, table, column)

    private fun <T, S> getOrCreateObjectId(
        obj: T,
        mapper: Function<T, S>,
        cache: BiMap<T, Int>,
        entity: IntEntityClass<*>,
        table: IntIdTable,
        column: Column<S>,
    ): Int {
        getObjectId(obj, mapper, cache, entity, column)?.let { return it }

        return entity[
            table.insertAndGetId {
                it[column] = mapper.apply(obj)
            },
        ].id.value.also { cache.put(obj!!, it) }
    }

    private fun getOrCreatePlayerId(playerId: UUID): Int =
        getOrCreateObjectId(playerId, cache.playerKeys, Tables.Player, Tables.Players, Tables.Players.playerId)

    private fun getOrCreateSourceId(source: String): Int =
        getOrCreateObjectId(source, cache.sourceKeys, Tables.Source, Tables.Sources, Tables.Sources.name)

    private fun getOrCreateActionId(actionTypeId: String): Int = getOrCreateObjectId(
        actionTypeId,
        cache.actionIdentifierKeys,
        Tables.ActionIdentifier,
        Tables.ActionIdentifiers,
        Tables.ActionIdentifiers.actionIdentifier,
    )

    private fun getOrCreateRegistryKeyId(identifier: Identifier): Int = getOrCreateObjectId(
        identifier,
        Identifier::toString,
        cache.objectIdentifierKeys,
        Tables.ObjectIdentifier,
        Tables.ObjectIdentifiers,
        Tables.ObjectIdentifiers.identifier,
    )

    private fun getOrCreateWorldId(identifier: Identifier): Int = getOrCreateObjectId(
        identifier,
        Identifier::toString,
        cache.worldIdentifierKeys,
        Tables.World,
        Tables.Worlds,
        Tables.Worlds.identifier,
    )

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
