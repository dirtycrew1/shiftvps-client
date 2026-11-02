package fr.husi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.composenativetray.tray.api.Tray
import fr.husi.bg.BackendState
import fr.husi.platform.PlatformInfo
import fr.husi.repository.DesktopRepository
import fr.husi.resources.Res
import fr.husi.resources.app_name
import fr.husi.resources.app_update_check_now
import fr.husi.resources.exit
import fr.husi.resources.ic_shiftvps
import fr.husi.resources.shiftvps_disconnect
import fr.husi.resources.shiftvps_open_app
import fr.husi.shiftvps.DesktopMacUpdater
import fr.husi.shiftvps.DesktopWindowsUpdater
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Keeps the main window reachable without exposing unmanaged connection settings. */
@Composable
internal fun HusiTray(
    repository: DesktopRepository,
    onOpenWindow: () -> Unit,
    onExit: () -> Unit,
) {
    val status by BackendState.status.collectAsState()
    Tray(
        icon = painterResource(Res.drawable.ic_shiftvps),
        tooltip = stringResource(Res.string.app_name),
        primaryAction = onOpenWindow,
        menuContent = {
            Item(label = stringResource(Res.string.shiftvps_open_app), onClick = onOpenWindow)
            if (status.state.canStop) {
                Item(label = stringResource(Res.string.shiftvps_disconnect), onClick = { repository.stopService() })
            }
            if (PlatformInfo.isWindows || PlatformInfo.isMacOs) {
                Item(label = stringResource(Res.string.app_update_check_now), onClick = {
                    if (PlatformInfo.isWindows) DesktopWindowsUpdater.check() else DesktopMacUpdater.check()
                })
            }
            Item(label = stringResource(Res.string.exit), onClick = onExit)
        },
    )
}
