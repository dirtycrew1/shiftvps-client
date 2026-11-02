package fr.husi.shiftvps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import kotlin.coroutines.coroutineContext

internal const val MAX_UPDATE_MANIFEST_BYTES = 64 * 1024
private const val MAX_APK_BYTES = 512L * 1024 * 1024
private const val DOWNLOAD_TIMEOUT_NANOS = 20L * 60 * 1_000_000_000
private const val MANIFEST_TIMEOUT_NANOS = 45L * 1_000_000_000
private val updateJson = Json { ignoreUnknownKeys = false }
private val androidUpdateAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

@Serializable
internal data class SignedUpdateEnvelope(val payload: String, val signature: String)

@Serializable
internal data class AndroidUpdateManifest(
    val schema: Int,
    val channel: String,
    @SerialName("version_code") val versionCode: Int,
    val version: String,
    val artifacts: Map<String, AndroidUpdateArtifact>,
)

@Serializable
internal data class AndroidUpdateArtifact(val url: String, val sha256: String, val size: Long)

internal data class VerifiedAndroidUpdate(
    val version: String,
    val versionCode: Int,
    val abi: String,
    val artifact: AndroidUpdateArtifact,
)

/** The signature covers the exact decoded payload bytes, before parsing or trusting any fields. */
internal class SignedAndroidUpdateVerifier(publicKeyBase64: String) {
    private val publicKey = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(requireNotNull(publicKeyBase64.decodeBase64()).toByteArray()))
        .also { require((it as RSAPublicKey).modulus.bitLength() >= 2048) }

    fun verify(
        envelopeText: String,
        installedVersionCode: Long,
        supportedAbis: List<String>,
        highestSeenVersionCode: Long = installedVersionCode,
    ): VerifiedAndroidUpdate? {
        require(envelopeText.toByteArray(Charsets.UTF_8).size <= MAX_UPDATE_MANIFEST_BYTES)
        val envelope = updateJson.decodeFromString<SignedUpdateEnvelope>(envelopeText)
        val payload = requireNotNull(envelope.payload.decodeBase64()).toByteArray()
        val signature = requireNotNull(envelope.signature.decodeBase64()).toByteArray()
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(payload)
            verify(signature)
        }) { "Invalid update signature" }
        val manifest = updateJson.decodeFromString<AndroidUpdateManifest>(payload.toString(Charsets.UTF_8))
        require(manifest.schema == 1 && manifest.channel == "stable")
        require(manifest.versionCode in 1..2_100_000_000)
        require(manifest.version.length <= 64 && manifest.version.matches(
            Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"),
        ))
        require(manifest.artifacts.isNotEmpty() && manifest.artifacts.size <= androidUpdateAbis.size)
        manifest.artifacts.forEach { (name, artifact) ->
            require(name.removePrefix("android-") in androidUpdateAbis && name.startsWith("android-"))
            require(artifact.size in 1..MAX_APK_BYTES)
            require(artifact.sha256.matches(Regex("[a-f0-9]{64}")))
            requireUpdateArtifactUrl(artifact.url)
        }
        require(manifest.versionCode.toLong() >= highestSeenVersionCode) { "Stale update metadata" }
        if (manifest.versionCode <= installedVersionCode) return null
        val abi = supportedAbis.firstOrNull { manifest.artifacts.containsKey("android-$it") }
            ?: throw IllegalArgumentException("No compatible update artifact")
        return VerifiedAndroidUpdate(manifest.version, manifest.versionCode, abi,
            manifest.artifacts.getValue("android-$abi"))
    }
}

internal data class AndroidPackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

internal fun requireOwnUpdateApk(
    archive: AndroidPackageIdentity,
    installed: AndroidPackageIdentity,
    expectedVersionCode: Int,
) {
    require(archive.packageName == installed.packageName) { "Unexpected update package" }
    require(archive.versionCode == expectedVersionCode.toLong() && archive.versionCode > installed.versionCode)
    require(installed.signerSha256.isNotEmpty() && archive.signerSha256 == installed.signerSha256) {
        "Unexpected update signing certificate"
    }
}

private fun updateUri(url: String): URI = URI(url).also {
    require(it.scheme.equals("https", ignoreCase = true) && it.host != null)
    require(it.userInfo == null && it.fragment == null && it.port in listOf(-1, 443))
    require(it.path.split('/').none { part -> part == "." || part == ".." })
}

internal fun requireUpdateManifestUrl(url: String) {
    val uri = updateUri(url)
    require(uri.query == null)
    require(when (uri.host.lowercase()) {
        "raw.githubusercontent.com" -> uri.path.startsWith("/dirtycrew1/shiftvps-client/updates/")
        "shiftvps.ru", "web-backup.shiftvps.ru" -> uri.path.startsWith("/downloads/updates/")
        else -> false
    }) { "Unexpected update manifest origin" }
}

internal fun requireUpdateArtifactUrl(url: String, isRedirect: Boolean = false) {
    val uri = updateUri(url)
    require(when (uri.host.lowercase()) {
        "github.com" -> uri.query == null && uri.path.startsWith("/dirtycrew1/shiftvps-client/releases/download/")
        "shiftvps.ru", "web-backup.shiftvps.ru" -> uri.query == null && uri.path.startsWith("/downloads/")
        "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "github-releases.githubusercontent.com" -> isRedirect
        else -> false
    }) { "Unexpected update artifact origin" }
}

internal fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/** Public update downloads never attach account tokens or inherit the subscription transport. */
internal class SignedUpdateHttp(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    suspend fun manifest(url: String): String = withContext(Dispatchers.IO) {
        requireUpdateManifestUrl(url)
        val connection = open(url, artifact = false)
        try {
            val output = ByteArrayOutputStream()
            copy(connection, MAX_UPDATE_MANIFEST_BYTES.toLong(), MANIFEST_TIMEOUT_NANOS) { bytes, count ->
                output.write(bytes, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        update: VerifiedAndroidUpdate,
        directory: File,
        progress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        requireUpdateArtifactUrl(update.artifact.url)
        require(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "shiftvps-${update.versionCode}-${update.abi}.apk")
        if (target.isFile && target.length() == update.artifact.size && sha256(target) == update.artifact.sha256) {
            progress(100)
            return@withContext target
        }
        val connection = open(update.artifact.url, artifact = true)
        val partial = try {
            File.createTempFile("download-", ".part", directory)
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
        try {
            val declaredSize = connection.contentLengthLong
            require(declaredSize < 0 || declaredSize == update.artifact.size) { "Unexpected APK length" }
            var copied = 0L
            partial.outputStream().use { output ->
                copy(connection, update.artifact.size, DOWNLOAD_TIMEOUT_NANOS) { bytes, count ->
                    output.write(bytes, 0, count)
                    copied += count
                    progress((copied * 100 / update.artifact.size).toInt().coerceIn(0, 100))
                }
                output.fd.sync()
            }
            require(partial.length() == update.artifact.size && sha256(partial) == update.artifact.sha256) {
                "Invalid APK digest"
            }
            require(!target.exists() || target.delete())
            require(partial.renameTo(target)) { "Cannot retain verified APK" }
            directory.listFiles()?.filter {
                it != target && it.isFile && it.name.startsWith("shiftvps-") && it.extension == "apk"
            }?.forEach { it.delete() }
            target
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun open(url: String, artifact: Boolean): HttpURLConnection {
        var current = URI(url)
        repeat(4) { attempt ->
            if (artifact) requireUpdateArtifactUrl(current.toString(), isRedirect = attempt > 0)
            else requireUpdateManifestUrl(current.toString())
            val connection = openConnection(current)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "ShiftVPS-Update/1")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Accept-Encoding", "identity")
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection
                    301, 302, 303, 307, 308 -> {
                        require(attempt < 3) { "Too many update redirects" }
                        current = current.resolve(requireNotNull(connection.getHeaderField("Location")))
                        connection.disconnect()
                    }
                    else -> throw IOException("Update request failed")
                }
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
        }
        throw IOException("Update request failed")
    }

    private suspend fun copy(
        connection: HttpURLConnection,
        limit: Long,
        timeoutNanos: Long,
        write: (ByteArray, Int) -> Unit,
    ) {
        val started = System.nanoTime()
        var total = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                if (System.nanoTime() - started > timeoutNanos) throw IOException("Update transfer timed out")
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Update response too large" }
                write(buffer, count)
            }
        }
    }
}
