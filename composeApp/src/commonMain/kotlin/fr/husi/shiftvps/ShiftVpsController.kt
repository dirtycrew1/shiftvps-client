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
import kotlinx.serialization.Serializable
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.net.ssl.SSLException

class ShiftVpsController : AutoCloseable, ShiftVpsAccountActions {
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
    private val cachedAccountIntents = DataStore.configurationStore.string("shiftvps.account_intents")

    val state: StateFlow<ShiftVpsUiState>
        field = MutableStateFlow(ShiftVpsUiState())

    private val eventQueue = Channel<ShiftVpsEvent>(Channel.BUFFERED)
    val events: Flow<ShiftVpsEvent> = eventQueue.receiveAsFlow()

    private val accountCoordinator by lazy {
        ShiftVpsAccountCoordinator(scope, api, state, ::accountIdentity, object : AccountIntentStore {
            override suspend fun load(): String = cachedAccountIntents.get()
            override suspend fun save(value: String) { cachedAccountIntents.set(value) }
        }, openUrl = { eventQueue.send(ShiftVpsEvent.OpenBrowser(it)) }, invalidIdentity = { owner, error ->
            scope.launch { if (owner.same(accountIdentity())) handleFailure(error) }
        }, signOut = { owner ->
            scope.launch { if (owner.same(accountIdentity())) logoutSession(notifyServer = false) }
        })
    }

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
                scope.launch { accountCoordinator.refresh().join(); syncAndProbe(policy.latency.probeOnLaunch) }
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
                    accountCoordinator.refresh().join()
                    syncAndProbe(policy.latency.probeOnLaunch)
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
        if (state.value.signedIn || loginJob?.isActive == true) return
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
                        val issued = api.identityToken(grant.grantId, verifier)
                        val token = issued.token
                        tokenAccess.withLock {
                            if (sessionGeneration != generation) return@launch
                            stage = LoginStage.STORE
                            tokens.save(token)
                            metricOwner = tokenOwner(token)
                        }
                        state.update { it.copy(authCode = null, signedIn = true, loading = true,
                            accessState = accessState(issued.accessActive)) }
                        accountCoordinator.refresh().join()
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
                state.update { it.copy(summary = summary, accountName = summary.account.name,
                    accessState = accessState(summary.account.accessActive)) }
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

    private suspend fun accountIdentity(): AccountIdentity? {
        val generation = sessionGeneration
        val token = summaryToken() ?: return null
        return if (generation == sessionGeneration) AccountIdentity(token, generation) else null
    }

    override fun refreshAccountDetails() { accountCoordinator.refreshAccountDetails(); refreshSummary() }
    override fun addDevice(name: String) { accountCoordinator.addDevice(name) }
    override fun renameDevice(deviceId: Long, name: String) { accountCoordinator.renameDevice(deviceId, name) }
    override fun deleteDevice(deviceId: Long) { accountCoordinator.deleteDevice(deviceId) }
    override fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String) {
        accountCoordinator.changePassword(currentPassword, newPassword, confirmPassword)
    }
    override fun unlinkTelegram() { accountCoordinator.unlinkTelegram() }
    override fun createCheckout(planCode: String, paymentMethod: String) { accountCoordinator.createCheckout(planCode, paymentMethod) }
    override fun requestLegacyPayout(network: String, wallet: String) { accountCoordinator.requestLegacyPayout(network, wallet) }
    override fun reconcileAccountOperation(requestId: String) { accountCoordinator.reconcileAccountOperation(requestId) }
    override fun openAccountLink(link: ShiftVpsAccountLink) { accountCoordinator.openAccountLink(link) }
    override fun dismissAccountAction() { accountCoordinator.dismissAccountAction() }

    fun checkServers() {
        if (state.value.accessState != ShiftVpsAccessState.Active) return
        scope.launch { surveySafely() }
    }

    fun selectServer(id: Long) {
        if (state.value.accessState != ShiftVpsAccessState.Active) return
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
        if (state.value.accessState != ShiftVpsAccessState.Active) {
            state.update { it.copy(error = if (it.accessState == ShiftVpsAccessState.Inactive)
                "Подписка не активна. Выберите тариф в аккаунте." else "Сначала обновите данные аккаунта.") }
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
                storeManagedAndroidRouting(selectedProfile, policy)
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

    fun logout() { logoutSession(notifyServer = true) }

    private fun logoutSession(notifyServer: Boolean) {
        sessionGeneration++
        summaryJob?.cancel()
        accountCoordinator.reset()
        metricOwner = null
        metrics.clear()
        prepareServiceMetrics(null, policy.version)
        loginJob?.cancel()
        connectionPending = false
        while (eventQueue.tryReceive().isSuccess) { /* Discard actions from the previous session. */ }
        state.update { it.copy(signedIn = false, servers = emptyList(), selectedId = null, authCode = null, loading = true,
            summary = null, summaryLoading = false, summaryError = null, accessState = ShiftVpsAccessState.Unknown,
            accountDetails = null, accountDetailsLoading = false, accountDetailsError = null,
            accountAction = ShiftVpsAccountActionState()) }
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
                    managedAndroidRoutingSnapshot.set("")
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
                if (notifyServer && token != null) runCatching { api.logout(token) }
            } catch (error: Exception) {
                handleFailure(error)
                state.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun syncAndProbe(probe: Boolean) {
        if (!operation.tryLock()) return
        var shouldProbe = false
        val generation = sessionGeneration
        state.update { it.copy(refreshing = true) }
        try {
            refreshSummary()
            summaryJob?.join()
            val token = currentToken() ?: return
            if (state.value.accessState != ShiftVpsAccessState.Active) {
                if (state.value.accessState == ShiftVpsAccessState.Inactive &&
                    (profiles.isNotEmpty() || state.value.serviceState.started || connectionPending)) {
                    handleFailure(ClientApiException("subscription_expired", 403))
                    accountCoordinator.refresh().join()
                }
                return
            }
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
        if (state.value.accessState != ShiftVpsAccessState.Active) return
        survey.withLock {
            try { probeProfiles() } catch (error: Exception) { handleFailure(error) }
        }
    }

    private suspend fun probeProfiles() = coroutineScope {
        if (state.value.accessState != ShiftVpsAccessState.Active) return@coroutineScope
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
            summaryJob?.cancel()
            accountCoordinator.reset()
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
                summaryLoading = false, accessState = if (error.status == 401) ShiftVpsAccessState.Unknown else ShiftVpsAccessState.Inactive,
                accountDetails = if (error.status == 401 || error.code == "device_deleted") null else it.accountDetails,
                accountDetailsLoading = false, accountAction = ShiftVpsAccountActionState()) }
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

internal class AccountIdentity(val token: String, val generation: Int) {
    fun same(other: AccountIdentity?): Boolean = other != null && token == other.token && generation == other.generation
    override fun toString(): String = "AccountIdentity"
}

internal interface AccountIntentStore {
    suspend fun load(): String
    suspend fun save(value: String)
}

@Serializable
internal data class SavedAccountIntent(val accountId: Long, val kind: String, val requestId: String, val fingerprint: String)

/** Account effects are isolated from the VPN repository and can be tested without launching a client. */
internal class ShiftVpsAccountCoordinator(
    private val scope: CoroutineScope,
    private val api: ShiftVpsApi,
    private val state: MutableStateFlow<ShiftVpsUiState>,
    private val identity: suspend () -> AccountIdentity?,
    private val intents: AccountIntentStore,
    private val openUrl: suspend (String) -> Unit,
    private val invalidIdentity: (AccountIdentity, ClientApiException) -> Unit,
    private val signOut: (AccountIdentity) -> Unit,
) : ShiftVpsAccountActions {
    private val mutation = Mutex()
    private var refreshJob: Job? = null
    private var mutationJob: Job? = null
    private var detailsIdentity: AccountIdentity? = null
    private var revision = 0L
    private val retryAllowed = mutableSetOf<String>()

    override fun refreshAccountDetails() { refresh() }

    internal fun refresh(): Job {
        if (state.value.accountAction.busy) return scope.launch { }
        refreshJob?.cancel()
        val requestRevision = ++revision
        return scope.launch {
            val owner = identity() ?: return@launch
            if (!owner.same(detailsIdentity)) state.update { it.copy(accountDetails = null) }
            state.update { it.copy(accountDetailsLoading = true, accountDetailsError = null) }
            try {
                val details = api.accountDetails(owner.token)
                if (!current(owner) || requestRevision != revision) return@launch
                detailsIdentity = owner
                state.update { it.copy(accountDetails = details, accountName = details.account.name,
                    accessState = accessState(details.account.accessActive), accountDetailsError = null) }
                try {
                    val saved = savedIntents().firstOrNull { it.accountId == details.accountId }
                    if (!current(owner) || requestRevision != revision) return@launch
                    if (saved != null && state.value.accountAction.kind == null) state.update {
                        it.copy(accountAction = ShiftVpsAccountActionState(actionKind(saved.kind),
                            ShiftVpsAccountActionPhase.Uncertain, errorCode = "operation_unknown", requestId = saved.requestId))
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (!current(owner) || requestRevision != revision) return@launch
                    state.update { it.copy(accountAction = ShiftVpsAccountActionState(
                        phase = ShiftVpsAccountActionPhase.Error, errorCode = "operation_state_unavailable")) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!current(owner) || requestRevision != revision) return@launch
                if (error is ClientApiException && error.status == 401) invalidIdentity(owner, error)
                else state.update { it.copy(accountDetailsError = "account_details_unavailable") }
            } finally {
                if (current(owner) && requestRevision == revision) state.update { it.copy(accountDetailsLoading = false) }
            }
        }.also { refreshJob = it }
    }

    override fun addDevice(name: String) {
        val normalized = runCatching { validDeviceName(name) }.getOrElse { inputError(); return }
        mutate(ShiftVpsAccountActionKind.AddDevice, durableKind = "device_add", payload = normalized,
            validate = { if (!it.capabilities.addDevice) throw ClientApiException("subscription_expired", 403) }) { token, requestId ->
            MutationResult(operation = api.addDevice(token, normalized, requestId!!).operation)
        }
    }

    override fun renameDevice(deviceId: Long, name: String) {
        val normalized = runCatching { validDeviceName(name) }.getOrElse { inputError(); return }
        mutate(ShiftVpsAccountActionKind.RenameDevice, deviceId,
            validate = { require(it.devices.any { row -> row.id == deviceId }) }) { token, _ ->
            api.renameDevice(token, deviceId, normalized)
            MutationResult()
        }
    }

    override fun deleteDevice(deviceId: Long) {
        mutate(ShiftVpsAccountActionKind.DeleteDevice, deviceId,
            validate = { require(it.devices.any { row -> row.id == deviceId }) }) { token, _ ->
            val current = details().currentDeviceId
            val response = api.deleteDevice(token, deviceId)
            require(response.currentDeviceDeleted == (current == deviceId))
            MutationResult(signOut = response.logoutRequired)
        }
    }

    override fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String) {
        if (currentPassword.codePointCount(0, currentPassword.length) !in 1..256 ||
            newPassword.codePointCount(0, newPassword.length) !in 12..256 || newPassword != confirmPassword) {
            inputError(); return
        }
        mutate(ShiftVpsAccountActionKind.ChangePassword,
            validate = { require(it.capabilities.changePassword) }) { token, _ ->
            api.changePassword(token, currentPassword, newPassword, confirmPassword)
            MutationResult(signOut = true)
        }
    }

    override fun unlinkTelegram() {
        mutate(ShiftVpsAccountActionKind.UnlinkTelegram,
            validate = { require(it.capabilities.unlinkTelegram) }) { token, _ ->
            api.unlinkTelegram(token)
            MutationResult()
        }
    }

    override fun createCheckout(planCode: String, paymentMethod: String) {
        mutate(ShiftVpsAccountActionKind.Checkout, durableKind = "checkout", payload = "$planCode|$paymentMethod", validate = { account ->
            require(account.capabilities.checkout && account.billing.onlineAvailable)
            require(account.plans.any { it.code == planCode } && paymentMethod in account.billing.paymentMethods)
        }) { token, requestId ->
            MutationResult(operation = api.createCheckout(token, planCode, paymentMethod, requestId!!).operation)
        }
    }

    override fun requestLegacyPayout(network: String, wallet: String) {
        if (network !in setOf("TRC20", "ERC20") || !wallet.matches(Regex("[A-Za-z0-9]{20,80}"))) {
            inputError(); return
        }
        mutate(ShiftVpsAccountActionKind.LegacyPayout, durableKind = "payout", payload = "$network|$wallet",
            validate = { require(it.legacyReferral.canRequestPayout && !it.legacyReferral.hasPendingPayout) }) { token, requestId ->
            MutationResult(operation = api.requestLegacyPayout(token, network, wallet, requestId!!).operation)
        }
    }

    override fun reconcileAccountOperation(requestId: String) {
        if (runCatching { requireRequestId(requestId) }.isFailure) { inputError(); return }
        mutate(ShiftVpsAccountActionKind.Reconcile, suppliedRequestId = requestId) { token, _ ->
            MutationResult(operation = api.accountOperation(token, requestId).operation)
        }
    }

    private data class MutationResult(val operation: ShiftVpsOperation? = null, val signOut: Boolean = false)

    private fun mutate(kind: ShiftVpsAccountActionKind, deviceId: Long? = null, durableKind: String? = null,
        payload: String = "", suppliedRequestId: String? = null,
        validate: (ShiftVpsFullAccount) -> Unit = {},
        perform: suspend (String, String?) -> MutationResult) {
        if (mutationJob?.isActive == true) return
        if (state.value.accountAction.phase == ShiftVpsAccountActionPhase.Uncertain && kind != ShiftVpsAccountActionKind.Reconcile) return
        mutationJob = scope.launch {
            if (!mutation.tryLock()) return@launch
            var owner: AccountIdentity? = null
            var requestId = suppliedRequestId
            var sent = false
            try {
                owner = identity() ?: throw ClientApiException("invalid_token", 401)
                if (!owner.same(detailsIdentity) || !state.value.signedIn) throw ClientApiException("account_details_unavailable")
                ++revision; refreshJob?.cancel()
                state.update { it.copy(accountDetailsLoading = false, accountAction = ShiftVpsAccountActionState(
                    kind, ShiftVpsAccountActionPhase.Running, deviceId, requestId = requestId)) }
                validate(details())
                if (durableKind != null) {
                    val account = details()
                    val saved = savedIntents()
                    val previous = saved.firstOrNull { it.accountId == account.accountId && it.kind == durableKind }
                    val serverPending = account.pendingOperations.firstOrNull { it.kind == durableKind && !terminal(it) }
                    val fingerprint = fingerprint("$durableKind|$payload")
                    if (previous != null) {
                        requestId = previous.requestId
                        if (requestId !in retryAllowed || previous.fingerprint != fingerprint) throw ClientApiException("operation_pending")
                        retryAllowed.remove(requestId)
                    } else if (serverPending != null) {
                        requestId = serverPending.requestId
                        throw ClientApiException("operation_pending")
                    } else {
                        requestId = UUID.randomUUID().toString()
                        require(saved.size < 30)
                        saveIntents(saved + SavedAccountIntent(account.accountId, durableKind, requestId, fingerprint))
                    }
                }
                if (!current(owner)) return@launch
                state.update { it.copy(accountAction = it.accountAction.copy(requestId = requestId)) }
                sent = true
                val result = perform(owner.token, requestId)
                if (!current(owner)) return@launch
                if (result.signOut) {
                    state.update { it.copy(accountAction = it.accountAction.copy(phase = ShiftVpsAccountActionPhase.Success)) }
                    signOut(owner)
                    return@launch
                }
                val operation = result.operation
                if (operation != null) {
                    require(operation.requestId == requestId)
                    applyOperation(operation)
                    if (terminal(operation)) removeIntent(operation.requestId)
                    val uncertain = !terminal(operation) && (operation.state in setOf("pending", "unknown") ||
                        operation.kind == "checkout" && operation.checkoutStatus == "unknown")
                    val resultKind = if (operation.kind == "checkout" && !terminal(operation)) ShiftVpsAccountActionKind.Checkout else kind
                    state.update { it.copy(accountAction = ShiftVpsAccountActionState(resultKind,
                        if (uncertain) ShiftVpsAccountActionPhase.Uncertain else if (operation.state == "failed" &&
                            !(operation.kind == "checkout" && terminal(operation))) ShiftVpsAccountActionPhase.Error else ShiftVpsAccountActionPhase.Success,
                        deviceId, if (uncertain) "operation_${operation.state}" else operation.error, operation.requestId)) }
                    if (!uncertain && operation.kind == "checkout" && operation.checkoutStatus == "created" &&
                        !details().account.isFree && details().capabilities.checkout) {
                        operation.checkoutUrl?.let { if (current(owner)) openUrl(it) }
                    }
                } else state.update { it.copy(accountAction = it.accountAction.copy(phase = ShiftVpsAccountActionPhase.Success)) }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (owner != null && !current(owner)) return@launch
                if (error is ClientApiException && error.status == 401 && owner != null) {
                    invalidIdentity(owner, error)
                } else {
                    val notFound = kind == ShiftVpsAccountActionKind.Reconcile && error is ClientApiException && error.code == "operation_not_found"
                    if (notFound && requestId != null) retryAllowed.add(requestId)
                    val uncertain = !notFound && (error is ClientApiException && error.code == "operation_pending" ||
                        sent && (error !is ClientApiException || error.status == 0 || error.status >= 500))
                    var code = if (uncertain) "operation_unknown" else safeCode(error)
                    if (!uncertain && durableKind != null && requestId != null && error is ClientApiException && error.status in 400..499) {
                        try { removeIntent(requestId) } catch (storage: Exception) {
                            if (storage is CancellationException) throw storage
                            code = "operation_state_unavailable"
                        }
                    }
                    state.update { it.copy(accountAction = ShiftVpsAccountActionState(kind,
                        if (uncertain) ShiftVpsAccountActionPhase.Uncertain else ShiftVpsAccountActionPhase.Error,
                        deviceId, code, requestId)) }
                }
            } finally { mutation.unlock() }
        }
    }

    override fun openAccountLink(link: ShiftVpsAccountLink) {
        scope.launch {
            val owner = identity() ?: return@launch
            val account = state.value.accountDetails
            val url = when (link) {
                ShiftVpsAccountLink.TelegramLink -> TELEGRAM_LINK_PAGE
                ShiftVpsAccountLink.ManualPayment -> PAYMENT_PAGE
                ShiftVpsAccountLink.LegacySettlement -> REFERRAL_PAGE
                ShiftVpsAccountLink.Privacy -> "https://shiftvps.ru/privacy"
                ShiftVpsAccountLink.Terms -> "https://shiftvps.ru/terms"
                ShiftVpsAccountLink.SupportEmail -> account?.support?.email?.let { "mailto:$it" }
                ShiftVpsAccountLink.SupportTelegram -> account?.support?.telegramUrl
                ShiftVpsAccountLink.Windows -> account?.downloads?.windows
                ShiftVpsAccountLink.Android -> account?.downloads?.android
                ShiftVpsAccountLink.MacArm64 -> account?.downloads?.macosArm64
                ShiftVpsAccountLink.MacAmd64 -> account?.downloads?.macosAmd64
            }
            if (url != null && current(owner)) openUrl(url)
        }
    }

    override fun dismissAccountAction() {
        val action = state.value.accountAction
        if (!action.busy && action.phase != ShiftVpsAccountActionPhase.Uncertain &&
            !(action.kind == ShiftVpsAccountActionKind.Checkout && action.requestId != null)) {
            state.update { it.copy(accountAction = ShiftVpsAccountActionState()) }
        }
    }

    fun reset() { ++revision; refreshJob?.cancel(); mutationJob?.cancel(); detailsIdentity = null; retryAllowed.clear() }

    private fun inputError() {
        if (!state.value.accountAction.busy && state.value.accountAction.phase != ShiftVpsAccountActionPhase.Uncertain) state.update { it.copy(accountAction = ShiftVpsAccountActionState(
            phase = ShiftVpsAccountActionPhase.Error, errorCode = "invalid_request")) }
    }

    private suspend fun current(owner: AccountIdentity): Boolean = owner.same(identity()) && state.value.signedIn
    private fun details(): ShiftVpsFullAccount = state.value.accountDetails ?: throw ClientApiException("account_details_unavailable")
    private suspend fun savedIntents(): List<SavedAccountIntent> = try {
        val raw = intents.load()
        require(raw.length <= 32768)
        if (raw.isBlank()) emptyList() else clientJson.decodeFromString<List<SavedAccountIntent>>(raw).also { saved ->
            require(saved.size <= 30 && saved.map { it.requestId }.distinct().size == saved.size)
            saved.forEach {
                require(it.accountId > 0 && it.kind in setOf("device_add", "checkout", "payout"))
                requireRequestId(it.requestId)
                require(it.fingerprint.matches(Regex("[a-f0-9]{64}")))
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        throw ClientApiException("operation_state_unavailable")
    }
    private suspend fun saveIntents(value: List<SavedAccountIntent>) {
        try { intents.save(clientJson.encodeToString(ListSerializer(SavedAccountIntent.serializer()), value)) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            throw ClientApiException("operation_state_unavailable")
        }
    }
    private suspend fun removeIntent(requestId: String) { saveIntents(savedIntents().filterNot { it.requestId == requestId }) }
    private fun applyOperation(operation: ShiftVpsOperation) {
        state.update { current -> current.copy(accountDetails = current.accountDetails?.let {
            it.copy(pendingOperations = it.pendingOperations.filterNot { existing -> existing.requestId == operation.requestId } +
                if (terminal(operation)) emptyList() else listOf(operation))
        }) }
    }
    private fun terminal(operation: ShiftVpsOperation): Boolean = if (operation.kind == "checkout")
        operation.checkoutStatus in setOf("paid", "canceled", "refunded", "chargeback")
    else operation.state in setOf("complete", "failed")
    private fun actionKind(kind: String) = when (kind) {
        "device_add" -> ShiftVpsAccountActionKind.AddDevice
        "payout" -> ShiftVpsAccountActionKind.LegacyPayout
        else -> ShiftVpsAccountActionKind.Checkout
    }
    private fun safeCode(error: Exception): String = if (error is ClientApiException) error.code else "invalid_request"
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun accessState(active: Boolean): ShiftVpsAccessState =
    if (active) ShiftVpsAccessState.Active else ShiftVpsAccessState.Inactive
