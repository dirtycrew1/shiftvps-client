package fr.husi.shiftvps

import fr.husi.core.CoreRpcException
import fr.husi.fmt.ConfigBuildResult
import fr.husi.fmt.ConfigMetadata
import fr.husi.proto.v1.PluginProcessSpec
import fr.husi.proto.v1.URLTestOptions
import fr.husi.test.FakeCoreClient
import io.github.xchacha20_poly1305.kurpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ShiftVpsServerProbeTest {
    private val config = ConfigBuildResult(
        "fixture config",
        ConfigMetadata("real-proxy", emptyList(), trafficProfiles = emptyList(), tagToID = emptyMap()),
    )
    private val latency = ShiftVpsLatency()

    @Test
    fun `HTTP test explicitly uses the profile outbound and preserves successful zero latency`() = runTest {
        var calls = 0
        val core = object : FakeCoreClient() {
            override suspend fun standaloneUrlTest(
                config: String, tag: String, link: String, timeoutMs: Int,
                options: URLTestOptions, plugins: List<PluginProcessSpec>,
            ): Int {
                calls++
                assertEquals("fixture config", config)
                assertEquals("real-proxy", tag)
                assertEquals(latency.testUrl, link)
                assertEquals(latency.timeoutMs, timeoutMs)
                return 0
            }
        }
        val result = assertIs<ServerProbeResult.Success>(probeServer(core, config, latency, emptyList()))
        assertEquals(1, result.latencyMs)
        assertEquals(1, calls)
    }

    @Test
    fun `core failures stay inconclusive and expose only fixed safe diagnostic codes`() = runTest {
        for ((code, expected) in listOf(
            Status.Code.UNAVAILABLE to ServerProbeFailure.CoreUnavailable,
            Status.Code.DEADLINE_EXCEEDED to ServerProbeFailure.Timeout,
            Status.Code.NOT_FOUND to ServerProbeFailure.Configuration,
            Status.Code.INTERNAL to ServerProbeFailure.Unknown,
        )) {
            val core = failingCore(CoreRpcException(code, "private uri credential fixture"))
            val result = assertIs<ServerProbeResult.Inconclusive>(probeServer(core, config, latency, emptyList()))
            assertEquals(expected, result.reason)
        }
        val negativeCore = object : FakeCoreClient() {
            override suspend fun standaloneUrlTest(
                config: String, tag: String, link: String, timeoutMs: Int,
                options: URLTestOptions, plugins: List<PluginProcessSpec>,
            ): Int = -1
        }
        val negative = assertIs<ServerProbeResult.Inconclusive>(probeServer(negativeCore, config, latency, emptyList()))
        assertEquals(ServerProbeFailure.InvalidResult, negative.reason)
    }

    @Test
    fun `cancellation remains cancellation rather than an unavailable node`() = runTest {
        assertFailsWith<CancellationException> {
            probeServer(failingCore(CancellationException("cancelled")), config, latency, emptyList())
        }
    }

    private fun failingCore(error: Exception) = object : FakeCoreClient() {
        override suspend fun standaloneUrlTest(
            config: String, tag: String, link: String, timeoutMs: Int,
            options: URLTestOptions, plugins: List<PluginProcessSpec>,
        ): Int = throw error
    }
}
