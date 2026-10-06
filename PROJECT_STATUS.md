# Project Status

This file is the handoff checklist for the Docker/web edition. Keep it current when behavior or scope
changes.

## Coherent experimental TV session path - 2026-10-05

Implemented from clean `main` at `27fe527`. The reported real-account malformed-progress response
was not captured. The reference-valid no-current-drop sentinel deterministically failed the old
parser; this change repairs that protocol case and the runtime semantics without claiming the
user's live symptom has been verified. Browser login remains the recommended default.

- [x] Strict CurrentDrop parser accepts explicit null and the exact empty-session sentinel, validates
  envelopes/field types/bounds, and correlates positive channel IDs. Absence, other-channel results,
  unknown IDs, unavailable responses and malformed responses have distinct outcomes. Unknown
  same-channel drops retain bounded inventory reconciliation.
- [x] Absence never changes confirmed minutes, claim state or confirmed-stall counters. A separate
  three-observation/five-minute probe uses an honest earning-not-confirmed reason and existing
  channel cooldown. Real confirmed stalls retain their recovery. Temporary progress errors appear
  locally; sustained errors escalate after three failures, with deduplicated recovery diagnostics.
- [x] Session capabilities derive from stored browser/TV/legacy identity, with an explicit redacted
  UI projection and separate operation health. TV Inventory uses the pinned TV hash and fixtures;
  browser Inventory remains unchanged. TV's transport guard still blocks gated discovery operations.
  Catalog transport remains anonymous and cannot authenticate a session or provide account evidence.
- [x] Ambiguous account drop identifiers are rejected. A confirmed completion followed by lagging
  Inventory retains historical progress as not fresh and waits for current claim evidence without
  a repeated watch/reload loop. Durable claim intent, prerequisite ordering, unknown account state,
  usable partial admission, and bounded public metadata retention remain in place.
- [x] TV method survives preparation, expiry, denial, consumed-code and downstream failure states.
  Issued-token validation clears the activation code instead of inviting reuse. Same-method retry,
  browser fallback, TV renewal copy, catalog source/time, unsupported Open Reward Campaigns, and
  progress/claim waiting states are visible. Unknown progress omits percentage/ETA promises.
- [x] Added protocol, runtime race/recovery, claim reconciliation, request-header/hash, public contract,
  and dashboard regressions. Updated Architecture, Security, README and Operations.

Verification actually performed:

- Root `gradle --no-daemon clean test installDist` passed in the repository's Docker build stage:
  **Gradle 9.5.1 / JDK 21, 250 JVM tests, zero failures/errors/skips**. The direct Windows attempt
  could not find JDK 21; this is Linux build-stage evidence, not a successful native Windows build.
- Complete Node suite: **51 passed**. Syntax checks passed for `app.js` and `app.test.cjs`.
- Affected runtime image `dockdrops:tv-session-check` built successfully using the passing test
  stage. Docker `desktop-linux` / Engine 29.8.2. A brief network-disabled, read-only, capability-
  dropped container with disposable tmpfs data passed the existing health command and remained
  running with zero restarts. The verification container was removed; no project Compose service,
  saved data, credentials, live login or mutation scenario was used.
- `git diff --check` passed. The separate ignored Android checkout remains clean at
  `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. No Android build, push, PR or deployment.
- Pinned `channels.rs` and `operations.rs` matched the local research files exactly at
  `c9c2c3a550625354ba162cb71ce31725ef537bae`. Reference source was read, never executed or vendored;
  independent Kotlin implementation and MIT licensing retained.

Remaining user-owned validation: fresh TV authorization; no-current-drop waiting; actual earning;
delayed claim evidence/claims; channel changes and failover; natural renewal and restart; browser
fallback; desktop/mobile, both themes, and keyboard/focus behavior. Event subscription compatibility,
public catalog coverage and real-account TV compatibility remain experimental. Synthetic checks and
packaging health do not establish those outcomes or confirm the original account symptom is fixed.

## TV inventory projection and partial admission repair - 2026-10-05

The reported **Twitch inventory is incomplete or malformed; TV credentials preserved** message
came from rejecting any per-record inventory diagnostic during TV admission. Inspection also found
that the adapter required public-catalog metadata fields from the differently shaped account
Inventory response. No real account response was captured, so the exact rejected field in the user's
session remains unverified. Read-only reference inspection of `ohne-b/twitch-drops-miner` at
`c9c2c3a550625354ba162cb71ce31725ef537bae` (`domain.rs`, `inventory.rs`, `catalog.rs`) and an anonymous
public-feed read informed the supported variants; implementation and synthetic fixtures are independent.

- [x] TV-only normalization supports optional status, game name fallback, omitted restriction flags
  with explicit channel lists, channel `name`/`login`, unknown reward types and unknown linkage.
  Claimed drops may omit watched minutes; unclaimed drops still require valid numeric progress.
- [x] Login/renewal accepts a correctly shaped empty inventory or at least one usable campaign.
  Malformed neighbors/history retain incomplete-data diagnostics; nonempty wholly unusable inventory
  and invalid response envelopes still reject replacement and preserve the saved credential.
- [x] Rejected campaign IDs remain excluded from catalog substitution. Missing prerequisites,
  malformed channel restrictions and invalid account state still fail parsing. Public metadata keeps
  its strict schema and cannot supply linkage/progress/claim evidence. No gated discovery calls added.
- [x] Fixed category/count diagnostics distinguish campaign metadata, campaign/drop account state,
  duplicate IDs and historical awards without logging raw fields, IDs or private responses.
- [x] Restricted public catalog channels now accept `name` as well as `login`. Browser flow unchanged.

Verification:

- Root `gradle test installDist`: **236 JVM tests passed**, zero failures/errors/skips; JDK 21 /
  Gradle 9.5.1 distribution built. Initial sandbox dependency resolution failed; the permitted run
  passed. Six new tests cover inventory projections, restrictions, nullable claimed progress, partial
  admission, wholly unusable rejection, bad award history and sanitized diagnostics. Existing
  credential-preservation, lifecycle, catalog isolation and claim tests passed.
- **48 Node tests passed**. No web scripts, state contract or visual layout changed.
- Docker `desktop-linux`, engine 29.8.2: `dockdrops:tv-inventory-fix-20261005` built once, including
  clean JVM tests/distribution. An isolated non-root, read-only container with no network/published
  ports and tmpfs data passed the existing app health command: running, healthy, zero restarts.
  The disposable container was stopped and removed; health confirms local readiness only.
- Complete task diff review and `git diff --check` passed.
- Baseline was clean `main` at `15d75ed`. Optional Android checkout remains clean at
  `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. Saved data, credentials and running services untouched.

Remaining user checks: rebuild/restart the app and request a fresh TV login code; confirm admission,
catalog coverage and partial-data messages, natural renewal, confirmed earning, restricted-channel
selection/failover and claims. Synthetic checks cannot establish that this real-account failure is
resolved. No live account, browser visual, deployment or push work was performed.

## TV public catalog discovery - 2026-10-05

This supersedes the direct Campaigns requirement in the earlier TV implementation/expiry records.
Root baseline was clean `main` at `7ff70d4`. The optional Android checkout was clean at
`dfd7d8c5316ff896c838301bd3c769c84aef8d15` and remains unchanged.

Reference inspection used current upstream HEAD
[`c9c2c3a550625354ba162cb71ce31725ef537bae`](https://github.com/ohne-b/twitch-drops-miner/tree/c9c2c3a550625354ba162cb71ce31725ef537bae),
specifically `src/twitch/catalog.rs`, `inventory.rs`, endpoint definitions and its PolyForm
Noncommercial license. An anonymous read of the live SunkwiBOT `/v2/drops` feed confirmed the grouped
`lastUpdatedAt` / `data[].rewards[]` schema. These are behavioral/protocol references only; no upstream
implementation was copied or added as a dependency. This repository's license remains unchanged.

- [x] TV admission/rotation validates OAuth client/account identity and usable Twitch account
  inventory before atomic encrypted replacement. It does not depend on catalog availability.
- [x] TV login, renewal, refresh, mining and claim recovery never request gated Twitch campaign
  list/details. Browser login and direct discovery retain their existing route.
- [x] Separate anonymous catalog transport has no session input or shared Twitch credentials,
  headers, cookies or interceptors. Requests, body size, concurrency, cache and freshness are bounded.
- [x] Catalog metadata joins Twitch account inventory. Public account fields are discarded;
  unknown linkage/progress are explicit. Twitch alone supplies progress, linkage and claim evidence.
- [x] Priorities, reward filters, prerequisites, subscription exclusion, time windows, restricted
  channels, failover and progress supervision apply to catalog discoveries. Missing claim evidence
  prompts inventory recovery; TV never synthesizes claim IDs. Pending history uses Twitch evidence.
- [x] Stale/malformed/partial/unavailable catalog responses preserve bounded known metadata and
  fresh Twitch inventory, with persistent incomplete-data diagnostics. Rejected account records
  cannot be replaced with public account assumptions or mined from retained stale state.
- [x] README, architecture/state contract, security boundary and operator troubleshooting updated.

Verification:

- Root `gradle test installDist`: **230 JVM tests passed**, zero skipped/failures/errors; distribution
  built using existing repository-local JDK 21 and Gradle 9.5.1. Sandbox dependency resolution and
  default JDK discovery initially failed; the permitted run with the existing JDK succeeded.
- **48 Node tests passed**; `node --check src/main/resources/web/app.js` passed. New rendering and
  serializer checks cover incomplete discovery, escaped diagnostics, unknown linkage/progress and
  redacted claim IDs. Existing browser acceptance/discovery and lifecycle race checks remain green.
- Thirteen new catalog tests cover routing/admission, parsing/merging, forged public state,
  credential/header isolation, caching, body bounds, redirects, freshness, partial/duplicate data,
  filters/prerequisites, restricted channels, pending claims and malformed progress. A new runtime
  test verifies rejected renewed inventory preserves the previous encrypted session.
- Docker `desktop-linux`, engine **29.8.2**: `dockdrops:tv-catalog-20261005` built once, including
  clean root JVM tests/distribution. A disposable no-network/no-published-port container with
  non-root user, read-only root, dropped capabilities and tmpfs data reported **running, healthy,
  zero restarts** through the existing app health command. It was stopped and removed.
- Complete task diff/markup review and `git diff --check` passed; Android status/HEAD matched the
  baseline. Saved data, credentials, ignored research artifacts and running services were untouched.

Remaining user-owned checks: real TV authorization and usable inventory; catalog discovery coverage;
natural token renewal and restart recovery; confirmed earning, priorities/filters/prerequisites,
restricted-channel discovery and failover; successful claims and interrupted-claim reconciliation.
Manually review desktop/mobile, dark/light themes, unknown/incomplete/error states, and keyboard/focus
behavior. No real-account or browser visual testing was performed for this change. Docker health is
local readiness only. The feed may omit account-specific campaigns; cache is memory-only and a
restart during an outage can only rediscover account inventory. Display-only Open Reward Campaigns
remain unavailable for TV because their endpoint belongs to the direct discovery flow.
No push or deployment was performed.

## Experimental TV token expiry repair - 2026-10-05

The user reported `Invalid TV token expiry. Retrying in 15s.` after authorizing a TV code,
followed by `Twitch token polling returned an unsupported OAuth [redacted]`. Inspection found
that login and renewal required an integer expiry between one second and one year, rejected
unspecified lifetimes, and retried the device-code exchange after rejecting its successful reply.
The second message was fixed diagnostic text altered by credential redaction; it did not reveal
Twitch's underlying rejection. The exact live expiry value and rejection were not captured.

- Login and renewal share optional lifetime parsing: omitted/null/zero means no advertised
  deadline; positive 64-bit seconds are bounded to a representable millisecond delay. Negative,
  fractional, malformed, and overflowing values remain rejected. No fallback lifetime is invented.
- No-deadline sessions retain OAuth identity and direct Inventory/Campaigns acceptance checks,
  encrypted refresh credentials, and authoritative invalid-token recovery. They do not rotate
  every second after restore or after receiving another no-deadline token.
- An unusable successful exchange ends the attempt and requests a new code instead of polling
  a possibly consumed code. Unknown rejections use readable fixed text and HTTP status;
  `invalid_grant` requests a new code. Redaction remains intact and no raw replies are exposed.
- Reference inspection was read-only: `ohne-b/twitch-drops-miner` at `c9c2c3a...` uses
  `twitch_oauth2` 0.17.1, whose response lifetime is optional. The implementation remains independent.

Verification:

- Root `gradle test installDist`: **215 JVM tests passed**, distribution built with JDK 21 and
  Gradle 9.5.1. The sandbox could not resolve the Kotlin plugin; the permitted run passed.
  One new invalidation fixture initially failed before reaching validation and was corrected;
  the final complete suite passed with no failures or skipped tests.
- Node regression suite: **47 passed**. No dashboard markup/styles/scripts changed.
- New regressions cover optional/zero/long lifetimes, encrypted round trips, malformed exchange
  termination, redaction-safe errors, saved-session preservation, no renewal loop, and recovery
  after authoritative invalidation. Existing reset and replacement protections remain covered.
- Docker `desktop-linux`, engine 29.8.2: `dockdrops:tv-expiry-fix-20261005` built successfully,
  including clean JVM tests/distribution in the image build. A disposable container with no network,
  read-only root, dropped capabilities, and tmpfs data reported **running, healthy, zero restarts**
  with the existing health command. The container was removed; no running user service was changed.
- Complete task diff and `git diff --check` passed. Root baseline was clean at `ff64e89`;
  the optional Android checkout remains clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.

Live limitation: these synthetic checks do not establish that the reported real-account login now
completes. Rebuild the app and authorize a **new** TV code; the old one may have been consumed.
Direct Inventory/Campaigns acceptance, renewal, earning, and claims remain user-owned live checks.
No account login, mining, claim, push, or deployment was attempted during this repair.

## Six improvements - 2026-10-05

Baseline inspection confirmed clean root `main` at `9aef73f`, and a clean optional Android checkout
at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. The reference's current HEAD was still
[`c9c2c3a550625354ba162cb71ce31725ef537bae`](https://github.com/ohne-b/twitch-drops-miner/commit/c9c2c3a550625354ba162cb71ce31725ef537bae).
Its `src/twitch/oauth.rs`, `mod.rs`, `pubsub.rs`, and `LICENSE.md` were inspected read-only.
The reference uses PolyForm Noncommercial. These changes independently implement behavior and
protocol integration; no reference implementation was copied or added as a dependency. This project
remains MIT. No external SunkwiBOT campaign catalog, publishing automation, deployment, or branch
refresh was added.

- [x] **1 — Alternative login:** optional, visibly experimental Android TV device authorization,
  separate client/device identity, encrypted refresh token and expiry, same-account validation,
  atomic rotation, generation guards, bounded throttle retry, and conservative ambiguous-exchange
  handling. Browser remains the default. Acceptance requires direct Twitch Inventory and Campaigns;
  the reference's external catalog does not establish compatibility with these operations.
- [x] **2 — Claim history:** bounded account-scoped confirmed/pending records, intent before claims,
  authoritative restart reconciliation, preserved cooldowns, corruption preservation, and dashboard
  loading/empty/error/retry states. Ambiguous requests are not blindly replayed. Incomplete benefit
  evidence no longer marks a multi-benefit reward claimed.
- [x] **4 — Event updates:** server-side account/current-channel subscriptions for progress, claim
  availability, offline and broadcast changes. Bounded/coalesced notifications request authoritative
  refresh through the runtime. Polling, reconnect backoff, acknowledgement/heartbeat checks,
  duplicate handling, and lifecycle cancellation remain in control.
- [x] **5 — Individual filters:** saved distribution-type selection and literal case-insensitive
  reward-name exclusions across settings/API/UI/selection. Defaults allow all; required shared
  prerequisites survive type filtering, explicit name exclusions block dependents, and Open Reward
  Campaigns remain display-only.
- [x] **6 — Dependencies:** missing/duplicate IDs, cycles/depth bounds, expired/non-watchable or
  impossible-window/insufficient-watch-time prerequisites, claimed prerequisites, and overlapping/shared branches have
  explicit eligibility reasons. Unknown prerequisites no longer unlock rewards. Priorities remain
  independent of the inventory.
- [x] **7 — Browser/accessibility:** Playwright + axe development-only suite with isolated JVM data,
  synthetic account/history fixtures, external browser traffic blocked, both themes and desktop/
  mobile layouts, keyboard/dialog/focus checks, persistence/schema checks, login fixture states,
  filters/reasons and history states. Fixed fixture time and reduced motion make checks deterministic.
  Light-mode muted/status contrast was corrected using the existing design tokens.

Verification on this change:

- `gradle test installDist`: **210 JVM tests passed**, distribution built with the existing JDK 21.
  The initial restricted run could not resolve dependencies; the permitted build used the existing
  Gradle 9.5.1 distribution and JDK 21. No machine configuration or Android build inputs changed.
- `node --test src/test/js/*.cjs src/test/js/*.mjs`: **47 passed**.
- `npm run test:browser`: **24 passed** in Chromium across four viewport/theme projects, including
  axe WCAG A/AA checks. These are synthetic/local checks, not real-account evidence.
- Syntax checks passed for changed web JavaScript, Playwright config, test launcher, and browser tests.
- Base and browser-merged Compose configurations passed quiet validation. Docker context was
  `desktop-linux`, engine `29.8.2`. The final app image `dockdrops:review-20261005` built successfully
  (including clean JVM tests/distribution inside Docker). A brief isolated startup with disposable
  tmpfs data, no network, read-only root and existing health command reported **running, healthy,
  zero restarts**; the task container was removed. No live service or saved session was touched.
- Complete diff/whitespace review and Android baseline comparison performed; ignored credentials,
  saved data, optional checkout, and pre-existing generated research artifacts are preserved.

Remaining live/manual verification:

- Android TV's direct Campaigns/Inventory acceptance, discovery coverage, earning, claims, naturally
  scheduled refresh-token rotation and restart recovery have **not** been tested with a real account.
  Missing direct-Twitch compatibility rejects acceptance; no checks were weakened to substitute a
  catalog or OAuth-only success. Keep Chromium available. An ambiguous refresh or crash between
  upstream rotation and local save may require reconnecting.
- Private event subscription acceptance/delivery, actual faster progress/claim/offline handling and
  reconnect behavior on Twitch need live verification. Polling remains the fallback.
- Real interrupted-claim reconciliation and all natural earning/failover/claim flows remain
  user-owned. History dates are local first-confirmation times; this is not exactly-once delivery.
- Manual screen-reader navigation, zoom, touch ergonomics, focus in real states and both themes still
  need review. Automated axe scans cannot prove full accessibility conformance.

## Verification scope - 2026-10-05

- Routine Docker Desktop verification is limited to affected image builds and brief isolated
  container startup, including the existing app health check. Deeper container scenarios and offline
  Chromium smoke tests are opt-in when the user requests them.
- The user handles manual UI and live Twitch testing, including Finish sign-in, renewal, earning,
  failover, and claims. Build/startup and synthetic test results do not confirm those flows or resolve
  the reported login failure. Earlier verification records remain historical evidence only.
- This instruction-only update was reviewed with `git diff --check`; no application builds or
  container tests were run.

## Implementation checklist

- [x] Published `deployment` branch keeps server build/runtime inputs and excludes environment files
- [x] Compact Overview/Settings account card shows the signed-in username, ID, and sign-in method
- [x] Public username survives encrypted-session restore, with legacy ID fallback and guarded enrichment
- [x] Device polling accepts Twitch message-based replies and keeps transient HTTP failures retryable
- [x] OAuth validation checks client/account identity and preserves credentials on inconclusive rejection
- [x] Pure GraphQL authentication/integrity errors trigger validation without replaying claims
- [x] Special Events/IRL ACL participants can earn across categories through discovery and live rechecks
- [x] Desktop browser login, complete integrity context, protected pairing, and helper-driven renewal
- [x] Optional isolated Docker browser service and dashboard login chooser with desktop fallback
- [x] Recoverable Chromium response-body capture failures no longer abort the desktop helper
- [x] Dashboard Finish observes proof before login and preserves the authenticated browser through capture
- [x] Encrypted scoped SDK seed, independent browser issuance, restart recovery and bounded renewal retries
- [x] Linux container capture/navigation and renewal verified with offline synthetic Chromium traffic
- [x] Live Twitch dashboard login and authenticated campaign loading on Linux Docker
- [ ] Live Twitch verification of unattended browser renewal, earning, and claims
- [x] Compact reward companion panel sits beside desktop campaigns and below the list on smaller screens
- [x] Open Reward Campaigns are visible in a separate Show/Hide panel with dates and reward names
- [x] Reward listings refresh with inventory without entering the mining selector or claim runtime
- [x] Campaign names link to their Twitch campaign from Campaigns, Now watching, and Up next
- [x] Category search shares validated request rules across routing, transport, and serialization
- [x] On-demand public Twitch category search finds games without campaigns or a saved login
- [x] Four-character category searches browse all available matches in cached pages; short searches retain 12 results
- [x] Independent category priority editor supports exact-name entry, scoped search, and numeric reordering
- [x] Returning campaigns inherit saved category order; known starts wake active promotion checks
- [x] In-flight promotion lookups cannot busy-loop on an overdue timer
- [x] Server-ranked Up next respects exclusions, eligibility, and custom fallback settings
- [x] Campaign sorting/paging, collapsible priority editor, and live-update focus preservation are available
- [x] Full priority lists reject additions without silently displacing saved games
- [x] Root Gradle JVM application compiles its own platform-neutral miner core
- [x] Root Git history is initialized; the Android reference remains a separate, untracked repository
- [x] Future-agent instructions require status/diff checks, focused commits, and Android-tree isolation
- [x] JVM settings, encrypted session, log, and network adapters are durable and tested
- [x] Redacted JSON state API and server-sent event updates are available
- [x] Login, start/stop, refresh, priority, exclusion, channel, settings, log, and reset controls are wired
- [x] Existing Twitch sessions refresh inventory or resume requested mining after container startup
- [x] Explicit Start/Stop mining intent survives restart and re-login while reset/shutdown scopes remain distinct
- [x] Active mining revalidates access tokens hourly on inventory reloads
- [x] Active channels are rechecked every three minutes for offline, category, and broadcast changes
- [x] Runtime lifecycle commands are serialized and stale coroutine results are generation-guarded
- [x] Active inventory refreshes are coalesced and mining waits react to settings/control changes
- [x] Unknown Twitch drops trigger an immediate inventory refresh with a five-minute retry cooldown
- [x] Campaign/drop boundaries and claim cooldowns participate in serialized runtime scheduling
- [x] Saved priorities survive incomplete Twitch inventories and promote in their saved game order
- [x] Default Auto Mode exhausts claimed, viewing, and fresh linked work before unlinked work
- [x] Fallback to other games is enabled by default while an explicitly saved off choice is preserved
- [x] Login start is idempotent and replacement codes use an explicit generation-invalidating command
- [x] Result-aware claim cooldowns reselect useful work and retry automatically without watch spam
- [x] Continuous confirmed-progress watchdogs drive unlinked and linked channel/campaign recovery
- [x] Every route enforces configured trusted Hosts; mutations enforce configured Origins and strict schemas
- [x] Direct JVM listening defaults to loopback and Compose separates internal listen from host publication
- [x] The example environment enables private-LAN access with same-origin Host/Origin enforcement
- [x] OAuth credentials are restricted to trusted Twitch endpoints; Spade watch events retain session attribution
- [x] Watch earning uses fresh direct-Spade form posts with canonical channel/stream/game/user attribution
- [x] Watch/configuration rejection is separate from authoritative invalid-token expiry
- [x] Device authorization handles pending, slow-down, denial, expiry, malformed fields, and bounded bodies
- [x] Campaign/drop windows are evaluated dynamically and active waits include campaign expiry
- [x] Malformed inventories produce bounded diagnostics and preserve last known-good/partial data safely
- [x] Candidate failures continue safely and detail/channel lookup uses bounded sliding concurrency
- [x] Upstream calls have a whole-call timeout and large lookup sets use a fixed worker pool
- [x] Persistent API mutations are serialized and acknowledged only after successful local storage
- [x] Verbose logs emit bounded selection, heartbeat, progress, and retry diagnostics without credentials
- [x] Corrupt persistence is distinguished, preserved/quarantined, permission-hardened, and safely diagnosed
- [x] JVM reachability is advisory for proxy compatibility; actual HTTP outcomes remain authoritative
- [x] OAuth/GraphQL/HTML bodies, SSE clients, command queues, logs, and diagnostics are resource-bounded
- [x] Mutable web assets revalidate and invalid nonnumeric port configuration fails startup
- [x] Responsive flat Twitch-purple dashboard covers overview, campaigns, activity, and settings
- [x] Header Start/Stop and status pill, hash-addressed views, offline banner, and expandable drop/reward
  lists with campaign end dates are available
- [x] General-user README showcases the app and routes operational detail to a dedicated guide
- [x] Dark mode is the default, with a persisted light-mode toggle and flash-free theme initialization
- [x] Active drop/channel Twitch links and linked/unlinked campaign filters are available
- [x] Compatible-channel loading, empty, refresh, and manual-selection states are available
- [x] Navigation/filter semantics, visible focus, and 44px touch targets cover desktop and mobile controls
- [x] Dockerfile configures a non-root runtime compatible with a read-only root filesystem
- [x] Compose declares loopback binding, a named volume, restart policy, init, and health check
- [x] Gradle tests pass
- [x] `docker compose config` validates
- [x] Desktop and mobile layouts receive visual QA

## Explicit live Docker login and renewal check - 2026-10-05

- The user explicitly requested a real-account test and supplied the email verification code.
  Tested commit `e782da6` in disposable Linux Docker app/browser containers on loopback port 18080;
  both used the existing non-root/read-only/capability restrictions, with session data and browser
  profiles on tmpfs. No existing user session, volume, or deployment was modified.
- Twitch email verification and **Finish sign-in** succeeded. The JVM accepted the account and
  protected Drops endpoints, loaded **105 campaigns**, and reported retained **Docker browser renewal**.
  Mining remained stopped and public state had no error. This exercises the no-SDK-seed fallback;
  it does not establish the underlying SDK-cookie failure's cause or SDK restart recovery.
- A temporary credential-free Java probe ran inside the app container, using production
  `SecureSessionStore`, `BrowserRenewalClient.renew` and `TwitchApiClient.validateBrowserContext`.
  One explicitly triggered renewal rotated the proof, advanced expiry from **09:41:34 UTC** to
  **09:45:35 UTC**, preserved account/token/device identity, and passed Inventory and Campaigns
  verification. The probe did not save its returned context or alter the runtime timer; the normal
  timer was still about 51 minutes away. Credentials stayed inside the test containers.
- Corrected the login chooser/viewer's unconditional restart-recovery wording. Both now direct
  users to Settings for the active mode's restart behavior. No runtime or state-contract change.
- Both image builds succeeded using cached build/test layers; these were not fresh JVM test runs.
  App health was healthy; both containers were running with zero restarts. The temporary probe
  compiled and passed, and both disposable containers were removed after the test.
- Verification of the copy changes: **47 Node tests passed**, JavaScript syntax and diff checks passed.
  The revised text still needs desktop/mobile layout, both-theme and keyboard/focus review.
- Unverified: natural timer-driven renewal and atomic replacement, repeated renewal/long-term
  operation, service-restart behavior, SDK-seeded mode, earning/claims, and Debian/Proxmox behavior.
  A successful manually triggered renewal does not close the unattended-renewal checklist item.
- Starting root tree clean on `main`; optional Android tree clean at
  `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. No push or deployment requested.

## Docker retained-browser renewal fallback - 2026-10-05

- The user reports the seed-preparation failure on the latest deployed build and confirms the desktop
  helper works. The exact SDK failure remains unknown; this message does not establish a container
  permission or networking defect. Compose already shares the app/browser network namespace.
- Rechecked current upstream [SDK issuance](https://github.com/rangermix/TwitchDropsMiner/blob/main/src/auth/server_renewal.py),
  [capture](https://github.com/rangermix/TwitchDropsMiner/blob/main/src/auth/session_helper.py),
  [authentication evidence](https://github.com/rangermix/TwitchDropsMiner/issues/118), CDP documentation
  through Context7, and [Twitch refresh-token requirements](https://dev.twitch.tv/docs/authentication/refresh-tokens/).
  Plain OAuth refresh is not a demonstrated replacement for the protected Drops integrity context.
- Finish no longer requires missing-cookie bootstrap. A present seed can still enable independently
  verified restart-capable renewal; missing cookies or failed SDK issuance fall back to the existing
  authenticated headed browser in Docker. The PC/helper is unnecessary for either Docker mode.
  Initial and renewed contexts still pass JVM account, Inventory and Campaigns verification.
- Added a mutually exclusive encrypted private browser lease, runtime-triggered renewal, bound
  OAuth/client/device identity, advancing proof checks, transfer release and lease revocation.
  Reset/replacement/authoritative expiry/orderly shutdown dispose the owned browser; Stop keeps it.
  Settings identifies the mode through existing account text. No public schema, extra port, volume,
  dependency, or container privilege was added.
- Retained profiles remain temporary. Browser restart or orderly app shutdown requires sign-in again;
  only SDK-seeded sessions support durable restart recovery. Transient errors preserve credentials,
  and a missing retained browser stops retrying with reconnect guidance.
- Linux image verification exposed an intermittent temporary-directory cleanup failure in the seed
  renewal test. Inspection found that renewal acceptance can enqueue Start/Refresh behind shutdown;
  those commands could start work or change saved intent after cleanup. Start/Refresh now refuse work
  once shutdown begins, with a regression covering unchanged intent and no inventory work.
- Verification after correction: root JDK 21 `test installDist` passes **190 JVM tests**, zero
  failures/errors/skips; **47 Node tests pass**, as do changed-script syntax and diff checks.
  Browser-enabled Compose validates; both images build, with the final app image passing its Linux
  clean test/distribution build. Brief disposable startup passes the existing app health check and
  browser running/zero-restart check. Test containers and the temporary diagnostic Dockerfile were
  removed. No live login, offline Chromium scenario, mutation, or endurance test was run.
- Manual/unverified: Finish sign-in on the user's latest deployment; renew past the original proof
  expiry with the PC off; both modes after service restart; earning/claims; desktop/mobile Settings
  copy in both themes and keyboard navigation. Synthetic evidence does not prove live Twitch acceptance.
- Starting root tree clean on `main`; Android clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.
  Local commit only; no push/deployment requested.

## Renewal seed browser profile correction - 2026-10-05

- The user's retry reached `seed_failed`: Drops evidence was captured, but cookie lookup/bootstrap
  did not complete. This narrows the failure to seed preparation; the exact SDK/browser failure was
  still hidden. It does not establish a container resource or Chromium-package problem.
- Found a likely cookie-policy conflict: `Target.createBrowserContext` creates Incognito storage,
  and Chrome blocks third-party cookies there by default. The SDK cookie host `k.twitchcdn.net` is
  cross-site from the `www.twitch.tv` bootstrap page. Sources: [CDP Target](https://chromedevtools.github.io/devtools-protocol/tot/Target/#method-createBrowserContext)
  and [Chrome cookie settings](https://support.google.com/chrome/answer/95647?hl=en).
- Missing-cookie bootstrap now uses a separate headed Chromium process with an empty regular
  temporary profile. The signed-in browser remains intact until seed preparation completes; no login
  cookie jar is copied. Cleanup, cancellation, independent issuance, and JVM account/Drops verification
  remain required. This briefly adds one browser process tree under the existing container limits;
  no image base, resource allowance, cookie-policy override or container privilege was changed.
- Added fixed, allowlisted SDK reasons for initialization/timeout, network fetch, rejected response,
  unusable cookie and unverifiable proof. Unknown errors still use generic safe messages. Tests cover
  the private worker and JVM public bridge to ensure these diagnostics cannot expose upstream secrets.
- Root JDK 21 `test installDist`: **184 tests passed**, no failures/errors/skips. Node suite:
  **43 tests passed**; changed-script syntax and diff checks passed. Both affected images built once;
  brief disposable container startup passed app health and browser-service running checks. Containers
  were removed. No saved user data, login attempt, mutation or endurance scenario was used.
- Corrected the optional offline bootstrap fixture to use a real `Set-Cookie` response rather than
  direct CDP cookie injection, which bypassed the policy at issue. That fixture was syntax-checked
  but **not executed**, following the current opt-in container-test policy. Earlier smoke results
  do not verify this behavior. Actual response-cookie acceptance and the user's Finish sign-in retry,
  renewal after expiry/restart, earning and claims remain manual and unverified for this change.
- Android reference unchanged and clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`; the root
  working tree was clean at task start. No push or deployment was performed.

## Finish sign-in missing-seed bootstrap - 2026-10-05

- Investigated the reported generic post-Finish failure. The worker mapped capture, cookie lookup,
  independent issuance and acceptance timeout to the same `capture_failed` message, so that report
  alone cannot identify the user's failing stage. Found a concrete gap against upstream
  `src/auth/session_helper.py` (`BrowserExporter.capture_seed`): a valid captured browser session
  without the scoped SDK cookie was rejected rather than bootstrapped.
- Finish now obtains missing seed material in an empty, disposable context of the same headed
  browser before closing it. No interactive cookies are copied or cleared. The context is removed
  on success, failure or disconnect. Independent headless issuance and JVM identity/Inventory/Campaigns
  verification remain mandatory; no capture/verification check was removed. Cancelled bootstrap or
  issuance cannot publish a late context. Browser failures now identify the stage using fixed messages.
- Root JDK 21 `test installDist`: **184 tests passed**, no failures/errors/skips. Node suite:
  **41 tests passed**; changed-script syntax and `git diff --check` passed. JVM coverage exercises the
  actual login bridge with private mock-worker failures and checks public redaction and owned cleanup.
- Both Docker images built. **Three offline Chromium tests passed** with networking disabled,
  including missing-cookie bootstrap in real headed Chromium, storage isolation, cleanup after
  success/failure/cancellation, and fresh-profile renewal. Disposable loopback Docker health and
  settings mutation/readback passed with tmpfs data; no saved user session was used.
- **Unverified:** the reported real-account failure and Twitch acceptance after this repair, ongoing
  renewal, earning/claims and Debian/Proxmox execution. This fixes a reproduced code path, not a
  confirmed diagnosis from private server diagnostics. A live sign-in retry is still required.
- Android reference unchanged at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`; untracked `CLAUDE.md`
  preserved. Changes are local; neither `main` nor `deployment` was pushed or deployed.

## Durable Docker browser renewal - 2026-10-05

- Investigated sessions lasting around one to two hours. The old companion depended on a live page
  yielding a new proof within a two-minute capture window starting only 90 seconds before expiry;
  any capture, verification or transport failure ended renewal. This is a code-level failure mode,
  not a confirmed diagnosis of the user's particular sessions (no private runtime logs were read).
- Rechecked rangermix/TwitchDropsMiner main at `1182d0172458db4e23a236e9d9c078fd3b1fd9c7` and its
  `server_seed.py`, `server_renewal.py`, authentication guide and issue #118. Adapted its scoped
  `KP_UIDz-ssn` seed and independent SDK issuance; no client-ID switch or direct HTTP-only token
  refresh. CDP cookie/interception API use was checked against official documentation via Context7.
- Finish captures in the signed-in browser, reads only the scoped secure/HttpOnly SDK cookie, and
  proves fresh issuance in a separate temporary browser before JVM account/Inventory/Campaigns
  acceptance. The seed stays in the existing AES-GCM session envelope. No persistent Chromium
  profile, browser volume, additional published port, or public state field was added.
- Runtime-owned renewal starts five minutes before expiry, restores from encrypted state at startup,
  validates the same account without interrupting current mining, and atomically installs successful
  replacements. Temporary failures retry with 15-second to five-minute backoff while the seed is
  valid. Reset/replacement/shutdown invalidate late results; Stop retains renewal without restarting
  mining. A new JVM can replace an orphaned renewal but cannot replace an interactive login.
- Root JDK 21 `test installDist`: **183 tests passed**, no failures/errors/skips. Node suite:
  **38 tests passed**; changed-script syntax checks passed. Coverage includes expired-proof restart,
  encrypted cookie rotation, transient retry, account mismatch, Stop, reset/replacement races,
  scoped-cookie validation, uncached issuance correlation, private transport and stale worker IDs.
- Both Compose configurations validate and both Linux/amd64 images build; the app image runs root
  `clean test installDist`. The offline Linux/Xvfb Chromium test passes headed capture and two SDK
  rotations across fresh headless profiles with Docker networking disabled and synthetic credentials.
- Disposable containers on loopback 18085 pass health and a settings mutation/readback. The browser
  starts through the dashboard; Finish without credentials enters verification, and stopping the test
  companion produces a safe failure message. Non-root/read-only/drop-all/no-new-privileges protections
  remain intact. Desktop/mobile sign-in guidance was inspected in both themes with keyboard navigation.
- **Not yet verified:** live Twitch acceptance of this new seed bootstrap, unattended multi-hour/day
  renewal, earning/claims or the user's Debian/Proxmox host. Offline fixtures do not prove Twitch will
  accept the SDK flow. Twitch challenges, OAuth revocation or downtime past seed expiry can still
  require login. Old dashboard sessions require one fresh sign-in to obtain the seed. The lightweight
  desktop helper retains its existing keep-running/reconnect requirements.
- Android reference remains unchanged at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`; pre-existing
  untracked `CLAUDE.md` is preserved. This change does not publish or refresh `deployment`.

## Signed-in account overview - 2026-10-04

- Overview and Settings show the viewer's Twitch username, numeric account ID, sign-in method,
  and an initials badge. Overview links to account settings through the existing view navigation.
  The card disappears during sign-out/replacement login and labels illustrative preview accounts.
- Username comes from Twitch's existing OAuth validation `login` field, checked against the official
  validation documentation through Context7. New browser/device sessions store it in the encrypted
  envelope. Legacy sessions remain readable, show an ID fallback, and enrich their display after
  inventory loading or mining validation. Delayed results cannot restore a reset account.
- Root Gradle `test installDist`: **175 tests passed**, zero failures/errors. Client suite:
  **32 tests passed**; JavaScript syntax and `git diff --check` passed. Coverage includes legacy
  persistence, validation parsing, public serialization, identity escaping, and reset races.
- Disposable loopback host: health returned OK; a settings mutation returned 200 and its saved value
  appeared in state. Desktop 1440px and mobile 390px visual checks covered Overview/Settings,
  dark/light themes, and logged-out, preparing, and failed-login states; no mobile overflow or browser
  errors were observed. The Android reference stayed clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.
- Live Twitch username verification was not performed for this change. A missing or temporarily
  unavailable username leaves the numeric-ID fallback; no profile-picture fetch is implemented.

## Finish sign-in capture repair - 2026-10-04

- Investigated the report that Twitch accepts login but Finish never reaches Connected. The old
  worker closed the authenticated browser and started headless Chromium. It also began observing
  issuance only after login. The revised worker observes before login and keeps that same headed
  Chromium, device context and user agent through Finish and renewal. The native helper also uses
  regular browser capture with a nonzero debugging port, consistent with upstream's current helper.
- Compared rangermix's `src/auth/login_helper.py`, `session_helper.py`, `browser_session.py`, and
  [auth investigation #118](https://github.com/rangermix/TwitchDropsMiner/issues/118), plus the original
  DevilXD project and Twitch's OAuth documentation. OAuth success alone is insufficient evidence of
  private campaign access; the existing independent account/Inventory/Campaigns checks remain required.
- The long-lived observer filters out unrelated assets, telemetry and preflights, clears unsuccessful
  completed requests, bounds recent evidence, retries campaign navigation, and accepts only a different
  proof for renewal. Capture times out after two minutes with fixed retry guidance. The interactive
  eight-minute timeout no longer controls the accepted browser's lifetime. No public state schema changed.
- Root `test installDist` with the repository-local JDK 21: **170 tests passed**, no failures/errors.
  Node regression suite: **30 tests passed**, including pre-Finish proof, bounded traffic, renewal,
  cancellation and server acceptance. The Linux/Xvfb offline integration test passes both initial
  capture and distinct proof renewal through actual Chromium/CDP with networking disabled.
- Both Compose images build successfully on Docker Desktop's Linux/amd64 engine; the app image runs
  root `clean test installDist`. Isolated Compose deployment on loopback port 18080 passes health,
  dashboard Start/Finish mutations, and the missing-login capture timeout. Non-root/read-only/drop-all/
  no-new-privileges protections are retained. The test volume is separate from operator data.
- Operator documentation now includes Debian/Proxmox LXC prerequisites and a reproducible offline
  browser test. The actual Proxmox host and unattended real-account renewal remain unverified.
- The Android reference remains clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.
- The user completed a real Twitch login in the isolated Linux Docker viewer. The public login
  status reached `ready`, runtime account state was `loggedin` with browser authentication, and
  **143 campaigns** loaded with no runtime error. Mining remained stopped. This verifies initial
  account/Inventory/Campaigns acceptance; unattended renewal, earning and claims were not exercised.

## Dashboard login and helper repair - 2026-10-04

- Re-reviewed upstream `1182d0172458db4e23a236e9d9c078fd3b1fd9c7`, including the container browser,
  session capture, and server renewal. The original generic **Browser command failed** message did
  not identify the failing CDP command. DockDrops now skips unrelated GraphQL response bodies and
  recoverable `Network.getResponseBody` failures (`-32000`), retains complete proof/campaign correlation,
  increases bounded capture buffers, and reports other command names/codes without raw diagnostics.
- Connect Twitch now offers dashboard login and the desktop helper. The optional
  `compose.browser.yaml` adds a Chromium/Xvfb/Node service with no data mount or extra published port.
  It retains non-root execution, dropped capabilities, read-only root, and no-new-privileges; the
  browser-only `--no-sandbox` tradeoff is documented in SECURITY.md. Login uses an allocated nonzero
  loopback CDP port, as upstream does, without navigator overrides.
- The same-origin screenshot viewer supports bounded text, clicks, navigation keys, scroll controls,
  zoom, Finish sign-in, cancellation, and a mobile keyboard field. Stale views cannot send input into
  a replacement login. Public state adds the `dashboard` login method; tickets, captured headers,
  cookies, and context never enter public state or companion status responses sent to the viewer.
- The JVM atomically owns the admission lease, validates every initial/renewed context through the
  existing runtime, and stops the browser after reset, replacement, cancellation, or transport failure.
  Only browser renewal runs in the companion; mining/claims stay in LocalMinerRuntime. The browser
  profile is temporary, so reconnection is required after JVM/browser restart or renewal failure.
- Root `test installDist`: 169 tests passed (zero failures/errors/skips), including two capture/renewal
  cycles through a mock companion, redacted status, lease revocation/reset, strict route/input bounds,
  and serialization. Node tests: 26 passed, covering capture recovery, safe diagnostics, browser-service
  request boundaries, input bounds, stale viewer/status races, and the login choices. JavaScript syntax checks passed.
- Native Windows Chrome checks passed for helper launch/control and the companion's screenshot,
  keyboard/click, cancellation, and temporary-profile cleanup. A packaged JVM plus native Chrome test
  companion displayed Twitch's logged-out login page through the real same-origin viewer. This is
  browser-control evidence, not successful account login or Linux/Xvfb container evidence.
- Desktop and mobile visual QA covers login choices, logged-out/startup, interactive viewer, service
  failure, and cancellation. Compose base/override configuration validation passed. Docker image
  build was attempted but the Docker Desktop Linux daemon is unavailable; image build/runtime,
  real-account login, browser renewal acceptance, earning, and claims remain unverified.
- Android reference unchanged at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. Pre-existing untracked
  `CLAUDE.md` is preserved.

## Browser login and Docker names - 2026-10-04 (superseded login presentation)

- Connect Twitch now pairs a bundled Node.js 22.4+ helper instead of requesting a device code from
  the endpoint returning HTTP 400. Native Chrome/Edge/Chromium performs login in a temporary profile;
  headless captures renew browser integrity proof while the helper stays running. The JVM checks web
  OAuth identity and both Drops queries before encrypted storage. Existing Android sessions load as
  before. This root-owned protocol follows upstream capture concepts with MIT attribution in the
  helper and `THIRD_PARTY_NOTICES.md`; it is not compatible with the upstream Python helper.
- One-use, expiring pairing codes issue a private helper ticket; renewal binds to the same account.
  Replacement/reset invalidate stale verification, preserve old credentials until successful save,
  and revoke prior helper ownership. Host/Origin checks, strict bounds, redacted state, and disabled
  redirects protect credential transfers. The account state adds only `method` for UI presentation.
- Image `dockdrops:local`, container `dockdrops`, and network `dockdrops` replace verbose generated
  names. Compose project/service identity and the existing `twitch-dock-drops-data` volume are retained.
- Root Gradle `test installDist`: 165 tests passed, zero failures/errors/skips. Nine new JVM tests cover
  context validation, Drops verification, encryption/redaction, pairing limits, redirects, HTTP routes,
  account binding, and reset races. Two coroutine test declarations were corrected to return Unit so
  JUnit actually discovers them. Rejected HTTP requests now close connections with unread bodies.
- All 18 Node tests and the JavaScript syntax check passed. Native Windows Chrome helper
  `--check-browser` passed for owned-profile launch, local CDP communication, and cleanup; this does
  not prove Twitch acceptance. The initial sandbox attempt could not open the browser socket; the
  authorized local-browser check passed.
- Packaged JVM smoke: health 200, browser pairing start 202, public account method `browser`, reset
  200. Browser QA covered real pairing, desktop 1440px, mobile 390px/320px, light/dark themes, and
  logged-out, pairing, verifying, error, and active previews. Checked mobile layouts have no horizontal
  overflow; no console warnings/errors were observed. Preview screenshots contain only demo data.
- Standalone Docker Compose 5.6.0 configuration validation passed. Image build was attempted but the
  Docker Desktop Linux daemon pipe is unavailable on this host; container build/runtime are unverified.
- The Android reference remains clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`; untracked
  `CLAUDE.md` is preserved. No live Twitch account login, renewal, earning, or claim was exercised.

## Upstream review - 2026-10-04 (initial compatibility port)

Reviewed [rangermix/TwitchDropsMiner at v2.1.1 / 1182d0172458](https://github.com/rangermix/TwitchDropsMiner/tree/1182d0172458),
including the September auth migration and October 4 helper restoration.

- [Android-session preservation](https://github.com/rangermix/TwitchDropsMiner/commit/ac82f0176b3d):
  retain the Android client and reject mismatched validated clients without deleting credentials.
  The earlier Smart TV switch from v1.3.1 was superseded; changing a client ID does not convert an
  existing token or establish private Drops access.
  Replacement authorization also retains the encrypted credential until successful atomic save;
  Start/refresh is blocked during authorization and explicit reset still clears the session.
- [v2.0 login migration](https://github.com/rangermix/TwitchDropsMiner/pull/124),
  [v2.1 container browser](https://github.com/rangermix/TwitchDropsMiner/pull/145), and
  [v2.1.1 desktop fallback](https://github.com/rangermix/TwitchDropsMiner/pull/154): reviewed but not
  ported in the initial compatibility commit; the browser-helper follow-up is recorded above.
  They require an interactive browser, integrity context capture, protected helper admission,
  server-side browser verification, and renewable credential storage. This patch preserves the
  independent non-root headless JVM deployment and does not claim equivalent new-login support.
- Adopted upstream's distinction between pure pre-execution GraphQL auth errors and partial results.
  HTTP-200 `invalid oauth token` / `failed integrity check` errors now validate OAuth before expiry;
  partial data is preserved and claims are not automatically replayed by the transport. This host
  retains its existing runtime retry/claim-cooldown ownership rather than adding another retry loop.
- The comparison also found the local device parser rejected Twitch's documented
  [`message: authorization_pending`](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow).
  Both `message` and OAuth `error` forms now work; 429/5xx remains transient, invalid device codes and
  device-request 4xx rejections other than 429 are terminal, and upstream error text/JSON parser
  excerpts do not enter OAuth diagnostics.
  [Token validation](https://dev.twitch.tv/docs/authentication/validate-tokens/) uses HTTP 401 as the
  authoritative invalid-token signal; 403 and malformed/client-mismatched results preserve storage.
- Ported the [Special Events/IRL eligibility fix](https://github.com/rangermix/TwitchDropsMiner/pull/105)
  using category IDs and explicit channel ACL membership. Discovery, the compatible-channel picker,
  and live rechecks share the exception; actual stream category attribution remains intact. Ordinary
  campaigns retain category matching. Campaign category IDs are server-only; the browser state schema
  is unchanged.
- Direct Spade delivery, fresh heartbeat payloads, prerequisite-aware drops, and manual category
  priority ordering already exist here. Upstream GUI, Telegram, drop-history/export, and dashboard
  password features were not copied as part of these compatibility fixes.

### Verification

- Root Gradle 9.5.1 / repository-local JDK 21 `test installDist`: 156 tests passed, zero failures,
  errors, or skips. Added 16 regressions for OAuth response forms/rejection/diagnostic safety,
  validation identity/status handling, GraphQL auth/partial-data behavior, preserved credentials,
  special-category mapping/ACL boundaries, and the runtime channel recheck.
- The initial sandbox build could not resolve the Kotlin plugin; the authorized cache/network build
  passed without changing dependencies. A new lifecycle test initially assumed all device-request
  failures were terminal; inspection exposed unconditional retry. Explicit authorization rejections
  now stop immediately while transient failures retain backoff. The final complete suite passed.
- Isolated packaged JVM on `127.0.0.1:18789`: health returned HTTP 200 with `status: ok`, inventory
  refresh returned HTTP 202, and state returned HTTP 200 without private credential fields. The
  process was stopped after verification; disposable data remains only under ignored `.gradle/`.
- `git diff --check` passed. No browser client/CSS, state schema, dependencies, Dockerfile, Compose,
  or distribution configuration changed, so visual QA and container rebuilds were not repeated.
- Android reference remained clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. The existing untracked
  root `CLAUDE.md` was left untouched. No live Twitch account login, earning, or claim was exercised.

## Verification record - 2026-09-29 (compact reward layout)

- Moved Open Reward Campaigns beside the Drop list at 1280px and wider, and below it on smaller
  screens. Reduced spacing and repeated labels, shortened the explanatory copy, added expandable
  Details, and reduced reward pages to four entries. Main campaigns retain 24-row pages.
- Reward controls have 44px targets, labeled pagination, 2px keyboard focus, and explicit expanded
  states. Expanded reward details survive panel hide/show and ordinary view updates.
- Browser QA covered 1440px and 1280px desktop, 1024px tablet, and 390px/320px mobile, light/dark
  themes, long names/descriptions, pagination, keyboard activation, Show/Hide, and reward details.
  Empty, loading, unavailable, and logged-out states were visually checked with local fixtures.
- Fixed a pre-existing 320px horizontal overflow caused by the root minimum width and header flex
  sizing. The status label now truncates while header buttons retain their size. Checked widths have
  no page overflow, and no browser warnings/errors were observed.
- All 14 Node tests, JavaScript syntax validation, and `git diff --check` passed. Client/CSS/docs only;
  JVM tests and Docker builds were not repeated. No live Twitch account flow was exercised.
- Android reference remained clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.

## Verification record - 2026-09-29 (reward campaign visibility)

- Added a default-visible Open Reward Campaigns panel with Show/Hide, descriptions, end dates,
  reward names, 24-row pagination, and a fixed Twitch campaigns link. Date windows determine open
  promotions, including sitewide rewards without a game. Drop filters and mining controls stay separate.
- Root Gradle 9.5.1/JDK 21 `test installDist`: 140 tests passed. Coverage includes reward request
  variables, mapping, missing/partial/empty inventories, serialization, refresh preservation, and reset.
- All 14 Node regressions and the JavaScript syntax check passed. Rendering tests cover escaping,
  date filtering, pagination, Show/Hide, loading, unavailable, empty, and logged-out states.
- Visual QA covered 1440px desktop, 390px mobile, dark/light themes, active preview and real logged-out
  states, plus 390px fixtures for empty/loading/unavailable panels. No horizontal overflow or browser
  warnings/errors were observed in the active mobile preview. Show/Hide worked in the browser.
- Isolated packaged host: `/api/health` returned `ok`; inventory refresh returned HTTP 202 and the
  logged-out state exposed an empty unavailable reward list. No real Twitch login or reward earning
  was exercised. Public Twitch client assets were inspected without account credentials.
- Android remained clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. No Docker, dependency,
  deployment, or persistence changes; no image rebuild was needed.

## Verification record - 2026-09-22 (campaign links)

- Campaign names reuse the existing validated HTTPS Twitch campaign URL, with a subtle underline,
  external-link arrow, and a labeled new-tab destination. Missing/unsafe URLs retain escaped text.
- All 12 Node client tests and the JavaScript syntax check passed. New coverage checks all three
  rendering locations, escaped names, safe new-tab attributes, and rejected/missing URL fallbacks.
- Browser preview QA at 1440px desktop and 390px mobile covered Campaigns, Now watching, Up next,
  expanded drops, dark/light themes, and keyboard navigation. Campaign links measured 44px high,
  keyboard focus showed the 2px accent ring, and no horizontal overflow or console warnings/errors
  appeared. Campaign destination URLs were checked against the preview data; live Twitch campaign
  navigation and account flows were not exercised.
- Client-only change; no API/state-schema, JVM, Docker, or dependency changes. JVM tests and image
  builds were not repeated. Android remained clean at
  `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.

## Verification record - 2026-09-22 (README refresh)

- Replaced the cropped README screenshot with a full current Overview capture, including campaign
  rewards, recent activity, and the server-ranked Up next queue. The image uses only the built-in
  preview fixture and is explicitly labeled as illustrative in the README.
- Removed README em dashes, clarified persistent category priorities and restart behavior, corrected
  the channel-picker description, and documented the existing read-only preview entry point.
- Captured the current root web assets from an isolated loopback static server. Desktop (1440px) and
  mobile (390px) active previews had no horizontal overflow or browser warnings/errors. README local
  links and image format were checked, and `git diff --check` passed.
- Documentation and screenshot only; JVM tests and Docker builds were not rerun. No live Twitch
  account, mining, or claims were exercised. Android remained clean at
  `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.

## Verification record — 2026-09-22 (review and refactor)

- Reviewed `246ba99` and `f097031` plus their late-September-21 prerequisite `616260f` against the
  runtime, API, persistence, security, and UI conventions. Public search remains independent of
  sessions/mining, queue previews reuse the runtime selector, and promotion changes remain root-only.
- Extracted `CategorySearchRequest` to centralize normalized query validation, cursor rules, and
  short/long page limits across the route, provider, and serializer. Split the Twitch response parser
  into page, category, and continuation checks; reused compiled ID/cursor patterns. Public API fields,
  search behavior, persistence, scheduling, and dependencies are unchanged.
- Root Gradle 9.5.1 with the existing isolated JDK 21: `test installDist` passed with 135 JVM tests,
  zero failures/errors/skips. New coverage checks request boundaries, 32-page cache eviction, malformed
  response shapes, and capacity recovery after parsing failures. All 10 Node regressions and both
  client JavaScript syntax checks passed.
- Isolated packaged-host smoke at `127.0.0.1:18786`: health returned `ok`, a priority mutation returned
  HTTP 200 and persisted in state, and a short-query cursor returned structured HTTP 400. Anonymous
  live Twitch search returned 48 and 49 categories on distinct successive `star` pages.
- No client/CSS, Docker, or dependency changes were made; browser visual QA and Docker builds were
  not repeated. Live account authorization, mining, earning, and claims were not exercised.
- Android reference remained clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.

## Verification record — 2026-09-22 (expanded category search)

- Searches of 2–3 characters retain the 12-result limit. Searches of 4–100 characters expose all
  Twitch-provided matches through Previous/Next, up to 50 per page, without a total app result cutoff.
  Each page loads on request and uses the existing five-minute/32-entry cache, now keyed by query and
  cursor. No background refresh or full-catalog crawl was added.
- Cursor validation, duplicate/unknown parameter rejection, safe failure on malformed pagination,
  page retry, stale-query suppression, and result-scroll preservation cover the expanded flow.
- Root Gradle 9.5.1/JDK 21: 131 JVM tests passed, zero failures/errors/skips; `installDist` built.
  Ten JavaScript regressions and syntax checks passed, covering the short/long threshold, cursor pages,
  cached requests, invalid continuations, failed-page retry, and stale responses after query changes.
- Isolated built-host health returned `ok`. Anonymous public Twitch lookup verified short searches,
  distinct first/second pages for `star`, and end-of-results for `Stardew Valley`. Browser checks at
  1440px desktop and 390px mobile covered Previous/Next, both themes, adding Star Fox 2 from page two,
  preserving result scroll after saving, resetting scroll on page changes, and hiding pagination for
  short/exhausted searches. No horizontal overflow or browser warnings/errors were observed.
- Android stayed clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`. No live farming account was used.

## Verification record — 2026-09-22

- Added All Twitch categories as the default priority search scope. Search submits explicitly with
  2–100 characters, loads at most 12 public category matches, and lets users save a returned exact name
  even without a loaded campaign. Local scopes and manual entry remain available.
- Public catalog lookups use no OAuth session, run independently of farming, and have a 15-second
  call timeout, two-request concurrency cap, 128 KiB response cap, and 32-entry/five-minute cache.
  The UI cancels outdated searches, suppresses stale completions, and shows loading/empty/error states.
- Root Gradle 9.5.1/JDK 21: 127 JVM tests passed with zero failures/errors/skips; `installDist` built.
  Seven Node rendering/search regressions and JavaScript syntax checks passed, including stale request
  cancellation, safe error display, malformed upstream replies, validation, cache expiry, and concurrency.
- The built app returned real Twitch catalog results without a session or any campaigns. Health and
  the browser-driven priority mutation succeeded; adding Stardew Valley persisted across server restart.
  Desktop (1440px) and mobile (390px) checks covered results, saved state, empty results, disconnected
  search errors, and both themes. Results scroll within a bounded panel with no horizontal page overflow.
  No console warnings/errors appeared before the deliberate disconnected-server check. Loading and
  stale completion behavior were checked by automated tests; no live farming account was exercised.
- Android reference remained clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.

## Verification record — 2026-09-21

- Added a persistent game/category editor independent of campaign rows, with arrows and direct rank
  entry, waiting/upcoming/excluded labels, and confirmation before clearing all. Scoped local search
  requires two characters and renders at most eight matches. Exact-name entry supports absent games;
  existing saved settings require no migration. The 500-game limit now returns HTTP 409 on additions.
- Refined dark surfaces and text contrast, simplified campaign actions, added Upcoming and sorting
  controls, paged campaigns at 24 rows, and preserved input focus/caret and list scroll during updates.
- Moved Up next ranking to the runtime selector. Known start boundaries now trigger priority checks;
  in-flight checks wait for completion without spinning on their old deadline.
- Root Gradle 9.5.1/JDK 21 tests: 118 passed, zero failures/errors/skips. The local distribution builds.
  Four Node rendering regressions and JavaScript syntax checks passed. A pre-existing startup test
  teardown race was exposed by the clean build and fixed by joining stop work before TempDir cleanup;
  the subsequent full suites passed.
- Isolated local server: health and priority/settings mutations returned HTTP 200. Browser checks
  exercised adding absent categories, arrow/numeric reordering, and retaining search text/focus while
  a settings mutation arrived over SSE. Responsive checks covered 1440px desktop, 900px tablet, and
  390px mobile, both themes, active/empty/logged-out/preparing/error states, and the collapsed editor.
  No browser console warnings/errors were observed in these checks; tested layouts had no page overflow.
- Android reference remained clean at `dfd7d8c5316ff896c838301bd3c769c84aef8d15`.
  Docker configuration/dependencies were unchanged; no image build was performed (no Docker CLI on
  this host). Live Twitch login, earning, claims, and return-to-priority behavior were not exercised.

### Current category-priority limitations

- Two- or three-character Twitch searches return the first 12 matches. Four or more characters enable
  paging through all results Twitch exposes; Twitch controls ranking, matching, and any upstream limits.
  The private public-search endpoint may change or become unavailable. Saved priorities still match exact names
  without regard to case, so category renames require a manual update. Manual entries in local scopes
  are not validated against Twitch.
- Newly published campaigns are discovered on inventory refresh. Cached scheduled campaigns can
  become eligible at their known start times. Live channel availability and account eligibility still
  determine whether a saved priority can run; the queue is a preview, not a reservation.

## Verification record — 2026-08-27

- Runtime logging now appends one sanitized, newline-terminated entry during normal operation and
  uses an atomic bounded compaction only when the line or physical-size limit requires it; bounded
  tail loading ignores blank lines and preserves the same on-disk format. Channel state now includes
  the canonical Twitch login for link targets while retaining display names as labels, and campaign
  ACL membership remains server-side instead of being repeated in state events. Focused regressions
  cover line-count compaction, append/reload ordering without blank entries, fresh-file POSIX
  permissions, canonical logins for current/alternative channels, and omitted campaign ACLs. The full
  Gradle 9.5.1/JDK 21 Docker suite passed with 112 tests across 16 suites, 0 failures, 0 errors, and
  0 skipped (`BUILD SUCCESSFUL in 31s`); the JavaScript syntax check and diff/isolation checks passed.
  Live Twitch behavior was not exercised.
- Persisted `miningRequested` runtime intent now resumes unattended mining after container restart and
  re-login, user Start/Stop commands update it through the serialized command channel, shutdown leaves
  it intact, session reset clears it, and preference reset preserves it. Failed intent writes emit a
  safe warning without blocking Start or Stop. The mining loop reuses guarded retry/backoff validation
  at least hourly on inventory reloads and rechecks the active channel every three minutes, failing over
  when it goes offline or changes category and refreshing cached watch configuration when its broadcast
  ID changes; unchanged rechecks do not update the snapshot. Focused persistence, startup, execution,
  and temporal scheduling regressions cover each behavior. The full Gradle 9.5.1/JDK 21 Docker suite
  passed with 109 tests across 16 suites, 0 failures, 0 errors, and 0 skipped (`BUILD SUCCESSFUL in
  57s`). Live Twitch behavior was not exercised.
- Hardened the Twitch client against transient GraphQL authorization responses by re-validating the
  saved token before session expiry, updated the Inventory and Viewer Drops Dashboard persisted-query
  hashes, accepted both beacon and spade collector URL keys, made scalar JSON parsing tolerant of
  object/array surprises, paged campaign ACL channel checks through at most 100 distinct logins, and
  rejected live category-less streams for game-specific Drops while retaining lenient behavior when
  broadcast settings are absent. Focused MockWebServer regressions cover each behavior. The full
  Gradle 9.5.1/JDK 21 Docker suite passed with 100 tests across 16 suites, 0 failures, 0 errors, and
  0 skipped (`BUILD SUCCESSFUL in 46s`). Live Twitch behavior was not exercised.

## Verification record — 2026-08-25

- Restructured the web UI layout and moved the primary accent from mint to Twitch purple
  (`#9146ff` fills, `#bf94ff`/`#6f2fd8` text in dark/light). The header now owns the miner status
  pill, a global Start/Stop button, refresh, and theme controls; the always-on connection pill and
  the duplicated sidebar/host indicator were replaced by a single sidebar host row plus an offline
  banner that only appears when the host stops responding. The authenticated overview replaced the
  marketing hero and its duplicated "Right now" fact list with four stat tiles (including the current
  drop with an estimated finish time), a "Now watching" panel that lists every drop in the active
  campaign, recent activity, and a priority-queue preview. Campaign rows gained relative end/start
  dates, a "Link account" action for unlinked campaigns, and an expandable drop list with rewards and
  per-drop state; the filter toolbar reports the visible count. Activity dropped the redundant
  "Session pulse" list, and Settings replaced the static posture notices with a Service card
  (account, miner, phase, version, uptime, pinned/excluded counts). Views are addressed by URL hash,
  the mobile tab bar has icons, and `[hidden]` now wins over component display rules. Verified with
  headless Chrome over CDP at 1440, 900, and 390 widths in both themes across the active, logged-out,
  device-code, and expired states plus campaigns (expanded drops), activity, settings, the channel
  picker, and the confirm dialog, with no horizontal overflow. The README screenshot was regenerated
  from the built-in preview. No JDK 21 was available locally, so `docker compose build` ran the Gradle
  suite in its builder stage (BUILD SUCCESSFUL); the built image on a throwaway volume answered
  `/api/health`, the redesigned assets, and a settings `PUT` with HTTP 200.

## Verification record — 2026-08-24

- Redesigned the web client from glassmorphism to a flat Apple/Anthropic-style system: opaque
  surfaces with hairline borders, no blur/gradients/ambient blobs/floating orbit art, a single mint
  accent with tint/deep token pairs, system type with tight heading tracking and larger body copy, an
  edge-anchored sidebar and bottom mobile bar, and hero panels that pair copy with a factual aside
  (status rows, onboarding steps, or the device code). Also fixed browser bugs found on the way: the
  confirm dialog reset `returnValue` before each prompt so Escape can no longer reuse a previous
  "confirm" result; clipboard copy explains the secure-context requirement (plain-HTTP LAN mode)
  instead of throwing a TypeError; the welcome hero's preview link now opens the full active preview
  (unknown `?preview=` values previously blanked activity and logs); unparseable timestamps render as
  "—" instead of aborting the render; active-navigation state is scoped to the nav bars; and the
  range slider no longer paints its track color across the whole control. Verified with headless
  Chrome at 1440, 900, and 390 widths in both themes across active, logged-out, preparing, device-code,
  and expired states, plus campaigns, activity, and settings views. `docker compose build` passed with
  the Gradle test suite in the builder stage; the local host answered `/api/health` and a settings
  `PUT` with HTTP 200. The README screenshot was regenerated from the built-in preview.

## Verification record — 2026-08-13

- Removed the sidebar navigation hover translation and the content-card backdrop blur whose sampling
  boundary could appear as a pale vertical band during hover repaints. The translucent card surface,
  border, and shadow remain. Desktop hover QA at 1200×900 retained the rounded color highlight with no
  transformed layer, sampling seam, or horizontal overflow; mobile QA at 390×844 retained the bottom
  navigation with no page overflow. The clean Gradle/JDK 21 test and install distribution passed with
  93 tests across 16 suites.
- Fallback to other games now defaults on for fresh, reset, and legacy field-absent settings while an
  explicitly saved off preference remains off after restart. Focused persistence coverage and the
  clean Gradle 9.5.1/JDK 21 test/install-distribution build passed with 93 tests across 16 suites.
  Android parity was reviewed read-only; its separate default remains off pending an explicit Android
  change request.
- Unknown-drop progress now prompts an immediate serialized inventory refresh while retaining the
  current watch; unresolved reports retry no more than every five minutes. A regression reproduces
  Twitch's blank drop ID at 0 minutes and verifies one immediate reload, clearer activity text, and no
  rapid refresh loop. The clean Gradle 9.5.1/JDK 21 suite and install distribution passed with 91 tests
  across 16 suites. Android parity was reviewed read-only; the separate Android runtime still ignores
  unexpected drops until its normal refresh and remained outside this root-only change.
- Reworked the root README into a user-facing app showcase with a safe built-in-preview screenshot,
  concise benefits, feature highlights, a three-step farming flow, and a focused quick start. Moved
  networking, environment, persistence, runtime, and maintenance detail into `OPERATIONS.md`.
- GitHub-flavored Markdown rendering resolved the icon, badges, 1265×712 preview image, and local
  documentation links. Every relative target exists, the new files contain no credential signatures,
  Compose configuration still validates, and the optional Android reference remains unchanged.
- Added opt-in private-network request trust and made `.env.example` LAN-ready. LAN mode accepts only
  literal private/link-local destination addresses, requires mutation Origin to match the request Host
  and port, and leaves the no-`.env` Compose default loopback-only.
- Gradle 9.5.1/JDK 21 verification passed 90 tests across 16 suites. Compose resolved the base config
  to `127.0.0.1` with LAN mode off and `.env.example` to `0.0.0.0` with LAN mode on. Packaged and
  hardened-container smokes returned HTTP 200 for a private-IP health request and matching-origin
  settings mutation; a mismatched LAN origin returned HTTP 403. The Docker image rebuilt successfully
  and reran the complete suite in its isolated builder stage.
- Prepared the root project for independent GitHub publication: removed Android submodule metadata,
  ignored optional local Android checkouts and broader local secret/build artifacts, added a root MIT
  license, and replaced repository-relative Android documentation links with the upstream repository.
- Publication checks confirmed `.env` and the optional Android checkout are excluded, tracked/history
  scans contain no real credentials, Compose configuration resolves, and both browser scripts pass
  syntax validation. A fresh image rebuild could not start because Docker Desktop's Linux engine was
  stopped; the same-source 87-test and image-build results below remain the latest full verification.
- Audited the merged direct-Spade fix in
  `rangermix/TwitchDropsMiner#70`, its upstream working implementation in DevilXD commit `4148c71`,
  and current public Twitch developer documentation. Twitch documents entitlement management but not
  its private viewer earning collector, so the maintained miners remain the compatibility reference.
- Tightened direct-Spade parity beyond the initial header repair: channel display names and canonical
  logins are now preserved separately, configuration lookup and event attribution use the login, every
  heartbeat gets a fresh millisecond UTC timestamp, and wire-level tests cover the full uncompressed
  Base64 form payload plus success, rejection, missing-stream, and missing-configuration outcomes.
- Restored current Twitch collector discovery: authenticated channel/config requests now accept the
  hashed settings bundle from `assets.twitch.tv` as well as legacy `static.twitchcdn.net`, and direct
  event delivery accepts the current exact `https://beacon.twitch.tv/track` destination as well as the
  legacy HTTPS `spade.twitch.tv` host. Tests reject non-Twitch hosts, non-settings asset paths,
  non-collector beacon paths, and plaintext HTTP.
- Live verification with the saved eligible account confirmed consecutive collector HTTP 204
  acceptances and Twitch inventory progress increasing from 83/120 to 84/120 minutes. Normal logging
  was restored afterward and the Compose miner was left actively watching.
- Post-fix packaged smoke on isolated `127.0.0.1:18791`: health, a persisted settings mutation, and
  state returned HTTP 200; the verification JVM was stopped and its disposable data was removed.
- Root release audit covered bootstrap/configuration, persistence and encryption, HTTP routing and
  mutation validation, state redaction, OAuth, inventory mapping, selection/failover, watch/progress,
  claims, command scheduling, packaged runtime behavior, and every browser view/state. The Android
  project was used only as a read-only behavioral reference.
- Required root `gradle clean test installDist --no-daemon`: passed with Gradle 9.5.1 on JDK 21.
- Root Gradle suite: 87 tests passed, 0 failures, 0 errors, 0 skipped across 16 suites. New regression
  coverage exercises terminal device-login denial without retry, safe partial-drop retention, current
  settings in serialized selection state, quoted JSON secret redaction, bounded persisted settings,
  valid manual channel selection, authenticated Spade attribution and configuration discovery, and
  the current Twitch settings/collector host and path allowlists.
- A root `.gitignore` scope error that hid every nested `data/` package was corrected to ignore only
  the runtime `/data/` directory; all root-owned miner and persistence sources/tests are now visible to
  Git. The Docker build context was separately verified against current Docker ignore semantics and
  was not affected by this Git-only pattern.
- Packaged distribution smoke on isolated `127.0.0.1:18784`: health and state returned HTTP 200; a
  settings mutation persisted and restored with HTTP 200; an untrusted Origin returned HTTP 403;
  `app.js` returned `no-cache`; state exposed neither `accessToken` nor `deviceCode`; and SSE delivered
  an initial state event.
- Browser QA at 1280×800 desktop, 390×844 mobile, and a narrow 320×640 mobile viewport covered real
  logged-out/empty, preparing/loading, displayed-code/replacement, expired/error, active, compatible
  channel selection, campaign search/filter, settings, and confirmation-dialog states. There were no
  console warnings/errors or horizontal page overflow. Visible interactive controls met 44px minimum
  targets, the narrow campaign filters scrolled within their card, keyboard focus used a visible 3px
  ring, navigation exposed `aria-current`, and filters exposed `aria-pressed`.
- JavaScript syntax checks passed for `app.js` and `theme-init.js`.
- Docker Compose configuration validation and image rebuild passed. The image builder reran the full
  Gradle test/install distribution, and an isolated non-root, read-only container with tmpfs-only data
  returned HTTP 200 for health, a settings mutation, and state on `127.0.0.1:18793` before it was
  removed.
- Android reference audit: nested Git worktree remained clean at
  `dfd7d8c5316ff896c838301bd3c769c84aef8d15` after all root implementation and verification.
- The isolated verification JVM was stopped. Separately, the real Compose service exercised saved
  authorization, campaign discovery, direct event delivery, and a confirmed progress increment;
  channel failover and claiming were not forced during this verification.

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

- Fresh Android-client device authorization can be rejected by Twitch. The previous dashboard browser
  capture has live account/campaign evidence on Linux Docker; desktop-helper account acceptance, unattended
  integrity renewal, earning, and claims remain unverified.
  New dashboard logins use a verified encrypted SDK seed when available, or retain the authenticated
  Docker browser without requiring the PC. Retained-browser mode requires reconnecting after service
  restart; temporary failures retry while its browser survives. SDK mode can recover across restarts
  while its seed remains valid. Retained-browser login and one manually triggered renewal passed
  live verification on 2026-10-05; natural scheduling, atomic renewal replacement, repeated renewal,
  restart behavior and SDK mode still need live verification. Older dashboard logins need one
  reconnection after upgrade. Desktop-helper
  logins still require the helper running and reconnecting after server restart. Firefox,
  popup-based social sign-in in the dashboard viewer, and upstream-helper protocol compatibility are
  not implemented. Native browser checks were on Windows. Different browser/miner network
  routes or Twitch browser challenges can reject capture or server-side verification.
- Linux/amd64 Docker image build/runtime and offline Chromium capture now pass on Docker Desktop.
  The user's Debian/Proxmox LXC deployment and unattended real-account renewal are still unverified.
- Reward-campaign visibility has fixture coverage and public Twitch client schema inspection, but
  has not been verified with an authenticated real-account reward listing. Twitch's private persisted
  query can change. These campaigns are view-only: reward progress, automatic earning/claiming, and
  redemption are not implemented. Summaries are not a complete eligibility or requirement breakdown;
  the panel links to Twitch for authoritative details.
- Twitch device login, private GraphQL hashes, watch telemetry, or claim response formats may change.
- Live behavior cannot be fully exercised without a real eligible Twitch account and active Drops
  campaign.
- Exposing the port beyond loopback requires an operator-provided authenticated TLS reverse proxy.

## Next candidates after parity

- Optional reverse-proxy Compose profile with documented authentication
- Export/import for non-secret settings
- Browser notifications for claim completion and session expiry
- Metrics endpoint that never exposes account or campaign names
