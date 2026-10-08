package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class PlaybackAccess(val value: String, val signature: String)

/** Playlist metadata GETs and media HEADs only. OAuth/browser headers never reach the CDN. */
internal class HlsWatchTransport(
    private val playbackAccess: suspend (StoredTwitchSession, Channel) -> PlaybackAccess?,
    private val testOrigin: HttpUrl? = null,
) {
    // Deliberately independent: no caller interceptors, cookies, authenticators or response cache.
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    @Volatile private var state: WatchState? = null

    private class WatchState(val session: StoredTwitchSession, val channelId: Long, val broadcastId: String) {
        var playlist: HttpUrl? = null
        val segments = LinkedHashSet<String>()
    }

    fun reset() { state = null }

    fun invalidate(channelId: Long) {
        state?.takeIf { it.channelId == channelId }?.playlist = null
    }

    suspend fun poll(session: StoredTwitchSession, channel: Channel, isCurrent: () -> Boolean): Boolean =
        mutex.withLock {
            if (!channel.online || channel.broadcastId.isNullOrBlank() || !isCurrent()) return@withLock false
            val selected = state?.takeIf {
                it.session == session && it.channelId == channel.id && it.broadcastId == channel.broadcastId
            } ?: WatchState(session, channel.id, channel.broadcastId).also { state = it }
            fun current() = state === selected && isCurrent()
            try {
                withTimeoutOrNull(10_000) {
                    if (selected.playlist == null) {
                        val token = playbackAccess(session, channel) ?: return@withTimeoutOrNull false
                        if (!current()) return@withTimeoutOrNull false
                        val master = (testOrigin ?: "https://usher.ttvnw.net".toHttpUrl()).newBuilder()
                            .addPathSegments("api/channel/hls").addPathSegment("${channel.login}.m3u8")
                            .addQueryParameter("sig", token.signature).addQueryParameter("token", token.value).build()
                        val response = request(master, head = false)
                        if (response.status != 200 || !current()) return@withTimeoutOrNull false
                        selected.playlist = parseHlsPlaylist(response.body, master, master = true, ::allowed)
                            .minByOrNull { it.bandwidth ?: Long.MAX_VALUE }?.url
                            ?: return@withTimeoutOrNull false
                    }
                    val playlist = selected.playlist ?: return@withTimeoutOrNull false
                    if (!current()) return@withTimeoutOrNull false
                    val response = request(playlist, head = false)
                    if (!current()) return@withTimeoutOrNull false
                    if (response.status != 200) {
                        if (response.status in ExpiredStatuses) selected.playlist = null
                        return@withTimeoutOrNull false
                    }
                    val segments = parseHlsPlaylist(response.body, playlist, master = false, ::allowed)
                    if (segments.isEmpty()) return@withTimeoutOrNull false
                    var success = true
                    for (segment in segments) {
                        currentCoroutineContext().ensureActive()
                        if (!current()) return@withTimeoutOrNull false
                        val key = segment.url.toString()
                        if (key in selected.segments) continue
                        val status = try { request(segment.url, head = true).status } catch (_: IOException) { 0 }
                        if (!current()) return@withTimeoutOrNull false
                        if (status == 200) {
                            selected.segments.add(key)
                            if (selected.segments.size > 256) selected.segments.remove(selected.segments.first())
                        } else {
                            success = false
                            if (status in ExpiredStatuses) selected.playlist = null
                        }
                    }
                    success
                } ?: false
            } catch (_: IOException) {
                false
            }
        }

    private data class PlaylistResponse(val status: Int, val body: String = "")

    private suspend fun request(url: HttpUrl, head: Boolean): PlaylistResponse {
        if (!allowed(url)) return PlaylistResponse(0)
        val request = Request.Builder().url(url).apply {
            if (head) head() else { get(); header("Connection", "close") }
        }.build()
        val call = http.newCall(request)
        if (head) call.timeout().timeout(3, TimeUnit.SECONDS)
        return call.readCancellable { response ->
            if (head || response.code != 200) PlaylistResponse(response.code)
            else {
                val body = response.body ?: throw IOException("Missing playlist")
                if (body.contentLength() > MaxPlaylistBytes) throw IOException("Playlist too large")
                val bytes = body.byteStream().readNBytes(MaxPlaylistBytes + 1)
                if (bytes.size > MaxPlaylistBytes) throw IOException("Playlist too large")
                PlaylistResponse(response.code, bytes.toString(Charsets.UTF_8))
            }
        }
    }

    private fun allowed(url: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
        if (testOrigin != null && testOrigin.host in setOf("127.0.0.1", "localhost", "::1") &&
            url.scheme == testOrigin.scheme && url.host == testOrigin.host && url.port == testOrigin.port) return true
        return isTrustedTwitchMediaUrl(url)
    }
}

internal fun isTrustedTwitchMediaUrl(url: HttpUrl): Boolean =
    url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
        (url.host == "ttvnw.net" || url.host.endsWith(".ttvnw.net"))

internal data class HlsEntry(val url: HttpUrl, val bandwidth: Long? = null)
private const val MaxPlaylistBytes = 256 * 1024
private val ExpiredStatuses = setOf(401, 403, 404)

/** Parse only URI lines paired with the expected tag; validate the whole list before requesting it. */
internal fun parseHlsPlaylist(
    text: String,
    base: HttpUrl,
    master: Boolean,
    allowed: (HttpUrl) -> Boolean = ::isTrustedTwitchMediaUrl,
): List<HlsEntry> {
    if (text.length > MaxPlaylistBytes) return emptyList()
    val lines = text.trim().lineSequence().iterator()
    if (!lines.hasNext() || lines.next().trim() != "#EXTM3U") return emptyList()
    val tag = if (master) "#EXT-X-STREAM-INF:" else "#EXTINF:"
    val entries = mutableListOf<HlsEntry>()
    var pending = false
    var bandwidth: Long? = null
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            if (line.startsWith(tag)) {
                if (pending) return emptyList()
                pending = true
                bandwidth = if (master) Regex("(?:^|,)BANDWIDTH=([0-9]+)(?:,|$)")
                    .find(line.substringAfter(':'))?.groupValues?.get(1)?.toLongOrNull() else null
            }
            continue
        }
        if (!pending || line.length > 16_384 || entries.size >= 128) return emptyList()
        val url = base.resolve(line) ?: return emptyList()
        if (!allowed(url) || master && !url.encodedPath.endsWith(".m3u8")) return emptyList()
        entries.add(HlsEntry(url, bandwidth))
        pending = false
    }
    return if (pending) emptyList() else entries
}
