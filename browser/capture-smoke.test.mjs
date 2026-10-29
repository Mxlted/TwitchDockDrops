// Opt-in Linux/Xvfb integration test: synthetic Twitch responses, no account or external traffic.
// See OPERATIONS.md for the hardened Docker command.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm, readdir, readFile, writeFile, stat, access} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {setTimeout as sleep} from 'node:timers/promises';
import {BrowserCapture, Cdp, browserPort, startBrowser, stopBrowser, initialPageTarget} from '../src/main/resources/web/login-helper.mjs';
import {acquireSeed, bootstrapSeed, issueSeed, SDK_URL} from './renewal.mjs';
import {startSeedBrowser} from './seed-browser.mjs';

const execute = promisify(execFile);

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
          const response = await fetch(`http://127.0.0.1:${port}/json/list`,{signal:AbortSignal.timeout(500)});
          target = initialPageTarget(await response.json()); if (target) break;
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

test('regular-profile bootstrap accepts a response cookie and cleans up on success failure and cancellation',
  {skip:process.env.DOCKDROPS_BROWSER_TEST !== '1',timeout:45000}, async t => {
    const profile = await mkdtemp(join(tmpdir(),'dockdrops-bootstrap-test-'));
    const port = await browserPort(), stop = new AbortController();
    const now = Math.floor(Date.now()/1000);
    const captured = {version:1,captured_at:now,expires_at:now+600,user_agent:'Chrome/fixture',
      headers:{'client-id':'kimne78kx3ncx6brgo4mv6wki5h1ko',authorization:'OAuth fixture',
        'x-device-id':'fixture-device','client-integrity':'captured-proof'}};
    let child, original, controller, sampledPids = 0, sampledMemory = 0, largestMemorySample;
    try {
      child = await startBrowser('/usr/bin/chromium',profile,['--no-sandbox',
        '--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${port}`,'about:blank']);
      let target;
      for (let n = 0; n < 100; n++) {
        try {
          target = initialPageTarget(await (await fetch(`http://127.0.0.1:${port}/json/list`,{signal:AbortSignal.timeout(500)})).json());
          if (target) break;
        } catch { await sleep(100); }
      }
      assert.ok(target);
      original = await Cdp.open(target.webSocketDebuggerUrl,port,stop.signal);
      const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
      controller = await Cdp.open(version.webSocketDebuggerUrl,port,stop.signal);
      await original.command('Network.setCookies',{cookies:[{name:'auth-marker',value:'keep-original',
        domain:'www.twitch.tv',path:'/',secure:true,httpOnly:true}]});
      const contexts = (await controller.command('Target.getBrowserContexts')).browserContextIds;
      const profiles = (await readdir(tmpdir())).filter(name => name.startsWith('dockdrops-renew-')).sort();
      for (const outcome of ['success','failure','cancel']) {
        const attempt = new AbortController();
        let ownedProfile, ownedGroup, injected = false;
        const task = bootstrapSeed('/usr/bin/chromium',['--no-sandbox'],captured,attempt.signal,async (cdp,bundle,signal,initial) => {
          assert.equal(initial,true);
          const names = (await readdir(tmpdir())).filter(name => name.startsWith('dockdrops-renew-') && !profiles.includes(name));
          assert.equal(names.length,1); ownedProfile = join(tmpdir(),names[0]);
          const owned = await ownedProcesses(ownedProfile);
          ownedGroup = owned.find(p => p.args.includes('/usr/bin/xvfb-run'))?.group;
          assert.ok(ownedGroup);
          sampledPids = Math.max(sampledPids,Number(await readFile('/sys/fs/cgroup/pids.current','utf8')));
          const memory = await memorySample();
          if (memory.current > sampledMemory) { sampledMemory = memory.current; largestMemorySample = memory; }
          const command = cdp.command.bind(cdp), event = cdp.event.bind(cdp);
          const cookies = await command('Network.getCookies',{urls:['https://www.twitch.tv/','https://k.twitchcdn.net/']});
          assert.deepEqual(cookies.cookies,[],'bootstrap must not inherit login cookies');
          injected = true;
          if (outcome === 'failure') throw Error('synthetic bootstrap failure');
          if (outcome === 'cancel') { attempt.abort(); signal.throwIfAborted(); }
          cdp.command = (method,params,timeout) => command(method,method === 'Fetch.enable' ? {patterns:[{urlPattern:'*'}]} : params,timeout);
          cdp.event = async () => {
            for (;;) {
              const message = await event();
              if (message?.method !== 'Fetch.requestPaused' || message.params.request.url === 'https://www.twitch.tv/') return message;
              const {requestId,request} = message.params;
              let body = '', type = 'text/plain';
              const responseCookies = [];
              if (request.url === SDK_URL) {
                // Real response-cookie acceptance, not CDP injection that bypasses cookie policy.
                responseCookies.push({name:'Set-Cookie',value:'KP_UIDz-ssn=bootstrapped-cookie; Path=/; Max-Age=86400; Secure; HttpOnly; SameSite=None'});
                type = 'text/javascript';
                body = "window.KPSDK={configure:()=>{}};document.dispatchEvent(new Event('kpsdk-load'));document.dispatchEvent(new Event('kpsdk-ready'));";
              } else if (request.url === 'https://gql.twitch.tv/integrity' && request.method === 'POST') {
                assert.equal(request.headers.Authorization || request.headers.authorization,'OAuth fixture');
                type = 'application/json'; body = JSON.stringify({token:'bootstrap-proof',expiration:(now+3600)*1000});
              }
              await command('Fetch.fulfillRequest',{requestId,responseCode:200,responseHeaders:[
                ...responseCookies,
                {name:'Content-Type',value:type},{name:'Access-Control-Allow-Origin',value:'*'},
                {name:'Access-Control-Allow-Methods',value:'POST,GET,OPTIONS'},
                {name:'Access-Control-Allow-Headers',value:'authorization,client-id,client-request-id,client-session-id,client-version,x-device-id'},
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
        } else await assert.rejects(task,error => {
          assert.ok(injected,'fault injection must be reached');
          if (outcome === 'failure') assert.equal(error.message,'synthetic bootstrap failure');
          else { assert.equal(attempt.signal.aborted,true); assert.equal(error,attempt.signal.reason); }
          return true;
        });
        for (let i = 0; i < 100 && (await ownedProcesses(ownedProfile,ownedGroup)).length; i++) await sleep(20);
        assert.deepEqual(await ownedProcesses(ownedProfile,ownedGroup),[]);
        assert.deepEqual((await readdir(tmpdir())).filter(name => name.startsWith('dockdrops-renew-')).sort(),profiles);
        assert.deepEqual((await controller.command('Target.getBrowserContexts')).browserContextIds,contexts);
        const cookies = (await original.command('Network.getCookies',{urls:['https://www.twitch.tv/','https://k.twitchcdn.net/']})).cookies;
        assert.equal(cookies.find(c => c.name === 'auth-marker')?.value,'keep-original');
        assert.equal(cookies.some(c => c.name === 'KP_UIDz-ssn'),false);
        assert.equal(child.exitCode,null);
      }
      assert.ok(sampledPids < 256); assert.ok(sampledMemory < 1073741824);
      t.diagnostic(`Concurrent login and private-display issuance: sampled tasks ${sampledPids}/256, memory ${sampledMemory}/1073741824 bytes`);
      await assertNoOom(t,'Concurrent browser memory',largestMemorySample);
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
            const response = await fetch(`http://127.0.0.1:${port}/json/list`,{signal:AbortSignal.timeout(500)});
            target = initialPageTarget(await response.json()); if (target) break;
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
              {name:'Access-Control-Allow-Headers',value:'authorization,client-id,client-request-id,client-session-id,client-version,x-device-id'},
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

async function ownedProcesses(profile,groupId) {
  const processes = [];
  for (const pid of (await readdir('/proc')).filter(name => /^\d+$/.test(name))) {
    try {
      const args = (await readFile(`/proc/${pid}/cmdline`,'utf8')).split('\0').filter(Boolean);
      const status = await readFile(`/proc/${pid}/stat`,'utf8');
      const group = Number(status.slice(status.lastIndexOf(')')+2).split(' ')[2]);
      if (args.some(arg => arg.includes(profile)) || group === groupId) processes.push({pid,args,group});
    } catch { /* A process can exit while /proc is being read. */ }
  }
  return processes;
}

async function cgroupCounters(name) {
  return Object.fromEntries((await readFile(`/sys/fs/cgroup/${name}`,'utf8')).trim().split('\n').map(line => {
    const [key,value] = line.split(/\s+/); return [key,Number(value)];
  }));
}

async function memorySample() {
  const [current,stat,swap] = await Promise.all([
    readFile('/sys/fs/cgroup/memory.current','utf8'),cgroupCounters('memory.stat'),
    readFile('/sys/fs/cgroup/memory.swap.current','utf8'),
  ]);
  return {current:Number(current),anon:stat.anon,file:stat.file,shmem:stat.shmem,kernel:stat.kernel,swap:Number(swap)};
}

async function assertNoOom(t,label,sample) {
  const events = await cgroupCounters('memory.events');
  const tasks = await cgroupCounters('pids.events');
  const peak = Number(await readFile('/sys/fs/cgroup/memory.peak','utf8'));
  // Snapshot components can change while read. `file` includes tmpfs/shared memory;
  // memory.events:max measures reclaim pressure, not an OOM kill.
  t.diagnostic(`${label}: ${JSON.stringify({sample,peak,events,taskLimitEvents:tasks.max})}`);
  assert.equal(events.oom,0,'no cgroup OOM allocation failures');
  assert.equal(events.oom_kill,0,'no process may be killed by OOM');
  assert.equal(events.oom_group_kill,0,'no cgroup OOM group kills');
}

async function verifyDisplayAuthentication(profile,display) {
  const options = authority => ({env:{...process.env,DISPLAY:display,XAUTHORITY:authority},timeout:5000,maxBuffer:256*1024});
  const accepted = await execute('/usr/bin/xdpyinfo',['-display',display],options(join(profile,'Xauthority')));
  assert.ok(accepted.stdout.includes(display),'the private authority must open its display');
  const missing = join(profile,'empty-authority'), wrong = join(profile,'wrong-authority');
  await writeFile(missing,'',{mode:0o600}); await writeFile(wrong,'',{mode:0o600});
  await execute('/usr/bin/xauth',['-f',wrong,'add',display,'MIT-MAGIC-COOKIE-1','0123456789abcdef0123456789abcdef'],{timeout:5000});
  for (const authority of [missing,wrong]) {
    await assert.rejects(execute('/usr/bin/xdpyinfo',['-display',display],options(authority)),error => {
      assert.equal(error.killed,false,'authentication denial must finish without a timeout');
      assert.match(error.stderr,/unable to open display/);
      assert.match(error.stderr,/Authorization required|Invalid MIT-MAGIC-COOKIE-1 key/);
      return true;
    });
  }
}

test('private display recovery owns credentials and drains browsers after success failure cancellation and deadline',
  {skip:process.env.DOCKDROPS_BROWSER_TEST !== '1',timeout:60000}, async t => {
    assert.equal(process.env.DOCKDROPS_CONTAINER_BROWSER,'true');
    assert.equal(process.getuid(),1000);
    assert.match(await readFile('/proc/self/status','utf8'),/NoNewPrivs:\s+1/);
    assert.match(await readFile('/proc/self/status','utf8'),/CapEff:\s+0+\n/);
    assert.equal((await readFile('/sys/fs/cgroup/pids.max','utf8')).trim(),'256');
    assert.equal((await readFile('/sys/fs/cgroup/memory.max','utf8')).trim(),'1073741824');
    const existing = (await readdir(tmpdir())).filter(name => name.startsWith('dockdrops-renew-')).sort();
    let sampledPids = 0, sampledMemory = 0, largestMemorySample;
    for (const outcome of ['normal','expired','near-expiry','failure','cancel','deadline']) {
      const now = Math.floor(Date.now()/1000), stop = new AbortController();
      const recovered = ['expired','near-expiry'].includes(outcome);
      const seed = {version:1,captured_at:now-3600,expires_at:now-1,user_agent:'Chrome/fixture',
        headers:{'client-id':'kimne78kx3ncx6brgo4mv6wki5h1ko',authorization:'OAuth fixture',
          'x-device-id':'fixture-device','client-integrity':'previous-proof','client-session-id':'original-session',
          'client-version':'original-version','accept-language':'en-US'},
        sdk_cookie:{value:'previous-cookie',expires_at:outcome === 'expired' ? now-1 : outcome === 'near-expiry' ? now+30 : now+3600}};
      let profile, groupId, identityChecked = false, injected = false, deadlineTimer;
      const startedAt = Date.now();
      const dependencies = {
        validateIdentity:async (_,userId) => { assert.equal(userId,'123'); assert.equal(profile,undefined); identityChecked = true; },
        start:async (executable,ownedProfile,args,headless) => {
          if (recovered) assert.equal(identityChecked,true);
          profile = ownedProfile;
          const child = await startSeedBrowser(executable,profile,args,headless);
          groupId = child.pid;
          return child;
        },
        acquire:async (cdp,bundle,signal,initial) => {
          assert.equal(initial,recovered);
          assert.equal(Boolean(bundle.sdk_cookie),!recovered);
          const directory = await stat(profile), authority = await stat(join(profile,'Xauthority'));
          assert.equal(directory.uid,1000); assert.equal(directory.mode & 0o777,0o700);
          assert.equal(authority.uid,1000); assert.equal(authority.mode & 0o777,0o600);
          assert.notEqual(join(profile,'Xauthority'),process.env.XAUTHORITY);
          const owned = await ownedProcesses(profile);
          const display = owned.find(p => p.args[0] === 'Xvfb');
          assert.ok(display,'owned authenticated display must be running');
          assert.notEqual(display.args[1],process.env.DISPLAY);
          assert.ok(display.args.includes('-nolisten') && display.args.includes('tcp'));
          assert.equal(display.args[display.args.indexOf('-auth')+1],join(profile,'Xauthority'));
          assert.ok(!display.args.includes('-ac'));
          if (outcome === 'normal') await verifyDisplayAuthentication(profile,display.args[1]);
          assert.ok(owned.some(p => p.args.some(a => a === '--user-data-dir='+profile)));
          const userAgent = (await cdp.command('Runtime.evaluate',{expression:'navigator.userAgent',returnByValue:true})).result.value;
          assert.ok(!userAgent.includes('HeadlessChrome'));
          sampledPids = Math.max(sampledPids,Number(await readFile('/sys/fs/cgroup/pids.current','utf8')));
          const memory = await memorySample();
          if (memory.current > sampledMemory) { sampledMemory = memory.current; largestMemorySample = memory; }
          injected = true;
          if (outcome === 'failure') throw Error('Synthetic acquisition failure');
          if (outcome === 'cancel') { stop.abort(); signal.throwIfAborted(); }
          if (outcome === 'deadline') {
            deadlineTimer = setTimeout(() => stop.abort(new DOMException('Synthetic deadline','TimeoutError')),200);
            await sleep(10000,undefined,{signal}); assert.fail('deadline must interrupt acquisition');
          }
          const command = cdp.command.bind(cdp), event = cdp.event.bind(cdp);
          cdp.command = (method,params,timeout) => command(method,method === 'Fetch.enable' ? {patterns:[{urlPattern:'*'}]} : params,timeout);
          cdp.event = async () => {
            for (;;) {
              const message = await event();
              if (message?.method !== 'Fetch.requestPaused' || message.params.request.url === 'https://www.twitch.tv/') return message;
              const {requestId,request} = message.params;
              let body = '', type = 'text/plain'; const extra = [];
              if (request.url === SDK_URL) {
                const cookies = (await command('Network.getCookies',{urls:['https://k.twitchcdn.net/']})).cookies;
                assert.equal(cookies.find(c => c.name === 'KP_UIDz-ssn')?.value,recovered ? undefined : 'previous-cookie');
                extra.push({name:'Set-Cookie',value:'KP_UIDz-ssn=fresh-cookie; Path=/; Max-Age=86400; Secure; HttpOnly; SameSite=None'});
                type = 'text/javascript'; body = "window.KPSDK={configure:()=>{}};document.dispatchEvent(new Event('kpsdk-load'));document.dispatchEvent(new Event('kpsdk-ready'));";
              } else if (request.url === 'https://gql.twitch.tv/integrity' && request.method === 'POST') {
                type = 'application/json'; body = JSON.stringify({token:'fresh-proof',expiration:(now+3600)*1000});
              }
              await command('Fetch.fulfillRequest',{requestId,responseCode:200,responseHeaders:[...extra,
                {name:'Content-Type',value:type},{name:'Access-Control-Allow-Origin',value:'*'},
                {name:'Access-Control-Allow-Methods',value:'POST,GET,OPTIONS'},
                {name:'Access-Control-Allow-Headers',value:'authorization,client-id,client-request-id,client-session-id,client-version,x-device-id'},
              ],body:Buffer.from(body).toString('base64')});
            }
          };
          return acquireSeed(cdp,bundle,signal,initial);
        },
      };
      try {
        const task = issueSeed('/usr/bin/chromium',['--no-sandbox'],seed,stop.signal,false,'123',dependencies);
        if (['failure','cancel','deadline'].includes(outcome)) await assert.rejects(task,error => {
          assert.ok(injected,'fault injection must follow all lifecycle assertions');
          if (outcome === 'failure') assert.equal(error.message,'Synthetic acquisition failure');
          else {
            assert.equal(stop.signal.aborted,true);
            assert.equal(stop.signal.reason.name,outcome === 'deadline' ? 'TimeoutError' : 'AbortError');
            assert.ok(error === stop.signal.reason || error.cause === stop.signal.reason);
          }
          return true;
        });
        else {
          const result = await task;
          assert.deepEqual(result.headers,{...seed.headers,'client-integrity':'fresh-proof'});
          assert.equal(result.sdk_cookie.value,'fresh-cookie');
          assert.ok(result.expires_at > seed.expires_at);
        }
      } finally { clearTimeout(deadlineTimer); stop.abort(); }
      assert.ok(Date.now()-startedAt < 10000,'owned cleanup must finish within a bounded interval');
      await assert.rejects(access(profile));
      for (let i = 0; i < 100 && (await ownedProcesses(profile,groupId)).length; i++) await sleep(20);
      assert.deepEqual(await ownedProcesses(profile,groupId),[],'owned browser/display/crash-handler processes must exit');
      assert.deepEqual((await readdir(tmpdir())).filter(name => name.startsWith('dockdrops-renew-')).sort(),existing);
    }
    assert.ok(sampledPids < 256); assert.ok(sampledMemory < 1073741824);
    t.diagnostic(`Observed private-display scenarios: 6; sampled tasks ${sampledPids}/256, memory ${sampledMemory}/1073741824 bytes`);
    await assertNoOom(t,'Private-display lifecycle memory',largestMemorySample);
  });
