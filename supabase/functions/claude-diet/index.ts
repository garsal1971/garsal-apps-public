// Supabase Edge Function: proxy verso Anthropic API
// Timeout: 150s Pro / 10s Free — usa claude-haiku per rispettare free tier
// v3 — haiku + max_tokens 4096 — 2026-03-28
// v4 — solo utenti loggati (utenteLoggato) — 2026-11-21

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

// Solo un utente loggato: la chiave pubblica anon NON passa. Senza questo controllo
// la funzione (verify_jwt = false) era un accesso libero alla chiave IA per chiunque
// conoscesse l'indirizzo. /auth/v1/user risponde solo a un JWT di un utente vero.
async function utenteLoggato(req: Request): Promise<boolean> {
  const header = req.headers.get('Authorization') ?? '';
  if (!header.toLowerCase().startsWith('bearer ')) return false;
  try {
    const r = await fetch(`${Deno.env.get('SUPABASE_URL')}/auth/v1/user`, {
      headers: { Authorization: header, apikey: Deno.env.get('SUPABASE_ANON_KEY') ?? '' },
    });
    if (!r.ok) return false;
    const u = await r.json();
    return !!u?.id;
  } catch {
    return false;
  }
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders });
  }

  if (req.method !== 'POST') {
    return new Response('Method Not Allowed', { status: 405, headers: corsHeaders });
  }

  if (!(await utenteLoggato(req))) {
    return new Response(
      JSON.stringify({ error: { message: 'Serve un login valido: rientra in AppSphere e riprova.' } }),
      { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  const apiKey = Deno.env.get('ANTHROPIC_API_KEY');
  if (!apiKey) {
    return new Response(
      JSON.stringify({ error: { message: 'ANTHROPIC_API_KEY non configurata nei Supabase Secrets.' } }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  let body: { prompt?: string };
  try {
    body = await req.json();
  } catch {
    return new Response(
      JSON.stringify({ error: { message: 'Body JSON non valido.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  const prompt = body.prompt;
  if (!prompt) {
    return new Response(
      JSON.stringify({ error: { message: 'Campo "prompt" mancante.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  try {
    const anthropicRes = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 4096,
        messages: [{ role: 'user', content: prompt }],
      }),
    });

    const data = await anthropicRes.json();
    return new Response(JSON.stringify(data), {
      status: anthropicRes.status,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });
  } catch (e) {
    return new Response(
      JSON.stringify({ error: { message: 'Errore chiamata Anthropic: ' + (e as Error).message } }),
      { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }
});
