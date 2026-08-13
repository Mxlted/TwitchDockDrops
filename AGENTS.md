# AGENTS.md

## Mission

This repository contains an independent headless JVM host, web UI, and Docker Compose deployment for
an unofficial Twitch Drops miner. `TwitchDropsMinerAndroid` is maintained in a separate repository as
the behavioral reference. An optional local checkout at `TwitchDropsMinerAndroid/` is ignored by Git
and Docker.

Preserve behavioral parity deliberately while keeping the projects isolated. Do not run an Android
emulator inside Docker, invoke Android Gradle wrappers from root commands, add the optional Android
checkout to this repository, or modify it unless the user explicitly requests Android work.

## Required reading before edits

Read these files before changing implementation:

- `README.md`
- `ARCHITECTURE.md`
- `PROJECT_STATUS.md`
- `SECURITY.md` for API, storage, networking, container, or auth work
- `TwitchDropsMinerAndroid/README.md` for miner behavior when the optional local reference is present

Then inspect the files in the area being changed. Do not infer Twitch behavior from UI labels alone.

## Git workflow

The repository root is the authoritative Git worktree on branch `main`. The optional Android reference
is a separate, ignored Git repository and must never be added as a submodule or ordinary tracked tree.
Use Git throughout every task so changes remain attributable and recoverable.

At the start of every task that may change files:

1. Run `git status --short` and inspect the recent root history with `git log -5 --oneline`.
2. If `TwitchDropsMinerAndroid/` exists locally, record its status and commit before root work with
   `git -C TwitchDropsMinerAndroid status --short` and `git -C TwitchDropsMinerAndroid rev-parse HEAD`.
3. Treat all pre-existing modifications and untracked files as user work. Do not discard, overwrite,
   stage, or commit them unless the task explicitly includes them.

While working:

- Use `git diff` to review the root changes at meaningful checkpoints and before verification.
- Keep generated output, `.env`, `/data`, credentials, tokens, keys, sessions, logs, and Twitch
  response captures untracked. Update `.gitignore` when a new local/generated artifact class appears.
- Never use `git reset --hard`, force checkout, clean commands, history rewriting, or destructive
  recovery unless the user explicitly requests it and the exact targets have been verified.
- Never modify Git configuration, remotes, branches, tags, or submodule pointers unless the task
  requires it. Never push, fetch, pull, publish, or open a pull request without explicit user
  authorization.
- Do not use Git commands in the root that recurse into, initialize, add, absorb, or rewrite the
  optional Android checkout during ordinary root work.

At the end of a completed change task:

1. Review `git diff --check`, the complete relevant diff, and the verification results.
2. If the optional `TwitchDropsMinerAndroid/` checkout exists, confirm it is still clean and remains
   at its starting commit unless Android work was explicitly requested.
3. Stage only files belonging to the task and create one focused local commit with an imperative,
   descriptive message. If unrelated user changes prevent a safe commit, leave the task changes
   uncommitted and explain why instead of mixing them.
4. Run `git status --short` after the commit and report the commit ID plus any remaining changes.

If the user explicitly asks to leave changes uncommitted, to split commits differently, or to avoid a
commit, follow that request. Do not amend or replace an existing commit unless the user explicitly
asks for history editing.

## Repository map

- `build.gradle`, `settings.gradle` — root JVM application build
- `src/main/kotlin/app/twitchdockdrops/` — server bootstrap, API, serialization
- `src/main/kotlin/com/nathan/.../data/` — JVM replacements for Android-only repositories/providers
- `src/main/resources/web/` — framework-free web UI
- `src/test/` — root host tests
- `TwitchDropsMinerAndroid/` — optional ignored local Android reference, never published or built
- `Dockerfile`, `compose.yaml`, `.dockerignore` — container delivery
- root Markdown files — operator and agent handoff documentation

## Build-isolation invariant

The root project owns these JVM miner files under `src/main/kotlin/com/nathan/twitchdropsminer/android/`:

- `data/model/AppSettings.kt`
- `data/model/AutoModePriority.kt`
- `data/model/BackendModels.kt`
- `data/model/RuntimeModels.kt`
- `data/twitch/TwitchApiClient.kt`
- `runtime/DropClaimRuntime.kt`
- `runtime/LocalMinerRuntime.kt`

The legacy package path is intentional. The root Gradle build must compile these root-owned files
directly and must never read, sync, execute, generate, or write anything under
`TwitchDropsMinerAndroid/`. The Docker context must continue to exclude the complete Android folder.
Parity work is manual and explicit; Android changes require a separate user request.

## Runtime invariants

- `LocalMinerRuntime` owns mining lifecycle and the authoritative `RuntimeSnapshot`.
- Do not create a parallel scheduler in the API or browser.
- Device login, inventory refresh, watch heartbeats, channel selection/failover, unlinked progress
  probing, and claims must remain server-side.
- Persist settings only after `AppSettings.normalized()`.
- Never expose access tokens, device-code secrets, encryption keys, or raw session files through API,
  logs, UI, errors, tests, or screenshots.
- Reset settings must not sign out Twitch. Reset session must stop mining and clear session-scoped
  priorities/exclusions.
- Health reflects local process readiness, not Twitch reachability.

## API conventions

- Keep browser and API same-origin.
- Read-only routes use `GET`; mutations use `POST` or `PUT` with `application/json`.
- Enforce the request-size limit and origin check on every mutation.
- Return structured JSON errors with a stable `error` field and an appropriate status.
- Command endpoints should return quickly; Twitch/network work belongs on application coroutines.
- Every state payload must pass through the redacting serializer. Never serialize domain objects
  reflectively.
- If the state document changes, update the client, tests, and `ARCHITECTURE.md` together.

## Persistence and security

- `/data` is the only durable writable path in the container.
- Use atomic replace for settings and session writes.
- Preserve AES-GCM authenticated encryption and key length validation.
- Keep generated key/session files owner-readable only where POSIX permissions are available.
- Do not weaken the Compose loopback default, non-root user, dropped capabilities, read-only root,
  `no-new-privileges`, or health check without documenting a concrete reason in `SECURITY.md`.
- Preserve LAN mode as an explicit opt-in: accept only literal private/link-local addresses and require
  LAN mutation origins to match the request Host and port. Never turn it into a public wildcard.
- Never commit `.env`, volume data, credentials, tokens, keys, logs, or Twitch response captures.

## UI design system

The web client is a soft-color "drop greenhouse," not a generic Twitch-purple dashboard.

- Use the existing mist, mint, lilac, peach, sky, ink, and muted tokens in `app.css`.
- Glass is supporting texture: maintain readable opaque-enough surfaces and visible borders.
- Preserve rounded, tactile controls, clear focus rings, minimum 44px touch targets, and useful empty
  states.
- Keep status meaning independent of color and meet accessible contrast.
- Support desktop, tablet, and mobile layouts plus `prefers-reduced-motion`.
- Do not add remote fonts, UI frameworks, icon packages, or build tooling without a strong reason.
- Escape all Twitch/user-controlled text. Prefer DOM `textContent`; if templates are used, pass every
  dynamic value through the shared escaping helper.

## Documentation lookup

For library, framework, SDK, API, CLI, cloud-service, Docker, Gradle, Kotlin, OkHttp, or coroutine
questions, use the repository-provided Context7/`ctx7` workflow before relying on memory. Use current
official documentation and record meaningful version changes in the relevant docs.

## Commands

From the repository root:

```powershell
# Root JVM tests/build, when Gradle 9.5.1 is installed locally
gradle clean test installDist

# Compose validation and image build
docker compose config
docker compose build

# Run locally through Compose
docker compose up -d
docker compose logs -f app
```

Root commands must not invoke `TwitchDropsMinerAndroid/gradlew` or `gradlew.bat`.

## Verification requirements

Choose verification proportional to the change, but before marking implementation complete:

1. Run root Gradle tests for server, API, serialization, storage, or shared-source changes.
2. Verify the Android directory has no changes after root or Docker work.
3. Run `docker compose config` for Compose changes.
4. Build the image for Dockerfile, dependency, or install-distribution changes.
5. Exercise the health endpoint and at least one mutation endpoint for server changes.
6. Visually inspect desktop and mobile UI for client/CSS changes; check empty, loading, logged-out,
   active, and error states when applicable.

Do not claim live Twitch verification unless a real account flow was actually exercised.

## Change discipline

- Preserve unrelated user changes and the nested Android Git history.
- Keep each completed task in a focused local Git commit unless the user requests otherwise.
- Prefer small, reviewable files and explicit serializers over reflection or magic routing.
- Update `PROJECT_STATUS.md` checkboxes and known limitations as work lands.
- Update `README.md` whenever operator commands, ports, environment variables, or persistence behavior
  changes.
- Add focused tests for bug fixes and nontrivial settings/security behavior.

## Definition of done

A change is done when implementation, focused tests, operator docs, agent handoff docs, and visual or
runtime verification agree. Known gaps must be written in `PROJECT_STATUS.md`, not left as implied
future work.
