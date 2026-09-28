package com.viameowts.vialogium.actions

import com.viameowts.vialogium.actionutils.Preview
import com.viameowts.vialogium.utility.MessageUtils
import com.viameowts.vialogium.utility.ServerIdentity
import com.viameowts.vialogium.utility.Sources
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.isCreativeFlagged
import com.viameowts.vialogium.utility.literal
import com.viameowts.vialogium.utility.markCreative
import com.viameowts.vialogium.utility.wrapCreative
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.util.Util
import net.minecraft.world.level.Level
import java.time.Instant
import java.util.*
import kotlin.time.ExperimentalTime

abstract class AbstractActionType : ActionType {
    private val positiveActions = setOf(
        "block-place",
        "item-insert",
        "frame-insert",
        "entity-mount",
        "player-join",
    )

    private val negativeActions = setOf(
        "block-break",
        "item-remove",
        "item-pick-up",
        "item-drop",
        "frame-remove",
        "entity-kill",
        "entity-dismount",
        "player-kill",
        "totem-pop",
        "player-leave",
    )

    override var id: Int = -1
    override var timestamp: Instant = Instant.now()
    override var pos: BlockPos = BlockPos.ZERO
    override var world: Identifier? = null
    override var objectIdentifier: Identifier = Identifier.withDefaultNamespace("air")
    override var oldObjectIdentifier: Identifier = Identifier.withDefaultNamespace("air")
    override var objectState: String? = null
    override var oldObjectState: String? = null
    override var sourceName: String = Sources.UNKNOWN
    override var sourceProfile: NameAndId? = null

    // Backing for extraData with deferred NBT->text encoding (see deferExtraData). While a tag is
    // pending, the string form is produced lazily on first read — which normally happens on the DB
    // thread at insert time rather than on the server tick.
    private var extraDataString: String? = null
    private var pendingExtraDataTag: CompoundTag? = null
    override var extraData: String?
        get() {
            pendingExtraDataTag?.let {
                extraDataString = it.toString()
                pendingExtraDataTag = null
            }
            return extraDataString
        }
        set(value) {
            extraDataString = value
            pendingExtraDataTag = null
        }

    override fun deferExtraData(tag: CompoundTag?) {
        extraDataString = null
        pendingExtraDataTag = tag
    }

    override fun flagCreative() {
        val tag = pendingExtraDataTag
        if (tag != null) {
            // Wrap the still-pending NBT at the tag level (serialized to text only once, later).
            // This avoids markCreative's SNBT re-parse, which can fail for complex entity NBT and
            // silently drop the whole payload.
            pendingExtraDataTag = wrapCreative(tag)
        } else {
            extraDataString = markCreative(extraDataString)
        }
    }

    override var rolledBack: Boolean = false
    override var serverId: String = ""

    override fun rollback(server: MinecraftServer): Boolean = false
    override fun previewRollback(preview: Preview, player: ServerPlayer) = Unit
    override fun previewRestore(preview: Preview, player: ServerPlayer) = Unit
    override fun restore(server: MinecraftServer): Boolean = false

    @ExperimentalTime
    override fun getMessage(source: CommandSourceStack): Component {
        val message = Component.translatable(
            "text.vialogium.action_message",
            getTimeMessage(),
            getSourceMessage(),
            getActionMessage(),
            getObjectMessage(source),
            getLocationMessage(),
        )
        message.style = TextColorPallet.light

        if (rolledBack) {
            message.withStyle(ChatFormatting.STRIKETHROUGH)
        }

        return message
    }

    @ExperimentalTime
    open fun getTimeMessage(): Component = MessageUtils.instantToText(timestamp)

    open fun getSourceMessage(): Component {
        val creativeMarker = if (isCreativeFlagged(extraData) && sourceProfile != null) {
            " *".literal().withStyle(ChatFormatting.LIGHT_PURPLE)
        } else {
            Component.empty()
        }

        if (sourceProfile == null) {
            return "@$sourceName".literal().setStyle(TextColorPallet.secondary)
        }

        if (sourceName == Sources.PLAYER) {
            return sourceProfile!!.name.literal().setStyle(TextColorPallet.secondary).append(creativeMarker)
        }

        return "@$sourceName (${sourceProfile!!.name})".literal().setStyle(
            TextColorPallet.secondary,
        ).append(creativeMarker)
    }

    open fun getActionMessage(): Component = Component.translatable("text.vialogium.action.$identifier")
        .setStyle(getActionStyle())
        .withStyle {
            it.withHoverEvent(
                HoverEvent.ShowText(
                    identifier.literal(),
                ),
            )
        }

    private fun getActionStyle() = when {
        positiveActions.contains(identifier) -> TextColorPallet.actionPositive
        negativeActions.contains(identifier) -> TextColorPallet.actionNegative
        else -> TextColorPallet.secondary
    }

    open fun getObjectMessage(source: CommandSourceStack): Component = Component.translatable(
        Util.makeDescriptionId(
            this.getTranslationType(),
            objectIdentifier,
        ),
    ).setStyle(TextColorPallet.secondaryVariant).withStyle {
        it.withHoverEvent(
            HoverEvent.ShowText(
                objectIdentifier.toString().literal(),
            ),
        )
    }

    open fun getLocationMessage(): Component {
        if (!ServerIdentity.isLocal(serverId)) {
            // Another server's world: show where it was, but there is nothing here to teleport to.
            return "$serverId ${pos.x} ${pos.y} ${pos.z}".literal()
                .setStyle(TextColorPallet.secondary)
                .withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal("$serverId\n${world ?: ""}"))) }
        }
        return getLocalLocationMessage()
    }

    private fun getLocalLocationMessage(): Component = "${pos.x} ${pos.y} ${pos.z}".literal()
        .setStyle(TextColorPallet.secondary)
        .withStyle {
            val tag = CompoundTag()
            tag.putInt("x", pos.x)
            tag.putInt("y", pos.y)
            tag.putInt("z", pos.z)
            tag.putString("world", (world ?: Level.OVERWORLD.identifier()).toString())
            it.withHoverEvent(
                HoverEvent.ShowText(
                    Component.literal(world?.let { "$it\n" } ?: "")
                        .append(Component.translatable("text.vialogium.action_message.location.hover")),
                ),
            ).withClickEvent(
                ClickEvent.Custom(MessageUtils.teleportAction, Optional.of(tag)),
            )
        }
}
