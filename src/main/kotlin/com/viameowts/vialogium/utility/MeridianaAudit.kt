package com.viameowts.vialogium.utility

import net.fabricmc.loader.api.FabricLoader
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.function.Supplier

/**
 * Hands viaLogium's admin actions and health checks to the central audit of meridiana-core
 * (`/аудит`). A soft link by reflection: without meridiana-core every call does nothing, and
 * viaLogium has no build-time dependency on it. Contract: dev.meridiana.core.audit.AuditApi.
 */
object MeridianaAudit {
    private const val SOURCE = "vialogium"
    private var event: MethodHandle? = null
    private var check: MethodHandle? = null
    private var resolved = false

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // the audit must never break the mod
    private fun resolve() {
        if (resolved) return
        resolved = true
        if (!FabricLoader.getInstance().isModLoaded("meridiana")) return
        try {
            val api = Class.forName("dev.meridiana.core.audit.AuditApi", false, MeridianaAudit::class.java.classLoader)
            val lookup = MethodHandles.publicLookup()
            val str = String::class.java
            event = lookup.findStatic(api, "event", MethodType.methodType(Void.TYPE, str, str, str, str, str))
            check = lookup.findStatic(
                api,
                "check",
                MethodType.methodType(Void.TYPE, str, str, str, Supplier::class.java),
            )
        } catch (t: Throwable) {
            event = null
            check = null
        }
    }

    /** One entry in the central journal. level: INFO, WARN or ALERT. */
    @Suppress("TooGenericExceptionCaught")
    fun event(who: String, level: String, what: String) {
        resolve()
        try {
            event?.invoke(SOURCE, who, "action", level, what)
        } catch (_: Throwable) {
            // the audit must never break the mod
        }
    }

    /** Registers a check for `/аудит проверить`; lines may start with WARN: or ALERT:, empty list = all well. */
    @Suppress("TooGenericExceptionCaught")
    fun check(id: String, title: String, run: () -> List<String>) {
        resolve()
        try {
            check?.invoke(SOURCE, id, title, Supplier { run() })
        } catch (_: Throwable) {
            // the audit must never break the mod
        }
    }
}
