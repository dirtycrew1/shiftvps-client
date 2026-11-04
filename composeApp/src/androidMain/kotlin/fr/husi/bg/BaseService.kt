package fr.husi.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.getSystemService
import fr.husi.Action
import fr.husi.bg.proto.ProxyInstance
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.database.SagerDatabase
import fr.husi.ktx.Logs
import fr.husi.ktx.broadcastReceiver
import fr.husi.ktx.hasPermission
import fr.husi.ktx.readableMessage
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.ktx.runOnMainDispatcher
import fr.husi.ktx.showToast
import fr.husi.plugin.PluginNotFoundException
import fr.husi.repository.resolveAndroidRepository
import fr.husi.repository.resolveRepository
import fr.husi.resources.*
import fr.husi.shiftvps.ShiftVpsServiceTelemetry
import fr.husi.shiftvps.ShiftVpsExecutionLease
import fr.husi.shiftvps.attachServiceMetricSession
import fr.husi.shiftvps.bindServiceMetricSession
import fr.husi.shiftvps.hasPendingOwnPackageInstall
import fr.husi.shiftvps.serviceFailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.milliseconds

class BaseService {

    interface BackendEngine {
        var proxy: ProxyInstance?

        suspend fun init(profile: ProxyEntity)

        suspend fun start(onFatal: suspend (Throwable) -> Unit)

        fun stop()

        fun resetNetwork()
    }

    private class AndroidBackendEngine(private val service: Interface) : BackendEngine {
        override var proxy: ProxyInstance? = null

        override suspend fun init(profile: ProxyEntity) {
            proxy = ProxyInstance(profile, service).also {
                it.init()
            }
        }

        override suspend fun start(onFatal: suspend (Throwable) -> Unit) {
            val proxy = proxy ?: return
            // Plugin processes are supervised by the Go pool; reverse-bind the
            // fatal callback so a crashing plugin still stops the runner.
            resolveAndroidRepository().boxService?.setPluginFatalHandler { message ->
                Logs.w(message)
                runOnDefaultDispatcher {
                    onFatal(Exception(message))
                }
            }
            proxy.launch()
        }

        override fun stop() {
            proxy?.close()
            proxy = null
        }

        override fun resetNetwork() {
            val proxy = proxy
            if (proxy != null && proxy.isInitialized() &&
                resolveAndroidRepository().boxService?.hasInstance() == true
            ) {
                runCatching {
                    resolveAndroidRepository().boxService?.resetNetwork()
                }
            }
        }
    }

    class Data internal constructor(val service: Interface) {
        var state = ServiceState.Stopped
        internal var executionLease: ShiftVpsExecutionLease? = null
        val backend = service.createBackendEngine()
        var proxy: ProxyInstance?
            get() = backend.proxy
            set(value) {
                backend.proxy = value
            }
        var notification: ServiceNotifier = NoopServiceNotifier

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Action.RELOAD -> service.reload()
                Action.RELOAD_IF_RUNNING -> if (state.canStop) service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val powerManager = (service as Context).getSystemService<PowerManager>()!!
                    val proxy = proxy
                    if (proxy != null && proxy.isInitialized() &&
                        resolveAndroidRepository().boxService?.hasInstance() == true
                    ) {
                        if (powerManager.isDeviceIdleMode) {
                            resolveAndroidRepository().boxService?.pause()
                        } else {
                            resolveAndroidRepository().boxService?.wake()
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    withTimeoutOrNull(1000.milliseconds) {
                        resetNetwork()
                        withContext(Dispatchers.Main) {
                            collapseStatusBar(ctx)
                            showToast(resolveRepository().getString(Res.string.have_reset_network))
                        }
                    }
                }

                else -> service.stopRunner()
            }
        }

        @SuppressLint("WrongConstant")
        private fun collapseStatusBar(context: Context) {
            try {
                val statusBarManager = context.getSystemService("statusbar")
                val collapse = statusBarManager.javaClass.getMethod("collapsePanels")
                collapse.invoke(statusBarManager)
            } catch (_: Exception) {
            }
        }

        var closeReceiverRegistered = false

        /** Lifecycle-only binder so BIND_AUTO_CREATE still starts/keeps :bg alive. */
        val binder = Binder()
        var connectingJob: Job? = null

        fun changeState(s: ServiceState, message: String? = null, profile: ProxyEntity? = null, diagnosticCode: String? = null) {
            if (state == s && message == null) return
            if (s == ServiceState.Stopped) {
                val lease = executionLease
                executionLease = null
                lease?.close()
            }
            state = s
            DataStore.serviceState = s
            BackendState.updateState(s, proxy?.displayProfileName)
            ServiceEventPublisher.publishState(s, proxy?.displayProfileName)
            ShiftVpsServiceTelemetry.onState(s, profile ?: proxy?.profile,
                diagnosticCode ?: if (message.isNullOrBlank()) null else "unknown")
        }

        fun resetNetwork() {
            backend.resetNetwork()
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createBackendEngine(): BackendEngine = AndroidBackendEngine(this)
        fun createNotifier(profileName: String): ServiceNotifier = NoopServiceNotifier

        fun onBind(intent: Intent): IBinder? = if (intent.action == Action.SERVICE) {
            data.binder
        } else {
            null
        }

        fun reload() {
            if (DataStore.selectedProxy.getBlocking() == 0L) {
                stopRunner(false, runBlocking { resolveRepository().getString(Res.string.profile_empty) })
                return
            }

            val state = data.state
            val restartCurrentService = javaClass == SagerConnection.serviceClass
            when {
                state == ServiceState.Stopped -> {
                    if (restartCurrentService) {
                        startRunner()
                    } else {
                        resolveRepository().startService()
                    }
                }

                state.canStop -> {
                    if (restartCurrentService) {
                        stopRunner(true)
                    } else {
                        stopRunner(afterStop = resolveRepository()::startService)
                    }
                }

                else -> Logs.w("Illegal state $state when invoking use")
            }
        }

        suspend fun startProcesses() {
            data.backend.start { throwable ->
                stopRunner(false, throwable.readableMessage)
            }
            if (resolveAndroidRepository().boxService?.needWIFIState() == true) {
                val wifiPermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
                this as Context
                if (!hasPermission(wifiPermission)) {
                    ServiceEventPublisher.publishAlert(ServiceAlert.NeedWifiPermission)
                }
            }
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(attachServiceMetricSession(Intent(this, javaClass)))
            } else {
                startService(attachServiceMetricSession(Intent(this, javaClass)))
            }
        }

        fun killProcesses() {
            data.backend.stop()
            wakeLock?.apply {
                release()
                wakeLock = null
            }
            runOnDefaultDispatcher {
                DefaultNetworkMonitor.stop()
            }
        }

        fun stopRunner(
            restart: Boolean = false,
            msg: String? = null,
            afterStop: (() -> Unit)? = null,
            diagnosticCode: String? = null,
        ) {
            ServiceRegistry.baseService = null
            ServiceRegistry.vpnService = null

            if (data.state == ServiceState.Stopping) return
            this as Service
            OpenConnectAuthWatcher.stop(this)
            OpenVPNAuthWatcher.stop(this)
            TailscaleNotificationWatcher.stop(this)
            data.notification.destroy()
            data.notification = NoopServiceNotifier

            data.changeState(ServiceState.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                }

                // change the state
                data.changeState(ServiceState.Stopped, msg, diagnosticCode = diagnosticCode)
                if (!msg.isNullOrBlank()) {
                    ServiceEventPublisher.publishAlert(ServiceAlert.Common(msg))
                }
                // stop the service if nothing has bound to it
                if (restart) startRunner() else {
                    afterStop?.invoke()
                    stopSelf()
                }
            }
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            DefaultNetworkMonitor.start()
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock.get()) {
                acquireWakeLock()
                data.notification.onWakeLock(true)
            } else {
                data.notification.onWakeLock(false)
            }
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val data = data
            if (data.state != ServiceState.Stopped) return Service.START_NOT_STICKY
            val lease = try {
                ShiftVpsExecutionLease.tryAcquire(resolveAndroidRepository().noBackupFilesDir)?.let { acquired ->
                    // A crashed UI loses its file lock, but its OS confirmation may still be open.
                    try {
                        if (hasPendingOwnPackageInstall(this as Context)) {
                            acquired.close()
                            null
                        } else acquired
                    } catch (e: Exception) {
                        acquired.close()
                        throw e
                    }
                }
            } catch (_: Exception) {
                Logs.e("Unable to reserve the VPN lifecycle")
                null
            }
            if (lease == null) {
                // Covers UI, queued, Always-on and system starts without disturbing a running VPN.
                val current = BackendState.status.value
                val state = if (current.state.started || current.state == ServiceState.Stopping) current.state
                    else ServiceState.Stopped
                ServiceEventPublisher.publishState(state, current.profileName)
                if (ServiceRegistry.vpnService === this) ServiceRegistry.vpnService = null
                (this as Service).stopSelf(startId)
                return Service.START_NOT_STICKY
            }
            data.executionLease = lease
            ServiceRegistry.baseService = this
            data.notification = createNotifier("")
            val profile = runBlocking { SagerDatabase.proxyDao.getById(DataStore.selectedProxy.getBlocking()) }
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                stopRunner(false, runBlocking { resolveRepository().getString(Res.string.profile_empty) })
                return Service.START_NOT_STICKY
            }

            setBootReceiverEnabled(DataStore.persistAcrossReboot.getBlocking())
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Action.RELOAD_IF_RUNNING)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.permission.SERVICE",
                        null,
                        Context.RECEIVER_NOT_EXPORTED,
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.permission.SERVICE",
                        null,
                    )
                }
                data.closeReceiverRegistered = true
            }

            bindServiceMetricSession(intent)
            data.changeState(ServiceState.Connecting, profile = profile)
            data.connectingJob = runOnDefaultDispatcher {
                try {
                    data.notification.onTitle(profile.displayNameForService())

                    Executable.killAll()    // clean up old processes
                    preInit()
                    data.backend.init(profile)
                    DataStore.currentProfile.set(profile.id)

                    startProcesses()
                    data.changeState(ServiceState.Connected)
                    OpenConnectAuthWatcher.start(this@Interface)
                    OpenVPNAuthWatcher.start(this@Interface)
                    TailscaleNotificationWatcher.start(this@Interface)

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (e: UnknownHostException) {
                    Logs.e(e)
                    stopRunner(false, resolveRepository().getString(Res.string.invalid_server), diagnosticCode = "dns")
                } catch (e: PluginNotFoundException) {
                    withContext(Dispatchers.Main) {
                        showToast(e.readableMessage)
                    }
                    Logs.w(e)
                    stopRunner(false, e.readableMessage, diagnosticCode = "configuration")
                    ServiceEventPublisher.publishAlert(ServiceAlert.MissingPlugin(e.plugin))
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.e(exc.readableMessage)
                    } else {
                        Logs.e(exc)
                    }
                    stopRunner(
                        false,
                        "${resolveRepository().getString(Res.string.service_failed)}: ${exc.readableMessage}",
                        diagnosticCode = serviceFailureCode(exc),
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}
