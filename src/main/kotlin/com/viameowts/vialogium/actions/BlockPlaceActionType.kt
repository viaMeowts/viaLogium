package com.viameowts.vialogium.actions

import com.viameowts.vialogium.utility.LOGGER
import com.viameowts.vialogium.utility.getWorld
import com.viameowts.vialogium.utility.parseExtraPayload
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput

class BlockPlaceActionType : BlockChangeActionType() {
    override val identifier = "block-place"

    override fun rollback(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false
        // Undoing a placement removes the block. If the player filled the placed container, clear it
        // first so vanilla doesn't spill the contents onto the ground when it's set to air.
        clearContainerToPreventDrops(world, pos)
        world.setBlockAndUpdate(pos, oldBlockState(world.holderLookup(Registries.BLOCK)))

        return true
    }

    override fun restore(server: MinecraftServer): Boolean {
        val world = server.getWorld(world) ?: return false

        clearContainerToPreventDrops(world, pos)
        val state = newBlockState(world.holderLookup(Registries.BLOCK))
        world.setBlockAndUpdate(pos, state)
        val payload = if (!extraData.isNullOrBlank()) parseExtraPayload(extraData) else null
        if (state.hasBlockEntity() && payload != null) {
            ProblemReporter.ScopedCollector({ "vialogium:restore:block-place@$pos" }, LOGGER).use {
                world.getBlockEntity(pos)?.loadWithComponents(
                    TagValueInput.create(it, server.registryAccess(), payload),
                )
            }
            restoreContainerContents(world, pos, server, payload)
        }

        return true
    }

    // Hover shows the placed block's id plus, for signs, the captured sign text.
    override fun getObjectMessage(source: CommandSourceStack): Component =
        blockNameWithHover(objectIdentifier, buildSignTextHover(source.server))
}
