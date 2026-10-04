// DockDrops browser login helper. Node.js 22.4+; no npm packages required.
// Request-context correlation follows rangermix/TwitchDropsMiner (MIT); see THIRD_PARTY_NOTICES.md.
/* MIT License - Copyright (c) 2024 DevilXD
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE. */
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, access, chmod } from 'node:fs/promises';
import { tmpdir, homedir, platform } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline/promises';
import { setTimeout as sleep } from 'node:timers/promises';

const GQL = 'https://gql.twitch.tv/gql';
const INTEGRITY = 'https://gql.twitch.tv/integrity';
const CLIENT = 'kimne78kx3ncx6brgo4mv6wki5h1ko';
const HEADER_NAMES = new Set(['authorization', 'client-id', 'client-integrity', 'client-version',
  'client-session-id', 'x-device-id', 'device-id', 'accept-language']);
export class HelperError extends Error {}
export class BrowserCommandError extends HelperError {
  constructor(method, code) {
    super(`Browser command ${method} failed (code ${Number.isInteger(code) ? code : 'unknown'}). Retry login or try another Chromium browser.`);
    this.method = method; this.code = code;
  }
}

export function dashboardUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw new HelperError('Enter a complete dashboard URL.'); }
  const host = url.hostname;
  const octets = host.split('.').map(Number);
  const privateV4 = octets.length === 4 && octets.every(n => Number.isInteger(n) && n >= 0 && n <= 255) &&
    (octets[0] === 10 || octets[0] === 127 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
      (octets[0] === 192 && octets[1] === 168) || (octets[0] === 169 && octets[1] === 254));
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/' ||
      !['http:', 'https:'].includes(url.protocol) ||
      (url.protocol === 'http:' && !(privateV4 || host === 'localhost' || host === '[::1]' || /^\[f[cd][0-9a-f]{2}:/i.test(host)))) {
    throw new HelperError('Use the trusted dashboard origin: private-network HTTP or verified HTTPS.');
  }
  return url.origin;
}

export class CaptureObservation {
  constructor(clock = () => Math.floor(Date.now() / 1000)) {
    this.clock = clock; this.requests = new Map(); this.responses = new Map(); this.issued = new Map(); this.campaigns = new Set();
  }
  async observe({ method, params }, body) {
    const id = params.requestId;
    if (this.requests.size + this.responses.size > 256) throw new HelperError('Browser capture exceeded its limit. Retry login.');
    if (method === 'Network.requestWillBeSent' && params.request.url === GQL && params.request.method === 'POST') {
      const headers = Object.fromEntries(Object.entries(params.request.headers).map(([k,v]) => [k.toLowerCase(),v]));
      if (headers['client-id'] === CLIENT && /^OAuth [A-Za-z0-9_-]+$/.test(headers.authorization || '') && headers['client-integrity']) {
        this.requests.set(id, Object.fromEntries(Object.entries(headers).filter(([k]) => HEADER_NAMES.has(k))));
      }
    } else if (method === 'Network.responseReceived' && [GQL, INTEGRITY].includes(params.response.url)) {
      this.responses.set(id, params.response);
    } else if (method === 'Network.loadingFinished') {
      const response = this.responses.get(id);
      if (!response || response.status !== 200 || response.fromDiskCache || response.fromServiceWorker) return;
      // Unauthenticated GraphQL traffic and preflight replies do not contribute to a session.
      if (response.url === GQL && !this.requests.has(id)) { this.responses.delete(id); return; }
      let data;
      try { data = await body(id); }
      catch (error) {
        // Chromium can discard a response during navigation. A later, complete response is
        // required; never accept missing evidence or abort login for an evicted response.
        if (!(error instanceof BrowserCommandError) || error.method !== 'Network.getResponseBody' || error.code !== -32000) throw error;
        this.requests.delete(id); this.responses.delete(id); return;
      }
      this.responses.delete(id);
      if (response.url === INTEGRITY && typeof data?.token === 'string' && Number.isFinite(data.expiration)) {
        this.issued.set(data.token, [this.clock(), Math.floor(data.expiration / 1000)]);
      } else if (this.requests.has(id)) {
        const rows = Array.isArray(data) ? data : [data];
        if (rows.some(row => row && !row.errors?.length && Array.isArray(row.data?.currentUser?.dropCampaigns))) this.campaigns.add(id);
      }
    } else if (method === 'Network.loadingFailed') {
      this.requests.delete(id); this.responses.delete(id);
    }
  }
  bundle(userAgent) {
    for (const [id, headers] of this.requests) {
      const issued = this.issued.get(headers['client-integrity']);
      if (!issued || !this.campaigns.has(id) || issued[1] <= this.clock() + 60 || issued[1] > issued[0] + 86400) continue;
      return { version: 1, captured_at: issued[0], expires_at: issued[1], user_agent: userAgent, headers };
    }
    return null;
  }
}

export class Cdp {
  constructor(socket) {
    this.closed = false;
    this.socket = socket; this.nextId = 0; this.pending = new Map(); this.events = []; this.waiter = null;
    socket.addEventListener('message', event => {
      if (typeof event.data !== 'string' || event.data.length > 8 * 1024 * 1024) { socket.close(); return; }
      let message; try { message = JSON.parse(event.data); } catch { socket.close(); return; }
      if (message.id) {
        const request = this.pending.get(message.id);
        if (request) { this.pending.delete(message.id); clearTimeout(request.timer); message.error ? request.reject(new BrowserCommandError(request.method, message.error.code)) : request.resolve(message.result); }
      } else if (message.method?.startsWith('Network.')) {
        if (this.events.length > 2048) { socket.close(); return; }
        if (this.waiter) { this.waiter(message); this.waiter = null; } else this.events.push(message);
      }
    });
    socket.addEventListener('close', () => {
      this.closed = true;
      for (const item of this.pending.values()) { clearTimeout(item.timer); item.reject(new HelperError('Browser disconnected.')); }
      this.pending.clear(); if (this.waiter) { this.waiter(null); this.waiter = null; }
    });
  }
  static async open(url, port, signal) {
    signal.throwIfAborted();
    const parsed = new URL(url);
    if (parsed.protocol !== 'ws:' || parsed.hostname !== '127.0.0.1' || parsed.port !== String(port)) throw new HelperError('Unexpected browser control address.');
    const socket = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { socket.close(); reject(new HelperError('Browser connection timed out.')); }, 10000);
      socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, {once:true});
      socket.addEventListener('error', () => { clearTimeout(timer); reject(new HelperError('Cannot connect to the owned browser.')); }, {once:true});
    });
    if (signal.aborted) { socket.close(); signal.throwIfAborted(); }
    signal.addEventListener('abort', () => socket.close(), { once: true });
    return new Cdp(socket);
  }
  command(method, params = {}) {
    if (this.socket.readyState !== WebSocket.OPEN) return Promise.reject(new HelperError('Browser disconnected.'));
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new HelperError('Browser command timed out.')); }, 15000);
      this.pending.set(id, {resolve,reject,timer,method});
      this.socket.send(JSON.stringify({id,method,params}));
    });
  }
  event() { return this.events.length ? Promise.resolve(this.events.shift()) : this.closed ? Promise.resolve(null) : new Promise(resolve => { this.waiter = resolve; }); }
  async body(id) {
    const reply = await this.command('Network.getResponseBody', {requestId:id});
    if (reply.body.length > 4 * 1024 * 1024) throw new HelperError('Browser response exceeded its limit.');
    try { return JSON.parse(reply.base64Encoded ? Buffer.from(reply.body,'base64').toString('utf8') : reply.body); }
    catch { return null; }
  }
  close() { this.socket.close(); }
}

async function browserExecutable(explicit) {
  const candidates = explicit ? [resolve(explicit)] : platform() === 'win32' ? [
    join(process.env.PROGRAMFILES || 'C:/Program Files', 'Google/Chrome/Application/chrome.exe'),
    join(process.env['PROGRAMFILES(X86)'] || 'C:/Program Files (x86)', 'Microsoft/Edge/Application/msedge.exe'),
    join(process.env.LOCALAPPDATA || homedir(), 'Google/Chrome/Application/chrome.exe'),
  ] : platform() === 'darwin' ? [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  ] : ['/usr/bin/google-chrome','/usr/bin/chromium','/usr/bin/chromium-browser','/usr/bin/microsoft-edge'];
  for (const candidate of candidates) { try { await access(candidate); return candidate; } catch {} }
  throw new HelperError('Install native Chrome, Edge, or Chromium, or pass its executable as the second argument.');
}

export async function stopBrowser(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  if (platform() === 'win32') {
    const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], {stdio:'ignore', windowsHide:true});
    await new Promise(resolve => { killer.once('exit',resolve); killer.once('error',resolve); });
  } else { try { process.kill(-child.pid, 'SIGTERM'); } catch {} }
  await Promise.race([new Promise(resolve => child.once('exit',resolve)), sleep(3000)]);
  if (platform() !== 'win32') { try { process.kill(-child.pid, 'SIGKILL'); } catch {} }
}

export async function startBrowser(executable, profile, args) {
  const child = spawn(executable, ['--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check',
    '--disable-background-mode', ...args], { stdio:'ignore', detached:platform() !== 'win32', windowsHide:true,
    env: { ...process.env, XDG_CONFIG_HOME:join(profile,'config'), XDG_CACHE_HOME:join(profile,'cache') } });
  await new Promise((resolve, reject) => { child.once('spawn',resolve); child.once('error',() => reject(new HelperError('Cannot launch browser.'))); });
  return child;
}

export async function capture(executable, profile, signal, checkOnly = false, browserArgs = []) {
  await rm(join(profile,'DevToolsActivePort'), {force:true});
  const child = await startBrowser(executable, profile, ['--headless=new','--remote-debugging-address=127.0.0.1',
    '--remote-debugging-port=0','--disable-dev-shm-usage',...browserArgs,'about:blank']);
  const timer = AbortSignal.timeout(120000);
  const abort = AbortSignal.any([signal,timer]);
  let cdp;
  try {
    let port;
    for (let i = 0; i < 200; i++) {
      abort.throwIfAborted();
      if (child.exitCode !== null) throw new HelperError('Browser exited during capture.');
      try { const value = (await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]; if (/^[0-9]{1,5}$/.test(value) && +value > 0 && +value < 65536) { port = +value; break; } } catch {}
      await sleep(100,undefined,{signal:abort});
    }
    if (!port) throw new HelperError('Browser did not start its local control connection.');
    const response = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, {method:'PUT',redirect:'error',signal:abort});
    const target = await response.json();
    cdp = await Cdp.open(target.webSocketDebuggerUrl, port, abort);
    await cdp.command('Network.enable', {maxTotalBufferSize:16777216, maxResourceBufferSize:4194304});
    await cdp.command('Network.setCacheDisabled',{cacheDisabled:true});
    const agent = (await cdp.command('Runtime.evaluate',{expression:'navigator.userAgent',returnByValue:true})).result.value;
    if (checkOnly) {
      if (typeof agent !== 'string' || !agent.includes('Chrome/')) throw new HelperError('Unexpected browser response.');
      return;
    }
    const observation = new CaptureObservation();
    await cdp.command('Page.navigate',{url:'https://www.twitch.tv/drops/campaigns'});
    while (!abort.aborted) {
      const event = await cdp.event();
      if (!event) break;
      await observation.observe(event, id => cdp.body(id));
      const bundle = observation.bundle(agent);
      if (bundle) return bundle;
    }
    throw new HelperError('Twitch did not provide a verified Drops session. Run the helper again and finish Twitch verification.');
  } finally {
    if (cdp) { await cdp.command('Browser.close').catch(() => {}); cdp.close(); }
    await stopBrowser(child);
  }
}

async function api(origin, path, body, signal) {
  let response;
  try {
    response = await fetch(origin + '/api/auth/browser/' + path, {method:'POST', redirect:'error',
      headers:{'Content-Type':'application/json','Origin':origin},body:JSON.stringify(body),signal:AbortSignal.any([signal,AbortSignal.timeout(20000)])});
  } catch { throw new HelperError('Cannot reach the dashboard. Check its address and network connection.'); }
  if (!response.ok) throw new HelperError('Dashboard rejected the helper request. Request a new pairing code and run the helper again.');
  const text = await response.text();
  if (text.length > 4096) throw new HelperError('Unexpected dashboard response.');
  return JSON.parse(text);
}

export async function main(args = process.argv.slice(2)) {
  if (!globalThis.WebSocket) throw new HelperError('Install Node.js 22.4 or newer.');
  const stop = new AbortController();
  const cancel = () => stop.abort();
  process.once('SIGINT',cancel); process.once('SIGTERM',cancel);
  const input = createInterface({input:process.stdin,output:process.stdout});
  let profile, interactive;
  try {
    if (args[0] === '--check-browser') {
      const executable = await browserExecutable(args[1]);
      profile = await mkdtemp(join(tmpdir(),'dockdrops-login-'));
      await chmod(profile,0o700);
      await capture(executable,profile,stop.signal,true);
      console.log('Browser launch and local control passed. Twitch login was not tested.');
      return;
    }
    const origin = dashboardUrl(args[0] || await input.question('Dashboard URL: '));
    const code = (await input.question('Pairing code shown in DockDrops: ')).trim();
    const executable = await browserExecutable(args[1]);
    const {ticket} = await api(origin,'claim',{code},stop.signal);
    if (!/^[A-Za-z0-9_-]{43}$/.test(ticket)) throw new HelperError('Unexpected pairing response.');
    input.close();
    profile = await mkdtemp(join(tmpdir(),'dockdrops-login-'));
    await chmod(profile,0o700);
    console.log('Sign in to Twitch in the new browser, finish verification, then close all windows of that browser. Your usual browser profile is untouched.');
    interactive = await startBrowser(executable,profile,['https://www.twitch.tv/login']);
    const closed = new Promise(resolve => interactive.once('exit',resolve));
    await Promise.race([closed, sleep(900000,undefined,{signal:stop.signal}).then(() => { throw new HelperError('Login timed out.'); })]);
    console.log('Verifying Drops access. Keep this helper open for automatic session renewal.');
    while (!stop.signal.aborted) {
      const context = await capture(executable,profile,stop.signal);
      await api(origin,'submit',{ticket,context},stop.signal);
      let ready = false;
      for (let i = 0; i < 90; i++) {
        const {state} = await api(origin,'status',{ticket},stop.signal);
        if (state === 'ready') { ready = true; break; }
        if (state === 'failed') throw new HelperError('Twitch rejected server-side verification. Check login and miner network routing, then reconnect.');
        await sleep(1000,undefined,{signal:stop.signal});
      }
      if (!ready) throw new HelperError('Server verification timed out. Check the dashboard before retrying.');
      console.log('Session accepted. Leave this helper running; Ctrl+C disconnects renewal.');
      const seconds = Math.max(15,Math.min(1800,Math.floor((context.expires_at - Date.now()/1000) / 2)));
      await sleep(seconds*1000,undefined,{signal:stop.signal});
    }
  } finally {
    stop.abort(); input.close(); await stopBrowser(interactive);
    if (profile) {
      // This path is owned by this invocation and never comes from an argument or dashboard response.
      if (dirname(profile) === resolve(tmpdir()) && profile.startsWith(join(tmpdir(),'dockdrops-login-'))) {
        await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:500}).catch(() => {
          console.error('Temporary browser profile cleanup failed. Remove the dockdrops-login folder from your temporary directory before sharing this computer.');
        });
      }
    }
    process.removeListener('SIGINT',cancel); process.removeListener('SIGTERM',cancel);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error(error instanceof HelperError ? error.message : 'Login helper stopped. Check the dashboard and rerun to reconnect.');
    process.exitCode = 1;
  });
}
