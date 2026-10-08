package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.DropReward
import com.nathan.twitchdropsminer.android.data.model.RewardCampaign
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.model.BrowserSessionContext
import com.nathan.twitchdropsminer.android.data.model.TwitchWebClientId
import com.nathan.twitchdropsminer.android.data.model.inEarningOrder
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody

internal const val TwitchTvClientId = "ue6666qo983tsx6so1t0vnawi233wa"
private const val TwitchTvOrigin = "https://android.tv.twitch.tv"
private const val TwitchTvAgent = "Mozilla/5.0 (Linux; Android 7.1; Smart Box C1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
internal const val TwitchClientId = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"
private const val TwitchClientUrl = "https://www.twitch.tv"
private const val MaxSpadeUrlCacheEntries = 64
private const val MaxConcurrentTwitchLookups = 4
private const val MaxAclChannelChecks = 100
private const val MaxOAuthResponseBytes = 64 * 1024
private const val MaxGraphQlResponseBytes = 4 * 1024 * 1024
private const val MaxWatchConfigurationBytes = 2 * 1024 * 1024
private const val MaxInventoryDiagnostics = 12
private const val MaxDiagnosticSummaryCharacters = 1_024
private const val TwitchUserAgent =
    "Dalvik/2.1.0 (Linux; U; Android 16; Pixel Build/AP3A.240905.015) tv.twitch.android.app/25.3.0/2503006"
private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
private val SpadeClientTimeFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Instant,
    val intervalSeconds: Int,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Instant? = null,
)

sealed interface DeviceTokenPollResult {
    data object AuthorizationPending : DeviceTokenPollResult
    data object SlowDown : DeviceTokenPollResult
    data class Authorized(val token: TokenResponse) : DeviceTokenPollResult
}

class DeviceAuthorizationException(
    val oauthError: String,
    message: String,
) : IllegalStateException(message)

class TvRefreshThrottledException(val retryAfterSeconds: Long) : IllegalStateException("TV renewal throttled.")

data class ValidatedToken(
    val userId: String,
    val clientId: String,
    val username: String? = null,
)

data class CurrentDropProgress(
    val dropId: String,
    val currentMinutes: Int,
    val channelId: Long? = null,
)

enum class DropClaimOutcome {
    Claimed,
    AlreadyClaimed,
    Ineligible,
    Failed,
    MissingDropInstanceId,
    UnexpectedResponse,
}

data class DropClaimResult(
    val outcome: DropClaimOutcome,
    val twitchStatus: String? = null,
    val message: String? = null,
) {
    val isTerminalSuccess: Boolean
        get() = outcome == DropClaimOutcome.Claimed || outcome == DropClaimOutcome.AlreadyClaimed

    val shouldCountAsNewClaim: Boolean
        get() = outcome == DropClaimOutcome.Claimed
}

enum class TwitchApiErrorType {
    InvalidToken,
    Network,
    Http,
    GraphQl,
    UnexpectedResponse,
}

class TwitchApiException(
    val type: TwitchApiErrorType,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class CampaignInventory(
    val campaigns: List<Campaign>,
    val sourceRecordCount: Int = campaigns.size,
    val diagnostics: List<String> = emptyList(),
    val rewardCampaigns: List<RewardCampaign> = emptyList(),
    val rewardCampaignsAvailable: Boolean = false,
    val publicCatalog: Boolean = false,
    val catalogUpdatedAt: Instant? = null,
    val rejectedAccountIds: Set<String> = emptySet(),
) {
    val isPartial: Boolean
        get() = diagnostics.isNotEmpty()
}

interface TwitchApi {
    suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization
    suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult
    suspend fun refreshTvSession(session: StoredTwitchSession): StoredTwitchSession = error("TV renewal unavailable.")
    suspend fun validateDropsAccess(session: StoredTwitchSession) { error("Direct Drops validation unavailable.") }
    suspend fun validateAccessToken(accessToken: String): ValidatedToken
    suspend fun validateSession(session: StoredTwitchSession): ValidatedToken = validateAccessToken(session.accessToken)
    suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession =
        throw IllegalStateException("Browser login is unavailable.")
    suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign>
    suspend fun fetchCampaignInventory(session: StoredTwitchSession): CampaignInventory =
        CampaignInventory(fetchCampaigns(session))
    suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int = 20,
    ): List<Channel>
    suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String? = null,
    ): Channel
    suspend fun fetchCampaignChannel(
        session: StoredTwitchSession,
        login: String,
        campaign: Campaign,
    ): Channel {
        if (campaign.publicCatalog && campaign.allowedChannels.isNotEmpty() &&
            campaign.allowedChannels.none { it.login.equals(login, ignoreCase = true) }) {
            return Channel(0, login, login = login)
        }
        val channel = fetchChannel(session, login, campaign.gameName)
        if (campaign.publicCatalog && campaign.allowedChannels.isNotEmpty() &&
            campaign.allowedChannels.none { it.id == channel.id && it.login.equals(channel.login, ignoreCase = true) }) {
            return channel.copy(dropsEnabled = false)
        }
        // Only Twitch's explicit participant ACL permits cross-category earning.
        return if (campaign.permitsCrossCategoryChannel(channel)) {
            channel.copy(dropsEnabled = channel.online)
        } else {
            channel
        }
    }
    suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean
    /** Null means the transport only needs the configured minute cadence. */
    val watchPollIntervalSeconds: Long? get() = null
    suspend fun pollWatchStream(session: StoredTwitchSession, channel: Channel, isCurrent: () -> Boolean): Boolean =
        if (isCurrent()) sendWatchMinute(session, channel) else false
    fun resetWatchSession() = Unit
    suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress?
    suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult
    fun invalidateWatchConfiguration(channelId: Long) = Unit
    fun watchRejectionStatus(): Int? = null
    fun newDeviceId(): String
}

class TwitchApiClient(
    okHttpClient: OkHttpClient,
    private val gqlEndpoint: String = "https://gql.twitch.tv/gql",
    private val twitchWebBaseUrl: String = TwitchClientUrl,
    private val oauthBaseUrl: String = "https://id.twitch.tv",
    private val watchEventTime: () -> Instant = Instant::now,
    private val deviceClientId: String = TwitchClientId,
    private val publicCatalogClient: PublicCatalogClient = PublicCatalogClient(),
    private val watchMonotonicNanos: () -> Long = System::nanoTime,
) : TwitchApi {
    init { require(deviceClientId in setOf(TwitchClientId, TwitchTvClientId)) }
    private val okHttpClient = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val spadeUrls = Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(MaxSpadeUrlCacheEntries, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, String>?,
            ): Boolean = size > MaxSpadeUrlCacheEntries
        },
    )
    @Volatile
    private var lastWatchRejectionStatus: Int? = null
    private val gqlUrl = requireServiceUrl(gqlEndpoint, setOf("gql.twitch.tv"))
    private val webBaseUrl = requireServiceUrl(twitchWebBaseUrl, setOf("www.twitch.tv", "twitch.tv"))
    private val oauthUrl = requireServiceUrl(oauthBaseUrl, setOf("id.twitch.tv"))
    private val localTestOrigin = webBaseUrl.takeIf { it.host.isLoopbackHost() }
    private val hlsWatch = HlsWatchTransport(::playbackAccess, localTestOrigin)
    private var lastTelemetryAt: Long? = null
    override val watchPollIntervalSeconds: Long = 10

    override fun resetWatchSession() {
        hlsWatch.reset()
        spadeUrls.clear()
        lastTelemetryAt = null
        lastWatchRejectionStatus = null
    }

    override suspend fun pollWatchStream(
        session: StoredTwitchSession,
        channel: Channel,
        isCurrent: () -> Boolean,
    ): Boolean {
        lastWatchRejectionStatus = null
        requireFreshBrowserContext(session)
        if (!hlsWatch.poll(session, channel, isCurrent) || !isCurrent()) return false
        val tick = watchMonotonicNanos()
        if (lastTelemetryAt?.let { tick - it >= 59_000_000_000L } != false) {
            lastTelemetryAt = tick
            // Auxiliary telemetry cannot mask an HLS failure or hold up the next playlist poll.
            try {
                withTimeoutOrNull(5_000) { sendWatchTelemetry(session, channel, isCurrent) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // CurrentDrop/Inventory remain the authority, even when telemetry is rejected.
            }
        }
        currentCoroutineContext().ensureActive()
        return isCurrent()
    }

    private suspend fun playbackAccess(session: StoredTwitchSession, channel: Channel): PlaybackAccess? {
        val response = try { gql(session, TwitchOperation.PlaybackAccessToken.request(buildJsonObject {
            put("isLive", true)
            put("isVod", false)
            put("login", channel.login)
            put("platform", "web")
            put("playerType", "site")
            put("vodID", "")
        }), watchRequest = true) } catch (error: TwitchApiException) {
            throw TwitchApiException(error.type, if (error.type == TwitchApiErrorType.InvalidToken)
                "Twitch session expired or could not be validated." else "Twitch playback access request failed; saved credentials preserved.")
        }
        val token = response["data"].asObjectOrNull()?.get("streamPlaybackAccessToken").asObjectOrNull()
            ?: return null
        fun field(name: String): String? = (token[name] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 16_384 }
        return PlaybackAccess(field("value") ?: return null, field("signature") ?: return null)
    }

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", deviceClientId)
                .add("scopes", "")
                .build()
            val request = Request.Builder()
                .url(oauthUrl.newBuilder().addPathSegments("oauth2/device").build())
                .headers(baseHeaders(deviceId))
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code in 400..499 && response.code != 429) {
                        throw DeviceAuthorizationException(
                            "device_authorization_rejected",
                            "Twitch device login was rejected (HTTP ${response.code}). Twitch may no longer support " +
                                "new device logins for this client. Existing saved sessions are preserved.",
                        )
                    }
                    throw TwitchApiException(TwitchApiErrorType.Http,
                        "Twitch device login temporarily failed (HTTP ${response.code}).")
                }
                val root = response.body.readOAuthObject("device login")
                val expiresIn = root["expires_in"].asIntOrNull()
                    ?.takeIf { it in 1..86_400 }
                    ?: throw IllegalStateException("Twitch device login returned an invalid expiry.")
                val deviceCode = root["device_code"].asRequiredNonBlank("device_code")
                val userCode = root["user_code"].asRequiredNonBlank("user_code")
                val verificationUri = root["verification_uri"].asRequiredNonBlank("verification_uri")
                    .takeIf(::isAllowedActivationUrl)
                    ?: throw IllegalStateException("Twitch device login returned an untrusted activation URL.")
                DeviceAuthorization(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUri = verificationUri,
                    expiresAt = Instant.now().plusSeconds(expiresIn.toLong()),
                    intervalSeconds = root["interval"].asIntOrNull()
                        ?.takeIf { it in 1..60 }
                        ?: throw IllegalStateException("Twitch device login returned an invalid polling interval."),
                )
            }
        }

    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", deviceClientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val request = Request.Builder()
                .url(oauthUrl.newBuilder().addPathSegments("oauth2/token").build())
                .headers(baseHeaders(deviceId))
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                // Gate transient HTTP failures before parsing proxy HTML or an OAuth-shaped body.
                if (response.code == 429 || response.code >= 500) {
                    throw TwitchApiException(
                        TwitchApiErrorType.Http,
                        "Twitch token polling temporarily failed (HTTP ${response.code}).",
                    )
                }
                if (response.isSuccessful) {
                    // A successful exchange may consume the code even when its reply is unusable.
                    // Never send the runtime back to polling that same code after a parse failure.
                    return@withContext try {
                        val root = response.body.readOAuthObject("token polling")
                        DeviceTokenPollResult.Authorized(
                            if (deviceClientId == TwitchTvClientId) root.tvTokenResponse()
                            else TokenResponse(root["access_token"].asRequiredNonBlank("access_token")),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        throw DeviceAuthorizationException("invalid_token_response",
                            "Twitch returned an unusable authorization reply. Start login again for a new code; existing credentials were preserved.")
                    }
                }
                val root = response.body.readOAuthObject("token polling")
                // Twitch documents {status:400,message:"authorization_pending"}; also accept
                // the standard OAuth error field, which takes precedence when present.
                when (val oauthError = (root["error"] ?: root["message"]).asStringOrNull()) {
                    "authorization_pending" -> DeviceTokenPollResult.AuthorizationPending
                    "slow_down" -> DeviceTokenPollResult.SlowDown
                    "access_denied" -> throw DeviceAuthorizationException(
                        oauthError,
                        "Twitch device authorization was denied.",
                    )
                    "expired_token", "invalid device code" -> throw DeviceAuthorizationException(
                        "expired_token",
                        "Twitch device authorization expired.",
                    )
                    "invalid_grant" -> throw DeviceAuthorizationException(
                        "invalid_grant",
                        "Twitch rejected the device authorization grant. Start login again for a new code.",
                    )
                    null -> throw TwitchApiException(
                        TwitchApiErrorType.UnexpectedResponse,
                        "Twitch token polling returned a malformed error response.",
                    )
                    else -> throw DeviceAuthorizationException(
                        "unsupported_error",
                        "Twitch rejected device authorization (HTTP ${response.code}). Start login again or use browser login.",
                    )
                }
            }
        }

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken = validateToken(accessToken, deviceClientId)

    override suspend fun validateSession(session: StoredTwitchSession): ValidatedToken =
        validateToken(session.accessToken, sessionClientId(session)).also {
            if (it.userId != session.userId) throw TwitchApiException(
                TwitchApiErrorType.UnexpectedResponse, "Twitch session account mismatch; reconnect Twitch.")
        }

    private fun sessionClientId(session: StoredTwitchSession): String {
        return com.nathan.twitchdropsminer.android.data.model.SessionCapabilities.from(session).oauthClientId
    }

    override suspend fun validateDropsAccess(session: StoredTwitchSession) {
        validateSession(session)
        if (sessionClientId(session) == TwitchTvClientId) {
            val account = fetchTvAccountInventory(session)
            if (!account.usableForLogin) throw IllegalStateException(
                "Twitch inventory has no usable campaign records; TV credentials preserved. " + account.diagnostics.joinToString(" "))
            return
        }
        val inventory = gql(session, TwitchOperation.Inventory.request())
        val campaigns = gql(session, TwitchOperation.Campaigns.request())
        if (inventory["errors"].asArray().isNotEmpty() || campaigns["errors"].asArray().isNotEmpty() ||
            inventory.path("data", "currentUser")["inventory"].asObjectOrNull() == null ||
            campaigns.path("data", "currentUser")["dropCampaigns"] !is JsonArray) {
            throw IllegalStateException("Experimental Android TV login cannot access direct Twitch Inventory and Campaigns. Browser login is required; existing credentials were preserved.")
        }
    }

    override suspend fun refreshTvSession(session: StoredTwitchSession): StoredTwitchSession = withContext(Dispatchers.IO) {
        require(sessionClientId(session) == TwitchTvClientId && session.browserContext == null)
        val refresh = requireNotNull(session.refreshToken)
        val request = Request.Builder().url(oauthUrl.newBuilder().addPathSegments("oauth2/token").build())
            .header("Origin", TwitchTvOrigin).header("User-Agent", TwitchTvAgent)
            .post(FormBody.Builder().add("client_id", TwitchTvClientId).add("grant_type", "refresh_token")
                .add("refresh_token", refresh).build()).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 429) throw TvRefreshThrottledException(response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: 15)
            if (!response.isSuccessful) throw IllegalStateException("TV token renewal failed; saved credentials preserved. Reconnect if this persists.")
            val token = response.body.readOAuthObject("TV renewal").tvTokenResponse()
            session.copy(accessToken = token.accessToken, refreshToken = token.refreshToken,
                savedAt = Instant.now(), tokenExpiresAt = token.expiresAt)
        }
    }

    override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
        context.requireFresh()
        val identity = validateToken(context.accessToken, TwitchWebClientId)
        val session = StoredTwitchSession(context.accessToken, identity.userId, context.deviceId, Instant.now(), context,
            username = identity.username)
        val inventory = gql(session, TwitchOperation.Inventory.request())
        val campaigns = gql(session, TwitchOperation.Campaigns.request())
        if (inventory["errors"].asArray().isNotEmpty() || campaigns["errors"].asArray().isNotEmpty() ||
            inventory.path("data", "currentUser")["inventory"].asObjectOrNull() == null ||
            campaigns.path("data", "currentUser")["dropCampaigns"] !is JsonArray) {
            throw IllegalStateException("Twitch did not accept browser access to Drops. Reconnect with the helper.")
        }
        context.requireFresh()
        return session
    }

    private suspend fun validateToken(accessToken: String, expectedClientId: String): ValidatedToken =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(oauthUrl.newBuilder().addPathSegments("oauth2/validate").build())
                .header("Authorization", "OAuth $accessToken")
                .get()
                .build()
            val (status, root) = try {
                okHttpClient.newCall(request).readCancellable {
                    it.code to if (it.isSuccessful) it.body.readOAuthObject("session validation") else null
                }
            } catch (error: IOException) {
                throw TwitchApiException(
                    TwitchApiErrorType.Network,
                    "Twitch session validation network failure.",
                )
            }
            run {
                if (status == 401) {
                    throw TwitchApiException(
                        TwitchApiErrorType.InvalidToken,
                        "Twitch session expired or could not be validated.",
                    )
                }
                if (status !in 200..299) {
                    throw TwitchApiException(
                        TwitchApiErrorType.Http,
                        "Twitch session validation failed: HTTP $status.",
                    )
                }
                val clientId = root!!["client_id"].asRequiredNonBlank("client_id")
                if (clientId != expectedClientId) {
                    throw TwitchApiException(
                        TwitchApiErrorType.UnexpectedResponse,
                        "Twitch session belongs to a different client. Reconnect Twitch; saved credentials were preserved.",
                    )
                }
                val userId = root["user_id"].asRequiredNonBlank("user_id")
                if (userId.toLongOrNull()?.let { id -> id > 0 } != true) {
                    throw TwitchApiException(
                        TwitchApiErrorType.UnexpectedResponse,
                        "Twitch validation returned an invalid account identity.",
                    )
                }
                ValidatedToken(
                    userId = userId,
                    clientId = clientId,
                    username = root["login"].asStringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,100}")) },
                )
            }
        }

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> =
        fetchCampaignInventory(session).campaigns

    override suspend fun fetchCampaignInventory(session: StoredTwitchSession): CampaignInventory {
        if (sessionClientId(session) == TwitchTvClientId) {
            val account = fetchTvAccountInventory(session)
            val catalog = publicCatalogClient.fetch()
            return mergeTvSources(account, catalog)
        }
        val inventoryResponse = gql(session, TwitchOperation.Inventory.request())
        val upstreamDiagnostics = inventoryResponse.graphQlDiagnostics("Inventory")
        val inventory = inventoryResponse.path("data", "currentUser", "inventory")
        val inProgress = inventory["dropCampaignsInProgress"].asArray()
        val claimedBenefits = inventory["gameEventDrops"].asArray()
            .mapNotNull { benefit ->
                val obj = benefit.asObjectOrNull() ?: return@mapNotNull null
                val id = obj["id"].asStringOrNull() ?: return@mapNotNull null
                val awardedAt = obj["lastAwardedAt"].asInstantOrNull() ?: return@mapNotNull null
                id to awardedAt
            }
            .toMap()

        val campaignsResponse = gql(session, TwitchOperation.Campaigns.request())
        val rewardListing = mapRewardCampaigns(campaignsResponse.path("data")["rewardCampaignsAvailableToUser"])
        val campaignDiagnostics = campaignsResponse.graphQlDiagnostics("Campaign list")
        val availableSummaries = campaignsResponse.path("data", "currentUser")["dropCampaigns"].asArray()
            .mapNotNull { it.asObjectOrNull() }
            .filter { it["status"].asStringOrNull() in setOf("ACTIVE", "UPCOMING") }
        val available = availableSummaries.mapConcurrent(MaxConcurrentTwitchLookups) { summary ->
            if (summary.hasSufficientCampaignDetails()) {
                summary
            } else {
                fetchCampaignDetails(session, summary["id"].asStringOrNull())
                    ?.let { details -> mergeJson(summary, details) }
                    ?: summary
            }
        }
        val merged = mergeCampaignRecords(
            primary = inProgress.mapNotNull { it.asObjectOrNull() },
            secondary = available,
        )

        val mappings = merged.map { campaign ->
            TwitchCampaignMapper.mapCampaign(campaign, claimedBenefits)
        }
        val parsed = mappings.mapNotNull(CampaignMappingResult::campaign).sortedWith(
            compareByDescending<Campaign> { it.active }
                .thenByDescending { it.linked }
            .thenBy { it.endsAt ?: Instant.MAX },
        )
        val diagnostics = (
            upstreamDiagnostics +
                campaignDiagnostics +
                mappings.flatMap(CampaignMappingResult::diagnostics)
            ).take(MaxInventoryDiagnostics)
        if (merged.isNotEmpty() && parsed.isEmpty()) {
            throw TwitchApiException(
                TwitchApiErrorType.UnexpectedResponse,
                "Twitch inventory contained ${merged.size} campaign records but none could be parsed safely. " +
                    diagnostics.joinToString("; ").take(MaxDiagnosticSummaryCharacters),
            )
        }
        return CampaignInventory(
            campaigns = parsed,
            sourceRecordCount = merged.size,
            diagnostics = diagnostics,
            rewardCampaigns = rewardListing.campaigns,
            rewardCampaignsAvailable = rewardListing.available && campaignDiagnostics.isEmpty(),
        )
    }

    private suspend fun fetchTvAccountInventory(session: StoredTwitchSession): TvAccountInventory {
        val response = try { gql(session, TwitchOperation.TvInventory.request()) }
        catch (error: CancellationException) { throw error }
        catch (error: TwitchApiException) {
            throw TwitchApiException(error.type, "Twitch inventory request failed; TV credentials preserved.")
        }
        return parseTvAccountInventory(response)
    }

    private suspend fun fetchCampaignDetails(
        session: StoredTwitchSession,
        campaignId: String?,
    ): JsonObject? {
        if (campaignId.isNullOrBlank()) {
            return null
        }
        return runCatchingCancellable {
            gql(
                session,
                TwitchOperation.CampaignDetails.request(
                    buildJsonObject {
                        put("channelLogin", session.userId)
                        put("dropID", campaignId)
                    },
                ),
            ).path("data", "user")["dropCampaign"].asObjectOrNull()
        }.getOrNullUnlessInvalidToken()
    }

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> {
        val allowedChannels = campaign.allowedChannels
            .distinctBy { it.login.lowercase() }
            .take(MaxAclChannelChecks)
        if (allowedChannels.isNotEmpty()) {
            // Campaign ACL membership is the strongest eligibility signal Twitch exposes here.
            // Page the live checks so large allow-lists remain bounded while later entries are reachable.
            for (page in allowedChannels.chunked(limit.coerceAtLeast(1))) {
                val attempts = page.mapConcurrent(MaxConcurrentTwitchLookups) { channel ->
                    runCatchingCancellable {
                        fetchCampaignChannel(session, channel.login, campaign)
                    }
                }
                val resolved = attempts.mapNotNull { it.getOrNullUnlessInvalidToken() }
                if (resolved.isEmpty() && attempts.all { it.isFailure }) {
                    throw attempts.firstNotNullOf { it.exceptionOrNull() }
                }
                val eligible = resolved.filter { it.online && it.dropsEnabled }
                if (eligible.isNotEmpty()) return eligible
            }
            return emptyList()
        }

        val slugResponse = gql(
            session,
            TwitchOperation.SlugRedirect.request(
                buildJsonObject { put("name", campaign.gameName) },
            ),
        )
        val slug = slugResponse.path("data", "game")["slug"].asStringOrNull()
            ?: campaign.gameName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        val directoryResponse = gql(
            session,
            TwitchOperation.GameDirectory.request(
                buildJsonObject {
                    put("limit", limit)
                    put("slug", slug)
                    put("imageWidth", 50)
                    put("includeCostreaming", false)
                    putJsonObject("options") {
                        put("sort", "VIEWER_COUNT")
                        put("requestID", "TDM-ANDROID")
                        put("tags", buildJsonArray {})
                        put("freeformTags", JsonNull)
                        put("systemFilters", buildJsonArray { add(JsonPrimitive("DROPS_ENABLED")) })
                        put("broadcasterLanguages", buildJsonArray {})
                        put("includeRestricted", buildJsonArray { add(JsonPrimitive("SUB_ONLY_LIVE")) })
                        putJsonObject("recommendationsContext") { put("platform", "android") }
                    }
                    put("sortTypeIsRecency", false)
                },
            ),
        )

        val streams = directoryResponse.path("data", "game", "streams")["edges"].asArray()
        return streams.mapNotNull { edge ->
            edge.path("node").asObjectOrNull()?.toDirectoryChannel(
                gameName = campaign.gameName,
                dropsEnabled = true,
            )
        }.filter { it.online && it.dropsEnabled }
    }

    override suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String?,
    ): Channel {
        val response = gql(
            session,
            TwitchOperation.GetStreamInfo.request(buildJsonObject { put("channel", login) }),
        )
        val user = response.path("data", "user")
        val stream = user["stream"].asObjectOrNull()
            ?: return Channel(
                id = user["id"].asLong(0L),
                name = user["displayName"].asString(login),
                game = expectedGame,
                online = false,
                dropsEnabled = false,
                aclBased = true,
                login = user["login"].asString(login),
            )
        val settings = user["broadcastSettings"].asObjectOrNull()
        val game = settings?.get("game").asObjectOrNull()
        val actualGameName = if (settings != null) {
            game?.get("displayName").asStringOrNull()
        } else {
            expectedGame
        }
        val matchesExpectedGame = expectedGame.isNullOrBlank() ||
            actualGameName?.equals(expectedGame, ignoreCase = true) == true
        return Channel(
            id = user["id"].asLong(0L),
            name = user["displayName"].asString(login),
            game = actualGameName,
            gameId = game?.get("id").asStringOrNull(),
            viewers = stream["viewersCount"].asIntOrNull(),
            online = true,
            dropsEnabled = matchesExpectedGame,
            aclBased = true,
            broadcastId = stream["id"].asStringOrNull(),
            title = settings?.get("title").asStringOrNull(),
            login = user["login"].asString(login),
        )
    }

    override suspend fun sendWatchMinute(
        session: StoredTwitchSession,
        channel: Channel,
    ): Boolean = sendWatchTelemetry(session, channel) { true }

    private suspend fun sendWatchTelemetry(
        session: StoredTwitchSession,
        channel: Channel,
        isCurrent: () -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isCurrent()) return@withContext false
        lastWatchRejectionStatus = null
        val broadcastId = channel.broadcastId?.takeIf { it.isNotBlank() }
            ?: return@withContext false
        val userId = session.userId.toLongOrNull()
            ?: return@withContext false
        var usedCachedUrl = spadeUrls[channel.id] != null
        var spadeUrl = spadeUrls[channel.id]
            ?: resolveSpadeUrl(session, channel, isCurrent)?.also { if (isCurrent()) spadeUrls[channel.id] = it }
            ?: return@withContext false
        val encodedPayload = encodeWatchPayload(
            userId = userId,
            channel = channel,
            broadcastId = broadcastId,
        )
        var retriedWithFreshConfiguration = false
        while (true) {
            if (!isCurrent()) return@withContext false
            if (postWatchEvent(session, spadeUrl, encodedPayload)) {
                return@withContext true
            }
            if (!isCurrent()) return@withContext false
            if (!usedCachedUrl || retriedWithFreshConfiguration) {
                spadeUrls.remove(channel.id)
                return@withContext false
            }
            spadeUrls.remove(channel.id)
            spadeUrl = resolveSpadeUrl(session, channel, isCurrent)
                ?.also { if (isCurrent()) spadeUrls[channel.id] = it }
                ?: return@withContext false
            usedCachedUrl = false
            retriedWithFreshConfiguration = true
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    override fun invalidateWatchConfiguration(channelId: Long) {
        spadeUrls.remove(channelId)
        hlsWatch.invalidate(channelId)
    }

    override fun watchRejectionStatus(): Int? = lastWatchRejectionStatus

    private suspend fun postWatchEvent(
        session: StoredTwitchSession,
        spadeUrl: String,
        encodedPayload: String,
    ): Boolean {
        if (!isAllowedSpadeUrl(spadeUrl)) {
            throw TwitchApiException(
                TwitchApiErrorType.UnexpectedResponse,
                "Twitch returned an untrusted watch-event destination.",
            )
        }
        val request = Request.Builder()
            .url(spadeUrl)
            .headers(sessionHeaders(session))
            .post(FormBody.Builder().add("data", encodedPayload).build())
            .build()
        val status = try {
            okHttpClient.newCall(request).readCancellable { it.code }
        } catch (error: IOException) {
            throw TwitchApiException(
                TwitchApiErrorType.Network,
                "Twitch watch-event network failure.",
            )
        }
        currentCoroutineContext().ensureActive()
        lastWatchRejectionStatus = status.takeUnless { it == 204 }
        return status == 204
    }

    override suspend fun currentDrop(
        session: StoredTwitchSession,
        channelId: Long,
    ): CurrentDropProgress? {
        val response = try { gql(
            session,
            TwitchOperation.CurrentDrop.request(
                buildJsonObject {
                    put("channelID", channelId.toString())
                    put("channelLogin", "")
                },
            ),
            watchRequest = true,
        ) } catch (error: TwitchApiException) {
            if (sessionClientId(session) != TwitchTvClientId) throw error
            throw TwitchApiException(error.type, "Twitch progress request failed.")
        }
        return parseCurrentDrop(response)
    }

    override suspend fun claimDrop(
        session: StoredTwitchSession,
        dropInstanceId: String,
    ): DropClaimResult {
        if (dropInstanceId.isBlank()) {
            return DropClaimResult(
                outcome = DropClaimOutcome.MissingDropInstanceId,
                message = "Drop instance ID is missing.",
            )
        }
        val response = gql(
            session,
            TwitchOperation.ClaimDrop.request(
                buildJsonObject {
                    putJsonObject("input") {
                        put("dropInstanceID", dropInstanceId)
                    }
                },
            ),
        )
        val data = response["data"].asObjectOrNull()
            ?: return DropClaimResult(
                outcome = DropClaimOutcome.UnexpectedResponse,
                message = "Twitch claim response did not contain a data object.",
            )
        val dataErrors = data["errors"].asArray()
        if (dataErrors.isNotEmpty()) {
            return DropClaimResult(
                outcome = DropClaimOutcome.Failed,
                message = "Twitch returned claim errors: $dataErrors",
            )
        }
        val claimRewards = data["claimDropRewards"]
        if (claimRewards == null || claimRewards is JsonNull) {
            return DropClaimResult(
                outcome = DropClaimOutcome.Failed,
                message = "Twitch did not return claimDropRewards.",
            )
        }
        val status = claimRewards.asObjectOrNull()?.get("status").asStringOrNull()
            ?: return DropClaimResult(
                outcome = DropClaimOutcome.UnexpectedResponse,
                message = "Twitch claim response did not include a status.",
            )
        return when (status) {
            "ELIGIBLE_FOR_ALL" -> DropClaimResult(DropClaimOutcome.Claimed, twitchStatus = status)
            "DROP_INSTANCE_ALREADY_CLAIMED" -> DropClaimResult(
                DropClaimOutcome.AlreadyClaimed,
                twitchStatus = status,
                message = "Drop was already claimed.",
            )
            "NOT_ELIGIBLE",
            "DROP_INSTANCE_NOT_ELIGIBLE",
            "ACCOUNT_NOT_CONNECTED" -> DropClaimResult(
                DropClaimOutcome.Ineligible,
                twitchStatus = status,
                message = "Twitch definitively rejected this drop for account-link or eligibility requirements.",
            )
            else -> DropClaimResult(
                outcome = DropClaimOutcome.Failed,
                twitchStatus = status,
                message = "Twitch claim operation returned status $status.",
            )
        }
    }

    override fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")

    private suspend fun gql(
        session: StoredTwitchSession,
        payload: JsonObject,
        watchRequest: Boolean = false,
    ): JsonObject = withContext(Dispatchers.IO) {
        check(sessionClientId(session) != TwitchTvClientId || payload["operationName"].asString() !in
            setOf(TwitchOperation.Campaigns.operationName, TwitchOperation.CampaignDetails.operationName)) {
            "TV sessions use public catalog discovery."
        }
        val request = Request.Builder()
            .url(gqlUrl)
            .headers(sessionHeaders(session, graphql = true))
            .post(payload.toString().toRequestBody(JsonMediaType))
            .build()
        val (status, body) = try {
            val call = okHttpClient.newCall(request)
            if (watchRequest) call.timeout().timeout(5, java.util.concurrent.TimeUnit.SECONDS)
            call.readCancellable { response ->
                response.code to if (response.isSuccessful) {
                    response.body.readBoundedString(MaxGraphQlResponseBytes, "GraphQL")
                } else ""
            }
        } catch (error: IOException) {
            throw TwitchApiException(
                TwitchApiErrorType.Network,
                "Twitch GraphQL network failure.",
            )
        }
        run {
            if (status == 401 || status == 403) {
                rejectGraphQlAuthentication(session, "HTTP $status", watchRequest)
            }
            if (status !in 200..299) {
                throw TwitchApiException(
                    TwitchApiErrorType.Http,
                    "Twitch GraphQL failed: HTTP $status",
                )
            }
            val root = runCatching { json.parseToJsonElement(body) }.getOrElse { error ->
                throw TwitchApiException(
                    TwitchApiErrorType.UnexpectedResponse,
                    "Twitch GraphQL returned invalid JSON.",
                    error,
                )
            }
            val first = if (root is JsonArray) root.firstOrNull() else root
            val obj = first as? JsonObject
                ?: throw TwitchApiException(
                    TwitchApiErrorType.UnexpectedResponse,
                    "Unexpected Twitch GraphQL response shape.",
                )
            val errors = obj["errors"].asArray()
            if (!obj.containsKey("data") && errors.isNotEmpty() && errors.all { error ->
                    val item = error.asObjectOrNull()
                    item != null && !item.containsKey("path") && item["message"].asStringOrNull() in
                        setOf("invalid oauth token", "failed integrity check")
                }
            ) {
                rejectGraphQlAuthentication(session, "authentication or integrity check", watchRequest)
            }
            if (errors.isNotEmpty() && obj["data"].asObjectOrNull() == null) {
                throw TwitchApiException(
                    TwitchApiErrorType.GraphQl,
                    "Twitch GraphQL error: ${errors.boundedSummary()}",
                )
            }
            obj
        }
    }

    private suspend fun rejectGraphQlAuthentication(session: StoredTwitchSession, reason: String, watchRequest: Boolean): Nothing {
        try {
            if (watchRequest) {
                withTimeoutOrNull(5_000) { validateSession(session) }
                    ?: throw TwitchApiException(TwitchApiErrorType.Network, "Twitch session validation timed out.")
            } else validateSession(session)
        } catch (error: TwitchApiException) {
            if (error.type == TwitchApiErrorType.InvalidToken) throw error
            throw TwitchApiException(
                TwitchApiErrorType.Http,
                "Twitch GraphQL rejected the request ($reason), but the rejection " +
                    "could not be confirmed because session validation failed.",
            )
        }
        throw TwitchApiException(
            TwitchApiErrorType.Http,
            "Twitch GraphQL rejected the request ($reason) although the OAuth session is still valid. " +
                "Saved credentials were preserved; Twitch may require browser-based login.",
        )
    }

    private fun baseHeaders(deviceId: String): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add("Accept", "application/json")
            .add("Client-Id", deviceClientId)
            .add("Origin", if (deviceClientId == TwitchTvClientId) TwitchTvOrigin else TwitchClientUrl)
            .add("Referer", if (deviceClientId == TwitchTvClientId) TwitchTvOrigin else TwitchClientUrl)
            .add("User-Agent", if (deviceClientId == TwitchTvClientId) TwitchTvAgent else TwitchUserAgent)
            .add("X-Device-Id", deviceId)
            .build()

    private fun sessionHeaders(session: StoredTwitchSession, graphql: Boolean = false): okhttp3.Headers {
        val isTv = session.clientId == TwitchTvClientId
        val browser = session.browserContext
        requireFreshBrowserContext(session)
        return okhttp3.Headers.Builder()
            .add("Accept", "*/*")
            .add("Client-Id", sessionClientId(session))
            .add("Authorization", "OAuth ${session.accessToken}")
            .add("Client-Session-Id", session.deviceId.take(16))
            .add("Origin", if (isTv) TwitchTvOrigin else TwitchClientUrl)
            .add("Referer", if (isTv) TwitchTvOrigin else TwitchClientUrl)
            .add("User-Agent", browser?.userAgent ?: if (isTv) TwitchTvAgent else TwitchUserAgent)
            .add("X-Device-Id", session.deviceId)
            .apply {
                if (graphql && browser != null) browser.headers.forEach { (name, value) -> set(name, value) }
            }
            .build()
    }

    private fun requireFreshBrowserContext(session: StoredTwitchSession) {
        try { session.browserContext?.requireFresh() } catch (_: IllegalArgumentException) {
            throw TwitchApiException(TwitchApiErrorType.Http,
                "Browser session proof expired. Keep the browser service or desktop helper running, or reconnect Twitch.")
        }
    }

    private fun encodeWatchPayload(
        userId: Long,
        channel: Channel,
        broadcastId: String,
    ): String {
        val payload = buildJsonArray {
            add(
                buildJsonObject {
                    put("event", "minute-watched")
                    putJsonObject("properties") {
                        put("broadcast_id", broadcastId)
                        put("channel_id", channel.id.toString())
                        put("channel", channel.login)
                        put("client_time", SpadeClientTimeFormatter.format(watchEventTime()))
                        put("game", channel.game.orEmpty())
                        put("game_id", channel.gameId.orEmpty())
                        put("hidden", false)
                        put("is_live", true)
                        put("live", true)
                        put("location", "channel")
                        put("logged_in", true)
                        put("minutes_logged", 1)
                        put("muted", false)
                        put("player", "site")
                        put("user_id", userId)
                    }
                },
            )
        }.toString()
        return Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    private suspend fun resolveSpadeUrl(
        session: StoredTwitchSession,
        channel: Channel,
        isCurrent: () -> Boolean,
    ): String? {
        val channelUrl = webBaseUrl.newBuilder()
            .addPathSegment(channel.login)
            .build()
        val channelHtml = getWatchConfiguration(session, channelUrl.toString(), allowSettingsHost = false)
        if (!isCurrent()) return null
        findAllowedSpadeUrl(channelHtml)?.let { return it }
        val settingsUrl = SettingsUrlPattern.find(channelHtml)?.groupValues?.get(1)
            ?.takeIf(::isAllowedSettingsUrl)
            ?: return null
        val settings = getWatchConfiguration(session, settingsUrl, allowSettingsHost = true)
        return findAllowedSpadeUrl(settings)
    }

    private fun findAllowedSpadeUrl(configuration: String): String? =
        sequenceOf(BeaconUrlPattern, SpadeUrlPattern)
            .flatMap { pattern -> pattern.findAll(configuration).map { it.groupValues[1] } }
            .firstOrNull(::isAllowedSpadeUrl)

    private suspend fun getWatchConfiguration(
        session: StoredTwitchSession,
        url: String,
        allowSettingsHost: Boolean,
    ): String {
        val trusted = if (allowSettingsHost) isAllowedSettingsUrl(url) else isAllowedChannelPageUrl(url)
        if (!trusted) {
            throw TwitchApiException(
                TwitchApiErrorType.UnexpectedResponse,
                "Twitch returned an untrusted watch-configuration destination.",
            )
        }
        val request = Request.Builder()
            .url(url)
            .headers(sessionHeaders(session))
            .get()
            .build()
        return try {
            okHttpClient.newCall(request).readCancellable {
                if (!it.isSuccessful) {
                    throw TwitchApiException(TwitchApiErrorType.Http,
                        "Twitch watch configuration failed: HTTP ${it.code}; cached configuration will be renewed.")
                }
                it.body.readBoundedString(MaxWatchConfigurationBytes, "watch configuration")
            }
        } catch (error: IOException) {
            throw TwitchApiException(
                TwitchApiErrorType.Network,
                "Twitch watch configuration network failure.",
            )
        }
    }

    private fun isAllowedActivationUrl(candidate: String): Boolean {
        val url = candidate.toHttpUrlOrNull() ?: return false
        if (oauthUrl.host.isLoopbackHost() && url.sameOrigin(oauthUrl)) return true
        return url.isHttps && url.host in setOf("www.twitch.tv", "twitch.tv") &&
            url.encodedPath.trimEnd('/') == "/activate"
    }

    private fun isAllowedChannelPageUrl(candidate: String): Boolean {
        val url = candidate.toHttpUrlOrNull() ?: return false
        return url.sameOrigin(webBaseUrl) && (url.isHttps || url.host.isLoopbackHost())
    }

    private fun isAllowedSettingsUrl(candidate: String): Boolean {
        val url = candidate.toHttpUrlOrNull() ?: return false
        if (localTestOrigin != null && url.sameOrigin(localTestOrigin)) return true
        return isTrustedTwitchSettingsUrl(candidate)
    }

    private fun isAllowedSpadeUrl(candidate: String): Boolean {
        val url = candidate.toHttpUrlOrNull() ?: return false
        if (localTestOrigin != null && url.sameOrigin(localTestOrigin)) return true
        return isTrustedTwitchWatchEventUrl(candidate)
    }

}

private val BeaconUrlPattern =
    Regex("\\\"beacon_?url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
private val SpadeUrlPattern =
    Regex("\\\"spade_?url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
private val SettingsUrlPattern = Regex(
    "src=[\\\"'](https?://[^\\\"']+/config/settings\\.[0-9a-f]{32}\\.js)[\\\"']",
    RegexOption.IGNORE_CASE,
)

internal fun isTrustedTwitchSettingsUrl(candidate: String): Boolean {
    val url = candidate.toHttpUrlOrNull() ?: return false
    return url.isHttps &&
        url.host in setOf("assets.twitch.tv", "static.twitchcdn.net") &&
        Regex("^/config/settings\\.[0-9a-f]{32}\\.js$").matches(url.encodedPath)
}

internal fun isTrustedTwitchWatchEventUrl(candidate: String): Boolean {
    val url = candidate.toHttpUrlOrNull() ?: return false
    if (!url.isHttps) return false
    return url.host == "spade.twitch.tv" ||
        (url.host == "beacon.twitch.tv" && url.encodedPath == "/track")
}

internal data class CampaignMappingResult(
    val campaign: Campaign?,
    val diagnostics: List<String> = emptyList(),
)

internal object TwitchCampaignMapper {
    fun mapCampaign(
        campaign: JsonObject,
        claimedBenefits: Map<String, Instant>,
    ): CampaignMappingResult {
        val campaignId = campaign["id"].asStringOrNull()?.trim().orEmpty()
        if (campaignId.isEmpty()) {
            return CampaignMappingResult(null, listOf("Campaign record is missing a nonblank id."))
        }
        val game = campaign["game"].asObjectOrNull()
        val gameName = game?.get("displayName").asStringOrNull()
            ?: game?.get("name").asStringOrNull()
        if (gameName.isNullOrBlank()) {
            return CampaignMappingResult(
                null,
                listOf("Campaign ${campaignId.take(80)} is missing a game name."),
            )
        }

        val diagnostics = mutableListOf<String>()
        val dropElement = campaign["timeBasedDrops"]
        if (dropElement != null && dropElement !is JsonNull && dropElement !is JsonArray) {
            return CampaignMappingResult(
                null,
                listOf("Campaign ${campaignId.take(80)} has a non-array drop list."),
            )
        }
        if (dropElement == null) {
            diagnostics += "Campaign ${campaignId.take(80)} omitted its drop list; retained as partial data."
        }
        val drops = dropElement.asArray().mapIndexedNotNull { index, element ->
            val obj = element.asObjectOrNull()
            if (obj == null) {
                diagnostics += "Campaign ${campaignId.take(80)} drop ${index + 1} is not an object."
                null
            } else {
                runCatching { obj.toCampaignDrop(claimedBenefits) }
                    .onFailure { error ->
                        diagnostics += "Campaign ${campaignId.take(80)} drop ${index + 1}: " +
                            (error.message ?: "invalid record").take(160)
                    }
                    .getOrNull()
            }
        }.inEarningOrder()
        return runCatching {
            CampaignMappingResult(
                campaign.toCampaign(claimedBenefits, drops),
                diagnostics.take(MaxInventoryDiagnostics),
            )
        }.getOrElse { error ->
            CampaignMappingResult(
                null,
                listOf(
                    "Campaign ${campaignId.take(80)} could not be parsed: " +
                        (error.message ?: "invalid record").take(160),
                ),
            )
        }
    }

    fun campaignFromJson(
        campaign: JsonObject,
        claimedBenefits: Map<String, Instant>,
    ): Campaign? = mapCampaign(campaign, claimedBenefits).campaign
}

enum class TwitchOperation(
    val operationName: String,
    val sha256Hash: String,
    val defaultVariables: JsonObject = buildJsonObject {},
) {
    PlaybackAccessToken(
        "PlaybackAccessToken",
        "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9",
    ),
    GetStreamInfo(
        "VideoPlayerStreamInfoOverlayChannel",
        "198492e0857f6aedead9665c81c5a06d67b25b58034649687124083ff288597d",
    ),
    ClaimDrop(
        "DropsPage_ClaimDropRewards",
        "a455deea71bdc9015b78eb49f4acfbce8baa7ccbedd28e549bb025bd0f751930",
    ),
    Inventory(
        "Inventory",
        "8337eb8541b314040b0edde0c09c5c7a2783ba1960aa9edfbf3bac16d0fec404",
        buildJsonObject { put("fetchRewardCampaigns", false) },
    ),
    TvInventory(
        "Inventory",
        "d86775d0ef16a63a33ad52e80eaff963b2d5b72fada7c991504a57496e1d8e4b",
        buildJsonObject { put("fetchRewardCampaigns", false) },
    ),
    CurrentDrop(
        "DropCurrentSessionContext",
        "4d06b702d25d652afb9ef835d2a550031f1cf762b193523a92166f40ea3d142b",
    ),
    Campaigns(
        "ViewerDropsDashboard",
        "d9cae7761dafab85908c85e6683cb4201b449e66ac3bb5e894f15ff12aeafaa7",
        buildJsonObject { put("fetchRewardCampaigns", true) },
    ),
    CampaignDetails(
        "DropCampaignDetails",
        "039277bf98f3130929262cc7c6efd9c141ca3749cb6dca442fc8ead9a53f77c1",
    ),
    GameDirectory(
        "DirectoryPage_Game",
        "cb5dc816e139dcb8a118f14b4b677d59abc224a4b016c4bc2bb00a47fe0ddec4",
    ),
    SlugRedirect(
        "DirectoryGameRedirect",
        "1f0300090caceec51f33c5e20647aceff9017f740f223c3c532ba6fa59f6b6cc",
    );

    fun request(variables: JsonObject = defaultVariables): JsonObject =
        buildJsonObject {
            put("operationName", operationName)
            put("variables", variables)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", sha256Hash)
                }
            }
        }
}

private fun JsonObject.toCampaign(
    claimedBenefits: Map<String, Instant>,
    mappedDrops: List<CampaignDrop>? = null,
): Campaign {
    val now = Instant.now()
    val game = this["game"].asObjectOrNull()
    val drops = mappedDrops ?: this["timeBasedDrops"].asArray()
        .mapNotNull { drop -> drop.asObjectOrNull()?.toCampaignDrop(claimedBenefits) }
        .inEarningOrder()
    val startsAt = this["startAt"].asInstantOrNull()
    val endsAt = this["endAt"].asInstantOrNull()
    val status = this["status"].asStringOrNull()
    val linkUrl = this["accountLinkURL"].asStringOrNull()
    val self = this["self"].asObjectOrNull()
    val linked = self?.get("isAccountConnected").asBool(false)
    val linkStatusKnown = self?.containsKey("isAccountConnected") == true || linkUrl != null
    val allowedChannels = this.path("allow")["channels"].asArray()
        .mapNotNull { it.asObjectOrNull()?.toAllowedChannel() }
    val expired = status == "EXPIRED" || (endsAt != null && now >= endsAt)
    val upcoming = !expired && (status == "UPCOMING" || (startsAt != null && now < startsAt))
    val active = !expired && !upcoming && (
        status == "ACTIVE" ||
            (startsAt != null && endsAt != null && now >= startsAt && now < endsAt)
        )
    return Campaign(
        id = this["id"].asString(),
        name = this["name"].asString("Unnamed campaign"),
        gameName = game?.get("displayName").asStringOrNull()
            ?: game?.get("name").asStringOrNull()
            ?: "Unknown game",
        gameBoxArtUrl = game?.get("boxArtURL").asStringOrNull(),
        gameId = game?.get("id").asStringOrNull(),
        campaignUrl = "https://www.twitch.tv/drops/campaigns?dropID=${this["id"].asString()}",
        linkUrl = linkUrl,
        startsAt = startsAt,
        endsAt = endsAt,
        linked = linked,
        linkStatusKnown = linkStatusKnown,
        active = active,
        upcoming = upcoming,
        expired = expired,
        claimedDrops = drops.count { it.isClaimed },
        totalDrops = drops.size,
        drops = drops,
        allowedChannels = allowedChannels,
    )
}

private fun mergeJson(primary: JsonObject, secondary: JsonObject): JsonObject =
    JsonObject(
        (primary.keys + secondary.keys).associateWith { key ->
            val primaryValue = primary[key]
            val secondaryValue = secondary[key]
            if (primaryValue is JsonObject && secondaryValue is JsonObject) {
                mergeJson(primaryValue, secondaryValue)
            } else {
                primaryValue ?: secondaryValue ?: JsonNull
            }
        },
    )

private fun JsonObject.hasSufficientCampaignDetails(): Boolean =
    this["timeBasedDrops"] is JsonArray && this["game"] is JsonObject

private fun mergeCampaignRecords(
    primary: List<JsonObject>,
    secondary: List<JsonObject>,
): List<JsonObject> {
    val records = linkedMapOf<String, JsonObject>()
    secondary.forEach { campaign ->
        val id = campaign["id"].asStringOrNull() ?: return@forEach
        records[id] = campaign
    }
    primary.forEach { campaign ->
        val id = campaign["id"].asStringOrNull() ?: return@forEach
        records[id] = records[id]?.let { existing -> mergeJson(campaign, existing) } ?: campaign
    }
    return records.values.toList()
}

private fun JsonObject.toCampaignDrop(claimedBenefits: Map<String, Instant>): CampaignDrop {
    val dropId = this["id"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("missing a nonblank id")
    val benefits = this["benefitEdges"].asArray().mapNotNull { benefit ->
        benefit.asObjectOrNull()?.toDropReward()
    }
    val startsAt = this["startAt"].asInstantOrNull()
    val endsAt = this["endAt"].asInstantOrNull()
    val currentMinutes = this.path("self")["currentMinutesWatched"].let { value ->
        if (value == null) 0 else value.asIntOrNull()
            ?: throw IllegalArgumentException("currentMinutesWatched is not an integer")
    }.coerceAtLeast(0)
    val requiredMinutes = this["requiredMinutesWatched"].asIntOrNull()
        ?: throw IllegalArgumentException("requiredMinutesWatched is not an integer")
    require(requiredMinutes >= 0) { "requiredMinutesWatched is negative" }
    val self = this["self"].asObjectOrNull()
    val claimId = self?.get("dropInstanceID").asStringOrNull()
    val claimedBySelf = self?.get("isClaimed").asBool(false)
    val claimedBenefitAwardTimes = benefits.mapNotNull { reward ->
        reward.id?.let(claimedBenefits::get)
    }
    val claimedByBenefit = self == null &&
        startsAt != null &&
        endsAt != null &&
        benefits.isNotEmpty() && claimedBenefitAwardTimes.size == benefits.size &&
        claimedBenefitAwardTimes.all { awardedAt -> awardedAt >= startsAt && awardedAt < endsAt }
    val isClaimed = claimedBySelf || claimedByBenefit
    val displayedMinutes = if (isClaimed) requiredMinutes else currentMinutes
    return CampaignDrop(
        id = dropId,
        name = this["name"].asString("Drop"),
        currentMinutes = displayedMinutes,
        requiredMinutes = requiredMinutes,
        progress = if (requiredMinutes <= 0) 0f else (displayedMinutes.toFloat() / requiredMinutes).coerceIn(0f, 1f),
        isClaimed = isClaimed,
        canClaim = !isClaimed && requiredMinutes > 0 && currentMinutes >= requiredMinutes,
        rewards = benefits,
        startsAt = startsAt,
        endsAt = endsAt,
        claimId = claimId,
        claimEvidenceKnown = self?.get("isClaimed") is JsonPrimitive && (self["isClaimed"] as? JsonPrimitive)?.booleanOrNull != null,
        preconditionDropIds = this["preconditionDrops"].asArray()
            .mapNotNull { it.asObjectOrNull()?.get("id").asStringOrNull() },
    )
}

private fun JsonObject.toDropReward(): DropReward =
    DropReward(
        name = this.path("benefit")["name"].asString(this["name"].asString("Reward")),
        type = this.path("benefit")["distributionType"].asString(
            this.path("benefit")["type"].asString(this["type"].asString("UNKNOWN")),
        ),
        imageUrl = this.path("benefit")["imageAssetURL"].asStringOrNull()
            ?: this["imageAssetURL"].asStringOrNull(),
        id = this.path("benefit")["id"].asStringOrNull(),
    )

private fun JsonObject.toAllowedChannel(): Channel {
    val login = this["login"].asString(
        this["name"].asString(this["displayName"].asString("channel")),
    )
    return Channel(
        id = this["id"].asLong(0L),
        name = this["displayName"].asString(this["name"].asString(login)),
        aclBased = true,
        login = login,
    )
}

private fun JsonObject.toDirectoryChannel(gameName: String, dropsEnabled: Boolean): Channel {
    val broadcaster = this["broadcaster"].asObjectOrNull()
    val game = this["game"].asObjectOrNull()
    val login = broadcaster?.get("login").asString(
        broadcaster?.get("displayName").asString("streamer"),
    )
    return Channel(
        id = broadcaster?.get("id").asLong(0L),
        name = broadcaster?.get("displayName").asString(
            login,
        ),
        game = game?.get("displayName").asStringOrNull() ?: gameName,
        gameId = game?.get("id").asStringOrNull(),
        viewers = this["viewersCount"].asIntOrNull(),
        online = true,
        dropsEnabled = dropsEnabled,
        broadcastId = this["id"].asStringOrNull(),
        title = this["title"].asStringOrNull(),
        login = login,
    )
}

private fun JsonElement?.path(vararg keys: String): JsonObject {
    var current: JsonElement? = this
    for (key in keys) {
        current = current.asObjectOrNull()?.get(key)
    }
    return current.asObjectOrNull() ?: buildJsonObject {}
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArray(): List<JsonElement> =
    (this as? JsonArray)?.toList() ?: emptyList()

private fun JsonObject.tvTokenResponse(): TokenResponse {
    val expiry = this["expires_in"]
    // First-party token replies can omit a finite lifetime. Keep that distinct from an
    // expired token; TV acceptance still requires OAuth identity and usable account inventory.
    val seconds = if (expiry == null || expiry == JsonNull) null else
        (expiry as? JsonPrimitive)?.longOrNull
            ?.takeIf { it in 0..(Long.MAX_VALUE / 1000) }
            ?: throw IllegalStateException("Invalid TV token lifetime.")
    return TokenResponse(
        accessToken = this["access_token"].asRequiredNonBlank("access_token"),
        refreshToken = this["refresh_token"].asRequiredNonBlank("refresh_token"),
        // Bound to a representable millisecond delay, not an arbitrary one-year policy.
        expiresAt = seconds?.takeIf { it > 0 }?.let { Instant.now().plusSeconds(it) },
    )
}

private fun JsonElement?.asString(default: String = ""): String =
    asStringOrNull() ?: default

private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.asRequiredNonBlank(fieldName: String): String =
    asStringOrNull()?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IllegalStateException("Twitch response is missing $fieldName.")

private fun JsonElement?.asInt(default: Int): Int =
    asIntOrNull() ?: default

private fun JsonElement?.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.intOrNull

private fun JsonElement?.asLong(default: Long): Long =
    asStringOrNull()?.toLongOrNull() ?: default

private fun JsonElement?.asBool(default: Boolean): Boolean =
    (this as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonElement?.asInstantOrNull(): Instant? =
    asStringOrNull()?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }

@Suppress("unused")
private fun JsonElement?.asFloat(default: Float): Float =
    (this as? JsonPrimitive)?.floatOrNull ?: default

private suspend inline fun <T> runCatchingCancellable(
    crossinline block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

private suspend fun <T, R> List<T>.mapConcurrent(
    maxConcurrency: Int,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    if (isEmpty()) return@coroutineScope emptyList()
    val nextIndex = AtomicInteger(0)
    val results = arrayOfNulls<Any?>(size)
    val workers = List(minOf(size, maxConcurrency.coerceAtLeast(1))) {
        launch {
            while (true) {
                val index = nextIndex.getAndIncrement()
                if (index >= size) break
                results[index] = transform(this@mapConcurrent[index])
            }
        }
    }
    workers.joinAll()
    @Suppress("UNCHECKED_CAST")
    results.map { result -> result as R }
}

private fun <T> Result<T>.getOrNullUnlessInvalidToken(): T? =
    fold(
        onSuccess = { it },
        onFailure = { error ->
            if (error is TwitchApiException && error.type == TwitchApiErrorType.InvalidToken) {
                throw error
            }
            null
        },
    )

private fun requireServiceUrl(value: String, productionHosts: Set<String>): okhttp3.HttpUrl {
    val url = value.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Twitch service endpoint must be an absolute HTTP URL.")
    val allowed = (url.isHttps && url.host in productionHosts) || url.host.isLoopbackHost()
    require(allowed) { "Twitch service endpoint host is not trusted." }
    return url
}

private fun String.isLoopbackHost(): Boolean =
    this == "localhost" || this == "127.0.0.1" || this == "::1"

private fun okhttp3.HttpUrl.sameOrigin(other: okhttp3.HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

private fun ResponseBody?.readBoundedString(maximumBytes: Int, label: String): String {
    val body = this ?: throw TwitchApiException(
        TwitchApiErrorType.UnexpectedResponse,
        "Twitch $label returned no body.",
    )
    val declaredLength = body.contentLength()
    if (declaredLength > maximumBytes) {
        throw TwitchApiException(
            TwitchApiErrorType.UnexpectedResponse,
            "Twitch $label response exceeded the safe size limit.",
        )
    }
    val bytes = body.byteStream().readNBytes(maximumBytes + 1)
    if (bytes.size > maximumBytes) {
        throw TwitchApiException(
            TwitchApiErrorType.UnexpectedResponse,
            "Twitch $label response exceeded the safe size limit.",
        )
    }
    return bytes.toString(Charsets.UTF_8)
}

private fun ResponseBody?.readOAuthObject(label: String): JsonObject {
    val body = readBoundedString(MaxOAuthResponseBytes, label)
    return runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: throw TwitchApiException(
            TwitchApiErrorType.UnexpectedResponse,
            "Twitch $label returned invalid JSON.",
        )
}

private fun List<JsonElement>.boundedSummary(): String =
    joinToString(prefix = "[", postfix = "]", limit = 4, truncated = "…") { error ->
        val message = error.asObjectOrNull()?.get("message").asStringOrNull()
        (message ?: "unspecified error").replace('\r', ' ').replace('\n', ' ').take(160)
    }.take(MaxDiagnosticSummaryCharacters)

private fun JsonObject.graphQlDiagnostics(label: String): List<String> =
    this["errors"].asArray().take(4).mapIndexed { index, error ->
        val message = error.asObjectOrNull()?.get("message").asStringOrNull()
        "$label partial error ${index + 1}: " +
            (message ?: "unspecified upstream error")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .take(160)
    }
