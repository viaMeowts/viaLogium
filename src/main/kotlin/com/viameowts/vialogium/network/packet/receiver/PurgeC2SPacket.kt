package com.viameowts.vialogium.network.packet.receiver

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.network.packet.ViaLogiumPacketTypes
import com.viameowts.vialogium.network.packet.action.ActionS2CPacket
import com.viameowts.vialogium.network.packet.response.ResponseCodes
import com.viameowts.vialogium.network.packet.response.ResponseContent
import com.viameowts.vialogium.network.packet.response.ResponseS2CPacket
import com.viameowts.vialogium.utility.inspectParams
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class PurgeC2SPacket(val pos: BlockPos, val pages: Int) : CustomPacketPayload {

    override fun type() = ID

    companion object : ServerPlayNetworking.PlayPayloadHandler<PurgeC2SPacket> {
        val ID: CustomPacketPayload.Type<PurgeC2SPacket> = CustomPacketPayload.Type(ViaLogiumPacketTypes.PURGE.id)
        val CODEC: StreamCodec<FriendlyByteBuf, PurgeC2SPacket> = CustomPacketPayload.codec({ packet, buf ->
            buf.writeBlockPos(packet.pos)
            buf.writeInt(packet.pages)
        }, {
            PurgeC2SPacket(it.readBlockPos(), it.readInt())
        })

        override fun receive(payload: PurgeC2SPacket, context: ServerPlayNetworking.Context) {
            val player = context.player()
            val sender = context.responseSender()
            if (!Permissions.check(player, "vialogium.networking", CommandConsts.PERMISSION_LEVEL) ||
                !Permissions.check(player, "vialogium.commands.purge", CommandConsts.PERMISSION_LEVEL)
            ) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(
                        ViaLogiumPacketTypes.INSPECT_POS.id,
                        ResponseCodes.NO_PERMISSION.code,
                    ),
                    sender,
                )
                return
            }
            ResponseS2CPacket.sendResponse(
                ResponseContent(ViaLogiumPacketTypes.INSPECT_POS.id, ResponseCodes.EXECUTING.code),
                sender,
            )

            val maxRequestPages = ViaLogium.config[DatabaseSpec.networkMaxPages].coerceAtLeast(1)
            val requestedPages = payload.pages.coerceIn(1, maxRequestPages)

            val params = player.inspectParams(payload.pos)
            ViaLogium.launch {
                for (i in 1..requestedPages) {
                    val page = DatabaseManager.searchActions(params, i)
                    page.actions.forEach { action ->
                        sender.sendPacket(ActionS2CPacket(action))
                    }
                    if (i >= page.pages) break
                }
                ResponseS2CPacket.sendResponse(
                    ResponseContent(ViaLogiumPacketTypes.INSPECT_POS.id, ResponseCodes.COMPLETED.code),
                    sender,
                )
            }
        }
    }
}
