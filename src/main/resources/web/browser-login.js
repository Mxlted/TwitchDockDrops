// All input goes to the isolated Twitch browser. Nothing is stored in browser storage or logs.
const byId = id => document.getElementById(id);
const screen = byId('screen');
let sessionId = null, state = 'idle', inputQueue = Promise.resolve(), queued = 0, frameRunning = false, generation = 0, mutationBusy = false;
const text = {
  idle:'Ready to open Twitch in the Docker browser.', starting:'Starting the browser…',
  interactive:'Complete Twitch sign-in below, then select Finish sign-in.',
  capturing:'Verifying your account and Drops access…', ready:'Connected. The browser service will renew your session. You can return to the dashboard.',
  failed:'Sign-in did not complete. Retry or use the desktop helper.',
  unavailable:'Dashboard login is not enabled. Start Docker with compose.browser.yaml or use the desktop helper.',
};
async function api(action, body) {
  const response = await fetch(`/api/auth/dashboard/${action}`, {method:body ? 'POST' : 'GET',cache:'no-store',
    headers:body ? {'Content-Type':'application/json'} : {}, ...(body ? {body:JSON.stringify(body)} : {}), signal:AbortSignal.timeout(10000)});
  const data = await response.json();
  if (!response.ok) throw Error(data.error || 'Browser request failed.');
  return data;
}
function showError(message) { byId('error').textContent = message; byId('error').hidden = !message; }
function paint(next) {
  state = next.state;
  byId('status').textContent = text[state] || 'Browser service is unavailable.';
  byId('start').disabled = ['starting','interactive','capturing','unavailable'].includes(state);
  byId('start').textContent = state === 'idle' ? 'Start sign-in' : 'Restart sign-in';
  byId('finish').hidden = state !== 'interactive';
  byId('cancel').hidden = !['starting','interactive','capturing'].includes(state);
  byId('viewerPanel').hidden = state !== 'interactive';
  if (state !== 'interactive') { screen.removeAttribute('src'); byId('typing').value = ''; }
  showError(next.error || '');
}
async function poll() {
  const current = generation;
  try {
    if (mutationBusy) return;
    const next = await api('status');
    if (current !== generation) return;
    // An old tab must not type into a replacement login opened elsewhere.
    if (sessionId && next.id && sessionId !== next.id) {
      paint({state:'failed',error:'A newer login replaced this view. Reload to open that login.'});
      sessionId = 'expired'; return;
    }
    if (sessionId === 'expired') return;
    if (next.id) sessionId = next.id;
    paint(next);
    if (state === 'interactive') await frame();
  } catch { if (current === generation) showError('Cannot reach the browser service. Check Docker, then retry.'); }
  finally { setTimeout(poll, state === 'interactive' ? 500 : 1500); }
}
async function frame() {
  if (frameRunning || !sessionId) return;
  frameRunning = true;
  const id = sessionId;
  try {
    const data = await api(`frame?id=${encodeURIComponent(id)}`);
    if (id === sessionId && state === 'interactive' && typeof data.image === 'string' && /^[A-Za-z0-9+/=]+$/.test(data.image)) {
      const first = !screen.getAttribute('src');
      screen.src = `data:image/jpeg;base64,${data.image}`;
      if (first) byId('viewport').scrollLeft = Math.max(0,(screen.clientWidth-byId('viewport').clientWidth)/2);
    }
  } catch { if (state === 'interactive') showError('Browser view is updating. If it stays unavailable, restart sign-in.'); }
  finally { frameRunning = false; }
}
function sendInput(body) {
  if (state !== 'interactive' || !sessionId || sessionId === 'expired') return;
  if (queued >= 64) { showError('Typing is ahead of the connection. Wait a moment before continuing.'); return; }
  const id = sessionId; queued++;
  inputQueue = inputQueue.then(async () => {
    if (id === sessionId && state === 'interactive') await api('input',{id,...body});
  }).catch(() => { showError('Input was not delivered. Check the selected Twitch field before continuing.'); }).finally(() => { queued--; });
}
byId('start').addEventListener('click',async () => {
  generation++; mutationBusy = true;
  byId('start').disabled = true;
  try { sessionId = null; await api('start',{}); paint({state:'starting'}); }
  catch (error) { byId('start').disabled = false; showError(error.message); }
  finally { generation++; mutationBusy = false; }
});
byId('finish').addEventListener('click',async () => {
  byId('finish').disabled = true;
  try { await inputQueue; await api('finish',{id:sessionId}); paint({state:'capturing'}); }
  catch (error) { showError(error.message); }
  finally { byId('finish').disabled = false; }
});
byId('cancel').addEventListener('click',async () => {
  generation++; mutationBusy = true;
  try { await api('cancel',{}); sessionId = null; paint({state:'idle'}); } catch (error) { showError(error.message); }
  finally { generation++; mutationBusy = false; }
});
screen.addEventListener('click',event => {
  const rect = screen.getBoundingClientRect(); screen.focus({preventScroll:true});
  sendInput({kind:'click',x:Math.min(1099,Math.max(0,Math.floor((event.clientX-rect.left)*1100/rect.width))),y:Math.min(759,Math.max(0,Math.floor((event.clientY-rect.top)*760/rect.height)))});
});
screen.addEventListener('keydown',event => {
  if (event.key === 'Escape') { screen.blur(); return; }
  if (event.ctrlKey || event.metaKey || event.altKey) return;
  if (['Tab','Enter','Backspace','Delete','ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(event.key)) {
    event.preventDefault(); sendInput({kind:'key',key:event.key,shift:event.shiftKey});
  } else if (event.key.length === 1) { event.preventDefault(); sendInput({kind:'text',text:event.key}); }
});
screen.addEventListener('paste',event => {
  event.preventDefault(); const value = event.clipboardData.getData('text');
  if (value.length <= 256 && !/[\x00-\x1f\x7f]/.test(value)) sendInput({kind:'text',text:value});
  else showError('Paste at most 256 characters without line breaks.');
});
byId('typing').addEventListener('input',event => {
  if (event.isComposing) return;
  const value = event.target.value; event.target.value = '';
  if (value && value.length <= 256 && !/[\x00-\x1f\x7f]/.test(value)) sendInput({kind:'text',text:value});
});
byId('typing').addEventListener('keydown',event => {
  if (event.key === 'Escape') { byId('finish').focus(); return; }
  if (['Enter','Backspace','Tab'].includes(event.key)) { event.preventDefault(); sendInput({kind:'key',key:event.key,shift:event.shiftKey}); }
});
document.querySelectorAll('[data-key]').forEach(button => button.addEventListener('click',() => sendInput({kind:'key',key:button.dataset.key,shift:false})));
document.querySelectorAll('[data-scroll]').forEach(button => button.addEventListener('click',() => sendInput({kind:'wheel',delta:Number(button.dataset.scroll)})));
byId('zoom').addEventListener('change',event => {
  screen.className = event.target.value === '.75' ? 'zoom-75' : event.target.value === '.5' ? 'zoom-50' : '';
  byId('viewport').scrollLeft = Math.max(0,(screen.clientWidth-byId('viewport').clientWidth)/2);
});
window.addEventListener('resize',() => {
  if (state === 'interactive') byId('viewport').scrollLeft = Math.max(0,(screen.clientWidth-byId('viewport').clientWidth)/2);
});
poll();
