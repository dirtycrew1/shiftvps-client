package fr.husi.repository

import fr.husi.shiftvps.DesktopVpnSetupFailure
import fr.husi.shiftvps.DesktopConnectRequest
import fr.husi.bg.ServiceState
import fr.husi.proto.daemon.ServiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MacVpnBootstrapTest {
    @Test
    fun `first run uses absolute system elevation tool and quoted create-only argv`() {
        val path = "/Applications/Shift VPS.app/Contents/MacOS/husi-core"
        assertEquals(
            listOf("/usr/bin/osascript", "-e", "do shell script \"'/Applications/Shift VPS.app/Contents/MacOS/husi-core' service install --if-absent\" with administrator privileges"),
            macDaemonInstallCommand(path, ifAbsent = true),
        )
        assertEquals(
            listOf("/usr/bin/osascript", "-e", "do shell script \"'/Applications/Shift VPS.app/Contents/MacOS/husi-core' service install\" with administrator privileges"),
            macDaemonInstallCommand(path, ifAbsent = false),
        )
    }

    @Test
    fun `real core active status is protected even when UI still says stopped`() {
        for (reported in listOf(ServiceStatus.Type.STARTED, ServiceStatus.Type.STARTING, ServiceStatus.Type.STOPPING)) {
            assertTrue(protectExistingDesktopService(ServiceState.Stopped, reported))
        }
        for (local in listOf(ServiceState.Connected, ServiceState.Connecting, ServiceState.Stopping)) {
            assertTrue(protectExistingDesktopService(local, ServiceStatus.Type.IDLE))
        }
        assertFalse(protectExistingDesktopService(ServiceState.Stopped, ServiceStatus.Type.IDLE))
        assertFalse(protectExistingDesktopService(ServiceState.Idle, null))
    }

    private class Fixture {
        var active = false
        var current = true
        var host = CoreHostState()
        var installResult: DaemonInstallResult = DaemonInstallResult.Success
        var installs = 0
        var installation = MacDaemonInstallation.Absent
        var attaches = 0
        var starts = 0
        var onInstall: suspend () -> DaemonInstallResult = {
            if (installResult == DaemonInstallResult.Success) host = CoreHostState(isDaemon = true)
            installResult
        }
        var onAttach: suspend () -> CoreHostState = { host }
        val bootstrap = MacVpnBootstrap(
            isServiceActive = { active },
            attachDaemon = { attaches++; onAttach() },
            installation = { installation },
            install = { installs++; onInstall() },
            startService = { starts++ },
        )
        suspend fun connect() = bootstrap.connect { current }
    }

    @Test
    fun `relogin while old administrator dialog is open clears busy new request without touching later intent`() = runTest {
        val prompted = CompletableDeferred<Unit>()
        val approve = CompletableDeferred<Unit>()
        val fixture = Fixture().apply {
            onInstall = {
                prompted.complete(Unit)
                approve.await()
                host = CoreHostState(isDaemon = true)
                DaemonInstallResult.Success
            }
        }
        var identity = 1
        var intent = Any()
        var pending = true
        var completions = 0
        fun capture(): DesktopConnectRequest {
            val owner = identity
            val id = intent
            return DesktopConnectRequest({ identity == owner && intent === id && pending }, { true }) {
                pending = false
                completions++
            }
        }
        val original = capture()
        val first = async {
            fixture.bootstrap.connect(original::isCurrent).also { it.completeRequest(original) }
        }
        prompted.await()
        identity = 2
        intent = Any()
        pending = true
        val relogged = capture()
        val busy = fixture.bootstrap.connect(relogged::isCurrent)
        assertEquals(MacVpnBootstrapResult.Busy, busy)
        busy.completeRequest(relogged)
        assertFalse(pending)
        assertEquals(1, completions)
        intent = Any()
        pending = true
        approve.complete(Unit)
        assertEquals(MacVpnBootstrapResult.Abandoned, first.await())
        assertTrue(pending)
        assertEquals(1, completions)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `unavailable existing daemon control cannot trigger installation or start`() = runTest {
        val fixture = Fixture().apply { onAttach = { throw java.io.IOException("temporarily unavailable") } }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.DaemonUnavailable), fixture.connect())
        assertEquals(0, fixture.installs)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `unknown or stopped existing installation never invokes elevation`() = runTest {
        val fixture = Fixture().apply { installation = MacDaemonInstallation.PresentOrUnknown }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.DaemonUnavailable), fixture.connect())
        assertEquals(0, fixture.installs)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `daemon appearing during administrator dialog is never repaired or started`() = runTest {
        val fixture = Fixture().apply {
            onInstall = {
                active = true
                DaemonInstallResult.Failed("create-only installer refused an existing file")
            }
        }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.DaemonUnavailable), fixture.connect())
        assertEquals(1, fixture.installs)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `fresh Mac asks once attaches daemon then starts TUN`() = runTest {
        val fixture = Fixture()
        assertEquals(MacVpnBootstrapResult.Started, fixture.connect())
        assertEquals(1, fixture.installs)
        assertEquals(2, fixture.attaches)
        assertEquals(1, fixture.starts)
    }

    @Test
    fun `existing daemon starts without privilege prompt`() = runTest {
        val fixture = Fixture().apply { host = CoreHostState(isDaemon = true) }
        assertEquals(MacVpnBootstrapResult.Started, fixture.connect())
        assertEquals(0, fixture.installs)
        assertEquals(1, fixture.starts)
    }

    @Test
    fun `cancel is typed and a new user action can retry`() = runTest {
        val fixture = Fixture().apply { installResult = DaemonInstallResult.Cancelled }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.PermissionCancelled), fixture.connect())
        assertEquals(0, fixture.starts)
        fixture.installResult = DaemonInstallResult.Success
        assertEquals(MacVpnBootstrapResult.Started, fixture.connect())
        assertEquals(2, fixture.installs)
        assertEquals(1, fixture.starts)
    }

    @Test
    fun `elevation unavailable and failed output never become server errors or raw text`() = runTest {
        for ((outcome, reason) in listOf(
            DaemonInstallResult.ElevationUnavailable("osascript") to DesktopVpnSetupFailure.ElevationUnavailable,
            DaemonInstallResult.Failed("fixture raw sensitive output") to DesktopVpnSetupFailure.DaemonUnavailable,
        )) {
            val fixture = Fixture().apply { installResult = outcome }
            val result = fixture.connect()
            assertEquals(MacVpnBootstrapResult.Failed(reason), result)
            assertFalse(result.toString().contains("sensitive"))
            assertEquals(0, fixture.starts)
            assertEquals(1, fixture.installs)
        }
    }

    @Test
    fun `install acknowledgement alone does not prove daemon ready`() = runTest {
        val fixture = Fixture().apply { onInstall = { DaemonInstallResult.Success } }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.DaemonUnavailable), fixture.connect())
        assertEquals(1, fixture.installs)
        assertEquals(21, fixture.attaches)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `daemon can become ready on a later bounded probe`() = runTest {
        val fixture = Fixture().apply {
            onInstall = { DaemonInstallResult.Success }
            onAttach = { CoreHostState(isDaemon = attaches >= 4) }
        }
        assertEquals(MacVpnBootstrapResult.Started, fixture.connect())
        assertEquals(4, fixture.attaches)
        assertEquals(1, fixture.installs)
        assertEquals(1, fixture.starts)
    }

    @Test
    fun `stalled daemon probe is bounded and does not run installer`() = runTest {
        val fixture = Fixture().apply { onAttach = { awaitCancellation() } }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.DaemonUnavailable), fixture.connect())
        assertEquals(0, fixture.installs)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `parallel manual clicks produce one administrator prompt`() = runTest {
        val accepted = CompletableDeferred<DaemonInstallResult>()
        val fixture = Fixture().apply { onInstall = { accepted.await().also { host = CoreHostState(isDaemon = true) } } }
        val first = async { fixture.connect() }
        runCurrent()
        assertEquals(MacVpnBootstrapResult.Busy, fixture.connect())
        assertEquals(1, fixture.installs)
        accepted.complete(DaemonInstallResult.Success)
        assertEquals(MacVpnBootstrapResult.Started, first.await())
        assertEquals(1, fixture.starts)
    }

    @Test
    fun `already active connection is never detached or restarted`() = runTest {
        val fixture = Fixture().apply { active = true }
        assertEquals(MacVpnBootstrapResult.AlreadyActive, fixture.connect())
        assertEquals(0, fixture.attaches)
        assertEquals(0, fixture.installs)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `connection becoming active during dialog is left alone`() = runTest {
        val fixture = Fixture().apply { onInstall = { active = true; DaemonInstallResult.Success } }
        assertEquals(MacVpnBootstrapResult.AlreadyActive, fixture.connect())
        assertEquals(1, fixture.attaches)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `signed out request never opens privilege dialog`() = runTest {
        val fixture = Fixture().apply { current = false }
        assertEquals(MacVpnBootstrapResult.Abandoned, fixture.connect())
        assertEquals(0, fixture.attaches)
        assertEquals(0, fixture.installs)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `signout during administrator dialog prevents any TUN start`() = runTest {
        val fixture = Fixture().apply { onInstall = { current = false; DaemonInstallResult.Success } }
        assertEquals(MacVpnBootstrapResult.Abandoned, fixture.connect())
        assertEquals(1, fixture.attaches)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `foreign daemon owner is not taken over or reinstalled`() = runTest {
        val fixture = Fixture().apply { host = CoreHostState(isDaemon = true, foreignOwner = DaemonOwner("fixture", "other")) }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.DaemonConflict), fixture.connect())
        assertEquals(0, fixture.installs)
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `installer exception is sanitized and retry lock is released`() = runTest {
        val fixture = Fixture().apply { onInstall = { error("fixture private diagnostic") } }
        assertEquals(MacVpnBootstrapResult.Failed(DesktopVpnSetupFailure.DaemonUnavailable), fixture.connect())
        fixture.onInstall = { fixture.host = CoreHostState(isDaemon = true); DaemonInstallResult.Success }
        assertEquals(MacVpnBootstrapResult.Started, fixture.connect())
    }

    @Test
    fun `cancellation propagates without leaving bootstrap busy`() = runTest {
        val fixture = Fixture().apply { onInstall = { throw CancellationException("fixture cancelled") } }
        assertFailsWith<CancellationException> { fixture.connect() }
        fixture.onInstall = { fixture.host = CoreHostState(isDaemon = true); DaemonInstallResult.Success }
        assertEquals(MacVpnBootstrapResult.Started, fixture.connect())
    }
}
