// Usage: node render.mjs <html> <outdir> <hash>...   Drives headless Chrome over CDP.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { join } from 'node:path';
const [html, outdir, ...hashes] = process.argv.slice(2);
const prof = mkdtempSync(join(outdir, 'chrome-'));
const chrome = spawn('google-chrome', ['--headless=new', '--no-sandbox', '--disable-gpu', '--remote-debugging-port=0',
  `--user-data-dir=${prof}`, '--window-size=1440,1000', 'about:blank'], { stdio: 'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));
let port; for (let i = 0; i < 100 && !port; i++) { await sleep(100); const f = join(prof, 'DevToolsActivePort'); if (existsSync(f)) port = readFileSync(f, 'utf8').split('\n')[0]; }
const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pend = {}; const logs = [];
ws.onmessage = ev => { const m = JSON.parse(ev.data);
  if (m.id && pend[m.id]) { pend[m.id](m); delete pend[m.id]; }
  if (m.method === 'Runtime.consoleAPICalled') logs.push(`${m.params.type}: ` + m.params.args.map(a => a.value ?? a.description ?? '').join(' '));
  if (m.method === 'Runtime.exceptionThrown') logs.push('EXCEPTION: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
  if (m.method === 'Log.entryAdded') logs.push(`log-${m.params.entry.level}: ${m.params.entry.text}`); };
const send = (method, params = {}) => new Promise(r => { const i = ++id; pend[i] = r; ws.send(JSON.stringify({ id: i, method, params })); });
await send('Runtime.enable'); await send('Log.enable'); await send('Page.enable');
await send('Page.navigate', { url: 'file://' + html }); await sleep(3000);
const shot = async name => { const r = await send('Page.captureScreenshot', { format: 'png' }); writeFileSync(join(outdir, name), Buffer.from(r.result.data, 'base64')); };
await shot('index.png');
for (const h of hashes) {
  await send('Runtime.evaluate', { expression: `location.hash = ${JSON.stringify(h)}` }); await sleep(2500);
  await shot(h.replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') + '.png');
}
// Walk every page once to surface render errors.
const pages = JSON.parse((await send('Runtime.evaluate', { expression: 'JSON.stringify(DATA.pages.map(p=>p.id))', returnByValue: true })).result.result.value);
for (const p of pages) { await send('Runtime.evaluate', { expression: `location.hash = "#/${p}"` }); await sleep(1200); }
const svgCount = (await send('Runtime.evaluate', { expression: 'document.querySelectorAll("svg").length', returnByValue: true })).result.result.value;
console.log('pages visited:', pages.length, '| svgs on last page:', svgCount);
console.log('console:', logs.length ? '\n' + logs.join('\n') : 'no messages');
ws.close(); chrome.kill(); await sleep(500); rmSync(prof, { recursive: true, force: true });
