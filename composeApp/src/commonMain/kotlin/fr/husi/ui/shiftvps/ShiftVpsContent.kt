package fr.husi.ui.shiftvps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.husi.bg.ServiceState
import fr.husi.resources.*
import fr.husi.shiftvps.ShiftVpsServer
import fr.husi.shiftvps.ShiftVpsAccountActions
import fr.husi.shiftvps.ShiftVpsAccessState
import fr.husi.shiftvps.ShiftVpsUiState
import fr.husi.shiftvps.ShiftVpsUpdatePhase
import fr.husi.shiftvps.ShiftVpsUpdateState
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

private val CardShape = RoundedCornerShape(20.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

internal enum class ShiftVpsTab { Connection, Account, Invitations }

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
    updateState: ShiftVpsUpdateState = ShiftVpsUpdateState(),
    onUpdate: () -> Unit = {},
    onCheckAppUpdate: () -> Unit = {},
    onRefreshSummary: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenPayment: () -> Unit = {},
    onOpenReferral: () -> Unit = {},
    accountActions: ShiftVpsAccountActions? = null,
    initialTab: ShiftVpsTab = ShiftVpsTab.Connection,
    initialAccountSection: ShiftVpsAccountSection = ShiftVpsAccountSection.Overview,
    onTestSpeed: () -> Unit = {},
) {
    var selectedTab by remember(state.signedIn) { mutableStateOf(initialTab) }
    var accountSection by remember(state.signedIn) { mutableStateOf(initialAccountSection) }
    ShiftVpsTheme {
        Surface(modifier.fillMaxSize(), color = ShiftVpsPalette.Background) {
            Box(Modifier.fillMaxSize().drawBehind {
                val spacing = 22.dp.toPx()
                val radius = 0.65.dp.toPx()
                var y = 14.dp.toPx()
                while (y < size.height) {
                    val alpha = 0.28f * (1f - y / size.height)
                    var x = 12.dp.toPx()
                    while (x < size.width) {
                        drawCircle(ShiftVpsPalette.Muted.copy(alpha = alpha), radius, Offset(x, y))
                        x += spacing
                    }
                    y += spacing
                }
            }, contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxSize()
                        .statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
                ) {
                    BrandHeader(state)
                    val error = if (browserError) stringResource(Res.string.shiftvps_browser_error) else state.error
                    if (state.signedIn) {
                        if (browserError && selectedTab != ShiftVpsTab.Connection) {
                            ErrorMessage(stringResource(Res.string.shiftvps_browser_error), onDismissError)
                            Spacer(Modifier.height(8.dp))
                        }
                        when (selectedTab) {
                            ShiftVpsTab.Connection -> if (state.accessState != ShiftVpsAccessState.Active) {
                                InactiveAccessContent(state, onChoosePlan = {
                                    accountSection = ShiftVpsAccountSection.Subscription
                                    selectedTab = ShiftVpsTab.Account
                                }, onRefresh = onRefreshSummary, onDisconnect = onToggleConnection, modifier = Modifier.weight(1f))
                            } else ServerContent(state, updateState, error, onRefresh, onCheckServers, onSelectServer,
                                onToggleConnection, onDismissError, onUpdate, onTestSpeed, Modifier.weight(1f))
                            else -> ShiftVpsAccountContent(state, selectedTab, onRefreshSummary, onOpenAccount,
                                onOpenReferral, onLogout, Modifier.weight(1f), accountSection,
                                { accountSection = it }, accountActions, updateState, onCheckAppUpdate, onUpdate)
                        }
                        ShiftVpsBottomNavigation(selectedTab, {
                            accountSection = ShiftVpsAccountSection.Overview
                            selectedTab = it
                        },
                            Modifier.padding(top = 8.dp, bottom = 12.dp))
                    } else {
                        SignInContent(state, updateState, error, onSignIn, onCancelLogin,
                            onUpdate, onOpenPayment, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InactiveAccessContent(state: ShiftVpsUiState, onChoosePlan: () -> Unit, onRefresh: () -> Unit,
    onDisconnect: () -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (state.serviceState.canStop) item {
            ConnectionCard(state, false, onDisconnect)
            Spacer(Modifier.height(20.dp))
        }
        item {
            Surface(color = ShiftVpsPalette.Card, shape = CardShape, border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    val inactive = state.accessState == ShiftVpsAccessState.Inactive
                    Text(stringResource(if (inactive) Res.string.shiftvps_account_inactive else Res.string.shiftvps_account_access_status),
                        style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(if (inactive) Res.string.shiftvps_account_inactive_hint else Res.string.shiftvps_account_refresh_access), style = MaterialTheme.typography.bodyLarge,
                        color = ShiftVpsPalette.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
                    BrandButton(stringResource(if (inactive) Res.string.shiftvps_account_choose_plan else Res.string.shiftvps_refresh),
                        if (inactive) onChoosePlan else onRefresh, enabled = !state.loading, busy = state.loading)
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(state: ShiftVpsUiState) {
    Row(Modifier.fillMaxWidth().heightIn(min = 84.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("SHIFT") }
                withStyle(SpanStyle(color = ShiftVpsPalette.Muted, fontWeight = FontWeight.Medium)) { append("VPS") }
            }, style = MaterialTheme.typography.titleLarge, letterSpacing = (-0.6).sp)
            if (state.signedIn && state.accountName.isNotBlank()) {
                Text(state.accountName, style = MaterialTheme.typography.bodySmall,
                    color = ShiftVpsPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, end = 12.dp))
            }
        }
    }
}

@Composable
internal fun ShiftVpsBottomNavigation(selectedTab: ShiftVpsTab, onSelect: (ShiftVpsTab) -> Unit,
    modifier: Modifier = Modifier) {
    val dockShape = RoundedCornerShape(28.dp)
    val itemShape = RoundedCornerShape(22.dp)
    val density = LocalDensity.current
    val labelLines = if (density.fontScale > 1.2f) 2 else 1
    val itemHeight = maxOf(64.dp, 40.dp + with(density) { (18.sp * labelLines).toDp() })
    BoxWithConstraints(modifier.fillMaxWidth().clip(dockShape)
        .background(Brush.verticalGradient(listOf(
            ShiftVpsPalette.CardRaised.copy(alpha = 0.97f), ShiftVpsPalette.Card.copy(alpha = 0.97f))))
        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(
            ShiftVpsPalette.Text.copy(alpha = 0.18f), ShiftVpsPalette.Border.copy(alpha = 0.7f)))), dockShape)
        .padding(6.dp).selectableGroup()) {
        val itemWidth = maxWidth / ShiftVpsTab.entries.size
        val indicatorOffset by animateDpAsState(itemWidth * selectedTab.ordinal,
            animationSpec = tween(240, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)), label = "navigation selection")
        Box(Modifier.width(itemWidth).height(itemHeight)
            .offset(x = indicatorOffset)
            .background(Brush.verticalGradient(listOf(
                ShiftVpsPalette.Accent.copy(alpha = 0.19f), ShiftVpsPalette.Selected.copy(alpha = 0.8f))), itemShape)
            .border(1.dp, ShiftVpsPalette.Accent.copy(alpha = 0.17f), itemShape)
            .semantics { testTag = "shiftvps-navigation-indicator" })
        Row(Modifier.fillMaxWidth()) {
            ShiftVpsTab.entries.forEach { tab ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val focused by interaction.collectIsFocusedAsState()
                val color by animateColorAsState(if (tab == selectedTab) ShiftVpsPalette.Text else ShiftVpsPalette.Muted,
                    animationSpec = tween(140), label = "navigation label")
                Column(Modifier.weight(1f).height(itemHeight).clip(itemShape)
                    .background(if (pressed || focused) ShiftVpsPalette.Text.copy(alpha = 0.08f) else Color.Transparent)
                    .then(if (focused) Modifier.border(1.dp, ShiftVpsPalette.Accent, itemShape) else Modifier)
                    .selectable(selected = tab == selectedTab, role = Role.Tab,
                        interactionSource = interaction, indication = null, onClick = { onSelect(tab) })
                    .padding(horizontal = 2.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    NavigationMark(tab, color, Modifier.size(23.dp))
                    Text(stringResource(when (tab) {
                        ShiftVpsTab.Connection -> Res.string.shiftvps_tab_connection
                        ShiftVpsTab.Account -> Res.string.shiftvps_tab_account
                        ShiftVpsTab.Invitations -> Res.string.shiftvps_tab_invitations
                    }), color = color, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        maxLines = labelLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun NavigationMark(tab: ShiftVpsTab, color: Color, modifier: Modifier) {
    if (tab == ShiftVpsTab.Connection) {
        PowerMark(color, modifier)
    } else {
        Canvas(modifier) {
            val stroke = Stroke(width = size.width * 0.075f, cap = StrokeCap.Round)
            val centerX = size.width * if (tab == ShiftVpsTab.Account) 0.5f else 0.39f
            drawCircle(color, size.width * 0.16f, Offset(centerX, size.height * 0.29f), style = stroke)
            val shoulders = Path().apply {
                moveTo(centerX - size.width * 0.28f, size.height * 0.86f)
                lineTo(centerX - size.width * 0.28f, size.height * 0.75f)
                cubicTo(centerX - size.width * 0.28f, size.height * 0.48f,
                    centerX + size.width * 0.28f, size.height * 0.48f,
                    centerX + size.width * 0.28f, size.height * 0.75f)
                lineTo(centerX + size.width * 0.28f, size.height * 0.86f)
            }
            drawPath(shoulders, color, style = stroke)
            if (tab == ShiftVpsTab.Invitations) {
                drawLine(color, Offset(size.width * 0.72f, size.height * 0.34f),
                    Offset(size.width * 0.98f, size.height * 0.34f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.85f, size.height * 0.21f),
                    Offset(size.width * 0.85f, size.height * 0.47f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SignInContent(
    state: ShiftVpsUiState,
    updateState: ShiftVpsUpdateState,
    error: String?,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onUpdate: () -> Unit,
    onOpenPayment: () -> Unit,
    modifier: Modifier,
) {
    val waiting = state.authCode != null && error == null
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (updateState.phase != ShiftVpsUpdatePhase.Idle) {
            item { UpdateBanner(updateState, onUpdate); Spacer(Modifier.height(24.dp)) }
        }
        item {
            Surface(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(), color = ShiftVpsPalette.Card,
                shape = CardShape, border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(when {
                        error != null -> Res.string.shiftvps_sign_in_failed
                        waiting -> Res.string.shiftvps_sign_in_waiting
                        else -> Res.string.shiftvps_sign_in_title
                    }), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                    when {
                        error != null -> {
                            Text(error, style = MaterialTheme.typography.bodyMedium, color = ShiftVpsPalette.Error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite })
                            Spacer(Modifier.height(28.dp))
                            BrandButton(stringResource(Res.string.shiftvps_retry), onSignIn,
                                enabled = !state.loading && updateState.phase != ShiftVpsUpdatePhase.Installing,
                                busy = state.loading)
                        }
                        waiting -> {
                            Text(stringResource(Res.string.shiftvps_sign_in_code),
                                style = MaterialTheme.typography.bodyMedium, color = ShiftVpsPalette.Muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp)
                                .background(ShiftVpsPalette.CardRaised, ButtonShape).padding(horizontal = 12.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Text(checkNotNull(state.authCode), style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                                Spacer(Modifier.width(12.dp))
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ShiftVpsPalette.Accent)
                            }
                            BrandButton(stringResource(Res.string.shiftvps_cancel), onCancel, secondary = true)
                        }
                        else -> {
                            Spacer(Modifier.height(28.dp))
                            BrandButton(stringResource(Res.string.shiftvps_sign_in), onSignIn,
                                enabled = !state.loading && updateState.phase != ShiftVpsUpdatePhase.Installing, busy = state.loading)
                            Text(stringResource(Res.string.shiftvps_browser_sign_in_hint), style = MaterialTheme.typography.bodySmall,
                                color = ShiftVpsPalette.Muted, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                        }
                    }
                    if (!waiting) {
                        TextButton(onOpenPayment, modifier = Modifier.heightIn(min = 48.dp).padding(top = 8.dp)) {
                            Text(stringResource(Res.string.shiftvps_account_renew), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerContent(
    state: ShiftVpsUiState,
    updateState: ShiftVpsUpdateState,
    error: String?,
    onRefresh: () -> Unit,
    onCheckServers: () -> Unit,
    onSelectServer: (Long) -> Unit,
    onToggleConnection: () -> Unit,
    onDismissError: () -> Unit,
    onUpdate: () -> Unit,
    onTestSpeed: () -> Unit,
    modifier: Modifier,
) {
    val selectionLocked = state.serviceState == ServiceState.Stopping
    LazyColumn(modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (updateState.phase != ShiftVpsUpdatePhase.Idle) {
            item { UpdateBanner(updateState, onUpdate); Spacer(Modifier.height(14.dp)) }
        }
        if (error != null) {
            item { ErrorMessage(error, onDismissError); Spacer(Modifier.height(14.dp)) }
        }
        item {
            ConnectionCard(state, updateState.phase == ShiftVpsUpdatePhase.Installing, onToggleConnection)
            if (state.serviceState.connected) {
                TextButton(onTestSpeed, enabled = !state.speedChecking, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.speedChecking) Res.string.shiftvps_speed_checking else Res.string.shiftvps_speed_test))
                }
                val speedResult = state.downloadKbps?.let {
                    val formatted = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(it / 1000.0)
                    stringResource(Res.string.shiftvps_speed_result, formatted)
                } ?: if (state.speedFailed) stringResource(Res.string.shiftvps_speed_failed) else null
                if (speedResult != null) {
                    Text(speedResult, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted)
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(Res.string.shiftvps_server_list), style = MaterialTheme.typography.titleLarge)
                TextButton(onRefresh, enabled = !state.loading && !state.refreshing && !state.probing,
                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(Res.string.shiftvps_refresh), style = MaterialTheme.typography.labelMedium)
                }
            }
            val notice = when {
                state.refreshing -> Res.string.shiftvps_refreshing
                state.probing -> Res.string.shiftvps_checking_servers
                else -> null
            }
            if (notice != null) {
                Text(stringResource(notice), style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted)
            }
            Spacer(Modifier.height(14.dp))
            if (state.refreshing || state.probing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = ShiftVpsPalette.Accent,
                    trackColor = ShiftVpsPalette.Border)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.servers.isEmpty() && (state.loading || state.refreshing)) {
            item {
                Surface(color = ShiftVpsPalette.Card, shape = CardShape, border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        repeat(3) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(24.dp).background(ShiftVpsPalette.CardRaised, CircleShape))
                                Spacer(Modifier.width(14.dp))
                                Box(Modifier.width(120.dp).height(14.dp).background(ShiftVpsPalette.CardRaised, RoundedCornerShape(4.dp)))
                            }
                        }
                    }
                }
            }
        } else if (state.servers.isEmpty()) {
            item {
                Surface(color = ShiftVpsPalette.Card, shape = CardShape,
                    border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp)) {
                        Text(stringResource(Res.string.shiftvps_no_servers), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(Res.string.shiftvps_no_servers_description), style = MaterialTheme.typography.bodyMedium,
                            color = ShiftVpsPalette.Muted)
                        TextButton(onRefresh, enabled = !state.refreshing, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.shiftvps_retry))
                        }
                    }
                }
            }
        } else {
            itemsIndexed(state.servers, key = { _, server -> server.id }) { index, server ->
                ServerRow(server, server.id == state.selectedId, !selectionLocked && !state.loading,
                    first = index == 0, last = index == state.servers.lastIndex) { onSelectServer(server.id) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.lastUpdatedMillis?.let {
                    stringResource(Res.string.shiftvps_updated_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
                } ?: stringResource(Res.string.shiftvps_auto_check), style = MaterialTheme.typography.bodySmall,
                    color = ShiftVpsPalette.Muted, modifier = Modifier.weight(1f).padding(end = 8.dp))
                TextButton(onCheckServers, enabled = !state.probing && !state.refreshing && !state.loading && state.servers.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(Res.string.shiftvps_check_servers), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: ShiftVpsUiState, installing: Boolean, onToggleConnection: () -> Unit) {
    val connected = state.serviceState == ServiceState.Connected
    val busy = state.serviceState == ServiceState.Connecting || state.serviceState == ServiceState.Stopping
    val selectedServer = state.servers.firstOrNull { it.id == state.selectedId }
    Surface(color = ShiftVpsPalette.Card, shape = CardShape,
        border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(if (connected) ShiftVpsPalette.Selected else ShiftVpsPalette.CardRaised,
                    RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    PowerMark(if (connected) ShiftVpsPalette.Accent else ShiftVpsPalette.Muted, Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connected) {
                            Box(Modifier.size(6.dp).background(ShiftVpsPalette.Online, CircleShape))
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(stringResource(when (state.serviceState) {
                            ServiceState.Connected -> Res.string.shiftvps_connected
                            ServiceState.Connecting -> Res.string.shiftvps_connecting
                            ServiceState.Stopping -> Res.string.shiftvps_disconnecting
                            else -> Res.string.shiftvps_disconnected
                        }), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedServer != null) {
                            ShiftVpsCountryFlag(selectedServer.countryCode, Modifier.size(20.dp, 15.dp))
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(selectedServer?.name ?: stringResource(Res.string.shiftvps_select_server),
                            style = MaterialTheme.typography.bodyMedium, color = ShiftVpsPalette.Muted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            BrandButton(stringResource(when (state.serviceState) {
                ServiceState.Connected -> Res.string.shiftvps_disconnect
                ServiceState.Connecting -> Res.string.shiftvps_cancel_connection
                ServiceState.Stopping -> Res.string.shiftvps_disconnecting
                else -> Res.string.shiftvps_connect
            }), onToggleConnection,
                enabled = state.serviceState != ServiceState.Stopping && (state.serviceState.canStop ||
                    (!installing && !state.loading && selectedServer != null)), busy = busy, secondary = connected,
                leadingBolt = !connected && !busy)
        }
    }
}

@Composable
internal fun BrandButton(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false,
    secondary: Boolean = false, leadingBolt: Boolean = false) {
    val fill = if (secondary || !enabled) Brush.verticalGradient(listOf(ShiftVpsPalette.CardRaised, ShiftVpsPalette.CardRaised))
        else Brush.verticalGradient(listOf(ShiftVpsPalette.ButtonTop, ShiftVpsPalette.ButtonBottom))
    Surface(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { role = Role.Button }
            .then(if (enabled && !secondary) Modifier.shadow(10.dp, ButtonShape,
                ambientColor = Color.Transparent, spotColor = Color(0x663E4FBD)) else Modifier),
        shape = ButtonShape,
        color = Color.Transparent, contentColor = if (enabled) Color.White else ShiftVpsPalette.Muted,
        border = if (secondary) BorderStroke(1.dp, ShiftVpsPalette.Border) else null,
    ) {
        Row(Modifier.fillMaxWidth().background(fill).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = if (enabled) Color.White else ShiftVpsPalette.Muted)
                Spacer(Modifier.width(10.dp))
            } else if (leadingBolt) {
                LightningMark(if (enabled) Color.White else ShiftVpsPalette.Muted, Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LightningMark(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val bolt = Path().apply {
            moveTo(size.width * 0.58f, 0f)
            lineTo(size.width * 0.13f, size.height * 0.56f)
            lineTo(size.width * 0.43f, size.height * 0.56f)
            lineTo(size.width * 0.35f, size.height)
            lineTo(size.width * 0.88f, size.height * 0.4f)
            lineTo(size.width * 0.56f, size.height * 0.4f)
            close()
        }
        drawPath(bolt, color)
    }
}

@Composable
private fun ServerRow(server: ShiftVpsServer, selected: Boolean, enabled: Boolean, first: Boolean, last: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
        bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp)
    Column(Modifier.fillMaxWidth().background(if (selected) ShiftVpsPalette.Selected else ShiftVpsPalette.Card, shape)
        .clip(shape).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = ShiftVpsPalette.Accent,
                    unselectedColor = ShiftVpsPalette.Muted, disabledSelectedColor = ShiftVpsPalette.Accent,
                    disabledUnselectedColor = ShiftVpsPalette.Muted))
            Spacer(Modifier.width(12.dp))
            ShiftVpsCountryFlag(server.countryCode)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (selected) {
                    Text(stringResource(Res.string.shiftvps_selected_server), style = MaterialTheme.typography.bodySmall,
                        color = ShiftVpsPalette.Accent, modifier = Modifier.padding(top = 1.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(when {
                    server.checking -> stringResource(Res.string.shiftvps_checking)
                    server.reachable == false -> stringResource(Res.string.shiftvps_unavailable)
                    server.latencyMs != null -> stringResource(Res.string.shiftvps_latency_ms, server.latencyMs)
                    else -> "—"
                }, style = MaterialTheme.typography.labelMedium,
                    color = if (server.reachable == false) ShiftVpsPalette.Muted else ShiftVpsPalette.Text,
                    textAlign = TextAlign.End, maxLines = 1)
                if (server.latencyMs != null && server.reachable != false && !server.checking) {
                    Text(stringResource(Res.string.shiftvps_latency_label), style = MaterialTheme.typography.bodySmall,
                        color = ShiftVpsPalette.Muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        if (!last) HorizontalDivider(color = ShiftVpsPalette.Border, modifier = Modifier.padding(start = 14.dp, end = 14.dp))
    }
}

@Composable
internal fun UpdateBanner(state: ShiftVpsUpdateState, onUpdate: () -> Unit) {
    val busy = state.phase == ShiftVpsUpdatePhase.Downloading || state.phase == ShiftVpsUpdatePhase.Installing
    val title = stringResource(when (state.phase) {
        ShiftVpsUpdatePhase.Downloading -> Res.string.shiftvps_update_downloading
        ShiftVpsUpdatePhase.Installing -> Res.string.shiftvps_update_installing
        ShiftVpsUpdatePhase.NeedsPermission -> Res.string.shiftvps_update_permission
        ShiftVpsUpdatePhase.NeedsDisconnect -> Res.string.shiftvps_update_disconnect
        ShiftVpsUpdatePhase.Failed -> Res.string.shiftvps_update_failed
        else -> Res.string.shiftvps_update_available
    })
    Surface(color = ShiftVpsPalette.Selected, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF3D477B))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.update), contentDescription = null, tint = ShiftVpsPalette.Accent,
                    modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFE4E7FF))
                    state.availableVersion?.let {
                        Text(stringResource(Res.string.shiftvps_update_version, it), style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC5CCEF))
                    }
                }
                if (busy) {
                    if (state.phase == ShiftVpsUpdatePhase.Downloading) {
                        Text("${state.progressPercent.coerceIn(0, 100)}%", style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFE4E7FF), modifier = Modifier.padding(start = 8.dp))
                    } else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ShiftVpsPalette.Accent)
                } else {
                    TextButton(onUpdate, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(when (state.phase) {
                            ShiftVpsUpdatePhase.Failed -> Res.string.shiftvps_retry
                            ShiftVpsUpdatePhase.NeedsDisconnect, ShiftVpsUpdatePhase.NeedsPermission -> Res.string.shiftvps_update_continue
                            else -> Res.string.shiftvps_update_action
                        }), style = MaterialTheme.typography.labelMedium, color = ShiftVpsPalette.Accent)
                    }
                }
            }
            state.message?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC5CCEF),
                    modifier = Modifier.padding(top = 6.dp))
            }
            if (state.phase == ShiftVpsUpdatePhase.Downloading) {
                LinearProgressIndicator(progress = { state.progressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp),
                    color = ShiftVpsPalette.Accent, trackColor = Color(0xFF3D477B))
            }
        }
    }
}

@Composable
private fun ErrorMessage(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF5C323E))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.shiftvps_dismiss), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun PowerMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.075f, cap = StrokeCap.Round)
        drawArc(color, startAngle = -48f, sweepAngle = 276f, useCenter = false,
            topLeft = Offset(size.width * 0.14f, size.height * 0.14f),
            size = Size(size.width * 0.72f, size.height * 0.72f), style = stroke)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.06f),
            Offset(size.width * 0.5f, size.height * 0.46f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}
