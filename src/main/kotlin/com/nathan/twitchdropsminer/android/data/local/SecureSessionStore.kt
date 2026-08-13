package com.nathan.twitchdropsminer.android.data.local

import app.twitchdockdrops.storage.AtomicFiles
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SecureSessionStore(
    dataDirectory: Path,
    configuredKey: String? = null,
) {
    private val sessionFile = dataDirectory.resolve("session.enc")
    private val keyFile = dataDirectory.resolve("session.key")
    private val secureRandom = SecureRandom()
    private val configuredKeyProvided = configuredKey != null
    private val key: ByteArray = configuredKey?.let(::decodeConfiguredKey) ?: loadOrCreateKey()

    @Volatile
    var loadStatus: PersistenceStatus = PersistenceStatus(PersistenceFileState.Absent)
        private set

    @Synchronized
    fun saveTwitchSession(session: StoredTwitchSession) {
        val plainText = buildJsonObject {
            put("accessToken", session.accessToken)
            put("userId", session.userId)
            put("deviceId", session.deviceId)
            put("savedAt", session.savedAt.toString())
        }.toString().toByteArray(Charsets.UTF_8)
        val initializationVector = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, initializationVector),
        )
        val encrypted = cipher.doFinal(plainText)
        val envelope = byteArrayOf(1) + initializationVector + encrypted
        AtomicFiles.writeString(
            sessionFile,
            Base64.getEncoder().encodeToString(envelope),
            ownerOnly = true,
        )
        loadStatus = PersistenceStatus(PersistenceFileState.Loaded)
    }

    @Synchronized
    fun twitchSession(): StoredTwitchSession? {
        if (!Files.exists(sessionFile)) return null
        return try {
            require(Files.size(sessionFile) <= MaxSessionEnvelopeBytes) {
                "Encrypted session exceeds the safe size limit."
            }
            val envelope = Base64.getDecoder().decode(Files.readString(sessionFile).trim())
            require(envelope.size > 29 && envelope[0] == 1.toByte()) { "Unsupported session envelope." }
            val initializationVector = envelope.copyOfRange(1, 13)
            val encrypted = envelope.copyOfRange(13, envelope.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, initializationVector),
            )
            val root = Json.parseToJsonElement(
                cipher.doFinal(encrypted).toString(Charsets.UTF_8),
            ) as JsonObject
            StoredTwitchSession(
                accessToken = root.requiredString("accessToken"),
                userId = root.requiredString("userId"),
                deviceId = root.requiredString("deviceId"),
                savedAt = Instant.parse(root.requiredString("savedAt")),
            ).also {
                loadStatus = PersistenceStatus(PersistenceFileState.Loaded)
            }
        } catch (error: AEADBadTagException) {
            if (configuredKeyProvided) {
                loadStatus = PersistenceStatus(
                    PersistenceFileState.KeyMismatched,
                    "The configured session key does not match the encrypted Twitch session; restore the correct key or reset the session.",
                )
            } else {
                val quarantined = quarantineCorruptSession()
                loadStatus = PersistenceStatus(
                    PersistenceFileState.Corrupt,
                    if (quarantined) {
                        "The encrypted Twitch session failed authentication and was quarantined; reconnect Twitch."
                    } else {
                        "The encrypted Twitch session failed authentication and was preserved; correct data-directory permissions or reset the session."
                    },
                )
            }
            null
        } catch (error: java.nio.file.AccessDeniedException) {
            loadStatus = PersistenceStatus(
                PersistenceFileState.Unreadable,
                "The encrypted Twitch session is unreadable; correct data-directory permissions.",
            )
            null
        } catch (error: SecurityException) {
            loadStatus = PersistenceStatus(
                PersistenceFileState.Unreadable,
                "The encrypted Twitch session is unreadable; correct data-directory permissions.",
            )
            null
        } catch (_: Throwable) {
            val quarantined = quarantineCorruptSession()
            loadStatus = PersistenceStatus(
                PersistenceFileState.Corrupt,
                if (quarantined) {
                    "The encrypted Twitch session is corrupt and was quarantined; reconnect Twitch."
                } else {
                    "The encrypted Twitch session is corrupt and was preserved; correct data-directory permissions or reset the session."
                },
            )
            null
        }
    }

    @Synchronized
    fun clear() {
        Files.deleteIfExists(sessionFile)
        loadStatus = PersistenceStatus(PersistenceFileState.Absent)
    }

    private fun loadOrCreateKey(): ByteArray {
        if (Files.exists(keyFile)) {
            return try {
                require(Files.size(keyFile) <= MaxKeyFileBytes) { "Stored session key is corrupt." }
                decodeConfiguredKey(Files.readString(keyFile).trim()).also {
                    AtomicFiles.restrictToOwner(keyFile)
                }
            } catch (error: java.nio.file.AccessDeniedException) {
                throw IllegalStateException(
                    "Stored session key is unreadable; correct data-directory permissions.",
                    error,
                )
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "Stored session key is corrupt; restore the key or remove the encrypted session and key before reconnecting Twitch.",
                    error,
                )
            }
        }
        val generated = ByteArray(32).also(secureRandom::nextBytes)
        AtomicFiles.writeString(
            keyFile,
            Base64.getEncoder().encodeToString(generated),
            ownerOnly = true,
        )
        return generated
    }

    private fun quarantineCorruptSession(): Boolean = runCatching {
            val quarantine = sessionFile.resolveSibling("session.enc.corrupt-${Instant.now().toEpochMilli()}")
            Files.move(sessionFile, quarantine, StandardCopyOption.ATOMIC_MOVE)
            AtomicFiles.restrictToOwner(quarantine)
            true
        }.getOrElse { false }

    private fun decodeConfiguredKey(value: String): ByteArray {
        val decoded = runCatching { Base64.getDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("TWITCH_DROPS_SESSION_KEY must be valid base64.") }
        require(decoded.size == 32) {
            "TWITCH_DROPS_SESSION_KEY must decode to exactly 32 bytes."
        }
        return decoded
    }
}

private const val MaxSessionEnvelopeBytes = 64L * 1_024L
private const val MaxKeyFileBytes = 1_024L

private fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Encrypted session is missing $key.")
