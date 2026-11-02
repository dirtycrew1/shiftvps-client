package fr.husi.shiftvps

import fr.husi.ProtocolProvider
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.fmt.AbstractBean
import fr.husi.fmt.anytls.AnyTLSBean
import fr.husi.fmt.config.ConfigBean
import fr.husi.fmt.direct.DirectBean
import fr.husi.fmt.http.HttpBean
import fr.husi.fmt.hysteria.HysteriaBean
import fr.husi.fmt.internal.ChainBean
import fr.husi.fmt.internal.ProxySetBean
import fr.husi.fmt.juicity.JuicityBean
import fr.husi.fmt.masque.MASQUEBean
import fr.husi.fmt.mieru.MieruBean
import fr.husi.fmt.naive.NaiveBean
import fr.husi.fmt.openconnect.OpenConnectBean
import fr.husi.fmt.openvpn.OpenVPNBean
import fr.husi.fmt.shadowquic.ShadowQUICBean
import fr.husi.fmt.shadowsocks.ShadowsocksBean
import fr.husi.fmt.shadowtls.ShadowTLSBean
import fr.husi.fmt.snell.SnellBean
import fr.husi.fmt.socks.SOCKSBean
import fr.husi.fmt.ssh.SSHBean
import fr.husi.fmt.tailscale.TailcatBean
import fr.husi.fmt.tailscale.TailscaleBean
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.fmt.trusttunnel.TrustTunnelBean
import fr.husi.fmt.tuic.TuicBean
import fr.husi.fmt.v2ray.VLESSBean
import fr.husi.fmt.v2ray.VMessBean
import fr.husi.fmt.wireguard.WireGuardBean
import fr.husi.group.RawUpdater
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShiftVpsCompatibilityTest : HusiKoinTest() {
    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.providerNaive.set(ProtocolProvider.CORE)
    }

    @Test
    fun `built-in Naive remains compatible with HTTPS QUIC and ECH`() = runBlocking {
        assertTrue(compatible(NaiveBean()))
        assertTrue(compatible(NaiveBean().apply { proto = NaiveBean.PROTO_QUIC }))
        assertTrue(compatible(NaiveBean().apply { enableEch = true }))
    }

    @Test
    fun `every other protocol and configuration bean is rejected by managed accounts`() = runBlocking {
        val unsupported = listOf(
            SOCKSBean(), HttpBean(), ShadowsocksBean(), VMessBean(), VLESSBean(), TrojanBean(),
            MieruBean(), HysteriaBean(), TuicBean(), JuicityBean(), SSHBean(), WireGuardBean(),
            OpenConnectBean(), OpenVPNBean(), MASQUEBean(), TailscaleBean(), TailcatBean(),
            ShadowTLSBean(), DirectBean(), AnyTLSBean(), ShadowQUICBean(), TrustTunnelBean(),
            SnellBean(), ProxySetBean(), ChainBean(), ConfigBean(),
        )
        for (bean in unsupported) {
            assertFalse(compatible(bean), bean::class.simpleName)
        }
    }

    @Test
    fun `plugin-only Naive options are rejected even when ECH is enabled`() = runBlocking {
        for (ech in listOf(false, true)) {
            assertFalse(compatible(NaiveBean().apply { enableEch = ech; noPostQuantum = true }))
            assertFalse(compatible(NaiveBean().apply { enableEch = ech; tunnelTimeout = 30 }))
            assertFalse(compatible(NaiveBean().apply { enableEch = ech; idleTimeout = 30 }))
        }
        DataStore.providerNaive.set(ProtocolProvider.PLUGIN)
        assertFalse(compatible(NaiveBean()))
    }

    @Test
    fun `mixed raw feed retains only Naive profiles in their original order`() = runBlocking {
        val parsed = assertNotNull(RawUpdater.parseRaw("""
            vless://00000000-0000-4000-8000-000000000000@ordinary.invalid:443?security=tls&type=xhttp&path=%2Ftest
            naive+https://test-user:test-password@first.invalid:443#First
            trojan://test-password@ordinary.invalid:443?security=tls&type=ws
            naive+https://test-user:test-password@second.invalid:443#Second
        """.trimIndent()))

        val compatible = prepareManagedFeed(parsed)

        assertEquals(listOf("first.invalid", "second.invalid"), compatible.map { it.serverAddress })
        assertTrue(compatible.all { it is NaiveBean })
        assertSame(parsed[1], compatible[0])
        assertSame(parsed[3], compatible[1])
    }

    @Test
    fun `feed without compatible Naive reports unsupported transport`() = runBlocking {
        val ordinaryOnly = assertFailsWith<ClientApiException> {
            prepareManagedFeed(listOf(VLESSBean(), TrojanBean(), TrustTunnelBean()))
        }
        assertEquals("unsupported_transport", ordinaryOnly.code)
        val pluginOnly = assertFailsWith<ClientApiException> {
            prepareManagedFeed(listOf(NaiveBean().apply { noPostQuantum = true }))
        }
        assertEquals("unsupported_transport", pluginOnly.code)
    }

    @Test
    fun `cached Naive profiles cannot override outbound type or complete configuration`() = runBlocking {
        assertFalse(compatible(NaiveBean().apply {
            customOutboundJson = """{"type":"trojan","server":"ordinary.invalid"}"""
        }))
        assertFalse(compatible(NaiveBean().apply {
            customConfigJson = """{"outbounds":[{"type":"direct","tag":"proxy"}]}"""
        }))
        assertTrue(compatible(NaiveBean()))
    }

    @Test
    fun `accepted Naive feeds cannot inject custom configurations or use protected server`() = runBlocking {
        val naive = NaiveBean().apply {
            customConfigJson = "untrusted-config"
            customOutboundJson = "untrusted-outbound"
        }
        val accepted = prepareManagedFeed(listOf(naive))
        assertSame(naive, accepted.single())
        assertEquals("", naive.customConfigJson)
        assertFailsWith<IllegalArgumentException> {
            prepareManagedFeed(listOf(NaiveBean().apply { serverAddress = "46.182.25.146" }))
        }
        assertEquals("", naive.customOutboundJson)
    }

    private suspend fun compatible(bean: AbstractBean): Boolean = isManagedProfileCompatible(ProxyEntity().putBean(bean))
}
