// Supabase Edge Function: rilegge i conti di una sessione Enable Banking già ottenuta.
//
// Serve quando il consenso è andato a buon fine ma la lista dei conti è tornata vuota (è
// successo con UniCredit): la sessione resta valida, quindi non c'è motivo di rifare login e
// SCA per riprovare. Se i conti compaiono, la connessione viene completata con il suo
// account_id e i conti in più diventano righe nuove.
//
// Richiede i Supabase Secrets: ENABLE_BANKING_APP_ID, ENABLE_BANKING_PRIVATE_KEY.
// v1 — 2026-08-04
// v2 — 2026-08-07: i conti trovati non ricevono più un nome d'ufficio (l'IBAN va nella sua
//   colonna) e nascono senza usi: il battesimo è un gesto dell'utente, in finanza.html.
//   Via la risposta grezza — serviva a capire perché UniCredit tornava vuota, e la risposta
//   era la whitelist in restricted mode, che in quel JSON non compare.

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
  return crypto.subtle.importKey('pkcs8', binaryDer.buffer, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
}

async function createEnableBankingJWT(appId: string, privateKeyPem: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { typ: 'JWT', alg: 'RS256', kid: appId };
  const jwtBody = { iss: 'enablebanking.com', aud: 'api.enablebanking.com', iat: now, exp: now + 3600 };
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(jwtBody))}`;
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

// I conti possono arrivare in tre posti diversi, e non sempre insieme: "accounts_data"
// (oggetti completi, con IBAN), "accounts" (spesso solo gli uid come stringhe) e
// "account_ids". Si leggono tutti e si uniscono per uid, tenendo l'IBAN quando c'è: leggere
// solo "accounts" significa perdere il conto quando la banca popola soltanto accounts_data.
// uid deve essere una stringa — in Enable Banking account_id è spesso l'oggetto { iban }, e
// prenderlo per buono salverebbe "[object Object]" come identificativo del conto.
// NOTA: questa funzione è duplicata in enable-banking-callback e
// enable-banking-refresh-accounts (le Edge Function non condividono moduli): vanno tenute
// allineate.
function extractAccounts(data: any): { uid: string; iban: string | null }[] {
  const found = new Map<string, string | null>();
  const add = (uid: unknown, iban: unknown) => {
    if (typeof uid !== 'string' || !uid) return;
    const ibanStr = typeof iban === 'string' && iban ? iban : null;
    if (!found.has(uid) || (ibanStr && !found.get(uid))) found.set(uid, ibanStr);
  };
  for (const key of ['accounts_data', 'accounts', 'account_ids']) {
    const list = (data || {})[key];
    if (!Array.isArray(list)) continue;
    for (const acc of list) {
      if (typeof acc === 'string') { add(acc, null); continue; }
      const uid = [acc?.uid, acc?.account_id, acc?.id, acc?.resource_id, acc?.resourceId, acc?.identification_hash]
        .find((v: unknown) => typeof v === 'string' && v.length > 0);
      add(uid, acc?.account_id?.iban || acc?.identification?.iban || acc?.iban || null);
    }
  }
  return [...found.entries()].map(([uid, iban]) => ({ uid, iban }));
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
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders });
  if (req.method !== 'POST') return new Response('Method Not Allowed', { status: 405, headers: corsHeaders });

  const json = (payload: unknown, status = 200) =>
    new Response(JSON.stringify(payload), { status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

  const appId = Deno.env.get('ENABLE_BANKING_APP_ID');
  const privateKeyPem = Deno.env.get('ENABLE_BANKING_PRIVATE_KEY');
  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const supabaseServiceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!appId || !privateKeyPem || !supabaseUrl || !supabaseServiceRoleKey) {
    return json({ error: { message: 'Configurazione mancante (secrets Enable Banking o Supabase).' } }, 500);
  }

  const userId = decodeSupabaseJwtSub((req.headers.get('authorization') || '').replace(/^Bearer\s+/i, ''));
  if (!userId) return json({ error: { message: 'Utente non autenticato.' } }, 401);

  let body: { bankConnectionId?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: { message: 'Body JSON non valido.' } }, 400);
  }
  const bankConnectionId = (body.bankConnectionId || '').trim();
  if (!bankConnectionId) return json({ error: { message: 'Serve "bankConnectionId".' } }, 400);

  const supabase = createClient(supabaseUrl, supabaseServiceRoleKey);
  const { data: connection } = await supabase
    .from('cm_bank_connections')
    .select('*')
    .eq('id', bankConnectionId)
    .eq('user_id', userId)
    .maybeSingle();
  if (!connection) return json({ error: { message: 'Conto collegato non trovato.' } }, 404);
  if (!connection.consent_id) {
    return json({ error: { message: 'Questo collegamento non ha una sessione salvata: va rifatto il consenso in banca.' } }, 400);
  }

  try {
    const jwt = await createEnableBankingJWT(appId, privateKeyPem);
    const res = await fetch(`${ENABLE_BANKING_API_BASE}/sessions/${connection.consent_id}`, {
      headers: { Authorization: 'Bearer ' + jwt, ...psuHeaders(req) },
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      return json({ error: { message: `Enable Banking ha risposto ${res.status}: ${data?.message || 'errore'}` } }, 502);
    }

    const accounts = extractAccounts(data);

    if (!accounts.length) {
      await supabase.from('cm_sync_log').insert({
        user_id: userId,
        bank_connection_id: connection.id,
        finished_at: new Date().toISOString(),
        status: 'no_accounts',
        imported_count: 0,
        error_message: `Rilettura sessione ${connection.aspsp_name}: nessun conto.`,
      });
      // Una sessione autorizzata che non elenca conti non si riempirà rileggendola: quasi
      // sempre il conto non è in whitelist su Enable Banking (restricted mode), e la
      // whitelist si tocca solo dal Control Panel. L'app lo spiega e propone di rifare il
      // consenso dopo averla sistemata.
      return json({
        accounts: [], updated: 0, created: 0,
        sessionStatus: data?.status || null,
        hopeless: true,
      });
    }

    // Il primo conto completa la connessione esistente; gli altri diventano righe nuove, ma
    // solo se non ci sono già (la rilettura si può ripetere quante volte si vuole).
    const { data: siblings } = await supabase
      .from('cm_bank_connections')
      .select('id, account_id')
      .eq('user_id', userId)
      .eq('consent_id', connection.consent_id);
    const known = new Set((siblings || []).map((s) => s.account_id).filter(Boolean));

    let updated = 0;
    let created = 0;
    for (const acc of accounts) {
      if (known.has(acc.uid)) continue;
      if (!connection.account_id && updated === 0) {
        // display_name non si tocca: se l'utente aveva già battezzato la riga vuota il nome
        // resta suo, e se non l'aveva fatto il conto va battezzato adesso, non riempito
        // d'ufficio con l'IBAN.
        await supabase
          .from('cm_bank_connections')
          .update({ account_id: acc.uid, iban: acc.iban })
          .eq('id', connection.id);
        updated++;
      } else {
        // I conti in più nascono anonimi e senza usi: sono comparsi ora, nessuno ha ancora
        // detto a cosa servono.
        await supabase.from('cm_bank_connections').insert({
          user_id: userId,
          provider: connection.provider,
          institution_id: connection.institution_id,
          aspsp_name: connection.aspsp_name,
          display_name: null,
          uses: [],
          iban: acc.iban,
          account_id: acc.uid,
          consent_id: connection.consent_id,
          consent_expires_at: connection.consent_expires_at,
          status: 'active',
        });
        created++;
      }
      known.add(acc.uid);
    }

    return json({
      accounts: accounts.map((a) => ({ uid: a.uid, iban: a.iban })),
      updated,
      created,
      sessionStatus: data?.status || null,
    });
  } catch (e) {
    return json({ error: { message: (e as Error).message } }, 502);
  }
});
