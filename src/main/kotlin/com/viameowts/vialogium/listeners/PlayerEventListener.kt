package com.viameowts.vialogium.listeners

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionFactory
import com.viameowts.vialogium.callbacks.EntityDismountCallback
import com.viameowts.vialogium.callbacks.EntityMountCallback
import com.viameowts.vialogium.callbacks.ItemDropCallback
import com.viameowts.vialogium.callbacks.ItemPickUpCallback
import com.viameowts.vialogium.database.ActionQueueService
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.network.Networking.disableNetworking
import com.viameowts.vialogium.utility.inspectBlock
import com.viameowts.vialogium.utility.isInspecting
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.PacketSender
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

private const val CONNECTION_LOG_PERMISSION = "vialogium.logging.connection"

fun registerPlayerListeners() {
    ServerPlayConnectionEvents.JOIN.register(::onJoin)
    ServerPlayConnectionEvents.DISCONNECT.register(::onLeave)
    AttackBlockCallback.EVENT.register(::onBlockAttack)
    UseBlockCallback.EVENT.register(::onUseBlock)
    ItemPickUpCallback.EVENT.register(::onItemPickUp)
    ItemDropCallback.EVENT.register(::onItemDrop)
    EntityMountCallback.EVENT.register(::onEntityMount)
    EntityDismountCallback.EVENT.register(::onEntityDismount)
}

fun onLeave(handler: ServerGamePacketListenerImpl, server: MinecraftServer) {
    val player = handler.player
    if (Permissions.check(player, CONNECTION_LOG_PERMISSION, 0)) {
        ActionQueueService.addToQueue(ActionFactory.playerLeaveAction(player))
    }
    // Avoid leaking per-player state for players who disconnect with a pending search/preview.
    ViaLogium.searchCache.remove(player.createCommandSourceStack().textName)
    ViaLogium.previewCache.remove(player.uuid)
    player.disableNetworking()
}

private fun onUseBlock(
    player: Player,
    world: Level,
    hand: InteractionHand,
    blockHitResult: BlockHitResult,
): InteractionResult {
    if (player is ServerPlayer && player.isInspecting() && hand == InteractionHand.MAIN_HAND) {
        player.createCommandSourceStack().inspectBlock(blockHitResult.blockPos.relative(blockHitResult.direction))
        return InteractionResult.SUCCESS
    }
    return InteractionResult.PASS
}

private fun onBlockAttack(
    player: Player,
    world: Level,
    hand: InteractionHand,
    pos: BlockPos,
    direction: Direction,
): InteractionResult {
    if (player is ServerPlayer && player.isInspecting()) {
        player.createCommandSourceStack().inspectBlock(pos)
        return InteractionResult.SUCCESS
    }
    return InteractionResult.PASS
}

private fun onJoin(networkHandler: ServerGamePacketListenerImpl, packetSender: PacketSender, server: MinecraftServer) {
    ViaLogium.launch {
        DatabaseManager.logPlayer(networkHandler.player.uuid, networkHandler.player.scoreboardName)

        if (Permissions.check(networkHandler.player, CONNECTION_LOG_PERMISSION, 0)) {
            ActionQueueService.addToQueue(ActionFactory.playerJoinAction(networkHandler.player))
        }
    }
}

private fun onBlockPlace(
    world: Level,
    player: Player,
    pos: BlockPos,
    state: BlockState,
    context: BlockPlaceContext?,
    blockEntity: BlockEntity?,
) {
    ActionQueueService.addToQueue(
        ActionFactory.blockPlaceAction(
            world,
            pos,
            state,
            player,
            blockEntity,
        ),
    )
}

private fun onItemPickUp(entity: ItemEntity, player: Player) {
    ActionQueueService.addToQueue(ActionFactory.itemPickUpAction(entity, player))
}

private fun onItemDrop(entity: ItemEntity, playerOrGolem: LivingEntity) {
    ActionQueueService.addToQueue(ActionFactory.itemDropAction(entity, playerOrGolem))
}

private fun onEntityMount(entity: Entity, playerEntity: Player) {
    ActionQueueService.addToQueue(ActionFactory.entityMountAction(entity, playerEntity))
}

private fun onEntityDismount(entity: Entity, playerEntity: Player) {
    ActionQueueService.addToQueue(ActionFactory.entityDismountAction(entity, playerEntity))
}
