import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createWorkerServer, validateInput} from '../../../browser/worker.mjs';

test('input allows bounded typing and navigation, never arbitrary browser commands or shortcuts', () => {
  for (const value of [{kind:'text',text:' '},{kind:'text',text:'Twitch'},{kind:'key',key:'Tab',shift:true},{kind:'click',x:1099,y:759},{kind:'wheel',delta:-100}]) assert.equal(validateInput(value),value);
  for (const value of [{kind:'text',text:'a\n'},{kind:'text',text:'x'.repeat(257)},{kind:'key',key:'F12',shift:false},{kind:'key',key:'Tab',shift:false,ctrl:true},{kind:'click',x:-1,y:0},{kind:'click',x:0,y:760},{kind:'text',text:'x',method:'Runtime.evaluate'}]) assert.throws(() => validateInput(value));
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
