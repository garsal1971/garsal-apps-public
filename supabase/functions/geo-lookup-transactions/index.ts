// Supabase Edge Function: categorizza le transazioni (Analisi Costi) cercando prima ogni
// merchant su OpenStreetMap Nominatim (gratis, nessuna chiave richiesta) aggiungendo la città
// indicata, e passando il tipo di locale trovato (es. farmacia, ristorante, supermercato) come
// indizio a Groq insieme alla descrizione, per un'unica classificazione finale.
//
// Nominatim usage policy: max 1 richiesta/secondo e User-Agent identificativo obbligatori —
// per questo il batch è tenuto piccolo (max 6 descrizioni a chiamata).
//
// v1 — 2026-07-17: prima versione, usata come stadio di fallback dopo un primo passaggio AI
// testuale (categorize-transactions).
// v2 — 2026-07-18: diventa lo stadio principale — la ricerca geografica parte sempre per prima
// su ogni merchant, poi Groq classifica usando anche l'eventuale indizio trovato. Aggiunto il
// supporto alle regole già definite dall'utente (come in categorize-transactions), e il campo
// "source" per risultato distingue se è stato usato un indizio geografico ('geo') o solo il
// testo della descrizione ('text').
// v3 — 2026-08-13: non suggerisce più solo la categoria della singola descrizione, ma una
// REGOLA — testo da cercare (`pattern`) + categoria — che l'utente rivede, corregge e salva in
// ca_rules. Il pattern deve essere una sottostringa esatta della descrizione (confronto
// case-insensitive): quello che non lo è viene scartato qui e il chiamante ripiega sul merchant
// normalizzato, perché una regola che non aggancia nemmeno la transazione da cui nasce è peggio
// di nessun suggerimento.
// v4 — 2026-10-14: il modello Groq non è più scritto nel codice. `llama-3.3-70b-versatile` è
// stato ritirato da Groq e la funzione rispondeva «model does not exist»: ora si legge l'elenco
// /models (cache di un'ora) e si prende il primo dei preferiti che c'è ancora, altrimenti il più
// grande — la stessa scelta di scegliModelloGroq in pv-ai, da cambiare insieme.

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const NOMINATIM_USER_AGENT = 'AnalisiCosti/1.0 (Supabase Edge Function; contatto: garsal1971@gmail.com)';

function extractJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    const start = text.indexOf('{');
    const end = text.lastIndexOf('}');
    if (start !== -1 && end !== -1 && end > start) {
      try {
        return JSON.parse(text.slice(start, end + 1));
      } catch {
        return null;
      }
    }
    return null;
  }
}

const GROQ_URL = 'https://api.groq.com/openai/v1';
const GROQ_PREFERITI = ['openai/gpt-oss-120b', 'llama-3.3-70b-versatile', 'moonshotai/kimi-k2-instruct', 'qwen/qwen3-32b'];
let groqCache: { quando: number; ids: string[] } | null = null;

/** Quanti miliardi di parametri dice il nome («…-70b-…», «120b»); 0 se non lo dice. */
function taglia(id: string): number {
  const m = [...id.matchAll(/(\d+)b(?![a-z])/gi)].map((x) => Number(x[1]));
  return m.length ? Math.max(...m) : 0;
}

/** Un modello di chat che Groq offre oggi: mai un nome scritto fisso, Groq li toglie senza preavviso. */
async function scegliModelloGroq(apiKey: string): Promise<string> {
  if (!groqCache || Date.now() - groqCache.quando >= 3600_000) {
    const r = await fetch(`${GROQ_URL}/models`, {
      headers: { Authorization: 'Bearer ' + apiKey },
      signal: AbortSignal.timeout(15_000),
    });
    const j = await r.json().catch(() => ({}));
    if (r.status === 401) throw new Error('la chiave GROQ_API_KEY non è valida');
    if (!r.ok) throw new Error(`non riesco a leggere l'elenco dei modelli (${j?.error?.message || 'HTTP ' + r.status})`);
    const ids = (j?.data ?? [])
      .filter((m: { active?: boolean }) => m?.active !== false)
      .map((m: { id: string }) => String(m.id))
      // fuori ciò che non è una chat: trascrizione, voce, filtri di sicurezza, sistemi composti
      .filter((id: string) => !/whisper|tts|playai|guard|orpheus|distil|compound/i.test(id));
    groqCache = { quando: Date.now(), ids };
  }
  const ids = groqCache.ids;
  if (!ids.length) throw new Error('Groq non offre nessun modello di chat');
  for (const id of GROQ_PREFERITI) if (ids.includes(id)) return id;
  return [...ids].sort((a, b) => taglia(b) - taglia(a))[0];
}

async function nominatimLookup(query: string): Promise<string | null> {
  try {
    const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=${encodeURIComponent(query)}`;
    const res = await fetch(url, { headers: { 'User-Agent': NOMINATIM_USER_AGENT } });
    if (!res.ok) return null;
    const data = await res.json();
    const place = Array.isArray(data) ? data[0] : null;
    if (!place) return null;
    const cls = (place.class || '').replace(/_/g, ' ');
    const type = (place.type || '').replace(/_/g, ' ');
    const name = place.name || '';
    if (!cls && !type) return null;
    return `${cls}/${type}${name ? ' — ' + name : ''}`;
  } catch {
    return null;
  }
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders });
  }
  if (req.method !== 'POST') {
    return new Response('Method Not Allowed', { status: 405, headers: corsHeaders });
  }

  const apiKey = Deno.env.get('GROQ_API_KEY');
  if (!apiKey) {
    return new Response(
      JSON.stringify({ error: { message: 'GROQ_API_KEY non configurata nei Supabase Secrets.' } }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  let body: {
    descriptions?: string[];
    categories?: { id: string; name: string }[];
    rules?: { pattern: string; categoryName: string }[];
    city?: string;
  };
  try {
    body = await req.json();
  } catch {
    return new Response(
      JSON.stringify({ error: { message: 'Body JSON non valido.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  // Batch piccolo: rispettiamo il limite di 1 richiesta/secondo di Nominatim e restiamo
  // dentro al timeout della edge function (10s sul piano free di Supabase).
  const descriptions = (body.descriptions || [])
    .filter((d): d is string => typeof d === 'string' && d.trim().length > 0)
    .slice(0, 6);
  const categories = body.categories || [];
  const rules = (body.rules || [])
    .filter((r) => r && typeof r.pattern === 'string' && typeof r.categoryName === 'string')
    .slice(0, 150);
  const city = (body.city || '').trim();

  if (!descriptions.length || !categories.length) {
    return new Response(
      JSON.stringify({ error: { message: 'Servono "descriptions" e "categories" non vuoti.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  // Ricerca geografica in sequenza, con pausa per rispettare il limite di 1 richiesta/secondo.
  const hints: (string | null)[] = [];
  for (let i = 0; i < descriptions.length; i++) {
    const query = city ? `${descriptions[i]} ${city}` : descriptions[i];
    hints.push(await nominatimLookup(query));
    if (i < descriptions.length - 1) await new Promise((r) => setTimeout(r, 1100));
  }

  const categoryList = categories.map((c, i) => `${i}: ${c.name}`).join('\n');
  const descList = descriptions
    .map((d, i) => `${i}: ${d}${hints[i] ? ` (indizio geografico: ${hints[i]})` : ''}`)
    .join('\n');
  const rulesSection = rules.length
    ? `\nRegole già definite manualmente dall'utente (usale come riferimento per capire il suo stile di categorizzazione, non sono vincolanti):\n${rules
        .map((r) => `"${r.pattern}" → ${r.categoryName}`)
        .join('\n')}\n`
    : '';

  const prompt = `Sei un assistente che scrive REGOLE di categorizzazione per transazioni bancarie personali (spese/entrate). Una regola è una coppia "testo da cercare nella descrizione" → categoria: quando la descrizione di una transazione contiene quel testo (confronto case-insensitive, sottostringa), la transazione prende quella categoria. Per alcune descrizioni è disponibile anche un indizio da una ricerca geografica (tipo di locale trovato su OpenStreetMap, in inglese: es. amenity/pharmacy, shop/supermarket).

Categorie disponibili (indice: nome):
${categoryList}
${rulesSection}
Descrizioni di esempio, una per esercente (indice: descrizione, con eventuale indizio geografico):
${descList}

Per ciascuna descrizione proponi una regola:
- "c": l'INDICE della categoria più adatta, usando anche l'indizio geografico quando presente. Se resta incerto, usa null.
- "p": il testo da cercare. DEVE essere una sottostringa ESATTA della descrizione (stessi identici caratteri e spazi, si possono cambiare solo maiuscole e minuscole): non inventare parole, non abbreviare, non correggere errori di battitura. Scegli la parte più corta che identifica l'esercente, lasciando fuori tutto ciò che cambia da una transazione all'altra: date, orari, importi, numeri di carta, codici operazione, numero del punto vendita, sigle come "PAGAMENTO POS" o "ADDEBITO". Se non c'è un pezzo stabile, usa l'intera descrizione.

Rispondi SOLO con un oggetto JSON compatto, senza markdown né spiegazioni, con questa forma esatta:
{"0": {"c": 2, "p": "ESSELUNGA"}, "1": {"c": null, "p": "FARMACIA COMUNALE"}}
dove ogni chiave è l'indice della descrizione.`;

  try {
    const modello = await scegliModelloGroq(apiKey);
    const groqRes = await fetch(`${GROQ_URL}/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + apiKey,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: modello,
        messages: [{ role: 'user', content: prompt }],
        temperature: 0,
        // gpt-oss ragiona prima di rispondere e quei token contano nel tetto: con 1024 il JSON
        // poteva restare tagliato. Il ragionamento si tiene basso, qui si classifica e basta.
        max_tokens: 4096,
        ...(/gpt-oss/i.test(modello) ? { reasoning_effort: 'low' } : {}),
      }),
    });

    const data = await groqRes.json();
    if (!groqRes.ok) {
      return new Response(
        JSON.stringify({ error: { message: 'Errore Groq: ' + (data?.error?.message || groqRes.status) } }),
        { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      );
    }

    const content: string = data?.choices?.[0]?.message?.content || '{}';
    const parsed = (extractJson(content) as Record<string, unknown> | null) || {};

    const results = descriptions.map((description, i) => {
      // Il formato v3 è {"c": indice, "p": "testo"}; un numero secco è la risposta del v2 (o di
      // un modello che si dimentica il pattern) e resta accettata: la categoria è comunque utile.
      const raw = parsed[String(i)];
      const obj = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : null;
      const rawCat = obj ? obj.c : raw;
      const catIdx = typeof rawCat === 'number' ? rawCat : typeof rawCat === 'string' ? parseInt(rawCat, 10) : NaN;
      const cat = Number.isInteger(catIdx) && catIdx >= 0 && catIdx < categories.length ? categories[catIdx] : null;

      // Un pattern che non è contenuto nella descrizione non aggancerebbe nemmeno la transazione
      // da cui nasce: si scarta e il chiamante ripiega sul merchant normalizzato.
      const rawPattern = obj && typeof obj.p === 'string' ? obj.p.trim() : '';
      const pattern = rawPattern.length >= 3 && description.toUpperCase().includes(rawPattern.toUpperCase())
        ? rawPattern
        : null;

      return { description, categoryId: cat ? cat.id : null, pattern, source: hints[i] ? 'geo' : 'text' };
    });

    return new Response(JSON.stringify({ results }), {
      status: 200,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });
  } catch (e) {
    return new Response(
      JSON.stringify({ error: { message: 'Errore chiamata Groq: ' + (e as Error).message } }),
      { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }
});
