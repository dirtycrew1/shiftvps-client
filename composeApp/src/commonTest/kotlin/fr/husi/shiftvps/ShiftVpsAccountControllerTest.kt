package fr.husi.shiftvps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShiftVpsAccountControllerTest {
    @Test
    fun `inactive identity can load account and start payment without any VPN request`() = runTest {
        val harness = Harness(this)
        harness.coordinator.refresh().join()
        assertEquals(ShiftVpsAccessState.Inactive, harness.state.value.accessState)
        assertTrue(harness.state.value.signedIn)
        assertEquals(9L, harness.state.value.accountDetails?.accountId)
        harness.handler = { _, url, _, body ->
            if (url.endsWith("/checkout")) {
                val requestId = requestId(body)
                assertTrue(harness.store.raw.contains(requestId), "The intent must exist before the write")
                ClientResponse(201, checkoutFixture().replace(ACCOUNT_REQUEST_ID, requestId))
            } else ClientResponse(200, fullAccountFixture)
        }
        harness.coordinator.createCheckout("month", "sbp")
        advanceUntilIdle()
        assertEquals(1, harness.calls.count { it.method == "POST" })
        assertEquals(listOf("https://pay.rollypay.io/pay/tok_fixture"), harness.opened)
        assertTrue(harness.calls.none { it.url.endsWith("/manifest") || it.url.endsWith("/feed") || it.url.endsWith("/events") })
        assertEquals(0, harness.signOuts)
    }

    @Test
    fun `one user mutation runs at a time and a completed checkout cannot create a second intent`() = runTest {
        val harness = Harness(this)
        harness.coordinator.refresh().join()
        val release = CompletableDeferred<Unit>()
        harness.handler = { method, url, _, body ->
            if (method == "POST" && url.endsWith("/checkout")) {
                release.await()
                ClientResponse(201, checkoutFixture().replace(ACCOUNT_REQUEST_ID, requestId(body)))
            } else ClientResponse(200, fullAccountFixture)
        }
        harness.coordinator.createCheckout("month", "sbp")
        runCurrent()
        assertTrue(harness.state.value.accountAction.busy)
        harness.coordinator.renameDevice(7, "Other name")
        runCurrent()
        assertEquals(1, harness.calls.count { it.method == "POST" })
        release.complete(Unit)
        advanceUntilIdle()
        val request = harness.state.value.accountAction.requestId
        assertNotNull(request)
        assertEquals(ShiftVpsAccountActionPhase.Success, harness.state.value.accountAction.phase)
        harness.coordinator.refresh().join()
        assertEquals(request, harness.state.value.accountAction.requestId)
        harness.coordinator.createCheckout("month", "card")
        advanceUntilIdle()
        assertEquals(1, harness.calls.count { it.method == "POST" })
    }

    @Test
    fun `uncertain checkout survives refresh and process-style recreation without replaying or exposing errors`() = runTest {
        val store = MemoryIntentStore()
        val first = Harness(this, store)
        first.coordinator.refresh().join()
        first.handler = { method, _, _, _ ->
            if (method == "POST") throw IOException("https://private.invalid/fixture-secret")
            ClientResponse(200, fullAccountFixture)
        }
        first.coordinator.createCheckout("month", "sbp")
        advanceUntilIdle()
        assertEquals(ShiftVpsAccountActionPhase.Uncertain, first.state.value.accountAction.phase)
        assertFalse(first.state.value.accountAction.toString().contains("fixture-secret"))
        assertFalse(store.raw.contains("fixture-session"))
        val id = first.state.value.accountAction.requestId
        first.coordinator.dismissAccountAction()
        first.coordinator.createCheckout("month", "sbp")
        advanceUntilIdle()
        assertEquals(1, first.calls.count { it.method == "POST" })
        val recreated = Harness(this, store)
        recreated.owner = AccountIdentity("different-session-same-user", 2)
        recreated.coordinator.refresh().join()
        assertEquals(id, recreated.state.value.accountAction.requestId)
        assertEquals(ShiftVpsAccountActionPhase.Uncertain, recreated.state.value.accountAction.phase)
        recreated.coordinator.createCheckout("month", "sbp")
        advanceUntilIdle()
        assertEquals(0, recreated.calls.count { it.method == "POST" })
        assertTrue(recreated.opened.isEmpty())
    }

    @Test
    fun `not found reconciliation only enables a new explicit same-intent retry`() = runTest {
        val harness = Harness(this)
        harness.coordinator.refresh().join()
        var writes = 0
        val requestIds = mutableListOf<String>()
        harness.handler = { method, url, _, body ->
            when {
                method == "POST" -> {
                    writes++
                    requestIds.add(requestId(body))
                    if (writes == 1) throw IOException("synthetic uncertain response")
                    ClientResponse(201, checkoutFixture().replace(ACCOUNT_REQUEST_ID, requestId(body)))
                }
                url.contains("/operations/") -> ClientResponse(404, """{"error":"operation_not_found"}""")
                else -> ClientResponse(200, fullAccountFixture)
            }
        }
        harness.coordinator.createCheckout("month", "sbp")
        advanceUntilIdle()
        val id = harness.state.value.accountAction.requestId!!
        harness.coordinator.reconcileAccountOperation(id)
        advanceUntilIdle()
        assertEquals(1, writes, "A GET must not replay the original write")
        assertEquals("operation_not_found", harness.state.value.accountAction.errorCode)
        harness.coordinator.createCheckout("month", "sbp")
        advanceUntilIdle()
        assertEquals(2, writes)
        assertEquals(listOf(id, id), requestIds)
    }

    @Test
    fun `late response and saved intent from another owner never change a new identity`() = runTest {
        val harness = Harness(this)
        harness.coordinator.refresh().join()
        val release = CompletableDeferred<Unit>()
        harness.handler = { method, url, _, body ->
            if (method == "POST" && url.endsWith("/checkout")) {
                withContext(NonCancellable) { release.await() }
                ClientResponse(201, checkoutFixture().replace(ACCOUNT_REQUEST_ID, requestId(body)))
            } else ClientResponse(200, fullAccountFixture)
        }
        harness.coordinator.createCheckout("month", "sbp")
        runCurrent()
        harness.owner = AccountIdentity("new-owner-session", 2)
        harness.coordinator.reset()
        harness.state.value = ShiftVpsUiState(signedIn = true, accountName = "New owner")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("New owner", harness.state.value.accountName)
        assertTrue(harness.opened.isEmpty())
        assertEquals(0, harness.signOuts)
        harness.handler = { _, _, _, _ -> ClientResponse(200, fullAccountFixture.replace("\"account_id\":9", "\"account_id\":10")) }
        harness.coordinator.refresh().join()
        assertEquals(10L, harness.state.value.accountDetails?.accountId)
        assertEquals(ShiftVpsAccountActionPhase.Idle, harness.state.value.accountAction.phase)
    }

    @Test
    fun `ordinary password and Telegram business errors do not clear valid identity`() = runTest {
        for (code in listOf("current_password_invalid", "telegram_required")) {
            val harness = Harness(this)
            harness.coordinator.refresh().join()
            harness.handler = { _, _, _, _ -> ClientResponse(if (code == "telegram_required") 409 else 403, """{"error":"$code"}""") }
            harness.coordinator.changePassword("current-fixture", "replacement-fixture", "replacement-fixture")
            advanceUntilIdle()
            assertEquals(ShiftVpsAccountActionPhase.Error, harness.state.value.accountAction.phase)
            assertEquals(code, harness.state.value.accountAction.errorCode)
            assertTrue(harness.state.value.signedIn)
            assertEquals(0, harness.invalidations)
            assertEquals(0, harness.signOuts)
        }
    }

    @Test
    fun `read-only current device state never signs out and only explicit confirmed deletion does`() = runTest {
        val harness = Harness(this)
        harness.coordinator.refresh().join()
        assertEquals(0, harness.signOuts)
        harness.handler = { _, _, _, _ -> ClientResponse(200, """{"deleted_id":7,"current_device_deleted":true,"logout_required":true}""") }
        harness.coordinator.deleteDevice(7)
        advanceUntilIdle()
        assertEquals(1, harness.signOuts)
        assertEquals(1, harness.calls.count { it.method == "POST" })
    }

    @Test
    fun `unavailable capability rejects before creating an intent or issuing a write`() = runTest {
        val harness = Harness(this)
        harness.coordinator.refresh().join()
        harness.coordinator.addDevice("New phone")
        advanceUntilIdle()
        assertEquals("subscription_expired", harness.state.value.accountAction.errorCode)
        assertEquals(0, harness.calls.count { it.method == "POST" })
        assertEquals("", harness.store.raw)
        assertTrue(harness.state.value.signedIn)
        assertEquals(0, harness.invalidations)
    }

    @Test
    fun `ready checkout reconciliation does not reopen a bank page for lifetime access`() = runTest {
        val harness = Harness(this)
        harness.handler = { _, url, _, _ ->
            if (url.contains("/operations/")) ClientResponse(200, checkoutFixture())
            else ClientResponse(200, fullAccountFixture.replace("\"is_free\":false", "\"is_free\":true")
                .replace("\"checkout\":true", "\"checkout\":false"))
        }
        harness.coordinator.refresh().join()
        harness.coordinator.reconcileAccountOperation(ACCOUNT_REQUEST_ID)
        advanceUntilIdle()
        assertTrue(harness.opened.isEmpty())
        assertEquals(0, harness.calls.count { it.method == "POST" })
    }

    @Test
    fun `authoritative paid reconciliation clears the protected intent without creating another payment`() = runTest {
        val harness = Harness(this)
        harness.coordinator.refresh().join()
        harness.handler = { method, url, _, body ->
            when {
                method == "POST" -> ClientResponse(201, checkoutFixture().replace(ACCOUNT_REQUEST_ID, requestId(body)))
                url.contains("/operations/") -> ClientResponse(200, checkoutFixture(status = "paid").replace(ACCOUNT_REQUEST_ID, url.substringAfterLast('/')))
                else -> ClientResponse(200, fullAccountFixture)
            }
        }
        harness.coordinator.createCheckout("month", "sbp")
        advanceUntilIdle()
        val id = harness.state.value.accountAction.requestId!!
        assertTrue(harness.store.raw.contains(id))
        harness.opened.clear()
        harness.coordinator.reconcileAccountOperation(id)
        advanceUntilIdle()
        assertFalse(harness.store.raw.contains(id))
        assertTrue(harness.opened.isEmpty())
        assertEquals(1, harness.calls.count { it.method == "POST" })
    }

    @Test
    fun `terminal checkout status overrides an unknown or pending row and unlocks independent actions`() = runTest {
        for (rowState in listOf("unknown", "pending")) for (paymentState in listOf("paid", "refunded")) {
            val harness = Harness(this)
            harness.coordinator.refresh().join()
            harness.handler = { method, url, _, _ ->
                when {
                    method == "POST" && url.endsWith("/checkout") -> throw IOException("synthetic uncertain response")
                    method == "POST" && url.endsWith("/rename") -> ClientResponse(200,
                        """{"device":{"id":7,"name":"Updated","is_current":true,"provisioning":false,"online":null,"traffic_bytes":null,"last_seen_at":null,"status_source":"unknown"}}""")
                    url.contains("/operations/") -> ClientResponse(200,
                        """{"operation":{"request_id":"${url.substringAfterLast('/')}","kind":"checkout","state":"$rowState","checkout_status":"$paymentState"}}""")
                    else -> ClientResponse(200, fullAccountFixture)
                }
            }
            harness.coordinator.createCheckout("month", "sbp")
            advanceUntilIdle()
            val id = harness.state.value.accountAction.requestId!!
            harness.coordinator.reconcileAccountOperation(id)
            advanceUntilIdle()
            assertEquals(ShiftVpsAccountActionPhase.Success, harness.state.value.accountAction.phase)
            assertFalse(harness.store.raw.contains(id))
            assertTrue(harness.opened.isEmpty())
            assertEquals(1, harness.calls.count { it.method == "POST" })
            harness.coordinator.renameDevice(7, "Updated")
            advanceUntilIdle()
            assertEquals(2, harness.calls.count { it.method == "POST" })
            assertEquals(ShiftVpsAccountActionPhase.Success, harness.state.value.accountAction.phase)
        }
    }

    @Test
    fun `definitive failed device or payout operations remain errors rather than false success`() = runTest {
        for (kind in listOf("device_add", "payout")) {
            val harness = Harness(this)
            val eligible = fullAccountFixture.replace("\"add_device\":false", "\"add_device\":true")
                .replace("\"can_request_payout\":false", "\"can_request_payout\":true")
                .replace("\"balance_usdt\":\"49.125\"", "\"balance_usdt\":\"75.00\"")
            harness.handler = { method, _, _, body ->
                if (method == "POST") ClientResponse(200,
                    """{"operation":{"request_id":"${requestId(body)}","kind":"$kind","state":"failed","error":"mutation_unavailable"}}""")
                else ClientResponse(200, eligible)
            }
            harness.coordinator.refresh().join()
            if (kind == "device_add") harness.coordinator.addDevice("Example phone")
            else harness.coordinator.requestLegacyPayout("TRC20", "T".repeat(34))
            advanceUntilIdle()
            assertEquals(ShiftVpsAccountActionPhase.Error, harness.state.value.accountAction.phase)
            assertEquals("mutation_unavailable", harness.state.value.accountAction.errorCode)
            assertEquals("[]", harness.store.raw)
            assertEquals(1, harness.calls.count { it.method == "POST" })
        }
    }

    private data class Call(val method: String, val url: String)
    private class MemoryIntentStore : AccountIntentStore {
        var raw = ""
        override suspend fun load(): String = raw
        override suspend fun save(value: String) { raw = value }
    }
    private class Harness(scope: CoroutineScope, val store: MemoryIntentStore = MemoryIntentStore()) {
        val state = MutableStateFlow(ShiftVpsUiState(signedIn = true))
        var owner: AccountIdentity? = AccountIdentity("fixture-session", 1)
        val calls = mutableListOf<Call>()
        val opened = mutableListOf<String>()
        var signOuts = 0
        var invalidations = 0
        var handler: suspend (String, String, String?, String?) -> ClientResponse = { _, _, _, _ -> ClientResponse(200, fullAccountFixture) }
        private val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                calls.add(Call(method, url))
                return handler(method, url, token, body)
            }
        })
        val coordinator = ShiftVpsAccountCoordinator(scope, api, state, { owner }, store,
            openUrl = { opened.add(it) }, invalidIdentity = { _, _ -> invalidations++ }, signOut = { signOuts++ })
    }

    private fun requestId(body: String?): String =
        (clientJson.parseToJsonElement(body!!) as JsonObject).getValue("request_id").jsonPrimitive.content
}
