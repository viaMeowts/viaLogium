package com.viameowts.vialogium.actionutils

import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.config.SearchSpec
import com.viameowts.vialogium.utility.Negatable
import com.viameowts.vialogium.utility.ServerIdentity
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.time.Instant
import java.util.*
import kotlin.math.max

data class ActionSearchParams(
    val bounds: BoundingBox?,
    val before: Instant?,
    val after: Instant?,
    val rolledBack: Boolean?,
    var actions: MutableSet<Negatable<String>>?,
    var objects: MutableSet<Negatable<Identifier>>?,
    var sourceNames: MutableSet<Negatable<String>>?,
    var sourcePlayerIds: MutableSet<Negatable<UUID>>?,
    var worlds: MutableSet<Negatable<Identifier>>?,
    /** null = this server only; see ServerIdentity. */
    var servers: MutableSet<Negatable<String>>? = null,
) {
    private constructor(builder: Builder) : this(
        builder.bounds,
        builder.before,
        builder.after,
        builder.rolledBack,
        builder.actions,
        builder.objects,
        builder.sourceNames,
        builder.sourcePlayerIds,
        builder.worlds,
        builder.servers,
    )

    fun ensureSpecific() {
        // Rollback/restore change this server's worlds; asking for another server is refused rather
        // than silently applied here.
        val otherServer = servers?.firstOrNull { !(it.allowed && it.property == ServerIdentity.id) }
        if (otherServer != null) {
            throw SimpleCommandExceptionType(
                Component.translatable("error.vialogium.other_server", ServerIdentity.id),
            ).create()
        }
        if (bounds == null) {
            throw SimpleCommandExceptionType(Component.translatable("error.vialogium.unspecific.range")).create()
        }
        val range = (max(bounds.xSpan, max(bounds.ySpan, bounds.zSpan)) + 1) / 2
        if (range > ViaLogium.config[SearchSpec.maxRange] && bounds != GLOBAL) {
            throw SimpleCommandExceptionType(
                Component.translatable(
                    "error.vialogium.unspecific.range_to_big",
                    ViaLogium.config[SearchSpec.maxRange],
                ),
            ).create()
        }
        if (sourceNames == null && sourcePlayerIds == null && after == null && before == null) {
            throw SimpleCommandExceptionType(
                Component.translatable("error.vialogium.unspecific.source_or_time"),
            ).create()
        }
    }

    /** Rollback, restore and previews change this server's worlds, so they never see other servers. */
    fun localOnly(): ActionSearchParams = if (servers == null) this else copy(servers = null)

    companion object {
        val GLOBAL: BoundingBox =
            BoundingBox(-Int.MAX_VALUE, -Int.MAX_VALUE, -Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        inline fun build(block: Builder.() -> Unit) = Builder().apply(block).build()
    }

    class Builder {
        var bounds: BoundingBox? = null
        var before: Instant? = null
        var after: Instant? = null
        var rolledBack: Boolean? = null
        var actions: MutableSet<Negatable<String>>? = null
        var objects: MutableSet<Negatable<Identifier>>? = null
        var sourceNames: MutableSet<Negatable<String>>? = null
        var sourcePlayerIds: MutableSet<Negatable<UUID>>? = null
        var worlds: MutableSet<Negatable<Identifier>>? = null
        var servers: MutableSet<Negatable<String>>? = null

        fun build() = ActionSearchParams(this)
    }
}
