package com.viameowts.vialogium.mixin.entities;

import com.viameowts.vialogium.callbacks.EntityDismountCallback;
import com.viameowts.vialogium.callbacks.EntityKillCallback;
import com.viameowts.vialogium.callbacks.TotemPopCallback;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(
            method = "die",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/damagesource/DamageSource;getEntity()Lnet/minecraft/world/entity/Entity;")
    )
    private void vialogiumEntityKillInvoker(DamageSource source, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity instanceof Player) {
            return;
        }

        EntityKillCallback.EVENT.invoker().kill(
                entity.level(), entity.blockPosition(), entity, source
        );
    }

    @Inject(method = "dismountVehicle", at = @At("RETURN"))
    private void onEntityDismount(Entity vehicle, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        if (!(entity instanceof Player player)) {
            return;
        }

        EntityDismountCallback.EVENT.invoker().dismount(vehicle, player);
    }

    @Inject(method = "checkTotemDeathProtection", at = @At("RETURN"))
    private void onTotemPop(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }

        LivingEntity entity = (LivingEntity) (Object) this;
        TotemPopCallback.EVENT.invoker().pop(entity.level(), entity, source);
    }
}
