package fr.husi.shiftvps

import fr.husi.ktx.JSONMap
import fr.husi.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftVpsPolicyTest {
    @Test
    fun `Cyrillic RF domains share the direct route and local resolver with ru`() {
        assertEquals("xn--p1ai", java.net.IDN.toASCII("рф"))
        val options: JSONMap = mutableMapOf("inbounds" to listOf(mapOf("type" to "tun")))
        enforceManagedRouting(options)
        val route = ((options["route"] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }
            .single { it["domain_suffix"] == MANAGED_DIRECT_SUFFIXES }
        assertEquals(listOf("ru", "xn--p1ai"), route["domain_suffix"])
        assertEquals(MANAGED_DIRECT_TAG, route["outbound"])
        val dns = ((options["dns"] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }
        assertEquals(2, dns.count { it["domain_suffix"] == listOf("ru", "xn--p1ai") })
    }
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
        assertEquals(listOf("ru", "xn--p1ai"), firstRule["domain_suffix"])
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
        val ru = rules.map { it as Map<*, *> }.single { it["domain_suffix"] == MANAGED_DIRECT_SUFFIXES }
        assertEquals(listOf("ru", "xn--p1ai"), ru["domain_suffix"])
        assertEquals(MANAGED_DIRECT_TAG, ru["outbound"])
        assertEquals(mapOf("network" to listOf("tcp"), "port" to listOf(22),
            "action" to "route", "outbound" to MANAGED_DIRECT_TAG),
            rules.dropLast(1).map { it as Map<*, *> }.single { it["port"] == listOf(22) })
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
        val ssh = rules.map { it as Map<*, *> }.single { it["port"] == listOf(22) }
        assertEquals(setOf("network", "port", "action", "outbound"), ssh.keys)
        assertEquals(listOf("tcp"), ssh["network"])
        assertEquals(listOf(22), ssh["port"])
        assertEquals(MANAGED_DIRECT_TAG, ssh["outbound"])
        assertFalse((ssh["port"] as List<*>).contains(443))
        assertFalse((ssh["network"] as List<*>).contains("udp"))
        assertTrue(rules.dropLast(1).map { it as Map<*, *> }.any { it["domain_suffix"] == MANAGED_DIRECT_SUFFIXES })
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
        assertTrue(rules.dropLast(1).map { it as Map<*, *> }.any { it["domain_suffix"] == MANAGED_DIRECT_SUFFIXES })
        assertTrue(rules.dropLast(1).map { it as Map<*, *> }.any { it["port"] == listOf(22) })
        assertEquals(proxy, rules.last())
    }

    @Test
    fun `empty custom configuration still receives both managed routes and the direct resolver`() {
        val options: JSONMap = mutableMapOf()
        enforceManagedRouting(options)
        val rules = (options["route"] as Map<*, *>)["rules"] as List<*>
        assertTrue(rules.map { it as Map<*, *> }.any { it["domain_suffix"] == MANAGED_DIRECT_SUFFIXES })
        assertTrue(rules.map { it as Map<*, *> }.any { it["port"] == listOf(22) })
        val direct = (options["outbounds"] as List<*>).single() as Map<*, *>
        assertEquals("direct", direct["type"])
        assertEquals(mapOf("server" to MANAGED_DNS_TAG,"strategy" to "prefer_ipv4"), direct["domain_resolver"])
    }

    @Test
    fun `FCM uses only the push hosts or Google services on push ports`() {
        val options: JSONMap = mutableMapOf()
        enforceManagedRouting(options, Platform.Android)
        val routes = ((options["route"] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }
        val gms = routes.single { "package_name" in it }
        assertEquals(listOf("com.google.android.gms"), gms["package_name"])
        assertEquals(listOf(5228, 5229, 5230), gms["port"])
        val fcm = routes.single { (it["domain"] as? List<*>)?.contains("mtalk.google.com") == true }
        assertEquals(listOf(5228, 5229, 5230, 443), fcm["port"])
        assertTrue(listOf(fcm, gms).all { it["outbound"] == MANAGED_DIRECT_TAG && it["network"] == listOf("tcp") })
        val dns = ((options["dns"] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }
        assertTrue(dns.any { it["domain"] == fcm["domain"] && it["server"] == MANAGED_DNS_TAG })
    }

    @Test
    fun `desktop routes match a verified app path and keep FCM rules off desktop`() {
        val options: JSONMap = mutableMapOf()
        enforceManagedRouting(options, Platform.Windows)
        val routes = ((options["route"] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }
        val paths = routes.single { "process_path_regex" in it }["process_path_regex"] as List<*>
        val expressions = paths.map { Regex(it as String) }
        assertTrue(expressions.any { it.containsMatchIn("C:\\Users\\Demo\\AppData\\Local\\Yandex\\YandexBrowser\\Application\\browser.exe") })
        assertTrue(expressions.any { it.containsMatchIn("C:\\Users\\Demo\\AppData\\Roaming\\MAX\\max-service\\max-service.exe") })
        assertFalse(expressions.any { it.containsMatchIn("C:\\Apps\\Other\\max.exe") })
        assertFalse(expressions.any { it.containsMatchIn("C:\\Apps\\Other\\browser.exe") })
        assertFalse(routes.any { "package_name" in it })
        val dns = ((options["dns"] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }
        assertTrue(dns.any { it["process_path_regex"] == paths && it["server"] == MANAGED_DNS_TAG })
    }

    @Test
    fun `Apple and Linux packages have their own desktop routes`() {
        for ((platform, executable) in listOf(
            Platform.MacOs to "/Applications/Max.app/Contents/Frameworks/max-service.app/Contents/MacOS/max-service",
            Platform.Linux to "/usr/share/max/bin/max",
        )) {
            val options: JSONMap = mutableMapOf()
            enforceManagedRouting(options, platform)
            val routes = ((options["route"] as Map<*, *>)["rules"] as List<*>).map { it as Map<*, *> }
            val direct = routes.single { "process_path_regex" in it }
            val expressions = (direct["process_path_regex"] as List<*>).map { Regex(it as String) }
            assertTrue(expressions.any { it.containsMatchIn(executable) })
            assertEquals(MANAGED_DIRECT_TAG, direct["outbound"])
        }
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
