// Private browser companion. No miner volume, saved credentials, or public listening port.
import {createServer} from 'node:http';
import {createServer as createPortReservation} from 'node:net';
import {mkdtemp, chmod, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve, dirname} from 'node:path';
import {pathToFileURL} from 'node:url';
import {setTimeout as sleep} from 'node:timers/promises';
import {Cdp, capture, startBrowser, stopBrowser} from '../src/main/resources/web/login-helper.mjs';

const executable = process.env.DOCKDROPS_CHROMIUM || '/usr/bin/chromium';
// This browser is confined by its own non-root, read-only container without the miner volume.
// Docker's no-new-privileges prevents Chromium's setuid sandbox from starting.
const browserArgs = process.env.DOCKDROPS_CONTAINER_BROWSER === 'true' ? ['--no-sandbox'] : [];
const keys = new Set(['Tab','Enter','Backspace','Delete','Escape','ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End']);

export function validateInput(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw Error('Invalid input');
  const fields = body.kind === 'click' ? ['id','kind','x','y'] : body.kind === 'wheel' ? ['id','kind','delta'] : ['id','kind','text','key','shift'];
  if (Object.keys(body).some(k => !fields.includes(k))) throw Error('Invalid input');
  if (body.kind === 'click' && Number.isInteger(body.x) && body.x >= 0 && body.x < 1100 && Number.isInteger(body.y) && body.y >= 0 && body.y < 760) return body;
  if (body.kind === 'wheel' && Number.isInteger(body.delta) && Math.abs(body.delta) <= 760) return body;
  if (body.kind === 'text' && typeof body.text === 'string' && body.text.length > 0 && body.text.length <= 256 && !/[\x00-\x1f\x7f]/.test(body.text) && body.key === undefined && body.shift === undefined) return body;
  if (body.kind === 'key' && keys.has(body.key) && typeof body.shift === 'boolean' && body.text === undefined) return body;
  throw Error('Invalid input');
}

export class BrowserWorker {
  constructor(options = {}) { this.session = null; this.executable = options.executable || executable; this.browserArgs = options.browserArgs || browserArgs; }
  status() {
    const s = this.session;
    return s ? {state:s.state, sequence:s.sequence, ...(s.context ? {context:s.context} : {})} : {state:'idle',sequence:0};
  }
  async start(id) {
    await this.cancel();
    const s = {id, state:'starting', stop:new AbortController(), sequence:0, context:null, cdp:null, child:null, profile:null};
    this.session = s;
    s.task = this.run(s).catch(() => { if (!s.stop.signal.aborted) s.state = 'failed'; }).finally(async () => {
      await this.closeBrowser(s);
      // Only delete the profile created by this invocation, under the resolved temporary root.
      if (s.profile && dirname(s.profile) === resolve(tmpdir()) && s.profile.startsWith(join(tmpdir(),'dockdrops-browser-'))) {
        await rm(s.profile,{recursive:true,force:true,maxRetries:5,retryDelay:200}).catch(() => {});
      }
      s.context = null;
    });
  }
  async run(s) {
    s.profile = await mkdtemp(join(tmpdir(),'dockdrops-browser-'));
    await chmod(s.profile,0o700);
    // Use an allocated nonzero port for the interactive browser, as upstream does. Chrome treats
    // port zero as automated execution. This viewer does not inject navigator overrides.
    const reservation = createPortReservation();
    await new Promise((resolve,reject) => { reservation.once('error',reject); reservation.listen(0,'127.0.0.1',resolve); });
    s.port = reservation.address().port;
    await new Promise(resolve => reservation.close(resolve));
    s.stop.signal.throwIfAborted();
    s.child = await startBrowser(this.executable,s.profile,[...this.browserArgs,'--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${s.port}`,
      '--disable-dev-shm-usage','--window-size=1100,850','--disable-extensions','about:blank']);
    const signal = AbortSignal.any([s.stop.signal,AbortSignal.timeout(480000)]);
    let ready = false;
    for (let i = 0; i < 200; i++) {
      signal.throwIfAborted();
      if (s.child.exitCode !== null) throw Error('Browser launch failed');
      try {
        const response = await fetch(`http://127.0.0.1:${s.port}/json/version`,{redirect:'error',signal:AbortSignal.any([signal,AbortSignal.timeout(500)])});
        const version = await response.json();
        if (response.ok && typeof version.webSocketDebuggerUrl === 'string') { ready = true; break; }
      } catch {}
      await sleep(100,undefined,{signal});
    }
    if (!ready) throw Error('Browser launch timed out');
    const target = await (await fetch(`http://127.0.0.1:${s.port}/json/new?about:blank`,{method:'PUT',redirect:'error',signal})).json();
    s.cdp = await Cdp.open(target.webSocketDebuggerUrl,s.port,signal);
    await s.cdp.command('Browser.setDownloadBehavior',{behavior:'deny'});
    await s.cdp.command('Emulation.setDeviceMetricsOverride',{width:1100,height:760,deviceScaleFactor:1,mobile:false});
    await s.cdp.command('Page.navigate',{url:'https://www.twitch.tv/login'});
    s.state = 'interactive';
    while (!s.finished) { await sleep(200,undefined,{signal}); }
    s.state = 'capturing';
    await this.closeBrowser(s);
    while (!s.stop.signal.aborted) {
      s.context = await capture(this.executable,s.profile,s.stop.signal,false,this.browserArgs);
      s.sequence++;
      s.state = 'capturing';
      const deadline = Date.now() + 120000;
      while (s.accepted !== s.sequence) {
        if (Date.now() > deadline) throw Error('Server verification timed out');
        await sleep(500,undefined,{signal:s.stop.signal});
      }
      s.state = 'ready';
      const seconds = Math.max(15,Math.min(1800,Math.floor((s.context.expires_at-Date.now()/1000)/2)));
      s.context = null;
      await sleep(seconds*1000,undefined,{signal:s.stop.signal});
      s.state = 'capturing';
    }
  }
  async closeBrowser(s) {
    if (s.cdp) { await s.cdp.command('Browser.close').catch(() => {}); s.cdp.close(); s.cdp = null; }
    await stopBrowser(s.child); s.child = null;
  }
  async cancel(id) {
    const s = this.session;
    if (!s || (id && s.id !== id)) return;
    s.stop.abort(); await s.task;
    if (this.session === s) this.session = null;
  }
  sessionFor(id) {
    const s = this.session;
    if (!s || s.id !== id) throw Error('Expired login');
    return s;
  }
  async frame() {
    const s = this.session;
    if (s?.state !== 'interactive') throw Error('Browser not ready');
    const result = await s.cdp.command('Page.captureScreenshot',{format:'jpeg',quality:70,captureBeyondViewport:false});
    if (typeof result.data !== 'string' || result.data.length > 2_000_000) throw Error('Frame too large');
    return {id:s.id,image:result.data};
  }
  async input(body) {
    validateInput(body);
    const s = this.sessionFor(body.id);
    if (s.state !== 'interactive') throw Error('Browser not ready');
    if (body.kind === 'click') {
      await s.cdp.command('Input.dispatchMouseEvent',{type:'mousePressed',x:body.x,y:body.y,button:'left',clickCount:1});
      await s.cdp.command('Input.dispatchMouseEvent',{type:'mouseReleased',x:body.x,y:body.y,button:'left',clickCount:1});
    } else if (body.kind === 'wheel') {
      await s.cdp.command('Input.dispatchMouseEvent',{type:'mouseWheel',x:550,y:380,deltaX:0,deltaY:body.delta});
    } else if (body.kind === 'text') {
      await s.cdp.command('Input.insertText',{text:body.text});
    } else {
      const codes = {Tab:9,Enter:13,Backspace:8,Delete:46,Escape:27,ArrowLeft:37,ArrowRight:39,ArrowUp:38,ArrowDown:40,Home:36,End:35};
      const params = {key:body.key,code:body.key,windowsVirtualKeyCode:codes[body.key],modifiers:body.shift ? 8 : 0};
      await s.cdp.command('Input.dispatchKeyEvent',{type:'rawKeyDown',...params});
      await s.cdp.command('Input.dispatchKeyEvent',{type:'keyUp',...params});
    }
  }
}

export function createWorkerServer(worker = new BrowserWorker(), port = 8091) {
  let busy = false, frameBusy = false;
  const server = createServer(async (req,res) => {
    res.setHeader('Cache-Control','no-store'); res.setHeader('Content-Type','application/json');
    const send = (status,body) => { res.writeHead(status); res.end(JSON.stringify(body)); };
    // Browser pages cannot send this header without a CORS preflight. No CORS is enabled,
    // and all Origin-bearing requests are rejected, including on read-only routes.
    if (req.headers.host !== `127.0.0.1:${server.address().port}` || req.headers.origin || req.headers['x-dockdrops-internal'] !== '1') {
      req.resume(); return send(403,{error:'Private browser service.'});
    }
    if (req.method === 'GET' && req.url === '/status') return send(200,worker.status());
    if (req.method === 'GET' && req.url === '/frame') {
      if (frameBusy) return send(429,{error:'Browser busy.'});
      frameBusy = true;
      try { return send(200,await worker.frame()); }
      catch { return send(409,{error:'Browser view unavailable.'}); }
      finally { frameBusy = false; }
    }
    if (busy) { req.resume(); return send(429,{error:'Browser busy.'}); }
    busy = true;
    try {
      if (req.method !== 'POST' || !['/start','/cancel','/finish','/input','/accepted'].includes(req.url)) return send(405,{error:'Method not allowed.'});
      if (req.headers['content-type']?.split(';')[0] !== 'application/json') return send(415,{error:'JSON required.'});
      let size = 0; const chunks = [];
      for await (const chunk of req) { size += chunk.length; if (size > 4096) throw Error('Too large'); chunks.push(chunk); }
      const body = JSON.parse(Buffer.concat(chunks).toString());
      if (typeof body.id !== 'string' || !/^[0-9a-f-]{36}$/.test(body.id)) throw Error('Invalid id');
      if (req.url !== '/input' && Object.keys(body).some(k => !['id',...(req.url === '/accepted' ? ['sequence'] : [])].includes(k))) throw Error('Invalid fields');
      if (req.url === '/start') await worker.start(body.id);
      if (req.url === '/cancel') await worker.cancel(body.id);
      if (req.url === '/finish') { const s = worker.sessionFor(body.id); if (s.state !== 'interactive') throw Error('Not interactive'); s.finished = true; }
      if (req.url === '/input') await worker.input(body);
      if (req.url === '/accepted') {
        const s = worker.sessionFor(body.id);
        if (!Number.isInteger(body.sequence) || body.sequence !== s.sequence || !s.context) throw Error('Invalid sequence');
        s.accepted = body.sequence;
      }
      send(200,{ok:true});
    } catch { if (!res.headersSent) send(400,{error:'Browser request failed.'}); }
    finally { busy = false; req.resume(); }
  });
  server.requestTimeout = 10000; server.headersTimeout = 5000;
  server.listen(port,'127.0.0.1');
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const worker = new BrowserWorker();
  const server = createWorkerServer(worker);
  for (const signal of ['SIGINT','SIGTERM']) process.once(signal,async () => { server.close(); await worker.cancel(); });
}
