package fr.husi.ui.shiftvps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import fr.husi.bg.ServiceState
import fr.husi.resources.*
import fr.husi.shiftvps.*
import fr.husi.compose.getPlainText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.Image
import java.io.File
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raster-only UI fixtures: no account controller, core process or network is constructed. */
@OptIn(ExperimentalComposeUiApi::class)
class ShiftVpsScreenSnapshotTest {
    private var frameTimeNanos = 0L
    @Test
    fun `login remains a single browser sign in action`() = runBlocking {
        val signIn = getString(Res.string.shiftvps_sign_in)
        withContext(Dispatchers.Swing) {
            authSizes.forEach { (device, width, height) ->
                snapshot("login-$device", width, height, ShiftVpsUiState()) { scene ->
                    assertTrue(texts(scene).contains(signIn))
                    assertEquals(2, buttons(scene).count())
                }
            }
        }
    }

    @Test
    fun `browser confirmation keeps the comparison code and one cancel action`() = runBlocking {
        val title = getString(Res.string.shiftvps_sign_in_waiting)
        val comparison = getString(Res.string.shiftvps_sign_in_code)
        val cancel = getString(Res.string.shiftvps_cancel)
        withContext(Dispatchers.Swing) {
            authSizes.forEach { (device, width, height) ->
                snapshot("login-pending-$device", width, height, ShiftVpsUiState(authCode = "ABCD-EFGH")) { scene ->
                    assertTrue(texts(scene).containsAll(listOf(title, comparison, "ABCD-EFGH", cancel)))
                    assertEquals(1, buttons(scene).count())
                }
            }
        }
    }

    @Test
    fun `browser failure replaces stale confirmation with one retry action`() = runBlocking {
        val title = getString(Res.string.shiftvps_sign_in_failed)
        val message = getString(Res.string.shiftvps_browser_error)
        val retry = getString(Res.string.shiftvps_retry)
        withContext(Dispatchers.Swing) {
            authSizes.forEach { (device, width, height) ->
                snapshot("login-error-$device", width, height, ShiftVpsUiState(authCode = "ABCD-EFGH"),
                    browserError = true) { scene ->
                    assertTrue(texts(scene).containsAll(listOf(title, message, retry)))
                    assertFalse(texts(scene).contains("ABCD-EFGH"))
                    assertEquals(2, buttons(scene).count())
                }
            }
        }
    }

    private val authSizes = listOf(Triple("phone-narrow", 320, 700), Triple("desktop", 460, 760))

    @Test
    fun `server list keeps distinct logical server choices`() = runBlocking {
        val connect = getString(Res.string.shiftvps_connect)
        withContext(Dispatchers.Swing) {
            snapshot("servers-phone", 390, 844, fixture()) { scene ->
                assertTrue(texts(scene).contains(connect))
                assertTrue(texts(scene).containsAll(listOf("Швеция", "Литва", "Израиль")))
                assertEquals(3, radioRows(scene).count())
            }
            snapshot("servers-desktop", 460, 760, fixture()) { scene ->
                assertTrue(texts(scene).contains(connect))
                assertEquals(3, radioRows(scene).count())
            }
        }
    }

    @Test
    fun `connected state locks server changes and exposes disconnect`() = runBlocking {
        val disconnect = getString(Res.string.shiftvps_disconnect)
        withContext(Dispatchers.Swing) {
            snapshot("connected-phone", 390, 844, fixture().copy(serviceState = ServiceState.Connected)) { scene ->
                assertTrue(texts(scene).contains(disconnect))
                val rows = radioRows(scene).toList()
                assertEquals(3, rows.size)
                assertTrue(rows.all { SemanticsProperties.Disabled in it.config })
            }
        }
    }

    @Test
    fun `hover feedback covers the whole disconnect button`() = runBlocking {
        val disconnect = getString(Res.string.shiftvps_disconnect)
        withContext(Dispatchers.Swing) {
            snapshot("connected-interaction-desktop", 460, 760, fixture().copy(serviceState = ServiceState.Connected)) { scene ->
                val label = nodes(scene).first {
                    SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { text -> text.text == disconnect }
                }
                val buttonNode = buttons(scene).first { it.boundsInRoot.contains(label.boundsInRoot.center) }
                val button = buttonNode.boundsInRoot
                scene.sendPointerEvent(PointerEventType.Move, position = label.boundsInRoot.center, type = PointerType.Mouse)
                repeat(6) { renderFrame(scene).close(); delay(30) }
                saveSnapshot(scene, "connected-hover-desktop")
                if (SemanticsActions.RequestFocus in buttonNode.config) {
                    buttonNode.config[SemanticsActions.RequestFocus].action?.invoke()
                    repeat(6) { renderFrame(scene).close(); delay(30) }
                    saveSnapshot(scene, "connected-focus-desktop")
                }
                scene.sendPointerEvent(PointerEventType.Press, position = label.boundsInRoot.center,
                    type = PointerType.Mouse, button = PointerButton.Primary)
                repeat(6) { renderFrame(scene).close(); delay(30) }
                saveSnapshot(scene, "connected-pressed-desktop")
                renderFrame(scene).use { image ->
                    val pixels = ImageIO.read(ByteArrayInputStream(checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes))
                    val y = button.top.toInt() + 18
                    assertEquals(pixels.getRGB(button.left.toInt() + 8, y), pixels.getRGB(button.left.toInt() + 30, y),
                        "Hover feedback must not become a separate rectangle inside the button padding")
                }
            }
        }
    }

    @Test
    fun `available update shows install without adding settings navigation`() = runBlocking {
        val title = getString(Res.string.shiftvps_update_available)
        val action = getString(Res.string.shiftvps_update_action)
        withContext(Dispatchers.Swing) {
            snapshot("update-phone", 390, 844, fixture(),
                updateState = ShiftVpsUpdateState(ShiftVpsUpdatePhase.Available, availableVersion = "1.0.1")) { scene ->
                assertTrue(texts(scene).containsAll(listOf(title, action)))
            }
        }
    }

    @Test
    fun `brand fonts resolve to Plex for body rows and buttons`() = runBlocking {
        withContext(Dispatchers.Swing) {
            val families = mutableMapOf<String, String>()
            val scene = ImageComposeScene(480, 160, Density(1f), coroutineContext = Dispatchers.Swing) {
                ShiftVpsTheme {
                    val resolver = LocalFontFamilyResolver.current
                    Column {
                        listOf("body" to MaterialTheme.typography.bodyLarge, "row" to MaterialTheme.typography.titleMedium).forEach { (name, style) ->
                            val loaded = resolver.resolve(style.fontFamily, checkNotNull(style.fontWeight)).value
                            families[name] = resolvedFamilyName(loaded)
                        }
                        Button(onClick = {}) {
                            val style = MaterialTheme.typography.labelLarge
                            val loaded = resolver.resolve(style.fontFamily, checkNotNull(style.fontWeight)).value
                            families["button"] = resolvedFamilyName(loaded)
                            Text("Подключиться", style = style)
                        }
                    }
                }
            }
            try {
                repeat(5) { renderFrame(scene).close(); delay(20) }
                assertEquals(setOf("body", "row", "button"), families.keys)
                assertTrue(families.values.all { it.startsWith("IBM Plex Sans") }, "Resolved native fonts: $families")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `country flags render as native vectors with a neutral unknown fallback`() = runBlocking {
        withContext(Dispatchers.Swing) {
            val codes = listOf("NL", "LT", "LV", "IL", "SE", "FI", "DE", "EE", "KZ", "GE", "")
            val scene = ImageComposeScene(460, 460, Density(1f), coroutineContext = Dispatchers.Swing) {
                ShiftVpsTheme {
                    Column(Modifier.background(ShiftVpsPalette.Background)) {
                        PreviewCaption()
                        codes.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceAround) {
                                row.forEach { code ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ShiftVpsCountryFlag(code)
                                        Text(code.ifBlank { "Unknown" }, color = ShiftVpsPalette.Text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            try {
                repeat(5) { renderFrame(scene).close(); delay(20) }
                saveSnapshot(scene, "country-flags")
                assertTrue(texts(scene).containsAll(codes.filter(String::isNotBlank) + "Unknown"))
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `all application states have honest phone and desktop previews`() = runBlocking {
        val summary = summaryFixture()
        assertEquals(summary.referral.paidCount * summary.referral.bonusDays, summary.referral.rewardedDays)
        val full = fullAccountFixture()
        val base = fixture().copy(summary = summary, accountDetails = full)
        val unpaid = unpaidFixture()
        val telegramOnly = telegramOnlyFixture()
        val summaryFailure = "Не удалось загрузить данные аккаунта. Попробуйте ещё раз."
        val cases = listOf(
            GalleryCase("login", "Login", "start", ShiftVpsUiState()),
            GalleryCase("login-pending", "Login", "waiting", ShiftVpsUiState(authCode = "ABCD-EFGH")),
            GalleryCase("login-error", "Login", "error", ShiftVpsUiState(authCode = "ABCD-EFGH"), browserError = true),
            GalleryCase("connection-disconnected", "Connection", "disconnected", base),
            GalleryCase("connection-connecting", "Connection", "connecting", base.copy(serviceState = ServiceState.Connecting)),
            GalleryCase("connection-connected", "Connection", "connected", base.copy(serviceState = ServiceState.Connected)),
            GalleryCase("connection-disconnecting", "Connection", "disconnecting", base.copy(serviceState = ServiceState.Stopping)),
            GalleryCase("connection-empty", "Connection", "empty", base.copy(servers = emptyList(), selectedId = null)),
            GalleryCase("connection-loading", "Connection", "loading", base.copy(servers = emptyList(), selectedId = null, loading = true)),
            GalleryCase("connection-checking", "Connection", "checking", base.copy(probing = true)),
            GalleryCase("connection-error", "Connection", "error", base.copy(error = "Не удалось обновить серверы. Попробуйте ещё раз.")),
            GalleryCase("connection-inactive", "Connection", "inactive", base.copy(accessState = ShiftVpsAccessState.Inactive,
                summary = summary.copy(account = summary.account.copy(accessActive = false, subscriptionExpiresAt = null)),
                accountDetails = full.copy(account = full.account.copy(accessActive = false, subscriptionExpiresAt = null),
                    capabilities = full.capabilities.copy(addDevice = false)))),
            GalleryCase("connection-unpaid", "Connection", "unpaid", unpaid),
            GalleryCase("connection-access-unknown", "Connection", "unknown", base.copy(accessState = ShiftVpsAccessState.Unknown,
                summary = null, accountDetails = null, summaryError = summaryFailure)),
            GalleryCase("account-active", "Account", "active", base, ShiftVpsTab.Account),
            GalleryCase("account-unpaid", "Account", "unpaid", unpaid, ShiftVpsTab.Account),
            GalleryCase("account-expired", "Account", "expired", base.copy(accessState = ShiftVpsAccessState.Inactive, summary = summary.copy(account =
                summary.account.copy(accessActive = false, subscriptionExpiresAt = "2026-10-01T12:00:00+00:00")), accountDetails = full.copy(
                    account = full.account.copy(accessActive = false, subscriptionExpiresAt = "2026-10-01T12:00:00+00:00"),
                    capabilities = full.capabilities.copy(addDevice = false))), ShiftVpsTab.Account),
            GalleryCase("account-free", "Account", "free", base.copy(summary = summary.copy(account =
                summary.account.copy(isFree = true, subscriptionExpiresAt = null, deviceLimit = 5)), accountDetails = full.copy(
                    account = full.account.copy(isFree = true, subscriptionExpiresAt = null, deviceLimit = 5))), ShiftVpsTab.Account),
            GalleryCase("account-loading", "Account", "loading", base.copy(summary = null, summaryLoading = true,
                accountDetails = null, accountDetailsLoading = true), ShiftVpsTab.Account),
            GalleryCase("account-error", "Account", "error", base.copy(summary = null, summaryError = summaryFailure,
                accountDetails = null, accountDetailsError = summaryFailure), ShiftVpsTab.Account),
            GalleryCase("account-cached-error", "Account", "cached-error", base.copy(summaryError = summaryFailure), ShiftVpsTab.Account),
            GalleryCase("account-subscription", "Account", "subscription", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Subscription),
            GalleryCase("account-checkout-unknown", "Account", "checkout-unknown", base.copy(accountDetails = full.copy(pendingOperations = listOf(
                ShiftVpsOperation("preview-checkout-request", "checkout", "unknown")))), ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Subscription),
            GalleryCase("account-checkout-ready", "Account", "checkout-ready", base.copy(accountDetails = full.copy(pendingOperations = listOf(
                ShiftVpsOperation("preview-checkout-request", "checkout", "complete", checkoutUrl = "https://pay.rollypay.io/pay/preview-example",
                    checkoutStatus = "created")))), ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Subscription),
            GalleryCase("account-checkout-retained", "Account", "checkout-retained", base.copy(accountAction = ShiftVpsAccountActionState(
                kind = ShiftVpsAccountActionKind.Checkout, phase = ShiftVpsAccountActionPhase.Success, requestId = "preview-checkout-request")),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Subscription),
            GalleryCase("account-lifetime-billing", "Account", "lifetime-billing", base.copy(accountDetails = full.copy(
                account = full.account.copy(isFree = true, subscriptionExpiresAt = null, deviceLimit = 5),
                capabilities = full.capabilities.copy(checkout = false), pendingOperations = listOf(
                    ShiftVpsOperation("preview-checkout-request", "checkout", "complete", checkoutUrl = "https://pay.rollypay.io/pay/preview-example", checkoutStatus = "created")))),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Subscription),
            GalleryCase("account-payment-unavailable", "Account", "payment-unavailable", base.copy(accountDetails = full.copy(
                billing = full.billing.copy(onlineAvailable = false), capabilities = full.capabilities.copy(checkout = false))),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Subscription),
            GalleryCase("account-devices", "Account", "devices", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices),
            GalleryCase("account-device-add", "Account", "device-add", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices, interaction = "add"),
            GalleryCase("account-device-rename", "Account", "device-rename", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices, interaction = "rename"),
            GalleryCase("account-current-device-delete", "Account", "current-device-delete", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices, interaction = "delete-current"),
            GalleryCase("account-device-delete", "Account", "device-delete", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices, interaction = "delete-other"),
            GalleryCase("account-devices-limit", "Account", "devices-limit", base.copy(accountDetails = full.copy(
                account = full.account.copy(devicesUsed = 3), capabilities = full.capabilities.copy(addDevice = false))),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices),
            GalleryCase("account-device-pending", "Account", "device-pending", base.copy(accountDetails = full.copy(
                devices = full.devices + ShiftVpsDeviceDetails(-3, "Новый телефон", false, true, statusSource = "unknown"),
                account = full.account.copy(devicesUsed = 3), capabilities = full.capabilities.copy(addDevice = false),
                pendingOperations = listOf(ShiftVpsOperation("preview-device-request", "device_add", "pending")))),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices),
            GalleryCase("account-payments", "Account", "payments", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Payments),
            GalleryCase("account-payments-empty", "Account", "payments-empty", base.copy(accountDetails = full.copy(payments = emptyList())),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Payments),
            GalleryCase("account-telegram-linked", "Account", "telegram-linked", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Telegram),
            GalleryCase("account-telegram-unlinked", "Account", "telegram-unlinked", base.copy(accountDetails = full.copy(
                telegram = full.telegram.copy(linked = false, canUnlink = false, username = null, trialAvailable = true),
                capabilities = full.capabilities.copy(unlinkTelegram = false))), ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Telegram),
            GalleryCase("account-telegram-unlink", "Account", "telegram-unlink", base, ShiftVpsTab.Account,
                accountSection = ShiftVpsAccountSection.Telegram, interaction = "unlink"),
            GalleryCase("account-security", "Account", "security", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Security),
            GalleryCase("account-telegram-only", "Account", "telegram-only", telegramOnly, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Telegram),
            GalleryCase("account-security-telegram-only", "Account", "security-telegram-only", telegramOnly, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Security),
            GalleryCase("account-password-confirm", "Account", "password-confirm", base, ShiftVpsTab.Account,
                accountSection = ShiftVpsAccountSection.Security, interaction = "password-confirm"),
            GalleryCase("account-support", "Account", "support", base, ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Support),
            GalleryCase("account-details-loading", "Account", "details-loading", base.copy(accountDetails = null, accountDetailsLoading = true),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices),
            GalleryCase("account-details-error", "Account", "details-error", base.copy(accountDetails = null, accountDetailsError = summaryFailure),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices),
            GalleryCase("account-action-error", "Account", "action-error", base.copy(accountAction = ShiftVpsAccountActionState(
                ShiftVpsAccountActionKind.RenameDevice, ShiftVpsAccountActionPhase.Error, errorCode = "mutation_unavailable")),
                ShiftVpsTab.Account, accountSection = ShiftVpsAccountSection.Devices),
            GalleryCase("invitations-default", "Invitations", "default", base, ShiftVpsTab.Invitations),
            GalleryCase("invitations-zero", "Invitations", "zero", base.copy(summary = summary.copy(referral =
                summary.referral.copy(invitedCount = 0, paidCount = 0, rewardedDays = 0, rewardedMonths = 0))), ShiftVpsTab.Invitations),
            GalleryCase("invitations-loading", "Invitations", "loading", base.copy(summary = null, summaryLoading = true), ShiftVpsTab.Invitations),
            GalleryCase("invitations-error", "Invitations", "error", base.copy(summary = null, summaryError = summaryFailure), ShiftVpsTab.Invitations),
            GalleryCase("invitations-copied", "Invitations", "copied", base, ShiftVpsTab.Invitations, copyInvitation = true),
            GalleryCase("invitations-legacy", "Invitations", "legacy", base, ShiftVpsTab.Invitations, accountSection = ShiftVpsAccountSection.Legacy),
            GalleryCase("invitations-legacy-request", "Invitations", "legacy-request", base.copy(accountDetails = full.copy(
                legacyReferral = full.legacyReferral.copy(balanceUsdt = "75.50", canRequestPayout = true))),
                ShiftVpsTab.Invitations, accountSection = ShiftVpsAccountSection.Legacy),
            GalleryCase("invitations-legacy-confirm", "Invitations", "legacy-confirm", base.copy(accountDetails = full.copy(
                legacyReferral = full.legacyReferral.copy(balanceUsdt = "75.50", canRequestPayout = true))),
                ShiftVpsTab.Invitations, accountSection = ShiftVpsAccountSection.Legacy, interaction = "payout-confirm"),
            GalleryCase("update-available", "Updates", "available", base, update =
                ShiftVpsUpdateState(ShiftVpsUpdatePhase.Available, availableVersion = "0.1.7")),
            GalleryCase("update-downloading", "Updates", "downloading", base, update =
                ShiftVpsUpdateState(ShiftVpsUpdatePhase.Downloading, availableVersion = "0.1.7", progressPercent = 45)),
            GalleryCase("update-error", "Updates", "error", base, update =
                ShiftVpsUpdateState(ShiftVpsUpdatePhase.Failed, availableVersion = "0.1.7",
                    message = "Не удалось скачать обновление. Попробуйте ещё раз.")),
            GalleryCase("update-permission", "Updates", "permission", base, update =
                ShiftVpsUpdateState(ShiftVpsUpdatePhase.NeedsPermission, availableVersion = "0.1.7")),
            GalleryCase("update-disconnect", "Updates", "disconnect", base.copy(serviceState = ServiceState.Connected), update =
                ShiftVpsUpdateState(ShiftVpsUpdatePhase.NeedsDisconnect, availableVersion = "0.1.7")),
            GalleryCase("update-installing", "Updates", "installing", base, update =
                ShiftVpsUpdateState(ShiftVpsUpdatePhase.Installing, availableVersion = "0.1.7")),
        )
        val tabs = mapOf(ShiftVpsTab.Connection to getString(Res.string.shiftvps_tab_connection),
            ShiftVpsTab.Account to getString(Res.string.shiftvps_tab_account),
            ShiftVpsTab.Invitations to getString(Res.string.shiftvps_tab_invitations))
        val copy = getString(Res.string.shiftvps_invite_copy)
        val copied = getString(Res.string.shiftvps_invite_copied)
        val rows = mutableListOf<String>()
        withContext(Dispatchers.Swing) {
            for (case in cases) {
                for ((viewport, width, height) in listOf(Triple("phone", 390, 844), Triple("desktop", 460, 760))) {
                    val name = "${case.id}-$viewport"
                    val clipboard = MemoryClipboard()
                    val uiState = case.uiState.copy(summary = case.uiState.summary?.let { account ->
                        account.copy(device = account.device.copy(name = if (viewport == "phone") "Мой телефон" else "Мой компьютер"))
                    }, accountDetails = case.uiState.accountDetails?.let { account -> account.copy(devices = account.devices.map { device ->
                        if (device.isCurrent) device.copy(name = if (viewport == "phone") "Мой телефон" else "Мой компьютер") else device
                    }) })
                    snapshot(name, width, height, uiState, updateState = case.update,
                        browserError = case.browserError, clipboard = clipboard,
                        initialTab = if (case.accountSection == ShiftVpsAccountSection.Overview) ShiftVpsTab.Connection else case.tab,
                        initialAccountSection = case.accountSection) { scene ->
                        if (case.tab != ShiftVpsTab.Connection && case.accountSection == ShiftVpsAccountSection.Overview) {
                            clickText(scene, tabs.getValue(case.tab))
                            settle(scene)
                        }
                        galleryInteraction(scene, case.interaction)
                        if (case.copyInvitation) {
                            clickText(scene, copy)
                            settle(scene)
                            assertTrue(texts(scene).contains(copied))
                            assertEquals(summary.referral.invitationUrl, clipboard.getPlainText())
                        }
                        if (case.id == "account-free") {
                            assertFalse(texts(scene).contains(getString(Res.string.shiftvps_account_renew)))
                            assertTrue(texts(scene).contains(getString(Res.string.shiftvps_account_free)))
                        }
                        if (case.id == "account-payments") assertTrue(texts(scene).contains(getString(Res.string.shiftvps_account_amount, "150.50")))
                        if (case.id == "account-lifetime-billing") {
                            assertTrue(texts(scene).contains(getString(Res.string.shiftvps_account_free)))
                            assertFalse(texts(scene).contains(getString(Res.string.shiftvps_account_pay)))
                            assertFalse(texts(scene).contains(getString(Res.string.shiftvps_account_checkout_continue)))
                        }
                        assertTrue(nodes(scene).filter { SemanticsProperties.Text in it.config }
                            .all { it.boundsInRoot.left >= -0.5f && it.boundsInRoot.right <= width + 0.5f },
                            "Text must remain inside the $name viewport")
                    }
                    rows += """{"id":"$name","section":"${case.section}","state":"${case.status}","viewport":"$viewport","filename":"$name.png","width":$width,"height":$height,"locale":"ru"}"""
                }
            }
        }
        val sourceHashes = listOf("ShiftVpsContent.kt", "ShiftVpsAccountContent.kt", "ShiftVpsAccountDetailsContent.kt", "ShiftVpsTheme.kt", "ShiftVpsCountryFlag.kt", "ShiftVpsScreen.kt")
            .associateWith { name ->
                val bytes = File("src/commonMain/kotlin/fr/husi/ui/shiftvps").resolve(name).readBytes()
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
        val hashesJson = sourceHashes.entries.joinToString(",") { (name, hash) -> """"$name":"$hash"""" }
        val fixtureSourceHash = MessageDigest.getInstance("SHA-256")
            .digest(File("src/desktopTest/kotlin/fr/husi/ui/shiftvps/ShiftVpsScreenSnapshotTest.kt").readBytes())
            .joinToString("") { "%02x".format(it) }
        val resourceHashes = listOf("values/shiftvps.xml", "values-ru/shiftvps.xml",
            "values/shiftvps_account_strings.xml", "values-ru/shiftvps_account_strings.xml",
            "values/shiftvps_account_details_strings.xml", "values-ru/shiftvps_account_details_strings.xml").associateWith { path ->
            MessageDigest.getInstance("SHA-256").digest(File("src/commonMain/composeResources").resolve(path).readBytes())
                .joinToString("") { "%02x".format(it) }
        }.entries.joinToString(",") { (name, hash) -> """"$name":"$hash"""" }
        File("build/shiftvps-ui/screen-gallery-manifest.json").writeText(
            """{"generatedAt":"${Instant.now()}","renderer":"ImageComposeScene","fixture_data":true,"application_runtime_executed":false,"sourceHashes":{$hashesJson},"fixtureSourceHash":"$fixtureSourceHash","resourceHashes":{$resourceHashes},"screens":[${rows.joinToString(",")}]}"""
        )
    }

    @Test
    fun `switching tabs never changes the active connection`() = runBlocking {
        val account = getString(Res.string.shiftvps_tab_account)
        val invitations = getString(Res.string.shiftvps_tab_invitations)
        val connection = getString(Res.string.shiftvps_tab_connection)
        val disconnect = getString(Res.string.shiftvps_disconnect)
        var connectionActions = 0
        withContext(Dispatchers.Swing) {
            snapshot("tabs-preserve-connection", 390, 844,
                fixture().copy(summary = summaryFixture(), serviceState = ServiceState.Connected),
                onToggleConnection = { connectionActions++ }) { scene ->
                for (tab in listOf(account, invitations, connection)) {
                    clickText(scene, tab)
                    settle(scene)
                }
                assertEquals(0, connectionActions)
                assertTrue(texts(scene).contains(disconnect))
                assertTrue(radioRows(scene).all { SemanticsProperties.Disabled in it.config })
            }
        }
    }

    @Test
    fun `account application check is explicit and never connects installs or stops vpn`() = runBlocking {
        var checks = 0
        var installs = 0
        var connections = 0
        withContext(Dispatchers.Swing) {
            for ((device, width, height) in listOf(Triple("phone", 390, 844), Triple("desktop", 460, 760))) {
                snapshot("account-application-check-$device", width, height,
                    fixture().copy(summary = summaryFixture(), serviceState = ServiceState.Connected),
                    onToggleConnection = { connections++ }, onCheckAppUpdate = { checks++ }, onUpdate = { installs++ },
                    initialTab = ShiftVpsTab.Account) { scene ->
                    val before = checks
                    assertTrue(texts(scene).contains(getString(Res.string.shiftvps_update_check_action)))
                    assertEquals(0, installs)
                    assertEquals(0, connections)
                    clickText(scene, getString(Res.string.shiftvps_update_check_action))
                    assertEquals(before + 1, checks)
                    for (tab in listOf(Res.string.shiftvps_tab_invitations, Res.string.shiftvps_tab_connection,
                        Res.string.shiftvps_tab_account)) {
                        clickText(scene, getString(tab)); settle(scene)
                    }
                    assertEquals(before + 1, checks)
                    assertEquals(0, installs)
                    assertEquals(0, connections)
                }
            }
        }
    }

    @Test
    fun `account application checking current error and native dialog results stay visible`() = runBlocking {
        val cases = listOf(
            Triple("checking", ShiftVpsUpdateCheckPhase.Checking, Res.string.shiftvps_update_check_running),
            Triple("current", ShiftVpsUpdateCheckPhase.UpToDate, Res.string.shiftvps_update_check_current),
            Triple("error", ShiftVpsUpdateCheckPhase.Failed, Res.string.shiftvps_update_check_failed),
            Triple("native-dialog", ShiftVpsUpdateCheckPhase.ExternalDialog, Res.string.shiftvps_update_check_external),
        )
        withContext(Dispatchers.Swing) {
            for ((name, phase, result) in cases) {
                for ((device, width, height) in listOf(Triple("phone", 390, 844), Triple("desktop", 460, 760))) {
                    snapshot("account-application-$name-$device", width, height, fixture().copy(summary = summaryFixture()),
                        updateState = ShiftVpsUpdateState(checkPhase = phase), initialTab = ShiftVpsTab.Account) { scene ->
                        assertTrue(texts(scene).contains(getString(result)))
                        val label = textNode(scene, getString(if (phase == ShiftVpsUpdateCheckPhase.Checking) {
                            Res.string.shiftvps_update_check_running
                        } else Res.string.shiftvps_update_check_action))
                        val button = buttons(scene).first { it.boundsInRoot.contains(label.boundsInRoot.center) }
                        assertEquals(phase == ShiftVpsUpdateCheckPhase.Checking, SemanticsProperties.Disabled in button.config)
                        assertFalse(texts(scene).contains(getString(Res.string.shiftvps_update_action)))
                    }
                }
            }
        }
    }

    @Test
    fun `account application install remains a separate action and retains disconnect notice`() = runBlocking {
        var checks = 0
        var installs = 0
        var connections = 0
        withContext(Dispatchers.Swing) {
            for (phase in listOf(ShiftVpsUpdatePhase.Available, ShiftVpsUpdatePhase.NeedsDisconnect)) {
                snapshot("account-application-${phase.name.lowercase()}", 390, 844, fixture().copy(summary = summaryFixture()),
                    updateState = ShiftVpsUpdateState(phase, "0.1.10"), initialTab = ShiftVpsTab.Account,
                    onCheckAppUpdate = { checks++ }, onUpdate = { installs++ }, onToggleConnection = { connections++ }) { scene ->
                    val label = textNode(scene, getString(Res.string.shiftvps_update_check_action))
                    val button = buttons(scene).first { it.boundsInRoot.contains(label.boundsInRoot.center) }
                    assertEquals(phase == ShiftVpsUpdatePhase.NeedsDisconnect, SemanticsProperties.Disabled in button.config)
                    if (phase == ShiftVpsUpdatePhase.Available) {
                        clickText(scene, getString(Res.string.shiftvps_update_action))
                        assertEquals(1, installs)
                    } else {
                        assertTrue(texts(scene).contains(getString(Res.string.shiftvps_update_disconnect)))
                        assertEquals(1, installs)
                    }
                    assertEquals(0, checks)
                    assertEquals(0, connections)
                }
            }
        }
    }

    @Test
    fun `inactive account buys a real plan through native tariffs without exposing vpn actions`() = runBlocking {
        val actions = RecordingAccountActions()
        withContext(Dispatchers.Swing) {
            snapshot("inactive-native-checkout", 390, 844, unpaidFixture().copy(summary = null,
                summaryError = "Данные профиля недоступны."), accountActions = actions) { scene ->
                assertEquals(0, radioRows(scene).count())
                assertFalse(texts(scene).contains(getString(Res.string.shiftvps_connect)))
                assertFalse(texts(scene).contains(getString(Res.string.shiftvps_check_servers)))
                clickText(scene, getString(Res.string.shiftvps_account_choose_plan)); settle(scene)
                assertTrue(texts(scene).contains("12 месяцев"))
                assertEquals(0, actions.checkouts.size)
                clickText(scene, "12 месяцев")
                clickText(scene, getString(Res.string.shiftvps_account_card)); settle(scene)
                clickText(scene, getString(Res.string.shiftvps_account_pay)); settle(scene)
                assertEquals(listOf("year" to "card"), actions.checkouts)
            }
        }
    }

    @Test
    fun `ready and retained checkouts offer safe resume instead of a second purchase`() = runBlocking {
        withContext(Dispatchers.Swing) {
            for (retained in listOf(false, true)) {
                val actions = RecordingAccountActions()
                val requestId = "preview-checkout-request"
                val details = fullAccountFixture().let { it.copy(pendingOperations = if (retained) emptyList() else listOf(
                    ShiftVpsOperation(requestId, "checkout", "complete", checkoutUrl = "https://pay.rollypay.io/pay/preview-example", checkoutStatus = "created"))) }
                snapshot("checkout-resume-$retained", 390, 844, fixture().copy(accountDetails = details,
                    accountAction = if (retained) ShiftVpsAccountActionState(ShiftVpsAccountActionKind.Checkout,
                        ShiftVpsAccountActionPhase.Success, requestId = requestId) else ShiftVpsAccountActionState()),
                    accountActions = actions, initialTab = ShiftVpsTab.Account, initialAccountSection = ShiftVpsAccountSection.Subscription) { scene ->
                    assertFalse(texts(scene).contains(getString(Res.string.shiftvps_account_pay)))
                    clickText(scene, getString(Res.string.shiftvps_account_checkout_continue)); settle(scene)
                    assertTrue(actions.checkouts.isEmpty())
                    assertEquals(listOf(requestId), actions.reconciledOperations)
                }
            }
        }
    }

    @Test
    fun `unverified and inactive access retain disconnect but never expose new connection or probes`() = runBlocking {
        withContext(Dispatchers.Swing) {
            for (access in listOf(ShiftVpsAccessState.Unknown, ShiftVpsAccessState.Inactive)) {
                var toggles = 0
                snapshot("access-${access.name.lowercase()}-connected", 390, 844,
                    fixture().copy(accessState = access, serviceState = ServiceState.Connected), onToggleConnection = { toggles++ }) { scene ->
                    assertEquals(0, toggles)
                    assertEquals(0, radioRows(scene).count())
                    assertFalse(texts(scene).contains(getString(Res.string.shiftvps_connect)))
                    assertFalse(texts(scene).contains(getString(Res.string.shiftvps_check_servers)))
                    clickText(scene, getString(Res.string.shiftvps_disconnect)); settle(scene)
                    assertEquals(1, toggles)
                }
            }
        }
    }

    @Test
    fun `device removal requires a clear confirmation and cancel keeps every device`() = runBlocking {
        val actions = RecordingAccountActions()
        val delete = getString(Res.string.shiftvps_account_delete)
        withContext(Dispatchers.Swing) {
            snapshot("device-delete-confirmation", 390, 844, fixture().copy(summary = summaryFixture(), accountDetails = fullAccountFixture()),
                accountActions = actions, initialTab = ShiftVpsTab.Account, initialAccountSection = ShiftVpsAccountSection.Devices) { scene ->
                clickText(scene, delete); settle(scene)
                assertTrue(texts(scene).contains(getString(Res.string.shiftvps_account_delete_current_hint)))
                assertTrue(actions.deletedDevices.isEmpty())
                clickText(scene, getString(Res.string.shiftvps_cancel)); settle(scene)
                assertTrue(actions.deletedDevices.isEmpty())
                clickText(scene, delete, occurrence = 1); settle(scene)
                assertTrue(texts(scene).contains("Ноутбук"))
                clickText(scene, delete); settle(scene)
                assertEquals(listOf(102L), actions.deletedDevices)
            }
        }
    }

    @Test
    fun `password form validates confirmation and clears fields only after explicit submission`() = runBlocking {
        val actions = RecordingAccountActions()
        val change = getString(Res.string.shiftvps_account_password_change)
        withContext(Dispatchers.Swing) {
            snapshot("password-confirmation-flow", 390, 844, fixture().copy(summary = summaryFixture(), accountDetails = fullAccountFixture()),
                accountActions = actions, initialTab = ShiftVpsTab.Account, initialAccountSection = ShiftVpsAccountSection.Security) { scene ->
                setField(scene, 0, "preview-current")
                setField(scene, 1, "preview-new-password")
                setField(scene, 2, "different-preview")
                settle(scene)
                assertTrue(texts(scene).contains(getString(Res.string.shiftvps_account_password_mismatch)))
                assertEquals(0, actions.passwordChanges)
                setField(scene, 2, "preview-new-password"); settle(scene)
                clickText(scene, change); settle(scene)
                assertTrue(texts(scene).contains(getString(Res.string.shiftvps_account_password_hint)))
                assertEquals(0, actions.passwordChanges)
                clickText(scene, change); settle(scene)
                assertEquals(1, actions.passwordChanges)
                assertTrue(nodes(scene).filter { SemanticsProperties.EditableText in it.config }
                    .all { it.config[SemanticsProperties.EditableText].text.isEmpty() })
            }
        }
    }

    @Test
    fun `telegram-only capability hides unlink and impossible password forms`() = runBlocking {
        withContext(Dispatchers.Swing) {
            for (section in listOf(ShiftVpsAccountSection.Telegram, ShiftVpsAccountSection.Security)) {
                val actions = RecordingAccountActions()
                snapshot("telegram-only-${section.name.lowercase()}", 390, 844, telegramOnlyFixture(),
                    accountActions = actions, initialTab = ShiftVpsTab.Account, initialAccountSection = section) { scene ->
                    assertTrue(texts(scene).contains(getString(Res.string.shiftvps_account_telegram_sign_in)))
                    assertFalse(texts(scene).contains(getString(Res.string.shiftvps_account_telegram_unlink)))
                    assertFalse(texts(scene).contains(getString(Res.string.shiftvps_account_password_current)))
                    assertEquals(0, nodes(scene).count { SemanticsActions.SetText in it.config })
                    assertEquals(0, actions.passwordChanges)
                    clickText(scene, getString(Res.string.shiftvps_account_support)); settle(scene)
                    assertEquals(listOf(ShiftVpsAccountLink.SupportTelegram), actions.openedLinks)
                }
            }
        }
    }

    @Test
    fun `bottom navigation reserves space and keeps labels readable at compact and large text sizes`() = runBlocking {
        val connect = getString(Res.string.shiftvps_connect)
        val labels = listOf(getString(Res.string.shiftvps_tab_connection), getString(Res.string.shiftvps_tab_account),
            getString(Res.string.shiftvps_tab_invitations))
        withContext(Dispatchers.Swing) {
            for ((name, width, height, fontScale) in listOf(
                DockViewport("compact", 320, 700, 1f), DockViewport("large-text", 390, 844, 1.5f),
                DockViewport("desktop", 460, 760, 1f))) {
                snapshot("navigation-$name", width, height, fixture().copy(summary = summaryFixture()),
                    density = Density(1f, fontScale)) { scene ->
                    saveSnapshot(scene, "navigation-$name")
                    val tabs = navigationTabs(scene).toList()
                    assertEquals(3, tabs.size)
                    assertTrue(tabs.all { it.boundsInRoot.height >= 48f && it.boundsInRoot.left >= 20f &&
                        it.boundsInRoot.right <= width - 20f && it.boundsInRoot.bottom <= height - 12f })
                    val connectLabel = textNode(scene, connect)
                    val connectButton = buttons(scene).first { it.boundsInRoot.contains(connectLabel.boundsInRoot.center) }
                    assertTrue(connectButton.boundsInRoot.bottom < tabs.first().boundsInRoot.top,
                        "Navigation must reserve its own space beneath the connect action")
                    labels.forEach { label ->
                        val node = textNode(scene, label)
                        val layouts = mutableListOf<TextLayoutResult>()
                        assertEquals(true, node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts))
                        assertTrue(layouts.isNotEmpty() && layouts.all { layout ->
                            !layout.multiParagraph.didExceedMaxLines && layout.multiParagraph.width <= layout.size.width + 1f &&
                                layout.multiParagraph.height <= layout.size.height + 1f &&
                                (0 until layout.lineCount).none(layout::isLineEllipsized)
                        }, "$name: $label must read in full")
                    }
                }
            }
        }
    }

    @Test
    fun `bottom selection moves smoothly and honors disabled animation with pressed and focus feedback`() = runBlocking {
        val invitations = getString(Res.string.shiftvps_tab_invitations)
        withContext(Dispatchers.Swing) {
            for (scale in listOf(1f, 0f)) {
                var selected by mutableStateOf(ShiftVpsTab.Connection)
                val scene = ImageComposeScene(390, 110, Density(1f), coroutineContext = Dispatchers.Swing + object : MotionDurationScale {
                    override val scaleFactor = scale
                }) {
                    ShiftVpsTheme {
                        Box(Modifier.fillMaxSize().background(ShiftVpsPalette.Background).padding(horizontal = 20.dp, vertical = 12.dp)) {
                            ShiftVpsBottomNavigation(selected, { selected = it })
                        }
                    }
                }
                try {
                    settle(scene)
                    val initialX = navigationIndicator(scene).boundsInRoot.center.x
                    clickText(scene, invitations)
                    val targetX = navigationTabs(scene).last().boundsInRoot.center.x
                    val positions = mutableListOf<Float>()
                    repeat(8) { index ->
                        renderFrame(scene).use { image ->
                            val position = navigationIndicator(scene).boundsInRoot.center.x
                            positions += position
                            writeSnapshot(image, "navigation-motion-${scale.toInt()}-${(index + 1).toString().padStart(2, '0')}")
                            if (scale == 1f && positions.count { it > initialX + 1f && it < targetX - 1f } == 1) {
                                writeSnapshot(image, "navigation-motion-middle")
                            }
                        }
                        delay(20)
                    }
                    if (scale == 0f) {
                        assertTrue(positions.all { abs(it - initialX) < 1f || abs(it - targetX) < 1f },
                            "System-disabled motion must select without spatial animation: $positions")
                    } else {
                        assertTrue(positions.any { it > initialX + 1f && it < targetX - 1f },
                            "The selected pill must travel between tab positions: $positions")
                    }
                    settle(scene)
                    assertTrue(abs(navigationIndicator(scene).boundsInRoot.center.x - targetX) < 1f)
                    assertEquals(1, navigationTabs(scene).count { it.config[SemanticsProperties.Selected] })
                    val tab = navigationTabs(scene).last()
                    scene.sendPointerEvent(PointerEventType.Press, position = tab.boundsInRoot.center,
                        type = PointerType.Mouse, button = PointerButton.Primary)
                    settle(scene)
                    saveSnapshot(scene, "navigation-pressed-${scale.toInt()}")
                    scene.sendPointerEvent(PointerEventType.Release, position = tab.boundsInRoot.center,
                        type = PointerType.Mouse, button = PointerButton.Primary)
                    assertEquals(true, tab.config[SemanticsActions.RequestFocus].action?.invoke())
                    settle(scene)
                    assertTrue(navigationTabs(scene).last().config[SemanticsProperties.Focused])
                    saveSnapshot(scene, "navigation-focus-${scale.toInt()}")
                } finally {
                    scene.close()
                }
            }
        }
        val frameGroups = listOf(1, 0).joinToString(",") { scale ->
            val frames = (1..8).joinToString(",") { index ->
                val name = "navigation-motion-$scale-${index.toString().padStart(2, '0')}.png"
                val hash = MessageDigest.getInstance("SHA-256").digest(File("build/shiftvps-ui").resolve(name).readBytes())
                    .joinToString("") { "%02x".format(it) }
                """{"filename":"$name","sha256":"$hash","frame_time_ms":${(index - 1) * 40}}"""
            }
            """{"motion_duration_scale":$scale,"frames":[$frames]}"""
        }
        File("build/shiftvps-ui/navigation-motion-manifest.json").writeText(
            """{"renderer":"ImageComposeScene","surface":"ShiftVpsBottomNavigation","fixture_data":true,"application_runtime_executed":false,"width":390,"height":110,"frame_duration_ms":40,"sequences":[$frameGroups]}""")
    }

    private data class DockViewport(val name: String, val width: Int, val height: Int, val fontScale: Float)

    private fun navigationTabs(scene: ImageComposeScene) = nodes(scene)
        .filter { SemanticsProperties.Role in it.config && it.config[SemanticsProperties.Role] == Role.Tab }
        .sortedBy { it.boundsInRoot.left }

    private fun navigationIndicator(scene: ImageComposeScene) = nodes(scene).first {
        SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag] == "shiftvps-navigation-indicator"
    }

    private fun textNode(scene: ImageComposeScene, text: String) = nodes(scene).first {
        SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { item -> item.text == text }
    }

    private data class GalleryCase(
        val id: String,
        val section: String,
        val status: String,
        val uiState: ShiftVpsUiState,
        val tab: ShiftVpsTab = ShiftVpsTab.Connection,
        val update: ShiftVpsUpdateState = ShiftVpsUpdateState(),
        val browserError: Boolean = false,
        val copyInvitation: Boolean = false,
        val accountSection: ShiftVpsAccountSection = ShiftVpsAccountSection.Overview,
        val interaction: String? = null,
    )

    private class MemoryClipboard : Clipboard {
        private var entry: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    }

    private class RecordingAccountActions : ShiftVpsAccountActions {
        val checkouts = mutableListOf<Pair<String, String>>()
        val deletedDevices = mutableListOf<Long>()
        val reconciledOperations = mutableListOf<String>()
        val openedLinks = mutableListOf<ShiftVpsAccountLink>()
        var passwordChanges = 0
        override fun refreshAccountDetails() = Unit
        override fun addDevice(name: String) = Unit
        override fun renameDevice(deviceId: Long, name: String) = Unit
        override fun deleteDevice(deviceId: Long) { deletedDevices += deviceId }
        override fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String) { passwordChanges++ }
        override fun unlinkTelegram() = Unit
        override fun createCheckout(planCode: String, paymentMethod: String) { checkouts += planCode to paymentMethod }
        override fun requestLegacyPayout(network: String, wallet: String) = Unit
        override fun reconcileAccountOperation(requestId: String) { reconciledOperations += requestId }
        override fun openAccountLink(link: ShiftVpsAccountLink) { openedLinks += link }
        override fun dismissAccountAction() = Unit
    }

    private fun summaryFixture() = ShiftVpsAccountSummary(
        account = ShiftVpsAccountDetails(name = "Александр", phone = "+7 (000) 000-00-00",
            subscriptionExpiresAt = "2026-12-03T12:00:00+00:00", accessActive = true,
            isFree = false, devicesUsed = 2, deviceLimit = 3),
        device = ShiftVpsAccountDevice(101, "Мой компьютер"),
        links = ShiftVpsAccountLinks("https://shiftvps.ru/dashboard", "https://shiftvps.ru/pay", "https://shiftvps.ru/referral"),
        referral = ShiftVpsReferralSummary("https://shiftvps.ru/r/preview-example", 3, 1, 30, 1, 30),
    )

    private fun fullAccountFixture() = ShiftVpsFullAccount(
        schemaVersion = 1, accountId = 1, account = summaryFixture().account, currentDeviceId = 101,
        devices = listOf(ShiftVpsDeviceDetails(101, "Мой компьютер", true, false, statusSource = "unknown"),
            ShiftVpsDeviceDetails(102, "Ноутбук", false, false, statusSource = "unknown")),
        plans = listOf(ShiftVpsPlan("month", "1 месяц", 30, 199, "RUB"), ShiftVpsPlan("quarter", "3 месяца", 90, 399, "RUB"),
            ShiftVpsPlan("year", "12 месяцев", 365, 1200, "RUB")),
        payments = listOf(ShiftVpsPayment(1, "month", "1 месяц", "199.00", 30, "2026-11-03T12:00:00+00:00", "paid"),
            ShiftVpsPayment(2, "month", "1 месяц", "150.50", 30, "2026-10-03T12:00:00+00:00", "paid")),
        telegram = ShiftVpsTelegram(linked = true, canUnlink = true, username = "demo_user",
            linkUrl = "https://shiftvps.ru/dashboard#telegram", trialAvailable = false, trialDays = 3),
        billing = ShiftVpsBilling(true, listOf("sbp", "card"), "https://shiftvps.ru/pay", listOf("pay.rollypay.io")),
        support = ShiftVpsSupport("support@shiftvps.ru", "https://t.me/shiftvpsbot", "https://shiftvps.ru/privacy", "https://shiftvps.ru/terms"),
        downloads = ShiftVpsDownloads("https://shiftvps.ru/downloads/shiftvps-0.1.5-windows-x64-setup.exe",
            "https://shiftvps.ru/downloads/shiftvps-0.1.6-arm64-v8a.apk", "https://shiftvps.ru/downloads/shiftvps-0.1.5-macos-arm64.dmg",
            "https://shiftvps.ru/downloads/shiftvps-0.1.5-macos-amd64.dmg"),
        legacyReferral = ShiftVpsLegacyReferral("12.50", "50", false, false, "https://shiftvps.ru/referral",
            listOf(ShiftVpsLegacyPayout(1, "50.50", "paid", "2026-09-03T12:00:00+00:00", "2026-09-05T12:00:00+00:00"))),
        pendingOperations = emptyList(), capabilities = ShiftVpsAccountCapabilities(true, true, true, true),
    )

    private fun unpaidFixture(): ShiftVpsUiState {
        val full = fullAccountFixture()
        val account = full.account.copy(accessActive = false, subscriptionExpiresAt = null, devicesUsed = 1)
        val summary = summaryFixture().copy(account = account,
            referral = summaryFixture().referral.copy(invitedCount = 0, paidCount = 0, rewardedDays = 0, rewardedMonths = 0))
        return fixture().copy(accessState = ShiftVpsAccessState.Inactive, servers = emptyList(), selectedId = null,
            summary = summary, accountDetails = full.copy(account = account, devices = listOf(full.devices.first()), payments = emptyList(),
                telegram = full.telegram.copy(linked = false, canUnlink = false, username = null, trialAvailable = true),
                legacyReferral = full.legacyReferral.copy(balanceUsdt = "0", payouts = emptyList()),
                capabilities = full.capabilities.copy(addDevice = false, unlinkTelegram = false)))
    }

    private fun telegramOnlyFixture(): ShiftVpsUiState {
        val unpaid = unpaidFixture()
        val details = checkNotNull(unpaid.accountDetails)
        val account = details.account.copy(phone = "", accessActive = true, subscriptionExpiresAt = "2026-11-06T12:00:00+00:00")
        return unpaid.copy(accessState = ShiftVpsAccessState.Active, summary = checkNotNull(unpaid.summary).copy(account = account),
            accountDetails = details.copy(account = account,
                telegram = details.telegram.copy(linked = true, canUnlink = false, username = "demo_user", trialAvailable = false),
                capabilities = details.capabilities.copy(addDevice = true, changePassword = false, unlinkTelegram = false)))
    }

    private suspend fun galleryInteraction(scene: ImageComposeScene, interaction: String?) {
        when (interaction) {
            "add" -> clickText(scene, getString(Res.string.shiftvps_account_add_device))
            "rename" -> clickText(scene, getString(Res.string.shiftvps_account_rename))
            "delete-current" -> clickText(scene, getString(Res.string.shiftvps_account_delete))
            "delete-other" -> clickText(scene, getString(Res.string.shiftvps_account_delete), occurrence = 1)
            "unlink" -> clickText(scene, getString(Res.string.shiftvps_account_telegram_unlink))
            "password-confirm" -> {
                setField(scene, 0, "preview-current")
                setField(scene, 1, "preview-new-password")
                setField(scene, 2, "preview-new-password")
                settle(scene)
                clickText(scene, getString(Res.string.shiftvps_account_password_change))
            }
            "payout-confirm" -> {
                setField(scene, 0, "TAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                settle(scene)
                clickText(scene, getString(Res.string.shiftvps_account_legacy_request))
            }
        }
        if (interaction != null) settle(scene)
    }

    private fun setField(scene: ImageComposeScene, index: Int, value: String) {
        val field = nodes(scene).filter { SemanticsActions.SetText in it.config }.elementAt(index)
        assertEquals(true, field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)))
    }

    private suspend fun settle(scene: ImageComposeScene) {
        repeat(8) { renderFrame(scene).close(); delay(20) }
    }

    private fun renderFrame(scene: ImageComposeScene): Image {
        frameTimeNanos += 40_000_000L
        return scene.render(frameTimeNanos)
    }

    private fun clickText(scene: ImageComposeScene, text: String, occurrence: Int = 0) {
        val labels = nodes(scene).filter {
            SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { item -> item.text == text }
        }.toList()
        val action = labels.mapNotNull { label -> nodes(scene).filter {
                SemanticsActions.OnClick in it.config && it.boundsInRoot.contains(label.boundsInRoot.center)
            }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        }.distinctBy { it.id }.elementAt(occurrence)
        assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke())
    }

    private fun fixture() = ShiftVpsUiState(
        signedIn = true,
        accessState = ShiftVpsAccessState.Active,
        accountName = "Александр",
        servers = listOf(
            ShiftVpsServer(-1, "Швеция", latencyMs = 82, reachable = true, countryCode = "SE"),
            ShiftVpsServer(-2, "Литва", latencyMs = 116, reachable = true, countryCode = "LT"),
            ShiftVpsServer(-3, "Израиль", reachable = false, countryCode = "IL"),
        ),
        selectedId = -1,
        lastUpdatedMillis = 1_794_963_600_000,
    )

    private suspend fun snapshot(
        name: String,
        width: Int,
        height: Int,
        state: ShiftVpsUiState,
        updateState: ShiftVpsUpdateState = ShiftVpsUpdateState(),
        browserError: Boolean = false,
        clipboard: Clipboard = MemoryClipboard(),
        onToggleConnection: () -> Unit = {},
        onCheckAppUpdate: () -> Unit = {},
        onUpdate: () -> Unit = {},
        density: Density = Density(1f),
        accountActions: ShiftVpsAccountActions? = null,
        initialTab: ShiftVpsTab = ShiftVpsTab.Connection,
        initialAccountSection: ShiftVpsAccountSection = ShiftVpsAccountSection.Overview,
        verify: suspend (ImageComposeScene) -> Unit,
    ) {
        val scene = ImageComposeScene(width, height, density, coroutineContext = Dispatchers.Swing) {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme(colorScheme = ShiftVpsColors) {
                    Column {
                        ShiftVpsScreen(
                            state = state,
                            modifier = Modifier.weight(1f),
                            onSignIn = {},
                            onCancelLogin = {},
                            onRefresh = {},
                            onCheckServers = {},
                            onSelectServer = {},
                            onToggleConnection = onToggleConnection,
                            onLogout = {},
                            onDismissError = {},
                            browserError = browserError,
                            updateState = updateState,
                            onCheckAppUpdate = onCheckAppUpdate,
                            onUpdate = onUpdate,
                            accountActions = accountActions,
                            initialTab = initialTab,
                            initialAccountSection = initialAccountSection,
                        )
                    }
                }
            }
        }
        try {
            repeat(5) { renderFrame(scene).close(); delay(20) }
            verify(scene)
            saveSnapshot(scene, name)
        } finally {
            scene.close()
        }
    }

    private fun saveSnapshot(scene: ImageComposeScene, name: String) {
        renderFrame(scene).use { image -> writeSnapshot(image, name) }
    }

    private fun writeSnapshot(image: Image, name: String) {
        val output = File("build/shiftvps-ui").apply { mkdirs() }.resolve("$name.png")
        checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> output.writeBytes(data.bytes) }
        assertTrue(output.length() > 0)
        val pixels = ImageIO.read(output)
        val colors = mutableSetOf<Int>()
        for (y in 0 until pixels.height step 8) {
            for (x in 0 until pixels.width step 8) colors += pixels.getRGB(x, y)
        }
        assertTrue(colors.size > 4, "$name must contain rendered content rather than an empty frame")
    }

    private fun resolvedFamilyName(value: Any): String {
        val typeface = if (value is Typeface) value else value.javaClass.getMethod("getTypeface").invoke(value) as Typeface
        return typeface.familyName
    }

    @Composable
    private fun PreviewCaption() {
        Text(
            "ПРЕДПРОСМОТР · пример данных",
            color = Color(0xFF191600),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().background(Color(0xFFFFE8A3)).padding(8.dp),
        )
    }

    private fun nodes(scene: ImageComposeScene): Sequence<SemanticsNode> {
        fun children(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(children(child))
        }
        return scene.semanticsOwners.asSequence().flatMap { children(it.rootSemanticsNode) }
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene)
        .filter { SemanticsProperties.Text in it.config }
        .flatMap { it.config[SemanticsProperties.Text].asSequence() }
        .map { it.text }
        .toList()

    private fun radioRows(scene: ImageComposeScene): Sequence<SemanticsNode> = nodes(scene).filter {
        SemanticsProperties.Role in it.config && it.config[SemanticsProperties.Role] == Role.RadioButton &&
            SemanticsActions.OnClick in it.config
    }

    private fun buttons(scene: ImageComposeScene): Sequence<SemanticsNode> = nodes(scene).filter {
        SemanticsProperties.Role in it.config && it.config[SemanticsProperties.Role] == Role.Button &&
            SemanticsActions.OnClick in it.config
    }
}
