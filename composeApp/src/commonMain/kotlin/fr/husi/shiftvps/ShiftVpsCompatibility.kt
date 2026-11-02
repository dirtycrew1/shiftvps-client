package fr.husi.shiftvps

import fr.husi.database.ProxyEntity
import fr.husi.fmt.AbstractBean
import fr.husi.fmt.http.HttpBean
import fr.husi.fmt.hysteria.HysteriaBean
import fr.husi.fmt.naive.NaiveBean
import fr.husi.fmt.shadowsocks.ShadowsocksBean
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.fmt.trusttunnel.TrustTunnelBean
import fr.husi.fmt.tuic.TuicBean
import fr.husi.fmt.v2ray.StandardV2RayBean
import fr.husi.fmt.v2ray.VLESSBean

internal fun supportsManagedTransport(bean: AbstractBean): Boolean = when (bean) {
    is VLESSBean, is TrojanBean -> (bean as StandardV2RayBean).v2rayTransport in
        setOf("", "tcp", "ws", "http", "quic", "grpc", "httpupgrade")
    is HttpBean, is ShadowsocksBean, is HysteriaBean, is NaiveBean, is TrustTunnelBean, is TuicBean -> true
    else -> false
}

/** This package ships built-in core transports only; external plugin apps are not bundled. */
internal suspend fun isManagedProfileCompatible(profile: ProxyEntity): Boolean =
    supportsManagedTransport(profile.requireBean()) && !profile.needExternal()
