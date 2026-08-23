package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.callbacks.EntityModifyCallback;
import com.viameowts.vialogium.utility.NbtUtils;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DyeItem.class)
public abstract class DyeItemMixin {
    @Unique
    private CompoundTag oldEntityTags;

    @Inject(method = "interactLivingEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/sheep/Sheep;setColor(Lnet/minecraft/world/item/DyeColor;)V"))
    private void vialogiumOldEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        oldEntityTags = NbtUtils.INSTANCE.createNbt(entity);
    }

    @Inject(method = "interactLivingEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/sheep/Sheep;setColor(Lnet/minecraft/world/item/DyeColor;)V", shift = At.Shift.AFTER))
    private void vialogiumPlayerDyeSheep(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        EntityModifyCallback.EVENT.invoker().modify(player.level(), entity.blockPosition(), oldEntityTags, entity, stack, player, Sources.DYE);
    }
}
