package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.callbacks.BlockChangeCallback;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CampfireBlockEntity.class)
public abstract class CampfireBlockEntityMixin {

    @Inject(method = "cookTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V", shift = At.Shift.AFTER))
    private static void logCampfireRemoveItem(ServerLevel world, BlockPos pos, BlockState state, CampfireBlockEntity campfire, RecipeManager.CachedCheck<SingleRecipeInput, CampfireCookingRecipe> matchGetter, CallbackInfo ci) {
        BlockChangeCallback.EVENT.invoker().changeBlock(world, pos, state, world.getBlockState(pos), campfire, world.getBlockEntity(pos), Sources.REMOVE);
    }

}