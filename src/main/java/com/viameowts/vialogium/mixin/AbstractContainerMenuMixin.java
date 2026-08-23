package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.callbacks.ItemInsertCallback;
import com.viameowts.vialogium.callbacks.ItemRemoveCallback;
import com.viameowts.vialogium.utility.HandledSlot;
import com.viameowts.vialogium.utility.HandlerWithContext;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin implements HandlerWithContext {
    @Unique
    private ServerPlayer player = null;
    
    @Unique
    private BlockPos pos = null;

    @Inject(method = "addSlot", at = @At(value = "HEAD"))
    private void vialogiumGiveSlotHandlerReference(Slot slot, CallbackInfoReturnable<Slot> cir) {
        ((HandledSlot) slot).setHandler((AbstractContainerMenu) (Object) this);
    }

    @Inject(method = "clickMenuButton", at = @At(value = "HEAD"))
    private void vialogiumButtonClickGetPlayer(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
        this.player = (ServerPlayer) player;
    }

    @Inject(method = "doClick", at = @At(value = "HEAD"))
    private void internalOnSlotClickGetPlayer(int slotIndex, int button, ContainerInput containerInput, Player player, CallbackInfo ci) {
        this.player = (ServerPlayer) player;
    }

    @Inject(method = "clicked", at = @At(value = "HEAD"))
    private void vialogiumSlotClickGetPlayer(int slotIndex, int button, ContainerInput containerInput, Player player, CallbackInfo ci) {
        this.player = (ServerPlayer) player;
    }

    @Inject(method = "clearContainer", at = @At(value = "HEAD"))
    private void vialogiumDropInventoryGetPlayer(Player player, Container inventory, CallbackInfo ci) {
        this.player = (ServerPlayer) player;
    }

    @Nullable
    @Override
    public ServerPlayer getPlayer() {
        return player;
    }

    @Nullable
    @Override
    public BlockPos getPos() {
        return pos;
    }

    @Override
    public void setPos(@NotNull BlockPos pos) {
        this.pos = pos;
    }

    @Unique
    private void vialogiumLogInsert(ItemStack stack, BlockPos targetPos, ServerLevel level, ServerPlayer actor) {
        if (stack.isEmpty()) {
            return;
        }

        int remaining = stack.getCount();
        while (remaining > 0) {
            int part = Math.min(remaining, stack.getMaxStackSize());
            ItemInsertCallback.EVENT.invoker().insert(stack.copyWithCount(part), targetPos, level, Sources.PLAYER, actor);
            remaining -= part;
        }
    }

    @Unique
    private void vialogiumLogRemove(ItemStack stack, BlockPos targetPos, ServerLevel level, ServerPlayer actor) {
        if (stack.isEmpty()) {
            return;
        }

        int remaining = stack.getCount();
        while (remaining > 0) {
            int part = Math.min(remaining, stack.getMaxStackSize());
            ItemRemoveCallback.EVENT.invoker().remove(stack.copyWithCount(part), targetPos, level, Sources.PLAYER, actor);
            remaining -= part;
        }
    }

    @Override
    public void onStackChanged(@NotNull ItemStack old, @NotNull ItemStack itemStack, @NotNull BlockPos pos) {
        if (player == null || player.level().isClientSide()) {
            return;
        }

        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (old.isEmpty() && !itemStack.isEmpty()) {
            vialogiumLogInsert(itemStack, pos, serverLevel, player);
            return;
        }

        if (!old.isEmpty() && itemStack.isEmpty()) {
            vialogiumLogRemove(old, pos, serverLevel, player);
            return;
        }

        if (old.isEmpty() || itemStack.isEmpty()) {
            return;
        }

        if (ItemStack.isSameItemSameComponents(old, itemStack)) {
            int delta = itemStack.getCount() - old.getCount();
            if (delta > 0) {
                vialogiumLogInsert(itemStack.copyWithCount(delta), pos, serverLevel, player);
            } else if (delta < 0) {
                vialogiumLogRemove(old.copyWithCount(-delta), pos, serverLevel, player);
            }
            return;
        }

        vialogiumLogRemove(old, pos, serverLevel, player);
        vialogiumLogInsert(itemStack, pos, serverLevel, player);
    }
}
