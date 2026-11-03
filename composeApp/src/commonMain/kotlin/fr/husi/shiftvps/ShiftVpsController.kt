package fr.husi.shiftvps

import fr.husi.BuildConfig
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.buildPluginSpecs
import fr.husi.bg.initPlugins
import fr.husi.core.CoreClient
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.database.preference.string
import fr.husi.database.preference.long
import fr.husi.database.preference.int
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
import fr.husi.ktx.Logs
import fr.husi.platform.PlatformInfo
import fr.husi.repository.resolveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
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
    companion object {
        private const val MANAGED_GROUP = "ShiftVPS managed"
        private val activeController = java.util.concurrent.atomic.AtomicReference<ShiftVpsController?>()

        /** Serializes a desktop installer with account verification and a queued connect action. */
        internal fun reserveApplicationUpdate(): AutoCloseable? =
            activeController.get()?.reserveUpdatePreparation()
    }
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
    private var summaryJob: Job? = null
    private var sessionGeneration = 0
    private var policy = ShiftVpsPolicy()
    private var account: ShiftVpsAccount? = null
    private var accountVerifiedAt = 0L
    private var userSelectedServer = false
    private var selectedProfileId: Long? = null
    private var connectionPending = false
    private var profiles = emptyList<ProxyEntity>()
    private var groups = emptyList<ShiftVpsServerGroup>()
    private val measurements = mutableMapOf<Long, ServerCandidate>()
    private val metrics = ClientMetricOutbox.decode("", System.currentTimeMillis())
    private var metricOwner: String? = null
    private val repository = resolveRepository()
    private val core: CoreClient = GlobalContext.get().get()
    private val cachedPolicy = DataStore.configurationStore.string("shiftvps.policy")
    private val cachedAccount = DataStore.configurationStore.string("shiftvps.account")
    private val cachedVerificationTime = DataStore.configurationStore.long("shiftvps.account_verified_at")
    private val cachedGroups = DataStore.configurationStore.string("shiftvps.server_groups")

    val state: StateFlow<ShiftVpsUiState>
        field = MutableStateFlow(ShiftVpsUiState())

    private val eventQueue = Channel<ShiftVpsEvent>(Channel.BUFFERED)
    val events: Flow<ShiftVpsEvent> = eventQueue.receiveAsFlow()

    init {
        activeController.set(this)
        scope.launch {
            var previous = ServiceState.Idle
            BackendState.status.collect { status ->
                state.update { it.copy(serviceState = status.state) }
                if (status.state == ServiceState.Stopped) connectionPending = false
                publishProfiles()
                when {
                    PlatformInfo.isAndroid -> Unit
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
                if (!PlatformInfo.isAndroid) metric("connect_failed", errorCode = "configuration")
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
                    groups = runCatching {
                        clientJson.decodeFromString<List<ShiftVpsServerGroup>>(cachedGroups.get())
                    }.getOrDefault(emptyList())
                    selectedProfileId = DataStore.selectedProxy.get().takeIf { id -> profiles.any { it.id == id } }
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
            var stage = LoginStage.AUTHORIZE
            try {
                val verifier = createVerifier()
                val grant = api.authorize(platform(), platformName(), verifierChallenge(verifier))
                state.update { it.copy(authCode = grant.userCode, loading = false) }
                eventQueue.send(ShiftVpsEvent.OpenBrowser(grant.verificationUri))
                val deadline = System.currentTimeMillis() + grant.expiresIn * 1000L
                var interval = grant.interval
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    try {
                        stage = LoginStage.POLL
                        val token = api.token(grant.grantId, verifier)
                        tokenAccess.withLock {
                            if (sessionGeneration != generation) return@launch
                            stage = LoginStage.STORE
                            tokens.save(token)
                            metricOwner = tokenOwner(token)
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
                if (error is CancellationException) throw error
                val diagnostic = loginFailureDiagnostic(stage, error)
                Logs.w("ShiftVPS login failed: $diagnostic")
                handleFailure(error)
                state.update { it.copy(error = "${it.error.orEmpty()}\nКод: $diagnostic") }
            } finally {
                state.update { it.copy(loading = false, authCode = null) }
            }
        }
    }

    fun cancelLogin() { loginJob?.cancel() }
    fun dismissError() { state.update { it.copy(error = null) } }

    fun refresh() { scope.launch { syncAndProbe(true) } }
    fun refreshSummary() {
        if (!state.value.signedIn || summaryJob?.isActive == true) return
        summaryJob = scope.launch {
            val generation = sessionGeneration
            val token = summaryToken()
            if (generation != sessionGeneration) return@launch
            if (token == null) {
                state.update { it.copy(summaryError = "Не удалось загрузить данные аккаунта.") }
                return@launch
            }
            state.update { it.copy(summaryLoading = true, summaryError = null) }
            try {
                val summary = api.accountSummary(token)
                if (!state.value.signedIn || summaryToken() != token) return@launch
                state.update { it.copy(summary = summary, accountName = summary.account.name) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!state.value.signedIn || summaryToken() != token) return@launch
                if (generation == sessionGeneration && error is ClientApiException && error.status == 401) {
                    handleFailure(error)
                } else {
                    state.update { it.copy(summaryError = "Не удалось загрузить данные аккаунта.") }
                }
            } finally {
                if (generation == sessionGeneration || state.value.signedIn && summaryToken() == token) {
                    state.update { it.copy(summaryLoading = false) }
                }
            }
        }
    }

    fun openAccount() { openPage(ACCOUNT_PAGE) }
    fun openPayment() { openPage(PAYMENT_PAGE) }
    fun openReferral() { openPage(REFERRAL_PAGE) }
    private fun openPage(url: String) { scope.launch { eventQueue.send(ShiftVpsEvent.OpenBrowser(url)) } }

    private suspend fun summaryToken(): String? = try {
        currentToken()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    fun checkServers() {
        scope.launch { surveySafely() }
    }

    fun selectServer(id: Long) {
        if (state.value.serviceState.started || connectionPending) return
        val candidates = candidates()
        val row = serverClusters(candidates, groups).firstOrNull { it.id == id } ?: return
        val profileId = chooseProfile(row, candidates) ?: return
        userSelectedServer = true
        selectedProfileId = profileId
        state.update { it.copy(selectedId = id) }
        scope.launch { DataStore.selectedProxy.set(profileId) }
    }

    fun toggleConnection() {
        if (state.value.serviceState.canStop) {
            repository.stopService()
            return
        }
        scope.launch {
            if (!connectionPreparation.tryLock()) return@launch
            val generation = sessionGeneration
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
                val candidates = candidates()
                val row = serverClusters(candidates, groups).firstOrNull { it.id == state.value.selectedId }
                    ?: throw ClientApiException("no_server")
                val selected = chooseProfile(row, candidates) ?: throw ClientApiException("no_server")
                applyManagedPolicy(policy)
                val selectedProfile = profiles.firstOrNull { it.id == selected } ?: throw ClientApiException("no_server")
                if (!isManagedProfileCompatible(selectedProfile)) throw ClientApiException("unsupported_transport")
                require(state.value.signedIn)
                selectedProfileId = selected
                connectionPending = true
                DataStore.selectedProxy.set(selected)
                DataStore.configurationStore.int("shiftvps.active_policy_version").set(policy.version)
                if (generation != sessionGeneration || !state.value.signedIn) {
                    connectionPending = false
                    return@launch
                }
                state.update { it.copy(error = null, policyVersion = policy.version) }
                prepareServiceMetrics(tokenOwner(token), policy.version)
                eventQueue.send(ShiftVpsEvent.LaunchVpn)
            } catch (error: Exception) {
                handleFailure(error)
                metric("connect_failed", errorCode = diagnosticCode(error))
            } finally { connectionPreparation.unlock() }
        }
    }

    fun permissionDenied() {
        connectionPending = false
        state.update { it.copy(error = "Разрешите VPN-подключение в системном окне, чтобы подключиться.") }
        metric("connect_failed", errorCode = "permission")
    }

    fun logout() {
        sessionGeneration++
        summaryJob?.cancel()
        metricOwner = null
        metrics.clear()
        prepareServiceMetrics(null, policy.version)
        loginJob?.cancel()
        connectionPending = false
        while (eventQueue.tryReceive().isSuccess) { /* Discard actions from the previous session. */ }
        state.update { it.copy(signedIn = false, servers = emptyList(), selectedId = null, authCode = null, loading = true,
            summary = null, summaryLoading = false, summaryError = null) }
        repository.stopService()
        scope.launch {
            try {
                val token = tokenAccess.withLock {
                    val previous = runCatching { tokens.load() }.getOrNull()
                    tokens.clear()
                    previous
                }
                operation.withLock {
                    clearManagedProfiles()
                    cachedAccount.set("")
                    cachedPolicy.set("")
                    cachedVerificationTime.set(0L)
                    cachedGroups.set("")
                    account = null
                    accountVerifiedAt = 0L
                    profiles = emptyList()
                    groups = emptyList()
                    measurements.clear()
                    selectedProfileId = null
                    metrics.clear()
                    state.value = ShiftVpsUiState()
                }
                if (token != null) runCatching { api.logout(token) }
            } catch (error: Exception) {
                handleFailure(error)
                state.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun syncAndProbe(probe: Boolean) {
        if (!operation.tryLock()) return
        refreshSummary()
        var shouldProbe = false
        val generation = sessionGeneration
        state.update { it.copy(refreshing = true) }
        try {
            val token = currentToken() ?: return
            val manifest = api.manifest(token, policy)
            requireActiveAccount(manifest.account)
            val parsed = withContext(Dispatchers.Default) { RawUpdater.parseRaw(api.feed(token)) }
                ?.takeIf { it.isNotEmpty() } ?: throw ClientApiException("invalid_feed")
            val compatible = prepareManagedFeed(parsed)
            if (generation != sessionGeneration) return
            val group = managedGroup()
            val update = RawUpdater.tidyProxies(compatible, SubscriptionBean(), group, false, mutableListOf())
            val previousSelectedProfile = selectedProfileId
            val updatedProfiles = SagerDatabase.proxyDao.getByGroup(group.id).first().filter { isManagedProfileCompatible(it) }
            if (generation != sessionGeneration) return
            profiles = updatedProfiles
            groups = manifest.serverGroups
            account = manifest.account
            accountVerifiedAt = System.currentTimeMillis()
            policy = manifest.policy.validated()
            cachedPolicy.set(clientJson.encodeToString(ShiftVpsPolicy.serializer(), policy))
            cachedAccount.set(clientJson.encodeToString(ShiftVpsAccount.serializer(), manifest.account))
            cachedVerificationTime.set(accountVerifiedAt)
            cachedGroups.set(clientJson.encodeToString(ListSerializer(ShiftVpsServerGroup.serializer()), groups))
            state.update {
                it.copy(signedIn = true, accountName = manifest.account.name, policyVersion = policy.version,
                    lastUpdatedMillis = System.currentTimeMillis(), error = null, loading = false)
            }
            publishProfiles()
            if (previousSelectedProfile != null && profiles.none { it.id == previousSelectedProfile } && state.value.serviceState.started) {
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
        profiles.forEach { profile ->
            measurements[profile.id] = candidate(profile).copy(checking = true)
        }
        state.update { it.copy(probing = true) }
        publishProfiles()
        val gate = Semaphore(policy.latency.concurrency)
        try {
            profiles.toList().map { profile ->
                launch {
                    gate.withPermit {
                        val cache = mutableListOf<File>()
                        val result = try {
                            val config = withContext(Dispatchers.IO) { buildConfig(profile, forTest = true) }
                            val plugins = withContext(Dispatchers.IO) {
                                buildPluginSpecs(config.metadata, initPlugins(config.metadata, cache))
                            }
                            probeServer(core, config, policy.latency, plugins)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            ServerProbeResult.Inconclusive(ServerProbeFailure.Setup)
                        } finally { cache.forEach { it.delete() } }
                        val latency = (result as? ServerProbeResult.Success)?.latencyMs
                        val failure = (result as? ServerProbeResult.Inconclusive)?.reason
                        if (failure != null) Logs.w("ShiftVPS server probe inconclusive: ${failure.name}")
                        measurements[profile.id] = candidate(profile).copy(
                            latencyMs = latency, checking = false, checked = true, probeFailure = failure,
                        )
                        publishProfiles()
                    }
                }
            }.joinAll()
            // One bounded batch per survey; failure rows are most useful when a large list is probed.
            candidates().sortedBy { if (it.checked && it.latencyMs == null) 0 else 1 }.take(20).forEach { candidate ->
                profiles.firstOrNull { it.id == candidate.profileId }?.let { profile ->
                    metric("latency_sample", profile, candidate.latencyMs, candidate.probeFailure?.metricCode)
                }
            }
            if (!userSelectedServer && !state.value.serviceState.started && !connectionPending) {
                state.value.servers.firstOrNull { it.reachable == true }?.let { best ->
                    state.update { it.copy(selectedId = best.id) }
                    publishProfiles()
                }
            }
        } finally {
            measurements.replaceAll { _, candidate -> candidate.copy(checking = false) }
            state.update { it.copy(probing = false) }
            publishProfiles()
        }
    }

    private fun publishProfiles() {
        val candidates = candidates()
        val clusters = serverClusters(candidates, groups)
        val pinned = selectedProfileId.takeIf { state.value.serviceState.started || connectionPending }
        val servers = rankServers(clusters.map { cluster -> clusterRow(cluster, candidates, pinned) })
        state.update { current ->
            val selected = pinned?.let { profileId -> clusters.firstOrNull { profileId in it.profileIds }?.id }
                ?: current.selectedId?.takeIf { id -> servers.any { it.id == id } }
                ?: servers.firstOrNull()?.id
            if (pinned == null) {
                selectedProfileId = clusters.firstOrNull { it.id == selected }?.let { chooseProfile(it, candidates) }
            }
            current.copy(servers = servers, selectedId = selected)
        }
    }

    private fun candidate(profile: ProxyEntity): ServerCandidate {
        val endpoint = diagnosticServerId(profile)
        return measurements[profile.id]?.takeIf { it.endpointId == endpoint }
            ?.copy(name = profile.displayName())
            ?: ServerCandidate(profile.id, endpoint, profile.displayName())
    }

    private fun candidates(): List<ServerCandidate> = profiles.map(::candidate)

    private suspend fun currentToken(): String? {
        val generation = sessionGeneration
        return tokenAccess.withLock {
            tokens.load().also { token ->
                if (generation == sessionGeneration) metricOwner = token?.let(::tokenOwner)
            }
        }
    }

    private suspend fun managedGroup(): ProxyGroup = SagerDatabase.groupDao.allGroups().first()
        .firstOrNull { it.name == MANAGED_GROUP }
        ?: GroupManager.createGroup(ProxyGroup(name = MANAGED_GROUP))

    private suspend fun managedProfiles(): List<ProxyEntity> {
        val group = SagerDatabase.groupDao.allGroups().first().firstOrNull { it.name == MANAGED_GROUP } ?: return emptyList()
        return SagerDatabase.proxyDao.getByGroup(group.id).first().filter { isManagedProfileCompatible(it) }
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
            groups = emptyList()
            measurements.clear()
            selectedProfileId = null
            connectionPending = false
            metrics.clear()
            metricOwner = null
            prepareServiceMetrics(null, policy.version)
            while (eventQueue.tryReceive().isSuccess) { /* Revoked sessions cannot launch queued actions. */ }
            account = null
            accountVerifiedAt = 0L
            cachedVerificationTime.set(0L)
            cachedAccount.set("")
            if (error.status == 401) tokenAccess.withLock { tokens.clear() }
            state.update { it.copy(servers = emptyList(), selectedId = null, signedIn = error.status != 401,
                summary = if (error.status == 401 || error.code == "device_deleted") null else it.summary,
                summaryLoading = false) }
        }
        val message = if (error is ClientApiException && error.code == "unsupported_transport") {
            "В подписке нет совместимых подключений. Обновите приложение или обратитесь в поддержку."
        } else if (error is ClientApiException && error.code == "device_limit") {
            "Достигнут лимит устройств. Удалите старое устройство в личном кабинете и повторите вход."
        } else when (diagnosticCode(error)) {
            "subscription_expired" -> "Подписка закончилась. Продлите её в личном кабинете."
            "authentication" -> "Войдите в аккаунт ещё раз."
            "timeout" -> "Сервер не ответил вовремя. Попробуйте ещё раз."
            "tls" -> "Не удалось проверить защищённое соединение. Попробуйте другую сеть."
            else -> "Не удалось обновить данные. Проверьте интернет и попробуйте ещё раз."
        }
        state.update { it.copy(error = message) }
    }

    private fun metric(event: String, profile: ProxyEntity? = profiles.firstOrNull { it.id == selectedProfileId }, latency: Int? = null, errorCode: String? = null) {
        if (!policy.telemetry.enabled || !state.value.signedIn) return
        val owner = metricOwner ?: return
        val now = System.currentTimeMillis()
        metrics.append(owner, ClientMetric(event, profile?.let(::diagnosticServerId), latency?.coerceIn(0, 60000), errorCode,
            BuildConfig.VERSION_NAME.substringBefore('-').take(32), platform(), policy.version, occurredAt = now / 1000L), now)
    }

    private suspend fun flushMetrics() {
        if (!policy.telemetry.enabled) { metrics.clear(); return }
        val token = currentToken() ?: return
        val owner = tokenOwner(token)
        val batch = metrics.peek(owner, System.currentTimeMillis())
        if (batch.isEmpty()) return
        try {
            api.metrics(token, batch.map { it.metric }, metricBatchId(owner, batch))
            metrics.acknowledge(owner, batch.map { it.id }.toSet())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is ClientApiException && (error.status == 401 || error.code == "subscription_expired")) {
                if (currentToken() == token) handleFailure(error)
            }
        }
    }

    private fun reserveUpdatePreparation(): AutoCloseable? {
        if (!connectionPreparation.tryLock()) return null
        if (connectionPending || !canRestartApplication(BackendState.status.value.state)) {
            connectionPreparation.unlock()
            return null
        }
        return AutoCloseable { connectionPreparation.unlock() }
    }

    override fun close() {
        activeController.compareAndSet(this, null)
        scope.cancel()
    }

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

internal enum class LoginStage { AUTHORIZE, POLL, STORE }

internal fun loginFailureDiagnostic(stage: LoginStage, error: Exception): String {
    val status = (error as? ClientApiException)?.status?.takeIf { it in 100..599 } ?: 0
    val storage = if (stage == LoginStage.STORE) when (error.javaClass.simpleName) {
        "KeyStoreException" -> "KEYSTORE"
        "KeyPermanentlyInvalidatedException" -> "KEY_INVALID"
        "UnrecoverableKeyException" -> "KEY_READ"
        "UserNotAuthenticatedException" -> "KEY_AUTH"
        "ProviderException" -> "PROVIDER"
        "FileNotFoundException" -> "FILE"
        "IOException" -> "IO"
        "SecurityException" -> "PERMISSION"
        else -> null
    } else null
    val reason = storage ?: when (diagnosticCode(error)) {
        "timeout" -> "TIMEOUT"
        "dns" -> "DNS"
        "tls" -> "TLS"
        "subscription_expired" -> "ACCESS"
        "authentication" -> "AUTH"
        "configuration" -> "API"
        else -> "NET"
    }
    return "L-${stage.name.first()}-$reason-$status"
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

internal fun diagnosticServerId(profile: ProxyEntity): String {
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
