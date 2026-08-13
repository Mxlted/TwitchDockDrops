# AGENTS.md

## Mission

This repository contains two delivery surfaces for the same unofficial Twitch Drops miner:

1. `TwitchDropsMinerAndroid/` is an untouched Android reference project.
2. The repository root is an independent headless JVM host, web UI, and Docker Compose deployment.

Preserve behavioral parity deliberately while keeping the two build trees isolated. Do not run an
Android emulator inside Docker, invoke Android Gradle wrappers from root commands, or modify any file
under `TwitchDropsMinerAndroid/` unless the user explicitly requests Android work.

## Required reading before edits

Read these files before changing implementation:

- `README.md`
- `ARCHITECTURE.md`
- `PROJECT_STATUS.md`
- `SECURITY.md` for API, storage, networking, container, or auth work
- `TwitchDropsMinerAndroid/README.md` for miner behavior

Then inspect the files in the area being changed. Do not infer Twitch behavior from UI labels alone.

## Repository map

- `build.gradle`, `settings.gradle` — root JVM application build
- `src/main/kotlin/app/twitchdockdrops/` — server bootstrap, API, serialization
- `src/main/kotlin/com/nathan/.../data/` — JVM replacements for Android-only repositories/providers
- `src/main/resources/web/` — framework-free web UI
- `src/test/` — root host tests
- `TwitchDropsMinerAndroid/` — read-only Android reference, excluded from Docker builds
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
- Prefer small, reviewable files and explicit serializers over reflection or magic routing.
- Update `PROJECT_STATUS.md` checkboxes and known limitations as work lands.
- Update `README.md` whenever operator commands, ports, environment variables, or persistence behavior
  changes.
- Add focused tests for bug fixes and nontrivial settings/security behavior.

## Definition of done

A change is done when implementation, focused tests, operator docs, agent handoff docs, and visual or
runtime verification agree. Known gaps must be written in `PROJECT_STATUS.md`, not left as implied
future work.
