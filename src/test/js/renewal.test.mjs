import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateSeed,readSdkCookie,acquireSeed} from '../../../browser/renewal.mjs';
import {BrowserWorker} from '../../../browser/worker.mjs';

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
  await worker.renew('old',seed());
  const cancel = worker.cancel('old');
  release(seed()); await cancel;
  assert.equal(worker.status().state,'idle');
  await worker.renew('new',seed());
  await worker.cancel('old');
  assert.equal(worker.status().id,'new');
  const cancelled = worker.cancel('new'); release(seed()); await cancelled;
});

test('failed renewal exposes fixed errors only', async () => {
  const worker = new BrowserWorker({issue:async () => { throw Error('private-token-and-cookie'); }});
  await worker.renew('test',seed()); await worker.session.task;
  assert.equal(worker.status().error,'renewal_failed');
  assert.ok(!JSON.stringify(worker.status()).includes('private-token'));
  await worker.cancel();
});

test('a restarted JVM can replace an orphaned renewal but never an interactive login', async () => {
  const worker = new BrowserWorker({issue:async () => seed()});
  await worker.renew('previous-process',seed()); await worker.session.task;
  await worker.renew('restarted-process',seed()); await worker.session.task;
  assert.equal(worker.status().id,'restarted-process');
  await worker.cancel();
  worker.session = {id:'interactive',state:'interactive'};
  await assert.rejects(worker.renew('late-renewal',seed()),/Browser busy/);
  assert.equal(worker.status().id,'interactive');
});

function protocol({cached=false,mismatch=false,replay=false,cookieStale=false,noPreviousCookie=false,noReturnedCookie=false}={}) {
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
        emit({method:'Network.requestWillBeSent',params:{requestId:'i',request:{url:'https://gql.twitch.tv/integrity',method:'POST'}}});
        emit({method:'Network.responseReceived',params:{requestId:'i',response:{url:'https://gql.twitch.tv/integrity',status:200,fromDiskCache:cached}}});
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
  for (const option of [{cached:true},{mismatch:true},{replay:true},{cookieStale:true}]) {
    await assert.rejects(acquireSeed(protocol(option),seed(),AbortSignal.timeout(1000)));
  }
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
