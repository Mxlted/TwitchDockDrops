package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.*
import com.nathan.twitchdropsminer.android.runtime.*
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class PublicCatalogTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: java.nio.file.Path
    private val now = Instant.now()
    private val session = StoredTwitchSession("private-access", "42", "private-device", now,
        clientId = TwitchTvClientId, refreshToken = "private-refresh")
    private fun response(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")
    private fun record(id: String = "campaign", minutes: Int? = null): JsonObject = Json.parseToJsonElement("""{
        "id":"$id","name":"Campaign","status":"ACTIVE","startAt":"${now.minusSeconds(43200)}","endAt":"${now.plusSeconds(129600)}",
        "game":{"id":"1","displayName":"Game"},"allow":{"isEnabled":false},"accountLinkURL":"https://example.com/link",
        "self":{"isAccountConnected":true},
        "timeBasedDrops":[{"id":"drop","name":"Reward","requiredMinutesWatched":60,"requiredSubs":0,
        "startAt":"${now.minusSeconds(43200)}","endAt":"${now.plusSeconds(129600)}","preconditionDrops":null,
        "benefitEdges":[{"benefit":{"id":"benefit","name":"Item","distributionType":"BADGE"}}]
        ${minutes?.let { ",\"self\":{\"currentMinutesWatched\":$it,\"isClaimed\":false,\"dropInstanceID\":\"private-claim\"}" }.orEmpty()}}]}""").jsonObject
    private fun feed(vararg records: JsonObject, time: Instant = now) = buildJsonObject {
        put("lastUpdatedAt", time.toString()); put("data", buildJsonArray {
            add(buildJsonObject { put("gameBoxArtURL", "https://example.com/art"); put("rewards", JsonArray(records.toList())) })
        }) }.toString()
    private fun inventory(vararg records: JsonObject) = Json.parseToJsonElement(
        """{"data":{"currentUser":{"inventory":{"dropCampaignsInProgress":${JsonArray(records.toList())},"gameEventDrops":[]}}}}""").jsonObject
    private fun api(twitch: MockWebServer, catalog: MockWebServer, client: OkHttpClient = OkHttpClient()) = TwitchApiClient(client,
        gqlEndpoint = twitch.url("/gql").toString(), oauthBaseUrl = twitch.url("/").toString(),
        publicCatalogClient = PublicCatalogClient(catalog.url("/catalog").toString()) { now })

    @Test fun `catalog discards forged account state and selection preserves unknowns`() {
        val catalog = parsePublicCatalog(feed(record(minutes = 60)), now)
        assertNull(catalog.problem)
        val campaign = catalog.campaigns.single()
        assertFalse(campaign.linked); assertFalse(campaign.linkStatusKnown); assertFalse(campaign.isKnownUnlinked)
        val drop = campaign.drops.single()
        assertFalse(drop.progressKnown); assertFalse(drop.canClaim); assertNull(drop.claimId); assertFalse(drop.isClaimed)
        val settings = AppSettings(selectedGamePriority = listOf("Game"), fallbackToOtherGames = false)
        assertEquals("campaign", CampaignPrioritySelector.orderedCandidates(settings, listOf(campaign), now).single().id)
        assertTrue(CampaignPrioritySelector.orderedCandidates(settings.copy(excludedRewardNames = listOf("Item")), listOf(campaign), now).isEmpty())
        assertTrue(CampaignPrioritySelector.orderedCandidates(settings.copy(allowedRewardTypes = setOf("EMOTE")), listOf(campaign), now).isEmpty())
        assertIs<DropClaimPreparation.NotClaimable>(DropClaimResolver.prepare(session, campaign, drop))
        val confirmed = assertIs<TwitchProgressUpdate.Updated>(campaign.applyTwitchProgress(CurrentDropProgress("drop", 60))).campaign
        assertTrue(confirmed.drops.single().progressKnown)
        assertIs<DropClaimPreparation.NotClaimable>(DropClaimResolver.prepare(session, confirmed, confirmed.drops.single()))
    }

    @Test fun `TV admission and rotation validation use only OAuth and inventory`() = runBlocking {
        MockWebServer().use { twitch -> MockWebServer().use { catalog ->
            twitch.start(); catalog.start()
            val client = api(twitch, catalog)
            repeat(2) {
                twitch.enqueue(response("""{"client_id":"$TwitchTvClientId","user_id":"42"}"""))
                twitch.enqueue(response(inventory().toString()))
                client.validateDropsAccess(session.copy(accessToken = "private-$it"))
                assertEquals("/oauth2/validate", twitch.takeRequest().path)
                assertEquals("Inventory", Json.parseToJsonElement(twitch.takeRequest().body.readUtf8()).jsonObject["operationName"]!!.jsonPrimitive.content)
            }
            assertEquals(0, catalog.requestCount)
        } }
    }

    @Test fun `TV discovery isolates cookies interceptors identifiers and authentication from catalog`() = runBlocking {
        MockWebServer().use { twitch -> MockWebServer().use { catalog ->
            twitch.start(); catalog.start()
            val cookieJar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("auth-token").value("private-cookie").domain(url.host).build())
            }
            val shared = OkHttpClient.Builder().cookieJar(cookieJar).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Private", "private-interceptor").build())
            }.build()
            twitch.enqueue(response(inventory(record(minutes = 12)).toString()))
            catalog.enqueue(response(feed(record(minutes = 60), record("public", 60))))
            val result = api(twitch, catalog, shared).fetchCampaignInventory(session)
            assertEquals(12, result.campaigns.first { it.id == "campaign" }.drops.single().currentMinutes)
            assertFalse(result.campaigns.first { it.id == "public" }.linkStatusKnown)
            assertEquals(1, twitch.requestCount)
            assertEquals("Inventory", Json.parseToJsonElement(twitch.takeRequest().body.readUtf8()).jsonObject["operationName"]!!.jsonPrimitive.content)
            val request = catalog.takeRequest()
            assertEquals("GET", request.method); assertEquals("/catalog", request.path); assertEquals(0, request.bodySize)
            for (header in listOf("Authorization", "Cookie", "Client-Id", "X-Device-Id", "Client-Session-Id", "Origin", "Referer", "X-Private")) assertNull(request.getHeader(header))
            assertFalse(request.headers.toString().contains("private"))
        } }
    }

    @Test fun `legacy browser discovery does not contact catalog`() = runBlocking {
        MockWebServer().use { twitch -> MockWebServer().use { catalog ->
            twitch.start(); catalog.start()
            twitch.enqueue(response(inventory().toString()))
            twitch.enqueue(response("""{"data":{"currentUser":{"dropCampaigns":[]}}}"""))
            api(twitch, catalog).fetchCampaignInventory(session.copy(clientId = null, refreshToken = null))
            assertEquals(2, twitch.requestCount); assertEquals(0, catalog.requestCount)
            twitch.takeRequest()
            assertContains(twitch.takeRequest().body.readUtf8(), "ViewerDropsDashboard")
        } }
    }

    @Test fun `catalog timestamp schema partial duplicates and unsafe restrictions are rejected`() {
        assertNotNull(parsePublicCatalog(feed(record(), time = now.minusSeconds(1801)), now).problem)
        assertNotNull(parsePublicCatalog(feed(record(), time = now.plusSeconds(301)), now).problem)
        for (bad in listOf("{}", "[]", "garbage", "{\"lastUpdatedAt\":\"$now\",\"data\":null}")) assertNotNull(parsePublicCatalog(bad, now).problem)
        val duplicate = parsePublicCatalog(feed(record(), record(), record()), now)
        assertTrue(duplicate.campaigns.isEmpty()); assertNotNull(duplicate.problem)
        val malformed = listOf(
            JsonObject(record() - "allow"),
            JsonObject(record() + ("allow" to Json.parseToJsonElement("""{"isEnabled":true,"channels":[]}"""))),
            JsonObject(record() + ("timeBasedDrops" to JsonArray(listOf(JsonObject(record()["timeBasedDrops"]!!.jsonArray[0].jsonObject - "preconditionDrops"))))),
        )
        for (bad in malformed) {
            val result = parsePublicCatalog(feed(record("good"), bad), now)
            assertNotNull(result.problem); assertEquals(listOf("good"), result.campaigns.map { it.id })
        }
    }

    @Test fun `catalog failures retain metadata throttle requests and cannot invalidate OAuth`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); var time = now
            val client = PublicCatalogClient(server.url("/catalog").toString()) { time }
            server.enqueue(response(feed(record())))
            assertNull(client.fetch().problem)
            (1..8).map { async { client.fetch() } }.awaitAll()
            assertEquals(1, server.requestCount)
            time = time.plusSeconds(61)
            server.enqueue(response("private-response").setResponseCode(401))
            val failed = client.fetch()
            assertEquals(1, failed.campaigns.size); assertContains(failed.problem!!, "Public catalog")
            assertFalse(failed.problem.contains("private"))
            time = time.plusSeconds(1801)
            server.enqueue(response(feed(record())))
            assertNotNull(client.fetch().problem)
            assertEquals(1, client.fetch().campaigns.size)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `catalog rejects oversized bodies and never follows redirects`(): Unit = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            server.enqueue(response("{}").setResponseCode(302).setHeader("Location", other.url("/leak")))
            assertNotNull(PublicCatalogClient(server.url("/").toString()) { now }.fetch().problem)
            assertEquals(0, other.requestCount)
            server.enqueue(response("x".repeat(8 * 1024 * 1024 + 1)))
            assertNotNull(PublicCatalogClient(server.url("/").toString()) { now }.fetch().problem)
        } }
        assertFailsWith<IllegalArgumentException> { PublicCatalogClient("https://example.com/catalog") }
    }

    @Test fun `malformed account records never acquire public account state and cannot be mined`() {
        val valid = parseTvAccountInventory(inventory(record(minutes = 60)))
        val known = mergeTvSources(valid, parsePublicCatalog(feed(record()), now)).campaigns.single()
        assertTrue(known.drops.single().canClaim)
        assertIs<DropClaimPreparation.Ready>(DropClaimResolver.prepare(session, known, known.drops.single()))
        val duplicate = parseTvAccountInventory(inventory(record(), record()))
        assertEquals(setOf("campaign"), duplicate.rejectedIds)
        val partial = mergeTvSources(duplicate, parsePublicCatalog(feed(record()), now))
        assertTrue(partial.campaigns.isEmpty())
        val retained = retainTvInventory(listOf(known), partial, now).single()
        assertFalse(retained.accountStateUsable); assertFalse(retained.canEarnLocallyAt(now))
        assertFalse(retained.drops.single().canClaim); assertNull(retained.drops.single().claimId)
        assertFalse(retained.drops.single().hasCompletedProgress)
        assertFailsWith<TwitchApiException> { parseTvAccountInventory(Json.parseToJsonElement("""{"data":{"currentUser":{"inventory":{}}}}""").jsonObject) }
    }

    @Test fun `fresh inventory wins during catalog outage and missing account progress stays unknown`() {
        val owned = mergeTvSources(parseTvAccountInventory(inventory(record(minutes = 12))), PublicCatalogResult(problem = "Public catalog unavailable"))
        assertEquals(12, owned.campaigns.single().drops.single().currentMinutes); assertTrue(owned.isPartial)
        val fresh = mergeTvSources(parseTvAccountInventory(inventory()), parsePublicCatalog(feed(record()), now))
        val retained = retainTvInventory(owned.campaigns, fresh, now).single()
        assertEquals(12, retained.drops.single().currentMinutes)
        assertFalse(retained.drops.single().progressKnown); assertFalse(retained.linkStatusKnown)
        assertFalse(retained.drops.single().canClaim)
    }

    @Test fun `restricted catalog channels reject manual outside channel before Twitch request`() = runBlocking {
        MockWebServer().use { twitch -> MockWebServer().use { catalog ->
            twitch.start(); catalog.start()
            val campaign = parsePublicCatalog(feed(record()), now).campaigns.single().copy(allowedChannels = listOf(Channel(7, "allowed", login = "allowed")))
            assertFalse(api(twitch, catalog).fetchCampaignChannel(session, "outside", campaign).dropsEnabled)
            assertEquals(0, twitch.requestCount)
        } }
    }

    @Test fun `catalog prerequisites and subscription rewards remain constrained through merging`() {
        val original = record()
        val first = original.getValue("timeBasedDrops").jsonArray.single().jsonObject
        val dependent = JsonObject(first + mapOf("id" to JsonPrimitive("dependent"),
            "preconditionDrops" to JsonArray(listOf(buildJsonObject { put("id", "drop") }))))
        val subscription = JsonObject(first + mapOf("id" to JsonPrimitive("subscription"), "requiredSubs" to JsonPrimitive(1)))
        val campaign = JsonObject(original + ("timeBasedDrops" to JsonArray(listOf(first, dependent, subscription))))
        val catalog = parsePublicCatalog(feed(campaign), now)
        val public = catalog.campaigns.single().withRewardEligibility(AppSettings(), now)
        assertContains(public.drops.first { it.id == "dependent" }.blockedReason!!, "prerequisite")
        assertEquals(0, public.drops.first { it.id == "subscription" }.requiredMinutes)
        val merged = mergeTvSources(parseTvAccountInventory(inventory(record(minutes = 12))), catalog).campaigns.single()
        assertEquals(3, merged.drops.size)
        assertEquals(12, merged.drops.first { it.id == "drop" }.currentMinutes)
        assertFalse(merged.drops.first { it.id == "dependent" }.progressKnown)
    }

    @Test fun `pending claim recovery requires fresh Twitch evidence despite forged catalog self`() {
        var time = now
        val history = com.nathan.twitchdropsminer.android.data.local.ClaimHistoryStore(directory, now = { time })
        val owned = mergeTvSources(parseTvAccountInventory(inventory(record(minutes = 60))), PublicCatalogResult()).campaigns.single()
        history.begin(session.userId, owned, owned.drops.single())
        time = now.plusSeconds(601)
        val public = mergeTvSources(parseTvAccountInventory(inventory()), parsePublicCatalog(feed(record(minutes = 60)), now))
        history.reconcile(session.userId, public.campaigns)
        assertTrue(history.suppressed(session.userId, "campaign", "drop"))
        history.reconcile(session.userId, listOf(owned))
        assertFalse(history.suppressed(session.userId, "campaign", "drop"))
        val completed = assertIs<TwitchProgressUpdate.Updated>(public.campaigns.single().applyTwitchProgress(CurrentDropProgress("drop", 60))).campaign
        assertTrue(completed.awaitingTvClaimEvidence())
        assertFalse(completed.drops.single().canClaim)
    }

    @Test fun `TV progress errors cannot masquerade as zero progress`() = runBlocking {
        MockWebServer().use { twitch -> MockWebServer().use { catalog ->
            twitch.start(); catalog.start(); val client = api(twitch, catalog)
            for (body in listOf("{}", """{"errors":[{"message":"private-error"}]}""",
                """{"data":{"currentUser":{"dropCurrentSession":{"dropID":"drop"}}}}""")) {
                twitch.enqueue(response(body))
                val failure = assertFailsWith<TwitchApiException> { client.currentDrop(session, 7) }
                assertContains(failure.message!!, "Twitch progress"); assertFalse(failure.message!!.contains("private"))
            }
        } }
    }
}
