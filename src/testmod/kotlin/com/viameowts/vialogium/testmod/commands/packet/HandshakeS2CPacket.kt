package com.viameowts.vialogium.testmod.commands.packet


import com.viameowts.vialogium.testmod.ViaLogiumTest
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class HandshakeS2CPacket(val protocolVersion: Int, val vialogiumVersion: String, val actionTypes: Collection<String>) :
    CustomPacketPayload {

    override fun type() = ID

    companion object : ClientPlayNetworking.PlayPayloadHandler<HandshakeS2CPacket> {
        val ID: CustomPacketPayload.Type<HandshakeS2CPacket> = CustomPacketPayload.Type(ViaLogiumTest.HANDSHAKE)
        val CODEC: StreamCodec<FriendlyByteBuf, HandshakeS2CPacket> =
            CustomPacketPayload.codec({ _, _ -> TODO() }, {
                val protocolVersion = it.readInt()
                val vialogiumVersion = it.readUtf()
                val actionsLength = it.readInt()
                val actionTypes: MutableList<String> = mutableListOf()
                for (i in 0..actionsLength) {
                    actionTypes.add(it.readUtf())
                }
                HandshakeS2CPacket(protocolVersion, vialogiumVersion, actionTypes)
            })

        override fun receive(payload: HandshakeS2CPacket, context: ClientPlayNetworking.Context?) {
            ViaLogiumTest.LOGGER.info("Protocol version: {}", payload.protocolVersion)
            ViaLogiumTest.LOGGER.info("ViaLogium version: {}", payload.vialogiumVersion)
            ViaLogiumTest.LOGGER.info("Number of types registered: {}", payload.actionTypes.size)
            payload.actionTypes.forEach {
                ViaLogiumTest.LOGGER.info("Action type: {}", it)
            }
        }
    }

}
