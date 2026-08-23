package com.viameowts.vialogium.callbacks

import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level

fun interface TotemPopCallback {
    fun pop(world: Level, entity: LivingEntity, source: DamageSource)

    companion object {
        @JvmField
        val EVENT: Event<TotemPopCallback> =
            EventFactory.createArrayBacked(TotemPopCallback::class.java) { listeners ->
                TotemPopCallback { world, entity, source ->
                    for (listener in listeners) {
                        listener.pop(world, entity, source)
                    }
                }
            }
    }
}
