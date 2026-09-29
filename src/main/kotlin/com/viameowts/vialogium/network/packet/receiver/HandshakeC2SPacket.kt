package com.viameowts.vialogium.network.packet.receiver

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.logInfo
import com.viameowts.vialogium.network.Networking
import com.viameowts.vialogium.network.Networking.enableNetworking
import com.viameowts.vialogium.network.packet.ViaLogiumPacketTypes
import com.viameowts.vialogium.network.packet.handshake.HandshakeContent
import com.viameowts.vialogium.network.packet.handshake.ModInfo
import com.viameowts.vialogium.registry.ActionRegistry
import com.viameowts.vialogium.utility.TextColorPallet
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.*

data class HandshakeC2SPacket(val nbt: CompoundTag?) : CustomPacketPayload {

    override fun type() = ID

    companion object : ServerPlayNetworking.PlayPayloadHandler<HandshakeC2SPacket> {
        val ID: CustomPacketPayload.Type<HandshakeC2SPacket> = CustomPacketPayload.Type(
            ViaLogiumPacketTypes.HANDSHAKE.id,
        )
        val CODEC: StreamCodec<FriendlyByteBuf, HandshakeC2SPacket> = CustomPacketPayload.codec({ packet, buf ->
            buf.writeNbt(packet.nbt)
        }, {
            HandshakeC2SPacket(it.readNbt())
        })

        override fun receive(payload: HandshakeC2SPacket, context: ServerPlayNetworking.Context) {
            val player = context.player()
            if (!Permissions.check(player, "vialogium.networking", CommandConsts.PERMISSION_LEVEL)) return
            // This should be sent by the client whenever a player joins with a client mod
            // We do some validation on the packet to make sure it's complete and intact
            val info = readInfo(payload.nbt)
            if (info.isPresent) {
                val modid = info.get().modid
                val modVersion = info.get().version
                val vialogiumVersion = FabricLoader.getInstance().getModContainer(
                    ViaLogium.MOD_ID,
                ).get().metadata.version.friendlyString
                if (Networking.PROTOCOL_VERSION == info.get().protocolVersion) {
                    logInfo("${player.name.string} joined the server with a ViaLogium compatible client mod")
                    logInfo("Mod: $modid, Version: $modVersion")

                    // Player has networking permissions so we send a response
                    val packet = com.viameowts.vialogium.network.packet.handshake.HandshakeS2CPacket(
                        HandshakeContent(
                            Networking.PROTOCOL_VERSION,
                            vialogiumVersion,
                            ActionRegistry.getTypes().toList(),
                        ),
                    )
                    ServerPlayNetworking.send(player, packet)
                    player.enableNetworking()
                } else {
                    player.sendSystemMessage(
                        Component.translatable(
                            "text.vialogium.network.protocols_mismatched",
                            Networking.PROTOCOL_VERSION,
                            info.get().protocolVersion,
                        ).setStyle(TextColorPallet.actionNegative),
                    )
                    logInfo(
                        "${player.name.string} joined the server with a ViaLogium compatible client mod, " +
                            "but has a mismatched protocol: " +
                            "ViaLogium protocol version: ${Networking.PROTOCOL_VERSION}" +
                            ", Client mod protocol version ${info.get().protocolVersion}",
                    )
                }
            } else {
                player.sendSystemMessage(
                    Component.translatable(
                        "text.vialogium.network.no_mod_info",
                    ).setStyle(TextColorPallet.actionNegative),
                )
            }
        }
        private fun readInfo(nbt: CompoundTag?): Optional<ModInfo> {
            if (nbt == null) {
                return Optional.empty()
            }

            return Optional.of(
                ModInfo(
                    nbt.getString("modid").orElseThrow(),
                    nbt.getString("version").orElseThrow(),
                    nbt.getInt("protocol_version").orElseThrow(),
                ),
            )
        }
    }
}
