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
  expandedCampaigns: new Set(),
};

const app = document.querySelector("#app");
const pageTitle = document.querySelector("#pageTitle");
const campaignCount = document.querySelector("#campaignCount");
const previewPill = document.querySelector("#previewPill");
const confirmDialog = document.querySelector("#confirmDialog");
const themeButton = document.querySelector("#themeButton");
const serviceStatus = document.querySelector("#serviceStatus");
const serviceDot = document.querySelector("#serviceDot");
const minerPill = document.querySelector("#minerPill");
const minerPillLabel = document.querySelector("#minerPillLabel");
const primaryAction = document.querySelector("#primaryAction");
const primaryActionLabel = document.querySelector("#primaryActionLabel");
const refreshButton = document.querySelector("#refreshButton");
const offlineBanner = document.querySelector("#offlineBanner");
const titleByView = {
  overview: "Overview",
  campaigns: "Campaigns",
  activity: "Activity",
  settings: "Settings",
};
const previewVariants = ["active", "loggedout", "preparing", "code", "expired"];

document.addEventListener("DOMContentLoaded", boot);
document.addEventListener("click", handleClick);
document.addEventListener("input", handleInput);
document.addEventListener("change", handleChange);
window.addEventListener("hashchange", () => {
  const view = viewFromHash();
  if (view === ui.view) return;
  ui.view = view;
  render();
});

function viewFromHash() {
  const requested = window.location.hash.replace(/^#/, "");
  return Object.hasOwn(titleByView, requested) ? requested : "overview";
}

function showView(view) {
  ui.view = view;
  const hash = view === "overview" ? "" : `#${view}`;
  if (window.location.hash !== hash) {
    // Keep the URL in step with the view so refresh, back, and bookmarks land on the same page.
    window.history.pushState(null, "", `${window.location.pathname}${window.location.search}${hash}`);
  }
  render();
}

async function boot() {
  ui.view = viewFromHash();
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
  pageTitle.textContent = titleByView[ui.view];
  campaignCount.textContent = String(ui.data.snapshot.campaigns.length);
  document.querySelectorAll(".primary-nav [data-view], .mobile-nav [data-view]").forEach((button) => {
    const isActive = button.dataset.view === ui.view;
    button.classList.toggle("is-active", isActive);
    if (isActive) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  renderTopbar();
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
  const logBody = document.querySelector("#logBody");
  if (logBody) logBody.scrollTop = logBody.scrollHeight;
}

function renderTopbar() {
  const snapshot = ui.data.snapshot;
  const authenticated = snapshot.account.authenticated;
  const active = snapshot.miningActive;
  let tone = "is-idle";
  let label = "Not connected";
  if (authenticated && active) {
    tone = "is-live";
    label = snapshot.currentChannel ? `Watching @${snapshot.currentChannel.name}` : "Looking for a channel";
  } else if (authenticated) {
    label = "Miner stopped";
  } else if (snapshot.account.actionRequired) {
    tone = "is-waiting";
    label = "Waiting for Twitch";
  }
  minerPill.className = `miner-pill ${tone}`;
  minerPillLabel.textContent = label;

  primaryAction.hidden = !authenticated;
  primaryAction.dataset.action = active ? "stop" : "start";
  primaryAction.className = `button topbar-primary ${active ? "button-danger is-stop" : "button-primary"}`;
  const actionLabel = active ? "Stop miner" : "Start miner";
  primaryActionLabel.textContent = actionLabel;
  // The text label is hidden on narrow screens, so the accessible name must not depend on it.
  primaryAction.setAttribute("aria-label", actionLabel);
  primaryAction.title = actionLabel;
  refreshButton.disabled = !authenticated;
}

function renderConnection() {
  const online = ui.preview || ui.connected;
  serviceStatus.textContent = ui.preview ? "Preview only" : ui.connected ? "Connected" : "Reconnecting";
  serviceDot.className = `service-dot ${online ? "is-online" : "is-offline"}`;
  offlineBanner.hidden = online || !ui.data;
  if (!online && ui.data) {
    minerPill.className = "miner-pill is-offline";
    minerPillLabel.textContent = "Reconnecting";
  }
}

/* Overview ---------------------------------------------------------------- */

function renderOverview(data) {
  const snapshot = data.snapshot;
  const account = snapshot.account;
  const authenticated = account.authenticated;
  const waitingForCode = account.actionRequired && account.oauthCode;
  const preparingLogin = !account.authenticated && !account.oauthCode &&
    ["connecting", "authenticating"].includes(String(snapshot.phase || "").toLowerCase());
  if (!snapshot.miningActive || !snapshot.activeCampaign || !snapshot.activeDrop) {
    ui.channelPickerOpen = false;
    ui.channelPickerLoading = false;
  }

  if (!authenticated) {
    const hero = waitingForCode
      ? renderLoginCodeHero(account)
      : preparingLogin
        ? renderLoginPreparingHero()
        : renderWelcomeHero();
    return `
      <div class="page-stack">
        ${snapshot.error ? renderError(snapshot.error) : ""}
        ${hero}
      </div>`;
  }

  const campaigns = snapshot.campaigns;
  const claimable = campaigns.reduce(
    (total, campaign) => total + campaign.drops.filter((drop) => drop.canClaim && !drop.claimed).length,
    0,
  );
  const activeDrop = snapshot.activeDrop;
  return `
    <div class="page-stack">
      ${snapshot.error ? renderError(snapshot.error) : ""}
      <div class="stat-grid">
        ${renderStat("Claimed this session", snapshot.dropsClaimedThisSession, "drops claimed by the miner", "twitch", dropletIcon())}
        ${renderStat("Active campaigns", snapshot.activeCampaignCount, `${campaigns.length} loaded from Twitch`, "sky", bloomIcon())}
        ${renderStat("Ready to claim", claimable, claimable ? "claiming on the next pass" : "nothing waiting right now", "peach", giftIcon())}
        ${renderStat("Current drop", activeDrop ? `${percent(activeDrop.progress)}%` : "—", activeDrop ? `${activeDrop.remainingMinutes}m left · done ${formatEta(activeDrop.remainingMinutes)}` : "no drop in progress", "mint", clockIcon())}
      </div>
      <div class="grid-two">
        <section class="soft-card section-card">
          <div class="section-head">
            <div><h2>Now watching</h2><p>${esc(snapshot.currentTask || (snapshot.miningActive ? "Miner active" : "Miner stopped"))}</p></div>
            <span class="head-meta">Updated ${esc(formatTime(snapshot.lastUpdate))}</span>
          </div>
          ${snapshot.activeCampaign && activeDrop
            ? renderWatchCard(snapshot.activeCampaign, activeDrop, snapshot.currentChannel, snapshot.channels, snapshot.channelSearchInProgress)
              + `<div class="watch-drops"><p class="watch-drops-title">Drops in ${esc(snapshot.activeCampaign.name)}</p>${renderDropList(snapshot.activeCampaign, "activeCampaignDrops")}</div>`
            : renderEmptyWatch(snapshot)}
        </section>
        <section class="soft-card section-card">
          <div class="section-head">
            <div><h2>Recent activity</h2><p>The latest miner milestones.</p></div>
            <button class="tiny-button" data-view="activity" type="button">See all</button>
          </div>
          ${renderTimeline(snapshot.activity.slice(-6).reverse(), true)}
        </section>
      </div>
      <section class="soft-card section-card">
        <div class="section-head">
          <div><h2>Priority queue</h2><p>Pinned games in order, then active linked campaigns. Auto Mode decides the exact route.</p></div>
          <button class="tiny-button" data-view="campaigns" type="button">Manage campaigns</button>
        </div>
        ${renderQueue(snapshot)}
      </section>
    </div>`;
}

function renderWelcomeHero() {
  return `
    <section class="hero">
      <div class="hero-copy">
        <p class="hero-kicker">Container ready</p>
        <h2>Connect Twitch to <em>start farming.</em></h2>
        <p>Approve a one-time device code on Twitch. The miner then follows eligible campaigns, sends watch heartbeats, and claims completed drops from this container.</p>
        <div class="hero-actions">
          <button class="button button-primary" data-action="connect" type="button">${linkIcon()} Connect Twitch</button>
          <a class="button button-quiet" href="/?preview=active">Explore with preview data</a>
        </div>
      </div>
      <aside class="hero-aside" aria-label="How it works">
        <p class="hero-aside-title">How it works</p>
        <ol class="step-list">
          ${renderStep(1, "Connect Twitch", "Approve a one-time device code. The token stays encrypted in this container.")}
          ${renderStep(2, "Choose priorities", "Pin the games you want first, or let Auto Mode find useful work.")}
          ${renderStep(3, "Let it run", "Watch heartbeats, channel recovery, and claims happen on the server.")}
        </ol>
      </aside>
    </section>`;
}

function renderStep(index, title, detail) {
  return `<li class="step"><span class="step-index" aria-hidden="true">${index}</span><span><strong>${esc(title)}</strong><span>${esc(detail)}</span></span></li>`;
}

function renderLoginCodeHero(account) {
  const activationUrl = safeUrl(account.oauthUrl) || "https://www.twitch.tv/activate";
  return `
    <section class="hero">
      <div class="hero-copy">
        <p class="hero-kicker">Action needed</p>
        <h2>Approve this <em>device code.</em></h2>
        <p>Open Twitch activation in a new tab, sign in there, and enter the code shown here. This page updates as soon as approval completes.</p>
        <div class="hero-actions">
          <a class="button button-primary" href="${attr(activationUrl)}" target="_blank" rel="noopener noreferrer">Open Twitch activation</a>
          <button class="button button-quiet" data-action="replace-code" type="button">Request a new code</button>
        </div>
      </div>
      <aside class="hero-aside" aria-label="Device code">
        <p class="hero-aside-title">Your device code</p>
        <div class="code-display">
          <strong>${esc(account.oauthCode)}</strong>
          <button class="tiny-button" type="button" data-action="copy-code" data-code="${attr(account.oauthCode)}">Copy code</button>
          <small>${account.expiresAt ? `Expires ${esc(formatTime(account.expiresAt))}` : "Enter it on twitch.tv/activate"}</small>
        </div>
      </aside>
    </section>`;
}

function renderLoginPreparingHero() {
  return `
    <section class="hero" aria-busy="true">
      <div class="hero-copy">
        <p class="hero-kicker">Contacting Twitch</p>
        <h2>Preparing your <em>device code.</em></h2>
        <p>The local miner is asking Twitch for a one-time activation code. You can leave this page open.</p>
        <div class="hero-actions">
          <button class="button button-primary" type="button" disabled>${refreshIcon()} Preparing code…</button>
        </div>
      </div>
      <aside class="hero-aside" aria-label="Login status">
        <p class="hero-aside-title">Status</p>
        <div class="spinner-block"><span class="spinner" aria-hidden="true"></span><span>Waiting for Twitch to issue a device code…</span></div>
      </aside>
    </section>`;
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
  const viewersChip = channel && channel.viewers != null ? `<span class="soft-chip">${esc(formatViewers(channel.viewers))} viewers</span>` : "";
  const searching = channelSearchInProgress || ui.channelPickerLoading;
  const showChannelPicker = ui.channelPickerOpen || channelSearchInProgress;
  const alternatives = channels.filter((candidate) =>
    candidate && Number.isSafeInteger(candidate.id) && candidate.id > 0 && candidate.online &&
    candidate.dropsEnabled && (!channel || candidate.id !== channel.id),
  );
  const dropPosition = campaign.totalDrops > 1 ? ` · ${campaign.claimedDrops + 1} of ${campaign.totalDrops} drops` : "";
  return `
    <div class="watch-card">
      ${renderArt(campaign)}
      <div class="watch-meta">
        <div class="campaign-tags">
          <span class="status-chip is-live">Live</span>
          ${channelChip}
          ${viewersChip}
        </div>
        <h3>${dropName}</h3>
        <p>${esc(campaign.gameName)} · ${esc(campaign.name)}${dropPosition}</p>
        <progress class="progress-track" max="100" value="${progress}" aria-label="${progress}% watched"></progress>
        <div class="progress-line"><span>${drop.currentMinutes} of ${drop.requiredMinutes} min</span><span>${drop.remainingMinutes}m left · done ${esc(formatEta(drop.remainingMinutes))}</span></div>
        ${channel ? `<div class="inline-actions"><button class="tiny-button" data-action="find-channel" type="button" aria-expanded="${showChannelPicker}" aria-controls="channelPicker" ${searching ? "disabled" : ""}>${searching ? "Finding channels…" : showChannelPicker ? "Refresh channel list" : "Switch channel"}</button></div>` : ""}
      </div>
      <div class="progress-ring"><svg viewBox="0 0 44 44" aria-hidden="true"><circle cx="22" cy="22" r="18"></circle><circle class="ring-progress" cx="22" cy="22" r="18" pathLength="100" stroke-dasharray="${progress} 100"></circle></svg><strong>${progress}%</strong></div>
      ${channel && showChannelPicker ? renderChannelPicker(alternatives, searching) : ""}
    </div>`;
}

function renderChannelPicker(channels, searching) {
  return `
    <div class="channel-picker" id="channelPicker" role="group" aria-label="Compatible live channels">
      <div class="channel-picker-head">
        <strong>Compatible live channels</strong>
        <span role="status">${searching ? "Refreshing Twitch’s compatible channel list…" : channels.length ? `${channels.length} alternative${channels.length === 1 ? "" : "s"} available` : "No live alternatives found"}</span>
      </div>
      ${searching ? '<div class="channel-picker-loading" aria-hidden="true"><span></span><span></span><span></span></div>' : channels.length ? `
        <div class="channel-options">
          ${channels.map((candidate) => `
            <button class="channel-option" type="button" data-action="select-channel" data-id="${attr(candidate.id)}">
              <span>@${esc(candidate.name)}</span>
              <small>${candidate.viewers == null ? "Live with Drops" : `${formatViewers(candidate.viewers)} viewers`}</small>
            </button>`).join("")}
        </div>` : '<p class="channel-picker-empty">The current channel stays active. Try again after more Drops-enabled streams go live.</p>'}
    </div>`;
}

function renderEmptyWatch(snapshot) {
  const active = snapshot.miningActive;
  const hasCampaigns = snapshot.campaigns.length > 0;
  const title = active ? "Looking for work" : hasCampaigns ? "The miner is stopped" : "No campaigns loaded";
  const detail = active
    ? "The miner is selecting a campaign and a compatible live channel."
    : hasCampaigns
      ? "Start the miner to begin watching the highest-priority campaign."
      : "Refresh the inventory to load eligible campaigns from Twitch.";
  const action = active
    ? ""
    : hasCampaigns
      ? `<button class="button button-primary" data-action="start" type="button">${playIcon()} Start miner</button>`
      : `<button class="button button-quiet" data-action="refresh" type="button">${refreshIcon()} Refresh inventory</button>`;
  return `
    <div class="empty-state">
      <div>${emptyIcon()}<h3>${title}</h3><p>${detail}</p>${action ? `<div class="empty-actions">${action}</div>` : ""}</div>
    </div>`;
}

function renderQueue(snapshot) {
  const activeId = snapshot.activeCampaign?.id;
  const candidates = snapshot.campaigns.filter((campaign) =>
    campaign.id !== activeId && !campaign.excluded && !campaign.expired && campaign.claimedDrops < campaign.totalDrops);
  const pinned = candidates.filter((campaign) => campaign.priorityIndex >= 0).sort((a, b) => a.priorityIndex - b.priorityIndex);
  const auto = candidates.filter((campaign) => campaign.priorityIndex < 0 && campaign.active && campaign.linked);
  const queue = [...pinned, ...auto].slice(0, 5);
  if (!queue.length) {
    return `<div class="empty-state is-short"><div>${emptyIcon()}<h3>Nothing queued</h3><p>${snapshot.campaigns.length ? "Pin a game on the Campaigns page to shape what runs next." : "Campaigns appear here after the inventory loads."}</p></div></div>`;
  }
  return `
    <ol class="queue-list">
      ${queue.map((campaign, index) => `
        <li class="queue-item">
          <span class="queue-index" aria-hidden="true">${index + 1}</span>
          ${renderArt(campaign)}
          <div class="queue-copy">
            <strong>${esc(campaign.gameName)}</strong>
            <span>${esc(campaign.name)} · ${campaign.claimedDrops}/${campaign.totalDrops} claimed · ${campaign.remainingMinutes}m left</span>
          </div>
          <div class="campaign-tags">
            ${campaign.priorityIndex >= 0 ? `<span class="priority-chip">Priority ${campaign.priorityIndex + 1}</span>` : '<span class="soft-chip">Auto Mode</span>'}
            ${renderCampaignStatusChip(campaign)}
          </div>
        </li>`).join("")}
    </ol>`;
}

/* Campaigns --------------------------------------------------------------- */

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
  const filters = [["all", "All"], ["active", "Active"], ["linked", "Linked"], ["unlinked", "Unlinked"], ["priority", "Priority"], ["excluded", "Excluded"]];

  return `
    <div class="page-stack">
      ${data.snapshot.error ? renderError(data.snapshot.error) : ""}
      <section class="soft-card toolbar">
        <label class="search-field">
          ${searchIcon()}
          <span class="sr-only">Search campaigns</span>
          <input id="campaignSearch" type="search" autocomplete="off" placeholder="Search games or campaigns" value="${attr(ui.campaignSearch)}">
        </label>
        <div class="filter-pills" role="group" aria-label="Campaign filters">
          ${filters.map(([filter, label]) => `<button class="filter-pill ${ui.campaignFilter === filter ? "is-active" : ""}" type="button" data-action="campaign-filter" data-filter="${filter}" aria-pressed="${ui.campaignFilter === filter}">${label}</button>`).join("")}
        </div>
      </section>
      ${unavailablePriorities.length ? `
        <div class="notice" role="status">
          <div class="notice-icon">◇</div>
          <div>
            <h3>Pinned games without a current campaign</h3>
            <p>These priorities stay saved through campaign gaps and partial Twitch responses.</p>
            <div class="unavailable-priorities">
              ${unavailablePriorities.map((gameName) => `<span class="soft-chip">${esc(gameName)} <button class="chip-action" type="button" data-action="toggle-priority" data-game="${attr(gameName)}" aria-label="Remove unavailable priority ${attr(gameName)}">×</button></span>`).join("")}
            </div>
          </div>
        </div>` : ""}
      <section class="soft-card section-card">
        <div class="section-head">
          <div><h2>${filtered.length === campaigns.length ? `${campaigns.length} campaign${campaigns.length === 1 ? "" : "s"}` : `${filtered.length} of ${campaigns.length} campaigns`}</h2><p>Pinned games are mined first, in order. Excluded campaigns stay listed but are never mined.</p></div>
          ${data.settings.selectedGamePriority.length ? '<button class="tiny-button" data-action="clear-priorities" type="button">Clear priorities</button>' : ""}
        </div>
        ${filtered.length ? `<div class="campaign-list">${filtered.map((campaign) => renderCampaignRow(campaign, data.settings)).join("")}</div>` : renderFilteredEmpty(campaigns.length)}
      </section>
    </div>`;
}

function renderCampaignStatusChip(campaign) {
  const statusClass = campaign.excluded ? "is-excluded" : campaign.active ? "is-live" : campaign.upcoming ? "is-upcoming" : campaign.expired ? "is-ended" : "is-unknown";
  const statusLabel = campaign.excluded ? "Excluded" : campaign.active ? "Active" : campaign.upcoming ? "Upcoming" : campaign.expired ? "Ended" : "Unknown";
  return `<span class="status-chip ${statusClass}">${statusLabel}</span>`;
}

function renderCampaignRow(campaign, settings) {
  const priority = campaign.priorityIndex;
  const linkLabel = campaign.linkStatusKnown ? (campaign.linked ? "Linked" : "Unlinked") : "Link unknown";
  const progress = percent(campaign.progress);
  const expanded = ui.expandedCampaigns.has(campaign.id);
  const timing = campaign.active && campaign.endsAt
    ? `Ends ${formatRelative(campaign.endsAt)}`
    : campaign.upcoming && campaign.startsAt
      ? `Starts ${formatRelative(campaign.startsAt)}`
      : campaign.expired && campaign.endsAt
        ? `Ended ${formatDateTime(campaign.endsAt)}`
        : "";
  const linkUrl = campaign.linkStatusKnown && !campaign.linked ? safeUrl(campaign.linkUrl) : null;
  const detailsId = `drops-${cssId(campaign.id)}`;
  return `
    <article class="campaign-row ${campaign.excluded ? "is-excluded" : ""}">
      ${renderArt(campaign)}
      <div class="campaign-copy">
        <div class="campaign-tags">
          ${renderCampaignStatusChip(campaign)}
          <span class="soft-chip">${linkLabel}</span>
          ${priority >= 0 ? `<span class="priority-chip">Priority ${priority + 1}</span>` : ""}
          ${timing ? `<span class="soft-chip is-plain">${esc(timing)}</span>` : ""}
        </div>
        <h3>${esc(campaign.gameName)}</h3>
        <p>${esc(campaign.name)}</p>
        <progress class="progress-track" max="100" value="${progress}" aria-label="${progress}% watched"></progress>
        <div class="progress-line"><span>${progress}% watched</span><span>${campaign.claimedDrops}/${campaign.totalDrops} claimed · ${campaign.remainingMinutes}m left</span></div>
      </div>
      <div class="campaign-actions">
        ${priority >= 0 ? `
          <button class="tiny-button" type="button" data-action="move-priority" data-game="${attr(campaign.gameName)}" data-offset="-1" ${priority === 0 ? "disabled" : ""} aria-label="Move ${attr(campaign.gameName)} earlier">↑</button>
          <button class="tiny-button" type="button" data-action="move-priority" data-game="${attr(campaign.gameName)}" data-offset="1" ${priority === settings.selectedGamePriority.length - 1 ? "disabled" : ""} aria-label="Move ${attr(campaign.gameName)} later">↓</button>
        ` : ""}
        <button class="tiny-button ${priority >= 0 ? "" : "is-accent"}" type="button" data-action="toggle-priority" data-game="${attr(campaign.gameName)}">${priority >= 0 ? "Unpin" : "Pin game"}</button>
        <button class="tiny-button" type="button" data-action="toggle-exclusion" data-id="${attr(campaign.id)}" data-excluded="${campaign.excluded}">${campaign.excluded ? "Restore" : "Exclude"}</button>
        ${linkUrl ? `<a class="tiny-button" href="${attr(linkUrl)}" target="_blank" rel="noopener noreferrer">Link account ↗</a>` : ""}
        <button class="tiny-button drops-toggle" type="button" data-action="toggle-drops" data-id="${attr(campaign.id)}" aria-expanded="${expanded}" aria-controls="${detailsId}">${campaign.drops.length} drop${campaign.drops.length === 1 ? "" : "s"} <span class="chevron" aria-hidden="true">${expanded ? "▴" : "▾"}</span></button>
      </div>
      ${expanded ? renderDropList(campaign, detailsId) : ""}
    </article>`;
}

function renderDropList(campaign, id) {
  if (!campaign.drops.length) {
    return `<div class="drop-list" id="${attr(id)}"><p class="drop-empty">Twitch returned no drops for this campaign.</p></div>`;
  }
  return `
    <ul class="drop-list" id="${attr(id)}">
      ${campaign.drops.map((drop) => {
        const state = drop.claimed ? ["is-claimed", "Claimed"] : drop.canClaim ? ["is-ready", "Ready to claim"] : drop.currentMinutes > 0 ? ["is-progress", "In progress"] : ["is-waiting", "Not started"];
        const rewards = drop.rewards.map((reward) => reward.name).filter(Boolean);
        return `
          <li class="drop-item">
            <div class="drop-copy">
              <strong>${esc(drop.name)}</strong>
              <span>${rewards.length ? esc(rewards.join(", ")) : "Reward details unavailable"}</span>
            </div>
            <span class="drop-time">${drop.currentMinutes}/${drop.requiredMinutes} min</span>
            <span class="status-chip ${state[0]}">${state[1]}</span>
          </li>`;
      }).join("")}
    </ul>`;
}

/* Activity ---------------------------------------------------------------- */

function renderActivity(data) {
  const activities = data.snapshot.activity.slice().reverse();
  const errors = activities.filter((activity) => activity.state === "error").length;
  const logText = data.logs.length
    ? data.logs.map((entry) => `${entry.timestamp} [${entry.level}] ${entry.message}`).join("\n")
    : "No local log entries yet.";
  return `
    <div class="page-stack">
      ${data.snapshot.error ? renderError(data.snapshot.error) : ""}
      <section class="soft-card section-card">
        <div class="section-head">
          <div><h2>Miner timeline</h2><p>What the miner selected, refreshed, watched, or claimed.</p></div>
          <span class="head-meta">${activities.length} event${activities.length === 1 ? "" : "s"}${errors ? ` · ${errors} error${errors === 1 ? "" : "s"}` : ""}</span>
        </div>
        ${activities.length ? renderTimeline(activities) : renderTimelineEmpty()}
      </section>
      <section class="soft-card section-card">
        <div class="section-head">
          <div><h2>Runtime log</h2><p>Bounded diagnostic output stored in the data volume. Enable verbose logs in Settings for more detail.</p></div>
          <div class="card-actions">
            <button class="tiny-button" data-action="copy-logs" type="button">Copy</button>
            <button class="tiny-button" data-action="clear-logs" type="button">Clear</button>
          </div>
        </div>
        <div class="log-window">
          <div class="log-head"><span>runtime.log</span><span>last ${data.logs.length} entries</span></div>
          <pre class="log-body" id="logBody">${esc(logText)}</pre>
        </div>
      </section>
    </div>`;
}

/* Settings ---------------------------------------------------------------- */

function renderSettings(data) {
  const settings = data.settings;
  const snapshot = data.snapshot;
  const authenticated = snapshot.account.authenticated;
  return `
    <div class="page-stack">
      <div class="grid-two">
        <section class="soft-card section-card">
          <div class="section-head"><div><h2>Timing</h2><p>Intervals are normalized by the server before they are saved.</p></div></div>
          <div class="settings-group">
            ${renderRange("Watch heartbeat", "How often the runtime reports watch activity to Twitch.", "watchIntervalSeconds", settings.watchIntervalSeconds, 20, 300, 1, "seconds")}
            ${renderRange("Inventory refresh", "How often campaigns and Twitch-reported progress are reloaded.", "inventoryRefreshMinutes", settings.inventoryRefreshMinutes, 15, 180, 1, "minutes")}
            ${renderToggle("Fallback to other games", "Use the ordered Auto Mode groups when preferred work is unavailable.", "fallbackToOtherGames", settings.fallbackToOtherGames)}
            ${renderToggle("Verbose local logs", "Record additional diagnostics for long-running troubleshooting.", "debugLogging", settings.debugLogging)}
          </div>
        </section>
        <section class="soft-card section-card">
          <div class="section-head"><div><h2>Service</h2><p>This container and its Twitch connection.</p></div></div>
          <div class="fact-list">
            ${renderFactRow("Twitch account", snapshot.account.statusText || (authenticated ? "Signed in" : "Not connected"))}
            ${renderFactRow("Miner", snapshot.miningActive ? "Running" : "Stopped")}
            ${renderFactRow("Phase", capitalize(snapshot.phase) || "—")}
            ${renderFactRow("Version", data.server.version || "—")}
            ${renderFactRow("Uptime", formatDuration(data.server.uptimeSeconds))}
            ${renderFactRow("Pinned games", String(settings.selectedGamePriority.length))}
            ${renderFactRow("Excluded campaigns", String(settings.excludedCampaignIds.length))}
          </div>
          ${authenticated ? "" : `<div class="card-actions actions-spaced"><button class="button button-primary" data-action="connect" type="button">${linkIcon()} Connect Twitch</button></div>`}
        </section>
      </div>
      <section class="soft-card section-card">
        <div class="section-head"><div><h2>Auto Mode order</h2><p>Pinned games always come first. These groups decide the remaining route.</p></div></div>
        <ol class="auto-list">
          ${settings.autoModePriorityOrder.map((option, index, order) => renderAutoPriority(option, index, order.length)).join("")}
        </ol>
      </section>
      <section class="soft-card section-card">
        <div class="section-head"><div><h2>Resets</h2><p>Settings and the Twitch session are reset separately.</p></div></div>
        <div class="setting-row">
          <div class="setting-copy"><h3>Restore runtime defaults</h3><p>Clears timing, priorities, exclusions, fallback, and log preferences. Twitch stays connected.</p></div>
          <button class="button button-quiet" data-action="reset-settings" type="button">Reset settings</button>
        </div>
        <div class="setting-row">
          <div class="setting-copy"><h3>Sign out of Twitch</h3><p>Stops mining, removes the encrypted session, and clears session-scoped priorities and exclusions.</p></div>
          <button class="button button-danger" data-action="reset-session" type="button" ${authenticated ? "" : "disabled"}>Sign out &amp; reset</button>
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
    <li class="auto-item">
      <span class="auto-index" aria-hidden="true">${index + 1}</span>
      <div class="auto-copy"><strong>${esc(option.title)}</strong><small>${esc(option.description)}</small></div>
      <div class="auto-actions">
        <button class="tiny-button" type="button" data-action="move-auto" data-key="${attr(option.key)}" data-offset="-1" ${index === 0 ? "disabled" : ""} aria-label="Move ${attr(option.title)} earlier">↑</button>
        <button class="tiny-button" type="button" data-action="move-auto" data-key="${attr(option.key)}" data-offset="1" ${index === length - 1 ? "disabled" : ""} aria-label="Move ${attr(option.title)} later">↓</button>
      </div>
    </li>`;
}

/* Shared pieces ----------------------------------------------------------- */

function renderFactRow(label, value) {
  return `<div class="fact-row"><span>${esc(label)}</span><span>${esc(value)}</span></div>`;
}

function renderTimeline(activities, compact = false) {
  if (!activities.length) return renderTimelineEmpty();
  return `<div class="timeline">${activities.map((activity) => `
    <div class="activity-item">
      <span class="activity-seed ${activity.state === "error" ? "is-error" : ""}">${activity.state === "error" ? "!" : "·"}</span>
      <div class="activity-copy"><h4>${esc(activity.title)}</h4>${activity.detail ? `<p>${esc(activity.detail)}</p>` : ""}</div>
      <time class="activity-time" datetime="${attr(activity.timestamp)}">${compact ? formatTime(activity.timestamp) : formatDateTime(activity.timestamp)}</time>
    </div>`).join("")}</div>`;
}

function renderTimelineEmpty() {
  return `<div class="empty-state is-short"><div>${emptyIcon()}<h3>No activity yet</h3><p>Login, refresh, and miner milestones will appear here.</p></div></div>`;
}

function emptyIcon() {
  return `<div class="empty-state-icon" aria-hidden="true">${sproutIcon()}</div>`;
}

function renderStat(label, value, note, tint, icon) {
  const tintClass = ["twitch", "mint", "sky", "peach"].includes(tint) ? `tint-${tint}` : "tint-twitch";
  return `<article class="soft-card stat-card ${tintClass}"><div class="stat-label"><span>${esc(label)}</span><span class="stat-icon">${icon}</span></div><div class="stat-value">${esc(value)}</div><p class="stat-note">${esc(note)}</p></article>`;
}

function renderArt(campaign) {
  const imageUrl = safeUrl(campaign.gameBoxArtUrl);
  const initials = (campaign.gameName || "?").split(/\s+/).slice(0, 2).map((word) => word[0]).join("").toUpperCase();
  return `<div class="game-art">${imageUrl ? `<img src="${attr(imageUrl)}" alt="" loading="lazy">` : esc(initials)}</div>`;
}

function renderError(message) {
  return `<div class="error-banner" role="alert"><span class="activity-seed">!</span><div><strong>Something needs attention</strong>${esc(message)}</div></div>`;
}

function renderFilteredEmpty(hasCampaigns) {
  return `<div class="empty-state"><div>${emptyIcon()}<h3>${hasCampaigns ? "No campaigns match" : "No campaigns loaded"}</h3><p>${hasCampaigns ? "Try a different search or filter." : "Connect Twitch and refresh the inventory to begin."}</p></div></div>`;
}

function renderUnavailable(message) {
  return `<div class="page-stack"><section class="soft-card empty-state"><div>${emptyIcon()}<h3>The local host is out of reach</h3><p>${esc(message)} The page will keep trying.</p></div></section></div>`;
}

/* Events ------------------------------------------------------------------ */

async function handleClick(event) {
  const viewButton = event.target.closest("[data-view]");
  if (viewButton) {
    showView(viewButton.dataset.view);
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
    if (action === "refresh") await command("/api/inventory/refresh", {}, "Refreshing inventory");
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
    if (action === "toggle-drops") {
      const id = button.dataset.id;
      if (ui.expandedCampaigns.has(id)) ui.expandedCampaigns.delete(id);
      else ui.expandedCampaigns.add(id);
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
    if (action === "reset-settings" && await ask("Restore runtime defaults?", "Your Twitch login stays connected, but priorities, exclusions, fallback, timing, and log preferences return to defaults.", "Reset settings")) await command("/api/settings/reset", {}, "Settings restored");
    if (action === "reset-session" && await ask("Sign out of Twitch?", "Mining stops and the encrypted session, game priorities, and campaign exclusions are cleared.", "Sign out & reset")) await command("/api/session/reset", {}, "Twitch session reset");
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
  document.querySelector('meta[name="theme-color"]').content = ui.theme === "dark" ? "#141513" : "#f5f4f0";
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
  // Escape closes the dialog without touching returnValue, so a stale "confirm" from an
  // earlier prompt would otherwise be treated as approval.
  confirmDialog.returnValue = "";
  confirmDialog.showModal();
  return new Promise((resolve) => {
    confirmDialog.addEventListener("close", () => resolve(confirmDialog.returnValue === "confirm"), { once: true });
  });
}

async function copyText(value, message) {
  // navigator.clipboard is only exposed in secure contexts, so plain-HTTP LAN mode needs a
  // clear explanation instead of a TypeError.
  if (!navigator.clipboard?.writeText) {
    throw new Error("Copying needs a secure (HTTPS or localhost) page. Select the text and copy it manually.");
  }
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    throw new Error("The browser refused clipboard access. Select the text and copy it manually.");
  }
  toast(message);
}

function toast(message, error = false) {
  const element = document.createElement("div");
  element.className = `toast ${error ? "is-error" : ""}`;
  element.textContent = message;
  document.querySelector("#toastRegion").append(element);
  window.setTimeout(() => element.remove(), 3600);
}

/* Preview data ------------------------------------------------------------ */

function previewState() {
  const now = new Date();
  const iso = (offsetMinutes = 0) => new Date(now.getTime() + offsetMinutes * 60000).toISOString();
  const campaign = (overrides) => ({
    id: overrides.id,
    name: overrides.name,
    gameName: overrides.gameName,
    gameBoxArtUrl: null,
    campaignUrl: `https://www.twitch.tv/drops/campaigns?dropID=${encodeURIComponent(overrides.id)}`,
    linkUrl: overrides.linked === false ? "https://www.twitch.tv/settings/connections" : null,
    startsAt: iso(-1800),
    endsAt: iso(overrides.endsIn ?? 7200),
    linked: overrides.linked ?? true,
    linkStatusKnown: true,
    active: true,
    upcoming: false,
    expired: false,
    claimedDrops: overrides.claimed ?? 0,
    totalDrops: 2,
    remainingMinutes: 180 - overrides.minutes,
    progress: overrides.progress,
    selected: overrides.priorityIndex >= 0,
    excluded: false,
    priorityIndex: overrides.priorityIndex,
    earnable: true,
    allowedChannels: [],
    drops: [
      { id: `${overrides.id}-1`, name: overrides.drop, currentMinutes: overrides.minutes, requiredMinutes: 120, remainingMinutes: 120 - overrides.minutes, progress: overrides.minutes / 120, claimed: false, canClaim: false, completed: false, startsAt: null, endsAt: null, rewards: [{ name: overrides.reward, type: "In-game", imageUrl: null }] },
      { id: `${overrides.id}-2`, name: "Afterglow Cache", currentMinutes: 0, requiredMinutes: 60, remainingMinutes: 60, progress: 0, claimed: false, canClaim: false, completed: false, startsAt: null, endsAt: null, rewards: [{ name: "Afterglow Emote", type: "Emote", imageUrl: null }] },
    ],
  });
  const campaigns = [
    campaign({ id: "echo-bloom", name: "Echo Bloom Week", gameName: "No Man's Sky", drop: "Iridescent Trail", reward: "Echo Bloom Ship Trail", minutes: 74, progress: 0.41, priorityIndex: 0, endsIn: 5400 }),
    campaign({ id: "solstice-relay", name: "Solstice Relay", gameName: "Warframe", drop: "Relay Supply", reward: "Solstice Color Palette", minutes: 28, progress: 0.16, priorityIndex: 1, endsIn: 900 }),
    campaign({ id: "meadow-market", name: "Meadow Market", gameName: "Palia", drop: "Garden Parcel", reward: "Soft Fern Planter", minutes: 0, progress: 0, priorityIndex: -1, linked: false, endsIn: 20000 }),
    campaign({ id: "harbor-lights", name: "Harbor Lights", gameName: "Sea of Thieves", drop: "Lantern Set", reward: "Harbor Lantern", minutes: 0, progress: 0, priorityIndex: -1, endsIn: 3000 }),
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
      // Chronological, like the server's appended activity list; views reverse it for display.
      activity: [
        { timestamp: iso(-31), state: "claiming", title: "Drop claimed", detail: "Solstice Relay · Relay Beacon" },
        { timestamp: iso(-9), state: "loadinginventory", title: "Campaign inventory refreshed", detail: "4 campaigns, 8 drops" },
        { timestamp: iso(-8), state: "selectingcampaign", title: "Priority campaign selected", detail: "Echo Bloom Week" },
        { timestamp: iso(-7), state: "findingchannel", title: "Compatible channel selected", detail: "willowbyte, 812 viewers" },
        { timestamp: iso(-1), state: "watching", title: "Watch heartbeat accepted", detail: "willowbyte · No Man's Sky" },
      ],
      currentChannel: { id: 1, name: "willowbyte", game: "No Man's Sky", viewers: 812, online: true, dropsEnabled: true, watching: true, statusLabel: "Watching" },
      activeCampaign: campaigns[0],
      activeDrop: campaigns[0].drops[0],
      dropsClaimedThisSession: 2,
      miningActive: true,
      channelSearchInProgress: false,
      error: null,
      activeCampaignCount: 4,
    },
    logs: [
      { timestamp: iso(-9), level: "INFO", message: "Campaign inventory loaded: 4 active campaigns" },
      { timestamp: iso(-8), level: "INFO", message: "Selected Echo Bloom Week from priority game No Man's Sky" },
      { timestamp: iso(-7), level: "INFO", message: "Watching willowbyte with Drops enabled" },
      { timestamp: iso(-1), level: "DEBUG", message: "Watch heartbeat accepted; current drop 74/120 minutes" },
    ],
  };
  const requested = new URLSearchParams(window.location.search).get("preview") || "active";
  const variant = previewVariants.includes(requested) ? requested : "active";
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

/* Helpers ----------------------------------------------------------------- */

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
  return formatDate(value, { hour: "numeric", minute: "2-digit" });
}

function formatDateTime(value) {
  return formatDate(value, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function formatDate(value, options) {
  if (!value) return "—";
  const date = new Date(value);
  // An unparseable timestamp must not throw and take the whole view down with it.
  return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat(undefined, options).format(date);
}

function formatEta(remainingMinutes) {
  const minutes = Number(remainingMinutes);
  if (!Number.isFinite(minutes) || minutes < 0) return "—";
  return formatTime(new Date(Date.now() + minutes * 60000).toISOString());
}

function formatRelative(value) {
  const date = new Date(value);
  if (!value || Number.isNaN(date.getTime())) return "—";
  const minutes = Math.round((date.getTime() - Date.now()) / 60000);
  const absolute = Math.abs(minutes);
  const unit = absolute < 60 ? `${absolute}m` : absolute < 2880 ? `${Math.round(absolute / 60)}h` : `${Math.round(absolute / 1440)}d`;
  return minutes < 0 ? `${unit} ago` : `in ${unit}`;
}

function formatDuration(seconds) {
  const total = Number(seconds);
  if (!Number.isFinite(total) || total < 0) return "—";
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days) return `${days}d ${hours}h`;
  if (hours) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
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

function cssId(value) {
  return String(value ?? "").replace(/[^a-zA-Z0-9_-]/g, "_");
}

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" })[character]);
}

const attr = esc;

function playIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 7 8 5-8 5V7Z"></path></svg>'; }
function refreshIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 7v5h-5"></path><path d="M18.2 15a7 7 0 1 1-.3-6.4L20 12"></path></svg>'; }
function linkIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.5.5l2-2a5 5 0 0 0-7-7l-1.2 1.2"></path><path d="M14 11a5 5 0 0 0-7.5-.5l-2 2a5 5 0 0 0 7 7l1.2-1.2"></path></svg>'; }
function searchIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"></circle><path d="m20 20-4-4"></path></svg>'; }
function dropletIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3C8 8 6 10 6 14a6 6 0 0 0 12 0c0-4-2-6-6-11Z"></path></svg>'; }
function bloomIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-4l-3 4-3-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z"></path><path d="M8 10h8M8 7h5"></path></svg>'; }
function giftIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 10h16v10H4zM3 6h18v4H3zM12 6v14M12 6H8.5A2.5 2.5 0 1 1 11 3.5L12 6Zm0 0h3.5A2.5 2.5 0 1 0 13 3.5L12 6Z"></path></svg>'; }
function clockIcon() { return '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8"></circle><path d="M12 8v4l3 2"></path></svg>'; }
function sproutIcon() { return '<svg viewBox="0 0 48 48" aria-hidden="true"><path d="M24 40V20M24 29c-8 0-13-4-13-12 8 0 13 4 13 12Zm0-6c0-8 6-12 13-12 0 8-5 12-13 12Z"></path></svg>'; }
