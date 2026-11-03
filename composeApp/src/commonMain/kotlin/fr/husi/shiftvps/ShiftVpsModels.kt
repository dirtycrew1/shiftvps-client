package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface ShiftVpsTokenStore {
    suspend fun load(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

expect fun platformShiftVpsTokenStore(): ShiftVpsTokenStore
internal expect fun prepareServiceMetrics(owner: String?, policyVersion: Int)

data class ShiftVpsServer(
    val id: Long,
    val name: String,
    val latencyMs: Int? = null,
    val checking: Boolean = false,
    val reachable: Boolean? = null,
    val countryCode: String = "",
)

data class ShiftVpsUiState(
    val signedIn: Boolean = false,
    val accountName: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val probing: Boolean = false,
    val authCode: String? = null,
    val servers: List<ShiftVpsServer> = emptyList(),
    val selectedId: Long? = null,
    val serviceState: ServiceState = ServiceState.Idle,
    val error: String? = null,
    val policyVersion: Int = 1,
    val lastUpdatedMillis: Long? = null,
    val summary: ShiftVpsAccountSummary? = null,
    val summaryLoading: Boolean = false,
    val summaryError: String? = null,
    val accessState: ShiftVpsAccessState = ShiftVpsAccessState.Unknown,
    val accountDetails: ShiftVpsFullAccount? = null,
    val accountDetailsLoading: Boolean = false,
    val accountDetailsError: String? = null,
    val accountAction: ShiftVpsAccountActionState = ShiftVpsAccountActionState(),
)

enum class ShiftVpsAccessState { Unknown, Active, Inactive }

enum class ShiftVpsAccountActionKind {
    AddDevice, RenameDevice, DeleteDevice, ChangePassword, UnlinkTelegram, Checkout, LegacyPayout, Reconcile,
}

enum class ShiftVpsAccountActionPhase { Idle, Running, Success, Error, Uncertain }

data class ShiftVpsAccountActionState(
    val kind: ShiftVpsAccountActionKind? = null,
    val phase: ShiftVpsAccountActionPhase = ShiftVpsAccountActionPhase.Idle,
    val targetDeviceId: Long? = null,
    val errorCode: String? = null,
    val requestId: String? = null,
) {
    val busy: Boolean get() = phase == ShiftVpsAccountActionPhase.Running
}

enum class ShiftVpsAccountLink {
    TelegramLink, SupportTelegram, SupportEmail, Privacy, Terms, Windows, Android, MacArm64, MacAmd64,
    ManualPayment, LegacySettlement,
}

interface ShiftVpsAccountActions {
    fun refreshAccountDetails()
    fun addDevice(name: String)
    fun renameDevice(deviceId: Long, name: String)
    fun deleteDevice(deviceId: Long)
    fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String)
    fun unlinkTelegram()
    fun createCheckout(planCode: String, paymentMethod: String)
    fun requestLegacyPayout(network: String, wallet: String)
    fun reconcileAccountOperation(requestId: String)
    fun openAccountLink(link: ShiftVpsAccountLink)
    fun dismissAccountAction()
}

sealed interface ShiftVpsEvent {
    data class OpenBrowser(val url: String) : ShiftVpsEvent
    data object LaunchVpn : ShiftVpsEvent
}

@Serializable
data class ShiftVpsAccount(
    val name: String = "",
    @SerialName("subscription_expires_at") val subscriptionExpiresAt: String? = null,
    @SerialName("access_active") val accessActive: Boolean = false,
)

@Serializable
data class ShiftVpsAccountSummary(
    val account: ShiftVpsAccountDetails,
    val device: ShiftVpsAccountDevice,
    val links: ShiftVpsAccountLinks,
    val referral: ShiftVpsReferralSummary,
)

@Serializable
data class ShiftVpsAccountDetails(
    val name: String,
    val phone: String,
    @SerialName("subscription_expires_at") val subscriptionExpiresAt: String? = null,
    @SerialName("access_active") val accessActive: Boolean,
    @SerialName("is_free") val isFree: Boolean,
    @SerialName("devices_used") val devicesUsed: Int,
    @SerialName("device_limit") val deviceLimit: Int,
)

@Serializable
data class ShiftVpsAccountDevice(val id: Long, val name: String)

@Serializable
data class ShiftVpsAccountLinks(
    @SerialName("account_url") val accountUrl: String,
    @SerialName("payment_url") val paymentUrl: String,
    @SerialName("referral_url") val referralUrl: String,
)

@Serializable
data class ShiftVpsReferralSummary(
    @SerialName("invitation_url") val invitationUrl: String,
    @SerialName("invited_count") val invitedCount: Int,
    @SerialName("paid_count") val paidCount: Int,
    @SerialName("rewarded_days") val rewardedDays: Int,
    @SerialName("rewarded_months") val rewardedMonths: Int,
    @SerialName("bonus_days") val bonusDays: Int,
)

@Serializable
data class ShiftVpsFullAccount(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("account_id") val accountId: Long,
    val account: ShiftVpsAccountDetails,
    @SerialName("current_device_id") val currentDeviceId: Long,
    val devices: List<ShiftVpsDeviceDetails>,
    val plans: List<ShiftVpsPlan>,
    val payments: List<ShiftVpsPayment>,
    val telegram: ShiftVpsTelegram,
    val billing: ShiftVpsBilling,
    val support: ShiftVpsSupport,
    val downloads: ShiftVpsDownloads,
    @SerialName("legacy_referral") val legacyReferral: ShiftVpsLegacyReferral,
    @SerialName("pending_operations") val pendingOperations: List<ShiftVpsOperation>,
    val capabilities: ShiftVpsAccountCapabilities,
)

@Serializable
data class ShiftVpsDeviceDetails(
    val id: Long,
    val name: String,
    @SerialName("is_current") val isCurrent: Boolean,
    val provisioning: Boolean,
    val online: Boolean? = null,
    @SerialName("traffic_bytes") val trafficBytes: Long? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("status_source") val statusSource: String,
)

@Serializable
data class ShiftVpsPlan(val code: String, val title: String, val days: Int,
    @SerialName("price_rub") val priceRub: Int, val currency: String)

@Serializable
data class ShiftVpsPayment(
    val id: Long,
    @SerialName("plan_code") val planCode: String,
    @SerialName("plan_title") val planTitle: String,
    @SerialName("amount_rub") val amountRub: String,
    val days: Int,
    @SerialName("paid_at") val paidAt: String? = null,
    val status: String,
)

@Serializable
data class ShiftVpsTelegram(
    val linked: Boolean,
    @SerialName("can_unlink") val canUnlink: Boolean,
    val username: String? = null,
    @SerialName("link_url") val linkUrl: String,
    @SerialName("trial_available") val trialAvailable: Boolean,
    @SerialName("trial_days") val trialDays: Int,
)

@Serializable
data class ShiftVpsBilling(
    @SerialName("online_available") val onlineAvailable: Boolean,
    @SerialName("payment_methods") val paymentMethods: List<String>,
    @SerialName("browser_url") val browserUrl: String,
    @SerialName("checkout_hosts") val checkoutHosts: List<String>,
)

@Serializable
data class ShiftVpsSupport(
    val email: String,
    @SerialName("telegram_url") val telegramUrl: String? = null,
    @SerialName("privacy_url") val privacyUrl: String,
    @SerialName("terms_url") val termsUrl: String,
)

@Serializable
data class ShiftVpsDownloads(
    val windows: String,
    val android: String,
    @SerialName("macos_arm64") val macosArm64: String,
    @SerialName("macos_amd64") val macosAmd64: String,
)

@Serializable
data class ShiftVpsLegacyReferral(
    @SerialName("balance_usdt") val balanceUsdt: String,
    @SerialName("payout_min_usdt") val payoutMinUsdt: String,
    @SerialName("can_request_payout") val canRequestPayout: Boolean,
    @SerialName("has_pending_payout") val hasPendingPayout: Boolean,
    @SerialName("settlement_url") val settlementUrl: String,
    val payouts: List<ShiftVpsLegacyPayout>,
)

@Serializable
data class ShiftVpsLegacyPayout(
    val id: Long,
    @SerialName("amount_usdt") val amountUsdt: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("paid_at") val paidAt: String? = null,
    val note: String? = null,
)

@Serializable
data class ShiftVpsOperation(
    @SerialName("request_id") val requestId: String,
    val kind: String,
    val state: String,
    @SerialName("device_id") val deviceId: Long? = null,
    @SerialName("checkout_url") val checkoutUrl: String? = null,
    @SerialName("payout_id") val payoutId: Long? = null,
    val error: String? = null,
    @SerialName("checkout_status") val checkoutStatus: String? = null,
)

@Serializable
data class ShiftVpsAccountCapabilities(
    @SerialName("add_device") val addDevice: Boolean,
    @SerialName("change_password") val changePassword: Boolean,
    @SerialName("unlink_telegram") val unlinkTelegram: Boolean,
    val checkout: Boolean,
)

@Serializable
data class ShiftVpsManifest(
    val account: ShiftVpsAccount,
    val policy: ShiftVpsPolicy,
    @SerialName("server_groups") val serverGroups: List<ShiftVpsServerGroup> = emptyList(),
)

@Serializable
data class ShiftVpsServerGroup(
    val id: String,
    val name: String,
    @SerialName("endpoint_ids") val endpointIds: List<String>,
    @SerialName("country_code") val countryCode: String = "",
)

@Serializable
data class ShiftVpsPolicy(
    val version: Int = 1,
    val routing: ShiftVpsRouting = ShiftVpsRouting(),
    val dns: ShiftVpsDns = ShiftVpsDns(),
    val latency: ShiftVpsLatency = ShiftVpsLatency(),
    @SerialName("refresh_interval_s") val refreshIntervalSeconds: Int = 300,
    @SerialName("connect_timeout_ms") val connectTimeoutMs: Int = 10000,
    val telemetry: ShiftVpsTelemetryPolicy = ShiftVpsTelemetryPolicy(),
) {
    fun validated(): ShiftVpsPolicy {
        require(version > 0)
        require(routing.directDomainSuffixes == listOf("ru"))
        validatedManagedAndroidPackages(routing.directAndroidPackages)
        require(dns.direct == "system")
        require(dns.proxy in setOf("quad9", "cloudflare"))
        require(dns.strategy in setOf("prefer_ipv4", "ipv4_only", "prefer_ipv6"))
        require(latency.timeoutMs in 1000..15000)
        require(latency.concurrency in 1..5)
        require(latency.intervalSeconds in 60..3600)
        require(latency.testUrl in ALLOWED_PROBE_URLS)
        require(refreshIntervalSeconds in 60..3600)
        require(connectTimeoutMs in 3000..30000)
        require(telemetry.batchIntervalSeconds in 30..300)
        return this
    }

    companion object {
        val ALLOWED_PROBE_URLS = setOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
    }
}

@Serializable
data class ShiftVpsRouting(
    @SerialName("direct_domain_suffixes") val directDomainSuffixes: List<String> = listOf("ru"),
    @SerialName("direct_android_packages") val directAndroidPackages: List<String> = emptyList(),
)

@Serializable
data class ShiftVpsDns(
    val direct: String = "system",
    val proxy: String = "quad9",
    val strategy: String = "prefer_ipv4",
)

@Serializable
data class ShiftVpsLatency(
    @SerialName("probe_on_launch") val probeOnLaunch: Boolean = true,
    @SerialName("timeout_ms") val timeoutMs: Int = 5000,
    val concurrency: Int = 3,
    @SerialName("interval_s") val intervalSeconds: Int = 300,
    @SerialName("test_url") val testUrl: String = "https://www.gstatic.com/generate_204",
)

@Serializable
data class ShiftVpsTelemetryPolicy(
    val enabled: Boolean = true,
    @SerialName("batch_interval_s") val batchIntervalSeconds: Int = 30,
)

@Serializable
internal data class DeviceAuthorization(
    @SerialName("grant_id") val grantId: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

@Serializable
internal data class DeviceTokenResponse(
    val state: String,
    @SerialName("access_token") val accessToken: String? = null,
    val capability: String? = null,
    @SerialName("access_active") val accessActive: Boolean? = null,
)

internal class IssuedClientIdentity(val token: String, val accessActive: Boolean) {
    override fun toString(): String = "IssuedClientIdentity"
}

@Serializable
internal data class AccountOperationResponse(val operation: ShiftVpsOperation, val device: ShiftVpsDeviceDetails? = null)

@Serializable
internal data class AccountDeviceResponse(val device: ShiftVpsDeviceDetails)

@Serializable
internal data class AccountDeleteResponse(
    @SerialName("deleted_id") val deletedId: Long,
    @SerialName("current_device_deleted") val currentDeviceDeleted: Boolean,
    @SerialName("logout_required") val logoutRequired: Boolean,
)

@Serializable
internal data class AccountPasswordResponse(
    @SerialName("password_changed") val passwordChanged: Boolean,
    @SerialName("logout_required") val logoutRequired: Boolean,
)

@Serializable
internal data class AccountTelegramResponse(val linked: Boolean)

@Serializable
internal data class ClientMetric(
    val event: String,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("latency_ms") val latencyMs: Int? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("app_version") val appVersion: String,
    val platform: String,
    @SerialName("policy_version") val policyVersion: Int,
    @SerialName("occurred_at") val occurredAt: Long? = null,
)

internal fun rankServers(servers: List<ShiftVpsServer>): List<ShiftVpsServer> = servers.sortedWith(
    compareBy<ShiftVpsServer> { if (it.reachable == true && it.latencyMs != null) 0 else 1 }
        .thenBy { it.latencyMs ?: Int.MAX_VALUE }
        .thenBy { it.id },
)
