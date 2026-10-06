/* ══════════════ SPESE FAMIGLIA — regole e vista in sola lettura ══════════════
   Un file solo per tre pagine, e l'unica ragione per cui non è dentro un HTML:
   fino alla v1.0.0 di questo file le stesse regole stavano scritte tre volte —
   un blocco identico in finanza.html e in situazione-teresa.html, e una terza
   copia in cost-analysis.html — con l'avviso di cambiarle insieme.

   Due parti, e le usano pagine diverse:

   1. SF — le REGOLE, funzioni pure che ricevono i dati invece di leggerli da
      uno stato globale: quali movimenti contano nei totali, come si somma una
      categoria con le sue sotto-categorie, i periodi, la spesa per mese e per
      persona. Le usano tutt'e tre le pagine.
   2. CA / ca*() — la VISTA in sola lettura (dashboard + transazioni) di
      finanza.html e situazione-teresa.html. cost-analysis.html non la usa:
      ha la sua, con import, categorie e regole.

   Dipendenze dalla pagina che lo carica, lette solo quando servono (la vista
   parte da caOpen, mai al caricamento del file): SUPABASE_URL,
   SUPABASE_ANON_KEY, tok(), Chart.js e le classi .card / .card-title /
   .kpi-* / .mono / .pos / .neg / .text-muted / .text-sm / .fw-700.
   Le classi .ca-* invece le porta questo file (caStili), una volta sola.
   ═══════════════════════════════════════════════════════════════════════════ */
var SF_VERSION = 'v1.0.0';

var SF = (function () {
  // La data si scrive coi campi LOCALI e non con toISOString(), che converte in
  // UTC: la mezzanotte italiana del primo del mese lì dentro è l'ultimo giorno
  // del mese prima. Fino a questo file cost-analysis.html la scriveva proprio
  // così, e il suo «Questo mese» cominciava un giorno prima degli altri due.
  function isoLocale(d) {
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }

  // I periodi della dashboard, come coppia di date ISO (null = nessun limite).
  function periodo(p) {
    var now = new Date(), y = now.getFullYear(), m = now.getMonth(), from = null, to = null;
    if (p === 'month') { from = new Date(y, m, 1); to = new Date(y, m + 1, 0); }
    else if (p === 'prevMonth') { from = new Date(y, m - 1, 1); to = new Date(y, m, 0); }
    else if (p === '3m') { from = new Date(y, m - 2, 1); to = new Date(y, m + 1, 0); }
    else if (p === 'year') { from = new Date(y, 0, 1); to = new Date(y, 11, 31); }
    return { from: from ? isoLocale(from) : null, to: to ? isoLocale(to) : null };
  }

  function nelPeriodo(t, r) {
    return (!r.from || t.date >= r.from) && (!r.to || t.date <= r.to);
  }

  function catById(cats, id) {
    for (var i = 0; i < cats.length; i++) if (cats[i].id === id) return cats[i];
    return null;
  }

  // Risale dalla sotto-categoria alla principale, per aggregare i totali.
  function catPrincipale(cats, id) {
    var c = catById(cats, id);
    if (!c) return id;
    return c.parent_id || c.id;
  }

  // Un movimento è escluso dai totali se una delle sue categorie (o la
  // principale, per le sotto-categorie) ha excluded_from_totals — giroconti,
  // pagamenti della carta di credito, ricariche: movimenti veri che non devono
  // contare come spesa o entrata.
  function escluso(cats, catIds) {
    return catIds.some(function (cid) {
      var top = catById(cats, catPrincipale(cats, cid));
      return !!(top && top.excluded_from_totals);
    });
  }

  // I movimenti su cui ragiona la dashboard: quelli del periodo, meno gli esclusi.
  // catIdsOf(id) → gli id di categoria di quel movimento: ogni pagina tiene
  // l'associazione a modo suo, e la regola non deve sapere come.
  function movimentiDashboard(txs, cats, catIdsOf, p) {
    var r = periodo(p);
    return txs.filter(function (t) { return nelPeriodo(t, r) && !escluso(cats, catIdsOf(t.id)); });
  }

  // Totale di una categoria (con o senza le sue sotto-categorie) nel periodo.
  // Si conta a parte da movimentiDashboard perché «Ricarica» ha di solito
  // excluded_from_totals, ma questo riquadro esiste proprio per mostrarla.
  function totaleCategoria(txs, cats, catIdsOf, p, categoryId, conFiglie) {
    if (!categoryId) return 0;
    var r = periodo(p);
    return txs.filter(function (t) {
      if (!nelPeriodo(t, r)) return false;
      var ids = catIdsOf(t.id);
      if (!ids.length) return false;
      return conFiglie ? ids.some(function (cid) { return catPrincipale(cats, cid) === categoryId; }) : ids.indexOf(categoryId) >= 0;
    }).reduce(function (s, t) { return s + Math.abs(t.amount); }, 0);
  }

  // Le tre cifre del riquadro «Ricarica»: la categoria principale RICARICA con
  // le sue figlie, e le due figlie che nel nome portano SALVATORE e TERESA.
  function ricariche(txs, cats, catIdsOf, p) {
    var top = cats.filter(function (c) { return !c.parent_id && c.name.toUpperCase() === 'RICARICA'; })[0];
    var figlie = top ? cats.filter(function (c) { return c.parent_id === top.id; }) : [];
    function perNome(n) { return figlie.filter(function (c) { return c.name.toUpperCase().indexOf(n) >= 0; })[0]; }
    var sal = perNome('SALVATORE'), ter = perNome('TERESA');
    return {
      totale: totaleCategoria(txs, cats, catIdsOf, p, top && top.id, true),
      salvatore: totaleCategoria(txs, cats, catIdsOf, p, sal && sal.id, false),
      teresa: totaleCategoria(txs, cats, catIdsOf, p, ter && ter.id, false),
    };
  }

  // La torta mostra le sole categorie principali: un movimento con una
  // sotto-categoria si somma alla sua principale. subTotals tiene il dettaglio
  // per etichetta effettiva, sotto la principale a cui appartiene.
  // Restituisce le fette già ordinate, dalla più grande: [{ id, val }], con
  // '__none__' per quel che non ha categoria.
  function totaliCategorie(spese, cats, catIdsOf) {
    var totals = {}, subTotals = {};
    spese.forEach(function (t) {
      var ids = catIdsOf(t.id), v = Math.abs(t.amount);
      if (!ids.length) { totals.__none__ = (totals.__none__ || 0) + v; return; }
      var tops = [];
      ids.forEach(function (cid) { var top = catPrincipale(cats, cid); if (tops.indexOf(top) < 0) tops.push(top); });
      tops.forEach(function (cid) { totals[cid] = (totals[cid] || 0) + v; });
      ids.forEach(function (cid) {
        var top = catPrincipale(cats, cid);
        if (!subTotals[top]) subTotals[top] = {};
        subTotals[top][cid] = (subTotals[top][cid] || 0) + v;
      });
    });
    var fette = Object.keys(totals).map(function (id) { return { id: id, val: totals[id] }; })
      .sort(function (a, b) { return b.val - a.val; });
    return { fette: fette, subTotals: subTotals };
  }

  // La spesa degli ultimi `mesi` mesi, mese in corso compreso: [{ mese, val }],
  // dove `mese` è il primo del mese come Date.
  function spesaMensile(txs, cats, catIdsOf, mesi) {
    var now = new Date(), out = [];
    for (var i = (mesi || 6) - 1; i >= 0; i--) {
      var d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      var from = isoLocale(d), to = isoLocale(new Date(d.getFullYear(), d.getMonth() + 1, 0));
      var val = txs.filter(function (t) {
        return t.amount < 0 && t.date >= from && t.date <= to && !escluso(cats, catIdsOf(t.id));
      }).reduce(function (s, t) { return s + Math.abs(t.amount); }, 0);
      out.push({ mese: d, val: Number(val.toFixed(2)) });
    }
    return out;
  }

  // Spesa per persona (per chi è la spesa, `person_id`), dalla più alta:
  // [{ id, val }], con '__none__' per i movimenti non assegnati.
  function spesaPerPersona(spese) {
    var totals = {};
    spese.forEach(function (t) { var k = t.person_id || '__none__'; totals[k] = (totals[k] || 0) + Math.abs(t.amount); });
    return Object.keys(totals).map(function (id) { return { id: id, val: totals[id] }; })
      .sort(function (a, b) { return b.val - a.val; });
  }

  return {
    isoLocale: isoLocale, periodo: periodo, nelPeriodo: nelPeriodo,
    catPrincipale: catPrincipale, escluso: escluso,
    movimentiDashboard: movimentiDashboard, totaleCategoria: totaleCategoria, ricariche: ricariche,
    totaliCategorie: totaliCategorie, spesaMensile: spesaMensile, spesaPerPersona: spesaPerPersona,
  };
})();

/* ══════════════ La vista in sola lettura (finanza + situazione-teresa) ══════
   Dashboard + transazioni di Spese Famiglia (cost-analysis.html), senza
   scritture: import, categorie, persone, regole e conti restano nell'app
   completa. I conti passano da SF, qui sopra. */
const CA = {
  loaded: false,
  tab: 'dashboard',
  txView: 'list',
  period: 'month',
  categories: [],
  people: [],
  transactions: [],
  txCatIdx: new Map(),   // transaction_id -> [category_id]
  charts: {},
  chartTopIds: [],       // id categoria principale (o '__none__') nell'ordine delle fette
  chartSubTotals: {},    // { topId: { categoryId: importo } }
  filters: { text: '', categoryId: '', personId: '', spenderId: '', from: '', to: '', amountFrom: '', amountTo: '', type: '', sortBy: 'title' },
};

/* ── Caricamento dati ─────────────────────────────────────────── */
// PostgREST limita a 1000 righe una select senza range: pagina finché
// non arriva una pagina più corta di quella richiesta.
async function caFetchAll(table, order) {
  const PAGE = 1000;
  let rows = [], offset = 0;
  while (true) {
    const qs = `select=*${order ? '&order=' + order : ''}&offset=${offset}&limit=${PAGE}`;
    const r = await fetch(`${SUPABASE_URL}/rest/v1/${table}?${qs}`, {
      headers: { 'apikey': SUPABASE_ANON_KEY, 'Authorization': `Bearer ${tok()}` },
    });
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).message || r.statusText);
    const data = await r.json();
    rows = rows.concat(data);
    if (data.length < PAGE) break;
    offset += PAGE;
  }
  return rows;
}

async function caLoad() {
  const [categories, people, transactions, txCategories] = await Promise.all([
    caFetchAll('ca_categories', 'name.asc'),
    caFetchAll('ca_people', 'name.asc'),
    caFetchAll('ca_transactions', 'date.desc'),
    caFetchAll('ca_transaction_categories'),
  ]);
  CA.categories = categories;
  CA.people = people;
  CA.transactions = transactions;
  CA.txCatIdx = new Map();
  txCategories.forEach(tc => {
    const ids = CA.txCatIdx.get(tc.transaction_id);
    if (ids) ids.push(tc.category_id);
    else CA.txCatIdx.set(tc.transaction_id, [tc.category_id]);
  });
  CA.loaded = true;
}

/* ── Helper ───────────────────────────────────────────────────── */
const caEUR = v => (v || 0).toLocaleString('it-IT', { style: 'currency', currency: 'EUR' });
const caDate = iso => { if (!iso) return ''; const [y, m, d] = iso.split('-'); return `${d}/${m}/${y}`; };
const caEsc = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const caCatById = id => CA.categories.find(c => c.id === id);
const caCatLabel = c => (c && c.icon ? c.icon + ' ' : '') + (c ? c.name : '?');
const caTopCats = () => CA.categories.filter(c => !c.parent_id);
const caChildCats = parentId => CA.categories.filter(c => c.parent_id === parentId);
const caTxCatIds = txId => CA.txCatIdx.get(txId) || [];

// Il colore appartiene solo alle categorie principali: le sotto-categorie
// ereditano sempre quello della categoria padre.
function caCatColor(c) {
  if (!c) return '#9CA3AF';
  if (c.parent_id) { const p = caCatById(c.parent_id); return (p && p.color) || '#9CA3AF'; }
  return c.color || '#9CA3AF';
}

const caTopCatId = id => SF.catPrincipale(CA.categories, id);

function caCatFullName(id) {
  const c = caCatById(id);
  if (!c) return '';
  if (!c.parent_id) return c.name;
  const p = caCatById(c.parent_id);
  return p ? `${p.name} > ${c.name}` : c.name;
}

// Esclusi dai totali (giroconti, carta di credito, ricariche): la regola è SF.escluso.
const caIsExcluded = t => SF.escluso(CA.categories, caTxCatIds(t.id));

// Gli stili .ca-* stanno qui e non nelle due pagine, che ne portavano due copie
// identiche: si aggiungono una volta sola, alla prima apertura della vista.
function caStili() {
  if (document.getElementById('ca-stili')) return;
  const st = document.createElement('style');
  st.id = 'ca-stili';
  st.textContent = `
.ca-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.ca-title { font-size: 1.3rem; font-weight: 700; display: flex; align-items: center; gap: 10px; }
.ca-tabs { display: flex; gap: 6px; }
.ca-tab { padding: 8px 14px; border-radius: var(--radius-sm); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-muted); font-family: var(--font-sans); font-size: .85rem; font-weight: 600; cursor: pointer; }
.ca-tab.active { background: var(--c-primary); border-color: var(--c-primary); color: #fff; }
.ca-note { font-size: .8rem; color: var(--c-muted); margin-bottom: 16px; }
.ca-sub { font-size: .72rem; color: var(--c-muted); margin-top: 6px; display: flex; justify-content: space-between; gap: 8px; }
.ca-charts { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 20px; align-items: start; }
.ca-canvas-wrap { position: relative; height: 300px; }
.ca-bar-row { margin-bottom: 10px; }
.ca-bar-info { display: flex; justify-content: space-between; gap: 10px; font-size: .84rem; margin-bottom: 4px; }
.ca-bar { height: 8px; border-radius: 99px; background: var(--c-border); overflow: hidden; }
.ca-bar-fill { height: 100%; border-radius: 99px; }
.ca-bar-row.ca-clickable { cursor: pointer; }
.ca-filters { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 10px; align-items: end; }
.ca-field { display: flex; flex-direction: column; gap: 4px; }
.ca-field > span { font-size: .72rem; font-weight: 600; color: var(--c-muted); }
.ca-input, .ca-select { width: 100%; padding: 9px 11px; border: 1px solid var(--c-border); border-radius: var(--radius-sm); background: var(--c-bg); color: var(--c-text); font-family: var(--font-sans); font-size: .85rem; }
.ca-tx { border: 1px solid var(--c-border); border-radius: var(--radius); background: var(--c-surface); padding: 12px 14px; margin-bottom: 8px; }
.ca-tx-top { display: flex; justify-content: space-between; gap: 12px; align-items: baseline; }
.ca-tx-desc { font-weight: 600; font-size: .9rem; word-break: break-word; }
.ca-tx-amount { font-family: var(--font-mono); font-weight: 700; white-space: nowrap; }
.ca-tx-meta { font-size: .76rem; color: var(--c-muted); margin-top: 3px; }
.ca-tx-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.ca-chip { display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: 99px; font-size: .74rem; font-weight: 600; background: var(--c-bg); border: 1px solid var(--c-border); }
.ca-chip-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.ca-group { margin-bottom: 18px; }
.ca-group-head { font-weight: 700; font-size: .9rem; margin-bottom: 8px; display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.ca-group-count { font-size: .74rem; color: var(--c-muted); font-weight: 600; }
.ca-empty { text-align: center; padding: 40px; color: var(--c-muted); font-size: .88rem; }
@media (max-width: 900px) { .ca-charts { grid-template-columns: 1fr; } }`;
  document.head.appendChild(st);
}

/* ── Shell + navigazione interna ──────────────────────────────── */
async function caOpen(container) {
  if (!container) return;
  caStili();
  if (!CA.loaded) {
    container.innerHTML = '<div class="ca-empty">Caricamento Spese Famiglia…</div>';
    try { await caLoad(); }
    catch (e) {
      container.innerHTML = `<div class="ca-empty">Impossibile caricare i dati di Spese Famiglia.<br>${caEsc(e.message)}</div>`;
      return;
    }
  }
  container.innerHTML = `
    <div class="ca-head">
      <h2 class="ca-title">🧾 Spese Famiglia</h2>
      <div class="ca-tabs">
        <button class="ca-tab" id="ca-tab-btn-dashboard" onclick="caSetTab('dashboard')">📊 Dashboard</button>
        <button class="ca-tab" id="ca-tab-btn-transazioni" onclick="caSetTab('transazioni')">💳 Transazioni</button>
      </div>
    </div>
    <p class="ca-note">Sola consultazione: qui i dati non si modificano.</p>
    <div id="ca-body"></div>`;
  caRenderTab();
}

function caSetTab(tab) {
  CA.tab = tab;
  caRenderTab();
}

function caRenderTab() {
  ['dashboard', 'transazioni'].forEach(t => {
    const btn = document.getElementById(`ca-tab-btn-${t}`);
    if (btn) btn.classList.toggle('active', CA.tab === t);
  });
  if (CA.tab === 'transazioni') caRenderTransazioni();
  else caRenderDashboard();
}

function caDestroyCharts() {
  Object.keys(CA.charts).forEach(k => { try { CA.charts[k].destroy(); } catch {} delete CA.charts[k]; });
}

/* ── Dashboard ────────────────────────────────────────────────── */
const caDashTransactions = () => SF.movimentiDashboard(CA.transactions, CA.categories, caTxCatIds, CA.period);

function caRenderDashboard() {
  const body = document.getElementById('ca-body');
  if (!body) return;
  caDestroyCharts();

  const txs = caDashTransactions();
  const spese = txs.filter(t => t.amount < 0);
  const totSpeso = spese.reduce((s, t) => s + Math.abs(t.amount), 0);
  const uncategorized = txs.filter(t => !caTxCatIds(t.id).length).length;

  const ric = SF.ricariche(CA.transactions, CA.categories, caTxCatIds, CA.period);

  const periodOpts = [
    ['month', 'Questo mese'], ['prevMonth', 'Mese scorso'], ['3m', 'Ultimi 3 mesi'],
    ['year', "Quest'anno"], ['all', 'Tutto'],
  ].map(([v, l]) => `<option value="${v}"${CA.period === v ? ' selected' : ''}>${l}</option>`).join('');

  body.innerHTML = `
    <div class="ca-head" style="justify-content:flex-end">
      <select class="ca-select" style="width:auto" onchange="caSetPeriod(this.value)">${periodOpts}</select>
    </div>
    <div class="kpi-grid">
      <div class="kpi-card"><div class="kpi-label">Speso</div><div class="kpi-value mono neg">${caEUR(-totSpeso)}</div></div>
      <div class="kpi-card">
        <div class="kpi-label">Ricarica</div>
        <div class="kpi-value mono">${caEUR(ric.totale)}</div>
        <div class="ca-sub">
          <span>Salvatore <span class="mono">${caEUR(ric.salvatore)}</span></span>
          <span>Teresa <span class="mono">${caEUR(ric.teresa)}</span></span>
        </div>
      </div>
      <div class="kpi-card"><div class="kpi-label">Transazioni</div><div class="kpi-value mono">${txs.length}</div></div>
      <div class="kpi-card"><div class="kpi-label">Da categorizzare</div><div class="kpi-value mono">${uncategorized}</div></div>
    </div>
    <div class="card">
      <div class="card-title">Spesa per categoria</div>
      <div class="ca-charts">
        <div class="ca-canvas-wrap"><canvas id="ca-cat-chart"></canvas></div>
        <div id="ca-cat-detail" class="text-muted text-sm">Clicca su una categoria per vedere il dettaglio delle sotto-categorie.</div>
      </div>
    </div>
    <div class="card">
      <div class="card-title">Andamento mensile</div>
      <div class="ca-canvas-wrap" style="height:220px"><canvas id="ca-month-chart"></canvas></div>
    </div>
    <div class="card">
      <div class="card-title">Spesa per persona</div>
      <div id="ca-person-breakdown"></div>
    </div>`;

  caRenderCatChart(spese);
  caRenderMonthChart();
  caRenderPersonBreakdown(spese);
}

function caSetPeriod(period) {
  CA.period = period;
  caRenderDashboard();
}

// Il grafico mostra solo le categorie principali: l'importo di una transazione
// taggata con una sotto-categoria viene sommato alla sua categoria padre.
function caRenderCatChart(spese) {
  const { fette, subTotals } = SF.totaliCategorie(spese, CA.categories, caTxCatIds);

  const labels = [], data = [], colors = [], topIds = [];
  fette.forEach(({ id: cid, val }) => {
    if (cid === '__none__') { labels.push('Da categorizzare'); colors.push('#9CA3AF'); }
    else { const c = caCatById(cid); labels.push(c ? caCatLabel(c) : '?'); colors.push(caCatColor(c)); }
    data.push(Number(val.toFixed(2)));
    topIds.push(cid);
  });
  CA.chartTopIds = topIds;
  CA.chartSubTotals = subTotals;

  const ctx = document.getElementById('ca-cat-chart');
  if (!ctx || !labels.length || typeof Chart === 'undefined') return;
  CA.charts.cat = new Chart(ctx, {
    type: 'doughnut',
    data: { labels, datasets: [{ data, backgroundColor: colors }] },
    options: {
      maintainAspectRatio: false,
      plugins: {
        legend: {
          position: 'bottom',
          labels: { boxWidth: 14, padding: 12 },
          // Di default cliccare la legenda nasconde la fetta: qui apre
          // invece lo stesso dettaglio sotto-categorie della fetta.
          onClick: (evt, item) => caOnCatChartClick(CA.chartTopIds[item.index]),
        },
      },
      onClick: (evt, elements) => { if (elements.length) caOnCatChartClick(CA.chartTopIds[elements[0].index]); },
    },
  });
}

function caOnCatChartClick(topId) {
  const panel = document.getElementById('ca-cat-detail');
  if (!panel) return;
  if (!topId || topId === '__none__') {
    panel.innerHTML = '<div class="text-muted text-sm">Le transazioni "Da categorizzare" non hanno sotto-categorie.</div>';
    return;
  }
  const top = caCatById(topId);
  const subMap = CA.chartSubTotals[topId] || {};
  const rows = [];
  // L'importo taggato direttamente sulla categoria principale compare come "(diretto)".
  if (subMap[topId]) rows.push({ label: caCatLabel(top) + ' (diretto)', val: subMap[topId], color: caCatColor(top) });
  caChildCats(topId).forEach(s => { if (subMap[s.id]) rows.push({ label: caCatLabel(s), val: subMap[s.id], color: caCatColor(s) }); });
  if (!rows.length) { panel.innerHTML = '<div class="text-muted text-sm">Nessuna sotto-categoria per questa categoria nel periodo.</div>'; return; }
  rows.sort((a, b) => b.val - a.val);
  const max = Math.max(...rows.map(r => r.val));
  panel.innerHTML = `<div class="fw-700" style="margin-bottom:10px">${caEsc(caCatLabel(top))}</div>` + rows.map(r => `
    <div class="ca-bar-row">
      <div class="ca-bar-info"><span>${caEsc(r.label)}</span><span class="mono">${caEUR(-r.val)}</span></div>
      <div class="ca-bar"><div class="ca-bar-fill" style="width:${max ? r.val / max * 100 : 0}%;background:${r.color}"></div></div>
    </div>`).join('');
}

function caRenderMonthChart() {
  const mesi = SF.spesaMensile(CA.transactions, CA.categories, caTxCatIds, 6);
  const labels = mesi.map(m => m.mese.toLocaleDateString('it-IT', { month: 'short', year: '2-digit' }));
  const data = mesi.map(m => m.val);
  const ctx = document.getElementById('ca-month-chart');
  if (!ctx || typeof Chart === 'undefined') return;
  CA.charts.month = new Chart(ctx, {
    type: 'bar',
    data: { labels, datasets: [{ label: 'Speso', data, backgroundColor: '#FF3366' }] },
    options: { maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } },
  });
}

function caRenderPersonBreakdown(spese) {
  const container = document.getElementById('ca-person-breakdown');
  if (!container) return;
  const entries = SF.spesaPerPersona(spese).map(e => [e.id, e.val]);
  if (!entries.length) { container.innerHTML = '<div class="text-muted text-sm">Nessun dato nel periodo.</div>'; return; }
  const max = Math.max(...entries.map(e => e[1]));
  container.innerHTML = entries.map(([pid, val]) => {
    const p = pid === '__none__' ? null : CA.people.find(x => x.id === pid);
    const label = p ? p.name : 'Non assegnato';
    const color = p ? (p.color || '#2563EB') : '#9CA3AF';
    return `<div class="ca-bar-row">
      <div class="ca-bar-info"><span>${caEsc(label)}</span><span class="mono">${caEUR(-val)}</span></div>
      <div class="ca-bar"><div class="ca-bar-fill" style="width:${max ? val / max * 100 : 0}%;background:${color}"></div></div>
    </div>`;
  }).join('');
}

/* ── Transazioni ──────────────────────────────────────────────── */
function caFilteredTransactions() {
  const f = CA.filters;
  return CA.transactions.filter(t => {
    if (f.text && !(t.description || '').toLowerCase().includes(f.text.toLowerCase())) return false;
    if (f.categoryId) {
      const ids = caTxCatIds(t.id);
      if (f.categoryId === '__none__') { if (ids.length) return false; }
      else if (!ids.includes(f.categoryId)) return false;
    }
    if (f.personId && t.person_id !== f.personId) return false;
    if (f.spenderId && t.spender_person_id !== f.spenderId) return false;
    if (f.from && t.date < f.from) return false;
    if (f.to && t.date > f.to) return false;
    // Confronto sul segno reale dell'importo (negativo = spesa, positivo = entrata).
    if (f.amountFrom !== '' && t.amount < parseFloat(f.amountFrom)) return false;
    if (f.amountTo !== '' && t.amount > parseFloat(f.amountTo)) return false;
    if (f.type) {
      if (f.type === '__none__') { if (t.type) return false; }
      else if (t.type !== f.type) return false;
    }
    return true;
  });
}

function caRenderTransazioni() {
  const body = document.getElementById('ca-body');
  if (!body) return;
  caDestroyCharts();
  const f = CA.filters;

  const catOptions = ['<option value="">Tutte le categorie</option>', `<option value="__none__">Da categorizzare</option>`]
    .concat(caTopCats().map(c => {
      const kids = caChildCats(c.id).map(k => `<option value="${k.id}">${caEsc(caCatLabel(k))}</option>`).join('');
      return `<optgroup label="${caEsc(c.name)}"><option value="${c.id}">${caEsc(caCatLabel(c))}</option>${kids}</optgroup>`;
    })).join('');
  const peopleOptions = CA.people.map(p => `<option value="${p.id}">${caEsc(p.name)}</option>`).join('');
  const types = [...new Set(CA.transactions.map(t => t.type).filter(Boolean))].sort();
  const typeOptions = types.map(ty => `<option value="${caEsc(ty)}">${caEsc(ty)}</option>`).join('');

  body.innerHTML = `
    <div class="card">
      <div class="ca-filters">
        <input type="text" class="ca-input" id="ca-f-text" placeholder="Cerca descrizione..." value="${caEsc(f.text)}">
        <select class="ca-select" id="ca-f-category">${catOptions}</select>
        <select class="ca-select" id="ca-f-person"><option value="">Tutte (per chi)</option>${peopleOptions}</select>
        <select class="ca-select" id="ca-f-spender"><option value="">Tutti (chi ha speso)</option>${peopleOptions}</select>
        <select class="ca-select" id="ca-f-type"><option value="">Tutti i tipi</option><option value="__none__">Senza tipo</option>${typeOptions}</select>
        <label class="ca-field"><span>Dal</span><input type="date" class="ca-input" id="ca-f-from" value="${caEsc(f.from)}"></label>
        <label class="ca-field"><span>Al</span><input type="date" class="ca-input" id="ca-f-to" value="${caEsc(f.to)}"></label>
        <label class="ca-field"><span>Importo da</span><input type="number" step="0.01" class="ca-input" id="ca-f-amount-from" value="${caEsc(f.amountFrom)}"></label>
        <label class="ca-field"><span>Importo a</span><input type="number" step="0.01" class="ca-input" id="ca-f-amount-to" value="${caEsc(f.amountTo)}"></label>
        <select class="ca-select" id="ca-f-sort">
          <option value="title">Titolo (raggruppate, più frequenti prima)</option>
          <option value="date_desc">Data (più recenti prima)</option>
          <option value="date_asc">Data (meno recenti prima)</option>
          <option value="category">Categoria</option>
          <option value="spender">Chi ha speso</option>
          <option value="type">Tipo</option>
        </select>
      </div>
    </div>
    <div class="ca-head">
      <div class="ca-tabs">
        <button class="ca-tab${CA.txView === 'list' ? ' active' : ''}" onclick="caSetTxView('list')">📋 Elenco</button>
        <button class="ca-tab${CA.txView === 'summary' ? ' active' : ''}" onclick="caSetTxView('summary')">📊 Voci entrate/spese</button>
      </div>
      <div class="text-muted text-sm" id="ca-tx-count"></div>
    </div>
    <div id="ca-tx-body"></div>`;

  // I valori delle select vanno impostati dopo l'innerHTML: un value su
  // <select> nel markup non seleziona nulla se l'opzione arriva da optgroup.
  document.getElementById('ca-f-category').value = f.categoryId;
  document.getElementById('ca-f-person').value = f.personId;
  document.getElementById('ca-f-spender').value = f.spenderId;
  document.getElementById('ca-f-type').value = f.type;
  document.getElementById('ca-f-sort').value = f.sortBy;

  ['text', 'category', 'person', 'spender', 'type', 'from', 'to', 'amount-from', 'amount-to', 'sort'].forEach(k => {
    const el = document.getElementById(`ca-f-${k}`);
    if (el) el.addEventListener(el.tagName === 'SELECT' || el.type === 'date' ? 'change' : 'input', caOnFilterChange);
  });

  caRenderTxBody();
}

function caOnFilterChange() {
  const val = id => { const el = document.getElementById(id); return el ? el.value : ''; };
  CA.filters = {
    text: val('ca-f-text'),
    categoryId: val('ca-f-category'),
    personId: val('ca-f-person'),
    spenderId: val('ca-f-spender'),
    from: val('ca-f-from'),
    to: val('ca-f-to'),
    amountFrom: val('ca-f-amount-from'),
    amountTo: val('ca-f-amount-to'),
    type: val('ca-f-type'),
    sortBy: val('ca-f-sort'),
  };
  caRenderTxBody();
}

function caSetTxView(view) {
  CA.txView = view;
  caRenderTransazioni();
}

function caRenderTxBody() {
  const list = caFilteredTransactions();
  const countEl = document.getElementById('ca-tx-count');
  if (countEl) {
    const noun = list.length === 1 ? 'transazione' : 'transazioni';
    countEl.textContent = list.length === CA.transactions.length
      ? `${list.length} ${noun}`
      : `${list.length} ${noun} su ${CA.transactions.length}`;
  }
  const container = document.getElementById('ca-tx-body');
  if (!container) return;
  if (!list.length) { container.innerHTML = '<div class="ca-empty">Nessuna transazione trovata.</div>'; return; }
  container.innerHTML = CA.txView === 'summary' ? caSummaryHTML(list) : caListHTML(list);
}

function caTxRowHTML(t) {
  const chips = caTxCatIds(t.id).map(cid => {
    const c = caCatById(cid);
    return `<span class="ca-chip"><span class="ca-chip-dot" style="background:${caCatColor(c)}"></span>${caEsc(caCatFullName(cid) || '?')}</span>`;
  });
  if (!chips.length) chips.push('<span class="ca-chip text-muted">Da categorizzare</span>');
  const spender = CA.people.find(p => p.id === t.spender_person_id);
  const person = CA.people.find(p => p.id === t.person_id);
  if (spender) chips.push(`<span class="ca-chip">💳 ${caEsc(spender.name)}</span>`);
  if (person) chips.push(`<span class="ca-chip">👤 ${caEsc(person.name)}</span>`);
  return `
    <div class="ca-tx">
      <div class="ca-tx-top">
        <span class="ca-tx-desc">${caEsc(t.description || '(senza descrizione)')}</span>
        <span class="ca-tx-amount ${t.amount < 0 ? 'neg' : 'pos'}">${caEUR(t.amount)}</span>
      </div>
      <div class="ca-tx-meta">${caDate(t.date)}${t.type ? ' · ' + caEsc(t.type) : ''}</div>
      <div class="ca-tx-tags">${chips.join('')}</div>
    </div>`;
}

// Ordinamenti alternativi: lista piatta, senza raggruppamento per titolo.
function caSortedFlat(list, sortBy) {
  const catKey = t => { const ids = caTxCatIds(t.id); return ids.length ? caCatFullName(ids[0]) : '￿'; }; // non categorizzate in fondo
  const spenderKey = t => { const p = CA.people.find(x => x.id === t.spender_person_id); return p ? p.name : '￿'; };
  return [...list].sort((a, b) => {
    switch (sortBy) {
      case 'date_asc': return a.date.localeCompare(b.date);
      case 'date_desc': return b.date.localeCompare(a.date);
      case 'category': return catKey(a).localeCompare(catKey(b));
      case 'spender': return spenderKey(a).localeCompare(spenderKey(b));
      case 'type': return (a.type || '￿').localeCompare(b.type || '￿');
      default: return 0;
    }
  });
}

// Elenco raggruppato per titolo (descrizione): il gruppo con più
// transazioni dallo stesso titolo viene mostrato per primo, NON in
// ordine alfabetico.
function caListHTML(list) {
  if (CA.filters.sortBy && CA.filters.sortBy !== 'title') {
    return caSortedFlat(list, CA.filters.sortBy).map(caTxRowHTML).join('');
  }
  const groups = {};
  list.forEach(t => {
    const key = (t.description || '').trim() || '(senza descrizione)';
    (groups[key] = groups[key] || []).push(t);
  });
  return Object.entries(groups).sort((a, b) => b[1].length - a[1].length).map(([title, items]) => `
    <div class="ca-group">
      <div class="ca-group-head">${caEsc(title)}<span class="ca-group-count">× ${items.length}</span></div>
      ${[...items].sort((a, b) => b.date.localeCompare(a.date)).map(caTxRowHTML).join('')}
    </div>`).join('');
}

// Riepilogo per voce: entrate e spese separate, una riga per categoria
// (la prima della transazione), dalla voce più consistente alla meno.
function caCategoryTotals(txs) {
  const totals = {};
  txs.forEach(t => {
    const key = caTxCatIds(t.id)[0] || '__none__';
    totals[key] = (totals[key] || 0) + Math.abs(t.amount);
  });
  return Object.entries(totals).map(([id, val]) => ({
    id, val,
    label: id === '__none__' ? 'Da categorizzare' : caCatFullName(id),
    color: id === '__none__' ? '#9CA3AF' : caCatColor(caCatById(id)),
  })).sort((a, b) => b.val - a.val);
}

function caSummaryGroupHTML(title, rows, total) {
  if (!rows.length) return `<div class="card"><div class="card-title">${title}</div><div class="text-muted text-sm">Nessuna transazione.</div></div>`;
  const max = Math.max(...rows.map(r => r.val));
  return `<div class="card">
    <div class="ca-head"><div class="card-title" style="margin-bottom:0">${title}</div><span class="mono fw-700">${caEUR(total)}</span></div>
    ${rows.map(r => `
      <div class="ca-bar-row">
        <div class="ca-bar-info"><span><span class="ca-chip-dot" style="display:inline-block;background:${r.color};margin-right:6px"></span>${caEsc(r.label)}</span><span class="mono">${caEUR(r.val)}</span></div>
        <div class="ca-bar"><div class="ca-bar-fill" style="width:${max ? r.val / max * 100 : 0}%;background:${r.color}"></div></div>
      </div>`).join('')}
  </div>`;
}

function caSummaryHTML(list) {
  const entrate = list.filter(t => t.amount > 0);
  const spese = list.filter(t => t.amount < 0);
  return caSummaryGroupHTML('💰 Entrate', caCategoryTotals(entrate), entrate.reduce((s, t) => s + t.amount, 0))
    + caSummaryGroupHTML('💸 Spese', caCategoryTotals(spese), spese.reduce((s, t) => s + Math.abs(t.amount), 0));
}

