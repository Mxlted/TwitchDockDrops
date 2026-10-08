package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*

class HlsWatchTransportTest {
    @Test fun `rolling playlists HEAD each new segment once and retain same broadcast metadata`() = runBlocking {
        fixture { server, transport, requests ->
            server.enqueue(master())
            server.enqueue(media("one.ts", "two.ts"))
            repeat(2) { server.enqueue(MockResponse()) }
            assertTrue(transport.poll(session, channel) { true })
            server.enqueue(media("two.ts", "three.ts"))
            server.enqueue(MockResponse())
            assertTrue(transport.poll(session, channel.copy(viewers = 999)) { true })
            assertEquals(listOf("/low/one.ts", "/low/two.ts", "/low/three.ts"), requests.filter { it.method == "HEAD" }.map { it.path })
            assertEquals(1, requests.count { it.path!!.startsWith("/api/channel/hls/") })
            assertTrue(requests.filter { it.path!!.endsWith(".ts") }.all { it.method == "HEAD" })
            requests.forEach {
                for (header in listOf("Authorization", "Client-Integrity", "Client-Id", "X-Device-Id", "Cookie"))
                    assertNull(it.getHeader(header), header)
            }
        }
    }

    @Test fun `failed segments retry while successful siblings stay deduplicated`() = runBlocking {
        fixture { server, transport, requests ->
            server.enqueue(master()); server.enqueue(media("one.ts", "two.ts"))
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse())
            assertFalse(transport.poll(session, channel) { true })
            server.enqueue(media("one.ts", "two.ts")); server.enqueue(MockResponse())
            assertTrue(transport.poll(session, channel) { true })
            assertEquals(listOf("/low/one.ts", "/low/two.ts", "/low/one.ts"), requests.filter { it.method == "HEAD" }.map { it.path })
        }
    }

    @Test fun `expired playlist and segment URLs refresh without resetting dedup or expiring OAuth`() = runBlocking {
        for (status in listOf(401, 403, 404)) {
            fixture { server, transport, requests ->
                server.enqueue(master()); server.enqueue(media("one.ts")); server.enqueue(MockResponse())
                assertTrue(transport.poll(session, channel) { true })
                server.enqueue(MockResponse().setResponseCode(status))
                assertFalse(transport.poll(session, channel) { true })
                server.enqueue(master()); server.enqueue(media("one.ts", "two.ts"))
                server.enqueue(MockResponse().setResponseCode(status))
                assertFalse(transport.poll(session, channel) { true })
                server.enqueue(master()); server.enqueue(media("one.ts", "two.ts")); server.enqueue(MockResponse())
                assertTrue(transport.poll(session, channel) { true })
                assertEquals(3, requests.count { it.path!!.startsWith("/api/channel/hls/") })
                assertEquals(listOf("/low/one.ts", "/low/two.ts", "/low/two.ts"), requests.filter { it.method == "HEAD" }.map { it.path })
            }
        }
    }

    @Test fun `new broadcasts accounts and stopped sessions discard derived watch state`() = runBlocking {
        fixture { server, transport, requests ->
            for ((account, stream) in listOf(session to channel, session to channel.copy(broadcastId = "new"),
                session.copy(userId = "42", accessToken = "new-token") to channel)) {
                server.enqueue(master()); server.enqueue(media("same.ts")); server.enqueue(MockResponse())
                assertTrue(transport.poll(account, stream) { true })
            }
            transport.reset()
            server.enqueue(master()); server.enqueue(media("same.ts")); server.enqueue(MockResponse())
            assertTrue(transport.poll(session, channel) { true })
            assertEquals(4, requests.count { it.method == "HEAD" })
            assertEquals(4, requests.count { it.path!!.startsWith("/api/channel/hls/") })
        }
    }

    @Test fun `dedup stays bounded over hundreds of overlapping playlists`() = runBlocking {
        fixture { server, transport, requests ->
            server.enqueue(master())
            repeat(260) { index ->
                server.enqueue(media("$index.ts", "${index + 1}.ts"))
                if (index == 0) server.enqueue(MockResponse())
                server.enqueue(MockResponse())
                assertTrue(transport.poll(session, channel) { true })
            }
            assertEquals(261, requests.count { it.method == "HEAD" })
            server.enqueue(media("0.ts")); server.enqueue(MockResponse())
            assertTrue(transport.poll(session, channel) { true })
            assertEquals(262, requests.count { it.method == "HEAD" })
        }
    }

    @Test fun `malformed and untrusted playlists do not trigger media requests`() = runBlocking {
        for (body in listOf("{}", "#EXTM3U\nhttps://attacker.example/token.ts", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/stream.ts",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://127.0.0.1/steal.m3u8")) {
            fixture { server, transport, requests ->
                server.enqueue(MockResponse().setBody(body))
                assertFalse(transport.poll(session, channel) { true })
                assertEquals(1, requests.size)
            }
        }
        fixture { server, transport, requests ->
            server.enqueue(master()); server.enqueue(media("okay.ts", "https://attacker.example/steal.ts"))
            assertFalse(transport.poll(session, channel) { true })
            assertEquals(0, requests.count { it.method == "HEAD" })
        }
    }

    @Test fun `redirects oversized bodies and offline channels are rejected`() = runBlocking {
        fixture { server, transport, requests ->
            assertFalse(transport.poll(session, channel.copy(online = false)) { true })
            assertEquals(0, requests.size)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://attacker.example/steal"))
            assertFalse(transport.poll(session, channel) { true })
            server.enqueue(MockResponse().setBody("x".repeat(256 * 1024 + 1)))
            assertFalse(transport.poll(session, channel) { true })
            assertEquals(2, requests.size)
        }
    }

    @Test fun `selection change during playlist read prevents all segment requests`() = runBlocking {
        fixture { server, transport, requests ->
            server.enqueue(master()); server.enqueue(media("stale.ts").setBodyDelay(100, TimeUnit.MILLISECONDS))
            var current = true
            val job = async { transport.poll(session, channel) { current } }
            withTimeout(2_000) { while (requests.size < 2) delay(1) }
            current = false
            assertFalse(job.await())
            assertEquals(0, requests.count { it.method == "HEAD" })
        }
    }

    @Test fun `cancellation during segment request closes it and prevents remaining batch`() = runBlocking {
        fixture { server, transport, requests ->
            server.enqueue(master()); server.enqueue(media("blocked.ts", "later.ts"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async { transport.poll(session, channel) { true } }
            withTimeout(2_000) { while (requests.none { it.method == "HEAD" }) delay(1) }
            withTimeout(1_000) { job.cancelAndJoin() }
            assertEquals(listOf("/low/blocked.ts"), requests.filter { it.method == "HEAD" }.map { it.path })
            // Cancelled HEAD is not deduplicated when the same session resumes.
            server.enqueue(media("blocked.ts")); server.enqueue(MockResponse())
            assertTrue(transport.poll(session, channel) { true })
        }
    }

    @Test fun `blocked HEAD times out and remaining siblings still run`() = runBlocking {
        fixture { server, transport, requests ->
            server.enqueue(master()); server.enqueue(media("blocked.ts", "later.ts"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.enqueue(MockResponse())
            assertFalse(withTimeout(6_000) { transport.poll(session, channel) { true } })
            assertEquals(listOf("/low/blocked.ts", "/low/later.ts"), requests.filter { it.method == "HEAD" }.map { it.path })
        }
    }

    @Test fun `whole poll deadline stops a batch of blocked segments`() = runBlocking {
        fixture { server, transport, requests ->
            server.enqueue(master()); server.enqueue(media("1.ts", "2.ts", "3.ts", "4.ts", "5.ts"))
            repeat(5) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            assertFalse(withTimeout(12_000) { transport.poll(session, channel) { true } })
            assertTrue(requests.count { it.method == "HEAD" } in 3..4)
        }
    }

    @Test fun `slow playback propagates caller cancellation`() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val transport = HlsWatchTransport(playbackAccess = { _, _ -> try { awaitCancellation() } finally { finished.complete(Unit) } })
        val job = async { transport.poll(session, channel) { true } }
        yield()
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(finished.isCompleted)
    }

    @Test fun `media destination checks reject credentials lookalikes ports and non HTTPS`() {
        assertTrue(isTrustedTwitchMediaUrl("https://video-edge.example.hls.ttvnw.net/segment.ts".toHttpUrl()))
        for (url in listOf("http://a.ttvnw.net/a", "https://ttvnw.net.attacker.example/a", "https://evilttvnw.net/a",
            "https://user:pass@a.ttvnw.net/a", "https://a.ttvnw.net:8443/a", "https://127.0.0.1/a", "https://a.ttvnw.net/a#fragment")) {
            assertFalse(isTrustedTwitchMediaUrl(url.toHttpUrl()), url)
        }
    }

    private suspend fun fixture(block: suspend (MockWebServer, HlsWatchTransport, List<RecordedRequest>) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val requests = CopyOnWriteArrayList<RecordedRequest>()
            server.dispatcher = object : QueueDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return super.dispatch(request)
                }
            }
            // NO_RESPONSE marks a route failed. Avoid localhost's second, unbound IPv6 address
            // masking the next request when implicit connection retries are deliberately disabled.
            val origin = server.url("/").newBuilder().host("127.0.0.1").build()
            val transport = HlsWatchTransport({ _, _ -> PlaybackAccess("synthetic value&?", "synthetic-signature") }, origin)
            block(server, transport, requests)
        }
    }

    private fun master() = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\n/high/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n/low/index.m3u8\n")
    private fun media(vararg segments: String) = MockResponse().setBody("#EXTM3U\n" + segments.joinToString("\n") { "#EXTINF:2.0,\n$it" } + "\n")
    private val session = StoredTwitchSession("synthetic-token", "12345", "synthetic-device", Instant.EPOCH)
    private val channel = Channel(67890, "Synthetic", online = true, dropsEnabled = true, broadcastId = "broadcast", login = "synthetic")
}
