package fr.husi.shiftvps

import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import fr.husi.BuildConfig
import fr.husi.bg.DefaultNetworkMonitor
import fr.husi.bg.ServiceState
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.database.preference.string
import fr.husi.repository.resolveAndroidRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

private const val SESSION_OWNER = "ru.shiftvps.client.METRIC_OWNER"
private const val SESSION_POLICY = "ru.shiftvps.client.METRIC_POLICY"
@Volatile private var preparedSession: ServiceMetricSession? = null
@Volatile private var serviceSession: ServiceMetricSession? = null

internal actual fun prepareServiceMetrics(owner: String?, policyVersion: Int) {
    preparedSession = owner?.takeIf { it.matches(Regex("[0-9a-f]{64}")) && policyVersion > 0 }
        ?.let { ServiceMetricSession(it, policyVersion) }
}

internal fun attachServiceMetricSession(intent: Intent): Intent = intent.also {
    preparedSession?.let { session ->
        it.putExtra(SESSION_OWNER, session.owner)
        it.putExtra(SESSION_POLICY, session.policyVersion)
    }
}

internal fun bindServiceMetricSession(intent: Intent?) {
    val owner = intent?.getStringExtra(SESSION_OWNER)
    val version = intent?.getIntExtra(SESSION_POLICY, 0) ?: 0
    serviceSession = owner?.takeIf { it.matches(Regex("[0-9a-f]{64}")) && version > 0 }
        ?.let { ServiceMetricSession(it, version) }
    preparedSession = serviceSession
}

/** Only sanitized values enter this channel; service startup never waits for disk or HTTP. */
internal object ShiftVpsServiceTelemetry {
    private val transitions = Channel<ServiceMetricTransition>(64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            val tracker = ServiceLifecycleTracker()
            for (transition in transitions) {
                try {
                    val captured = tracker.transition(transition) ?: continue
                    val policy = cachedServicePolicy()
                    if (!policy.telemetry.enabled) continue
                    val event = captured.metric
                    val metric = ClientMetric(event.event, event.serverId, errorCode = event.errorCode,
                        appVersion = BuildConfig.VERSION_NAME.substringBefore('-').take(32), platform = "android", policyVersion = captured.session.policyVersion)
                    val appended = AndroidMetricDisk.access { queue ->
                        // Check ownership while holding the queue lock: a stale event cannot replace a new account's queue.
                        val token = platformShiftVpsTokenStore().load() ?: return@access false
                        if (!captured.belongsTo(tokenOwner(token))) return@access false
                        queue.append(captured.session.owner, metric, System.currentTimeMillis(), captured.occurredAt)
                        true
                    }
                    if (appended) scheduleMetricDelivery()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Diagnostics must not affect the VPN or write secrets into the ordinary log.
                }
            }
        }
    }

    fun onState(state: ServiceState, profile: ProxyEntity?, errorCode: String?) {
        val endpoint = runCatching { profile?.let(::diagnosticServerId) }.getOrNull()
        transitions.trySend(ServiceMetricTransition(serviceSession, state, endpoint, errorCode, System.currentTimeMillis()))
    }
}

private object AndroidMetricDisk {
    private val mutex = Mutex()

    suspend fun <T> access(block: suspend (ClientMetricOutbox) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = resolveAndroidRepository().noBackupFilesDir
            directory.mkdirs()
            val file = directory.resolve("shiftvps-service-metrics.json")
            RandomAccessFile(directory.resolve("shiftvps-service-metrics.lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val text = if (file.exists() && file.length() in 1L..ClientMetricOutbox.MAX_BYTES.toLong()) file.readText() else ""
                    val queue = ClientMetricOutbox.decode(text, System.currentTimeMillis())
                    val result = block(queue)
                    val pending = File(directory, "${file.name}.pending")
                    try {
                        pending.writeText(queue.encode())
                        check(pending.renameTo(file)) { "Could not save private diagnostics queue" }
                    } finally { pending.delete() }
                    result
                }
            }
        }
    }
}

private suspend fun cachedServicePolicy(): ShiftVpsPolicy = runCatching {
    clientJson.decodeFromString<ShiftVpsPolicy>(DataStore.configurationStore.string("shiftvps.policy").get()).validated()
}.getOrDefault(ShiftVpsPolicy())

private const val DELIVERY_WORK = "ShiftVpsServiceMetrics"
private const val RETRY_WORK = "ShiftVpsServiceMetricsRetry"
private const val PERIODIC_KICK = "periodic_kick"

private fun scheduleMetricDelivery() {
    val manager = RemoteWorkManager.getInstance(resolveAndroidRepository().context)
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    manager.enqueueUniqueWork(DELIVERY_WORK, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequest.Builder(ShiftVpsMetricsWorker::class.java)
            .setConstraints(constraints).setInitialDelay(3, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    // A periodic kick recovers the enqueue/finishing race or a killed process with a persisted queue.
    manager.enqueueUniquePeriodicWork(RETRY_WORK, ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequest.Builder(ShiftVpsMetricsWorker::class.java, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf(PERIODIC_KICK to true)).setConstraints(constraints).build())
}

class ShiftVpsMetricsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        var owner: String? = null
        try {
            val token = platformShiftVpsTokenStore().load() ?: run {
                RemoteWorkManager.getInstance(applicationContext).cancelUniqueWork(RETRY_WORK)
                return Result.success()
            }
            val activeOwner = tokenOwner(token)
            owner = activeOwner
            val policy = cachedServicePolicy()
            if (!policy.telemetry.enabled) {
                AndroidMetricDisk.access { it.discard(activeOwner) }
                RemoteWorkManager.getInstance(applicationContext).cancelUniqueWork(RETRY_WORK)
                return Result.success()
            }
            val batch = AndroidMetricDisk.access { it.peek(activeOwner, System.currentTimeMillis()) }
            if (batch.isEmpty()) return Result.success()
            if (inputData.getBoolean(PERIODIC_KICK, false)) {
                scheduleMetricDelivery()
                return Result.success()
            }
            val api = ShiftVpsApi(HttpsClientTransport(::openPhysicalConnection))
            // Safe GET discovers the working primary/backup origin before the non-replayed POST.
            val manifest = api.manifest(token, policy)
            requireActiveAccount(manifest.account)
            if (!manifest.policy.telemetry.enabled) {
                AndroidMetricDisk.access { it.discard(activeOwner) }
                return Result.success()
            }
            api.metrics(token, batch.map { it.metric.copy(occurredAt = it.createdAt / 1000L) }, metricBatchId(activeOwner, batch))
            AndroidMetricDisk.access { it.acknowledge(activeOwner, batch.map { item -> item.id }.toSet()) }
            val remaining = AndroidMetricDisk.access { it.peek(activeOwner, System.currentTimeMillis()).isNotEmpty() }
            return if (remaining) Result.retry() else Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is ClientApiException && (error.status == 401 || error.code == "subscription_expired")) {
                owner?.let { fingerprint -> AndroidMetricDisk.access { it.discard(fingerprint) } }
                val current = platformShiftVpsTokenStore().load()
                if (current != null && tokenOwner(current) == owner) resolveAndroidRepository().stopService()
                return Result.success()
            }
            return Result.retry()
        }
    }
}

/** Control-plane reports must still be deliverable when the VPN data plane has failed. */
private fun openPhysicalConnection(uri: URI): HttpURLConnection {
    val connectivity = resolveAndroidRepository().connectivity
    val candidates = listOfNotNull(DefaultNetworkMonitor.defaultNetwork, connectivity.activeNetwork) + connectivity.allNetworks
    val physical = candidates.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let { capabilities ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    } ?: throw IOException("Physical network unavailable")
    return physical.openConnection(uri.toURL()) as HttpURLConnection
}
