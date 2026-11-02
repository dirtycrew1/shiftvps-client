package fr.husi.shiftvps

import fr.husi.ktx.JSONMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShiftVpsPolicyTest {
    @Test
    fun `managed ru DNS bypass precedes fake DNS and proxy DNS`() {
        val options: JSONMap = mutableMapOf(
            "dns" to mutableMapOf<String, Any?>(
                "servers" to listOf(mapOf("tag" to MANAGED_DNS_TAG, "type" to "https", "server" to "untrusted.invalid")),
                "rules" to listOf(mapOf("server" to "dns-fake"), mapOf("server" to "dns-proxy")),
            ),
        )
        enforceManagedRouting(options)
        val dns = options["dns"] as Map<*, *>
        val firstRule = (dns["rules"] as List<*>).first() as Map<*, *>
        assertEquals(listOf("ru"), firstRule["domain_suffix"])
        assertEquals(MANAGED_DNS_TAG, firstRule["server"])
        assertEquals(listOf(mapOf("type" to "local", "tag" to MANAGED_DNS_TAG)), dns["servers"])
    }

    @Test
    fun `managed route follows sniff but precedes catch all proxy rules`() {
        val options: JSONMap = mutableMapOf(
            "route" to mutableMapOf<String, Any?>("rules" to listOf(
                mapOf("action" to "sniff"),
                mapOf("action" to "hijack-dns", "protocol" to listOf("dns")),
                mapOf("action" to "route", "outbound" to "proxy"),
            )),
            "outbounds" to listOf(mapOf("type" to "socks", "tag" to MANAGED_DIRECT_TAG)),
        )
        enforceManagedRouting(options)
        val rules = (options["route"] as Map<*, *>)["rules"] as List<*>
        assertEquals("sniff", (rules[0] as Map<*, *>)["action"])
        assertEquals("hijack-dns", (rules[1] as Map<*, *>)["action"])
        assertEquals(listOf("ru"), (rules[2] as Map<*, *>)["domain_suffix"])
        assertEquals(MANAGED_DIRECT_TAG, (rules[2] as Map<*, *>)["outbound"])
        assertEquals("proxy", (rules[3] as Map<*, *>)["outbound"])
        val direct = (options["outbounds"] as List<*>).single() as Map<*, *>
        assertEquals("direct", direct["type"])
        assertEquals(mapOf("server" to MANAGED_DNS_TAG), direct["domain_resolver"])
    }

    @Test
    fun `remote policy cannot disable ru bypass or select arbitrary DNS and probe origins`() {
        assertFailsWith<IllegalArgumentException> { ShiftVpsPolicy(routing = ShiftVpsRouting(emptyList())).validated() }
        assertFailsWith<IllegalArgumentException> { ShiftVpsPolicy(dns = ShiftVpsDns(proxy = "https://attacker.invalid")).validated() }
        assertFailsWith<IllegalArgumentException> { ShiftVpsPolicy(latency = ShiftVpsLatency(testUrl = "http://127.0.0.1/")).validated() }
        assertFailsWith<IllegalArgumentException> { ShiftVpsPolicy(latency = ShiftVpsLatency(concurrency = 1000)).validated() }
        assertFailsWith<IllegalArgumentException> { ShiftVpsPolicy(refreshIntervalSeconds = 1).validated() }
    }

    @Test
    fun `reachable servers rank by actual delay and unavailable records remain last`() {
        val ranked = rankServers(listOf(
            ShiftVpsServer(4, "Offline", reachable = false),
            ShiftVpsServer(3, "Unknown"),
            ShiftVpsServer(2, "Slow", latencyMs = 400, reachable = true),
            ShiftVpsServer(1, "Fast", latencyMs = 50, reachable = true),
        ))
        assertEquals(listOf(1L, 2L, 3L, 4L), ranked.map { it.id })
    }
}
