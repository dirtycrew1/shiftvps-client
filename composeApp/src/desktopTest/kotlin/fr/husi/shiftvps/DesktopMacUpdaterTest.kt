package fr.husi.shiftvps

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMacUpdaterTest {
    @Test
    fun `architecture selects a fixed HTTPS feed and rejects unsupported targets`() {
        assertEquals(macUpdaterFeed("arm64"), macUpdaterFeed("aarch64"))
        assertEquals(macUpdaterFeed("amd64"), macUpdaterFeed("x86_64"))
        assertTrue(assertNotNull(macUpdaterFeed("arm64")).endsWith("/macos-arm64.xml"))
        assertTrue(assertNotNull(macUpdaterFeed("amd64")).endsWith("/macos-amd64.xml"))
        assertNull(macUpdaterFeed("x86"))
        assertNull(macUpdaterFeed(""))
    }

    @Test
    fun `automatic checks always defer installation and do not request elevated access`() {
        val bundle = File("Shift VPS ; echo unsafe.app").absoluteFile
        val command = macUpdaterCommand(bundle, assertNotNull(macUpdaterFeed("arm64")), false)
        assertEquals(bundle.absolutePath, command[1])
        assertEquals(bundle.absolutePath, command[command.indexOf("--application") + 1])
        assertTrue("--defer-install" in command)
        assertTrue("--grant-automatic-checks" in command)
        assertFalse("--interactive" in command)
        assertFalse("--check-immediately" in command)
        assertFalse("--send-profile" in command)
    }

    @Test
    fun `manual checks can request authorization but still cannot force an app restart`() {
        val command = macUpdaterCommand(File("ShiftVPS.app"), assertNotNull(macUpdaterFeed("amd64")), true)
        assertTrue("--check-immediately" in command)
        assertTrue("--interactive" in command)
        assertTrue("--defer-install" in command)
        assertFalse("--send-profile" in command)
    }

    @Test
    fun `arbitrary remote feeds and non HTTPS feeds are refused`() {
        for (feed in listOf("https://example.com/update.xml", "http://example.com/update.xml", "file:///tmp/update.xml")) {
            assertFailsWith<IllegalArgumentException> {
                macUpdaterCommand(File("ShiftVPS.app"), feed, false)
            }
        }
    }

    @Test
    fun `only a packaged app with its executable updater is accepted`() {
        val directory = createTempDirectory("shiftvps-updater-test").toFile()
        try {
            val bundle = directory.resolve("Shift VPS.app")
            val launcher = bundle.resolve("Contents/MacOS/ru.shiftvps.client")
            val helper = bundle.resolve("Contents/Helpers/ShiftVPSUpdater.app/Contents/MacOS/ShiftVPSUpdater")
            for (executable in listOf(launcher, helper)) {
                executable.parentFile.mkdirs()
                executable.writeText("test executable")
                executable.setExecutable(true)
            }
            bundle.resolve("Contents/Info.plist").writeText("test metadata")
            assertEquals(bundle.canonicalFile, macUpdaterApplicationBundle(launcher))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `updater does not initialize for development folders or incomplete app bundles`() {
        val directory = createTempDirectory("shiftvps-updater-test").toFile()
        try {
            val launcher = directory.resolve("Contents/MacOS/ru.shiftvps.client")
            launcher.parentFile.mkdirs()
            launcher.writeText("test executable")
            launcher.setExecutable(true)
            assertNull(macUpdaterApplicationBundle(launcher))
            assertNull(macUpdaterApplicationBundle(null))
            assertNull(macUpdaterApplicationBundle(directory.resolve("missing")))
            val incompleteBundle = directory.resolve("Shift VPS.app")
            val packagedLauncher = incompleteBundle.resolve("Contents/MacOS/ru.shiftvps.client")
            packagedLauncher.parentFile.mkdirs()
            packagedLauncher.writeText("test executable")
            packagedLauncher.setExecutable(true)
            incompleteBundle.resolve("Contents/Info.plist").writeText("test metadata")
            assertNull(macUpdaterApplicationBundle(packagedLauncher))
        } finally {
            directory.deleteRecursively()
        }
    }
}
