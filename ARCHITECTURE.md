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

Compose runs this graph as one service. Splitting the static UI, API, and miner into separate
containers would add synchronization and failure modes without improving isolation: they share one
account session and one authoritative runtime state.

At process startup, `LocalMinerRuntime` restores any encrypted Twitch session and schedules an
inventory refresh on its application coroutine scope. The refresh is deliberately asynchronous so
local process readiness does not depend on Twitch reachability.

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
bounded and coalesces queued idempotent lifecycle requests. Device authorization parses
`authorization_pending`, increases its polling cadence for `slow_down`, surfaces `access_denied` and
`expired_token` immediately without routing either terminal outcome through transient retry, and
rejects malformed/unknown replies. It retries genuinely transient
Twitch/network failures until the code expires, and a completed login immediately schedules inventory
loading. Ordinary login start is idempotent while a code is being
prepared or a still-valid code is being polled. A separate replacement command cancels and invalidates
that attempt before requesting a new code. Mining and refresh commands received during authorization
leave the displayed code, activation URL, and expiry intact.

The mining loop waits on state changes rather than polling blindly. Settings changes, channel-control
requests, refresh requests, higher-priority channel checks, and watch deadlines wake it through
coroutine selection. Watch heartbeats retain their own cadence, so a settings change or priority check
does not accidentally emit an extra heartbeat. Idle waits also include the earliest future campaign or
drop start and pending claim-retry deadline; active waits include the current campaign and drop ends. A boundary wake
re-evaluates cached lifecycle dates and refreshes inventory as needed, so later scheduled drops do not
wait for the hourly refresh and do not cause busy polling. Independent Twitch detail/channel lookups use
a fixed-size sliding worker pool, so a slow early lookup does not hold all later candidates behind a
fixed batch and large inventories cannot create one suspended coroutine per candidate. Campaign detail
requests are skipped when summary/inventory fields are already sufficient. The shared HTTP client also
bounds each complete upstream call, including redirects and response-body reads, to two minutes.

Automatic selection exhausts linked work before unlinked work by default: linked claimed-progress,
linked viewing-progress, linked fresh, unlinked claimed-progress, unlinked viewing-progress, then
unlinked fresh. User-saved ordering remains authoritative. Both campaign and drop start/end windows
must be open before work is watchable, including when the drop omits its own end. Completed or
claimable drops remain claim candidates after their watch window.
Within the prioritized-game group, promotion checks only games earlier than the current game in the
saved order; fallback work still checks every higher fallback group. Promotion results are revalidated
against the latest settings before they commit.

Unlinked attempts use a short speculative validation window, but the first progress increase no longer
disables supervision. Both linked and confirmed-unlinked work continue through a longer sustained-stall
watchdog. Unavailable or mismatched progress data is not evidence of a stall. A confirmed stall first
renews cached watch configuration, then abandons the channel if a fresh configuration also stalls. The
normal selector tries another channel for the same campaign, the next campaign in the same group, and
only then the next fallback group. Existing channel cooldowns prevent rapid cycling. If Twitch reports
progress for a different known campaign, the runtime follows that authoritative campaign when settings
permit it.

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

`TwitchDropsMinerAndroid/` is reference material only. `.dockerignore` excludes the entire directory,
the root Gradle build has no path dependency on it, and Docker copies only root build files and
`src/`. Parity changes must be applied deliberately to each project; root builds must never sync,
generate, or write Android sources.

## State and commands

`GET /api/state` returns one redacted state document containing the runtime snapshot, settings, and
local logs. `GET /api/events` is a server-sent event stream of the same document. The access token,
device code secret, encryption key, and filesystem paths are never serialized.

Every route validates Host against `TWITCH_DROPS_TRUSTED_HOSTS` before routing. Mutations under
`/api/*` additionally require a `TWITCH_DROPS_TRUSTED_ORIGINS` Origin, the exact route method,
`application/json`, an object body no larger than 64 KiB, known fields, exact JSON primitive types,
and bounded values. Forwarded headers are ignored. Errors are stable JSON with an `error` field.

Persistence mutations share one server mutex and return HTTP 200 only after atomic local storage
succeeds. Commands whose Twitch/network work continues asynchronously return HTTP 202. An acknowledged
log clear holds the repository lock through deletion, so it cannot erase a later append. Long Twitch
calls run on coroutines without holding an HTTP connection open. The browser suppresses an identical
mutation while that command is in flight, and the runtime remains the final idempotency boundary.
`/api/auth/start` begins login idempotently;
`/api/auth/replace` explicitly invalidates the current device-code generation and requests a new code.

Direct execution listens on loopback by default. Compose explicitly uses a container-internal
`0.0.0.0` listener while retaining loopback host publication. Reverse proxies must configure external
trusted hosts and origins explicitly.

## Persistence

The Compose volume at `/data` is the only mutable application filesystem:

- settings are normalized before an atomic JSON replacement;
- saved game priorities and campaign exclusions are deduplicated, length-checked, and capped at 500
  entries each before persistence;
- the Twitch session is encrypted using AES-256-GCM;
- a random local key is created on first start unless an external key is supplied;
- logs are capped so unattended runs cannot grow the volume without bound.

Settings, session material, keys, and logs use owner-only permissions where POSIX supports them.
Readers enforce file-size limits and distinguish absent, loaded, corrupt, key-mismatched, and
unreadable states. Corrupt settings and locally keyed sessions are quarantined; an externally keyed
session is preserved on key mismatch. Startup exposes only bounded, redacted diagnostics. Log loading
reads a bounded tail, then rewrites within line, per-entry, and physical-size limits.

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

OAuth Authorization is restricted to fixed Twitch OAuth, GraphQL, and Spade destinations. Public
channel HTML and upstream-derived static configuration use minimal unauthenticated headers. Spade
watch events use the authenticated Twitch session headers required to attribute progress, but only
after the derived destination is verified as `https://spade.twitch.tv`. Same-origin loopback endpoint
injection is constructor-only for MockWebServer tests.

## Web client

The greenhouse palette is dark by default, with a browser-local light preference available from the
header. A same-origin pre-paint initializer applies a saved preference before the stylesheet renders,
avoiding a theme flash. The server checks for state changes every two seconds but serializes and sends
the full state document only when the snapshot, settings, or bounded logs change; idle connections
receive sparse keepalives. Event clients are capped, and excess tabs receive HTTP 503 and use the
polling fallback rather than creating unbounded virtual threads. The browser uses periodic state polling only when the event stream is
unavailable. State updates replace the current view only when its rendered markup changes, and the
page entrance animation is reserved for initial load and explicit navigation so updates do not
discard focus. Active drop and channel links are emitted only for validated HTTPS `twitch.tv`
destinations, and linked/unlinked campaign filters remain browser-local presentation state. Priorities
missing from a partial/current inventory are displayed as unavailable without being deleted. Mutable
HTML, JavaScript, and CSS use `no-cache`, preventing a stale client from crossing a state-schema upgrade.
The active-watch card exposes compatible live channel alternatives through the existing serialized
runtime command flow; the browser owns only the accessible loading, empty, and selection presentation.

The responsive breakpoints are:

- desktop: fixed left navigation and a wide content canvas;
- tablet: compact navigation with two-column content;
- mobile: top brand strip, bottom navigation, and single-column cards.

## Failure behavior

- Invalid Twitch tokens delete the persisted credential only after token validation or an authoritative
  Twitch GraphQL response confirms invalid credentials. Watch/configuration 401/403 results preserve
  the session and trigger configuration recovery.
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
