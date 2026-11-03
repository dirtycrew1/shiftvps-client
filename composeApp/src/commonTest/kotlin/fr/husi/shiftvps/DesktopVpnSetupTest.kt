package fr.husi.shiftvps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class DesktopVpnSetupTest {
    @Test
    fun `expiry while administrator dialog is open clears only its pending request and allows retry`() = runTest {
        var requestId = Any()
        val captured = requestId
        var pending = true
        var allowed = true
        var errors = 0
        val request = DesktopConnectRequest(
            sameRequest = { pending && requestId === captured },
            allowed = { allowed },
            finish = { pending = false; if (it != null) errors++ },
        )
        assertTrue(request.isCurrent())
        allowed = false
        assertFalse(request.isCurrent())
        request.complete(DesktopVpnSetupFailure.PermissionCancelled)
        assertFalse(pending)
        assertEquals(0, errors)
        allowed = true
        pending = true
        requestId = Any()
        request.complete(DesktopVpnSetupFailure.DaemonUnavailable)
        assertTrue(pending)
        assertEquals(0, errors)
    }

    @Test
    fun `signout new identity profile or new manual intent cannot be changed by stale completion`() = runTest {
        for (change in 0..3) {
            var generation = 1
            var profile = 10
            var identity = Any()
            var intent = Any()
            val capturedIdentity = identity
            val capturedIntent = intent
            var completions = 0
            val request = DesktopConnectRequest(
                sameRequest = { generation == 1 && profile == 10 && identity === capturedIdentity && intent === capturedIntent },
                allowed = { true }, finish = { completions++ },
            )
            when (change) { 0 -> generation++; 1 -> profile++; 2 -> identity = Any(); else -> intent = Any() }
            request.complete(DesktopVpnSetupFailure.DaemonUnavailable)
            assertFalse(request.isCurrent())
            assertEquals(0, completions)
        }
    }

    @Test
    fun `same permitted manual failure completes once with curated error`() = runTest {
        var pending = true
        val reasons = mutableListOf<DesktopVpnSetupFailure?>()
        val request = DesktopConnectRequest({ pending }, { true }) { pending = false; reasons += it }
        request.complete(DesktopVpnSetupFailure.PermissionCancelled)
        request.complete(DesktopVpnSetupFailure.DaemonUnavailable)
        assertEquals(listOf<DesktopVpnSetupFailure?>(DesktopVpnSetupFailure.PermissionCancelled), reasons)
    }
    @Test
    fun `local setup reasons have bounded existing telemetry codes and distinct curated resources`() {
        assertEquals(setOf("permission", "configuration"), DesktopVpnSetupFailure.entries.map(::desktopVpnSetupMetric).toSet())
        assertEquals(DesktopVpnSetupFailure.entries.size, DesktopVpnSetupFailure.entries.map { desktopVpnSetupResource(it).key }.toSet().size)
        assertTrue(DesktopVpnSetupFailure.entries.all { desktopVpnSetupResource(it).key.contains("shiftvps_mac_") })
    }
}
