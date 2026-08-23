package com.viameowts.vialogium.actions

import com.viameowts.vialogium.utility.LOGGER
import com.viameowts.vialogium.utility.NbtUtils
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.UUID
import com.viameowts.vialogium.utility.getWorld
import com.viameowts.vialogium.utility.literal
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.storage.TagValueInput

// TODO remove duplication from ItemPickUpActionType and ItemDropActionType
open class ItemDropActionType : AbstractActionType() {
    override val identifier = "item-drop"

    private fun parseEntityTag(raw: String?): CompoundTag? =
        raw?.let { runCatching { TagParser.parseCompoundFully(it) }.getOrNull() }

    // Not used
    override fun getTranslationType(): String = "item"

    private fun getStack(server: MinecraftServer) = NbtUtils.itemFromProperties(
        extraData,
        objectIdentifier,
        server.registryAccess(),
    )

    override fun getObjectMessage(source: CommandSourceStack): Component {
        val stack = getStack(source.server)

        return "${stack.count} ".literal().append(
            stack.itemName,
        ).setStyle(TextColorPallet.secondaryVariant).withStyle {
            it.withHoverEvent(
                HoverEvent.ShowItem(
                    stack,
                ),
            )
        }
    }

    override fun rollback(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false

        val newEntity = parseEntityTag(objectState) ?: return false
        val optionalUUID = newEntity.read(UUID, UUIDUtil.CODEC)
        if (optionalUUID.isEmpty) return false
        val entity = world.getEntity(optionalUUID.get())

        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED)
            return true
        }
        return false
    }

    override fun restore(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false

        val newEntity = parseEntityTag(objectState) ?: return false
        val optionalUUID = newEntity.read(UUID, UUIDUtil.CODEC)
        if (optionalUUID.isEmpty) return false
        val entity = world.getEntity(optionalUUID.get())

        if (entity == null) {
            val entity = ItemEntity(EntityType.ITEM, world)
            ProblemReporter.ScopedCollector({ "vialogium:restore:item-drop@$pos" }, LOGGER).use {
                val readView = TagValueInput.create(it, world.registryAccess(), newEntity)
                entity.load(readView)
                world.addFreshEntity(entity)
            }
        }
        return true
    }
}
