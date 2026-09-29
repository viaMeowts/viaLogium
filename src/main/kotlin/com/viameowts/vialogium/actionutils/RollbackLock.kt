package com.viameowts.vialogium.actionutils

import com.viameowts.vialogium.utility.TextColorPallet
import kotlinx.coroutines.Job
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One rollback or restore at a time per server. Two runs over overlapping areas would each apply
 * the same actions on top of the other and leave the world and the rolled_back flags out of step.
 */
object RollbackLock {
    private val owner = AtomicReference<String?>(null)

    /** Null (and a message to [source]) when another rollback or restore is still running. */
    fun acquire(source: CommandSourceStack): Ticket? = acquire(source.textName) ?: run {
        source.sendFailure(
            Component.translatable("error.vialogium.rollback_in_progress", owner.get() ?: "?")
                .setStyle(TextColorPallet.actionNegative),
        )
        null
    }

    fun acquire(name: String): Ticket? = if (owner.compareAndSet(null, name)) Ticket() else null

    class Ticket internal constructor() {
        private val handedOff = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        /** The run continues in [job] (the main-thread part); the lock is freed when it ends. */
        fun handOff(job: Job?) {
            handedOff.set(true)
            if (job == null) release() else job.invokeOnCompletion { release() }
        }

        /** For the coroutine that did the counting: frees the lock unless the run was handed off. */
        fun releaseUnlessHandedOff() {
            if (!handedOff.get()) release()
        }

        private fun release() {
            if (released.compareAndSet(false, true)) owner.set(null)
        }
    }
}
