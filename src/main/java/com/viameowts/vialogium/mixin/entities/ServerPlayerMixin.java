package com.viameowts.vialogium.mixin.entities;

import com.viameowts.vialogium.callbacks.EntityKillCallback;
import com.viameowts.vialogium.callbacks.ItemDropCallback;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void logPlayerKill(DamageSource source, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        EntityKillCallback.EVENT.invoker().kill(player.level(), player.blockPosition(), player, source);
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("RETURN"))
    private void logPlayerItemDrop(ItemStack stack, boolean dropAtSelf, Prediction prediction, CallbackInfoReturnable<ItemEntity> cir) {
        Player player = (Player) (Object) this;
        var itemEntity = cir.getReturnValue();
        if (itemEntity != null) {
            ItemDropCallback.EVENT.invoker().drop(itemEntity, player);
        }
    }
}
