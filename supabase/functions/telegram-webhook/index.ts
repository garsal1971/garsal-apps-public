// ============================================================
// telegram-webhook — i bottoni inline dei promemoria su Telegram
// verify_jwt = false (vedi config.toml) — Telegram non manda nessun JWT
//
// ⚠️ QUI NON C'È PIÙ NESSUNA REGOLA. Che cosa succede premendo ✅ Fatto,
// ⏸ rinvia o ❌ annulla lo decide `notification-action`, che è l'unica
// implementazione e la chiama anche l'APK nativo quando lo stesso pulsante si
// preme sulla notifica Android. Copiarla di qua e di là voleva dire due esiti
// diversi per lo stesso promemoria il giorno che una delle due cambia — è la
// stessa ragione per cui il ciclo di vita dei task sta nelle RPC.
//
// A questa funzione resta quel che è di Telegram e che nessun altro può fare:
// rispondere al bottone (answerCallbackQuery) e togliere di mezzo i messaggi
// già mandati, compresi quelli dei preavvisi dello stesso promemoria — che
// `notification-action` restituisce in `siblings` col loro message_id.
//
// callback_data:
//   snooze:<minuti>:<queue_id> · cancel:<queue_id> · complete:<queue_id>
//   dismiss:<queue_id> — chiude il messaggio e basta, nessuna scrittura
// ============================================================

const SUPABASE_URL       = Deno.env.get('SUPABASE_URL')!
const SERVICE_ROLE_KEY   = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
const TELEGRAM_BOT_TOKEN = Deno.env.get('TELEGRAM_BOT_TOKEN')!
const WEBHOOK_SECRET     = Deno.env.get('TELEGRAM_WEBHOOK_SECRET') ?? ''

const CORS_HEADERS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, X-Telegram-Bot-Api-Secret-Token',
}

// ---------------------------------------------------------------------------
// Telegram
// ---------------------------------------------------------------------------

async function answerCallbackQuery(callbackQueryId: string, text: string): Promise<void> {
  await fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/answerCallbackQuery`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ callback_query_id: callbackQueryId, text, show_alert: false }),
  })
}

async function deleteMessage(chatId: number, messageId: number): Promise<void> {
  const res = await fetch(`https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/deleteMessage`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ chat_id: chatId, message_id: messageId }),
  })
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    console.warn(`[telegram-webhook] deleteMessage fallita msg=${messageId}:`, body)
  }
}

// ---------------------------------------------------------------------------
// L'azione, che sta da un'altra parte
// ---------------------------------------------------------------------------

interface Fratello { id: string; channel: string; telegram_message_id: number | null }
interface Esito {
  ok:        boolean
  message?:  string
  error?:    string
  siblings?: Fratello[]
}

/**
 * Chiama `notification-action` con la service role key: da lì passa senza il
 * controllo di proprietà, che serve invece all'APK — Telegram un utente
 * autenticato non ce l'ha, ha solo la sua chat.
 */
async function eseguiAzione(queueId: string, action: string, minutes?: number): Promise<Esito> {
  try {
    const res = await fetch(`${SUPABASE_URL}/functions/v1/notification-action`, {
      method:  'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': `Bearer ${SERVICE_ROLE_KEY}`,
      },
      body: JSON.stringify({ queue_id: queueId, action, minutes }),
    })
    const body = await res.json().catch(() => ({}))
    return body as Esito
  } catch (e) {
    console.error('[telegram-webhook] notification-action irraggiungibile:', e)
    return { ok: false, error: String(e) }
  }
}

/** Toglie dalla chat i messaggi degli altri preavvisi dello stesso promemoria. */
async function pulisciFratelli(siblings: Fratello[] | undefined, chatId: number): Promise<void> {
  for (const f of siblings ?? []) {
    // I fratelli su un altro canale (Android) un messaggio Telegram non ce
    // l'hanno: quelli si spengono da soli sul telefono.
    if (f.telegram_message_id) await deleteMessage(chatId, f.telegram_message_id)
  }
}

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: CORS_HEADERS })
  }

  // Il secret che Telegram rimanda a ogni chiamata: senza, l'endpoint è aperto.
  if (WEBHOOK_SECRET) {
    const incoming = req.headers.get('X-Telegram-Bot-Api-Secret-Token') ?? ''
    if (incoming !== WEBHOOK_SECRET) {
      console.warn('[telegram-webhook] secret token non valido')
      return new Response('Forbidden', { status: 403 })
    }
  }

  if (req.method !== 'POST') return new Response('Method Not Allowed', { status: 405 })

  let update: Record<string, unknown>
  try {
    update = await req.json()
  } catch {
    return new Response('Bad Request', { status: 400 })
  }

  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json', ...CORS_HEADERS },
    })

  // Telegram manda anche altri tipi di update (messaggi, ecc.): si ignorano.
  const cq = update.callback_query as Record<string, unknown> | undefined
  if (!cq) return json({ ok: true })

  const callbackQueryId = cq.id as string
  const callbackData    = cq.data as string | undefined
  const message         = cq.message as Record<string, unknown> | undefined
  const chatId          = (message?.chat as Record<string, unknown>)?.id as number | undefined
  const messageId       = message?.message_id as number | undefined

  console.log(`[telegram-webhook] callback_data="${callbackData}" chat=${chatId} msg=${messageId}`)

  if (!callbackData || !chatId || !messageId) {
    await answerCallbackQuery(callbackQueryId, '❌ Dati non validi')
    return json({ ok: false, error: 'campi mancanti' })
  }

  // "snooze:<minuti>:<uuid>" | "cancel:<uuid>" | "complete:<uuid>" | "dismiss:<uuid>"
  const parts  = callbackData.split(':')
  const azione = parts[0]

  if (azione === 'dismiss' && parts.length === 2) {
    // Nessuna scrittura: si toglie il messaggio e il promemoria resta com'era.
    await answerCallbackQuery(callbackQueryId, '🗑 Messaggio rimosso')
    await deleteMessage(chatId, messageId)
    return json({ ok: true })
  }

  let queueId: string
  let minuti: number | undefined

  if (azione === 'snooze' && parts.length === 3) {
    minuti  = parseInt(parts[1], 10)
    queueId = parts[2]
    if (isNaN(minuti) || minuti <= 0) {
      await answerCallbackQuery(callbackQueryId, '❌ Durata sospensione non valida')
      return json({ ok: false, error: 'minuti non validi' })
    }
  } else if ((azione === 'cancel' || azione === 'complete') && parts.length === 2) {
    queueId = parts[1]
  } else {
    console.warn('[telegram-webhook] callback_data non riconosciuto:', callbackData)
    await answerCallbackQuery(callbackQueryId, '❓ Azione non riconosciuta')
    return json({ ok: true })
  }

  const esito = await eseguiAzione(queueId, azione, minuti)

  if (!esito.ok) {
    const testo = (esito.error ?? 'azione non riuscita').slice(0, 150)
    await answerCallbackQuery(callbackQueryId, `❌ ${testo}`)
    return json({ ok: false, error: esito.error })
  }

  await answerCallbackQuery(callbackQueryId, esito.message ?? '✅ Fatto')
  await pulisciFratelli(esito.siblings, chatId)
  await deleteMessage(chatId, messageId)

  return json({ ok: true, action: azione })
})
