package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignedAndroidUpdateTest {
    private val artifactUrl = "https://github.com/dirtycrew1/shiftvps-client/releases/download/v0.1.1/shiftvps-0.1.1-arm64-v8a.apk"
    private val apkBytes = "signed fixture artifact".toByteArray()
    private val digest = apkBytes.toByteString().sha256().hex()

    @Test
    fun `only signed newer compatible releases are offered and a replay cannot lower the observed version`() {
        val verifier = SignedAndroidUpdateVerifier(keys.public.encoded.toByteString().base64())
        val envelope = sign(manifest())
        val update = assertNotNull(verifier.verify(envelope, 1, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(2, update.versionCode)
        assertEquals("arm64-v8a", update.abi)
        assertNull(verifier.verify(envelope, 2, listOf("arm64-v8a")))
        assertFailsWith<IllegalArgumentException> { verifier.verify(envelope, 1, listOf("arm64-v8a"), 3) }
        assertFailsWith<IllegalArgumentException> { verifier.verify(envelope, 1, listOf("x86")) }
        val tampered = Json.decodeFromString<SignedUpdateEnvelope>(envelope).copy(
            payload = manifest(versionCode = 3).toByteArray().toByteString().base64(),
        )
        assertFailsWith<IllegalArgumentException> {
            verifier.verify(Json.encodeToString(tampered), 1, listOf("arm64-v8a"))
        }
    }

    @Test
    fun `even signed metadata cannot authorize unexpected origins unbounded packages or invalid hashes`() {
        val verifier = SignedAndroidUpdateVerifier(keys.public.encoded.toByteString().base64())
        for (body in listOf(manifest(url = "https://attacker.invalid/update.apk"),
            manifest(size = 513L * 1024 * 1024), manifest(hash = "invalid"))) {
            assertFailsWith<IllegalArgumentException> { verifier.verify(sign(body), 1, listOf("arm64-v8a")) }
        }
        assertFailsWith<IllegalArgumentException> {
            verifier.verify(" ".repeat(MAX_UPDATE_MANIFEST_BYTES + 1), 1, listOf("arm64-v8a"))
        }
    }

    @Test
    fun `updates refuse cleartext credentials traversal wrong repositories and the protected websites host`() {
        requireUpdateManifestUrl("https://raw.githubusercontent.com/dirtycrew1/shiftvps-client/updates/android.json")
        requireUpdateArtifactUrl(artifactUrl)
        requireUpdateArtifactUrl("https://release-assets.githubusercontent.com/signed-download?token=fixture", true)
        for (url in listOf("http://github.com/dirtycrew1/shiftvps-client/releases/download/v1/a.apk",
            "https://user@github.com/dirtycrew1/shiftvps-client/releases/download/v1/a.apk",
            "https://github.com/attacker/repo/releases/download/v1/a.apk",
            "https://github.com:8443/dirtycrew1/shiftvps-client/releases/download/v1/a.apk",
            "https://shiftvps.ru/downloads/%2e%2e/private.apk", "https://46.182.25.146/downloads/update.apk",
            "https://release-assets.githubusercontent.com/unsigned-initial-url")) {
            assertFailsWith<IllegalArgumentException> { requireUpdateArtifactUrl(url) }
        }
    }

    @Test
    fun `downloads reject redirected foreign origins and never add account credentials`() = runTest {
        var calls = 0
        val connection = FakeConnection(byteArrayOf(), status = 302, location = "https://attacker.invalid/file.apk")
        val http = SignedUpdateHttp { calls++; connection }
        withDirectory { directory ->
            assertFailsWith<IllegalArgumentException> { http.download(update(), directory) {} }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
        assertEquals(1, calls)
        assertFalse(connection.requestProperties.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) })
    }

    @Test
    fun `only a complete hashed file enters the cache and valid cached downloads are reused`() = runTest {
        var calls = 0
        val http = SignedUpdateHttp { calls++; FakeConnection(apkBytes) }
        withDirectory { directory ->
            val target = http.download(update(), directory) {}
            assertEquals(digest, sha256(target))
            assertEquals(target, http.download(update(), directory) {})
            assertEquals(1, calls)
            target.writeBytes("corrupt artifact".toByteArray())
            val badHttp = SignedUpdateHttp { FakeConnection(ByteArray(apkBytes.size + 1), declaredSize = -1) }
            assertFailsWith<IllegalArgumentException> { badHttp.download(update(), directory) {} }
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
        }
    }

    @Test
    fun `the installer accepts only the same package certificate and exact newer signed version`() {
        val installed = AndroidPackageIdentity("ru.shiftvps.client", 1, setOf("release-certificate"))
        val archive = installed.copy(versionCode = 2)
        requireOwnUpdateApk(archive, installed, 2)
        for (invalid in listOf(archive.copy(packageName = "fr.husi"), archive.copy(versionCode = 1),
            archive.copy(versionCode = 3), archive.copy(signerSha256 = setOf("foreign-certificate")),
            archive.copy(signerSha256 = emptySet()))) {
            assertFailsWith<IllegalArgumentException> { requireOwnUpdateApk(invalid, installed, 2) }
        }
        assertTrue(canRestartApplication(ServiceState.Idle))
        assertTrue(canRestartApplication(ServiceState.Stopped))
        for (state in listOf(ServiceState.Connecting, ServiceState.Connected, ServiceState.Stopping)) {
            assertFalse(canRestartApplication(state))
        }
    }

    private fun manifest(versionCode: Int = 2, url: String = artifactUrl, size: Long = apkBytes.size.toLong(),
                         hash: String = digest) =
        """{"schema":1,"channel":"stable","version_code":$versionCode,"version":"0.1.1","artifacts":{"android-arm64-v8a":{"url":"$url","sha256":"$hash","size":$size}}}"""

    private fun update() = VerifiedAndroidUpdate("0.1.1", 2, "arm64-v8a",
        AndroidUpdateArtifact(artifactUrl, digest, apkBytes.size.toLong()))

    private fun sign(payload: String): String {
        val bytes = payload.toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(bytes)
            sign()
        }
        return Json.encodeToString(SignedUpdateEnvelope(bytes.toByteString().base64(), signature.toByteString().base64()))
    }

    private suspend fun withDirectory(action: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("shiftvps-update-test-").toFile()
        try { action(directory) } finally {
            directory.listFiles()?.forEach { require(it.isFile); it.delete() }
            directory.delete()
        }
    }

    private class FakeConnection(
        private val bytes: ByteArray,
        private val status: Int = 200,
        private val location: String? = null,
        private val declaredSize: Long = bytes.size.toLong(),
    ) : HttpURLConnection(URL("https://fixture.invalid/")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getContentLengthLong() = declaredSize
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }

    companion object {
        private val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
}
