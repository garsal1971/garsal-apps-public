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
    const groqRes = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + apiKey,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'llama-3.3-70b-versatile',
        messages: [{ role: 'user', content: prompt }],
        temperature: 0,
        max_tokens: 1024,
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
