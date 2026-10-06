// Disposable, logged-out JVM instance. Never read .env or reuse an existing service/data directory.
const {mkdtempSync, rmSync, existsSync} = require('node:fs');
const {tmpdir} = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');
const lib = path.resolve('build/install/twitch-dock-drops/lib');
if (!existsSync(lib)) throw new Error('Run gradle test installDist before browser tests.');
const data = mkdtempSync(path.join(tmpdir(), 'dockdrops-browser-'));
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('TWITCH_DROPS_')));
Object.assign(env, {TWITCH_DROPS_DATA_DIR: data, TWITCH_DROPS_PORT: '18743', TWITCH_DROPS_LISTEN_HOST: '127.0.0.1', TWITCH_DROPS_ALLOW_LAN: 'false', TWITCH_DROPS_DASHBOARD_LOGIN: 'false'});
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const child = spawn(java, ['--add-modules=jdk.httpserver', '-cp', path.join(lib, '*'), 'app.twitchdockdrops.MainKt'], {env, stdio: 'inherit', windowsHide: true});
let stopping = false;
function stop() { if (!stopping) { stopping = true; child.kill(); } }
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, stop);
process.on('exit', stop);
child.on('exit', code => { rmSync(data, {recursive: true, force: true}); process.exit(stopping ? 0 : code || 0); });
