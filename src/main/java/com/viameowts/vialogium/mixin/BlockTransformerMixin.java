package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.callbacks.BlockChangeCallback;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Axe stripping/scraping/unwaxing, shovel paths and hoe tilling. Since 26.3 these are
 * data-driven block transformers instead of AxeItem/ShovelItem/HoeItem code.
 */
@Mixin(BlockTransformer.class)
public abstract class BlockTransformerMixin {
    @ModifyArgs(method = "transformBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    public void logBlockTransform(Args args, UseOnContext context) {
        Level world = context.getLevel();
        BlockPos pos = args.get(0);
        BlockState oldState = world.getBlockState(pos);
        BlockState newState = args.get(1);
        Player player = context.getPlayer();
        if (player != null) {
            BlockChangeCallback.EVENT.invoker().changeBlock(world, pos, oldState, newState, null, null, player);
        } else {
            BlockChangeCallback.EVENT.invoker().changeBlock(world, pos, oldState, newState, null, null, Sources.INTERACT);
        }
    }
}
