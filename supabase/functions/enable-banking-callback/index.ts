// Supabase Edge Function: riceve il redirect da Enable Banking dopo il consenso
// dell'utente sulla propria banca. Scambia il "code" con una sessione (POST /sessions),
// crea le righe in cm_bank_connections (una per conto restituito) e reindirizza il
// browser alla pagina da cui è partito il collegamento, con l'esito.
//
// Questo è l'URL da registrare come redirect_uri nell'applicazione Enable Banking:
//   https://<project-ref>.supabase.co/functions/v1/enable-banking-callback
//
// Richiede i Supabase Secrets: ENABLE_BANKING_APP_ID, ENABLE_BANKING_PRIVATE_KEY.
// SUPABASE_URL e SUPABASE_SERVICE_ROLE_KEY sono già forniti automaticamente da Supabase
// a ogni edge function.
//
// v1 — 2026-07-18
// v2 — 2026-08-03: la gestione dei conti collegati è passata a finanza.html ed è condivisa
//   tra moduli. Il redirect finale non è più fisso su cost-analysis.html: si ricava dal
//   "module" che enable-banking-connect ha messo nello state.
// v3 — 2026-08-03: lettura dei conti tollerante alle varianti di formato (UniCredit non
//   restituiva niente sul formato assunto per Revolut), con GET /sessions/{id} di riserva e
//   salvataggio del collegamento anche quando la lista resta vuota, più traccia diagnostica
//   in cm_sync_log.
// v4 — 2026-08-06: la riga "consent_request" scritta da enable-banking-connect viene agganciata
//   alla connessione appena creata (state.logId), così l'app sa dire cosa era stato chiesto per
//   quel collegamento. Con state.replaceConnectionId il consenso rifatto prende il posto di un
//   collegamento rimasto senza conto: i fondi ci vengono spostati sopra e la riga vuota sparisce.
// v5 — 2026-08-07: i conti nascono anonimi. Il nome e gli usi non arrivano più dallo state —
//   non c'erano ancora quando il consenso è partito — quindi ogni riga viene creata con
//   display_name NULL e uses '{}' e finanza.html apre subito il "battesimo" sui conti di
//   questa sessione (il session_id torna nel redirect). Via anche l'IBAN richiesto e la
//   diagnostica del consenso: la causa delle sessioni vuote è la whitelist, non ciò che si
//   spediva nella richiesta.

import { createClient } from 'npm:@supabase/supabase-js@2';

const ENABLE_BANKING_API_BASE = 'https://api.enablebanking.com';
const APP_BASE_URL = 'https://garsal.netlify.app';
// La gestione dei conti sta tutta in finanza.html (sezione Banche e Conti): c'è un solo
// posto dove tornare, qualunque uso avrà poi il conto.
const REDIRECT_URL = `${APP_BASE_URL}/finanza.html`;

function base64url(input: ArrayBuffer | string): string {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : new Uint8Array(input);
  let str = '';
  for (const b of bytes) str += String.fromCharCode(b);
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlDecode(input: string): string {
  const padded = input.replace(/-/g, '+').replace(/_/g, '/');
  return atob(padded);
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
  const body = { iss: 'enablebanking.com', aud: 'api.enablebanking.com', iat: now, exp: now + 3600 };
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(body))}`;
  const key = await importPrivateKey(privateKeyPem);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(signingInput));
  return `${signingInput}.${base64url(signature)}`;
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

// consentId identifica la sessione appena creata: finanza.html lo usa per aprire il
// battesimo esattamente sui conti nati da questo consenso, senza rimescolare quelli vecchi.
function redirectTo(status: 'success' | 'error' | 'partial', message?: string, consentId?: string | null): Response {
  const url = new URL(REDIRECT_URL);
  url.searchParams.set('bank_connect', status);
  if (consentId) url.searchParams.set('bank_connect_consent', consentId);
  if (message) url.searchParams.set('bank_connect_message', message);
  return new Response(null, { status: 302, headers: { Location: url.toString() } });
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
  const url = new URL(req.url);
  const code = url.searchParams.get('code');
  const stateRaw = url.searchParams.get('state');
  const bankError = url.searchParams.get('error') || url.searchParams.get('error_description');

  if (bankError) {
    return redirectTo('error', 'Consenso rifiutato o annullato sulla banca.');
  }
  if (!code || !stateRaw) {
    return redirectTo('error', 'Callback incompleto (code o state mancante).');
  }

  let state: {
    userId: string;
    institutionId?: string | null;
    aspspName: string;
    validUntil: string;
    replaceConnectionId?: string | null;
  };
  try {
    state = JSON.parse(base64urlDecode(stateRaw));
  } catch {
    return redirectTo('error', 'State non valido.');
  }

  const appId = Deno.env.get('ENABLE_BANKING_APP_ID');
  const privateKeyPem = Deno.env.get('ENABLE_BANKING_PRIVATE_KEY');
  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const supabaseServiceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!appId || !privateKeyPem || !supabaseUrl || !supabaseServiceRoleKey) {
    return redirectTo('error', 'Configurazione mancante lato server.');
  }

  try {
    const jwt = await createEnableBankingJWT(appId, privateKeyPem);
    const psu = psuHeaders(req);
    const sessionRes = await fetch(`${ENABLE_BANKING_API_BASE}/sessions`, {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + jwt, 'Content-Type': 'application/json', ...psu },
      body: JSON.stringify({ code }),
    });
    const sessionData = await sessionRes.json();
    if (!sessionRes.ok) {
      return redirectTo('error', 'Errore Enable Banking: ' + (sessionData?.message || sessionRes.status));
    }

    const supabase = createClient(supabaseUrl, supabaseServiceRoleKey);
    const sessionId = sessionData.session_id || sessionData.sessionId || null;

    // Prima versione: si leggeva solo sessionData.accounts come array di oggetti con "uid".
    // Con Revolut funzionava, con UniCredit no — la lista dei conti è tornata vuota e il
    // collegamento veniva buttato via con un errore, perdendo anche la sessione appena
    // ottenuta. Ora si accettano tutte le forme plausibili e, se non basta, si richiede la
    // sessione con una GET dedicata: certi ASPSP popolano i conti solo lì.
    //
    // La GET si ripete qualche volta con una pausa in mezzo. POST /sessions è la chiamata che
    // chiude l'autorizzazione E va a prendere l'elenco dei conti dalla banca: se quel secondo
    // pezzo è asincrono, rileggere la sessione un istante dopo trova la stessa lista vuota di
    // prima e il tentativo è sprecato. Con UniCredit la schermata di selezione mostra il conto
    // spuntato e la sessione torna comunque a zero conti, che è esattamente il quadro in cui
    // vale la pena aspettare qualche secondo prima di dichiarare fallimento. Il costo è nel
    // solo ramo che sta già andando male.
    const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
    let accounts = extractAccounts(sessionData);
    let accountsSource = 'POST /sessions';
    const retryDelays = [0, 2000, 4000];
    for (let i = 0; i < retryDelays.length && !accounts.length && sessionId; i++) {
      if (retryDelays[i]) await sleep(retryDelays[i]);
      const detailRes = await fetch(`${ENABLE_BANKING_API_BASE}/sessions/${sessionId}`, {
        headers: { Authorization: 'Bearer ' + jwt, ...psu },
      });
      const detailData = await detailRes.json().catch(() => ({}));
      if (detailRes.ok) {
        accounts = extractAccounts(detailData);
        accountsSource = `GET /sessions/{id}, tentativo ${i + 1}/${retryDelays.length}`;
      }
    }

    // I conti nascono anonimi: display_name NULL e uses '{}'. Il nome e gli usi si danno
    // dopo, in finanza.html, davanti alla lista di quello che la banca ha davvero restituito
    // — prima di quel momento non c'era niente da battezzare. L'IBAN va nella sua colonna e
    // non più dentro display_name, dove non si capiva se era un nome o un ripiego.
    //
    // Nessun conto nemmeno dopo i tentativi: la riga si salva lo stesso, senza account_id.
    // Il consenso è già stato dato e rifarlo da capo costa un altro giro di SCA; con la riga
    // in mano si vede cosa è arrivato e si può correggere. Il sync si rifiuta di partire
    // finché account_id è nullo, quindi non c'è il rischio di importare dal conto sbagliato.
    const baseRow = {
      user_id: state.userId,
      provider: 'enable_banking',
      institution_id: state.institutionId || null,
      aspsp_name: state.aspspName,
      display_name: null,
      uses: [] as string[],
      consent_id: sessionId,
      consent_expires_at: sessionData.access?.valid_until || state.validUntil,
      status: 'active',
    };
    const rows = accounts.length
      ? accounts.map((acc) => ({ ...baseRow, account_id: acc.uid, iban: acc.iban }))
      : [{ ...baseRow, account_id: null, iban: null }];

    const { data: inserted, error } = await supabase.from('cm_bank_connections').insert(rows).select('id, account_id');
    if (error) {
      return redirectTo('error', 'Errore salvataggio conto: ' + error.message);
    }
    const newConnectionId = inserted?.[0]?.id || null;

    // Consenso rifatto per rimpiazzare un collegamento rimasto senza conto: i fondi che lo
    // usavano vanno spostati sul nuovo, altrimenti restano agganciati a una riga che non
    // sincronizzerà mai — è il passaggio che a mano ci si dimentica. Si sostituisce solo una
    // riga davvero vuota e davvero dell'utente. Se anche il nuovo consenso nasce senza conti
    // la sostituzione si fa lo stesso, sul collegamento appena creato: due righe vuote per lo
    // stesso conto non aiutano nessuno, e quella nuova ha almeno una sessione fresca da
    // rileggere. Il fondo resta agganciato a un conto senza account_id, e l'app lo dice.
    const firstWithAccount = (inserted || []).find((r) => r.account_id)?.id || null;
    const replacementTarget = firstWithAccount || newConnectionId;
    if (state.replaceConnectionId && replacementTarget && state.replaceConnectionId !== replacementTarget) {
      const { data: old } = await supabase
        .from('cm_bank_connections')
        .select('id, account_id, display_name, uses, owner_person_id')
        .eq('id', state.replaceConnectionId)
        .eq('user_id', state.userId)
        .is('account_id', null)
        .maybeSingle();
      if (old) {
        // Il conto è lo stesso, cambia solo il consenso: nome, usi e proprietario erano già
        // stati dati sulla riga vuota e si trasferiscono. Senza, un consenso rifatto tornerebbe
        // "da battezzare" e sparirebbe dagli elenchi dei moduli che lo stavano già usando.
        await supabase.from('cm_bank_connections')
          .update({
            display_name: old.display_name,
            uses: old.uses || [],
            owner_person_id: old.owner_person_id,
          })
          .eq('id', replacementTarget)
          .eq('user_id', state.userId);
        await supabase.from('fnz_funds')
          .update({ bank_connection_id: replacementTarget })
          .eq('bank_connection_id', old.id)
          .eq('user_id', state.userId);
        // cm_sync_log.bank_connection_id cancella in cascata: le righe del tentativo fallito
        // si staccano prima, sono la storia di come ci si è arrivati.
        await supabase.from('cm_sync_log')
          .update({ bank_connection_id: null })
          .eq('bank_connection_id', old.id);
        await supabase.from('cm_bank_connections').delete().eq('id', old.id).eq('user_id', state.userId);
      }
    }

    if (!accounts.length) {
      // Una riga nel registro, non un dump: la causa di una sessione vuota è quasi sempre
      // la whitelist di Enable Banking (restricted mode), e quella non si legge nella
      // risposta della banca — è l'app a spiegarla.
      await supabase.from('cm_sync_log').insert({
        user_id: state.userId,
        bank_connection_id: newConnectionId,
        finished_at: new Date().toISOString(),
        status: 'no_accounts',
        imported_count: 0,
        error_message: `Consenso ${state.aspspName} autorizzato ma senza conti (ultimo tentativo: ${accountsSource}).`,
      });
      return redirectTo('partial', 'La banca non ha restituito nessun conto: il collegamento è stato salvato senza conto associato.', sessionId);
    }

    return redirectTo('success', undefined, sessionId);
  } catch (e) {
    return redirectTo('error', 'Errore imprevisto: ' + (e as Error).message);
  }
});
