import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const VERSION = "1.2.0";
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

// Attesa massima per l'aggiornamento prezzi preliminare. get-prices fa scraping
// su più fonti e può durare minuti: se sfora si prosegue comunque con i prezzi
// già in fnz_price_history, meglio uno snapshot con prezzi vecchi che nessuno.
const PRICES_TIMEOUT_MS = 5 * 60 * 1000;

type Row = Record<string, any>;

function log(level: string, message: string, data?: unknown) {
  console.log(JSON.stringify({ timestamp: new Date().toISOString(), level, message, ...(data === undefined ? {} : { data }) }));
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

function num(value: unknown): number {
  const n = typeof value === "number" ? value : parseFloat(String(value));
  return Number.isFinite(n) ? n : 0;
}

// Data di oggi in Europa/Roma. Il job gira alle 23:00 locali: usare la data UTC
// darebbe il giorno giusto lo stesso, ma solo per coincidenza del fuso — meglio
// essere espliciti, così resta corretto anche se l'orario del cron cambia.
function romeToday(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit",
  }).format(new Date());
}

// ── Logica di calcolo ──────────────────────────────────────────────────────
// Dalla v1.2.0 questa è l'UNICA implementazione dello snapshot: finanza.html non
// ne costruisce più uno suo, lo chiede qui col JWT dell'utente (vedi chiChiama).
// ⚠️ La pagina tiene però una gemella di portfolioStats / computeLoanValue /
// computePricesFromHistory per la dashboard dal vivo: se una di quelle cambia là,
// va cambiata anche qui, o lo snapshot direbbe un numero diverso da quello a schermo.
//
// ⚠️ Posizioni (quantità e costo medio) e liquidità NON sono più qui: le danno
// le RPC `fnz_posizioni` / `fnz_liquidita` (v1.1.0,
// `20260924140000_fnz_posizioni_liquidita.sql`), le stesse che legge
// finanza.html. Qui resta il valore: quantità × prezzo.

// Ultimo prezzo noto per simbolo, dedotto da fnz_price_history.
function pricesFromHistory(priceHistory: Row[]): Record<string, number> {
  const grouped: Record<string, Row[]> = {};
  for (const h of priceHistory) (grouped[h.symbol] ||= []).push(h);

  const prices: Record<string, number> = {};
  for (const [symbol, entries] of Object.entries(grouped)) {
    entries.sort((a, b) => String(a.id).localeCompare(String(b.id)));
    entries.sort((a, b) => String(a.price_date).localeCompare(String(b.price_date)));
    const last = entries[entries.length - 1];
    if (last) prices[symbol] = num(last.price);
  }
  return prices;
}

type Holding = {
  product: Row; qty: number; avgCost: number; costBasis: number;
  currentPrice: number | null; currentValue: number | null;
  pnl: number | null; pnlPct: number | null;
};

// Le posizioni di un portafoglio, dalle righe di `fnz_posizioni` (già nell'ordine
// della prima transazione), valorizzate coi prezzi.
function computeHoldings(
  positions: Row[], products: Map<string, Row>, prices: Record<string, number>, portfolioId: string,
): Holding[] {
  const out: Holding[] = [];
  for (const r of positions) {
    if (r.scope !== "portfolio" || r.scope_id !== portfolioId) continue;
    const product = products.get(r.product_id);
    if (!product) continue;
    const qty = num(r.qty);
    const costBasis = num(r.cost_basis);
    const avgCost = qty > 0 ? costBasis / qty : 0;
    const currentPrice = product.symbol in prices ? prices[product.symbol] : null;
    const currentValue = currentPrice != null ? qty * currentPrice : null;
    const pnl = currentValue != null ? currentValue - costBasis : null;
    const pnlPct = costBasis > 0 && pnl != null ? (pnl / costBasis) * 100 : null;
    out.push({ product, qty, avgCost, costBasis, currentPrice, currentValue, pnl, pnlPct });
  }
  return out;
}

type PortfolioStat = {
  portfolio: Row; holdings: Holding[]; totalValue: number; cash: number;
  totalCost: number; pnl: number; pnlPct: number;
};

function portfolioStats(
  portfolios: Row[], positions: Row[], products: Map<string, Row>, prices: Record<string, number>,
  cashOf: Map<string, number>,
): PortfolioStat[] {
  return portfolios.map((p) => {
    const own = (num(p.ownership_percentage) || 100) / 100;
    const holdings = computeHoldings(positions, products, prices, p.id);
    const titoli = holdings.reduce((s, x) => s + (x.currentValue ?? x.costBasis), 0) * own;
    const cash = (cashOf.get(p.id) ?? 0) * own;
    const totalCost = holdings.reduce((s, x) => s + x.costBasis, 0) * own;
    // P&L sui soli titoli: la liquidità non guadagna né perde, e sommarla al valore senza
    // sommarla al costo la farebbe comparire come utile il giorno che entra sul conto.
    const pnl = titoli - totalCost;
    return {
      portfolio: p, holdings, totalValue: titoli + cash, cash, totalCost, pnl,
      pnlPct: totalCost > 0 ? (pnl / totalCost) * 100 : 0,
    };
  });
}

// Debito residuo di un mutuo o prestito, quota di competenza inclusa.
function computeLoanValue(loan: Row, loanRepayments: Row[]): number {
  const today = new Date();
  const own = (num(loan.ownership_percentage) || 100) / 100;

  if (loan.type === "MUTUO") {
    const capitale = num(loan.capitale);
    const rata = num(loan.rata);
    const totaleRate = parseInt(String(loan.totale_rate), 10);
    const start = new Date(loan.giorno_prima_rata);

    let mesiPagati = (today.getFullYear() - start.getFullYear()) * 12 + (today.getMonth() - start.getMonth());
    mesiPagati = Math.max(0, Math.min(mesiPagati, totaleRate));

    // Tasso implicito della rata: Newton-Raphson sul valore attuale dell'annualità.
    let rate = 0.01;
    const tol = 1e-7;
    for (let i = 0; i < 1000; i++) {
      const f = rata * (1 - Math.pow(1 + rate, -totaleRate)) / rate - capitale;
      const df = (-rata * (1 - Math.pow(1 + rate, -totaleRate)) / (rate * rate)) +
                 (rata * totaleRate * Math.pow(1 + rate, -totaleRate - 1) / rate);
      const newRate = rate - f / df;
      if (Math.abs(newRate - rate) < tol) { rate = newRate; break; }
      rate = newRate;
    }

    if (Math.abs(rate) < 1e-8) return (capitale - rata * mesiPagati) * own;
    return (capitale * Math.pow(1 + rate, mesiPagati) - rata * (Math.pow(1 + rate, mesiPagati) - 1) / rate) * own;
  }

  // PRESTITO: capitale che matura interesse composto, ridotto dai rimborsi.
  const C = num(loan.capitale);
  const i = num(loan.interesse) / 100;
  const todayStr = today.toISOString().slice(0, 10);
  const repayments = loanRepayments
    .filter((r) => r.loan_id === loan.id && r.date <= todayStr)
    .slice()
    .sort((a, b) => String(a.date).localeCompare(String(b.date)) || String(a.created_at).localeCompare(String(b.created_at)));

  let balance = C;
  let cursorStr = loan.data_inizio_prestito;
  for (const r of repayments) {
    const days = Math.max(0, (+new Date(r.date) - +new Date(cursorStr)) / 86400000);
    balance = balance * Math.pow(1 + i, days / 365.25) - num(r.amount);
    cursorStr = r.date;
  }
  const daysToToday = Math.max(0, (+new Date(todayStr) - +new Date(cursorStr)) / 86400000);
  return balance * Math.pow(1 + i, daysToToday / 365.25) * own;
}

type SnapshotPayload = {
  topLevel: { patrimonio_netto: number; portafogli_totali: number; asset_totali: number; debiti_totali: number };
  details: { portfolios: Row[]; loans: Row[]; other_assets: Row[] };
};

function buildSnapshotPayload(
  portfolios: Row[], positions: Row[], products: Map<string, Row>, prices: Record<string, number>,
  loans: Row[], loanRepayments: Row[], otherAssets: Row[], cashOf: Map<string, number>,
): SnapshotPayload {
  const stats = portfolioStats(portfolios, positions, products, prices, cashOf);
  const grandValue = stats.reduce((s, x) => s + x.totalValue, 0);
  const totalLoans = loans.reduce((s, l) => s + (computeLoanValue(l, loanRepayments) || 0), 0);
  // danaro_rosa è escluso dai totali del patrimonio (come in finanza.html)
  const visibleAssets = otherAssets.filter((a) => a.asset_type !== "danaro_rosa");
  const totalOther = visibleAssets.reduce((s, a) => s + num(a.value) * (num(a.ownership_percentage) || 100) / 100, 0);

  const portfolioDetails = stats.map(({ portfolio: p, holdings, totalValue, cash, totalCost, pnl, pnlPct }) => {
    const own = (num(p.ownership_percentage) || 100) / 100;
    return {
      id: p.id, name: p.name, color: p.color,
      ownership_pct: num(p.ownership_percentage) || 100,
      total_value: totalValue,
      total_value_full: own > 0 ? totalValue / own : totalValue,
      // Quanto del valore è liquidità non investita: senza, uno snapshot storico non lascia
      // capire perché il portafoglio valeva quella cifra pur avendo pochi titoli.
      cash,
      total_cost: totalCost, pnl, pnl_pct: pnlPct,
      holdings: holdings.map((hh) => ({
        symbol: hh.product.symbol, name: hh.product.name, asset_type: hh.product.asset_type,
        qty: hh.qty, avg_cost: hh.avgCost, cost_basis: hh.costBasis,
        current_price: hh.currentPrice, current_value: hh.currentValue,
        current_value_quota: hh.currentValue != null ? hh.currentValue * own : null,
        pnl: hh.pnl, pnl_pct: hh.pnlPct,
      })),
    };
  });

  const loanDetails = loans.map((l) => {
    const value = computeLoanValue(l, loanRepayments);
    const own = (num(l.ownership_percentage) || 100) / 100;
    return {
      id: l.id, type: l.type, name: l.name || (l.type === "MUTUO" ? "Mutuo" : "Prestito"),
      ownership_pct: num(l.ownership_percentage) || 100,
      residual_value: value,
      residual_value_full: own > 0 ? value / own : value,
    };
  });

  const assetDetails = visibleAssets.map((a) => {
    const own = (num(a.ownership_percentage) || 100) / 100;
    return {
      id: a.id, title: a.title, asset_type: a.asset_type,
      value: num(a.value),
      ownership_pct: num(a.ownership_percentage) || 100,
      ownership_value: num(a.value) * own,
      valuation_date: a.valuation_date,
    };
  });

  return {
    topLevel: {
      patrimonio_netto: grandValue + totalOther - totalLoans,
      portafogli_totali: grandValue,
      asset_totali: grandValue + totalOther,
      debiti_totali: totalLoans,
    },
    details: { portfolios: portfolioDetails, loans: loanDetails, other_assets: assetDetails },
  };
}

// ── Chi chiama ─────────────────────────────────────────────────────────────
// ⚠️ Fino alla v1.1.0 qui non si controllava niente: con la sola chiave pubblica
// chiunque poteva far girare lo snapshot di tutti gli utenti (e l'aggiornamento
// prezzi, che fa scraping per minuti). Ora passano due chiamanti e basta:
//   • il SERVICE ROLE (il job pg_cron delle 23:00) — tutti gli utenti;
//   • un UTENTE col suo JWT (finanza.html all'apertura e dal 💾) — il suo solo
//     snapshot, e senza aggiornare i prezzi.
// La firma la verifica la piattaforma (verify_jwt resta acceso su questa funzione,
// come su notification-action): qui arriva solo un JWT buono, e se ne legge il ruolo.
// La chiave anon è un JWT buono col ruolo `anon`, ed è lei che resta fuori.
function chiChiama(req: Request): { serviceRole: boolean; userId: string | null } {
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  // La stessa chiave che la funzione ha nei suoi segreti: il riconoscimento più diretto.
  const chiave = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (chiave && token === chiave) return { serviceRole: true, userId: null };
  try {
    const b64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const payload = JSON.parse(atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4)));
    return {
      serviceRole: payload.role === "service_role",
      userId: payload.role === "authenticated" && typeof payload.sub === "string" ? payload.sub : null,
    };
  } catch {
    return { serviceRole: false, userId: null };
  }
}

// ── Handler ────────────────────────────────────────────────────────────────

serve(async (req) => {
  const startTime = Date.now();
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const chi = chiChiama(req);
  if (!chi.serviceRole && !chi.userId) {
    return json({ error: { message: "Serve un login valido" } }, 401);
  }
  // Un utente scrive il suo solo snapshot, e i prezzi non li aggiorna: quello è il
  // lavoro del job notturno, e da un'apertura di pagina durerebbe minuti.
  const soloUtente = chi.serviceRole ? null : chi.userId;

  let body: Row = {};
  try { body = await req.json(); } catch { /* corpo vuoto: va bene */ }
  if (soloUtente) body.skipPrices = true;

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) {
    return json({ error: "Server configuration error: missing Supabase env vars" }, 500);
  }
  const supabase = createClient(supabaseUrl, serviceRoleKey);

  // ── 1. Aggiornamento prezzi preliminare ─────────────────────────────────
  let pricesResult: unknown = "skipped";
  if (body.skipPrices !== true) {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), PRICES_TIMEOUT_MS);
      const res = await fetch(`${supabaseUrl}/functions/v1/get-prices`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${serviceRoleKey}` },
        body: "{}",
        signal: controller.signal,
      });
      clearTimeout(timer);
      pricesResult = res.ok ? await res.json() : `HTTP ${res.status}`;
      log("INFO", "get-prices completato", { pricesResult });
    } catch (err) {
      // Timeout o errore di rete: si prosegue con i prezzi già in archivio.
      pricesResult = `error: ${err instanceof Error ? err.message : String(err)}`;
      log("WARN", "get-prices non completato, snapshot con i prezzi esistenti", { pricesResult });
    }
  }

  // Legge una tabella per intero, a pagine. PostgREST tronca le risposte oltre
  // un certo numero di righe: senza paginazione una tabella cresciuta abbastanza
  // (transazioni, storico prezzi) arriverebbe monca e lo snapshot risulterebbe
  // sbagliato senza che nulla segnali l'errore.
  const PAGE = 1000;
  const fetchAll = async (table: string, columns: string, orderBy: string): Promise<Row[]> => {
    const out: Row[] = [];
    for (let from = 0; ; from += PAGE) {
      const { data, error } = await supabase
        .from(table).select(columns).order(orderBy, { ascending: true }).range(from, from + PAGE - 1);
      if (error) throw new Error(`${table}: ${error.message}`);
      const page = (data ?? []) as Row[];
      out.push(...page);
      if (page.length < PAGE) return out;
    }
  };

  // ── 2. Caricamento dati (service role: vede tutti gli utenti) ───────────
  try {
    const [portfolios, products, priceHistory, loans, loanRepayments, otherAssets] = await Promise.all([
      fetchAll("fnz_portfolios", "id,user_id,name,color,ownership_percentage", "id"),
      fetchAll("fnz_products", "id,symbol,name,asset_type", "id"),
      fetchAll("fnz_price_history", "id,symbol,price,price_date", "id"),
      fetchAll("fnz_loans", "*", "id"),
      fetchAll("fnz_loan_repayments", "loan_id,date,amount,created_at", "id"),
      fetchAll("fnz_other_assets", "id,user_id,title,asset_type,value,valuation_date,ownership_percentage", "id"),
    ]);
    log("INFO", "Dati caricati", {
      portfolios: portfolios.length, products: products.length,
      priceHistory: priceHistory.length, loans: loans.length, otherAssets: otherAssets.length,
    });

    const productMap = new Map<string, Row>(products.map((p: Row) => [p.id, p]));
    const prices = pricesFromHistory(priceHistory);

    // ── 3. Uno snapshot per ogni utente che ha dati di Finanza ─────────────
    const userIds = new Set<string>();
    for (const p of portfolios) userIds.add(p.user_id);
    for (const l of loans) userIds.add(l.user_id);
    for (const a of otherAssets) userIds.add(a.user_id);
    if (soloUtente) {
      const presente = userIds.has(soloUtente);
      userIds.clear();
      if (presente) userIds.add(soloUtente);
    }

    const snapshotDate = romeToday();
    const written: Row[] = [];

    for (const userId of userIds) {
      const userPortfolios = portfolios.filter((p: Row) => p.user_id === userId);
      // Posizioni e liquidità dal database, ristrette all'utente: il service role non ha
      // RLS, ed è `p_user` a fare da filtro. ⚠️ Se non rispondono lo snapshot di quell'utente
      // NON si scrive: coi portafogli a zero sarebbe una fotografia falsa in archivio.
      const [posRes, liqRes] = await Promise.all([
        supabase.rpc("fnz_posizioni", { p_user: userId }),
        supabase.rpc("fnz_liquidita", { p_user: userId }),
      ]);
      if (posRes.error || liqRes.error) {
        const message = (posRes.error || liqRes.error)!.message;
        log("ERROR", "Posizioni non lette, snapshot saltato", { userId, message });
        written.push({ user_id: userId, ok: false, error: `posizioni: ${message}` });
        continue;
      }
      const userPositions = (posRes.data ?? []) as Row[];
      const cashOf = new Map<string, number>(
        ((liqRes.data ?? []) as Row[]).map((r) => [r.portfolio_id, num(r.cash)]),
      );
      const userLoans = loans.filter((l: Row) => l.user_id === userId);
      const loanIds = new Set(userLoans.map((l: Row) => l.id));
      const userRepayments = loanRepayments.filter((r: Row) => loanIds.has(r.loan_id));
      const userAssets = otherAssets.filter((a: Row) => a.user_id === userId);

      const { topLevel, details } = buildSnapshotPayload(
        userPortfolios, userPositions, productMap, prices, userLoans, userRepayments, userAssets,
        cashOf,
      );

      const { error } = await supabase.from("fnz_dashboard_snapshots").upsert({
        user_id: userId,
        snapshot_date: snapshotDate,
        patrimonio_netto: topLevel.patrimonio_netto,
        portafogli_totali: topLevel.portafogli_totali,
        asset_totali: topLevel.asset_totali,
        debiti_totali: topLevel.debiti_totali,
        details,
        updated_at: new Date().toISOString(),
      }, { onConflict: "user_id,snapshot_date" });

      if (error) {
        log("ERROR", "Upsert snapshot fallito", { userId, message: error.message });
        written.push({ user_id: userId, ok: false, error: error.message });
        continue;
      }
      log("INFO", "Snapshot salvato", { userId, snapshotDate, ...topLevel });
      written.push({ user_id: userId, ok: true, ...topLevel });
    }

    return json({
      message: "OK", version: VERSION, snapshot_date: snapshotDate,
      users: written.length, written, prices: pricesResult,
      totalTimeMs: Date.now() - startTime,
    });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    log("ERROR", "save-snapshot fallita", { error: msg });
    return json({ error: msg }, 500);
  }
});
