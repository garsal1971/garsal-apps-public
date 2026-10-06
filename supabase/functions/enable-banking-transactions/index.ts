// Supabase Edge Function: legge i movimenti (e i saldi) di un conto collegato via Enable
// Banking e li restituisce normalizzati, senza scrivere niente.
//
// Fratello di enable-banking-sync (Spese Famiglia) e enable-banking-fondo-sync (Fondi), con una
// differenza: quelle due decidono anche dove mettere i movimenti, questa no. Serve a chi ha già
// il proprio schema di destinazione e le proprie regole — il Conto Risparmio scrive in
// cntrs_transactions_terr con le sue categorie e la sua revisione dei duplicati, e non
// guadagnerebbe niente da una terza copia di quella logica qui dentro.
//
// ⚠️ L'applicazione Enable Banking è in restricted mode: l'API restituisce solo i conti messi
// in whitelist nel Control Panel con "Link accounts". Un conto non collegato lì non ha
// movimenti da leggere, per quanto valido sia il consenso.
//
// Richiede i Supabase Secrets: ENABLE_BANKING_APP_ID, ENABLE_BANKING_PRIVATE_KEY.
// v1 — 2026-08-06

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

// I nomi dei campi cambiano tra ASPSP (snake_case, camelCase, varianti Berlin Group): si
// provano le forme plausibili invece di fidarsi di una sola.
function pick(obj: any, ...keys: string[]): unknown {
  for (const k of keys) {
    if (obj && obj[k] !== undefined && obj[k] !== null) return obj[k];
  }
  return null;
}

const normIban = (s: string | null | undefined): string => (s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');

// La carta con cui è stata fatta la spesa, quando la banca la espone: sta in
// debtor_account_additional_identification, la stessa fonte da cui enable-banking-sync ricava
// "chi ha speso" per Spese Famiglia. Nessuna carta (bonifico, addebito) → null.
function cardOf(tx: any): { issuer: string | null; identification: string | null } | null {
  const list = pick(tx, 'debtor_account_additional_identification', 'debtorAccountAdditionalIdentification') as any[];
  const card = Array.isArray(list) && list.length ? list[0] : null;
  if (!card) return null;
  const issuer = (pick(card, 'issuer') as string) || null;
  const identification = (pick(card, 'identification') as string) || null;
  return issuer || identification ? { issuer, identification } : null;
}

// Alcune banche pretendono di sapere da quale IP arriva l'utente: lo dichiarano in
// required_psu_headers nel catalogo /aspsps (UniCredit IT chiede "psu-ip-address").
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

  let body: { bankConnectionId?: string; dateFrom?: string; dateTo?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: { message: 'Body JSON non valido.' } }, 400);
  }
  const bankConnectionId = (body.bankConnectionId || '').trim();
  if (!bankConnectionId) return json({ error: { message: 'Serve "bankConnectionId".' } }, 400);
  const isIsoDate = (s: string) => /^\d{4}-\d{2}-\d{2}$/.test(s);
  const dateFrom = (body.dateFrom || '').trim();
  const dateTo = (body.dateTo || '').trim();
  if (dateFrom && !isIsoDate(dateFrom)) return json({ error: { message: '"dateFrom" va nel formato YYYY-MM-DD.' } }, 400);
  if (dateTo && !isIsoDate(dateTo)) return json({ error: { message: '"dateTo" va nel formato YYYY-MM-DD.' } }, 400);

  const supabase = createClient(supabaseUrl, supabaseServiceRoleKey);
  const { data: connection } = await supabase
    .from('cm_bank_connections')
    .select('*')
    .eq('id', bankConnectionId)
    .eq('user_id', userId)
    .maybeSingle();
  if (!connection) return json({ error: { message: 'Conto collegato non trovato.' } }, 404);
  if (!connection.account_id) {
    return json({ error: { message:
      'Il conto collegato non ha un account_id: il consenso è stato autorizzato ma la banca non ha ' +
      'elencato nessun conto. Controlla che il conto sia collegato nel Control Panel di Enable ' +
      'Banking ("Link accounts") e rifai il consenso.' } }, 400);
  }

  try {
    const jwt = await createEnableBankingJWT(appId, privateKeyPem);
    const psu = psuHeaders(req);

    // I saldi sono un'altra chiamata e un'altra autorizzazione: se la banca non li concede il
    // resto deve funzionare lo stesso, quindi l'errore qui non ferma i movimenti.
    let balances: { name: string | null; amount: number; currency: string | null; date: string | null }[] = [];
    try {
      const balRes = await fetch(`${ENABLE_BANKING_API_BASE}/accounts/${connection.account_id}/balances`, {
        headers: { Authorization: 'Bearer ' + jwt, ...psu },
      });
      if (balRes.ok) {
        const balData = await balRes.json();
        const list: any[] = Array.isArray(balData.balances) ? balData.balances : [];
        balances = list.map((b: any) => {
          const amountRaw = pick(b, 'balance_amount', 'balanceAmount') as any;
          return {
            name: (pick(b, 'name', 'balance_type', 'balanceType') as string) || null,
            amount: parseFloat((pick(amountRaw, 'amount') as string) || '0') || 0,
            currency: (pick(amountRaw, 'currency') as string) || null,
            date: (pick(b, 'reference_date', 'referenceDate', 'last_change_date_time') as string) || null,
          };
        });
      }
    } catch {
      // saldi non disponibili: si va avanti con i soli movimenti
    }

    const MAX_PAGES = 50;
    const rawTransactions: any[] = [];
    let continuationKey: string | null = null;
    let pageCount = 0;
    do {
      const url = new URL(`${ENABLE_BANKING_API_BASE}/accounts/${connection.account_id}/transactions`);
      if (dateFrom) url.searchParams.set('date_from', dateFrom);
      if (dateTo) url.searchParams.set('date_to', dateTo);
      if (continuationKey) url.searchParams.set('continuation_key', continuationKey);
      const txRes = await fetch(url.toString(), { headers: { Authorization: 'Bearer ' + jwt, ...psu } });
      const txData = await txRes.json();
      if (!txRes.ok) throw new Error('Errore Enable Banking: ' + (txData?.message || txRes.status));
      const page: any[] = Array.isArray(txData.transactions) ? txData.transactions : [];
      rawTransactions.push(...page);
      continuationKey = txData.continuation_key || null;
      pageCount++;
    } while (continuationKey && pageCount < MAX_PAGES);

    const transactions = rawTransactions.map((tx: any) => {
      const amountRaw = pick(tx, 'transaction_amount', 'transactionAmount') as any;
      const absAmount = Math.abs(parseFloat((pick(amountRaw, 'amount') as string) || '0') || 0);
      const indicator = ((pick(tx, 'credit_debit_indicator', 'creditDebitIndicator') as string) || '').toUpperCase();
      const isCredit = indicator === 'CRDT';

      // Chi sta dall'altro capo: il denaro che entra arriva da qualcuno (debtor), quello che
      // esce va a qualcuno (creditor).
      const party = (isCredit ? pick(tx, 'debtor') : pick(tx, 'creditor')) as any;
      const partyAccount = (isCredit
        ? pick(tx, 'debtor_account', 'debtorAccount')
        : pick(tx, 'creditor_account', 'creditorAccount')) as any;

      const remittanceInfo = pick(
        tx, 'remittance_information', 'remittanceInformation',
        'remittance_information_unstructured', 'remittanceInformationUnstructured'
      );
      const remittanceText = Array.isArray(remittanceInfo) ? remittanceInfo.join(' ') : ((remittanceInfo as string) || '');
      const code = pick(tx, 'bank_transaction_code', 'bankTransactionCode') as any;

      return {
        // Importo con segno: entrate positive, uscite negative. Il destinatario non deve
        // rifare il ragionamento su CRDT/DBIT, che è la parte che si sbaglia.
        amount: isCredit ? absAmount : -absAmount,
        currency: (pick(amountRaw, 'currency') as string) || null,
        creditDebit: indicator || null,
        bookingDate: (pick(tx, 'booking_date', 'bookingDate') as string) || null,
        valueDate: (pick(tx, 'value_date', 'valueDate') as string) || null,
        description: (remittanceText || '').trim(),
        counterpartyName: (((party && (pick(party, 'name') as string)) || '') as string).trim(),
        counterpartyIban: normIban(
          (partyAccount &&
            ((pick(partyAccount, 'iban') as string) ||
              (pick(pick(partyAccount, 'identification') as any, 'identification', 'iban') as string) ||
              (pick(partyAccount, 'identification') as string))) as string
        ) || null,
        externalId: (pick(tx, 'entry_reference', 'entryReference', 'transaction_id', 'transactionId') as string) || null,
        // Carta usata (emittente + ultime cifre), quando la banca la espone. Su un conto con più
        // carte è l'unico modo per dire di chi è una spesa senza spuntarle a mano una per una:
        // Spese Ada ci filtra sopra. Chi non la usa la ignora — il campo è solo in più.
        card: cardOf(tx),
        // Codice ISO 20022 (domain/family/sub_family): non è la causale numerica UniCredit che
        // usa l'import da CSV, ma è l'unica classificazione che passa dall'API.
        bankTransactionCode: code
          ? [pick(code, 'domain', 'domain_code'), pick(code, 'family', 'family_code'), pick(code, 'sub_family', 'subFamily', 'sub_family_code')]
              .filter(Boolean).join('/')
          : null,
        status: (pick(tx, 'status', 'transaction_status') as string) || null,
      };
    });

    // Traccia leggera: la lettura non scrive movimenti, ma sapere quando è stata fatta e quanti
    // ne sono arrivati serve quando "mancano dei movimenti" e bisogna capire da dove ripartire.
    await supabase.from('cm_sync_log').insert({
      user_id: userId,
      bank_connection_id: connection.id,
      finished_at: new Date().toISOString(),
      status: 'read',
      imported_count: transactions.length,
      error_message: `Lettura movimenti ${connection.aspsp_name}` +
        ` · dal ${dateFrom || 'inizio disponibile'} al ${dateTo || 'oggi'}` +
        ` · ${transactions.length} movimenti, ${pageCount} pagine` +
        (balances.length ? ` · saldi: ${balances.map((b) => `${b.name || '?'}=${b.amount}`).join(', ')}` : ' · saldi non disponibili'),
    });

    return json({
      account: { id: connection.account_id, aspspName: connection.aspsp_name, displayName: connection.display_name },
      balances,
      transactions,
      pages: pageCount,
      truncated: pageCount >= MAX_PAGES,
    });
  } catch (e) {
    return json({ error: { message: (e as Error).message } }, 502);
  }
});
