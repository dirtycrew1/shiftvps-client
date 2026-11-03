package fr.husi.shiftvps

import fr.husi.ktx.JSONMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
        val ru = rules.map { it as Map<*, *> }.single { "domain_suffix" in it }
        assertEquals(listOf("ru"), ru["domain_suffix"])
        assertEquals(MANAGED_DIRECT_TAG, ru["outbound"])
        assertEquals(mapOf("network" to listOf("tcp"), "port" to listOf(22),
            "action" to "route", "outbound" to MANAGED_DIRECT_TAG), rules[rules.lastIndex-1])
        assertEquals("proxy", (rules.last() as Map<*, *>)["outbound"])
        val direct = (options["outbounds"] as List<*>).single() as Map<*, *>
        assertEquals("direct", direct["type"])
        assertEquals(mapOf("server" to MANAGED_DNS_TAG,"strategy" to "prefer_ipv4"), direct["domain_resolver"])
    }

    @Test
    fun `SSH bypass covers all destination names and IP families without bypassing foreign HTTPS or UDP`() {
        val proxy = mapOf("action" to "route", "outbound" to "proxy")
        val options: JSONMap = mutableMapOf("route" to mapOf("rules" to listOf(proxy)))
        enforceManagedRouting(options)
        val rules = (options["route"] as Map<*, *>)["rules"] as List<*>
        val ssh = rules.map { it as Map<*, *> }.single { "port" in it }
        assertEquals(setOf("network", "port", "action", "outbound"), ssh.keys)
        assertEquals(listOf("tcp"), ssh["network"])
        assertEquals(listOf(22), ssh["port"])
        assertEquals(MANAGED_DIRECT_TAG, ssh["outbound"])
        assertFalse((ssh["port"] as List<*>).contains(443))
        assertFalse((ssh["network"] as List<*>).contains("udp"))
        assertEquals(listOf("ru"), rules.map { it as Map<*, *> }.single { "domain_suffix" in it }["domain_suffix"])
        assertEquals(proxy, rules.last())
    }

    @Test
    fun `SSH and ru routing guards follow all preprocessing actions before custom routes`() {
        val preprocessing = listOf("sniff", "hijack-dns", "route-options", "resolve").map { mapOf("action" to it) }
        val proxy = mapOf("action" to "route", "outbound" to "custom-proxy")
        val options: JSONMap = mutableMapOf("route" to mapOf("rules" to preprocessing + proxy))
        enforceManagedRouting(options)
        val rules = (options["route"] as Map<*, *>)["rules"] as List<*>
        assertEquals(preprocessing, rules.take(4))
        assertEquals(listOf("ru"), rules.map { it as Map<*, *> }.single { "domain_suffix" in it }["domain_suffix"])
        assertEquals(listOf(22), (rules[rules.lastIndex-1] as Map<*, *>)["port"])
        assertEquals(proxy, rules.last())
    }

    @Test
    fun `empty custom configuration still receives both managed routes and the direct resolver`() {
        val options: JSONMap = mutableMapOf()
        enforceManagedRouting(options)
        val rules = (options["route"] as Map<*, *>)["rules"] as List<*>
        assertEquals(6, rules.size)
        assertEquals(listOf("ru"), rules.map { it as Map<*, *> }.single { "domain_suffix" in it }["domain_suffix"])
        assertEquals(listOf(22), (rules.last() as Map<*, *>)["port"])
        val direct = (options["outbounds"] as List<*>).single() as Map<*, *>
        assertEquals("direct", direct["type"])
        assertEquals(mapOf("server" to MANAGED_DNS_TAG,"strategy" to "prefer_ipv4"), direct["domain_resolver"])
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
