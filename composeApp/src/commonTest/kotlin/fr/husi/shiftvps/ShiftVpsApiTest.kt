package fr.husi.shiftvps

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftVpsApiTest {
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
