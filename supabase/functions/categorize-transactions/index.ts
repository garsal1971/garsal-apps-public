// Supabase Edge Function: suggerisce la categoria di spesa più adatta per un elenco di
// descrizioni di transazioni (Analisi Costi), usando Groq (free tier, modelli Llama).
// Nessun costo — richiede GROQ_API_KEY nei Supabase Secrets.
//
// v2 — 2026-07-17: il modello restituisce l'INDICE della categoria (non l'id UUID) per
// ridurre drasticamente i token di output — meno rischio di JSON troncato/malformato su
// batch grandi. Rimosso response_format:"json_object" (la validazione server-side di Groq
// falliva con "json_validate_failed" su batch grandi); parsing lato nostro con fallback
// tollerante invece che un errore secco.
// v3 — 2026-07-17: il client ora passa solo le categorie "foglia" (sotto-categorie, o
// principali senza sotto-categorie) — le categorie principali con figli non sono più
// un'opzione selezionabile. Aggiunto un elenco opzionale di regole già definite
// dall'utente, passato come riferimento nel prompt.

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

function extractJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    // Il modello a volte aggiunge testo/markdown attorno al JSON: prova a isolarlo.
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
  };
  try {
    body = await req.json();
  } catch {
    return new Response(
      JSON.stringify({ error: { message: 'Body JSON non valido.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  const descriptions = (body.descriptions || [])
    .filter((d): d is string => typeof d === 'string' && d.trim().length > 0)
    .slice(0, 60); // batch piccoli: meno token di output, meno rischio di JSON troncato
  const categories = body.categories || [];
  const rules = (body.rules || [])
    .filter((r) => r && typeof r.pattern === 'string' && typeof r.categoryName === 'string')
    .slice(0, 150);

  if (!descriptions.length || !categories.length) {
    return new Response(
      JSON.stringify({ error: { message: 'Servono "descriptions" e "categories" non vuoti.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  // Il modello ragiona sull'INDICE numerico della categoria (non l'id UUID): output molto
  // più corto e affidabile. L'id reale viene rimappato qui sotto dopo la risposta.
  const categoryList = categories.map((c, i) => `${i}: ${c.name}`).join('\n');
  const descList = descriptions.map((d, i) => `${i}: ${d}`).join('\n');
  const rulesSection = rules.length
    ? `\nRegole già definite manualmente dall'utente (usale come riferimento per capire il suo stile di categorizzazione, non sono vincolanti):\n${rules
        .map((r) => `"${r.pattern}" → ${r.categoryName}`)
        .join('\n')}\n`
    : '';

  const prompt = `Sei un assistente che categorizza transazioni bancarie personali (spese/entrate) in base alla descrizione/nome del merchant.

Categorie disponibili (indice: nome):
${categoryList}
${rulesSection}
Descrizioni da classificare (indice: descrizione):
${descList}

Per ciascuna descrizione scegli l'INDICE della categoria più adatta tra quelle elencate sopra. Se nessuna categoria è chiaramente adatta, usa null.
Rispondi SOLO con un oggetto JSON compatto, senza markdown né spiegazioni, con questa forma esatta:
{"0": 2, "1": null, "2": 0}
dove ogni chiave è l'indice della descrizione e il valore è l'indice della categoria scelta (o null).`;

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
        max_tokens: 2048,
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
    const parsed = (extractJson(content) as Record<string, number | string | null> | null) || {};

    const results = descriptions.map((description, i) => {
      const raw = parsed[String(i)];
      const catIdx = typeof raw === 'number' ? raw : (typeof raw === 'string' ? parseInt(raw, 10) : NaN);
      const cat = Number.isInteger(catIdx) && catIdx >= 0 && catIdx < categories.length ? categories[catIdx] : null;
      return { description, categoryId: cat ? cat.id : null };
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
