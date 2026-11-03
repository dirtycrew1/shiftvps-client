@file:Suppress("UNCHECKED_CAST")

package fr.husi.shiftvps

import fr.husi.Key
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.database.preference.string
import fr.husi.ktx.JSONMap

internal const val MANAGED_DIRECT_TAG = "shiftvps-direct"
internal const val MANAGED_DNS_TAG = "shiftvps-system-dns"

internal val managedAndroidRoutingSnapshot
    get() = DataStore.configurationStore.string(MANAGED_ANDROID_ROUTING_KEY)

internal suspend fun storeManagedAndroidRouting(profile: ProxyEntity, policy: ShiftVpsPolicy) {
    require(profile.id > 0 && profile.groupId > 0)
    val snapshot = ManagedAndroidRoutingSnapshot(profile.id, profile.groupId,
        validatedManagedAndroidPackages(policy.routing.directAndroidPackages))
    managedAndroidRoutingSnapshot.set(clientJson.encodeToString(ManagedAndroidRoutingSnapshot.serializer(), snapshot))
}

internal suspend fun applyManagedPolicy(policy: ShiftVpsPolicy) {
    policy.validated()
    DataStore.serviceMode.set(Key.MODE_VPN)
    DataStore.directDns.set("local")
    DataStore.remoteDns.set(when (policy.dns.proxy) {
        "cloudflare" -> "https://cloudflare-dns.com/dns-query"
        else -> "https://dns.quad9.net/dns-query"
    })
    DataStore.domainStrategyForServer.set(policy.dns.strategy)
    DataStore.domainStrategyForDirect.set(policy.dns.strategy)
    DataStore.enableFakeDns.set(true)
    DataStore.fakeDNSForAll.set(true)
    DataStore.allowAccess.set(false)
    DataStore.connectionTestURL.set(policy.latency.testUrl)
    DataStore.connectionTestTimeout.set(policy.latency.timeoutMs)
    DataStore.connectionTestConcurrent.set(policy.latency.concurrency)
    DataStore.disableTcpKeepAlive.set(false)
    DataStore.mtu.set(1500)
}

/** The product's direct-route promise is applied after custom JSON merging, not as an editable preference. */
fun enforceManagedRouting(options: JSONMap) {
    val route = (options["route"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
    val rules = (route["rules"] as? List<Any?>).orEmpty().toMutableList()
    val guard = linkedMapOf<String, Any?>("domain_suffix" to listOf("ru"), "action" to "route", "outbound" to MANAGED_DIRECT_TAG)
    val sshGuard = linkedMapOf<String, Any?>("network" to listOf("tcp"), "port" to listOf(22), "action" to "route", "outbound" to MANAGED_DIRECT_TAG)
    val sniff = mapOf("action" to "sniff")
    val namedDirect = listOf("yandex.ru", "www.yandex.ru", "ya.ru").map { name ->
        // Cached browser IPs must be resolved again on the underlying network, not dialed as fixed IPv6.
        linkedMapOf<String, Any?>("domain" to listOf(name), "action" to "route", "outbound" to MANAGED_DIRECT_TAG, "override_address" to name)
    }
    rules.removeAll { it == sniff || it == guard || it == sshGuard || it in namedDirect }
    val beforeSniff = rules.indexOfFirst { (it as? Map<*, *>)?.get("action") != "hijack-dns" }
        .let { if (it < 0) rules.size else it }
    rules.add(beforeSniff, sniff)
    val firstRoutingRule = rules.indexOfFirst { item ->
        val action = (item as? Map<*, *>)?.get("action")
        action !in setOf("sniff", "hijack-dns", "route-options", "resolve")
    }.let { if (it < 0) rules.size else it }
    rules.addAll(firstRoutingRule, namedDirect + listOf(guard, sshGuard))
    route["rules"] = rules
    options["route"] = route

    val dns = (options["dns"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
    val dnsServers = (dns["servers"] as? List<Any?>).orEmpty().toMutableList()
    dnsServers.removeAll { (it as? Map<*, *>)?.get("tag") == MANAGED_DNS_TAG }
    val hasTun = (options["inbounds"] as? List<*>)?.any { (it as? Map<*, *>)?.get("type") == "tun" } == true
    val fakeServer = dnsServers.mapNotNull { it as? Map<*, *> }.filter {
        it["type"] == "fakeip" && (it["tag"] as? String)?.isNotBlank() == true
    }.let { servers -> servers.firstOrNull { it["tag"] == "dns-fake" } ?: servers.firstOrNull() }
    val fakeTag = if (!hasTun) null else (fakeServer?.get("tag") as? String) ?: run {
        var tag = "shiftvps-fake-dns"
        while (dnsServers.any { (it as? Map<*, *>)?.get("tag") == tag }) tag += "_"
        // These are the existing product pools, used only if custom JSON removed the generated server.
        dnsServers.add(linkedMapOf("type" to "fakeip", "tag" to tag,
            "inet4_range" to "198.51.100.0/24", "inet6_range" to "2001:2::/48"))
        tag
    }
    dnsServers.add(linkedMapOf("type" to "local", "tag" to MANAGED_DNS_TAG))
    val dnsRules = (dns["rules"] as? List<Any?>).orEmpty().toMutableList()
    dnsRules.removeAll { item ->
        val rule = item as? Map<*, *>
        rule?.get("domain_suffix") == listOf("ru") &&
            (rule["server"] == MANAGED_DNS_TAG || (rule["inbound"] == listOf("tun-in") && rule["query_type"] == listOf("A", "AAAA")))
    }
    dnsRules.add(0, linkedMapOf("domain_suffix" to listOf("ru"), "action" to "route", "server" to MANAGED_DNS_TAG))
    fakeTag?.let {
        // FakeIP preserves the domain for dialing; the direct outbound performs the real system lookup.
        dnsRules.add(0, linkedMapOf("inbound" to listOf("tun-in"), "domain_suffix" to listOf("ru"),
            "query_type" to listOf("A", "AAAA"), "action" to "route", "server" to it, "disable_cache" to true))
    }
    dns["servers"] = dnsServers
    dns["rules"] = dnsRules
    dns["reverse_mapping"] = true
    options["dns"] = dns

    val outbounds = (options["outbounds"] as? List<Any?>).orEmpty().toMutableList()
    outbounds.removeAll { (it as? Map<*, *>)?.get("tag") == MANAGED_DIRECT_TAG }
    outbounds.add(linkedMapOf("type" to "direct", "tag" to MANAGED_DIRECT_TAG,
        "domain_resolver" to linkedMapOf("server" to MANAGED_DNS_TAG, "strategy" to "prefer_ipv4")))
    options["outbounds"] = outbounds
}
