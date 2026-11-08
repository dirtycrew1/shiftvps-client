package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import java.security.MessageDigest

internal data class ServerCandidate(
    val profileId: Long,
    val endpointId: String,
    val name: String,
    val latencyMs: Int? = null,
    val checked: Boolean = false,
    val checking: Boolean = false,
    val probeFailure: ServerProbeFailure? = null,
)

internal data class ServerCluster(
    val id: Long, val name: String, val profileIds: List<Long>, val countryCode: String = "",
    val directProfileIds: List<Long> = emptyList(),
)

internal fun managedServiceProfileId(
    state: ServiceState, requested: Long, current: Long, managedIds: Set<Long>,
): Long? = when (state) {
    ServiceState.Connecting -> requested
    ServiceState.Connected -> current
    else -> null
}?.takeIf { it > 0 && it in managedIds }

internal fun serverClusters(candidates: List<ServerCandidate>, groups: List<ShiftVpsServerGroup>): List<ServerCluster> {
    val validGroups = groups.take(256).filter { group ->
        group.id.length in 1..128 && group.name.length in 1..80 && group.name.none(Char::isISOControl) &&
            group.endpointIds.size in 1..512 && group.endpointIds.all { it.matches(Regex("[0-9a-f]{64}")) } &&
            group.directEndpointIds.size <= group.endpointIds.size &&
            group.directEndpointIds.distinct().size == group.directEndpointIds.size &&
            group.endpointIds.containsAll(group.directEndpointIds)
    }.groupBy { it.id }.values.filter { it.size == 1 }.map { it.single() }
    val claims = mutableMapOf<String, MutableList<ShiftVpsServerGroup>>()
    for (group in validGroups) {
        for (endpoint in group.endpointIds.distinct()) claims.getOrPut(endpoint) { mutableListOf() }.add(group)
    }
    val clusters = linkedMapOf<Long, ServerCluster>()
    for (candidate in candidates) {
        val authoritative = claims[candidate.endpointId]?.singleOrNull()
        val rowId = authoritative?.let { logicalRowId(it.id) } ?: candidate.profileId
        val name = authoritative?.name ?: candidate.name
        val old = clusters[rowId]
        val country = authoritative?.countryCode?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.uppercase().orEmpty()
        val direct = old?.directProfileIds.orEmpty() +
            listOfNotNull(candidate.profileId.takeIf { authoritative?.directEndpointIds?.contains(candidate.endpointId) == true })
        clusters[rowId] = ServerCluster(rowId, name, old?.profileIds.orEmpty() + candidate.profileId, country, direct)
    }
    return clusters.values.toList()
}

internal fun chooseProfile(
    cluster: ServerCluster,
    candidates: List<ServerCandidate>,
    pinnedProfileId: Long? = null,
): Long? {
    if (pinnedProfileId != null && pinnedProfileId in cluster.profileIds) return pinnedProfileId
    val members = candidates.filter { it.profileId in cluster.profileIds }
    for (tier in listOf(members.filter { it.profileId in cluster.directProfileIds },
        members.filter { it.profileId !in cluster.directProfileIds })) {
        tier.filter { it.latencyMs != null && it.latencyMs > 0 }.minByOrNull { it.latencyMs!! }?.let { return it.profileId }
        tier.firstOrNull { !it.checked }?.let { return it.profileId }
    }
    return members.firstOrNull { it.profileId in cluster.directProfileIds }?.profileId ?: members.firstOrNull()?.profileId
}

/** A fresh successful HTTP request on a direct route prevents any relay attempt. */
internal suspend fun chooseConnectionProfile(
    cluster: ServerCluster,
    candidates: List<ServerCandidate>,
    allowUnverified: Boolean = true,
    probe: suspend (ServerCandidate) -> ServerProbeResult,
): Long? {
    val ordered = candidates.filter { it.profileId in cluster.profileIds }.sortedWith(
        compareBy<ServerCandidate> { if (it.profileId in cluster.directProfileIds) 0 else 1 }
            .thenBy { it.latencyMs?.takeIf { latency -> latency > 0 } ?: Int.MAX_VALUE },
    )
    for (candidate in ordered) {
        if (probe(candidate) is ServerProbeResult.Success) return candidate.profileId
    }
    // A test-site or core failure is inconclusive; retain the ability to try the preferred VPN route.
    return if (allowUnverified) ordered.firstOrNull()?.profileId else null
}

internal suspend fun chooseRecoveryProfile(
    cluster: ServerCluster,
    activeId: Long,
    candidates: List<ServerCandidate>,
    primaryTestUrl: String,
    checkActive: suspend (String) -> ServerProbeResult,
    checkCandidate: suspend (ServerCandidate) -> ServerProbeResult,
): Long? {
    val alternatives = candidates.filter { it.profileId != activeId && it.profileId in cluster.profileIds }
    if (alternatives.isEmpty()) return null
    val urls = listOf(primaryTestUrl) + ShiftVpsPolicy.ALLOWED_PROBE_URLS.filter { it != primaryTestUrl }
    for (url in urls) {
        val failure = (checkActive(url) as? ServerProbeResult.Inconclusive)?.reason ?: return null
        if (failure !in setOf(ServerProbeFailure.Timeout, ServerProbeFailure.Dns, ServerProbeFailure.Tls, ServerProbeFailure.Unknown)) return null
    }
    return chooseConnectionProfile(cluster, alternatives, allowUnverified = false, probe = checkCandidate)
}

internal fun clusterRow(
    cluster: ServerCluster,
    candidates: List<ServerCandidate>,
    pinnedProfileId: Long? = null,
): ShiftVpsServer {
    val members = candidates.filter { it.profileId in cluster.profileIds }
    val measured = chooseProfile(cluster, candidates, pinnedProfileId)?.let { id -> members.firstOrNull { it.profileId == id } }
    val latency = measured?.latencyMs?.takeIf { it > 0 }
    // A failed request can also mean a bad test setup, a core error, or a test-site failure.
    val reachable = if (latency != null) true else null
    return ShiftVpsServer(cluster.id, cluster.name, latency, members.any { it.checking }, reachable, cluster.countryCode)
}

private fun logicalRowId(id: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
    return digest.take(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255L) } or Long.MIN_VALUE
}
