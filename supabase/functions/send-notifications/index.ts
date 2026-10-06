// ============================================================
// Job 2 — send-notifications
// Frequenza: ogni 5 minuti (cron: "*/5 * * * *")
//
// Prende le righe pending con fire_at nella finestra ±5 minuti e le consegna:
//   channel = 'telegram' → messaggio al bot, coi bottoni inline
//   channel = 'android'  → push FCM ai telefoni di cm_push_devices
// Consegna OK → status = 'sent', KO → 'failed' (nessun retry).
//
// ⚠️ I due canali sono INDIPENDENTI e arrivano tutt'e due: sono due righe
// diverse della coda, nate da due regole diverse. Una che fallisce non tocca
// l'altra — il telefono spento non deve far sparire il promemoria da Telegram.
//
// Il canale 'smart_block' non passa di qui: quelle righe se le va a prendere
// in polling l'APK Smart Blocker.
//
// Stati attivi: pending | sent | failed | cancelled | completed
// ============================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL       = Deno.env.get('SUPABASE_URL')!
const SERVICE_ROLE_KEY   = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
const TELEGRAM_BOT_TOKEN = Deno.env.get('TELEGRAM_BOT_TOKEN')!
// JSON dell'account di servizio Firebase, tutto intero.
// ⚠️ Assente = le push non partono e le righe 'android' finiscono 'failed', ma
// Telegram continua a funzionare: i due canali non si tengono per mano.
const FCM_SERVICE_ACCOUNT = Deno.env.get('FCM_SERVICE_ACCOUNT') ?? ''

const sb = createClient(SUPABASE_URL, SERVICE_ROLE_KEY)

// ---------------------------------------------------------------------------
// Tipi
// ---------------------------------------------------------------------------
interface QueueItem {
  id:                   string
  rule_id:              string
  user_id:              string
  app:                  string
  entity_id:            string
  title:                string
  body:                 string
  channel:              string
  fire_at:              string
  status:               string
  created_at:           string
  telegram_message_id?: number | null
  occurrence_id?:       string | null
  metadata?:            {
    completion_update?:      Record<string, unknown>
    telegram_cancel_button?: boolean
  } | null
}

// ---------------------------------------------------------------------------
// Telegram
// ---------------------------------------------------------------------------
async function sendTelegram(
  chatId: string,
  text: string,
  replyMarkup?: object
): Promise<{ ok: boolean; response: string; message_id?: number }> {
  const url = `https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`
  const payload: Record<string, unknown> = {
    chat_id: chatId,
    text,
    parse_mode: 'HTML',
  }
  if (replyMarkup) {
    payload.reply_markup = replyMarkup
  }
  const res      = await fetch(url, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(payload),
  })
  const bodyText = await res.text()
  let message_id: number | undefined
  try {
    const parsed = JSON.parse(bodyText)
    message_id = parsed?.result?.message_id
  } catch {
    // ignore parse error
  }
  return { ok: res.ok, response: bodyText, message_id }
}

// Costruisce la inline keyboard con le opzioni snooze + annulla
// Se completeButton è true aggiunge il pulsante "✅ Fatto" come prima riga
function buildInlineKeyboard(itemId: string, completeButton = false, cancelButton = true): object {
  const rows: object[][] = []
  if (completeButton) {
    rows.push([{ text: '✅ Fatto', callback_data: `complete:${itemId}` }])
  }
  rows.push([
    { text: '⏸ 30min',  callback_data: `snooze:30:${itemId}`   },
    { text: '⏸ 1h',     callback_data: `snooze:60:${itemId}`   },
  ])
  rows.push([
    { text: '⏸ 3h',     callback_data: `snooze:180:${itemId}`  },
    { text: '⏸ Domani', callback_data: `snooze:1440:${itemId}` },
  ])
  const lastRow: object[] = []
  if (cancelButton) lastRow.push({ text: '❌ Annulla promemoria', callback_data: `cancel:${itemId}` })
  lastRow.push({ text: '🗑 Chiudi', callback_data: `dismiss:${itemId}` })
  rows.push(lastRow)
  return { inline_keyboard: rows }
}

// ---------------------------------------------------------------------------
// FCM — le push all'APK nativo
// ---------------------------------------------------------------------------
//
// ⚠️ Si usa l'API HTTP v1, che vuole un access token OAuth2 firmato con
// l'account di servizio: la vecchia "server key" di FCM legacy è spenta dal
// 2024, e un esempio trovato in rete che manda una `key=AAAA…` non funziona
// più. Il token dura un'ora e si tiene in memoria: rifarlo a ogni notifica
// sarebbe una firma RSA e una chiamata di rete per ogni riga della coda.

interface AccountServizio {
  client_email: string
  private_key:  string
  project_id:   string
}

let accessToken: { valore: string; scadenza: number } | null = null

function accountServizio(): AccountServizio | null {
  if (!FCM_SERVICE_ACCOUNT) return null
  try {
    const a = JSON.parse(FCM_SERVICE_ACCOUNT) as AccountServizio
    if (!a.client_email || !a.private_key || !a.project_id) {
      console.error('[send-notif] FCM_SERVICE_ACCOUNT incompleto')
      return null
    }
    return a
  } catch (e) {
    console.error('[send-notif] FCM_SERVICE_ACCOUNT non è un JSON:', e)
    return null
  }
}

function base64url(dati: ArrayBuffer | string): string {
  const bytes = typeof dati === 'string'
    ? new TextEncoder().encode(dati)
    : new Uint8Array(dati)
  let binario = ''
  for (const b of bytes) binario += String.fromCharCode(b)
  return btoa(binario).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** La chiave privata del JSON è un PEM PKCS#8: qui diventa una CryptoKey. */
async function chiavePrivata(pem: string): Promise<CryptoKey> {
  const corpo = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, '')
    .replace(/-----END PRIVATE KEY-----/, '')
    .replace(/\s+/g, '')
  const grezzo = Uint8Array.from(atob(corpo), c => c.charCodeAt(0))
  return await crypto.subtle.importKey(
    'pkcs8',
    grezzo.buffer,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  )
}

async function tokenFcm(): Promise<string | null> {
  if (accessToken && accessToken.scadenza > Date.now()) return accessToken.valore

  const conto = accountServizio()
  if (!conto) return null

  const ora  = Math.floor(Date.now() / 1000)
  const testa = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
  const corpo = base64url(JSON.stringify({
    iss:   conto.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud:   'https://oauth2.googleapis.com/token',
    exp:   ora + 3600,
    iat:   ora,
  }))

  try {
    const chiave = await chiavePrivata(conto.private_key)
    const firma  = await crypto.subtle.sign(
      'RSASSA-PKCS1-v1_5',
      chiave,
      new TextEncoder().encode(`${testa}.${corpo}`),
    )
    const jwt = `${testa}.${corpo}.${base64url(firma)}`

    const res = await fetch('https://oauth2.googleapis.com/token', {
      method:  'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body:    new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion:  jwt,
      }),
    })
    const dati = await res.json()
    if (!res.ok || !dati.access_token) {
      console.error('[send-notif] token FCM rifiutato:', JSON.stringify(dati))
      return null
    }
    // Cinque minuti di margine: un token che scade mentre si manda darebbe un
    // 401 su una notifica sola, e sarebbe indistinguibile da un token sbagliato.
    accessToken = { valore: dati.access_token, scadenza: Date.now() + 55 * 60 * 1000 }
    return accessToken.valore
  } catch (e) {
    console.error('[send-notif] firma del token FCM fallita:', e)
    return null
  }
}

interface Telefono { id: string; token: string }

/**
 * Manda la push a un telefono.
 *
 * ⚠️ È un messaggio di soli **dati** e non una `notification`: la notifica la
 * disegna l'app, perché una notification payload di FCM non può portare i
 * pulsanti (✅ Fatto, ⏸ Rinvia) — e senza pulsanti il telefono direbbe meno di
 * Telegram. `priority: high` perché una notifica in ritardo di venti minuti è
 * una notifica che non serve più.
 */
async function inviaPush(
  token: string,
  progetto: string,
  accesso: string,
  item: QueueItem,
): Promise<{ ok: boolean; risposta: string; tokenMorto: boolean }> {
  const dati: Record<string, string> = {
    queue_id:  item.id,
    app:       item.app,
    entity_id: item.entity_id ?? '',
    title:     item.title,
    body:      item.body,
    // Le stringhe: FCM ammette solo stringhe nel blocco `data`, quindi i
    // booleani viaggiano come "true"/"false" e l'app li rilegge così.
    completa:  item.metadata?.completion_update ? 'true' : 'false',
    annulla:   (item.metadata?.telegram_cancel_button ?? true) ? 'true' : 'false',
  }

  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${progetto}/messages:send`,
    {
      method:  'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': `Bearer ${accesso}`,
      },
      body: JSON.stringify({
        message: {
          token,
          data: dati,
          android: {
            priority: 'high',
            ttl:      '3600s',
          },
        },
      }),
    },
  )

  const risposta = await res.text()
  // UNREGISTERED / INVALID_ARGUMENT su un token = quell'installazione non
  // esiste più (app disinstallata, dati svuotati, ripristino da backup).
  const tokenMorto = res.status === 404 ||
    (res.status === 400 && risposta.includes('INVALID_ARGUMENT') && risposta.includes('token')) ||
    risposta.includes('UNREGISTERED')
  return { ok: res.ok, risposta, tokenMorto }
}

// ---------------------------------------------------------------------------
// Handler principale
// ---------------------------------------------------------------------------
Deno.serve(async (_req) => {
  try {
    const nowDate   = new Date()
    const WINDOW_MS = 5 * 60 * 1000
    const windowMin = new Date(nowDate.getTime() - WINDOW_MS).toISOString()
    const windowMax = new Date(nowDate.getTime() + WINDOW_MS).toISOString()

    // ⚠️ I due canali si leggono INSIEME e non in due giri: sono righe diverse
    // della stessa coda, e due query vorrebbero dire due finestre temporali
    // leggermente diverse per lo stesso promemoria.
    const { data: items, error: fetchError } = await sb
      .from('cm_notification_queue')
      .select('*')
      .eq('status', 'pending')
      .in('channel', ['telegram', 'android'])
      .gte('fire_at', windowMin)
      .lte('fire_at', windowMax)
      .order('fire_at', { ascending: true })

    if (fetchError) throw fetchError

    let sent   = 0
    let failed = 0

    // ── Cache: le impostazioni e i telefoni si leggono una volta per utente ──
    const settingsCache = new Map<string, { telegram_chat_id: string | null; telegram_enabled: boolean } | null>()
    const deviceCache   = new Map<string, Telefono[]>()

    async function getUserSettings(userId: string) {
      if (settingsCache.has(userId)) return settingsCache.get(userId)!
      const { data } = await sb
        .from('cm_user_notification_settings')
        .select('telegram_chat_id, telegram_enabled')
        .eq('user_id', userId)
        .single()
      settingsCache.set(userId, data ?? null)
      return data ?? null
    }

    async function getDevices(userId: string): Promise<Telefono[]> {
      if (deviceCache.has(userId)) return deviceCache.get(userId)!
      const { data } = await sb
        .from('cm_push_devices')
        .select('id, token')
        .eq('user_id', userId)
        .eq('enabled', true)
      const elenco = (data ?? []) as Telefono[]
      deviceCache.set(userId, elenco)
      return elenco
    }

    // Il token OAuth si chiede solo se c'è davvero una push da mandare: senza
    // righe 'android' non si paga una firma RSA per niente.
    let accesso: string | null = null
    let accessoChiesto = false
    const conto = accountServizio()

    async function accessoFcm(): Promise<string | null> {
      if (!accessoChiesto) { accesso = await tokenFcm(); accessoChiesto = true }
      return accesso
    }

    for (const item of (items as QueueItem[]) ?? []) {
      let consegnata     = false
      let errorMsg       = ''
      let telegramMsgId: number | undefined

      if (item.channel === 'telegram') {
        const settings = await getUserSettings(item.user_id)
        if (settings?.telegram_enabled && settings?.telegram_chat_id) {
          const message     = `${item.title}\n${item.body}`
          const hasComplete = !!(item.metadata?.completion_update)
          const hasCancel   = item.metadata?.telegram_cancel_button ?? true
          const replyMarkup = buildInlineKeyboard(item.id, hasComplete, hasCancel)
          const result      = await sendTelegram(settings.telegram_chat_id, message, replyMarkup)
          consegnata        = result.ok
          telegramMsgId     = result.message_id
          if (!result.ok) errorMsg = `Telegram API error: ${result.response}`
        } else {
          errorMsg = 'Canale non configurato o disabilitato'
        }

      } else if (item.channel === 'android') {
        const telefoni = await getDevices(item.user_id)
        const token    = conto ? await accessoFcm() : null

        if (!conto) {
          errorMsg = 'FCM_SERVICE_ACCOUNT non configurato'
        } else if (!token) {
          errorMsg = 'token FCM non ottenuto'
        } else if (telefoni.length === 0) {
          errorMsg = 'nessun telefono registrato'
        } else {
          // ⚠️ Basta UN telefono raggiunto perché la notifica sia consegnata:
          // il tablet spento non deve far risultare fallito un promemoria che
          // è arrivato sul telefono in tasca.
          for (const telefono of telefoni) {
            const esito = await inviaPush(telefono.token, conto.project_id, token, item)
            if (esito.ok) {
              consegnata = true
            } else {
              errorMsg = esito.risposta.slice(0, 300)
              console.warn(`[send-notif] push fallita device=${telefono.id}: ${errorMsg}`)
              if (esito.tokenMorto) {
                // Una riga morta lasciata accesa fa fallire ogni invio
                // successivo e non lo dice a nessuno: si spegne qui.
                await sb.from('cm_push_devices')
                  .update({ enabled: false })
                  .eq('id', telefono.id)
                deviceCache.delete(item.user_id)
                console.log(`[send-notif] device ${telefono.id} spento: token non più valido`)
              }
            }
          }
        }
      }

      const updatePayload: Record<string, unknown> = { status: consegnata ? 'sent' : 'failed' }
      if (telegramMsgId) updatePayload.telegram_message_id = telegramMsgId

      await sb
        .from('cm_notification_queue')
        .update(updatePayload)
        .eq('id', item.id)

      if (consegnata) sent++
      else {
        failed++
        console.warn(`[send-notif] ${item.channel} ko id=${item.id}: ${errorMsg}`)
      }
    }

    const total = (items as QueueItem[])?.length ?? 0
    console.log(`[send-notif] done — total:${total} sent:${sent} failed:${failed} window:[${windowMin},${windowMax}]`)

    return new Response(
      JSON.stringify({ ok: true, total, sent, failed }),
      { headers: { 'Content-Type': 'application/json' } }
    )
  } catch (err) {
    console.error('[send-notif] fatal:', err)
    return new Response(
      JSON.stringify({ ok: false, error: String(err) }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
})
