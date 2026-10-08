# AGENTS.md

## Scope and priorities

This is the independent JVM host, web dashboard, and Docker deployment for an unofficial Twitch
Drops miner. Work in this root repository. The optional `TwitchDropsMinerAndroid/` checkout is a
separate, ignored behavioral reference, not a build dependency. Android edits require an explicit
user request.

Preserve user work, credentials, and runtime correctness. Keep changes within the requested scope.
Follow explicit user instructions over workflow defaults here. Make routine implementation decisions
autonomously; ask when missing information or authorization prevents safe progress. Missing optional
references or tools do not justify weakening checks or changing architecture.

## Start here

Before any file changes:

1. Run `git status --short`, `git branch --show-current`, and `git log -5 --oneline`. Record pre-existing
   staged, unstaged, and untracked work; inspect relevant diffs before editing overlapping files.
2. If `TwitchDropsMinerAndroid/` exists, record its status and HEAD with
   `git -C TwitchDropsMinerAndroid status --short` and `git -C TwitchDropsMinerAndroid rev-parse HEAD`.
   Preserve that baseline, including existing changes.
3. Read `README.md`, relevant `ARCHITECTURE.md` sections, and the current checklist, latest relevant
   verification, and known limitations in `PROJECT_STATUS.md`. Read older history only as needed.
4. Use the table below, then inspect affected implementation and tests before editing.

| Task area | Additional reading / entry points |
| --- | --- |
| Installation, networking, persistence, environment, containers | `OPERATIONS.md`, `SECURITY.md`, build/Compose files, `.env.example` |
| API, authentication, storage, outbound requests | `SECURITY.md`; `WebServer.kt`, `StateJson.kt`, `AppEnvironment.kt`, affected adapters |
| Mining, selection, watch progress, claims | Root runtime and Twitch client; optional Android `README.md` and corresponding reference source |
| Dashboard, browser login, helper | Relevant web files and Node tests; `DashboardLogin.kt`, `browser/worker.mjs`, architecture/security browser sections |
| Documentation only | Referenced files and commands; verify claims against current source without unrelated builds |

Source, tests, and configuration establish implemented behavior; status records establish what was
verified. Investigate disagreements and correct task-related documentation. Do not infer Twitch
behavior from UI labels or treat historical test results as verification of new changes.

## Repository map

- `build.gradle`, `settings.gradle`: root JVM build; JDK 21, Gradle 9.5.1.
- `src/main/kotlin/app/twitchdockdrops/`: bootstrap, environment, API, redacting serializer, dashboard
  login bridge, atomic-file and safe-text utilities.
- `src/main/kotlin/com/nathan/twitchdropsminer/android/`: root-owned models, JVM data adapters, Twitch
  transport, and runtime. The legacy package name is intentional.
- `src/main/resources/web/`: framework-free dashboard, theme initialization, browser-login viewer,
  and downloadable `login-helper.mjs`.
- `browser/`: optional Chromium companion, Dockerfile, and offline capture smoke test.
- `src/test/kotlin/`, `src/test/js/`: JVM and Node regression suites.
- `Dockerfile`, `compose.yaml`, `compose.browser.yaml`, `.dockerignore`: container delivery.
- `docs/`: public showcase assets; use synthetic data, never real account/login captures.

`main` is the development branch. The former `deployment` branch has been removed. Server uploads
use the local `docker-server/` copy folder described below and in `OPERATIONS.md`.

## Docker server copy folder

When completing changes intended for the Docker server, refresh the ignored root `docker-server/`
folder so the user can drag its contents into the existing server directory. This is a source build
bundle for `docker compose -f compose.yaml -f compose.browser.yaml up --build -d`.

- Copy reviewed, tracked files from `src/` and `browser/`, plus `.dockerignore`, `.env.example`,
  `build.gradle`, `settings.gradle`, `Dockerfile`, `compose.yaml`, `compose.browser.yaml`, `LICENSE`,
  and `THIRD_PARTY_NOTICES.md`. Include JVM tests because the Dockerfile runs them.
- Do not copy `.env`, data, credentials, sessions, logs, browser profiles, Git metadata, the Android
  reference, generated `build/`, or `node_modules/`. Root package/Playwright files are test tooling
  and are not required by either Dockerfile. Keep the copy folder ignored by Git and Docker.
- Refresh only this generated folder after verifying its resolved path stays inside the repository;
  preserve unexpected user files, especially any `.env` or data placed there. Remove stale generated
  source files when refreshing so renamed/deleted code cannot remain in the upload.
- Verify every copy against its source and validate both base and browser-merged Compose configs
  from the copy folder with `config --quiet`. Do not start the real Compose services to test packaging.
- Tell the user to upload the folder's contents, preserve the server's existing `.env` and named data
  volume, and run the command above from the server directory. For a fresh installation only, copy
  `.env.example` to `.env` and configure it. Report the absolute copy-folder path in the handoff.
- This workflow does not authorize pushes or remote deployment; obtain explicit user authorization
  for those actions as required by the Git rules below.

## Isolation and Git discipline

- Root Gradle and Docker builds must never read, sync, execute, generate, or write anything under
  `TwitchDropsMinerAndroid/`. Never invoke its Gradle wrappers from root commands, run an Android
  emulator in Docker, or add the checkout as tracked files or a submodule. Keep the entire directory
  excluded from Git and the Docker context. Read-only manual parity inspection is allowed.
- Compile the root-owned `AppSettings.kt`, `AutoModePriority.kt`, `BackendModels.kt`, `RuntimeModels.kt`,
  `TwitchApiClient.kt`, `DropClaimRuntime.kt`, and `LocalMinerRuntime.kt` directly. Apply parity changes
  manually and document intentional differences; never synchronize trees as a build step.
- Review `git diff` at meaningful checkpoints. Do not discard, overwrite, stage, or commit unrelated
  user work. Avoid blanket staging; inspect staged and working-tree diffs.
- Do not run root Git commands that initialize, absorb, or rewrite the optional Android checkout.
- Never commit `.env`, `/data`, credentials, tokens, keys, sessions, logs, browser profiles, raw Twitch
  captures, or generated output. Add ignore rules when introducing generated artifact classes.
- Do not change Git configuration, remotes, branches, tags, or submodule pointers unless required by
  the task. Never fetch, pull, push, publish, or open a PR without explicit user authorization.
- Never use hard resets, forced checkout, clean commands, history rewriting, or destructive recovery
  without an explicit request and verified targets. Do not amend an existing commit by default.

## Runtime and Twitch behavior

- `LocalMinerRuntime` owns the authoritative `RuntimeSnapshot` and mining lifecycle. Keep commands
  serialized and async results guarded by session/operation generations. Cancelled or superseded
  work must not commit state, credentials, settings, logs, or counters.
- Login completion, inventory refresh, channel selection/failover, watch heartbeats, unlinked probing,
  and claims stay server-side. The API, dashboard, and browser companion must not become schedulers.
- Preserve idempotent commands, bounded work/queues/retries, and coalesced refreshes. Test reset, stop,
  replacement-login, and stale-completion races when changing lifecycle behavior.
- Normalize settings with `AppSettings.normalized()` before persistence. Reset settings preserves
  Twitch login and mining intent; reset session stops mining and clears session priorities/exclusions.
  Shutdown preserves the saved Start/Stop intent.
- Keep category priorities independent of current inventory. Use the server selector for Up next.
  Open Reward Campaigns remain display-only, outside mining selection, watch totals, and claims.
- Preserve campaign/drop windows, confirmed-progress supervision, failover, and claim retries.
  Missing progress is not zero progress; failed or partial inventory is not an empty list.
- Preserve credentials on inconclusive authentication/integrity failures. Only authoritative token
  validation HTTP 401 proves invalidity; do not replay claims automatically on authentication failure.
- Health reports local readiness, not Twitch reachability. Claim live Twitch success only for the
  real-account flow exercised; fixtures and browser capture tests are separate evidence.

## API, authentication, and persistence

- Keep browser and public API same-origin. Validate Host on every route and Origin on every mutation;
  do not trust forwarded headers to redefine that boundary.
- Prefer small, reviewable files and explicit routing. Never expose real credentials, encryption keys,
  raw sessions, or upstream response bodies through public APIs, logs, errors, fixtures, or screenshots.
- Read-only routes use GET; mutations use POST/PUT with `application/json`, strict known fields/types,
  bounded values, and the 64 KiB body limit. Return stable JSON errors with an `error` field and
  appropriate HTTP status. Keep raw upstream diagnostics out of public errors.
- Commands return promptly while Twitch work runs on application coroutines. Acknowledge persistence
  mutations only after successful atomic storage. Bound upstream reads, concurrency, queues, and SSE clients.
- Use explicit redacting serializers, never reflective domain serialization. State/events must exclude
  OAuth tokens, device-code secrets, helper tickets, browser context, keys, and filesystem paths.
  Preserve the narrowly scoped pairing-code and login-frame responses defined in `ARCHITECTURE.md`.
- Update client, serializer tests, and `ARCHITECTURE.md` together when changing the state contract.
  Keep Twitch/user text escaped and outbound credential destinations narrowly allowlisted.
- Replacement login preserves encrypted credentials until validated atomic replacement or explicit
  reset. Browser renewal remains bound to the original account and revocable lease.
- Keep browser control internal to loopback, with fixed routes, bounded input, and stale-view rejection.
  Never expose arbitrary navigation/CDP or proxy private worker status wholesale. Preserve the
  authenticated browser through Finish sign-in and renewal; viewer input/frames are interactive-only.
- Never log login input, frames, cookies, tickets, or captured headers. Keep input/frames and tickets
  out of durable storage, browser profiles ephemeral, and accepted context inside the encrypted session.
  Use synthetic credentials and responses in tests.
- `/data` is the app container's only durable writable path. Preserve atomic settings/session writes,
  AES-GCM authentication, 32-byte key validation, owner-only permissions where supported, and recovery
  of corrupt/key-mismatched files without silently overwriting recoverable credentials.
- Preserve non-root containers, dropped capabilities, read-only roots, `no-new-privileges`, and app
  health checks. Document concrete reasons for security changes in `SECURITY.md`. The optional browser
  receives no miner volume/secrets or Docker socket; its profile stays ephemeral.
- Keep Compose's no-environment loopback default. `.env.example` explicitly opts into trusted LAN;
  do not confuse it with the default. LAN admission accepts only literal private/link-local addresses
  with matching HTTP Origin Host/port, never a public wildcard. LAN access is not authentication.

## UI design and interaction

Keep the flat Apple/Anthropic-style dashboard with Twitch purple as its single primary accent.

- Reuse `app.css` mist, paper, line, ink, muted, twitch, mint, lilac, peach, sky, and lemon tokens and
  their existing tint/deep variants. Use `--accent` for text, `--accent-tint` for highlights, and
  `--accent-fill` (#9146ff) for solid controls/progress. Mint means live/success, lemon waiting, and
  danger tokens errors/destructive actions; convey meaning with text as well as color.
- Use opaque surfaces, 1px hairline borders, and no gradients, blur, ambient decoration, or surface
  shadows. Dialogs/toasts may use `--shadow-pop`. Keep dark default and persisted light mode consistent.
- Use the existing system font stack, tight heading tracking, 13–15px body text, and nothing below
  11px. Preserve 8–20px rounding, 2px accent focus rings, 44px minimum touch targets, accessible
  contrast, keyboard navigation, and reduced-motion support. Limit motion to short opacity/color changes.
- Logged-out panels pair copy with factual steps/login information; authenticated views use functional
  panels. The header owns global status and Start/Stop. Route hash navigation through `showView`.
- Preserve focus, selection, in-progress edits, and scroll during state updates. Provide useful empty,
  loading, error, and offline states at desktop, tablet, and mobile widths.
- Prefer `textContent`; escape every dynamic template value through the shared helper. Validate link
  destinations separately from escaping. Keep external navigation distinct from editing controls.
- Do not add remote fonts, UI frameworks, icon packages, or build tooling without a concrete need.

## Documentation lookup

For library, framework, SDK, API, CLI, cloud-service, Docker, Gradle, Kotlin, OkHttp, or coroutine
questions, use available Context7 tools or `ctx7` before relying on memory. Resolve the library and
query the relevant version's documentation. If unavailable or insufficient, use official documentation
and state material uncertainty. Do not invent a repository wrapper or use Android tooling.
Private Twitch behavior requires current implementation/reference evidence, not assumptions from public
API docs. Record meaningful dependency/version changes in the relevant project docs.

## Docker Desktop on this Windows host

Docker Desktop was verified installed and running on 2026-10-04: Desktop 4.93.0, Engine 29.8.1,
Compose v5.5.1, with a reachable Linux engine in the `desktop-linux` context. Its installation is
`%LOCALAPPDATA%\Programs\DockerDesktop`; these are observed host facts, not project version requirements.

When container verification is relevant, check the selected context and engine once. If `docker` is
absent from PATH, use `resources\bin\docker.exe` under the installation above or
`%ProgramFiles%\Docker\Docker`, invoked as `& $dockerCli` in PowerShell. Do not silently switch
contexts, reinstall Docker, or change global PATH. Investigate installation/process state only if
that check fails; distinguish access denial from a missing installation or stopped engine.

Keep routine Docker Desktop verification to affected image builds and a brief isolated container
startup check (running without a restart loop, and the app's existing health check). Stop there unless
the user asks for deeper testing. Do not routinely run offline Chromium capture tests, login attempts,
mutation scenarios, restart/renewal exercises, or endurance tests in Docker. Build/startup success
confirms packaging and local readiness only; it does not establish working Twitch login or mining.

## Verification by change

Use the union of applicable rows. Add focused regression tests for bugs and nontrivial settings,
security, or lifecycle changes. Keep verification proportional; documentation-only edits do not need
an application build. Never report an unavailable or skipped check as passed.

The user owns manual UI and real-account testing by default, including sign-in through Finish sign-in,
renewal, earning, failover, and claims. List the affected manual checks in the handoff; do not attempt
them or expand container testing unless requested. Synthetic tests remain useful code checks, but do
not substitute for the user's live results or prove a reported login failure is fixed.

| Changed area | Required checks |
| --- | --- |
| Documentation/instructions only | Verify referenced paths, commands, and claims; review Markdown and `git diff --check` |
| JVM server, API, serializer, storage, miner core | Root Gradle tests and distribution build; additional runtime scenarios only when requested |
| Web JavaScript or login helper/worker | Node regression suite and syntax checks on changed scripts; JVM checks too if contract/server changes |
| Client HTML/CSS/interaction | Review markup/styles and affected automated tests; hand off desktop/mobile, both themes, affected states, and keyboard/focus checks for manual testing |
| Compose | Validate each affected base/merged configuration |
| Dockerfile, dependencies, distribution | Build affected images once; brief isolated startup check and existing app health check |
| Browser capture/login | Relevant JVM and Node tests; user tests actual login/renewal; offline Chromium smoke test only when requested |

Run from the root; Node tests need no package installation:

```powershell
gradle test installDist
node --test src/test/js/*.cjs src/test/js/*.mjs
node --check src/main/resources/web/app.js
docker compose config --quiet
docker compose -f compose.yaml -f compose.browser.yaml config --quiet
docker compose build
docker compose -f compose.yaml -f compose.browser.yaml build
```

These are a command menu, not a requirement to run every command on every task. Use
`gradle clean test installDist` for clean-build verification. Run `node --check` for other changed
scripts too. Quiet Compose validation avoids printing interpolated secrets. Choose the relevant base
or browser-enabled build; do not rebuild the same app image through both configurations without need.

Use disposable data and an unused loopback port for smoke tests; never mutate a saved user session or
reset live settings to test an endpoint. Compose has explicit container/network/volume names, so a
different project name alone does not isolate it: override those resources or use a disposable local
JVM host. For explicitly requested mutation smoke tests, send the expected Origin and JSON headers
and verify results through state.
Clean up only resources created by the task.

For visual fixtures use `/?preview=active`, `/?preview=loggedout`, `/?preview=preparing`,
`/?preview=code`, or `/?preview=expired`. Preview controls do not verify real mutations or Twitch
behavior. See `OPERATIONS.md` for optional offline Chromium testing and deployment commands.

## Finish and handoff

1. Review the complete task diff, `git diff --check`, and verification results. Compare the Android
   checkout's status and HEAD with the starting baseline; do not try to clean pre-existing changes.
2. Update `PROJECT_STATUS.md` when behavior, scope, verification evidence, or known limitations change.
   Update `ARCHITECTURE.md` for contracts/design, `SECURITY.md` for trust boundaries, and `README.md` plus
   `OPERATIONS.md` for operator commands, ports, environment, or persistence changes. Avoid unrelated
   history rewrites and status churn for editorial-only edits.
3. Stage only reviewed task files/hunks, inspect `git diff --cached`, and create one focused local
   commit with an imperative descriptive message. Preserve unrelated staged work; if it cannot be
   safely excluded, leave task changes uncommitted and explain why. Honor a user request not to commit.
4. Run `git status --short`. Report changes, checks actually run and their outcomes, material unverified
   areas, commit ID, and remaining user changes. Record implementation verification gaps in
   `PROJECT_STATUS.md`; do not claim completion beyond the available evidence.
