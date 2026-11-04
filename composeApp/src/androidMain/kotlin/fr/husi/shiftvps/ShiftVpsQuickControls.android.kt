package fr.husi.shiftvps

import fr.husi.bg.withBackgroundProcess
import fr.husi.core.CoreClient
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.database.SagerDatabase
import fr.husi.database.preference.int
import fr.husi.database.preference.long
import fr.husi.database.preference.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

internal data class QuickServerChoice(val name: String, val profileId: Long, val selected: Boolean)

private suspend fun quickControlAccessReady(): Boolean {
    if (platformShiftVpsTokenStore().load().isNullOrBlank()) return false
    if (!recentVerification(DataStore.configurationStore.long("shiftvps.account_verified_at").get())) return false
    val account = clientJson.decodeFromString<ShiftVpsAccount>(
        DataStore.configurationStore.string("shiftvps.account").get(),
    )
    requireActiveAccount(account)
    return true
}

private suspend fun quickProfiles(): List<ProxyEntity> {
    val group = SagerDatabase.groupDao.allGroups().first()
        .firstOrNull { it.name == "ShiftVPS managed" } ?: return emptyList()
    return SagerDatabase.proxyDao.getByGroup(group.id).first().filter {
        isManagedProfileCompatible(it) && it.requireBean().serverAddress != "46.182.25.146"
    }
}

/** The tile and switcher preserve the cached entitlement and managed routing checks. */
internal suspend fun prepareManagedQuickConnection(profileId: Long): Boolean = withContext(Dispatchers.IO) {
    try {
        if (!quickControlAccessReady()) return@withContext false
        val token = platformShiftVpsTokenStore().load()
        val previous = DataStore.selectedProxy.get()
        val profiles = quickProfiles()
        val candidates = profiles.map { ServerCandidate(it.id, diagnosticServerId(it), it.displayName(), it.ping.takeIf { ping -> ping > 0 }) }
        val groups = clientJson.decodeFromString<List<ShiftVpsServerGroup>>(
            DataStore.configurationStore.string("shiftvps.server_groups").get(),
        )
        val cluster = serverClusters(candidates, groups).firstOrNull { profileId in it.profileIds } ?: return@withContext false
        val policy = clientJson.decodeFromString<ShiftVpsPolicy>(
            DataStore.configurationStore.string("shiftvps.policy").get(),
        ).validated()
        applyManagedPolicy(policy)
        val resolved = withBackgroundProcess {
            val core = GlobalContext.get().get<CoreClient>()
            chooseConnectionProfile(cluster, candidates) { candidate ->
                val profile = profiles.first { it.id == candidate.profileId }
                probeManagedProfile(core, profile, policy.latency)
            }
        } ?: return@withContext false
        if (platformShiftVpsTokenStore().load() != token || !quickControlAccessReady() ||
            DataStore.selectedProxy.get() != previous) return@withContext false
        val profile = profiles.first { it.id == resolved }
        storeManagedAndroidRouting(profile, policy)
        DataStore.selectedProxy.set(resolved)
        DataStore.configurationStore.int("shiftvps.active_policy_version").set(policy.version)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

internal suspend fun managedQuickServerChoices(): List<QuickServerChoice> = withContext(Dispatchers.IO) {
    if (!quickControlAccessReady()) return@withContext emptyList()
    val profiles = quickProfiles()
    val candidates = profiles.map {
        ServerCandidate(it.id, diagnosticServerId(it), it.displayName(), it.ping.takeIf { ping -> ping > 0 })
    }
    val groups = runCatching {
        clientJson.decodeFromString<List<ShiftVpsServerGroup>>(
            DataStore.configurationStore.string("shiftvps.server_groups").get(),
        )
    }.getOrDefault(emptyList())
    val selected = DataStore.selectedProxy.get()
    serverClusters(candidates, groups).mapNotNull { cluster ->
        chooseProfile(cluster, candidates, selected)?.let { profile ->
            val flag = cluster.countryCode.takeIf { it.matches(Regex("[A-Z]{2}")) }
                ?.map { Character.toChars(127397 + it.code).concatToString() }?.joinToString("")
            QuickServerChoice(listOfNotNull(flag, cluster.name).joinToString(" "), profile, selected in cluster.profileIds)
        }
    }
}
