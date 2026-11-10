package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import javax.net.ssl.SSLException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShiftVpsMetricOutboxTest {
    private val owner = "a".repeat(64)
    private val otherOwner = "b".repeat(64)
    private val endpoint = "c".repeat(64)
    private val now = 1_000_000_000L

    private fun metric(latency: Int = 1) = ClientMetric("latency_sample", endpoint, latency,
        appVersion = "0.1.0", platform = "android", policyVersion = 1)

    @Test
    fun `quality and speed samples survive a retry with separate fields`() {
        val response = metric(420).copy(event = "connection_quality")
        val failure = response.copy(latencyMs = null, errorCode = "timeout")
        val speed = response.copy(event = "speed_sample", latencyMs = null, downloadKbps = 12_000)
        val queue = ClientMetricOutbox.decode("", now)
        listOf(response, failure, speed).forEach { queue.append(owner, it, now) }
        assertEquals(listOf(response, failure, speed),
            ClientMetricOutbox.decode(queue.encode(), now).peek(owner, now).map { it.metric })
        assertFalse(speed.copy(downloadKbps = 0).isSafeMetric())
        assertFalse(response.copy(errorCode = "timeout").isSafeMetric())
        assertFalse(failure.copy(errorCode = null).isSafeMetric())
    }

    @Test
    fun `persistent queue is bounded and sends at most twenty records per request`() {
        val queue = ClientMetricOutbox.decode("", now)
        repeat(120) { queue.append(owner, metric(it), now) }
        val restored = ClientMetricOutbox.decode(queue.encode(), now)
        val batch = restored.peek(owner, now)
        assertEquals(20, batch.size)
        assertEquals((20..39).toList(), batch.map { it.metric.latencyMs })
        repeat(5) {
            val pending = restored.peek(owner, now)
            restored.acknowledge(owner, pending.map { it.id }.toSet())
        }
        assertTrue(restored.peek(owner, now).isEmpty())
    }

    @Test
    fun `an acknowledged retry removes only that batch and cannot drop newly appended events`() {
        val queue = ClientMetricOutbox.decode("", now)
        queue.append(owner, metric(10), now)
        val inFlight = queue.peek(owner, now)
        queue.append(owner, metric(20), now + 1)
        queue.acknowledge(owner, inFlight.map { it.id }.toSet())
        assertEquals(listOf(20), queue.peek(owner, now + 1).map { it.metric.latencyMs })
    }

    @Test
    fun `an accepted batch with a lost response stays frozen across append and process restart`() {
        val queue = ClientMetricOutbox.decode("", now)
        queue.append(owner, metric(10), now)
        val first = queue.peek(owner, now)
        val restored = ClientMetricOutbox.decode(queue.encode(), now).peek(owner, now)
        assertEquals(metricBatchId(owner, first), metricBatchId(owner, restored))
        assertTrue(metricBatchId(owner, first).matches(Regex("[0-9a-f]{64}")))
        assertFalse(metricBatchId(owner, first) == metricBatchId(otherOwner, first))
        queue.append(owner, metric(20), now + 1)
        val retry = ClientMetricOutbox.decode(queue.encode(), now + 1)
        assertEquals(first, retry.peek(owner, now + 1))
        assertEquals(metricBatchId(owner, first), metricBatchId(owner, retry.peek(owner, now + 1)))
        retry.acknowledge(owner, first.map { it.id }.toSet())
        assertEquals(listOf(20), retry.peek(owner, now + 1).map { it.metric.latencyMs })
    }

    @Test
    fun `queue pressure cannot evict or grow an unacknowledged frozen batch`() {
        val queue = ClientMetricOutbox.decode("", now)
        queue.append(owner, metric(10), now)
        val accepted = queue.peek(owner, now)
        repeat(120) { queue.append(owner, metric(it), now + 1) }
        val retry = ClientMetricOutbox.decode(queue.encode(), now + 1)
        assertEquals(accepted, retry.peek(owner, now + 1))
        retry.acknowledge(owner, accepted.map { it.id }.toSet())
        assertEquals(20, retry.peek(owner, now + 1).size)
    }

    @Test
    fun `late captured events retain their occurrence time without pruning newer in-flight records`() {
        val queue = ClientMetricOutbox.decode("", now)
        queue.append(owner, metric(10), now + 1000)
        val first = queue.peek(owner, now + 1000)
        queue.append(owner, metric(20), now + 2000, occurredAt = now)
        assertEquals(first, queue.peek(owner, now + 2000))
        queue.acknowledge(owner, first.map { it.id }.toSet())
        assertEquals(now, queue.peek(owner, now + 2000).single().createdAt)
    }

    @Test
    fun `expiry drops a whole ambiguous batch instead of resending a subset with another identity`() {
        val queue = ClientMetricOutbox.decode("", now)
        queue.append(owner, metric(10), now)
        queue.append(owner, metric(20), now + 1000)
        queue.peek(owner, now + 1000)
        val expiredAt = now + 7 * 24 * 60 * 60 * 1000L + 1
        queue.append(owner, metric(30), expiredAt)
        assertEquals(listOf(30), queue.peek(owner, expiredAt).map { it.metric.latencyMs })
    }

    @Test
    fun `old account events cannot be submitted or acknowledged as a new account`() {
        val queue = ClientMetricOutbox.decode("", now)
        queue.append(owner, metric(10), now)
        val oldBatch = queue.peek(owner, now)
        assertTrue(queue.peek(otherOwner, now).isEmpty())
        queue.append(otherOwner, metric(20), now + 1)
        queue.acknowledge(owner, oldBatch.map { it.id }.toSet())
        assertEquals(listOf(20), queue.peek(otherOwner, now + 1).map { it.metric.latencyMs })
        assertTrue(queue.peek(owner, now + 1).isEmpty())
    }

    @Test
    fun `events older than seven days and corrupt or oversized queues are discarded`() {
        val queue = ClientMetricOutbox.decode("", now)
        queue.append(owner, metric(), now)
        assertTrue(ClientMetricOutbox.decode(queue.encode(), now + 7 * 24 * 60 * 60 * 1000L + 1).peek(owner, now).isEmpty())
        assertTrue(ClientMetricOutbox.decode("broken JSON", now).peek(owner, now).isEmpty())
        assertTrue(ClientMetricOutbox.decode("x".repeat(ClientMetricOutbox.MAX_BYTES + 1), now).peek(owner, now).isEmpty())
    }

    @Test
    fun `raw URLs credentials exception messages and extra fields cannot enter the diagnostic payload`() {
        val queue = ClientMetricOutbox.decode("", now)
        val privateText = "https://private.invalid/visited?token=fixture"
        assertFailsWith<IllegalArgumentException> { queue.append(owner, metric().copy(event = privateText), now) }
        assertFailsWith<IllegalArgumentException> { queue.append(owner, metric().copy(errorCode = privateText), now) }
        assertFailsWith<IllegalArgumentException> { queue.append(owner, metric().copy(serverId = privateText), now) }
        val injected = """{"owner":"$owner","items":[],"raw_log":"$privateText"}"""
        val restored = ClientMetricOutbox.decode(injected, now)
        assertFalse(restored.encode().contains(privateText))
        assertEquals("tls", serviceFailureCode(SSLException(privateText)))
        assertEquals("dns", serviceFailureCode(UnknownHostException(privateText)))
    }

    @Test
    fun `service lifecycle emits once without needing an Activity and retains only an opaque endpoint`() {
        val tracker = ServiceLifecycleTracker()
        assertNull(tracker.transition(transition(ServiceState.Connecting)))
        assertEquals(LifecycleMetric("connect_succeeded", endpoint), tracker.transition(transition(ServiceState.Connected))?.metric)
        assertNull(tracker.transition(transition(ServiceState.Connected)))
        assertNull(tracker.transition(transition(ServiceState.Stopping)))
        assertEquals(LifecycleMetric("disconnected", endpoint, "tls"), tracker.transition(transition(ServiceState.Stopped, "tls"))?.metric)
        assertNull(tracker.transition(transition(ServiceState.Stopped)))
    }

    @Test
    fun `failed startup has a category while user canceled startup is not counted as a fault`() {
        val failed = ServiceLifecycleTracker()
        failed.transition(transition(ServiceState.Connecting))
        failed.transition(transition(ServiceState.Stopping))
        assertEquals(LifecycleMetric("connect_failed", endpoint, "dns"), failed.transition(transition(ServiceState.Stopped, "dns"))?.metric)
        val canceled = ServiceLifecycleTracker()
        canceled.transition(transition(ServiceState.Connecting))
        canceled.transition(transition(ServiceState.Stopping))
        assertNull(canceled.transition(transition(ServiceState.Stopped)))
    }

    @Test
    fun `delayed lifecycle events retain capture owner and time and are rejected for another account`() {
        val tracker = ServiceLifecycleTracker()
        tracker.transition(transition(ServiceState.Connecting))
        val delayed = tracker.transition(transition(ServiceState.Connected))!!
        assertEquals(owner, delayed.session.owner)
        assertEquals(now, delayed.occurredAt)
        assertTrue(delayed.belongsTo(owner))
        assertFalse(delayed.belongsTo(otherOwner))
        assertNull(tracker.transition(transition(ServiceState.Stopped).copy(session = ServiceMetricSession(otherOwner, 2))))
        assertNull(tracker.transition(transition(ServiceState.Connecting).copy(session = null)))
        assertNull(tracker.transition(transition(ServiceState.Connected).copy(session = null)))
    }

    private fun transition(state: ServiceState, error: String? = null) = ServiceMetricTransition(
        ServiceMetricSession(owner, 1), state, endpoint, error, now)
}
