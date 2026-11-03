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
            put("capability", "account-v1")
        }.toString())
        return clientJson.decodeFromString<DeviceAuthorization>(response).also {
            requireTrustedUrl(it.verificationUri)
            require(it.userCode.length in 4..32 && it.expiresIn in 60..900 && it.interval in 1..30)
        }
    }

    suspend fun token(grantId: String, verifier: String): String =
        exchangeToken(grantId, verifier).accessToken!!

    suspend fun identityToken(grantId: String, verifier: String): IssuedClientIdentity {
        val issued = exchangeToken(grantId, verifier)
        if (issued.capability != "account-v1" || issued.accessActive == null) {
            throw ClientApiException("invalid_token_response")
        }
        return IssuedClientIdentity(issued.accessToken!!, issued.accessActive)
    }

    private suspend fun exchangeToken(grantId: String, verifier: String): DeviceTokenResponse {
        val response = request("POST", "/device-token", body = buildJsonObject {
            put("grant_id", grantId)
            put("code_verifier", verifier)
        }.toString())
        val result = try {
            val document = clientJson.parseToJsonElement(response) as? JsonObject ?: throw IllegalArgumentException()
            requireBooleans(document, "access_active")
            clientJson.decodeFromJsonElement<DeviceTokenResponse>(document)
        } catch (_: SerializationException) {
            throw ClientApiException("invalid_token_response")
        } catch (_: IllegalArgumentException) {
            throw ClientApiException("invalid_token_response")
        }
        when (result.state) {
            "authorization_pending", "slow_down" -> {
                if (result.accessToken != null) throw ClientApiException("invalid_token_response")
                throw ClientApiException(result.state)
            }
            "issued" -> {
                if (result.accessToken?.matches(Regex("[A-Za-z0-9_-]{32,256}")) != true) {
                    throw ClientApiException("invalid_token_response")
                }
                return result
            }
            else -> throw ClientApiException("invalid_token_response")
        }
    }

    suspend fun manifest(token: String, lastGoodPolicy: ShiftVpsPolicy = ShiftVpsPolicy()): ShiftVpsManifest {
        val document = clientJson.parseToJsonElement(request("GET", "/manifest", token)) as JsonObject
        validateAccountBooleans(document)
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
            val document = clientJson.parseToJsonElement(response) as? JsonObject ?: throw IllegalArgumentException()
            validateAccountBooleans(document)
            clientJson.decodeFromJsonElement<ShiftVpsAccountSummary>(document).also { summary ->
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

    suspend fun accountDetails(token: String): ShiftVpsFullAccount = decodeAccountResponse(
        request("GET", "/account/details", token), "invalid_account_details",
    ) { details ->
        require(details.schemaVersion == 1 && details.accountId > 0 && details.currentDeviceId > 0)
        validateAccount(details.account)
        require(details.devices.size <= 50 && details.devices.map { it.id }.distinct().size == details.devices.size)
        details.devices.forEach(::validateDevice)
        require(details.devices.singleOrNull { it.isCurrent }?.id == details.currentDeviceId)
        require(details.account.devicesUsed >= details.devices.size)
        require(details.plans.size <= 10 && details.plans.map { it.code }.distinct().size == details.plans.size)
        details.plans.forEach {
            require(it.code in PLAN_CODES && it.title.length in 1..200 && it.days in 1..3660)
            require(it.priceRub > 0 && it.currency == "RUB")
        }
        require(details.payments.size <= 50 && details.payments.map { it.id }.distinct().size == details.payments.size)
        details.payments.forEach {
            require(it.id > 0 && it.planCode.length <= 64 && it.planTitle.length <= 200 && it.days >= 0)
            requireDecimal(it.amountRub, nonnegative = true)
            require(it.paidAt == null || it.paidAt.length <= 64)
            require(it.status == "paid")
        }
        require(details.telegram.username == null || details.telegram.username.length <= 64)
        require(details.telegram.canUnlink == details.capabilities.unlinkTelegram)
        require(!details.telegram.canUnlink || details.telegram.linked)
        require(details.telegram.linkUrl == TELEGRAM_LINK_PAGE && details.telegram.trialDays in 0..3660)
        require(details.billing.browserUrl == PAYMENT_PAGE && details.billing.checkoutHosts == listOf(CHECKOUT_HOST))
        require(details.billing.paymentMethods.size <= 2 && details.billing.paymentMethods.distinct() == details.billing.paymentMethods)
        require(details.billing.paymentMethods.all { it in PAYMENT_METHODS })
        require(details.support.email.length <= 254 && details.support.email.matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}")))
        details.support.telegramUrl?.let(::requirePublicTelegramUrl)
        require(details.support.privacyUrl == "https://shiftvps.ru/privacy" && details.support.termsUrl == "https://shiftvps.ru/terms")
        listOf(details.downloads.windows, details.downloads.android, details.downloads.macosArm64,
            details.downloads.macosAmd64).forEach { url ->
            requireTrustedUrl(url)
            val parsed = URI(url)
            require(parsed.rawQuery == null && parsed.path.startsWith("/downloads/"))
        }
        val legacy = details.legacyReferral
        requireDecimal(legacy.balanceUsdt)
        requireDecimal(legacy.payoutMinUsdt, nonnegative = true)
        require(java.math.BigDecimal(legacy.payoutMinUsdt).signum() > 0)
        require(legacy.settlementUrl == REFERRAL_PAGE && legacy.payouts.size <= 20)
        require(legacy.payouts.map { it.id }.distinct().size == legacy.payouts.size)
        legacy.payouts.forEach {
            require(it.id > 0 && it.status in setOf("pending", "paid", "rejected"))
            requireDecimal(it.amountUsdt, nonnegative = true)
            require(it.createdAt.length in 1..64 && (it.paidAt == null || it.paidAt.length <= 64))
            require(it.note == null || it.note.length <= 1000)
        }
        require(details.pendingOperations.size <= 10)
        require(details.pendingOperations.map { it.requestId }.distinct().size == details.pendingOperations.size)
        details.pendingOperations.forEach(::validateOperation)
    }

    suspend fun addDevice(token: String, name: String, requestId: String): AccountOperationResponse {
        val normalized = validDeviceName(name)
        requireRequestId(requestId)
        return operationRequest("/account/devices", token, requestId, "device_add", buildJsonObject {
            put("name", normalized); put("request_id", requestId)
        }.toString())
    }

    suspend fun renameDevice(token: String, deviceId: Long, name: String): ShiftVpsDeviceDetails {
        require(deviceId > 0)
        val normalized = validDeviceName(name)
        return decodeAccountResponse<AccountDeviceResponse>(request("POST", "/account/devices/$deviceId/rename", token,
            buildJsonObject { put("name", normalized) }.toString()), "invalid_account_action") {
            validateDevice(it.device)
            require(it.device.id == deviceId && it.device.name == normalized)
        }.device
    }

    suspend fun deleteDevice(token: String, deviceId: Long): AccountDeleteResponse {
        require(deviceId > 0)
        return decodeAccountResponse(request("POST", "/account/devices/$deviceId/delete", token, "{}"), "invalid_account_action") {
            require(it.deletedId == deviceId && it.logoutRequired == it.currentDeviceDeleted)
        }
    }

    suspend fun changePassword(token: String, current: String, replacement: String, confirmation: String) {
        require(current.codePointCount(0, current.length) in 1..256)
        require(replacement.codePointCount(0, replacement.length) in 12..256 && replacement == confirmation)
        decodeAccountResponse<AccountPasswordResponse>(request("POST", "/account/password", token, buildJsonObject {
            put("current_password", current); put("new_password", replacement); put("confirm_password", confirmation)
        }.toString()), "invalid_account_action") { require(it.passwordChanged && it.logoutRequired) }
    }

    suspend fun unlinkTelegram(token: String) {
        decodeAccountResponse<AccountTelegramResponse>(request("POST", "/account/telegram/unlink", token, "{}"),
            "invalid_account_action") { require(!it.linked) }
    }

    suspend fun createCheckout(token: String, planCode: String, paymentMethod: String, requestId: String): AccountOperationResponse {
        require(planCode in PLAN_CODES && paymentMethod in PAYMENT_METHODS)
        requireRequestId(requestId)
        return operationRequest("/account/checkout", token, requestId, "checkout", buildJsonObject {
            put("plan_code", planCode); put("payment_method", paymentMethod); put("request_id", requestId)
        }.toString())
    }

    suspend fun requestLegacyPayout(token: String, network: String, wallet: String, requestId: String): AccountOperationResponse {
        require(network in setOf("TRC20", "ERC20") && wallet.matches(Regex("[A-Za-z0-9]{20,80}")))
        requireRequestId(requestId)
        return operationRequest("/account/referrals/payout", token, requestId, "payout", buildJsonObject {
            put("network", network); put("wallet", wallet); put("request_id", requestId)
        }.toString())
    }

    suspend fun accountOperation(token: String, requestId: String): AccountOperationResponse {
        requireRequestId(requestId)
        return decodeAccountResponse(request("GET", "/account/operations/$requestId", token), "invalid_account_action") {
            validateOperation(it.operation)
            require(it.operation.requestId == requestId)
            it.device?.let(::validateDevice)
            require(it.device == null || it.device.id == it.operation.deviceId)
        }
    }

    private suspend fun operationRequest(path: String, token: String, requestId: String, kind: String, body: String): AccountOperationResponse =
        decodeAccountResponse(request("POST", path, token, body), "invalid_account_action") {
            validateOperation(it.operation)
            require(it.operation.requestId == requestId && it.operation.kind == kind)
            it.device?.let(::validateDevice)
            require(it.device == null || it.device.id == it.operation.deviceId)
        }

    private inline fun <reified T> decodeAccountResponse(body: String, errorCode: String, validate: (T) -> Unit): T = try {
        val document = clientJson.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException()
        validateAccountBooleans(document)
        clientJson.decodeFromJsonElement<T>(document).also(validate)
    } catch (_: SerializationException) {
        throw ClientApiException(errorCode)
    } catch (_: IllegalArgumentException) {
        throw ClientApiException(errorCode)
    } catch (_: java.net.URISyntaxException) {
        throw ClientApiException(errorCode)
    } catch (_: ClientApiException) {
        throw ClientApiException(errorCode)
    }

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
            "account_details_unavailable", "invalid_request", "current_password_invalid", "device_not_found",
            "operation_not_found", "request_conflict", "password_changed_concurrently", "payout_pending",
            "mutation_unavailable",
            "telegram_required",
        )
    }
}

internal const val ACCOUNT_PAGE = "https://shiftvps.ru/dashboard"
internal const val PAYMENT_PAGE = "https://shiftvps.ru/pay"
internal const val REFERRAL_PAGE = "https://shiftvps.ru/referral"
internal const val TELEGRAM_LINK_PAGE = "https://shiftvps.ru/dashboard#telegram"
internal const val CHECKOUT_HOST = "pay.rollypay.io"
private val PLAN_CODES = setOf("month", "quarter", "year")
private val PAYMENT_METHODS = setOf("sbp", "card")

private fun validateAccount(account: ShiftVpsAccountDetails) {
    require(account.name.length <= 200 && account.phone.length <= 64)
    require(account.devicesUsed >= 0 && account.deviceLimit > 0)
    require(account.subscriptionExpiresAt == null || account.subscriptionExpiresAt.length <= 64)
}

private fun validateDevice(device: ShiftVpsDeviceDetails) {
    require(device.id > 0 && device.name.length <= 200)
    require(device.trafficBytes == null || device.trafficBytes >= 0)
    require(device.lastSeenAt == null || device.lastSeenAt.length <= 64)
    require(device.statusSource.matches(Regex("[a-z_]{1,40}")))
    if (device.statusSource == "unknown") {
        require(device.online == null && device.trafficBytes == null && device.lastSeenAt == null)
    }
}

internal fun requireRequestId(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{16,64}"))) }

internal fun validDeviceName(value: String): String = value.trim().also {
    require(it.codePointCount(0, it.length) in 1..50 && it.none(Char::isISOControl))
}

internal fun validateOperation(operation: ShiftVpsOperation) {
    requireRequestId(operation.requestId)
    require(operation.kind in setOf("device_add", "checkout", "payout"))
    require(operation.state in setOf("pending", "complete", "unknown", "failed"))
    require(operation.error == null || operation.error.matches(Regex("[a-z][a-z0-9_]{0,63}")))
    require(operation.deviceId == null || operation.deviceId > 0 && operation.kind == "device_add")
    require(operation.payoutId == null || operation.payoutId > 0 && operation.kind == "payout")
    require(operation.checkoutStatus == null || operation.kind == "checkout" && operation.checkoutStatus in CHECKOUT_STATES)
    operation.checkoutUrl?.let {
        require(operation.kind == "checkout" && operation.state == "complete")
        requireTrustedCheckoutUrl(it)
    }
    if (operation.state == "complete") when (operation.kind) {
        "device_add" -> require(operation.deviceId != null)
        "checkout" -> {
            require(operation.checkoutStatus != null)
            require(operation.checkoutStatus != "created" || operation.checkoutUrl != null)
        }
        "payout" -> require(operation.payoutId != null)
    }
}

internal fun requireTrustedCheckoutUrl(url: String) {
    try {
        val parsed = URI(url)
        require(url.length <= 4096 && parsed.scheme == "https" && parsed.host == CHECKOUT_HOST)
        require(parsed.port == -1 || parsed.port == 443)
        require(parsed.userInfo == null && parsed.fragment == null && parsed.rawPath.startsWith("/pay/"))
        require(parsed.rawPath.length > 5)
    } catch (_: java.net.URISyntaxException) {
        throw ClientApiException("invalid_account_action")
    } catch (_: IllegalArgumentException) {
        throw ClientApiException("invalid_account_action")
    }
}

private val CHECKOUT_STATES = setOf("created", "paid", "canceled", "refunded", "chargeback", "unknown")

private fun requirePublicTelegramUrl(url: String) {
    val parsed = URI(url)
    require(parsed.scheme == "https" && parsed.host == "t.me" && (parsed.port == -1 || parsed.port == 443))
    require(parsed.userInfo == null && parsed.rawQuery == null && parsed.fragment == null)
    require(parsed.rawPath.matches(Regex("/[A-Za-z0-9_]{3,64}")))
}

private fun requireDecimal(value: String, nonnegative: Boolean = false) {
    require(value.matches(Regex("-?[0-9]{1,12}(\\.[0-9]{1,8})?")))
    require(!nonnegative || java.math.BigDecimal(value).signum() >= 0)
}

private fun requireBooleans(document: JsonObject, vararg names: String) {
    names.forEach { name -> document[name]?.let { value ->
        require(value is JsonPrimitive && !value.isString && value.content in setOf("true", "false"))
    } }
}

private fun validateAccountBooleans(document: JsonObject) {
    (document["account"] as? JsonObject)?.let { requireBooleans(it, "access_active", "is_free") }
    (document["capabilities"] as? JsonObject)?.let { requireBooleans(it, "add_device", "change_password", "unlink_telegram", "checkout") }
    (document["telegram"] as? JsonObject)?.let { requireBooleans(it, "linked", "can_unlink", "trial_available") }
    (document["billing"] as? JsonObject)?.let { requireBooleans(it, "online_available") }
    (document["legacy_referral"] as? JsonObject)?.let { requireBooleans(it, "can_request_payout", "has_pending_payout") }
    fun device(value: JsonObject) {
        requireBooleans(value, "is_current", "provisioning")
        if (value["online"] != null && value["online"] != kotlinx.serialization.json.JsonNull) requireBooleans(value, "online")
    }
    (document["device"] as? JsonObject)?.let(::device)
    (document["devices"] as? kotlinx.serialization.json.JsonArray)?.forEach { (it as? JsonObject)?.let(::device) }
    requireBooleans(document, "current_device_deleted", "logout_required", "password_changed", "linked")
}
