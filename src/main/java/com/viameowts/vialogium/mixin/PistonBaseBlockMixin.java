package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.utility.PistonLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot-based piston movement logging. The actual diff is emitted by
 * {@link PistonLog} a few ticks later, after moving_piston placeholders settle.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

    private static final int SNAPSHOT_DEPTH = 14;

    @Inject(method = "moveBlocks", at = @At("HEAD"))
    private void vialogiumSnapshotPistonMove(
            Level world,
            BlockPos pos,
            Direction dir,
            boolean extending,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(world instanceof ServerLevel serverLevel) || !PistonLog.enabled()) {
            return;
        }

        List<BlockPos> positions = new ArrayList<>();
        if (extending) {
            for (int i = 1; i <= SNAPSHOT_DEPTH; i++) {
                positions.add(pos.relative(dir, i));
            }
        } else {
            positions.add(pos.relative(dir));
            positions.add(pos.relative(dir, 2));
        }

        List<BlockState> oldStates = new ArrayList<>(positions.size());
        for (BlockPos p : positions) {
            oldStates.add(serverLevel.getBlockState(p));
        }

        PistonLog.scheduleMoveDiff(serverLevel, positions, oldStates);
    }
}
