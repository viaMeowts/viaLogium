package com.viameowts.vialogium.testmod.commands.packet


import com.viameowts.vialogium.testmod.ViaLogiumTest
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class SearchC2SPacket(val query: String) : CustomPacketPayload {

    override fun type() = ID

    private fun write(buf: FriendlyByteBuf?) {
        buf?.writeUtf(query)
    }

    companion object {
        val ID: CustomPacketPayload.Type<SearchC2SPacket> = CustomPacketPayload.Type(ViaLogiumTest.SEARCH)
        val CODEC: StreamCodec<FriendlyByteBuf, SearchC2SPacket> =
            CustomPacketPayload.codec(SearchC2SPacket::write) { TODO() }
    }

}
