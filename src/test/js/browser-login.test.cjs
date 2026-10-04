const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const tick = () => new Promise(resolve => setImmediate(resolve));

function viewer() {
  const elements = new Map();
  const calls = [];
  let status = {id:'',state:'idle'}, pendingStatus = null;
  const element = id => {
    if (!elements.has(id)) elements.set(id,{handlers:{},value:'',clientWidth:1100,
      addEventListener(event,handler) { this.handlers[event] = handler; },removeAttribute() {},getAttribute() { return null; }});
    return elements.get(id);
  };
  const context = vm.createContext({
    document:{getElementById:element,querySelectorAll:() => []}, window:{addEventListener() {}}, AbortSignal, setTimeout() {},
    fetch:async (url,options) => {
      calls.push({url,body:options.body && JSON.parse(options.body)});
      const data = url.endsWith('/status') ? pendingStatus ? await pendingStatus : status
        : url.includes('/frame?') ? {image:'ZmFrZQ=='} : {};
      return {ok:true,json:async () => data};
    },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/resources/web/browser-login.js'),'utf8') +
    '\nthis.driver = {poll,sendInput,start:()=>byId("start").handlers.click(),current:()=>({id:sessionId,state}),flush:()=>inputQueue};',context);
  return {driver:context.driver,calls,status:value => { status = value; },pending:value => { pendingStatus = value; }};
}

test('an old viewer refuses replacement input even after an intervening idle status', async () => {
  const c = viewer(); await tick();
  c.status({id:'first',state:'interactive'}); await c.driver.poll();
  c.status({id:'',state:'idle'}); await c.driver.poll();
  c.status({id:'second',state:'interactive'}); await c.driver.poll();
  assert.equal(c.driver.current().id,'expired');
  c.driver.sendInput({kind:'text',text:'sample'}); await c.driver.flush();
  assert.equal(c.calls.filter(x => x.url.endsWith('/input')).length,0);
});

test('late status replies cannot overwrite a newly requested login', async () => {
  const c = viewer(); await tick();
  let release;
  c.pending(new Promise(resolve => { release = resolve; }));
  const oldPoll = c.driver.poll();
  await c.driver.start();
  release({id:'old',state:'interactive'}); await oldPoll;
  assert.equal(c.driver.current().state,'starting');
  assert.equal(c.driver.current().id,null);
  c.pending(null); c.status({id:'new',state:'interactive'}); await c.driver.poll();
  c.driver.sendInput({kind:'text',text:' '}); await c.driver.flush();
  const input = c.calls.find(x => x.url.endsWith('/input')).body;
  assert.equal(input.id,'new'); assert.equal(input.text,' ');
});
