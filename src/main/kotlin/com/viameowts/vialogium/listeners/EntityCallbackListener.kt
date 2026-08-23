package com.viameowts.vialogium.listeners

import com.viameowts.vialogium.actionutils.ActionFactory
import com.viameowts.vialogium.callbacks.EntityKillCallback
import com.viameowts.vialogium.callbacks.EntityModifyCallback
import com.viameowts.vialogium.callbacks.TotemPopCallback
import com.viameowts.vialogium.database.ActionQueueService
import com.viameowts.vialogium.utility.Sources
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

fun registerEntityListeners() {
    EntityKillCallback.EVENT.register(::onKill)
    EntityModifyCallback.EVENT.register(::onModify)
    TotemPopCallback.EVENT.register(::onTotemPop)
}

private fun onKill(world: Level, pos: BlockPos, entity: Entity, source: DamageSource) {
    ActionQueueService.addToQueue(
        ActionFactory.entityKillAction(world, pos, entity, source),
    )
}

fun onKill(world: Level, pos: BlockPos, entity: Entity, source: String) {
    ActionQueueService.addToQueue(
        ActionFactory.entityKillAction(world, pos, entity, source),
    )
}

private fun onModify(
    world: Level,
    pos: BlockPos,
    oldEntityTags: CompoundTag,
    entity: Entity,
    itemStack: ItemStack?,
    entityActor: Entity?,
    sourceType: String,
) {
    if (entity is ItemFrame && itemStack != null) {
        when (sourceType) {
            Sources.EQUIP -> ActionQueueService.addToQueue(
                ActionFactory.itemFrameInsertAction(world, pos, oldEntityTags, entity, itemStack, entityActor),
            )

            Sources.REMOVE -> ActionQueueService.addToQueue(
                ActionFactory.itemFrameRemoveAction(world, pos, oldEntityTags, entity, itemStack, entityActor),
            )

            else -> ActionQueueService.addToQueue(
                ActionFactory.entityChangeAction(world, pos, oldEntityTags, entity, itemStack, entityActor, sourceType),
            )
        }
        return
    }

    ActionQueueService.addToQueue(
        ActionFactory.entityChangeAction(world, pos, oldEntityTags, entity, itemStack, entityActor, sourceType),
    )
}

private fun onTotemPop(world: Level, entity: LivingEntity, source: DamageSource) {
    ActionQueueService.addToQueue(ActionFactory.totemPopAction(world, entity, source))
}
