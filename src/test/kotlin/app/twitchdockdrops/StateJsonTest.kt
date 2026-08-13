package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimeActivity
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateJsonTest {
    @Test
    fun `state serialization redacts credentials and omits device secrets`() {
        val secrets = listOf(
            "access-token-secret",
            "device-code-secret",
            "encryption-key-secret",
            "raw-session-secret",
            "filesystem-secret",
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
            error = "encryption_key=${secrets[2]}",
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
}
