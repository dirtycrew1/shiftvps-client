package fr.husi.shiftvps

import fr.husi.resources.Res
import fr.husi.resources.shiftvps_mac_daemon_conflict
import fr.husi.resources.shiftvps_mac_daemon_unavailable
import fr.husi.resources.shiftvps_mac_elevation_unavailable
import fr.husi.resources.shiftvps_mac_permission_cancelled

internal enum class DesktopVpnSetupFailure {
    PermissionCancelled,
    ElevationUnavailable,
    DaemonUnavailable,
    DaemonConflict,
}

internal class DesktopConnectRequest(
    private val sameRequest: () -> Boolean,
    private val allowed: () -> Boolean,
    private val finish: suspend (DesktopVpnSetupFailure?) -> Unit,
) {
    fun isCurrent(): Boolean = sameRequest() && allowed()

    suspend fun complete(reason: DesktopVpnSetupFailure? = null) {
        if (sameRequest()) finish(reason?.takeIf { allowed() })
    }
}

internal fun desktopVpnSetupResource(reason: DesktopVpnSetupFailure) = when (reason) {
    DesktopVpnSetupFailure.PermissionCancelled -> Res.string.shiftvps_mac_permission_cancelled
    DesktopVpnSetupFailure.ElevationUnavailable -> Res.string.shiftvps_mac_elevation_unavailable
    DesktopVpnSetupFailure.DaemonUnavailable -> Res.string.shiftvps_mac_daemon_unavailable
    DesktopVpnSetupFailure.DaemonConflict -> Res.string.shiftvps_mac_daemon_conflict
}

internal fun desktopVpnSetupMetric(reason: DesktopVpnSetupFailure) = when (reason) {
    DesktopVpnSetupFailure.PermissionCancelled, DesktopVpnSetupFailure.ElevationUnavailable -> "permission"
    DesktopVpnSetupFailure.DaemonUnavailable, DesktopVpnSetupFailure.DaemonConflict -> "configuration"
}
