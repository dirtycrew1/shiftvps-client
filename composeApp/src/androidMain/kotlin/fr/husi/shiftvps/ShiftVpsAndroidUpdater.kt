package fr.husi.shiftvps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import fr.husi.BuildConfig
import fr.husi.bg.ApkInstallResult
import fr.husi.bg.BackendState
import fr.husi.bg.SystemApkInstallSessionHost
import fr.husi.bg.installApk
import fr.husi.repository.resolveAndroidRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

/** Retained by the Android screen's ViewModel. Uses only the ordinary OS package installer. */
class ShiftVpsAndroidUpdater(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val preferences = this.context.getSharedPreferences("shiftvps-updates", Context.MODE_PRIVATE)
    private val http = SignedUpdateHttp()
    private val mutableState = MutableStateFlow(ShiftVpsUpdateState())
    val state: StateFlow<ShiftVpsUpdateState> = mutableState.asStateFlow()
    private var availableUpdate: VerifiedAndroidUpdate? = null
    @Volatile private var installRequested = false
    @Volatile private var initialized = false
    @Volatile private var foreground = true

    fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            try {
                mutex.withLock { abandonPendingOwnInstalls() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The service also refuses a pending own-package session if OS cleanup fails.
            }
            while (true) {
                checkIfDue()
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
    }

    fun check() = runExclusive { checkUpdate() }

    fun onResume() {
        foreground = true
        checkIfDue()
        val pendingPermission = state.value.phase == ShiftVpsUpdatePhase.NeedsPermission && canInstallPackages()
        val readyDownload = state.value.phase == ShiftVpsUpdatePhase.Available && state.value.progressPercent == 100
        if (installRequested && (pendingPermission || readyDownload)) {
            install()
        }
    }

    fun onPause() {
        foreground = false
    }

    fun install() = runExclusive {
        val update = availableUpdate ?: return@runExclusive
        installRequested = true
        try {
            mutableState.value = ShiftVpsUpdateState(ShiftVpsUpdatePhase.Downloading, update.version)
            val apk = http.download(update, File(context.cacheDir, "shiftvps-updates")) { percent ->
                if (state.value.progressPercent != percent) mutableState.update { it.copy(progressPercent = percent) }
            }
            verifyApk(apk, update)
            if (!foreground) {
                mutableState.update { it.copy(phase = ShiftVpsUpdatePhase.Available) }
                return@runExclusive
            }
            if (!canInstallPackages()) {
                mutableState.update { it.copy(phase = ShiftVpsUpdatePhase.NeedsPermission) }
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runExclusive
            }
            if (!canRestartApplication(BackendState.status.value.state)) {
                mutableState.update { it.copy(phase = ShiftVpsUpdatePhase.NeedsDisconnect) }
                return@runExclusive
            }
            val lease = ShiftVpsExecutionLease.tryAcquire(resolveAndroidRepository().noBackupFilesDir)
            if (lease == null) {
                mutableState.update { it.copy(phase = ShiftVpsUpdatePhase.NeedsDisconnect) }
                return@runExclusive
            }
            lease.use {
                // Recheck after acquiring the cross-process lease; an IPC snapshot alone can lag.
                if (!foreground) {
                    mutableState.update { it.copy(phase = ShiftVpsUpdatePhase.Available) }
                    return@runExclusive
                }
                if (!canRestartApplication(BackendState.status.value.state)) {
                    mutableState.update { it.copy(phase = ShiftVpsUpdatePhase.NeedsDisconnect) }
                    return@runExclusive
                }
                abandonPendingOwnInstalls()
                // The OS validates signatures again and asks for approval. The lease remains held
                // while its confirmation UI is visible and is released on result or cancellation.
                mutableState.update { it.copy(phase = ShiftVpsUpdatePhase.Installing) }
                when (installApk(context, SystemApkInstallSessionHost(context), apk)) {
                    ApkInstallResult.Success -> {
                        availableUpdate = null
                        installRequested = false
                        mutableState.value = ShiftVpsUpdateState()
                    }
                    is ApkInstallResult.Failed -> showInstallFailure(update)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            showInstallFailure(update)
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun showInstallFailure(update: VerifiedAndroidUpdate) {
        installRequested = false
        mutableState.value = ShiftVpsUpdateState(ShiftVpsUpdatePhase.Failed, update.version,
            message = "Не удалось обновить приложение. Попробуйте ещё раз.")
    }

    private fun checkIfDue() {
        val elapsed = System.currentTimeMillis() - preferences.getLong("checked_at", 0)
        if (elapsed !in 0 until UPDATE_CHECK_INTERVAL_MS) check()
    }

    private suspend fun checkUpdate() {
        if (state.value.phase in setOf(ShiftVpsUpdatePhase.Downloading, ShiftVpsUpdatePhase.Installing,
                ShiftVpsUpdatePhase.NeedsPermission, ShiftVpsUpdatePhase.NeedsDisconnect)) return
        if (BuildConfig.UPDATE_RSA_PUBLIC_KEY.isBlank() || BuildConfig.UPDATE_ANDROID_MANIFEST_URL.isBlank()) return
        try {
            val verifier = SignedAndroidUpdateVerifier(BuildConfig.UPDATE_RSA_PUBLIC_KEY)
            val installed = installedIdentity()
            val highestSeen = preferences.getLong("highest_version", installed.versionCode)
            val urls = listOf(BuildConfig.UPDATE_ANDROID_MANIFEST_URL,
                "https://shiftvps.ru/downloads/updates/android.json",
                "https://web-backup.shiftvps.ru/downloads/updates/android.json").distinct()
            var lastFailure: Exception? = null
            for (url in urls) {
                try {
                    val update = verifier.verify(http.manifest(url), installed.versionCode,
                        Build.SUPPORTED_ABIS.orEmpty().toList(), highestSeen)
                    val edit = preferences.edit().putLong("checked_at", System.currentTimeMillis())
                    if (update != null) edit.putLong("highest_version", update.versionCode.toLong())
                    require(edit.commit())
                    availableUpdate = update
                    mutableState.value = update?.let {
                        ShiftVpsUpdateState(ShiftVpsUpdatePhase.Available, it.version)
                    } ?: ShiftVpsUpdateState()
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastFailure = e
                }
            }
            if (lastFailure != null) throw lastFailure
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline/background checks must not interrupt account login, VPN, or the last valid offer.
        }
    }

    private fun runExclusive(action: suspend () -> Unit) {
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try { action() } finally { mutex.unlock() }
        }
    }

    private fun canInstallPackages() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    private fun abandonPendingOwnInstalls() {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.filter { it.appPackageName == context.packageName }.forEach { installer.abandonSession(it.sessionId) }
        require(!hasPendingOwnPackageInstall(context)) { "A previous package update is still pending" }
    }

    @Suppress("DEPRECATION")
    private fun installedIdentity(): AndroidPackageIdentity = identity(context.packageManager.getPackageInfo(
        context.packageName, signatureFlags(),
    ))

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, update: VerifiedAndroidUpdate) {
        require(apk.length() == update.artifact.size && sha256(apk) == update.artifact.sha256)
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags()))
        requireOwnUpdateApk(identity(archive), installedIdentity(), update.versionCode)
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): AndroidPackageIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else info.signatures.orEmpty()
        val signers = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return AndroidPackageIdentity(info.packageName, version, signers)
    }
}

internal fun hasPendingOwnPackageInstall(context: Context): Boolean =
    context.packageManager.packageInstaller.mySessions.any { it.appPackageName == context.packageName }
