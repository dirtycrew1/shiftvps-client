package fr.husi.shiftvps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShiftVpsExecutionLeaseTest {
    @Test
    fun `VPN and update reservations exclude each other including aliases and repeated close`() {
        withDirectory { directory ->
            val vpn = assertNotNull(ShiftVpsExecutionLease.tryAcquire(directory))
            try {
                assertNull(ShiftVpsExecutionLease.tryAcquire(File(directory, ".")))
            } finally { vpn.close() }
            assertNotNull(ShiftVpsExecutionLease.tryAcquire(directory)).use {
                vpn.close()
                assertNull(ShiftVpsExecutionLease.tryAcquire(directory))
            }
            assertNotNull(ShiftVpsExecutionLease.tryAcquire(directory)).close()
        }
    }

    @Test
    fun `failure and cancellation release the reservation before another session can start`() = runTest {
        val directory = Files.createTempDirectory("shiftvps-lease-test-").toFile()
        try {
            assertFailsWith<IOException> {
                assertNotNull(ShiftVpsExecutionLease.tryAcquire(directory)).use { throw IOException("fixture failure") }
            }
            val entered = CompletableDeferred<Unit>()
            val installer = launch {
                assertNotNull(ShiftVpsExecutionLease.tryAcquire(directory)).use {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            assertNull(ShiftVpsExecutionLease.tryAcquire(directory))
            installer.cancelAndJoin()
            assertNotNull(ShiftVpsExecutionLease.tryAcquire(directory)).close()
        } finally { cleanDirectory(directory) }
    }

    @Test
    fun `a separate JVM sees the reservation and can acquire only after its owner releases it`() {
        withDirectory { directory ->
            val probe = File(directory, "LeaseProbe.java").apply {
                writeText("""
                    import java.io.RandomAccessFile;
                    import java.nio.channels.FileLock;
                    public class LeaseProbe {
                        public static void main(String[] args) throws Exception {
                            try (RandomAccessFile file = new RandomAccessFile(args[0], "rw");
                                 FileLock lock = file.getChannel().tryLock()) {
                                System.out.print(lock == null ? "busy" : "free");
                            }
                        }
                    }
                """.trimIndent())
            }
            assertNotNull(ShiftVpsExecutionLease.tryAcquire(directory)).use {
                assertEquals("busy", probe(probe, directory))
            }
            assertEquals("free", probe(probe, directory))
        }
    }

    private fun probe(source: File, directory: File): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), executable)
        val process = ProcessBuilder(java.absolutePath, source.absolutePath,
            File(directory, "shiftvps-execution.lock").absolutePath).directory(directory).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The isolated lock probe timed out")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.exitValue(), output)
            return output
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun withDirectory(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("shiftvps-lease-test-").toFile()
        try { action(directory) } finally { cleanDirectory(directory) }
    }

    private fun cleanDirectory(directory: File) {
        directory.listFiles()?.forEach { require(it.isFile); it.delete() }
        directory.delete()
    }
}
