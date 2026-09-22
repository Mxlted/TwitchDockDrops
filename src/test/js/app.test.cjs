const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function client() {
  const context = vm.createContext({
    URLSearchParams,
    document: { documentElement: { dataset: {} }, querySelector: () => ({}), addEventListener() {} },
    window: { location: { search: '?preview=active' }, addEventListener() {} },
  });
  const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/app.js'), 'utf8');
  vm.runInContext(source + '\nthis.client = { ui, previewState, renderGamePriorities, renderCampaigns, renderQueue };', context);
  return context.client;
}

test('category search waits for two characters, caps matches and honors linked scope', () => {
  const c = client();
  const data = c.previewState();
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
  data.settings.selectedGamePriority = ['Absent <script>', 'Warframe'];
  const html = c.renderGamePriorities(data);
  assert.match(html, /Absent &lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /Waiting for a campaign/);
  assert.ok(html.indexOf('Absent &lt;script&gt;') < html.indexOf('Priority position for Warframe'));
  c.ui.gameSearch = 'WARFRAME';
  assert.match(c.renderGamePriorities(data), /Already prioritized/);
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
