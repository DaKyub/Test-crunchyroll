package com.dakyub.crunchymal.server

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
    /** Adresse IPv4 de la Shield sur le réseau local (Wi-Fi ou Ethernet), ou null. */
    fun ipv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .sortedBy { if (it.isSiteLocalAddress) 0 else 1 }
            .firstOrNull()?.hostAddress
    }.getOrNull()
}
