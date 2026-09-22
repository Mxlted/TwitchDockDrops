const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function client(fetch = () => { throw new Error('Unexpected request'); }) {
  const context = vm.createContext({
    URLSearchParams,
    AbortController,
    fetch,
    document: { documentElement: { dataset: {} }, querySelector: () => ({}), addEventListener() {} },
    window: { location: { search: '?preview=active' }, addEventListener() {}, setTimeout, clearTimeout },
  });
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/app.js'), 'utf8');
  vm.runInContext(source + '\nrender = () => {}; this.client = { ui, previewState, renderGamePriorities, renderCampaigns, renderQueue, searchTwitchCategories, cancelGameSearch };', context);
  return context.client;
}

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
