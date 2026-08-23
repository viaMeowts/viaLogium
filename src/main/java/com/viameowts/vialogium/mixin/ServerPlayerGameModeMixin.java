package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.callbacks.BlockBreakCallback;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private ServerPlayer player;

    @Inject(
            method = "destroyBlock",
            at = @At("HEAD")
    )
    private void vialogiumOnPlayerBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);

        BlockBreakCallback.EVENT.invoker().breakBlock(
                level,
                pos.immutable(),
                state,
                blockEntity,
                Sources.PLAYER,
                player
        );
    }
}
