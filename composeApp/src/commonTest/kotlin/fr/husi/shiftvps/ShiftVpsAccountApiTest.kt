package fr.husi.shiftvps

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal const val ACCOUNT_REQUEST_ID = "fixture-request-123456"

// These are owned demonstration records, not a connection to the account service.
internal val fullAccountFixture = """{
  "schema_version":1,"account_id":9,"current_device_id":7,
  "account":{"name":"Demo","phone":"","subscription_expires_at":null,"access_active":false,"is_free":false,"devices_used":1,"device_limit":3},
  "devices":[{"id":7,"name":"Phone","is_current":true,"provisioning":false,"online":null,"traffic_bytes":null,"last_seen_at":null,"status_source":"unknown"}],
  "plans":[{"code":"month","title":"Example plan","days":31,"price_rub":263,"currency":"RUB"}],
  "payments":[{"id":11,"plan_code":"legacy","plan_title":"Historical payment","amount_rub":"99.75","days":30,"paid_at":"2026-10-01T12:00:00Z","status":"paid"}],
  "telegram":{"linked":false,"can_unlink":false,"username":null,"link_url":"https://shiftvps.ru/dashboard#telegram","trial_available":false,"trial_days":3},
  "billing":{"online_available":true,"payment_methods":["sbp","card"],"browser_url":"https://shiftvps.ru/pay","checkout_hosts":["pay.rollypay.io"]},
  "support":{"email":"support@shiftvps.ru","telegram_url":"https://t.me/example_support","privacy_url":"https://shiftvps.ru/privacy","terms_url":"https://shiftvps.ru/terms"},
  "downloads":{"windows":"https://shiftvps.ru/downloads/shiftvps-0.1.5-windows-x64-setup.exe","android":"https://shiftvps.ru/downloads/shiftvps-0.1.6-arm64-v8a.apk","macos_arm64":"https://shiftvps.ru/downloads/shiftvps-0.1.5-macos-arm64.dmg","macos_amd64":"https://shiftvps.ru/downloads/shiftvps-0.1.5-macos-amd64.dmg"},
  "legacy_referral":{"balance_usdt":"49.125","payout_min_usdt":"50.00","can_request_payout":false,"has_pending_payout":false,"settlement_url":"https://shiftvps.ru/referral","payouts":[]},
  "pending_operations":[],"capabilities":{"add_device":false,"change_password":true,"unlink_telegram":false,"checkout":true}
}"""

internal fun checkoutFixture(url: String = "https://pay.rollypay.io/pay/tok_fixture", status: String = "created") =
    """{"operation":{"request_id":"$ACCOUNT_REQUEST_ID","kind":"checkout","state":"complete","checkout_url":${JsonPrimitive(url)},"checkout_status":"$status"}}"""

class ShiftVpsAccountApiTest {
    @Test
    fun `actual isolated backend responses match the native contract`() = runTest {
        val input = assertNotNull(javaClass.getResourceAsStream("/shiftvps/account-backend-fixtures.json"))
        val fixtures = clientJson.parseToJsonElement(input.use { it.readBytes().toString(Charsets.UTF_8) }) as JsonObject
        for ((name, response) in fixtures) {
            if (name.startsWith("details_")) {
                val details = responseApi(response.toString()).accountDetails("fixture-session")
                assertTrue(details.accountId > 0)
                assertEquals(details.capabilities.unlinkTelegram, details.telegram.canUnlink)
            } else if (name.startsWith("checkout_")) {
                val operation = (response as JsonObject).getValue("operation") as JsonObject
                val id = operation.getValue("request_id").jsonPrimitive.content
                responseApi(response.toString()).accountOperation("fixture-session", id)
            } else if (name == "metadata_device_added") {
                val document = response as JsonObject
                val operation = document.getValue("operation") as JsonObject
                val device = document.getValue("device") as JsonObject
                responseApi(response.toString()).addDevice("fixture-session", device.getValue("name").jsonPrimitive.content,
                    operation.getValue("request_id").jsonPrimitive.content)
            } else if (name == "password_changed") {
                responseApi(response.toString()).changePassword("fixture-session", "current-fixture", "replacement-fixture", "replacement-fixture")
            } else if (name == "current_device_deleted") {
                val document = response as JsonObject
                responseApi(response.toString()).deleteDevice("fixture-session", document.getValue("deleted_id").jsonPrimitive.content.toLong())
            } else if (name == "telegram_required") {
                val api = ShiftVpsApi(object : ClientTransport {
                    override suspend fun request(method: String, url: String, token: String?, body: String?) = ClientResponse(409, response.toString())
                })
                assertEquals("telegram_required", assertFailsWith<ClientApiException> { api.unlinkTelegram("fixture-session") }.code)
            }
        }
    }
    @Test
    fun `account capability is explicit and an unpaid identity is still issued`() = runTest {
        val bodies = mutableListOf<String>()
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                bodies.add(body.orEmpty())
                return if (url.endsWith("/authorize")) ClientResponse(200,
                    """{"grant_id":"fixture","verification_uri":"https://shiftvps.ru/app/connect","user_code":"ABCD1234","expires_in":600,"interval":3}""")
                else ClientResponse(200, """{"state":"issued","access_token":"${"T".repeat(43)}","capability":"account-v1","access_active":false}""")
            }
        })
        api.authorize("android", "Demo", verifierChallenge(createVerifier()))
        assertTrue(bodies.single().contains("\"capability\":\"account-v1\""))
        val identity = api.identityToken("fixture", "fixture-verifier")
        assertFalse(identity.accessActive)
        assertEquals("T".repeat(43), identity.token)
        assertFalse(identity.toString().contains(identity.token))
    }

    @Test
    fun `new login requires authoritative account capability flags without replaying exchange`() = runTest {
        for (extra in listOf("", ",\"capability\":\"account-v1\"", ",\"capability\":\"other\",\"access_active\":true",
            ",\"capability\":\"account-v1\",\"access_active\":\"true\"")) {
            var calls = 0
            val api = ShiftVpsApi(object : ClientTransport {
                override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                    calls++
                    return ClientResponse(200, """{"state":"issued","access_token":"${"T".repeat(43)}"$extra}""")
                }
            })
            val error = assertFailsWith<ClientApiException> { api.identityToken("fixture", "verifier") }
            assertEquals("invalid_token_response", error.code)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `details preserve inactive ownership unknown statistics and historical fractional money`() = runTest {
        val calls = mutableListOf<String>()
        val details = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                assertEquals("fixture-session", token)
                calls.add("$method $url")
                return ClientResponse(200, fullAccountFixture)
            }
        }).accountDetails("fixture-session")
        assertEquals(9L, details.accountId)
        assertFalse(details.account.accessActive)
        assertEquals("", details.account.phone)
        assertNull(details.devices.single().online)
        assertNull(details.devices.single().trafficBytes)
        assertEquals(263, details.plans.single().priceRub)
        assertEquals(31, details.plans.single().days)
        assertEquals(BigDecimal("99.75"), BigDecimal(details.payments.single().amountRub))
        assertEquals(BigDecimal("49.125"), BigDecimal(details.legacyReferral.balanceUsdt))
        assertEquals(listOf("GET https://shiftvps.ru/api/client/v1/account/details"), calls)
    }

    @Test
    fun `malformed private response URLs become fixed errors rather than leaking URI text`() = runTest {
        for (badUrl in listOf("https://shiftvps.ru/downloads/private value", "https://shiftvps.ru/downloads/%broken",
            "https://shiftvps.ru/downloads/private\u0001value")) {
            val escaped = JsonPrimitive(badUrl).toString()
            val payload = fullAccountFixture.replace(JsonPrimitive("https://shiftvps.ru/downloads/shiftvps-0.1.5-windows-x64-setup.exe").toString(), escaped)
            val api = responseApi(payload)
            val error = assertFailsWith<ClientApiException> { api.accountDetails("fixture-session") }
            assertEquals("invalid_account_details", error.code)
            assertFalse(error.message.orEmpty().contains("private"))
            assertFalse(error.message.orEmpty().contains("broken"))
        }
    }

    @Test
    fun `checkout permits only the exact hosted payment page including malformed URL rejection`() = runTest {
        for (url in listOf("http://pay.rollypay.io/pay/fixture", "https://pay.rollypay.io.attacker.invalid/pay/fixture",
            "https://attacker@pay.rollypay.io/pay/fixture", "https://pay.rollypay.io:8443/pay/fixture",
            "https://pay.rollypay.io/pay/fixture#private", "https://pay.rollypay.io/redirect/fixture",
            "https://pay.rollypay.io/pay/private value", "https://pay.rollypay.io/pay/%broken",
            "https://pay.rollypay.io/pay/private\u0001value")) {
            val direct = assertFailsWith<ClientApiException> { requireTrustedCheckoutUrl(url) }
            assertEquals("invalid_account_action", direct.code)
            val error = assertFailsWith<ClientApiException> { responseApi(checkoutFixture(url)).accountOperation("fixture", ACCOUNT_REQUEST_ID) }
            assertEquals("invalid_account_action", error.code)
            assertFalse(error.message.orEmpty().contains("private"))
            assertFalse(error.message.orEmpty().contains("attacker"))
        }
        val result = responseApi(checkoutFixture()).accountOperation("fixture", ACCOUNT_REQUEST_ID)
        assertEquals("created", result.operation.checkoutStatus)
    }

    @Test
    fun `uncertain writes for every account action never fail over or replay`() = runTest {
        val actions: List<suspend (ShiftVpsApi) -> Unit> = listOf(
            { it.addDevice("fixture", "Demo", ACCOUNT_REQUEST_ID) },
            { it.renameDevice("fixture", 7, "Demo") },
            { it.deleteDevice("fixture", 7) },
            { it.changePassword("fixture", "current-fixture", "replacement-fixture", "replacement-fixture") },
            { it.unlinkTelegram("fixture") },
            { it.createCheckout("fixture", "month", "sbp", ACCOUNT_REQUEST_ID) },
            { it.requestLegacyPayout("fixture", "TRC20", "T".repeat(34), ACCOUNT_REQUEST_ID) },
        )
        for (action in actions) {
            var calls = 0
            val api = ShiftVpsApi(object : ClientTransport {
                override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                    calls++
                    assertEquals("POST", method)
                    assertFalse(url.contains("web-backup"))
                    throw IOException("private-fixture-error")
                }
            })
            assertFailsWith<IOException> { action(api) }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `checkout body cannot supply a price and business errors remain private typed failures`() = runTest {
        var sent = ""
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                sent = body.orEmpty()
                return ClientResponse(201, checkoutFixture())
            }
        })
        api.createCheckout("fixture", "month", "sbp", ACCOUNT_REQUEST_ID)
        val document = clientJson.parseToJsonElement(sent) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("plan_code", "payment_method", "request_id"), document.keys)
        val passwordApi = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?) =
                ClientResponse(403, """{"error":"current_password_invalid","private":"never displayed"}""")
        })
        val error = assertFailsWith<ClientApiException> {
            passwordApi.changePassword("fixture", "current-fixture", "replacement-fixture", "replacement-fixture")
        }
        assertEquals(403, error.status)
        assertEquals("current_password_invalid", error.code)
        assertFalse(error.message.orEmpty().contains("never displayed"))
    }

    @Test
    fun `details reject invented unknown stats invalid decimals and mismatched current identity`() = runTest {
        for (payload in listOf(
            fullAccountFixture.replace("\"online\":null", "\"online\":false"),
            fullAccountFixture.replace("\"traffic_bytes\":null", "\"traffic_bytes\":0"),
            fullAccountFixture.replace("\"amount_rub\":\"99.75\"", "\"amount_rub\":\"NaN\""),
            fullAccountFixture.replace("\"amount_rub\":\"99.75\"", "\"amount_rub\":99.75"),
            fullAccountFixture.replace("\"current_device_id\":7", "\"current_device_id\":99"),
            fullAccountFixture.replace("https://shiftvps.ru/pay", "https://untrusted.invalid/pay"),
        )) {
            assertEquals("invalid_account_details", assertFailsWith<ClientApiException> {
                responseApi(payload).accountDetails("fixture")
            }.code)
        }
    }

    @Test
    fun `authoritative access and capability flags must be actual JSON booleans`() = runTest {
        for (payload in listOf(
            fullAccountFixture.replace("\"access_active\":false", "\"access_active\":\"true\""),
            fullAccountFixture.replace("\"is_free\":false", "\"is_free\":\"true\""),
            fullAccountFixture.replace("\"can_unlink\":false", "\"can_unlink\":\"false\""),
            fullAccountFixture.replace("\"checkout\":true", "\"checkout\":\"true\""),
        )) {
            assertEquals("invalid_account_details", assertFailsWith<ClientApiException> {
                responseApi(payload).accountDetails("fixture-session")
            }.code)
        }
    }

    @Test
    fun `delete acknowledgement binds the requested device and explicit signout effect`() = runTest {
        val accepted = responseApi("""{"deleted_id":7,"current_device_deleted":true,"logout_required":true}""").deleteDevice("fixture", 7)
        assertTrue(accepted.logoutRequired)
        for (payload in listOf("""{"deleted_id":8,"current_device_deleted":true,"logout_required":true}""",
            """{"deleted_id":7,"current_device_deleted":false,"logout_required":true}""")) {
            assertEquals("invalid_account_action", assertFailsWith<ClientApiException> { responseApi(payload).deleteDevice("fixture", 7) }.code)
        }
    }

    @Test
    fun `operation validation never invents a checkout URL for pending or unknown results`() = runTest {
        for (state in listOf("pending", "unknown")) {
            val payload = """{"operation":{"request_id":"$ACCOUNT_REQUEST_ID","kind":"checkout","state":"$state","checkout_status":"unknown"}}"""
            val operation = responseApi(payload).accountOperation("fixture", ACCOUNT_REQUEST_ID).operation
            assertNull(operation.checkoutUrl)
            val invalid = checkoutFixture().replace("\"state\":\"complete\"", "\"state\":\"$state\"")
            assertFailsWith<ClientApiException> { responseApi(invalid).accountOperation("fixture", ACCOUNT_REQUEST_ID) }
        }
    }

    private fun responseApi(responseBody: String) = ShiftVpsApi(object : ClientTransport {
        override suspend fun request(method: String, url: String, token: String?, body: String?) = ClientResponse(200, responseBody)
    })
}
