// Scoped SDK renewal adapted from rangermix/TwitchDropsMiner 1182d0172458.
// MIT, Copyright (c) 2024 DevilXD. Full license in THIRD_PARTY_NOTICES.md.
import {mkdtemp, chmod, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {setTimeout as sleep} from 'node:timers/promises';
import {Cdp, browserPort, startBrowser, stopBrowser} from '../src/main/resources/web/login-helper.mjs';

export const SDK_URL = 'https://k.twitchcdn.net/149e9513-01fa-4fb0-aad4-566afd725d1b/2d206a39-8ed7-437e-a3be-862e0f06eea3/p.js';
const PAGE = 'https://www.twitch.tv/';
const INTEGRITY = 'https://gql.twitch.tv/integrity';
const COOKIE_URL = 'https://k.twitchcdn.net/';
const HEADERS = new Set(['authorization','client-id','client-integrity','client-version','client-session-id','x-device-id','device-id','accept-language']);

export function validateSeed(seed, now = Date.now()/1000, allowMissingCookie = false) {
  const cookie = seed?.sdk_cookie;
  if (!seed || seed.version !== 1 || Object.keys(seed).some(k => !['version','captured_at','expires_at','user_agent','headers','sdk_cookie'].includes(k)) ||
      JSON.stringify(seed).length > 36*1024 || typeof seed.user_agent !== 'string' || !/^[\x20-\x7e]{1,1024}$/.test(seed.user_agent) ||
      !Number.isInteger(seed.captured_at) || seed.captured_at <= 0 || seed.captured_at > now+60 ||
      !Number.isInteger(seed.expires_at) || seed.expires_at <= seed.captured_at || seed.expires_at > seed.captured_at+86400 ||
      !seed.headers || Array.isArray(seed.headers) || Object.keys(seed.headers).some(k => !HEADERS.has(k)) ||
      Object.values(seed.headers).some(v => typeof v !== 'string' || !/^[\x20-\x7e]{1,16384}$/.test(v)) ||
      seed.headers['client-id'] !== 'kimne78kx3ncx6brgo4mv6wki5h1ko' || !/^OAuth [A-Za-z0-9_-]{1,512}$/.test(seed.headers.authorization || '') ||
      !seed.headers['client-integrity'] || !(seed.headers['x-device-id'] || seed.headers['device-id']) ||
      (!(allowMissingCookie && cookie === undefined) && (!cookie || Object.keys(cookie).sort().join(',') !== 'expires_at,value' ||
      typeof cookie.value !== 'string' || !/^[\x21\x23-\x2b\x2d-\x3a\x3c-\x5b\x5d-\x7e]{1,8192}$/.test(cookie.value) ||
      !Number.isInteger(cookie.expires_at) || cookie.expires_at <= now))) throw Error('Invalid renewal seed');
  return seed;
}

export class SdkCookieUnavailable extends Error {}

export async function readSdkCookie(cdp, now = Date.now()/1000) {
  const result = await cdp.command('Network.getCookies',{urls:[COOKIE_URL]});
  const cookies = result.cookies?.filter(c => c.name === 'KP_UIDz-ssn' && c.domain === 'k.twitchcdn.net' &&
    c.path === '/' && c.secure === true && c.httpOnly === true);
  if (cookies?.length !== 1) throw new SdkCookieUnavailable('SDK cookie unavailable');
  const c = cookies[0];
  if (typeof c.value !== 'string' || !/^[\x21\x23-\x2b\x2d-\x3a\x3c-\x5b\x5d-\x7e]{1,8192}$/.test(c.value) ||
      !Number.isFinite(c.expires) || c.expires <= now) throw new SdkCookieUnavailable('SDK cookie unavailable');
  return {value:c.value, expires_at:Math.floor(c.expires)};
}

// Run only the Twitch SDK on an intercepted, empty Twitch-origin document. Credentials are
// arguments to a fixed function, never interpolated into executable code or arbitrary URLs.
const ISSUE = `async function(headers, sdk) {
  return await new Promise(resolve => {
    const timer = setTimeout(() => resolve({failure:true}), 90000);
    const finish = value => { clearTimeout(timer); resolve(value); };
    document.addEventListener('kpsdk-load', () => window.KPSDK.configure([
      {protocol:'https:', method:'POST', domain:'gql.twitch.tv', path:'/integrity'}
    ]), {once:true});
    document.addEventListener('kpsdk-ready', async () => {
      try {
        const r = await fetch('https://gql.twitch.tv/integrity', {
          method:'POST', headers, body:null, credentials:'omit', mode:'cors', redirect:'error', signal:AbortSignal.timeout(30000)
        });
        finish({status:r.status, data:await r.json()});
      } catch { finish({failure:true}); }
    }, {once:true});
    const script = document.createElement('script');
    script.onerror = () => finish({failure:true}); script.src = sdk; document.body.appendChild(script);
  });
}`;

export async function acquireSeed(cdp, seed, signal, initial = false) {
  // Only initial bootstrap may begin without a cookie; every returned seed requires one.
  validateSeed(seed, Date.now()/1000, initial);
  let loaded = false, proof = null, failure = null;
  const posts = new Set(), responses = new Map(), cached = new Set();
  const collect = (async () => {
    for (;;) {
      const event = await cdp.event();
      if (!event) throw Error('Browser disconnected');
      const {method,params:p} = event, id = p.requestId;
      if (method === 'Fetch.requestPaused') {
        if (p.request.url !== PAGE || p.request.method !== 'GET' || p.resourceType !== 'Document') throw Error('Unexpected SDK page');
        await cdp.command('Fetch.fulfillRequest',{requestId:id,responseCode:200,
          responseHeaders:[{name:'Content-Type',value:'text/html; charset=utf-8'}],
          body:Buffer.from('<!doctype html><html><body></body></html>').toString('base64')});
      } else if (method === 'Page.loadEventFired') loaded = true;
      else if (method === 'Network.requestWillBeSent' && p.request.url === INTEGRITY && p.request.method === 'POST') posts.add(id);
      else if (method === 'Network.requestServedFromCache') cached.add(id);
      else if (method === 'Network.responseReceived' && posts.has(id)) responses.set(id,p.response);
      else if (method === 'Network.loadingFailed' && posts.has(id)) throw Error('SDK issuance failed');
      else if (method === 'Network.loadingFinished' && posts.has(id)) {
        const response = responses.get(id);
        if (response?.url !== INTEGRITY || response.status !== 200 || response.fromDiskCache || response.fromServiceWorker || cached.has(id)) throw Error('SDK issuance failed');
        proof = await cdp.body(id);
      }
      if (posts.size + responses.size + cached.size > 256) throw Error('SDK capture limit');
    }
  })().catch(error => { failure = error; });
  const waitFor = async predicate => {
    while (!predicate()) {
      signal.throwIfAborted(); if (failure) throw failure;
      await sleep(25,undefined,{signal});
    }
    signal.throwIfAborted(); if (failure) throw failure;
  };
  try {
    await cdp.command('Network.enable');
    await cdp.command('Network.setCacheDisabled',{cacheDisabled:true});
    await cdp.command('Network.setBypassServiceWorker',{bypass:true});
    if (seed.sdk_cookie) await cdp.command('Network.setCookies',{cookies:[{name:'KP_UIDz-ssn',value:seed.sdk_cookie.value,
      expires:seed.sdk_cookie.expires_at,domain:'k.twitchcdn.net',path:'/',secure:true,httpOnly:true,sameSite:'None'}]});
    await cdp.command('Page.enable');
    await cdp.command('Fetch.enable',{patterns:[{urlPattern:PAGE,resourceType:'Document',requestStage:'Request'}]});
    await cdp.command('Page.navigate',{url:PAGE});
    await waitFor(() => loaded);
    const userAgent = (await cdp.command('Runtime.evaluate',{expression:'navigator.userAgent',returnByValue:true})).result.value;
    const global = await cdp.command('Runtime.evaluate',{expression:'globalThis'});
    const headers = {...seed.headers}; delete headers['client-integrity'];
    const result = await cdp.command('Runtime.callFunctionOn',{objectId:global.result.objectId,functionDeclaration:ISSUE,
      arguments:[{value:headers},{value:SDK_URL}],awaitPromise:true,returnByValue:true},110000);
    const data = result.result?.value?.data;
    if (result.exceptionDetails || result.result?.value?.status !== 200 || !data) throw Error('SDK issuance failed');
    await waitFor(() => proof);
    if (data.token !== proof.token || data.expiration !== proof.expiration || !Number.isFinite(data.expiration)) throw Error('Unverified SDK issuance');
    const now = Math.floor(Date.now()/1000), expiry = Math.floor(data.expiration/1000);
    if (data.token === seed.headers['client-integrity'] || expiry <= Math.max(now+60,initial ? 0 : seed.expires_at)) throw Error('Stale SDK issuance');
    const cookie = await readSdkCookie(cdp);
    if (cookie.expires_at <= Math.max(expiry,initial ? 0 : seed.sdk_cookie.expires_at)) throw Error('Stale SDK cookie');
    return validateSeed({version:1,captured_at:now,expires_at:expiry,user_agent:userAgent,
      headers:{...headers,'client-integrity':data.token},sdk_cookie:cookie});
  } finally {
    // This protocol belongs to this one acquisition, including the event collector.
    cdp.close(); await collect;
  }
}

// A valid signed-in profile need not contain the SDK renewal cookie. Bootstrap in an empty
// context of the same headed browser, never copy or clear the interactive cookie jar.
export async function bootstrapSeed(port, captured, parentSignal, acquire = acquireSeed) {
  validateSeed(captured, Date.now()/1000, true);
  const signal = AbortSignal.any([parentSignal,AbortSignal.timeout(150000)]);
  const response = await fetch(`http://127.0.0.1:${port}/json/version`,{redirect:'error',signal});
  if (!response.ok) throw Error('Browser unavailable');
  const version = await response.json();
  const controller = await Cdp.open(version.webSocketDebuggerUrl,port,signal);
  let contextId, cdp;
  try {
    const context = await controller.command('Target.createBrowserContext',{disposeOnDetach:true});
    contextId = context.browserContextId;
    if (typeof contextId !== 'string' || !/^[a-zA-Z0-9-]+$/.test(contextId)) throw Error('Invalid browser context');
    const {targetId} = await controller.command('Target.createTarget',{url:'about:blank',browserContextId:contextId});
    if (typeof targetId !== 'string' || !/^[a-zA-Z0-9-]+$/.test(targetId)) throw Error('Invalid browser target');
    cdp = await Cdp.open(`ws://127.0.0.1:${port}/devtools/page/${targetId}`,port,signal,
      ['Fetch.requestPaused','Page.loadEventFired','Network.requestServedFromCache']);
    const seed = await acquire(cdp,captured,signal,true);
    signal.throwIfAborted();
    return validateSeed(seed);
  } finally {
    cdp?.close();
    if (contextId) await controller.command('Target.disposeBrowserContext',{browserContextId:contextId}).catch(() => {});
    controller.close(); // disposeOnDetach also covers cancellation/disconnection.
  }
}

export async function issueSeed(executable, browserArgs, seed, parentSignal, initial = false) {
  validateSeed(seed);
  const signal = AbortSignal.any([parentSignal,AbortSignal.timeout(150000)]);
  signal.throwIfAborted();
  const profile = await mkdtemp(join(tmpdir(),'dockdrops-renew-'));
  let child, cdp;
  try {
    await chmod(profile,0o700);
    const port = await browserPort(); signal.throwIfAborted();
    child = await startBrowser(executable,profile,[...browserArgs,'--headless=new',
      '--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${port}`,'--disable-dev-shm-usage',
      '--disable-blink-features=AutomationControlled','about:blank']);
    for (let n = 0; n < 200; n++) {
      signal.throwIfAborted();
      if (child.exitCode !== null) throw Error('Browser launch failed');
      let target;
      try {
        const response = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,
          {method:'PUT',redirect:'error',signal:AbortSignal.any([signal,AbortSignal.timeout(500)])});
        if (response.ok) target = await response.json();
      } catch {}
      if (target) {
        cdp = await Cdp.open(target.webSocketDebuggerUrl,port,signal,['Fetch.requestPaused','Page.loadEventFired','Network.requestServedFromCache']);
        return await acquireSeed(cdp,seed,signal,initial);
      }
      await sleep(100,undefined,{signal});
    }
    throw Error('Browser launch timed out');
  } finally {
    cdp?.close(); await stopBrowser(child);
    // Exactly the owned mkdtemp profile; never reuse a user or interactive profile.
    await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});
  }
}
