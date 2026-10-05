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

test('Finish proves independent seeded renewal and waits for JVM acceptance', async () => {
  let closed = false;
  const worker = new BrowserWorker({issue:async (exe,args,seed,signal,initial) => {
    assert.equal(closed,true); assert.equal(initial,true);
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
  assert.equal(worker.status().state,'ready');
});

test('Finish bootstraps a missing SDK cookie before closing the signed-in browser', async () => {
  let closed = false, bootstrapped = false;
  const captured = {headers:{'client-integrity':'captured'}};
  const worker = new BrowserWorker({
    bootstrap:async (executable,args,context,signal) => {
      assert.equal(closed,false); assert.equal(executable,worker.executable); assert.equal(args,worker.browserArgs); assert.equal(context,captured);
      signal.throwIfAborted(); bootstrapped = true;
      return {...context,sdk_cookie:{value:'new-cookie'}};
    },
    issue:async (exe,args,seed,signal,initial) => {
      assert.equal(bootstrapped,true); assert.equal(closed,true); assert.equal(initial,true);
      assert.equal(seed.sdk_cookie.value,'new-cookie'); return seed;
    },
  });
  worker.closeBrowser = async () => { closed = true; };
  const s = {port:1234,stop:new AbortController(),state:'capturing',sequence:0,
    cdp:{command:async () => ({cookies:[]})},capture:{wait:async () => captured}};
  worker.session = s;
  const task = worker.maintainSession(s);
  while (s.sequence !== 1) await sleep(5);
  assert.equal(s.state,'capturing'); s.accepted = 1; await task;
  assert.equal(s.state,'ready');
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
  for (const stage of ['capture','bootstrap','issuance','cancel-bootstrap','cancel-issuance']) {
    const fail = async () => { throw Error('private-token-and-cookie'); };
    const worker = new BrowserWorker({
      bootstrap:stage === 'bootstrap' ? fail : async () => {
        if (stage === 'cancel-bootstrap') worker.session.stop.abort();
        return {};
      },
      issue:stage === 'issuance' ? fail : async () => { worker.session.stop.abort(); return {}; },
    });
    worker.closeBrowser = async () => {};
    worker.run = async s => {
      s.state = 'capturing'; s.capture = {wait:stage === 'capture' ? fail : async () => ({})};
      s.cdp = {command:async () => ({cookies:[]})};
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
