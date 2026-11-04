package fr.husi.shiftvps

import fr.husi.bg.ServiceState

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShiftVpsServerGroupsTest {
    @Test
    fun `notification and tile connections follow the service profile without adopting foreign profiles`() {
        val managed = setOf(10L, 20L)
        assertEquals(20L, managedServiceProfileId(ServiceState.Connecting, 20L, 10L, managed))
        assertEquals(10L, managedServiceProfileId(ServiceState.Connected, 20L, 10L, managed))
        assertEquals(null, managedServiceProfileId(ServiceState.Connected, 20L, 99L, managed))
        assertEquals(null, managedServiceProfileId(ServiceState.Connecting, 0L, 10L, managed))
        for (state in listOf(ServiceState.Idle, ServiceState.Stopped, ServiceState.Stopping)) {
            assertEquals(null, managedServiceProfileId(state, 20L, 10L, managed))
        }
    }

    private val endpointA = "a".repeat(64)
    private val endpointB = "b".repeat(64)
    private val endpointC = "c".repeat(64)
    private val node = ShiftVpsServerGroup("node-uuid-a", "Нидерланды", listOf(endpointA, endpointB))

    @Test
    fun `country flags come only from the authoritative node and tolerate old cached metadata`() {
        val candidates = listOf(ServerCandidate(1, endpointA, "Misleading Germany"))
        val grouped = serverClusters(candidates, listOf(node.copy(countryCode = "nl"))).single()
        assertEquals("NL", clusterRow(grouped, candidates).countryCode)
        assertEquals("", serverClusters(candidates, emptyList()).single().countryCode)
        assertEquals("", serverClusters(candidates, listOf(node)).single().countryCode)
        assertEquals("", serverClusters(candidates, listOf(node.copy(countryCode = "NL\u0000"))).single().countryCode)
        val old = clientJson.decodeFromString<ShiftVpsServerGroup>(
            """{"id":"node-uuid-a","name":"Нидерланды","endpoint_ids":["$endpointA"]}""",
        )
        assertEquals("", old.countryCode)
    }

    @Test
    fun `one VPS with several protocols renders one stable row and chooses its fastest successful transport`() {
        val candidates = listOf(
            ServerCandidate(1, endpointA, "Reality", 180, checked = true),
            ServerCandidate(2, endpointB, "XHTTP", 70, checked = true),
            ServerCandidate(3, endpointB, "WebSocket", checked = true),
        )
        val cluster = serverClusters(candidates, listOf(node)).single()
        assertEquals(listOf(1L, 2L, 3L), cluster.profileIds)
        assertEquals("Нидерланды", cluster.name)
        assertTrue(cluster.id < 0)
        assertEquals(2L, chooseProfile(cluster, candidates))
        assertEquals(70, clusterRow(cluster, candidates).latencyMs)
        val changedWinner = candidates.map { if (it.profileId == 1L) it.copy(latencyMs = 30) else it }
        assertEquals(cluster.id, serverClusters(changedWinner, listOf(node)).single().id)
        assertEquals(1L, chooseProfile(cluster, changedWinner))
    }

    @Test
    fun `connected transport remains pinned when another member becomes faster`() {
        val candidates = listOf(
            ServerCandidate(1, endpointA, "Reality", 180, checked = true),
            ServerCandidate(2, endpointB, "XHTTP", 20, checked = true),
        )
        val cluster = serverClusters(candidates, listOf(node)).single()
        assertEquals(1L, chooseProfile(cluster, candidates, pinnedProfileId = 1))
        assertEquals(180, clusterRow(cluster, candidates, pinnedProfileId = 1).latencyMs)
        assertEquals(2L, chooseProfile(cluster, candidates))
    }

    @Test
    fun `zero or timed out measurements never outrank a positive working delay`() {
        val candidates = listOf(
            ServerCandidate(1, endpointA, "Not measured", 0, checked = true),
            ServerCandidate(2, endpointB, "Timed out", checked = true),
            ServerCandidate(3, endpointB, "Working", 90, checked = true),
        )
        val cluster = serverClusters(candidates, listOf(node)).single()
        assertEquals(3L, chooseProfile(cluster, candidates))
        assertEquals(true, clusterRow(cluster, candidates).reachable)
        val failed = candidates.map { it.copy(latencyMs = null) }
        assertEquals(null, clusterRow(cluster, failed).reachable)
        assertEquals(1L, chooseProfile(cluster, failed))
    }

    @Test
    fun `completed probes without HTTP evidence do not declare a node unavailable`() {
        val candidates = listOf(
            ServerCandidate(1, endpointA, "Reality", checked = true),
            ServerCandidate(2, endpointB, "WebSocket", checked = true),
        )
        val cluster = serverClusters(candidates, listOf(node)).single()
        assertEquals(null, clusterRow(cluster, candidates).reachable)
        assertEquals(null, clusterRow(cluster, candidates, pinnedProfileId = 1).reachable)
    }

    @Test
    fun `unmeasured group stays unknown while tests are in progress and empty metadata creates no phantom nodes`() {
        assertTrue(serverClusters(emptyList(), listOf(node)).isEmpty())
        val candidates = listOf(ServerCandidate(1, endpointA, "Reality", checking = true))
        val cluster = serverClusters(candidates, listOf(node)).single()
        val row = clusterRow(cluster, candidates)
        assertEquals(null, row.reachable)
        assertEquals(null, row.latencyMs)
        assertTrue(row.checking)
    }

    @Test
    fun `matching names are never used to invent logical groups`() {
        val candidates = listOf(
            ServerCandidate(1, endpointA, "Нидерланды"),
            ServerCandidate(2, endpointB, "Нидерланды"),
        )
        val rows = serverClusters(candidates, emptyList())
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertTrue(rows.all { it.profileIds.size == 1 })
    }

    @Test
    fun `conflicting node claims preserve distinct profiles instead of guessing an exit`() {
        val candidates = listOf(ServerCandidate(1, endpointA, "Profile one"), ServerCandidate(2, endpointC, "Profile two"))
        val conflicting = ShiftVpsServerGroup("node-uuid-b", "Другая страна", listOf(endpointA, endpointC))
        val rows = serverClusters(candidates, listOf(node, conflicting))
        assertEquals(2, rows.size)
        assertEquals(1L, rows.first().id)
        assertEquals(listOf(1L), rows.first().profileIds)
        assertEquals("Другая страна", rows.last().name)
        assertNotEquals(rows.first().id, rows.last().id)
        assertFalse(rows.last().id > 0)
    }
}
