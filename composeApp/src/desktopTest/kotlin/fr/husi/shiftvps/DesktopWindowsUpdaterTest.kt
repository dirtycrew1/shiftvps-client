package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWindowsUpdaterTest {
    @Test
    fun `updates cannot close a connecting connected or stopping VPN`() {
        assertFalse(windowsUpdaterCanShutdown(ServiceState.Connecting))
        assertFalse(windowsUpdaterCanShutdown(ServiceState.Connected))
        assertFalse(windowsUpdaterCanShutdown(ServiceState.Stopping))
        assertTrue(windowsUpdaterCanShutdown(ServiceState.Stopped))
        assertTrue(windowsUpdaterCanShutdown(ServiceState.Idle))
    }

    @Test
    fun `portable and development applications cannot replace an installed copy`() {
        val root = Files.createTempDirectory("shiftvps-updater-test-").toFile()
        try {
            val jar = root.resolve("app").also { it.mkdir() }.resolve("ru.shiftvps.client.jar").also { it.writeText("") }
            root.resolve("ShiftVPS.exe").writeText("")
            assertNull(windowsUpdateInstallationRoot(jar))
            root.resolve("uninstall.exe").writeText("")
            assertEquals(root, windowsUpdateInstallationRoot(jar))
            assertNull(windowsUpdateInstallationRoot(root))
            assertNull(windowsUpdateInstallationRoot(root.resolve("other.jar").also { it.writeText("") }))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `verified installer receives only fixed update arguments and the local PID`() {
        val root = Files.createTempDirectory("shiftvps-updater-test-").toFile()
        try {
            val installer = root.resolve("обновление с пробелом.exe").also { it.writeText("") }
            assertEquals(
                listOf(installer.absolutePath, "/S", "/UPDATE", "/UPDATEPID=1234"),
                windowsUpdateInstallerCommand(installer, 1234),
            )
            assertFailsWith<IllegalArgumentException> { windowsUpdateInstallerCommand(installer, 0) }
            assertFailsWith<IllegalArgumentException> { windowsUpdateInstallerCommand(root.resolve("missing.exe"), 1234) }
            assertFailsWith<IllegalArgumentException> { windowsUpdateInstallerCommand(root.resolve("update.zip").also { it.writeText("") }, 1234) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `native updater accepts only the supported architecture pins`() {
        assertEquals(windowsUpdaterLibraryHash("amd64"), windowsUpdaterLibraryHash("x86_64"))
        assertEquals(windowsUpdaterLibraryHash("arm64"), windowsUpdaterLibraryHash("aarch64"))
        assertNotEquals(windowsUpdaterLibraryHash("amd64"), windowsUpdaterLibraryHash("arm64"))
        assertEquals(64, windowsUpdaterLibraryHash("amd64")?.length)
        assertNull(windowsUpdaterLibraryHash("x86"))
    }
}
