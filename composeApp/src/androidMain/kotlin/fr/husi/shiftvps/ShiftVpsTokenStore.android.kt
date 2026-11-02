package fr.husi.shiftvps

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fr.husi.repository.resolveAndroidRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun platformShiftVpsTokenStore(): ShiftVpsTokenStore = AndroidShiftVpsTokenStore()

private class AndroidShiftVpsTokenStore : ShiftVpsTokenStore {
    private val tokenFile: File
        get() = resolveAndroidRepository().noBackupFilesDir.resolve("shiftvps-session.bin")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (!tokenFile.exists()) return@withContext null
        require(tokenFile.length() in MIN_TOKEN_FILE_BYTES.toLong()..MAX_TOKEN_FILE_BYTES.toLong()) { "Invalid saved session" }
        val bytes = tokenFile.readBytes()
        require(bytes.size in MIN_TOKEN_FILE_BYTES..MAX_TOKEN_FILE_BYTES) { "Invalid saved session" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        val plaintext = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        try {
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
            bytes.fill(0)
        }
    }

    override suspend fun save(token: String): Unit = withContext(Dispatchers.IO) {
        require(token.length in 32..1024) { "Invalid session token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = token.toByteArray(Charsets.UTF_8)
        val encrypted = try {
            cipher.iv + cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val file = tokenFile
        val pending = File(file.parentFile, "${file.name}.pending")
        try {
            pending.outputStream().use { it.write(encrypted) }
            check(pending.renameTo(file)) { "Could not save session securely" }
        } finally {
            encrypted.fill(0)
            pending.delete()
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        val file = tokenFile
        check(!file.exists() || file.delete()) { "Could not remove saved session" }
    }

    private companion object {
        const val KEY_ALIAS = "ru.shiftvps.client.session.v1"
        const val IV_BYTES = 12
        const val MIN_TOKEN_FILE_BYTES = IV_BYTES + 16 + 32
        const val MAX_TOKEN_FILE_BYTES = 4096
    }
}
