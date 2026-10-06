// Supabase Edge Function: avvia il collegamento di un conto via Enable Banking (PSD2,
// restricted mode). Firma un JWT con la chiave privata dell'applicazione, chiama
// POST /auth su Enable Banking e restituisce l'URL a cui reindirizzare l'utente per il
// consenso sulla propria banca (login + autorizzazione).
//
// ⚠️ RESTRICTED MODE non è un dettaglio di contorno: l'API restituisce solo i conti messi in
// whitelist nel Control Panel di Enable Banking con "Link accounts". Per un conto non
// collegato lì il consenso riesce, la banca mostra il conto spuntato e la sessione torna con
// accounts: [] — senza errori, perché è il filtro previsto. Niente in questa function può
// aggirarlo: prima di cercare la causa qui dentro, controllare la whitelist.
//
// Richiede i Supabase Secrets: ENABLE_BANKING_APP_ID, ENABLE_BANKING_PRIVATE_KEY (PEM),
// SUPABASE_SERVICE_ROLE_KEY (per decodificare in sicurezza l'utente chiamante).
//
// Il callback dopo il consenso arriva su enable-banking-callback, che crea davvero le righe
// in cm_bank_connections — questa function non scrive nel database, legge solo l'istituto.
//
// v1 — 2026-07-18
// v2 — 2026-08-03: i conti sono condivisi tra moduli.
// v3/v4/v5 — 2026-08-04/06: IBAN in access.accounts, header PSU, traccia diagnostica del
//   consenso. Tutta questa parte è stata ritirata in v6, vedi sotto.
// v6 — 2026-08-07: la chiamata parte da un istituto censito (cm_institutions) e non da un
//   form. Il body si riduce a { institutionId, replaceConnectionId }: nome ASPSP e paese si
//   leggono dall'anagrafica, mentre nome del conto, usi e proprietario non si chiedono più
//   prima del consenso — si assegnano dopo, quando i conti che la banca ha restituito sono
//   sotto gli occhi ("battesimo"). Sparisce anche l'IBAN: era obbligatorio perché si credeva
//   che un consenso senza access.accounts fosse la causa delle sessioni vuote di UniCredit,
//   ma la causa era la whitelist in restricted mode, e le richieste con l'IBAN tornavano
//   vuote esattamente come le altre. Con l'IBAN se ne va la riga 'consent_request' in
//   cm_sync_log, che serviva solo a distinguere quei due casi.

import { createClient } from 'npm:@supabase/supabase-js@2';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const ENABLE_BANKING_API_BASE = 'https://api.enablebanking.com';

function base64url(input: ArrayBuffer | string): string {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : new Uint8Array(input);
  let str = '';
  for (const b of bytes) str += String.fromCharCode(b);
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const pemBody = pem
    .replace(/-----BEGIN (RSA )?PRIVATE KEY-----/, '')
    .replace(/-----END (RSA )?PRIVATE KEY-----/, '')
    .replace(/\s+/g, '');
  const binaryDer = Uint8Array.from(atob(pemBody), (c) => c.charCodeAt(0));
  // Nota: Web Crypto accetta solo PKCS8 ("PRIVATE KEY"). Se la chiave scaricata da Enable
  // Banking è in formato PKCS1 ("RSA PRIVATE KEY"), l'import fallisce qui — va convertita con:
  // openssl pkcs8 -topk8 -inform PEM -outform PEM -in key.pem -out key_pkcs8.pem -nocrypt
  return crypto.subtle.importKey(
    'pkcs8',
    binaryDer.buffer,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign']
  );
}

async function createEnableBankingJWT(appId: string, privateKeyPem: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { typ: 'JWT', alg: 'RS256', kid: appId };
  const body = { iss: 'enablebanking.com', aud: 'api.enablebanking.com', iat: now, exp: now + 3600 };
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(body))}`;
  const key = await importPrivateKey(privateKeyPem);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(signingInput));
  return `${signingInput}.${base64url(signature)}`;
}

function decodeSupabaseJwtSub(token: string): string | null {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.sub || null;
  } catch {
    return null;
  }
}

// Alcune banche pretendono di sapere da quale IP arriva l'utente che sta autorizzando: lo
// dichiarano in required_psu_headers nel catalogo /aspsps (UniCredit IT chiede
// "psu-ip-address"). Senza quell'header il consenso viene comunque autorizzato, ma la banca
// non espone nessun conto — e non arriva nessun errore che lo spieghi.
// L'IP è quello del browser dell'utente, che raggiunge la Edge Function in x-forwarded-for:
// se manca (chiamata da un job, senza utente davanti) l'header non si inventa.
function psuHeaders(req: Request): Record<string, string> {
  const fwd = req.headers.get('x-forwarded-for') || '';
  const ip = fwd.split(',')[0].trim();
  if (!ip) return {};
  const headers: Record<string, string> = { 'psu-ip-address': ip };
  const ua = req.headers.get('user-agent');
  if (ua) headers['psu-user-agent'] = ua;
  return headers;
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders });
  }
  if (req.method !== 'POST') {
    return new Response('Method Not Allowed', { status: 405, headers: corsHeaders });
  }

  const appId = Deno.env.get('ENABLE_BANKING_APP_ID');
  const privateKeyPem = Deno.env.get('ENABLE_BANKING_PRIVATE_KEY');
  if (!appId || !privateKeyPem) {
    return new Response(
      JSON.stringify({ error: { message: 'ENABLE_BANKING_APP_ID / ENABLE_BANKING_PRIVATE_KEY non configurate nei Supabase Secrets.' } }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  const authHeader = req.headers.get('authorization') || '';
  const userToken = authHeader.replace(/^Bearer\s+/i, '');
  const userId = decodeSupabaseJwtSub(userToken);
  if (!userId) {
    return new Response(
      JSON.stringify({ error: { message: 'Utente non autenticato.' } }),
      { status: 401, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  let body: { institutionId?: string; replaceConnectionId?: string };
  try {
    body = await req.json();
  } catch {
    return new Response(
      JSON.stringify({ error: { message: 'Body JSON non valido.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  const institutionId = (body.institutionId || '').trim();
  // Collegamento vuoto che questo consenso viene a sostituire: il callback ci sposta sopra i
  // fondi e poi lo elimina, così l'utente non resta con due righe e il fondo agganciato a
  // quella morta.
  const replaceConnectionId = (body.replaceConnectionId || '').trim() || null;
  if (!institutionId) {
    return new Response(
      JSON.stringify({ error: { message: 'Serve "institutionId": il collegamento parte da un istituto censito.' } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  // Banca e paese vengono dall'anagrafica, non dal client: sono gli unici due valori che
  // Enable Banking accetta per identificare la banca, e ridigitarli a ogni collegamento era
  // il modo più semplice per sbagliarli.
  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!supabaseUrl || !serviceRoleKey) {
    return new Response(
      JSON.stringify({ error: { message: 'SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY non configurate.' } }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }
  const { data: institution } = await createClient(supabaseUrl, serviceRoleKey)
    .from('cm_institutions')
    .select('id,name,kind,connectable,aspsp_name,aspsp_country')
    .eq('id', institutionId)
    .eq('user_id', userId)
    .maybeSingle();
  if (!institution) {
    return new Response(
      JSON.stringify({ error: { message: 'Istituto non trovato.' } }),
      { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }
  if (!institution.connectable || !institution.aspsp_name || !institution.aspsp_country) {
    return new Response(
      JSON.stringify({ error: { message:
        `«${institution.name}» è censito come non collegabile: non è nel catalogo Enable Banking, ` +
        `quindi non c'è nessun consenso da chiedere.` } }),
      { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }
  const aspspName = institution.aspsp_name;
  const country = String(institution.aspsp_country).toUpperCase();

  const supabaseProjectRef = new URL(Deno.env.get('SUPABASE_URL') || '').hostname.split('.')[0];
  const redirectUrl = `https://${supabaseProjectRef}.supabase.co/functions/v1/enable-banking-callback`;
  const psu = psuHeaders(req);
  const PSU_TYPE = 'personal';

  let jwt: string;
  try {
    jwt = await createEnableBankingJWT(appId, privateKeyPem);
  } catch (e) {
    return new Response(
      JSON.stringify({ error: { message: 'Chiave Enable Banking non utilizzabile: ' + (e as Error).message } }),
      { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }

  // Dal catalogo servono due soli valori, ed entrambi entrano nella richiesta: il tetto alla
  // durata del consenso e — quando la banca lo lega esplicitamente al nostro psu_type — il
  // metodo di autenticazione. Se il catalogo non si legge si prosegue con i default: non
  // sapere cosa dichiara la banca non è un motivo per non provare a collegarla.
  let maxValiditySeconds: number | null = null;
  let authMethodName: string | null = null;
  try {
    const res = await fetch(`${ENABLE_BANKING_API_BASE}/aspsps?country=${encodeURIComponent(country)}`, {
      headers: { Authorization: 'Bearer ' + jwt },
    });
    if (res.ok) {
      const data = await res.json();
      const list: any[] = Array.isArray(data.aspsps) ? data.aspsps : Array.isArray(data) ? data : [];
      const entry = list.find((a) => String(a?.name || '').toLowerCase() === aspspName.toLowerCase());
      if (entry) {
        // Si manda auth_method solo quando la banca lega esplicitamente un metodo al nostro
        // psu_type, uno solo, e gli dà un nome. Se il legame non è dichiarato la scelta resta
        // a Enable Banking: indovinare un identificativo fa fallire /auth e basta.
        const methods: any[] = Array.isArray(entry.auth_methods) ? entry.auth_methods : [];
        const forOurPsuType = methods.filter((m: any) => m?.psu_type === PSU_TYPE);
        if (forOurPsuType.length === 1 && typeof forOurPsuType[0]?.name === 'string' && forOurPsuType[0].name) {
          authMethodName = forOurPsuType[0].name;
        }
        maxValiditySeconds = Number.isFinite(entry.maximum_consent_validity) ? entry.maximum_consent_validity : null;
      }
    }
  } catch {
    // Catalogo non leggibile: si va avanti con i default.
  }

  // Durata del consenso: 180 giorni è il massimo PSD2, ma UniCredit dichiara esattamente
  // 15 552 000 secondi — 180 giorni tondi. Chiedere il massimo esatto significa appoggiarsi
  // al bordo: basta qualche secondo di scarto tra l'orologio di qui e quello di Enable
  // Banking per sforare. Un'ora di margine non toglie niente a nessuno.
  const requestedSeconds = Math.min(180 * 24 * 3600, maxValiditySeconds ?? 180 * 24 * 3600) - 3600;
  const validUntil = new Date(Date.now() + requestedSeconds * 1000).toISOString();

  const state = base64url(JSON.stringify({
    userId,
    institutionId,
    aspspName,
    validUntil,
    replaceConnectionId,
  }));

  try {
    const authRes = await fetch(`${ENABLE_BANKING_API_BASE}/auth`, {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + jwt, 'Content-Type': 'application/json', ...psu },
      body: JSON.stringify({
        // Nessun access.accounts: il consenso vale su tutti i conti che la banca espone, ed
        // è la banca a farceli scegliere sulla sua schermata. L'elenco degli IBAN c'è stato
        // per due giorni, nella convinzione che fosse la cura per le sessioni vuote di
        // UniCredit; le richieste del 5 e 6 agosto 2026 sono partite con accounts: [{iban}]
        // e sono tornate identiche a quelle senza — access.accounts: null, accounts: [].
        // La causa era la whitelist in restricted mode. balances/transactions erano già i
        // default di Enable Banking: meglio dichiararli che ereditarli in silenzio.
        access: { valid_until: validUntil, balances: true, transactions: true },
        aspsp: { name: aspspName, country },
        state,
        redirect_url: redirectUrl,
        psu_type: PSU_TYPE,
        // Solo se il catalogo ne dichiara uno solo per il nostro psu_type e con un nome
        // esplicito: altrimenti sceglie Enable Banking, come ha sempre fatto.
        ...(authMethodName ? { auth_method: authMethodName } : {}),
      }),
    });

    const data = await authRes.json();
    if (!authRes.ok) {
      return new Response(
        JSON.stringify({ error: { message: 'Errore Enable Banking: ' + (data?.message || data?.error || authRes.status) } }),
        { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      );
    }

    return new Response(JSON.stringify({ url: data.url }), {
      status: 200,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });
  } catch (e) {
    return new Response(
      JSON.stringify({ error: { message: 'Errore chiamata Enable Banking: ' + (e as Error).message } }),
      { status: 502, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  }
});
