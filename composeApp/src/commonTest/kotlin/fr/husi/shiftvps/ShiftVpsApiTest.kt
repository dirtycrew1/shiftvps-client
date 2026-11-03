package fr.husi.shiftvps

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftVpsApiTest {
    private val accountSummaryFixture = """{
        "account":{"name":"Demo","phone":"+70000000000","subscription_expires_at":null,"access_active":false,"is_free":false,"devices_used":4,"device_limit":3},
        "device":{"id":7,"name":"Phone"},
        "links":{"account_url":"https://shiftvps.ru/dashboard","payment_url":"https://shiftvps.ru/pay","referral_url":"https://shiftvps.ru/referral"},
        "referral":{"invitation_url":"https://shiftvps.ru/r/DEMO1234","invited_count":2,"paid_count":1,"rewarded_days":75,"rewarded_months":2,"bonus_days":30}
    }"""

    @Test
    fun `account summary remains useful for inactive and over quota accounts without opening the feed`() = runTest {
        val calls = mutableListOf<String>()
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                calls.add("$method $url")
                assertEquals("fixture-session", token)
                return ClientResponse(200, accountSummaryFixture)
            }
        })
        val summary = api.accountSummary("fixture-session")
        assertFalse(summary.account.accessActive)
        assertEquals(4, summary.account.devicesUsed)
        assertEquals(3, summary.account.deviceLimit)
        assertEquals(75, summary.referral.rewardedDays)
        assertEquals(2, summary.referral.rewardedMonths)
        assertEquals(listOf("GET https://shiftvps.ru/api/client/v1/account"), calls)
    }

    @Test
    fun `account summary rejects misleading counts missing values and untrusted action URLs`() = runTest {
        val sensitive = "fixture-private-value"
        for (payload in listOf(
            accountSummaryFixture.replace("https://shiftvps.ru/pay", "https://untrusted.invalid/$sensitive"),
            accountSummaryFixture.replace("DEMO1234", "DEMO1234?token=$sensitive"),
            accountSummaryFixture.replace("\"paid_count\":1", "\"paid_count\":3"),
            accountSummaryFixture.replace("\"rewarded_months\":2", "\"rewarded_months\":3"),
            accountSummaryFixture.replace("\"bonus_days\":30", "\"bonus_days\":365"),
            accountSummaryFixture.replace("\"invited_count\":2,", ""),
            "malformed-$sensitive",
        )) {
            val api = ShiftVpsApi(object : ClientTransport {
                override suspend fun request(method: String, url: String, token: String?, body: String?) = ClientResponse(200, payload)
            })
            val error = assertFailsWith<ClientApiException> { api.accountSummary("fixture-session") }
            assertEquals("invalid_account_summary", error.code)
            assertFalse(error.message.orEmpty().contains(sensitive))
            assertFalse(error.message.orEmpty().contains("untrusted.invalid"))
        }
    }

    @Test
    fun `account summary transport failure tries only the safe GET fallback and never synthesizes zero rewards`() = runTest {
        val calls = mutableListOf<String>()
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                calls.add("$method $url")
                if (calls.size == 1) throw java.io.IOException("fixture-network-failure")
                return ClientResponse(503, """{"error":"account_summary_unavailable"}""")
            }
        })
        val error = assertFailsWith<ClientApiException> { api.accountSummary("fixture-session") }
        assertEquals(503, error.status)
        assertEquals(listOf("GET https://shiftvps.ru/api/client/v1/account", "GET https://web-backup.shiftvps.ru/api/client/v1/account"), calls)
    }

    @Test
    fun `PKCE challenge matches the RFC7636 vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            verifierChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(createVerifier().matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun `browser authorization refuses origin confusion credentials and cleartext`() {
        requireTrustedUrl("https://shiftvps.ru/app/connect?request=public-grant")
        requireTrustedUrl("https://web-backup.shiftvps.ru/app/connect")
        for (url in listOf("http://shiftvps.ru/", "https://shiftvps.ru.attacker.invalid/",
            "https://attacker@shiftvps.ru/", "https://shiftvps.ru:8443/", "https://127.0.0.1/")) {
            assertFailsWith<IllegalArgumentException> { requireTrustedUrl(url) }
        }
    }

    @Test
    fun `GET failure may use backup but uncertain token exchange is never replayed`() = runTest {
        val calls = mutableListOf<String>()
        val transport = object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                calls.add("$method $url")
                if (url.contains("web-backup")) return ClientResponse(200, "personal feed")
                throw java.io.IOException("synthetic network error")
            }
        }
        val api = ShiftVpsApi(transport)
        assertEquals("personal feed", api.feed("fixture-token"))
        assertEquals(2, calls.size)
        calls.clear()
        assertFailsWith<java.io.IOException> {
            ShiftVpsApi(transport).token("fixture-grant", createVerifier())
        }
        assertEquals(1, calls.size)
    }

    @Test
    fun `pending grant creation may move to backup without reusing an approved exchange`() = runTest {
        val calls = mutableListOf<String>()
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                calls.add(url)
                if (!url.contains("web-backup")) throw java.io.IOException("synthetic network error")
                return ClientResponse(200,
                    """{"grant_id":"fixture","verification_uri":"https://web-backup.shiftvps.ru/client/authorize","user_code":"ABCD1234","expires_in":600,"interval":3}""")
            }
        })
        val grant = api.authorize("android", "Fixture phone", verifierChallenge(createVerifier()))
        assertEquals(2, calls.size)
        assertTrue(grant.verificationUri.startsWith("https://web-backup.shiftvps.ru/"))
    }

    @Test
    fun `device token waiting states use successful typed responses without issuing credentials`() = runTest {
        for (state in listOf("authorization_pending", "slow_down")) {
            val calls = mutableListOf<String>()
            val api = ShiftVpsApi(object : ClientTransport {
                override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                    calls.add("$method $url")
                    return ClientResponse(200, """{"state":"$state","interval":3}""")
                }
            })
            val error = assertFailsWith<ClientApiException> { api.token("fixture-grant", createVerifier()) }
            assertEquals(state, error.code)
            assertEquals(listOf("POST https://shiftvps.ru/api/client/v1/device-token"), calls)
        }
    }

    @Test
    fun `device token requires an issued state and a valid credential`() = runTest {
        val issued = "T".repeat(43)
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?) =
                ClientResponse(200, """{"state":"issued","access_token":"$issued","token_type":"Bearer","expires_in":3600}""")
        })
        assertEquals(issued, api.token("fixture-grant", createVerifier()))
    }

    @Test
    fun `waiting through approval reads normal HTTPS responses without the error stream`() = runTest {
        val issued = "T".repeat(43)
        val payloads = ArrayDeque(listOf(
            """{"state":"authorization_pending","interval":3}""",
            """{"state":"slow_down","interval":3}""",
            """{"state":"issued","access_token":"$issued"}""",
        ))
        val paths = mutableListOf<String>()
        val api = ShiftVpsApi(HttpsClientTransport { uri ->
            paths.add(uri.path)
            val payload = payloads.removeFirst()
            object : HttpURLConnection(uri.toURL()) {
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(payload.toByteArray())
                override fun getErrorStream(): java.io.InputStream? = error("Waiting must not read the error stream")
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        })
        assertEquals("authorization_pending", assertFailsWith<ClientApiException> {
            api.token("fixture-grant", "fixture-verifier")
        }.code)
        assertEquals("slow_down", assertFailsWith<ClientApiException> {
            api.token("fixture-grant", "fixture-verifier")
        }.code)
        assertEquals(issued, api.token("fixture-grant", "fixture-verifier"))
        assertEquals(List(3) { "/api/client/v1/device-token" }, paths)
        assertTrue(payloads.isEmpty())
    }

    @Test
    fun `unknown mixed and malformed successful device token responses fail closed`() = runTest {
        val credential = "T".repeat(43)
        for (payload in listOf(
            """{"access_token":"$credential"}""",
            """{"state":"unknown","access_token":"$credential"}""",
            """{"state":"authorization_pending","access_token":"$credential"}""",
            """{"state":"slow_down","access_token":"$credential"}""",
            """{"state":"issued"}""",
            """{"state":"issued","access_token":"invalid"}""",
            """{"state":{},"access_token":"$credential"}""",
            """{"state":"issued","access_token":{}}""",
            "not-json-$credential",
        )) {
            val api = ShiftVpsApi(object : ClientTransport {
                override suspend fun request(method: String, url: String, token: String?, body: String?) =
                    ClientResponse(200, payload)
            })
            val error = assertFailsWith<ClientApiException> { api.token("fixture-grant", createVerifier()) }
            assertEquals("invalid_token_response", error.code)
            assertFalse(error.message.orEmpty().contains(credential))
        }
    }

    @Test
    fun `terminal device token rejections retain their meaning and are never replayed`() = runTest {
        for ((status, code) in listOf(400 to "invalid_grant", 403 to "access_denied",
            403 to "subscription_expired", 409 to "device_limit")) {
            val calls = mutableListOf<String>()
            val api = ShiftVpsApi(object : ClientTransport {
                override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                    calls.add(url)
                    return ClientResponse(status, """{"error":"$code"}""")
                }
            })
            val error = assertFailsWith<ClientApiException> { api.token("fixture-grant", createVerifier()) }
            assertEquals(code, error.code)
            assertEquals(status, error.status)
            assertEquals(listOf("https://shiftvps.ru/api/client/v1/device-token"), calls)
        }
    }

    @Test
    fun `login diagnostics contain only the stage a known category and HTTP status`() {
        val sensitive = "fixture-private-value"
        for ((error, code, status) in listOf(
            Triple(java.io.IOException("https://private.invalid/$sensitive"), "NET", 0),
            Triple(java.net.SocketTimeoutException(sensitive), "TIMEOUT", 0),
            Triple(java.net.UnknownHostException(sensitive), "DNS", 0),
            Triple(javax.net.ssl.SSLException(sensitive), "TLS", 0),
            Triple(ClientApiException(sensitive, 400), "API", 400),
            Triple(ClientApiException(sensitive, Int.MAX_VALUE), "API", 0),
        )) {
            val diagnostic = loginFailureDiagnostic(LoginStage.POLL, error)
            assertEquals("L-P-$code-$status", diagnostic)
            assertFalse(diagnostic.contains(sensitive))
            assertFalse(diagnostic.contains("private.invalid"))
        }
        for ((error, code) in listOf(
            java.security.KeyStoreException(sensitive) to "KEYSTORE",
            java.security.UnrecoverableKeyException(sensitive) to "KEY_READ",
            java.security.ProviderException(sensitive) to "PROVIDER",
            java.io.FileNotFoundException(sensitive) to "FILE",
            SecurityException(sensitive) to "PERMISSION",
        )) {
            assertEquals("L-S-$code-0", loginFailureDiagnostic(LoginStage.STORE, error))
        }
    }

    @Test
    fun `policy parse failure retains last good policy while account remains independently authoritative`() = runTest {
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?) = ClientResponse(200,
                """{"account":{"name":"Fixture","access_active":false},"policy":{"dns":{"proxy":"attacker"}}}""")
        })
        val fallback = ShiftVpsPolicy(version = 3)
        val manifest = api.manifest("fixture-token", fallback)
        assertEquals(3, manifest.policy.version)
        assertFalse(manifest.account.accessActive)
        assertFailsWith<ClientApiException> { requireActiveAccount(manifest.account) }
    }

    @Test
    fun `expired and revoked access does not fall back as a network error`() = runTest {
        for ((status, code) in listOf(401 to "invalid_token", 403 to "subscription_expired")) {
            var count = 0
            val api = ShiftVpsApi(object : ClientTransport {
                override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                    count++
                    return ClientResponse(status, """{"error":"$code"}""")
                }
            })
            val error = assertFailsWith<ClientApiException> { api.manifest("fixture-token") }
            assertEquals(code, error.code)
            assertEquals(1, count)
        }
    }

    @Test
    fun `expired cached entitlement fails closed even with active flag`() {
        assertFailsWith<ClientApiException> {
            requireActiveAccount(ShiftVpsAccount(accessActive = true, subscriptionExpiresAt = "2020-01-01T00:00:00Z"))
        }
        assertFailsWith<ClientApiException> {
            requireActiveAccount(ShiftVpsAccount(accessActive = true, subscriptionExpiresAt = "not-a-date"))
        }
        requireActiveAccount(ShiftVpsAccount(accessActive = true, subscriptionExpiresAt = null))
    }

    @Test
    fun `offline grace is bounded to one day and rejects absent or future verification clocks`() {
        val now = 100_000_000L
        assertTrue(recentVerification(now - 60_000L, now))
        assertTrue(recentVerification(now - 86_400_000L, now))
        assertFalse(recentVerification(now - 86_400_001L, now))
        assertFalse(recentVerification(0L, now))
        assertFalse(recentVerification(now + 1L, now))
    }

    @Test
    fun `durable telemetry sends a stable batch identity and separate device occurrence time`() = runTest {
        var posted: String? = null
        val api = ShiftVpsApi(object : ClientTransport {
            override suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse {
                posted = body
                return ClientResponse(204, "")
            }
        })
        val metric = ClientMetric("disconnected", errorCode = "tls", appVersion = "0.1.0", platform = "android",
            policyVersion = 1, occurredAt = 1_700_000_000L)
        api.metrics("fixture-token", listOf(metric), "a".repeat(64))
        val payload = clientJson.parseToJsonElement(posted!!) as JsonObject
        assertEquals("a".repeat(64), payload["batch_id"]?.jsonPrimitive?.content)
        assertTrue(posted!!.contains("\"occurred_at\":1700000000"))
        assertFalse(posted!!.contains("fixture-token"))
    }
}
