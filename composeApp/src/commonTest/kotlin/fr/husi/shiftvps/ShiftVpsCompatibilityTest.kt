package fr.husi.shiftvps

import fr.husi.ProtocolProvider
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.fmt.AbstractBean
import fr.husi.fmt.config.ConfigBean
import fr.husi.fmt.hysteria.HysteriaBean
import fr.husi.fmt.mieru.MieruBean
import fr.husi.fmt.naive.NaiveBean
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.fmt.trusttunnel.TrustTunnelBean
import fr.husi.fmt.v2ray.VLESSBean
import fr.husi.fmt.v2ray.parseV2Ray
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftVpsCompatibilityTest : HusiKoinTest() {
    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.providerNaive.set(ProtocolProvider.CORE)
        DataStore.providerHysteria2.set(ProtocolProvider.CORE)
    }

    @Test
    fun `XHTTP and unknown stream types cannot silently become ordinary TCP`() = runBlocking {
        val parsed = parseV2Ray("vless://00000000-0000-4000-8000-000000000000@example.invalid:443?security=tls&type=xhttp&path=%2Ftest")
        assertFalse(compatible(parsed))
        assertFalse(compatible(VLESSBean().apply { v2rayTransport = "splithttp" }))
        assertFalse(compatible(TrojanBean().apply { v2rayTransport = "unknown" }))
    }

    @Test
    fun `WS gRPC and Reality over TCP remain available without external plugins`() = runBlocking {
        for (transport in listOf("", "tcp", "ws", "grpc", "http", "httpupgrade", "quic")) {
            assertTrue(compatible(VLESSBean().apply { v2rayTransport = transport }))
        }
        assertTrue(compatible(VLESSBean().apply {
            v2rayTransport = "tcp"
            security = "tls"
            realityPublicKey = "public-test-key"
        }))
        assertTrue(compatible(TrojanBean().apply { v2rayTransport = "ws" }))
    }

    @Test
    fun `Mieru and plugin-only Naive modes are excluded from this package`() = runBlocking {
        val mieru = ProxyEntity().putBean(MieruBean())
        assertTrue(mieru.needExternal())
        assertFalse(isManagedProfileCompatible(mieru))
        assertFalse(compatible(NaiveBean().apply { noPostQuantum = true }))
        assertFalse(compatible(NaiveBean().apply { tunnelTimeout = 30 }))
        DataStore.providerNaive.set(ProtocolProvider.PLUGIN)
        assertFalse(compatible(NaiveBean()))
    }

    @Test
    fun `built-in Naive TrustTunnel and ordinary Hysteria stay available`() = runBlocking {
        assertTrue(compatible(NaiveBean()))
        assertTrue(compatible(TrustTunnelBean()))
        assertTrue(compatible(HysteriaBean().apply { protocolVersion = HysteriaBean.PROTOCOL_VERSION_2 }))
        DataStore.providerHysteria2.set(ProtocolProvider.PLUGIN)
        assertFalse(compatible(HysteriaBean().apply { protocolVersion = HysteriaBean.PROTOCOL_VERSION_2 }))
    }

    @Test
    fun `arbitrary configuration beans remain ineligible even without an external plugin`() = runBlocking {
        assertFalse(compatible(ConfigBean()))
    }

    private suspend fun compatible(bean: AbstractBean): Boolean = isManagedProfileCompatible(ProxyEntity().putBean(bean))
}
