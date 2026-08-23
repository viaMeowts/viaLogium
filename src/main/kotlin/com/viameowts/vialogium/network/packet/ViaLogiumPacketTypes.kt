package com.viameowts.vialogium.network.packet

import com.viameowts.vialogium.ViaLogium
import net.minecraft.resources.Identifier

enum class ViaLogiumPacketTypes(val id: Identifier) {
    ACTION(ViaLogium.identifier("action")),
    INSPECT_POS(ViaLogium.identifier("inspect")),
    SEARCH(ViaLogium.identifier("search")),
    HANDSHAKE(ViaLogium.identifier("handshake")),
    RESPONSE(ViaLogium.identifier("response")),
    ROLLBACK(ViaLogium.identifier("rollback")),
    PURGE(ViaLogium.identifier("purge")),
}
