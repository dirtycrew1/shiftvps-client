package fr.husi.shiftvps

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class LegacyRouting018(
    @SerialName("direct_domain_suffixes") val directDomainSuffixes: List<String> = listOf("ru"),
)

class ShiftVpsAndroidRoutingTest {
    private val ownPackage = "ru.shiftvps.client"
    private val directApps = setOf("ru.oneme.app", "ru.yandex.taxi", "ru.rostel")

    @Test
    fun `actual backend policy fixtures match strict new parsing and unchanged legacy parsing`() {
        val input = checkNotNull(javaClass.getResourceAsStream("/shiftvps/android-routing-backend-fixtures.json"))
        val document = clientJson.parseToJsonElement(input.use { it.readBytes().toString(Charsets.UTF_8) }) as JsonObject
        val cases = document.getValue("cases") as JsonArray
        assertEquals(38, cases.size)
        cases.forEach { item ->
            val fixture = item as JsonObject
            val name = fixture.getValue("name").jsonPrimitive.content
            val policy = fixture.getValue("policy") as JsonObject
            val expected = fixture.getValue("valid_backend_and_new_client").jsonPrimitive.boolean
            val accepted = runCatching {
                clientJson.decodeFromJsonElement(ShiftVpsPolicy.serializer(), policy).validated()
            }.isSuccess
            assertEquals(expected, accepted, name)
            val legacy = clientJson.decodeFromJsonElement(LegacyRouting018.serializer(), policy.getValue("routing"))
            assertEquals(listOf("ru"), legacy.directDomainSuffixes, name)
        }
    }

    @Test
    fun `legacy policy without packages preserves old behavior`() {
        val policy = clientJson.decodeFromString<ShiftVpsPolicy>("""{"routing":{"direct_domain_suffixes":["ru"]}}""").validated()
        assertEquals(emptyList(), policy.routing.directAndroidPackages)
        assertNull(androidAppRouting(false, true, false, emptySet(), emptySet(), emptySet(), ownPackage) {
            error("No installed app discovery is needed")
        })
    }

    @Test
    fun `policy accepts exact packages and stably deduplicates before use`() {
        val packages = listOf("ru.oneme.app", "ru.yandex.taxi", "ru.oneme.app", "ru.rostel")
        ShiftVpsPolicy(routing = ShiftVpsRouting(directAndroidPackages = packages)).validated()
        assertEquals(directApps.toList(), validatedManagedAndroidPackages(packages))
        assertEquals(listOf("ru"), ShiftVpsRouting(directAndroidPackages = packages).directDomainSuffixes)
    }

    @Test
    fun `untrusted package policy cannot select browsers system services or malformed identifiers`() {
        val rejected = listOf("", "ru", "ru.*", "ru.oneme.app\n", " ru.oneme.app", "ru..app", "ru.1app",
            "ru.приложение", "https://ru.oneme.app", "ru." + "a".repeat(126),
            "com.google.android.gms", "com.android.chrome", "com.huawei.hwid", "android.system",
            ownPackage, "$ownPackage.debug", "com.yandex.browser", "com.yandex.browser.beta",
            "ru.yandex.searchplugin", "org.mozilla.firefox", "com.opera.browser", "com.brave.browser",
            "com.microsoft.emmx", "com.sec.android.app.sbrowser")
        rejected.forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) {
                ShiftVpsPolicy(routing = ShiftVpsRouting(directAndroidPackages = listOf(name))).validated()
            }
        }
        assertFailsWith<IllegalArgumentException> { validatedManagedAndroidPackages(List(65) { "ru.oneme.app" }) }
        assertEquals(listOf("ru.oneme.app"), validatedManagedAndroidPackages(List(64) { "ru.oneme.app" }))
    }

    @Test
    fun `managed apps bypass the entire Android VPN without installed app discovery`() {
        val result = androidAppRouting(false, false, false, setOf("manual.saved.app"), emptySet(), directApps, ownPackage) {
            error("Managed deny list must not enumerate installed applications")
        }!!
        assertTrue(result.bypass)
        assertEquals(directApps, result.packages)
    }

    @Test
    fun `managed bypass takes priority over manual proxy rules without rewriting preferences`() {
        val selected = mutableSetOf("manual.saved.app", "ru.oneme.app")
        val rules = mutableSetOf("ru.oneme.app", "forced.proxy.app")
        val result = androidAppRouting(true, true, false, selected, rules, directApps, ownPackage) {
            error("Bypass lists need no installed app discovery")
        }!!
        assertTrue(result.bypass)
        assertEquals(directApps + "manual.saved.app", result.packages)
        assertEquals(setOf("manual.saved.app", "ru.oneme.app"), selected)
        assertEquals(setOf("ru.oneme.app", "forced.proxy.app"), rules)
    }

    @Test
    fun `manual allow list excludes managed apps and retains own service`() {
        val result = androidAppRouting(true, false, false,
            setOf("manual.saved.app", "ru.oneme.app"), setOf("ru.rostel", "forced.proxy.app"), directApps, ownPackage) {
            error("Explicit allow list needs no installed app discovery")
        }!!
        assertFalse(result.bypass)
        assertEquals(setOf("manual.saved.app", "forced.proxy.app", ownPackage), result.packages)
    }

    @Test
    fun `existing root uid allow-list branch still respects managed exclusions`() {
        val installed = setOf("android", "other.installed.app", "manual.saved.app") + directApps
        val result = androidAppRouting(true, true, true, setOf("manual.saved.app"), emptySet(), directApps, ownPackage) {
            installed
        }!!
        assertFalse(result.bypass)
        assertEquals(setOf("android", "other.installed.app", ownPackage), result.packages)
    }

    @Test
    fun `removing managed policy restores the manual connection snapshot`() {
        val selected = setOf("ru.oneme.app", "manual.saved.app")
        val result = androidAppRouting(true, true, false, selected, setOf("ru.oneme.app"), emptySet(), ownPackage) {
            error("Bypass lists need no installed app discovery")
        }!!
        assertTrue(result.bypass)
        assertEquals(setOf("manual.saved.app"), result.packages)
        assertEquals(setOf("ru.oneme.app", "manual.saved.app"), selected)
    }

    @Test
    fun `managed snapshot applies only to the selected owned compatible Naive profile`() {
        val snapshot = clientJson.encodeToString(ManagedAndroidRoutingSnapshot.serializer(),
            ManagedAndroidRoutingSnapshot(12, 4, directApps.toList()))
        assertEquals(directApps, managedAndroidPackagesForProfile(snapshot, 12, 4, true))
        assertEquals(emptySet(), managedAndroidPackagesForProfile(snapshot, 13, 4, true))
        assertEquals(emptySet(), managedAndroidPackagesForProfile(snapshot, 12, 5, true))
        assertEquals(emptySet(), managedAndroidPackagesForProfile(snapshot, 12, 4, false))
        assertEquals(emptySet(), managedAndroidPackagesForProfile(snapshot, 0, 4, true))
        assertEquals(emptySet(), managedAndroidPackagesForProfile("", 12, 4, true))
        assertEquals(emptySet(), managedAndroidPackagesForProfile("x".repeat(12289), 12, 4, true))
        val bad = clientJson.encodeToString(ManagedAndroidRoutingSnapshot.serializer(),
            ManagedAndroidRoutingSnapshot(12, 4, listOf("com.android.chrome")))
        assertEquals(emptySet(), managedAndroidPackagesForProfile(bad, 12, 4, true))
    }

    @Test
    fun `lockdown notice requires supported Android real lockdown and an installed managed app`() {
        assertFalse(shouldWarnManagedAndroidLockdown(28, true, true))
        assertFalse(shouldWarnManagedAndroidLockdown(29, false, true))
        assertFalse(shouldWarnManagedAndroidLockdown(36, true, false))
        assertTrue(shouldWarnManagedAndroidLockdown(29, true, true))
        assertTrue(shouldWarnManagedAndroidLockdown(36, true, true))
    }

    @Test
    fun `split apps omit only the connection HTTP proxy without changing its saved preference`() {
        assertFalse(shouldAppendAndroidVpnHttpProxy(false, false))
        assertFalse(shouldAppendAndroidVpnHttpProxy(false, true))
        assertTrue(shouldAppendAndroidVpnHttpProxy(true, false))
        assertFalse(shouldAppendAndroidVpnHttpProxy(true, true))
    }
}
