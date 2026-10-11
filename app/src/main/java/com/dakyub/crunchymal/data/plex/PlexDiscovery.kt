package com.dakyub.crunchymal.data.plex

import com.dakyub.crunchymal.server.LocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Lecteur Plex qui a répondu à la découverte réseau (GDM). */
data class GdmPlayer(
    val address: String,
    val port: Int?,
    val name: String?,
    val product: String?,
    val version: String?,
    val identifier: String?,
    val contentType: String?,
)

/**
 * Découverte « GDM » de Plex : les lecteurs (apps Plex) répondent sur le port UDP 32412 avec leur nom et le
 * port de leur contrôle à distance. Envoi à cet appareil, au réseau local et au groupe multicast de Plex.
 */
suspend fun discoverPlexPlayers(timeoutMs: Long = 2500): List<GdmPlayer> = withContext(Dispatchers.IO) {
    val found = mutableListOf<GdmPlayer>()
    runCatching {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 300
            val message = "M-SEARCH * HTTP/1.0".toByteArray()
            listOfNotNull("127.0.0.1", LocalNetwork.ipv4(), "239.0.0.250", "255.255.255.255").distinct().forEach { host ->
                runCatching { socket.send(DatagramPacket(message, message.size, InetAddress.getByName(host), 32412)) }
            }
            val until = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val headers = String(packet.data, 0, packet.length, Charsets.UTF_8).lines().mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }.toMap()
                found += GdmPlayer(
                    address = packet.address.hostAddress.orEmpty(),
                    port = headers["port"]?.toIntOrNull(),
                    name = headers["name"],
                    product = headers["product"],
                    version = headers["version"],
                    identifier = headers["resource-identifier"],
                    contentType = headers["content-type"],
                )
            }
        }
    }
    found.distinctBy { it.address to it.identifier }
}
