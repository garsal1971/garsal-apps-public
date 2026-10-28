// ============================================================
// af-ai — Auto-formazione: la correzione di un esercizio
// v1.0.0 — 2026-10-28  (la pagina è autoformazione.html)
//
// Riceve { prompt: string, testo: string, provider?: 'gemini'|'qwen'|'groq'|'claude' }
// col JWT dell'utente, manda all'IA scelta il prompt di correzione seguito dal
// testo dell'esercizio, e risponde con { answer, voto, provider, model }.
//
// ⚠️ NON SCRIVE NIENTE: l'esercizio si salva solo alla ✅ Conferma, dalla
// pagina. Una correzione che non piace si rifà senza lasciare righe dietro.
//
// ⚠️ La richiesta del voto la aggiunge QUESTA funzione in coda al prompt
// (CODA_VOTO), non la pagina e non l'utente: il voto si legge con una regex
// (leggiVoto), e la forma che la regex si aspetta deve stare accanto a lei.
// Un voto non leggibile torna `null`, e la pagina blocca la conferma.
//
// { action: 'fornitori' } risponde con le IA che hanno la chiave nei Secrets.
//
// ⚠️ Le chiamate ai quattro motori sono COPIATE da ia-tunnel (che le ha
// copiate da pv-ai): se cambi un fornitore là, portalo anche qui.
//
// Segreti (gli stessi di pv-ai, basta averne uno):
//   GEMINI_API_KEY, DASHSCOPE_API_KEY, GROQ_API_KEY, ANTHROPIC_API_KEY
// ============================================================

import Anthropic from 'npm:@anthropic-ai/sdk'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const ANON_KEY     = Deno.env.get('SUPABASE_ANON_KEY')!
const MODELLO_CLAUDE = 'claude-opus-5'
const MODELLO_GEMINI = Deno.env.get('GEMINI_MODEL')   || 'gemini-2.5-flash'
const MODELLO_QWEN   = Deno.env.get('QWEN_MODEL')     || 'qwen-plus'
const GROQ_MODEL_SCELTO    = Deno.env.get('GROQ_MODEL')    || ''
const GROQ_VL_MODEL_SCELTO = Deno.env.get('GROQ_VL_MODEL') || ''
const GROQ_PREFERITI    = ['openai/gpt-oss-120b', 'llama-3.3-70b-versatile', 'moonshotai/kimi-k2-instruct', 'qwen/qwen3-32b']
const GROQ_PREFERITI_VL = ['meta-llama/llama-4-maverick-17b-128e-instruct', 'meta-llama/llama-4-scout-17b-16e-instruct']
const GROQ_URL       = 'https://api.groq.com/openai/v1'
const DASHSCOPE_URL  = (Deno.env.get('DASHSCOPE_BASE_URL') || 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1').replace(/\/$/, '')

const FORNITORI = [
  { id: 'gemini', nome: 'Gemini', segreto: 'GEMINI_API_KEY' },
  { id: 'qwen',   nome: 'Qwen',   segreto: 'DASHSCOPE_API_KEY' },
  { id: 'groq',   nome: 'Groq',   segreto: 'GROQ_API_KEY' },
  { id: 'claude', nome: 'Claude', segreto: 'ANTHROPIC_API_KEY' },
] as const
type Fornitore = typeof FORNITORI[number]['id']
const disponibili = () => FORNITORI.filter(f => !!Deno.env.get(f.segreto))

const ATTESA_MS = 150_000
const MAX_PROMPT = 10_000
const MAX_TESTO  = 20_000

class ErroreIA extends Error {
  constructor(msg: string, public status = 502) { super(msg) }
}

const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, 'Content-Type': 'application/json' } })

const SISTEMA = ''

const CODA_VOTO =
  '\n\nAl termine della correzione scrivi, su una riga a sé e come ultima riga, ' +
  'il voto complessivo da 1 a 10 (numero intero) esattamente in questa forma:\n' +
  'VOTO: <numero>/10'

/** L'ULTIMO «VOTO: n» della risposta, intero fra 1 e 10; altrimenti null. */
function leggiVoto(testo: string): number | null {
  const tutti = [...testo.matchAll(/VOTO\**\s*[:=]\s*\**\s*(\d{1,2}(?:[.,]\d+)?)\s*(?:\/\s*10)?/gi)]
  if (!tutti.length) return null
  const n = Math.round(Number(tutti[tutti.length - 1][1].replace(',', '.')))
  return Number.isFinite(n) && n >= 1 && n <= 10 ? n : null
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS })
  if (req.method !== 'POST') return json({ ok: false, error: 'Method Not Allowed' }, 405)

  const auth = req.headers.get('Authorization') ?? ''
  if (!auth) return json({ ok: false, error: 'serve un login' }, 401)

  let corpo: { prompt?: string; testo?: string; provider?: string; action?: string }
  try { corpo = await req.json() } catch { return json({ ok: false, error: 'corpo non leggibile' }, 400) }

  if (corpo.action === 'fornitori') {
    return json({ ok: true, fornitori: disponibili().map(f => ({ id: f.id, nome: f.nome })) })
  }

  const liberi = disponibili()
  if (!liberi.length) return json({ ok: false, error: 'Nessuna IA configurata: metti GEMINI_API_KEY, DASHSCOPE_API_KEY, GROQ_API_KEY o ANTHROPIC_API_KEY nei Secrets di Supabase.' }, 500)
  const scelta = (corpo.provider || liberi[0].id) as Fornitore
  const fornitore = FORNITORI.find(f => f.id === scelta)
  if (!fornitore) return json({ ok: false, error: `IA sconosciuta: ${scelta}` }, 400)
  const apiKey = Deno.env.get(fornitore.segreto)
  if (!apiKey) return json({ ok: false, error: `${fornitore.nome} non è configurata: manca ${fornitore.segreto} nei Secrets di Supabase.` }, 400)

  const prompt = (corpo.prompt ?? '').trim()
  const testo  = (corpo.testo ?? '').trim()
  if (!prompt) return json({ ok: false, error: 'scrivi il prompt di correzione' }, 400)
  if (!testo)  return json({ ok: false, error: "scrivi il testo dell'esercizio" }, 400)
  if (prompt.length > MAX_PROMPT) return json({ ok: false, error: `prompt troppo lungo (max ${MAX_PROMPT} caratteri)` }, 400)
  if (testo.length > MAX_TESTO)   return json({ ok: false, error: `testo troppo lungo (max ${MAX_TESTO} caratteri)` }, 400)

  // Il login si verifica davvero: la funzione spende chiavi a pagamento.
  const sb = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } })
  const { data: utente, error: errUtente } = await sb.auth.getUser(auth.replace(/^Bearer\s+/i, ''))
  if (errUtente || !utente?.user) return json({ ok: false, error: 'login non valido' }, 401)

  const richiesta = `${prompt}${CODA_VOTO}\n\n--- TESTO DELL'ESERCIZIO ---\n${testo}`

  let risposta: string, modello: string
  try {
    ;({ testo: risposta, modello } =
      scelta === 'gemini' ? await chiediGemini(apiKey, richiesta, [])
      : scelta === 'qwen' ? await chiediCompatibile('Qwen', DASHSCOPE_URL, apiKey, MODELLO_QWEN, richiesta, [])
      : scelta === 'groq' ? await chiediGroq(apiKey, richiesta, [])
      :                     await chiediClaude(apiKey, richiesta, []))
  } catch (e) {
    if (e instanceof ErroreIA) return json({ ok: false, error: e.message }, e.status)
    if (e instanceof Anthropic.RateLimitError) return json({ ok: false, error: 'IA sovraccarica, riprova fra poco.' }, 429)
    if (e instanceof Anthropic.APIError)       return json({ ok: false, error: `Claude: ${e.message}` }, 502)
    if ((e as Error).name === 'TimeoutError')  return json({ ok: false, error: `${fornitore.nome} non ha risposto in tempo, riprova.` }, 504)
    return json({ ok: false, error: `${fornitore.nome} non raggiungibile: ` + (e as Error).message }, 502)
  }

  if (!risposta) return json({ ok: false, error: 'risposta vuota' }, 502)

  return json({ ok: true, answer: risposta, voto: leggiVoto(risposta), provider: scelta, model: modello })
})

// ── i tre fornitori ─────────────────────────────────────────────────────────

type Esito = { testo: string; modello: string }

async function chiediClaude(apiKey: string, testo: string, foto: string[]): Promise<Esito> {
  // deno-lint-ignore no-explicit-any
  const contenuto: any[] = [
    ...foto.map((url) => ({ type: 'image', source: { type: 'url', url } })),
    { type: 'text', text: testo },
  ]
  const client = new Anthropic({ apiKey })
  // ⚠️ Streaming e finalMessage(): una risposta lunga con le foto può durare
  // parecchio, e una richiesta non in streaming rischierebbe il timeout HTTP.
  // deno-lint-ignore no-explicit-any
  const parametri: any = {
    model:         MODELLO_CLAUDE,
    max_tokens:    8000,
    ...(SISTEMA ? { system: SISTEMA } : {}),
    thinking:      { type: 'adaptive' },
    output_config: { effort: 'medium' },
    // Se i filtri di sicurezza rifiutano (succede, per esempio, con foto
    // ambigue), la richiesta ripassa da sé su un altro modello.
    betas:         ['server-side-fallback-2026-07-01'],
    fallbacks:     'default',
    messages:      [{ role: 'user', content: contenuto }],
  }
  const msg = await client.beta.messages.stream(parametri).finalMessage()
  if (msg.stop_reason === 'refusal') throw new ErroreIA('Claude non ha voluto rispondere a questa domanda.', 422)
  const out = msg.content
    .filter((b) => b.type === 'text')
    .map((b) => (b as { text: string }).text)
    .join('\n').trim()
  return { testo: out, modello: MODELLO_CLAUDE }
}

/**
 * Gemini (Google AI Studio, API generateContent).
 * ⚠️ Le immagini qui vanno INLINE in base64: Gemini non scarica da sé un
 * indirizzo qualsiasi. Sono al massimo quattro, già ridotte a 1600 px.
 */
async function chiediGemini(apiKey: string, testo: string, foto: string[]): Promise<Esito> {
  // deno-lint-ignore no-explicit-any
  const parti: any[] = []
  for (const url of foto) {
    const img = await scaricaBase64(url)
    if (img) parti.push({ inline_data: { mime_type: img.mime, data: img.data } })
  }
  parti.push({ text: testo })
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(MODELLO_GEMINI)}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
    body: JSON.stringify({
      ...(SISTEMA ? { systemInstruction: { parts: [{ text: SISTEMA }] } } : {}),
      contents: [{ role: 'user', parts: parti }],
      generationConfig: { maxOutputTokens: 8192 },
    }),
    signal: AbortSignal.timeout(ATTESA_MS),
  })
  const j = await r.json().catch(() => ({}))
  if (r.status === 429) throw new ErroreIA('Gemini: limite di richieste raggiunto, riprova fra poco.', 429)
  if (!r.ok) throw new ErroreIA(`Gemini: ${j?.error?.message || 'HTTP ' + r.status}`)
  if (j?.promptFeedback?.blockReason) throw new ErroreIA(`Gemini non ha voluto rispondere (${j.promptFeedback.blockReason}).`, 422)
  const cand = j?.candidates?.[0]
  // I «pensieri» dei modelli 2.5 arrivano come parti con thought: true: non sono la risposta.
  // deno-lint-ignore no-explicit-any
  const out = (cand?.content?.parts ?? []).filter((p: any) => p.text && !p.thought).map((p: any) => p.text).join('').trim()
  if (!out && cand?.finishReason === 'SAFETY') throw new ErroreIA('Gemini non ha voluto rispondere a questa domanda.', 422)
  return { testo: out, modello: MODELLO_GEMINI }
}

/**
 * Qwen (Alibaba DashScope) e Groq: tutt'e due parlano il dialetto di OpenAI
 * (`/chat/completions`), quindi la chiamata è una sola e cambiano indirizzo,
 * chiave e modello. Con le foto serve il modello che le sa leggere (vision):
 * quello di solo testo le rifiuterebbe. Le foto passano come indirizzi
 * firmati, che il servizio scarica da sé.
 */
async function chiediCompatibile(
  nome: string, base: string, apiKey: string, modello: string, testo: string, foto: string[],
): Promise<Esito> {
  const contenuto = foto.length
    ? [...foto.map((url) => ({ type: 'image_url', image_url: { url } })), { type: 'text', text: testo }]
    : testo
  const r = await fetch(`${base}/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${apiKey}` },
    body: JSON.stringify({
      model: modello,
      max_tokens: 8000,
      messages: [...(SISTEMA ? [{ role: 'system', content: SISTEMA }] : []), { role: 'user', content: contenuto }],
    }),
    signal: AbortSignal.timeout(ATTESA_MS),
  })
  const j = await r.json().catch(() => ({}))
  if (r.status === 429) throw new ErroreIA(`${nome}: limite di richieste raggiunto, riprova fra poco.`, 429)
  if (!r.ok) throw new ErroreIA(`${nome}: ${j?.error?.message || j?.message || 'HTTP ' + r.status}`)
  const out = String(j?.choices?.[0]?.message?.content ?? '').trim()
  return { testo: out, modello }
}

/**
 * Groq toglie i modelli senza preavviso, e un nome scritto nel codice (o in un
 * secret) diventa «the model does not exist» dall'oggi al domani. Il modello
 * quindi si CHIEDE a Groq a ogni avvio della funzione (con una cache di un'ora)
 * e si sceglie da quel che c'è davvero.
 *
 * ⚠️ Con le foto serve un modello che le legga. Se Groq non ne ha nessuno le
 * foto si lasciano fuori e la risposta LO DICE: una risposta che ignora le foto
 * in silenzio sembrerebbe averle guardate.
 */
async function chiediGroq(apiKey: string, testo: string, foto: string[]): Promise<Esito> {
  const conFoto = foto.length > 0
  const vl = conFoto ? await scegliModelloGroq(apiKey, true) : null
  if (vl) return chiediCompatibile('Groq', GROQ_URL, apiKey, vl, testo, foto)
  const modello = await scegliModelloGroq(apiKey, false)
  if (!modello) throw new ErroreIA('Groq: nessun modello di testo disponibile per questa chiave.')
  const esito = await chiediCompatibile('Groq', GROQ_URL, apiKey, modello, testo, [])
  if (conFoto) esito.testo = '_(Groq oggi non ha un modello che legga le foto: ho risposto sul solo testo.)_\n\n' + esito.testo
  return esito
}

let groqCache: { quando: number; ids: string[] } | null = null

async function modelliGroq(apiKey: string): Promise<string[]> {
  if (groqCache && Date.now() - groqCache.quando < 3600_000) return groqCache.ids
  const r = await fetch(`${GROQ_URL}/models`, {
    headers: { 'Authorization': `Bearer ${apiKey}` },
    signal: AbortSignal.timeout(15_000),
  })
  const j = await r.json().catch(() => ({}))
  if (r.status === 401) throw new ErroreIA('Groq: la chiave GROQ_API_KEY non è valida.', 502)
  if (!r.ok) throw new ErroreIA(`Groq: non riesco a leggere l'elenco dei modelli (${j?.error?.message || 'HTTP ' + r.status}).`)
  const ids = (j?.data ?? [])
    .filter((m: { active?: boolean }) => m?.active !== false)
    .map((m: { id: string }) => String(m.id))
    // fuori ciò che non è una chat: trascrizione, voce, filtri di sicurezza
    .filter((id: string) => !/whisper|tts|playai|guard|orpheus|distil/i.test(id))
  groqCache = { quando: Date.now(), ids }
  return ids
}

/** Quanti miliardi di parametri dice il nome («…-70b-…», «120b»); 0 se non lo dice. */
function taglia(id: string): number {
  const m = [...id.matchAll(/(\d+)b(?![a-z])/gi)].map((x) => Number(x[1]))
  return m.length ? Math.max(...m) : 0
}

async function scegliModelloGroq(apiKey: string, vision: boolean): Promise<string | null> {
  const ids = await modelliGroq(apiKey)
  const leggeFoto = (id: string) => /llama-4|vision|scout|maverick/i.test(id)
  const adatti = ids.filter((id) => vision ? leggeFoto(id) : !/compound/i.test(id))
  if (!adatti.length) return null
  // Il secret vince, ma solo se quel modello c'è ancora: un secret vecchio non
  // deve rompere la funzione più di quanto la rompesse un nome nel codice.
  const scelto = vision ? GROQ_VL_MODEL_SCELTO : GROQ_MODEL_SCELTO
  if (scelto) {
    if (adatti.includes(scelto)) return scelto
    console.warn(`[af-ai] Groq: il modello «${scelto}» del secret non c'è più, ne scelgo un altro`)
  }
  for (const id of vision ? GROQ_PREFERITI_VL : GROQ_PREFERITI) if (adatti.includes(id)) return id
  return [...adatti].sort((a, b) => taglia(b) - taglia(a))[0]
}

async function scaricaBase64(url: string): Promise<{ mime: string; data: string } | null> {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(30_000) })
    if (!r.ok) return null
    const buf = new Uint8Array(await r.arrayBuffer())
    let bin = ''
    for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode(...buf.subarray(i, i + 0x8000))
    return { mime: r.headers.get('content-type')?.split(';')[0] || 'image/jpeg', data: btoa(bin) }
  } catch (e) {
    console.warn('[af-ai] immagine non scaricata:', (e as Error).message)
    return null
  }
}
