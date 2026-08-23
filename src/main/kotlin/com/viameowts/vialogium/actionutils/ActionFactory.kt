package com.viameowts.vialogium.actionutils

import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.actions.BlockBreakActionType
import com.viameowts.vialogium.actions.BlockChangeActionType
import com.viameowts.vialogium.actions.BlockPlaceActionType
import com.viameowts.vialogium.actions.EntityChangeActionType
import com.viameowts.vialogium.actions.EntityDismountActionType
import com.viameowts.vialogium.actions.EntityKillActionType
import com.viameowts.vialogium.actions.EntityMountActionType
import com.viameowts.vialogium.actions.ItemDropActionType
import com.viameowts.vialogium.actions.ItemFrameInsertActionType
import com.viameowts.vialogium.actions.ItemFrameRemoveActionType
import com.viameowts.vialogium.actions.ItemInsertActionType
import com.viameowts.vialogium.actions.ItemPickUpActionType
import com.viameowts.vialogium.actions.ItemRemoveActionType
import com.viameowts.vialogium.actions.PlayerJoinActionType
import com.viameowts.vialogium.actions.PlayerKillActionType
import com.viameowts.vialogium.actions.PlayerLeaveActionType
import com.viameowts.vialogium.actions.TotemPopActionType
import com.viameowts.vialogium.actions.VillagerTradeActionType
import com.viameowts.vialogium.utility.NbtUtils
import com.viameowts.vialogium.utility.NbtUtils.createNbt
import com.viameowts.vialogium.utility.Sources
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.golem.CopperGolem
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

object ActionFactory {
    /**
     * ThreadLocal that allows mixins to override the entity NBT captured
     * during kill actions. Used by ArmorStandMixin to capture NBT before
     * brokenByDamage() drops equipment items.
     * The value is consumed once and then cleared.
     */
    @JvmField
    val overrideEntityKillNbt: ThreadLocal<CompoundTag> = ThreadLocal()

    private fun markCreativeSource(action: ActionType, player: Player?) {
        if (player != null && player.abilities.instabuild) {
            // Wraps the still-pending NBT tag at tag level (no lossy SNBT re-parse) when available.
            action.flagCreative()
        }
    }

    fun blockBreakAction(
        world: Level,
        pos: BlockPos,
        state: BlockState,
        source: String,
        entity: BlockEntity? = null,
    ): BlockBreakActionType {
        val action = BlockBreakActionType()
        setBlockData(action, pos, world, Blocks.AIR.defaultBlockState(), state, source, entity)

        return action
    }

    fun blockBreakAction(
        world: Level,
        pos: BlockPos,
        state: BlockState,
        player: Player,
        entity: BlockEntity? = null,
        source: String = Sources.PLAYER,
    ): BlockChangeActionType {
        val action = blockBreakAction(world, pos, state, source, entity)
        action.sourceProfile = player.nameAndId()
        markCreativeSource(action, player)

        return action
    }

    fun blockPlaceAction(
        world: Level,
        pos: BlockPos,
        state: BlockState,
        source: String,
        entity: BlockEntity? = null,
    ): BlockChangeActionType {
        val action = BlockPlaceActionType()
        setBlockData(action, pos, world, state, Blocks.AIR.defaultBlockState(), source, entity)

        return action
    }

    fun blockPlaceAction(
        world: Level,
        pos: BlockPos,
        state: BlockState,
        player: Player,
        entity: BlockEntity? = null,
        source: String = Sources.PLAYER,
    ): BlockChangeActionType {
        val action = blockPlaceAction(world, pos, state, source, entity)
        action.sourceProfile = player.nameAndId()
        markCreativeSource(action, player)

        return action
    }

    private fun setBlockData(
        action: ActionType,
        pos: BlockPos,
        world: Level,
        state: BlockState,
        oldState: BlockState,
        source: String,
        entity: BlockEntity? = null,
    ) {
        action.pos = pos
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.BLOCK.getKey(state.block)
        action.oldObjectIdentifier = BuiltInRegistries.BLOCK.getKey(oldState.block)
        action.objectState = NbtUtils.blockStateToProperties(state)?.toString()
        action.oldObjectState = NbtUtils.blockStateToProperties(oldState)?.toString()
        action.sourceName = source
        // Snapshot is built here on the main thread; SNBT encoding is deferred to the DB thread.
        action.deferExtraData(entity?.saveWithoutMetadata(world.registryAccess()))
    }

    fun itemInsertAction(world: Level, stack: ItemStack, pos: BlockPos, source: String): ItemInsertActionType {
        val action = ItemInsertActionType()
        setItemData(action, pos, world, stack, source)

        return action
    }

    fun itemInsertAction(world: Level, stack: ItemStack, pos: BlockPos, source: LivingEntity): ItemInsertActionType {
        val action = ItemInsertActionType()
        var sourceType = Sources.UNKNOWN
        if (source is Player) {
            sourceType = Sources.PLAYER
            action.sourceProfile = source.nameAndId()
            markCreativeSource(action, source)
        } else if (source is CopperGolem) {
            sourceType = Sources.COPPER_GOLEM
            action.sourceName = sourceType
        }
        setItemData(action, pos, world, stack, sourceType)

        return action
    }

    fun itemRemoveAction(world: Level, stack: ItemStack, pos: BlockPos, source: String): ItemRemoveActionType {
        val action = ItemRemoveActionType()
        setItemData(action, pos, world, stack, source)

        return action
    }

    fun itemRemoveAction(world: Level, stack: ItemStack, pos: BlockPos, source: LivingEntity): ItemRemoveActionType {
        val action = ItemRemoveActionType()
        var sourceType = Sources.UNKNOWN
        if (source is Player) {
            sourceType = Sources.PLAYER
            action.sourceProfile = source.nameAndId()
            markCreativeSource(action, source)
        } else if (source is CopperGolem) {
            sourceType = Sources.COPPER_GOLEM
            action.sourceName = sourceType
        }
        setItemData(action, pos, world, stack, sourceType)

        return action
    }

    fun itemPickUpAction(entity: ItemEntity, source: Player): ItemPickUpActionType {
        val action = ItemPickUpActionType()

        setItemData(action, entity.blockPosition(), entity.level(), entity.item, Sources.PLAYER)

        action.oldObjectState = entity.createNbt().toString()
        action.sourceProfile = source.nameAndId()
        markCreativeSource(action, source)

        return action
    }

    fun itemDropAction(entity: ItemEntity, source: LivingEntity): ItemDropActionType {
        val action = ItemDropActionType()

        setItemData(action, entity.blockPosition(), entity.level(), entity.item, Sources.PLAYER)

        action.objectState = entity.createNbt().toString()
        if (source is Player) {
            action.sourceProfile = source.nameAndId()
            markCreativeSource(action, source)
        } else if (source is CopperGolem) {
            action.sourceName = Sources.COPPER_GOLEM
        }

        return action
    }

    fun blockChangeAction(
        world: Level,
        pos: BlockPos,
        oldState: BlockState,
        newState: BlockState,
        oldBlockEntity: BlockEntity?,
        source: String,
        player: Player?,
    ): ActionType {
        val action = BlockChangeActionType()
        setBlockData(action, pos, world, newState, oldState, source, oldBlockEntity)
        action.sourceProfile = player?.nameAndId()
        markCreativeSource(action, player)
        return action
    }

    private fun setItemData(action: ActionType, pos: BlockPos, world: Level, stack: ItemStack, source: String) {
        action.pos = pos
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ITEM.getKey(stack.item)
        action.sourceName = source
        if (!stack.isEmpty) {
            action.deferExtraData(stack.createNbt(world.registryAccess()))
        }
    }

    fun entityKillAction(world: Level, pos: BlockPos, entity: Entity, cause: DamageSource): EntityKillActionType {
        val killer = cause.entity
        val action = if (killer is Player && entity is Player) {
            PlayerKillActionType()
        } else {
            EntityKillActionType()
        }

        when {
            killer is Player -> {
                setEntityData(action, pos, world, entity, Sources.PLAYER)
                action.sourceProfile = killer.nameAndId()
                markCreativeSource(action, killer)
            }

            killer != null -> {
                val source = BuiltInRegistries.ENTITY_TYPE.getKey(killer.type).path
                setEntityData(action, pos, world, entity, source)
            }

            else -> {
                setEntityData(action, pos, world, entity, cause.msgId)
            }
        }

        if (action is PlayerKillActionType && entity is Player) {
            action.oldObjectState = entity.name.string
        }

        return action
    }

    fun villagerTradeAction(
        world: Level,
        pos: BlockPos,
        offer: MerchantOffer,
        player: Player,
        villager: Entity,
    ): VillagerTradeActionType {
        val action = VillagerTradeActionType()
        action.pos = pos
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(villager.type)
        action.oldObjectIdentifier = action.objectIdentifier
        action.sourceProfile = player.nameAndId()
        action.sourceName = Sources.INTERACT
        markCreativeSource(action, player)

        val buyA = describeStack(offer.baseCostA)
        val buyB = describeStack(offer.costB)
        val result = describeStack(offer.result)
        val costPart = if (offer.costB.isEmpty) buyA else "$buyA + $buyB"
        action.extraData = "Trade: $costPart -> $result (uses ${offer.uses}/${offer.maxUses})"

        return action
    }

    private fun describeStack(stack: ItemStack): String {
        if (stack.isEmpty) return "none"
        val id = BuiltInRegistries.ITEM.getKey(stack.item)
        return "${stack.count}x $id"
    }

    fun entityKillAction(world: Level, pos: BlockPos, entity: Entity, source: String): EntityKillActionType {
        val action = EntityKillActionType()
        setEntityData(action, pos, world, entity, source)
        return action
    }

    private fun setEntityData(action: ActionType, pos: BlockPos, world: Level, entity: Entity, source: String) {
        action.pos = pos
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.sourceName = source
        val overrideNbt = overrideEntityKillNbt.get()
        if (overrideNbt != null) {
            action.deferExtraData(overrideNbt)
            overrideEntityKillNbt.remove()
        } else {
            action.deferExtraData(entity.createNbt())
        }
    }

    fun entityChangeAction(
        world: Level,
        pos: BlockPos,
        oldEntityTags: CompoundTag,
        entity: Entity,
        itemStack: ItemStack?,
        entityActor: Entity?,
        sourceType: String,
    ): EntityChangeActionType {
        val action = EntityChangeActionType()

        action.pos = pos
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.oldObjectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)

        if (itemStack != null && !itemStack.isEmpty) {
            action.deferExtraData(itemStack.createNbt(world.registryAccess()))
        }
        action.oldObjectState = oldEntityTags.toString()
        action.objectState = entity.createNbt().toString()
        action.sourceName = sourceType

        if (entityActor is Player) {
            action.sourceProfile = entityActor.nameAndId()
            markCreativeSource(action, entityActor)
        }

        return action
    }

    fun itemFrameInsertAction(
        world: Level,
        pos: BlockPos,
        oldEntityTags: CompoundTag,
        entity: Entity,
        itemStack: ItemStack,
        entityActor: Entity?,
    ): ItemFrameInsertActionType {
        val action = ItemFrameInsertActionType()
        action.pos = pos
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.oldObjectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.deferExtraData(itemStack.createNbt(world.registryAccess()))
        action.oldObjectState = oldEntityTags.toString()
        action.objectState = entity.createNbt().toString()
        action.sourceName = Sources.EQUIP
        if (entityActor is Player) {
            action.sourceProfile = entityActor.nameAndId()
            markCreativeSource(action, entityActor)
        }
        return action
    }

    fun itemFrameRemoveAction(
        world: Level,
        pos: BlockPos,
        oldEntityTags: CompoundTag,
        entity: Entity,
        itemStack: ItemStack,
        entityActor: Entity?,
    ): ItemFrameRemoveActionType {
        val action = ItemFrameRemoveActionType()
        action.pos = pos
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.oldObjectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.deferExtraData(itemStack.createNbt(world.registryAccess()))
        action.oldObjectState = oldEntityTags.toString()
        action.objectState = entity.createNbt().toString()
        action.sourceName = Sources.REMOVE
        if (entityActor is Player) {
            action.sourceProfile = entityActor.nameAndId()
            markCreativeSource(action, entityActor)
        }
        return action
    }

    fun totemPopAction(world: Level, entity: LivingEntity, source: DamageSource): TotemPopActionType {
        val action = TotemPopActionType()
        val attacker = source.entity

        action.pos = entity.blockPosition()
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ITEM.getKey(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)
        action.oldObjectIdentifier = action.objectIdentifier

        when {
            attacker is Player -> {
                action.sourceName = Sources.PLAYER
                action.sourceProfile = attacker.nameAndId()
                markCreativeSource(action, attacker)
            }

            attacker != null -> {
                action.sourceName = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.type).path
            }

            else -> {
                action.sourceName = source.msgId
            }
        }

        action.oldObjectState = entity.name.string

        return action
    }

    fun entityMountAction(entity: Entity, player: Player): EntityMountActionType {
        val world = entity.level()

        val action = EntityMountActionType()

        action.pos = entity.blockPosition()
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.oldObjectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)

        action.objectState = entity.createNbt().toString()
        action.sourceName = Sources.PLAYER

        action.sourceProfile = player.nameAndId()
        markCreativeSource(action, player)

        return action
    }

    fun entityDismountAction(entity: Entity, player: Player): EntityDismountActionType {
        val world = entity.level()

        val action = EntityDismountActionType()

        action.pos = entity.blockPosition()
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)
        action.oldObjectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)

        action.objectState = entity.createNbt().toString()
        action.sourceName = Sources.PLAYER

        action.sourceProfile = player.nameAndId()
        markCreativeSource(action, player)

        return action
    }

    fun playerJoinAction(player: Player): PlayerJoinActionType {
        val world = player.level()
        val action = PlayerJoinActionType()

        action.pos = player.blockPosition()
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(player.type)
        action.oldObjectIdentifier = action.objectIdentifier
        action.sourceName = Sources.PLAYER
        action.sourceProfile = player.nameAndId()
        markCreativeSource(action, player)

        return action
    }

    fun playerLeaveAction(player: Player): PlayerLeaveActionType {
        val world = player.level()
        val action = PlayerLeaveActionType()

        action.pos = player.blockPosition()
        action.world = world.dimension().identifier()
        action.objectIdentifier = BuiltInRegistries.ENTITY_TYPE.getKey(player.type)
        action.oldObjectIdentifier = action.objectIdentifier
        action.sourceName = Sources.PLAYER
        action.sourceProfile = player.nameAndId()
        markCreativeSource(action, player)

        return action
    }
}
