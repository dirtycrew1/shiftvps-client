package fr.husi.shiftvps

import fr.husi.database.ProxyEntity
import fr.husi.fmt.AbstractBean
import fr.husi.fmt.naive.NaiveBean

internal fun supportsManagedTransport(bean: AbstractBean): Boolean =
    bean is NaiveBean && !bean.noPostQuantum && bean.tunnelTimeout <= 0 && bean.idleTimeout <= 0

/** Managed accounts expose built-in Naive only; external plugin apps are not bundled. */
internal suspend fun isManagedProfileCompatible(profile: ProxyEntity): Boolean {
    val bean = profile.requireBean()
    return supportsManagedTransport(bean) && bean.customOutboundJson.isEmpty() &&
        bean.customConfigJson.isEmpty() && !profile.needExternal()
}

/** Reject an unusable refresh before it can replace the last valid managed profile cache. */
internal suspend fun prepareManagedFeed(parsed: List<AbstractBean>): List<AbstractBean> {
    require(parsed.size <= 256)
    val naive = parsed.filterIsInstance<NaiveBean>()
    naive.forEach { bean ->
        bean.customOutboundJson = ""
        bean.customConfigJson = ""
    }
    val compatible = naive.filter { isManagedProfileCompatible(ProxyEntity().putBean(it)) }
    if (compatible.isEmpty()) throw ClientApiException("unsupported_transport")
    compatible.forEach { bean ->
        require(bean.serverAddress != "46.182.25.146")
    }
    return compatible
}
