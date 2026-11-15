// ============================================================
// sc-promemoria v1.1.0 — le notifiche delle fasce di studio di Ada
// Frequenza: ogni 5 minuti (pg_cron 'sc-promemoria', vedi 20261112130000_sc_promemoria.sql)
//
// Le fasce sono quelle di impostazioni-ada.html (sc_impostazioni.fasce, ora di Roma).
//   • all'INIZIO di una fascia: «📚 Inizio studio», con quel che il piano di oggi
//     prevede — solo se prevede qualcosa;
//   • alla FINE di una fascia: «⏰ Fine fascia», solo se qualcosa di oggi è ancora
//     aperto: un compito non spuntato, o un argomento con meno minuti cronometrati
//     (sc_sessioni) di quanti il piano gliene dà oggi.
//
// A chi: Salvatore e gli ospiti che in cm_guest_access hanno una pagina della scuola.
// Come: righe di cm_notification_queue sui canali 'browser' (Web Push) e 'android'
// (FCM all'APK nativa), solo dove l'utente ha un dispositivo acceso; poi si chiama
// send-notifications, che le consegna. Le regole (app 'scuola') nascono SPENTE:
// servono solo come rule_id della coda — fill-notification-queue legge le accese
// e non le tocca.
//
// ⚠️ Il piano NON è riscritto qui: è SC.piano di scuola-ada.js, letto dal sito e
// valutato (la porzione fra «const pIso» e «window.SC»). Una regola sola per
// calendario, studio e notifiche. Cambiando quei due segnaposto, cambia qui.
// ⚠️ piano() usa l'ora del server (UTC): fra mezzanotte e l'una (le due d'estate)
// il «giorno» sarebbe quello prima — nessuna fascia di studio cade lì.
//
// ⚠️ Una notifica si manda UNA volta: sc_promemoria_inviati (giorno, fascia, tipo)
// si scrive prima di mandare, e chi arriva secondo non manda niente.
// ============================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL     = Deno.env.get('SUPABASE_URL')!
const SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
const SITO             = 'https://garsal.men'
const OWNER_EMAIL      = 'garsal1971@gmail.com'
const PAGINE_SCUOLA    = ['compiti-ada.html', 'verifiche-ada.html', 'calendario-ada.html',
                          'studio-ada.html', 'impostazioni-ada.html', 'da-classeviva.html']
// Quanto dopo l'orario si manda ancora: il cron gira ogni 5 minuti, e un giro saltato
// non deve far perdere la notifica.
const FINESTRA_MIN = 12
// entity_id è uuid in produzione: un id fisso per «lo studio di Ada» (5c = scuola).
const ENTITA_STUDIO = '5c000000-0000-4000-8000-000000000001'

const sb = createClient(SUPABASE_URL, SERVICE_ROLE_KEY)

const pad = (n: number) => String(n).padStart(2, '0')
const aMin = (t: string) => { const [h, m] = String(t || '').split(':').map(Number); return isNaN(h) ? NaN : h * 60 + (m || 0) }
const fmtMin = (m: number) => m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? ' ' + (m % 60) : ''}` : `${m} min`

/** Chi chiama: solo il service role (il cron). verify_jwt ha già controllato la firma. */
function ruolo(req: Request): string | null {
  const t = (req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '')
  try {
    const p = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return p.role ?? null
  } catch { return null }
}

/** Adesso, in ora di Roma: giorno ISO, giorno della settimana (0 = domenica), minuti. */
function oraRoma() {
  const r = new Date(new Date().toLocaleString('en-US', { timeZone: 'Europe/Rome' }))
  return {
    giorno: `${r.getFullYear()}-${pad(r.getMonth() + 1)}-${pad(r.getDate())}`,
    dow: r.getDay(),
    minuti: r.getHours() * 60 + r.getMinutes(),
  }
}

// deno-lint-ignore no-explicit-any
type Piano = (dati: any, finoA: unknown, giorniMin: number) => any

async function caricaPiano(): Promise<Piano> {
  const r = await fetch(`${SITO}/scuola-ada.js?v=${Date.now()}`, { cache: 'no-store' })
  if (!r.ok) throw new Error(`scuola-ada.js: HTTP ${r.status}`)
  const testo = await r.text()
  const da = testo.indexOf('const pIso'), a = testo.indexOf('window.SC =')
  if (da < 0 || a < da) throw new Error('scuola-ada.js: piano() non trovato')
  return new Function(testo.slice(da, a) + '\nreturn piano;')() as Piano
}

async function leggi(q: PromiseLike<{ data: unknown; error: unknown }>) {
  const { data, error } = await q
  if (error) throw new Error((error as { message?: string }).message || String(error))
  return (data ?? []) as Record<string, unknown>[]
}

Deno.serve(async (req) => {
  if (ruolo(req) !== 'service_role') {
    return new Response(JSON.stringify({ ok: false, error: 'solo il cron' }), { status: 403 })
  }
  try {
    const ora = oraRoma()
    const imp = (await leggi(sb.from('sc_impostazioni').select('fasce,pausa_minuti').limit(1)))[0] as
      { fasce?: Record<string, string[][]>; pausa_minuti?: number } | undefined
    const fasce = (imp?.fasce?.[String(ora.dow)] || []).filter(f => !isNaN(aMin(f[0])) && !isNaN(aMin(f[1])))

    // Gli eventi di adesso: inizio o fine di una fascia negli ultimi FINESTRA_MIN minuti.
    const eventi: Array<{ tipo: 'inizio' | 'fine'; fascia: string; orario: string }> = []
    for (const [s, e] of fasce) {
      const fascia = `${s}-${e}`
      const ds = ora.minuti - aMin(s), de = ora.minuti - aMin(e)
      if (ds >= 0 && ds < FINESTRA_MIN) eventi.push({ tipo: 'inizio', fascia, orario: s })
      if (de >= 0 && de < FINESTRA_MIN) eventi.push({ tipo: 'fine', fascia, orario: e })
    }
    if (!eventi.length) return new Response(JSON.stringify({ ok: true, eventi: 0 }))

    // Il piano di oggi, con la regola di scuola-ada.js.
    const oggi = ora.giorno
    const d0 = new Date(oggi + 'T12:00:00Z'); d0.setUTCDate(d0.getUTCDate() - 60)
    const [materie, orario, fatti, ver, args, sposta, sess] = await Promise.all([
      leggi(sb.from('sc_materie').select('id,nome,minuti')),
      leggi(sb.from('sc_orario').select('id,giorno,ora,materia_id,senza_compiti').order('giorno').order('ora')),
      leggi(sb.from('sc_compiti_fatti').select('giorno,orario_id,stato').gte('giorno', d0.toISOString().slice(0, 10))),
      leggi(sb.from('sc_verifiche').select('id,data,materia').gt('data', oggi).order('data').order('created_at')),
      leggi(sb.from('sc_argomenti').select('id,verifica_id,titolo,ore,studiato,annullato,posizione').order('posizione')),
      leggi(sb.from('sc_spostamenti').select('*')).catch(() => []),
      leggi(sb.from('sc_sessioni').select('tipo,rif,inizio,fine').eq('giorno', oggi)),
    ])
    const piano = await caricaPiano()
    const g = piano({ materie, orario, fatti, ver, args, sposta, imp: imp || { fasce: {}, pausa_minuti: 0 } }, null, 1).giorni[0]
    if (!g || g.giornoIso !== oggi) throw new Error(`il piano parte dal ${g?.giornoIso}, non da oggi ${oggi}`)

    // Le voci di una fascia (v1.1.0): ogni fascia ha il suo blocco nel piano
    // (g.blocchi, scuola-ada.js ≥ v1.8.0). Un argomento è aperto a fine fascia se i
    // minuti cronometrati oggi sono meno di quelli che il piano gli dà fino a quella
    // fascia compresa. Senza blocchi (scuola-ada.js vecchio) vale il giorno intero.
    type Voce = { nome: string; min: number; aperta: boolean }
    const adesso = Date.now()
    const cronometrati = (id: string) => {
      let ms = 0
      for (const s of sess) if (s.tipo === 'argomento' && s.rif === id)
        ms += (s.fine ? Date.parse(String(s.fine)) : adesso) - Date.parse(String(s.inizio))
      return Math.floor(ms / 60000)
    }
    // deno-lint-ignore no-explicit-any
    const blocchi: any[] = g.blocchi && g.blocchi.length ? g.blocchi : [{ inizio: '', fine: '', compiti: g.compiti, studio: g.studio }]
    const vociDi = (k: number): Voce[] => {
      const b = blocchi[k], voci: Voce[] = []
      for (const c of b.compiti) if (!c.ok) voci.push({ nome: `${c.nome} (compiti)`, min: c.min, aperta: true })
      const perArg = new Map<string, { nome: string; min: number }>()
      for (const s of b.studio) {
        const x = perArg.get(s.a.id)
        if (x) x.min += s.min; else perArg.set(s.a.id, { nome: `${s.v.materia} · ${s.a.titolo}`, min: s.min })
      }
      for (const [id, x] of perArg) {
        let finoQui = 0
        for (let j = 0; j <= k; j++) for (const s of blocchi[j].studio) if (s.a.id === id) finoQui += s.min
        voci.push({ nome: x.nome, min: x.min, aperta: cronometrati(id) < finoQui })
      }
      return voci
    }
    const bloccoDi = (fascia: string) => {
      const k = blocchi.findIndex(b => `${b.inizio}-${b.fine}` === fascia)
      return k >= 0 ? k : 0
    }
    const elenco = (vv: Voce[]) => {
      const testa = vv.slice(0, 4).map(v => `• ${v.nome} (${fmtMin(v.min)})`)
      if (vv.length > 4) testa.push(`… e altre ${vv.length - 4}`)
      return testa.join('\n')
    }

    // I destinatari: Salvatore e gli ospiti della scuola, coi soli canali che hanno un dispositivo.
    const { data: utenti, error: eu } = await sb.auth.admin.listUsers({ perPage: 1000 })
    if (eu) throw eu
    const ospiti = await leggi(sb.from('cm_guest_access').select('email').in('page', PAGINE_SCUOLA))
    const email = new Set([OWNER_EMAIL, ...ospiti.map(o => String(o.email).toLowerCase())])
    const ids = utenti.users.filter(u => email.has((u.email || '').toLowerCase())).map(u => u.id)
    const disp = await leggi(sb.from('cm_push_devices').select('user_id,platform').eq('enabled', true).in('user_id', ids))
    const canali: Array<{ user_id: string; channel: string }> = []
    for (const id of ids) {
      if (disp.some(d => d.user_id === id && d.platform === 'web')) canali.push({ user_id: id, channel: 'browser' })
      if (disp.some(d => d.user_id === id && d.platform !== 'web')) canali.push({ user_id: id, channel: 'android' })
    }

    const esiti: unknown[] = []
    for (const ev of eventi) {
      const voci = vociDi(bloccoDi(ev.fascia)), aperte = voci.filter(v => v.aperta)
      let titolo = '', corpo = ''
      if (ev.tipo === 'inizio') {
        if (!voci.length) { esiti.push({ ...ev, saltata: 'niente in programma' }); continue }
        titolo = `📚 Inizio studio · ${ev.fascia.replace('-', '–')}`
        corpo = `In questa fascia:\n${elenco(voci)}`
      } else {
        if (!aperte.length) { esiti.push({ ...ev, saltata: 'tutto fatto' }); continue }
        titolo = `⏰ Fine fascia ${ev.fascia.replace('-', '–')}`
        corpo = `Ancora da finire di questa fascia:\n${elenco(aperte)}`
      }

      // Si prenota l'evento: chi arriva secondo trova la riga e non manda niente.
      const { data: preso, error: ep } = await sb.from('sc_promemoria_inviati')
        .upsert({ giorno: oggi, fascia: ev.fascia, tipo: ev.tipo }, { onConflict: 'giorno,fascia,tipo', ignoreDuplicates: true })
        .select()
      if (ep) throw ep
      if (!preso?.length) { esiti.push({ ...ev, saltata: 'già mandata' }); continue }

      const fireAt = new Date().toISOString()
      const righe = []
      try {
        for (const c of canali) {
          const { data: regola, error: er } = await sb.from('cm_notification_rules').upsert({
            user_id: c.user_id, app: 'scuola', entity_id: ENTITA_STUDIO, entity_type: 'task', channel: c.channel,
            entity_title: 'Studio', enabled: false, reminder_presets: { kind: 'scuola' }, notification_spec: {},
          }, { onConflict: 'user_id,app,entity_id,channel' }).select('id').single()
          if (er) throw er
          righe.push({
            rule_id: regola.id, user_id: c.user_id, app: 'scuola', entity_id: ENTITA_STUDIO,
            title: titolo, body: corpo, channel: c.channel, fire_at: fireAt, status: 'pending',
            occurrence_id: `${regola.id}:${oggi}:${ev.orario}`,
            metadata: { telegram_cancel_button: false, scuola: ev.tipo },
          })
        }
        if (righe.length) {
          const { error: eq } = await sb.from('cm_notification_queue').insert(righe)
          if (eq) throw eq
        }
      } catch (e) {
        // Non è partita: si toglie la prenotazione, e il giro dopo riprova.
        await sb.from('sc_promemoria_inviati').delete().match({ giorno: oggi, fascia: ev.fascia, tipo: ev.tipo })
        throw e
      }
      esiti.push({ ...ev, righe: righe.length, voci: ev.tipo === 'fine' ? aperte.length : voci.length })
    }

    // Consegna subito invece di aspettare il giro di send-notifications.
    if (esiti.some(e => (e as { righe?: number }).righe)) {
      const r = await fetch(`${SUPABASE_URL}/functions/v1/send-notifications`, {
        method: 'POST', headers: { Authorization: `Bearer ${SERVICE_ROLE_KEY}`, 'Content-Type': 'application/json' }, body: '{}',
      })
      console.log(`[sc-promemoria] send-notifications: HTTP ${r.status} ${(await r.text()).slice(0, 200)}`)
    }
    console.log('[sc-promemoria]', JSON.stringify({ ora, esiti }))
    return new Response(JSON.stringify({ ok: true, esiti }), { headers: { 'Content-Type': 'application/json' } })
  } catch (err) {
    console.error('[sc-promemoria] errore:', err)
    return new Response(JSON.stringify({ ok: false, error: String((err as Error)?.message || err) }), { status: 500 })
  }
})
