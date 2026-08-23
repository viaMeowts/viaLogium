package com.viameowts.vialogium.mixin.entities;

import com.viameowts.vialogium.callbacks.ItemInsertCallback;
import com.viameowts.vialogium.callbacks.ItemRemoveCallback;
import com.viameowts.vialogium.utility.HopperTransferFlags;
import com.viameowts.vialogium.utility.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Mixin that intercepts MinecartHopper.suckInItems() to log item transfers
 * when the minecart pulls items from a container above.
 *
 * <p>Why this is needed: While HopperBlockEntity.addItem() (which handles most
 * hopper transfers) is already captured by HopperBlockEntityMixin, the hopper
 * minecart pull path calls addItem() indirectly through
 * HopperBlockEntity.suckInItems(). This mixin provides a direct hook at the
 * MinecartHopper level and uses a flag to prevent the lower-level addItem()
 * mixin from double-logging the same transfer.</p>
 */
@Mixin(MinecartHopper.class)
public abstract class MinecartHopperMixin {

    /**
     * Stores snapshots of the container-above inventory taken before suckInItems()
     * runs, keyed by thread.  A Deque is used for safety in case of re-entrant calls
     * (though this is unlikely in single-threaded game ticks).
     */
    @Unique
    private static final ThreadLocal<Deque<List<ItemStack>>> vialogium$snapshotStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Stores the BlockPos of the container above, paired with each snapshot.
     */
    @Unique
    private static final ThreadLocal<Deque<BlockPos>> vialogium$abovePosStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    // ----------------------------------------------------------------
    //  HEAD  –  take a snapshot of the container above before any items move
    // ----------------------------------------------------------------

    @Inject(
            method = "suckInItems",
            at = @At("HEAD")
    )
    private void vialogiumCaptureState(CallbackInfoReturnable<Boolean> cir) {
        MinecartHopper self = (MinecartHopper) (Object) this;
        Level level = self.level();
        if (level.isClientSide()) {
            return;
        }

        // Work out the block position directly above the minecart (same logic
        // as HopperBlockEntity.suckInItems uses).
        BlockPos abovePos = BlockPos.containing(
                self.getLevelX(),
                self.getLevelY() + 1.0,
                self.getLevelZ()
        );

        // Obtain the full container (handles double chests etc.)
        Container containerAbove = HopperBlockEntity.getContainerAt(level, abovePos);
        if (containerAbove == null) {
            return;
        }

        // Snapshot every slot
        int size = containerAbove.getContainerSize();
        List<ItemStack> snapshot = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            snapshot.add(containerAbove.getItem(i).copy());
        }

        vialogium$snapshotStack.get().push(snapshot);
        vialogium$abovePosStack.get().push(abovePos);

        // Tell HopperBlockEntityMixin to skip its own logging for addItem calls
        // that happen inside this suckInItems() invocation.
        HopperTransferFlags.setSkipMinecartHopperTransfer(true);
        }

    // ----------------------------------------------------------------
    //  RETURN  –  compare with the snapshot and fire callbacks
    // ----------------------------------------------------------------

    @Inject(
            method = "suckInItems",
            at = @At("RETURN")
    )
    private void vialogiumTrackTransfers(CallbackInfoReturnable<Boolean> cir) {
        try {
            // Only process if the method actually transferred items.
            if (!cir.getReturnValue()) {
                return;
            }

            MinecartHopper self = (MinecartHopper) (Object) this;
            Level level = self.level();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }

            Deque<List<ItemStack>> snapshots = vialogium$snapshotStack.get();
            Deque<BlockPos> positions = vialogium$abovePosStack.get();

            if (snapshots.isEmpty() || positions.isEmpty()) {
                return;
            }

            List<ItemStack> oldItems = snapshots.peek();
            BlockPos abovePos = positions.peek();

            // Re-obtain the container above to compare current state.
            Container containerAbove = HopperBlockEntity.getContainerAt(level, abovePos);
            if (containerAbove == null) {
                return;
            }

            BlockPos sourcePos = getBlockPos(containerAbove, abovePos);
            if (sourcePos == null) {
                return;
            }

            BlockPos minecartPos = self.blockPosition();

            int size = Math.min(containerAbove.getContainerSize(), oldItems.size());
            for (int i = 0; i < size; i++) {
                ItemStack oldStack = oldItems.get(i);
                ItemStack newStack = containerAbove.getItem(i);

                int removed = oldStack.getCount() - newStack.getCount();
                if (removed > 0) {
                    // The stack type should match – but if the container
                    // changed type (e.g. a different item was placed during
                    // the tick), skip this slot.
                    if (!ItemStack.isSameItem(oldStack, newStack) && !newStack.isEmpty()) {
                        continue;
                    }

                    ItemStack moved = oldStack.copyWithCount(removed);

                    ItemRemoveCallback.EVENT.invoker().remove(
                            moved.copy(), sourcePos, serverLevel, Sources.HOPPER_MINECART, null
                    );
                    ItemInsertCallback.EVENT.invoker().insert(
                            moved, minecartPos, serverLevel, Sources.HOPPER_MINECART, null
                    );
                }
            }
        } finally {
            // Always clean up the flag and snapshot stack
            HopperTransferFlags.setSkipMinecartHopperTransfer(false);

            Deque<List<ItemStack>> snapshots = vialogium$snapshotStack.get();
            Deque<BlockPos> positions = vialogium$abovePosStack.get();
            if (!snapshots.isEmpty()) {
                snapshots.pop();
            }
            if (!positions.isEmpty()) {
                positions.pop();
            }
            if (snapshots.isEmpty()) {
                vialogium$snapshotStack.remove();
            }
            if (positions.isEmpty()) {
                vialogium$abovePosStack.remove();
            }
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    /**
     * Obtain a BlockPos for the container.  Tries well-known interfaces first,
     * then falls back to the "above" position that was used to look up the
     * container.
     */
    @Nullable
    @Unique
    private static BlockPos getBlockPos(Container container, BlockPos fallback) {
        if (container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            return be.getBlockPos();
        }
        if (container instanceof net.minecraft.world.entity.Entity entity) {
            return entity.blockPosition();
        }
        // Some containers (e.g. CompoundContainer for double chests) don't
        // directly extend BlockEntity or Entity; fall back to the position
        // they were looked up from.
        return fallback;
    }
}
