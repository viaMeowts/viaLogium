package com.viameowts.vialogium.actions

import com.viameowts.vialogium.actionutils.Preview
import com.viameowts.vialogium.config.ActionsSpec
import com.viameowts.vialogium.config.config
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import java.time.Instant
import kotlin.time.ExperimentalTime

interface ActionType {
    var id: Int
    val identifier: String
    var timestamp: Instant
    var pos: BlockPos
    var world: Identifier?
    var objectIdentifier: Identifier
    var oldObjectIdentifier: Identifier
    var objectState: String?
    var oldObjectState: String?
    var sourceName: String
    var sourceProfile: NameAndId?
    var extraData: String?
    var rolledBack: Boolean

    /**
     * Stores [tag] as [extraData] but defers the (potentially expensive) NBT -> SNBT text encoding
     * until [extraData] is first read. The snapshot itself must still be built on the main thread
     * (it reads live game state); only the string encoding is deferred, so it can run on the
     * database thread at insert time instead of on the server tick. Passing null clears extraData.
     *
     * Default implementation encodes eagerly; [AbstractActionType] overrides it to defer.
     */
    fun deferExtraData(tag: CompoundTag?) {
        extraData = tag?.toString()
    }

    /**
     * Mark this action's source as creative. Default implementation re-wraps the extraData string;
     * [AbstractActionType] overrides it to wrap the still-pending NBT tag instead, avoiding a lossy
     * SNBT re-parse that can drop complex payloads (e.g. entity data).
     */
    fun flagCreative() {
        extraData = com.viameowts.vialogium.utility.markCreative(extraData)
    }

    fun rollback(server: MinecraftServer): Boolean
    fun restore(server: MinecraftServer): Boolean
    fun previewRollback(preview: Preview, player: ServerPlayer)
    fun previewRestore(preview: Preview, player: ServerPlayer)
    fun getTranslationType(): String

    @ExperimentalTime
    fun getMessage(source: CommandSourceStack): Component

    fun isBlacklisted() = config[ActionsSpec.typeBlacklist].contains(identifier) ||
        config[ActionsSpec.objectBlacklist].contains(objectIdentifier) ||
        config[ActionsSpec.objectBlacklist].contains(oldObjectIdentifier) ||
        config[ActionsSpec.sourceBlacklist].contains(sourceName) ||
        config[ActionsSpec.sourceBlacklist].contains("@${sourceProfile?.name}") ||
        config[ActionsSpec.worldBlacklist].contains(world)
}
