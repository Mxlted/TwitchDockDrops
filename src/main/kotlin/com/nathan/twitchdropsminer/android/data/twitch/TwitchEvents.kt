package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*

/** Private protocol notifications are invalidation hints, never claim/progress authority. */
class TwitchEvents(client: OkHttpClient, private val endpoint: String = "wss://pubsub-edge.twitch.tv/v1") {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    init {
        val uri = java.net.URI(endpoint)
        require(endpoint == "wss://pubsub-edge.twitch.tv/v1" ||
            (uri.scheme == "ws" && uri.host in setOf("localhost", "127.0.0.1", "::1")))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    suspend fun listen(session: StoredTwitchSession, channelId: Long?, hint: suspend () -> Unit) {
        val topics = setOfNotNull("user-drop-events.${session.userId}",
            channelId?.let { "video-playback-by-id.$it" }, channelId?.let { "broadcast-settings-update.$it" })
        var backoff = 1000L
        while (currentCoroutineContext().isActive) {
            val events = Channel<Unit>(Channel.CONFLATED)
            val ended = CompletableDeferred<Unit>()
            val pongAt = AtomicLong(System.nanoTime())
            val accepted = AtomicBoolean(false)
            val nonce = UUID.randomUUID().toString()
            val recent = LinkedHashSet<String>()
            val socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildJsonObject {
                        put("type", "LISTEN"); put("nonce", nonce)
                        put("data", buildJsonObject {
                            put("topics", JsonArray(topics.map(::JsonPrimitive))); put("auth_token", session.accessToken)
                        })
                    }.toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > 65536) { webSocket.cancel(); ended.complete(Unit); return }
                    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    when ((root["type"] as? JsonPrimitive)?.content) {
                        "PONG" -> pongAt.set(System.nanoTime())
                        "RECONNECT" -> ended.complete(Unit)
                        "RESPONSE" -> if ((root["nonce"] as? JsonPrimitive)?.content == nonce) {
                            if ((root["error"] as? JsonPrimitive)?.content == "") accepted.set(true) else ended.complete(Unit)
                        }
                        "MESSAGE" -> if (accepted.get() && isRelevant(root, topics) && recent.add(text)) {
                            if (recent.size > 128) recent.remove(recent.first())
                            events.trySend(Unit)
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.complete(Unit) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(Unit) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { ended.complete(Unit) }
            })
            val connectedAt = System.nanoTime()
            try {
                coroutineScope {
                    val delivering = launch { for (ignored in events) { hint(); delay(10_000) } }
                    val heartbeat = launch {
                        delay(15_000)
                        if (!accepted.get()) ended.complete(Unit)
                        while (isActive && !ended.isCompleted) {
                            val sentAt = System.nanoTime()
                            if (!socket.send("{\"type\":\"PING\"}")) { ended.complete(Unit); break }
                            delay(15_000)
                            if (pongAt.get() < sentAt) { ended.complete(Unit); break }
                            delay(150_000)
                        }
                    }
                    try { ended.await() } finally { delivering.cancel(); heartbeat.cancel() }
                }
            } finally { socket.cancel(); events.close() }
            if (System.nanoTime() - connectedAt > 60_000_000_000L && accepted.get()) backoff = 1000
            delay(backoff + kotlin.random.Random.nextLong(0, 500))
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }

    companion object {
        internal fun isRelevant(root: JsonObject, topics: Set<String>): Boolean = runCatching {
            val data = root.getValue("data").jsonObject
            val topic = data.getValue("topic").jsonPrimitive.content
            if (topic !in topics) return false
            val payload = Json.parseToJsonElement(data.getValue("message").jsonPrimitive.content).jsonObject
            val type = payload.getValue("type").jsonPrimitive.content
            when {
                topic.startsWith("user-drop-events.") -> when (type) {
                    "drop-progress" -> payload["data"]?.jsonObject?.let {
                        !it["drop_id"]?.jsonPrimitive?.content.isNullOrBlank() &&
                            it["current_progress_min"]?.jsonPrimitive?.intOrNull?.let { minutes -> minutes in 0..1000000 } == true
                    } == true
                    "drop-claim" -> !payload["data"]?.jsonObject?.get("drop_id")?.jsonPrimitive?.content.isNullOrBlank()
                    else -> false
                }
                topic.startsWith("video-playback-by-id.") -> type in setOf("stream-up", "stream-down")
                topic.startsWith("broadcast-settings-update.") -> type == "broadcast_settings_update"
                else -> false
            }
        }.getOrDefault(false)
    }
}
