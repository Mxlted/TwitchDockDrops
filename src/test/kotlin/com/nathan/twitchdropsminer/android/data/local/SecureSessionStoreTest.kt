package com.nathan.twitchdropsminer.android.data.local

import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SecureSessionStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `session round trips without writing the token in plaintext`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { index -> index.toByte() })
        val store = SecureSessionStore(directory, key)
        val expected = StoredTwitchSession(
            accessToken = "super-secret-token",
            userId = "12345",
            deviceId = "device-abc",
            savedAt = Instant.parse("2026-08-10T12:00:00Z"),
        )

        store.saveTwitchSession(expected)

        assertEquals(expected, SecureSessionStore(directory, key).twitchSession())
        assertFalse(Files.readString(directory.resolve("session.enc")).contains(expected.accessToken))
    }

    @Test
    fun `a different key cannot decrypt the session`() {
        val firstKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val secondKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        SecureSessionStore(directory, firstKey).saveTwitchSession(
            StoredTwitchSession("token", "user", "device", Instant.EPOCH),
        )

        assertNull(SecureSessionStore(directory, secondKey).twitchSession())
        val mismatched = SecureSessionStore(directory, secondKey)
        assertNull(mismatched.twitchSession())
        assertEquals(PersistenceFileState.KeyMismatched, mismatched.loadStatus.state)
        assertTrue(Files.exists(directory.resolve("session.enc")))
    }

    @Test
    fun `corrupt encrypted session is quarantined without exposing raw content`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val store = SecureSessionStore(directory, key)
        Files.writeString(directory.resolve("session.enc"), "raw-session-secret")

        assertNull(store.twitchSession())
        assertEquals(PersistenceFileState.Corrupt, store.loadStatus.state)
        assertTrue(store.loadStatus.diagnostic?.contains("raw-session-secret") == false)
        assertTrue(
            Files.list(directory).use { files ->
                files.anyMatch { it.fileName.toString().startsWith("session.enc.corrupt-") }
            },
        )
    }
}
