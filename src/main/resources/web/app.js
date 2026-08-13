const ui = {
  view: "overview",
  data: null,
  campaignFilter: "all",
  campaignSearch: "",
  preview: new URLSearchParams(window.location.search).has("preview"),
  connected: false,
  eventSource: null,
  pollTimer: null,
  pendingCommands: new Set(),
  renderedView: null,
  renderedMarkup: null,
  theme: document.documentElement.dataset.theme === "light" ? "light" : "dark",
  channelPickerOpen: false,
  channelPickerLoading: false,
};

const app = document.querySelector("#app");
const pageTitle = document.querySelector("#pageTitle");
const pageEyebrow = document.querySelector("#pageEyebrow");
const connectionPill = document.querySelector("#connectionPill");
const campaignCount = document.querySelector("#campaignCount");
const previewPill = document.querySelector("#previewPill");
const confirmDialog = document.querySelector("#confirmDialog");
const themeButton = document.querySelector("#themeButton");
const serviceStatus = document.querySelector("#serviceStatus");
const titleByView = {
  overview: ["Overview", "Your drop garden"],
  campaigns: ["Campaigns", "Choose what grows first"],
  activity: ["Activity", "A quiet record of the work"],
  settings: ["Settings", "Shape the runtime"],
};

document.addEventListener("DOMContentLoaded", boot);
document.addEventListener("click", handleClick);
document.addEventListener("input", handleInput);
document.addEventListener("change", handleChange);

async function boot() {
  renderThemeToggle();
  if (ui.preview) {
    ui.data = previewState();
    ui.connected = true;
    previewPill.hidden = false;
    render();
    return;
  }

  await loadState();
  connectEvents();
}

function startPolling() {
  if (ui.pollTimer) return;
  loadState();
  ui.pollTimer = window.setInterval(loadState, 15000);
}

function stopPolling() {
  if (!ui.pollTimer) return;
  window.clearInterval(ui.pollTimer);
  ui.pollTimer = null;
}

async function loadState() {
  try {
    const response = await fetch("/api/state", { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`Local host returned ${response.status}`);
    ui.data = await response.json();
    ui.connected = true;
    render();
  } catch (error) {
    ui.connected = false;
    renderConnection();
    if (!ui.data) {
      app.innerHTML = renderUnavailable(error.message);
      app.setAttribute("aria-busy", "false");
    }
  }
}

function connectEvents() {
  if (!("EventSource" in window)) {
    startPolling();
    return;
  }
  ui.eventSource?.close();
  ui.eventSource = new EventSource("/api/events");
  ui.eventSource.addEventListener("state", (event) => {
    try {
      ui.data = JSON.parse(event.data);
      ui.connected = true;
      stopPolling();
      render();
    } catch {
      toast("A state update could not be read.", true);
    }
  });
  ui.eventSource.onerror = () => {
    startPolling();
  };
}

function render() {
  if (!ui.data) return;
  const [title, eyebrow] = titleByView[ui.view];
  pageTitle.textContent = title;
  pageEyebrow.textContent = eyebrow;
  campaignCount.textContent = String(ui.data.snapshot.campaigns.length);
  document.querySelectorAll("[data-view]").forEach((button) => {
    const isActive = button.dataset.view === ui.view;
    button.classList.toggle("is-active", isActive);
    if (isActive) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  document.querySelector(".topbar [data-action='refresh']").disabled = !ui.data.snapshot.account.authenticated;
  renderConnection();

  const view = {
    overview: renderOverview,
    campaigns: renderCampaigns,
    activity: renderActivity,
    settings: renderSettings,
  }[ui.view];
  const markup = view(ui.data);
  const viewChanged = ui.renderedView !== ui.view;
  if (!viewChanged && ui.renderedMarkup === markup) return;

  app.innerHTML = markup;
  app.setAttribute("aria-busy", "false");
  if (viewChanged) app.firstElementChild?.classList.add("is-entering");
  ui.renderedView = ui.view;
  ui.renderedMarkup = markup;
}

function renderConnection() {
  const label = ui.preview ? "Preview" : ui.connected ? "Local host" : "Reconnecting";
  connectionPill.className = `connection-pill ${ui.connected ? "is-online" : "is-offline"}`;
  connectionPill.querySelector("span:last-child").textContent = label;
  serviceStatus.textContent = ui.preview ? "Preview only" : ui.connected ? "Host reachable" : "Reconnecting";
}

function renderOverview(data) {
  const snapshot = data.snapshot;
  const account = snapshot.account;
  const authenticated = account.authenticated;
  const waitingForCode = account.actionRequired && account.oauthCode;
  const preparingLogin = !account.authenticated && !account.oauthCode &&
    ["connecting", "authenticating"].includes(String(snapshot.phase || "").toLowerCase());
  const activeCampaign = snapshot.activeCampaign;
  const activeDrop = snapshot.activeDrop;
  const activeChannel = snapshot.currentChannel;
  const campaigns = snapshot.campaigns;
  const claimable = campaigns.reduce(
    (total, campaign) => total + campaign.drops.filter((drop) => drop.canClaim && !drop.claimed).length,
    0,
  );
  if (!snapshot.miningActive || !activeCampaign || !activeDrop) {
    ui.channelPickerOpen = false;
    ui.channelPickerLoading = false;
  }

  const hero = waitingForCode
    ? renderLoginCodeHero(account)
    : preparingLogin
      ? renderLoginPreparingHero()
    : authenticated
      ? renderMinerHero(snapshot)
      : renderWelcomeHero();

  return `
    <div class="page-stack">
      ${snapshot.error ? renderError(snapshot.error) : ""}
      ${hero}
      <div class="stat-grid">
        ${renderStat("Drops gathered", snapshot.dropsClaimedThisSession, "this container session", "mint", dropletIcon())}
        ${renderStat("Active campaigns", snapshot.activeCampaignCount, `${campaigns.length} loaded from Twitch`, "lilac", bloomIcon())}
        ${renderStat("Ready to claim", claimable, claimable ? "the miner will claim these" : "nothing waiting right now", "peach", giftIcon())}
      </div>
      <div class="grid-two">
        <section class="soft-card section-card">
          <div class="section-head">
            <div><h2>Growing now</h2><p>The campaign and drop currently receiving watch progress.</p></div>
            ${snapshot.miningActive ? '<span class="soft-chip"><span class="status-dot"></span>heartbeat active</span>' : ""}
          </div>
          ${activeCampaign && activeDrop ? renderWatchCard(activeCampaign, activeDrop, activeChannel, snapshot.channels, snapshot.channelSearchInProgress) : renderEmptyWatch(authenticated)}
        </section>
        <section class="soft-card section-card">
          <div class="section-head">
            <div><h2>Recent tending</h2><p>The latest changes from the miner.</p></div>
            <button class="tiny-button" data-view="activity" type="button">See all</button>
          </div>
          ${renderTimeline(snapshot.activity.slice(-5).reverse(), true)}
        </section>
      </div>
      <section class="soft-card section-card">
        <div class="section-head">
          <div><h2>Campaign bed</h2><p>A quick look at the most relevant campaigns in your inventory.</p></div>
          <button class="button button-quiet" data-view="campaigns" type="button">Open campaigns</button>
        </div>
        ${campaigns.length ? `<div class="campaign-list">${campaigns.slice(0, 4).map((campaign) => renderCampaignRow(campaign, data.settings, true)).join("")}</div>` : renderEmptyCampaigns(authenticated)}
      </section>
    </div>`;
}

function renderWelcomeHero() {
  return `
    <section class="hero">
      <div class="hero-copy">
        <p class="hero-kicker">Container awake</p>
        <h2>Let your drops <em>gather softly.</em></h2>
        <p>Connect Twitch with a one-time device code. The miner stays in this container, follows eligible campaigns, sends watch heartbeats, and claims completed drops.</p>
        <div class="hero-actions">
          <button class="button button-primary" data-action="connect" type="button">${linkIcon()} Connect Twitch</button>
          <a class="button button-quiet" href="/?preview=1">Explore with preview data</a>
        </div>
      </div>
      ${renderDropOrbit()}
    </section>`;
}

function renderLoginCodeHero(account) {
  const activationUrl = safeUrl(account.oauthUrl) || "https://www.twitch.tv/activate";
  return `
    <section class="hero">
      <div class="hero-copy">
        <p class="hero-kicker">One small step outside</p>
        <h2>Approve this <em>device code.</em></h2>
        <p>Open Twitch activation in a new tab, sign in there, and enter the code below. This page will notice when approval is complete.</p>
        <div class="login-code">
          <strong>${esc(account.oauthCode)}</strong>
          <button class="tiny-button" type="button" data-action="copy-code" data-code="${attr(account.oauthCode)}">Copy</button>
        </div>
        <div class="hero-actions">
          <a class="button button-primary" href="${attr(activationUrl)}" target="_blank" rel="noopener noreferrer">Open Twitch activation</a>
          <button class="button button-quiet" data-action="replace-code" type="button">Request a new code</button>
        </div>
      </div>
      ${renderDropOrbit()}
    </section>`;
}

function renderLoginPreparingHero() {
  return `
    <section class="hero" aria-busy="true">
      <div class="hero-copy">
        <p class="hero-kicker">Opening a secure path</p>
        <h2>Preparing your <em>device code.</em></h2>
        <p>The local miner is asking Twitch for a one-time activation code. You can leave this page open.</p>
        <div class="hero-actions">
          <button class="button button-primary" type="button" disabled>${refreshIcon()} Preparing code…</button>
        </div>
      </div>
      ${renderDropOrbit(true)}
    </section>`;
}

function renderMinerHero(snapshot) {
  const active = snapshot.miningActive;
  const task = snapshot.currentTask || (active ? "Miner active" : "Miner is resting");
  return `
    <section class="hero">
      <div class="hero-copy">
        <p class="hero-kicker">${active ? "Greenhouse in motion" : "Ready when you are"}</p>
        <h2>${active ? "Your drop garden is <em>being tended.</em>" : "Everything is ready to <em>begin growing.</em>"}</h2>
        <p><strong>${esc(task)}</strong><br>${esc(snapshot.progressSummary || "Campaign inventory is ready.")}</p>
        <div class="hero-actions">
          <button class="button ${active ? "button-danger" : "button-primary"}" data-action="${active ? "stop" : "start"}" type="button">
            ${active ? stopIcon() : playIcon()} ${active ? "Let the miner rest" : "Start tending drops"}
          </button>
          <button class="button button-quiet" data-action="refresh" type="button">${refreshIcon()} Refresh inventory</button>
        </div>
      </div>
      ${renderDropOrbit(active)}
    </section>`;
}

function renderDropOrbit(active = false) {
  return `
    <div class="hero-visual" aria-hidden="true">
      <div class="drop-orbit ${active ? "is-active" : ""}">
        <span class="orbit-seed one"></span><span class="orbit-seed two"></span><span class="orbit-seed three"></span>
        <div class="drop-core">${sproutIcon()}</div>
      </div>
    </div>`;
}

function renderWatchCard(campaign, drop, channel, channels = [], channelSearchInProgress = false) {
  const progress = percent(drop.progress);
  const campaignUrl = safeTwitchUrl(campaign.campaignUrl);
  const channelUrl = channel ? twitchChannelUrl(channel.name) : null;
  const dropName = campaignUrl
    ? `<a class="active-drop-link" href="${attr(campaignUrl)}" target="_blank" rel="noopener noreferrer" aria-label="Open ${attr(drop.name)} drop campaign on Twitch">${esc(drop.name)}<span class="external-mark" aria-hidden="true">↗</span></a>`
    : esc(drop.name);
  const channelChip = channel
    ? channelUrl
      ? `<a class="soft-chip is-link" href="${attr(channelUrl)}" target="_blank" rel="noopener noreferrer" aria-label="Open ${attr(channel.name)} on Twitch">@${esc(channel.name)}<span class="external-mark" aria-hidden="true">↗</span></a>`
      : `<span class="soft-chip">@${esc(channel.name)}</span>`
    : "";
  const searching = channelSearchInProgress || ui.channelPickerLoading;
  const showChannelPicker = ui.channelPickerOpen || channelSearchInProgress;
  const alternatives = channels.filter((candidate) =>
    candidate && Number.isSafeInteger(candidate.id) && candidate.id > 0 && candidate.online &&
    candidate.dropsEnabled && (!channel || candidate.id !== channel.id),
  );
  return `
    <div class="watch-card">
      ${renderArt(campaign)}
      <div class="watch-meta">
        <div class="campaign-tags">
          <span class="status-chip is-live">Live campaign</span>
          ${channelChip}
        </div>
        <h3>${dropName}</h3>
        <p>${esc(campaign.gameName)} · ${drop.currentMinutes} of ${drop.requiredMinutes} minutes</p>
        <progress class="progress-track" max="100" value="${progress}" aria-label="${progress}% gathered"></progress>
        <div class="progress-line"><span>${progress}% gathered</span><span>${drop.remainingMinutes}m remaining</span></div>
        ${channel ? `<div class="inline-actions"><button class="tiny-button" data-action="find-channel" type="button" aria-expanded="${showChannelPicker}" aria-controls="channelPicker" ${searching ? "disabled" : ""}>${searching ? "Finding channels…" : showChannelPicker ? "Refresh channel list" : "Find another channel"}</button></div>` : ""}
      </div>
      <div class="progress-ring"><svg viewBox="0 0 44 44" aria-hidden="true"><circle cx="22" cy="22" r="18"></circle><circle class="ring-progress" cx="22" cy="22" r="18" pathLength="100" stroke-dasharray="${progress} 100"></circle></svg><strong>${progress}%</strong></div>
      ${channel && showChannelPicker ? renderChannelPicker(alternatives, searching) : ""}
    </div>`;
}

function renderChannelPicker(channels, searching) {
  return `
    <div class="channel-picker" id="channelPicker" role="group" aria-label="Compatible live channels">
      <div class="channel-picker-head">
        <strong>Choose another streamer</strong>
        <span role="status">${searching ? "Refreshing Twitch’s compatible channel list…" : channels.length ? `${channels.length} alternative${channels.length === 1 ? "" : "s"} available` : "No live alternatives found"}</span>
      </div>
      ${searching ? '<div class="channel-picker-loading" aria-hidden="true"><span></span><span></span><span></span></div>' : channels.length ? `
        <div class="channel-options">
          ${channels.map((candidate) => `
            <button class="channel-option" type="button" data-action="select-channel" data-id="${attr(candidate.id)}">
              <span>@${esc(candidate.name)}</span>
              <small>${candidate.viewers == null ? "Live with Drops" : `${formatViewers(candidate.viewers)} viewers`}</small>
            </button>`).join("")}
        </div>` : '<p class="channel-picker-empty">The current channel stays active. Try refreshing again after more Drops-enabled streams go live.</p>'}
    </div>`;
}

function renderEmptyWatch(authenticated) {
  return `
    <div class="empty-state">
      <div><div class="empty-state-illustration" aria-hidden="true"></div>
      <h3>${authenticated ? "No drop is growing yet" : "The campaign bed is empty"}</h3>
      <p>${authenticated ? "Start the miner, or refresh the inventory if you just linked a game account." : "Connect Twitch to load eligible campaigns and watch progress."}</p></div>
    </div>`;
}

function renderCampaigns(data) {
  const campaigns = data.snapshot.campaigns;
  const availableGames = new Set(campaigns.map((campaign) => campaign.gameName.toLowerCase()));
  const unavailablePriorities = data.settings.selectedGamePriority.filter(
    (gameName) => !availableGames.has(gameName.toLowerCase()),
  );
  const query = ui.campaignSearch.trim().toLowerCase();
  const filtered = campaigns.filter((campaign) => {
    const matchesQuery = !query || `${campaign.gameName} ${campaign.name}`.toLowerCase().includes(query);
    const matchesFilter = ui.campaignFilter === "all"
      || (ui.campaignFilter === "active" && campaign.active && !campaign.excluded)
      || (ui.campaignFilter === "linked" && campaign.linked)
      || (ui.campaignFilter === "unlinked" && campaign.linkStatusKnown && !campaign.linked)
      || (ui.campaignFilter === "priority" && campaign.priorityIndex >= 0)
      || (ui.campaignFilter === "excluded" && campaign.excluded);
    return matchesQuery && matchesFilter;
  });

  return `
    <div class="page-stack">
      ${data.snapshot.error ? renderError(data.snapshot.error) : ""}
      <section class="soft-card toolbar">
        <label class="search-field">
          ${searchIcon()}
          <span class="sr-only">Search campaigns</span>
          <input id="campaignSearch" type="search" autocomplete="off" placeholder="Find a game or campaign" value="${attr(ui.campaignSearch)}">
        </label>
        <div class="filter-pills" aria-label="Campaign filters">
          ${["all", "active", "linked", "unlinked", "priority", "excluded"].map((filter) => `<button class="filter-pill ${ui.campaignFilter === filter ? "is-active" : ""}" type="button" data-action="campaign-filter" data-filter="${filter}" aria-pressed="${ui.campaignFilter === filter}">${capitalize(filter)}</button>`).join("")}
        </div>
      </section>
      <section class="soft-card section-card">
        <div class="section-head">
          <div><h2>Choose the order</h2><p>Prioritized games are tried first. Excluded campaigns stay visible but are never mined.</p></div>
          ${data.settings.selectedGamePriority.length ? '<button class="button button-quiet" data-action="clear-priorities" type="button">Clear priorities</button>' : ""}
        </div>
        ${unavailablePriorities.length ? `
          <div class="notice notice-spaced" role="status">
            <div class="notice-icon">◇</div>
            <div>
              <h3>Saved priorities currently unavailable</h3>
              <p>These remain saved through campaign gaps and partial Twitch responses:</p>
              <div class="unavailable-priorities">
                ${unavailablePriorities.map((gameName) => `<span class="soft-chip">${esc(gameName)} <button class="chip-action" type="button" data-action="toggle-priority" data-game="${attr(gameName)}" aria-label="Remove unavailable priority ${attr(gameName)}">×</button></span>`).join("")}
              </div>
            </div>
          </div>` : ""}
        ${filtered.length ? `<div class="campaign-list">${filtered.map((campaign) => renderCampaignRow(campaign, data.settings)).join("")}</div>` : renderFilteredEmpty(campaigns.length)}
      </section>
    </div>`;
}

function renderCampaignRow(campaign, settings, compact = false) {
  const priority = campaign.priorityIndex;
  const statusClass = campaign.excluded ? "is-excluded" : campaign.active ? "is-live" : campaign.upcoming ? "is-upcoming" : campaign.expired ? "is-ended" : "is-unknown";
  const statusLabel = campaign.excluded ? "Excluded" : campaign.active ? "Active" : campaign.upcoming ? "Upcoming" : campaign.expired ? "Ended" : "Unknown";
  const linkLabel = campaign.linkStatusKnown ? (campaign.linked ? "Linked" : "Unlinked") : "Link unknown";
  const topDrop = campaign.drops.find((drop) => !drop.claimed) || campaign.drops[0];
  const progress = percent(campaign.progress);
  return `
    <article class="campaign-row ${campaign.excluded ? "is-excluded" : ""}">
      ${renderArt(campaign)}
      <div class="campaign-copy">
        <div class="campaign-tags">
          <span class="status-chip ${statusClass}">${statusLabel}</span>
          <span class="soft-chip">${linkLabel}</span>
          ${priority >= 0 ? `<span class="priority-chip">Priority ${priority + 1}</span>` : ""}
        </div>
        <h3>${esc(campaign.gameName)}</h3>
        <p>${esc(campaign.name)}${topDrop ? ` · ${esc(topDrop.name)}` : ""}</p>
        <progress class="progress-track" max="100" value="${progress}" aria-label="${progress}% watched"></progress>
        <div class="progress-line"><span>${progress}% watched</span><span>${campaign.claimedDrops}/${campaign.totalDrops} claimed</span></div>
      </div>
      ${compact ? "" : `
        <div class="campaign-actions">
          ${priority >= 0 ? `
            <button class="tiny-button" type="button" data-action="move-priority" data-game="${attr(campaign.gameName)}" data-offset="-1" ${priority === 0 ? "disabled" : ""} aria-label="Move ${attr(campaign.gameName)} earlier">↑</button>
            <button class="tiny-button" type="button" data-action="move-priority" data-game="${attr(campaign.gameName)}" data-offset="1" ${priority === settings.selectedGamePriority.length - 1 ? "disabled" : ""} aria-label="Move ${attr(campaign.gameName)} later">↓</button>
          ` : ""}
          <button class="tiny-button" type="button" data-action="toggle-priority" data-game="${attr(campaign.gameName)}">${priority >= 0 ? "Unpin" : "Prioritize"}</button>
          <button class="tiny-button" type="button" data-action="toggle-exclusion" data-id="${attr(campaign.id)}" data-excluded="${campaign.excluded}">${campaign.excluded ? "Restore" : "Exclude"}</button>
        </div>`}
    </article>`;
}

function renderActivity(data) {
  const activities = data.snapshot.activity.slice().reverse();
  const logText = data.logs.length
    ? data.logs.map((entry) => `${entry.timestamp} [${entry.level}] ${entry.message}`).join("\n")
    : "No local log entries yet.";
  return `
    <div class="page-stack">
      <div class="grid-two">
        <section class="soft-card section-card">
          <div class="section-head"><div><h2>Runtime trail</h2><p>Human-readable milestones from the current and recent work.</p></div></div>
          ${activities.length ? renderTimeline(activities) : renderTimelineEmpty()}
        </section>
        <section class="soft-card section-card">
          <div class="section-head"><div><h2>Session pulse</h2><p>What the miner believes right now.</p></div></div>
          <div class="settings-list">
            ${renderFact("Phase", capitalize(data.snapshot.phase))}
            ${renderFact("Current task", data.snapshot.currentTask)}
            ${renderFact("Last update", formatDateTime(data.snapshot.lastUpdate))}
            ${renderFact("Claims this session", String(data.snapshot.dropsClaimedThisSession))}
          </div>
        </section>
      </div>
      <section class="soft-card section-card">
        <div class="section-head">
          <div><h2>Local runtime log</h2><p>Bounded diagnostic output stored in the Compose volume.</p></div>
          <div class="card-actions">
            <button class="tiny-button" data-action="copy-logs" type="button">Copy</button>
            <button class="tiny-button" data-action="clear-logs" type="button">Clear</button>
          </div>
        </div>
        <div class="log-window">
          <div class="log-head"><span class="window-dots"><i></i><i></i><i></i></span><span>runtime.log · last ${data.logs.length} entries</span></div>
          <pre class="log-body" id="logBody">${esc(logText)}</pre>
        </div>
      </section>
    </div>`;
}

function renderSettings(data) {
  const settings = data.settings;
  return `
    <div class="page-stack">
      <div class="grid-two">
        <section class="soft-card section-card">
          <div class="section-head"><div><h2>Timing & rhythm</h2><p>Intervals are normalized by the server before they are saved.</p></div></div>
          <div class="settings-group">
            ${renderRange("Watch heartbeat", "How often the runtime reports watch activity.", "watchIntervalSeconds", settings.watchIntervalSeconds, 20, 300, 1, "seconds")}
            ${renderRange("Inventory refresh", "How often campaigns and Twitch-reported progress are reloaded.", "inventoryRefreshMinutes", settings.inventoryRefreshMinutes, 15, 180, 1, "minutes")}
            ${renderToggle("Fallback to other games", "Use the ordered Auto Mode groups when preferred work is unavailable.", "fallbackToOtherGames", settings.fallbackToOtherGames)}
            ${renderToggle("Verbose local logs", "Record additional diagnostics for long-running troubleshooting.", "debugLogging", settings.debugLogging)}
          </div>
        </section>
        <section class="soft-card section-card">
          <div class="section-head"><div><h2>Container posture</h2><p>Defaults chosen for a quiet local service.</p></div></div>
          <div class="notice">
            <div class="notice-icon">⌂</div>
            <div><h3>Loopback by default</h3><p>Compose publishes this UI on 127.0.0.1. Add an authenticated TLS proxy before making it reachable from other machines.</p></div>
          </div>
          <div class="notice notice-spaced">
            <div class="notice-icon">◇</div>
            <div><h3>Session sealed at rest</h3><p>The Twitch access token is encrypted with AES-256-GCM inside the persistent volume and is never returned by the API.</p></div>
          </div>
        </section>
      </div>
      <section class="soft-card section-card">
        <div class="section-head"><div><h2>Auto Mode path</h2><p>Selected games always come first. These groups decide the remaining route.</p></div></div>
        <div class="auto-list">
          ${settings.autoModePriorityOrder.map((option, index, order) => renderAutoPriority(option, index, order.length)).join("")}
        </div>
      </section>
      <section class="soft-card section-card">
        <div class="section-head"><div><h2>Resets</h2><p>Settings and Twitch identity are deliberately separate.</p></div></div>
        <div class="setting-row">
          <div class="setting-copy"><h3>Restore runtime defaults</h3><p>Clears timing, priorities, exclusions, fallback, and debug preferences. Twitch stays connected.</p></div>
          <button class="button button-quiet" data-action="reset-settings" type="button">Reset settings</button>
        </div>
        <div class="setting-row">
          <div class="setting-copy"><h3>Reset Twitch session</h3><p>Stops mining, signs out, and clears session-scoped priorities and exclusions.</p></div>
          <button class="button button-danger" data-action="reset-session" type="button">Sign out & reset</button>
        </div>
      </section>
    </div>`;
}

function renderRange(title, description, key, value, min, max, step, unit) {
  return `
    <div class="setting-row">
      <div class="setting-copy"><h3>${esc(title)}</h3><p>${esc(description)}</p></div>
      <label class="range-setting">
        <span class="range-top"><span>${min}–${max}</span><output id="${attr(key)}Output">${value} ${unit}</output></span>
        <input type="range" min="${min}" max="${max}" step="${step}" value="${value}" data-setting-range="${attr(key)}" data-unit="${attr(unit)}" aria-label="${attr(title)}">
      </label>
    </div>`;
}

function renderToggle(title, description, key, enabled) {
  return `
    <div class="setting-row">
      <div class="setting-copy"><h3>${esc(title)}</h3><p>${esc(description)}</p></div>
      <button class="switch ${enabled ? "is-on" : ""}" type="button" role="switch" aria-checked="${enabled}" aria-label="${attr(title)}" data-action="toggle-setting" data-key="${attr(key)}" data-enabled="${enabled}"></button>
    </div>`;
}

function renderAutoPriority(option, index, length) {
  return `
    <div class="auto-item">
      <span class="auto-index">${index + 1}</span>
      <div class="auto-copy"><strong>${esc(option.title)}</strong><small>${esc(option.description)}</small></div>
      <div class="auto-actions">
        <button class="tiny-button" type="button" data-action="move-auto" data-key="${attr(option.key)}" data-offset="-1" ${index === 0 ? "disabled" : ""} aria-label="Move ${attr(option.title)} earlier">↑ Earlier</button>
        <button class="tiny-button" type="button" data-action="move-auto" data-key="${attr(option.key)}" data-offset="1" ${index === length - 1 ? "disabled" : ""} aria-label="Move ${attr(option.title)} later">↓ Later</button>
      </div>
    </div>`;
}

function renderTimeline(activities, compact = false) {
  if (!activities.length) return renderTimelineEmpty();
  return `<div class="timeline">${activities.map((activity) => `
    <div class="activity-item">
      <span class="activity-seed">${activity.state === "error" ? "!" : "·"}</span>
      <div class="activity-copy"><h4>${esc(activity.title)}</h4>${activity.detail ? `<p>${esc(activity.detail)}</p>` : ""}</div>
      <time class="activity-time" datetime="${attr(activity.timestamp)}">${compact ? formatTime(activity.timestamp) : formatDateTime(activity.timestamp)}</time>
    </div>`).join("")}</div>`;
}

function renderTimelineEmpty() {
  return `<div class="empty-state"><div><div class="empty-state-illustration" aria-hidden="true"></div><h3>Nothing stirred yet</h3><p>Login, refresh, and miner milestones will appear here.</p></div></div>`;
}

function renderFact(label, value) {
  return `<div class="setting-row"><div class="setting-copy"><h3>${esc(label)}</h3></div><span class="soft-chip">${esc(value || "—")}</span></div>`;
}

function renderStat(label, value, note, tint, icon) {
  const tintClass = ["mint", "lilac", "peach"].includes(tint) ? `tint-${tint}` : "tint-mint";
  return `<article class="soft-card stat-card ${tintClass}"><div class="stat-label"><span>${esc(label)}</span><span class="stat-icon">${icon}</span></div><div class="stat-value">${esc(value)}</div><p class="stat-note">${esc(note)}</p></article>`;
}

function renderArt(campaign) {
  const imageUrl = safeUrl(campaign.gameBoxArtUrl);
  const initials = (campaign.gameName || "?").split(/\s+/).slice(0, 2).map((word) => word[0]).join("").toUpperCase();
  return `<div class="game-art">${imageUrl ? `<img src="${attr(imageUrl)}" alt="" loading="lazy">` : esc(initials)}</div>`;
}

function renderError(message) {
  return `<div class="error-banner" role="alert"><span class="activity-seed">!</span><div><strong>The garden hit a snag</strong>${esc(message)}</div></div>`;
}

function renderEmptyCampaigns(authenticated) {
  return `<div class="empty-state"><div><div class="empty-state-illustration" aria-hidden="true"></div><h3>${authenticated ? "No campaigns loaded" : "Connect Twitch first"}</h3><p>${authenticated ? "Refresh the inventory to ask Twitch for current eligible campaigns." : "Your eligible campaigns and progress will live here after device authorization."}</p></div></div>`;
}

function renderFilteredEmpty(hasCampaigns) {
  return `<div class="empty-state"><div><div class="empty-state-illustration" aria-hidden="true"></div><h3>${hasCampaigns ? "No campaigns match" : "No campaigns loaded"}</h3><p>${hasCampaigns ? "Try a different search or filter." : "Connect Twitch and refresh the inventory to begin."}</p></div></div>`;
}

function renderUnavailable(message) {
  return `<div class="page-stack"><section class="soft-card empty-state"><div><div class="empty-state-illustration" aria-hidden="true"></div><h3>The local host is out of reach</h3><p>${esc(message)} The page will keep trying.</p></div></section></div>`;
}

async function handleClick(event) {
  const viewButton = event.target.closest("[data-view]");
  if (viewButton) {
    ui.view = viewButton.dataset.view;
    render();
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    window.scrollTo({ top: 0, behavior: reducedMotion ? "auto" : "smooth" });
    return;
  }

  const button = event.target.closest("[data-action]");
  if (!button) return;
  const action = button.dataset.action;
  try {
    if (action === "toggle-theme") toggleTheme();
    if (action === "connect") await command("/api/auth/start");
    if (action === "replace-code") await command("/api/auth/replace");
    if (action === "start") await command("/api/miner/start");
    if (action === "stop") await command("/api/miner/stop");
    if (action === "refresh") await command("/api/inventory/refresh");
    if (action === "find-channel") {
      ui.channelPickerOpen = true;
      ui.channelPickerLoading = true;
      render();
      try {
        await command("/api/channels/find");
      } finally {
        ui.channelPickerLoading = false;
        render();
      }
    }
    if (action === "select-channel") {
      const channelId = Number(button.dataset.id);
      if (!Number.isSafeInteger(channelId) || channelId <= 0) throw new Error("The selected channel ID is invalid.");
      await command("/api/channels/select", { channelId });
      ui.channelPickerOpen = false;
      render();
    }
    if (action === "toggle-priority") await command("/api/priorities/toggle", { gameName: button.dataset.game });
    if (action === "move-priority") await command("/api/priorities/move", { gameName: button.dataset.game, offset: Number(button.dataset.offset) });
    if (action === "clear-priorities") await command("/api/priorities/clear");
    if (action === "toggle-exclusion") await command("/api/campaigns/exclusion", { campaignIds: [button.dataset.id], excluded: button.dataset.excluded !== "true" });
    if (action === "campaign-filter") {
      ui.campaignFilter = button.dataset.filter;
      render();
    }
    if (action === "toggle-setting") await command("/api/settings", { [button.dataset.key]: button.dataset.enabled !== "true" }, "Setting saved");
    if (action === "move-auto") await command("/api/settings/auto-priority/move", { key: button.dataset.key, offset: Number(button.dataset.offset) });
    if (action === "copy-code") await copyText(button.dataset.code, "Device code copied");
    if (action === "copy-logs") await copyText(document.querySelector("#logBody")?.textContent || "", "Logs copied");
    if (action === "clear-logs" && await ask("Clear local logs?", "This removes the bounded diagnostic log from the persistent volume.", "Clear logs")) await command("/api/logs/clear", {}, "Logs cleared");
    if (action === "reset-settings" && await ask("Restore runtime defaults?", "Your Twitch login stays connected, but priorities, exclusions, fallback, timing, and debug preferences return to defaults.", "Reset settings")) await command("/api/settings/reset", {}, "Settings restored");
    if (action === "reset-session" && await ask("Sign out and reset Twitch?", "Mining stops and the encrypted session, game priorities, and campaign exclusions are cleared.", "Sign out & reset")) await command("/api/session/reset", {}, "Twitch session reset");
  } catch (error) {
    toast(error.message || "The action could not be completed.", true);
  }
}

function toggleTheme() {
  ui.theme = ui.theme === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = ui.theme;
  try {
    window.localStorage.setItem("twitch-dock-drops-theme", ui.theme);
  } catch {
    // The selected theme still applies for this page when storage is unavailable.
  }
  renderThemeToggle();
}

function renderThemeToggle() {
  const useLight = ui.theme === "dark";
  const label = useLight ? "Use light mode" : "Use dark mode";
  themeButton.setAttribute("aria-label", label);
  themeButton.title = label;
  document.querySelector('meta[name="theme-color"]').content = ui.theme === "dark" ? "#0e1715" : "#eef5f1";
}

function handleInput(event) {
  if (event.target.id === "campaignSearch") {
    const cursor = event.target.selectionStart;
    ui.campaignSearch = event.target.value;
    render();
    const input = document.querySelector("#campaignSearch");
    input?.focus();
    input?.setSelectionRange(cursor, cursor);
  }
  if (event.target.matches("[data-setting-range]")) {
    const output = document.querySelector(`#${event.target.dataset.settingRange}Output`);
    if (output) output.textContent = `${event.target.value} ${event.target.dataset.unit}`;
  }
}

async function handleChange(event) {
  if (!event.target.matches("[data-setting-range]")) return;
  try {
    await command("/api/settings", { [event.target.dataset.settingRange]: Number(event.target.value) }, "Timing saved");
  } catch (error) {
    toast(error.message, true);
  }
}

async function command(path, body = {}, successMessage = "") {
  if (ui.preview) {
    toast("Preview mode is read-only. Remove ?preview=1 to control the miner.");
    return;
  }
  const commandKey = `${path}:${JSON.stringify(body)}`;
  if (ui.pendingCommands.has(commandKey)) return;
  ui.pendingCommands.add(commandKey);
  try {
    const response = await fetch(path, {
      method: path === "/api/settings" ? "PUT" : "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || `Request failed (${response.status})`);
    if (successMessage) toast(successMessage);
    window.setTimeout(loadState, 250);
  } finally {
    ui.pendingCommands.delete(commandKey);
  }
}

function ask(title, message, confirmLabel) {
  document.querySelector("#confirmTitle").textContent = title;
  document.querySelector("#confirmMessage").textContent = message;
  document.querySelector("#confirmButton").textContent = confirmLabel;
  confirmDialog.showModal();
  return new Promise((resolve) => {
    confirmDialog.addEventListener("close", () => resolve(confirmDialog.returnValue === "confirm"), { once: true });
  });
}

async function copyText(value, message) {
  await navigator.clipboard.writeText(value);
  toast(message);
}

function toast(message, error = false) {
  const element = document.createElement("div");
  element.className = `toast ${error ? "is-error" : ""}`;
  element.textContent = message;
  document.querySelector("#toastRegion").append(element);
  window.setTimeout(() => element.remove(), 3600);
}

function previewState() {
  const now = new Date();
  const iso = (offsetMinutes = 0) => new Date(now.getTime() + offsetMinutes * 60000).toISOString();
  const campaign = (overrides) => ({
    id: overrides.id,
    name: overrides.name,
    gameName: overrides.gameName,
    gameBoxArtUrl: null,
    campaignUrl: `https://www.twitch.tv/drops/campaigns?dropID=${encodeURIComponent(overrides.id)}`,
    linkUrl: null,
    startsAt: iso(-1800),
    endsAt: iso(7200),
    linked: overrides.linked ?? true,
    linkStatusKnown: true,
    active: true,
    upcoming: false,
    expired: false,
    claimedDrops: 0,
    totalDrops: 2,
    remainingMinutes: 94,
    progress: overrides.progress,
    selected: overrides.priorityIndex >= 0,
    excluded: false,
    priorityIndex: overrides.priorityIndex,
    earnable: true,
    allowedChannels: [],
    drops: [
      { id: `${overrides.id}-1`, name: overrides.drop, currentMinutes: overrides.minutes, requiredMinutes: 120, remainingMinutes: 120 - overrides.minutes, progress: overrides.minutes / 120, claimed: false, canClaim: false, completed: false, startsAt: null, endsAt: null, rewards: [{ name: overrides.reward, type: "In-game", imageUrl: null }] },
      { id: `${overrides.id}-2`, name: "Afterglow Cache", currentMinutes: 0, requiredMinutes: 60, remainingMinutes: 60, progress: 0, claimed: false, canClaim: false, completed: false, startsAt: null, endsAt: null, rewards: [] },
    ],
  });
  const campaigns = [
    campaign({ id: "echo-bloom", name: "Echo Bloom Week", gameName: "No Man's Sky", drop: "Iridescent Trail", reward: "Echo Bloom Ship Trail", minutes: 74, progress: 0.41, priorityIndex: 0 }),
    campaign({ id: "solstice-relay", name: "Solstice Relay", gameName: "Warframe", drop: "Relay Supply", reward: "Solstice Color Palette", minutes: 28, progress: 0.16, priorityIndex: 1 }),
    campaign({ id: "meadow-market", name: "Meadow Market", gameName: "Palia", drop: "Garden Parcel", reward: "Soft Fern Planter", minutes: 0, progress: 0, priorityIndex: -1, linked: false }),
  ];
  const preview = {
    server: { version: "0.1.0", now: iso(), uptimeSeconds: 28420 },
    settings: {
      watchIntervalSeconds: 59,
      inventoryRefreshMinutes: 60,
      fallbackToOtherGames: true,
      debugLogging: false,
      selectedGamePriority: ["No Man's Sky", "Warframe"],
      excludedCampaignIds: [],
      autoModePriorityOrder: [
        ["linked_claimed_progress", "Linked · claimed-drop progress", "Linked campaigns where at least one drop is already claimed."],
        ["linked_viewing_progress", "Linked · viewing progress", "Linked campaigns with Twitch-reported watch progress."],
        ["linked_fresh", "Linked · no progress", "Linked campaigns without claimed drops or viewing progress."],
        ["unlinked_claimed_progress", "Unlinked · claimed-drop progress", "Unlinked campaigns where at least one drop is already claimed."],
        ["unlinked_viewing_progress", "Unlinked · viewing progress", "Unlinked campaigns with Twitch-reported watch progress."],
        ["unlinked_fresh", "Unlinked · no progress", "Unlinked campaigns without claimed drops or viewing progress."],
      ].map(([key, title, description]) => ({ key, title, description })),
    },
    snapshot: {
      phase: "watching",
      currentTask: "Watching willowbyte for No Man's Sky",
      progressSummary: "Iridescent Trail is 62% complete.",
      lastUpdate: iso(-1),
      account: { state: "loggedin", statusText: "Logged in with Twitch", userId: "preview", oauthUrl: null, oauthCode: null, expiresAt: null, authenticated: true, actionRequired: false },
      campaigns,
      channels: [
        { id: 1, name: "willowbyte", game: "No Man's Sky", viewers: 812, online: true, dropsEnabled: true, aclBased: false, watching: true, title: "Soft base building & expedition", statusLabel: "Watching" },
        { id: 2, name: "fern_signal", game: "No Man's Sky", viewers: 426, online: true, dropsEnabled: true, aclBased: false, watching: false, title: "Expedition route and cozy bases", statusLabel: "Drops enabled" },
        { id: 3, name: "quiet_orbit", game: "No Man's Sky", viewers: 97, online: true, dropsEnabled: true, aclBased: true, watching: false, title: "Community drop session", statusLabel: "Drops enabled" },
      ],
      activity: [
        { timestamp: iso(-1), state: "watching", title: "Watch heartbeat accepted", detail: "willowbyte · No Man's Sky" },
        { timestamp: iso(-7), state: "findingchannel", title: "Compatible channel selected", detail: "willowbyte, 812 viewers" },
        { timestamp: iso(-8), state: "selectingcampaign", title: "Priority campaign selected", detail: "Echo Bloom Week" },
        { timestamp: iso(-9), state: "loadinginventory", title: "Campaign inventory refreshed", detail: "3 campaigns, 6 drops" },
      ],
      currentChannel: { id: 1, name: "willowbyte", game: "No Man's Sky", viewers: 812, online: true, dropsEnabled: true, watching: true, statusLabel: "Watching" },
      activeCampaign: campaigns[0],
      activeDrop: campaigns[0].drops[0],
      dropsClaimedThisSession: 2,
      miningActive: true,
      channelSearchInProgress: false,
      error: null,
      activeCampaignCount: 3,
    },
    logs: [
      { timestamp: iso(-9), level: "INFO", message: "Campaign inventory loaded: 3 active campaigns" },
      { timestamp: iso(-8), level: "INFO", message: "Selected Echo Bloom Week from priority game No Man's Sky" },
      { timestamp: iso(-7), level: "INFO", message: "Watching willowbyte with Drops enabled" },
      { timestamp: iso(-1), level: "DEBUG", message: "Watch heartbeat accepted; current drop 74/120 minutes" },
    ],
  };
  const variant = new URLSearchParams(window.location.search).get("preview") || "active";
  if (variant === "loggedout") {
    preview.snapshot.phase = "stopped";
    preview.snapshot.currentTask = "Local miner stopped";
    preview.snapshot.account = { state: "loggedout", statusText: "Twitch login required", userId: null, oauthUrl: null, oauthCode: null, expiresAt: null, authenticated: false, actionRequired: false };
    preview.snapshot.campaigns = [];
    preview.snapshot.channels = [];
    preview.snapshot.currentChannel = null;
    preview.snapshot.activeCampaign = null;
    preview.snapshot.activeDrop = null;
    preview.snapshot.miningActive = false;
    preview.snapshot.activeCampaignCount = 0;
  } else if (variant === "preparing") {
    preview.snapshot.phase = "connecting";
    preview.snapshot.currentTask = "Preparing Twitch device login";
    preview.snapshot.account = { state: "loginrequired", statusText: "Preparing Twitch device login", userId: null, oauthUrl: null, oauthCode: null, expiresAt: null, authenticated: false, actionRequired: true };
    preview.snapshot.campaigns = [];
    preview.snapshot.channels = [];
    preview.snapshot.currentChannel = null;
    preview.snapshot.activeCampaign = null;
    preview.snapshot.activeDrop = null;
    preview.snapshot.miningActive = false;
    preview.snapshot.activeCampaignCount = 0;
  } else if (variant === "code") {
    preview.snapshot.phase = "authenticating";
    preview.snapshot.currentTask = "Waiting for Twitch activation";
    preview.snapshot.account = { state: "loginrequired", statusText: "Enter Twitch device code MINT-4K7", userId: null, oauthUrl: "https://www.twitch.tv/activate", oauthCode: "MINT-4K7", expiresAt: iso(10), authenticated: false, actionRequired: true };
    preview.snapshot.campaigns = [];
    preview.snapshot.channels = [];
    preview.snapshot.currentChannel = null;
    preview.snapshot.activeCampaign = null;
    preview.snapshot.activeDrop = null;
    preview.snapshot.miningActive = false;
    preview.snapshot.activeCampaignCount = 0;
  } else if (variant === "expired") {
    preview.snapshot.phase = "error";
    preview.snapshot.currentTask = "Twitch device code expired";
    preview.snapshot.account = { state: "loginrequired", statusText: "Device code expired", userId: null, oauthUrl: null, oauthCode: null, expiresAt: iso(-1), authenticated: false, actionRequired: true };
    preview.snapshot.campaigns = [];
    preview.snapshot.channels = [];
    preview.snapshot.currentChannel = null;
    preview.snapshot.activeCampaign = null;
    preview.snapshot.activeDrop = null;
    preview.snapshot.miningActive = false;
    preview.snapshot.activeCampaignCount = 0;
    preview.snapshot.error = "Twitch device code expired. Start login again.";
  }
  if (variant !== "active") {
    preview.snapshot.dropsClaimedThisSession = 0;
    preview.snapshot.activity = [];
    preview.logs = [];
  }
  return preview;
}

function safeUrl(value) {
  if (!value) return null;
  try {
    const url = new URL(value, window.location.origin);
    return url.protocol === "https:" || (url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname)) ? url.href : null;
  } catch {
    return null;
  }
}

function safeTwitchUrl(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    const hostname = url.hostname.toLowerCase();
    return url.protocol === "https:" && (hostname === "twitch.tv" || hostname === "www.twitch.tv") ? url.href : null;
  } catch {
    return null;
  }
}

function twitchChannelUrl(name) {
  const channelName = String(name || "").trim();
  return channelName ? safeTwitchUrl(`https://www.twitch.tv/${encodeURIComponent(channelName)}`) : null;
}

function percent(value) {
  return Math.round(Math.max(0, Math.min(1, Number(value) || 0)) * 100);
}

function formatTime(value) {
  if (!value) return "—";
  return new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(new Date(value));
}

function formatDateTime(value) {
  if (!value) return "—";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }).format(new Date(value));
}

function formatViewers(value) {
  const viewers = Number(value);
  return Number.isFinite(viewers) && viewers >= 0
    ? new Intl.NumberFormat(undefined, { notation: viewers >= 10000 ? "compact" : "standard", maximumFractionDigits: 1 }).format(viewers)
    : "—";
}

function capitalize(value) {
  return String(value || "").replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" })[character]);
}

const attr = esc;

function playIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 7 8 5-8 5V7Z"></path></svg>'; }
function stopIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="2"></rect></svg>'; }
function refreshIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 7v5h-5"></path><path d="M18.2 15a7 7 0 1 1-.3-6.4L20 12"></path></svg>'; }
function linkIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.5.5l2-2a5 5 0 0 0-7-7l-1.2 1.2"></path><path d="M14 11a5 5 0 0 0-7.5-.5l-2 2a5 5 0 0 0 7 7l1.2-1.2"></path></svg>'; }
function searchIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"></circle><path d="m20 20-4-4"></path></svg>'; }
function dropletIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3C8 8 6 10 6 14a6 6 0 0 0 12 0c0-4-2-6-6-11Z"></path></svg>'; }
function bloomIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21v-9M12 15c-4 0-7-2-7-6 4 0 7 2 7 6Zm0-3c0-4 3-6 7-6 0 4-3 6-7 6Z"></path></svg>'; }
function giftIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 10h16v10H4zM3 6h18v4H3zM12 6v14M12 6H8.5A2.5 2.5 0 1 1 11 3.5L12 6Zm0 0h3.5A2.5 2.5 0 1 0 13 3.5L12 6Z"></path></svg>'; }
function sproutIcon() { return '<svg viewBox="0 0 48 48" aria-hidden="true"><path d="M24 40V20M24 29c-8 0-13-4-13-12 8 0 13 4 13 12Zm0-6c0-8 6-12 13-12 0 8-5 12-13 12Z"></path></svg>'; }
