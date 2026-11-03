package fr.husi.shiftvps

import fr.husi.ktx.JSONMap
import fr.husi.fmt.SingBoxOptions
import fr.husi.ktx.asKxsMap
import fr.husi.ktx.toJsonObjectKxs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
class ShiftVpsManagedDnsTest {
    private fun base(fake: Boolean = true): JSONMap = mutableMapOf(
        "inbounds" to listOf(mapOf("type" to "tun", "tag" to "tun-in")),
        "route" to mapOf("rules" to listOf(mapOf("action" to "hijack-dns", "protocol" to listOf("dns")),
            mapOf("action" to "route", "outbound" to "proxy")), "final" to "proxy"),
        "dns" to mapOf("servers" to if(fake) listOf(mapOf("type" to "fakeip", "tag" to "existing-fake",
            "inet4_range" to "198.51.100.0/24", "inet6_range" to "2001:2::/48")) else emptyList<Any?>(),
            "rules" to listOf(mapOf("server" to "proxy-dns")), "final" to "proxy-dns"),
    )

    /** DNS matching uses the request's real inbound/type; it does not assume every query is TUN. */
    private fun dnsServer(options: JSONMap, name: String, type: String, inbound: String): String? {
        val dns=options["dns"] as Map<String, Any?>
        val rule=(dns["rules"] as List<Map<String, Any?>>).firstOrNull { rule ->
            (rule["domain_suffix"] as? List<String>)?.any { name==it || name.endsWith(".$it") }!=false &&
                (rule["query_type"] as? List<String>)?.contains(type)!=false &&
                (rule["inbound"] as? List<String>)?.contains(inbound)!=false
        }
        return rule?.get("server") as? String ?: dns["final"] as? String
    }

    /** Domain metadata can arrive from SNI or reverse mapping while the original destination remains an IP. */
    private data class Selection(val outbound: String?, val destination: String, val port: Int)

    private fun route(options: JSONMap, domain: String?, ip: String, port: Int=443, network: String="tcp"): Selection {
        val route=options["route"] as Map<String, Any?>
        val rule=(route["rules"] as List<Map<String, Any?>>).firstOrNull { rule ->
            rule["action"]=="route" &&
                (rule["domain"] as? List<String>)?.contains(domain)!=false &&
                (rule["domain_suffix"] as? List<String>)?.any { domain==it || domain?.endsWith(".$it")==true }!=false &&
                (rule["network"] as? List<String>)?.contains(network)!=false &&
                (rule["port"] as? List<Int>)?.contains(port)!=false
        }
        return Selection(rule?.get("outbound") as? String ?: route["final"] as? String,
            rule?.get("override_address") as? String ?: ip,rule?.get("override_port") as? Int ?: port)
    }

    @Test
    fun `only intercepted ru address queries use the existing FakeIP pool`() {
        val options=base();enforceManagedRouting(options)
        for(type in listOf("A","AAAA")) assertEquals("existing-fake",dnsServer(options,"yandex.ru",type,"tun-in"))
        for(type in listOf("HTTPS","TXT","MX")) assertEquals(MANAGED_DNS_TAG,dnsServer(options,"yandex.ru",type,"tun-in"))
        assertEquals(MANAGED_DNS_TAG,dnsServer(options,"yandex.ru","A",""))
        assertEquals("proxy-dns",dnsServer(options,"example.com","AAAA","tun-in"))
        val dns=options["dns"] as Map<String,Any?>
        assertEquals(true,dns["reverse_mapping"])
        assertEquals(1,(dns["servers"] as List<Map<String,Any?>>).count { it["type"]=="fakeip" })
    }

    @Test
    fun `missing custom FakeIP pool gets one nonconflicting fallback and guards remain idempotent`() {
        val options=base(false)
        (options["dns"] as Map<String,Any?>).toMutableMap().also {
            it["servers"]=listOf(mapOf("type" to "https","tag" to "shiftvps-fake-dns","server" to "resolver.example.invalid"))
            options["dns"]=it
        }
        enforceManagedRouting(options);val once=options.toString();enforceManagedRouting(options)
        assertEquals(once,options.toString())
        val dns=options["dns"] as Map<String,Any?>;val servers=dns["servers"] as List<Map<String,Any?>>
        val fake=servers.single { it["type"]=="fakeip" }
        assertEquals("198.51.100.0/24",fake["inet4_range"]);assertEquals("2001:2::/48",fake["inet6_range"])
        assertEquals(servers.size,servers.map { it["tag"] }.distinct().size)
        assertEquals("https",servers.single { it["tag"]=="shiftvps-fake-dns" }["type"])
        val rules=(options["route"] as Map<*,*>)["rules"] as List<Map<String,Any?>>
        assertEquals("sniff",rules.first()["action"])
        assertEquals(1,rules.count { it==mapOf("action" to "sniff") })
    }

    @Test
    fun `three exact Yandex names replace real IPv6 with the same domain and other traffic keeps its destination`() {
        val options=base();enforceManagedRouting(options)
        for(name in listOf("yandex.ru","www.yandex.ru","ya.ru")) {
            for(ip in listOf("2a02:6b8:a::a","77.88.55.88")) for(protocol in listOf("tcp","udp")) for(port in listOf(443,8443)) {
                assertEquals(Selection(MANAGED_DIRECT_TAG,name,port),route(options,name,ip,port,protocol))
            }
        }
        for((name,ip) in listOf("example.com" to "1.1.1.1","yandex.com" to "77.88.55.88","www.yandex.com" to "2a02:6b8:a::a","example.ru.evil" to "8.8.8.8",null to "10.0.0.1",
            null to "127.0.0.1",null to "198.51.100.21",null to "2001:2::5",null to "2a02:6b8:a::a")) {
            assertEquals(Selection("proxy",ip,443),route(options,name,ip))
        }
        assertEquals(Selection(MANAGED_DIRECT_TAG,"203.0.113.5",443),route(options,"other.ru","203.0.113.5"))
        assertEquals(Selection(MANAGED_DIRECT_TAG,"1.1.1.1",22),route(options,null,"1.1.1.1",22))
        assertEquals(Selection("proxy","1.1.1.1",22),route(options,null,"1.1.1.1",22,"udp"))
        val direct=(options["outbounds"] as List<Map<String,Any?>>).single { it["tag"]==MANAGED_DIRECT_TAG }
        assertEquals(mapOf("server" to MANAGED_DNS_TAG,"strategy" to "prefer_ipv4"),direct["domain_resolver"])
        val rules=(options["route"] as Map<*,*>)["rules"] as List<Map<String,Any?>>
        assertFalse(rules.any { "ip_cidr" in it || "tls_spoof" in it || "tls_fragment" in it })
    }

    @Test
    fun `exact domain overrides roundtrip through the generated typed route options`() {
        val options=base();enforceManagedRouting(options)
        val rules=((options["route"] as Map<*,*>)["rules"] as List<JSONMap>).filter { "override_address" in it }
        assertEquals(3,rules.size)
        rules.forEach { rule ->
            val typed=Json.decodeFromJsonElement(SingBoxOptions.Rule_Default.serializer(),rule.toJsonObjectKxs())
            assertEquals(typed.domain!!.single(),typed.override_address)
            assertEquals(null,typed.override_port)
            assertEquals(MANAGED_DIRECT_TAG,typed.outbound)
            assertEquals(rule,typed.asKxsMap())
        }
    }

    @Test
    fun `probe without TUN stays local and a custom existing pool is never duplicated`() {
        val noTun=base(false);noTun.remove("inbounds");enforceManagedRouting(noTun)
        assertEquals(MANAGED_DNS_TAG,dnsServer(noTun,"yandex.ru","AAAA",""))
        assertFalse(((noTun["dns"] as Map<*,*>)["servers"] as List<Map<*,*>>).any { it["type"]=="fakeip" })
        val custom=base();enforceManagedRouting(custom);enforceManagedRouting(custom)
        assertEquals(1,((custom["dns"] as Map<*,*>)["servers"] as List<Map<*,*>>).count { it["type"]=="fakeip" })
        assertEquals("existing-fake",dnsServer(custom,"example.ru","AAAA","tun-in"))
    }

    @Test
    fun `address preference keeps both families available and injected TCP fallback can use IPv6`() {
        val options=base();enforceManagedRouting(options)
        val direct=(options["outbounds"] as List<Map<String,Any?>>).single { it["tag"]==MANAGED_DIRECT_TAG }
        val strategy=(direct["domain_resolver"] as Map<*,*>)["strategy"]
        assertEquals("prefer_ipv4",strategy)
        val resolved=listOf("2a02:6b8:a::a","77.88.55.88")
        val ordered=if(strategy=="prefer_ipv4") resolved.sortedBy { ':' in it } else resolved
        assertEquals(resolved.toSet(),ordered.toSet())
        assertEquals("77.88.55.88",ordered.firstOrNull { ':' !in it })
        assertEquals("2a02:6b8:a::a",ordered.firstOrNull { ':' in it })
        assertTrue(ordered.size==2) // Injected family outcomes; not a claim about actual UDP reachability.
    }
}
