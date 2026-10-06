import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const VERSION = "5.20.0"; // fonte per tipo: BTPi→SoldiOnline, BTP→rendimentibtp, ETF→JustETF(HTML)/Yahoo/Investing, crypto→CoinGecko(batch)+Coinbase, azioni→TD/GoogleFinance
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
      "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const CACHE_TTL_MS = 15 * 60 * 1000;
const TWELVE_DATA_API_KEY = Deno.env.get("TWELVE_DATA_API_KEY");
const TARGET_CURRENCY = "EUR";

// Scarto massimo ammesso rispetto all'ultimo prezzo noto, prima di considerare la
// risposta di una fonte un abbaglio invece di un movimento di mercato.
// Un prezzo può essere insieme plausibile e sbagliato: uno scraper che legge il
// numero accanto a quello giusto risponde 200 e riempie la cache in silenzio, e in
// Finanza → Prezzi la riga resta verde «OK» con dentro un valore falso. Mezzo prezzo
// in un giro non lo fa un'obbligazione, un ETF o un'azione (le crypto sì, e infatti
// sono escluse): quando succede si scarta la risposta e si passa alla fonte dopo.
// Se non ne risponde nessuna resta il prezzo di ieri, e il simbolo si vede come
// «Non oggi» — un buco è visibile, un numero sbagliato no.
const MAX_PRICE_DEVIATION = 0.5;

// Dopo quanto il prezzo in cache smette di essere un metro attendibile e il
// controllo qui sopra si disarma. Senza questa via d'uscita il confronto si
// morde la coda: se in cache finisce un valore sbagliato (o il titolo fa
// davvero un movimento enorme — uno split, un raggruppamento), ogni prezzo
// giusto che arriva dopo diventa «implausibile» rispetto a quello, e il simbolo
// resta congelato per sempre sul numero da correggere.
// Tre giorni non sono un fine settimana: finché una fonte qualsiasi risponde,
// `updated_at` si riscrive ogni ora anche a mercati chiusi, quindi una riga
// vecchia di tre giorni vuol dire che da tre giorni non si scrive niente — cioè
// che siamo esattamente nel caso da sbloccare.
const PREV_PRICE_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000;

// ── Logging ────────────────────────────────────────────────────────────────

type DbEntry = { level: string; message: string; data: unknown; request_id: string };

function log(level: string, message: string, data?: unknown) {
  console.log(JSON.stringify({ timestamp: new Date().toISOString(), level, message, ...(data && { data }) }));
}

function dbLog(entries: DbEntry[], level: string, message: string, data: unknown, requestId: string) {
  if (!["ERROR", "WARN", "INFO"].includes(level)) return;
  entries.push({ level, message: message.substring(0, 500), data: data ?? null, request_id: requestId });
}

async function flushLogs(supabase: ReturnType<typeof createClient>, entries: DbEntry[]) {
  if (entries.length === 0) return;
  const { error } = await supabase.from("fn_logs").insert(entries);
  if (error) console.error("fn_logs insert failed:", error.message);
}

// ── rendimentibtp.it scraper ───────────────────────────────────────────────

function parseRendimentiBtpHtml(html: string): Map<string, number> {
  const prices = new Map<string, number>();

  // Try multiple price column positions (5 or 4) in case table structure varies
  for (const [row] of html.matchAll(/<tr[\s\S]*?<\/tr>/gi)) {
    const isinMatch = row.match(/>(IT\d{10})<\/a>/i);
    if (!isinMatch) continue;
    const isin = isinMatch[1];

    const cells = [...row.matchAll(/<td[^>]*>([\s\S]*?)<\/td>/gi)]
        .map((m) => m[1].replace(/<[^>]+>/g, "").replace(/&nbsp;/g, " ").trim());

    // Try column 5 first (standard), then column 4 as fallback
    for (const colIdx of [5, 4]) {
      if (cells.length <= colIdx) continue;
      const raw = cells[colIdx].replace(",", ".").replace(/[^\d.]/g, "");
      const price = parseFloat(raw);
      if (price > 0 && price < 200) { prices.set(isin, price); break; }
    }
  }
  return prices;
}

// Fetches BTP and BTPi pages from rendimentibtp.it, returns { prices, pageStats }.
async function fetchRendimentiBtpPrices(
  requestId: string,
): Promise<{ prices: Map<string, number>; pageStats: Record<string, number | string> }> {
  const prices = new Map<string, number>();
  const pageStats: Record<string, number | string> = {};
  const headers = { "User-Agent": "Mozilla/5.0 (compatible; GarsalFinanza/1.0)" };

  // Pages to scrape: main (BTP) + BTPi (inflation-linked) + BTP Green
  const pages = [
    "https://www.rendimentibtp.it/",
    "https://www.rendimentibtp.it/btpi/",
    "https://www.rendimentibtp.it/btp-indicizzati/",
    "https://www.rendimentibtp.it/btp-green/",
  ];

  for (const url of pages) {
    const pageKey = url.replace("https://www.rendimentibtp.it", "") || "/";
    try {
      const res = await fetch(url, { headers });
      if (!res.ok) {
        log("WARN", `rendimentibtp.it ${url} HTTP ${res.status}`, { requestId });
        pageStats[pageKey] = `HTTP ${res.status}`;
        continue;
      }
      const html = await res.text();
      const pageMap = parseRendimentiBtpHtml(html);
      for (const [isin, price] of pageMap) prices.set(isin, price);
      log("INFO", `rendimentibtp.it ${url}: ${pageMap.size} BTPs`, { requestId });
      pageStats[pageKey] = pageMap.size;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      log("WARN", `rendimentibtp.it fetch error: ${url}`, { requestId, error: msg });
      pageStats[pageKey] = `error: ${msg}`;
    }
  }

  log("INFO", `rendimentibtp.it total: ${prices.size} BTPs`, {
    requestId, sample: [...prices.keys()].slice(0, 5),
  });
  return { prices, pageStats };
}

// ── Borsa Italiana (Euronext) scraper ─────────────────────────────────────

// Tries MIC codes in order: MOTX (bonds on MOT), ETFP (ETF), MTAA (equities).
// Uses the Euronext AJAX detail endpoint — returns an HTML fragment, no auth required.
// Price is in: <span id="header-instrument-price">102,34</span>
async function fetchBorsaItalianaPrice(
  isin: string, requestId: string, dbEntries: DbEntry[],
): Promise<number | null> {
  const mics = ["MOTX", "ETFP", "MTAA"];
  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml",
    "Accept-Language": "it-IT,it;q=0.9,en;q=0.7",
    "Referer": "https://live.euronext.com/",
  };

  for (const mic of mics) {
    try {
      const url = `https://live.euronext.com/en/ajax/getDetailedQuote/${isin}-${mic}`;
      const res = await fetch(url, { headers });
      if (!res.ok) {
        dbLog(dbEntries, "WARN", `Euronext ${mic} HTTP ${res.status} for ${isin}`, { isin, mic, status: res.status }, requestId);
        continue;
      }
      const html = await res.text();

      const match = html.match(/id="header-instrument-price"[^>]*>\s*([0-9.,]+)/);
      if (!match) {
        const snippet = html.substring(0, 200).replace(/\s+/g, " ").trim();
        dbLog(dbEntries, "WARN", `Euronext ${mic} price not found for ${isin}`, { isin, mic, snippet }, requestId);
        continue;
      }

      const price = parseFloat(match[1].replace(",", "."));
      if (price > 0) {
        log("INFO", `Borsa Italiana (${mic}): ${isin} → ${price}`, { requestId });
        dbLog(dbEntries, "INFO", `Fetched from Euronext ${mic}`, { isin, mic, price }, requestId);
        return price;
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      dbLog(dbEntries, "WARN", `Euronext ${mic} fetch error for ${isin}`, { isin, mic, error: msg }, requestId);
    }
  }
  dbLog(dbEntries, "WARN", `Euronext: no price found for ${isin}`, { isin, mics }, requestId);
  return null;
}

// ── Investing.com scraper ─────────────────────────────────────────────────

// Override simbolo per Twelve Data: ticker generico → "SYMBOL:EXCHANGE" esatto.
// Usato quando TD non trova il ticker senza suffisso exchange.
const TWELVE_DATA_SYMBOL_OVERRIDES: Record<string, string> = {
  "RY4C": "RY4C:XETR", // Ryanair su Xetra (Frankfurt)
};

// Symbol → ticker diretto Yahoo Finance (non richiede ISIN nel DB).
// Usato quando TD richiede piano a pagamento.
// Un ticker scritto qui è una scelta esplicita e vince sulla catena delle fonti,
// che invece indovina lo strumento partendo dall'ISIN. Ci si mette un simbolo
// quando quell'indovinare ha già sbagliato.
const YAHOO_FINANCE_TICKER_MAP: Record<string, string> = {
  "RY4C": "RYA.IR", // Ryanair su Euronext Dublin, quotato in EUR
  // BTP10 su Borsa Italiana, in EUR. Fissato qui dopo due numeri falsi di fila
  // presi raschiando pagine: ≈3 € (la performance dell'API JustETF) e poi 18,70 €
  // quando la quota ne vale ≈157. È lo stesso strumento di `LU1598691217`, ma
  // chiesto per nome invece che cercato per ISIN.
  "BTP10": "BTP10.MI",
  // SMEA, iShares Core MSCI Europe (IE00B4K48X80), su Borsa Italiana in EUR.
  // Fissato perché il prodotto non è registrato come `etf` (come ITAMID), quindi
  // il prezzo veniva chiesto a Twelve Data col simbolo nudo «SMEA»: un nome, non
  // un'identità: nostro, non necessariamente lo stesso strumento su TD.
  "SMEA": "SMEA.MI",
};

// Known symbol → Google Finance exchange + currency.
// Add entries here for stocks not covered by Twelve Data.
const GOOGLE_FINANCE_SYMBOL_MAP: Record<string, { exchange: string; currency: string }> = {
  "RY4C": { exchange: "FRA", currency: "EUR" }, // Ryanair su Frankfurt Xetra (fallback)
};

// Known ISIN → direct Investing.com ETF page URL.
// Add entries here when a new ETF is not reachable via JustETF/Yahoo.
const INVESTING_COM_URLS: Record<string, string> = {
  // ⚠️ DA VERIFICARE: LU1900066033 è CHIP, Amundi MSCI Semiconductors (ex Lyxor),
  // ma lo slug dice «msci-taiwan» — un altro indice, non un altro nome dello stesso.
  // Gli altri quattro URL qui sotto descrivono tutti lo strumento giusto, questo no.
  // Se la pagina è davvero quella dell'ETF Taiwan, questa riga scrive per CHIP il
  // prezzo di un fondo diverso: i due possono stare entro il 50 %, quindi
  // `pushPrice()` non se ne accorgerebbe. Si arriva qui solo se JustETF e Yahoo
  // falliscono entrambi (per CHIP di norma risponde Yahoo, CHIP.SW convertito da
  // CHF), quindi finora non è mai stata la fonte in uso. Aprire l'URL e, se porta
  // al fondo sbagliato, togliere la riga: restano JustETF, Yahoo ed Euronext.
  "LU1900066033": "https://it.investing.com/etfs/lyxor-msci-taiwan",
  "LU1390062831": "https://www.investing.com/etfs/infu",
  "FR0011758085": "https://it.investing.com/etfs/lyxor-ftse-italia-mid-cap",
  "IE00B3F81R35": "https://it.investing.com/etfs/ishares-barclays-euro-corp.-bd-eur",
  "LU1598691217": "https://it.investing.com/etfs/lyxor-euromts-10it-btp-govt-dr-ceur", // BTP10 — Amundi Ita BTP 10y
};

async function fetchInvestingComPrice(
  isin: string, requestId: string, dbEntries: DbEntry[],
): Promise<number | null> {
  const url = INVESTING_COM_URLS[isin];
  if (!url) return null;

  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,*/*",
    "Accept-Language": "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
    "Referer": "https://www.google.com/",
    "Cache-Control": "no-cache",
  };

  try {
    const res = await fetch(url, { headers });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `Investing.com HTTP ${res.status} for ${isin}`, { isin, url, status: res.status }, requestId);
      return null;
    }
    const html = await res.text();

    // Try known patterns from Investing.com ETF pages
    const patterns: RegExp[] = [
      // React/Next.js data attribute (modern layout)
      /data-test="instrument-price-last"[^>]*>\s*([0-9]+[.,][0-9]+)/,
      // Legacy: id="last_last"
      /id="last_last"[^>]*>\s*([0-9]+[.,][0-9]+)/,
      // JSON embedded in page: "last":"106.29" or "price":"106.29"
      /"last"\s*:\s*"?([0-9]+(?:[.,][0-9]+)?)"?/,
      /"last_close"\s*:\s*"?([0-9]+(?:[.,][0-9]+)?)"?/,
      // Span with large text class containing price
      /class="[^"]*text-\d+xl[^"]*"[^>]*>\s*([0-9]+[.,][0-9]+)/,
      // Generic: any span/div right after "Prezzo" or "Price" label
      /(?:Prezzo|Price)[^<]*<[^>]+>\s*([1-9][0-9]{1,4}[.,][0-9]{1,4})/i,
    ];

    for (const pat of patterns) {
      const m = html.match(pat);
      if (m) {
        const price = parseFloat(m[1].replace(",", "."));
        if (price >= 1 && price < 100000) {
          dbLog(dbEntries, "INFO", `Fetched from Investing.com`, { isin, url, price, pat: pat.source.substring(0, 60) }, requestId);
          return price;
        }
      }
    }

    // Log snippet around first price-like number for diagnosis
    const snippet = html.substring(0, 500).replace(/\s+/g, " ").trim();
    dbLog(dbEntries, "WARN", `Investing.com: price not found for ${isin}`, { isin, url, snippet }, requestId);
    return null;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `Investing.com fetch error for ${isin}`, { isin, url, error: msg }, requestId);
    return null;
  }
}

// ── Google Finance scraper ────────────────────────────────────────────────

// Fetches price for stocks listed in GOOGLE_FINANCE_SYMBOL_MAP.
// URL: https://www.google.com/finance/quote/{SYMBOL}:{EXCHANGE}
// Returns { price, currency } or null.
async function fetchGoogleFinancePrice(
  symbol: string, requestId: string, dbEntries: DbEntry[],
): Promise<{ price: number; currency: string } | null> {
  const entry = GOOGLE_FINANCE_SYMBOL_MAP[symbol.toUpperCase()];
  if (!entry) return null;

  const url = `https://www.google.com/finance/quote/${encodeURIComponent(symbol)}:${entry.exchange}`;
  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,*/*",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://www.google.com/finance/",
  };

  try {
    const res = await fetch(url, { headers });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `Google Finance HTTP ${res.status} for ${symbol}`, { symbol, url, status: res.status }, requestId);
      return null;
    }
    const html = await res.text();

    // Most reliable: data-last-price attribute on the quote element
    const attrMatch = html.match(/data-last-price="([0-9]+(?:\.[0-9]+)?)"/);
    if (attrMatch) {
      const price = parseFloat(attrMatch[1]);
      if (price > 0) {
        dbLog(dbEntries, "INFO", `Google Finance price found for ${symbol}`, { symbol, price, exchange: entry.exchange }, requestId);
        return { price, currency: entry.currency };
      }
    }

    // Fallback: JSON patterns embedded in the page
    const jsonPatterns: RegExp[] = [
      /"last_price"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)"?/,
      /"regularMarketPrice"\s*:\s*([0-9]+(?:\.[0-9]+)?)/,
    ];
    for (const pat of jsonPatterns) {
      const m = html.match(pat);
      if (m) {
        const price = parseFloat(m[1]);
        if (price > 0) {
          dbLog(dbEntries, "INFO", `Google Finance price (JSON) for ${symbol}`, { symbol, price, exchange: entry.exchange, pat: pat.source.substring(0, 40) }, requestId);
          return { price, currency: entry.currency };
        }
      }
    }

    // Fallback: price display element (class contains YMlKec)
    const cssMatch = html.match(/class="[^"]*YMlKec[^"]*"[^>]*>([0-9]+[.,][0-9]+)/);
    if (cssMatch) {
      const price = parseFloat(cssMatch[1].replace(",", "."));
      if (price > 0) {
        dbLog(dbEntries, "INFO", `Google Finance price (CSS) for ${symbol}`, { symbol, price, exchange: entry.exchange }, requestId);
        return { price, currency: entry.currency };
      }
    }

    const snippet = html.substring(0, 600).replace(/\s+/g, " ").trim();
    dbLog(dbEntries, "WARN", `Google Finance: price not found for ${symbol}`, { symbol, url, snippet }, requestId);
    return null;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `Google Finance fetch error for ${symbol}`, { symbol, error: msg }, requestId);
    return null;
  }
}

// ── JustETF scraper ───────────────────────────────────────────────────────

// ⚠️ L'endpoint /api/etfs/{ISIN}/performance NON è una fonte di prezzi, e non va
// rimesso davanti a questa funzione. Restituisce la *performance* della quota, non
// la quota: il suo `latestValue` (come i `positions[].value`) è una percentuale, e
// `valuation=NAV` non cambia la natura di quei numeri. Passava indenne il controllo
// `price > 0 && price < 100000` ed è finito in `fnz_price_cache` come se fosse un
// prezzo — il 13 agosto 2026 BTP10 (Amundi Ita BTP 10y, quota ≈157 €) risultava
// quotato poche unità di euro, senza nessun errore da nessuna parte. È la stessa
// trappola già annotata qui sotto per il `latestValue` della pagina HTML: lo stesso
// nome di campo, sulla stessa fonte, vuol dire due cose diverse dal prezzo.

// Scrapes the JustETF HTML profile page (IT locale) for the current price.
// URL: https://www.justetf.com/it/etf-profile.html?isin={ISIN}
// The price "EUR 106,29" is in the "Quotazione" section.
async function fetchJustEtfHtmlPrice(
  isin: string, requestId: string, dbEntries: DbEntry[],
): Promise<number | null> {
  const url = `https://www.justetf.com/it/etf-profile.html?isin=${isin}`;
  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,*/*",
    "Accept-Language": "it-IT,it;q=0.9,en;q=0.7",
    "Referer": "https://www.justetf.com/",
  };

  try {
    const res = await fetch(url, { headers });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `JustETF HTML HTTP ${res.status} for ${isin}`, { isin, status: res.status }, requestId);
      return null;
    }
    const html = await res.text();

    // ── 1. Try JSON patterns embedded in script/data tags ─────────────────
    // NOTE: "latestValue" in the HTML page context refers to performance/TER,
    // NOT the NAV price — do NOT add it here.
    const jsonPatterns = [
      /"latestQuote"\s*:\s*\{\s*"value"\s*:\s*([0-9]+(?:[.,][0-9]+)?)/,
      /"priceSnapshot"\s*:\s*\{\s*"value"\s*:\s*([0-9]+(?:[.,][0-9]+)?)/,
      /"nav"\s*:\s*\{\s*"value"\s*:\s*([0-9]+(?:[.,][0-9]+)?)/,
      /"price"\s*:\s*([0-9]+(?:[.,][0-9]+)?)(?:\s*,\s*"currency"\s*:\s*"EUR")/,
    ];
    for (const pat of jsonPatterns) {
      const m = html.match(pat);
      if (m) {
        const price = parseFloat(m[1].replace(",", "."));
        if (price >= 5 && price < 100000) {
          dbLog(dbEntries, "INFO", `JustETF HTML price (JSON) for ${isin}`, { isin, price, pat: pat.source.substring(0, 60) }, requestId);
          return price;
        }
      }
    }

    // ── 2. Collect all "Quotazione" section windows (~1500 chars each) ───
    // The first occurrence is usually the tab-nav link (has data-testid /
    // onmousedown in context); the actual price heading comes later in the DOM.
    const sectionPatterns: RegExp[] = [
      /EUR\s*(?:(?:<[^>]*>|&nbsp;|&#160;)\s*)*([1-9][0-9]{1,4}[,.][0-9]{1,4})/,
      /([1-9][0-9]{1,4}[,.][0-9]{1,4})\s*(?:(?:<[^>]*>|&nbsp;|&#160;)\s*)*EUR/,
      />\s*([1-9][0-9]{1,4}[,.][0-9]{2,4})\s*</,
    ];

    const htmlLower = html.toLowerCase();
    const sections: string[] = [];
    let searchFrom = 0;
    while (true) {
      const idx = htmlLower.indexOf("quotazione", searchFrom);
      if (idx === -1) break;
      sections.push(html.substring(idx, idx + 1500));
      searchFrom = idx + 1;
    }
    if (sections.length === 0) sections.push(html);

    dbLog(dbEntries, "INFO", `JustETF Quotazione occurrences for ${isin}`, {
      isin, count: sections.length,
      first: sections[0].substring(0, 300).replace(/\s+/g, " ").trim(),
    }, requestId);

    // ── 3. Search each section, skipping nav/attribute contexts ──────────
    for (const section of sections) {
      // Skip occurrences that are clearly inside HTML attributes (nav tabs, links)
      const ctx = section.substring(0, 200);
      if (/data-testid|onmousedown/.test(ctx)) continue;

      for (const pat of sectionPatterns) {
        const m = section.match(pat);
        if (m) {
          const price = parseFloat(m[1].replace(",", "."));
          if (price >= 5 && price < 100000) {
            dbLog(dbEntries, "INFO", `JustETF HTML price found for ${isin}`, { isin, price, pat: pat.source.substring(0, 70) }, requestId);
            return price;
          }
        }
      }
    }

    // Last resort: try full-page EUR+price pattern
    for (const pat of sectionPatterns) {
      const m = html.match(pat);
      if (m) {
        const price = parseFloat(m[1].replace(",", "."));
        if (price >= 5 && price < 100000) {
          dbLog(dbEntries, "INFO", `JustETF HTML price (full-page) for ${isin}`, { isin, price }, requestId);
          return price;
        }
      }
    }

    dbLog(dbEntries, "WARN", `JustETF HTML: price not found for ${isin}`, { isin }, requestId);
    return null;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `JustETF HTML fetch error for ${isin}`, { isin, error: msg }, requestId);
    return null;
  }
}

// ── Yahoo Finance scraper ─────────────────────────────────────────────────

// Searches the ISIN on Yahoo Finance, then fetches the quote.
// Preferred for EU ETFs (LU/IE ISINs): returns price only if currency is EUR.
async function fetchYahooFinanceByIsin(
  isin: string, requestId: string, dbEntries: DbEntry[],
  apiKey: string, rateCache: Map<string, number>,
): Promise<number | null> {
  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "application/json",
    "Accept-Language": "en-US,en;q=0.9",
  };

  try {
    // Step 1: search ISIN
    const searchUrl = `https://query1.finance.yahoo.com/v1/finance/search?q=${encodeURIComponent(isin)}&quotesCount=5&newsCount=0&enableFuzzyQuery=false`;
    const searchRes = await fetch(searchUrl, { headers });
    if (!searchRes.ok) {
      dbLog(dbEntries, "WARN", `Yahoo Finance search HTTP ${searchRes.status} for ${isin}`, { isin, status: searchRes.status }, requestId);
      return null;
    }
    const searchData = await searchRes.json();
    const quotes: Record<string, string>[] = searchData?.quotes ?? [];
    if (!quotes.length) {
      dbLog(dbEntries, "WARN", `Yahoo Finance: no results for ISIN ${isin}`, { isin }, requestId);
      return null;
    }

    // Prefer EUR-denominated exchanges (Borsa Italiana, Euronext Paris, XETRA, Amsterdam, Vienna).
    // EBS (SIX Svizzera) è volutamente escluso: quota in CHF, e se compare prima di una
    // piazza in euro nei risultati la sceglierebbe al posto di quella giusta.
    const eurExchanges = ["MIL", "PAR", "GER", "AMS", "VIE"];
    const best = quotes.find((q) => eurExchanges.includes(q.exchange)) ?? quotes[0];
    const ySymbol = best.symbol;

    // Step 2: get quote
    const quoteUrl = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(ySymbol)}?interval=1d&range=1d`;
    const quoteRes = await fetch(quoteUrl, { headers });
    if (!quoteRes.ok) {
      dbLog(dbEntries, "WARN", `Yahoo Finance quote HTTP ${quoteRes.status} for ${ySymbol}`, { isin, symbol: ySymbol, status: quoteRes.status }, requestId);
      return null;
    }
    const quoteData = await quoteRes.json();
    const meta = quoteData?.chart?.result?.[0]?.meta;
    if (!meta) {
      dbLog(dbEntries, "WARN", `Yahoo Finance: no meta for ${ySymbol}`, { isin, symbol: ySymbol }, requestId);
      return null;
    }

    const price = parseNumber(meta.regularMarketPrice ?? meta.chartPreviousClose);
    const currency = (meta.currency as string ?? "").toUpperCase();

    if (price === null || price <= 0) {
      dbLog(dbEntries, "WARN", `Yahoo Finance: invalid price for ${ySymbol}`, { isin, symbol: ySymbol, price }, requestId);
      return null;
    }
    // Quotazione in valuta diversa: stesso ISIN = stessa classe di quote, cambia solo
    // la piazza di negoziazione, quindi il prezzo convertito al cambio è quello giusto.
    // Prima veniva scartato, e per gli ETF quotati solo fuori dall'area euro (es. CHIP,
    // che Yahoo risolve in CHIP.SW in CHF) significava restare senza prezzo.
    if (currency !== TARGET_CURRENCY) {
      const rate = await getConversionRate(currency, TARGET_CURRENCY, apiKey, requestId, rateCache);
      if (!rate) {
        dbLog(dbEntries, "WARN", `Yahoo Finance: conversione ${currency}→EUR fallita per ${ySymbol}`, { isin, symbol: ySymbol, currency, price }, requestId);
        return null;
      }
      const converted = roundMoney(price * rate);
      dbLog(dbEntries, "INFO", `Fetched from Yahoo Finance (convertito)`, { isin, symbol: ySymbol, currency, price, rate, converted }, requestId);
      return converted;
    }

    dbLog(dbEntries, "INFO", `Fetched from Yahoo Finance`, { isin, symbol: ySymbol, price }, requestId);
    return price;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `Yahoo Finance fetch error for ${isin}`, { isin, error: msg }, requestId);
    return null;
  }
}

// Fetches price directly from Yahoo Finance using a known ticker (e.g. "RYA.IR").
// Does NOT require ISIN. Returns price only if currency is EUR.
async function fetchYahooFinanceByTicker(
  yTicker: string, requestId: string, dbEntries: DbEntry[],
): Promise<number | null> {
  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "application/json",
    "Accept-Language": "en-US,en;q=0.9",
  };
  try {
    const quoteUrl = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(yTicker)}?interval=1d&range=1d`;
    const res = await fetch(quoteUrl, { headers });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `Yahoo Finance direct HTTP ${res.status} for ${yTicker}`, { yTicker, status: res.status }, requestId);
      return null;
    }
    const data = await res.json();
    const meta = data?.chart?.result?.[0]?.meta;
    if (!meta) {
      dbLog(dbEntries, "WARN", `Yahoo Finance direct: no meta for ${yTicker}`, { yTicker }, requestId);
      return null;
    }
    const price = parseNumber(meta.regularMarketPrice ?? meta.chartPreviousClose);
    const currency = ((meta.currency as string) ?? "").toUpperCase();
    if (price === null || price <= 0) {
      dbLog(dbEntries, "WARN", `Yahoo Finance direct: invalid price for ${yTicker}`, { yTicker, price }, requestId);
      return null;
    }
    if (currency !== "EUR") {
      dbLog(dbEntries, "WARN", `Yahoo Finance direct: non-EUR for ${yTicker}`, { yTicker, currency, price }, requestId);
      return null;
    }
    dbLog(dbEntries, "INFO", `Yahoo Finance direct: ${yTicker} → ${price} EUR`, { yTicker, price }, requestId);
    return price;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `Yahoo Finance direct fetch error for ${yTicker}`, { yTicker, error: msg }, requestId);
    return null;
  }
}

// ── SoldiOnline scraper ────────────────────────────────────────────────────

// ISIN-direct URL: https://www.soldionline.it/quotazioni/dettaglio/{ISIN}.html
// Exact price element unknown — tries multiple patterns and logs HTML snippet for diagnosis.
async function fetchSoldiOnlinePrice(
  isin: string, requestId: string, dbEntries: DbEntry[],
): Promise<number | null> {
  const url = `https://www.soldionline.it/quotazioni/dettaglio/${isin}.html`;
  const headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,*/*",
    "Accept-Language": "it-IT,it;q=0.9",
    "Referer": "https://www.soldionline.it/",
  };

  try {
    const res = await fetch(url, { headers });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `SoldiOnline HTTP ${res.status} for ${isin}`, { isin, status: res.status }, requestId);
      return null;
    }
    const html = await res.text();

    // Prices can be integers ("103") or decimals ("102,63") — use (?:[.,]\d+)?
    const patterns = [
      /class="[^"]*pricereal[^"]*"[^>]*>\s*([0-9]+(?:[.,][0-9]+)?)/i,
      /class="[^"]*price[^"]*"[^>]*>\s*([0-9]+(?:[.,][0-9]+)?)/i,
      /class="[^"]*prezzo[^"]*"[^>]*>\s*([0-9]+(?:[.,][0-9]+)?)/i,
      /class="[^"]*quotazione[^"]*"[^>]*>\s*([0-9]+(?:[.,][0-9]+)?)/i,
      /id="[^"]*last[^"]*"[^>]*>\s*([0-9]+(?:[.,][0-9]+)?)/i,
      /"last_price"\s*:\s*"?([0-9]+(?:[.,][0-9]+)?)/i,
      /"price"\s*:\s*"?([0-9]+(?:[.,][0-9]+)?)/i,
    ];

    for (const pat of patterns) {
      const match = html.match(pat);
      if (match) {
        const price = parseFloat(match[1].replace(",", "."));
        if (price > 0 && price < 500) {
          dbLog(dbEntries, "INFO", `Fetched from SoldiOnline`, { isin, price, pattern: pat.source.substring(0, 50) }, requestId);
          return price;
        }
      }
    }

    // Log around the first occurrence of "prezzo" for diagnosis, not the <head>
    const priceKeyIdx = html.search(/prezzo|quotazione|pricereal/i);
    const snippetStart = priceKeyIdx > 0 ? Math.max(0, priceKeyIdx - 100) : 0;
    const snippet = html.substring(snippetStart, snippetStart + 600).replace(/\s+/g, " ").trim();
    dbLog(dbEntries, "WARN", `SoldiOnline: price not found for ${isin}`, { isin, snippetStart, snippet }, requestId);
    return null;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `SoldiOnline fetch error for ${isin}`, { isin, error: msg }, requestId);
    return null;
  }
}

// ── CoinGecko crypto scraper ──────────────────────────────────────────────

// Hardcoded symbol → CoinGecko ID map for common coins (fast path, no extra request).
const COINGECKO_ID_MAP: Record<string, string> = {
  BTC: "bitcoin", ETH: "ethereum", SOL: "solana", BNB: "binancecoin",
  XRP: "ripple", ADA: "cardano", DOGE: "dogecoin", DOT: "polkadot",
  MATIC: "matic-network", AVAX: "avalanche-2", LINK: "chainlink",
  LTC: "litecoin", UNI: "uniswap", ATOM: "cosmos", ALGO: "algorand",
  XLM: "stellar", VET: "vechain", FIL: "filecoin", TRX: "tron",
  NEAR: "near", APT: "aptos", OP: "optimism", ARB: "arbitrum",
  SUI: "sui", SEI: "sei-network", PEPE: "pepe", SHIB: "shiba-inu",
  POL: "polygon-ecosystem-token", // rebrand di MATIC (2024), ID CoinGecko diverso da matic-network
};

const COINGECKO_HEADERS = {
  "User-Agent": "GarsalFinanza/1.0",
  "Accept": "application/json",
};

// Risolve un simbolo non presente in COINGECKO_ID_MAP tramite /search.
async function searchCoinGeckoId(
  symbol: string, requestId: string, dbEntries: DbEntry[],
): Promise<string | null> {
  try {
    const searchUrl = `https://api.coingecko.com/api/v3/search?query=${encodeURIComponent(symbol)}`;
    const res = await fetch(searchUrl, { headers: COINGECKO_HEADERS });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `CoinGecko search HTTP ${res.status} for ${symbol}`, { symbol, status: res.status }, requestId);
      return null;
    }
    const data = await res.json();
    const coins: { id: string; symbol: string }[] = data?.coins ?? [];
    const match = coins.find((c) => c.symbol.toUpperCase() === symbol.toUpperCase()) ?? coins[0];
    return match?.id ?? null;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `CoinGecko search error for ${symbol}`, { symbol, error: msg }, requestId);
    return null;
  }
}

// Prezzi EUR di TUTTE le crypto in una sola chiamata a /simple/price.
// /simple/price accetta più id separati da virgola: una richiesta invece di una
// per simbolo. Il piano free di CoinGecko rate-limita pesantemente gli IP
// condivisi di Supabase Edge, e con una chiamata per crypto i simboli in fondo
// alla lista prendevano 429 in modo sistematico.
async function fetchCryptoPricesBatch(
  symbols: string[], requestId: string, dbEntries: DbEntry[],
): Promise<Map<string, number>> {
  const out = new Map<string, number>();
  if (symbols.length === 0) return out;

  // symbol → coin id (mappa statica, /search solo per i simboli sconosciuti)
  const idBySymbol = new Map<string, string>();
  for (const symbol of symbols) {
    const known = COINGECKO_ID_MAP[symbol.toUpperCase()];
    if (known) { idBySymbol.set(symbol, known); continue; }
    const found = await searchCoinGeckoId(symbol, requestId, dbEntries);
    if (found) idBySymbol.set(symbol, found);
    else dbLog(dbEntries, "WARN", `CoinGecko: no coin ID found for ${symbol}`, { symbol }, requestId);
  }
  if (idBySymbol.size === 0) return out;

  const ids = [...new Set(idBySymbol.values())];
  const priceUrl = `https://api.coingecko.com/api/v3/simple/price?ids=${encodeURIComponent(ids.join(","))}&vs_currencies=eur&include_24hr_change=true`;

  try {
    let res = await fetch(priceUrl, { headers: COINGECKO_HEADERS });
    if (res.status === 429) {
      dbLog(dbEntries, "WARN", `CoinGecko 429 sul batch, retry dopo backoff`, { ids }, requestId);
      await delay(5000);
      res = await fetch(priceUrl, { headers: COINGECKO_HEADERS });
    }
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `CoinGecko batch HTTP ${res.status}`, { ids, status: res.status }, requestId);
      return out;
    }
    const data = await res.json();
    for (const [symbol, coinId] of idBySymbol) {
      const price = parseNumber(data?.[coinId]?.eur);
      if (price === null || price <= 0) {
        dbLog(dbEntries, "WARN", `CoinGecko: prezzo mancante per ${symbol}`, { symbol, coinId }, requestId);
        continue;
      }
      out.set(symbol, price);
    }
    dbLog(dbEntries, "INFO", `Fetched from CoinGecko (batch)`, {
      requested: symbols.length, resolved: idBySymbol.size, withPrice: out.size,
    }, requestId);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `CoinGecko batch error`, { ids, error: msg }, requestId);
  }
  return out;
}

// Seconda fonte per le crypto: spot price di Coinbase, senza chiave e con
// limiti molto più larghi di CoinGecko. Prima non c'era alcun ripiego, quindi
// un 429 di CoinGecko lasciava la crypto senza prezzo per tutto il giro.
async function fetchCoinbaseSpotPrice(
  symbol: string, requestId: string, dbEntries: DbEntry[],
): Promise<number | null> {
  try {
    const url = `https://api.coinbase.com/v2/prices/${encodeURIComponent(symbol.toUpperCase())}-${TARGET_CURRENCY}/spot`;
    const res = await fetch(url, { headers: { "Accept": "application/json" } });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `Coinbase HTTP ${res.status} for ${symbol}`, { symbol, status: res.status }, requestId);
      return null;
    }
    const data = await res.json();
    const price = parseNumber(data?.data?.amount);
    if (price === null || price <= 0) {
      dbLog(dbEntries, "WARN", `Coinbase: prezzo non valido per ${symbol}`, { symbol, price }, requestId);
      return null;
    }
    dbLog(dbEntries, "INFO", `Fetched from Coinbase`, { symbol, price }, requestId);
    return price;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    dbLog(dbEntries, "WARN", `Coinbase error for ${symbol}`, { symbol, error: msg }, requestId);
    return null;
  }
}

// ── Twelve Data helpers ────────────────────────────────────────────────────

type QuoteOutcome =
  | { ok: true; result: Record<string, unknown> }
  | { ok: false; status: number; message: string };

async function fetchTwelveDataQuote(symbol: string, apiKey: string): Promise<QuoteOutcome> {
  try {
    const url = `https://api.twelvedata.com/quote?symbol=${encodeURIComponent(symbol)}&apikey=${apiKey}`;
    const res = await fetch(url, { headers: { "Accept": "application/json" } });
    if (!res.ok) return { ok: false, status: res.status, message: await res.text() };
    const result = await res.json();
    if (result.status === "error") return { ok: false, status: 200, message: result.message ?? "error" };
    return { ok: true, result };
  } catch (err) {
    return { ok: false, status: 0, message: err instanceof Error ? err.message : String(err) };
  }
}

// Resolve ISIN → "SYMBOL:EXCHANGE" via Twelve Data symbol_search (ETFs / foreign stocks)
async function resolveIsinToSymbol(
  isin: string, apiKey: string, requestId: string, dbEntries: DbEntry[],
): Promise<string | null> {
  try {
    const url = `https://api.twelvedata.com/symbol_search?symbol=${encodeURIComponent(isin)}&outputsize=5&apikey=${apiKey}`;
    const res = await fetch(url, { headers: { "Accept": "application/json" } });
    if (!res.ok) {
      dbLog(dbEntries, "WARN", `ISIN symbol_search HTTP ${res.status} for ${isin}`, { isin, status: res.status }, requestId);
      return null;
    }
    const result = await res.json();
    if (result.status === "error" || !Array.isArray(result.data) || result.data.length === 0) {
      dbLog(dbEntries, "WARN", `ISIN not found on Twelve Data: ${isin}`, { isin, apiError: result.message }, requestId);
      return null;
    }
    // XDUB/ISE first: Dublin trades RYA in EUR; LON trades in GBX (pence)
    const euroExchanges = ["MIL", "XMIL", "MTA", "EPA", "ETR", "AMS", "XDUB", "ISE", "LON"];
    const match = result.data.find((d: Record<string, string>) => euroExchanges.includes(d.exchange))
        ?? result.data[0];
    const resolved = `${match.symbol}:${match.exchange}`;
    log("INFO", `ISIN ${isin} → ${resolved}`, { requestId });
    dbLog(dbEntries, "INFO", `ISIN resolved on Twelve Data`, { isin, resolved }, requestId);
    return resolved;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    log("WARN", `ISIN resolve error for ${isin}`, { requestId, error: msg });
    dbLog(dbEntries, "WARN", `ISIN resolve error for ${isin}`, { isin, error: msg }, requestId);
    return null;
  }
}

// ── Handler ────────────────────────────────────────────────────────────────

serve(async (req) => {
  const requestId = crypto.randomUUID();
  const startTime = Date.now();
  const dbEntries: DbEntry[] = [];

  log("INFO", "=== Request started ===", { requestId, method: req.method });

  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    // Body not required — symbols and ISINs are read directly from the products table.
    // Accept empty body or {} from manual trigger and cron scheduler.
    try { await req.json(); } catch { /* intentionally ignored */ }

    if (!TWELVE_DATA_API_KEY) {
      log("ERROR", "TWELVE_DATA_API_KEY not configured", { requestId });
      return json({ error: "Server configuration error: missing TWELVE_DATA_API_KEY" }, 500);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const supabaseServiceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!supabaseUrl || !supabaseServiceRoleKey) {
      return json({ error: "Server configuration error: missing Supabase env vars" }, 500);
    }

    const supabase = createClient(supabaseUrl, supabaseServiceRoleKey);

    // ── Load symbols, ISINs, and asset types from products table ──────────

    const { data: productRows, error: prodError } = await supabase
        .from("fnz_products")
        .select("symbol, isin, asset_type");
    if (prodError) {
      log("ERROR", "Products query failed", { requestId, message: prodError.message });
      dbLog(dbEntries, "ERROR", "Products query failed", { message: prodError.message }, requestId);
      await flushLogs(supabase, dbEntries);
      return json({ error: "Failed to read products", details: prodError.message }, 500);
    }

    const symbolSet = new Set<string>();
    const isinMap: Record<string, string> = {};
    const typeMap: Record<string, string> = {};
    for (const p of productRows ?? []) {
      if (p.symbol) {
        const sym = p.symbol.trim().toUpperCase();
        symbolSet.add(sym);
        if (p.isin) isinMap[sym] = p.isin.trim().toUpperCase();
        if (p.asset_type) typeMap[sym] = p.asset_type.trim().toLowerCase();
      }
    }
    const symbols = [...symbolSet];

    if (symbols.length === 0) {
      dbLog(dbEntries, "WARN", "No products found in table", {}, requestId);
      await flushLogs(supabase, dbEntries);
      return json({ message: "No products to process", version: VERSION });
    }

    // ── Cache check (skip symbols updated within TTL) ──────────────────────

    const staleThreshold = new Date(Date.now() - CACHE_TTL_MS).toISOString();
    const { data: cached, error: cacheError } = await supabase
        .from("fnz_price_cache")
        .select("*")
        .in("symbol", symbols)
        .eq("currency", TARGET_CURRENCY)
        .gt("updated_at", staleThreshold);

    if (cacheError) {
      log("ERROR", "Cache query failed", { requestId, message: cacheError.message });
      dbLog(dbEntries, "ERROR", "Cache query failed", { message: cacheError.message }, requestId);
      await flushLogs(supabase, dbEntries);
      return json({ error: "Failed to read price cache", details: cacheError.message }, 500);
    }

    const cachedMap = new Map((cached ?? []).map((r: Record<string, unknown>) => [r.symbol, r]));
    const toFetch = symbols.filter((s) => !cachedMap.has(s));

    log("INFO", "Cache analysis", { requestId, total: symbols.length, cached: cachedMap.size, toFetch: toFetch.length });
    const toFetchDetail = toFetch.map((s) => {
      const t = typeMap[s] ? `[${typeMap[s]}]` : "";
      return isinMap[s] ? `${s}${t}=${isinMap[s]}` : `${s}${t}`;
    });
    dbLog(dbEntries, "INFO", "Request started", {
      version: VERSION, symbols, cachedCount: cachedMap.size, toFetchCount: toFetch.length,
      isinCount: Object.keys(isinMap).length, toFetchDetail,
    }, requestId);

    // ── Fetch from external sources ────────────────────────────────────────

    if (toFetch.length > 0) {
      const rows: Record<string, unknown>[] = [];
      const rateCache = new Map<string, number>();
      const fetchedSymbols = new Set<string>();

      // Pre-load rendimentibtp.it solo per BTP standard (non BTPi che usano SoldiOnline)
      const needsBtpSource = toFetch.some(
        (s) => typeMap[s] === "bond" && (isinMap[s] ?? "").startsWith("IT") && !s.startsWith("BTPI"),
      );
      let btpPrices: Map<string, number> | null = null;
      if (needsBtpSource) {
        const { prices: fetched, pageStats } = await fetchRendimentiBtpPrices(requestId);
        btpPrices = fetched;
        dbLog(dbEntries, "INFO", "Loaded rendimentibtp.it", { count: btpPrices.size, pageStats }, requestId);
      }

      // Pre-carica in una sola richiesta i prezzi di tutte le crypto da aggiornare.
      const cryptoSymbols = toFetch.filter((s) => typeMap[s] === "crypto");
      const cryptoBatch = await fetchCryptoPricesBatch(cryptoSymbols, requestId, dbEntries);

      // Ultimo prezzo noto, a prescindere dal TTL: è il metro del controllo di
      // plausibilità. `cached` qui non basta — contiene solo le righe ancora fresche,
      // cioè per definizione i simboli che *non* stiamo aggiornando.
      const { data: lastKnown } = await supabase
          .from("fnz_price_cache")
          .select("symbol, price, updated_at")
          .in("symbol", toFetch)
          .eq("currency", TARGET_CURRENCY);
      const prevPrice = new Map<string, number>();
      const tooOld = Date.now() - PREV_PRICE_MAX_AGE_MS;
      for (const r of lastKnown ?? []) {
        const p = parseNumber(r.price);
        if (p === null || p <= 0) continue;
        if (new Date(r.updated_at as string).getTime() < tooOld) continue;
        prevPrice.set(r.symbol as string, p);
      }

      // Secondo metro, per i simboli che in cache non ce l'hanno: l'ultima chiusura
      // in archivio, **esclusa quella di oggi**. Serve a chiudere il buco che si apre
      // ogni volta che una riga di cache viene cancellata a mano per rimediare a un
      // prezzo falso: senza precedente il controllo non ha metro, la prima risposta
      // che arriva passa comunque, e se la fonte sbaglia ancora il valore falso si
      // riscrive da solo — è successo il 13 agosto 2026, BTP10 ripulito la mattina e
      // tornato a 18,70 € nel pomeriggio. La riga di oggi va esclusa perché il trigger
      // su `fnz_price_cache` l'ha appena riscritta con lo stesso valore da giudicare.
      // Si interroga solo per i simboli rimasti senza metro — di norma nessuno, e
      // allora la query non parte proprio. La finestra è larga (90 giorni) di
      // proposito: quando si arriva qui è perché le righe recenti sono state
      // cancellate, e cercarne una negli ultimi giorni troverebbe il vuoto che si
      // sta cercando di riempire. Un prezzo di tre mesi fa è un metro grossolano,
      // ma serve solo a distinguere 157 da 18,70, non a validare il centesimo.
      const senzaMetro = toFetch.filter((s) => !prevPrice.has(s));
      if (senzaMetro.length > 0) {
        const oggi = new Date().toISOString().slice(0, 10);
        const daQuando = new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
        const { data: histRows } = await supabase
            .from("fnz_price_history")
            .select("symbol, price, price_date")
            .in("symbol", senzaMetro)
            .gte("price_date", daQuando)
            .lt("price_date", oggi)
            .order("price_date", { ascending: false });
        for (const r of histRows ?? []) {
          const sym = r.symbol as string;
          if (prevPrice.has(sym)) continue; // ordinate al contrario: la prima è la più recente
          const p = parseNumber(r.price);
          if (p !== null && p > 0) prevPrice.set(sym, p);
        }
        dbLog(dbEntries, "INFO", "Metro dallo storico per i simboli senza cache", {
          senzaMetro, trovati: senzaMetro.filter((s) => prevPrice.has(s)),
        }, requestId);
      }

      // Unico varco da cui un prezzo entra in `rows`: arrotondamento, controllo di
      // plausibilità e log stanno qui invece di essere ricopiati in ognuno dei rami
      // della catena delle fonti. Restituisce false se il prezzo è stato scartato,
      // così il chiamante non lo dà per buono e prova la fonte successiva.
      const pushPrice = (
        symbol: string, price: number, extra: Record<string, unknown> = {},
      ): boolean => {
        const rounded = roundMoney(price);
        const prev = prevPrice.get(symbol);
        const assetType = typeMap[symbol] ?? "";
        if (prev !== undefined && assetType !== "crypto") {
          const deviation = Math.abs(rounded - prev) / prev;
          if (deviation > MAX_PRICE_DEVIATION) {
            log("ERROR", `Prezzo implausibile per ${symbol}: ${rounded} (ultimo noto ${prev})`, { requestId });
            dbLog(dbEntries, "ERROR", `Prezzo implausibile scartato per ${symbol}`, {
              symbol, price: rounded, prev, assetType,
              deviationPct: Math.round(deviation * 100),
            }, requestId);
            return false;
          }
        }
        rows.push({
          symbol, price: rounded,
          prev_close: null, change_amt: null, change_pct: null,
          currency: TARGET_CURRENCY, market_state: null,
          updated_at: new Date().toISOString(),
          ...extra,
        });
        return true;
      };

      // Scrive su fnz_price_cache quello che è stato raccolto finora. Serve
      // chiamarla durante il ciclo e non solo alla fine: il giro dura minuti fra
      // scraping e pause, e con un unico upsert finale un timeout della Edge
      // Function buttava via anche i prezzi già recuperati.
      const flushRows = async () => {
        if (rows.length === 0) return;
        const batch = rows.splice(0, rows.length);
        const { error: upsertError } = await supabase
            .from("fnz_price_cache")
            .upsert(batch, { onConflict: "symbol,currency" })
            .select();
        if (upsertError) {
          log("ERROR", "Cache upsert failed", { requestId, message: upsertError.message });
          dbLog(dbEntries, "ERROR", "Cache upsert failed", { message: upsertError.message, symbols: batch.map((r) => r.symbol) }, requestId);
          return;
        }
        for (const r of batch) {
          cachedMap.set(r.symbol as string, r);
          fetchedSymbols.add(r.symbol as string);
        }
      };

      for (let i = 0; i < toFetch.length; i++) {
        if (rows.length >= 5) await flushRows();
        const symbol = toFetch[i];
        const isin = isinMap[symbol];
        const assetType = typeMap[symbol] ?? "";
        const isBTPi = symbol.startsWith("BTPI");
        const isBond = assetType === "bond";
        const isEtf  = assetType === "etf";
        const isStock = assetType === "stock";
        const isCrypto = assetType === "crypto";

        // Bond/ETF/crypto: salta TD diretto (simboli custom non esistono su TD)
        const useIsinDirectly = !!(isin && (isBond || isEtf)) || isCrypto;

        // Un ticker fissato salta anche Twelve Data, ma solo se per quel simbolo non
        // c'è un override TD: l'override dice *quale* simbolo chiedere a TD ed è a
        // sua volta una scelta esplicita, quindi resta il primo (è il caso di RY4C).
        // Senza override si chiederebbe a TD il simbolo nudo, che è il nome che il
        // titolo ha *da noi*: niente garantisce che su TD indichi lo stesso
        // strumento, e se TD risponde `outcome.ok` è vero e tutta la catena — ticker
        // fissato compreso — non viene nemmeno interpellata. Fra un ticker scelto a
        // mano e una omonimia da verificare, viene prima il primo.
        const tickerFissato = !!YAHOO_FINANCE_TICKER_MAP[symbol]
            && !TWELVE_DATA_SYMBOL_OVERRIDES[symbol];

        let madeApiCall = false;
        let resolvedAs = symbol;

        try {
          // Step 1: TD diretto — solo per azioni (e tipi sconosciuti senza ISIN)
          let outcome: QuoteOutcome;
          if (useIsinDirectly || tickerFissato) {
            outcome = { ok: false, status: 200, message: "skip-td-direct" };
          } else {
            const tdSymbol = TWELVE_DATA_SYMBOL_OVERRIDES[symbol] ?? symbol;
            if (tdSymbol !== symbol) resolvedAs = tdSymbol;
            outcome = await fetchTwelveDataQuote(tdSymbol, TWELVE_DATA_API_KEY);
            madeApiCall = true;
          }

          // Step 2: catena fallback basata su ISIN e tipo strumento

          // 2-pin. Yahoo Finance con ticker fissato a mano: **prima** di tutto il
          // resto. Un ticker in `YAHOO_FINANCE_TICKER_MAP` è una scelta esplicita su
          // quale strumento chiedere, mentre tutta la catena qui sotto lo cerca per
          // ISIN e raschia pagine — cioè indovina. Quando l'indovinare ha già
          // sbagliato per un simbolo non ha senso lasciargli la precedenza: fu così
          // che BTP10, ripulito la mattina del 13 agosto 2026, si riscrisse a 18,70 €
          // nel pomeriggio.
          if (!outcome.ok && YAHOO_FINANCE_TICKER_MAP[symbol]) {
            const yfDirectPrice = await fetchYahooFinanceByTicker(YAHOO_FINANCE_TICKER_MAP[symbol], requestId, dbEntries);
            if (yfDirectPrice !== null && pushPrice(symbol, yfDirectPrice)) {
              dbLog(dbEntries, "INFO", `Fetched ${symbol} from Yahoo Finance (ticker fissato)`, { yTicker: YAHOO_FINANCE_TICKER_MAP[symbol], price: yfDirectPrice }, requestId);
              continue;
            }
          }

          // 2-crypto. Crypto → CoinGecko (batch già scaricato), poi Coinbase
          if (isCrypto) {
            let cryptoPrice = cryptoBatch.get(symbol) ?? null;
            let source = "CoinGecko";
            if (cryptoPrice === null) {
              cryptoPrice = await fetchCoinbaseSpotPrice(symbol, requestId, dbEntries);
              source = "Coinbase";
            }
            if (cryptoPrice !== null) {
              pushPrice(symbol, cryptoPrice, { market_state: "REGULAR" });
              dbLog(dbEntries, "INFO", `Fetched ${symbol} from ${source}`, { price: cryptoPrice }, requestId);
            } else {
              dbLog(dbEntries, "WARN", `Nessun prezzo per ${symbol}: CoinGecko e Coinbase falliti`, { symbol }, requestId);
            }
            continue;
          }

          if (!outcome.ok && isin) {

            // 2a. BTPi → SoldiOnline come prima fonte
            if (isBTPi) {
              const soPrice = await fetchSoldiOnlinePrice(isin, requestId, dbEntries);
              if (soPrice !== null && pushPrice(symbol, soPrice)) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from SoldiOnline`, { isin, price: soPrice }, requestId);
                continue;
              }
            }

            // 2b. BTP standard → rendimentibtp.it
            if (!isBTPi && isBond && isin.startsWith("IT") && btpPrices !== null) {
              const btpPrice = btpPrices.get(isin);
              if (btpPrice !== undefined && pushPrice(symbol, btpPrice)) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from rendimentibtp.it`, { isin, price: btpPrice }, requestId);
                continue;
              }
              if (btpPrice === undefined) {
                dbLog(dbEntries, "WARN", `${symbol} not in rendimentibtp.it`, { isin, btpCount: btpPrices.size }, requestId);
              }
            }

            // 2c. ETF → JustETF (scraping della scheda HTML), poi Yahoo Finance come backup
            if (isEtf) {
              const jePrice = await fetchJustEtfHtmlPrice(isin, requestId, dbEntries);
              if (jePrice !== null && pushPrice(symbol, jePrice)) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from JustETF`, { isin, price: jePrice }, requestId);
                continue;
              }
              const yfPrice = await fetchYahooFinanceByIsin(isin, requestId, dbEntries, TWELVE_DATA_API_KEY, rateCache);
              if (yfPrice !== null && pushPrice(symbol, yfPrice)) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from Yahoo Finance`, { isin, price: yfPrice }, requestId);
                continue;
              }
            }

            // 2d. Investing.com — per qualsiasi tipo strumento con URL noto nella mappa
            if (INVESTING_COM_URLS[isin]) {
              const icPrice = await fetchInvestingComPrice(isin, requestId, dbEntries);
              if (icPrice !== null && pushPrice(symbol, icPrice)) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from Investing.com`, { isin, price: icPrice }, requestId);
                continue;
              }
            }

            // 2e. Yahoo Finance by ISIN — per azioni quando TD richiede piano a pagamento
            if (isStock) {
              const yfPrice = await fetchYahooFinanceByIsin(isin, requestId, dbEntries, TWELVE_DATA_API_KEY, rateCache);
              if (yfPrice !== null && pushPrice(symbol, yfPrice)) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from Yahoo Finance (stock)`, { isin, price: yfPrice }, requestId);
                continue;
              }
            }

            // 2f. Euronext AJAX — bond su MOTX, ETF su ETFP (non per azioni)
            if (!isStock) {
              const biPrice = await fetchBorsaItalianaPrice(isin, requestId, dbEntries);
              if (biPrice !== null && pushPrice(symbol, biPrice)) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from Borsa Italiana`, { isin, price: biPrice }, requestId);
                continue;
              }
            }

            // 2g. TD symbol_search via ISIN — per azioni con ISIN e qualsiasi rimanente
            const resolved = await resolveIsinToSymbol(isin, TWELVE_DATA_API_KEY, requestId, dbEntries);
            if (resolved) {
              madeApiCall = true;
              const outcome2 = await fetchTwelveDataQuote(resolved, TWELVE_DATA_API_KEY);
              if (outcome2.ok) {
                outcome = outcome2;
                resolvedAs = resolved;
                dbLog(dbEntries, "INFO", `${symbol} resolved via ISIN`, { isin, resolvedAs }, requestId);
              } else {
                dbLog(dbEntries, "WARN", `TD quote failed for resolved symbol`, { symbol, resolved, error: outcome2.message }, requestId);
              }
            }
          }

          // 2h. Google Finance — per azioni con exchange noto nella mappa (anche senza ISIN)
          if (!outcome.ok && GOOGLE_FINANCE_SYMBOL_MAP[symbol]) {
            const gfResult = await fetchGoogleFinancePrice(symbol, requestId, dbEntries);
            if (gfResult !== null) {
              let gfPrice = gfResult.price;
              if (gfResult.currency !== TARGET_CURRENCY) {
                const rate = await getConversionRate(gfResult.currency, TARGET_CURRENCY, TWELVE_DATA_API_KEY, requestId, rateCache);
                if (rate) {
                  gfPrice = roundMoney(gfResult.price * rate);
                } else {
                  dbLog(dbEntries, "WARN", `GF currency conversion failed`, { symbol, currency: gfResult.currency }, requestId);
                  if (i < toFetch.length - 1) await delay(8000);
                  continue;
                }
              }
              if (pushPrice(symbol, gfPrice, { market_state: "REGULAR" })) {
                dbLog(dbEntries, "INFO", `Fetched ${symbol} from Google Finance`, { price: gfPrice, exchange: GOOGLE_FINANCE_SYMBOL_MAP[symbol].exchange }, requestId);
                continue;
              }
            }
          }

          // Step 3: process final outcome
          if (!outcome.ok) {
            const warnData = { symbol, status: outcome.status, error: outcome.message };
            log("WARN", outcome.status === 429 ? `Rate limit for ${symbol}` : `No quote for ${symbol}`,
                { requestId, ...warnData });
            dbLog(dbEntries, "WARN", `No quote for ${symbol}`, warnData, requestId);
            if (i < toFetch.length - 1 && madeApiCall) await delay(8000);
            continue;
          }

          const result = outcome.result;
          let price = parseFloat((result.close ?? result.price ?? "0") as string);
          const previousClose = parseFloat((result.previous_close ?? "0") as string) || null;
          const changeAmount = parseFloat((result.change ?? "0") as string) || null;
          const changePct = parseFloat((result.percent_change ?? "0") as string) || null;
          let sourceCurrency = ((result.currency as string) || "USD").toUpperCase();

          // GBX = British pence; normalise to GBP before EUR conversion
          if (sourceCurrency === "GBX") {
            price = price / 100;
            sourceCurrency = "GBP";
          }

          if (price > 0) {
            const conversionRate = await getConversionRate(sourceCurrency, TARGET_CURRENCY, TWELVE_DATA_API_KEY, requestId, rateCache);
            if (!conversionRate) {
              log("WARN", `Currency conversion failed for ${symbol}`, { requestId, sourceCurrency });
              dbLog(dbEntries, "WARN", `Currency conversion failed`, { symbol, sourceCurrency }, requestId);
              if (i < toFetch.length - 1 && madeApiCall) await delay(8000);
              continue;
            }
            const pushed = pushPrice(symbol, price * conversionRate, {
              prev_close: previousClose === null ? null : roundMoney(previousClose * conversionRate),
              change_amt: changeAmount === null ? null : roundMoney(changeAmount * conversionRate),
              change_pct: changePct,
              market_state: result.exchange_timezone ? "REGULAR" : null,
            });
            if (pushed) {
              log("INFO", `${symbol} → ${roundMoney(price * conversionRate)} EUR (${resolvedAs})`, { requestId });
              dbLog(dbEntries, "INFO", `Fetched ${symbol}`, {
                price: roundMoney(price * conversionRate), resolvedAs, changePct,
              }, requestId);
            }
          } else {
            log("WARN", `Invalid price for ${symbol}`, { requestId, price });
            dbLog(dbEntries, "WARN", `Invalid price for ${symbol}`, { price }, requestId);
          }

          if (i < toFetch.length - 1 && madeApiCall) await delay(8000);
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          log("ERROR", `Error fetching ${symbol}`, { requestId, error: msg });
          dbLog(dbEntries, "ERROR", `Error fetching ${symbol}`, { error: msg }, requestId);
        }
      }

      await flushRows();

      if (fetchedSymbols.size === 0) {
        log("WARN", "No prices fetched", { requestId, toFetch });
        dbLog(dbEntries, "WARN", "No prices fetched", { toFetch }, requestId);
      }

      // Riepilogo esplicito di cosa è rimasto senza prezzo: senza questa riga
      // bisogna ricostruire l'esito simbolo per simbolo leggendo tutti i WARN.
      const missing = toFetch.filter((s) => !fetchedSymbols.has(s));
      if (missing.length > 0) {
        const missingDetail = missing.map((s) => `${s}[${typeMap[s] ?? "?"}]${isinMap[s] ? "=" + isinMap[s] : ""}`);
        log("WARN", `Symbols without price: ${missing.join(", ")}`, { requestId });
        dbLog(dbEntries, "WARN", "Symbols without price", { count: missing.length, missing: missingDetail }, requestId);
      }
    }

    const totalTime = Date.now() - startTime;
    const updatedCount = cachedMap.size;
    log("INFO", "=== Request completed ===", { requestId, totalTimeMs: totalTime, updated: updatedCount });
    dbLog(dbEntries, "INFO", "Request completed", {
      totalSymbols: symbols.length, updated: updatedCount, totalTimeMs: totalTime,
    }, requestId);

    await flushLogs(supabase, dbEntries);
    return json({ message: "OK", updated: updatedCount, total: symbols.length, version: VERSION });
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err);
    log("ERROR", "=== Request failed ===", { requestId, totalTimeMs: Date.now() - startTime, error: msg });
    return json({ error: msg }, 500);
  }
});

// ── Helpers ────────────────────────────────────────────────────────────────

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function normalizeSymbols(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return [...new Set(
      value
          .filter((s): s is string => typeof s === "string")
          .map((s) => s.trim().toUpperCase())
          .filter(Boolean),
  )];
}

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function getConversionRate(
    sourceCurrency: string,
    targetCurrency: string,
    apiKey: string,
    requestId: string,
    rateCache: Map<string, number>,
): Promise<number | null> {
  const src = sourceCurrency.trim().toUpperCase();
  const tgt = targetCurrency.trim().toUpperCase();
  if (src === tgt) return 1;

  const pair = `${src}/${tgt}`;
  if (rateCache.has(pair)) return rateCache.get(pair)!;

  try {
    const url = `https://api.twelvedata.com/currency_conversion?symbol=${encodeURIComponent(pair)}&amount=1&apikey=${apiKey}`;
    const res = await fetch(url, { headers: { "Accept": "application/json" } });
    if (!res.ok) return null;
    const result = await res.json();
    if (result.status === "error") return null;
    const rate = parseNumber(result.amount ?? result.value ?? result.rate ?? result.price);
    if (rate === null || rate <= 0) return null;
    rateCache.set(pair, rate);
    log("INFO", `Rate ${pair} = ${rate}`, { requestId });
    return rate;
  } catch {
    return null;
  }
}

function parseNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const n = Number.parseFloat(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function roundMoney(value: number): number {
  return Math.round(value * 10000) / 10000;
}
