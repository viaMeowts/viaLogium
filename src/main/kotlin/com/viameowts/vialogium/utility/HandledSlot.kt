package com.viameowts.vialogium.utility

import net.minecraft.world.inventory.AbstractContainerMenu

interface HandledSlot {
    fun getHandler(): AbstractContainerMenu
    fun setHandler(handler: AbstractContainerMenu)
}
