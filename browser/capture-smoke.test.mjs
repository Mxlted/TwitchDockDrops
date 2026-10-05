// Opt-in Linux/Xvfb integration test: synthetic Twitch responses, no account or external traffic.
// See OPERATIONS.md for the hardened Docker command.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {setTimeout as sleep} from 'node:timers/promises';
import {BrowserCapture, Cdp, browserPort, startBrowser, stopBrowser} from '../src/main/resources/web/login-helper.mjs';
import {acquireSeed, bootstrapSeed, SDK_URL} from './renewal.mjs';

test('headed Chromium retains login issuance and captures initial and renewed Drops proof',
  {skip:process.env.DOCKDROPS_BROWSER_TEST !== '1',timeout:45000}, async () => {
    const profile = await mkdtemp(join(tmpdir(),'dockdrops-capture-test-'));
    const port = await browserPort(), stop = new AbortController();
    let child, cdp, generation = 1;
    const interceptionErrors = [];
    try {
      child = await startBrowser('/usr/bin/chromium',profile,['--no-sandbox',
        '--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${port}`,'about:blank']);
      let target;
      for (let n = 0; n < 100; n++) {
        try {
          const response = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT',signal:AbortSignal.timeout(500)});
          target = await response.json(); break;
        } catch { await sleep(100); }
      }
      assert.ok(target);
      cdp = await Cdp.open(target.webSocketDebuggerUrl,port,stop.signal);
      // Fulfil all requests locally. The production collector still observes actual Chromium
      // Network events and reads its actual response bodies across document navigation.
      cdp.socket.addEventListener('message',event => {
        const message = JSON.parse(event.data);
        if (message.method !== 'Fetch.requestPaused') return;
        const {requestId, request} = message.params;
        let body = '{}', type = 'application/json';
        if (request.url === 'https://www.twitch.tv/login') {
          type = 'text/html';
          body = `<script>fetch('https://gql.twitch.tv/integrity',{method:'POST'}).then(r=>r.json()).then(p=>sessionStorage.setItem('proof',p.token));</script>`;
        } else if (request.url === 'https://www.twitch.tv/drops/campaigns') {
          type = 'text/html';
          body = `<script>fetch('https://gql.twitch.tv/gql',{method:'POST',headers:{'Authorization':'OAuth fixture','Client-Id':'kimne78kx3ncx6brgo4mv6wki5h1ko','Client-Integrity':sessionStorage.getItem('proof'),'X-Device-Id':'fixture-device'},body:'{}'});</script>`;
        } else if (request.url === 'https://gql.twitch.tv/integrity') {
          body = JSON.stringify({token:`fixture-proof-${generation}`,expiration:Date.now()+600000});
        } else if (request.url === 'https://gql.twitch.tv/gql') {
          body = JSON.stringify({data:{currentUser:{dropCampaigns:[]}}});
        }
        cdp.command('Fetch.fulfillRequest',{requestId,responseCode:200,responseHeaders:[
          {name:'Content-Type',value:type},{name:'Access-Control-Allow-Origin',value:'*'},
          {name:'Access-Control-Allow-Methods',value:'POST,GET,OPTIONS'},
          {name:'Access-Control-Allow-Headers',value:'authorization,client-id,client-integrity,x-device-id'},
        ],body:Buffer.from(request.method === 'OPTIONS' ? '' : body).toString('base64')}).catch(error => interceptionErrors.push(error));
      });
      await cdp.command('Fetch.enable',{patterns:[{urlPattern:'*'}]});
      const capture = await BrowserCapture.start(cdp);
      assert.ok(!capture.userAgent.includes('HeadlessChrome'));
      for (generation = 1; generation <= 2; generation++) {
        await cdp.command('Page.navigate',{url:'https://www.twitch.tv/login'});
        for (let n = 0; n < 100 && !capture.observation.issued.has(`fixture-proof-${generation}`); n++) await sleep(50);
        assert.ok(capture.observation.issued.has(`fixture-proof-${generation}`),'issuance must precede Finish');
        // Wait until the page has received the same response before navigating away.
        for (let n = 0; n < 100; n++) {
          const value = await cdp.command('Runtime.evaluate',{expression:"sessionStorage.getItem('proof')",returnByValue:true});
          if (value.result.value === `fixture-proof-${generation}`) break;
          await sleep(50);
        }
        const bundle = await capture.wait(stop.signal,generation === 2 ? 'fixture-proof-1' : null,10000);
        assert.equal(bundle.headers['client-integrity'],`fixture-proof-${generation}`);
        assert.equal(bundle.headers.authorization,'OAuth fixture');
        assert.ok(bundle.expires_at > Date.now()/1000+60);
        assert.equal(child.exitCode,null,'Finish must retain the logged-in process');
      }
      assert.deepEqual(interceptionErrors,[]);
    } finally {
      if (cdp) { await cdp.command('Browser.close').catch(() => {}); cdp.close(); }
      stop.abort(); await stopBrowser(child);
      // Owned mkdtemp path only.
      await rm(profile,{recursive:true,force:true});
    }
  });

test('headed bootstrap starts without cookies and disposes only its isolated context on success failure and cancellation',
  {skip:process.env.DOCKDROPS_BROWSER_TEST !== '1',timeout:45000}, async () => {
    const profile = await mkdtemp(join(tmpdir(),'dockdrops-bootstrap-test-'));
    const port = await browserPort(), stop = new AbortController();
    const now = Math.floor(Date.now()/1000);
    const captured = {version:1,captured_at:now,expires_at:now+600,user_agent:'Chrome/fixture',
      headers:{'client-id':'kimne78kx3ncx6brgo4mv6wki5h1ko',authorization:'OAuth fixture',
        'x-device-id':'fixture-device','client-integrity':'captured-proof'}};
    let child, original, controller;
    try {
      child = await startBrowser('/usr/bin/chromium',profile,['--no-sandbox',
        '--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${port}`,'about:blank']);
      let target;
      for (let n = 0; n < 100; n++) {
        try {
          target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT',signal:AbortSignal.timeout(500)})).json();
          break;
        } catch { await sleep(100); }
      }
      assert.ok(target);
      original = await Cdp.open(target.webSocketDebuggerUrl,port,stop.signal);
      const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
      controller = await Cdp.open(version.webSocketDebuggerUrl,port,stop.signal);
      await original.command('Network.setCookies',{cookies:[{name:'auth-marker',value:'keep-original',
        domain:'www.twitch.tv',path:'/',secure:true,httpOnly:true}]});
      const contexts = (await controller.command('Target.getBrowserContexts')).browserContextIds;
      for (const outcome of ['success','failure','cancel']) {
        const attempt = new AbortController();
        const task = bootstrapSeed(port,captured,attempt.signal,async (cdp,bundle,signal,initial) => {
          assert.equal(initial,true);
          const command = cdp.command.bind(cdp), event = cdp.event.bind(cdp);
          const cookies = await command('Network.getCookies',{urls:['https://www.twitch.tv/','https://k.twitchcdn.net/']});
          assert.deepEqual(cookies.cookies,[],'bootstrap must not inherit login cookies');
          if (outcome === 'failure') throw Error('synthetic bootstrap failure');
          if (outcome === 'cancel') { attempt.abort(); signal.throwIfAborted(); }
          cdp.command = (method,params,timeout) => command(method,method === 'Fetch.enable' ? {patterns:[{urlPattern:'*'}]} : params,timeout);
          cdp.event = async () => {
            for (;;) {
              const message = await event();
              if (message?.method !== 'Fetch.requestPaused' || message.params.request.url === 'https://www.twitch.tv/') return message;
              const {requestId,request} = message.params;
              let body = '', type = 'text/plain';
              if (request.url === SDK_URL) {
                await command('Network.setCookies',{cookies:[{name:'KP_UIDz-ssn',value:'bootstrapped-cookie',
                  domain:'k.twitchcdn.net',path:'/',secure:true,httpOnly:true,sameSite:'None',expires:now+86400}]});
                type = 'text/javascript';
                body = "window.KPSDK={configure:()=>{}};document.dispatchEvent(new Event('kpsdk-load'));document.dispatchEvent(new Event('kpsdk-ready'));";
              } else if (request.url === 'https://gql.twitch.tv/integrity' && request.method === 'POST') {
                assert.equal(request.headers.Authorization || request.headers.authorization,'OAuth fixture');
                type = 'application/json'; body = JSON.stringify({token:'bootstrap-proof',expiration:(now+3600)*1000});
              }
              await command('Fetch.fulfillRequest',{requestId,responseCode:200,responseHeaders:[
                {name:'Content-Type',value:type},{name:'Access-Control-Allow-Origin',value:'*'},
                {name:'Access-Control-Allow-Methods',value:'POST,GET,OPTIONS'},
                {name:'Access-Control-Allow-Headers',value:'authorization,client-id,x-device-id'},
              ],body:Buffer.from(body).toString('base64')});
            }
          };
          return acquireSeed(cdp,bundle,signal,initial);
        });
        if (outcome === 'success') {
          const seed = await task;
          assert.equal(seed.sdk_cookie.value,'bootstrapped-cookie');
          assert.equal(seed.headers['client-integrity'],'bootstrap-proof');
          assert.ok(!seed.user_agent.includes('HeadlessChrome'));
        } else await assert.rejects(task);
        // disposeOnDetach cleanup can complete after the aborted socket closes.
        for (let n = 0; n < 100; n++) {
          if (JSON.stringify((await controller.command('Target.getBrowserContexts')).browserContextIds) === JSON.stringify(contexts)) break;
          await sleep(20);
        }
        assert.deepEqual((await controller.command('Target.getBrowserContexts')).browserContextIds,contexts);
        const cookies = (await original.command('Network.getCookies',{urls:['https://www.twitch.tv/','https://k.twitchcdn.net/']})).cookies;
        assert.equal(cookies.find(c => c.name === 'auth-marker')?.value,'keep-original');
        assert.equal(cookies.some(c => c.name === 'KP_UIDz-ssn'),false);
        assert.equal(child.exitCode,null);
      }
    } finally {
      controller?.close(); original?.close(); stop.abort(); await stopBrowser(child);
      await rm(profile,{recursive:true,force:true});
    }
  });

test('headless Chromium rotates a scoped SDK seed across two fresh profiles',
  {skip:process.env.DOCKDROPS_BROWSER_TEST !== '1',timeout:45000}, async () => {
    const now = Math.floor(Date.now()/1000);
    let seed = {version:1,captured_at:now-3600,expires_at:now-1,user_agent:'Chrome/fixture',
      headers:{'client-id':'kimne78kx3ncx6brgo4mv6wki5h1ko',authorization:'OAuth fixture','x-device-id':'fixture-device','client-integrity':'expired-proof'},
      sdk_cookie:{value:'fixture-cookie-0',expires_at:now+3600}};
    for (let generation = 1; generation <= 2; generation++) {
      const profile = await mkdtemp(join(tmpdir(),'dockdrops-renew-test-'));
      const port = await browserPort(), stop = new AbortController();
      let child, cdp;
      try {
        child = await startBrowser('/usr/bin/chromium',profile,['--no-sandbox','--headless=new',
          '--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${port}`,'about:blank']);
        let target;
        for (let n = 0; n < 100; n++) {
          try {
            const response = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT',signal:AbortSignal.timeout(500)});
            target = await response.json(); break;
          } catch { await sleep(100); }
        }
        assert.ok(target);
        cdp = await Cdp.open(target.webSocketDebuggerUrl,port,stop.signal,
          ['Fetch.requestPaused','Page.loadEventFired','Network.requestServedFromCache']);
        const command = cdp.command.bind(cdp), event = cdp.event.bind(cdp);
        // Intercept every outbound request in this offline-only test. Production still owns
        // document setup, SDK event handling, issuance correlation and cookie extraction.
        cdp.command = (method,params,timeout) => command(method,method === 'Fetch.enable' ? {patterns:[{urlPattern:'*'}]} : params,timeout);
        cdp.event = async () => {
          for (;;) {
            const message = await event();
            if (message?.method !== 'Fetch.requestPaused' || message.params.request.url === 'https://www.twitch.tv/') return message;
            const {requestId,request} = message.params;
            let body = '', type = 'text/plain';
            if (request.url === SDK_URL) {
              const cookies = await command('Network.getCookies',{urls:['https://k.twitchcdn.net/']});
              assert.equal(cookies.cookies.find(c => c.name === 'KP_UIDz-ssn')?.value,`fixture-cookie-${generation-1}`);
              await command('Network.setCookies',{cookies:[{name:'KP_UIDz-ssn',value:`fixture-cookie-${generation}`,
                domain:'k.twitchcdn.net',path:'/',secure:true,httpOnly:true,sameSite:'None',expires:now+86400+generation*600}]});
              type = 'text/javascript';
              body = "window.KPSDK={configure:()=>{}}; document.dispatchEvent(new Event('kpsdk-load')); document.dispatchEvent(new Event('kpsdk-ready'));";
            } else if (request.url === 'https://gql.twitch.tv/integrity') {
              type = 'application/json';
              if (request.method === 'POST') {
                assert.equal(request.headers.Authorization || request.headers.authorization,'OAuth fixture');
                body = JSON.stringify({token:`fixture-issued-${generation}`,expiration:(now+3600+generation*600)*1000});
              }
            }
            await command('Fetch.fulfillRequest',{requestId,responseCode:200,responseHeaders:[
              {name:'Content-Type',value:type},{name:'Access-Control-Allow-Origin',value:'*'},
              {name:'Access-Control-Allow-Methods',value:'POST,GET,OPTIONS'},
              {name:'Access-Control-Allow-Headers',value:'authorization,client-id,x-device-id'},
            ],body:Buffer.from(body).toString('base64')});
          }
        };
        seed = await acquireSeed(cdp,seed,AbortSignal.any([stop.signal,AbortSignal.timeout(15000)]));
        assert.equal(seed.headers['client-integrity'],`fixture-issued-${generation}`);
        assert.equal(seed.sdk_cookie.value,`fixture-cookie-${generation}`);
        assert.ok(seed.user_agent.includes('HeadlessChrome'));
      } finally {
        cdp?.close(); stop.abort(); await stopBrowser(child);
        await rm(profile,{recursive:true,force:true});
      }
    }
  });
