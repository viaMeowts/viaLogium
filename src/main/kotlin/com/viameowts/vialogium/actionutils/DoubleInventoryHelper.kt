package com.viameowts.vialogium.actionutils

import net.minecraft.world.Container

interface DoubleInventoryHelper {
    fun getInventory(slot: Int): Container
}
