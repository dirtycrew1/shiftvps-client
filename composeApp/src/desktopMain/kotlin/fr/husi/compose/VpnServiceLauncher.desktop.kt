package fr.husi.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import fr.husi.platform.PlatformInfo
import fr.husi.repository.DesktopRepository
import fr.husi.repository.MacVpnBootstrap
import fr.husi.repository.completeRequest
import fr.husi.repository.installMacDaemonIfAbsent
import fr.husi.repository.macDaemonInstallation
import fr.husi.repository.resolveRepository
import fr.husi.shiftvps.ShiftVpsController
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
actual fun rememberVpnServiceLauncher(onFailed: () -> Unit): () -> Unit {
    return { resolveRepository().startService() }
}

@Composable
actual fun rememberManagedVpnServiceLauncher(onFailed: () -> Unit): () -> Unit {
    if (!PlatformInfo.isMacOs) return rememberVpnServiceLauncher(onFailed)
    val repository = resolveRepository() as DesktopRepository
    val scope = rememberCoroutineScope()
    val bootstrap = remember(repository) {
        MacVpnBootstrap(
            isServiceActive = { repository.isServiceActiveForManagedConnect },
            attachDaemon = repository::attachDaemonWhileStopped,
            installation = ::macDaemonInstallation,
            install = ::installMacDaemonIfAbsent,
            startService = repository::startService,
        )
    }
    return {
        scope.launch {
            val request = ShiftVpsController.pendingDesktopConnectRequest() ?: return@launch
            try {
                bootstrap.connect(request::isCurrent).completeRequest(request)
            } catch (cancelled: CancellationException) {
                request.complete()
                throw cancelled
            }
        }
    }
}
