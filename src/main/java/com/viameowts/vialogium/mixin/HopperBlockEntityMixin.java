package com.viameowts.vialogium.mixin;

import com.viameowts.vialogium.actionutils.DoubleInventoryHelper;
import com.viameowts.vialogium.actionutils.LocationalInventory;
import com.viameowts.vialogium.callbacks.ItemInsertCallback;
import com.viameowts.vialogium.callbacks.ItemRemoveCallback;
import com.viameowts.vialogium.utility.HopperTransferFlags;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    private static final ThreadLocal<Deque<ItemStack>> vialogium$originalStack = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Flag set during tryTakeInItemFromSlot() (the hopper PULL path) to prevent
     * the addItem() mixin from double-logging the same transfer, because
     * vialogium$logPullTransfer fires the callbacks directly.
     */
    private static final ThreadLocal<Boolean> vialogium$skipPullTransfer = ThreadLocal.withInitial(() -> false);

    /**
     * Captures the item stack being pulled so the RETURN handler can reference it
     * after the method has transferred the item.  Used only for the PULL direction.
     */
    private static final ThreadLocal<ItemStack> vialogium$pendingPullStack = new ThreadLocal<>();

    // ----------------------------------------------------------------
    //  Flag accessors for regular hopper PULL (tryTakeInItemFromSlot) context
    // ----------------------------------------------------------------

    private static boolean vialogium_shouldSkipPullTransfer() {
        return vialogium$skipPullTransfer.get();
    }

    private static void vialogium_setSkipPullTransfer(boolean skip) {
        if (skip) {
            vialogium$skipPullTransfer.set(true);
        } else {
            vialogium$skipPullTransfer.remove();
        }
    }

    // ================================================================
    //  EXISTING: addItem() mixin — handles PUSH direction (hopper → container below)
    //            and theoretically the PULL direction too, but the PULL is
    //            now captured more reliably by tryTakeInItemFromSlot() below.
    // ================================================================

    @Inject(
            method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD")
    )
    private static void vialogiumCaptureInputCount(
            Container source,
            Container destination,
            ItemStack stack,
            Direction direction,
            CallbackInfoReturnable<ItemStack> cir
    ) {
        vialogium$originalStack.get().push(stack.copy());
    }


    @Inject(
            method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN")
    )
    private static void vialogiumTrackHopperTransfers(
            Container source,
            Container destination,
            ItemStack stack,
            Direction direction,
            CallbackInfoReturnable<ItemStack> cir
    ) {
        // When MinecartHopperMixin is handling the suck-in context, skip here
        // to avoid duplicate logging (the minecart mixin fires its own callbacks).
        if (HopperTransferFlags.shouldSkipMinecartHopperTransfer()) {
            popCapturedStack();
            return;
        }

        // When tryTakeInItemFromSlot (regular hopper PULL) is handling the transfer,
        // skip here — the pull mixin fires its own callbacks.
        if (vialogium_shouldSkipPullTransfer()) {
            popCapturedStack();
            return;
        }

        boolean sourceIsHopper = isHopperLike(source);
        boolean destinationIsHopper = isHopperLike(destination);
        if (!sourceIsHopper && !destinationIsHopper) {
            popCapturedStack();
            return;
        }

        Container actor = resolveActor(source, destination);
        if (!isHopperLike(actor)) {
            popCapturedStack();
            return;
        }

        Level level = getLevel(source, destination);
        if (!(level instanceof ServerLevel serverLevel)) {
            popCapturedStack();
            return;
        }

        BlockPos sourcePos = getContainerPos(source);
        BlockPos destinationPos = getContainerPos(destination);
        if (sourcePos == null || destinationPos == null) {
            popCapturedStack();
            return;
        }

        ItemStack originalStack = popCapturedStack();
        if (originalStack.isEmpty()) {
            return;
        }

        ItemStack remainder = cir.getReturnValue();
        int originalCount = originalStack.getCount();
        int movedCount = originalCount - remainder.getCount();
        if (movedCount <= 0) {
            return;
        }

        ItemStack moved = originalStack.copy();
        moved.setCount(movedCount);
        if (moved.isEmpty()) {
            return;
        }

        String sourceType = getSourceType(actor);

        ItemRemoveCallback.EVENT.invoker().remove(moved.copy(), sourcePos, serverLevel, sourceType, null);
        ItemInsertCallback.EVENT.invoker().insert(moved, destinationPos, serverLevel, sourceType, null);
    }

    // ================================================================
    //  NEW: tryTakeInItemFromSlot() mixin — captures the hopper PULL path
    //  (sucking items FROM a container above INTO the hopper).
    //
    //  This is the most reliable hook for the pull direction because it
    //  fires at the exact point where an item is moved from source → hopper.
    //
    //  If we are inside a MinecartHopper.suckInItems() context, we skip
    //  entirely because MinecartHopperMixin handles that case.
    // ================================================================

    /**
     * At the HEAD of tryTakeInItemFromSlot, snapshot the item about to be pulled
     * and set the skip flag so the addItem() mixin doesn't double-log.
     * Skip entirely if MinecartHopperMixin is active.
     */
    @Inject(
            method = "tryTakeInItemFromSlot(Lnet/minecraft/world/level/block/entity/Hopper;Lnet/minecraft/world/Container;ILnet/minecraft/core/Direction;)Z",
            at = @At("HEAD")
    )
    private static void vialogium$capturePullStack(
            Hopper hopper,
            Container source,
            int slot,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        // If a MinecartHopper.suckInItems() is active, MinecartHopperMixin
        // will log the transfer — we do nothing here.
        if (HopperTransferFlags.shouldSkipMinecartHopperTransfer()) {
            return;
        }

        ItemStack stack = source.getItem(slot);
        if (!stack.isEmpty()) {
            vialogium$pendingPullStack.set(stack.copy());
        }
        vialogium_setSkipPullTransfer(true);
    }

    /**
     * At the RETURN of tryTakeInItemFromSlot, fire callbacks if the pull succeeded
     * (the method returned true).  Cleans up the skip flag and captured stack.
     */
    @Inject(
            method = "tryTakeInItemFromSlot(Lnet/minecraft/world/level/block/entity/Hopper;Lnet/minecraft/world/Container;ILnet/minecraft/core/Direction;)Z",
            at = @At("RETURN")
    )
    private static void vialogium$logPullTransfer(
            Hopper hopper,
            Container source,
            int slot,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        try {
            // If a MinecartHopper.suckInItems() is active, skip entirely —
            // MinecartHopperMixin owns the logging for that path.
            if (HopperTransferFlags.shouldSkipMinecartHopperTransfer()) {
                return;
            }

            // Only log when the pull actually transferred an item.
            if (!cir.getReturnValueZ()) {
                return;
            }

            ItemStack capturedStack = vialogium$pendingPullStack.get();
            if (capturedStack == null || capturedStack.isEmpty()) {
                return;
            }

            // Obtain the Level from the source container or the hopper.
            Level level = getLevel(source, (Container) hopper);
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }

            // Resolve the source-container position (the container above).
            BlockPos sourcePos = getContainerPos(source);
            if (sourcePos == null && source instanceof BlockEntity sourceBe) {
                sourcePos = sourceBe.getBlockPos();
            }

            // Resolve the hopper position.
            BlockPos hopperPos = getContainerPos((Container) hopper);
            if (hopperPos == null && hopper instanceof BlockEntity hopperBe) {
                hopperPos = hopperBe.getBlockPos();
            }

            if (sourcePos == null || hopperPos == null) {
                return;
            }

            // tryTakeInItemFromSlot always removes exactly 1 item via
            // source.removeItem(slot, 1).  If it returned true, that 1 item
            // was successfully placed into the hopper.
            ItemStack moved = capturedStack.copyWithCount(1);

            ItemRemoveCallback.EVENT.invoker().remove(
                    moved.copy(), sourcePos, serverLevel, Sources.HOPPER, null
            );
            ItemInsertCallback.EVENT.invoker().insert(
                    moved, hopperPos, serverLevel, Sources.HOPPER, null
            );
        } finally {
            // Always clean up thread-local state.
            vialogium$pendingPullStack.remove();
            vialogium_setSkipPullTransfer(false);
        }
    }

    private static Container resolveActor(Container source, Container destination) {
        boolean sourceIsHopper = isHopperLike(source);
        boolean destinationIsHopper = isHopperLike(destination);

        if (sourceIsHopper && !destinationIsHopper) {
            return source;
        }

        if (!sourceIsHopper && destinationIsHopper) {
            return destination;
        }

        if (sourceIsHopper) {
            return source;
        }

        return destination;
    }

    private static String getSourceType(Container actor) {
        if (actor instanceof MinecartHopper) {
            return Sources.HOPPER_MINECART;
        }

        return Sources.HOPPER;
    }

    private static boolean isHopperLike(Container container) {
        return container instanceof Hopper || container instanceof MinecartHopper;
    }

    private static ItemStack popCapturedStack() {
        Deque<ItemStack> stack = vialogium$originalStack.get();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack originalStack = stack.pop();
        if (stack.isEmpty()) {
            vialogium$originalStack.remove();
        }
        return originalStack;
    }

    @Nullable
    private static BlockPos getContainerPos(Container container) {
        if (container instanceof DoubleInventoryHelper doubleInventoryHelper) {
            BlockPos firstInventoryPos = getContainerPos(doubleInventoryHelper.getInventory(0));
            if (firstInventoryPos != null) {
                return firstInventoryPos;
            }
        }

        if (container instanceof LocationalInventory locationalInventory) {
            return locationalInventory.getLocation();
        }

        if (container instanceof Hopper hopper) {
            return BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        }

        if (container instanceof Entity entity) {
            return entity.blockPosition();
        }

        // Safety net: any BlockEntity-based container that wasn't matched by
        // LocationalInventory, Hopper, or Entity above.
        if (container instanceof BlockEntity blockEntity) {
            return blockEntity.getBlockPos();
        }

        return null;
    }

    @Nullable
    private static Level getLevel(Container source, Container destination) {
        if (source instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
            return blockEntity.getLevel();
        }

        if (destination instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
            return blockEntity.getLevel();
        }

        if (source instanceof Entity entity && entity.level() != null) {
            return entity.level();
        }

        if (destination instanceof Entity entity && entity.level() != null) {
            return entity.level();
        }

        return null;
    }
}
