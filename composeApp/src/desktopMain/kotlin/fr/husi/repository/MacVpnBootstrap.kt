package fr.husi.repository

import fr.husi.shiftvps.DesktopVpnSetupFailure
import fr.husi.shiftvps.DesktopConnectRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface MacVpnBootstrapResult {
    data object Started : MacVpnBootstrapResult
    data object AlreadyActive : MacVpnBootstrapResult
    data object Busy : MacVpnBootstrapResult
    data object Abandoned : MacVpnBootstrapResult
    data class Failed(val reason: DesktopVpnSetupFailure) : MacVpnBootstrapResult
}

internal suspend fun MacVpnBootstrapResult.completeRequest(request: DesktopConnectRequest) {
    when (this) {
        is MacVpnBootstrapResult.Failed -> request.complete(reason)
        MacVpnBootstrapResult.Abandoned, MacVpnBootstrapResult.AlreadyActive, MacVpnBootstrapResult.Busy -> request.complete()
        MacVpnBootstrapResult.Started -> Unit
    }
}

/** Only a manual Connect calls this; the platform owns the administrator password dialog. */
internal class MacVpnBootstrap(
    private val isServiceActive: () -> Boolean,
    private val attachDaemon: suspend () -> CoreHostState,
    private val installation: suspend () -> MacDaemonInstallation,
    private val install: suspend () -> DaemonInstallResult,
    private val startService: () -> Unit,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val access = Mutex()

    suspend fun connect(isRequestCurrent: () -> Boolean): MacVpnBootstrapResult {
        if (!access.tryLock()) return MacVpnBootstrapResult.Busy
        try {
            if (!isRequestCurrent()) return MacVpnBootstrapResult.Abandoned
            if (isServiceActive()) return MacVpnBootstrapResult.AlreadyActive
            var host = withTimeoutOrNull(DAEMON_READY_TIMEOUT_MS) { attachDaemon() }
                ?: return failed(DesktopVpnSetupFailure.DaemonUnavailable)
            if (!isRequestCurrent()) return MacVpnBootstrapResult.Abandoned
            if (isServiceActive()) return MacVpnBootstrapResult.AlreadyActive
            if (host.foreignOwner != null) return failed(DesktopVpnSetupFailure.DaemonConflict)
            if (!host.isDaemon) {
                if (installation() != MacDaemonInstallation.Absent) return failed(DesktopVpnSetupFailure.DaemonUnavailable)
                if (!isRequestCurrent()) return MacVpnBootstrapResult.Abandoned
                if (isServiceActive()) return MacVpnBootstrapResult.AlreadyActive
                when (install()) {
                    DaemonInstallResult.Success -> Unit
                    DaemonInstallResult.Cancelled -> return failed(DesktopVpnSetupFailure.PermissionCancelled)
                    is DaemonInstallResult.ElevationUnavailable -> return failed(DesktopVpnSetupFailure.ElevationUnavailable)
                    is DaemonInstallResult.Failed -> return failed(DesktopVpnSetupFailure.DaemonUnavailable)
                }
                if (!isRequestCurrent()) return MacVpnBootstrapResult.Abandoned
                if (isServiceActive()) return MacVpnBootstrapResult.AlreadyActive
                host = withTimeoutOrNull(DAEMON_READY_TIMEOUT_MS) {
                    repeat(DAEMON_READY_ATTEMPTS) {
                        if (!isRequestCurrent()) return@withTimeoutOrNull CoreHostState()
                        if (isServiceActive()) return@withTimeoutOrNull CoreHostState()
                        val current = attachDaemon()
                        if (current.isDaemon || current.foreignOwner != null) return@withTimeoutOrNull current
                        wait(DAEMON_READY_INTERVAL_MS)
                    }
                    null
                } ?: return failed(DesktopVpnSetupFailure.DaemonUnavailable)
            }
            if (!isRequestCurrent()) return MacVpnBootstrapResult.Abandoned
            if (isServiceActive()) return MacVpnBootstrapResult.AlreadyActive
            if (host.foreignOwner != null) return failed(DesktopVpnSetupFailure.DaemonConflict)
            if (!host.isDaemon) return failed(DesktopVpnSetupFailure.DaemonUnavailable)
            startService()
            return MacVpnBootstrapResult.Started
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(DesktopVpnSetupFailure.DaemonUnavailable)
        } finally {
            access.unlock()
        }
    }

    private fun failed(reason: DesktopVpnSetupFailure) = MacVpnBootstrapResult.Failed(reason)

    private companion object {
        const val DAEMON_READY_TIMEOUT_MS = 10_000L
        const val DAEMON_READY_ATTEMPTS = 20
        const val DAEMON_READY_INTERVAL_MS = 250L
    }
}
