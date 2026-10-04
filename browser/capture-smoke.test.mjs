// Opt-in Linux/Xvfb integration test: synthetic Twitch responses, no account or external traffic.
// See OPERATIONS.md for the hardened Docker command.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {setTimeout as sleep} from 'node:timers/promises';
import {BrowserCapture, Cdp, browserPort, startBrowser, stopBrowser} from '../src/main/resources/web/login-helper.mjs';

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
