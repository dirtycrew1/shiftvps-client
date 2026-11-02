package fr.husi.shiftvps

import fr.husi.core.CoreClient
import fr.husi.core.CoreRpcException
import fr.husi.core.urlTestOptions
import fr.husi.fmt.ConfigBuildResult
import fr.husi.proto.v1.PluginProcessSpec
import io.github.xchacha20_poly1305.kurpc.Status
import kotlinx.coroutines.CancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class ServerProbeFailure(val metricCode: String) {
    Setup("configuration"),
    Configuration("configuration"),
    CoreUnavailable("unknown"),
    Timeout("timeout"),
    Dns("dns"),
    Tls("tls"),
    Permission("permission"),
    InvalidResult("unknown"),
    Unknown("unknown"),
}

internal sealed interface ServerProbeResult {
    data class Success(val latencyMs: Int) : ServerProbeResult
    data class Inconclusive(val reason: ServerProbeFailure) : ServerProbeResult
}

internal suspend fun probeServer(
    core: CoreClient,
    config: ConfigBuildResult,
    latency: ShiftVpsLatency,
    plugins: List<PluginProcessSpec>,
): ServerProbeResult = try {
    val measured = core.standaloneUrlTest(
        config.configJson, config.metadata.mainTag, latency.testUrl,
        latency.timeoutMs, urlTestOptions(false, false), plugins,
    )
    // The core can complete a successful HTTP request in less than one millisecond.
    if (measured >= 0) ServerProbeResult.Success(measured.coerceAtLeast(1))
    else ServerProbeResult.Inconclusive(ServerProbeFailure.InvalidResult)
} catch (error: Exception) {
    if (error is CancellationException) throw error
    val reason = when (error) {
        is CoreRpcException -> when (error.code) {
            Status.Code.UNAVAILABLE -> ServerProbeFailure.CoreUnavailable
            Status.Code.DEADLINE_EXCEEDED -> ServerProbeFailure.Timeout
            Status.Code.INVALID_ARGUMENT, Status.Code.NOT_FOUND, Status.Code.FAILED_PRECONDITION -> ServerProbeFailure.Configuration
            Status.Code.PERMISSION_DENIED -> ServerProbeFailure.Permission
            else -> ServerProbeFailure.Unknown
        }
        is SocketTimeoutException -> ServerProbeFailure.Timeout
        is UnknownHostException -> ServerProbeFailure.Dns
        is SSLException -> ServerProbeFailure.Tls
        is SecurityException -> ServerProbeFailure.Permission
        else -> ServerProbeFailure.Unknown
    }
    ServerProbeResult.Inconclusive(reason)
}
