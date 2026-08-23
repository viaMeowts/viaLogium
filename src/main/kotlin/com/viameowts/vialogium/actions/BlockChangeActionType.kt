package com.viameowts.vialogium.actions

import com.viameowts.vialogium.actionutils.Preview
import com.viameowts.vialogium.logWarn
import com.viameowts.vialogium.utility.LOGGER
import com.viameowts.vialogium.utility.NbtUtils
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.getWorld
import com.viameowts.vialogium.utility.literal
import com.viameowts.vialogium.utility.parseExtraPayload
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderGetter
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.util.Util
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.TagValueInput

open class BlockChangeActionType : AbstractActionType() {
    override val identifier = "block-change"

    /**
     * During rollback/restore we set block states authoritatively. If the block currently at [pos]
     * is a container, vanilla would spill its contents onto the ground when the block is replaced
     * (Containers.dropContents in onRemove / affectNeighborsAfterRemoval). Clear it first so nothing
     * drops; the intended contents (if the restored block is itself a container) are then put back
     * inside via loadWithComponents after the new block is set.
     */
    protected fun clearContainerToPreventDrops(world: ServerLevel, pos: BlockPos) {
        val blockEntity = world.getBlockEntity(pos)
        if (blockEntity is Container) {
            blockEntity.clearContent()
        }
    }

    /**
     * Restore a container's inventory from the snapshot NBT taken before the block was broken/changed,
     * so the container comes back in its pre-break state (items inside, not dropped). Uses the game's
     * own ContainerHelper.loadAllItems on the saved "Items" list and writes it into the just-placed
     * block entity. This is the authoritative restore; loadWithComponents alone does not reliably
     * repopulate the inventory in this version.
     */
    protected fun restoreContainerContents(
        world: ServerLevel,
        pos: BlockPos,
        server: MinecraftServer,
        tag: CompoundTag,
    ) {
        val blockEntity = world.getBlockEntity(pos)
        if (blockEntity !is Container) return
        if (!tag.contains("Items")) return

        ProblemReporter.ScopedCollector({ "vialogium:rollback:items@$pos" }, LOGGER).use { reporter ->
            val input = TagValueInput.create(reporter, server.registryAccess(), tag)
            val items = NonNullList.withSize(blockEntity.containerSize, ItemStack.EMPTY)
            ContainerHelper.loadAllItems(input, items)
            for (i in 0 until minOf(items.size, blockEntity.containerSize)) {
                blockEntity.setItem(i, items[i])
            }
        }
        blockEntity.setChanged()
    }

    override fun rollback(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false
        val oldState = oldBlockState(world.holderLookup(Registries.BLOCK))
        clearContainerToPreventDrops(world, pos)
        world.setBlockAndUpdate(pos, oldState)

        // Unwrap the creative-flag wrapper (markCreative nests the real block-entity NBT under a
        // payload key); otherwise "Items" sits one level down and the container restores empty.
        val tag = if (!extraData.isNullOrBlank()) parseExtraPayload(extraData) else null
        if (oldState.hasBlockEntity() && tag != null) {
            // loadWithComponents restores non-item BE state (custom name, etc.) but is unreliable for
            // the inventory; wrap it so a failure can't block the explicit content restore below.
            try {
                ProblemReporter.ScopedCollector({ "vialogium:rollback:block-change@$pos" }, LOGGER).use {
                    world.getBlockEntity(pos)?.loadWithComponents(
                        TagValueInput.create(it, server.registryAccess(), tag),
                    )
                }
            } catch (t: Throwable) {
                logWarn("vialogium:rollback:block-change@$pos loadWithComponents failed: ${t.message}")
            }
            // Authoritative inventory restore from the snapshot's "Items" via the game's own loader.
            restoreContainerContents(world, pos, server, tag)
        }
        world.chunkSource.blockChanged(pos)

        return true
    }

    override fun previewRollback(preview: Preview, player: ServerPlayer) {
        if (player.level().dimension().identifier() == world) {
            player.connection.send(
                ClientboundBlockUpdatePacket(
                    pos,
                    oldBlockState(player.level().holderLookup(Registries.BLOCK)),
                ),
            )
            preview.positions.add(pos)
        }
    }

    override fun restore(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false
        clearContainerToPreventDrops(world, pos)
        world.setBlockAndUpdate(pos, newBlockState(world.holderLookup(Registries.BLOCK)))
        return true
    }

    override fun previewRestore(preview: Preview, player: ServerPlayer) {
        if (player.level().dimension().identifier() == world) {
            player.connection.send(
                ClientboundBlockUpdatePacket(
                    pos,
                    newBlockState(player.level().holderLookup(Registries.BLOCK)),
                ),
            )
            preview.positions.add(pos)
        }
    }

    override fun getTranslationType() = "block"

    override fun getObjectMessage(source: CommandSourceStack): Component {
        val signHover = buildSignTextHover(source.server)
        val text = Component.literal("")
        text.append(blockNameWithHover(oldObjectIdentifier, signHover))
        if (oldObjectIdentifier != objectIdentifier) {
            text.append(" → ".literal())
            text.append(blockNameWithHover(objectIdentifier, signHover))
        }
        return text
    }

    /** Block name component whose hover shows the block id, plus the captured sign text if any. */
    protected fun blockNameWithHover(id: Identifier, signHover: Component?): Component {
        val hover = Component.literal(id.toString())
        if (signHover != null) {
            hover.append("\n".literal()).append(signHover)
        }
        return Component.translatable(Util.makeDescriptionId(getTranslationType(), id))
            .setStyle(TextColorPallet.secondaryVariant)
            .withStyle { it.withHoverEvent(HoverEvent.ShowText(hover)) }
    }

    /**
     * If the captured block entity NBT is a sign, render its front/back text as a hover component.
     * Returns null for non-signs or empty signs. The text shown is the sign's content at the moment
     * the action was logged (e.g. the text it had when broken, or the previous text before an edit).
     */
    protected fun buildSignTextHover(server: MinecraftServer): Component? {
        val raw = extraData ?: return null
        // Cheap string pre-check: skip parsing the (potentially large) NBT for non-sign blocks.
        if (!raw.contains("front_text") && !raw.contains("back_text")) return null
        val tag = parseExtraPayload(raw) ?: return null
        if (!tag.contains("front_text") && !tag.contains("back_text")) return null

        val blockLookup = server.registryAccess().lookupOrThrow(Registries.BLOCK)
        val sign = loadSign(newBlockState(blockLookup), tag, server)
            ?: loadSign(oldBlockState(blockLookup), tag, server)
            ?: return null

        val result = Component.literal("")
        var any = false
        fun appendSide(label: String, signText: SignText) {
            val messages = signText.getMessages(false)
            if (messages.none { it.string.isNotBlank() }) return
            if (any) result.append("\n".literal())
            result.append(label.literal().setStyle(TextColorPallet.secondary))
            for (line in messages) {
                result.append("\n".literal()).append(line)
            }
            any = true
        }
        appendSide("Front:", sign.frontText)
        appendSide("Back:", sign.backText)
        return if (any) result else null
    }

    private fun loadSign(state: BlockState, tag: CompoundTag, server: MinecraftServer): SignBlockEntity? {
        if (!state.hasBlockEntity()) return null
        return try {
            BlockEntity.loadStatic(pos, state, tag, server.registryAccess()) as? SignBlockEntity
        } catch (t: Throwable) {
            null
        }
    }

    fun oldBlockState(blockLookup: HolderGetter<Block>) = checkForBlockState(
        oldObjectIdentifier,
        oldObjectState?.let {
            NbtUtils.blockStateFromProperties(
                TagParser.parseCompoundFully(it),
                oldObjectIdentifier,
                blockLookup,
            )
        },
    )

    fun newBlockState(blockLookup: HolderGetter<Block>) = checkForBlockState(
        objectIdentifier,
        objectState?.let {
            NbtUtils.blockStateFromProperties(
                TagParser.parseCompoundFully(it),
                objectIdentifier,
                blockLookup,
            )
        },
    )

    private fun checkForBlockState(identifier: Identifier, checkState: BlockState?): BlockState {
        val block = BuiltInRegistries.BLOCK.getOptional(identifier)
        if (block.isEmpty) {
            logWarn("Unknown block $identifier")
            return Blocks.AIR.defaultBlockState()
        }

        var state = block.get().defaultBlockState()
        if (checkState != null) state = checkState

        return state
    }
}
