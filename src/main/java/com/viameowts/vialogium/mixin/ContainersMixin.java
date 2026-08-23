package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.actionutils.LocationalInventory;
import com.viameowts.vialogium.callbacks.ItemRemoveCallback;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Containers.class)
public abstract class ContainersMixin {

    @ModifyArgs(
            method = "dropContents(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/Container;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Container;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private static void vialogiumTrackContainerBreakRemove(Args args, Level world, double x, double y, double z, Container inventory) {
        // Disabled: item-remove/broke actions are redundant with block-break NBT capture.
        // The block-break action already saves the complete block entity including all items.
        // Remove this if individual item-remove/broke rollback support is needed separately.
    }
}