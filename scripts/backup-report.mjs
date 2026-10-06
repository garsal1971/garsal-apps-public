#!/usr/bin/env node
// ═══════════════════════════════════════════════════════════════════════════
// backup-report.mjs — la relazione settimanale di AppSphere
// ═══════════════════════════════════════════════════════════════════════════
//
// Il dump è il salvataggio, questa è la lettura: una pagina HTML che racconta
// cosa c'è dentro il database — i task aperti, il patrimonio, i punti, le
// abitudini, le spese — perché un file .sql da qualche megabyte dice se il
// backup è riuscito ma non cosa c'era dentro quella settimana.
//
// Gira in GitHub Actions (`.github/workflows/backup.yml`) e legge via
// PostgREST con la service key. Niente dipendenze: Node 20 ha `fetch`.
//
// ⚠️ NIENTE RISERVATI. Le schede di Memo, i gruppi di Eventi e i task marcati
// `riservato` non entrano nella relazione: si contano e basta. La modalità
// nascosta esiste perché quella roba non si legga di sfuggita, e una relazione
// è un file che si apre senza chiedere niente a nessuno. Nel **dump** ci sono
// per forza — quello è un backup, non una lettura.
//
// ⚠️ LE COLONNE NON SI DANNO PER SCONTATE. Metà di queste tabelle non sta in
// nessuna migration (`ts_*`, `hb_*`, `el_*`, `ps_*`, `cm_categories`…): sono
// nate a mano in produzione. Lo script legge quindi lo **schema vero** dallo
// spec OpenAPI di PostgREST e chiede solo colonne che esistono davvero. Una
// tabella che non c'è, o una colonna sparita, danno una riga che lo dice —
// non una relazione che si interrompe a metà.
//
// Uso:
//   SUPABASE_URL=… SUPABASE_SERVICE_KEY=… node scripts/backup-report.mjs out.html

// La relazione porta la sua versione come le app: due relazioni della stessa
// settimana generate da due versioni diverse dello script non si distinguono
// altrimenti, ed è la prima cosa che si vuole sapere quando una sezione manca.
const VERSIONE = 'v1.0.2';

const BASE  = (process.env.SUPABASE_URL || '').replace(/\/+$/, '');
const KEY   = process.env.SUPABASE_SERVICE_KEY || '';
const EMAIL = (process.env.BACKUP_USER_EMAIL || 'garsal1971@gmail.com').toLowerCase();
const OUT   = process.argv[2] || process.env.BACKUP_REPORT_OUT || 'relazione.html';
// Scheda dei file del dump, scritta dal workflow: nome, peso, impronta.
const INFO_DUMP = process.env.BACKUP_DUMP_INFO || '';

if (!BASE || !KEY) {
  console.error('backup-report: servono SUPABASE_URL e SUPABASE_SERVICE_KEY');
  process.exit(1);
}

const HDR = { apikey: KEY, Authorization: `Bearer ${KEY}` };

// ── Diagnostica ────────────────────────────────────────────────────────────
// Ogni cosa che non è andata finisce qui e compare in fondo alla relazione.
// Un errore taciuto è una sezione vuota che sembra un archivio vuoto.
const guai = [];
function guaio(dove, err) {
  const msg = err && err.message ? err.message : String(err);
  guai.push({ dove, msg });
  console.warn(`  ⚠ ${dove}: ${msg}`);
}

// ── Schema vero, letto da PostgREST ────────────────────────────────────────
let SCHEMA = {};   // tabella → Set(colonne)

async function caricaSchema() {
  const r = await fetch(`${BASE}/rest/v1/`, { headers: { ...HDR, Accept: 'application/openapi+json' } });
  if (!r.ok) throw new Error(`spec OpenAPI: HTTP ${r.status}`);
  const spec = await r.json();
  const defs = spec.definitions || (spec.components && spec.components.schemas) || {};
  for (const [nome, def] of Object.entries(defs)) {
    SCHEMA[nome] = new Set(Object.keys(def.properties || {}));
  }
  console.log(`  schema: ${Object.keys(SCHEMA).length} tabelle`);
}

const c_e      = (t) => Object.prototype.hasOwnProperty.call(SCHEMA, t);
const ha       = (t, col) => c_e(t) && SCHEMA[t].has(col);
// Prima colonna che esiste davvero fra quelle proposte.
const primaCol = (t, ...cols) => cols.find((c) => ha(t, c)) || null;

// ── Lettura ────────────────────────────────────────────────────────────────
let UID = null;

// Filtro utente: solo dove la colonna c'è. Le tabelle figlie (voci di una
// lista, storico di un'azione) spesso non ce l'hanno — ereditano dal padre.
function filtroUtente(t) {
  return ha(t, 'user_id') && UID ? `user_id=eq.${UID}` : null;
}

// Fuori dalla relazione ciò che è riservato. ⚠️ `riservato.eq.false` da solo
// non basta: la colonna è arrivata dopo le righe, quindi le vecchie hanno
// NULL e con la sola uguaglianza sparirebbero tutte.
function filtroRiservato(t) {
  return ha(t, 'riservato') ? 'or=(riservato.eq.false,riservato.is.null)' : null;
}

async function righe(t, opz = {}) {
  if (!c_e(t)) return null;
  const { select = '*', order = null, limit = 5000, extra = [], riservati = false } = opz;
  const fissi = [filtroUtente(t), riservati ? null : filtroRiservato(t), ...extra].filter(Boolean);
  const out = [];
  const passo = 1000;
  try {
    while (out.length < limit) {
      const da = out.length;
      const a  = Math.min(da + passo, limit) - 1;
      const qs = [`select=${encodeURIComponent(select)}`];
      if (order) qs.push(`order=${encodeURIComponent(order)}`);
      qs.push(...fissi);
      const r = await fetch(`${BASE}/rest/v1/${t}?${qs.join('&')}`, {
        headers: { ...HDR, Range: `${da}-${a}`, 'Range-Unit': 'items' },
      });
      if (!r.ok) throw new Error(`HTTP ${r.status} — ${(await r.text()).slice(0, 200)}`);
      const blocco = await r.json();
      out.push(...blocco);
      if (blocco.length < a - da + 1) break;
    }
  } catch (e) {
    guaio(`lettura ${t}`, e);
    return null;
  }
  return out;
}

// Quante righe ci sono, senza portarsele dietro.
async function quante(t, extra = []) {
  if (!c_e(t)) return null;
  const fissi = [filtroUtente(t), ...extra].filter(Boolean);
  try {
    const qs = ['select=' + encodeURIComponent(primaCol(t, 'id') || '*'), ...fissi];
    const r = await fetch(`${BASE}/rest/v1/${t}?${qs.join('&')}`, {
      headers: { ...HDR, Range: '0-0', 'Range-Unit': 'items', Prefer: 'count=exact' },
    });
    if (!r.ok) return null;
    const cr = r.headers.get('content-range') || '';
    const n = parseInt(cr.split('/')[1], 10);
    return Number.isFinite(n) ? n : null;
  } catch (e) {
    guaio(`conteggio ${t}`, e);
    return null;
  }
}

async function rpc(nome, params) {
  try {
    const r = await fetch(`${BASE}/rest/v1/rpc/${nome}`, {
      method: 'POST',
      headers: { ...HDR, 'Content-Type': 'application/json' },
      body: JSON.stringify(params || {}),
    });
    if (!r.ok) throw new Error(`HTTP ${r.status} — ${(await r.text()).slice(0, 200)}`);
    return await r.json();
  } catch (e) {
    guaio(`rpc ${nome}`, e);
    return null;
  }
}

// L'utente si cerca per email nell'Admin API: `auth.users` da PostgREST non
// si legge, e l'uuid scritto a mano qui dentro sarebbe una costante da
// aggiornare il giorno che l'account cambia.
async function trovaUtente() {
  for (let pagina = 1; pagina <= 10; pagina++) {
    const r = await fetch(`${BASE}/auth/v1/admin/users?per_page=200&page=${pagina}`, { headers: HDR });
    if (!r.ok) throw new Error(`admin/users: HTTP ${r.status}`);
    const dati = await r.json();
    const utenti = Array.isArray(dati) ? dati : dati.users || [];
    const trovato = utenti.find((u) => (u.email || '').toLowerCase() === EMAIL);
    if (trovato) return trovato;
    if (utenti.length < 200) break;
  }
  throw new Error(`nessun utente con email ${EMAIL}`);
}

// ── Formattazione ──────────────────────────────────────────────────────────
const esc = (v) =>
  String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

const NUM = new Intl.NumberFormat('it-IT');
const EUR = new Intl.NumberFormat('it-IT', { style: 'currency', currency: 'EUR', maximumFractionDigits: 2 });

const n  = (v, d = '—') => (v == null || v === '' || Number.isNaN(Number(v)) ? d : NUM.format(Number(v)));
const eur = (v, d = '—') => (v == null || v === '' || Number.isNaN(Number(v)) ? d : EUR.format(Number(v)));
const dec = (v, k = 1, d = '—') =>
  v == null || v === '' || Number.isNaN(Number(v)) ? d : Number(v).toLocaleString('it-IT', { minimumFractionDigits: k, maximumFractionDigits: k });

// Le date si tagliano dalla stringa, non si passano da `new Date`: un
// `YYYY-MM-DD` letto come Date è mezzanotte UTC, che in Italia è il giorno
// prima. È lo stesso inciampo annotato in tutta la repo.
function giorno(v, d = '—') {
  if (!v) return d;
  const s = String(v).slice(0, 10);
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : esc(String(v).slice(0, 20));
}
function istante(v, d = '—') {
  if (!v) return d;
  const s = String(v);
  const g = giorno(s);
  const m = /[T ](\d{2}:\d{2})/.exec(s);
  return m ? `${g} ${m[1]}` : g;
}
const peso = (b) =>
  b == null ? '—' : b < 1024 ? `${b} B` : b < 1048576 ? `${(b / 1024).toFixed(0)} KB` : `${(b / 1048576).toFixed(1)} MB`;

// Primo campo valorizzato fra quelli proposti: le tabelle nate a mano non
// hanno tutte lo stesso nome per la stessa cosa.
function campo(riga, ...nomi) {
  if (!riga) return null;
  for (const k of nomi) if (riga[k] != null && riga[k] !== '') return riga[k];
  return null;
}
const somma = (arr, f) => (arr || []).reduce((t, r) => t + (Number(f(r)) || 0), 0);

function conta(arr, f) {
  const m = new Map();
  for (const r of arr || []) {
    const k = f(r);
    if (k == null || k === '') continue;
    m.set(k, (m.get(k) || 0) + 1);
  }
  return [...m.entries()].sort((a, b) => b[1] - a[1]);
}

const oggiISO = new Date().toISOString().slice(0, 10);

// ── Pezzi di pagina ────────────────────────────────────────────────────────
const sezioni = [];   // {id, titolo, emoji, html}

function sezione(id, emoji, titolo, ...blocchi) {
  const html = blocchi.filter(Boolean).join('\n');
  sezioni.push({ id, emoji, titolo, html: html || vuoto('Niente da mostrare.') });
}

const vuoto = (msg) => `<p class="vuoto">${esc(msg)}</p>`;
const nota  = (msg) => `<p class="nota">${msg}</p>`;
const h3    = (t) => `<h3>${esc(t)}</h3>`;

// Riquadri con i numeri di testa. Griglia che va a capo da sé: la soglia è in
// `rem`, quindi cresce col testo invece di guardare la larghezza dello schermo.
function kpi(voci) {
  const celle = voci
    .filter((v) => v)
    .map((v) => `<div class="kpi"><span class="kpi-v">${v.valore}</span><span class="kpi-e">${esc(v.etichetta)}</span>${v.sotto ? `<span class="kpi-s">${esc(v.sotto)}</span>` : ''}</div>`)
    .join('');
  return celle ? `<div class="kpis">${celle}</div>` : '';
}

// Le tabelle scorrono **dentro il proprio riquadro**: una pagina che scorre di
// lato nasconde la prima colonna, che è quella che si legge.
function tabella(intestazioni, righeDati, opz = {}) {
  if (!righeDati || !righeDati.length) return vuoto(opz.seVuota || 'Nessuna riga.');
  const th = intestazioni.map((h) => `<th>${esc(h)}</th>`).join('');
  const tr = righeDati
    .map((r) => `<tr>${r.map((c) => `<td>${c == null ? '—' : c}</td>`).join('')}</tr>`)
    .join('');
  const coda = opz.coda ? `<p class="coda">${esc(opz.coda)}</p>` : '';
  return `<div class="scroll"><table><thead><tr>${th}</tr></thead><tbody>${tr}</tbody></table></div>${coda}`;
}

// Elenco chiave/valore per le cose che non sono una tabella.
function elenco(voci) {
  const ok = voci.filter((v) => v && v[1] != null && v[1] !== '');
  if (!ok.length) return '';
  return `<dl>${ok.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${v}</dd>`).join('')}</dl>`;
}

// «Mostrate le prime N di M»: una tabella tagliata in silenzio è una tabella
// che mente sul totale.
function tagliata(tutte, mostrate, cosa) {
  return tutte > mostrate ? `Mostrate le prime ${n(mostrate)} di ${n(tutte)} ${cosa}.` : null;
}

// ── Vocabolari condivisi ───────────────────────────────────────────────────
// Categorie e priorità sono di `cm_*` e le usano Tasks e Obiettivi: si
// leggono una volta sola e si passano a chi serve.
let CATEGORIE = new Map();
let PRIORITA  = new Map();

function nomiDa(mappa, ids, max = 3) {
  const lista = (Array.isArray(ids) ? ids : []).map((i) => mappa.get(i)).filter(Boolean);
  if (!lista.length) return '—';
  return lista.length > max ? `${esc(lista.slice(0, max).join(', '))} +${lista.length - max}` : esc(lista.join(', '));
}

async function vocabolari() {
  for (const [t, dest] of [['cm_categories', CATEGORIE], ['cm_priorities', PRIORITA]]) {
    const r = await righe(t, { limit: 500, riservati: true });
    for (const x of r || []) {
      const nome = campo(x, 'name', 'nome', 'title', 'label');
      if (x.id != null && nome) dest.set(x.id, nome);
    }
  }
}

// ── 🏆 Punteggi e premi ────────────────────────────────────────────────────
async function sezPunteggi() {
  const punteggi = await rpc('backup_scores', { p_user: UID });
  const premi    = await righe('cm_rewards', { limit: 500, riservati: true });
  const log      = await righe('cm_rewards_log', { limit: 2000, riservati: true });

  let blocchiPunti = vuoto('La funzione backup_scores non ha risposto: i punteggi non sono disponibili.');
  let lordo = null;

  if (Array.isArray(punteggi)) {
    // ⚠️ Non tutti i numeri di score_query sono punti: Spuntiamola conta i
    // giorni che mancano, Obiettivi gli obiettivi attivi, Finanza le
    // transazioni. Quei numeri dimensionano la bolla ma non entrano nel totale
    // che paga i premi. Lo dice `cm_apps.conta_punti`
    // (`20260924130000_cm_apps_conta_punti.sql`), la stessa colonna che leggono
    // le due home: fino al 24 settembre 2026 era una terza copia a mano
    // dell'elenco. Conta solo un `true` esplicito, come là.
    const apps = await righe('cm_apps', { select: 'html_file,conta_punti', riservati: true });
    const PUNTI = new Set((apps || []).filter((a) => a.conta_punti === true).map((a) => a.html_file));
    const contaPunti = (a) => PUNTI.has(a.html_file);
    lordo = punteggi
      .filter((a) => contaPunti(a) && Number.isFinite(Number(a.score)))
      .reduce((t, a) => t + Number(a.score), 0);

    blocchiPunti = tabella(
      ['App', 'Numero', 'Conta nei punti?'],
      punteggi.map((a) => [
        esc(a.title || a.html_file || '—'),
        a.errore ? `<span class="ko">errore</span>` : n(a.score),
        contaPunti(a) ? 'sì' : '<span class="muto">no, è un conteggio</span>',
      ]),
    );
  }

  const spesi = log ? somma(log, (r) => campo(r, 'points_spent', 'points', 'punti')) : null;
  const netto = lordo != null && spesi != null ? Math.max(0, lordo - spesi) : null;

  const daRitirare = (premi || []).filter((p) => !campo(p, 'is_redeemed'));
  const rigaPremio = (p) => [
    esc(campo(p, 'title', 'name', 'nome') || '—'),
    n(campo(p, 'points_cost', 'cost', 'punti')),
    campo(p, 'points_per_use') != null
      ? Number(campo(p, 'points_per_use')) === 0
        ? '<span class="muto">costo fisso</span>'
        : `+${n(campo(p, 'points_per_use'))} a uso`
      : '—',
    n(campo(p, 'use_count') || 0),
  ];

  sezione('punteggi', '🏆', 'Punteggi e premi',
    kpi([
      { etichetta: 'Punti guadagnati', valore: n(lordo) },
      { etichetta: 'Punti spesi',      valore: n(spesi) },
      { etichetta: 'Saldo spendibile', valore: n(netto), sotto: 'mai sotto zero' },
      { etichetta: 'Premi in catalogo', valore: n(daRitirare.length) },
    ]),
    h3('Il numero di ogni app'),
    blocchiPunti,
    h3('Premi da ritirare'),
    tabella(['Premio', 'Costa', 'Incremento', 'Usato'], daRitirare.slice(0, 40).map(rigaPremio),
      { seVuota: 'Nessun premio nel catalogo.', coda: tagliata(daRitirare.length, 40, 'premi') }),
    h3('Ultimi premi riscossi'),
    tabella(['Quando', 'Premio', 'Punti'],
      (log || [])
        .slice()
        .sort((a, b) => String(campo(b, 'redeemed_at', 'created_at') || '').localeCompare(String(campo(a, 'redeemed_at', 'created_at') || '')))
        .slice(0, 20)
        .map((r) => [istante(campo(r, 'redeemed_at', 'created_at')), esc(campo(r, 'reward_title', 'title', 'name') || '—'), n(campo(r, 'points_spent', 'points'))]),
      { seVuota: 'Nessun premio ancora riscosso.' }),
  );
}

// ── ✅ Tasks ───────────────────────────────────────────────────────────────
async function sezTasks() {
  const t = await righe('ts_tasks', { limit: 5000 });
  if (!t) return sezione('tasks', '✅', 'Tasks', vuoto('Tabella ts_tasks non leggibile.'));

  const riservati = await quante('ts_tasks', ['riservato=eq.true']);
  const VIVI = new Set(['started', 'completed', 'skipped', 'failed']);
  const attivi = t.filter((x) => VIVI.has(String(campo(x, 'status') || '')));
  const scaduti = attivi.filter((x) => {
    const d = campo(x, 'next_occurrence_date', 'start_date');
    return d && String(d).slice(0, 10) < oggiISO;
  });
  const oggi = attivi.filter((x) => String(campo(x, 'next_occurrence_date', 'start_date') || '').slice(0, 10) === oggiISO);

  const perProssima = (a, b) =>
    String(campo(a, 'next_occurrence_date', 'start_date') || '9999').localeCompare(String(campo(b, 'next_occurrence_date', 'start_date') || '9999'));

  const riga = (x) => [
    esc(campo(x, 'title') || '—'),
    esc(campo(x, 'type') || '—'),
    esc(campo(x, 'status') || '—'),
    esc(PRIORITA.get(campo(x, 'priority_id')) || '—'),
    nomiDa(CATEGORIE, campo(x, 'categories')),
    giorno(campo(x, 'next_occurrence_date', 'start_date')),
    n(campo(x, 'success_points')),
  ];

  const colStoria = primaCol('ts_history', 'timestamp', 'created_at');
  const storia = await righe('ts_history', { limit: 4000, ...(colStoria ? { order: `${colStoria}.desc` } : {}) });
  const recenti = (storia || []).filter((h) => {
    const q = String(campo(h, 'timestamp', 'created_at') || '').slice(0, 10);
    return q >= new Date(Date.now() - 90 * 864e5).toISOString().slice(0, 10);
  });

  sezione('tasks', '✅', 'Tasks',
    kpi([
      { etichetta: 'Task in archivio', valore: n(t.length) },
      { etichetta: 'Attivi',           valore: n(attivi.length) },
      { etichetta: 'Scaduti',          valore: n(scaduti.length) },
      { etichetta: 'In scadenza oggi', valore: n(oggi.length) },
      riservati ? { etichetta: 'Riservati', valore: n(riservati), sotto: 'non elencati' } : null,
    ]),
    h3('Per stato'),
    tabella(['Stato', 'Quanti'], conta(t, (x) => campo(x, 'status')).map(([k, v]) => [esc(k), n(v)])),
    h3('Per tipo'),
    tabella(['Tipo', 'Quanti'], conta(t, (x) => campo(x, 'type')).map(([k, v]) => [esc(k), n(v)])),
    h3('Task attivi'),
    tabella(['Titolo', 'Tipo', 'Stato', 'Priorità', 'Categorie', 'Prossima volta', 'Punti'],
      attivi.slice().sort(perProssima).slice(0, 200).map(riga),
      { seVuota: 'Nessun task attivo.', coda: tagliata(attivi.length, 200, 'task attivi') }),
    h3('Cosa è successo negli ultimi 90 giorni'),
    tabella(['Azione', 'Quante volte'], conta(recenti, (h) => campo(h, 'action', 'status', 'type')).map(([k, v]) => [esc(k), n(v)]),
      { seVuota: 'Nessuna riga di storico nel periodo.' }),
  );
}

// ── 🔁 Abituati ────────────────────────────────────────────────────────────
async function sezAbituati() {
  const h = await righe('hb_habits', { limit: 2000 });
  if (!h) return sezione('abituati', '🔁', 'Abituati', vuoto('Tabella hb_habits non leggibile.'));

  const attive   = h.filter((x) => String(campo(x, 'status') || 'active') === 'active');
  const ferme    = h.filter((x) => String(campo(x, 'status') || '') === 'stopped');
  const punti    = await righe('hb_user_points', { limit: 10 });
  const archivio = await righe('hb_archived_stacks', { limit: 200 });
  const compl    = await quante('hb_completions');

  const riga = (x) => [
    esc(campo(x, 'name', 'title', 'nome') || '—'),
    esc(campo(x, 'frequency') || '—'),
    giorno(campo(x, 'started_at', 'start_date')),
    n(campo(x, 'goal_days', 'goal', 'target_days')),
    n(campo(x, 'current_failures') || 0),
    n(campo(x, 'jolly', 'max_failures', 'allowed_failures')),
  ];

  sezione('abituati', '🔁', 'Abituati',
    kpi([
      { etichetta: 'Abitudini attive',   valore: n(attive.length) },
      { etichetta: 'Interrotte',         valore: n(ferme.length) },
      { etichetta: 'Spunte in archivio', valore: n(compl) },
      { etichetta: 'Punti',              valore: n(campo((punti || [])[0] || {}, 'points', 'total_points', 'punti')) },
      { etichetta: 'Stack archiviati',   valore: n((archivio || []).length) },
    ]),
    h3('Abitudini attive'),
    tabella(['Abitudine', 'Frequenza', 'Iniziata il', 'Traguardo', 'Fallimenti', 'Jolly'],
      attive.map(riga), { seVuota: 'Nessuna abitudine attiva.' }),
    ferme.length ? h3('Interrotte') : null,
    ferme.length ? tabella(['Abitudine', 'Frequenza', 'Iniziata il', 'Traguardo', 'Fallimenti', 'Jolly'], ferme.map(riga)) : null,
    archivio && archivio.length ? h3('Stack archiviati') : null,
    archivio && archivio.length
      ? tabella(['Stack', 'Chiuso il', 'Esito'],
          archivio.slice(0, 40).map((s) => [
            esc(campo(s, 'name', 'title', 'nome') || '—'),
            giorno(campo(s, 'archived_at', 'closed_at', 'created_at')),
            esc(campo(s, 'outcome', 'esito', 'status') || '—'),
          ]))
      : null,
  );
}

// ── 🎯 Obiettivi ───────────────────────────────────────────────────────────
async function sezObiettivi() {
  const ob = await righe('ob_objectives', { limit: 500 });
  if (!ob) return sezione('obiettivi', '🎯', 'Obiettivi', vuoto('Tabella ob_objectives non leggibile.'));

  const metriche = (await righe('ob_metrics', { limit: 1000 })) || [];
  const azioni   = (await righe('ob_actions', { limit: 2000 })) || [];
  const rileva   = (await righe('ob_measurements', { limit: 4000 })) || [];

  const nomeOb = (x) => campo(x, 'title', 'name', 'nome') || '—';
  const ultima = new Map();   // metric_id → riga più recente
  for (const m of rileva) {
    const k = campo(m, 'metric_id');
    const d = String(campo(m, 'measured_on', 'created_at') || '');
    if (!k) continue;
    const p = ultima.get(k);
    if (!p || d > p._d) ultima.set(k, { ...m, _d: d });
  }

  const VIVE = new Set(['started', 'completed', 'skipped', 'failed']);
  const aperte = azioni.filter((a) => VIVE.has(String(campo(a, 'status') || '')));

  sezione('obiettivi', '🎯', 'Obiettivi',
    kpi([
      { etichetta: 'Obiettivi',   valore: n(ob.length) },
      { etichetta: 'Metriche',    valore: n(metriche.length) },
      { etichetta: 'Azioni aperte', valore: n(aperte.length) },
      { etichetta: 'Rilevazioni', valore: n(rileva.length) },
    ]),
    h3('Gli obiettivi'),
    tabella(['Obiettivo', 'Livello', 'Stato', 'Da', 'A'],
      ob.map((x) => [
        esc(nomeOb(x)),
        esc(campo(x, 'level', 'kind', 'tipo') || (campo(x, 'parent_id') ? 'trimestrale' : 'annuale')),
        esc(campo(x, 'status') || '—'),
        giorno(campo(x, 'start_date', 'starts_on')),
        giorno(campo(x, 'end_date', 'ends_on', 'deadline')),
      ])),
    h3('Le metriche e il loro ultimo valore'),
    tabella(['Metrica', 'Tipo', 'Ruolo', 'Ultimo valore', 'Quando'],
      metriche.map((m) => {
        const u = ultima.get(m.id);
        return [
          esc(campo(m, 'name', 'title', 'nome') || '—'),
          esc(campo(m, 'kind') || '—'),
          esc(campo(m, 'role') || '—'),
          u ? dec(campo(u, 'value'), 2) : '<span class="muto">mai rilevata</span>',
          u ? giorno(campo(u, 'measured_on', 'created_at')) : '—',
        ];
      }), { seVuota: 'Nessuna metrica.' }),
    h3('Azioni ancora da fare'),
    tabella(['Azione', 'Tipo', 'Stato', 'Prossima volta'],
      aperte
        .slice()
        .sort((a, b) => String(campo(a, 'next_occurrence_date', 'start_date') || '9999').localeCompare(String(campo(b, 'next_occurrence_date', 'start_date') || '9999')))
        .slice(0, 120)
        .map((a) => [
          esc(campo(a, 'title', 'name') || '—'),
          esc(campo(a, 'type') || '—'),
          esc(campo(a, 'status') || '—'),
          giorno(campo(a, 'next_occurrence_date', 'start_date')),
        ]),
      { seVuota: 'Nessuna azione aperta.', coda: tagliata(aperte.length, 120, 'azioni') }),
  );
}

// ── 📋 Events Log ──────────────────────────────────────────────────────────
async function sezEventi() {
  const gruppi = await righe('el_groups', { limit: 300 });
  if (!gruppi) return sezione('eventi', '📋', 'Events Log', vuoto('Tabella el_groups non leggibile.'));

  const nascosti = await quante('el_groups', ['riservato=eq.true']);
  const visibili = new Set(gruppi.map((g) => g.id));
  const eventi   = ((await righe('el_events', { limit: 2000 })) || []).filter((e) => visibili.has(campo(e, 'group_id', 'gruppo_id')));
  const idEventi = new Set(eventi.map((e) => e.id));
  const log      = ((await righe('el_logs', { limit: 8000 })) || []).filter((l) => idEventi.has(campo(l, 'event_id', 'evento_id')));

  const perEvento = new Map();
  for (const l of log) {
    const k = campo(l, 'event_id', 'evento_id');
    const d = String(campo(l, 'logged_at', 'created_at', 'timestamp') || '');
    const p = perEvento.get(k) || { n: 0, ultima: '' };
    p.n += 1;
    if (d > p.ultima) p.ultima = d;
    perEvento.set(k, p);
  }

  const nomeGruppo = new Map(gruppi.map((g) => [g.id, campo(g, 'name', 'nome', 'title') || '—']));

  sezione('eventi', '📋', 'Events Log',
    kpi([
      { etichetta: 'Gruppi',        valore: n(gruppi.length) },
      { etichetta: 'Eventi',        valore: n(eventi.length) },
      { etichetta: 'Registrazioni', valore: n(log.length) },
      nascosti ? { etichetta: 'Gruppi riservati', valore: n(nascosti), sotto: 'non elencati' } : null,
    ]),
    tabella(['Evento', 'Gruppo', 'Registrazioni', 'Ultima volta'],
      eventi
        .map((e) => ({ e, s: perEvento.get(e.id) || { n: 0, ultima: '' } }))
        .sort((a, b) => b.s.n - a.s.n)
        .slice(0, 150)
        .map(({ e, s }) => [
          esc(campo(e, 'name', 'nome', 'title') || '—'),
          esc(nomeGruppo.get(campo(e, 'group_id', 'gruppo_id')) || '—'),
          n(s.n),
          s.ultima ? istante(s.ultima) : '<span class="muto">mai</span>',
        ]),
      { seVuota: 'Nessun evento.', coda: tagliata(eventi.length, 150, 'eventi') }),
  );
}

// ── ⚖️ Ti pisasti? ─────────────────────────────────────────────────────────
async function sezPeso() {
  const ob = await righe('ps_objectives', { limit: 200 });
  if (!ob) return sezione('peso', '⚖️', 'Ti pisasti?', vuoto('Tabella ps_objectives non leggibile.'));

  const attivo = ob.find((o) => campo(o, 'status') === 'active' || campo(o, 'active') === true) || ob[0];
  const pesate = (await righe('ps_weight_tracking', { limit: 4000 })) || [];
  const premi  = (await righe('ps_milestone_prizes', { limit: 200 })) || [];
  const punti  = (await righe('ps_milestone_points', { limit: 200 })) || [];

  const perGiorno = new Map();   // giorno → minimo della giornata (la pesata che fa punti)
  for (const p of pesate) {
    const g = String(campo(p, 'date', 'day', 'created_at') || '').slice(0, 10);
    const kg = Number(campo(p, 'weight'));
    if (!g || !Number.isFinite(kg)) continue;
    const q = perGiorno.get(g);
    if (!q || kg < q.min) perGiorno.set(g, { min: kg, target: campo(p, 'target_weight') });
    else if (q) q.target = q.target ?? campo(p, 'target_weight');
  }
  const giorni = [...perGiorno.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  const ultimo = giorni[giorni.length - 1];

  sezione('peso', '⚖️', 'Ti pisasti?',
    kpi([
      { etichetta: 'Obiettivi',        valore: n(ob.length) },
      { etichetta: 'Giorni con pesata', valore: n(giorni.length) },
      { etichetta: 'Ultimo peso',      valore: ultimo ? `${dec(ultimo[1].min, 1)} kg` : '—', sotto: ultimo ? giorno(ultimo[0]) : undefined },
      { etichetta: 'Target di quel giorno', valore: ultimo && ultimo[1].target != null ? `${dec(ultimo[1].target, 1)} kg` : '—' },
      { etichetta: 'Premi vinti',      valore: n(premi.length) },
    ]),
    attivo ? h3('Obiettivo in corso') : null,
    attivo
      ? elenco([
          ['Nome',         esc(campo(attivo, 'name', 'title', 'nome') || '—')],
          ['Tipo',         esc(campo(attivo, 'type', 'objective_type') || '—')],
          ['Stato',        esc(campo(attivo, 'status') || '—')],
          ['Periodo',      `${giorno(campo(attivo, 'start_date'))} → ${giorno(campo(attivo, 'end_date'))}`],
          ['Peso di partenza', campo(attivo, 'start_weight') != null ? `${dec(campo(attivo, 'start_weight'), 1)} kg` : null],
          ['Peso finale',  campo(attivo, 'end_weight') != null ? `${dec(campo(attivo, 'end_weight'), 1)} kg` : null],
          ['Punteggio',    campo(attivo, 'total_score') != null ? n(campo(attivo, 'total_score')) : null],
        ])
      : null,
    h3('Le ultime 30 giornate'),
    tabella(['Giorno', 'Peso minimo', 'Target'],
      giorni.slice(-30).reverse().map(([g, v]) => [giorno(g), `${dec(v.min, 1)} kg`, v.target != null ? `${dec(v.target, 1)} kg` : '—']),
      { seVuota: 'Nessuna pesata in archivio.' }),
    h3('Obiettivi chiusi'),
    tabella(['Obiettivo', 'Periodo', 'Stato', 'Punteggio'],
      ob.filter((o) => o !== attivo).map((o) => [
        esc(campo(o, 'name', 'title', 'nome') || '—'),
        `${giorno(campo(o, 'start_date'))} → ${giorno(campo(o, 'end_date'))}`,
        esc(campo(o, 'status') || '—'),
        n(campo(o, 'total_score')),
      ]), { seVuota: 'Nessun obiettivo chiuso.' }),
    premi.length ? h3('Premi dei traguardi') : null,
    premi.length
      ? tabella(['Premio', 'Vinto il', 'Mangiato il'],
          premi.slice(-30).reverse().map((p) => [
            esc(campo(p, 'prize_id') || '—'),
            giorno(campo(p, 'won_on')),
            campo(p, 'consumed_on') ? giorno(campo(p, 'consumed_on')) : '<span class="muto">non ancora</span>',
          ]))
      : null,
    punti.length ? nota(`Punti dei traguardi in archivio: <strong>${n(somma(punti, (r) => campo(r, 'points', 'punti')))}</strong>.`) : null,
  );
}

// ── 🍽️ Calorie ─────────────────────────────────────────────────────────────
async function sezCalorie() {
  const giornate = await righe('al_days', { limit: 2000 });
  if (!giornate) return sezione('calorie', '🍽️', 'Calorie', vuoto('Tabella al_days non leggibile.'));

  const log      = (await righe('al_log', { limit: 20000 })) || [];
  const alimenti = (await righe('al_foods', { limit: 5000 })) || [];
  const profilo  = ((await righe('al_profile', { limit: 5 })) || [])[0];

  // kcal della riga: valori per 100 congelati sulla riga × quantità / 100.
  const kcalRiga = (r) => {
    const per100 = Number(campo(r, 'kcal_100g', 'energy_kcal_100g', 'calories_100g'));
    const q = Number(campo(r, 'grams', 'quantity', 'amount'));
    return Number.isFinite(per100) && Number.isFinite(q) ? (per100 * q) / 100 : 0;
  };
  const perGiorno = new Map();
  for (const r of log) {
    const g = String(campo(r, 'day', 'date') || '').slice(0, 10);
    if (!g) continue;
    perGiorno.set(g, (perGiorno.get(g) || 0) + kcalRiga(r));
  }
  const target = new Map(giornate.map((d) => [String(campo(d, 'day', 'date') || '').slice(0, 10), Number(campo(d, 'target_kcal', 'target', 'tdee'))]));

  const ultimi = [...new Set([...perGiorno.keys(), ...target.keys()])].filter(Boolean).sort().slice(-21).reverse();
  const verificati = alimenti.filter((a) => campo(a, 'verified') === true).length;

  sezione('calorie', '🍽️', 'Calorie',
    kpi([
      { etichetta: 'Giorni segnati',    valore: n(perGiorno.size) },
      { etichetta: 'Righe di diario',   valore: n(log.length) },
      { etichetta: 'Alimenti in catalogo', valore: n(alimenti.length), sotto: `${n(verificati)} verificati` },
      { etichetta: 'Fattore di attività', valore: profilo && campo(profilo, 'activity') != null ? dec(campo(profilo, 'activity'), 2) : '—' },
    ]),
    h3('Le ultime tre settimane'),
    tabella(['Giorno', 'Mangiate', 'Target', 'Scarto'],
      ultimi.map((g) => {
        const m = perGiorno.get(g);
        const t = target.get(g);
        const s = Number.isFinite(m) && Number.isFinite(t) ? m - t : null;
        return [
          giorno(g),
          m == null ? '<span class="muto">niente segnato</span>' : `${n(Math.round(m))} kcal`,
          Number.isFinite(t) ? `${n(Math.round(t))} kcal` : '—',
          s == null ? '—' : `<span class="${s > 0 ? 'ko' : 'ok'}">${s > 0 ? '+' : ''}${n(Math.round(s))}</span>`,
        ];
      }), { seVuota: 'Nessun giorno segnato.' }),
  );
}

// ── 📝 Memorandum ──────────────────────────────────────────────────────────
async function sezMemo() {
  const schede = await righe('mm_cards', { limit: 3000 });
  if (!schede) return sezione('memo', '📝', 'Memorandum', vuoto('Tabella mm_cards non leggibile.'));

  const nascoste = await quante('mm_cards', ['riservato=eq.true']);
  const visibili = new Set(schede.map((s) => s.id));
  const voci     = ((await righe('mm_list_items', { limit: 8000 })) || []).filter((v) => visibili.has(campo(v, 'card_id')));
  const reg      = ((await righe('mm_diary_entries', { limit: 8000 })) || []).filter((r) => visibili.has(campo(r, 'card_id')));
  const misure   = ((await righe('mm_diary_metrics', { limit: 500 })) || []).filter((m) => visibili.has(campo(m, 'card_id')));

  const regPerScheda = new Map();
  for (const r of reg) {
    const k = campo(r, 'card_id');
    const d = String(campo(r, 'entry_date', 'created_at') || '');
    const p = regPerScheda.get(k) || { n: 0, ultima: '' };
    p.n += 1;
    if (d > p.ultima) p.ultima = d;
    regPerScheda.set(k, p);
  }
  const vociAperte = voci.filter((v) => campo(v, 'done') !== true).length;

  const perTipo = conta(schede, (s) => campo(s, 'kind') || 'nota');
  const titolo  = (s) => {
    const t = campo(s, 'title', 'titolo', 'name');
    return t ? esc(t) : '<span class="muto">senza titolo</span>';
  };

  sezione('memo', '📝', 'Memorandum',
    kpi([
      { etichetta: 'Schede',          valore: n(schede.length) },
      { etichetta: 'Voci di lista aperte', valore: n(vociAperte), sotto: `su ${n(voci.length)}` },
      { etichetta: 'Registrazioni di diario', valore: n(reg.length) },
      { etichetta: 'Misure',          valore: n(misure.length) },
      nascoste ? { etichetta: 'Schede riservate', valore: n(nascoste), sotto: 'non elencate' } : null,
    ]),
    h3('Per tipo'),
    tabella(['Tipo', 'Quante'], perTipo.map(([k, v]) => [esc(k), n(v)])),
    h3('Le schede'),
    tabella(['Scheda', 'Tipo', 'Registrazioni', 'Aggiornata'],
      schede
        .slice()
        .sort((a, b) => String(campo(b, 'updated_at', 'created_at') || '').localeCompare(String(campo(a, 'updated_at', 'created_at') || '')))
        .slice(0, 150)
        .map((s) => {
          const r = regPerScheda.get(s.id);
          return [
            (campo(s, 'pinned') ? '📌 ' : '') + titolo(s),
            esc(campo(s, 'kind') || 'nota'),
            r ? `${n(r.n)} · ultima ${giorno(r.ultima)}` : '—',
            istante(campo(s, 'updated_at', 'created_at')),
          ];
        }),
      { seVuota: 'Nessuna scheda.', coda: tagliata(schede.length, 150, 'schede') }),
  );
}

// ── 💰 Finanza ─────────────────────────────────────────────────────────────
async function sezFinanza() {
  const colSnap = primaCol('fnz_dashboard_snapshots', 'snapshot_date', 'created_at');
  const snap = await righe('fnz_dashboard_snapshots', {
    limit: 1,
    ...(colSnap ? { order: `${colSnap}.desc` } : {}),
  });
  const s = (snap || [])[0];
  const d = (s && s.details) || {};

  const portafogli = Array.isArray(d.portfolios) ? d.portfolios : [];
  const mutui      = Array.isArray(d.loans) ? d.loans : [];
  const asset      = Array.isArray(d.other_assets) ? d.other_assets : [];

  const fondi     = (await righe('fnz_funds', { limit: 200 })) || [];
  const contrib   = (await righe('fnz_fund_contributions', { limit: 8000 })) || [];
  const reddito   = (await righe('fnz_income', { limit: 500 })) || [];
  const coperture = (await righe('fnz_coverage_items', { limit: 300 })) || [];

  const perFondo = new Map();
  for (const c of contrib) {
    const st = String(campo(c, 'status') || '');
    if (st !== 'auto' && st !== 'confermato') continue;   // da_rivedere ed escluso non fanno quota
    const k = campo(c, 'fund_id');
    perFondo.set(k, (perFondo.get(k) || 0) + Number(campo(c, 'amount') || 0));
  }

  const anni = [...new Set(reddito.map((r) => campo(r, 'year')))].filter((a) => a != null).sort((a, b) => b - a);
  const perAnno = (anno, kind) => {
    const r = reddito.find((x) => campo(x, 'year') === anno && campo(x, 'kind') === kind);
    return r ? Number(campo(r, 'amount')) : null;
  };

  // Le migliori posizioni di tutti i portafogli messe insieme: è la domanda
  // «di cosa è fatto» — dentro un portafoglio sono già ordinate.
  const posizioni = portafogli.flatMap((p) => (p.holdings || []).map((h) => ({ ...h, portafoglio: p.name })));
  posizioni.sort((a, b) => (Number(b.current_value) || 0) - (Number(a.current_value) || 0));

  sezione('finanza', '💰', 'Finanza',
    s
      ? kpi([
          { etichetta: 'Patrimonio netto', valore: eur(campo(s, 'patrimonio_netto')), sotto: `fotografia del ${giorno(campo(s, 'snapshot_date'))}` },
          { etichetta: 'Portafogli',       valore: eur(campo(s, 'portafogli_totali')) },
          { etichetta: 'Asset totali',     valore: eur(campo(s, 'asset_totali')) },
          { etichetta: 'Debiti',           valore: eur(campo(s, 'debiti_totali')) },
        ])
      : vuoto('Nessuno snapshot in fnz_dashboard_snapshots: i totali del patrimonio non sono disponibili.'),
    s ? nota('I totali sono al <strong>lordo</strong> delle imposte, come lo snapshot: è la stessa scelta di <code>save-snapshot</code>, per tenere confrontabile la serie storica.') : null,
    h3('Portafogli'),
    tabella(['Portafoglio', 'Valore', 'di cui liquidità', 'Costo', 'P&L', '%'],
      portafogli.map((p) => [
        esc(p.name || '—'),
        eur(p.total_value),
        eur(p.cash),
        eur(p.total_cost),
        `<span class="${Number(p.pnl) < 0 ? 'ko' : 'ok'}">${eur(p.pnl)}</span>`,
        p.pnl_pct != null ? `${dec(p.pnl_pct, 2)} %` : '—',
      ]), { seVuota: 'Nessun portafoglio nello snapshot.' }),
    posizioni.length ? h3('Le posizioni più grosse') : null,
    posizioni.length
      ? tabella(['Titolo', 'Portafoglio', 'Quantità', 'Prezzo', 'Valore', 'P&L'],
          posizioni.slice(0, 40).map((h) => [
            esc(h.name || h.symbol || '—'),
            esc(h.portafoglio || '—'),
            dec(h.qty, 2),
            eur(h.current_price),
            eur(h.current_value),
            `<span class="${Number(h.pnl) < 0 ? 'ko' : 'ok'}">${eur(h.pnl)}</span>`,
          ]), { coda: tagliata(posizioni.length, 40, 'posizioni') })
      : null,
    h3('Mutui e prestiti'),
    tabella(['Nome', 'Tipo', 'Debito residuo', 'Quota'],
      mutui.map((l) => [esc(l.name || '—'), esc(l.type || '—'), eur(l.residual_value), `${dec(l.ownership_pct, 0)} %`]),
      { seVuota: 'Nessun debito.' }),
    h3('Altri asset'),
    tabella(['Asset', 'Tipo', 'Valore', 'Quota', 'Valutato il'],
      asset.map((a) => [esc(a.title || '—'), esc(a.asset_type || '—'), eur(a.value), `${dec(a.ownership_pct, 0)} %`, giorno(a.valuation_date)]),
      { seVuota: 'Nessun altro asset.' }),
    h3('Fondi'),
    tabella(['Fondo', 'Movimenti che fanno quota'],
      fondi.map((f) => [esc(campo(f, 'name') || '—'), eur(perFondo.get(f.id) || 0)]),
      { seVuota: 'Nessun fondo.' }),
    anni.length ? h3('Reddito, anno per anno') : null,
    anni.length
      ? tabella(['Anno', 'Lavoro', 'Pensione', 'Altri lordi', 'Altri netti', 'Imponibile', 'Imposta netta', 'Reddito netto (calc.)'],
          anni.slice(0, 12).map((a) => {
            const imp = perAnno(a, 'liq_imponibile');
            const net = perAnno(a, 'liq_imposta_netta');
            return [
              esc(a),
              eur(perAnno(a, 'lavoro')), eur(perAnno(a, 'pensione')),
              eur(perAnno(a, 'altri_lordi')), eur(perAnno(a, 'altri_netti')),
              eur(imp), eur(net),
              imp != null && net != null ? eur(imp - net) : '—',
            ];
          }),
          { coda: 'Reddito netto è calcolato (imponibile − imposta netta) e non toglie le addizionali regionale e comunale.' })
      : null,
    coperture.length ? h3('Possibili soluzioni — le voci compilate') : null,
    coperture.length
      ? tabella(['Voce', 'Lato', 'Importo', 'Periodicità', 'Esclusa'],
          coperture.map((c) => [
            esc(campo(c, 'item_key') || '—'),
            esc(campo(c, 'side') || '—'),
            campo(c, 'amount') != null ? eur(campo(c, 'amount')) : '<span class="muto">vale il dato di Finanza</span>',
            esc(campo(c, 'periodicity') || '—'),
            campo(c, 'excluded') ? 'sì' : '—',
          ]))
      : null,
  );
}

// ── 🧾 I conti delle spese ─────────────────────────────────────────────────
// Cinque app con lo stesso mestiere e prefissi diversi (`ca_`, `ada_`, `sal_`,
// `cntrs_`, `cntrs_*_terr`): una funzione sola, non cinque copie. `amount` è
// sempre **con segno**, quindi entrate e uscite si separano dal segno e non da
// una colonna in più.
async function bloccoSpese(cfg) {
  const mov = await righe(cfg.movimenti, { limit: 30000 });
  if (!mov || !mov.length) return null;

  const cats = new Map();
  for (const t of cfg.categorie || []) {
    for (const c of (await righe(t, { limit: 1000 })) || []) {
      const nome = campo(c, 'name', 'nome', 'title');
      if (c.id != null && nome) cats.set(c.id, nome);
    }
  }

  const imp  = (r) => Number(campo(r, 'amount', 'importo') || 0);
  const data = (r) => String(campo(r, 'booking_date', 'date', 'data', 'transaction_date', 'created_at') || '').slice(0, 10);

  const anni = new Map();
  for (const r of mov) {
    const a = data(r).slice(0, 4);
    if (!a) continue;
    const q = anni.get(a) || { entrate: 0, uscite: 0, n: 0 };
    const v = imp(r);
    if (v >= 0) q.entrate += v; else q.uscite += -v;
    q.n += 1;
    anni.set(a, q);
  }

  const annoCorrente = String(new Date().getFullYear());
  const uscitePerCat = new Map();
  for (const r of mov) {
    if (data(r).slice(0, 4) !== annoCorrente) continue;
    const v = imp(r);
    if (v >= 0) continue;
    const k = cats.get(campo(r, 'category_id')) || 'senza categoria';
    uscitePerCat.set(k, (uscitePerCat.get(k) || 0) + -v);
  }

  const senzaCat = mov.filter((r) => ha(cfg.movimenti, 'category_id') && campo(r, 'category_id') == null).length;

  return [
    h3(cfg.titolo),
    kpi([
      { etichetta: 'Movimenti', valore: n(mov.length) },
      { etichetta: `Uscite ${annoCorrente}`, valore: eur((anni.get(annoCorrente) || {}).uscite || 0) },
      { etichetta: `Entrate ${annoCorrente}`, valore: eur((anni.get(annoCorrente) || {}).entrate || 0) },
      cats.size ? { etichetta: 'Senza categoria', valore: n(senzaCat) } : null,
    ]),
    tabella(['Anno', 'Movimenti', 'Entrate', 'Uscite', 'Saldo'],
      [...anni.entries()].sort((a, b) => b[0].localeCompare(a[0])).slice(0, 10).map(([a, q]) => [
        esc(a), n(q.n), eur(q.entrate), eur(q.uscite),
        `<span class="${q.entrate - q.uscite < 0 ? 'ko' : 'ok'}">${eur(q.entrate - q.uscite)}</span>`,
      ])),
    uscitePerCat.size ? tabella([`Voce di spesa ${annoCorrente}`, 'Uscite'],
      [...uscitePerCat.entries()].sort((a, b) => b[1] - a[1]).slice(0, 20).map(([k, v]) => [esc(k), eur(v)])) : null,
  ].filter(Boolean).join('\n');
}

async function sezSpese() {
  const blocchi = [];
  for (const cfg of [
    { titolo: 'Spese Famiglia',   movimenti: 'ca_transactions',           categorie: ['ca_categories'] },
    { titolo: 'Spese Ada',        movimenti: 'ada_transactions',          categorie: ['ada_categories'] },
    { titolo: 'Spese Personali',  movimenti: 'sal_transactions',          categorie: ['sal_categories'] },
    { titolo: 'Casa Rosa',        movimenti: 'cntrs_transactions',        categorie: ['cntrs_categories'] },
    { titolo: 'Conto Risparmio',  movimenti: 'cntrs_transactions_terr',   categorie: ['cntrs_categories_terr'] },
    { titolo: 'Danaro di Rosa',   movimenti: 'fnz_rosa_transactions',     categorie: [] },
  ]) {
    const b = await bloccoSpese(cfg);
    if (b) blocchi.push(b);
  }

  // Contribuzione ha uno schema suo: persona e tipo, importo sempre positivo.
  const contrib = await righe('acct_transactions', { limit: 20000 });
  if (contrib && contrib.length) {
    const perPersona = new Map();
    for (const r of contrib) {
      const k = campo(r, 'persona', 'person') || '—';
      perPersona.set(k, (perPersona.get(k) || 0) + Number(campo(r, 'importo', 'amount') || 0));
    }
    blocchi.push([
      h3('Contribuzione al conto delle spese comuni'),
      tabella(['Chi', 'Versato in tutto'],
        [...perPersona.entries()].sort((a, b) => b[1] - a[1]).map(([k, v]) => [esc(k), eur(v)])),
      nota(`Righe in archivio: <strong>${n(contrib.length)}</strong>. Le quote sono 2/5–3/5 fino al 2023 e 1/3–2/3 dal 2024.`),
    ].join('\n'));
  }

  sezione('spese', '🧾', 'I conti', ...(blocchi.length ? blocchi : [vuoto('Nessun conto leggibile.')]));
}

// ── ☑️ Spuntiamola ─────────────────────────────────────────────────────────
async function sezSpuntiamola() {
  const cfg = ((await righe('sp_settings', { limit: 5 })) || [])[0];
  const spunte = (await righe('sp_checks', { limit: 2000 })) || [];
  const chiave = (await righe('sp_key_days', { limit: 200 })) || [];
  const chiuse = (await righe('sp_stecche', { limit: 200 })) || [];

  if (!cfg && !chiuse.length) return sezione('spuntiamola', '☑️', 'Spuntiamola', vuoto('Nessuna stecca, in corso o chiusa.'));

  let mancano = null;
  if (cfg) {
    const fine = String(campo(cfg, 'end_date') || '').slice(0, 10);
    if (fine) {
      const gg = Math.ceil((new Date(fine + 'T12:00:00') - new Date(oggiISO + 'T12:00:00')) / 864e5);
      mancano = Math.max(0, gg);
    }
  }

  sezione('spuntiamola', '☑️', 'Spuntiamola',
    cfg
      ? [
          kpi([
            { etichetta: 'Giorni spuntati', valore: n(spunte.length) },
            { etichetta: 'Giorni al traguardo', valore: n(mancano) },
            { etichetta: 'Giornate chiave', valore: n(chiave.length) },
            { etichetta: 'Umore', valore: esc(campo(cfg, 'mood') || 'attesa') },
          ]),
          elenco([
            ['Traguardo', esc(campo(cfg, 'goal', 'traguardo', 'title') || '—')],
            ['Periodo',   `${giorno(campo(cfg, 'start_date'))} → ${giorno(campo(cfg, 'end_date'))}`],
            ['Salta sabato e domenica', campo(cfg, 'skip_weekend') ? 'sì' : 'no'],
          ]),
        ].join('\n')
      : vuoto('Nessuna stecca in corso.'),
    chiuse.length ? h3('Le stecche chiuse') : null,
    chiuse.length
      ? tabella(['Traguardo', 'Periodo', 'Giorni fatti', 'Soddisfazione'],
          chiuse.map((s) => [
            esc(campo(s, 'goal', 'traguardo', 'title') || '—'),
            `${giorno(campo(s, 'start_date'))} → ${giorno(campo(s, 'end_date'))}`,
            `${n(campo(s, 'done_days'))} / ${n(campo(s, 'total_days'))}`,
            campo(s, 'satisfaction') != null ? `${n(campo(s, 'satisfaction'))}/100` : '—',
          ]))
      : null,
  );
}

// ── 🎯 Ta Firi? ────────────────────────────────────────────────────────────
async function sezTaFiri() {
  const sfide = await righe('sf_challenges', { limit: 500 });
  if (!sfide) return;
  const checkin = (await righe('sf_checkins', { limit: 8000 })) || [];
  const perSfida = new Map();
  for (const c of checkin) {
    const k = campo(c, 'challenge_id');
    const st = String(campo(c, 'status', 'state', 'esito') || '');
    const q = perSfida.get(k) || {};
    q[st] = (q[st] || 0) + 1;
    perSfida.set(k, q);
  }
  const attive = sfide.filter((s) => ['active', 'started', 'in_corso'].includes(String(campo(s, 'status') || '')));

  sezione('tafiri', '🎯', 'Ta Firi?',
    kpi([
      { etichetta: 'Sfide',      valore: n(sfide.length) },
      { etichetta: 'In corso',   valore: n(attive.length) },
      { etichetta: 'Check-in',   valore: n(checkin.length) },
      { etichetta: 'Punti presi', valore: n(somma(sfide, (s) => campo(s, 'final_score', 'score', 'total_score'))) },
    ]),
    tabella(['Sfida', 'Stato', 'Periodo', 'Check-in', 'Punteggio'],
      sfide
        .slice()
        .sort((a, b) => String(campo(b, 'start_date', 'created_at') || '').localeCompare(String(campo(a, 'start_date', 'created_at') || '')))
        .slice(0, 60)
        .map((s) => {
          const q = perSfida.get(s.id) || {};
          return [
            esc(campo(s, 'title', 'name', 'nome') || '—'),
            esc(campo(s, 'status') || '—'),
            `${giorno(campo(s, 'start_date'))} → ${giorno(campo(s, 'end_date'))}`,
            esc(Object.entries(q).map(([k, v]) => `${k}: ${v}`).join(' · ') || '—'),
            n(campo(s, 'final_score', 'score', 'total_score')),
          ];
        }), { seVuota: 'Nessuna sfida.' }),
  );
}

// ── 🆘 SOS ─────────────────────────────────────────────────────────────────
async function sezSos() {
  const tipi = await righe('sos_types', { limit: 100 });
  if (!tipi) return;
  const giri = (await righe('sos_sessions', { limit: 5000 })) || [];
  const esiti = (await righe('sos_outcomes', { limit: 200 })) || [];
  const nomeEsito = new Map(esiti.map((e) => [e.id, campo(e, 'label', 'text', 'nome') || '—']));

  const chiusi = giri.filter((g) => campo(g, 'outcome_id'));
  const min = (sec) => (sec == null ? '—' : `${Math.round(Number(sec) / 60)} min`);

  sezione('sos', '🆘', 'SOS',
    kpi([
      { etichetta: 'SOS configurati', valore: n(tipi.length) },
      { etichetta: 'Giri fatti',      valore: n(giri.length) },
      { etichetta: 'Giri chiusi',     valore: n(chiusi.length) },
      { etichetta: 'Punti',           valore: n(somma(chiusi, (g) => campo(g, 'points'))) },
    ]),
    tabella(['SOS', 'Prossimo giro', 'Di partenza', 'Fra', 'Giri'],
      tipi.map((t) => [
        esc(campo(t, 'name', 'title', 'nome') || '—'),
        min(campo(t, 'current_seconds')),
        min(campo(t, 'base_seconds')),
        `${min(campo(t, 'min_seconds'))} – ${min(campo(t, 'max_seconds'))}`,
        n(giri.filter((g) => campo(g, 'sos_type_id') === t.id).length),
      ])),
    h3('Gli ultimi venti giri'),
    tabella(['Quando', 'Esito', 'Durata prevista', 'Completato'],
      giri
        .slice()
        .sort((a, b) => String(campo(b, 'created_at', 'started_at') || '').localeCompare(String(campo(a, 'created_at', 'started_at') || '')))
        .slice(0, 20)
        .map((g) => [
          istante(campo(g, 'created_at', 'started_at')),
          esc(nomeEsito.get(campo(g, 'outcome_id')) || '<span class="muto">non chiuso</span>'),
          min(campo(g, 'planned_seconds')),
          campo(g, 'completed') === false ? '<span class="ko">interrotto</span>' : 'sì',
        ]), { seVuota: 'Nessun giro.' }),
  );
}

// ── 🏦 Conti bancari e profilo ─────────────────────────────────────────────
async function sezConti() {
  const conti = await righe('cm_bank_connections', { limit: 200 });
  // ⚠️ cm_sync_log NON ha created_at: porta started_at/finished_at (nasce come
  // ca_sync_log in 20260718110000). Il nome si cerca fra quelli possibili e, se
  // non c'è, si legge senza ordinare — una colonna indovinata fa fallire la
  // lettura intera con un 400, cioè perde la sezione per un ordinamento.
  const colSync = primaCol('cm_sync_log', 'started_at', 'finished_at', 'created_at', 'synced_at');
  const sync  = (await righe('cm_sync_log', { limit: 500, ...(colSync ? { order: `${colSync}.desc` } : {}) })) || [];
  const prof  = ((await righe('cm_profile', { limit: 5 })) || [])[0];
  const disp  = (await righe('cm_push_devices', { limit: 50 })) || [];

  const ultimoSync = new Map();
  for (const s of sync) {
    const k = campo(s, 'bank_connection_id');
    if (k && !ultimoSync.has(k)) ultimoSync.set(k, s);
  }

  sezione('conti', '🏦', 'Conti, profilo e telefoni',
    conti && conti.length ? h3('I conti collegati') : null,
    conti && conti.length
      ? tabella(['Conto', 'Banca', 'A cosa serve', 'Ultimo aggiornamento'],
          conti.map((c) => {
            const s = ultimoSync.get(c.id);
            return [
              esc(campo(c, 'display_name') || '<span class="muto">da battezzare</span>'),
              esc(campo(c, 'aspsp_name', 'institution_name') || '—'),
              esc((campo(c, 'uses') || []).join(', ') || '<span class="muto">nessun uso</span>'),
              s ? istante(campo(s, 'finished_at', 'started_at', 'created_at')) : '<span class="muto">mai</span>',
            ];
          }))
      : null,
    prof ? h3('Scheda personale') : null,
    prof
      ? elenco([
          ['Nome', esc([campo(prof, 'nome'), campo(prof, 'cognome')].filter(Boolean).join(' ') || '—')],
          ['Data di nascita', giorno(campo(prof, 'data_nascita'))],
          ['Sesso', esc(campo(prof, 'sesso') || '—')],
          ['Altezza', campo(prof, 'altezza_cm') != null ? `${n(campo(prof, 'altezza_cm'))} cm` : null],
        ])
      : null,
    disp.length ? nota(`Telefoni registrati per le notifiche push: <strong>${n(disp.filter((d) => campo(d, 'enabled') !== false).length)}</strong> attivi su ${n(disp.length)}.`) : null,
  );
}

// ── 📊 Inventario ──────────────────────────────────────────────────────────
// Le sezioni raccontano quello che si sa leggere; questa dice **tutto quello
// che c'è**, tabella per tabella. È la rete che impedisce a una tabella nuova
// di restare invisibile nella relazione finché qualcuno non la aggiunge a mano.
let INVENTARIO = [];

async function sezInventario() {
  const nomi = Object.keys(SCHEMA).sort();
  for (const t of nomi) {
    const q = await quante(t);
    INVENTARIO.push({ tabella: t, righe: q, mie: ha(t, 'user_id') });
  }
  const conRighe = INVENTARIO.filter((r) => (r.righe || 0) > 0);

  sezione('inventario', '📊', 'Inventario del database',
    kpi([
      { etichetta: 'Tabelle',        valore: n(INVENTARIO.length) },
      { etichetta: 'Con dei dati',   valore: n(conRighe.length) },
      { etichetta: 'Righe in tutto', valore: n(somma(INVENTARIO, (r) => r.righe)) },
    ]),
    nota('Dove la tabella ha una colonna <code>user_id</code> il conteggio è delle sole righe di Salvatore; dove non ce l\'ha, sono tutte (le tabelle figlie ereditano il proprietario dal padre).'),
    tabella(['Tabella', 'Righe', 'Filtrate per utente'],
      conRighe.sort((a, b) => (b.righe || 0) - (a.righe || 0)).map((r) => [
        `<code>${esc(r.tabella)}</code>`, n(r.righe), r.mie ? 'sì' : '<span class="muto">no</span>',
      ])),
    h3('Tabelle vuote'),
    tabella(['Tabella'],
      INVENTARIO.filter((r) => (r.righe || 0) === 0).map((r) => [`<code>${esc(r.tabella)}</code>`]),
      { seVuota: 'Nessuna tabella vuota.' }),
  );
}

// ── 🏠 Panoramica ──────────────────────────────────────────────────────────
function sezPanoramica(utente) {
  let dump = null;
  try { dump = INFO_DUMP ? JSON.parse(INFO_DUMP) : null; } catch (e) { guaio('scheda del dump', e); }

  sezione('panoramica', '🏠', 'Panoramica',
    kpi([
      { etichetta: 'Data del backup', valore: giorno(oggiISO) },
      { etichetta: 'Tabelle',         valore: n(INVENTARIO.length) },
      { etichetta: 'Righe in tutto',  valore: n(somma(INVENTARIO, (r) => r.righe)) },
      dump ? { etichetta: 'Peso del dump', valore: peso((dump.file || []).reduce((t, f) => t + (f.bytes || 0), 0)) } : null,
    ]),
    elenco([
      ['Utente',   esc(utente.email)],
      ['Progetto', `<code>${esc(BASE.replace(/^https?:\/\//, ''))}</code>`],
      ['Generata', istante(new Date().toISOString())],
    ]),
    dump && dump.file && dump.file.length ? h3('I file del dump') : null,
    dump && dump.file && dump.file.length
      ? tabella(['File', 'Peso', 'SHA-256'],
          dump.file.map((f) => [`<code>${esc(f.nome)}</code>`, peso(f.bytes), `<code class="hash">${esc((f.sha256 || '').slice(0, 16))}…</code>`]))
      : null,
    nota('⚠️ <strong>Questa relazione non contiene i dati riservati.</strong> Le schede di Memo, i gruppi di Events Log e i task marcati 🙈 riservato si contano e non si elencano: la modalità nascosta esiste perché non si leggano di sfuggita. Nel <strong>dump</strong> ci sono per forza — quello è un backup, non una lettura.'),
  );
}

// ── La pagina ──────────────────────────────────────────────────────────────
const ORDINE = ['panoramica', 'punteggi', 'tasks', 'abituati', 'obiettivi', 'eventi', 'peso',
                'calorie', 'memo', 'finanza', 'spese', 'spuntiamola', 'tafiri', 'sos',
                'conti', 'inventario'];

function pagina(utente) {
  sezioni.sort((a, b) => {
    const ia = ORDINE.indexOf(a.id), ib = ORDINE.indexOf(b.id);
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
  });

  const indice = sezioni.map((s) => `<li><a href="#${s.id}">${s.emoji} ${esc(s.titolo)}</a></li>`).join('');
  const corpo  = sezioni
    .map((s) => `<section id="${s.id}"><h2>${s.emoji} ${esc(s.titolo)}</h2>${s.html}</section>`)
    .join('\n');

  const diagnostica = guai.length
    ? `<section id="guai"><h2>⚠️ Cosa non è stato letto</h2>${tabella(['Dove', 'Errore'], guai.map((g) => [esc(g.dove), `<code>${esc(g.msg)}</code>`]))}</section>`
    : '';

  return `<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<!-- ⚠️ Questa pagina si legge dentro un <iframe srcdoc> in relazione.html, e
     un documento srcdoc SENZA base eredita l'indirizzo della pagina che lo
     contiene: un «#tasks» dell'indice si risolverebbe allora in
     «…/relazione.html#tasks» e l'iframe **navigherebbe** lì invece di
     scorrere — cioè caricherebbe relazione.html dentro sé stessa, senza
     JavaScript (la sandbox non lo concede), restando per sempre su «Carico…».
     Con questa base i collegamenti dell'indice tornano a essere quello che
     sono: salti dentro lo stesso documento. -->
<base href="about:srcdoc">
<title>Relazione AppSphere ${VERSIONE} — ${giorno(oggiISO)}</title>
<style>
  /* I caratteri di sistema sul telefono sono grandi: niente altezze fisse,
     soglie in rem e non in px, e le tabelle scorrono dentro il loro riquadro. */
  :root{
    --blu:#0081C8; --scuro:#1F2937; --muto:#6B7280; --bordo:#E5E7EB;
    --fondo:#F9FAFB; --carta:#FFFFFF; --ok:#00967A; --ko:#E74C3C;
  }
  @media (prefers-color-scheme: dark){
    :root{ --scuro:#E5E7EB; --muto:#9CA3AF; --bordo:#374151; --fondo:#111827; --carta:#1F2937; }
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--fondo);color:var(--scuro);
       font:16px/1.55 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;
       overflow-wrap:anywhere}
  header{background:var(--blu);color:#fff;padding:1.25rem 1rem}
  header h1{margin:0;font-size:1.4rem;line-height:1.25}
  header p{margin:.35rem 0 0;opacity:.9;font-size:.9rem}
  .guscio{max-width:60rem;margin:0 auto;padding:1rem}
  nav{background:var(--carta);border:1px solid var(--bordo);border-radius:.75rem;padding:.75rem 1rem;margin:1rem 0}
  nav ul{list-style:none;margin:0;padding:0;display:flex;flex-wrap:wrap;gap:.4rem .9rem}
  nav a{color:var(--blu);text-decoration:none;font-size:.92rem}
  nav a:hover{text-decoration:underline}
  section{background:var(--carta);border:1px solid var(--bordo);border-radius:.75rem;
          padding:1rem 1.1rem;margin:0 0 1rem}
  h2{margin:.1rem 0 .9rem;font-size:1.2rem;border-bottom:2px solid var(--blu);padding-bottom:.4rem}
  h3{margin:1.4rem 0 .5rem;font-size:1rem;color:var(--muto);text-transform:uppercase;letter-spacing:.04em}
  .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(9rem,1fr));gap:.6rem;margin:.6rem 0 1rem}
  .kpi{background:var(--fondo);border:1px solid var(--bordo);border-radius:.6rem;
       padding:.65rem .75rem;display:flex;flex-direction:column;gap:.15rem;min-height:4.2rem}
  .kpi-v{font-size:1.25rem;font-weight:700;line-height:1.2}
  .kpi-e{font-size:.8rem;color:var(--muto)}
  .kpi-s{font-size:.72rem;color:var(--muto);opacity:.8}
  .scroll{overflow-x:auto;-webkit-overflow-scrolling:touch;margin:.4rem 0}
  table{border-collapse:collapse;width:100%;font-size:.88rem}
  th,td{text-align:left;padding:.45rem .6rem;border-bottom:1px solid var(--bordo);vertical-align:top}
  th{background:var(--fondo);font-weight:600;white-space:nowrap;position:sticky;top:0}
  tbody tr:hover td{background:var(--fondo)}
  dl{display:grid;grid-template-columns:repeat(auto-fit,minmax(14rem,1fr));gap:.5rem 1rem;margin:.5rem 0}
  dt{font-size:.78rem;color:var(--muto);text-transform:uppercase;letter-spacing:.03em}
  dd{margin:0 0 .5rem;font-weight:600}
  code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.85em;
       background:var(--fondo);padding:.1rem .3rem;border-radius:.25rem}
  .hash{font-size:.75em}
  .ok{color:var(--ok);font-weight:600}
  .ko{color:var(--ko);font-weight:600}
  .muto{color:var(--muto)}
  .vuoto,.nota,.coda{color:var(--muto);font-size:.87rem;margin:.5rem 0}
  .nota{border-left:3px solid var(--blu);padding-left:.7rem}
  footer{color:var(--muto);font-size:.8rem;text-align:center;padding:1rem 0 2rem}
  @media print{ body{background:#fff} section{break-inside:avoid;border:0;padding:0} nav{display:none} }
</style>
</head>
<body>
<header>
  <div class="guscio" style="padding:0">
    <h1>Relazione AppSphere</h1>
    <p>Fotografia del ${giorno(oggiISO)} · ${esc(utente.email)} · relazione ${VERSIONE}</p>
  </div>
</header>
<div class="guscio">
  <nav><ul>${indice}</ul></nav>
  ${corpo}
  ${diagnostica}
  <footer>Generata da <code>scripts/backup-report.mjs</code> ${VERSIONE} il ${istante(new Date().toISOString())}.</footer>
</div>
</body>
</html>
`;
}

// ── Il giro ────────────────────────────────────────────────────────────────
async function main() {
  const fs = await import('node:fs/promises');

  console.log(`backup-report ${VERSIONE}`);
  console.log('▶ schema…');
  await caricaSchema();

  console.log('▶ utente…');
  const utente = await trovaUtente();
  UID = utente.id;
  console.log(`  ${utente.email} → ${UID}`);

  console.log('▶ vocabolari…');
  await vocabolari();

  // Ogni sezione è indipendente: una che salta non porta giù le altre. Una
  // relazione parziale che dice cosa manca vale più di nessuna relazione.
  const pezzi = [
    ['punteggi',    sezPunteggi],
    ['tasks',       sezTasks],
    ['abituati',    sezAbituati],
    ['obiettivi',   sezObiettivi],
    ['eventi',      sezEventi],
    ['peso',        sezPeso],
    ['calorie',     sezCalorie],
    ['memo',        sezMemo],
    ['finanza',     sezFinanza],
    ['spese',       sezSpese],
    ['spuntiamola', sezSpuntiamola],
    ['tafiri',      sezTaFiri],
    ['sos',         sezSos],
    ['conti',       sezConti],
    ['inventario',  sezInventario],
  ];
  for (const [nome, fn] of pezzi) {
    console.log(`▶ ${nome}…`);
    try { await fn(); } catch (e) { guaio(`sezione ${nome}`, e); }
  }

  sezPanoramica(utente);

  await fs.writeFile(OUT, pagina(utente), 'utf8');
  const st = await fs.stat(OUT);
  console.log(`✔ ${OUT} — ${peso(st.size)}, ${sezioni.length} sezioni, ${guai.length} avvisi`);
}

main().catch((e) => {
  console.error('backup-report: ' + (e && e.stack ? e.stack : e));
  process.exit(1);
});
