package com.viameowts.vialogium.network.packet.handshake

data class HandshakeContent(val protocolVersion: Int, val vialogiumVersion: String, val actions: List<String>)
