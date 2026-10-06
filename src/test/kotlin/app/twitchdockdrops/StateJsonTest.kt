package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimeActivity
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StateJsonTest {
    @Test fun `reward settings and prerequisite reasons are serialized without claim identifiers`() {
        val drop = CampaignDrop("drop", "Reward", 0, 30, 0f, false, false, emptyList(),
            claimId = "private-claim-instance", preconditionDropIds = listOf("missing"))
        val encoded = StateJson().encode(RuntimeSnapshot(campaigns = listOf(Campaign("campaign", "Campaign", "Game", drops = listOf(drop)))),
            AppSettings(allowedRewardTypes = setOf("BADGE"), excludedRewardNames = listOf("Unwanted")), emptyList())
        val root = Json.parseToJsonElement(encoded).jsonObject
        val settings = root.getValue("settings").jsonObject
        assertEquals("BADGE", settings.getValue("allowedRewardTypes").jsonArray.single().jsonPrimitive.content)
        assertEquals("Unwanted", settings.getValue("excludedRewardNames").jsonArray.single().jsonPrimitive.content)
        val reward = root.getValue("snapshot").jsonObject.getValue("campaigns").jsonArray.single().jsonObject.getValue("drops").jsonArray.single().jsonObject
        assertTrue(reward.getValue("blockedReason").jsonPrimitive.content.contains("Missing prerequisite"))
        assertEquals("false", reward.getValue("eligibleByFilter").toString())
        assertEquals("missing", reward.getValue("preconditionDropIds").jsonArray.single().jsonPrimitive.content)
        assertFalse(encoded.contains("private-claim-instance"))
        assertFalse(encoded.contains("refreshToken"))
    }
    @Test fun `account username is public only while authenticated`() {
        for (state in listOf(LoginState.LoggedIn, LoginState.LoggedOut, LoginState.LoginRequired, LoginState.Expired)) {
            val snapshot = RuntimeSnapshot(account = LoginSession(state, "Status", userId = "123", username = "cozy_collector"))
            val account = Json.parseToJsonElement(StateJson().encode(snapshot, AppSettings(), emptyList()))
                .jsonObject.getValue("snapshot").jsonObject.getValue("account").jsonObject
            assertEquals(if (state == LoginState.LoggedIn) "\"cozy_collector\"" else "null", account.getValue("username").toString())
        }
    }

    @Test fun `dashboard authorization exposes only its method and no pairing credential`() {
        val snapshot = RuntimeSnapshot(account = LoginSession(LoginState.LoginRequired, "Waiting for dashboard login", method = "dashboard"))
        val account = Json.parseToJsonElement(StateJson().encode(snapshot, AppSettings(), emptyList()))
            .jsonObject.getValue("snapshot").jsonObject.getValue("account").jsonObject
        assertEquals("dashboard", account.getValue("method").jsonPrimitive.content)
        assertEquals("null", account.getValue("oauthCode").toString())
        assertFalse(account.containsKey("ticket"))
    }

    @Test
    fun `display rewards serialize independently of selection and drop counters`() {
        val reward = com.nathan.twitchdropsminer.android.data.model.RewardCampaign(
            "reward", "Celebration", "Twitch", null, "Watch participating channels",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"), listOf("Badge"),
        )
        val encoded = StateJson().encode(RuntimeSnapshot(rewardCampaigns = listOf(reward),
            rewardCampaignsAvailable = true), AppSettings(), emptyList())
        val snapshot = Json.parseToJsonElement(encoded).jsonObject.getValue("snapshot").jsonObject
        assertEquals("true", snapshot.getValue("rewardCampaignsAvailable").toString())
        assertEquals("Celebration", snapshot.getValue("rewardCampaigns").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(snapshot.getValue("campaigns").jsonArray.isEmpty())
        assertTrue(snapshot.getValue("selectionPreview").jsonArray.isEmpty())
        assertEquals("0", snapshot.getValue("activeCampaignCount").toString())
    }

    @Test
    fun `selection preview respects priorities exclusions fallback and active watch`() {
        val campaigns = (1..8).map { index -> Campaign(
            id = "campaign-$index", name = "Campaign $index", gameName = "Game $index", linked = true, active = true,
            drops = listOf(CampaignDrop(id = "drop-$index", name = "Drop", requiredMinutes = 60,
                currentMinutes = 0, progress = 0f, isClaimed = false, canClaim = false, rewards = emptyList())),
        ) }
        fun preview(settings: AppSettings): List<String> {
            val encoded = StateJson().encode(RuntimeSnapshot(campaigns = campaigns, activeCampaign = campaigns[0]), settings.normalized(), emptyList())
            return Json.parseToJsonElement(encoded).jsonObject.getValue("snapshot").jsonObject
                .getValue("selectionPreview").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(listOf("campaign-3"), preview(AppSettings(
            selectedGamePriority = listOf("Game 1", "Absent", "Game 2", "Game 3"),
            excludedCampaignIds = setOf("campaign-2"), fallbackToOtherGames = false,
        )))
        assertEquals(5, preview(AppSettings()).size)
        assertFalse(preview(AppSettings()).contains("campaign-1"))
    }

    @Test
    fun `state serialization redacts credentials and omits device secrets`() {
        val secrets = listOf(
            "access-token-secret",
            "device-code-secret",
            "encryption-key-secret",
            "raw-session-secret",
            "filesystem-secret",
            "refresh-token-secret",
        )
        val snapshot = RuntimeSnapshot(
            phase = RuntimePhase.Error,
            account = LoginSession(
                state = LoginState.LoginRequired,
                statusText = "Authorization: OAuth ${secrets[0]}",
                oauthUrl = "https://evil.example/activate",
                oauthCode = "PUBLIC-CODE",
                deviceCode = secrets[1],
            ),
            currentTask = "accessToken=${secrets[0]}",
            progressSummary = "device_code=${secrets[1]}",
            error = "encryption_key=${secrets[2]} refresh_token=${secrets[5]}",
            activity = listOf(
                RuntimeActivity(
                    Instant.EPOCH,
                    RuntimePhase.Error,
                    "Session load failed",
                    "session_key=${secrets[3]}",
                ),
            ),
        )
        val encoded = StateJson(Instant.EPOCH).encode(
            snapshot,
            AppSettings(),
            listOf(LocalLogEntry(Instant.EPOCH, "ERROR", "Authorization: Bearer ${secrets[4]}")),
        )

        secrets.forEach { secret -> assertFalse(encoded.contains(secret), secret) }
        assertFalse(encoded.contains("deviceCode"))
        assertFalse(encoded.contains("evil.example"))
        assertTrue(encoded.contains("[redacted]"))
        assertTrue(encoded.contains("PUBLIC-CODE"))
    }

    @Test
    fun `campaign selection is derived from current settings instead of stale runtime flags`() {
        val campaign = Campaign(
            id = "campaign",
            name = "Campaign",
            gameName = "Game",
            selected = true,
        )

        val unselected = StateJson(Instant.EPOCH).encode(
            RuntimeSnapshot(campaigns = listOf(campaign)),
            AppSettings().normalized(),
            emptyList(),
        )
        val selected = StateJson(Instant.EPOCH).encode(
            RuntimeSnapshot(campaigns = listOf(campaign.copy(selected = false))),
            AppSettings(selectedGamePriority = listOf("Game")).normalized(),
            emptyList(),
        )

        assertTrue(unselected.contains("\"selected\":false"))
        assertTrue(selected.contains("\"selected\":true"))
    }

    @Test
    fun `channels serialize canonical login while campaign ACL membership stays private`() {
        val channel = Channel(id = 7, name = "Display Name", login = "canonical_login")
        val campaign = Campaign(
            id = "campaign",
            name = "Campaign",
            gameName = "Game",
            allowedChannels = listOf(channel),
        )
        val encoded = StateJson(Instant.EPOCH).encode(
            RuntimeSnapshot(
                campaigns = listOf(campaign),
                channels = listOf(channel),
                currentChannel = channel,
                activeCampaign = campaign,
            ),
            AppSettings(),
            emptyList(),
        )

        val snapshot = Json.parseToJsonElement(encoded).jsonObject.getValue("snapshot").jsonObject
        assertEquals(
            "canonical_login",
            snapshot.getValue("currentChannel").jsonObject.getValue("login").jsonPrimitive.content,
        )
        assertEquals(
            "canonical_login",
            snapshot.getValue("channels").jsonArray.single().jsonObject.getValue("login").jsonPrimitive.content,
        )
        assertFalse(snapshot.getValue("campaigns").jsonArray.single().jsonObject.containsKey("allowedChannels"))
        assertFalse(snapshot.getValue("activeCampaign").jsonObject.containsKey("allowedChannels"))
    }
}
