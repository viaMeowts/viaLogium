package com.viameowts.vialogium.utility

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser

private const val CREATIVE_FLAG_KEY = "vialogium_creative"
private const val PAYLOAD_KEY = "vialogium_payload"

fun parseExtraTag(raw: String?): CompoundTag? {
    if (raw == null) return null
    return try {
        TagParser.parseCompoundFully(raw)
    } catch (_: CommandSyntaxException) {
        null
    }
}

fun parseExtraPayload(raw: String?): CompoundTag? {
    val tag = parseExtraTag(raw) ?: return null
    return if (tag.contains(PAYLOAD_KEY)) {
        tag.getCompound(PAYLOAD_KEY).orElse(tag)
    } else {
        tag
    }
}

fun markCreative(raw: String?): String {
    val payload = parseExtraTag(raw)
    return if (payload == null) {
        CompoundTag().apply {
            putBoolean(CREATIVE_FLAG_KEY, true)
        }.toString()
    } else {
        wrapCreative(payload).toString()
    }
}

/**
 * Wrap a payload [CompoundTag] with the creative flag at the tag level — no string round-trip.
 * Preferred over [markCreative] when the original NBT is still available as a tag: re-parsing
 * SNBT (as markCreative does) can fail for complex NBT (e.g. full entity data), silently dropping
 * the payload. Pairs with [parseExtraPayload], which unwraps it.
 */
fun wrapCreative(payload: CompoundTag): CompoundTag = CompoundTag().apply {
    putBoolean(CREATIVE_FLAG_KEY, true)
    put(PAYLOAD_KEY, payload)
}

fun isCreativeFlagged(raw: String?): Boolean = parseExtraTag(raw)?.getBoolean(CREATIVE_FLAG_KEY)?.orElse(false) == true
