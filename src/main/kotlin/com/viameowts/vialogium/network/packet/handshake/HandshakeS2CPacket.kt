package com.viameowts.vialogium.network.packet.handshake

import com.viameowts.vialogium.network.packet.ViaLogiumPacketTypes
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class HandshakeS2CPacket(val content: HandshakeContent) : CustomPacketPayload {
    fun write(buf: FriendlyByteBuf?) {
        // ViaLogium information
        // Protocol Version
        buf?.writeInt(content.protocolVersion)

        // ViaLogium Version
        buf?.writeUtf(content.vialogiumVersion)

        // We tell the client mod how many actions we are writing
        buf?.writeInt(content.actions.size)

        for (action in content.actions) {
            buf?.writeUtf(action)
        }
    }

    override fun type() = ID

    companion object {
        val ID: CustomPacketPayload.Type<HandshakeS2CPacket> = CustomPacketPayload.Type(
            ViaLogiumPacketTypes.HANDSHAKE.id,
        )
        val CODEC: StreamCodec<FriendlyByteBuf, HandshakeS2CPacket> = CustomPacketPayload.codec(
            HandshakeS2CPacket::write,
        ) { buf ->
            val protocolVersion = buf.readInt()
            val vialogiumVersion = buf.readUtf()
            val actionsLength = buf.readInt().coerceAtLeast(0)
            val actionTypes = mutableListOf<String>()
            repeat(actionsLength) {
                actionTypes.add(buf.readUtf())
            }
            HandshakeS2CPacket(HandshakeContent(protocolVersion, vialogiumVersion, actionTypes))
        }
    }
}
