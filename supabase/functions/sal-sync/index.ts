// ============================================================
// sal-sync v1.1.0 — sincronizzazione del Conto Personale (Spese Personali) e del conto di Ada
//
// ⚠️ Due conti, una regola sola: il corpo { conto: 'ada' } sceglie Spese Ada (tabelle ada_*,
// uso 'spese_ada', SOLO LE USCITE, e solo la carta salvata in cm_settings.ada_carta se c'è;
// nessun saldo). Senza 'conto' è il Conto Personale di sempre (CONTI.sal).
//
// Chi la chiama:
//   • il job pg_cron 'sal-sync', il 28 di ogni mese (service role): tutti gli utenti che hanno
//     un conto spuntato 'spese_sal', e a lavoro finito una notifica su Telegram, telefono e
//     browser;
//   • il pulsante «🏦 Sincronizza dal conto» di spese-personali.html, col JWT dell'utente: il
//     suo solo conto, nessuna notifica (il riepilogo lo mostra la pagina).
//
// Cosa fa, per ogni conto: legge movimenti e saldo (_shared/eb-movimenti.ts), salva il saldo in
// cm_settings.sal_saldo, e importa in sal_transactions i movimenti che non ci sono già.
//
// ⚠️ I doppioni sono la stessa rete a tre maglie di indiceDoppioni() in spese-personali.html, e
// vanno cambiate insieme:
//   1. stesso external_id                        → certo, si salta;
//   2. stessa data + importo + descrizione       → certo, si salta;
//   3. stesso importo entro DUP_MARGINE giorni   → SOSPETTO: entra PROVVISORIO
//      (provvisorio = true, duplicato_di = il movimento che somiglia). Fuori dai totali finché
//      nella pagina non si dice «✓ Tieni» o «🗑 È un doppione».
// ⚠️ Le categorie si assegnano con le regole della fonte 'bank' di sal_merchant_map: stessa
// normalizzazione e stesso jolly «%» di normalizeMerchant / matcherRegola nella pagina.
// ============================================================

import { createClient } from 'npm:@supabase/supabase-js@2';
import { createEnableBankingJWT, psuHeaders } from '../_shared/enable-banking.ts';
import { leggiMovimenti, type Saldo } from '../_shared/eb-movimenti.ts';

const VERSION = '1.1.0';
const DUP_MARGINE = 3;          // giorni: lo stesso default della pagina
const SOVRAPPOSIZIONE = 7;      // si rilegge da 7 giorni prima dell'ultimo movimento in archivio
const PRIMA_VOLTA = 90;         // senza movimenti in archivio: gli ultimi 90 giorni
// entity_id è uuid: un id fisso per conto («la sincronizzazione di quel conto»).
type Conto = { uso: string; tx: string; mm: string; saldo: string | null; soloUscite: boolean;
  carta: string | null; entita: string; kind: string; nome: string; pagina: string; etichetta: string };
const CONTI: Record<string, Conto> = {
  sal: { uso: 'spese_sal', tx: 'sal_transactions', mm: 'sal_merchant_map', saldo: 'sal_saldo', soloUscite: false,
         carta: null, entita: '5a100000-0000-4000-8000-000000000001', kind: 'sal_sync',
         nome: 'Conto personale', pagina: 'Spese Personali', etichetta: 'Spese Personali' },
  ada: { uso: 'spese_ada', tx: 'ada_transactions', mm: 'ada_merchant_map', saldo: null, soloUscite: true,
         carta: 'ada_carta', entita: '5a100000-0000-4000-8000-000000000004', kind: 'ada_sync',
         nome: 'Spese Ada', pagina: 'Spese Ada', etichetta: 'Spese Ada' },
};

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

/** role e sub del JWT: la firma l'ha già verificata la piattaforma (verify_jwt). */
function chiChiama(req: Request): { role: string | null; sub: string | null } {
  const t = (req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
  try {
    const p = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return { role: p.role ?? null, sub: p.sub ?? null };
  } catch { return { role: null, sub: null }; }
}

// ── Le regole dei negozi: gemelle di spese-personali.html ──────────────────────
const POS_MERCHANT_RE = /\bDI\s+[A-Z]{3}\s+[\d.,]+\s+(.+)$/i;
const merchantText = (desc: string) => {
  const s = (desc || '').replace(/\s+/g, ' ').trim();
  const m = s.match(POS_MERCHANT_RE);
  return (m && m[1].trim()) || s;
};
const normalizeMerchant = (desc: string) => merchantText(desc).toUpperCase().replace(/[*#]?\s*\d{3,}$/, '').trim();
const RE_SPECIALI = /[.*+?^${}()|[\]\\]/g;
function aggancia(key: string, hay: string): boolean {
  const k = (key || '').trim().toUpperCase();
  if (!k) return false;
  if (!k.includes('%')) return hay.includes(k);
  return new RegExp(k.split('%').map((p) => p.replace(RE_SPECIALI, '\\$&')).join('.*')).test(hay);
}
const peso = (key: string) => (key || '').replace(/%/g, '').length;
const CARD_IN_DESC_RE = /\bCARTA\s+(\d{4,})\b/i;
const cardFromDescription = (desc: string) => ((desc || '').match(CARD_IN_DESC_RE) || [])[1] || null;

// ── I doppioni: gemelli di indiceDoppioni() ─────────────────────────────────────
const chiaveEsatta = (data: string, importo: number, desc: string) =>
  `${data}|${(+importo).toFixed(2)}|${(desc || '').replace(/\s+/g, ' ').trim().toLowerCase()}`;
const numeroGiorno = (d: string) => Math.round(new Date(`${(d || '').slice(0, 10)}T00:00:00Z`).getTime() / 86400000);

// Fra i saldi si preferisce il contabile: gemello di sceltaSaldo() nella pagina.
const TIPI_SALDO = ['CLBD', 'ITBD', 'closingBooked', 'interimBooked', 'CLAV', 'ITAV', 'interimAvailable', 'closingAvailable', 'XPCD', 'expected'];
function sceltaSaldo(balances: Saldo[]): Saldo | null {
  const lista = (balances || []).filter((b) => Number.isFinite(Number(b.amount)));
  if (!lista.length) return null;
  const rango = (b: Saldo) => { const i = TIPI_SALDO.findIndex((t) => (b.name || '').toLowerCase().includes(t.toLowerCase())); return i < 0 ? 99 : i; };
  return lista.slice().sort((a, b) => rango(a) - rango(b))[0];
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
  try { body = await req.json(); } catch { /* corpo vuoto: va bene */ }
  const C = CONTI[body.conto || 'sal'];
  if (!C) return json({ error: { message: `Conto sconosciuto: ${body.conto}` } }, 400);
  const dateFromChiesta = /^\d{4}-\d{2}-\d{2}$/.test(body.dateFrom || '') ? body.dateFrom! : null;

  const sb = createClient(url, service);
  const leggiTutto = async (q: () => any) => {
    const out: any[] = [];
    for (let da = 0; ; da += 1000) {
      const { data, error } = await q().range(da, da + 999);
      if (error) throw error;
      out.push(...(data || []));
      if (!data || data.length < 1000) break;
    }
    return out;
  };

  try {
    // I conti da sincronizzare: per un utente il suo più recente, per il job uno per utente.
    let qc = sb.from('cm_bank_connections').select('*').contains('uses', [C.uso]).not('account_id', 'is', null)
      .order('created_at', { ascending: false });
    if (!daJob) qc = qc.eq('user_id', sub);
    const { data: conns, error: ec } = await qc;
    if (ec) throw ec;
    const perUtente = new Map<string, any>();
    for (const c of conns || []) if (!perUtente.has(c.user_id)) perUtente.set(c.user_id, c);
    if (!perUtente.size) {
      return json({ ok: false, error: { message: `Nessun conto spuntato «${C.etichetta}» in Finanza → Banche e Conti.` } }, daJob ? 200 : 404);
    }

    const jwt = await createEnableBankingJWT(appId, pem);
    const psu = daJob ? {} : psuHeaders(req);
    const esiti: any[] = [];

    // La carta (solo Spese Ada): quella scelta nella pagina, salvata in cm_settings. Vuota = tutte.
    let carta = '';
    if (C.carta) {
      const { data: rc } = await sb.from('cm_settings').select('value').eq('key', C.carta).maybeSingle();
      try { carta = String(JSON.parse(rc?.value || '{}').carta || '').trim(); } catch { carta = ''; }
    }

    for (const [userId, conn] of perUtente) {
      const esito: any = { user_id: userId, conto: conn.id };
      try {
        const archivio = await leggiTutto(() => sb.from(C.tx)
          .select('id,date,amount,description,external_id').eq('user_id', userId).order('date', { ascending: true }));
        const regole = await leggiTutto(() => sb.from(C.mm)
          .select('merchant_key,category_id,source').eq('user_id', userId));
        const regoleBank = regole.filter((r) => (r.source || 'bank') === 'bank' && r.merchant_key);

        // Da quando leggere: l'ultimo movimento meno qualche giorno (la banca contabilizza in
        // ritardo), oppure la data chiesta dalla pagina. I doppioni li toglie l'indice.
        const ultima = archivio.length ? archivio[archivio.length - 1].date : null;
        const dateFrom = dateFromChiesta || (ultima
          ? new Date(new Date(ultima + 'T00:00:00Z').getTime() - SOVRAPPOSIZIONE * 86400000).toISOString().slice(0, 10)
          : new Date(Date.now() - PRIMA_VOLTA * 86400000).toISOString().slice(0, 10));

        const letti = await leggiMovimenti(conn.account_id, jwt, psu, dateFrom);

        // Il saldo vero del conto, come salvaSaldo() della pagina (stessa forma del JSON).
        const b = C.saldo ? sceltaSaldo(letti.balances) : null;
        if (b && C.saldo) {
          const v = { importo: Number(b.amount), valuta: b.currency || 'EUR', data: b.date || null,
                      tipo: b.name || null, conto: conn.id, letto_il: new Date().toISOString() };
          const { error: es } = await sb.from('cm_settings').upsert({ key: C.saldo, value: JSON.stringify(v) }, { onConflict: 'key' });
          if (es) console.warn('[sal-sync] saldo non salvato', es.message);
          esito.saldo = v.importo;
        }

        // L'indice dei doppioni sull'archivio (provvisori compresi).
        const perExternal = new Set(archivio.filter((t) => t.external_id).map((t) => t.external_id));
        const esatti = new Set(archivio.map((t) => chiaveEsatta(String(t.date).slice(0, 10), t.amount, t.description)));
        const perImporto = new Map<string, any[]>();
        for (const t of archivio) {
          const k = (+t.amount).toFixed(2);
          if (!perImporto.has(k)) perImporto.set(k, []);
          perImporto.get(k)!.push(t);
        }

        const righe: any[] = [];
        let saltati = 0;
        let fuoriCarta = 0;
        for (const t of letti.transactions) {
          const data = (t.bookingDate || t.valueDate || '').slice(0, 10);
          if (!t.amount || !data) continue;
          if (C.soloUscite && t.amount > 0) continue;
          const cartaTx = t.card?.identification || cardFromDescription(t.description) || null;
          // Come il filtro per carta di spese-ada.html: con una carta scelta, i movimenti senza carta restano fuori.
          if (carta && cartaTx !== carta) { fuoriCarta++; continue; }
          // La descrizione come la compone la pagina: causale + controparte, senza ripetizioni.
          const descrizione = [t.description, t.counterpartyName].map((s) => (s || '').trim()).filter(Boolean)
            .filter((p, i, arr) => !arr.slice(0, i).some((q) => q.toLowerCase().includes(p.toLowerCase())))
            .join(' · ') || t.bankTransactionCode || 'Movimento';
          if ((t.externalId && perExternal.has(t.externalId)) || esatti.has(chiaveEsatta(data, t.amount, descrizione))) {
            saltati++; continue;
          }
          const g = numeroGiorno(data);
          const simile = (perImporto.get((+t.amount).toFixed(2)) || [])
            .map((x) => ({ x, scarto: Math.abs(numeroGiorno(x.date) - g) }))
            .filter((y) => y.scarto <= DUP_MARGINE)
            .sort((a, b2) => a.scarto - b2.scarto)[0]?.x || null;
          const hay = normalizeMerchant(descrizione);
          const regola = hay ? regoleBank.filter((r) => aggancia(r.merchant_key, hay))
            .sort((a, b2) => peso(b2.merchant_key) - peso(a.merchant_key) || b2.merchant_key.length - a.merchant_key.length)[0] : null;
          righe.push({
            user_id: userId, bank_connection_id: conn.id, external_id: t.externalId,
            date: data, amount: t.amount, currency: t.currency, description: descrizione,
            category_id: regola?.category_id || null,
            card_identification: cartaTx,
            type: t.bankTransactionCode || null, import_source: 'bank', raw: t,
            provvisorio: !!simile, duplicato_di: simile?.id || null,
          });
          if (t.externalId) perExternal.add(t.externalId);
        }

        let inseriti: any[] = [];
        if (righe.length) {
          const { data: ins, error: ei } = await sb.from(C.tx)
            .upsert(righe, { onConflict: 'bank_connection_id,external_id', ignoreDuplicates: true })
            .select('id,category_id,provvisorio');
          if (ei) throw ei;
          inseriti = ins || [];
        }
        esito.importati = inseriti.length;
        esito.provvisori = inseriti.filter((r) => r.provvisorio).length;
        esito.senza_categoria = inseriti.filter((r) => !r.category_id).length;
        esito.saltati = saltati + (righe.length - inseriti.length);
        esito.dal = dateFrom;
        if (carta) { esito.carta = carta; esito.fuori_carta = fuoriCarta; }
        esito.troncato = letti.truncated;

        await sb.from('cm_sync_log').insert({
          user_id: userId, bank_connection_id: conn.id, finished_at: new Date().toISOString(),
          status: 'success', imported_count: esito.importati,
          error_message: `sal-sync ${VERSION} ${C.kind}${daJob ? ' (job)' : ''} · dal ${dateFrom} · ${letti.transactions.length} letti, ` +
            `${esito.importati} importati (${esito.provvisori} provvisori, ${esito.senza_categoria} senza categoria), ${esito.saltati} già presenti`,
        });
      } catch (e) {
        esito.errore = (e as Error).message || String(e);
        await sb.from('cm_sync_log').insert({
          user_id: userId, bank_connection_id: conn.id, finished_at: new Date().toISOString(),
          status: 'error', imported_count: 0, error_message: `sal-sync ${VERSION} ${C.kind}: ${esito.errore}`,
        });
      }

      // La notifica: solo dal job — dal pulsante il riepilogo lo mostra la pagina.
      if (daJob) {
        try { await notifica(sb, userId, esito, C); } catch (e) { console.warn('[sal-sync] notifica non accodata', (e as Error).message); }
      }
      esiti.push(esito);
    }

    if (daJob) {
      await fetch(`${url}/functions/v1/send-notifications`, {
        method: 'POST', headers: { Authorization: `Bearer ${service}`, 'Content-Type': 'application/json' }, body: '{}',
      }).catch(() => {});
    }
    console.log('[sal-sync]', JSON.stringify(esiti));
    const unico = !daJob ? esiti[0] : null;
    if (unico?.errore) return json({ ok: false, error: { message: unico.errore } }, 502);
    return json({ ok: true, version: VERSION, esiti });
  } catch (e) {
    console.error('[sal-sync] errore', e);
    return json({ ok: false, error: { message: (e as Error).message || String(e) } }, 500);
  }
});

/** Una riga di coda per canale (Telegram, telefono, browser), come sc-promemoria: la regola
 *  'spese' nasce SPENTA e serve solo come rule_id. */
async function notifica(sb: any, userId: string, e: any, C: Conto) {
  const titolo = e.errore ? `⚠️ ${C.nome}: sincronizzazione fallita` : `🏦 ${C.nome} sincronizzato`;
  const corpo = e.errore
    ? `${e.errore}\nRiprova da ${C.pagina} → Movimenti.`
    : [
        `${e.importati} movimenti importati`,
        e.senza_categoria ? `${e.senza_categoria} senza categoria` : null,
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
