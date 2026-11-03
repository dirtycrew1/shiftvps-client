package fr.husi.shiftvps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import okio.ByteString.Companion.toByteString

internal val clientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal class ClientApiException(val code: String, val status: Int = 0) : Exception(code)

internal data class ClientResponse(val status: Int, val body: String)

internal interface ClientTransport {
    suspend fun request(method: String, url: String, token: String?, body: String?): ClientResponse
}

internal class HttpsClientTransport(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : ClientTransport {
    override suspend fun request(
        method: String, url: String, token: String?, body: String?,
    ): ClientResponse = withContext(Dispatchers.IO) {
        requireTrustedUrl(url)
        val connection = openConnection(URI(url))
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("User-Agent", "ShiftVPS-Client/1")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw ClientApiException("redirect_refused", status)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw ClientApiException("response_too_large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            if (bytes.size > MAX_RESPONSE_BYTES) throw ClientApiException("response_too_large")
            ClientResponse(status, bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}

internal fun requireTrustedUrl(url: String) {
    val parsed = URI(url)
    require(parsed.scheme == "https" && parsed.host in TRUSTED_CLIENT_HOSTS)
    require(parsed.port == -1 || parsed.port == 443)
    require(parsed.userInfo == null && parsed.fragment == null)
}

private val TRUSTED_CLIENT_HOSTS = setOf("shiftvps.ru", "web-backup.shiftvps.ru")

internal fun createVerifier(): String {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return bytes.toByteString().base64Url().trimEnd('=')
}

internal fun verifierChallenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
    .digest(verifier.toByteArray(Charsets.US_ASCII)).toByteString().base64Url().trimEnd('=')

internal class ShiftVpsApi(private val transport: ClientTransport = HttpsClientTransport()) {
    private var origin = "https://shiftvps.ru"

    suspend fun authorize(platform: String, name: String, challenge: String): DeviceAuthorization {
        val response = request("POST", "/authorize", body = buildJsonObject {
            put("platform", platform)
            put("device_name", name)
            put("code_challenge", challenge)
        }.toString())
        return clientJson.decodeFromString<DeviceAuthorization>(response).also {
            requireTrustedUrl(it.verificationUri)
            require(it.userCode.length in 4..32 && it.expiresIn in 60..900 && it.interval in 1..30)
        }
    }

    suspend fun token(grantId: String, verifier: String): String {
        val response = request("POST", "/device-token", body = buildJsonObject {
            put("grant_id", grantId)
            put("code_verifier", verifier)
        }.toString())
        val result = try {
            clientJson.decodeFromString<DeviceTokenResponse>(response)
        } catch (_: SerializationException) {
            throw ClientApiException("invalid_token_response")
        }
        when (result.state) {
            "authorization_pending", "slow_down" -> {
                if (result.accessToken != null) throw ClientApiException("invalid_token_response")
                throw ClientApiException(result.state)
            }
            "issued" -> return result.accessToken?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{32,256}")) }
                ?: throw ClientApiException("invalid_token_response")
            else -> throw ClientApiException("invalid_token_response")
        }
    }

    suspend fun manifest(token: String, lastGoodPolicy: ShiftVpsPolicy = ShiftVpsPolicy()): ShiftVpsManifest {
        val document = clientJson.parseToJsonElement(request("GET", "/manifest", token)) as JsonObject
        val account = clientJson.decodeFromJsonElement<ShiftVpsAccount>(document.getValue("account"))
        val policy = runCatching {
            clientJson.decodeFromJsonElement<ShiftVpsPolicy>(document.getValue("policy")).validated()
        }.getOrElse { lastGoodPolicy.validated() }
        val groups = document["server_groups"]?.let { element ->
            runCatching { clientJson.decodeFromJsonElement<List<ShiftVpsServerGroup>>(element) }.getOrNull()
        }.orEmpty()
        return ShiftVpsManifest(account, policy, groups)
    }

    suspend fun feed(token: String): String = request("GET", "/feed", token)

    suspend fun accountSummary(token: String): ShiftVpsAccountSummary {
        val response = request("GET", "/account", token)
        return try {
            clientJson.decodeFromString<ShiftVpsAccountSummary>(response).also { summary ->
                require(summary.device.id > 0 && summary.device.name.length <= 200)
                require(summary.account.name.length <= 200 && summary.account.phone.length <= 64)
                require(summary.account.devicesUsed >= 0 && summary.account.deviceLimit > 0)
                require(summary.links.accountUrl == ACCOUNT_PAGE && summary.links.paymentUrl == PAYMENT_PAGE)
                require(summary.links.referralUrl == REFERRAL_PAGE)
                val referral = summary.referral
                require(referral.invitationUrl.matches(Regex("https://shiftvps\\.ru/r/[A-Za-z0-9_-]{6,64}")))
                require(referral.invitedCount >= 0 && referral.paidCount in 0..referral.invitedCount)
                require(referral.rewardedDays >= 0 && referral.rewardedMonths == referral.rewardedDays / 30)
                require(referral.bonusDays == 30)
            }
        } catch (_: SerializationException) {
            throw ClientApiException("invalid_account_summary")
        } catch (_: IllegalArgumentException) {
            throw ClientApiException("invalid_account_summary")
        }
    }

    suspend fun logout(token: String) { request("POST", "/logout", token, "{}") }

    suspend fun metrics(token: String, events: List<ClientMetric>, batchId: String? = null) {
        val encoded = clientJson.encodeToJsonElement(ListSerializer(ClientMetric.serializer()), events.take(20))
        require(batchId == null || batchId.matches(Regex("[0-9a-f]{64}")))
        request("POST", "/events", token, buildJsonObject {
            put("events", encoded)
            if (batchId != null) put("batch_id", batchId)
        }.toString())
    }

    private suspend fun request(method: String, path: String, token: String? = null, body: String? = null): String {
        val response = try {
            transport.request(method, "$origin/api/client/v1$path", token, body)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Creating an abandoned pending grant is harmless. Token exchanges and mutations are never replayed.
            if (method != "GET" && path != "/authorize") throw error
            val fallback = if (origin == "https://shiftvps.ru") "https://web-backup.shiftvps.ru" else "https://shiftvps.ru"
            transport.request(method, "$fallback/api/client/v1$path", token, body).also { origin = fallback }
        }
        if (response.status !in 200..299) {
            val returnedCode = runCatching {
                clientJson.parseToJsonElement(response.body) as JsonObject
            }.getOrNull()?.get("error")?.jsonPrimitive?.contentOrNull
            throw ClientApiException(returnedCode?.takeIf { it in API_ERRORS } ?: "service_unavailable", response.status)
        }
        return response.body
    }

    companion object {
        private val API_ERRORS = setOf(
            "authorization_pending", "slow_down", "expired_token", "access_denied", "invalid_grant",
            "invalid_token", "token_expired", "subscription_expired", "device_limit", "device_deleted", "unauthorized",
            "account_summary_unavailable",
        )
    }
}

internal const val ACCOUNT_PAGE = "https://shiftvps.ru/dashboard"
internal const val PAYMENT_PAGE = "https://shiftvps.ru/pay"
internal const val REFERRAL_PAGE = "https://shiftvps.ru/referral"
