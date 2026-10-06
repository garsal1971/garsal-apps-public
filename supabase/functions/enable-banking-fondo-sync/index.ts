// Supabase Edge Function: importa i movimenti di un fondo dal conto bancario collegato
// (Enable Banking / PSD2). Fratello di enable-banking-sync, che fa la stessa cosa per Spese
// Famiglia: qui però la destinazione è fnz_fund_contributions e l'attribuzione non passa
// dalla carta usata ma dalla CONTROPARTE del bonifico.
//
// Regole (fisse, non configurabili):
//   · credit_debit_indicator = CRDT → versamento (importo positivo), controparte = debtor
//   · credit_debit_indicator = DBIT → prelievo   (importo negativo), controparte = creditor
//   · match della controparte sui partecipanti del fondo: IBAN come chiave primaria, nome
//     normalizzato (maiuscolo, senza doppi spazi, confronto tra set di token) come ripiego
//   · nessun filtro su causale o importo: ogni bonifico da/verso un partecipante è un
//     movimento del fondo
//   · solo bonifici: prelievi bancomat, pagamenti carta e addebiti diretti restano fuori
//   · nessun match → la riga NON entra nel calcolo delle quote, ma viene comunque salvata con
//     status 'da_rivedere' e participant_id nullo, così finisce nella coda di revisione invece
//     di sparire senza lasciare traccia
//
// preview=true calcola tutto senza scrivere niente, per far rivedere l'elenco prima di
// confermare; excludeExternalIds sono le righe deselezionate nell'anteprima.
//
// Richiede i Supabase Secrets: ENABLE_BANKING_APP_ID, ENABLE_BANKING_PRIVATE_KEY.
// v1 — 2026-08-03

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

const normIban = (s: string | null | undefined): string =>
  (s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');

// "LECCISOTTI  Teresa " e "Teresa Leccisotti" devono corrispondere: l'ordine dei token non
// conta, la punteggiatura nemmeno. Titoli e forme societarie che comparirebbero in ogni nome
// non aiutano a distinguere e vengono scartati.
const NAME_STOPWORDS = new Set(['SIG', 'SIGR', 'SIGRA', 'SPA', 'SRL', 'DOTT', 'MR', 'MRS', 'DI', 'DE', 'DA']);
function nameTokens(s: string | null | undefined): string[] {
  return (s || '')
    .toUpperCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^A-Z0-9\s]/g, ' ')
    .split(/\s+/)
    .map((t) => t.trim())
    .filter((t) => t.length > 1 && !NAME_STOPWORDS.has(t));
}

// Un set è compatibile con l'altro se uno contiene tutto l'altro: "TERESA LECCISOTTI" trova
// "LECCISOTTI TERESA MARIA", ma un solo token in comune (il solo cognome, o il solo nome di
// battesimo) non basta per attribuire soldi a qualcuno.
function nameMatches(a: string | null | undefined, b: string | null | undefined): boolean {
  const ta = nameTokens(a);
  const tb = nameTokens(b);
  if (!ta.length || !tb.length) return false;
  const sa = new Set(ta);
  const sb = new Set(tb);
  const common = [...sa].filter((t) => sb.has(t)).length;
  if (common < 2) return common === 1 && sa.size === 1 && sb.size === 1;
  return common === Math.min(sa.size, sb.size);
}

// Solo bonifici. Enable Banking espone bank_transaction_code secondo lo schema ISO 20022
// (domain / family / sub_family): i bonifici stanno in PMNT/ICDT (inviati) e PMNT/RCDT
// (ricevuti), il prelievo bancomat è CCRD/CWDL, i pagamenti carta CCRD/POSD.
// I codici di UniCredit non sono stati verificati su una risposta reale: quando il codice
// manca del tutto si ripiega sulla presenza di un IBAN di controparte (una carta o un ATM non
// ce l'hanno) e sul testo della causale. Le righe scartate vengono contate e mostrate
// nell'anteprima, così se il filtro sbaglia si vede invece di perdere movimenti in silenzio.
const TRANSFER_FAMILIES = new Set(['ICDT', 'RCDT']);
const NON_TRANSFER_FAMILIES = new Set(['CCRD', 'CWDL']);
const NON_TRANSFER_TEXT = /(PRELIEVO|BANCOMAT|ATM|POS\b|CARTA|COMMISSION|CANONE|IMPOSTA|BOLLO)/i;
function isTransfer(tx: any, counterpartyIban: string, text: string): boolean {
  const code = pick(tx, 'bank_transaction_code', 'bankTransactionCode') as any;
  const family = ((code && (pick(code, 'family', 'code') as string)) || '').toUpperCase();
  const subFamily = ((code && (pick(code, 'sub_family', 'subFamily') as string)) || '').toUpperCase();
  if (NON_TRANSFER_FAMILIES.has(family) || NON_TRANSFER_FAMILIES.has(subFamily)) return false;
  if (TRANSFER_FAMILIES.has(family) || TRANSFER_FAMILIES.has(subFamily)) return true;
  if (NON_TRANSFER_TEXT.test(text)) return false;
  return counterpartyIban.length > 0;
}

async function fetchAllRows(
  makeQuery: (from: number, to: number) => PromiseLike<{ data: any[] | null; error: any }>
): Promise<any[]> {
  const PAGE_SIZE = 1000;
  const all: any[] = [];
  let from = 0;
  while (true) {
    const { data, error } = await makeQuery(from, from + PAGE_SIZE - 1);
    if (error) throw new Error(error.message);
    all.push(...(data || []));
    if (!data || data.length < PAGE_SIZE) break;
    from += PAGE_SIZE;
  }
  return all;
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
    new Response(JSON.stringify(payload), {
      status,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });

  const appId = Deno.env.get('ENABLE_BANKING_APP_ID');
  const privateKeyPem = Deno.env.get('ENABLE_BANKING_PRIVATE_KEY');
  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const supabaseServiceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!appId || !privateKeyPem || !supabaseUrl || !supabaseServiceRoleKey) {
    return json({ error: { message: 'Configurazione mancante (secrets Enable Banking o Supabase).' } }, 500);
  }

  const authHeader = req.headers.get('authorization') || '';
  const userId = decodeSupabaseJwtSub(authHeader.replace(/^Bearer\s+/i, ''));
  if (!userId) return json({ error: { message: 'Utente non autenticato.' } }, 401);

  let body: { fundId?: string; preview?: boolean; excludeExternalIds?: string[] };
  try {
    body = await req.json();
  } catch {
    return json({ error: { message: 'Body JSON non valido.' } }, 400);
  }
  const fundId = (body.fundId || '').trim();
  if (!fundId) return json({ error: { message: 'Serve "fundId".' } }, 400);
  const preview = body.preview === true;
  const excludeExternalIds = new Set((body.excludeExternalIds || []).filter((x) => typeof x === 'string'));

  const supabase = createClient(supabaseUrl, supabaseServiceRoleKey);

  const { data: fund } = await supabase
    .from('fnz_funds')
    .select('*')
    .eq('id', fundId)
    .eq('user_id', userId)
    .maybeSingle();
  if (!fund) return json({ error: { message: 'Fondo non trovato.' } }, 404);
  if (!fund.bank_connection_id) {
    return json({ error: { message: 'Questo fondo non ha un conto bancario collegato.' } }, 400);
  }

  const { data: connection } = await supabase
    .from('cm_bank_connections')
    .select('*')
    .eq('id', fund.bank_connection_id)
    .eq('user_id', userId)
    .maybeSingle();
  if (!connection) return json({ error: { message: 'Conto collegato non trovato.' } }, 404);
  if (!connection.account_id) {
    return json({ error: { message: 'Il conto collegato non ha un account_id valido (non è stato collegato via consenso bancario).' } }, 400);
  }

  const { data: participants } = await supabase
    .from('fnz_fund_participants')
    .select('id, name, iban, active')
    .eq('fund_id', fundId)
    .eq('user_id', userId);
  const activeParticipants = (participants || []).filter((p) => p.active);
  if (!activeParticipants.length) {
    return json({ error: { message: 'Nessun partecipante attivo: senza anagrafica non si può attribuire nessun movimento.' } }, 400);
  }

  let syncLog: { id: string } | null = null;
  if (!preview) {
    const { data } = await supabase
      .from('cm_sync_log')
      .insert({ user_id: userId, bank_connection_id: connection.id, status: 'running' })
      .select()
      .single();
    syncLog = data;
  }

  try {
    const jwt = await createEnableBankingJWT(appId, privateKeyPem);

    const MAX_PAGES = 50;
    const rawTransactions: unknown[] = [];
    let continuationKey: string | null = null;
    let pageCount = 0;
    do {
      const url = new URL(`${ENABLE_BANKING_API_BASE}/accounts/${connection.account_id}/transactions`);
      if (continuationKey) url.searchParams.set('continuation_key', continuationKey);
      const txRes = await fetch(url.toString(), { headers: { Authorization: 'Bearer ' + jwt, ...psuHeaders(req) } });
      const txData = await txRes.json();
      if (!txRes.ok) throw new Error('Errore Enable Banking: ' + (txData?.message || txRes.status));
      const page: unknown[] = Array.isArray(txData.transactions) ? txData.transactions : [];
      rawTransactions.push(...page);
      continuationKey = txData.continuation_key || null;
      pageCount++;
    } while (continuationKey && pageCount < MAX_PAGES);

    const skippedNonTransfer: { date: string | null; amount: number; description: string }[] = [];

    const rows = rawTransactions.map((tx: any) => {
      const amountRaw = pick(tx, 'transaction_amount', 'transactionAmount') as any;
      const absAmount = Math.abs(parseFloat(pick(amountRaw, 'amount') as string) || 0);
      const indicator = ((pick(tx, 'credit_debit_indicator', 'creditDebitIndicator') as string) || '').toUpperCase();
      const isCredit = indicator === 'CRDT';
      const amount = isCredit ? absAmount : -absAmount;
      const type: 'versamento' | 'prelievo' = isCredit ? 'versamento' : 'prelievo';

      // Il denaro che entra arriva da qualcuno (debtor), quello che esce va a qualcuno
      // (creditor): la controparte è sempre "l'altro capo" del bonifico.
      const party = (isCredit ? pick(tx, 'debtor') : pick(tx, 'creditor')) as any;
      const partyAccount = (isCredit
        ? pick(tx, 'debtor_account', 'debtorAccount')
        : pick(tx, 'creditor_account', 'creditorAccount')) as any;
      const counterpartyName = ((party && (pick(party, 'name') as string)) || '').trim();
      const counterpartyIban = normIban(
        (partyAccount &&
          ((pick(partyAccount, 'iban') as string) ||
            (pick(pick(partyAccount, 'identification') as any, 'identification', 'iban') as string) ||
            (pick(partyAccount, 'identification') as string))) as string
      );

      const remittanceInfo = pick(
        tx,
        'remittance_information',
        'remittanceInformation',
        'remittance_information_unstructured',
        'remittanceInformationUnstructured'
      );
      const remittanceText = Array.isArray(remittanceInfo) ? remittanceInfo.join(' ') : ((remittanceInfo as string) || '');
      const date = (pick(tx, 'booking_date', 'bookingDate', 'value_date', 'valueDate') as string) || null;
      const externalId = (pick(tx, 'entry_reference', 'entryReference', 'transaction_id', 'transactionId') as string) || null;

      return {
        date,
        amount,
        type,
        counterpartyName,
        counterpartyIban,
        remittanceText,
        externalId,
        transfer: isTransfer(tx, counterpartyIban, `${remittanceText} ${counterpartyName}`),
        raw: tx,
      };
    }).filter((t) => {
      if (!t.date || !t.externalId || t.amount === 0) return false;
      if (!t.transfer) {
        skippedNonTransfer.push({ date: t.date, amount: t.amount, description: t.remittanceText || t.counterpartyName });
        return false;
      }
      return true;
    });

    // Idempotenza: quello che c'è già non si ripropone nemmeno in anteprima.
    const existing = await fetchAllRows((from, to) =>
      supabase
        .from('fnz_fund_contributions')
        .select('external_id')
        .eq('user_id', userId)
        .eq('bank_connection_id', connection.id)
        .not('external_id', 'is', null)
        .range(from, to)
    );
    const alreadyImported = new Set(existing.map((r) => r.external_id));

    const candidates = rows.filter((t) => !alreadyImported.has(t.externalId));

    const matchParticipant = (t: (typeof rows)[number]) => {
      if (t.counterpartyIban) {
        const byIban = activeParticipants.filter((p) => p.iban && normIban(p.iban) === t.counterpartyIban);
        if (byIban.length === 1) return { participant: byIban[0], via: 'iban' as const };
        if (byIban.length > 1) return { participant: null, via: 'ambiguo' as const };
      }
      const byName = activeParticipants.filter((p) => nameMatches(p.name, t.counterpartyName));
      if (byName.length === 1) return { participant: byName[0], via: 'nome' as const };
      if (byName.length > 1) return { participant: null, via: 'ambiguo' as const };
      return { participant: null, via: 'nessuno' as const };
    };

    const classified = candidates.map((t) => {
      const m = matchParticipant(t);
      return {
        ...t,
        participantId: m.participant?.id || null,
        participantName: m.participant?.name || null,
        matchedVia: m.via,
        status: m.participant ? ('auto' as const) : ('da_rivedere' as const),
      };
    });

    if (preview) {
      return json({
        preview: true,
        items: classified
          .map((t) => ({
            externalId: t.externalId,
            date: t.date,
            amount: t.amount,
            type: t.type,
            counterpartyName: t.counterpartyName,
            counterpartyIban: t.counterpartyIban,
            description: t.remittanceText,
            participantId: t.participantId,
            participantName: t.participantName,
            matchedVia: t.matchedVia,
            status: t.status,
          }))
          .sort((a, b) => (b.date || '').localeCompare(a.date || '')),
        totalFetched: rawTransactions.length,
        pages: pageCount,
        alreadyImported: rows.length - candidates.length,
        skippedNonTransfer: skippedNonTransfer.length,
        skippedNonTransferSample: skippedNonTransfer.slice(0, 10),
      });
    }

    const toInsert = classified.filter((t) => !excludeExternalIds.has(t.externalId || ''));
    let insertedCount = 0;
    if (toInsert.length) {
      const insertRows = toInsert.map((t) => ({
        user_id: userId,
        fund_id: fundId,
        // participant resta il nome mostrato dall'app: per una riga da rivedere è quello che
        // ha scritto la banca, così si capisce chi cercare quando la si sistema a mano.
        participant: t.participantName || t.counterpartyName || '(da abbinare)',
        participant_id: t.participantId,
        date: t.date,
        amount: t.amount,
        type: t.type,
        status: t.status,
        external_id: t.externalId,
        bank_connection_id: connection.id,
        counterparty_name: t.counterpartyName || null,
        counterparty_iban: t.counterpartyIban || null,
        notes: t.remittanceText || '',
        raw: t.raw,
      }));
      const { data: insertedRows, error: insertError } = await supabase
        .from('fnz_fund_contributions')
        .upsert(insertRows, { onConflict: 'bank_connection_id,external_id', ignoreDuplicates: true })
        .select('id');
      if (insertError) throw new Error(insertError.message);
      insertedCount = (insertedRows || []).length;
    }

    // amount_adjusted è una cache: dopo un import va rigenerata, altrimenti le righe nuove
    // restano senza valore rivalutato (e un movimento più vecchio di tutti gli altri sposta
    // l'anno di riferimento dell'intero fondo).
    let recalc: unknown = null;
    if (insertedCount) {
      const { data: recalcData } = await supabase.rpc('fnz_fund_recalc_adjusted', { p_fund_id: fundId });
      recalc = recalcData;
    }

    if (syncLog) {
      await supabase
        .from('cm_sync_log')
        .update({ finished_at: new Date().toISOString(), status: 'success', imported_count: insertedCount })
        .eq('id', syncLog.id);
    }

    return json({
      imported: insertedCount,
      toReview: toInsert.filter((t) => t.status === 'da_rivedere').length,
      totalFetched: rawTransactions.length,
      pages: pageCount,
      skippedNonTransfer: skippedNonTransfer.length,
      recalc,
    });
  } catch (e) {
    if (syncLog) {
      await supabase
        .from('cm_sync_log')
        .update({ finished_at: new Date().toISOString(), status: 'error', error_message: (e as Error).message })
        .eq('id', syncLog.id);
    }
    return json({ error: { message: (e as Error).message } }, 502);
  }
});
