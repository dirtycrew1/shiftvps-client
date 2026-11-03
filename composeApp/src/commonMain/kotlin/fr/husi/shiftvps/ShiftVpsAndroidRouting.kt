package fr.husi.shiftvps

import kotlinx.serialization.Serializable

internal const val MANAGED_ANDROID_ROUTING_KEY = "shiftvps.android_routing_snapshot"

private val androidPackagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
private val protectedPackageFamilies = setOf(
    "android", "com.android", "com.google", "com.huawei", "ru.shiftvps.client",
    "com.yandex.browser", "ru.yandex.searchplugin", "org.mozilla", "com.opera",
    "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
)

internal fun validatedManagedAndroidPackages(packages: List<String>): List<String> {
    require(packages.size <= 64)
    require(packages.all { name ->
        name.length in 1..128 && androidPackagePattern.matches(name) &&
            protectedPackageFamilies.none { name == it || name.startsWith("$it.") }
    })
    return packages.distinct()
}

@Serializable
internal data class ManagedAndroidRoutingSnapshot(
    val profileId: Long,
    val groupId: Long,
    val packages: List<String>,
)

internal fun managedAndroidPackagesForProfile(
    raw: String,
    profileId: Long,
    groupId: Long,
    compatibleManagedNaive: Boolean,
): Set<String> {
    if (!compatibleManagedNaive || profileId <= 0 || groupId <= 0 || raw.length > 12288) return emptySet()
    return runCatching {
        val snapshot = clientJson.decodeFromString(ManagedAndroidRoutingSnapshot.serializer(), raw)
        if (snapshot.profileId != profileId || snapshot.groupId != groupId) emptySet()
        else validatedManagedAndroidPackages(snapshot.packages).toSet()
    }.getOrDefault(emptySet())
}

internal fun shouldWarnManagedAndroidLockdown(sdk: Int, lockdownEnabled: Boolean, hasInstalledManagedApps: Boolean): Boolean =
    sdk >= 29 && lockdownEnabled && hasInstalledManagedApps

internal fun shouldAppendAndroidVpnHttpProxy(requested: Boolean, hasInstalledManagedApps: Boolean): Boolean =
    requested && !hasInstalledManagedApps

internal data class AndroidAppRouting(val bypass: Boolean, val packages: Set<String>)

/** Merge managed exclusions into a connection snapshot without changing manual app preferences. */
internal fun androidAppRouting(
    proxyApps: Boolean,
    bypassMode: Boolean,
    needBypassRootUid: Boolean,
    selectedApps: Set<String>,
    ruleApps: Set<String>,
    managedDirectApps: Set<String>,
    ownPackage: String,
    installedApps: () -> Set<String>,
): AndroidAppRouting? {
    if (!proxyApps && !needBypassRootUid && managedDirectApps.isEmpty()) return null
    val bypass = !needBypassRootUid && (!proxyApps || bypassMode)
    val individual = when {
        !proxyApps && !needBypassRootUid -> mutableSetOf()
        !proxyApps -> installedApps().toMutableSet()
        bypassMode && needBypassRootUid -> (installedApps() - selectedApps).toMutableSet()
        else -> selectedApps.toMutableSet()
    }
    if (bypass) {
        individual.removeAll(ruleApps)
        individual.addAll(managedDirectApps)
    } else {
        individual.addAll(ruleApps)
        individual.removeAll(managedDirectApps)
    }
    individual.remove(ownPackage)
    if (!bypass) individual.add(ownPackage)
    return AndroidAppRouting(bypass, individual)
}
