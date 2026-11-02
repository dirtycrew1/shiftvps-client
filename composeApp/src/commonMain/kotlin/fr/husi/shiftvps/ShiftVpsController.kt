package fr.husi.shiftvps

import fr.husi.BuildConfig
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.buildPluginSpecs
import fr.husi.bg.initPlugins
import fr.husi.core.CoreClient
import fr.husi.core.urlTestOptions
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.database.preference.string
import fr.husi.database.preference.long
import fr.husi.fmt.buildConfig
import fr.husi.fmt.http.HttpBean
import fr.husi.fmt.hysteria.HysteriaBean
import fr.husi.fmt.mieru.MieruBean
import fr.husi.fmt.naive.NaiveBean
import fr.husi.fmt.shadowsocks.ShadowsocksBean
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.fmt.trusttunnel.TrustTunnelBean
import fr.husi.fmt.tuic.TuicBean
import fr.husi.fmt.v2ray.VLESSBean
import fr.husi.group.RawUpdater
import fr.husi.platform.PlatformInfo
import fr.husi.repository.resolveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.net.ssl.SSLException

class ShiftVpsController : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = ShiftVpsApi()
    private val tokens = platformShiftVpsTokenStore()
    private val tokenAccess = Mutex()
    private val operation = Mutex()
    private val survey = Mutex()
    private val connectionPreparation = Mutex()
    private var loginJob: Job? = null
    private var foregroundJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var sessionGeneration = 0
    private var policy = ShiftVpsPolicy()
    private var account: ShiftVpsAccount? = null
    private var accountVerifiedAt = 0L
    private var userSelectedServer = false
    private var profiles = emptyList<ProxyEntity>()
    private val metrics = ArrayDeque<ClientMetric>()
    private val repository = resolveRepository()
    private val core: CoreClient = GlobalContext.get().get()
    private val cachedPolicy = DataStore.configurationStore.string("shiftvps.policy")
    private val cachedAccount = DataStore.configurationStore.string("shiftvps.account")
    private val cachedVerificationTime = DataStore.configurationStore.long("shiftvps.account_verified_at")

    val state: StateFlow<ShiftVpsUiState>
        field = MutableStateFlow(ShiftVpsUiState())

    val events: SharedFlow<ShiftVpsEvent>
        field = MutableSharedFlow(extraBufferCapacity = 4)

    init {
        scope.launch {
            var previous = ServiceState.Idle
            BackendState.status.collect { status ->
                state.update { it.copy(serviceState = status.state) }
                when {
                    status.state.connected && !previous.connected -> metric("connect_succeeded")
                    previous.connected && !status.state.connected -> metric("disconnected")
                }
                if (status.state == ServiceState.Connecting && previous != ServiceState.Connecting) {
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = scope.launch {
                        delay(policy.connectTimeoutMs.toLong())
                        if (BackendState.status.value.state == ServiceState.Connecting) {
                            repository.stopService()
                            state.update { it.copy(error = "Сервер не ответил. Выберите другой сервер и попробуйте ещё раз.") }
                            metric("connect_failed", errorCode = "timeout")
                        }
                    }
                } else if (status.state != ServiceState.Connecting) {
                    connectTimeoutJob?.cancel()
                }
                previous = status.state
            }
        }
        scope.launch {
            BackendState.alerts.collect {
                state.update { it.copy(error = "Не удалось установить соединение. Попробуйте другой сервер.") }
                metric("connect_failed", errorCode = "configuration")
            }
        }
        scope.launch {
            while (isActive) {
                delay(policy.telemetry.batchIntervalSeconds * 1000L)
                flushMetrics()
            }
        }
    }

    fun onForeground() {
        if (foregroundJob?.isActive == true) {
            if (state.value.signedIn && state.value.authCode == null) {
                scope.launch { surveySafely() }
                scope.launch { syncAndProbe(false) }
            }
            return
        }
        foregroundJob = scope.launch {
            state.update { it.copy(loading = true) }
            try {
                if (currentToken() != null) {
                    policy = runCatching { clientJson.decodeFromString<ShiftVpsPolicy>(cachedPolicy.get()).validated() }
                        .getOrDefault(ShiftVpsPolicy())
                    account = runCatching { clientJson.decodeFromString<ShiftVpsAccount>(cachedAccount.get()) }.getOrNull()
                    accountVerifiedAt = cachedVerificationTime.get()
                    profiles = managedProfiles()
                    state.update { it.copy(signedIn = true, accountName = account?.name.orEmpty(), loading = false) }
                    publishProfiles()
                    val hadCachedProfiles = profiles.isNotEmpty()
                    if (hadCachedProfiles && policy.latency.probeOnLaunch) scope.launch { surveySafely() }
                    syncAndProbe(!hadCachedProfiles && policy.latency.probeOnLaunch)
                }
            } catch (error: Exception) {
                handleFailure(error)
            } finally {
                state.update { it.copy(loading = false) }
            }
            while (isActive) {
                delay(policy.refreshIntervalSeconds * 1000L)
                if (state.value.signedIn) syncAndProbe(false)
            }
        }
    }

    fun signIn() {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch {
            state.update { it.copy(loading = true, error = null) }
            val generation = sessionGeneration
            try {
                val verifier = createVerifier()
                val grant = api.authorize(platform(), platformName(), verifierChallenge(verifier))
                state.update { it.copy(authCode = grant.userCode, loading = false) }
                events.emit(ShiftVpsEvent.OpenBrowser(grant.verificationUri))
                val deadline = System.currentTimeMillis() + grant.expiresIn * 1000L
                var interval = grant.interval
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    try {
                        val token = api.token(grant.grantId, verifier)
                        tokenAccess.withLock {
                            if (sessionGeneration != generation) return@launch
                            tokens.save(token)
                        }
                        state.update { it.copy(authCode = null, signedIn = true, loading = true) }
                        syncAndProbe(true)
                        return@launch
                    } catch (error: ClientApiException) {
                        when (error.code) {
                            "authorization_pending" -> Unit
                            "slow_down" -> interval = (interval + 2).coerceAtMost(30)
                            else -> throw error
                        }
                    }
                }
                state.update { it.copy(error = "Время входа истекло. Нажмите «Войти» ещё раз.") }
            } catch (error: Exception) {
                handleFailure(error)
            } finally {
                state.update { it.copy(loading = false, authCode = null) }
            }
        }
    }

    fun cancelLogin() { loginJob?.cancel() }
    fun dismissError() { state.update { it.copy(error = null) } }

    fun refresh() { scope.launch { syncAndProbe(true) } }
    fun checkServers() {
        scope.launch { surveySafely() }
    }

    fun selectServer(id: Long) {
        if (state.value.serviceState.started || profiles.none { it.id == id }) return
        userSelectedServer = true
        state.update { it.copy(selectedId = id) }
        scope.launch { DataStore.selectedProxy.set(id) }
    }

    fun toggleConnection() {
        if (state.value.serviceState.canStop) {
            repository.stopService()
            return
        }
        scope.launch {
            if (!connectionPreparation.tryLock()) return@launch
            try {
                val token = currentToken() ?: throw ClientApiException("invalid_token", 401)
                val cached = account
                if (cached != null) requireActiveAccount(cached)
                if (cached == null || !recentVerification(accountVerifiedAt)) {
                    val manifest = api.manifest(token, policy)
                    requireActiveAccount(manifest.account)
                    account = manifest.account
                    policy = manifest.policy.validated()
                    accountVerifiedAt = System.currentTimeMillis()
                    cachedVerificationTime.set(accountVerifiedAt)
                    cachedAccount.set(clientJson.encodeToString(ShiftVpsAccount.serializer(), manifest.account))
                }
                val selected = state.value.selectedId ?: throw ClientApiException("no_server")
                require(profiles.any { it.id == selected })
                applyManagedPolicy(policy)
                require(state.value.signedIn && profiles.any { it.id == selected })
                DataStore.selectedProxy.set(selected)
                state.update { it.copy(error = null, policyVersion = policy.version) }
                events.emit(ShiftVpsEvent.LaunchVpn)
            } catch (error: Exception) {
                handleFailure(error)
                metric("connect_failed", errorCode = diagnosticCode(error))
            } finally { connectionPreparation.unlock() }
        }
    }

    fun permissionDenied() {
        state.update { it.copy(error = "Разрешите VPN-подключение в системном окне, чтобы подключиться.") }
        metric("connect_failed", errorCode = "permission")
    }

    fun logout() {
        sessionGeneration++
        loginJob?.cancel()
        scope.launch {
            repository.stopService()
            operation.withLock {
                tokenAccess.withLock {
                    val token = tokens.load()
                    if (token != null) runCatching { api.logout(token) }
                    tokens.clear()
                }
                clearManagedProfiles()
                cachedAccount.set("")
                cachedPolicy.set("")
                cachedVerificationTime.set(0L)
                account = null
                accountVerifiedAt = 0L
                profiles = emptyList()
                metrics.clear()
                state.value = ShiftVpsUiState()
            }
        }
    }

    private suspend fun syncAndProbe(probe: Boolean) {
        if (!operation.tryLock()) return
        var shouldProbe = false
        val generation = sessionGeneration
        state.update { it.copy(refreshing = true) }
        try {
            val token = currentToken() ?: return
            val manifest = api.manifest(token, policy)
            requireActiveAccount(manifest.account)
            val parsed = withContext(Dispatchers.Default) { RawUpdater.parseRaw(api.feed(token)) }
                ?.takeIf { it.isNotEmpty() } ?: throw ClientApiException("invalid_feed")
            require(parsed.size <= 256)
            require(parsed.all { bean ->
                bean is VLESSBean || bean is TrojanBean || bean is NaiveBean || bean is ShadowsocksBean ||
                    bean is HysteriaBean || bean is MieruBean || bean is TrustTunnelBean || bean is TuicBean || bean is HttpBean
            })
            parsed.forEach { bean ->
                require(bean.serverAddress != "46.182.25.146")
                bean.customOutboundJson = ""
                bean.customConfigJson = ""
            }
            if (generation != sessionGeneration) return
            val group = managedGroup()
            val update = RawUpdater.tidyProxies(parsed, SubscriptionBean(), group, false, mutableListOf())
            profiles = SagerDatabase.proxyDao.getByGroup(group.id).first()
            account = manifest.account
            accountVerifiedAt = System.currentTimeMillis()
            policy = manifest.policy.validated()
            cachedPolicy.set(clientJson.encodeToString(ShiftVpsPolicy.serializer(), policy))
            cachedAccount.set(clientJson.encodeToString(ShiftVpsAccount.serializer(), manifest.account))
            cachedVerificationTime.set(accountVerifiedAt)
            state.update {
                it.copy(signedIn = true, accountName = manifest.account.name, policyVersion = policy.version,
                    lastUpdatedMillis = System.currentTimeMillis(), error = null, loading = false)
            }
            val previousSelected = state.value.selectedId
            publishProfiles()
            if (previousSelected != null && profiles.none { it.id == previousSelected } && state.value.serviceState.started) {
                repository.stopService()
            }
            metric("feed_updated")
            shouldProbe = probe || update.diff.changed > 0
        } catch (error: Exception) {
            handleFailure(error)
            metric("feed_update_failed", errorCode = diagnosticCode(error))
        } finally {
            state.update { it.copy(refreshing = false) }
            operation.unlock()
        }
        if (shouldProbe) surveySafely()
    }

    private suspend fun surveySafely() {
        survey.withLock {
            try { probeProfiles() } catch (error: Exception) { handleFailure(error) }
        }
    }

    private suspend fun probeProfiles() = coroutineScope {
        if (profiles.isEmpty()) return@coroutineScope
        applyManagedPolicy(policy)
        state.update { it.copy(probing = true, servers = it.servers.map { server -> server.copy(checking = true) }) }
        val gate = Semaphore(policy.latency.concurrency)
        try {
            profiles.toList().map { profile ->
                launch {
                    gate.withPermit {
                        val cache = mutableListOf<File>()
                        val latency = try {
                            val config = withContext(Dispatchers.IO) { buildConfig(profile, forTest = true) }
                            val plugins = withContext(Dispatchers.IO) {
                                buildPluginSpecs(config.metadata, initPlugins(config.metadata, cache))
                            }
                            core.standaloneUrlTest(config.configJson, "", policy.latency.testUrl,
                                policy.latency.timeoutMs, urlTestOptions(false, false), plugins).takeIf { it > 0 }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        } finally { cache.forEach { it.delete() } }
                        state.update { current ->
                            current.copy(servers = rankServers(current.servers.map { server ->
                                if (server.id == profile.id) server.copy(latencyMs = latency, checking = false, reachable = latency != null)
                                else server
                            }))
                        }
                    }
                }
            }.joinAll()
            // One bounded batch per survey; failure rows are most useful when a large list is probed.
            state.value.servers.sortedBy { if (it.reachable == false) 0 else 1 }.take(20).forEach { server ->
                profiles.firstOrNull { it.id == server.id }?.let { profile ->
                    metric("latency_sample", profile, server.latencyMs, if (server.reachable == false) "unknown" else null)
                }
            }
            if (!userSelectedServer && !state.value.serviceState.started) {
                state.value.servers.firstOrNull { it.reachable == true }?.let { best ->
                    state.update { it.copy(selectedId = best.id) }
                    DataStore.selectedProxy.set(best.id)
                }
            }
        } finally {
            state.update { it.copy(probing = false, servers = it.servers.map { server -> server.copy(checking = false) }) }
        }
    }

    private fun publishProfiles() {
        val previous = state.value.servers.associateBy { it.id }
        val servers = rankServers(profiles.map { profile ->
            previous[profile.id]?.copy(name = profile.displayName()) ?: ShiftVpsServer(profile.id, profile.displayName())
        })
        state.update { current ->
            val selected = current.selectedId?.takeIf { id -> servers.any { it.id == id } }
                ?: servers.firstOrNull()?.id
            current.copy(servers = servers, selectedId = selected)
        }
    }

    private suspend fun currentToken() = tokenAccess.withLock { tokens.load() }

    private suspend fun managedGroup(): ProxyGroup = SagerDatabase.groupDao.allGroups().first()
        .firstOrNull { it.name == MANAGED_GROUP }
        ?: GroupManager.createGroup(ProxyGroup(name = MANAGED_GROUP))

    private suspend fun managedProfiles(): List<ProxyEntity> {
        val group = SagerDatabase.groupDao.allGroups().first().firstOrNull { it.name == MANAGED_GROUP } ?: return emptyList()
        return SagerDatabase.proxyDao.getByGroup(group.id).first()
    }

    private suspend fun clearManagedProfiles() {
        SagerDatabase.groupDao.allGroups().first().filter { it.name == MANAGED_GROUP }.forEach { GroupManager.deleteGroup(it.id) }
        DataStore.selectedProxy.set(0L)
    }

    private suspend fun handleFailure(error: Exception) {
        if (error is CancellationException) throw error
        if (error is ClientApiException && (error.status == 401 || error.code in setOf("subscription_expired", "device_deleted"))) {
            sessionGeneration++
            repository.stopService()
            clearManagedProfiles()
            profiles = emptyList()
            account = null
            accountVerifiedAt = 0L
            cachedVerificationTime.set(0L)
            cachedAccount.set("")
            if (error.status == 401) tokenAccess.withLock { tokens.clear() }
            state.update { it.copy(servers = emptyList(), selectedId = null, signedIn = error.status != 401) }
        }
        val message = when (diagnosticCode(error)) {
            "subscription_expired" -> "Подписка закончилась. Продлите её в личном кабинете."
            "authentication" -> "Войдите в аккаунт ещё раз."
            "timeout" -> "Сервер не ответил вовремя. Попробуйте ещё раз."
            "tls" -> "Не удалось проверить защищённое соединение. Попробуйте другую сеть."
            else -> "Не удалось обновить данные. Проверьте интернет и попробуйте ещё раз."
        }
        state.update { it.copy(error = message) }
    }

    private fun metric(event: String, profile: ProxyEntity? = profiles.firstOrNull { it.id == state.value.selectedId }, latency: Int? = null, errorCode: String? = null) {
        if (!policy.telemetry.enabled || !state.value.signedIn) return
        if (metrics.size >= 100) metrics.removeFirst()
        metrics.addLast(ClientMetric(event, profile?.let(::diagnosticServerId), latency?.coerceIn(0, 60000), errorCode,
            BuildConfig.VERSION_NAME.substringBefore('-').take(32), platform(), policy.version))
    }

    private suspend fun flushMetrics() {
        if (!policy.telemetry.enabled) { metrics.clear(); return }
        val token = currentToken() ?: return
        val batch = metrics.take(20)
        if (batch.isEmpty()) return
        try {
            api.metrics(token, batch)
            repeat(batch.size.coerceAtMost(metrics.size)) { metrics.removeFirst() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    override fun close() { scope.cancel() }

    companion object { private const val MANAGED_GROUP = "ShiftVPS managed" }
}

internal fun recentVerification(verifiedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
    verifiedAt > 0L && now - verifiedAt in 0L..86_400_000L

internal fun requireActiveAccount(account: ShiftVpsAccount) {
    if (!account.accessActive) throw ClientApiException("subscription_expired", 403)
    val expires = account.subscriptionExpiresAt ?: return
    val instant = runCatching { Instant.parse(expires) }.getOrElse {
        runCatching { LocalDateTime.parse(expires.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
            .getOrElse { throw ClientApiException("invalid_account") }
    }
    if (!instant.isAfter(Instant.now())) throw ClientApiException("subscription_expired", 403)
}

private fun platform(): String = when {
    PlatformInfo.isAndroid -> "android"
    PlatformInfo.isMacOs -> "macos"
    else -> "windows"
}

private fun platformName(): String = when (platform()) {
    "android" -> "ShiftVPS Android"
    "macos" -> "ShiftVPS Mac"
    else -> "ShiftVPS Windows"
}

private fun diagnosticCode(error: Exception): String = when (error) {
    is SocketTimeoutException -> "timeout"
    is UnknownHostException -> "dns"
    is SSLException -> "tls"
    is ClientApiException -> when {
        error.code == "subscription_expired" -> "subscription_expired"
        error.status == 401 -> "authentication"
        else -> "configuration"
    }
    else -> "network_unreachable"
}

private fun diagnosticServerId(profile: ProxyEntity): String {
    val bean = profile.requireBean()
    val protocol = when (bean) {
        is VLESSBean -> "vless"
        is TrojanBean -> "trojan"
        is NaiveBean -> "naive"
        is ShadowsocksBean -> "ss"
        is MieruBean -> "mieru"
        is TrustTunnelBean -> "trusttunnel"
        is TuicBean -> "tuic"
        is HysteriaBean -> if (bean.protocolVersion == HysteriaBean.PROTOCOL_VERSION_2) "hysteria2" else "hysteria"
        is HttpBean -> if (bean.isTLS) "https" else "http"
        else -> "unknown"
    }
    val identity = "$protocol|${bean.serverAddress.lowercase()}|${bean.serverPort}"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
