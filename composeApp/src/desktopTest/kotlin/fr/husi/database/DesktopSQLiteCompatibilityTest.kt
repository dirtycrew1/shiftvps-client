package fr.husi.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopSQLiteCompatibilityTest {
    @field:TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `packaged driver includes the exact host native library`() {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val arm64 = arch in setOf("aarch64", "arm64")
        val resource = when {
            os.contains("mac") -> "natives/osx_${if (arm64) "arm64" else "x64"}/libsqliteJni.dylib"
            os.startsWith("windows") -> "natives/windows_x64/sqliteJni.dll"
            else -> "natives/linux_${if (arm64) "arm64" else "x64"}/libsqliteJni.so"
        }
        assertNotNull(BundledSQLiteDriver::class.java.classLoader.getResource(resource))
    }

    @Test
    fun `official driver reads values and rolls back a transaction`() {
        BundledSQLiteDriver().open(":memory:").use { connection ->
            connection.execSQL("CREATE TABLE smoke (id INTEGER PRIMARY KEY, label TEXT, payload BLOB)")
            connection.prepare("INSERT INTO smoke (id, label, payload) VALUES (?, ?, ?)").use { statement ->
                statement.bindLong(1, 7)
                statement.bindText(2, "ShiftVPS SQLite smoke")
                statement.bindBlob(3, byteArrayOf(0, 1, 127, -1))
                statement.step()
            }
            connection.execSQL("BEGIN IMMEDIATE")
            connection.execSQL("DELETE FROM smoke")
            connection.execSQL("ROLLBACK")
            connection.prepare("SELECT id, label, payload FROM smoke").use { statement ->
                assertTrue(statement.step())
                assertEquals(7L, statement.getLong(0))
                assertEquals("ShiftVPS SQLite smoke", statement.getText(1))
                assertContentEquals(byteArrayOf(0, 1, 127, -1), statement.getBlob(2))
            }
        }
    }

    @Test
    fun `Room opens its complete schema and persists a managed group`() = runBlocking {
        val file = temporaryDirectory.resolve("profile-smoke.db")
        fun openDatabase() = Room.databaseBuilder<SagerDatabase>(name = file.toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

        val database = openDatabase()
        val id = try {
            database.groupDao().createGroup(ProxyGroup(name = "ShiftVPS managed smoke"))
        } finally {
            database.close()
        }
        val reopened = openDatabase()
        try {
            assertEquals("ShiftVPS managed smoke", reopened.groupDao().getById(id).first()?.name)
        } finally {
            reopened.close()
        }
    }
}
