// Supabase Edge Function: cerca un alimento nelle banche dati pubbliche e lo restituisce
// normalizzato, senza scrivere niente.
//
// Perché passa di qui e non dal browser, che pure ci arrivava:
//
//   1. CORS. Ogni servizio che non manda gli header giusti è inutilizzabile da una pagina, e
//      quali li mandino non lo si decide noi. Da qui il problema non esiste.
//   2. Le chiavi. USDA, FatSecret e simili vogliono una chiave: in una pagina HTML finirebbe in
//      chiaro, qui sta nei Supabase Secrets.
//   3. Più fonti, una forma sola. Il chiamante riceve sempre gli stessi campi, comunque sia
//      fatta la risposta di chi ha risposto — ed è questa funzione a sapere che Search-a-licious
//      dice `hits` e la vecchia API `products`, non la pagina.
//   4. La diagnosi. Ogni fonte torna col suo esito (HTTP, tempo, errore), così quando non si
//      trova niente si sa PERCHÉ: «il servizio è giù» e «quel prodotto non c'è» si vedevano
//      uguali, ed è la ragione per cui la ricerca è rimasta rotta per giorni senza dirlo.
//
// ⚠️ La normalizzazione vive SOLO qui. La pagina non ha più una sua copia di daOFF(): due
// implementazioni della stessa conversione sono due valori diversi per lo stesso prodotto il
// giorno che una delle due cambia.
//
// Secret facoltativo: USDA_API_KEY (gratuita su api.data.gov). Senza, la fonte USDA si salta.
// v1 — 2026-08-26

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const OFF_PRODOTTI = 'https://it.openfoodfacts.org';
// ⚠️ La ricerca testuale NON sta più su /cgi/search.pl: quello è deprecato e da aprile 2026
// risponde 503 a livello globale. Open Food Facts l'ha spostata su Search-a-licious.
const OFF_RICERCA   = 'https://search.openfoodfacts.org/search';
const USDA          = 'https://api.nal.usda.gov/fdc/v1';

const OFF_CAMPI = 'code,product_name,product_name_it,generic_name,brands,quantity,product_quantity_unit,serving_quantity,serving_quantity_unit,nutriments';

// Open Food Facts chiede a chi la usa di identificarsi. Da un server lo User-Agent si può
// impostare davvero, quindi qui si fa come chiedono — non col ripiego dei parametri in query
// string che serve al browser.
const UA = 'garsal-apps-calorie/1.0 (https://github.com/garsal1971/garsal-apps)';

type Alimento = {
  barcode: string | null; name: string; brand: string | null; source: string;
  kcal_100g: number; proteins_100g: number | null; fat_100g: number | null;
  sat_fat_100g: number | null; carbs_100g: number | null; sugars_100g: number | null;
  fiber_100g: number | null; salt_100g: number | null; default_grams: number | null;
  // 'g' o 'ml': su che base sono scritti i valori. Non è una densità e non converte niente —
  // dice solo se quel prodotto si pesa o si versa.
  unit: 'g' | 'ml';
  quantita?: string | null;
};

const num = (v: unknown): number | null => {
  const n = typeof v === 'string' ? parseFloat(v) : (v as number);
  return (n == null || Number.isNaN(n)) ? null : n;
};

// Un campo di testo di Open Food Facts: stringa dall'API dei prodotti, oggetto per lingua da
// Search-a-licious (che indicizza per lingua). Si accettano tutt'e due invece di dare per
// scontata la stringa, che darebbe «[object Object]» su una sola delle due strade.
function testo(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>;
    const c = o.it ?? o.main ?? o.en ?? Object.values(o).find((x) => typeof x === 'string' && x.trim());
    return typeof c === 'string' ? c : '';
  }
  return String(v);
}

/* ⚠️ Le calorie non sono sempre dove ci si aspetta. `energy-kcal_100g` c'è quasi sempre, ma dove
   l'etichetta è stata inserita in kJ va convertita (1 kcal = 4,184 kJ), e `energy_100g` senza
   suffisso è ambiguo — si guarda `energy_unit` invece di dare per scontato che siano kJ. Un
   prodotto senza nessuna delle tre NON è un prodotto a zero calorie: è un prodotto di cui non si
   sa niente, e va scartato perché la pagina non archivi uno zero falso. */
function kcalDa(n: Record<string, unknown>): number | null {
  const kcal = num(n['energy-kcal_100g']);
  if (kcal != null) return kcal;
  const kj = num(n['energy-kj_100g']);
  if (kj != null) return kj / 4.184;
  const e = num(n['energy_100g']);
  if (e != null) return n.energy_unit === 'kcal' ? e : e / 4.184;
  return null;
}

/* Solido o liquido? La risposta è **scritta sul prodotto**, non indovinata: Open Food Facts
   porta l'unità della confezione (`product_quantity_unit`) e quella della porzione
   (`serving_quantity_unit`), e in mancanza di entrambe la quantità in chiaro — «1 l», «33 cl»,
   «500 ml». Si legge quella. ⚠️ Quel che NON si fa è dedurlo dal nome: «latte» marcherebbe in
   ml anche il latte in polvere, e «olio» l'olio di semi di una tabella di composizione, che in
   grammi ci stanno di proposito. Senza nessuna unità dichiarata si resta su 'g', che è quel che
   l'archivio ha sempre voluto dire — e chi ha in mano la bottiglia lo corregge in un tocco. */
const UNITA_LIQUIDE = ['ml', 'mlt', 'cl', 'dl', 'l', 'litro', 'litri', 'lt'];

function unitaDa(...campi: unknown[]): 'g' | 'ml' {
  for (const c of campi) {
    const t = String(c ?? '').trim().toLowerCase();
    if (!t) continue;
    // La sola unità («ml»), oppure la coda di una quantità in chiaro («1,5 l», «33cl»).
    const coda = t.match(/([a-z]+)\s*$/)?.[1];
    if (coda && UNITA_LIQUIDE.includes(coda)) return 'ml';
  }
  return 'g';
}

function daOFF(p: Record<string, any>): Alimento | null {
  const n = (p?.nutriments || {}) as Record<string, unknown>;
  const kcal = kcalDa(n);
  const name = (testo(p.product_name_it) || testo(p.product_name) || testo(p.generic_name)).trim();
  if (!name || kcal == null) return null;
  return {
    barcode: p.code ? String(p.code) : null,
    name,
    brand: (testo(p.brands).split(',')[0] || '').trim() || null,
    source: 'off',
    kcal_100g: Math.round(kcal * 10) / 10,
    proteins_100g: num(n.proteins_100g), fat_100g: num(n.fat_100g),
    sat_fat_100g: num(n['saturated-fat_100g']), carbs_100g: num(n.carbohydrates_100g),
    sugars_100g: num(n.sugars_100g), fiber_100g: num(n.fiber_100g), salt_100g: num(n.salt_100g),
    default_grams: num(p.serving_quantity),
    unit: unitaDa(p.product_quantity_unit, p.serving_quantity_unit, p.quantity),
    quantita: p.quantity ? String(p.quantity) : null,
  };
}

// I numeri dei nutrienti USDA. Sono costanti del loro schema, non nomi: cercarli per etichetta
// significherebbe rompersi alla prima traduzione.
const USDA_NUM = { kcal: '208', prot: '203', fat: '204', sat: '606', carb: '205', sug: '269', fib: '291', sod: '307' };

function daUSDA(f: Record<string, any>): Alimento | null {
  const per = new Map<string, number>();
  for (const n of (f.foodNutrients || [])) {
    const numero = String(n.nutrientNumber ?? n.nutrient?.number ?? '');
    const val = num(n.value ?? n.amount);
    if (numero && val != null) per.set(numero, val);
  }
  const kcal = per.get(USDA_NUM.kcal);
  const name = (f.description || '').trim();
  if (!name || kcal == null) return null;
  const sodio = per.get(USDA_NUM.sod);
  return {
    barcode: f.gtinUpc ? String(f.gtinUpc) : null,
    name, brand: (f.brandOwner || f.brandName || null),
    source: 'usda',
    kcal_100g: Math.round(kcal * 10) / 10,
    proteins_100g: per.get(USDA_NUM.prot) ?? null, fat_100g: per.get(USDA_NUM.fat) ?? null,
    sat_fat_100g: per.get(USDA_NUM.sat) ?? null, carbs_100g: per.get(USDA_NUM.carb) ?? null,
    sugars_100g: per.get(USDA_NUM.sug) ?? null, fiber_100g: per.get(USDA_NUM.fib) ?? null,
    // USDA dà il sodio in mg; il sale è sodio × 2,5, che è la conversione dell'etichetta europea.
    salt_100g: sodio == null ? null : Math.round((sodio / 1000) * 2.5 * 100) / 100,
    default_grams: null,
    // USDA scrive l'unità della porzione a parte, e per i liquidi vale 'ml' o 'MLT'.
    unit: unitaDa(f.servingSizeUnit),
  };
}

// La forma della risposta non si dà per scontata: Search-a-licious risponde con `hits` (è
// Elasticsearch sotto), l'API vecchia con `products`. Un cambio di chiave deve dare un errore,
// non «nessun risultato» — che è il modo in cui un guasto resta invisibile.
function prodottiDaRisposta(d: any): any[] {
  if (Array.isArray(d)) return d;
  for (const k of ['hits', 'products', 'foods', 'results']) if (Array.isArray(d?.[k])) return d[k];
  return [];
}

type Esito = { fonte: string; url: string; ok: boolean; http?: number; trovati?: number; errore?: string; ms: number };

async function prova(fonte: string, url: string, mappa: (d: any) => Alimento[], esiti: Esito[]): Promise<Alimento[]> {
  const t0 = Date.now();
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, 'Accept': 'application/json' } });
    if (!r.ok) {
      esiti.push({ fonte, url, ok: false, http: r.status, ms: Date.now() - t0 });
      return [];
    }
    const d = await r.json();
    const out = mappa(d);
    esiti.push({ fonte, url, ok: true, http: r.status, trovati: out.length, ms: Date.now() - t0 });
    return out;
  } catch (e) {
    esiti.push({ fonte, url, ok: false, errore: (e as Error).message, ms: Date.now() - t0 });
    return [];
  }
}

const paresBarcode = (q: string) => /^\d{8,14}$/.test(q.trim());

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });
  const rispondi = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

  try {
    const { q = '', barcode = '', limite = 20, diagnostica = false } = await req.json().catch(() => ({}));
    const esiti: Esito[] = [];
    const testoCercato = String(q || '').trim();
    const codice = String(barcode || '').trim() || (paresBarcode(testoCercato) ? testoCercato : '');

    // ── Codice a barre: una fonte sola, e non è deprecata ──
    if (codice) {
      const alimenti = await prova(
        'openfoodfacts/product',
        `${OFF_PRODOTTI}/api/v2/product/${encodeURIComponent(codice)}.json?fields=${OFF_CAMPI}`,
        (d) => (d?.status === 1 && d.product ? [daOFF(d.product)].filter(Boolean) as Alimento[] : []),
        esiti,
      );
      return rispondi({ alimenti, esiti: diagnostica || !alimenti.length ? esiti : undefined });
    }

    if (testoCercato.length < 2) return rispondi({ alimenti: [], esiti });

    // ── Ricerca per nome: le fonti si provano in ordine e ci si ferma alla prima che risponde ──
    let alimenti = await prova(
      'openfoodfacts/search-a-licious',
      `${OFF_RICERCA}?q=${encodeURIComponent(testoCercato)}&langs=it,en&page_size=${limite}&fields=${OFF_CAMPI}`,
      (d) => prodottiDaRisposta(d).map(daOFF).filter(Boolean) as Alimento[],
      esiti,
    );

    // Ripiego sulla vecchia: di solito è 503, ma costa una richiesta sola e solo dopo che la
    // prima è già fallita. Serve al caso opposto — che sia il servizio nuovo a essere giù.
    if (!alimenti.length) {
      alimenti = await prova(
        'openfoodfacts/cgi-search (deprecata)',
        `${OFF_PRODOTTI}/cgi/search.pl?search_terms=${encodeURIComponent(testoCercato)}&search_simple=1&action=process&json=1&page_size=${limite}&fields=${OFF_CAMPI}`,
        (d) => prodottiDaRisposta(d).map(daOFF).filter(Boolean) as Alimento[],
        esiti,
      );
    }

    // USDA solo se la chiave c'è: senza, si salta invece di sprecare una chiamata che tornerà 403.
    const chiaveUsda = Deno.env.get('USDA_API_KEY');
    if (!alimenti.length && chiaveUsda) {
      alimenti = await prova(
        'usda/fooddata-central',
        `${USDA}/foods/search?api_key=${encodeURIComponent(chiaveUsda)}&query=${encodeURIComponent(testoCercato)}&pageSize=${limite}&dataType=Foundation,SR%20Legacy,Branded`,
        (d) => prodottiDaRisposta(d).map(daUSDA).filter(Boolean) as Alimento[],
        esiti,
      );
    }

    return rispondi({ alimenti, esiti: diagnostica || !alimenti.length ? esiti : undefined });
  } catch (e) {
    return rispondi({ error: (e as Error).message }, 500);
  }
});
