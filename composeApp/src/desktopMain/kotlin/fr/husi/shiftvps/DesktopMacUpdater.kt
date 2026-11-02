package fr.husi.shiftvps

import fr.husi.ktx.Logs
import fr.husi.platform.PlatformInfo
import fr.husi.resolvePackagedLauncherExecutable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Sparkle verifies and stages updates; replacement waits for a natural application exit. */
internal object DesktopMacUpdater {
    private var scope: CoroutineScope? = null
    private var applicationBundle: File? = null
    private val checking = AtomicBoolean(false)

    @Synchronized
    fun initialize(): Boolean {
        if (!PlatformInfo.isMacOs) return false
        if (scope != null) return true
        val bundle = runCatching {
            macUpdaterApplicationBundle(resolvePackagedLauncherExecutable())
        }.getOrNull() ?: return false
        if (macUpdaterFeed(System.getProperty("os.arch")) == null) return false
        applicationBundle = bundle
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { updaterScope ->
            updaterScope.launch {
                delay(30.seconds)
                while (isActive) {
                    runCheck(bundle, immediate = false)
                    delay(6.hours)
                }
            }
        }
        return true
    }

    /** A manual check may show the normal macOS authorization dialog, never force a restart. */
    @Synchronized
    fun check(): Boolean {
        val updaterScope = scope ?: return false
        val bundle = applicationBundle ?: return false
        if (checking.get()) return false
        updaterScope.launch { runCheck(bundle, immediate = true) }
        return true
    }

    @Synchronized
    fun close() {
        scope?.cancel()
        scope = null
        applicationBundle = null
        // Do not kill Sparkle's installer: a staged update must survive our normal exit.
    }

    private fun runCheck(bundle: File, immediate: Boolean) {
        if (!checking.compareAndSet(false, true)) return
        try {
            val feed = macUpdaterFeed(System.getProperty("os.arch")) ?: return
            val process = ProcessBuilder(macUpdaterCommand(bundle, feed, immediate))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                // Bound a stalled check, not the separate installer waiting for app exit.
                process.destroy()
                Logs.w("macOS update check timed out")
                return
            }
            val result = process.exitValue()
            // 4 is Sparkle CLI's normal "no update available" result.
            if (result != 0 && result != 4) {
                Logs.w("macOS update check did not complete (code $result)")
            }
        } catch (error: Exception) {
            Logs.w("Unable to start the bundled macOS updater", error)
        } finally {
            checking.set(false)
        }
    }
}

internal fun macUpdaterApplicationBundle(launcher: File?): File? {
    val executable = launcher?.canonicalFile ?: return null
    if (!executable.isFile || !executable.canExecute()) return null
    val macos = executable.parentFile?.takeIf { it.name == "MacOS" } ?: return null
    val contents = macos.parentFile?.takeIf { it.name == "Contents" } ?: return null
    val bundle = contents.parentFile?.takeIf { it.extension == "app" } ?: return null
    if (!contents.resolve("Info.plist").isFile) return null
    val helper = macUpdaterExecutable(bundle)
    if (!helper.isFile || !helper.canExecute()) return null
    // A development symlink must not make us execute an updater outside this install.
    if (!helper.canonicalFile.toPath().startsWith(bundle.toPath())) return null
    return bundle
}

private fun macUpdaterExecutable(bundle: File): File =
    bundle.resolve("Contents/Helpers/ShiftVPSUpdater.app/Contents/MacOS/ShiftVPSUpdater")

internal fun macUpdaterFeed(architecture: String): String? {
    val target = when (architecture.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "amd64"
        else -> return null
    }
    return "https://raw.githubusercontent.com/dirtycrew1/shiftvps-client/updates/macos-$target.xml"
}

internal fun macUpdaterCommand(bundle: File, feed: String, immediate: Boolean): List<String> {
    require(feed == macUpdaterFeed("arm64") || feed == macUpdaterFeed("amd64")) {
        "Unrecognized macOS update feed"
    }
    return buildList {
        add(macUpdaterExecutable(bundle).absolutePath)
        add(bundle.absolutePath)
        addAll(listOf("--application", bundle.absolutePath, "--defer-install"))
        addAll(listOf("--feed-url", feed, "--user-agent-name", "ShiftVPS"))
        if (immediate) {
            addAll(listOf("--check-immediately", "--interactive"))
        } else {
            add("--grant-automatic-checks")
        }
    }
}
