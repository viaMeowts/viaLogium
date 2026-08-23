package com.viameowts.vialogium.actions

import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.viameowts.vialogium.utility.LOGGER
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.UUID
import com.viameowts.vialogium.utility.getWorld
import com.viameowts.vialogium.utility.literal
import com.viameowts.vialogium.utility.parseExtraPayload
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.RegistryAccess
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ProblemReporter
import net.minecraft.util.Util
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.TagValueInput

open class EntityChangeActionType : AbstractActionType() {
    override val identifier: String = "entity-change"

    override fun getTranslationType(): String {
        val item = getStack(RegistryAccess.EMPTY).item
        return if (item is BlockItem) {
            "block"
        } else {
            "item"
        }
    }

    private fun getStack(registryManager: RegistryAccess): ItemStack {
        if (extraData == null) return ItemStack.EMPTY
        try {
            val readView = TagValueInput.create(
                ProblemReporter.DISCARDING,
                registryManager,
                parseExtraPayload(extraData) ?: TagParser.parseCompoundFully(extraData!!),
            )
            return readView.read(ItemStack.MAP_CODEC).orElse(ItemStack.EMPTY)
        } catch (_: CommandSyntaxException) {
            // In an earlier version of vialogium extraData only stored the item id
            val item = BuiltInRegistries.ITEM.getValue(Identifier.parse(extraData!!))
            return item.defaultInstance
        }
    }

    override fun getObjectMessage(source: CommandSourceStack): Component {
        val text = Component.literal("")
        text.append(
            Component.translatable(
                Util.makeDescriptionId(
                    "entity",
                    objectIdentifier,
                ),
            ).setStyle(TextColorPallet.secondaryVariant).withStyle {
                it.withHoverEvent(
                    HoverEvent.ShowText(
                        objectIdentifier.toString().literal(),
                    ),
                )
            },
        )

        val stack = getStack(source.registryAccess())
        if (!stack.isEmpty) {
            text.append(
                Component.literal(" ").append(Component.translatable("text.vialogium.action_message.with")).append(" "),
            )
            text.append(
                Component.translatable(
                    stack.item.descriptionId,
                ).setStyle(TextColorPallet.secondaryVariant).withStyle {
                    it.withHoverEvent(
                        HoverEvent.ShowItem(
                            stack,
                        ),
                    )
                },
            )
        }
        return text
    }

    override fun rollback(server: MinecraftServer): Boolean {
        val world = server.getWorld(world)

        val oldEntity = oldObjectState?.let {
            runCatching {
                TagParser.parseCompoundFully(
                    it,
                )
            }.getOrNull()
        } ?: return false
        val optionalUUID = oldEntity.read(UUID, UUIDUtil.CODEC)
        if (optionalUUID.isEmpty) return false
        val entity = world?.getEntity(optionalUUID.get())

        if (entity != null) {
            ProblemReporter.ScopedCollector({ "vialogium:rollback:entity-change@$pos" }, LOGGER).use {
                val readView = TagValueInput.create(it, server.registryAccess(), oldEntity)
                if (entity is ItemFrame) {
                    entity.item = ItemStack.EMPTY
                }
                when (entity) {
                    is LivingEntity -> entity.load(readView)
                    is HangingEntity -> entity.load(readView)
                }
            }
            return true
        }
        return false
    }

    override fun restore(server: MinecraftServer): Boolean {
        val world = server.getWorld(world)
        val newEntity = objectState?.let {
            runCatching {
                TagParser.parseCompoundFully(
                    it,
                )
            }.getOrNull()
        } ?: return false
        val optionalUUID = newEntity.read(UUID, UUIDUtil.CODEC)
        if (optionalUUID.isEmpty) return false
        val entity = world?.getEntity(optionalUUID.get())

        if (entity != null) {
            ProblemReporter.ScopedCollector({ "vialogium:restore:entity-change@$pos" }, LOGGER).use {
                val readView = TagValueInput.create(it, server.registryAccess(), newEntity)
                if (entity is ItemFrame) {
                    entity.item = ItemStack.EMPTY
                }
                when (entity) {
                    is LivingEntity -> entity.load(readView)
                    is HangingEntity -> entity.load(readView)
                }
            }
            return true
        }
        return false
    }
}
