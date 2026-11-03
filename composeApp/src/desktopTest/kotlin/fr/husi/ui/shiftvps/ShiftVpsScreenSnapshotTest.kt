package fr.husi.ui.shiftvps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
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
import fr.husi.bg.ServiceState
import fr.husi.resources.*
import fr.husi.shiftvps.ShiftVpsServer
import fr.husi.shiftvps.ShiftVpsAccountSummary
import fr.husi.shiftvps.ShiftVpsAccountDetails
import fr.husi.shiftvps.ShiftVpsAccountDevice
import fr.husi.shiftvps.ShiftVpsAccountLinks
import fr.husi.shiftvps.ShiftVpsReferralSummary
import fr.husi.compose.getPlainText
import fr.husi.shiftvps.ShiftVpsUiState
import fr.husi.shiftvps.ShiftVpsUpdatePhase
import fr.husi.shiftvps.ShiftVpsUpdateState
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
            val scene = ImageComposeScene(480, 160, Density(1f)) {
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
            val scene = ImageComposeScene(460, 460, Density(1f)) {
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
        val base = fixture().copy(summary = summary)
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
            GalleryCase("account-active", "Account", "active", base, ShiftVpsTab.Account),
            GalleryCase("account-expired", "Account", "expired", base.copy(summary = summary.copy(account =
                summary.account.copy(accessActive = false, subscriptionExpiresAt = "2026-10-01T12:00:00+00:00"))), ShiftVpsTab.Account),
            GalleryCase("account-free", "Account", "free", base.copy(summary = summary.copy(account =
                summary.account.copy(isFree = true, subscriptionExpiresAt = null, deviceLimit = 5))), ShiftVpsTab.Account),
            GalleryCase("account-loading", "Account", "loading", base.copy(summary = null, summaryLoading = true), ShiftVpsTab.Account),
            GalleryCase("account-error", "Account", "error", base.copy(summary = null, summaryError = summaryFailure), ShiftVpsTab.Account),
            GalleryCase("account-cached-error", "Account", "cached-error", base.copy(summaryError = summaryFailure), ShiftVpsTab.Account),
            GalleryCase("invitations-default", "Invitations", "default", base, ShiftVpsTab.Invitations),
            GalleryCase("invitations-zero", "Invitations", "zero", base.copy(summary = summary.copy(referral =
                summary.referral.copy(invitedCount = 0, paidCount = 0, rewardedDays = 0, rewardedMonths = 0))), ShiftVpsTab.Invitations),
            GalleryCase("invitations-loading", "Invitations", "loading", base.copy(summary = null, summaryLoading = true), ShiftVpsTab.Invitations),
            GalleryCase("invitations-error", "Invitations", "error", base.copy(summary = null, summaryError = summaryFailure), ShiftVpsTab.Invitations),
            GalleryCase("invitations-copied", "Invitations", "copied", base, ShiftVpsTab.Invitations, copyInvitation = true),
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
                    })
                    snapshot(name, width, height, uiState, updateState = case.update,
                        browserError = case.browserError, clipboard = clipboard) { scene ->
                        if (case.tab != ShiftVpsTab.Connection) {
                            clickText(scene, tabs.getValue(case.tab))
                            settle(scene)
                        }
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
                        assertTrue(nodes(scene).filter { SemanticsProperties.Text in it.config }
                            .all { it.boundsInRoot.left >= -0.5f && it.boundsInRoot.right <= width + 0.5f },
                            "Text must remain inside the $name viewport")
                    }
                    rows += """{"id":"$name","section":"${case.section}","state":"${case.status}","viewport":"$viewport","filename":"$name.png","width":$width,"height":$height,"locale":"ru"}"""
                }
            }
        }
        val sourceHashes = listOf("ShiftVpsContent.kt", "ShiftVpsAccountContent.kt", "ShiftVpsTheme.kt", "ShiftVpsCountryFlag.kt")
            .associateWith { name ->
                val bytes = File("src/commonMain/kotlin/fr/husi/ui/shiftvps").resolve(name).readBytes()
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
        val hashesJson = sourceHashes.entries.joinToString(",") { (name, hash) -> """"$name":"$hash"""" }
        val fixtureSourceHash = MessageDigest.getInstance("SHA-256")
            .digest(File("src/desktopTest/kotlin/fr/husi/ui/shiftvps/ShiftVpsScreenSnapshotTest.kt").readBytes())
            .joinToString("") { "%02x".format(it) }
        val resourceHashes = listOf("values/shiftvps.xml", "values-ru/shiftvps.xml",
            "values/shiftvps_account_strings.xml", "values-ru/shiftvps_account_strings.xml").associateWith { path ->
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

    private data class GalleryCase(
        val id: String,
        val section: String,
        val status: String,
        val uiState: ShiftVpsUiState,
        val tab: ShiftVpsTab = ShiftVpsTab.Connection,
        val update: ShiftVpsUpdateState = ShiftVpsUpdateState(),
        val browserError: Boolean = false,
        val copyInvitation: Boolean = false,
    )

    private class MemoryClipboard : Clipboard {
        private var entry: ClipEntry? = null
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    }

    private fun summaryFixture() = ShiftVpsAccountSummary(
        account = ShiftVpsAccountDetails(name = "Александр", phone = "+7 (000) 000-00-00",
            subscriptionExpiresAt = "2026-12-03T12:00:00+00:00", accessActive = true,
            isFree = false, devicesUsed = 2, deviceLimit = 3),
        device = ShiftVpsAccountDevice(-1, "Мой компьютер"),
        links = ShiftVpsAccountLinks("https://shiftvps.ru/dashboard", "https://shiftvps.ru/pay", "https://shiftvps.ru/referral"),
        referral = ShiftVpsReferralSummary("https://shiftvps.ru/r/preview-example", 3, 1, 30, 1, 30),
    )

    private suspend fun settle(scene: ImageComposeScene) {
        repeat(8) { renderFrame(scene).close(); delay(20) }
    }

    private fun renderFrame(scene: ImageComposeScene): Image {
        frameTimeNanos += 40_000_000L
        return scene.render(frameTimeNanos)
    }

    private fun clickText(scene: ImageComposeScene, text: String) {
        val label = nodes(scene).first {
            SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { item -> item.text == text }
        }
        val action = nodes(scene).filter {
            SemanticsActions.OnClick in it.config && it.boundsInRoot.contains(label.boundsInRoot.center)
        }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
        assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke())
    }

    private fun fixture() = ShiftVpsUiState(
        signedIn = true,
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
        verify: suspend (ImageComposeScene) -> Unit,
    ) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
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
        val output = File("build/shiftvps-ui").apply { mkdirs() }.resolve("$name.png")
        renderFrame(scene).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> output.writeBytes(data.bytes) }
        }
        assertTrue(output.length() > 0)
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
