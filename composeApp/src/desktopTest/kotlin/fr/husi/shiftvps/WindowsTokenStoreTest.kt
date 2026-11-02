package fr.husi.shiftvps

import fr.husi.platform.Platform
import fr.husi.platform.PlatformInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsTokenStoreTest {
    @Test
    fun `DPAPI protects token at rest replaces it and rejects corrupted storage`() = runBlocking {
        if (PlatformInfo.platform != Platform.Windows) return@runBlocking
        val directory = Files.createTempDirectory("shiftvps-dpapi-test-").toFile()
        val file = directory.resolve("session.dpapi")
        val store = WindowsShiftVpsTokenStore(file)
        val fixture = "non-production-session-fixture-".repeat(3)
        try {
            assertNull(store.load())
            store.save(fixture)
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(fixture))
            assertEquals(fixture, store.load())
            store.save(fixture.reversed())
            assertEquals(fixture.reversed(), store.load())
            val corrupt = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupt)
            assertNotNull(runCatching { store.load() }.exceptionOrNull())
            store.clear()
            assertNull(store.load())
        } finally {
            directory.resolve("session.dpapi.pending").delete()
            file.delete()
            assertTrue(directory.delete())
        }
    }
}
