package com.nathan.twitchdropsminer.android.runtime

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Single-owner helper lease; secrets never enter RuntimeSnapshot. */
class BrowserLoginAdmission(private val clock: () -> Instant = Instant::now) {
    private var generation = 0L
    private var code: String? = null
    private var ticket: String? = null
    private var expiresAt = Instant.EPOCH
    private var attempts = 0
    private var state = "closed"
    private var account: String? = null
    data class Lease(val generation: Long, val expectedAccount: String?)
    @Synchronized fun open(): Pair<String, Instant> {
        clear()
        code = random(9)
        expiresAt = clock().plusSeconds(600)
        state = "waiting"
        return code!! to expiresAt
    }
    @Synchronized fun clear() {
        generation++
        code = null; ticket = null; attempts = 0; account = null; state = "closed"
    }
    @Synchronized fun claim(candidate: String): String {
        require(state == "waiting" && clock() < expiresAt && attempts < 5) { "Helper pairing is closed. Request a new code." }
        attempts++
        require(equal(code, candidate)) { "Invalid helper pairing code." }
        code = null
        ticket = random(32)
        state = "connected"
        return ticket!!
    }
    @Synchronized fun begin(candidate: String): Lease {
        authorize(candidate)
        require(state != "verifying") { "Browser verification is already in progress." }
        state = "verifying"
        return Lease(generation, account)
    }
    @Synchronized fun accepted(lease: Long, userId: String) {
        if (lease != generation) return
        account = userId; state = "ready"; expiresAt = clock().plusSeconds(86400)
    }
    @Synchronized fun failed(lease: Long) { if (lease == generation) state = "failed" }
    @Synchronized fun status(candidate: String): String { authorize(candidate); return state }
    @Synchronized fun pending(): Boolean = state in setOf("waiting", "connected", "verifying")
    private fun authorize(candidate: String) {
        require(clock() < expiresAt && equal(ticket, candidate)) { "Helper connection expired or was revoked. Reconnect Twitch." }
    }
    private fun random(bytes: Int) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))
    private fun equal(expected: String?, actual: String) = expected != null &&
        MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}
