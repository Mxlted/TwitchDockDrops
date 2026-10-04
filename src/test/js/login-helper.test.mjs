import {test} from 'node:test';
import assert from 'node:assert/strict';
import {CaptureObservation, BrowserCapture, dashboardUrl, BrowserCommandError, Cdp} from '../../main/resources/web/login-helper.mjs';

test('helper only uploads to explicitly trusted dashboard origins', () => {
  for (const value of ['http://127.0.0.1:8080','http://192.168.1.2:8080','http://10.0.0.2:8080','https://drops.example.com']) assert.equal(dashboardUrl(value),value);
  for (const value of ['http://example.com','http://8.8.8.8','https://a:b@example.com','https://example.com/path','https://example.com/?token=secret','file:///tmp/a']) assert.throws(() => dashboardUrl(value));
});

test('missing Chromium response is skipped without accepting incomplete evidence', async () => {
  const observation = new CaptureObservation(() => 1000);
  await observation.observe({method:'Network.responseReceived',params:{requestId:'lost',response:{status:200,url:'https://gql.twitch.tv/integrity'}}});
  await observation.observe({method:'Network.loadingFinished',params:{requestId:'lost'}},async () => { throw new BrowserCommandError('Network.getResponseBody',-32000); });
  assert.equal(observation.bundle('Browser'),null);
  assert.equal(observation.responses.size,0);
  await observation.observe({method:'Network.responseReceived',params:{requestId:'next',response:{status:200,url:'https://gql.twitch.tv/integrity'}}});
  await observation.observe({method:'Network.loadingFinished',params:{requestId:'next'}},async () => ({token:'fresh',expiration:2000000}));
  assert.equal(observation.issued.has('fresh'),true);
});

test('unrelated GraphQL responses do not request a body and real protocol errors still fail', async () => {
  const observation = new CaptureObservation();
  await observation.observe({method:'Network.responseReceived',params:{requestId:'unrelated',response:{status:200,url:'https://gql.twitch.tv/gql'}}});
  await observation.observe({method:'Network.loadingFinished',params:{requestId:'unrelated'}},() => assert.fail('Unrelated body was requested'));
  await observation.observe({method:'Network.responseReceived',params:{requestId:'proof',response:{status:200,url:'https://gql.twitch.tv/integrity'}}});
  await assert.rejects(observation.observe({method:'Network.loadingFinished',params:{requestId:'proof'}},async () => { throw new BrowserCommandError('Network.getResponseBody',-32601); }),/Network.getResponseBody/);
});

test('CDP diagnostics include method and numeric code but never upstream text or parameters', async () => {
  class Socket extends EventTarget {
    readyState = 1;
    send(value) { const {id} = JSON.parse(value); this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({id,error:{code:-32000,message:'secret-token',data:'secret-cookie'}})})); }
  }
  const cdp = new Cdp(new Socket());
  await assert.rejects(cdp.command('Network.getResponseBody',{requestId:'private'}),error => {
    assert.match(error.message,/Network.getResponseBody.*-32000/);
    assert.doesNotMatch(error.message,/secret|private/); return true;
  });
});

test('capture needs matching issued proof and successful authenticated campaign data', async () => {
  const observation = new CaptureObservation(() => 1000);
  const headers = {'Client-Id':'kimne78kx3ncx6brgo4mv6wki5h1ko','Authorization':'OAuth fake-token',
    'Client-Integrity':'proof','X-Device-Id':'device','Cookie':'must-not-export'};
  const event = (method, params) => observation.observe({method,params}, async id => id === 'proof-request'
    ? {token:'proof',expiration:2000000} : {data:{currentUser:{dropCampaigns:[]}}});
  await event('Network.requestWillBeSent',{requestId:'campaign',request:{url:'https://gql.twitch.tv/gql',method:'POST',headers}});
  await event('Network.responseReceived',{requestId:'campaign',response:{status:200,url:'https://gql.twitch.tv/gql'}});
  await event('Network.loadingFinished',{requestId:'campaign'});
  assert.equal(observation.bundle('Browser'),null);
  await event('Network.responseReceived',{requestId:'proof-request',response:{status:200,url:'https://gql.twitch.tv/integrity'}});
  await event('Network.loadingFinished',{requestId:'proof-request'});
  const bundle = observation.bundle('Browser');
  assert.equal(bundle.headers['client-integrity'],'proof');
  assert.equal(bundle.headers.cookie,undefined);
  assert.equal(bundle.expires_at,2000);
  observation.clock = () => 1990;
  assert.equal(observation.bundle('Browser'),null);
});

test('cached proof issuance cannot create an exportable session', async () => {
  const observation = new CaptureObservation(() => 1000);
  await observation.observe({method:'Network.responseReceived',params:{requestId:'proof',response:{status:200,url:'https://gql.twitch.tv/integrity',fromDiskCache:true}}},() => assert.fail('must not read cached proof'));
  await observation.observe({method:'Network.loadingFinished',params:{requestId:'proof'}},() => assert.fail('must not read cached proof'));
  assert.equal(observation.issued.size,0);
});

test('long-lived capture discards unrelated completed requests and keeps recent matching evidence', async () => {
  const observation = new CaptureObservation(() => 1000);
  for (let n = 0; n < 400; n++) {
    const requestId = String(n);
    await observation.observe({method:'Network.requestWillBeSent',params:{requestId,request:{url:'https://gql.twitch.tv/gql',method:'POST',headers:{
      'Client-Id':'kimne78kx3ncx6brgo4mv6wki5h1ko',Authorization:'OAuth fake', 'Client-Integrity':'proof',
    }}}});
    await observation.observe({method:'Network.responseReceived',params:{requestId,response:{url:'https://gql.twitch.tv/gql',status:n % 2 ? 200 : 403}}});
    await observation.observe({method:'Network.loadingFinished',params:{requestId}},async () => n % 3 ? {data:{unrelated:true}} : {data:{currentUser:{dropCampaigns:[]}}});
  }
  assert.ok(observation.requests.size <= 16);
  assert.ok(observation.campaigns.size <= 16);
  assert.equal(observation.responses.size,0);
});

test('CDP ignores asset floods and preflight bodies but delivers correlated auth events', async () => {
  class Socket extends EventTarget { readyState = 1; close() { assert.fail('Unrelated traffic closed capture'); } }
  const socket = new Socket(), cdp = new Cdp(socket);
  const emit = (method,params) => socket.dispatchEvent(new MessageEvent('message',{data:JSON.stringify({method,params})}));
  for (let n = 0; n < 3000; n++) {
    emit('Network.requestWillBeSent',{requestId:String(n),request:{url:'https://assets.twitch.tv/file.js',method:'GET'}});
    emit('Network.dataReceived',{requestId:String(n)});
    emit('Network.loadingFinished',{requestId:String(n)});
  }
  emit('Network.requestWillBeSent',{requestId:'preflight',request:{url:'https://gql.twitch.tv/gql',method:'OPTIONS'}});
  emit('Network.responseReceived',{requestId:'preflight',type:'Preflight',response:{url:'https://gql.twitch.tv/gql',status:200}});
  emit('Network.loadingFinished',{requestId:'preflight'});
  assert.equal(cdp.events.length,0);
  emit('Network.requestWillBeSent',{requestId:'auth',request:{url:'https://gql.twitch.tv/gql',method:'POST'}});
  emit('Network.responseReceived',{requestId:'auth',response:{url:'https://gql.twitch.tv/gql',status:200}});
  emit('Network.loadingFinished',{requestId:'auth'});
  assert.equal(cdp.events.length,3);
  assert.equal(cdp.networkRequests.size,0);
});

test('Finish can use issuance observed during login, and renewal requires a different verified token', async () => {
  const commands = [];
  const cdp = {command:async (name) => { commands.push(name); },event:() => new Promise(() => {})};
  const capture = new BrowserCapture(cdp,'Chrome/test');
  const now = Math.floor(Date.now()/1000);
  capture.observation.issued.set('before-finish',[now,now+600]);
  capture.observation.requests.set('campaign',{authorization:'OAuth fake','client-integrity':'before-finish'});
  capture.observation.campaigns.add('campaign');
  assert.equal((await capture.wait(new AbortController().signal)).headers['client-integrity'],'before-finish');
  assert.deepEqual(commands,['Page.navigate']);
  await assert.rejects(capture.wait(new AbortController().signal,'before-finish',5),/verified Drops access/);
  capture.observation.issued.set('renewed',[now,now+1200]);
  capture.observation.requests.set('new-campaign',{authorization:'OAuth fake','client-integrity':'renewed'});
  capture.observation.campaigns.add('new-campaign');
  assert.equal((await capture.wait(new AbortController().signal,'before-finish')).headers['client-integrity'],'renewed');
  const cancelled = AbortSignal.abort();
  await assert.rejects(capture.wait(cancelled,'renewed'),{name:'AbortError'});
});
