const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function client(fetch = () => { throw new Error('Unexpected request'); }) {
  const context = vm.createContext({
    URL,
    URLSearchParams,
    AbortController,
    fetch,
    document: { documentElement: { dataset: {} }, querySelector: () => ({}), addEventListener() {} },
    window: { location: { search: '?preview=active' }, addEventListener() {}, setTimeout, clearTimeout },
  });
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/app.js'), 'utf8');
  vm.runInContext(source + '\nrender = () => {}; this.client = { ui, previewState, renderGamePriorities, renderCampaigns, renderQueue, renderWatchCard, renderCampaignLink, searchTwitchCategories, cancelGameSearch };', context);
  return context.client;
}

test('campaign names link to their own Twitch campaign across list, watch card and queue', () => {
  const c = client();
  const data = c.previewState();
  const campaign = data.snapshot.campaigns[0];
  campaign.name = 'Reward <week> & "friends"';
  data.snapshot.selectionPreview = [campaign.id];
  for (const html of [c.renderCampaigns(data), c.renderQueue(data.snapshot),
    c.renderWatchCard(campaign, campaign.drops[0], null)]) {
    assert.ok(html.includes(`class="campaign-link" href="${campaign.campaignUrl}"`));
    assert.match(html, /target="_blank" rel="noopener noreferrer" aria-label="Open Reward &lt;week&gt; &amp; &quot;friends&quot; campaign on Twitch \(opens in a new tab\)"/);
    assert.match(html, /<span>Reward &lt;week&gt; &amp; &quot;friends&quot;<\/span>/);
    assert.doesNotMatch(html, /<week>/);
  }
});

test('missing and unsafe campaign URLs leave escaped, noninteractive names', () => {
  const c = client();
  for (const campaignUrl of [null, '', '/relative', 'javascript:alert(1)',
    'http://www.twitch.tv/drops/campaigns', 'https://twitch.tv.example.com/', 'https://example.com/']) {
    assert.equal(c.renderCampaignLink({name:'<Campaign>', campaignUrl}), '&lt;Campaign&gt;');
  }
});

test('category search waits for two characters, caps matches and honors linked scope', () => {
  const c = client();
  const data = c.previewState();
  c.ui.gameScope = 'all';
  c.ui.gameSearch = 'p';
  assert.doesNotMatch(c.renderGamePriorities(data), /class="game-result"/);
  c.ui.gameSearch = 'pa';
  assert.match(c.renderGamePriorities(data), /data-game="Palia"/);
  c.ui.gameScope = 'linked';
  assert.doesNotMatch(c.renderGamePriorities(data), /data-game="Palia"/);
  c.ui.gameScope = 'all';
  data.snapshot.campaigns = Array.from({length: 40}, (_, i) => ({gameName: `Game ${i}`}));
  c.ui.gameSearch = 'game';
  assert.equal((c.renderGamePriorities(data).match(/class="game-result"/g) || []).length, 8);
});

test('saved categories without campaigns remain ordered and escaped', () => {
  const c = client();
  const data = c.previewState();
  c.ui.gameScope = 'all';
  data.settings.selectedGamePriority = ['Absent <script>', 'Warframe'];
  const html = c.renderGamePriorities(data);
  assert.match(html, /Absent &lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /Waiting for a campaign/);
  assert.ok(html.indexOf('Absent &lt;script&gt;') < html.indexOf('Priority position for Warframe'));
  c.ui.gameSearch = 'WARFRAME';
  assert.match(c.renderGamePriorities(data), /Already prioritized/);
});

test('remote search is explicit, validates minimum length, and ignores superseded responses', async () => {
  const requests = [];
  const c = client((url, options) => new Promise(resolve => requests.push({url, options, resolve})));
  c.ui.preview = false;
  c.ui.gameSearch = 'a';
  await c.searchTwitchCategories();
  assert.equal(requests.length, 0);
  c.ui.gameSearch = 'Old query';
  const oldRequest = c.searchTwitchCategories();
  await c.searchTwitchCategories();
  assert.equal(requests.length, 1);
  assert.match(requests[0].url, /q=Old%20query$/);
  c.cancelGameSearch();
  assert.equal(requests[0].options.signal.aborted, true);
  c.ui.gameSearch = 'New query';
  const newRequest = c.searchTwitchCategories();
  requests[1].resolve({ok:true, json: async () => ({categories:[{id:'2',name:'New Game'}]})});
  await newRequest;
  requests[0].resolve({ok:true, json: async () => ({categories:[{id:'1',name:'Old Game'}]})});
  await oldRequest;
  assert.equal(c.ui.gameResults[0].name, 'New Game');
  assert.equal(c.ui.gameResultQuery, 'New query');
  assert.equal(c.ui.gameSearchLoading, false);
});

test('Twitch results without campaigns can be added and are safely escaped', () => {
  const c = client();
  const data = c.previewState();
  data.snapshot.campaigns = [];
  c.ui.gameSearch = 'Future';
  c.ui.gameResultQuery = 'Future';
  c.ui.gameResults = [{id:'42',name:'Future <Game>'}];
  const html = c.renderGamePriorities(data);
  assert.match(html, /data-game="Future &lt;Game&gt;"/);
  assert.match(html, /No campaign loaded/);
  assert.match(html, /\+ Add/);
  assert.doesNotMatch(html, /<Game>/);
});

test('remote errors are visible and allow retry without stale results', async () => {
  const c = client(async () => ({ok:false, json:async () => ({error:'Search unavailable'})}));
  c.ui.preview = false;
  c.ui.gameSearch = 'Future';
  await c.searchTwitchCategories();
  assert.equal(c.ui.gameSearchError, 'Search unavailable');
  assert.equal(c.ui.gameResults.length, 0);
  assert.equal(c.ui.gameSearchLoading, false);
  assert.match(c.renderGamePriorities(c.previewState()), /Search unavailable/);
  c.cancelGameSearch();
  assert.equal(c.ui.gameSearchError, '');
  const offline = client(async () => { throw new TypeError('Failed to fetch'); });
  offline.ui.preview = false;
  offline.ui.gameSearch = 'Future';
  await offline.searchTwitchCategories();
  assert.match(offline.ui.gameSearchError, /Could not reach the local server/);
  assert.equal(offline.ui.gameSearchLoading, false);
});

test('short catalog searches stay capped while four characters expose fifty results and paging', async () => {
  const categories = Array.from({length:50}, (_, i) => ({id:String(i),name:`Star ${i}`}));
  const c = client(async () => ({ok:true, json:async () => ({categories,nextCursor:'NTA='})}));
  c.ui.preview = false;
  for (const query of ['st', 'sta', 'star']) {
    c.ui.gameSearch = query;
    await c.searchTwitchCategories();
    assert.equal(c.ui.gameResults.length, query.length < 4 ? 12 : 50);
    const html = c.renderGamePriorities(c.previewState());
    assert.equal((html.match(/class="game-result"/g) || []).length, query.length < 4 ? 12 : 50);
    assert.equal(html.includes('aria-label="Category search pages"'), query.length >= 4);
    assert.equal(c.ui.gameNextCursor, query.length < 4 ? null : 'NTA=');
  }
});

test('page navigation retains the previous page on failure and retries its cursor', async () => {
  const requests = [];
  let fail = false;
  const c = client(async (url) => {
    requests.push(url);
    if (fail) return {ok:false,json:async () => ({error:'Try again'})};
    const second = url.includes('&after=');
    return {ok:true,json:async () => ({categories:[{id:second?'2':'1',name:second?'Last':'First'}],nextCursor:second?null:'NTA='})};
  });
  c.ui.preview = false;
  c.ui.gameSearch = 'star';
  await c.searchTwitchCategories();
  fail = true;
  await c.searchTwitchCategories(1);
  assert.equal(c.ui.gamePage, 0);
  assert.equal(c.ui.gameResults[0].name, 'First');
  assert.equal(c.ui.gameNextCursor, 'NTA=');
  fail = false;
  await c.searchTwitchCategories(1);
  assert.match(requests[2], /after=NTA%3D$/);
  assert.equal(c.ui.gamePage, 1);
  assert.equal(c.ui.gameResults[0].name, 'Last');
  assert.equal(c.ui.gameNextCursor, null);
  await c.searchTwitchCategories(0);
  assert.equal(c.ui.gamePage, 0);
  assert.equal(c.ui.gameResults[0].name, 'First');
  assert.doesNotMatch(requests[3], /after=/);
  c.cancelGameSearch();
  assert.equal(c.ui.gameNextCursor, null);
  assert.equal(c.ui.gamePageCursors.length, 1);
});

test('old page responses cannot overwrite a new query and cursor loops are rejected', async () => {
  let resolvePage;
  const c = client((url) => url.includes('&after=')
    ? new Promise(resolve => { resolvePage = resolve; })
    : Promise.resolve({ok:true,json:async () => ({categories:[{name:'First'}],nextCursor:'NTA='})}));
  c.ui.preview = false;
  c.ui.gameSearch = 'star';
  await c.searchTwitchCategories();
  const old = c.searchTwitchCategories(1);
  c.cancelGameSearch();
  c.ui.gameSearch = 'game';
  await c.searchTwitchCategories();
  resolvePage({ok:true,json:async () => ({categories:[{name:'Old page'}],nextCursor:null})});
  await old;
  assert.equal(c.ui.gameResultQuery, 'game');
  assert.equal(c.ui.gamePage, 0);
  assert.equal(c.ui.gameResults[0].name, 'First');
  const loop = c.searchTwitchCategories(1);
  resolvePage({ok:true,json:async () => ({categories:[{name:'Repeated'}],nextCursor:'NTA='})});
  await loop;
  assert.match(c.ui.gameSearchError, /repeated or invalid page/);
  assert.equal(c.ui.gamePage, 0);
});

test('campaigns page renders at most 24 rows and clamps stale page positions', () => {
  const c = client();
  const data = c.previewState();
  data.snapshot.campaigns = Array.from({length: 70}, (_, i) => ({...data.snapshot.campaigns[0], id: `c${i}`}));
  assert.equal((c.renderCampaigns(data).match(/<article/g) || []).length, 24);
  c.ui.campaignPage = 2;
  assert.equal((c.renderCampaigns(data).match(/<article/g) || []).length, 22);
  c.ui.campaignSearch = 'nothing matches';
  assert.match(c.renderCampaigns(data), /No campaigns match/);
  assert.equal(c.ui.campaignPage, 0);
});

test('queue uses only the server selection preview in its supplied order', () => {
  const c = client();
  const data = c.previewState();
  data.snapshot.selectionPreview = ['harbor-lights', 'solstice-relay'];
  const html = c.renderQueue(data.snapshot);
  assert.ok(html.indexOf('Sea of Thieves') < html.indexOf('Warframe'));
  assert.doesNotMatch(html, /No Man|Palia/);
  data.snapshot.selectionPreview = [];
  assert.match(c.renderQueue(data.snapshot), /Nothing queued/);
});
