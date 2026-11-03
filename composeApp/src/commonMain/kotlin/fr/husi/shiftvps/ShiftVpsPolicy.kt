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
    val firstRoutingRule = rules.indexOfFirst { item ->
        val action = (item as? Map<*, *>)?.get("action")
        action !in setOf("sniff", "hijack-dns", "route-options", "resolve")
    }.let { if (it < 0) rules.size else it }
    rules.add(firstRoutingRule, guard)
    rules.add(firstRoutingRule + 1, sshGuard)
    route["rules"] = rules
    options["route"] = route

    val dns = (options["dns"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
    val dnsServers = (dns["servers"] as? List<Any?>).orEmpty().toMutableList()
    dnsServers.removeAll { (it as? Map<*, *>)?.get("tag") == MANAGED_DNS_TAG }
    dnsServers.add(linkedMapOf("type" to "local", "tag" to MANAGED_DNS_TAG))
    val dnsRules = (dns["rules"] as? List<Any?>).orEmpty().toMutableList()
    dnsRules.add(0, linkedMapOf("domain_suffix" to listOf("ru"), "action" to "route", "server" to MANAGED_DNS_TAG))
    dns["servers"] = dnsServers
    dns["rules"] = dnsRules
    options["dns"] = dns

    val outbounds = (options["outbounds"] as? List<Any?>).orEmpty().toMutableList()
    outbounds.removeAll { (it as? Map<*, *>)?.get("tag") == MANAGED_DIRECT_TAG }
    outbounds.add(linkedMapOf("type" to "direct", "tag" to MANAGED_DIRECT_TAG,
        "domain_resolver" to linkedMapOf("server" to MANAGED_DNS_TAG)))
    options["outbounds"] = outbounds
}
