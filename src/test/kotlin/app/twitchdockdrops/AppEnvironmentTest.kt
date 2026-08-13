package app.twitchdockdrops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppEnvironmentTest {
    @Test
    fun `environment uses explicit data directory and port`() {
        val environment = AppEnvironment.fromEnvironment(
            mapOf(
                "TWITCH_DROPS_DATA_DIR" to "./build/test-data",
                "TWITCH_DROPS_PORT" to "9090",
                "TWITCH_DROPS_SESSION_KEY" to "key-value",
            ),
        )

        assertEquals(9090, environment.port)
        assertEquals("127.0.0.1", environment.listenHost)
        assertFalse(environment.allowLanAccess)
        assertEquals("key-value", environment.sessionKey)
        assertEquals("test-data", environment.dataDirectory.fileName.toString())
    }

    @Test
    fun `invalid port is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AppEnvironment.fromEnvironment(mapOf("TWITCH_DROPS_PORT" to "70000"))
        }
    }

    @Test
    fun `nonnumeric nonblank port is rejected instead of using the default`() {
        assertFailsWith<IllegalArgumentException> {
            AppEnvironment.fromEnvironment(mapOf("TWITCH_DROPS_PORT" to "not-a-port"))
        }
    }

    @Test
    fun `lan access is explicitly enabled`() {
        val environment = AppEnvironment.fromEnvironment(
            mapOf("TWITCH_DROPS_ALLOW_LAN" to "true"),
        )

        assertTrue(environment.allowLanAccess)
    }

    @Test
    fun `invalid lan access flag is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AppEnvironment.fromEnvironment(mapOf("TWITCH_DROPS_ALLOW_LAN" to "yes"))
        }
    }
}
