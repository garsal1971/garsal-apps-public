/**
 * User test live — https://garsal.netlify.app
 * Esecuzione: node tests/run-tests.js
 *
 * Al primo avvio chiede il token Supabase (copia da DevTools), poi salva la sessione.
 * Le esecuzioni successive usano la sessione salvata automaticamente.
 * SOLO READ-ONLY — nessuna scrittura o cancellazione dati.
 */
const fs   = require('fs');
const path = require('path');

function loadPlaywright() {
  for (const p of [
    '/opt/node22/lib/node_modules/playwright',
    path.join(process.cwd(), 'node_modules', 'playwright'),
    'playwright',
  ]) {
    try { return require(p); } catch {}
  }
  throw new Error('Playwright non trovato. Installa con: npm install playwright');
}
const { chromium } = loadPlaywright();

const SESSION = path.join(__dirname, 'session.json');
const SHOTS   = path.join(__dirname, 'screenshots');
const REPORT  = path.join(__dirname, 'report.txt');
const BASE    = 'https://garsal.netlify.app';
const SB_KEY  = 'sb_token';

// ── Crea sessione se non esiste ───────────────────────────────────────────────
async function ensureSession() {
  if (fs.existsSync(SESSION)) return;

  // GitHub Actions: legge il token dalla variabile d'ambiente SB_TOKEN
  if (process.env.SB_TOKEN) {
    const session = {
      cookies: [],
      origins: [{ origin: BASE, localStorage: [{ name: SB_KEY, value: process.env.SB_TOKEN }] }],
    };
    fs.writeFileSync(SESSION, JSON.stringify(session, null, 2));
    console.log('✓ Sessione creata da SB_TOKEN env var');
    return;
  }

  // Interattivo: chiede il token da stdin
  console.log('\nNessuna sessione trovata.');
  console.log('─'.repeat(55));
  console.log('1. Apri ' + BASE + ' nel browser (già loggato)');
  console.log('2. DevTools → Console (F12) → incolla ed esegui:\n');
  console.log("   localStorage.getItem('" + SB_KEY + "')\n");
  console.log('3. Copia il testo che appare e incollalo qui sotto,');
  console.log('   poi premi Invio:\n');

  const token = await new Promise(resolve => {
    let buf = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', c => { buf += c; });
    process.stdin.on('end', () => resolve(buf.trim()));
    process.stdin.resume();
  });

  if (!token || token === 'null') {
    console.error('\n❌  Token non valido. Assicurati di essere loggato.');
    process.exit(1);
  }

  const session = {
    cookies: [],
    origins: [{ origin: BASE, localStorage: [{ name: SB_KEY, value: token }] }],
  };
  fs.writeFileSync(SESSION, JSON.stringify(session, null, 2));
  console.log('\n✓ Sessione salvata. Avvio test...\n');
}

// ── Helpers ───────────────────────────────────────────────────────────────────
if (!fs.existsSync(SHOTS)) fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
const cerrors = {};

const log  = msg => process.stdout.write(msg + '\n');
const pass = (app, t)    => { results.push({ app, t, ok: true });  log(`  ✓  ${t}`); };
const fail = (app, t, e) => { results.push({ app, t, ok: false, e: String(e.message||e).split('\n')[0] }); log(`  ✗  ${t}`); };

async function snap(page, name) {
  await page.screenshot({ path: path.join(SHOTS, name + '.png'), fullPage: false });
  log(`  📸  ${name}.png`);
}

async function openPage(ctx, app) {
  const page = await ctx.newPage();
  cerrors[app] = [];
  page.on('console',   m => { if (m.type() === 'error') cerrors[app].push(m.text()); });
  page.on('pageerror', e => cerrors[app].push('[JS] ' + e.message));
  return page;
}

async function clickNavItems(page, app, prefix) {
  const items = page.locator('.nav-item');
  const n = await items.count();
  for (let i = 0; i < n; i++) {
    const item = items.nth(i);
    if (!(await item.isVisible())) continue;
    try {
      const label = ((await item.getAttribute('data-text')) || (await item.innerText())).trim();
      const slug  = label.replace(/\s+/g,'_').replace(/[^\w_]/g,'').slice(0,18);
      await item.click();
      await new Promise(r => setTimeout(r, 500));
      await snap(page, `${prefix}-${i+1}-${slug}`);
      pass(app, `Nav "${label}"`);
    } catch(e) { fail(app, `Nav ${i+1}`, e); }
  }
}

// ── Test per app ──────────────────────────────────────────────────────────────
async function testLauncher(ctx) {
  const APP = 'AppSphere', page = await openPage(ctx, APP);
  log(`\n${'═'.repeat(48)}\n  ${APP}\n${'═'.repeat(48)}`);
  try {
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle', timeout: 30000 });
    await snap(page, '01-launcher');
    pass(APP, 'Pagina caricata');
  } catch(e) { fail(APP, 'Pagina caricata', e); await page.close(); return; }

  try { await page.waitForSelector('#user-bar', { state:'visible', timeout:8000 }); pass(APP, 'Login OK (#user-bar)'); }
  catch(e) { fail(APP, 'Login (#user-bar)', e); }

  try {
    await page.waitForSelector('.cw', { timeout:8000 });
    const n = await page.locator('.cw').count();
    pass(APP, `${n} bubble circles visibili`);
    await snap(page, '02-launcher-bubbles');
    await page.locator('.cw').first().hover();
    await new Promise(r => setTimeout(r, 500));
    await snap(page, '03-launcher-hover');
    pass(APP, 'Hover circle OK');
  } catch(e) { fail(APP, 'Bubble circles', e); }

  await page.close();
}

async function testTasks(ctx) {
  const APP = 'Tasks', page = await openPage(ctx, APP);
  log(`\n${'═'.repeat(48)}\n  ${APP}\n${'═'.repeat(48)}`);
  try {
    await page.goto(`${BASE}/tasks.html`, { waitUntil: 'networkidle', timeout: 45000 });
    await snap(page, '04-tasks');
    pass(APP, 'Pagina caricata');
  } catch(e) { fail(APP, 'Pagina caricata', e); await page.close(); return; }
  try { await page.waitForSelector('.main-content', { state:'visible', timeout:10000 }); pass(APP, 'Dashboard visibile'); }
  catch(e) { fail(APP, 'Dashboard visibile', e); }
  await clickNavItems(page, APP, '05-tasks-nav');
  await page.close();
}

async function testHabits(ctx) {
  const APP = 'HabitTracker', page = await openPage(ctx, APP);
  log(`\n${'═'.repeat(48)}\n  ${APP}\n${'═'.repeat(48)}`);
  try {
    await page.goto(`${BASE}/habit-tracker.html`, { waitUntil: 'networkidle', timeout: 30000 });
    await snap(page, '06-habits');
    pass(APP, 'Pagina caricata');
  } catch(e) { fail(APP, 'Pagina caricata', e); await page.close(); return; }
  try { await page.waitForSelector('#page-dashboard', { state:'visible', timeout:8000 }); pass(APP, 'Dashboard visibile'); }
  catch(e) { fail(APP, 'Dashboard visibile', e); }
  try {
    await page.waitForSelector('.habit-card', { timeout:5000 });
    pass(APP, `${await page.locator('.habit-card').count()} habit cards`);
    await snap(page, '07-habits-cards');
  } catch(_) { pass(APP, 'Dashboard OK (nessuna habit card)'); }
  await clickNavItems(page, APP, '08-habits-nav');
  await page.close();
}

async function testEvents(ctx) {
  const APP = 'EventsLog', page = await openPage(ctx, APP);
  log(`\n${'═'.repeat(48)}\n  ${APP}\n${'═'.repeat(48)}`);
  try {
    await page.goto(`${BASE}/events-log.html`, { waitUntil: 'networkidle', timeout: 30000 });
    await snap(page, '09-events');
    pass(APP, 'Pagina caricata');
  } catch(e) { fail(APP, 'Pagina caricata', e); await page.close(); return; }
  try { await page.waitForSelector('#logPage', { state:'visible', timeout:8000 }); pass(APP, 'Log page visibile'); }
  catch(e) { fail(APP, 'Log page visibile', e); }
  try {
    await page.waitForSelector('.event-btn', { timeout:5000 });
    pass(APP, `${await page.locator('.event-btn').count()} event buttons`);
    await snap(page, '10-events-buttons');
  } catch(_) { pass(APP, 'Log page OK (nessun evento)'); }
  await clickNavItems(page, APP, '11-events-nav');
  await page.close();
}

async function testWeight(ctx) {
  const APP = 'WeightQuest', page = await openPage(ctx, APP);
  log(`\n${'═'.repeat(48)}\n  ${APP}\n${'═'.repeat(48)}`);
  try {
    await page.goto(`${BASE}/weight-quest.html`, { waitUntil: 'networkidle', timeout: 30000 });
    await snap(page, '12-weight');
    pass(APP, 'Pagina caricata');
  } catch(e) { fail(APP, 'Pagina caricata', e); await page.close(); return; }

  if (await page.locator('#mainSection').isVisible().catch(() => false)) {
    pass(APP, 'Autenticato (#mainSection visibile)');
    try {
      await page.waitForSelector('#weightChart', { state:'visible', timeout:10000 });
      pass(APP, 'Chart visibile');
      await snap(page, '13-weight-chart');
    } catch(e) { fail(APP, 'Chart visibile', e); }
    const items = page.locator('.icon-nav-item');
    for (let i = 0; i < await items.count(); i++) {
      const item = items.nth(i);
      if (!(await item.isVisible())) continue;
      try {
        const label = await item.getAttribute('data-page') || String(i+1);
        await item.click();
        await new Promise(r => setTimeout(r, 500));
        await snap(page, `14-weight-nav-${i+1}-${label}`);
        pass(APP, `Nav "${label}"`);
      } catch(e) { fail(APP, `Nav ${i+1}`, e); }
    }
  } else {
    await snap(page, '13-weight-no-auth');
    pass(APP, 'Caricata (google_token assente — aprire da AppSphere)');
  }
  await page.close();
}

// ── Main ──────────────────────────────────────────────────────────────────────
(async () => {
  await ensureSession();

  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ storageState: SESSION, viewport: { width: 1280, height: 800 } });
  const t0  = Date.now();

  await testLauncher(ctx);
  await testTasks(ctx);
  await testHabits(ctx);
  await testEvents(ctx);
  await testWeight(ctx);

  await browser.close();

  const ok  = results.filter(r => r.ok).length;
  const ko  = results.filter(r => !r.ok).length;
  const sec = ((Date.now() - t0) / 1000).toFixed(1);

  const lines = [
    '═'.repeat(55),
    `REPORT — ${new Date().toISOString()}  (${sec}s)`,
    `TOTALE: ${ok+ko} test  ✓ ${ok} PASS  ✗ ${ko} FAIL`,
    '═'.repeat(55), '',
  ];

  for (const app of [...new Set(results.map(r => r.app))]) {
    const ar = results.filter(r => r.app === app);
    lines.push(`── ${app}  (${ar.filter(r=>r.ok).length}✓  ${ar.filter(r=>!r.ok).length}✗)`);
    ar.forEach(r => lines.push(`  ${r.ok?'✓':'✗'}  ${r.t}${r.e ? '\n     '+r.e : ''}`));
    const errs = cerrors[app] || [];
    if (errs.length) { lines.push(`  Console errors:`); errs.slice(0,5).forEach(e => lines.push('    • '+e.slice(0,100))); }
    lines.push('');
  }

  lines.push('Screenshots: ' + SHOTS);
  const report = lines.join('\n');
  log('\n' + report);
  fs.writeFileSync(REPORT, report);
})();
