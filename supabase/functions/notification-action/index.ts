// ============================================================
// notification-action — che cosa fa un pulsante di un promemoria
//
// ⚠️ QUESTA È L'UNICA IMPLEMENTAZIONE delle tre azioni. La chiamano:
//   • telegram-webhook, quando il bottone si preme dentro Telegram;
//   • l'APK nativo (com.garsal.appsphere), quando lo si preme sulla notifica
//     Android.
//
// Prima viveva dentro telegram-webhook. Copiarla in Kotlin per il telefono
// avrebbe voluto dire due implementazioni di completamento, punti e archivi —
// cioè due esiti diversi per lo stesso promemoria il giorno che una delle due
// cambia. È la stessa ragione per cui il ciclo di vita dei task sta nelle RPC
// e non nel JavaScript delle pagine.
//
// Azioni:
//   complete — esegue il completamento descritto in metadata.completion_update
//              (insert + RPC), poi chiude l'occorrenza
//   snooze   — riporta il promemoria più avanti: annulla l'occorrenza e ne
//              inserisce una copia con un fire_at nuovo
//   cancel   — annulla l'occorrenza senza completare niente
//
// ⚠️ Si ragiona sempre per OCCORRENZA (occurrence_id) e mai per singola riga:
// lo stesso promemoria delle 8:00 può avere una riga per canale (telegram,
// android) più i preavvisi, e chiudendone una sola le altre continuerebbero a
// suonare per una cosa già fatta.
//
// Chi può chiamarla:
//   • service_role (telegram-webhook) — passa senza controlli;
//   • un utente col suo JWT (l'APK) — solo sulle proprie righe. Il controllo
//     c'è perché qui dentro si scrive col service role, dove la RLS non vale:
//     senza, basterebbe l'id di una riga altrui per completarla. È la stessa
//     guardia `user_id = auth.uid()` delle RPC ob_action_*.
// ============================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL     = Deno.env.get('SUPABASE_URL')!
const SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

const sb = createClient(SUPABASE_URL, SERVICE_ROLE_KEY)

const CORS_HEADERS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, apikey',
}

// ---------------------------------------------------------------------------
// Chi sta chiamando
// ---------------------------------------------------------------------------

/**
 * Legge il JWT già verificato dalla piattaforma (verify_jwt resta acceso su
 * questa funzione, quindi qui arriva solo roba con una firma buona) e dice
 * chi è: il service role, oppure un utente con il suo id.
 */
function chiChiama(req: Request): { serviceRole: boolean; userId: string | null } {
  const header = req.headers.get('Authorization') ?? ''
  const token  = header.replace(/^Bearer\s+/i, '')
  if (!token) return { serviceRole: false, userId: null }
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return {
      serviceRole: payload.role === 'service_role',
      userId:      typeof payload.sub === 'string' ? payload.sub : null,
    }
  } catch {
    return { serviceRole: false, userId: null }
  }
}

// ---------------------------------------------------------------------------
// I fratelli della stessa occorrenza — attraverso i canali
// ---------------------------------------------------------------------------
//
// ⚠️ `occurrence_id` è "{rule_id}:{YYYY-MM-DD}:{HH:MM}" e porta dentro il
// **rule_id**, ma ogni canale ha la sua regola: la riga Telegram e quella
// Android dello stesso promemoria hanno quindi due occurrence_id diversi.
// Cercando i fratelli per occurrence_id uguale — com'era finché il canale era
// uno solo — premere ✅ Fatto sul telefono chiuderebbe il task e lascerebbe la
// riga Telegram in pending: il bot suonerebbe per una cosa già fatta, e
// premendo Fatto anche lì `task_complete` chiuderebbe **l'occorrenza
// successiva**, cioè la volta dopo, senza che nessuno se ne accorga.
//
// La stessa occorrenza è quindi: stesso utente, stessa app, stessa entità e
// stesso **giorno:ora** — cioè la coda dell'occurrence_id, che è la parte che
// non dipende dalla regola.

interface Fratello {
  id:                  string
  channel:             string
  status:              string
  rule_id:             string
  occurrence_id:       string | null
  telegram_message_id: number | null
}

/** La coda "YYYY-MM-DD:HH:MM" di un occurrence_id, che i canali condividono. */
function codaOccorrenza(occId: string | null): string | null {
  if (!occId) return null
  const i = occId.indexOf(':')
  return i < 0 ? null : occId.slice(i + 1)
}

/**
 * Le altre righe dello stesso promemoria, su qualunque canale e per qualunque
 * preavviso, esclusa quella su cui si è premuto.
 */
async function fratelli(r: RigaCoda): Promise<Fratello[]> {
  const coda = codaOccorrenza(r.occurrence_id)
  if (!coda) return []

  let q = sb
    .from('cm_notification_queue')
    .select('id, channel, status, rule_id, occurrence_id, telegram_message_id')
    .eq('user_id', r.user_id)
    .eq('app', r.app)
    .like('occurrence_id', `%:${coda}`)
    .neq('id', r.id)

  // Un promemoria rapido di AppSphere non ha un'entità dietro: lì l'entity_id
  // è l'id del promemoria stesso, e resta comunque il filtro giusto.
  q = r.entity_id ? q.eq('entity_id', r.entity_id) : q.is('entity_id', null)

  const { data, error } = await q
  if (error) { console.error('[notif-action] lettura fratelli:', error); return [] }
  return (data ?? []) as Fratello[]
}

/**
 * Chiude i fratelli. `soloPending` per il rinvio: lì le righe già mandate
 * restano `sent`, perché sono suonate davvero e l'archivio deve dirlo.
 */
async function annullaFratelli(elenco: Fratello[], soloPending: boolean): Promise<void> {
  const ids = elenco
    .filter(f => !soloPending || f.status === 'pending')
    .map(f => f.id)
  if (ids.length === 0) return
  const { error } = await sb
    .from('cm_notification_queue')
    .update({ status: 'cancelled' })
    .in('id', ids)
  if (error) console.error('[notif-action] annullaFratelli:', error)
}

// ---------------------------------------------------------------------------
// complete — il completamento descritto nella riga
// ---------------------------------------------------------------------------

interface RigaCoda {
  id:            string
  user_id:       string
  app:           string
  entity_id:     string | null
  title:         string
  body:          string
  channel:       string
  fire_at:       string
  rule_id:       string
  occurrence_id: string | null
  metadata:      Record<string, unknown> | null
}

/**
 * I segnaposto che `completion_update` può contenere.
 *
 * ⚠️ Si ricavano dal `fire_at` letto in ora di **Roma** e non dall'istante in
 * cui si preme il pulsante: un promemoria delle 23:30 chiuso dopo mezzanotte
 * verrebbe segnato sul giorno dopo, cioè su una giornata che non è quella per
 * cui suonava.
 */
function segnaposto(riga: RigaCoda) {
  const slotTime = riga.metadata?.slot_time as string | undefined
  const fireAt   = new Date(riga.fire_at)
  const data     = fireAt.toLocaleDateString('sv-SE', { timeZone: 'Europe/Rome' }) // YYYY-MM-DD
  const ora      = slotTime
    ?? fireAt.toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Rome' })

  const [y, m, d]   = data.split('-').map(Number)
  const mezzogiorno = new Date(Date.UTC(y, m - 1, d, 12, 0, 0))
  const dowUtc      = mezzogiorno.getUTCDay()        // 0=Dom … 6=Sab
  const giornoN     = dowUtc === 0 ? 7 : dowUtc      // 1=Lun … 7=Dom
  const lunedi      = new Date(mezzogiorno)
  lunedi.setUTCDate(lunedi.getUTCDate() + (dowUtc === 0 ? -6 : 1 - dowUtc))

  return {
    data,
    ora,
    lunedi: lunedi.toISOString().slice(0, 10),
    giornoN,
  }
}

function risolvi(valore: unknown, s: ReturnType<typeof segnaposto>): unknown {
  if (typeof valore !== 'string') return valore
  return valore
    .replace('{{fire_date_local}}', s.data)
    .replace('{{slot_time}}',       s.ora)
    .replace('{{monday_of_week}}',  s.lunedi)
    .replace('{{day_of_week_n}}',   String(s.giornoN))
}

async function completa(riga: RigaCoda): Promise<{ ok: boolean; errore?: string }> {
  const cu = riga.metadata?.completion_update as {
    app?:        string
    operations?: Array<{ op: string; table: string; fields?: Record<string, unknown> }>
  } | undefined

  if (!cu) return { ok: false, errore: 'Dati completamento non disponibili' }

  const s = segnaposto(riga)

  for (const operazione of (cu.operations ?? [])) {
    if (operazione.op === 'insert' && operazione.fields) {
      const campi: Record<string, unknown> = {}
      for (const [k, v] of Object.entries(operazione.fields)) campi[k] = risolvi(v, s)
      const { error } = await sb.from(operazione.table).insert(campi)
      if (error) {
        console.error('[notif-action] insert completamento:', error)
        return { ok: false, errore: error.message }
      }
    }
  }

  // ⚠️ Il ciclo di vita di un task non si scrive qui: lo decide task_complete,
  // che sa dove va la prossima occorrenza tipo per tipo.
  if (cu.app === 'tasks' && riga.entity_id) {
    const { data, error } = await sb.rpc('task_complete', {
      p_task_id: riga.entity_id,
      p_today:   s.data,
    })
    if (error) {
      console.error('[notif-action] task_complete:', error)
      return { ok: false, errore: error.message ?? String(error) }
    }
    const esito = data as { ok?: boolean; error?: string } | null
    if (esito?.ok === false) {
      console.warn('[notif-action] task_complete ok=false:', esito)
      return { ok: false, errore: esito.error ?? 'completamento non riuscito' }
    }
  }

  // Le azioni delle Piante: stesso principio dei task, ma la loro RPC.
  // ⚠️ Si passa dal nucleo col proprietario della RIGA, non da pv_action_complete:
  // qui si scrive col service role e auth.uid() è NULL. Il controllo su chi può
  // premere questo pulsante è già stato fatto più sotto, sull'user_id della riga.
  if (cu.app === 'plants' && riga.entity_id) {
    const { data, error } = await sb.rpc('pv_action_complete_core', {
      p_action_id: riga.entity_id,
      p_user:      riga.user_id,
      p_today:     s.data,
    })
    if (error) {
      console.error('[notif-action] pv_action_complete_core:', error)
      return { ok: false, errore: error.message ?? String(error) }
    }
    const esito = data as { ok?: boolean; error?: string } | null
    if (esito?.ok === false) {
      return { ok: false, errore: esito.error ?? 'completamento non riuscito' }
    }
  }

  // Le azioni di Obiettivi: come le Piante, dal nucleo col proprietario della riga.
  if (cu.app === 'objectives' && riga.entity_id) {
    const { data, error } = await sb.rpc('ob_action_complete_core', {
      p_action_id: riga.entity_id,
      p_user:      riga.user_id,
      p_today:     s.data,
    })
    if (error) {
      console.error('[notif-action] ob_action_complete_core:', error)
      return { ok: false, errore: error.message ?? String(error) }
    }
    const esito = data as { ok?: boolean; error?: string } | null
    if (esito?.ok === false) {
      return { ok: false, errore: esito.error ?? 'completamento non riuscito' }
    }
  }

  // Le abitudini: la riga in hb_completions l'ha già scritta l'insert qui
  // sopra, e questa RPC ne ricava streak, jolly e punti.
  if (cu.app === 'habits') {
    const insert  = (cu.operations ?? []).find(o => o.op === 'insert' && o.table === 'hb_completions')
    const habitId = insert?.fields?.habit_id as string | undefined
    if (habitId) {
      const { error } = await sb.rpc('habit_post_completion', {
        p_habit_id:   habitId,
        p_local_date: s.data,
      })
      // Non blocca: la spunta è già scritta, e un punteggio mancato si
      // riallinea alla prossima riconciliazione.
      if (error) console.error('[notif-action] habit_post_completion:', error)
    }
  }

  return { ok: true }
}

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS_HEADERS })

  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json', ...CORS_HEADERS },
    })

  if (req.method !== 'POST') return json({ ok: false, error: 'Method Not Allowed' }, 405)

  let corpo: { queue_id?: string; action?: string; minutes?: number }
  try {
    corpo = await req.json()
  } catch {
    return json({ ok: false, error: 'corpo non leggibile' }, 400)
  }

  const queueId = corpo.queue_id
  const azione  = corpo.action
  if (!queueId || !azione) return json({ ok: false, error: 'servono queue_id e action' }, 400)

  const { data: riga } = await sb
    .from('cm_notification_queue')
    .select('id, user_id, app, entity_id, title, body, channel, fire_at, rule_id, occurrence_id, metadata')
    .eq('id', queueId)
    .maybeSingle()

  if (!riga) return json({ ok: false, error: 'Promemoria non trovato' }, 404)

  const chiamante = chiChiama(req)
  if (!chiamante.serviceRole && chiamante.userId !== (riga as RigaCoda).user_id) {
    console.warn(`[notif-action] rifiutata: utente=${chiamante.userId} riga=${(riga as RigaCoda).user_id}`)
    return json({ ok: false, error: 'Non è un tuo promemoria' }, 403)
  }

  const r = riga as RigaCoda
  // Si leggono PRIMA di scrivere: servono a Telegram per cancellare i messaggi
  // già mandati, e dopo l'update direbbero le stesse righe con lo stato nuovo.
  const altri = await fratelli(r)

  if (azione === 'snooze') {
    const minuti = Number(corpo.minutes)
    if (!Number.isFinite(minuti) || minuti <= 0) {
      return json({ ok: false, error: 'durata non valida' }, 400)
    }
    const nuovoFireAt = new Date(Date.now() + minuti * 60 * 1000).toISOString()

    // ⚠️ Solo le righe ancora pending: quelle già mandate restano `sent`,
    // perché sono suonate davvero e l'archivio deve continuare a dirlo. La
    // riga su cui si è premuto non si tocca per la stessa ragione.
    await annullaFratelli(altri, true)

    // ⚠️ Il promemoria torna su TUTTI i canali su cui era arrivato, non solo
    // su quello da cui si è premuto: rimandandolo dal telefono deve tornare
    // anche su Telegram, o il rinvio spegnerebbe di nascosto un canale.
    // Ogni canale conserva la sua regola e il suo occurrence_id — sono le
    // colonne che dicono da quale regola quella riga è nata.
    const perCanale = new Map<string, { rule_id: string; occurrence_id: string | null }>()
    perCanale.set(r.channel, { rule_id: r.rule_id, occurrence_id: r.occurrence_id })
    for (const f of altri) {
      if (!perCanale.has(f.channel)) {
        perCanale.set(f.channel, { rule_id: f.rule_id, occurrence_id: f.occurrence_id })
      }
    }

    const nuove = Array.from(perCanale.entries()).map(([canale, o]) => ({
      rule_id:       o.rule_id,
      user_id:       r.user_id,
      app:           r.app,
      entity_id:     r.entity_id,
      title:         r.title,
      body:          r.body,
      channel:       canale,
      fire_at:       nuovoFireAt,
      status:        'pending',
      occurrence_id: o.occurrence_id,
      metadata:      r.metadata,
    }))

    const { error } = await sb.from('cm_notification_queue').insert(nuove)
    if (error) {
      console.error('[notif-action] snooze insert:', error)
      return json({ ok: false, error: error.message }, 500)
    }

    const etichetta = minuti < 60 ? `${minuti} min`
      : minuti < 1440 ? `${minuti / 60} h`
      : 'domani'
    return json({
      ok: true, action: 'snooze', message: `⏸ Rimandato di ${etichetta}`,
      channels: Array.from(perCanale.keys()), siblings: altri,
    })
  }

  if (azione === 'cancel') {
    await annullaFratelli(altri, false)
    const { error } = await sb
      .from('cm_notification_queue')
      .update({ status: 'cancelled' })
      .eq('id', r.id)
    if (error) {
      console.error('[notif-action] cancel:', error)
      return json({ ok: false, error: error.message }, 500)
    }
    return json({ ok: true, action: 'cancel', message: '❌ Promemoria annullato', siblings: altri })
  }

  if (azione === 'complete') {
    // ⚠️ Prima il completamento, poi la chiusura delle righe: al contrario, un
    // errore a metà lascerebbe un promemoria spento su una cosa non fatta.
    const esito = await completa(r)
    if (!esito.ok) return json({ ok: false, error: esito.errore, siblings: [] }, 400)

    await annullaFratelli(altri, false)
    const { error } = await sb
      .from('cm_notification_queue')
      .update({ status: 'completed' })
      .eq('id', r.id)
    if (error) console.error('[notif-action] set completed:', error)

    return json({ ok: true, action: 'complete', message: '✅ Completato!', siblings: altri })
  }

  if (azione === 'dismiss') {
    // Toglie di mezzo la notifica e basta: nessuna scrittura, il promemoria
    // resta com'era. È la 🗑 di Telegram.
    return json({ ok: true, action: 'dismiss', message: '🗑 Notifica chiusa', siblings: [] })
  }

  return json({ ok: false, error: `azione sconosciuta: ${azione}` }, 400)
})
