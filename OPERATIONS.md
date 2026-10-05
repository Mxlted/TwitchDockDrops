# Twitch Dock Drops Operator Guide

This guide contains deployment, networking, persistence, configuration, and maintenance details for
Twitch Dock Drops. For the user-facing project overview, start with the [README](./README.md).

## Requirements

- Docker Engine with Docker Compose v2
- A Twitch account eligible for Drops campaigns
- A supported browser on the Docker host or a trusted LAN device
- For desktop-helper login only: Node.js 22.4+ and native Chrome, Edge, or Chromium on a desktop that stays
  running for renewal (Windows, macOS, or Linux; browser launch has been checked on Windows)

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

The image is `dockdrops:local`, and the container and network are named `dockdrops`. The Compose
project identity and `app` service remain unchanged, so existing installations are upgraded with the
same command. The `twitch-dock-drops-data` volume keeps its name and contents. An old unused default
network may remain after upgrading; do not delete the data volume. Explicit container/network names
assume one instance per Docker host; override those names for multiple instances.

### Deployment branch

`main` is the full development repository. `deployment` is a deliberately published server snapshot
with the application, browser service, Docker/Compose files, Gradle build inputs, ignore rules, and
license notices. JVM tests stay because the Docker build runs them. Documentation, screenshots,
optional test harnesses, and all `.env` files are omitted.

For a new checkout (the destination must be absent or empty):

```bash
git clone --single-branch --branch deployment https://github.com/Mxlted/TwitchDockDrops.git /opt/TwitchDockDrops
```

Place your server's local `.env` in that checkout. To update it:

```bash
cd /opt/TwitchDockDrops
git pull --ff-only
docker compose -f compose.yaml -f compose.browser.yaml up --build -d
```

An existing Git checkout can switch with `git fetch origin` followed by
`git switch --track origin/deployment`. Preserve local edits before switching; uploaded folders
without a `.git` directory need a Git checkout first. Ignored `.env` files remain local.

Changes on `main` do not automatically publish to `deployment`. Refresh the deployment snapshot
deliberately, retaining its exclusions; do not merge the full development tree into the server branch.

### Debian Docker inside Proxmox LXC

Use a Debian container with Docker Engine and the Compose plugin installed following
[Docker's Debian instructions](https://docs.docker.com/engine/install/debian/). In the Proxmox
container's **Options → Features**, enable nesting and, for an unprivileged container, keyctl while
preserving other selected features. These are host prerequisites for nested Docker; see the
[Proxmox container feature reference](https://github.com/proxmox/pve-docs/blob/master/generated/pct.conf.5-opts.adoc).
Restart the LXC after changing its features. Keep the application's Compose security settings intact.

Run the two-file browser-login command below inside Debian. The browser's display is supplied by
Xvfb in Docker, so Debian needs no desktop environment, attached monitor, or GPU passthrough. Open
`http://<debian-lxc-ip>:8080` from your trusted LAN after applying `.env.example`; use the LXC address,
not the Proxmox management address. Allow that port through any LXC/host firewall rules you use.
Ensure the LXC has enough memory for the 1 GiB browser limit plus the JVM, Docker and Debian; allow
additional memory during image builds. Keep the host clock synchronized for Twitch proof expiry.

Verify deployment with:

```bash
docker compose -f compose.yaml -f compose.browser.yaml config --quiet
docker compose -f compose.yaml -f compose.browser.yaml up --build -d
docker compose -f compose.yaml -f compose.browser.yaml ps
curl --fail http://127.0.0.1:8080/api/health
```

The images and login browser have been exercised on Docker Desktop's Linux/amd64 engine. The actual
Proxmox LXC host, its kernel restrictions, and real-account login require verification on that host.

### Browser login

Connect Twitch offers two browser-based options. The old device-code endpoint can return HTTP 400;
changing client IDs does not restore private Drops access. Existing valid Android sessions remain
supported. The previous dashboard login and authenticated campaign loading were verified with a live
account on Linux Docker on 2026-10-04. The durable SDK renewal introduced on 2026-10-05 and
desktop-helper account acceptance still need live verification.

#### Dashboard login (no desktop download)

From your updated repository checkout, enable the optional browser service:

```bash
docker compose -f compose.yaml -f compose.browser.yaml up --build -d
```

Set `TZ` in `.env` to the timezone of your home connection; the example default is `America/New_York`.
Use both Compose files for subsequent `up`, `build`, `logs`, and `down` commands for this deployment.
The browser shares the app's network namespace, so recreate both services together after changing
ports or network settings. It adds no published port and does not mount the miner's data volume.

1. Open DockDrops, select **Connect Twitch → Open dashboard login → Start sign-in**.
2. Sign in on Twitch in the displayed browser. Complete email or two-factor verification there.
   Click a field and type; mobile users can use **Mobile keyboard / paste** after selecting a field.
   The browser panel scrolls horizontally on narrow screens; Zoom and Scroll up/down are available.
3. Select **Finish sign-in** only after Twitch confirms login. The miner verifies OAuth identity and
   both private Drops queries before accepting the session. Interactive login has an eight-minute limit.
   Finish collects Drops proof and a scoped SDK cookie in the signed-in browser. If the cookie is
   missing, it obtains one in a separate temporary regular browser profile before verifying independent
   issuance in a fresh temporary browser. Allow up to two minutes for capture, up to two and a half
   minutes each for optional seed preparation and independent issuance, plus account/Drops validation.
   A failure identifies whether capture, seed preparation, independent issuance, or server verification failed.
   SDK failures distinguish loading/initialization, timeout, network fetch, rejected response, missing
   usable cookie and unverifiable proof. These fixed messages contain no credentials or upstream bodies.
4. After **Connected**, return to the dashboard. The browser service handles renewal; this page and
   your computer can be closed while Docker keeps running.

The service uses a temporary profile on a 512 MiB tmpfs, 256 MiB shared memory, a 1 GiB memory limit,
and at most 256 processes. These are limits, not steady-state requirements. It uses Chromium/Xvfb.
Missing-cookie preparation briefly runs a second headed browser with a separate temporary profile;
both remain inside the same container limits. It uses regular storage because Incognito blocks
third-party cookies by default, including the SDK host's cookie when loaded from a Twitch page.
There is no Android emulator or remote desktop port. The login viewer relays screenshots, bounded
text, pointer clicks, and navigation keys through the same-origin JVM API. It does not offer arbitrary
browser commands, downloads, drag gestures, popup-based social sign-in, passkeys, or OS dialogs. Use the desktop fallback if a
Twitch challenge requires those interactions. Firefox is not supported.

**After upgrading from the previous login method, reconnect once through dashboard login.** New
logins save one scoped SDK renewal cookie alongside the context in the encrypted miner session.
The interactive browser closes after capture; disposable headless browsers issue replacements. The
runtime starts renewal five minutes before proof expiry and retries temporary failures with backoff.
Closing the dashboard or restarting the JVM/browser service no longer inherently ends renewal.
Keep the same data volume and key and keep both services available. Extended downtime beyond the
SDK cookie's expiry (normally about a day in upstream's observations), revoked OAuth, or a Twitch
challenge can still require sign-in. This is not a guarantee of indefinite login. Existing sessions
without a seed remain readable but still require reconnecting to gain durable renewal. Cancel stops
interactive login without deleting the saved credential; **Reset Twitch Session** signs the miner out.

If startup fails, check `docker compose -f compose.yaml -f compose.browser.yaml logs browser` and
confirm the browser service is running. Health remains a check of the JVM's local readiness, independent
of this optional service and Twitch. If Twitch itself rejects the container browser as unsupported,
use the desktop helper; changing client IDs or disabling container protections does not repair that.

#### Desktop helper (fallback)

1. Select **Connect Twitch → Use desktop helper**. Download `login-helper.mjs` from your dashboard to your desktop.
2. In that directory run `node login-helper.mjs`. Enter the dashboard origin (for example
   `http://192.168.1.20:8080`) and the displayed pairing code. Complete pairing and login within ten minutes.
3. Sign in directly on Twitch in the new browser, including any Twitch verification. Close all windows
   of that temporary browser when finished; your everyday profile is not used.
4. The helper reopens a regular browser briefly, captures a fresh authenticated Drops request, and
   sends its context directly to this server. The JVM validates the account and both Drops queries
   before saving the encrypted session and loading campaigns.
5. Leave the helper running. It periodically reopens its temporary profile to renew integrity proof.
   Closing the dashboard is fine; closing the helper or sleeping its computer interrupts renewal.

No password or access token is pasted into the dashboard. The helper needs no npm packages. To use a
specific browser, run `node login-helper.mjs "http://127.0.0.1:8080" "/path/to/browser"`. Run
`node login-helper.mjs --check-browser` to check isolated browser launch and local control without a
Twitch login. The helper deletes its temporary profile on normal exit; abrupt termination can leave
a `dockdrops-login-*` folder in the operating system's temporary directory.

After a server restart, helper failure, or expired proof, select **Reconnect Twitch** in Settings and
run the helper again. New pairing, session reset, and server restart revoke the previous helper
connection. The persisted session can be restored only within the captured proof's lifetime until
renewal resumes. In this lightweight deployment, renewal requires the desktop helper.
The upstream Python helper uses a different protocol and cannot connect here. Firefox is not supported
by this helper. Native browser login and network access from the miner must both be accepted by Twitch;
different VPN/proxy routes can cause server-side verification to fail.

The previous HTTP 400 device-login error is avoided by using browser login. Switching client IDs
cannot repair that old endpoint. Working saved Android sessions remain supported. Keep the session
and its key; do not reset or delete the volume for temporary 403/integrity errors. Replacement keeps
the previous credential until successful atomic save, and a failed replacement can restore it after
restart. Explicit **Reset Twitch Session** still deletes it. Desktop-helper account acceptance, unattended
renewal, earning, and claims remain unverified; see [Project Status](./PROJECT_STATUS.md).

The helper now skips unrelated GraphQL bodies and Chromium's discarded response-body error (`-32000`)
while still requiring complete matching proof and campaign evidence. Other failures report the CDP
command name and numeric code without raw browser diagnostics. This addresses a likely cause of the
old generic **Browser command failed** message; that old message cannot identify the precise command.
Download the updated helper after rebuilding. If it still fails, run `--check-browser` and try an
explicit Edge/Chrome executable. Users with a trusted repository checkout can run
`node src/main/resources/web/login-helper.mjs` directly without a separate download.

The dashboard observes integrity issuance before sign-in and completes campaign capture in the login
browser. It now carries the scoped SDK seed into independent server issuance, following upstream's
`server_seed.py` and `server_renewal.py` at `1182d0172458`. Simply opening an empty headless browser or
refreshing `/integrity` over plain HTTP does not establish accepted Drops access. The desktop helper
continues to use regular browser captures and must stay running. Every accepted context still passes
the server's account, Inventory, and Campaigns checks. Upstream documents the distinction in its
[authentication investigation](https://github.com/rangermix/TwitchDropsMiner/issues/118).

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

For restarts and maintenance, the last explicit **Start** or **Stop** choice is stored as
`miningRequested` in `settings.json`. It is honored after container restarts and after a Twitch
re-login, while process shutdown itself does not count as pressing **Stop**.
If that settings write fails, the current Start or Stop still takes effect and a warning is logged,
but the choice cannot survive the next restart.

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
| `TWITCH_DROPS_DASHBOARD_LOGIN` | Enables the optional browser bridge. Defaults to `false`; the browser Compose override sets `true`. Requires the companion on loopback port 8091. |
| `TZ` | Browser-service timezone; set it to match your home connection. Defaults to `America/New_York` in the override. |
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

On startup, a saved Twitch session resumes mining when `miningRequested` is enabled; otherwise it
triggers an inventory refresh in the background. Neither path delays the local health endpoint or web
UI.

The account card on Overview and Settings identifies the connected Twitch account by username and
numeric ID. New logins retain the username in the encrypted session. Older sessions initially show
their ID and load the username during mining validation or after an inventory refresh. If Twitch
cannot return it, the ID remains visible; reconnecting is not required solely to show the card.

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

Use **Campaigns → Game & category priorities** to save games before they have an active campaign.
Select **All Twitch categories**, enter 2–100 characters, and press Enter or **Search Twitch**.
Two- or three-character searches load at most 12 matching categories. Four or more characters let you
browse all matches available from Twitch using **Previous/Next**, with up to 50 per page, including
games with no campaign. There is no app-imposed total result cutoff for these longer searches.
Select **+ Add** to save the exact returned name. No Twitch login or additional API credentials are
needed for this public lookup. Searches run only when submitted, not as you type. Additional pages
load only when requested; the app does not crawl the full catalog or poll for changes. The server
caches up to 32 query/page combinations for five minutes and permits two concurrent lookups, each
limited to 15 seconds. Repeated searches and navigation reuse those cached pages until they expire
or are evicted; restarting the app clears the search cache. Refine the name to reduce broad results.
Loaded/saved, active, and linked scopes still search locally (at most eight displayed matches) and
allow manual exact-name entry if Twitch search is unavailable.
Positions are one-based; arrows or a position number reorder the same persistent list. Missing games
remain saved and do not prevent later eligible priorities from running. New campaigns match the saved
name case-insensitively. Twitch category renames require updating the saved name manually.
The list allows up to 500 games. Adding to a full list returns an error without removing any saved
game. Reset settings and reset session both clear this list, as before.

Known campaign/drop start times also wake promotion checks while another game is being watched.
Campaigns newly published by Twitch are discovered at the configured inventory refresh or by manual
refresh. The **Up next** list previews server-ranked eligible campaigns, not a guaranteed live channel.

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

Client rendering regressions use Node's built-in test runner (no package installation):

```bash
node --test src/test/js/*.cjs src/test/js/*.mjs
node --check src/main/resources/web/app.js
```

An optional offline integration test exercises real Chromium capture across login/navigation and
renewal using synthetic responses. After building `dockdrops-browser:local`, run from the repository
root on Linux (Docker networking is disabled; no Twitch account is used):

```bash
docker run --rm --init --network none --read-only \
  --tmpfs /tmp:size=512m,mode=1777 --shm-size=256m --memory=1g --pids-limit=256 \
  --cap-drop=ALL --security-opt=no-new-privileges \
  -e DOCKDROPS_BROWSER_TEST=1 \
  --mount "type=bind,src=$(pwd)/browser/capture-smoke.test.mjs,dst=/app/browser/capture-smoke.test.mjs,readonly" \
  --entrypoint xvfb-run dockdrops-browser:local \
  --server-args="-screen 0 1100x850x24 -nolisten tcp" node --test browser/capture-smoke.test.mjs
```

Keep `--init`: Xvfb's startup signalling requires it when launched this way in a container.

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
