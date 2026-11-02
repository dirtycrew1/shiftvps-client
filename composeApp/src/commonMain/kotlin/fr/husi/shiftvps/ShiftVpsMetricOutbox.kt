package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import javax.net.ssl.SSLException
import java.security.MessageDigest

@Serializable
internal data class QueuedClientMetric(val id: String, val createdAt: Long, val metric: ClientMetric)

@Serializable
private data class MetricOutboxSnapshot(
    val owner: String? = null,
    val items: List<QueuedClientMetric> = emptyList(),
    val inFlightIds: List<String> = emptyList(),
)

internal class ClientMetricOutbox private constructor(
    private var owner: String? = null,
    private val items: MutableList<QueuedClientMetric> = mutableListOf(),
    private var inFlightIds: List<String> = emptyList(),
) {
    fun append(owner: String, metric: ClientMetric, now: Long, occurredAt: Long = now) {
        require(owner.matches(Regex("[0-9a-f]{64}")))
        require(metric.isSafeMetric())
        if (now - occurredAt !in 0L..RETENTION_MS) return
        if (this.owner != owner) { clear(); this.owner = owner }
        prune(now)
        items.add(QueuedClientMetric(UUID.randomUUID().toString(), occurredAt, metric))
        while (items.size > MAX_ITEMS) items.removeAt(items.indexOfFirst { it.id !in inFlightIds })
    }

    fun peek(owner: String, now: Long): List<QueuedClientMetric> {
        prune(now)
        if (this.owner != owner) return emptyList()
        if (inFlightIds.isEmpty()) inFlightIds = items.take(BATCH_SIZE).map { it.id }
        return inFlightIds.mapNotNull { id -> items.firstOrNull { it.id == id } }
    }

    fun acknowledge(owner: String, ids: Set<String>) {
        if (this.owner != owner || ids != inFlightIds.toSet()) return
        items.removeAll { it.id in ids }
        inFlightIds = emptyList()
    }

    fun discard(owner: String) {
        if (this.owner == owner) clear()
    }

    fun clear() { items.clear(); inFlightIds = emptyList(); owner = null }

    fun encode(): String = queueJson.encodeToString(MetricOutboxSnapshot.serializer(), MetricOutboxSnapshot(owner, items, inFlightIds))

    private fun prune(now: Long) {
        // A partially expired ambiguous send cannot be retried under a different batch identity.
        if (items.any { it.id in inFlightIds && now - it.createdAt !in 0L..RETENTION_MS }) {
            items.removeAll { it.id in inFlightIds }
            inFlightIds = emptyList()
        }
        items.removeAll { now - it.createdAt !in 0L..RETENTION_MS }
    }

    companion object {
        const val MAX_BYTES = 65536
        private const val MAX_ITEMS = 100
        private const val BATCH_SIZE = 20
        private const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
        private val queueJson = Json { encodeDefaults = true; explicitNulls = false }

        fun decode(content: String, now: Long): ClientMetricOutbox {
            if (content.length > MAX_BYTES) return ClientMetricOutbox()
            return runCatching {
                val snapshot = queueJson.decodeFromString<MetricOutboxSnapshot>(content)
                require(snapshot.owner == null || snapshot.owner.matches(Regex("[0-9a-f]{64}")))
                require(snapshot.items.size <= MAX_ITEMS)
                require(snapshot.items.all { it.metric.isSafeMetric() && it.id.matches(Regex("[0-9a-f-]{36}")) })
                require(snapshot.items.map { it.id }.distinct().size == snapshot.items.size)
                require(snapshot.inFlightIds.size <= BATCH_SIZE && snapshot.inFlightIds.distinct().size == snapshot.inFlightIds.size)
                require(snapshot.inFlightIds.all { id -> snapshot.items.any { it.id == id } })
                ClientMetricOutbox(snapshot.owner, snapshot.items.toMutableList(), snapshot.inFlightIds).also { it.prune(now) }
            }.getOrElse { ClientMetricOutbox() }
        }
    }
}

internal fun ClientMetric.isSafeMetric(): Boolean =
    event in setOf("connect_succeeded", "connect_failed", "disconnected", "feed_updated", "feed_update_failed", "latency_sample", "app_error") &&
        (serverId == null || serverId.matches(Regex("[a-f0-9]{64}"))) &&
        (latencyMs == null || latencyMs in 0..60000) &&
        (errorCode == null || errorCode in setOf("timeout", "dns", "tls", "network_unreachable", "authentication", "subscription_expired", "configuration", "permission", "unknown")) &&
        appVersion.matches(Regex("[0-9]{1,5}(?:\\.[0-9]{1,5}){0,3}(?:-[a-z0-9.]{1,12})?")) &&
        platform in setOf("android", "windows", "macos") && policyVersion > 0 &&
        (occurredAt == null || occurredAt >= 0)

internal data class LifecycleMetric(val event: String, val serverId: String?, val errorCode: String? = null)

internal data class ServiceMetricSession(val owner: String, val policyVersion: Int)
internal data class ServiceMetricTransition(
    val session: ServiceMetricSession?, val state: ServiceState, val endpoint: String?, val errorCode: String?, val occurredAt: Long,
)
internal data class CapturedLifecycleMetric(val session: ServiceMetricSession, val occurredAt: Long, val metric: LifecycleMetric) {
    fun belongsTo(currentOwner: String): Boolean = session.owner == currentOwner
}

internal fun tokenOwner(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun metricBatchId(owner: String, batch: List<QueuedClientMetric>): String = MessageDigest.getInstance("SHA-256")
    .digest((owner + "|" + batch.joinToString(",") { it.id }).toByteArray(Charsets.US_ASCII))
    .joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun serviceFailureCode(error: Throwable): String = when (error) {
    is UnknownHostException -> "dns"
    is SocketTimeoutException -> "timeout"
    is SSLException -> "tls"
    is ConnectException -> "network_unreachable"
    is SecurityException -> "permission"
    is IllegalArgumentException -> "configuration"
    else -> "unknown"
}

/** Lives with the VPN service, so Activity rotation or dismissal cannot remove lifecycle reporting. */
internal class ServiceLifecycleTracker {
    private var session: ServiceMetricSession? = null
    private var activeEndpoint: String? = null
    private var attempting = false
    private var connected = false

    fun transition(transition: ServiceMetricTransition): CapturedLifecycleMetric? {
        if (transition.state == ServiceState.Connecting) {
            session = transition.session
            activeEndpoint = transition.endpoint
            attempting = true
            connected = false
            return null
        }
        val capturedSession = session ?: return null
        if (capturedSession != transition.session) return null
        if (transition.endpoint != null) activeEndpoint = transition.endpoint
        val event = when (transition.state) {
            ServiceState.Connected -> {
                if (connected) null else {
                    connected = true
                    attempting = false
                    LifecycleMetric("connect_succeeded", activeEndpoint)
                }
            }
            ServiceState.Stopped -> {
                val result = when {
                    connected -> LifecycleMetric("disconnected", activeEndpoint, transition.errorCode)
                    attempting && transition.errorCode != null -> LifecycleMetric("connect_failed", activeEndpoint, transition.errorCode)
                    else -> null
                }
                connected = false
                attempting = false
                activeEndpoint = null
                session = null
                result
            }
            else -> null
        }
        return event?.let { CapturedLifecycleMetric(capturedSession, transition.occurredAt, it) }
    }
}
