package com.viameowts.vialogium.utility

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.callbacks.BlockChangeCallback
import com.viameowts.vialogium.config.ActionsSpec
import com.viameowts.vialogium.logWarn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Deferred diff logging for piston block movements.
 *
 * A piston move passes blocks through the transient `moving_piston` state, so the
 * change is snapshotted at moveBlocks HEAD and diffed a few ticks later once the
 * destination placement has settled. Emitted with source [Sources.PISTON]; such
 * actions carry no player profile, so they are the first category dropped by the
 * CRITICAL/EMERGENCY queue modes - a redstone clock flood self-throttles instead of
 * overwhelming the database.
 */
object PistonLog {
    const val PISTON = "piston"
    private const val SETTLE_DELAY_TICKS = 4

    @JvmStatic
    fun enabled(): Boolean = ViaLogium.config[ActionsSpec.logPistons]

    @JvmStatic
    fun scheduleMoveDiff(level: ServerLevel, positions: List<BlockPos>, oldStates: List<BlockState>) {
        if (positions.isEmpty() || positions.size != oldStates.size) return
        val moved = positions.zip(oldStates)
        val server = ViaLogium.server
        ViaLogium.launch {
            delay(SETTLE_DELAY_TICKS.ticks)
            server.execute {
                for ((pos, oldState) in moved) {
                    try {
                        if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) continue
                        val newState = level.getBlockState(pos)
                        if (newState == oldState) continue
                        val newBlockEntity: BlockEntity? =
                            if (newState.hasBlockEntity()) level.getBlockEntity(pos) else null
                        BlockChangeCallback.EVENT.invoker()
                            .changeBlock(
                                level,
                                pos.immutable(),
                                oldState,
                                newState,
                                null,
                                newBlockEntity,
                                PISTON,
                                null,
                            )
                    } catch (t: Throwable) {
                        logWarn("Piston diff failed at $pos: ${t.message}")
                    }
                }
            }
        }
    }
}
