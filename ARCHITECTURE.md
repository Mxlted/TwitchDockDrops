# Architecture

## Goal

Provide the behavior of `TwitchDropsMinerAndroid` as a long-running, restartable Docker service with
a browser UI, without running an Android emulator or depending on the Android project at build or
runtime.

## Runtime shape

```text
Browser
  |  same-origin HTTP + server-sent state events
  v
JDK HttpServer / WebApi
  |-- static client resources
  |-- command endpoints
  |-- redacted state serialization
  v
LocalMinerRuntime (root-owned JVM source)
  |-- TwitchApiClient (root-owned JVM source)
  |-- JVM settings adapter -> /data/settings.json
  |-- JVM encrypted session adapter -> /data/session.enc
  |-- JVM bounded log adapter -> /data/runtime.log
  `-- JVM network status provider
```

The base Compose file runs this graph as one service. Splitting the static UI, API, and miner into separate
containers would add synchronization and failure modes without improving isolation: they share one
account session and one authoritative runtime state. `compose.browser.yaml` optionally adds an isolated
Chromium/Xvfb/Node companion for dashboard authentication. It shares only the app's network namespace,
has no data mounts, listens on loopback 8091, and is accessed through the existing same-origin JVM API.

At process startup, `LocalMinerRuntime` restores any encrypted Twitch session and honors the persisted
`miningRequested` intent: a previously running miner resumes, while a previously stopped miner only
schedules an inventory refresh. Both paths run on the application coroutine scope so local process
readiness does not depend on Twitch reachability. The same intent resumes mining after a successful
re-login; process shutdown stops and joins work without changing it.

### Execution model

`LocalMinerRuntime` is the single lifecycle owner. Start, stop, login, refresh, reset, authentication
completion, and credential-expiry commands pass through one serialized command channel. Twitch work
runs in child coroutines, but each job carries session and operation generations plus a guarded commit
context. Every Twitch completion is revalidated before state, activity, logs, counters, credentials,
claim-attempt tracking, or settings can change. A cancellation-insensitive response from a cancelled
or superseded job therefore cannot commit local state after a reset, replacement login, stop, session
expiry, or newer refresh. Twitch side effects that completed remotely cannot be undone.

Only one mining loop, authentication attempt, and standalone inventory refresh can be authoritative
at a time. Repeated start commands are idempotent. Refresh commands received while mining are
coalesced into the mining loop instead of creating a competing inventory job. The command channel is
bounded and coalesces queued idempotent lifecycle requests. User Start/Stop commands persist runtime
intent inside that serialized command flow before idempotency checks; session reset clears the intent,
while preference reset preserves it. Intent persistence is best-effort: a storage failure emits a safe
warning but never prevents the requested Start or Stop lifecycle transition. Device authorization parses
`authorization_pending`, increases its polling cadence for `slow_down`, surfaces `access_denied` and
`expired_token` immediately without routing either terminal outcome through transient retry, and
rejects malformed/unknown replies. It retries genuinely transient
Twitch/network failures until the code expires, and a completed login immediately schedules inventory
loading. Ordinary login start is idempotent while a code is being
prepared or a still-valid code is being polled. A separate replacement command cancels and invalidates
that attempt before requesting a new code. Mining and refresh commands received during authorization
leave the displayed code, activation URL, and expiry intact.

The mining loop validates the access token at startup and at least hourly on the next inventory reload,
using the same guarded retry/backoff path. It waits on state changes rather than polling blindly.
Settings changes, channel-control requests, refresh requests, higher-priority channel checks,
three-minute current-channel status checks, and watch deadlines wake it through coroutine selection.
The live recheck abandons offline or category-mismatched streams immediately; a changed broadcast ID
refreshes channel metadata and invalidates cached watch configuration before the next heartbeat. Watch
heartbeats retain their own cadence, so a settings change, priority check, or live recheck does not
accidentally emit an extra heartbeat. Idle waits also include the earliest future campaign or drop start
and pending claim-retry deadline; active waits include the current campaign and drop ends. A boundary wake
re-evaluates cached lifecycle dates and refreshes inventory as needed, so later scheduled drops do not
wait for the hourly refresh and do not cause busy polling. Independent Twitch detail/channel lookups use
a fixed-size sliding worker pool, so a slow early lookup does not hold all later candidates behind a
fixed batch and large inventories cannot create one suspended coroutine per candidate. Campaign detail
requests are skipped when summary/inventory fields are already sufficient. The shared HTTP client also
bounds each complete upstream call, including redirects and response-body reads, to two minutes.

Device polling accepts both OAuth `error` and Twitch's documented `message` response field. HTTP
429/5xx remains transient even if its body resembles a terminal OAuth error. OAuth parsing failures
use fixed diagnostics; device-request 4xx rejections other than 429 stop authorization immediately.
No raw OAuth response text is surfaced. Validation requires the session's Android or web client ID
and a positive numeric user ID; only a validation HTTP 401 proves token invalidity. A 403, client
mismatch, malformed validation, or integrity rejection preserves the encrypted credential. Pure
GraphQL `invalid oauth token` / `failed integrity check` errors without data or an execution path
also trigger OAuth validation, including when HTTP status is 200. Partial data is retained and this
transport never automatically replays a claim.
Starting replacement authorization preserves the old encrypted credential until successful atomic
replacement; failure to obtain a new code does not delete it. Start/refresh commands cannot use that
preserved credential while authorization is active. Explicit session reset still deletes it.

The 2026-10-04 upstream review covers rangermix/TwitchDropsMiner through `1182d0172458` (v2.1.1).
The desktop fallback uses a root-owned Node helper modeled on upstream's browser-context
capture. It uses a temporary native Chromium profile for interactive Twitch login, then regular headed
browser captures for renewal, with a nonzero loopback debugging port. It correlates issued integrity
tokens with successful authenticated campaign
responses and transfers only allowlisted request headers, user agent, and issuance/expiry timestamps.
The JVM validates the web OAuth client/account and both Inventory and Campaigns queries before atomic
encrypted save. Each helper lease binds to the first accepted account. Renewal goes through the same
generation-guarded runtime authentication command and resumes saved mining intent. Chromium is not
installed in the JVM image; renewal runs in the optional browser companion or desktop helper.
Upstream's helper protocol is not supported.
Existing Android sessions remain supported; the superseded Smart TV client switch is not adopted.

`BrowserLoginAdmission` owns a single in-memory pairing lease: a random 72-bit one-use code with a
ten-minute expiry and five-guess limit becomes a random 256-bit helper ticket. The initial ticket
shares that expiry; accepted uploads extend it for 24 hours. New pairing, reset, and process restart
revoke it. Ticket-authenticated status reports only connected/verifying/ready/failed state. Late
verification cannot commit after reset or a replacement. The desktop helper stops on capture, transfer, or
verification failure; reconnect to resume. Expired integrity proof blocks authenticated requests
without deleting the saved credential. Browser-context persistence remains within the encrypted
session envelope, and old sessions load without migration. Discarded CDP response bodies (`-32000` on
`Network.getResponseBody`) are skipped while waiting for complete evidence. Unrelated unauthenticated
GraphQL bodies are not read. Other command errors retain only the method and numeric code.

### Optional dashboard browser

`DashboardLogin` owns authentication transport only. Starting login requests a serialized runtime
command that opens and claims an admission lease atomically; neither pairing code nor ticket enters
the public state. A coroutine serializes companion startup, status/context transfers, and cleanup.
The worker opens a headed Chromium browser under Xvfb. The UI relays JPEG frames and a bounded set of
click, text, scroll, and key commands. Capture starts before the login page so proof issued during
sign-in is retained. Finish sign-in collects successful Drops evidence in that same browser and reads
only the secure, HttpOnly `KP_UIDz-ssn` cookie for exact host `k.twitchcdn.net`, path `/`. It then closes
the interactive browser and independently issues a fresh proof in a temporary headless browser using
that seed. The JVM validates OAuth identity, Inventory and Campaigns before accepting the result and
atomically saving it. The dashboard transport acknowledges acceptance and closes its lease's worker;
the runtime owns subsequent renewal. No mining, campaign, heartbeat or claim scheduling moves to the
companion. The desktop helper's existing live-browser lease remains supported separately.
The capture stream only queues relevant OAuth-context request, response and completion events,
excluding preflights and unrelated assets/telemetry. Completed non-campaign evidence is discarded;
at most 16 successful campaign requests and 16 issued proofs are retained. Initial capture has a
two-minute deadline and reloads campaigns every 30 seconds if proof and successful campaign data
have not yet matched. Independent SDK issuance has a 150-second deadline including browser startup.
Fixed browser-failure codes distinguish login timeout and capture failure without forwarding diagnostics.

`BrowserSessionContext` optionally includes `sdk_cookie: {value, expires_at}` inside the existing
encrypted session only (36 KiB maximum context). Older contexts remain readable. This is not a
general cookie jar or persistent browser profile. `LocalMinerRuntime` schedules renewal five minutes
before proof expiry, or shortly after startup for an expired proof with a fresh seed. The private
`BrowserRenewalClient` sends a bounded context to the companion's fixed loopback `/renew` route and
polls only its own attempt ID. Each attempt creates and removes a temporary headless profile. A blank
Twitch-origin document loads the fixed Twitch SDK and issues `/integrity`; acceptance requires a
matching uncached POST response, a different token, advancing proof expiry and an advancing SDK-cookie
expiry. OAuth/device headers stay bound to the saved context. No direct HTTP-only proof refresh is used.

Renewal validates the same account and both Drops queries while the current miner continues. Only a
successful, generation-checked atomic save replaces the context and restarts work with saved mining
intent. Transient failures preserve the saved context and retry at 15 seconds, doubling to five minutes,
until the saved SDK seed expires. Only authoritative token invalidity clears credentials. Reset,
replacement login and shutdown cancel renewal and invalidate late results; Stop preserves renewal
but prevents it from restarting mining. Shutdown joins renewal cleanup and preserves Start/Stop intent.

Public browser endpoints explicitly serialize only a view ID, state/error, or JPEG image. The worker's
private status can include captured context but is never proxied wholesale. Worker routes reject
Origin-bearing requests, require an internal header and the exact loopback Host, disable CORS, and
bound bodies. The JVM only calls a fixed loopback address without redirects. It never exposes arbitrary
CDP commands or URL navigation. View IDs reject stale input and frames after replacement. Interactive
login is limited to eight minutes; captures and verification are time-bounded. Browser profiles stay
on tmpfs; only the scoped SDK cookie joins the encrypted context in the miner volume. JVM and companion
restarts can recover while that seed remains fresh; extended downtime, revoked OAuth or a Twitch
challenge can still require login. Older logins need one new dashboard sign-in to acquire the seed.
New helper login, reset, cancellation, and shutdown stop the companion session; runtime generations
and lease revocation independently block late credential commits. A renewal request cannot replace an
interactive login. Original encrypted credentials survive failed replacement and transient renewal.

Watch earning telemetry uses the direct Spade transport restored by the current TwitchDropsMiner
implementations. Every heartbeat builds a new uncompressed Base64 JSON array containing one
`minute-watched` event, then form-POSTs it as `data` to the discovered Spade URL. The payload includes
the canonical channel login, numeric user ID, broadcast/channel/game IDs, a millisecond UTC timestamp,
and Twitch's live, logged-in, location, player, mute, hidden, and minutes fields. No
`sendSpadeEvents` GraphQL mutation or gzip wrapper participates in the watch path.
The channel page and hashed settings bundle are fetched with the saved Twitch session. Production
configuration bundles are accepted only from `assets.twitch.tv` or the legacy
`static.twitchcdn.net` settings path. Collector discovery accepts either the `beacon_url`/`beaconUrl`
or legacy `spade_url`/`spadeUrl` key; event delivery is restricted to the current
`https://beacon.twitch.tv/track` collector or the legacy `https://spade.twitch.tv` host.

Automatic selection exhausts linked work before unlinked work by default: linked claimed-progress,
linked viewing-progress, linked fresh, unlinked claimed-progress, unlinked viewing-progress, then
unlinked fresh. Fallback to other games is enabled for new and reset settings; an explicitly saved off
preference remains authoritative. User-saved ordering remains authoritative. Both campaign and drop
start/end windows must be open before work is watchable, including when the drop omits its own end.
Completed or claimable drops remain claim candidates after their watch window.
Within the prioritized-game group, promotion checks only games earlier than the current game in the
saved order; fallback work still checks every higher fallback group. Promotion results are revalidated
against the latest settings before they commit.

Special Events (`509663`) and IRL (`509672`) campaigns allow an explicitly listed live participant
to stream another category. Campaign category IDs remain server-side; names alone never enable this
exception. Initial ACL discovery, channel-picker discovery, and periodic live rechecks use the same
campaign-aware lookup. Both numeric channel ID and login must match the participant list. Ordinary
campaigns and campaigns without a participant list keep the category requirement. Watch events
retain the actual streamed game name and ID; priority selection still uses the campaign category.

Active promotion deadlines also include the next known campaign/drop start boundary. After that
instant, only future boundaries are considered, preventing a tight loop on the same start time.
While a promotion lookup is in flight, its timer is omitted from the active wait: completion wakes
the loop directly, and heartbeat/status deadlines remain active. Slow lookups cannot spin on an
overdue promotion timer.
This is a deliberate root-only enhancement; the optional Android reference is unchanged.

Unlinked attempts use a short speculative validation window, but the first progress increase no longer
disables supervision. Both linked and confirmed-unlinked work continue through a longer sustained-stall
watchdog. Unavailable or mismatched progress data is not evidence of a stall. A confirmed stall first
renews cached watch configuration, then abandons the channel if a fresh configuration also stalls. The
normal selector tries another channel for the same campaign, the next campaign in the same group, and
only then the next fallback group. Existing channel cooldowns prevent rapid cycling. If Twitch reports
progress for a different known campaign, the runtime follows that authoritative campaign when settings
permit it. If Twitch reports a drop absent from the current inventory, the same mining loop retains the
watch and refreshes inventory immediately. Persistent unknown reports are limited to one automatic
refresh every five minutes, avoiding both the normal 15-minute minimum wait and a rapid refresh loop.

## Root-owned JVM core

The container service owns its platform-neutral miner files under `src/main/kotlin`:

- `data/model/AppSettings.kt`
- `data/model/AutoModePriority.kt`
- `data/model/BackendModels.kt`
- `data/model/RuntimeModels.kt`
- `data/twitch/TwitchApiClient.kt`
- `runtime/DropClaimRuntime.kt`
- `runtime/LocalMinerRuntime.kt`

The legacy Kotlin package names are intentionally retained to keep behavior and tests easy to compare
with the Android reference. Android-only DataStore, encrypted preferences, connectivity, service,
and Compose UI code are replaced by JVM adapters in the root project.

The Android project is maintained separately and is not part of this repository. `.gitignore` and
`.dockerignore` defensively exclude an optional local `TwitchDropsMinerAndroid/` checkout, the root
Gradle build has no path dependency on it, and Docker copies only root build files and `src/`. Parity
changes must be applied deliberately to each project; root builds must never sync, generate, or write
Android sources.

## State and commands

`GET /api/state` returns one redacted state document containing the runtime snapshot, settings, and
local logs. `GET /api/events` is a server-sent event stream of the same document. The access token,
device code secret, encryption key, and filesystem paths are never serialized. Campaign ACL
membership remains server-side for selection and is not included in campaign state payloads.

`snapshot.account.method` is `device`, `browser`, or `dashboard` during integrated sign-in. An accepted
integrated session uses `browser`, since its persisted credential type is the same as the helper's.
`snapshot.account.username` is nullable public identity from the OAuth validation response's `login`
field, exposed only while authenticated. Browser and device login save it inside the existing
encrypted session envelope; older envelopes without it remain readable. Mining validation refreshes
the displayed name, and a stopped inventory refresh tries validation after loading campaigns when
the name is missing. Inconclusive lookup failures retain the inventory and numeric-ID fallback;
authoritative token expiry follows the existing sign-out path. Identity updates use the same
operation-generation guard as other runtime updates. Overview and Settings display the account
separately from the watched channel, using a local initials badge without profile-image requests.
For desktop browser pairing, `oauthCode` contains the
short-lived pairing code and `expiresAt` its deadline; `oauthUrl` is absent. Full browser context and
helper tickets never enter state/events/logs. The client renders helper instructions rather than
Twitch activation for this method. `dashboard` omits `oauthCode` and links to `/browser-login.html`.

`GET /api/auth/options` advertises configured dashboard support. `GET /api/auth/dashboard/status`
returns `{id,state,error}` (including an explicit unavailable state), and `GET /api/auth/dashboard/frame?id=...`
returns `{image}` for the current interactive view. All responses bypass caches. The `start`, `finish`,
`input`, and `cancel` routes under that prefix use POST, trusted Origin, strict JSON and the normal body
limit. Start returns immediately after local lease setup and launches browser work asynchronously.
Input is at most 256 characters or a bounded coordinate/navigation event; credentials are relayed
without trimming, logging, or persistence. Session material is never returned to the viewer.

`snapshot.rewardCampaigns` is a separate, display-only list of reward promotions, with explicit
`id`, `name`, nullable `brand`, `gameName`, `summary`, `startsAt`, `endsAt`, and `rewardNames` fields.
`snapshot.rewardCampaignsAvailable` distinguishes a complete empty list from unavailable/partial data.
The existing `ViewerDropsDashboard` request now sets `fetchRewardCampaigns=true` and reads
`data.rewardCampaignsAvailableToUser`; the Inventory operation and its claim data remain unchanged.
The mapper bounds listings to 500 campaigns, validates IDs/names/date windows, and deduplicates names
from `rewardGroups[].rewards` and the legacy `rewards` array. Dates, rather than the reward `status`
enum, determine which campaigns are open. Fields were checked against Twitch's public Drops client
bundle on 2026-09-29; the existing persisted query hash is retained. No private redemption values,
codes, instructions URLs, or raw upstream objects enter the state document.
The guarded runtime inventory path replaces complete lists and merges partial results with prior
details, marking them unavailable; failures retain prior details with the same stale indication.
New sessions/reset clear reward data. Reward campaigns never enter `CampaignPrioritySelector`,
watch heartbeats, claims, drop totals, or saved game priorities. The Campaigns view shows an independent
Show/Hide panel, four-row pages, and a fixed HTTPS Twitch campaigns link. The compact reward panel sits
beside the Drop list at viewport widths of 1280px and above and follows it in document order below
that breakpoint. Reward summaries show two names; Details exposes the full description and reward
list. Expanded details remain in browser presentation state across panel toggles and state updates.
Existing Drop filters apply
only to the Drop list. Watch-time reward automation and authenticated reward verification are outside
this visibility feature.

`snapshot.selectionPreview` contains up to five campaign IDs, excluding the current campaign, in
the order produced by `CampaignPrioritySelector.orderedCandidates`. The same selection stages power
the miner and this redacted preview, including saved category ranks, exclusions, watch windows, and
custom fallback order. This does not perform channel lookups or create another scheduler. The browser
resolves these IDs against the serialized campaigns to render **Up next**.

`GET /api/categories/search?q=...&after=...` performs a read-only public category lookup. It accepts
one `q` parameter of 2–100 characters and an optional opaque `after` cursor for queries of 4+ characters.
It validates Host, method, duplicate/unknown parameters, and cursor size/characters, and returns
explicitly serialized `{query, categories: [{id, name}], nextCursor}`. Two- or three-character searches
return at most 12 results and a null cursor; 4+ characters return up to 50 per page and a nullable next
cursor. Missing/invalid queries or paging on a short query return 400,
capacity exhaustion returns 429, and upstream failures return a safe 502 error. It does not touch
settings, credentials, `RuntimeSnapshot`, or the miner command queue.
`TwitchCategorySearch` sends an anonymous `SearchCategories` GraphQL query with JSON variables to the
fixed Twitch endpoint. `CategorySearchRequest` owns query normalization, validation, cursor rules, and
page limits; the route, search provider, and explicit serializer share the same validated request.
The transport separates bounded HTTP reads from page, category, and continuation parsing.
Two semaphore slots, a 15-second whole-call timeout, a 128 KiB response limit,
and a 32-entry/five-minute memory cache keyed by case-insensitive query plus cursor bound its cost
independently of mining. Redirects are disabled. Each request fetches exactly one page. Continuations
use the final edge cursor because Twitch's `pageInfo.endCursor` is null. Malformed, repeated, missing,
or oversized cursors fail safely; oversized pages fail rather than silently skipping truncated entries.
The private query was verified against Twitch on 2026-09-22; it can change independently of this app.

Every route validates Host against `TWITCH_DROPS_TRUSTED_HOSTS` before routing. Mutations under
`/api/*` additionally require a `TWITCH_DROPS_TRUSTED_ORIGINS` Origin, the exact route method,
`application/json`, an object body no larger than 64 KiB, known fields, exact JSON primitive types,
and bounded values. Forwarded headers are ignored. Errors are stable JSON with an `error` field.

`TWITCH_DROPS_ALLOW_LAN` is an opt-in extension to those static allowlists. It accepts only literal
private or link-local IP Host values. A LAN mutation must use HTTP and its Origin host and port must
match the request Host exactly, so another LAN web origin cannot issue commands merely because both
addresses are private. Public IPs and inferred DNS names remain rejected.

Persistence mutations share one server mutex and return HTTP 200 only after atomic local storage
succeeds. Commands whose Twitch/network work continues asynchronously return HTTP 202. An acknowledged
log clear holds the repository lock through deletion, so it cannot erase a later append. Long Twitch
calls run on coroutines without holding an HTTP connection open. The browser suppresses an identical
mutation while that command is in flight, and the runtime remains the final idempotency boundary.
`/api/auth/start` begins login idempotently;
`/api/auth/replace` explicitly invalidates the current device-code generation and requests a new code.
The default UI uses `POST /api/auth/browser/start` with `{}` to create/replace pairing. The desktop
helper uses POST `/api/auth/browser/claim` with `{code}`, `/submit` with `{ticket,context}`, and
`/status` with `{ticket}`. These routes enforce the same Host, Origin, JSON, and 64 KiB request limits;
context is independently capped at 36 KiB with exact fields and bounded ASCII header values. Claim
returns a ticket only to the caller, submit returns 202 after local admission while verification runs
asynchronously, and status returns only the lease state. `/login-helper.mjs` is a no-cache attachment.

Direct execution listens on loopback by default. Compose explicitly uses a container-internal
`0.0.0.0` listener while retaining loopback host publication unless `.env` opts into LAN binding.
Reverse proxies must configure external trusted hosts and origins explicitly.

## Persistence

The Compose volume at `/data` is the only mutable application filesystem:

- settings are normalized before an atomic JSON replacement, including the `miningRequested` runtime
  intent that is outside the public settings-update schema;
- saved game priorities and campaign exclusions are deduplicated, length-checked, and capped at 500
  entries each before persistence;
- the Twitch session is encrypted using AES-256-GCM;
- a random local key is created on first start unless an external key is supplied;
- logs are capped so unattended runs cannot grow the volume without bound.

Settings, session material, keys, and logs use owner-only permissions where POSIX supports them.
Readers enforce file-size limits and distinguish absent, loaded, corrupt, key-mismatched, and
unreadable states. Corrupt settings and locally keyed sessions are quarantined; an externally keyed
session is preserved on key mismatch. Startup exposes only bounded, redacted diagnostics. Log loading
reads a bounded tail, then rewrites within line, per-entry, and physical-size limits. New records use
single-line append writes until a line or physical-size bound requires an atomic compacting rewrite.

The image runs as a non-root user with all Linux capabilities dropped and a read-only root
filesystem. `/tmp` is a small in-memory filesystem. The default container JVM uses Serial GC with a
16–256 MiB heap and explicit metaspace, code-cache, and direct-memory ceilings; Compose exposes a
single full-string override through `TWITCH_DROPS_JAVA_OPTS` for exceptional inventories.

OAuth, GraphQL, HTML configuration, and error bodies have endpoint-specific response limits. Campaign
mapping produces bounded diagnostics instead of silently dropping malformed records. A nonempty
inventory with no safe campaign is a schema failure that preserves the last known-good inventory;
identified safe partial results merge without pruning omitted campaigns, missing drops within a
partially returned campaign, or saved priorities. Empty
GraphQL error arrays are accepted, while partial data with errors is retained with diagnostics.

OAuth Authorization is restricted to fixed Twitch OAuth and GraphQL destinations plus narrow Twitch
watch-configuration and collector allowlists. Channel HTML, hashed static configuration, and Spade
watch events use the authenticated Twitch session headers required to attribute progress. Derived
configuration is limited to `assets.twitch.tv` or `static.twitchcdn.net`, and event delivery is limited
to `https://beacon.twitch.tv/track` or HTTPS `spade.twitch.tv`. Same-origin loopback endpoint
injection is constructor-only for MockWebServer tests.
HTTP redirects are disabled. Captured integrity/version/session headers are added only to GraphQL;
watch configuration and collectors never receive the full browser context.

## Web client

The flat dashboard palette (opaque surfaces, hairline borders, a single Twitch-purple accent, system
type) is dark by default, with a browser-local light preference available from the header. A same-origin pre-paint initializer applies a saved preference before the stylesheet renders,
avoiding a theme flash. The server checks for state changes every two seconds but serializes and sends
the full state document only when the snapshot, settings, or bounded logs change; idle connections
receive sparse keepalives. Event clients are capped, and excess tabs receive HTTP 503 and use the
polling fallback rather than creating unbounded virtual threads. The browser uses periodic state polling only when the event stream is
unavailable. State updates replace the current view only when its rendered markup changes, and the
page entrance animation is reserved for initial load and explicit navigation so updates do not
discard focus. Active drop and channel links are emitted only for validated HTTPS `twitch.tv`
destinations; channel links use the serialized canonical login while visible labels retain Twitch's
display name. Campaign names in the campaign list, active watch card, and Up next reuse the serialized
`campaignUrl` with the same HTTPS Twitch validation, a subtle external-link indicator, and a new tab.
Missing or rejected URLs leave the campaign name as escaped plain text.
Linked/unlinked campaign filters remain browser-local presentation state. Priorities
missing from a partial/current inventory are displayed as unavailable without being deleted. Mutable
HTML, JavaScript, and CSS use `no-cache`, preventing a stale client from crossing a state-schema upgrade.
The active-watch card exposes compatible live channel alternatives through the existing serialized
runtime command flow; the browser owns only the accessible loading, empty, and selection presentation.
The header carries the miner status pill and the global Start/Stop, refresh, and theme controls; the
current view is mirrored in the URL hash so refresh, back, and bookmarks restore it. Estimated finish
times and relative campaign end dates are browser-side presentation of serialized snapshot fields;
the queue uses the server selection preview, not a second scheduler. When the event stream and polling both fail, an
offline banner is shown over the last known state.

Category priorities are an independent ordered editor on Campaigns. They reuse the existing
`selectedGamePriority` settings schema and priority mutation routes, so older saved settings need no
migration. Exact names can be added without an inventory entry; matching is case-insensitive and
does not track Twitch category renames by ID. New additions to a full 500-game list return HTTP 409
before persistence, instead of silently truncating another priority. The default All Twitch categories
scope performs an explicit submitted search: 2–3 characters show at most 12 results, while 4+ characters
enable Previous/Next navigation over all matches Twitch exposes, rendering up to 50 per page. There
is no total result cutoff or automatic page crawling. Only the current result page and a cursor history
are retained in the browser. Paging failures keep the current results/navigation available for retry;
page changes reset result scroll, while priority saves and state updates preserve it. Editing the query or scope
aborts the browser request, clears results, and invalidates stale completions; a 20-second browser
timeout makes failures retryable. Loaded/saved, active, and linked scopes remain browser-local, require
two characters, and render at most eight matches. Search selection saves Twitch's canonical name
through the existing priority route; category IDs do not migrate the name-based settings format.
Campaign rendering is paged at 24 rows. View updates restore focused controls, text selection,
in-progress numeric values, and priority-list scroll; successful mutations refresh state before their
duplicate-command guard is released. Clearing the list requires a dialog confirmation.

The responsive breakpoints are:

- desktop: fixed left navigation and a wide content canvas;
- tablet: compact navigation with two-column content;
- mobile: compact header with the status pill and actions, an icon bottom navigation, and
  single-column cards.

## Failure behavior

- Invalid Twitch tokens delete the persisted credential only after token validation returns HTTP 401 for invalid
  credentials. A Twitch GraphQL 401/403 triggers that validation first; a still-valid session or an
  inconclusive validation result is treated as a transient HTTP failure. Watch/configuration 401/403
  results preserve the session and trigger configuration recovery.
- Network failures are retried by the shared runtime with bounded backoff/channel failover. The JVM
  raw reachability probe is advisory because proxied OkHttp traffic can succeed when direct TCP does
  not; actual API outcomes remain authoritative. A stale
  cached watch endpoint is invalidated, re-resolved, and retried once.
- Claim successes and already-claimed responses are terminal. Invalid tokens expire the session;
  network, HTTP, GraphQL, and ambiguous claim failures receive a bounded cooldown even for unlinked
  campaigns. Completed work is not watched merely to wait for that cooldown: other useful work is
  selected immediately, and the claim deadline wakes the loop automatically.
- Missing progress data is treated as unavailable evidence, not as proof that a campaign is stalled.
- Per-candidate lookup failures continue to later campaigns/channels. Background promotion failures
  leave a current healthy watch untouched, while invalid-token errors still propagate.
- Reset, replacement login, stop, and credential expiry invalidate older in-flight results before
  publishing their terminal state.
- Container restarts preserve settings and session data in the named volume.
- A failed state event stream falls back to periodic state polling in the browser.
- Health checks cover the local HTTP process, not Twitch availability; external outages should not
  make Compose restart a healthy miner process.
