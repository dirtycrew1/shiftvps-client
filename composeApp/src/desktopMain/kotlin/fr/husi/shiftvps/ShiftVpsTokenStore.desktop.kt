package fr.husi.shiftvps

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import fr.husi.DesktopPaths
import fr.husi.platform.Platform
import fr.husi.platform.PlatformInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual fun prepareServiceMetrics(owner: String?, policyVersion: Int) = Unit

actual fun platformShiftVpsTokenStore(): ShiftVpsTokenStore = when (PlatformInfo.platform) {
    Platform.Windows -> WindowsShiftVpsTokenStore(DesktopPaths.dataDir.resolve("client-session.dpapi"))
    Platform.MacOs -> MacShiftVpsTokenStore()
    else -> error("Secure account storage is unavailable on this platform")
}

internal class WindowsShiftVpsTokenStore(private val file: File) : ShiftVpsTokenStore {
    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        require(file.length() in 1..MAX_ENCRYPTED_BYTES) { "Invalid saved session" }
        val decrypted = WindowsSessionProtection.unprotect(file.readBytes())
        try {
            decrypted.toString(Charsets.UTF_8)
        } finally {
            decrypted.fill(0)
        }
    }

    override suspend fun save(token: String): Unit = withContext(Dispatchers.IO) {
        require(token.length in 32..1024) { "Invalid session token" }
        val plaintext = token.toByteArray(Charsets.UTF_8)
        val encrypted = try {
            WindowsSessionProtection.protect(plaintext)
        } finally {
            plaintext.fill(0)
        }
        file.parentFile.mkdirs()
        val pending = File(file.parentFile, "${file.name}.pending")
        try {
            pending.outputStream().use { it.write(encrypted) }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            encrypted.fill(0)
            pending.delete()
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        check(!file.exists() || file.delete()) { "Could not remove saved session" }
    }

    private companion object {
        const val MAX_ENCRYPTED_BYTES = 16384L
    }
}

internal object WindowsSessionProtection {
    @Structure.FieldOrder("size", "data")
    class DataBlob : Structure() {
        @JvmField var size: Int = 0
        @JvmField var data: Pointer? = null
    }

    private interface Crypt32 : StdCallLibrary {
        fun CryptProtectData(
            input: DataBlob, description: WString?, entropy: Pointer?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, output: DataBlob,
        ): Boolean

        fun CryptUnprotectData(
            input: DataBlob, description: Pointer?, entropy: Pointer?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, output: DataBlob,
        ): Boolean
    }

    private interface Kernel32 : StdCallLibrary {
        fun LocalFree(pointer: Pointer): Pointer?
    }

    private val systemDirectory: File
        get() = File(requireNotNull(System.getenv("SystemRoot"))).resolve("System32")
    private val crypt32 by lazy {
        Native.load(systemDirectory.resolve("crypt32.dll").absolutePath, Crypt32::class.java)
    }
    private val kernel32 by lazy {
        Native.load(systemDirectory.resolve("kernel32.dll").absolutePath, Kernel32::class.java)
    }

    fun protect(bytes: ByteArray): ByteArray = transform(bytes, true)
    fun unprotect(bytes: ByteArray): ByteArray = transform(bytes, false)

    private fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        require(bytes.isNotEmpty())
        return Memory(bytes.size.toLong()).use { memory ->
            try {
                memory.write(0, bytes, 0, bytes.size)
                val input = DataBlob().also { it.size = bytes.size; it.data = memory }
                val output = DataBlob()
                val succeeded = if (encrypt) {
                    crypt32.CryptProtectData(input, null, null, null, null, NO_UI, output)
                } else {
                    crypt32.CryptUnprotectData(input, null, null, null, null, NO_UI, output)
                }
                check(succeeded) { "Windows could not access the saved session" }
                val pointer = checkNotNull(output.data)
                try {
                    require(output.size in 1..16384) { "Invalid protected session size" }
                    pointer.getByteArray(0, output.size)
                } finally {
                    if (output.size in 1..16384) pointer.clear(output.size.toLong())
                    kernel32.LocalFree(pointer)
                }
            } finally {
                memory.clear()
            }
        }
    }

    private const val NO_UI = 1
}

private class MacShiftVpsTokenStore : ShiftVpsTokenStore {
    private interface Security : Library {
        fun SecKeychainFindGenericPassword(
            keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, passwordLength: IntByReference?,
            password: PointerByReference?, item: PointerByReference?,
        ): Int
        fun SecKeychainAddGenericPassword(
            keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, passwordLength: Int,
            password: ByteArray, item: PointerByReference?,
        ): Int
        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemDelete(item: Pointer): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer): Int
    }

    private interface CoreFoundation : Library {
        fun CFRelease(reference: Pointer)
    }

    private val security by lazy {
        Native.load("/System/Library/Frameworks/Security.framework/Security", Security::class.java)
    }
    private val foundation by lazy {
        Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation::class.java)
    }
    private val service = "ru.shiftvps.client.session.v1".toByteArray(Charsets.UTF_8)
    private val account = "account".toByteArray(Charsets.UTF_8)

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null, service.size, service, account.size, account, length, data, null,
        )
        if (status == ITEM_NOT_FOUND) return@withContext null
        check(status == SUCCESS) { "macOS Keychain is unavailable ($status)" }
        val pointer = checkNotNull(data.value)
        try {
            require(length.value in 32..4096) { "Invalid saved session" }
            val plaintext = pointer.getByteArray(0, length.value)
            try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
        } finally {
            security.SecKeychainItemFreeContent(null, pointer)
        }
    }

    override suspend fun save(token: String): Unit = withContext(Dispatchers.IO) {
        require(token.length in 32..1024) { "Invalid session token" }
        val plaintext = token.toByteArray(Charsets.UTF_8)
        try {
            val item = PointerByReference()
            val found = security.SecKeychainFindGenericPassword(
                null, service.size, service, account.size, account, null, null, item,
            )
            val status = when (found) {
                SUCCESS -> {
                    val reference = checkNotNull(item.value)
                    try {
                        security.SecKeychainItemModifyAttributesAndData(reference, null, plaintext.size, plaintext)
                    } finally {
                        foundation.CFRelease(reference)
                    }
                }
                ITEM_NOT_FOUND -> security.SecKeychainAddGenericPassword(
                    null, service.size, service, account.size, account, plaintext.size, plaintext, null,
                )
                else -> error("macOS Keychain is unavailable ($found)")
            }
            check(status == SUCCESS) { "Could not save session in macOS Keychain ($status)" }
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null, service.size, service, account.size, account, null, null, item,
        )
        if (status == ITEM_NOT_FOUND) return@withContext
        check(status == SUCCESS) { "macOS Keychain is unavailable ($status)" }
        val reference = checkNotNull(item.value)
        try {
            check(security.SecKeychainItemDelete(reference) == SUCCESS) { "Could not remove saved session" }
        } finally {
            foundation.CFRelease(reference)
        }
    }

    private companion object {
        const val SUCCESS = 0
        const val ITEM_NOT_FOUND = -25300
    }
}
