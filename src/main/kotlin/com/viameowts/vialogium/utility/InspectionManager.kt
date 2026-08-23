package com.viameowts.vialogium.utility

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.actionutils.SearchResults
import com.viameowts.vialogium.database.DatabaseManager
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.*

private val inspectingUsers = HashSet<UUID>()

fun Player.isInspecting() = inspectingUsers.contains(this.uuid)

fun Player.inspectOn(): Int {
    inspectingUsers.add(this.uuid)
    this.sendSystemMessage(
        Component.translatable(
            "text.vialogium.inspect.toggle",
            "text.vialogium.inspect.on".translate().setStyle(TextColorPallet.actionPositive),
        ).setStyle(TextColorPallet.secondary),
    )

    return 1
}

fun Player.inspectOff(): Int {
    inspectingUsers.remove(this.uuid)
    this.sendSystemMessage(
        Component.translatable(
            "text.vialogium.inspect.toggle",
            "text.vialogium.inspect.off".translate().setStyle(TextColorPallet.actionNegative),
        ).setStyle(TextColorPallet.secondary),
    )

    return 1
}

fun CommandSourceStack.inspectBlock(pos: BlockPos) {
    val source = this

    ViaLogium.launch {
        var area = BoundingBox(pos)

        val state = source.level.getBlockState(pos)
        if (state.block is ChestBlock) {
            getOtherChestSide(state, pos)?.let {
                area = BoundingBox.fromCorners(pos, it)
            }
        } else if (state.block is DoorBlock) {
            getOtherDoorHalf(state, pos).let {
                area = BoundingBox.fromCorners(pos, it)
            }
        } else if (state.block is BedBlock) {
            getOtherBedPart(state, pos).let {
                area = BoundingBox.fromCorners(pos, it)
            }
        }

        val isContainerInspection = source.level.getBlockEntity(pos) is Container

        val params = ActionSearchParams.build {
            bounds = area
            worlds = mutableSetOf(Negatable.allow(source.level.dimension().identifier()))
            if (isContainerInspection) {
                actions = mutableSetOf(
                    Negatable.allow("item-insert"),
                    Negatable.allow("item-remove"),
                )
            }
        }

        ViaLogium.searchCache[source.textName] = params

        MessageUtils.warnBusy(source)
        val results = DatabaseManager.searchActions(params, 1)

        if (results.actions.isEmpty()) {
            source.sendFailure(
                Component.translatable("error.vialogium.command.no_results").setStyle(TextColorPallet.actionNegative),
            )
            return@launch
        }

        MessageUtils.sendSearchResults(
            source,
            results,
            Component.translatable(
                "text.vialogium.header.search.pos",
                "${pos.x} ${pos.y} ${pos.z}".literal(),
            ).setStyle(TextColorPallet.primary),
        )
    }
}

fun getOtherChestSide(state: BlockState, pos: BlockPos): BlockPos? {
    val type = state.getValue(ChestBlock.TYPE)
    return if (type != ChestType.SINGLE) {
        // We now need to query other container results in the same chest
        val facing = state.getValue(ChestBlock.FACING)
        if (type == ChestType.RIGHT) {
            // Chest is right, so left as you look at it
            pos.relative(facing.getCounterClockWise(Direction.Axis.Y))
        } else {
            pos.relative(facing.getClockWise(Direction.Axis.Y))
        }
    } else {
        null
    }
}

private fun getOtherDoorHalf(state: BlockState, pos: BlockPos): BlockPos {
    val half = state.getValue(DoorBlock.HALF)
    return if (half == DoubleBlockHalf.LOWER) {
        pos.relative(Direction.UP)
    } else {
        pos.relative(Direction.DOWN)
    }
}

private fun getOtherBedPart(state: BlockState, pos: BlockPos): BlockPos {
    val part = state.getValue(BedBlock.PART)
    val direction = state.getValue(BedBlock.FACING)
    return if (part == BedPart.FOOT) {
        pos.relative(direction)
    } else {
        pos.relative(direction.opposite)
    }
}

suspend fun ServerPlayer.getInspectResults(pos: BlockPos): SearchResults {
    val source = this.createCommandSourceStack()
    val isContainerInspection = source.level.getBlockEntity(pos) is Container

    val params = ActionSearchParams.build {
        bounds = BoundingBox(pos)
        worlds = mutableSetOf(Negatable.allow(source.level.dimension().identifier()))
        if (isContainerInspection) {
            actions = mutableSetOf(
                Negatable.allow("item-insert"),
                Negatable.allow("item-remove"),
            )
        }
    }

    ViaLogium.searchCache[source.textName] = params
    MessageUtils.warnBusy(source)
    return DatabaseManager.searchActions(params, 1)
}
