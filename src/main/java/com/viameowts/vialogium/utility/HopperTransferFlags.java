package com.viameowts.vialogium.utility;

/**
 * ThreadLocal flags shared between HopperBlockEntityMixin and MinecartHopperMixin
 * to coordinate skip logic and prevent duplicate logging during hopper transfers.
 */
public final class HopperTransferFlags {

    private static final ThreadLocal<Boolean> skipMinecartHopperTransfer = ThreadLocal.withInitial(() -> false);

    private HopperTransferFlags() {}

    public static boolean shouldSkipMinecartHopperTransfer() {
        return skipMinecartHopperTransfer.get();
    }

    public static void setSkipMinecartHopperTransfer(boolean skip) {
        if (skip) {
            skipMinecartHopperTransfer.set(true);
        } else {
            skipMinecartHopperTransfer.remove();
        }
    }
}
