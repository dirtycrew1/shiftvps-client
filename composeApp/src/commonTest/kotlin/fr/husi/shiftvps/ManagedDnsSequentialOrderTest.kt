package fr.husi.shiftvps

import fr.husi.ktx.JSONMap
import fr.husi.shiftvps.enforceManagedRouting as applyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("UNCHECKED_CAST")
class ManagedDnsSequentialOrderTest {
    private data class Packet(
        val network: String,
        val port: Int,
        val ip: String = "8.8.8.8",
        val sniffedProtocol: String? = "dns",
        val sniffedDomain: String? = null,
        val virtualTunDns: Boolean = false,
    )
    private data class Decision(val action: String, val destination: String, val port: Int)

    private fun config(): JSONMap = mutableMapOf<String, Any?>(
        "route" to mutableMapOf("rules" to listOf(
            mapOf("protocol" to listOf("dns"), "action" to "hijack-dns"),
            mapOf("action" to "route", "outbound" to "proxy")), "final" to "proxy"),
        "dns" to mutableMapOf("servers" to listOf(mapOf("type" to "fakeip", "tag" to "dns-fake"))),
        "inbounds" to listOf(mapOf("type" to "tun", "tag" to "tun-in")),
    ).also { applyPolicy(it) }

    /** The pinned engine advances after sniff; protocol metadata starts absent and earlier rules are not retried. */
    private fun sequential(options: JSONMap, packet: Packet): Decision {
        if (packet.virtualTunDns) return Decision("virtual-tun-dns", packet.ip, packet.port)
        var protocol: String? = null
        var domain: String? = null
        val route = options["route"] as Map<String, Any?>
        for (rule in route["rules"] as List<Map<String, Any?>>) {
            if ((rule["network"] as? List<String>)?.contains(packet.network) == false ||
                (rule["port"] as? List<Int>)?.contains(packet.port) == false ||
                (rule["protocol"] as? List<String>)?.contains(protocol) == false ||
                (rule["domain"] as? List<String>)?.contains(domain) == false ||
                (rule["domain_suffix"] as? List<String>)?.any { domain == it || domain?.endsWith(".$it") == true } == false) continue
            when (rule["action"]) {
                "sniff" -> { protocol = packet.sniffedProtocol; domain = packet.sniffedDomain }
                "hijack-dns" -> return Decision("hijack-dns", packet.ip, packet.port)
                "route" -> return Decision(rule["outbound"] as String, rule["override_address"] as? String ?: packet.ip, packet.port)
            }
        }
        return Decision(route["final"] as String, packet.ip, packet.port)
    }

    @Test fun `UDP53 with initially absent DNS metadata is hijacked`() {
        assertEquals("hijack-dns", sequential(config(), Packet("udp", 53)).action)
    }
    @Test fun `TCP53 with initially absent DNS metadata is hijacked`() {
        assertEquals("hijack-dns", sequential(config(), Packet("tcp", 53)).action)
    }
    @Test fun `UDP non53 DNS is also hijacked after sniff`() {
        assertEquals("hijack-dns", sequential(config(), Packet("udp", 8853)).action)
    }
    @Test fun `TCP non53 DNS is also hijacked after sniff`() {
        assertEquals("hijack-dns", sequential(config(), Packet("tcp", 8853)).action)
    }
    @Test fun `stock virtual TUN DNS is already handled before route matching`() {
        for (port in listOf(53, 8853)) {
            assertEquals("virtual-tun-dns", sequential(config(), Packet("udp", port, virtualTunDns = true)).action)
        }
    }
    @Test fun `sniffed TLS Yandex still dials same FQDN with unchanged port`() {
        for (name in listOf("yandex.ru", "www.yandex.ru", "ya.ru")) {
            assertEquals(Decision("shiftvps-direct", name, 8443), sequential(config(), Packet("tcp", 8443,
                ip = "2a02:6b8:a::a", sniffedProtocol = "tls", sniffedDomain = name)))
        }
    }
    @Test fun `SSH TCP direct and UDP22 foreign remain unchanged`() {
        assertEquals("shiftvps-direct", sequential(config(), Packet("tcp", 22, sniffedProtocol = "ssh")).action)
        assertEquals("proxy", sequential(config(), Packet("udp", 22, sniffedProtocol = null)).action)
    }
    @Test fun `foreign TLS QUIC and literal IP remain proxied`() {
        for ((network, protocol) in listOf("tcp" to "tls", "udp" to "quic")) {
            for (domain in listOf("yandex.com", "example.com", null)) {
                assertEquals("proxy", sequential(config(), Packet(network, 443, sniffedProtocol = protocol, sniffedDomain = domain)).action)
            }
        }
    }
    @Test fun `explicit port53 hijack still handles unclassified bytes`() {
        val options = config()
        val route = (options["route"] as Map<String, Any?>).toMutableMap()
        route["rules"] = listOf(mapOf("port" to listOf(53), "action" to "hijack-dns")) + (route["rules"] as List<Map<String, Any?>>)
        options["route"] = route
        applyPolicy(options)
        assertEquals("hijack-dns", sequential(options, Packet("udp", 53, sniffedProtocol = null)).action)
    }
}
