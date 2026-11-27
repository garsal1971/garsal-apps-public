// ============================================================
// cr-sync v1.1.0 — sincronizzazione del Conto Risparmio (conto-risparmio-teresa.html)
// e, con { conto: 'rosa' }, della Cassa Casa Rosa (casarosa.html): stesso schema, tabelle senza _terr.
//
// Gemella di sal-sync, sullo schema del Conto Risparmio:
//   • il job pg_cron 'cr-sync', il 28 di ogni mese (service role): i conti spuntati
//     'conto_risparmio', e a lavoro finito una notifica al PROPRIETARIO del conto (Salvatore:
//     Teresa no, di proposito) su Telegram, telefono e browser;
//   • il pulsante «🏦 Sincronizza dal conto» della pagina, col JWT dell'utente: nessuna notifica.
//
// ⚠️ Doppioni: la stessa regola che la pagina usava nell'import dalla banca (loadBankMovements),
// da cambiare insieme se cambia:
//   • stessa data + importo + descrizione → certo, si salta;
//   • stessa data + importo               → SOSPETTO: entra PROVVISORIO (provvisorio, duplicato_di),
//     fuori dai totali e dal fondo finché nella pagina non si dice «Tieni» o «È un doppione».
// ⚠️ Categorie: guessCategoryFromBank copiata dalla pagina; quel che non si riconosce va in
// «da attribuire». Saldo: una riga in cntrs_saldi_terr, come confirmBankImport.
// ============================================================

import { createClient } from 'npm:@supabase/supabase-js@2';
import { createEnableBankingJWT, psuHeaders } from '../_shared/enable-banking.ts';
import { leggiMovimenti, type Movimento } from '../_shared/eb-movimenti.ts';

const VERSION = '1.1.0';
const SOVRAPPOSIZIONE = 7;
const PRIMA_VOLTA = 90;
// I due conti gemelli: cambiano tabelle, uso del conto, la categoria del giroconto e la notifica.
const CONTI = {
  risparmio: {
    uso: 'conto_risparmio', tx: 'cntrs_transactions_terr', cat: 'cntrs_categories_terr', saldi: 'cntrs_saldi_terr',
    contante: 'AFFITTO CONTANTE TERRASINI', nome: 'Conto risparmio', emoji: '🏡',
    entita: '5a100000-0000-4000-8000-000000000002', kind: 'cr_sync',
  },
  rosa: {
    uso: 'casa_rosa', tx: 'cntrs_transactions', cat: 'cntrs_categories', saldi: 'cntrs_saldi',
    contante: 'AFFITTO CONTANTE ROSA', nome: 'Cassa Casa Rosa', emoji: '🏠',
    entita: '5a100000-0000-4000-8000-000000000003', kind: 'rosa_sync',
  },
} as const;
type Conto = typeof CONTI[keyof typeof CONTI];

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

function chiChiama(req: Request): { role: string | null; sub: string | null } {
  const t = (req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
  try {
    const p = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return { role: p.role ?? null, sub: p.sub ?? null };
  } catch { return { role: null, sub: null }; }
}

// Gemella di guessCategoryFromBank in conto-risparmio-teresa.html e casarosa.html.
function categoriaDa(tx: Movimento, C: Conto): string {
  const t = `${tx.description || ''} ${tx.counterpartyName || ''}`.toLowerCase();
  const code = (tx.bankTransactionCode || '').toUpperCase();
  if (/f23|f24|imu|tributi/.test(t)) return 'IMU';
  if (/mutuo|rata mutu/.test(t)) return 'RATA MUTUO';
  if (/bollo|commission|spese conto|canone/.test(t)) return 'SPESE BANCARIE';
  if (/interess|competenze/.test(t)) return 'INTERESSI BANCARIE';
  if (/giroconto/.test(t)) return C.contante;
  if (/affitto|locazione|canone di locazione/.test(t)) return 'AFFITTO BONIFICO CASA';
  if (tx.amount > 0 && /RCDT/.test(code)) return 'AFFITTO BONIFICO CASA';
  return 'da attribuire';
}
// Gemella di bankTxDescription.
function descrizioneDa(tx: Movimento): string {
  const parts = [tx.description, tx.counterpartyName].map((s) => (s || '').trim()).filter(Boolean);
  const uniq = parts.filter((p, i) => !parts.slice(0, i).some((q) => q.toLowerCase().includes(p.toLowerCase())));
  return uniq.join(' · ') || tx.bankTransactionCode || 'Movimento';
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
  const json = (p: unknown, status = 200) =>
    new Response(JSON.stringify(p), { status, headers: { ...cors, 'Content-Type': 'application/json' } });

  const appId = Deno.env.get('ENABLE_BANKING_APP_ID');
  const pem = Deno.env.get('ENABLE_BANKING_PRIVATE_KEY');
  const url = Deno.env.get('SUPABASE_URL')!;
  const service = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
  if (!appId || !pem) return json({ error: { message: 'Secrets di Enable Banking mancanti.' } }, 500);

  const { role, sub } = chiChiama(req);
  const daJob = role === 'service_role';
  if (!daJob && !(role === 'authenticated' && sub)) return json({ error: { message: 'Non autorizzato.' } }, 401);

  let body: { dateFrom?: string; conto?: string } = {};
  try { body = await req.json(); } catch { /* vuoto */ }
  const C: Conto = CONTI[(body.conto === 'rosa' ? 'rosa' : 'risparmio')];
  const dateFromChiesta = /^\d{4}-\d{2}-\d{2}$/.test(body.dateFrom || '') ? body.dateFrom! : null;

  const sb = createClient(url, service);
  try {
    let qc = sb.from('cm_bank_connections').select('*').contains('uses', [C.uso]).not('account_id', 'is', null)
      .order('created_at', { ascending: false });
    if (!daJob) qc = qc.eq('user_id', sub);
    const { data: conns, error: ec } = await qc;
    if (ec) throw ec;
    const perUtente = new Map<string, any>();
    for (const c of conns || []) if (!perUtente.has(c.user_id)) perUtente.set(c.user_id, c);
    if (!perUtente.size) {
      return json({ ok: false, error: { message: `Nessun conto spuntato «${C.nome}» in Finanza → Banche e Conti.` } }, daJob ? 200 : 404);
    }

    const jwt = await createEnableBankingJWT(appId, pem);
    const psu = daJob ? {} : psuHeaders(req);
    const esiti: any[] = [];

    for (const [userId, conn] of perUtente) {
      const esito: any = { user_id: userId, conto: conn.id };
      try {
        const { data: archivio, error: ea } = await sb.from(C.tx)
          .select('id,data,importo_voce,descrizione').eq('user_id', userId).order('data', { ascending: true }).limit(10000);
        if (ea) throw ea;
        const { data: cats, error: ecat } = await sb.from(C.cat).select('id,name').eq('user_id', userId);
        if (ecat) throw ecat;
        const categorie = [...(cats || [])];
        const idCategoria = async (nome: string) => {
          const c = categorie.find((x) => x.name.toLowerCase() === nome.toLowerCase())
            || categorie.find((x) => x.name.toLowerCase() === 'da attribuire');
          if (c) return c.id;
          const { data: nuova, error } = await sb.from(C.cat).insert({ name: 'da attribuire', user_id: userId }).select('id,name').single();
          if (error) throw error;
          categorie.push(nuova);
          return nuova.id;
        };

        const ultima = archivio?.length ? archivio[archivio.length - 1].data : null;
        const dateFrom = dateFromChiesta || (ultima
          ? new Date(new Date(ultima + 'T00:00:00Z').getTime() - SOVRAPPOSIZIONE * 86400000).toISOString().slice(0, 10)
          : new Date(Date.now() - PRIMA_VOLTA * 86400000).toISOString().slice(0, 10));
        const letti = await leggiMovimenti(conn.account_id, jwt, psu, dateFrom);

        const chiave = (d: string, imp: number) => `${d}|${(+imp).toFixed(2)}`;
        const esatti = new Set((archivio || []).map((t) => `${chiave(t.data, t.importo_voce)}|${(t.descrizione || '').trim().toLowerCase()}`));
        const perGiornoImporto = new Map<string, any>();
        for (const t of archivio || []) { const k = chiave(t.data, t.importo_voce); if (!perGiornoImporto.has(k)) perGiornoImporto.set(k, t); }

        const righe: any[] = [];
        let saltati = 0;
        for (const t of letti.transactions) {
          const data = (t.bookingDate || t.valueDate || '').slice(0, 10);
          if (!t.amount || !data) continue;
          const descrizione = descrizioneDa(t);
          const k = chiave(data, t.amount);
          if (esatti.has(`${k}|${descrizione.trim().toLowerCase()}`)) { saltati++; continue; }
          const simile = perGiornoImporto.get(k) || null;
          const cat = categoriaDa(t, C);
          righe.push({
            user_id: userId, data, descrizione, importo_voce: t.amount, category_id: await idCategoria(cat),
            note: '', anno: parseInt(data.slice(0, 4), 10),
            provvisorio: !!simile, duplicato_di: simile?.id || null,
            _cat: cat,
          });
          // Due movimenti identici nello stesso giro non si saltano a vicenda.
        }

        let inseriti: any[] = [];
        if (righe.length) {
          const { data: ins, error: ei } = await sb.from(C.tx)
            .insert(righe.map(({ _cat, ...r }) => r)).select('id,provvisorio');
          if (ei) throw ei;
          inseriti = ins || [];
        }
        esito.importati = inseriti.length;
        esito.provvisori = inseriti.filter((r) => r.provvisorio).length;
        esito.da_attribuire = righe.filter((r) => r._cat === 'da attribuire').length;
        esito.saltati = saltati;
        esito.dal = dateFrom;
        esito.troncato = letti.truncated;

        // Il saldo, come confirmBankImport: una riga nuova in cntrs_saldi_terr.
        const bal = letti.balances.find((b) => /disp|avai|clbd|closing|itbd/i.test(b.name || '')) || letti.balances[0];
        if (bal && bal.amount) {
          const { error: es } = await sb.from(C.saldi).insert({
            user_id: userId, data: (bal.date || new Date().toISOString()).slice(0, 10),
            saldo_contabile: bal.amount, saldo_disponibile: bal.amount,
            note: `Letto dalla banca (cr-sync${daJob ? ', job' : ''})`,
          });
          if (es) console.warn('[cr-sync] saldo non salvato', es.message);
          else esito.saldo = bal.amount;
        }

        await sb.from('cm_sync_log').insert({
          user_id: userId, bank_connection_id: conn.id, finished_at: new Date().toISOString(),
          status: 'success', imported_count: esito.importati,
          error_message: `cr-sync ${VERSION} ${C.uso}${daJob ? ' (job)' : ''} · dal ${dateFrom} · ${letti.transactions.length} letti, ` +
            `${esito.importati} importati (${esito.provvisori} provvisori, ${esito.da_attribuire} da attribuire), ${saltati} già presenti`,
        });
      } catch (e) {
        esito.errore = (e as Error).message || String(e);
        await sb.from('cm_sync_log').insert({
          user_id: userId, bank_connection_id: conn.id, finished_at: new Date().toISOString(),
          status: 'error', imported_count: 0, error_message: `cr-sync ${VERSION} ${C.uso}: ${esito.errore}`,
        });
      }
      if (daJob) {
        try { await notifica(sb, userId, esito, C); } catch (e) { console.warn('[cr-sync] notifica non accodata', (e as Error).message); }
      }
      esiti.push(esito);
    }

    if (daJob) {
      await fetch(`${url}/functions/v1/send-notifications`, {
        method: 'POST', headers: { Authorization: `Bearer ${service}`, 'Content-Type': 'application/json' }, body: '{}',
      }).catch(() => {});
    }
    console.log('[cr-sync]', JSON.stringify(esiti));
    const unico = !daJob ? esiti[0] : null;
    if (unico?.errore) return json({ ok: false, error: { message: unico.errore } }, 502);
    return json({ ok: true, version: VERSION, esiti });
  } catch (e) {
    console.error('[cr-sync] errore', e);
    return json({ ok: false, error: { message: (e as Error).message || String(e) } }, 500);
  }
});

/** La notifica al solo proprietario del conto, come in sal-sync (regole 'spese' spente). */
async function notifica(sb: any, userId: string, e: any, C: Conto) {
  const titolo = e.errore ? `⚠️ ${C.nome}: sincronizzazione fallita` : `${C.emoji} ${C.nome} sincronizzato`;
  const corpo = e.errore
    ? `${e.errore}\nRiprova da Finanza → ${C.nome}.`
    : [
        `${e.importati} movimenti importati`,
        e.da_attribuire ? `${e.da_attribuire} da attribuire` : null,
        e.provvisori ? `⚠️ ${e.provvisori} provvisori da verificare (possibili doppioni)` : null,
        e.saldo != null ? `Saldo: ${Number(e.saldo).toLocaleString('it-IT', { style: 'currency', currency: 'EUR' })}` : null,
      ].filter(Boolean).join('\n');

  const canali: string[] = [];
  const { data: imp } = await sb.from('cm_user_notification_settings').select('telegram_enabled,telegram_chat_id').eq('user_id', userId).maybeSingle();
  if (imp?.telegram_enabled && imp?.telegram_chat_id) canali.push('telegram');
  const { data: disp } = await sb.from('cm_push_devices').select('platform').eq('user_id', userId).eq('enabled', true);
  if ((disp || []).some((d: any) => d.platform !== 'web')) canali.push('android');
  if ((disp || []).some((d: any) => d.platform === 'web')) canali.push('browser');

  const oggi = new Date().toISOString().slice(0, 10);
  const ora = new Date().toISOString().slice(11, 16);
  const righe = [];
  for (const channel of canali) {
    const { data: regola, error: er } = await sb.from('cm_notification_rules').upsert({
      user_id: userId, app: 'spese', entity_id: C.entita, entity_type: 'task', channel,
      entity_title: C.nome, enabled: false, reminder_presets: { kind: C.kind }, notification_spec: {},
    }, { onConflict: 'user_id,app,entity_id,channel' }).select('id').single();
    if (er) throw er;
    righe.push({
      rule_id: regola.id, user_id: userId, app: 'spese', entity_id: C.entita,
      title: titolo, body: corpo, channel, fire_at: new Date().toISOString(), status: 'pending',
      occurrence_id: `${regola.id}:${oggi}:${ora}`, metadata: { telegram_cancel_button: false },
    });
  }
  if (righe.length) {
    const { error } = await sb.from('cm_notification_queue').insert(righe);
    if (error) throw error;
  }
}
