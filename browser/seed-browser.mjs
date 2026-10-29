import {spawn} from 'node:child_process';
import {join} from 'node:path';
import {setTimeout as sleep} from 'node:timers/promises';
import {startBrowser, stopBrowser} from '../src/main/resources/web/login-helper.mjs';

export function seedBrowserLaunch(executable, profile, args, headless, container = process.env.DOCKDROPS_CONTAINER_BROWSER === 'true') {
  const browser = ['--user-data-dir=' + profile,'--no-first-run','--no-default-browser-check',
    '--disable-background-mode',...args];
  if (container) return {executable:'/usr/bin/xvfb-run', args:['-a','-f',join(profile,'Xauthority'),
    '-s','-screen 0 1280x800x24 -nolisten tcp',executable,...browser,'--window-size=1200,760']};
  return {executable,args:[...browser,...(headless ? ['--headless=new'] : [])]};
}

export async function startSeedBrowser(executable, profile, args, headless) {
  if (process.env.DOCKDROPS_CONTAINER_BROWSER !== 'true') {
    return startBrowser(executable,profile,[...args,...(headless ? ['--headless=new'] : [])]);
  }
  const launch = seedBrowserLaunch(executable,profile,args,headless,true);
  // The runner owns its authenticated display; the login display is never reused.
  const env = {...process.env, XDG_CONFIG_HOME:join(profile,'config'), XDG_CACHE_HOME:join(profile,'cache')};
  delete env.DISPLAY; delete env.XAUTHORITY;
  const child = spawn(launch.executable,launch.args,{stdio:'ignore',detached:true,env});
  await new Promise((resolve,reject) => {
    child.once('spawn',resolve); child.once('error',() => reject(Error('Browser launch failed')));
  });
  return child;
}

export async function stopSeedBrowser(child) {
  if (!child) return;
  if (process.platform === 'win32') return stopBrowser(child);
  // A wrapper can exit before its browser/display. Always drain the owned process group.
  const kill = signal => { try { process.kill(-child.pid,signal); } catch {} };
  kill('SIGTERM');
  if (child.exitCode === null && child.signalCode === null) {
    await Promise.race([new Promise(resolve => child.once('exit',resolve)),sleep(3000)]);
  }
  kill('SIGKILL');
}
