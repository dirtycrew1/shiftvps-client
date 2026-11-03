package fr.husi.ui.shiftvps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.husi.compose.BackHandler
import fr.husi.resources.*
import fr.husi.shiftvps.*
import org.jetbrains.compose.resources.stringResource

internal enum class ShiftVpsAccountSection { Overview, Subscription, Devices, Payments, Telegram, Security, Support, Legacy }

@Composable
internal fun AccountDestination(title: String, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = ShiftVpsPalette.Muted, modifier = Modifier.padding(top = 3.dp))
        }
        Canvas(Modifier.size(16.dp)) {
            drawLine(ShiftVpsPalette.Muted, Offset(size.width * 0.35f, size.height * 0.2f),
                Offset(size.width * 0.65f, size.height * 0.5f), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(ShiftVpsPalette.Muted, Offset(size.width * 0.65f, size.height * 0.5f),
                Offset(size.width * 0.35f, size.height * 0.8f), 1.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun AccountMenu(state: ShiftVpsUiState, onSection: (ShiftVpsAccountSection) -> Unit) {
    AccountPanel {
        AccountDestination(stringResource(Res.string.shiftvps_account_devices), (state.accountDetails?.account ?: state.summary?.account)?.let {
            stringResource(Res.string.shiftvps_account_device_count, it.devicesUsed, it.deviceLimit)
        }) { onSection(ShiftVpsAccountSection.Devices) }
        MenuDivider()
        AccountDestination(stringResource(Res.string.shiftvps_account_history)) { onSection(ShiftVpsAccountSection.Payments) }
        MenuDivider()
        AccountDestination(stringResource(Res.string.shiftvps_account_telegram)) { onSection(ShiftVpsAccountSection.Telegram) }
        MenuDivider()
        AccountDestination(stringResource(Res.string.shiftvps_account_security)) { onSection(ShiftVpsAccountSection.Security) }
        MenuDivider()
        AccountDestination(stringResource(Res.string.shiftvps_account_support)) { onSection(ShiftVpsAccountSection.Support) }
    }
}

@Composable
internal fun ShiftVpsAccountDetailsContent(state: ShiftVpsUiState, section: ShiftVpsAccountSection,
    onBack: () -> Unit, actions: ShiftVpsAccountActions?, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    LazyColumn(modifier.fillMaxWidth().onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(); true } else false
    }, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.shiftvps_account_back))
                }
                Spacer(Modifier.weight(1f))
                TextButton({ actions?.refreshAccountDetails() }, enabled = !state.accountDetailsLoading,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.shiftvps_refresh)) }
            }
            Text(stringResource(when (section) {
                ShiftVpsAccountSection.Subscription -> Res.string.shiftvps_account_subscription
                ShiftVpsAccountSection.Devices -> Res.string.shiftvps_account_devices
                ShiftVpsAccountSection.Payments -> Res.string.shiftvps_account_history
                ShiftVpsAccountSection.Telegram -> Res.string.shiftvps_account_telegram
                ShiftVpsAccountSection.Security -> Res.string.shiftvps_account_security
                ShiftVpsAccountSection.Support -> Res.string.shiftvps_account_support
                ShiftVpsAccountSection.Legacy -> Res.string.shiftvps_account_legacy
                else -> Res.string.shiftvps_tab_account
            }), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            if (state.accountDetailsLoading || state.accountAction.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp),
                color = ShiftVpsPalette.Accent, trackColor = ShiftVpsPalette.Border)
        }
        state.accountDetailsError?.let { error -> item { AccountNotice(error, error = true) } }
        if (state.accountAction.phase == ShiftVpsAccountActionPhase.Error) {
            item {
                AccountNotice(stringResource(when (state.accountAction.errorCode) {
                    "current_password_invalid" -> Res.string.shiftvps_account_password_invalid
                    "device_limit" -> Res.string.shiftvps_account_device_limit
                    else -> Res.string.shiftvps_account_action_failed
                }), error = true)
            }
        }
        if (state.accountAction.phase == ShiftVpsAccountActionPhase.Uncertain) {
            item {
                Column {
                    AccountNotice(stringResource(Res.string.shiftvps_account_operation_unknown))
                    TextButton({
                        val requestId = state.accountAction.requestId
                        if (requestId != null) actions?.reconcileAccountOperation(requestId) else actions?.refreshAccountDetails()
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (state.accountAction.requestId != null) Res.string.shiftvps_account_operation_check else Res.string.shiftvps_refresh))
                    }
                }
            }
        }
        if (state.accountDetails == null) {
            if (!state.accountDetailsLoading) item {
                Text(stringResource(Res.string.shiftvps_account_unavailable), color = ShiftVpsPalette.Muted)
                TextButton({ actions?.refreshAccountDetails() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.shiftvps_retry))
                }
            }
        } else {
            item {
                val details = state.accountDetails
                when (section) {
                    ShiftVpsAccountSection.Subscription -> SubscriptionContent(details, state.accountAction, actions)
                    ShiftVpsAccountSection.Devices -> DevicesContent(details, state.accountAction, actions)
                    ShiftVpsAccountSection.Payments -> PaymentsContent(details)
                    ShiftVpsAccountSection.Telegram -> TelegramContent(details, state.accountAction.busy,
                        state.accountAction.phase != ShiftVpsAccountActionPhase.Uncertain, actions)
                    ShiftVpsAccountSection.Security -> if (details.capabilities.changePassword) {
                        PasswordContent(state.accountAction.phase != ShiftVpsAccountActionPhase.Uncertain, state.accountAction.busy, actions)
                    } else TelegramSignInContent(details, actions)
                    ShiftVpsAccountSection.Support -> SupportContent(details, actions)
                    ShiftVpsAccountSection.Legacy -> LegacyContent(details, state.accountAction, actions)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SubscriptionContent(details: ShiftVpsFullAccount, action: ShiftVpsAccountActionState, actions: ShiftVpsAccountActions?) {
    var planCode by remember(details.plans) { mutableStateOf(details.plans.firstOrNull()?.code) }
    var method by remember(details.billing.paymentMethods) { mutableStateOf(details.billing.paymentMethods.firstOrNull()) }
    val pending = details.pendingOperations.filter { it.kind == "checkout" && (it.state in listOf("pending", "unknown") ||
        (it.state == "complete" && it.checkoutStatus !in listOf("paid", "canceled", "refunded", "chargeback"))) }
    val checkoutCreated = action.kind == ShiftVpsAccountActionKind.Checkout && action.phase == ShiftVpsAccountActionPhase.Success
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (details.account.isFree) {
            Text(stringResource(Res.string.shiftvps_account_free), style = MaterialTheme.typography.titleLarge)
        } else if (pending.isNotEmpty()) {
            pending.forEach { OperationNotice(it, action.busy, actions) }
        } else if (checkoutCreated) {
            AccountNotice(stringResource(Res.string.shiftvps_account_checkout_refresh))
            TextButton({
                val requestId = action.requestId
                if (requestId != null) actions?.reconcileAccountOperation(requestId) else actions?.refreshAccountDetails()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (action.requestId != null) Res.string.shiftvps_account_checkout_continue else Res.string.shiftvps_refresh))
            }
        } else {
            if (details.billing.onlineAvailable && details.plans.isNotEmpty()) {
                AccountPanel {
                    Column(Modifier.selectableGroup()) {
                        details.plans.forEachIndexed { index, plan ->
                            if (index > 0) MenuDivider()
                            Row(Modifier.fillMaxWidth().selectable(plan.code == planCode, enabled = !action.busy,
                                role = Role.RadioButton, onClick = { planCode = plan.code }).padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioButton(plan.code == planCode, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(plan.title, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(Res.string.shiftvps_account_days, plan.days),
                                        color = ShiftVpsPalette.Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(stringResource(Res.string.shiftvps_account_price, plan.priceRub), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Text(stringResource(Res.string.shiftvps_account_payment_method), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    details.billing.paymentMethods.forEach { option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(option == method, enabled = !action.busy,
                            role = Role.RadioButton, onClick = { method = option }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(option == method, onClick = null, modifier = Modifier.padding(end = 12.dp))
                            Text(stringResource(if (option == "sbp") Res.string.shiftvps_account_sbp else Res.string.shiftvps_account_card))
                        }
                    }
                }
                BrandButton(stringResource(Res.string.shiftvps_account_pay), {
                    val selectedPlan = planCode
                    val selectedMethod = method
                    if (selectedPlan != null && selectedMethod != null) actions?.createCheckout(selectedPlan, selectedMethod)
                }, enabled = details.capabilities.checkout && !action.busy && pending.isEmpty() &&
                    action.phase != ShiftVpsAccountActionPhase.Uncertain && !checkoutCreated && planCode != null && method != null,
                    busy = action.busy && action.kind == ShiftVpsAccountActionKind.Checkout)
            } else Text(stringResource(Res.string.shiftvps_account_online_unavailable), color = ShiftVpsPalette.Muted)
            TextButton({ actions?.openAccountLink(ShiftVpsAccountLink.ManualPayment) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.shiftvps_account_manual_payment))
            }
        }
    }
}

@Composable
private fun DevicesContent(details: ShiftVpsFullAccount, action: ShiftVpsAccountActionState, actions: ShiftVpsAccountActions?) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val busy = action.busy
    LaunchedEffect(action.phase, action.kind) {
        if (action.phase in listOf(ShiftVpsAccountActionPhase.Success, ShiftVpsAccountActionPhase.Uncertain) && action.kind in listOf(
                ShiftVpsAccountActionKind.AddDevice, ShiftVpsAccountActionKind.RenameDevice, ShiftVpsAccountActionKind.DeleteDevice)) {
            adding = false; editing = null; deleting = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val deletedDevice = details.devices.firstOrNull { it.id == deleting }
        val editedDevice = details.devices.firstOrNull { it.id == editing }
        when {
            deletedDevice != null -> ConfirmationContent(stringResource(Res.string.shiftvps_account_delete_device), deletedDevice.name,
                stringResource(if (deletedDevice.isCurrent) Res.string.shiftvps_account_delete_current_hint else Res.string.shiftvps_account_delete_device_hint),
                stringResource(Res.string.shiftvps_account_delete), busy, destructive = true,
                onConfirm = { actions?.deleteDevice(deletedDevice.id) }, onCancel = { deleting = null })
            adding || editedDevice != null -> DeviceNameForm(editedDevice?.name.orEmpty(), busy,
                onSave = { name -> if (editedDevice == null) actions?.addDevice(name) else actions?.renameDevice(editedDevice.id, name) },
                onCancel = { adding = false; editing = null })
            else -> {
                Text(stringResource(Res.string.shiftvps_account_device_count, details.account.devicesUsed, details.account.deviceLimit),
                    color = ShiftVpsPalette.Muted)
                if (details.devices.isEmpty()) Text(stringResource(Res.string.shiftvps_account_no_devices))
                details.devices.forEach { device ->
                    AccountPanel {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            if (device.isCurrent || device.provisioning) Text(stringResource(if (device.provisioning)
                                Res.string.shiftvps_account_provisioning else Res.string.shiftvps_account_this_device_short),
                                style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted, modifier = Modifier.padding(top = 5.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton({ actions?.dismissAccountAction(); editing = device.id }, enabled = !busy && !device.provisioning && action.phase != ShiftVpsAccountActionPhase.Uncertain,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.shiftvps_account_rename)) }
                                TextButton({ actions?.dismissAccountAction(); deleting = device.id }, enabled = !busy && !device.provisioning && action.phase != ShiftVpsAccountActionPhase.Uncertain,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.shiftvps_account_delete), color = ShiftVpsPalette.Error) }
                            }
                        }
                    }
                }
                BrandButton(stringResource(Res.string.shiftvps_account_add_device), { actions?.dismissAccountAction(); adding = true },
                    enabled = details.capabilities.addDevice && !busy && action.phase != ShiftVpsAccountActionPhase.Uncertain)
                details.pendingOperations.filter { it.kind == "device_add" && it.state in listOf("pending", "unknown") }
                    .forEach { OperationNotice(it, busy, actions) }
            }
        }
    }
}

@Composable
private fun DeviceNameForm(initialName: String, busy: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (initialName.isBlank()) Res.string.shiftvps_account_add_device else Res.string.shiftvps_account_rename),
            style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { if (it.length <= 50) name = it }, enabled = !busy, singleLine = true,
            label = { Text(stringResource(Res.string.shiftvps_account_device_name)) }, modifier = Modifier.fillMaxWidth())
        BrandButton(stringResource(Res.string.shiftvps_account_save), { onSave(name.trim()) }, enabled = !busy && name.trim().isNotEmpty(), busy = busy)
        TextButton(onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(Res.string.shiftvps_cancel)) }
    }
}

@Composable
private fun PaymentsContent(details: ShiftVpsFullAccount) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (details.payments.isEmpty()) Text(stringResource(Res.string.shiftvps_account_no_payments), color = ShiftVpsPalette.Muted)
        details.payments.forEach { payment ->
            AccountPanel {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(payment.planTitle, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.shiftvps_account_amount, payment.amountRub), style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(payment.paidAt?.let(::formatSubscriptionDate), stringResource(Res.string.shiftvps_account_payment_paid)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted)
                }
            }
        }
    }
}

@Composable
private fun TelegramContent(details: ShiftVpsFullAccount, busy: Boolean, writeAllowed: Boolean, actions: ShiftVpsAccountActions?) {
    var confirming by remember { mutableStateOf(false) }
    val telegram = details.telegram
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (telegram.linked && !details.capabilities.unlinkTelegram) TelegramSignInContent(details, actions)
        else if (confirming) ConfirmationContent(stringResource(Res.string.shiftvps_account_telegram_unlink_title),
            telegram.username?.let { "@${it.removePrefix("@")}" }.orEmpty(), stringResource(Res.string.shiftvps_account_telegram_unlink_hint),
            stringResource(Res.string.shiftvps_account_telegram_unlink), busy, destructive = true,
            onConfirm = { actions?.unlinkTelegram(); confirming = false }, onCancel = { confirming = false })
        else {
            Text(stringResource(if (telegram.linked) Res.string.shiftvps_account_telegram_linked else Res.string.shiftvps_account_telegram_unlinked),
                style = MaterialTheme.typography.titleLarge)
            if (!telegram.username.isNullOrBlank()) Text("@${telegram.username.removePrefix("@")}", color = ShiftVpsPalette.Muted)
            if (telegram.linked) {
                BrandButton(stringResource(Res.string.shiftvps_account_telegram_unlink), { confirming = true },
                    enabled = !busy && writeAllowed && details.capabilities.unlinkTelegram, secondary = true)
            } else {
                if (telegram.trialAvailable) Text(stringResource(Res.string.shiftvps_account_trial_available, telegram.trialDays),
                    style = MaterialTheme.typography.bodyMedium)
                BrandButton(stringResource(Res.string.shiftvps_account_telegram_link), { actions?.openAccountLink(ShiftVpsAccountLink.TelegramLink) }, enabled = !busy)
                Text(stringResource(Res.string.shiftvps_account_telegram_browser), style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted)
            }
        }
    }
}

@Composable
private fun TelegramSignInContent(details: ShiftVpsFullAccount, actions: ShiftVpsAccountActions?) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(Res.string.shiftvps_account_telegram_sign_in), style = MaterialTheme.typography.titleLarge)
        details.telegram.username?.takeIf { it.isNotBlank() }?.let {
            Text("@${it.removePrefix("@")}", color = ShiftVpsPalette.Muted)
        }
        Text(stringResource(Res.string.shiftvps_account_telegram_access_help), style = MaterialTheme.typography.bodyMedium,
            color = ShiftVpsPalette.Muted)
        BrandButton(stringResource(Res.string.shiftvps_account_support), {
            actions?.openAccountLink(if (details.support.telegramUrl.isNullOrBlank()) ShiftVpsAccountLink.SupportEmail else ShiftVpsAccountLink.SupportTelegram)
        })
    }
}

@Composable
private fun PasswordContent(allowed: Boolean, busy: Boolean, actions: ShiftVpsAccountActions?) {
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (confirming) ConfirmationContent(stringResource(Res.string.shiftvps_account_password_title), "",
            stringResource(Res.string.shiftvps_account_password_hint), stringResource(Res.string.shiftvps_account_password_change), busy,
            onConfirm = {
                actions?.changePassword(current, password, confirmation)
                current = ""; password = ""; confirmation = ""; confirming = false
            }, onCancel = { confirming = false })
        else {
            PasswordField(current, { current = it }, stringResource(Res.string.shiftvps_account_password_current), !busy)
            PasswordField(password, { password = it }, stringResource(Res.string.shiftvps_account_password_new), !busy)
            Text(stringResource(Res.string.shiftvps_account_password_rule), style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted)
            PasswordField(confirmation, { confirmation = it }, stringResource(Res.string.shiftvps_account_password_confirm), !busy)
            if (confirmation.isNotEmpty() && password != confirmation) AccountNotice(stringResource(Res.string.shiftvps_account_password_mismatch), error = true)
            BrandButton(stringResource(Res.string.shiftvps_account_password_change), { confirming = true },
                enabled = allowed && !busy && current.isNotEmpty() && password.length >= 12 && password == confirmation, busy = busy)
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, { if (it.length <= 256) onChange(it) }, label = { Text(label) }, enabled = enabled,
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SupportContent(details: ShiftVpsFullAccount, actions: ShiftVpsAccountActions?) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AccountPanel {
            if (!details.support.telegramUrl.isNullOrBlank()) {
                AccountDestination(stringResource(Res.string.shiftvps_account_telegram)) { actions?.openAccountLink(ShiftVpsAccountLink.SupportTelegram) }
                MenuDivider()
            }
            AccountDestination(stringResource(Res.string.shiftvps_account_email), details.support.email) { actions?.openAccountLink(ShiftVpsAccountLink.SupportEmail) }
            MenuDivider()
            AccountDestination(stringResource(Res.string.shiftvps_account_privacy)) { actions?.openAccountLink(ShiftVpsAccountLink.Privacy) }
            MenuDivider()
            AccountDestination(stringResource(Res.string.shiftvps_account_terms)) { actions?.openAccountLink(ShiftVpsAccountLink.Terms) }
        }
        Text(stringResource(Res.string.shiftvps_account_downloads), style = MaterialTheme.typography.titleMedium)
        AccountPanel {
            AccountDestination("Windows") { actions?.openAccountLink(ShiftVpsAccountLink.Windows) }
            MenuDivider()
            AccountDestination("Android") { actions?.openAccountLink(ShiftVpsAccountLink.Android) }
            MenuDivider()
            AccountDestination("macOS · Apple Silicon") { actions?.openAccountLink(ShiftVpsAccountLink.MacArm64) }
            MenuDivider()
            AccountDestination("macOS · Intel") { actions?.openAccountLink(ShiftVpsAccountLink.MacAmd64) }
        }
    }
}

@Composable
private fun LegacyContent(details: ShiftVpsFullAccount, action: ShiftVpsAccountActionState, actions: ShiftVpsAccountActions?) {
    val legacy = details.legacyReferral
    var network by remember { mutableStateOf("TRC20") }
    var wallet by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("${legacy.balanceUsdt} USDT", style = MaterialTheme.typography.headlineMedium)
        Text("${stringResource(Res.string.shiftvps_account_legacy_minimum)}: ${legacy.payoutMinUsdt} USDT",
            style = MaterialTheme.typography.bodyMedium, color = ShiftVpsPalette.Muted)
        if (legacy.hasPendingPayout) Text(stringResource(Res.string.shiftvps_account_legacy_pending), color = ShiftVpsPalette.Muted)
        if (legacy.canRequestPayout && !legacy.hasPendingPayout) {
            if (confirming) ConfirmationContent(stringResource(Res.string.shiftvps_account_legacy_confirm),
                "${legacy.balanceUsdt} USDT · $network\n$wallet", stringResource(Res.string.shiftvps_account_legacy_hint),
                stringResource(Res.string.shiftvps_account_legacy_submit), action.busy,
                onConfirm = { actions?.requestLegacyPayout(network, wallet.trim()); confirming = false }, onCancel = { confirming = false })
            else {
                Text(stringResource(Res.string.shiftvps_account_legacy_network), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.selectableGroup()) {
                    listOf("TRC20", "ERC20").forEach { option ->
                        Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(network == option, enabled = !action.busy,
                            role = Role.RadioButton, onClick = { network = option }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(network == option, onClick = null, modifier = Modifier.padding(end = 8.dp)); Text(option)
                        }
                    }
                }
                OutlinedTextField(wallet, { if (it.length <= 80) wallet = it }, enabled = !action.busy, singleLine = true,
                    label = { Text(stringResource(Res.string.shiftvps_account_legacy_wallet)) }, modifier = Modifier.fillMaxWidth())
                BrandButton(stringResource(Res.string.shiftvps_account_legacy_request), { confirming = true },
                    enabled = !action.busy && wallet.trim().matches(Regex("[A-Za-z0-9]{20,80}")) && action.phase != ShiftVpsAccountActionPhase.Uncertain,
                    busy = action.busy && action.kind == ShiftVpsAccountActionKind.LegacyPayout)
            }
        }
        details.pendingOperations.filter { it.kind == "payout" && it.state in listOf("pending", "unknown") }
            .forEach { OperationNotice(it, action.busy, actions) }
        legacy.payouts.forEach { payout ->
            AccountPanel {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${payout.amountUsdt} USDT", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when (payout.status) {
                        "paid" -> Res.string.shiftvps_account_legacy_paid
                        "rejected" -> Res.string.shiftvps_account_legacy_rejected
                        else -> Res.string.shiftvps_account_legacy_pending
                    }), color = ShiftVpsPalette.Muted)
                    Text(formatSubscriptionDate(payout.createdAt), style = MaterialTheme.typography.bodySmall, color = ShiftVpsPalette.Muted)
                    if (!payout.note.isNullOrBlank()) Text(payout.note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton({ actions?.openAccountLink(ShiftVpsAccountLink.LegacySettlement) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.shiftvps_account_legacy_site))
        }
    }
}

@Composable
private fun OperationNotice(operation: ShiftVpsOperation, busy: Boolean, actions: ShiftVpsAccountActions?) {
    val ready = operation.kind == "checkout" && operation.state == "complete" && operation.checkoutStatus == "created"
    AccountPanel {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            AccountNotice(stringResource(when {
                ready -> Res.string.shiftvps_account_checkout_ready
                operation.state == "unknown" || operation.checkoutStatus == "unknown" ||
                    (operation.kind == "checkout" && operation.state == "complete" && operation.checkoutStatus == null) -> Res.string.shiftvps_account_operation_unknown
                else -> Res.string.shiftvps_account_operation_pending
            }))
            TextButton({ actions?.reconcileAccountOperation(operation.requestId) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) {
                Text(stringResource(if (ready) Res.string.shiftvps_account_checkout_continue else Res.string.shiftvps_account_operation_check))
            }
        }
    }
}

@Composable
private fun ConfirmationContent(title: String, detail: String, message: String, action: String, busy: Boolean,
    destructive: Boolean = false, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = ShiftVpsPalette.Muted)
        if (destructive) Button(onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        } else BrandButton(action, onConfirm, enabled = !busy, busy = busy)
        TextButton(onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.shiftvps_cancel))
        }
    }
}

@Composable
private fun AccountNotice(message: String, error: Boolean = false) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = if (error) ShiftVpsPalette.Error else ShiftVpsPalette.Muted,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun AccountPanel(content: @Composable () -> Unit) {
    Surface(color = ShiftVpsPalette.Card, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, ShiftVpsPalette.Border)) {
        Column { content() }
    }
}

@Composable
private fun MenuDivider() { HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = ShiftVpsPalette.Border) }
