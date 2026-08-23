package com.viameowts.vialogium

import com.uchuhimo.konf.Config
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.actionutils.Preview
import com.viameowts.vialogium.api.ExtensionManager
import com.viameowts.vialogium.api.ViaLogiumApi
import com.viameowts.vialogium.api.ViaLogiumApiImpl
import com.viameowts.vialogium.commands.registerCommands
import com.viameowts.vialogium.config.CONFIG_PATH
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.database.ActionQueueService
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.database.ViaLogiumDatabaseProvider
import com.viameowts.vialogium.integration.viapanel.ViaLogiumPanelProvider
import com.viameowts.vialogium.listeners.registerBlockListeners
import com.viameowts.vialogium.listeners.registerEntityListeners
import com.viameowts.vialogium.listeners.registerPlayerListeners
import com.viameowts.vialogium.listeners.registerWorldEventListeners
import com.viameowts.vialogium.network.Networking
import com.viameowts.vialogium.network.packet.action.ActionS2CPacket
import com.viameowts.vialogium.network.packet.handshake.HandshakeS2CPacket
import com.viameowts.vialogium.network.packet.response.ResponseS2CPacket
import com.viameowts.vialogium.registry.ActionRegistry
import com.viameowts.viapanel.api.ViaPanelApi
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import com.viameowts.vialogium.config.config as realConfig

object ViaLogium : DedicatedServerModInitializer, CoroutineScope {
    const val MOD_ID = "vialogium"
    val DEFAULT_DATABASE = SQLiteDialect.dialectName

    @JvmStatic
    val api: ViaLogiumApi = ViaLogiumApiImpl

    val logger: Logger = LogManager.getLogger("viaLogium")
    lateinit var config: Config
    lateinit var server: MinecraftServer
    val searchCache = ConcurrentHashMap<String, ActionSearchParams>()

    @JvmField // Required for mixin access
    val previewCache = ConcurrentHashMap<UUID, Preview>()

    override val coroutineContext: CoroutineContext = Dispatchers.Default + CoroutineName("ViaLogium")

    override fun onInitializeServer() {
        val version = FabricLoader.getInstance().getModContainer(MOD_ID).get().metadata.version
        logInfo("Initializing viaLogium ${version.friendlyString}")

        val configDir = FabricLoader.getInstance().configDir
        val oldConfigPath = configDir.resolve("vialogium.toml")
        val newConfigPath = configDir.resolve(CONFIG_PATH)

        if (!Files.exists(newConfigPath) && Files.exists(oldConfigPath)) {
            logInfo("Migrating legacy vialogium.toml to $CONFIG_PATH")
            Files.copy(oldConfigPath, newConfigPath, StandardCopyOption.REPLACE_EXISTING)
        }

        if (!Files.exists(newConfigPath)) {
            logInfo("No config file, Creating")
            Files.copy(
                FabricLoader.getInstance().getModContainer(MOD_ID).get().findPath(CONFIG_PATH).get(),
                newConfigPath,
            )
        }
        realConfig.validateRequired()
        config = realConfig

        ViaPanelApi.register(ViaLogiumPanelProvider)

        ServerLifecycleEvents.SERVER_STARTING.register(::serverStarting)
        // Drain on STOPPING (worlds still loaded) rather than STOPPED, where overworld() may be
        // gone and the save-state guard in DatabaseManager.execute would stall the drain.
        ServerLifecycleEvents.SERVER_STOPPING.register(::serverStopping)
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> registerCommands(dispatcher) }
        PayloadTypeRegistry.clientboundPlay().register(ActionS2CPacket.ID, ActionS2CPacket.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(HandshakeS2CPacket.ID, HandshakeS2CPacket.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ResponseS2CPacket.ID, ResponseS2CPacket.CODEC)
    }

    private fun serverStarting(server: MinecraftServer) {
        this.server = server
        ExtensionManager.serverStarting(server)
        try {
            val dataSource = ExtensionManager.getDataSource() ?: ViaLogiumDatabaseProvider.getDataSource()
            DatabaseManager.setup(dataSource)
            DatabaseManager.ensureTables()
        } catch (t: Throwable) {
            logFatal("Unable to initialize database. viaLogium will not start.")
            throw IllegalStateException("Database startup failed", t)
        }

        ActionRegistry.registerDefaultTypes()
        initListeners()
        Networking

        ViaLogium.launch {
            val idSet = setOf<Identifier>()
                .plus(BuiltInRegistries.BLOCK.keySet())
                .plus(BuiltInRegistries.ITEM.keySet())
                .plus(BuiltInRegistries.ENTITY_TYPE.keySet())

            logInfo("Inserting ${idSet.size} registry keys into the database...")
            DatabaseManager.insertIdentifiers(idSet)
            logInfo("Registry insert complete")

            DatabaseManager.setupCache()
            DatabaseManager.autoPurge()
        }.invokeOnCompletion {
            ActionQueueService.start()
        }
    }

    private fun serverStopping(server: MinecraftServer) {
        // Let queued writes through even if saving is disabled during shutdown, so the drain can
        // actually complete instead of spinning on the save-state guard until the timeout.
        DatabaseManager.bypassSaveStateWait = true
        runBlocking {
            try {
                withTimeout(config[DatabaseSpec.queueTimeoutMin].minutes) {
                    ViaLogium.launch {
                        while (ActionQueueService.size > 0) {
                            logInfo(
                                "Database is still busy. If you exit now data WILL be lost. " +
                                    "Actions in queue: ${ActionQueueService.size}",
                            )

                            delay(config[DatabaseSpec.queueCheckDelaySec].seconds)
                        }
                    }
                    ActionQueueService.drainAll()
                    logInfo("Successfully drained database queue")
                }
            } catch (e: TimeoutCancellationException) {
                logWarn(
                    "Database drain timed out. ${ActionQueueService.size} actions still in queue. Data may be lost.",
                )
            }
        }
    }

    private fun initListeners() {
        registerWorldEventListeners()
        registerPlayerListeners()
        registerBlockListeners()
        registerEntityListeners()
    }

    fun identifier(path: String) = Identifier.fromNamespaceAndPath(MOD_ID, path)
}

private const val LOG_PREFIX = "[viaLogium]"
private fun prefixed(message: String) = "$LOG_PREFIX $message"

fun logDebug(message: String) = ViaLogium.logger.debug(prefixed(message))
fun logInfo(message: String) = ViaLogium.logger.info(prefixed(message))
fun logWarn(message: String) = ViaLogium.logger.warn(prefixed(message))
fun logWarn(message: String, throwable: Throwable) = ViaLogium.logger.warn(prefixed(message), throwable)
fun logError(message: String) = ViaLogium.logger.error(prefixed(message))
fun logError(message: String, throwable: Throwable) = ViaLogium.logger.error(prefixed(message), throwable)
fun logFatal(message: String) = ViaLogium.logger.warn(prefixed(message))
