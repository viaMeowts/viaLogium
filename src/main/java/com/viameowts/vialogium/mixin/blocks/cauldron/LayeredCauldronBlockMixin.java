package com.viameowts.vialogium.mixin.blocks.cauldron;

import com.viameowts.vialogium.callbacks.BlockChangeCallback;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LayeredCauldronBlock.class)
public abstract class LayeredCauldronBlockMixin {

    @Unique
    private static Player playerEntity;

    @Inject(method = "lowerFillLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            shift = At.Shift.AFTER))
    private static void vialogiumLogDecrementLevelCauldron(BlockState state, Level world, BlockPos pos, CallbackInfo ci) {
        if (playerEntity != null) {
            BlockChangeCallback.EVENT.invoker().changeBlock(
                    world,
                    pos,
                    state,
                    world.getBlockState(pos),
                    null,
                    null,
                    Sources.DRAIN,
                    playerEntity);
            playerEntity = null;

        } else {
            BlockChangeCallback.EVENT.invoker().changeBlock(
                    world,
                    pos,
                    state,
                    world.getBlockState(pos),
                    null,
                    null,
                    Sources.DRAIN);
        }
    }

    @Inject(method = "method_71627", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/LayeredCauldronBlock;handleEntityOnFireInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void vialogiumLogPlayerExtinguish(ServerLevel serverWorld, BlockPos blockPos, BlockState blockState, Level world, Entity collidedEntity, CallbackInfo ci) {
        if (collidedEntity instanceof Player) {
            playerEntity = (Player) collidedEntity;
        }
    }

    @Inject(method = "receiveStalactiteDrip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", shift = At.Shift.AFTER))
    private void vialogiumLogIncrementLevelCauldron(BlockState state, Level world, BlockPos pos, Fluid fluid, CallbackInfo ci) {
        BlockChangeCallback.EVENT.invoker().changeBlock(
                world,
                pos,
                state,
                world.getBlockState(pos),
                null,
                null,
                Sources.DRIP);
    }

    @Inject(method = "handlePrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", shift = At.Shift.AFTER))
    private void vialogiumLogIncrementLevelCauldron(BlockState state, Level world, BlockPos pos, Biome.Precipitation precipitation, CallbackInfo ci) {
        BlockChangeCallback.EVENT.invoker().changeBlock(
                world,
                pos,
                state,
                world.getBlockState(pos),
                null,
                null,
                Sources.SNOW);
    }

}
