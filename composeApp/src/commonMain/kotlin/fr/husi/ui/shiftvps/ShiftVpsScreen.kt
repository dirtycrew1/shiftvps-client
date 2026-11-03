package fr.husi.ui.shiftvps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.husi.bg.ServiceState
import fr.husi.compose.rememberVpnServiceLauncher
import fr.husi.permission.AppPermission
import fr.husi.permission.LocalPermissionPlatform
import fr.husi.resources.*
import fr.husi.shiftvps.ShiftVpsController
import fr.husi.shiftvps.ShiftVpsEvent
import fr.husi.shiftvps.ShiftVpsServer
import fr.husi.shiftvps.ShiftVpsUiState
import fr.husi.ui.MainScreenAuthChallengeDialogs
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

internal val ShiftVpsColors = darkColorScheme(
    primary = Color(0xFF8194FF),
    onPrimary = Color(0xFF10132A),
    primaryContainer = Color(0xFF242C4B),
    onPrimaryContainer = Color(0xFFE0E4FF),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFF2F3F6),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFF2F3F6),
    surfaceVariant = Color(0xFF252931),
    onSurfaceVariant = Color(0xFFAEB4C0),
    outline = Color(0xFF626977),
    outlineVariant = Color(0xFF30353F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF382022),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun ShiftVpsMainScreen(modifier: Modifier = Modifier) {
    val holder = viewModel { ShiftVpsControllerHolder() }
    val controller = holder.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val updateState by holder.updater.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val permission = LocalPermissionPlatform.current
    var browserError by remember { mutableStateOf(false) }
    val launchVpn = rememberVpnServiceLauncher(controller::permissionDenied)
    val currentLaunchVpn by rememberUpdatedState(launchVpn)
    LifecycleResumeEffect(controller) {
        controller.onForeground()
        holder.updater.onResume()
        onPauseOrDispose { holder.updater.onPause() }
    }
    LaunchedEffect(state.serviceState, permission) {
        if (state.serviceState == ServiceState.Connected &&
            permission.canRequestPermission(AppPermission.PostNotifications) &&
            !permission.hasPermission(AppPermission.PostNotifications)
        ) {
            permission.requestPermission(AppPermission.PostNotifications)
        }
    }
    LaunchedEffect(controller, uriHandler) {
        controller.events.collect { event ->
            when (event) {
                is ShiftVpsEvent.OpenBrowser -> browserError = try {
                    uriHandler.openUri(event.url)
                    false
                } catch (_: Exception) {
                    controller.cancelLogin()
                    true
                }
                ShiftVpsEvent.LaunchVpn -> currentLaunchVpn()
            }
        }
    }
    MaterialTheme(colorScheme = ShiftVpsColors, typography = MaterialTheme.typography) {
        ShiftVpsScreen(
            state = state,
            modifier = modifier,
            browserError = browserError,
            onSignIn = { browserError = false; controller.signIn() },
            onCancelLogin = { browserError = false; controller.cancelLogin() },
            onRefresh = controller::refresh,
            onCheckServers = controller::checkServers,
            onSelectServer = controller::selectServer,
            onToggleConnection = {
                if (updateState.phase != fr.husi.shiftvps.ShiftVpsUpdatePhase.Installing || state.serviceState.canStop) {
                    controller.toggleConnection()
                }
            },
            onLogout = controller::logout,
            onDismissError = { browserError = false; controller.dismissError() },
            updateState = updateState,
            onUpdate = holder.updater::install,
            onRefreshSummary = controller::refreshSummary,
            onOpenAccount = controller::openAccount,
            onOpenPayment = controller::openPayment,
            onOpenReferral = controller::openReferral,
            accountActions = controller,
        )
        MainScreenAuthChallengeDialogs(onDismissed = controller::permissionDenied)
    }
}

private class ShiftVpsControllerHolder : ViewModel() {
    val controller = ShiftVpsController()
    val updater = fr.husi.shiftvps.platformShiftVpsApplicationUpdater().also { it.initialize() }

    override fun onCleared() {
        updater.close()
        controller.close()
        super.onCleared()
    }
}
