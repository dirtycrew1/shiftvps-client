package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface ShiftVpsTokenStore {
    suspend fun load(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

expect fun platformShiftVpsTokenStore(): ShiftVpsTokenStore
internal expect fun prepareServiceMetrics(owner: String?, policyVersion: Int)

data class ShiftVpsServer(
    val id: Long,
    val name: String,
    val latencyMs: Int? = null,
    val checking: Boolean = false,
    val reachable: Boolean? = null,
    val countryCode: String = "",
)

data class ShiftVpsUiState(
    val signedIn: Boolean = false,
    val accountName: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val probing: Boolean = false,
    val authCode: String? = null,
    val servers: List<ShiftVpsServer> = emptyList(),
    val selectedId: Long? = null,
    val serviceState: ServiceState = ServiceState.Idle,
    val error: String? = null,
    val policyVersion: Int = 1,
    val lastUpdatedMillis: Long? = null,
)

sealed interface ShiftVpsEvent {
    data class OpenBrowser(val url: String) : ShiftVpsEvent
    data object LaunchVpn : ShiftVpsEvent
}

@Serializable
data class ShiftVpsAccount(
    val name: String = "",
    @SerialName("subscription_expires_at") val subscriptionExpiresAt: String? = null,
    @SerialName("access_active") val accessActive: Boolean = false,
)

@Serializable
data class ShiftVpsManifest(
    val account: ShiftVpsAccount,
    val policy: ShiftVpsPolicy,
    @SerialName("server_groups") val serverGroups: List<ShiftVpsServerGroup> = emptyList(),
)

@Serializable
data class ShiftVpsServerGroup(
    val id: String,
    val name: String,
    @SerialName("endpoint_ids") val endpointIds: List<String>,
    @SerialName("country_code") val countryCode: String = "",
)

@Serializable
data class ShiftVpsPolicy(
    val version: Int = 1,
    val routing: ShiftVpsRouting = ShiftVpsRouting(),
    val dns: ShiftVpsDns = ShiftVpsDns(),
    val latency: ShiftVpsLatency = ShiftVpsLatency(),
    @SerialName("refresh_interval_s") val refreshIntervalSeconds: Int = 300,
    @SerialName("connect_timeout_ms") val connectTimeoutMs: Int = 10000,
    val telemetry: ShiftVpsTelemetryPolicy = ShiftVpsTelemetryPolicy(),
) {
    fun validated(): ShiftVpsPolicy {
        require(version > 0)
        require(routing.directDomainSuffixes == listOf("ru"))
        require(dns.direct == "system")
        require(dns.proxy in setOf("quad9", "cloudflare"))
        require(dns.strategy in setOf("prefer_ipv4", "ipv4_only", "prefer_ipv6"))
        require(latency.timeoutMs in 1000..15000)
        require(latency.concurrency in 1..5)
        require(latency.intervalSeconds in 60..3600)
        require(latency.testUrl in ALLOWED_PROBE_URLS)
        require(refreshIntervalSeconds in 60..3600)
        require(connectTimeoutMs in 3000..30000)
        require(telemetry.batchIntervalSeconds in 30..300)
        return this
    }

    companion object {
        val ALLOWED_PROBE_URLS = setOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
    }
}

@Serializable
data class ShiftVpsRouting(
    @SerialName("direct_domain_suffixes") val directDomainSuffixes: List<String> = listOf("ru"),
)

@Serializable
data class ShiftVpsDns(
    val direct: String = "system",
    val proxy: String = "quad9",
    val strategy: String = "prefer_ipv4",
)

@Serializable
data class ShiftVpsLatency(
    @SerialName("probe_on_launch") val probeOnLaunch: Boolean = true,
    @SerialName("timeout_ms") val timeoutMs: Int = 5000,
    val concurrency: Int = 3,
    @SerialName("interval_s") val intervalSeconds: Int = 300,
    @SerialName("test_url") val testUrl: String = "https://www.gstatic.com/generate_204",
)

@Serializable
data class ShiftVpsTelemetryPolicy(
    val enabled: Boolean = true,
    @SerialName("batch_interval_s") val batchIntervalSeconds: Int = 30,
)

@Serializable
internal data class DeviceAuthorization(
    @SerialName("grant_id") val grantId: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

@Serializable
internal data class ClientToken(
    @SerialName("access_token") val accessToken: String,
)

@Serializable
internal data class ClientMetric(
    val event: String,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("latency_ms") val latencyMs: Int? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("app_version") val appVersion: String,
    val platform: String,
    @SerialName("policy_version") val policyVersion: Int,
    @SerialName("occurred_at") val occurredAt: Long? = null,
)

internal fun rankServers(servers: List<ShiftVpsServer>): List<ShiftVpsServer> = servers.sortedWith(
    compareBy<ShiftVpsServer> { if (it.reachable == true && it.latencyMs != null) 0 else 1 }
        .thenBy { it.latencyMs ?: Int.MAX_VALUE }
        .thenBy { it.id },
)
