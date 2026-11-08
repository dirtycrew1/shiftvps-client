@file:Suppress("UNCHECKED_CAST")

package fr.husi.shiftvps

import fr.husi.Key
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.database.preference.string
import fr.husi.ktx.JSONMap
import fr.husi.platform.Platform
import fr.husi.platform.PlatformInfo

internal const val MANAGED_DIRECT_TAG = "shiftvps-direct"
internal const val MANAGED_DNS_TAG = "shiftvps-system-dns"
internal val MANAGED_DIRECT_SUFFIXES = listOf("ru", "xn--p1ai")
private val vkDirectSuffixes = listOf("vk.com", "vk.me", "userapi.com")
private val fcmHosts = listOf("mtalk.google.com", "mtalk4.google.com") + (1..8).map { "alt$it-mtalk.google.com" }
private val apnsNetworks = listOf("17.249.0.0/16", "17.252.0.0/16", "17.57.144.0/22", "17.188.128.0/18",
    "17.188.20.0/23", "2620:149:a44::/48", "2403:300:a42::/48", "2403:300:a51::/48", "2a01:b740:a42::/48")

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
fun enforceManagedRouting(options: JSONMap, platform: Platform = PlatformInfo.platform) {
    val route = (options["route"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
    val rules = (route["rules"] as? List<Any?>).orEmpty().toMutableList()
    val guard = linkedMapOf<String, Any?>("domain_suffix" to MANAGED_DIRECT_SUFFIXES, "action" to "route", "outbound" to MANAGED_DIRECT_TAG)
    val legacyGuard = guard + ("domain_suffix" to listOf("ru"))
    val sshGuard = linkedMapOf<String, Any?>("network" to listOf("tcp"), "port" to listOf(22), "action" to "route", "outbound" to MANAGED_DIRECT_TAG)
    val extraSuffixes = when (platform) {
        Platform.MacOs -> vkDirectSuffixes + "push.apple.com"
        Platform.Windows -> vkDirectSuffixes + "notify.windows.com"
        else -> vkDirectSuffixes
    }
    val desktopAppPaths = when (platform) {
        Platform.Windows -> listOf(
            """(?i)(?:^|[\\/])Yandex[\\/]YandexBrowser[\\/]Application[\\/]browser\.exe$""",
            """(?i)(?:^|[\\/])MAX[\\/](?:max\.exe|crashpad_handler\.exe|max-service[\\/](?:max-service|crashpad_handler)\.exe)$""",
        )
        Platform.MacOs -> listOf(
            """(?:^|/)Yandex\.app/Contents/(?:MacOS/|Frameworks/)""",
            """(?:^|/)Max\.app/Contents/(?:MacOS/|Frameworks/)""",
        )
        Platform.Linux -> listOf("""^/opt/yandex/browser/yandex_browser$""", """^/usr/share/max/""")
        Platform.Android -> emptyList()
    }
    val extraDns = buildList<Map<String, Any?>> {
        val direct = mapOf("action" to "route", "server" to MANAGED_DNS_TAG)
        add(direct + ("domain_suffix" to extraSuffixes))
        if (platform == Platform.Android) add(direct + ("domain" to fcmHosts))
        if (desktopAppPaths.isNotEmpty()) add(direct + ("process_path_regex" to desktopAppPaths))
    }
    val extraRoutes = buildList<Map<String, Any?>> {
        val direct = mapOf("action" to "route", "outbound" to MANAGED_DIRECT_TAG)
        add(direct + ("domain_suffix" to extraSuffixes))
        if (platform == Platform.Android) {
            val pushPorts = listOf(5228, 5229, 5230)
            add(direct + mapOf("domain" to fcmHosts, "network" to listOf("tcp"), "port" to (pushPorts + 443)))
            add(direct + mapOf("package_name" to listOf("com.google.android.gms"), "network" to listOf("tcp"), "port" to pushPorts))
        }
        if (platform == Platform.MacOs) {
            add(direct + mapOf("ip_cidr" to apnsNetworks, "network" to listOf("tcp"), "port" to listOf(443, 5223)))
        }
        if (desktopAppPaths.isNotEmpty()) add(direct + ("process_path_regex" to desktopAppPaths))
    }
    val sniff = mapOf("action" to "sniff")
    val namedDirect = listOf("yandex.ru", "www.yandex.ru", "ya.ru").map { name ->
        // Cached browser IPs must be resolved again on the underlying network, not dialed as fixed IPv6.
        linkedMapOf<String, Any?>("domain" to listOf(name), "action" to "route", "outbound" to MANAGED_DIRECT_TAG, "override_address" to name)
    }
    rules.removeAll { it == sniff || it == guard || it == legacyGuard || it == sshGuard || it in namedDirect || it in extraRoutes }
    rules.add(0, sniff)
    val firstRoutingRule = rules.indexOfFirst { item ->
        val action = (item as? Map<*, *>)?.get("action")
        action !in setOf("sniff", "hijack-dns", "route-options", "resolve")
    }.let { if (it < 0) rules.size else it }
    rules.addAll(firstRoutingRule, namedDirect + listOf(guard, sshGuard) + extraRoutes)
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
        rule != null && rule["domain_suffix"] in listOf(listOf("ru"), MANAGED_DIRECT_SUFFIXES) &&
            (rule["server"] == MANAGED_DNS_TAG || (rule["inbound"] == listOf("tun-in") && rule["query_type"] == listOf("A", "AAAA")))
    }
    dnsRules.add(0, linkedMapOf("domain_suffix" to MANAGED_DIRECT_SUFFIXES, "action" to "route", "server" to MANAGED_DNS_TAG))
    fakeTag?.let {
        // FakeIP preserves the domain for dialing; the direct outbound performs the real system lookup.
        dnsRules.add(0, linkedMapOf("inbound" to listOf("tun-in"), "domain_suffix" to MANAGED_DIRECT_SUFFIXES,
            "query_type" to listOf("A", "AAAA"), "action" to "route", "server" to it, "disable_cache" to true))
    }
    dnsRules.removeAll { it in extraDns }
    dnsRules.addAll(if (fakeTag == null) 1 else 2, extraDns)
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
