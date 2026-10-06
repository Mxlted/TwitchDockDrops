package com.nathan.twitchdropsminer.android.data.twitch

import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlin.test.*
import okhttp3.OkHttpClient

class TwitchEventsTest {
    @Test fun `websocket subscribes validates acknowledgements deduplicates and cancels`() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val hints = java.util.concurrent.atomic.AtomicInteger()
            val subscribed = kotlinx.coroutines.CompletableDeferred<String>()
            server.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val root = Json.parseToJsonElement(text).jsonObject
                    if (root["type"]?.jsonPrimitive?.content != "LISTEN") return
                    subscribed.complete(text)
                    webSocket.send(buildJsonObject { put("type", "RESPONSE"); put("nonce", root.getValue("nonce")); put("error", "") }.toString())
                    val event = buildJsonObject {
                        put("type", "MESSAGE")
                        put("data", message("user-drop-events.42", """{"type":"drop-progress","data":{"drop_id":"a","current_progress_min":12}}""").getValue("data"))
                    }.toString()
                    repeat(5) { webSocket.send(event) }
                }
            }))
            server.start()
            val client = OkHttpClient()
            val source = TwitchEvents(client, server.url("/v1").toString().replace("http:", "ws:"))
            val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                source.listen(com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession("synthetic", "42", "device", java.time.Instant.EPOCH), 7) { hints.incrementAndGet() }
            }
            try {
                val request = kotlinx.coroutines.withTimeout(2000) { subscribed.await() }
                assertContains(request, "user-drop-events.42")
                assertContains(request, "video-playback-by-id.7")
                kotlinx.coroutines.withTimeout(2000) { while (hints.get() == 0) kotlinx.coroutines.delay(10) }
                assertEquals(1, hints.get())
            } finally {
                job.cancel(); job.join()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
    private fun message(topic: String, payload: String) = buildJsonObject {
        put("data", buildJsonObject { put("topic", topic); put("message", payload) })
    }
    @Test fun `events require subscribed topic and valid payload`() {
        val topics = setOf("user-drop-events.42", "video-playback-by-id.7", "broadcast-settings-update.7")
        val progress = """{"type":"drop-progress","data":{"drop_id":"a","current_progress_min":12}}"""
        assertTrue(TwitchEvents.isRelevant(message("user-drop-events.42", progress), topics))
        assertFalse(TwitchEvents.isRelevant(message("user-drop-events.43", progress), topics))
        assertFalse(TwitchEvents.isRelevant(message("user-drop-events.42", progress.replace(":12", ":-1")), topics))
        assertFalse(TwitchEvents.isRelevant(message("user-drop-events.42", "malformed"), topics))
        assertTrue(TwitchEvents.isRelevant(message("video-playback-by-id.7", """{"type":"stream-down"}"""), topics))
        assertTrue(TwitchEvents.isRelevant(message("broadcast-settings-update.7", """{"type":"broadcast_settings_update"}"""), topics))
        assertFalse(TwitchEvents.isRelevant(message("video-playback-by-id.7", progress), topics))
    }
    @Test fun `credentials cannot be sent to an arbitrary websocket`() {
        for (url in listOf("wss://evil.example/v1", "ws://pubsub-edge.twitch.tv/v1", "wss://pubsub-edge.twitch.tv/v1?token=x")) {
            assertFailsWith<IllegalArgumentException> { TwitchEvents(OkHttpClient(), url) }
        }
    }
}
