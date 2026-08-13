# Twitch Dock Drops

Twitch Dock Drops is a Docker Compose-hosted web edition based on the behavior of
[`TwitchDropsMinerAndroid`](./TwitchDropsMinerAndroid/). The root project owns its Kotlin Twitch
device-login, campaign selection, channel discovery, watch heartbeat, progress refresh, and
drop-claim runtime, then exposes it through a responsive local web interface.

The Docker build is fully isolated from `TwitchDropsMinerAndroid/`: the directory is excluded from
the build context, no Android wrapper is executed, and no Android file is read or modified.

The web client is deliberately not a clone of the Android screens. Its visual direction is a soft
"drop greenhouse": dark-first translucent panes, quiet mint/lilac/peach color fields, rounded
controls, and clear status hierarchy without the usual Twitch-purple streaming dashboard treatment.
The header theme control switches to the light greenhouse palette and remembers that choice in the
browser; dark mode is the default for new browser profiles. The active-watch card links its current
drop campaign and channel to Twitch, exposes a **Find another channel** chooser for compatible live
streams, and keeps clear loading and empty results. Campaigns can be filtered by linked or unlinked
status.

> This is an unofficial project and is not affiliated with Twitch. Twitch's private endpoints and
> persisted GraphQL operations can change without notice.

## Quick start

Requirements:

- Docker Engine with Docker Compose v2
- A Twitch account eligible for Drops campaigns

Start the service:

```bash
docker compose up --build -d
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080), choose **Connect Twitch**, and approve the device
code at the Twitch activation page. The app never asks for a Twitch password. A successful login
loads the Drops inventory automatically; there is no need to issue a separate refresh.

Repeated **Connect Twitch** actions do not replace a device code that is still being prepared or
polled. Use **Request a new code** only when you explicitly want to cancel the displayed attempt and
obtain a replacement.

On later container launches, a saved Twitch session triggers an inventory refresh in the background
as soon as the local service starts. Twitch or network delays do not block the health endpoint or web
UI from becoming available.

The default automatic fallback order exhausts linked work before trying unlinked work: linked
campaigns with claimed-drop progress, linked campaigns with viewing progress, linked campaigns with
no progress, then those same three groups for unlinked campaigns. The order remains configurable in
the web UI. Saved custom orders and game priorities are preserved even when a Twitch inventory
response is partial or temporarily omits a game. While a lower-ranked prioritized game is active, the
miner keeps checking earlier games in the saved order and promotes when one becomes live.

The unattended scheduler wakes for known campaign/drop starts, active-drop ends, settings changes,
channel controls, inventory deadlines, and claim-retry deadlines. Unlinked progress remains supervised
after its first increase: a sustained confirmed stall renews watch configuration, then tries another
channel, campaign, or fallback group without treating progress-endpoint failures as no-progress proof.

Stop it without deleting the saved session:

```bash
docker compose down
```

Follow service logs:

```bash
docker compose logs -f app
```

The host publication defaults to `127.0.0.1:8080`. Copy `.env.example` to `.env` to change the host
port. Direct JVM execution also listens on `127.0.0.1` by default; Compose explicitly listens on
`0.0.0.0` only inside the container while retaining the loopback host publication.

Every request validates its `Host` header, including health, state, events, and static assets.
Mutations additionally require a configured browser `Origin`, strict `application/json`, an exact
field schema, and a body no larger than 64 KiB. Reverse-proxy deployments must explicitly set both
`TWITCH_DROPS_TRUSTED_HOSTS` and `TWITCH_DROPS_TRUSTED_ORIGINS`; forwarded headers are ignored.

Operator-visible environment variables:

- `TWITCH_DROPS_BIND` — Compose host-publication interface; defaults to `127.0.0.1`.
- `TWITCH_DROPS_PORT` — host port in Compose and listener port for direct JVM execution; a nonnumeric
  nonblank value is a startup error.
- `TWITCH_DROPS_LISTEN_HOST` — direct JVM listener; defaults to `127.0.0.1`. Compose sets it to
  `0.0.0.0` inside the container.
- `TWITCH_DROPS_TRUSTED_HOSTS` — comma-separated accepted Host names or authorities. Bare names accept
  any port; entries with a port are exact. Keep `127.0.0.1` for the Compose health check.
- `TWITCH_DROPS_TRUSTED_ORIGINS` — comma-separated `http://` or `https://` browser origins. Exact
  origins are recommended; the explicit `:*` port form is intended for loopback Compose publication.
- `TWITCH_DROPS_SESSION_KEY` — optional base64-encoded 32-byte AES key supplied through a secret
  manager.
- `TWITCH_DROPS_JAVA_OPTS` — complete Compose JVM-profile override.

## Runtime footprint

The container defaults to a small-service JVM profile: Serial GC, a 16 MiB initial heap, a 256 MiB
maximum heap, and bounded metaspace, code cache, and direct memory. This keeps an idle always-on
instance compact while retaining headroom for campaign inventories and Twitch responses. Set
`TWITCH_DROPS_JAVA_OPTS` in `.env` only when an unusually large account needs different limits; an
override replaces the complete default JVM option string.

## What is persisted

The `twitch-dock-drops-data` named volume is mounted at `/data` and stores:

- encrypted Twitch session material and its local encryption key;
- runtime settings, game priorities, and campaign exclusions;
- the bounded local activity log.

Settings and sessions are written atomically with owner-only permissions where POSIX permissions are
available. Corrupt settings and locally encrypted sessions are quarantined instead of being silently
overwritten. A session encrypted under a different configured key is preserved so the correct key can
be restored. Saved priorities and exclusions are normalized, deduplicated, length-checked, and capped
before every write so a malformed imported settings file cannot grow the durable state without bound.
Safe diagnostics appear in the local log without file paths or secret contents.

**Verbose local logs** adds bounded debug entries for campaign/channel selection, watch-heartbeat
results, progress observations, and claim-retry scheduling. Log messages have normalized line breaks,
redacted credential labels, per-entry limits, and a bounded physical file size.

`docker compose down` preserves that volume. `docker compose down -v` deletes it and signs the app
out irreversibly unless the volume was backed up.

## Useful commands

```bash
# Build and test the JVM host with a local Gradle 9.5.1 installation
gradle clean test installDist

# Or run the complete container build, which includes tests
docker compose build

# Validate the resolved Compose configuration
docker compose config

# Rebuild after source changes
docker compose up --build -d

# Check container health
docker compose ps
```

No command for the root service should invoke a wrapper under `TwitchDropsMinerAndroid/`.

## Development and version control

The repository root uses Git on the `main` branch. `TwitchDropsMinerAndroid/` retains its independent
history and is tracked by the root repository as a submodule. Clone the complete project with:

```bash
git clone --recurse-submodules <repository-url>
```

For an existing clone whose Android reference has not been populated, run:

```bash
git submodule update --init
```

Before making changes, inspect `git status --short` and preserve any existing work. Keep root-host
changes in focused local commits after tests pass, and verify that the Android submodule remains clean
and at the same commit unless Android work was explicitly intended. Build output, local data,
credentials, sessions, keys, logs, and `.env` must remain untracked. Pushing, changing remotes, and
rewriting history are separate actions and should be performed only when explicitly intended.

## Security boundary

The web UI intentionally has no application-level password because the default Compose port is
loopback-only. Do not publish it on a LAN or public interface without an authenticated TLS reverse
proxy, explicit trusted hosts/origins, and firewall rules. The application never trusts
`Forwarded`/`X-Forwarded-*` headers. See [SECURITY.md](./SECURITY.md) for the complete threat model and
deployment guidance.

## Project documentation

- [ARCHITECTURE.md](./ARCHITECTURE.md) — process boundaries, root-owned core, persistence, and API flow
- [PROJECT_STATUS.md](./PROJECT_STATUS.md) — implementation checklist and known limitations
- [SECURITY.md](./SECURITY.md) — token storage and safe network exposure
- [AGENTS.md](./AGENTS.md) — required context and working rules for future coding agents
- [TwitchDropsMinerAndroid/README.md](./TwitchDropsMinerAndroid/README.md) — upstream Android behavior

## License

The Android source is provided under its existing license in
[`TwitchDropsMinerAndroid/LICENSE`](./TwitchDropsMinerAndroid/LICENSE). New root-host code follows the
same project licensing context.
