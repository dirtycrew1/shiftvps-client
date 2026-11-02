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

private val ShiftVpsColors = darkColorScheme(
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
    val controller = viewModel { ShiftVpsControllerHolder() }.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val permission = LocalPermissionPlatform.current
    var browserError by remember { mutableStateOf(false) }
    val launchVpn = rememberVpnServiceLauncher(controller::permissionDenied)
    val currentLaunchVpn by rememberUpdatedState(launchVpn)
    LifecycleResumeEffect(controller) {
        controller.onForeground()
        onPauseOrDispose { }
    }
    LaunchedEffect(controller, uriHandler) {
        controller.events.collect { event ->
            when (event) {
                is ShiftVpsEvent.OpenBrowser -> browserError = try {
                    uriHandler.openUri(event.url)
                    false
                } catch (_: Exception) {
                    true
                }
                ShiftVpsEvent.LaunchVpn -> {
                    fun requestLocalNetworkAndLaunch() {
                        if (permission.canRequestPermission(AppPermission.LocalNetwork) &&
                            !permission.hasPermission(AppPermission.LocalNetwork)
                        ) {
                            permission.requestPermission(AppPermission.LocalNetwork) { currentLaunchVpn() }
                        } else currentLaunchVpn()
                    }
                    if (permission.canRequestPermission(AppPermission.PostNotifications) &&
                        !permission.hasPermission(AppPermission.PostNotifications)
                    ) {
                        permission.requestPermission(AppPermission.PostNotifications) {
                            requestLocalNetworkAndLaunch()
                        }
                    } else requestLocalNetworkAndLaunch()
                }
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
            onToggleConnection = controller::toggleConnection,
            onLogout = controller::logout,
            onDismissError = { browserError = false; controller.dismissError() },
        )
        MainScreenAuthChallengeDialogs(onDismissed = controller::permissionDenied)
    }
}

private class ShiftVpsControllerHolder : ViewModel() {
    val controller = ShiftVpsController()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}

@Composable
internal fun ShiftVpsScreen(
    state: ShiftVpsUiState,
    onSignIn: () -> Unit,
    onCancelLogin: () -> Unit,
    onRefresh: () -> Unit,
    onCheckServers: () -> Unit,
    onSelectServer: (Long) -> Unit,
    onToggleConnection: () -> Unit,
    onLogout: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    browserError: Boolean = false,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight()
                    .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("SHIFT VPS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (state.signedIn && state.accountName.isNotBlank()) {
                            Text(state.accountName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (state.signedIn) {
                        TextButton(onLogout, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.shiftvps_logout))
                        }
                    }
                }
                val error = if (browserError) stringResource(Res.string.shiftvps_browser_error) else state.error
                error?.let {
                    ErrorMessage(it, onDismissError)
                    Spacer(Modifier.height(16.dp))
                }
                if (state.signedIn) {
                    ServerContent(state, onRefresh, onCheckServers, onSelectServer, onToggleConnection, Modifier.weight(1f))
                } else {
                    SignInContent(state, onSignIn, onCancelLogin, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SignInContent(state: ShiftVpsUiState, onSignIn: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val waiting = state.authCode != null
        Text(stringResource(if (waiting) Res.string.shiftvps_sign_in_waiting else Res.string.shiftvps_sign_in_title),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(if (waiting) Res.string.shiftvps_sign_in_code else Res.string.shiftvps_sign_in_description),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        state.authCode?.let {
            Text(it, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(20.dp))
        }
        if (waiting) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            TextButton(onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.shiftvps_cancel)) }
        } else {
            Button(onSignIn, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
                if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(stringResource(Res.string.shiftvps_sign_in), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ServerContent(
    state: ShiftVpsUiState,
    onRefresh: () -> Unit,
    onCheckServers: () -> Unit,
    onSelectServer: (Long) -> Unit,
    onToggleConnection: () -> Unit,
    modifier: Modifier,
) {
    val connected = state.serviceState == ServiceState.Connected
    val selectionLocked = state.serviceState.canStop || state.serviceState == ServiceState.Stopping
    val selectedServer = state.servers.firstOrNull { it.id == state.selectedId }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
            Text(stringResource(when (state.serviceState) {
                ServiceState.Connected -> Res.string.shiftvps_connected
                ServiceState.Connecting -> Res.string.shiftvps_connecting
                ServiceState.Stopping -> Res.string.shiftvps_disconnecting
                else -> Res.string.shiftvps_disconnected
            }), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(6.dp))
            Text(selectedServer?.name ?: stringResource(Res.string.shiftvps_select_server),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.shiftvps_server_list), style = MaterialTheme.typography.titleMedium)
            TextButton(onRefresh, enabled = !state.loading && !state.refreshing && !state.probing, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.shiftvps_refresh))
            }
        }
        Text(stringResource(when {
            state.refreshing -> Res.string.shiftvps_refreshing
            state.probing -> Res.string.shiftvps_checking_servers
            selectionLocked -> Res.string.shiftvps_selection_locked
            else -> Res.string.shiftvps_sorted_by_latency
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (state.refreshing || state.probing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.servers.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(Res.string.shiftvps_no_servers), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.shiftvps_no_servers_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onRefresh, enabled = !state.refreshing) { Text(stringResource(Res.string.shiftvps_retry)) }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(state.servers, key = { it.id }) { server ->
                    ServerRow(server, server.id == state.selectedId, !selectionLocked && !state.loading) { onSelectServer(server.id) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.lastUpdatedMillis?.let {
                stringResource(Res.string.shiftvps_updated_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
            } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onCheckServers, enabled = !state.probing && !state.refreshing && !state.loading && state.servers.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.shiftvps_check_servers), style = MaterialTheme.typography.labelMedium)
            }
        }
        Button(onToggleConnection,
            enabled = state.serviceState != ServiceState.Stopping && (state.serviceState.canStop || (!state.loading && selectedServer != null)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
            colors = if (connected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface) else ButtonDefaults.buttonColors()) {
            if (state.serviceState == ServiceState.Connecting || state.serviceState == ServiceState.Stopping) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(stringResource(when (state.serviceState) {
                ServiceState.Connected -> Res.string.shiftvps_disconnect
                ServiceState.Connecting -> Res.string.shiftvps_cancel_connection
                ServiceState.Stopping -> Res.string.shiftvps_disconnecting
                else -> Res.string.shiftvps_connect
            }), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(Res.string.shiftvps_ru_direct), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp))
    }
}

@Composable
private fun ServerRow(server: ShiftVpsServer, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 68.dp).padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(disabledSelectedColor = MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(12.dp))
        Text(server.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Text(when {
            server.checking -> stringResource(Res.string.shiftvps_checking)
            server.reachable == false -> stringResource(Res.string.shiftvps_unavailable)
            server.latencyMs != null -> stringResource(Res.string.shiftvps_latency_ms, server.latencyMs)
            else -> "—"
        }, style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun ErrorMessage(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.shiftvps_dismiss), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
