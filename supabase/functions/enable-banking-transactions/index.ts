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
// v1 — 2026-08-06 · v2 — 2026-11-28: la lettura sta in _shared/eb-movimenti.ts, comune con sal-sync

import { createClient } from 'npm:@supabase/supabase-js@2';
import { createEnableBankingJWT, decodeSupabaseJwtSub, psuHeaders } from '../_shared/enable-banking.ts';
import { leggiMovimenti } from '../_shared/eb-movimenti.ts';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

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

    const { balances, transactions, pages: pageCount, truncated } = await leggiMovimenti(
      connection.account_id, jwt, psu, dateFrom || undefined, dateTo || undefined);

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
      truncated,
    });
  } catch (e) {
    return json({ error: { message: (e as Error).message } }, 502);
  }
});
