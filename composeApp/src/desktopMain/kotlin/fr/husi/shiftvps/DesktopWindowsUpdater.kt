package fr.husi.shiftvps

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import fr.husi.BuildConfig
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.ktx.Logs
import fr.husi.platform.PlatformInfo
import java.awt.EventQueue
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** WinSparkle owns checking, the consent dialog, download and Ed25519 verification. */
internal object DesktopWindowsUpdater {
    private var library: WinSparkleLibrary? = null
    private var installReservation: AutoCloseable? = null
    @Volatile private var shutdownRequested: (() -> Unit)? = null

    // JNA callbacks must remain strongly reachable until win_sparkle_cleanup returns.
    private val canShutdown = WindowsCanShutdownCallback {
        if (windowsUpdaterCanShutdown(BackendState.status.value.state)) 1 else 0
    }
    private val requestShutdown = WindowsUpdaterCallback {
        EventQueue.invokeLater { shutdownRequested?.invoke() }
    }
    private val reportError = WindowsUpdaterCallback {
        Logs.e("Windows updater reported an error")
    }
    private val runInstaller = WindowsRunInstallerCallback { verifiedPath ->
        try {
            check(windowsUpdaterCanShutdown(BackendState.status.value.state)) {
                "Disconnect the VPN before installing an update"
            }
            check(installReservation == null) { "An update is already being installed" }
            installReservation = requireNotNull(ShiftVpsController.reserveApplicationUpdate()) {
                "Wait for the pending connection before installing an update"
            }
            // This callback is reached only after WinSparkle verifies the downloaded EXE.
            // Arguments are local constants; an appcast cannot supply extra commands or paths.
            ProcessBuilder(windowsUpdateInstallerCommand(File(verifiedPath.toString()), ProcessHandle.current().pid()))
                .start()
            1
        } catch (error: Exception) {
            installReservation?.close()
            installReservation = null
            Logs.e("Unable to start the verified Windows update", error)
            -1 // WINSPARKLE_RETURN_ERROR: never fall back to remote installer arguments.
        }
    }

    @Synchronized
    fun initialize(onShutdownRequested: () -> Unit): Boolean {
        if (!PlatformInfo.isWindows) return false
        if (library != null) return true
        return try {
            val source = File(javaClass.protectionDomain.codeSource.location.toURI())
            val root = windowsUpdateInstallationRoot(source) ?: return false
            val dll = root.resolve("WinSparkle.dll")
            val expectedHash = windowsUpdaterLibraryHash(System.getProperty("os.arch")) ?: return false
            require(dll.isFile && sha256(dll) == expectedHash) { "Unrecognized WinSparkle library" }
            require(Base64.getDecoder().decode(BuildConfig.UPDATE_ED25519_PUBLIC_KEY).size == 32) {
                "Invalid Windows update verification key"
            }
            require(BuildConfig.UPDATE_WINDOWS_APPCAST_URL.startsWith("https://")) { "HTTPS appcast required" }
            val native = Native.load(dll.absolutePath, WinSparkleLibrary::class.java)
            native.win_sparkle_set_app_details(WString("ShiftVPS"), WString("ShiftVPS"), WString(BuildConfig.VERSION_NAME))
            native.win_sparkle_set_app_build_version(WString(BuildConfig.VERSION_CODE.toString()))
            native.win_sparkle_set_appcast_url(BuildConfig.UPDATE_WINDOWS_APPCAST_URL)
            check(native.win_sparkle_set_eddsa_public_key(BuildConfig.UPDATE_ED25519_PUBLIC_KEY) == 1) {
                "WinSparkle rejected the update verification key"
            }
            native.win_sparkle_set_can_shutdown_callback(canShutdown)
            native.win_sparkle_set_shutdown_request_callback(requestShutdown)
            native.win_sparkle_set_user_run_installer_callback(runInstaller)
            native.win_sparkle_set_error_callback(reportError)
            native.win_sparkle_set_update_check_interval(24 * 60 * 60)
            native.win_sparkle_set_automatic_check_for_updates(1)
            shutdownRequested = onShutdownRequested
            native.win_sparkle_init()
            library = native
            true
        } catch (error: Exception) {
            Logs.e("Windows updater is unavailable", error)
            false
        } catch (error: LinkageError) {
            Logs.e("Windows updater could not load its bundled library", error)
            false
        }
    }

    @Synchronized
    fun check(): Boolean {
        val native = library ?: return false
        native.win_sparkle_check_update_with_ui()
        return true
    }

    @Synchronized
    fun close() {
        library?.win_sparkle_cleanup()
        library = null
        shutdownRequested = null
        installReservation?.close()
        installReservation = null
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun windowsUpdaterCanShutdown(state: ServiceState): Boolean = !state.started && state != ServiceState.Stopping

internal fun windowsUpdateInstallationRoot(jar: File): File? {
    if (!jar.isFile || jar.extension != "jar" || jar.parentFile?.name != "app") return null
    val root = jar.parentFile.parentFile ?: return null
    // Portable archives deliberately have no uninstaller; development runs have no packaged jar.
    return root.takeIf { it.resolve("ShiftVPS.exe").isFile && it.resolve("uninstall.exe").isFile }
}

internal fun windowsUpdateInstallerCommand(installer: File, processId: Long): List<String> {
    require(installer.isAbsolute && installer.isFile && installer.extension.equals("exe", ignoreCase = true)) {
        "A verified Windows EXE installer is required"
    }
    require(processId > 0) { "Invalid application process" }
    return listOf(installer.absolutePath, "/S", "/UPDATE", "/UPDATEPID=$processId")
}

internal fun windowsUpdaterLibraryHash(architecture: String): String? = when (architecture.lowercase()) {
    "amd64", "x86_64" -> "9b43b1c16ee39fb9a91b5bd75138767898779510e0836be2919250607cdbe8ab"
    "aarch64", "arm64" -> "f38829a6b1ef853b9bed780e9e40614c100e0a3020318555c9f6681deade5756"
    else -> null
}

internal fun interface WindowsUpdaterCallback : Callback {
    fun invoke()
}

internal fun interface WindowsCanShutdownCallback : Callback {
    fun invoke(): Int
}

internal fun interface WindowsRunInstallerCallback : Callback {
    fun invoke(verifiedInstaller: WString): Int
}

@Suppress("FunctionName")
private interface WinSparkleLibrary : Library {
    fun win_sparkle_set_app_details(company: WString, app: WString, version: WString)
    fun win_sparkle_set_app_build_version(version: WString)
    fun win_sparkle_set_appcast_url(url: String)
    fun win_sparkle_set_eddsa_public_key(key: String): Int
    fun win_sparkle_set_automatic_check_for_updates(enabled: Int)
    fun win_sparkle_set_update_check_interval(seconds: Int)
    fun win_sparkle_set_can_shutdown_callback(callback: WindowsCanShutdownCallback)
    fun win_sparkle_set_shutdown_request_callback(callback: WindowsUpdaterCallback)
    fun win_sparkle_set_user_run_installer_callback(callback: WindowsRunInstallerCallback)
    fun win_sparkle_set_error_callback(callback: WindowsUpdaterCallback)
    fun win_sparkle_init()
    fun win_sparkle_check_update_with_ui()
    fun win_sparkle_cleanup()
}
