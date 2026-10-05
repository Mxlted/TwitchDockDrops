import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {BrowserWorker, createWorkerServer, validateInput} from '../../../browser/worker.mjs';
import {setTimeout as sleep} from 'node:timers/promises';
import {SeedFailure} from '../../../browser/renewal.mjs';

test('input allows bounded typing and navigation, never arbitrary browser commands or shortcuts', () => {
  for (const value of [{kind:'text',text:' '},{kind:'text',text:'Twitch'},{kind:'key',key:'Tab',shift:true},{kind:'click',x:1099,y:759},{kind:'wheel',delta:-100}]) assert.equal(validateInput(value),value);
  for (const value of [{kind:'text',text:'a\n'},{kind:'text',text:'x'.repeat(257)},{kind:'key',key:'F12',shift:false},{kind:'key',key:'Tab',shift:false,ctrl:true},{kind:'click',x:-1,y:0},{kind:'click',x:0,y:760},{kind:'text',text:'x',method:'Runtime.evaluate'}]) assert.throws(() => validateInput(value));
});

test('Finish keeps the signed-in browser until independent seeded issuance succeeds', async () => {
  let closed = false;
  const worker = new BrowserWorker({issue:async (exe,args,seed,signal,initial) => {
    assert.equal(closed,false); assert.equal(initial,true);
    assert.equal(seed.sdk_cookie.value,'sdk-fixture');
    return {...seed,headers:{'client-integrity':'renewed-proof'}};
  }});
  const stop = new AbortController();
  const s = {stop,state:'capturing',sequence:0,context:null,
    cdp:{command:async method => {
      assert.equal(method,'Network.getCookies');
      return {cookies:[{name:'KP_UIDz-ssn',domain:'k.twitchcdn.net',path:'/',secure:true,httpOnly:true,value:'sdk-fixture',expires:Date.now()/1000+86400}]};
    }},capture:{wait:async () => ({expires_at:Date.now()/1000+600,headers:{'client-integrity':'initial-proof'}})}};
  worker.session = s;
  worker.closeBrowser = async () => { closed = true; };
  const task = worker.maintainSession(s);
  while (s.sequence !== 1) await sleep(5);
  assert.equal(worker.status().state,'capturing');
  assert.equal(worker.status().context.headers['client-integrity'],'renewed-proof');
  s.accepted = 1; await task;
  assert.equal(closed,true);
  assert.equal(worker.status().state,'ready');
});

const capturedContext = () => ({version:1,captured_at:Math.floor(Date.now()/1000),expires_at:Math.floor(Date.now()/1000)+600,
  user_agent:'Chrome/test',headers:{authorization:'OAuth fixture','client-id':'kimne78kx3ncx6brgo4mv6wki5h1ko',
    'x-device-id':'device','client-integrity':'captured'}});

async function retainedLogin({hasCookie=false,issue=async () => { throw new SeedFailure('sdk_rejected'); }}={}) {
  const captured = capturedContext(); let closes = 0;
  const worker = new BrowserWorker({issue});
  worker.closeBrowser = async () => { closes++; };
  worker.run = async s => {
    s.state = 'capturing';
    s.cdp = {command:async () => ({cookies:hasCookie ? [{name:'KP_UIDz-ssn',domain:'k.twitchcdn.net',path:'/',
      secure:true,httpOnly:true,value:'fixture-sdk',expires:Date.now()/1000+86400}] : []})};
    s.capture = {wait:async () => captured};
    await worker.maintainSession(s);
  };
  await worker.start('login');
  while (worker.session.sequence !== 1) await sleep(5);
  const context = worker.status().context;
  assert.equal(worker.retained,null); // No accepted browser before the JVM verifies.
  worker.session.accepted = 1;
  await worker.release('login');
  assert.equal(closes,0); assert.equal(worker.status().context,undefined);
  return {worker,context,closes:() => closes};
}

test('missing cookie or failed SDK issuance accepts a retained Docker browser after JVM verification', async () => {
  for (const hasCookie of [false,true]) {
    const {worker,context,closes} = await retainedLogin({hasCookie});
    assert.match(context.browser_lease,/^[0-9a-f-]{36}$/);
    assert.equal(context.sdk_cookie,undefined);
    await assert.rejects(worker.frame(),/Browser not ready/);
    await worker.revoke('00000000-0000-0000-0000-000000000000');
    assert.equal(closes(),0);
    await worker.revoke(context.browser_lease);
    assert.equal(closes(),1); assert.equal(worker.retained,null);
  }
});

test('runtime-triggered retained renewal survives transfer release and binds the original identity', async () => {
  const {worker,context,closes} = await retainedLogin();
  worker.retained.capture.wait = async (signal,previous) => {
    assert.equal(previous,context.headers['client-integrity']);
    return {...context,expires_at:context.expires_at+600,headers:{...context.headers,'client-integrity':'renewed'}};
  };
  await worker.renew('renew',context); await worker.session.task;
  assert.equal(worker.status().context.headers['client-integrity'],'renewed');
  await worker.release('renew'); assert.equal(closes(),0);
  await worker.renew('foreign',{...context,headers:{...context.headers,authorization:'OAuth other'}});
  await worker.session.task;
  assert.equal(worker.status().error,'browser_missing');
  await worker.release('foreign'); assert.equal(closes(),0);
  await worker.cancel(); assert.equal(closes(),1);
  await worker.renew('restart',context); await worker.session.task;
  assert.equal(worker.status().error,'browser_missing'); await worker.release('restart');
});

test('cancelled retained renewal cannot publish late proof and stale releases cannot stop a new attempt', async () => {
  const {worker,context,closes} = await retainedLogin();
  let resolveCapture;
  worker.retained.capture.wait = () => new Promise(resolve => { resolveCapture = resolve; });
  await worker.renew('pending',context);
  const release = worker.release('pending');
  resolveCapture({...context,expires_at:context.expires_at+600}); await release;
  assert.equal(worker.status().context,undefined); assert.equal(closes(),0);
  await worker.release('login'); assert.ok(worker.retained);
  await worker.cancel(); assert.equal(closes(),1);
});

test('unaccepted capture never retains a browser', async () => {
  const worker = new BrowserWorker({issue:async () => { worker.session.stop.abort(); return {}; }});
  let closes = 0; worker.closeBrowser = async () => { closes++; };
  worker.run = async s => {
    s.state = 'capturing'; s.capture = {wait:async () => capturedContext()};
    s.cdp = {command:async () => ({cookies:[]})};
    await worker.maintainSession(s);
  };
  await worker.start('unaccepted');
  while (worker.session.sequence !== 1) await sleep(5);
  await worker.release('unaccepted');
  assert.equal(worker.retained,null); assert.equal(closes,1);
  assert.equal(worker.status().context,undefined);
});

test('retained renewal rejects replayed proof and changed captured credentials', async () => {
  const {worker,context} = await retainedLogin();
  for (const headers of [context.headers,{...context.headers,authorization:'OAuth other','client-integrity':'fresh'}]) {
    worker.retained.capture.wait = async () => ({...context,expires_at:context.expires_at+600,headers});
    await worker.renew('bad-proof',context); await worker.session.task;
    assert.equal(worker.status().error,'renewal_failed'); assert.equal(worker.status().context,undefined);
    await worker.release('bad-proof'); assert.ok(worker.retained);
  }
  worker.retained.capture.failure = Error('private-browser-error');
  await worker.renew('dead-browser',context); await worker.session.task;
  assert.equal(worker.status().error,'browser_missing');
  assert.ok(!JSON.stringify(worker.status()).includes('private-browser-error'));
  await worker.cancel();
});

test('SDK failures disclose only allowlisted reasons through private worker status', async () => {
  for (const code of ['sdk_timeout','sdk_script','sdk_fetch','sdk_rejected','sdk_cookie','sdk_proof','private-token']) {
    const worker = new BrowserWorker();
    worker.closeBrowser = async () => {};
    worker.run = async s => { s.state = 'capturing'; s.stage = 'bootstrap'; throw new SeedFailure(code); };
    await worker.start('test'); await worker.session.task;
    assert.equal(worker.status().error,code === 'private-token' ? 'seed_failed' : code);
    assert.equal(worker.status().context,undefined);
    assert.ok(!JSON.stringify(worker.status()).includes('private-token'));
    await worker.cancel();
  }
});

test('Finish reports fixed stage errors and never publishes failed or cancelled proof', async () => {
  for (const stage of ['capture','cancel-issuance']) {
    const fail = async () => { throw Error('private-token-and-cookie'); };
    const worker = new BrowserWorker({
      issue:async () => { worker.session.stop.abort(); return {}; },
    });
    worker.closeBrowser = async () => {};
    worker.run = async s => {
      s.state = 'capturing'; s.capture = {wait:stage === 'capture' ? fail : async () => ({})};
      s.cdp = {command:async () => ({cookies:[{name:'KP_UIDz-ssn',domain:'k.twitchcdn.net',path:'/',secure:true,httpOnly:true,value:'fixture',expires:Date.now()/1000+86400}]})};
      await worker.maintainSession(s);
    };
    await worker.start('test'); await worker.session.task;
    const status = worker.status();
    assert.equal(status.sequence,0); assert.equal(status.context,undefined);
    assert.equal(status.error,({capture:'capture_failed',bootstrap:'seed_failed',issuance:'issuance_failed'})[stage] || '');
    assert.ok(!JSON.stringify(status).includes('private-token'));
    await worker.cancel();
  }
});

test('worker requires internal header, refuses all browser Origins, bounds bodies and validates routes', async () => {
  let starts = 0;
  const worker = {status:() => ({state:'idle',sequence:0}),start:async () => { starts++; }};
  const server = createWorkerServer(worker,0);
  await once(server,'listening');
  const origin = `http://127.0.0.1:${server.address().port}`;
  const headers = {'X-DockDrops-Internal':'1','Content-Type':'application/json'};
  const id = '12345678-1234-1234-1234-123456789abc';
  try {
    assert.equal((await fetch(origin+'/status')).status,403);
    assert.equal((await fetch(origin+'/status',{headers:{...headers,Origin:'https://www.twitch.tv'}})).status,403);
    assert.equal((await fetch(origin+'/status',{headers})).status,200);
    assert.equal((await fetch(origin+'/start',{headers})).status,405);
    assert.equal((await fetch(origin+'/start',{headers,method:'POST',body:JSON.stringify({id,token:'private'})})).status,400);
    assert.equal((await fetch(origin+'/start',{headers,method:'POST',body:JSON.stringify({id})})).status,200);
    assert.equal(starts,1);
    assert.equal((await fetch(origin+'/start',{headers,method:'POST',body:JSON.stringify({id,pad:'x'.repeat(5000)})})).status,400);
    assert.equal(starts,1);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
