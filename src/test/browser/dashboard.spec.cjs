const {test, expect} = require('@playwright/test');
const AxeBuilder = require('@axe-core/playwright').default;

test.beforeEach(async ({page}, info) => {
  await page.clock.setFixedTime(new Date('2026-10-05T12:00:00Z'));
  await page.addInitScript(theme => localStorage.setItem('twitch-dock-drops-theme', theme), info.project.metadata.theme);
  // No browser request can reach Twitch, external images or a real account.
  await page.route('**/*', route => new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort());
});

test('login fixture states, campaign expansion and explicit blocked reasons', async ({page}) => {
  for (const variant of ['loggedout', 'preparing', 'code', 'expired']) {
    await page.goto(`/?preview=${variant}`);
    await expect(page.locator('#app')).toHaveAttribute('aria-busy', 'false');
    expect(await page.locator('#app').innerText()).not.toBe('');
    const scan = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect.soft(scan.violations.map(v => v.id)).toEqual([]);
  }
  await page.goto('/?preview=active#campaigns');
  await page.evaluate(() => {
    ui.data.snapshot.campaigns[0].drops[0].blockedReason = 'Missing prerequisite: synthetic-parent';
    render();
  });
  await page.locator('[data-action="toggle-drops"]').first().click();
  await expect(page.getByText('Missing prerequisite: synthetic-parent')).toBeVisible();
  await page.getByLabel('Search campaigns').fill('no-such-campaign');
  await expect(page.locator('#app')).toContainText('No campaigns');
});

async function navigate(page, view) {
  await page.locator(`[data-view="${view}"]:visible`).first().click();
  await expect(page.locator('#app')).toHaveAttribute('aria-busy', 'false');
}

test('navigation, reward reasons, themes, layouts and automatic accessibility', async ({page}, info) => {
  await page.goto('/?preview=active');
  await expect(page.locator('html')).toHaveAttribute('data-theme', info.project.metadata.theme);
  for (const view of ['overview', 'campaigns', 'history', 'activity', 'settings']) {
    await navigate(page, view);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy();
    const scan = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect.soft(scan.violations.map(v => ({id:v.id, nodes:v.nodes.map(n => n.target)}))).toEqual([]);
  }
  await expect(page.getByRole('heading', {name: 'Reward filters'})).toBeVisible();
  await page.locator('#excludedRewardNames').fill('Rare reward');
  await page.evaluate(() => { ui.data.snapshot.error = 'Synthetic state update'; render(); });
  await expect(page.locator('#excludedRewardNames')).toBeFocused();
  await expect(page.locator('#excludedRewardNames')).toHaveValue('Rare reward');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', {name:'Save reward filters'})).toBeFocused();
});

test('logged-out experimental login choice and keyboard dialog cancellation', async ({page}) => {
  await page.goto('/');
  await page.getByRole('button', {name:'Connect Twitch', exact:true}).click();
  await expect(page.getByRole('button', {name:'Try experimental TV login'})).toBeVisible();
  await expect(page.getByText(/OAuth success alone/)).toBeVisible();
  // Never activate any login method.
  await navigate(page, 'settings');
  await page.getByRole('button', {name:'Reset settings', exact:true}).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(page.getByRole('button', {name:'Reset settings', exact:true})).toBeFocused();
});

test('replacement TV code keeps its identity without contacting OAuth', async ({page}) => {
  await page.goto('/?preview=code');
  const fixture = await page.evaluate(() => previewState());
  fixture.snapshot.account.method = 'android_tv';
  await page.route('**/api/state', route => route.fulfill({json:fixture}));
  await page.route('**/api/events', route => route.fulfill({contentType:'text/event-stream', body:': synthetic\n\n'}));
  const calls = [];
  await page.route('**/api/auth/**', route => {
    if (route.request().method() === 'POST') calls.push(new URL(route.request().url()).pathname);
    return route.fulfill({json:{ok:true}});
  });
  await page.goto('/');
  await page.getByRole('button', {name:'Request a new code'}).click();
  await expect.poll(() => calls).toEqual(['/api/auth/tv/start']);
});

test('real isolated settings API saves filters and rejects invalid schema', async ({page}) => {
  await page.goto('/#settings');
  const savedType = await page.evaluate(async () => (await fetch('/api/settings', {method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({allowedRewardTypes:[' badge ', 'BADGE']})})).status);
  expect(savedType).toBe(200);
  await page.reload();
  await expect(page.getByRole('checkbox', {name:'BADGE', exact:true})).toBeChecked();
  await page.locator('#excludedRewardNames').fill('Badge\n badge \nRare');
  await page.getByRole('button', {name:'Save reward filters'}).click();
  await expect(page.getByText('Reward filters saved', {exact:true})).toBeVisible();
  await page.reload();
  await expect(page.locator('#excludedRewardNames')).toHaveValue('Badge\nRare');
  const status = await page.evaluate(async () => (await fetch('/api/settings', {method:'PUT', headers:{'Content-Type':'application/json'}, body:JSON.stringify({allowedRewardTypes:[42]})})).status);
  expect(status).toBe(400);
  await page.getByRole('checkbox', {name:'BADGE', exact:true}).uncheck();
  await page.locator('#excludedRewardNames').fill('');
  await page.getByRole('button', {name:'Save reward filters'}).click();
});

test('history loads, escapes names, handles pending, errors and retry', async ({page}) => {
  await page.goto('/?preview=active');
  const fixture = await page.evaluate(() => previewState());
  let historyStatus = 'loading';
  let release;
  const gate = new Promise(resolve => {release = resolve;});
  await page.route('**/api/state', route => route.fulfill({json:fixture}));
  await page.route('**/api/events', route => route.fulfill({contentType:'text/event-stream', body:': synthetic\n\n'}));
  await page.route('**/api/claims', async route => {
    if (historyStatus === 'loading') await gate;
    if (historyStatus === 'error') return route.fulfill({status:503, json:{error:'Synthetic history failure'}});
    return route.fulfill({json:{records:historyStatus === 'empty' ? [] : [
      {reward:'<script>Reward</script>', campaign:'Campaign', game:'Game', state:'confirmed', recordedAt:'2026-10-05T12:00:00Z'},
      {reward:'Pending reward', campaign:'Campaign', game:'Game', state:'pending', recordedAt:'2026-10-05T12:01:00Z'},
    ]}});
  });
  await page.goto('/#history');
  await expect(page.getByRole('status').filter({hasText:'Loading claim history'})).toBeVisible();
  historyStatus = 'error'; release();
  await expect(page.getByRole('alert')).toContainText('Synthetic history failure');
  historyStatus = 'records';
  await page.getByRole('button', {name:'Refresh history'}).click();
  await expect(page.getByText('<script>Reward</script>', {exact:true})).toBeVisible();
  await expect(page.getByText('Pending reconciliation', {exact:true})).toBeVisible();
  const scan = await new AxeBuilder({page}).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(scan.violations.map(v => v.id)).toEqual([]);
  historyStatus = 'empty';
  await page.getByRole('button', {name:'Refresh history'}).click();
  await expect(page.getByText('No recorded claims yet.')).toBeVisible();
});
