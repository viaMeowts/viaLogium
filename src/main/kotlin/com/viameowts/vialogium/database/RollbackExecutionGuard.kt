package com.viameowts.vialogium.database

object RollbackExecutionGuard {
    private val depth = ThreadLocal.withInitial { 0 }

    fun isActive(): Boolean = depth.get() > 0

    fun <T> runWithoutLogging(block: () -> T): T {
        depth.set(depth.get() + 1)
        return try {
            block()
        } finally {
            val next = depth.get() - 1
            if (next <= 0) {
                depth.remove()
            } else {
                depth.set(next)
            }
        }
    }
}
