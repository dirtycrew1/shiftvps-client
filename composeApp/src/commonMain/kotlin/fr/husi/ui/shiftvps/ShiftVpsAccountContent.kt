package fr.husi.ui.shiftvps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.husi.compose.setPlainText
import fr.husi.resources.*
import fr.husi.shiftvps.ShiftVpsAccountSummary
import fr.husi.shiftvps.ShiftVpsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date

@Composable
internal fun ShiftVpsAccountContent(
    state: ShiftVpsUiState,
    tab: ShiftVpsTab,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenPayment: () -> Unit,
    onOpenReferral: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val invitations = tab == ShiftVpsTab.Invitations
    val summary = state.summary
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (invitations) Res.string.shiftvps_tab_invitations else Res.string.shiftvps_tab_account),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onRefresh, enabled = !state.summaryLoading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.shiftvps_refresh), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (state.summaryLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = ShiftVpsPalette.Accent,
                    trackColor = ShiftVpsPalette.Border)
            }
        }
        state.summaryError?.let { error ->
            item {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = ShiftVpsPalette.Error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (summary == null) {
            item {
                if (state.summaryLoading) SummaryLoading() else {
                    if (state.summaryError == null) {
                        Text(stringResource(Res.string.shiftvps_account_unavailable), style = MaterialTheme.typography.bodyLarge,
                            color = ShiftVpsPalette.Muted)
                    }
                    TextButton(onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.shiftvps_retry))
                    }
                }
            }
        } else if (invitations) {
            item { InvitationSummary(summary, onOpenReferral) }
        } else {
            item { AccountSummary(summary) }
        }
        if (!invitations) {
            item {
                if (summary?.account?.isFree != true) {
                    BrandButton(stringResource(Res.string.shiftvps_account_renew), onOpenPayment)
                }
                TextButton(onOpenAccount, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) {
                    Text(stringResource(Res.string.shiftvps_account_browser), style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                TextButton(onLogout, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.shiftvps_logout), color = ShiftVpsPalette.Muted)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AccountSummary(summary: ShiftVpsAccountSummary) {
    val account = summary.account
    Surface(color = ShiftVpsPalette.Card, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(stringResource(if (account.accessActive) Res.string.shiftvps_account_active else Res.string.shiftvps_account_inactive),
                style = MaterialTheme.typography.headlineSmall,
                color = if (account.accessActive) ShiftVpsPalette.Online else ShiftVpsPalette.Text)
            if (account.isFree) {
                Text(stringResource(Res.string.shiftvps_account_free), style = MaterialTheme.typography.bodyMedium,
                    color = ShiftVpsPalette.Muted, modifier = Modifier.padding(top = 5.dp))
            }
            Spacer(Modifier.height(20.dp))
            if (!account.isFree) {
                AccountDetail(stringResource(Res.string.shiftvps_account_until),
                    account.subscriptionExpiresAt?.let(::formatSubscriptionDate) ?: "—")
            }
            if (account.phone.isNotBlank()) {
                AccountDetail(stringResource(Res.string.shiftvps_account_phone), account.phone)
            }
            AccountDetail(stringResource(Res.string.shiftvps_account_devices),
                stringResource(Res.string.shiftvps_account_device_count, account.devicesUsed, account.deviceLimit))
            AccountDetail(stringResource(Res.string.shiftvps_account_this_device), summary.device.name)
        }
    }
}

@Composable
private fun AccountDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ShiftVpsPalette.Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.15f))
    }
}

@Composable
private fun InvitationSummary(summary: ShiftVpsAccountSummary, onOpenReferral: () -> Unit) {
    val referral = summary.referral
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(referral.invitationUrl) { mutableStateOf(false) }
    var copyFailed by remember(referral.invitationUrl) { mutableStateOf(false) }
    Column {
        Text(stringResource(Res.string.shiftvps_invite_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.shiftvps_invite_description), style = MaterialTheme.typography.bodyLarge,
            color = ShiftVpsPalette.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        Surface(color = ShiftVpsPalette.Card, shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InvitationCount(stringResource(Res.string.shiftvps_invite_invited), referral.invitedCount.toString(), Modifier.weight(1f))
                    InvitationCount(stringResource(Res.string.shiftvps_invite_paid), referral.paidCount.toString(), Modifier.weight(1f))
                    InvitationCount(stringResource(Res.string.shiftvps_invite_rewarded),
                        stringResource(Res.string.shiftvps_invite_days, referral.rewardedDays), Modifier.weight(1f))
                }
                HorizontalDivider(Modifier.padding(vertical = 20.dp), color = ShiftVpsPalette.Border)
                Text(referral.invitationUrl.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium,
                    color = ShiftVpsPalette.Text, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(20.dp))
        BrandButton(stringResource(if (copied) Res.string.shiftvps_invite_copied else Res.string.shiftvps_invite_copy), onClick = {
            scope.launch {
                try {
                    clipboard.setPlainText(referral.invitationUrl)
                    copied = true
                    copyFailed = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    copyFailed = true
                }
            }
        })
        if (copyFailed) {
            Text(stringResource(Res.string.shiftvps_invite_copy_failed), style = MaterialTheme.typography.bodySmall,
                color = ShiftVpsPalette.Error, modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        TextButton(onOpenReferral, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) {
            Text(stringResource(Res.string.shiftvps_invite_more), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun InvitationCount(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted)
        Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun SummaryLoading() {
    Surface(color = ShiftVpsPalette.Card, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(Res.string.shiftvps_account_loading), style = MaterialTheme.typography.bodyMedium,
                color = ShiftVpsPalette.Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            repeat(3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Spacer(Modifier.width(80.dp).height(14.dp).background(ShiftVpsPalette.CardRaised, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(112.dp).height(14.dp).background(ShiftVpsPalette.CardRaised, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

private fun formatSubscriptionDate(value: String): String = runCatching {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(OffsetDateTime.parse(value).toInstant()))
}.getOrDefault(value.substringBefore('T'))
