// Supabase Edge Function: il token temporaneo di Azure Speech per l'APK Pronuncia.
//
// ⚠️ LA CHIAVE AZURE NON VA NELL'APP. Un'APK si apre con un unzip, e una chiave
// scritta lì dentro è una chiave pubblicata: chiunque la estragga consuma il
// livello gratuito (o la carta) a nome di Salvatore. Qui la chiave sta nei
// Secrets e l'app riceve un token che **dura dieci minuti** e vale solo per
// Speech in quella regione.
//
// ⚠️ CHI PUÒ CHIAMARLA. Il JWT si verifica **contro Supabase**, non si legge e
// basta — è la stessa scelta di `backup-drive`. Passa il solo `PRONUNCIA_EMAIL`
// (di partenza garsal1971@gmail.com): Teresa, Rosa e Ada hanno un login valido
// e non devono poter consumare la quota di Azure.
//
// ⚠️ IL TOKEN SI TIENE IN MEMORIA per nove minuti: rifarlo a ogni chiamata
// sarebbe un giro su Azure per ogni frase. Lo stesso token va bene per tutti i
// chiamanti — che comunque sono uno solo.
//
// Secrets: AZURE_SPEECH_KEY, AZURE_SPEECH_REGION (es. westeurope),
//          PRONUNCIA_EMAIL (facoltativo).
// v1 — 2026-10-28

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
const EMAIL_OK = (Deno.env.get('PRONUNCIA_EMAIL') ?? 'garsal1971@gmail.com').toLowerCase();

// Il token di Azure vale 10 minuti: lo si rende per 9, così chi lo riceve ha
// sempre almeno un minuto davanti anche nel caso peggiore.
const DURATA_MS = 9 * 60_000;

function risposta(corpo: unknown, stato = 200) {
  return new Response(JSON.stringify(corpo), {
    status: stato,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

let cache: { token: string; regione: string; scade: number } | null = null;

async function tokenAzure(): Promise<{ token: string; regione: string; scade: number }> {
  if (cache && cache.scade > Date.now() + 60_000) return cache;

  const chiave = Deno.env.get('AZURE_SPEECH_KEY') ?? '';
  const regione = (Deno.env.get('AZURE_SPEECH_REGION') ?? '').trim();
  const mancanti = [!chiave && 'AZURE_SPEECH_KEY', !regione && 'AZURE_SPEECH_REGION'].filter(Boolean);
  if (mancanti.length) throw new Error(`mancano i secret: ${mancanti.join(', ')}`);

  const r = await fetch(`https://${regione}.api.cognitive.microsoft.com/sts/v1.0/issueToken`, {
    method: 'POST',
    headers: { 'Ocp-Apim-Subscription-Key': chiave, 'Content-Length': '0' },
  });
  const testo = await r.text();
  // ⚠️ Il corpo di un errore di Azure non contiene la chiave, ma lo si taglia
  // comunque: finisce in un messaggio che l'app mostra a schermo.
  if (!r.ok || !testo) throw new Error(`Azure ha rifiutato la chiave: HTTP ${r.status} — ${testo.slice(0, 160)}`);

  cache = { token: testo, regione, scade: Date.now() + DURATA_MS };
  return cache;
}

// Chi sta chiamando. ⚠️ Si chiede a Supabase invece di decodificare il JWT: la
// firma la può verificare solo lui.
async function chiChiama(req: Request): Promise<string | null> {
  const header = req.headers.get('Authorization') ?? '';
  if (!header.toLowerCase().startsWith('bearer ')) return null;
  const r = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { Authorization: header, apikey: Deno.env.get('SUPABASE_ANON_KEY') ?? '' },
  });
  if (!r.ok) return null;
  const u = await r.json();
  return (u?.email ?? '').toLowerCase() || null;
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });
  if (req.method !== 'POST') return risposta({ error: 'usa POST' }, 405);

  const email = await chiChiama(req);
  if (!email) return risposta({ error: 'serve un login valido' }, 401);
  if (email !== EMAIL_OK) return risposta({ error: 'non autorizzato' }, 403);

  try {
    const t = await tokenAzure();
    return risposta({ token: t.token, region: t.regione, expiresAt: new Date(t.scade).toISOString() });
  } catch (e) {
    console.error('azure-speech-token:', e);
    return risposta({ error: String((e as Error)?.message ?? e) }, 502);
  }
});
