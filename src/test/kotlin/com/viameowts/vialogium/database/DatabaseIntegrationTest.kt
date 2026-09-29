package com.viameowts.vialogium.database

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.actions.BlockBreakActionType
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.registry.ActionRegistry
import com.viameowts.vialogium.utility.Negatable
import com.viameowts.vialogium.utility.Sources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.players.NameAndId
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Runs the database layer against a real database: SQLite by default, or the one named by
 * `VIALOGIUM_TEST_DB` (POSTGRESQL, MARIADB, MYSQL, H2) with `VIALOGIUM_TEST_URL`, `_USER` and
 * `_PASSWORD`. The tables are dropped first, so point it at a throwaway database only.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DatabaseIntegrationTest {
    companion object {
        private val backend = Databases.valueOf(System.getenv("VIALOGIUM_TEST_DB")?.uppercase() ?: "SQLITE")
        private val serverDatabase = backend != Databases.SQLITE && backend != Databases.H2
        private lateinit var testDb: Database
        private val player = NameAndId(UUID.fromString("5f6e3b1a-0c1d-4e2f-9a3b-7c8d9e0f1a2b"), "Tester")

        @JvmStatic
        @BeforeAll
        fun setUp() {
            config[DatabaseExtensionSpec.database] = backend
            System.getenv("VIALOGIUM_TEST_URL")?.let { config[DatabaseExtensionSpec.url] = it }
            System.getenv("VIALOGIUM_TEST_USER")?.let { config[DatabaseExtensionSpec.userName] = it }
            System.getenv("VIALOGIUM_TEST_PASSWORD")?.let { config[DatabaseExtensionSpec.password] = it }
            config[DatabaseExtensionSpec.maxPoolSize] = 4
            config[DatabaseSpec.location] = Files.createTempDirectory("vialogium-test").toString()
            config[DatabaseSpec.serverId] = "test"
            ViaLogium.config = config
            // No Minecraft server here: file databases must not wait for the world's save state.
            DatabaseManager.bypassSaveStateWait = true

            val dataSource = ViaLogiumDatabaseProvider.getDataSource()
            testDb = Database.connect(dataSource)
            transaction(testDb) {
                SchemaUtils.drop(
                    Tables.Actions,
                    Tables.Players,
                    Tables.ActionIdentifiers,
                    Tables.ObjectIdentifiers,
                    Tables.Sources,
                    Tables.Worlds,
                )
            }
            DatabaseManager.setup(dataSource)
            DatabaseManager.ensureTables()
            ActionRegistry.registerDefaultTypes()
            runBlocking {
                DatabaseManager.insertIdentifiers(listOf(id("minecraft:stone"), id("minecraft:air")))
                DatabaseManager.setupCache()
                DatabaseManager.logPlayer(player.id(), player.name())
            }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            DatabaseManager.close()
        }

        private fun sql(statement: String) {
            transaction(testDb) { exec(statement) }
        }

        private fun id(value: String) = Identifier.parse(value)

        private fun breakAction(
            world: String,
            x: Int,
            timestamp: Instant = Instant.now(),
            block: String = "minecraft:stone",
            source: String = Sources.PLAYER,
        ): ActionType = BlockBreakActionType().apply {
            this.timestamp = timestamp
            pos = BlockPos(x, 64, 0)
            this.world = id(world)
            oldObjectIdentifier = id(block)
            objectIdentifier = id("minecraft:air")
            oldObjectState = "{Name:\"$block\"}"
            objectState = "{Name:\"minecraft:air\"}"
            sourceName = source
            sourceProfile = if (source == Sources.PLAYER) player else null
        }

        private fun inWorld(world: String, block: ActionSearchParams.Builder.() -> Unit = {}) =
            ActionSearchParams.build {
                bounds = ActionSearchParams.GLOBAL
                worlds = mutableSetOf(Negatable.allow(id(world)))
                block()
            }
    }

    @Test
    @Order(1)
    fun `schema migration can run again on an existing database`() {
        DatabaseManager.ensureTables()
        DatabaseManager.ensureTables()
    }

    @Test
    @Order(2)
    fun `logged actions are found with all their fields`() = runBlocking {
        val world = "vialogium_test:search"
        DatabaseManager.logActionBatch((0 until 300).map { breakAction(world, it) })

        val params = inWorld(world)
        assertEquals(300L, DatabaseManager.countActions(params))
        val page = DatabaseManager.searchActions(params, 1)
        assertTrue(page.actions.isNotEmpty())
        val action = page.actions.first()
        assertEquals("block-break", action.identifier)
        assertEquals(id(world), action.world)
        assertEquals(id("minecraft:stone"), action.oldObjectIdentifier)
        assertEquals(player.name(), action.sourceProfile?.name())
        assertEquals("test", action.serverId)
        assertEquals(64, action.pos.y)
    }

    @Test
    @Order(3)
    fun `new worlds, blocks and sources are registered by the batch itself`() = runBlocking {
        val world = "vialogium_test:new_ids"
        DatabaseManager.logActionBatch(
            listOf(
                breakAction(world, 1, block = "vialogium_test:custom_block", source = "vialogium_test_src"),
                breakAction(world, 2, block = "vialogium_test:other_block"),
            ),
        )
        val found = DatabaseManager.searchActions(inWorld(world), 1).actions
        assertEquals(
            setOf(id("vialogium_test:custom_block"), id("vialogium_test:other_block")),
            found.map { it.oldObjectIdentifier }.toSet(),
        )
        assertTrue(found.any { it.sourceName == "vialogium_test_src" && it.sourceProfile == null })
    }

    @Test
    @Order(4)
    fun `rollback and restore flip only the matching rows`() = runBlocking {
        val world = "vialogium_test:rollback"
        DatabaseManager.logActionBatch((0 until 50).map { breakAction(world, it) })
        val inside = inWorld(world) { bounds = BoundingBox(0, 0, -1, 19, 100, 1) }

        assertEquals(20, DatabaseManager.rollbackActions(inside).size)
        assertEquals(0L, DatabaseManager.countRollbackActions(inside))
        assertEquals(20L, DatabaseManager.countRestoreActions(inside))
        assertEquals(30L, DatabaseManager.countRollbackActions(inWorld(world)))

        assertEquals(20, DatabaseManager.restoreActions(inside).size)
        assertEquals(0L, DatabaseManager.countRestoreActions(inWorld(world)))
    }

    @Test
    @Order(5)
    fun `the queue writes everything on shutdown drain`() = runBlocking {
        val world = "vialogium_test:queue"
        repeat(2_500) { assertTrue(ActionQueueService.addToQueue(breakAction(world, it))) }
        ActionQueueService.drainAll()

        assertEquals(0, ActionQueueService.size)
        assertEquals(2_500L, DatabaseManager.countActions(inWorld(world)))
    }

    @Test
    @Order(6)
    fun `a row the database rejects is dropped alone`() = runBlocking {
        assumeTrue(serverDatabase, "SQLite and H2 cannot add a CHECK constraint to an existing table")
        val world = "vialogium_test:bad_rows"
        sql("ALTER TABLE actions ADD CONSTRAINT vialogium_test_bad_x CHECK (x <> 1000013)")

        val droppedBefore = ActionQueueService.dropped
        (0 until 100).forEach { ActionQueueService.addToQueue(breakAction(world, if (it % 10 == 3) 1_000_013 else it)) }
        ActionQueueService.drainAll()

        assertEquals(90L, DatabaseManager.countActions(inWorld(world)))
        assertEquals(10L, ActionQueueService.dropped - droppedBefore)
    }

    @Test
    @Order(7)
    fun `reads run while writes are going on`() = runBlocking {
        val world = "vialogium_test:concurrent"
        val jobs = (0 until 10).map { batch ->
            async { DatabaseManager.logActionBatch((0 until 200).map { breakAction(world, batch * 200 + it) }) }
        } + (0 until 10).map {
            async { DatabaseManager.searchActions(inWorld(world), 1) }
        }
        jobs.awaitAll()
        assertEquals(2_000L, DatabaseManager.countActions(inWorld(world)))
    }

    @Test
    @Order(8)
    fun `purge deletes large ranges in chunks`() = runBlocking {
        val world = "vialogium_test:purge"
        val old = Instant.parse("2001-01-01T00:00:00Z")
        (0 until 12_000).chunked(2_000).forEach { part ->
            DatabaseManager.logActionBatch(part.map { breakAction(world, it, timestamp = old) })
        }
        DatabaseManager.logActionBatch((0 until 5).map { breakAction(world, it) })

        val purged = DatabaseManager.purgeActions(inWorld(world) { before = old.plus(1, ChronoUnit.DAYS) })
        assertEquals(12_000, purged)
        assertEquals(5L, DatabaseManager.countActions(inWorld(world)))
    }

    @Test
    @Order(9)
    fun `auto purge removes only old rows of this server`() = runBlocking {
        val world = "vialogium_test:auto_purge"
        DatabaseManager.logActionBatch(
            (0 until 10).map { breakAction(world, it, timestamp = Instant.parse("2001-01-01T00:00:00Z")) },
        )
        DatabaseManager.logActionBatch((0 until 3).map { breakAction(world, it) })
        sql("UPDATE actions SET server = 'other' WHERE x = 0")

        config[DatabaseSpec.autoPurgeDays] = 30
        DatabaseManager.autoPurge()

        assertEquals(2L, DatabaseManager.countActions(inWorld(world)))
        val all = inWorld(world) { servers = mutableSetOf(Negatable.allow("all")) }
        assertEquals(4L, DatabaseManager.countActions(all))
    }

    @Test
    @Order(10)
    fun `status row count works`() = runBlocking {
        val (count, _) = DatabaseManager.estimateAllActions()
        assertTrue(count >= 0)
        assertTrue(DatabaseManager.countAllActions() > 0)
    }
}
