import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateSeed,readSdkCookie,acquireSeed,seedFailureCode,SeedFailure,issuanceHeaders,validateRecoveryIdentity,RenewalOAuthInvalid,issueSeed,runSeedBrowser} from '../../../browser/renewal.mjs';
import {BrowserWorker} from '../../../browser/worker.mjs';
import {access,stat,mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createConnection} from 'node:net';
import {setTimeout as sleep} from 'node:timers/promises';
import {seedBrowserLaunch,stopSeedBrowser} from '../../../browser/seed-browser.mjs';

const now = Math.floor(Date.now()/1000);
export const seed = () => ({version:1,captured_at:now-3600,expires_at:now-60,user_agent:'Chrome/test',
  headers:{'client-id':'kimne78kx3ncx6brgo4mv6wki5h1ko',authorization:'OAuth fixture','x-device-id':'fixture-device','client-integrity':'old-proof'},
  sdk_cookie:{value:'fixture-sdk',expires_at:now+3600}});

test('renewal accepts expired proof with a fresh scoped seed and rejects malformed credentials', () => {
  assert.equal(validateSeed(seed()).headers['client-integrity'],'old-proof');
  for (const change of [
    {sdk_cookie:{value:'bad;cookie',expires_at:now+100}},
    {sdk_cookie:{value:'fixture',expires_at:now-1}},
    {sdk_cookie:{value:'fixture',expires_at:now+100,url:'https://attacker.invalid'}},
    {headers:{...seed().headers,cookie:'secret'}},
    {headers:{...seed().headers,authorization:'OAuth fixture\r\nX: injected'}},
    {user_agent:'x\n'}, {version:2}, {url:'https://attacker.invalid'},
  ]) assert.throws(() => validateSeed({...seed(),...change}),/Invalid renewal seed/);
});

test('cookie extraction refuses broader domains duplicates insecure or unrelated cookies', async () => {
  const valid = {name:'KP_UIDz-ssn',domain:'k.twitchcdn.net',path:'/',secure:true,httpOnly:true,value:'fixture',expires:now+3600};
  const read = cookies => readSdkCookie({command:async (name,args) => {
    assert.equal(name,'Network.getCookies'); assert.deepEqual(args,{urls:['https://k.twitchcdn.net/']}); return {cookies};
  }});
  assert.equal((await read([valid,{...valid,name:'other'}])).value,'fixture');
  for (const cookies of [[valid,valid],[{...valid,domain:'.twitchcdn.net'}],[{...valid,httpOnly:false}],[{...valid,secure:false}],[]]) {
    await assert.rejects(read(cookies),/SDK cookie unavailable/);
  }
});

test('cancelled or superseded renewal cannot publish a late context', async () => {
  let release;
  const worker = new BrowserWorker({issue:() => new Promise(resolve => { release = resolve; })});
  await worker.renew('old',seed(),'123');
  const cancel = worker.cancel('old');
  release(seed()); await cancel;
  assert.equal(worker.status().state,'idle');
  await worker.renew('new',seed(),'123');
  await worker.cancel('old');
  assert.equal(worker.status().id,'new');
  const cancelled = worker.cancel('new'); release(seed()); await cancelled;
});

test('failed renewal exposes fixed errors only', async () => {
  const worker = new BrowserWorker({issue:async () => { throw Error('private-token-and-cookie'); }});
  await worker.renew('test',seed(),'123'); await worker.session.task;
  assert.equal(worker.status().error,'renewal_failed');
  assert.ok(!JSON.stringify(worker.status()).includes('private-token'));
  await worker.cancel();
});

test('a restarted JVM can replace an orphaned renewal but never an interactive login', async () => {
  const worker = new BrowserWorker({issue:async () => seed()});
  await worker.renew('previous-process',seed(),'123'); await worker.session.task;
  await worker.renew('restarted-process',seed(),'123'); await worker.session.task;
  assert.equal(worker.status().id,'restarted-process');
  await worker.cancel();
  worker.session = {id:'interactive',state:'interactive'};
  await assert.rejects(worker.renew('late-renewal',seed(),'123'),/Browser busy/);
  assert.equal(worker.status().id,'interactive');
});

function protocol({cached=false,serviceWorker=false,prefetch=false,cacheEvent=false,mismatch=false,replay=false,cookieStale=false,noPreviousCookie=false,noReturnedCookie=false,sdkFailure=null,rejected=false,wrongRequest=false}={}) {
  const events = [], waiters = [];
  const emit = value => waiters.length ? waiters.shift()(value) : events.push(value);
  const data = {token:replay ? 'old-proof' : 'new-proof',expiration:(now+3600)*1000};
  const cdp = {
    event:() => events.length ? Promise.resolve(events.shift()) : new Promise(resolve => waiters.push(resolve)),
    close:() => emit(null), body:async () => ({...data,token:mismatch ? 'different-proof' : data.token}),
    command:async (method,args) => {
      if (noPreviousCookie) assert.notEqual(method,'Network.setCookies');
      if (method === 'Page.navigate') emit({method:'Page.loadEventFired',params:{}});
      if (method === 'Runtime.evaluate') return {result:args.expression === 'globalThis' ? {objectId:'global'} : {value:'Chrome/test'}};
      if (method === 'Runtime.callFunctionOn') {
        assert.ok(!('client-integrity' in args.arguments[0].value));
        if (sdkFailure) return {result:{value:{failure:sdkFailure}}};
        if (rejected) return {result:{value:{status:403,data:{private:'private-token'}}}};
        emit({method:'Network.requestWillBeSent',params:{requestId:'i',request:{url:'https://gql.twitch.tv/integrity',method:'POST',
          headers:{...args.arguments[0].value,...(wrongRequest ? {'client-request-id':'wrong-request'} : {})}}}});
        if (cacheEvent) emit({method:'Network.requestServedFromCache',params:{requestId:'i'}});
        emit({method:'Network.responseReceived',params:{requestId:'i',response:{url:'https://gql.twitch.tv/integrity',status:200,
          fromDiskCache:cached,fromServiceWorker:serviceWorker,fromPrefetchCache:prefetch}}});
        emit({method:'Network.loadingFinished',params:{requestId:'i'}});
        return {result:{value:{status:200,data}}};
      }
      if (method === 'Network.getCookies') return {cookies:noReturnedCookie ? [] : [{name:'KP_UIDz-ssn',domain:'k.twitchcdn.net',path:'/',secure:true,httpOnly:true,
        value:'rotated-sdk',expires:cookieStale ? now+3600 : now+86400}]};
      return {};
    },
  };
  return cdp;
}

test('SDK acceptance requires fresh uncached correlated issuance and rotated cookie', async () => {
  const result = await acquireSeed(protocol(),seed(),AbortSignal.timeout(1000));
  assert.equal(result.headers['client-integrity'],'new-proof');
  assert.equal(result.sdk_cookie.value,'rotated-sdk');
  for (const option of [{cached:true},{serviceWorker:true},{prefetch:true},{cacheEvent:true},{mismatch:true},{replay:true},{cookieStale:true}]) {
    await assert.rejects(acquireSeed(protocol(option),seed(),AbortSignal.timeout(1000)));
  }
});

test('SDK acquisition distinguishes timeout script network rejection and cookie failures safely', async () => {
  for (const [option,code] of [
    [{sdkFailure:'sdk_timeout'},'sdk_timeout'],[{sdkFailure:'sdk_script'},'sdk_script'],
    [{sdkFailure:'sdk_fetch'},'sdk_fetch'],[{rejected:true},'sdk_rejected'],
    [{noReturnedCookie:true},'sdk_cookie'],[{replay:true},'sdk_proof'],
    [{sdkFailure:'private-token'},null],
  ]) {
    await assert.rejects(acquireSeed(protocol(option),seed(),AbortSignal.timeout(1000)),error => {
      assert.equal(seedFailureCode(error),code); assert.ok(!error.message.includes('private-token')); return true;
    });
  }
  const changed = new SeedFailure('sdk_cookie'); changed.code = 'private-token';
  assert.equal(seedFailureCode(changed),null);
  assert.equal(seedFailureCode(Object.assign(Error('private-token'),{code:'sdk_cookie'})),null);
});

test('only initial bootstrap accepts missing cookie and must obtain a verified fresh seed', async () => {
  const captured = seed(); delete captured.sdk_cookie;
  assert.throws(() => validateSeed(captured));
  await assert.rejects(acquireSeed(protocol(),captured,AbortSignal.timeout(1000)));
  const result = await acquireSeed(protocol({noPreviousCookie:true}),captured,AbortSignal.timeout(1000),true);
  assert.equal(validateSeed(result).sdk_cookie.value,'rotated-sdk');
  for (const option of [{noReturnedCookie:true},{cached:true},{mismatch:true},{replay:true}]) {
    await assert.rejects(acquireSeed(protocol({...option,noPreviousCookie:true}),captured,AbortSignal.timeout(1000),true));
  }
});

test('issuance uses its exact allowlist and unique request IDs without changing saved GraphQL context', async () => {
  const original = seed();
  original.headers = {...original.headers,'accept-language':'en-US','client-session-id':'session','client-version':'version','device-id':'legacy'};
  const saved = structuredClone(original);
  const first = issuanceHeaders(original.headers), second = issuanceHeaders(original.headers);
  assert.deepEqual(Object.keys(first).sort(),['authorization','client-id','client-request-id','client-session-id','client-version','x-device-id']);
  assert.match(first['client-request-id'],/^[a-f0-9]{32}$/);
  assert.notEqual(first['client-request-id'],second['client-request-id']);
  assert.equal(first['x-device-id'],'fixture-device');
  const legacy = {...original.headers}; delete legacy['x-device-id'];
  assert.equal(issuanceHeaders(legacy)['x-device-id'],'legacy');
  const result = await acquireSeed(protocol(),original,AbortSignal.timeout(1000));
  assert.deepEqual(result.headers,{...saved.headers,'client-integrity':'new-proof'});
  assert.deepEqual(original,saved);
  assert.ok(!('client-request-id' in result.headers));
  assert.throws(() => validateSeed({...result,headers:{...result.headers,'client-request-id':first['client-request-id']}}));
  await assert.rejects(acquireSeed(protocol({wrongRequest:true}),original,AbortSignal.timeout(80)));
});

const account = {user_id:'123',client_id:'kimne78kx3ncx6brgo4mv6wki5h1ko',login:'fixture',expires_in:3600};
test('recovery identity validation is bounded, account matched, redacted and authoritative only on OAuth 401', async () => {
  const signal = AbortSignal.timeout(1000);
  await validateRecoveryIdentity(seed(),'123',signal,async (url,options) => {
    assert.equal(url,'https://id.twitch.tv/oauth2/validate'); assert.equal(options.redirect,'error');
    assert.deepEqual(options.headers,{Authorization:'OAuth fixture'});
    return Response.json(account);
  });
  await assert.rejects(validateRecoveryIdentity(seed(),'123',signal,async () => new Response('private',{status:401})),RenewalOAuthInvalid);
  for (const reply of [
    () => Response.json(account,{status:403}), () => new Response('private-token',{status:500}),
    () => new Response('private-token'), () => Response.json({...account,user_id:'456'}),
    () => Response.json({...account,client_id:'foreign'}), () => Response.json({...account,expires_in:0}),
    () => new Response('x'.repeat(16385)), () => { throw Error('private-token'); },
  ]) await assert.rejects(validateRecoveryIdentity(seed(),'123',signal,async () => reply()),error => {
    assert.equal(error.message,'Renewal identity unavailable'); assert.ok(!(error instanceof RenewalOAuthInvalid)); return true;
  });
  for (const user of [undefined,'0','-1',123,'1x']) await assert.rejects(validateRecoveryIdentity(seed(),user,signal,async () => {
    assert.fail('invalid identity must not send credentials');
  }));
  const cancelled = new AbortController(); cancelled.abort();
  await assert.rejects(validateRecoveryIdentity(seed(),'123',cancelled.signal,async () => assert.fail('cancelled fetch')));
});

function ownedFixture(acquire) {
  const state = {profile:null,starts:0,stops:0,closes:0};
  const dependencies = {
    port:async () => 54321,
    start:async (executable,profile) => {
      state.starts++; state.profile = profile;
      if (process.platform !== 'win32') assert.equal((await stat(profile)).mode & 0o777,0o700);
      return {exitCode:null,signalCode:null};
    },
    stop:async () => { state.stops++; }, request:async () => Response.json({webSocketDebuggerUrl:'ws://127.0.0.1:54321/devtools/page/fixture'}),
    open:async () => ({close:() => { state.closes++; }}), acquire,
  };
  return {state,dependencies};
}
const freshSeed = () => ({...seed(),captured_at:now,expires_at:now+3600,headers:{...seed().headers,'client-integrity':'fresh-proof'},
  sdk_cookie:{value:'fresh-cookie',expires_at:now+86400}});

test('expired or near-expiry SDK recovery validates first and bootstraps without installing old state', async () => {
  for (const expires_at of [now-3600,now+30]) {
    const expired = {...seed(),sdk_cookie:{value:'expired-cookie',expires_at}};
    const saved = structuredClone(expired); let verified = false;
    const {state,dependencies} = ownedFixture(async (cdp,captured,signal,initial) => {
      assert.ok(verified); assert.equal(initial,true); assert.equal(captured.sdk_cookie,undefined);
      return acquireSeed(protocol({noPreviousCookie:true}),captured,signal,initial);
    });
    dependencies.validateIdentity = async (context,userId) => { assert.equal(state.starts,0); assert.equal(userId,'123'); verified = true; };
    const result = await issueSeed('fixture-browser',[],expired,AbortSignal.timeout(1000),false,'123',dependencies);
    assert.equal(result.sdk_cookie.value,'rotated-sdk'); assert.deepEqual(expired,saved);
    assert.equal(state.stops,1); assert.ok(state.closes > 0); await assert.rejects(access(state.profile));
  }
});

test('failed or cancelled recovery cannot launch a browser or return a late seed', async () => {
  const expired = {...seed(),sdk_cookie:{value:'expired-cookie',expires_at:now-1}};
  for (const failure of [new RenewalOAuthInvalid(),Error('Renewal identity unavailable')]) {
    const {state,dependencies} = ownedFixture(async () => freshSeed());
    dependencies.validateIdentity = async () => { throw failure; };
    await assert.rejects(issueSeed('fixture-browser',[],expired,AbortSignal.timeout(1000),false,'123',dependencies));
    assert.equal(state.starts,0);
  }
  const stop = new AbortController();
  const {state,dependencies} = ownedFixture(async () => { stop.abort(); return freshSeed(); });
  await assert.rejects(issueSeed('fixture-browser',[],seed(),stop.signal,false,'123',dependencies));
  assert.equal(state.stops,1); await assert.rejects(access(state.profile));
});

test('owned browser cleanup covers launch, protocol and acquisition failures', async () => {
  for (const failure of ['launch','protocol','acquire','exited']) {
    const {state,dependencies} = ownedFixture(async () => { throw Error('fixture acquisition'); });
    if (failure === 'launch') dependencies.start = async (executable,profile) => { state.profile = profile; throw Error('fixture launch'); };
    if (failure === 'protocol') dependencies.open = async () => { throw Error('fixture protocol'); };
    if (failure === 'exited') {
      const start = dependencies.start;
      dependencies.start = async (...args) => ({...await start(...args),exitCode:1});
      dependencies.request = async () => assert.fail('exited process must not be polled');
    }
    await assert.rejects(runSeedBrowser('fixture-browser',[],seed(),AbortSignal.timeout(1000),false,true,dependencies.acquire,dependencies));
    assert.equal(state.stops,1); await assert.rejects(access(state.profile));
  }
});

test('container issuance owns an authenticated private display and a normal Chromium window', () => {
  const launch = seedBrowserLaunch('/usr/bin/chromium','/tmp/owned-profile',['about:blank'],true,true);
  assert.equal(launch.executable,'/usr/bin/xvfb-run');
  assert.deepEqual(launch.args.slice(0,6),['-a','-f','/tmp/owned-profile/Xauthority','-s','-screen 0 1280x800x24 -nolisten tcp','/usr/bin/chromium']);
  assert.ok(launch.args.includes('--window-size=1200,760'));
  assert.ok(!launch.args.some(value => value.includes('--headless') || value === '-ac' || value.includes('expired-cookie')));
  assert.ok(seedBrowserLaunch('browser','profile',[],true,false).args.includes('--headless=new'));
});

test('owned process-group cleanup reaches descendants after the launcher has exited', {skip:process.platform === 'win32'}, async () => {
  const directory = await mkdtemp(join(tmpdir(),'dockdrops-process-test-'));
  const info = join(directory,'fixture.json');
  const descendant = `const fs = require('node:fs'); const net = require('node:net');
    const server = net.createServer(socket => socket.end());
    server.listen(0,'127.0.0.1',() => fs.writeFileSync(process.argv[1],JSON.stringify({port:server.address().port})));`;
  const launcher = `const {spawn} = require('node:child_process');
    const child = spawn(process.execPath,['-e',process.argv[1],process.argv[2]],{stdio:'ignore'}); child.unref();`;
  let child;
  const connects = port => new Promise(resolve => {
    const socket = createConnection({host:'127.0.0.1',port});
    socket.once('connect',() => { socket.destroy(); resolve(true); });
    socket.once('error',() => resolve(false));
    socket.setTimeout(250,() => { socket.destroy(); resolve(false); });
  });
  try {
    child = spawn(process.execPath,['-e',launcher,descendant,info],{detached:true,stdio:'ignore'});
    await once(child,'exit');
    let port;
    for (let i = 0; i < 100 && !port; i++) {
      try { port = JSON.parse(await readFile(info,'utf8')).port; } catch { await sleep(10); }
    }
    assert.ok(port); assert.equal(await connects(port),true);
    await stopSeedBrowser(child);
    for (let i = 0; i < 100 && await connects(port); i++) await sleep(10);
    assert.equal(await connects(port),false);
  } finally { await stopSeedBrowser(child); await rm(directory,{recursive:true,force:true}); }
});

test('startup polling respects cancellation and removes its owned profile', async () => {
  const {state,dependencies} = ownedFixture(async () => assert.fail('unready browser must not acquire'));
  let calls = 0; const stop = new AbortController();
  dependencies.request = async () => { calls++; stop.abort(); throw Error('private connection detail'); };
  await assert.rejects(runSeedBrowser('fixture-browser',[],seed(),stop.signal,false,true,dependencies.acquire,dependencies));
  assert.equal(calls,1); assert.equal(state.stops,1); await assert.rejects(access(state.profile));
});

test('profile removal survives browser shutdown errors', async () => {
  const {state,dependencies} = ownedFixture(async () => freshSeed());
  dependencies.stop = async () => { throw Error('fixture shutdown'); };
  await assert.rejects(runSeedBrowser('fixture-browser',[],seed(),AbortSignal.timeout(1000),false,true,dependencies.acquire,dependencies),/fixture shutdown/);
  await assert.rejects(access(state.profile));
});

test('worker requires saved account identity, accepts expired seed structurally and reports OAuth invalidity safely', async () => {
  const worker = new BrowserWorker({issue:async (exe,args,context,signal,initial,userId) => {
    assert.equal(userId,'123'); assert.equal(initial,false); throw new RenewalOAuthInvalid();
  }});
  const expired = {...seed(),sdk_cookie:{value:'expired-cookie',expires_at:now-1}};
  await assert.rejects(worker.renew('bad',expired));
  await worker.renew('recovery',expired,'123'); await worker.session.task;
  assert.equal(worker.status().error,'oauth_invalid'); assert.equal(worker.status().context,undefined);
  assert.ok(!JSON.stringify(worker.status()).includes('expired-cookie'));
  await worker.cancel();
});
