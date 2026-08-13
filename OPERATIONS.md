# Twitch Dock Drops Operator Guide

This guide contains deployment, networking, persistence, configuration, and maintenance details for
Twitch Dock Drops. For the user-facing project overview, start with the [README](./README.md).

## Requirements

- Docker Engine with Docker Compose v2
- A Twitch account eligible for Drops campaigns
- A supported browser on the Docker host or a trusted LAN device

## Install and start

```bash
git clone https://github.com/Mxlted/TwitchDockDrops.git
cd TwitchDockDrops
cp .env.example .env
docker compose up --build -d
```

On PowerShell, use:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
```

Docker Compose loads `.env` from the project directory automatically. The supplied example publishes
the app on port 8080 to the Docker host and trusted private LAN.

Open one of these addresses:

- Docker host: [http://127.0.0.1:8080](http://127.0.0.1:8080)
- Trusted LAN device: `http://<docker-host-lan-ip>:8080`

Select **Connect Twitch** and approve the device code at Twitch's activation page. The app never asks
for a Twitch password. A completed login immediately loads the campaign inventory.

Repeated **Connect Twitch** actions do not replace a device code that is still being prepared or
polled. Use **Request a new code** only when you want to cancel the displayed attempt and obtain a
replacement.

## Everyday commands

```bash
# Start or rebuild in the background
docker compose up --build -d

# Show container and health status
docker compose ps

# Follow application logs
docker compose logs -f app

# Stop without deleting the saved Twitch session
docker compose down

# Validate the resolved Compose configuration
docker compose config
```

`docker compose down -v` also deletes the persistent volume. That removes the encrypted session,
settings, priorities, exclusions, and activity log, and signs the app out irreversibly unless the
volume was backed up.

## Network access

### Trusted LAN setup

Copying `.env.example` to `.env` sets:

```dotenv
TWITCH_DROPS_BIND=0.0.0.0
TWITCH_DROPS_ALLOW_LAN=true
TWITCH_DROPS_PORT=8080
```

This publishes the port on every host IPv4 interface. The server accepts literal RFC 1918,
link-local, and IPv6 unique-local destination addresses. Mutation requests remain same-origin: the
browser Origin must match the requested server IP and port.

LAN mode does not authenticate clients. Every device that can reach the port can control the miner
and its saved Twitch session. Use it only on a trusted private network, keep host firewall rules in
place, and never configure router port forwarding for the service.

If another device cannot connect, confirm that it is on the same network, use the Docker host's LAN
IP rather than `127.0.0.1`, and allow inbound TCP port 8080 on the host's private-network firewall
profile.

### Loopback-only setup

For access only from the Docker host, either run Compose without `.env` or change these values:

```dotenv
TWITCH_DROPS_BIND=127.0.0.1
TWITCH_DROPS_ALLOW_LAN=false
```

The Compose defaults are loopback-only when those variables are absent.

### Reverse proxy setup

Do not expose the service directly to the internet. Use an authenticated HTTPS reverse proxy and
firewall rules, then configure the exact external name and origin:

```dotenv
TWITCH_DROPS_BIND=127.0.0.1
TWITCH_DROPS_ALLOW_LAN=false
TWITCH_DROPS_TRUSTED_HOSTS=127.0.0.1,localhost,[::1],drops.example.com
TWITCH_DROPS_TRUSTED_ORIGINS=https://drops.example.com
```

Keep `127.0.0.1` in the trusted Host list for the container health check. The application ignores
`Forwarded`, `X-Forwarded-Host`, and related headers. See [SECURITY.md](./SECURITY.md) for the complete
deployment boundary.

## Environment reference

| Variable | Purpose |
| --- | --- |
| `TWITCH_DROPS_BIND` | Host interface used by Compose port publication. Defaults to `127.0.0.1`. |
| `TWITCH_DROPS_PORT` | Host port in Compose and listener port for direct JVM execution. Defaults to `8080`. |
| `TWITCH_DROPS_LISTEN_HOST` | Direct JVM listener. Defaults to `127.0.0.1`; Compose sets `0.0.0.0` inside the container. |
| `TWITCH_DROPS_ALLOW_LAN` | Accepts literal private/link-local Host addresses and matching HTTP origins. Defaults to `false`. |
| `TWITCH_DROPS_TRUSTED_HOSTS` | Comma-separated accepted Host names or authorities. Bare names accept any port; entries with a port are exact. |
| `TWITCH_DROPS_TRUSTED_ORIGINS` | Comma-separated accepted `http://` or `https://` browser origins. The explicit `:*` form accepts any port. |
| `TWITCH_DROPS_SESSION_KEY` | Optional base64-encoded 32-byte AES key supplied through a secret manager. |
| `TWITCH_DROPS_JAVA_OPTS` | Complete override for the Compose JVM profile. |

A nonnumeric, nonblank `TWITCH_DROPS_PORT` is a startup error. Do not store a real
`TWITCH_DROPS_SESSION_KEY` in a committed `.env` file.

## Persistent data

The `twitch-dock-drops-data` named volume is mounted at `/data` and stores:

- encrypted Twitch session material and its local encryption key;
- normalized runtime settings, game priorities, and campaign exclusions;
- the bounded local activity log.

Settings and sessions use atomic replacement and owner-only permissions where POSIX permissions are
available. Corrupt settings and locally encrypted sessions are quarantined rather than silently
overwritten. A session encrypted under a different configured key is preserved so the correct key can
be restored.

By default, the random encryption key is stored beside the encrypted session in the private Docker
volume. For stronger separation, provide `TWITCH_DROPS_SESSION_KEY` through a secret manager. The key
must decode to exactly 32 bytes.

Verbose local logs add bounded diagnostics for campaign/channel selection, watch heartbeats,
progress observations, and claim retries. Logs can contain Twitch user IDs, campaign names, and
channel names, but must never contain access tokens, device-code secrets, encryption keys, or raw
session data.

## Runtime behavior

On startup, a saved Twitch session triggers an inventory refresh in the background without delaying
the local health endpoint or web UI.

The default Auto Mode order exhausts linked work before unlinked work:

1. Linked campaigns with claimed-drop progress
2. Linked campaigns with viewing progress
3. Linked campaigns with no progress
4. Unlinked campaigns with claimed-drop progress
5. Unlinked campaigns with viewing progress
6. Unlinked campaigns with no progress

The order is configurable. Saved custom orders and priorities survive partial Twitch inventory
responses. While a lower-ranked prioritized game is active, the miner periodically checks earlier
games and promotes when a compatible channel becomes available.

The unattended scheduler wakes for campaign/drop starts and ends, settings changes, channel controls,
inventory deadlines, heartbeat deadlines, and claim retries. A sustained confirmed progress stall
renews watch configuration and then tries another channel or campaign. Progress endpoint failures are
not treated as proof of a stall.

The miner sends authenticated `minute-watched` events to the narrowly allowlisted Twitch collector
discovered from the current Twitch configuration. It does not download or play stream video/audio.
For implementation details, see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Runtime footprint

The container uses a small-service JVM profile with Serial GC, a 16 MiB initial heap, a 256 MiB
maximum heap, and bounded metaspace, code cache, and direct memory. An override through
`TWITCH_DROPS_JAVA_OPTS` replaces the complete option string.

## Build and verification

The root project requires JDK 21 and Gradle 9.5.1 for local builds:

```bash
gradle clean test installDist
```

The complete image build also runs the test and install-distribution tasks:

```bash
docker compose config
docker compose build
```

No root command needs or invokes an Android Gradle wrapper. The optional local
`TwitchDropsMinerAndroid/` checkout is ignored by both Git and Docker and is not part of this
repository.

## More documentation

- [README.md](./README.md) — project showcase and user quick start
- [SECURITY.md](./SECURITY.md) — threat model and safe deployment
- [ARCHITECTURE.md](./ARCHITECTURE.md) — internal design and failure behavior
- [PROJECT_STATUS.md](./PROJECT_STATUS.md) — verification history and known limitations
