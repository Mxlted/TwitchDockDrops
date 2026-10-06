const {defineConfig} = require('@playwright/test');

module.exports = defineConfig({
  testDir: './src/test/browser',
  timeout: 30000,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {baseURL: 'http://127.0.0.1:18743', browserName: 'chromium', reducedMotion: 'reduce', trace: 'retain-on-failure'},
  projects: ['dark', 'light'].flatMap(theme => [
    {name: `desktop-${theme}`, use: {viewport: {width: 1440, height: 1000}}, metadata: {theme}},
    {name: `mobile-${theme}`, use: {viewport: {width: 390, height: 844}, isMobile: true, hasTouch: true}, metadata: {theme}},
  ]),
  webServer: {
    command: 'node src/test/browser/server.cjs',
    url: 'http://127.0.0.1:18743/api/health',
    reuseExistingServer: false,
    timeout: 30000,
  },
});
