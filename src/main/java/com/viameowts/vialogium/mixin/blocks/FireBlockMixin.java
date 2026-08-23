package com.viameowts.vialogium.mixin.blocks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.viameowts.vialogium.callbacks.BlockBreakCallback;
import com.viameowts.vialogium.callbacks.BlockChangeCallback;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @WrapOperation(
        method = "checkBurnOut",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"
        )
    )
    private boolean onRemoveBlock(Level world, BlockPos pos, boolean move, Operation<Boolean> original, @Local BlockState blockState) {
        boolean result = original.call(world, pos, move);
        if (blockState.getBlock() != Blocks.FIRE) {
            BlockBreakCallback.EVENT.invoker().breakBlock(world, pos, blockState, world.getBlockEntity(pos), Sources.FIRE);
        }
        return result;
    }

    @WrapOperation(
        method = "checkBurnOut",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        )
    )
    private boolean onSetBlock(Level world, BlockPos pos, BlockState state, int flags, Operation<Boolean> original, @Local BlockState blockState) {
        BlockEntity oldBlockEntity = world.getBlockEntity(pos);
        boolean result = original.call(world, pos, state, flags);
        if (blockState.getBlock() != Blocks.FIRE) {
            BlockChangeCallback.EVENT.invoker().changeBlock(world, pos, blockState, state, oldBlockEntity, world.getBlockEntity(pos), Sources.FIRE, null);
        }
        return result;
    }
}
