package com.viameowts.vialogium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.viameowts.vialogium.actionutils.ActionFactory;
import com.viameowts.vialogium.database.ActionQueueService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {

    @Shadow
    @Final
    private Merchant merchant;

    @Inject(
        method = "onTake",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/trading/Merchant;notifyTrade(Lnet/minecraft/world/item/trading/MerchantOffer;)V",
            shift = At.Shift.AFTER
        )
    )
    private void vialogiumLogVillagerTrade(Player player, ItemStack stack, CallbackInfo ci, @Local MerchantOffer merchantOffer) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (!(serverPlayer.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (!(merchant instanceof Entity merchantEntity)) {
            return;
        }

        ActionQueueService.INSTANCE.addToQueue(
            ActionFactory.INSTANCE.villagerTradeAction(
                serverLevel,
                merchantEntity.blockPosition(),
                merchantOffer,
                serverPlayer,
                merchantEntity
            )
        );
    }
}
