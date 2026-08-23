package com.viameowts.vialogium.network.packet.action

import com.viameowts.vialogium.actions.AbstractActionType
import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.network.packet.ViaLogiumPacketTypes
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type
import java.time.Instant

data class ActionS2CPacket(val content: ActionType) : CustomPacketPayload {
    private fun write(buf: FriendlyByteBuf?) {
        // Position
        buf?.writeBlockPos(content.pos)
        // Type
        buf?.writeUtf(content.identifier)
        // Dimension
        buf?.writeIdentifier(content.world!!)
        // Objects
        buf?.writeIdentifier(content.oldObjectIdentifier)
        buf?.writeIdentifier(content.objectIdentifier)
        // Source
        buf?.writeUtf(content.sourceProfile?.name ?: "@" + content.sourceName)
        // Epoch second of event, sent as a long
        buf?.writeLong(content.timestamp.epochSecond)
        // Has been rolled back?
        buf?.writeBoolean(content.rolledBack)
        // NBT
        buf?.writeUtf(content.extraData ?: "")
    }

    override fun type() = ID

    companion object {
        val ID: Type<ActionS2CPacket> = Type(ViaLogiumPacketTypes.ACTION.id)
        val CODEC: StreamCodec<FriendlyByteBuf, ActionS2CPacket> = CustomPacketPayload.codec(
            ActionS2CPacket::write,
        ) {
            val pos = it.readBlockPos()
            val identifier = it.readUtf()
            val world = it.readIdentifier()
            val oldObjectId = it.readIdentifier()
            val objectId = it.readIdentifier()
            val source = it.readUtf()
            val timestamp = Instant.ofEpochSecond(it.readLong())
            val rolledBack = it.readBoolean()
            val extraData = it.readUtf().ifBlank { null }

            val action = DecodedNetworkAction(identifier).apply {
                this.pos = pos
                this.world = world
                this.oldObjectIdentifier = oldObjectId
                this.objectIdentifier = objectId
                if (source.startsWith("@")) {
                    this.sourceName = source.removePrefix("@")
                    this.sourceProfile = null
                } else {
                    this.sourceName = "player"
                    this.sourceProfile = null
                }
                this.timestamp = timestamp
                this.rolledBack = rolledBack
                this.extraData = extraData
            }

            ActionS2CPacket(action)
        }

        private class DecodedNetworkAction(override val identifier: String) : AbstractActionType() {
            override fun getTranslationType(): String = "item"
        }
    }
}
