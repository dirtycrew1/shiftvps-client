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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.husi.bg.ServiceState
import fr.husi.resources.Res
import fr.husi.resources.shiftvps_connect
import fr.husi.resources.shiftvps_disconnect
import fr.husi.resources.shiftvps_sign_in
import fr.husi.resources.shiftvps_sign_in_code
import fr.husi.resources.shiftvps_sign_in_waiting
import fr.husi.resources.shiftvps_sign_in_failed
import fr.husi.resources.shiftvps_browser_error
import fr.husi.resources.shiftvps_cancel
import fr.husi.resources.shiftvps_retry
import fr.husi.resources.shiftvps_update_action
import fr.husi.resources.shiftvps_update_available
import fr.husi.shiftvps.ShiftVpsServer
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raster-only UI fixtures: no account controller, core process or network is constructed. */
@OptIn(ExperimentalComposeUiApi::class)
class ShiftVpsScreenSnapshotTest {
    @Test
    fun `login remains a single browser sign in action`() = runBlocking {
        val signIn = getString(Res.string.shiftvps_sign_in)
        withContext(Dispatchers.Swing) {
            authSizes.forEach { (device, width, height) ->
                snapshot("login-$device", width, height, ShiftVpsUiState()) { scene ->
                    assertTrue(texts(scene).contains(signIn))
                    assertEquals(1, buttons(scene).count())
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
                    assertEquals(1, buttons(scene).count())
                }
            }
        }
    }

    private val authSizes = listOf(Triple("phone", 320, 700), Triple("desktop", 460, 760))

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
                repeat(5) { frame -> scene.render((frame + 1L) * 20_000_000L).close(); delay(20) }
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
                repeat(5) { frame -> scene.render((frame + 1L) * 20_000_000L).close(); delay(20) }
                saveSnapshot(scene, "country-flags")
                assertTrue(texts(scene).containsAll(codes.filter(String::isNotBlank) + "Unknown"))
            } finally {
                scene.close()
            }
        }
    }

    private fun fixture() = ShiftVpsUiState(
        signedIn = true,
        accountName = "Предпросмотр интерфейса",
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
        verify: (ImageComposeScene) -> Unit,
    ) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme(colorScheme = ShiftVpsColors) {
                Column {
                    PreviewCaption()
                    ShiftVpsScreen(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onSignIn = {},
                        onCancelLogin = {},
                        onRefresh = {},
                        onCheckServers = {},
                        onSelectServer = {},
                        onToggleConnection = {},
                        onLogout = {},
                        onDismissError = {},
                        browserError = browserError,
                        updateState = updateState,
                    )
                }
            }
        }
        try {
            repeat(5) { frame -> scene.render((frame + 1L) * 20_000_000L).close(); delay(20) }
            verify(scene)
            saveSnapshot(scene, name)
        } finally {
            scene.close()
        }
    }

    private fun saveSnapshot(scene: ImageComposeScene, name: String) {
        val output = File("build/shiftvps-ui").apply { mkdirs() }.resolve("$name.png")
        scene.render(1_000_000_000L).use { image ->
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
