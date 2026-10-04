<div align="center">
  <img src="src/main/resources/web/favicon.svg" width="96" alt="Twitch Dock Drops icon">
  <h1>Twitch Dock Drops</h1>
  <p><strong>Set it once. Let your Drops grow.</strong></p>
  <p>
    A self-hosted Twitch Drops farmer with campaign tracking, channel failover, progress supervision,
    and automatic claiming from a single web dashboard.
  </p>
  <p>
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-JVM-8b7cf6?style=flat-square">
    <img alt="Docker Compose" src="https://img.shields.io/badge/Docker-Compose-78b9e8?style=flat-square">
    <img alt="Responsive web UI" src="https://img.shields.io/badge/Web_UI-Desktop_%26_Mobile-9146ff?style=flat-square">
    <img alt="MIT License" src="https://img.shields.io/badge/License-MIT-f0b891?style=flat-square">
  </p>
</div>

![Twitch Dock Drops dark dashboard showing drop progress, campaign rewards, recent activity, and the Up next queue](docs/twitch-dock-drops-overview.jpg)

*Current dashboard with built-in preview data. Campaigns, rewards, and activity are illustrative.*

Twitch Dock Drops quietly farms timed Twitch Drops without playing or downloading the stream. Run it
in Docker, connect your Twitch account through dashboard login or the desktop browser helper, choose the games
you care about, and leave the miner to handle the rest.

> [!IMPORTANT]
> Twitch Dock Drops is unofficial and is not affiliated with Twitch. Twitch can change its private
> Drops endpoints at any time.

## Why Twitch Dock Drops?

- **No stream playback:** earns timed progress without downloading video or audio.
- **Campaign tracking:** refreshes campaigns and keeps the inventory current.
- **Channel hunting and recovery:** finds compatible live channels and moves on when progress stalls.
- **Persistent game priorities:** save categories before their next campaign, set their order, and let
  Auto Mode find useful fallback work.
- **Automatic claiming:** attempts to claim completed Drops and retries temporary failures.
- **Pick up after restarts:** restores your encrypted login, preferences, priorities, and saved
  Start/Stop choice.
- **A real dashboard:** watch progress, browse campaigns, switch channels, and review activity from
  one UI.
- **Desktop and mobile friendly:** use the responsive dark or light dashboard from any trusted
  LAN device.
- **Built for Docker:** one hardened, non-root container with a read-only root filesystem and durable
  data volume.

## Quick start

For a minimal server checkout, use the [`deployment` branch](https://github.com/Mxlted/TwitchDockDrops/tree/deployment).
It contains the build/runtime inputs and license notices; keep your `.env` local. See the
[deployment branch workflow](./OPERATIONS.md#deployment-branch) for cloning and updates.

You need [Docker](https://docs.docker.com/get-docker/) with Docker Compose and a Twitch account that
can participate in Drops campaigns. The optional browser service lets you sign in directly through the
dashboard without downloading a helper or installing Node.js on your computer.

```bash
git clone https://github.com/Mxlted/TwitchDockDrops.git
cd TwitchDockDrops
cp .env.example .env
docker compose -f compose.yaml -f compose.browser.yaml up --build -d
```

On PowerShell, use `Copy-Item .env.example .env` instead of `cp`.

Set `TZ` in `.env` to your local timezone (for example, `America/New_York`). The browser service has a
1 GiB memory limit and adds Chromium to a separate image. For the lightweight desktop-helper setup,
omit `-f compose.yaml -f compose.browser.yaml`. That option needs Node.js 22.4+ and Chrome, Edge, or
Chromium on a desktop that stays running. See [login options](./OPERATIONS.md#browser-login).

Then open:

- **This computer:** [http://127.0.0.1:8080](http://127.0.0.1:8080)
- **Another device on your LAN:** `http://<docker-host-lan-ip>:8080`

The supplied `.env.example` enables trusted-LAN access. Anyone on that LAN who can reach the port can
control the miner, so use it only on a private network and never port-forward it to the internet. See
the [Operator Guide](./OPERATIONS.md#network-access) for loopback-only and reverse-proxy setups.

## Start farming in three steps

1. Select **Connect Twitch** in the dashboard.
2. Choose **Open dashboard login**, then **Start sign-in**. Sign in to Twitch in the displayed browser,
   finish verification, and select **Finish sign-in**. **Use desktop helper** remains available as a fallback.
3. Choose game priorities or leave Auto Mode in charge, then start the miner.

Twitch Dock Drops refreshes your campaigns, chooses an eligible live channel, reports watch progress,
recovers from stalled channels, and claims completed Drops. Your saved session is restored after a
container restart, and the miner resumes automatically if it was running before the restart. Browser
sessions need fresh integrity proof: reconnect after restarting the server or browser service so renewal continues.
See [browser login](./OPERATIONS.md#browser-login) for setup and troubleshooting.
For Debian running inside Proxmox LXC, see the [LXC setup notes](./OPERATIONS.md#debian-docker-inside-proxmox-lxc).

## The dashboard

The interface is a flat, Twitch-purple dashboard that keeps the important parts close:

- **Header** shows the miner state on every page with Start/Stop, refresh, and theme controls.
- **Overview** shows a compact signed-in account card with your Twitch username, account ID, and
  sign-in method, plus session stats, the current Drop with progress and an estimated finish time,
  every drop in the active campaign, recent activity, and the server-ranked **Up next** queue.
- **Campaigns** lets you search, filter, and sort active or upcoming campaigns, expand their drops and
  rewards, check end dates, open account-link pages, and exclude campaigns. Campaign names link to
  their Twitch campaign in a new tab, including in Now watching and Up next. Lists use 24-row pages.
  **Open Reward Campaigns** appears in a compact panel beside the list on wide desktops and below it
  on smaller screens. Browse four reward campaigns per page, expand **Details** for descriptions and
  all reward names, or hide the panel contents. These promotions are view-only; complete any earning
  or redemption steps on Twitch.
- **Game & category priorities** saves favorites even without a current campaign. Add an exact Twitch
  category name, reorder with arrows or a rank number, and keep its place when a new campaign returns.
  **All Twitch categories** searches Twitch on demand, including games without Drops campaigns or
  a connected account. Enter at least two characters and select **Search Twitch**, then **+ Add**.
  Two- or three-character searches show up to 12 results. Four or more characters unlock all matches
  available from Twitch through **Previous/Next**, 50 per page. Pages load only on request and are
  cached for five minutes. You can also search loaded/saved categories or narrow to active or linked
  campaigns.
- **Activity** explains what the miner selected, refreshed, watched, or claimed, above the runtime log.
- **Settings** controls timing, Auto Mode order, fallback behavior, and resets, and reports the
  service version, uptime, and Twitch connection.
- **Switch channel** opens a compatible live-channel picker. The current channel keeps running until
  you select an alternative.
- Views are bookmarkable (`/#campaigns`), and an offline banner appears if the local host stops
  responding.

The miner keeps lifecycle work on the server. Closing the dashboard does not stop farming; keep the
browser service or selected desktop login helper running to renew browser sessions.
**Up next** follows your saved priorities, exclusions, campaign eligibility, and fallback order;
live-channel availability determines what can actually run.

To explore without connecting Twitch, select **Explore with preview data** on the welcome screen
or open `/?preview=active` on your instance. Preview controls do not change the miner.

## Good to know

- Your Twitch account must be eligible for the campaign, and some rewards require linking the related
  game account.
- Avoid manually watching Twitch on the same account while mining; simultaneous viewing can confuse
  progress reporting.
- Unlinked-campaign farming is optional and treated as speculative until Twitch confirms real progress.
- Private Twitch behavior can change, so review the [Project Status](./PROJECT_STATUS.md) when troubleshooting.
- Connect Twitch uses browser login because Twitch can reject the older device-code flow. Existing
  sessions are preserved. Live account acceptance of either browser option has not yet been verified.
- Local activity logs can contain campaign and channel names; review them before sharing.

## Documentation

- [Operator Guide](./OPERATIONS.md): deployment, networking, environment variables, data, and commands
- [Security](./SECURITY.md): session storage, browser protections, and safe exposure
- [Architecture](./ARCHITECTURE.md): runtime, API, scheduling, and Twitch integration details
- [Project Status](./PROJECT_STATUS.md): completed work, verification history, and known limitations
- [Android companion project](https://github.com/Mxlted/TwitchDropsMinerAndroid): the separate mobile edition

## Credits

Twitch Dock Drops is an independent project inspired by the Twitch Drops mining community, including
[rangermix/TwitchDropsMiner](https://github.com/rangermix/TwitchDropsMiner) and the original
[DevilXD/TwitchDropsMiner](https://github.com/DevilXD/TwitchDropsMiner). The separate
[TwitchDropsMinerAndroid](https://github.com/Mxlted/TwitchDropsMinerAndroid) project serves as a
behavioral reference for this JVM/web edition.

## License

Twitch Dock Drops is available under the [MIT License](./LICENSE).
