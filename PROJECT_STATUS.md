# Project Status

This file is the handoff checklist for the Docker/web edition. Keep it current when behavior or scope
changes.

## Implementation checklist

- [x] Root Gradle JVM application compiles its own platform-neutral miner core
- [x] JVM settings, encrypted session, log, and network adapters are durable and tested
- [x] Redacted JSON state API and server-sent event updates are available
- [x] Login, start/stop, refresh, priority, exclusion, channel, settings, log, and reset controls are wired
- [x] Existing Twitch sessions refresh inventory automatically after container startup
- [x] Runtime lifecycle commands are serialized and stale coroutine results are generation-guarded
- [x] Active inventory refreshes are coalesced and mining waits react to settings/control changes
- [x] Campaign/drop boundaries and claim cooldowns participate in serialized runtime scheduling
- [x] Saved priorities survive incomplete Twitch inventories and promote in their saved game order
- [x] Default Auto Mode exhausts claimed, viewing, and fresh linked work before unlinked work
- [x] Login start is idempotent and replacement codes use an explicit generation-invalidating command
- [x] Result-aware claim cooldowns reselect useful work and retry automatically without watch spam
- [x] Continuous confirmed-progress watchdogs drive unlinked and linked channel/campaign recovery
- [x] Every route enforces configured trusted Hosts; mutations enforce configured Origins and strict schemas
- [x] Direct JVM listening defaults to loopback and Compose separates internal listen from host publication
- [x] OAuth credentials are restricted to trusted Twitch endpoints and omitted from derived watch URLs
- [x] Watch/configuration rejection is separate from authoritative invalid-token expiry
- [x] Device authorization handles pending, slow-down, denial, expiry, malformed fields, and bounded bodies
- [x] Campaign/drop windows are evaluated dynamically and active waits include campaign expiry
- [x] Malformed inventories produce bounded diagnostics and preserve last known-good/partial data safely
- [x] Candidate failures continue safely and detail/channel lookup uses bounded sliding concurrency
- [x] Persistent API mutations are serialized and acknowledged only after successful local storage
- [x] Verbose logs emit bounded selection, heartbeat, progress, and retry diagnostics without credentials
- [x] Corrupt persistence is distinguished, preserved/quarantined, permission-hardened, and safely diagnosed
- [x] JVM reachability is advisory for proxy compatibility; actual HTTP outcomes remain authoritative
- [x] OAuth/GraphQL/HTML bodies, SSE clients, command queues, logs, and diagnostics are resource-bounded
- [x] Mutable web assets revalidate and invalid nonnumeric port configuration fails startup
- [x] Responsive soft-color glassmorphism UI covers overview, campaigns, activity, and settings
- [x] Dark mode is the default, with a persisted light-mode toggle and flash-free theme initialization
- [x] Active drop/channel Twitch links and linked/unlinked campaign filters are available
- [x] Dockerfile configures a non-root runtime compatible with a read-only root filesystem
- [x] Compose declares loopback binding, a named volume, restart policy, init, and health check
- [x] Gradle tests pass
- [x] `docker compose config` validates
- [x] Desktop and mobile layouts receive visual QA

## Verification record — 2026-08-12

- Required root `gradle clean test installDist --no-daemon`: passed with Gradle 9.5.1 on JDK 21.0.10
- Root Gradle suite: 77 tests passed, 0 failures, 0 errors, 0 skipped across 15 suites
- Coverage directly exercises WebServer methods/content type/body limits/malformed JSON/wrong types/
  unknown routes/Host/origin/schema errors, StateJson redaction, device OAuth outcomes, Spade/config
  rejection, authoritative invalid tokens, campaign mapping/partial preservation, time boundaries,
  claim retries/terminal eligibility, priority persistence, mutation ordering, bounded logs, corrupt
  persistence, proxy false negatives, offline recovery, candidate failover, and sliding concurrency
- Packaged distribution smoke on isolated `127.0.0.1:18773`: health and state returned HTTP 200; a
  persisted settings mutation returned HTTP 200; wrong-type and malformed bodies returned structured
  HTTP 400; untrusted Host and Origin returned structured HTTP 403; `app.js` returned `no-cache`;
  state exposed neither `accessToken` nor `deviceCode`; SSE delivered one state event and polling GET
  remained available
- JavaScript syntax checks passed for `app.js` and `theme-init.js`
- Browser QA at 1280×800 desktop and 390×844 mobile covered logged-out/empty, preparing/loading,
  displayed-code/replacement, expired/error, active, settings, and confirmation states. There were no
  console warnings/errors or horizontal overflow, all visible controls met 44px targets, reduced-motion
  CSS was present, focus showed a visible 3px ring, and unchanged SSE state retained focus/markup
- Docker validation/build could not run because the Docker CLI is not installed on this host
- Android reference audit: nested Git worktree remained clean at
  `dfd7d8c5316ff896c838301bd3c769c84aef8d15` after all root implementation and verification
- Default-order regression coverage confirms linked no-progress work precedes every unlinked group;
  persisted custom orders remain unchanged
- Auto Mode path QA at 1280×800 desktop and 390×844 mobile rendered the requested six groups in
  order with no horizontal overflow
- The isolated verification JVM was stopped and its disposable data directory removed
- Live Twitch authorization, earning telemetry, Spade delivery, progress, and claim behavior were not exercised

## Previous verification record — 2026-08-12

- Root Gradle suite: 18 tests passed, including duplicate-start idempotency, stale refresh rejection,
  invalid-token cleanup, active-refresh coalescing, confirmed-progress thresholds, drop time windows,
  certainty-aware fallback order, and stale watch-endpoint recovery
- Kotlin production and test compilation: passed on JDK 21 with Gradle 9.5.1
- Clean root `test installDist`: passed; the packaged service returned health `ok`, accepted a settings
  mutation with HTTP 202, persisted the normalized value, and exposed no `accessToken` field
- JavaScript syntax check: passed; desktop logged-out Overview and 390×844 mobile Overview/Settings
  rendered without console warnings, and a mobile settings mutation updated successfully
- Android reference audit: nested Git worktree remained clean after all root build and smoke work
- `docker compose config` was not rerun on this host because the Docker CLI is not installed; the last
  recorded Compose validation remains the 2026-08-10 result below
- The recommended default fallback order now keeps confirmed linked work ahead of speculative unlinked
  work; persisted custom orders remain unchanged
- Live Twitch authorization, telemetry, and Drops earning were not exercised; those remain dependent on
  an eligible real account and active campaign

## Verification record — 2026-08-10

- Root `gradle clean test installDist --no-daemon`: passed on JDK 21 with no Android path dependency
- Startup inventory regression: a restored Twitch session triggers one background inventory request;
  a logged-out startup makes no Twitch request
- Isolated root install distribution: started successfully and returned HTTP 200 from `/api/health`
- Inventory refresh smoke mutation: returned HTTP 202 and preserved the expected login-required state
  when no Twitch session was present
- Official builder tag `gradle:9.5.1-jdk21-alpine`: confirmed present in Docker Hub
- Android reference audit: nested Git worktree clean; full tree digest unchanged before/after root work
- JavaScript syntax check (`node --check`): passed
- Local health endpoint: HTTP 200 with security headers
- Settings mutation: HTTP 202 and persisted value observed in the next state response
- Cross-origin mutation: rejected with HTTP 403
- State redaction: no `accessToken` field observed
- Browser QA: 1280×800 desktop and 390×844 mobile; preview navigation/search/settings and the
  real logged-out state rendered without console errors
- Dark/light theme QA: both palettes rendered at 1280×800, the saved light preference survived
  reload, and dark mobile settings rendered at 390×844 without overflow or console errors
- Event-stream flicker regression: focus on a rendered action remained stable across multiple
  two-second state events, confirming unchanged markup was not replaced or reanimated
- Resource audit: the small-service JVM profile reduced measured idle working set from 100.3 MiB to
  73.8 MiB and thread count from 51 to 34 on the local JDK 21 host; the post-mutation smoke process
  remained at 78.1 MiB
- Built-image smoke test: the read-only, non-root container passed health and settings mutation checks
  at 55.1 MiB reported memory with an isolated Compose-style data volume
- Idle event-stream regression: one initial state document and no redundant state documents were sent
  during a seven-second unchanged-state sample, down from four full documents before the change
- Event delivery no longer retains the last serialized document per client; activity retention now
  matches the 100 entries exposed by the state API, and the Spade endpoint cache is capped at 64
- Browser polling is now a fallback for unavailable or failed event streams instead of running beside
  a healthy stream
- Link/filter QA: active drop and channel anchors resolve to validated HTTPS Twitch destinations;
  Linked returned two preview campaigns and Unlinked returned one at desktop and mobile widths
- Mobile campaign QA: the six-filter row scrolls within its card at 390×844 with no page overflow or
  browser console warnings
- Docker/Compose image rebuild: passed with the Gradle test suite in the isolated builder stage;
  `docker compose config` also resolved the default JVM profile and loopback port successfully

## Behavioral reference and isolation

The Android runtime remains the behavioral reference, but the root service owns a separate JVM core.
Docker and root Gradle builds do not access `TwitchDropsMinerAndroid/`. Changes to selection order,
fallback groups, watch intervals, channel failover, unlinked progress probing, or claims must be
reviewed for parity explicitly; never synchronize or modify the Android tree as a side effect of a
root build.

## Known external risks

- Twitch device login, private GraphQL hashes, watch telemetry, or claim response formats may change.
- Live behavior cannot be fully exercised without a real eligible Twitch account and active Drops
  campaign.
- Exposing the port beyond loopback requires an operator-provided authenticated TLS reverse proxy.

## Next candidates after parity

- Optional reverse-proxy Compose profile with documented authentication
- Export/import for non-secret settings
- Browser notifications for claim completion and session expiry
- Metrics endpoint that never exposes account or campaign names
