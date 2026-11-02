package fr.husi.shiftvps

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

internal data class ServerCluster(val id: Long, val name: String, val profileIds: List<Long>, val countryCode: String = "")

internal fun serverClusters(candidates: List<ServerCandidate>, groups: List<ShiftVpsServerGroup>): List<ServerCluster> {
    val validGroups = groups.take(256).filter { group ->
        group.id.length in 1..128 && group.name.length in 1..80 && group.name.none(Char::isISOControl) &&
            group.endpointIds.size in 1..512 && group.endpointIds.all { it.matches(Regex("[0-9a-f]{64}")) }
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
        clusters[rowId] = ServerCluster(rowId, name, old?.profileIds.orEmpty() + candidate.profileId, country)
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
    return members.filter { it.latencyMs != null && it.latencyMs > 0 }.minByOrNull { it.latencyMs!! }?.profileId
        ?: members.firstOrNull { !it.checked }?.profileId
        ?: members.firstOrNull()?.profileId
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
