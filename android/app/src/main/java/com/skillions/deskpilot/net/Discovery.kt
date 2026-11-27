package com.skillions.deskpilot.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class DiscoveredServer(
    val id: String, val name: String, val host: String, val port: Int,
    val os: String, val mac: String, val protected: Boolean,
)

/** Automatic server detection: UDP broadcast "DESKPILOT_DISCOVER" -> port 9511, collect JSON replies. */
object Discovery {
    private const val PORT = 9511
    private val MAGIC = "DESKPILOT_DISCOVER".toByteArray()

    suspend fun scan(context: Context, windowMs: Int = 1800): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("deskpilot-discovery")?.apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, DiscoveredServer>()
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                repeat(2) { targets.forEach { runCatching { sock.send(DatagramPacket(MAGIC, MAGIC.size, it, PORT)) } } }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + windowMs
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    runCatching {
                        val o = Json.parseToJsonElement(String(p.data, 0, p.length)).jsonObject
                        if (o["app"]?.jsonPrimitive?.content != "deskpilot") return@runCatching
                        val s = DiscoveredServer(
                            id = o["id"]!!.jsonPrimitive.content,
                            name = o["name"]?.jsonPrimitive?.content ?: p.address.hostAddress.orEmpty(),
                            host = p.address.hostAddress.orEmpty(),
                            port = o["port"]?.jsonPrimitive?.intOrNull ?: 9512,
                            os = o["os"]?.jsonPrimitive?.content ?: "",
                            mac = o["mac"]?.jsonPrimitive?.content ?: "",
                            protected = o["protected"]?.jsonPrimitive?.booleanOrNull ?: false,
                        )
                        found[s.id] = s
                    }
                }
            }
        } finally {
            lock?.release()
        }
        found.values.toList()
    }

    fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}

/** Wake On LAN: magic packet = 6 x 0xFF + 16 x MAC, sent to UDP 9 and 7 on every broadcast address. */
object WakeOnLan {
    suspend fun wake(mac: String) = withContext(Dispatchers.IO) {
        val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        require(bytes.size == 6) { "Invalid MAC address" }
        val packet = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { bytes.toList() }
        DatagramSocket().use { s ->
            s.broadcast = true
            val targets = Discovery.broadcastAddresses() + InetAddress.getByName("255.255.255.255")
            for (t in targets) for (port in intArrayOf(9, 7)) {
                runCatching { s.send(DatagramPacket(packet, packet.size, t, port)) }
            }
        }
    }
}
