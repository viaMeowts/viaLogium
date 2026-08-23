package com.viameowts.vialogium.network.packet.receiver

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.commands.arguments.SearchParamArgument
import com.viameowts.vialogium.config.DatabaseSpec
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.network.packet.ViaLogiumPacketTypes
import com.viameowts.vialogium.network.packet.response.ResponseCodes
import com.viameowts.vialogium.network.packet.response.ResponseContent
import com.viameowts.vialogium.network.packet.response.ResponseS2CPacket
import com.viameowts.vialogium.utility.MessageUtils
import com.viameowts.vialogium.utility.TextColorPallet
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class SearchC2SPacket(val args: String, val pages: Int) : CustomPacketPayload {

    override fun type() = ID

    companion object : ServerPlayNetworking.PlayPayloadHandler<SearchC2SPacket> {
        private const val MAX_ARGS_LENGTH = 4096

        val ID: CustomPacketPayload.Type<SearchC2SPacket> = CustomPacketPayload.Type(ViaLogiumPacketTypes.SEARCH.id)
        val CODEC: StreamCodec<FriendlyByteBuf, SearchC2SPacket> = CustomPacketPayload.codec({ packet, buf ->
            buf.writeUtf(packet.args, MAX_ARGS_LENGTH)
            buf.writeInt(packet.pages)
        }, {
            SearchC2SPacket(it.readUtf(MAX_ARGS_LENGTH), it.readInt())
        })

        override fun receive(payload: SearchC2SPacket, context: ServerPlayNetworking.Context) {
            val player = context.player()
            val sender = context.responseSender()
            if (!Permissions.check(player, "vialogium.networking", CommandConsts.PERMISSION_LEVEL) ||
                !Permissions.check(player, "vialogium.commands.search", CommandConsts.PERMISSION_LEVEL)
            ) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(
                        ViaLogiumPacketTypes.SEARCH.id,
                        ResponseCodes.NO_PERMISSION.code,
                    ),
                    sender,
                )
                return
            }

            val source = player.createCommandSourceStack()

            val params = runCatching { SearchParamArgument.get(payload.args, source) }.getOrElse {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(ViaLogiumPacketTypes.SEARCH.id, ResponseCodes.ERROR.code),
                    sender,
                )
                return
            }

            val maxRequestPages = ViaLogium.config[DatabaseSpec.networkMaxPages].coerceAtLeast(1)
            val requestedPages = payload.pages.coerceIn(1, maxRequestPages)

            ResponseS2CPacket.sendResponse(
                ResponseContent(ViaLogiumPacketTypes.SEARCH.id, ResponseCodes.EXECUTING.code),
                sender,
            )

            ViaLogium.launch {
                ViaLogium.searchCache[source.textName] = params

                MessageUtils.warnBusy(source)
                val results = DatabaseManager.searchActions(params, 1)

                for (i in 1..requestedPages) {
                    val page = DatabaseManager.searchActions(results.searchParams, i)
                    MessageUtils.sendSearchResults(
                        source,
                        page,
                        Component.translatable(
                            "text.vialogium.header.search",
                        ).setStyle(TextColorPallet.primary),
                    )
                }

                ResponseS2CPacket.sendResponse(
                    ResponseContent(
                        ViaLogiumPacketTypes.SEARCH.id,
                        ResponseCodes.COMPLETED.code,
                    ),
                    sender,
                )
            }
        }
    }
}
