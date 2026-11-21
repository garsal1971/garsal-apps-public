// I quattro motori IA (Gemini, Qwen, Groq, Claude) delle Edge Functions pv-ai, ia-tunnel e
// af-ai. Fino al 21/11/2026 erano copiati a mano nei tre file. Cambiandone uno qui vanno
// ridistribuite tutt'e tre: deploy.yml lo fa da sé quando il commit tocca
// supabase/functions/_shared/.
//
// Ogni funzione passa il suo prompt di sistema (vuoto = nessuno: Chiedi a Pino e
// Autoformazione mandano all'IA quel che si è scritto e basta) e un'etichetta per i log.

import Anthropic from 'npm:@anthropic-ai/sdk'

const MODELLO_CLAUDE = 'claude-opus-5'
const MODELLO_GEMINI = Deno.env.get('GEMINI_MODEL')   || 'gemini-2.5-flash'
const MODELLO_QWEN   = Deno.env.get('QWEN_MODEL')     || 'qwen-plus'
const MODELLO_QWEN_VL = Deno.env.get('QWEN_VL_MODEL') || 'qwen-vl-max'
const GROQ_MODEL_SCELTO    = Deno.env.get('GROQ_MODEL')    || ''
const GROQ_VL_MODEL_SCELTO = Deno.env.get('GROQ_VL_MODEL') || ''
// Solo un ORDINE DI PREFERENZA, non un elenco da cui dipendere: se nessuno di
// questi c'è più si prende il più grande che Groq offre (vedi scegliModelloGroq).
const GROQ_PREFERITI    = ['openai/gpt-oss-120b', 'llama-3.3-70b-versatile', 'moonshotai/kimi-k2-instruct', 'qwen/qwen3-32b']
const GROQ_PREFERITI_VL = ['meta-llama/llama-4-maverick-17b-128e-instruct', 'meta-llama/llama-4-scout-17b-16e-instruct']
const GROQ_URL       = 'https://api.groq.com/openai/v1'
const DASHSCOPE_URL  = (Deno.env.get('DASHSCOPE_BASE_URL') || 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1').replace(/\/$/, '')

// L'ordine è quello della tendina: prima le gratuite.
export const FORNITORI = [
  { id: 'gemini', nome: 'Gemini', segreto: 'GEMINI_API_KEY' },
  { id: 'qwen',   nome: 'Qwen',   segreto: 'DASHSCOPE_API_KEY' },
  { id: 'groq',   nome: 'Groq',   segreto: 'GROQ_API_KEY' },
  { id: 'claude', nome: 'Claude', segreto: 'ANTHROPIC_API_KEY' },
] as const
export type Fornitore = typeof FORNITORI[number]['id']
export const disponibili = () => FORNITORI.filter(f => !!Deno.env.get(f.segreto))

// Un'IA che non risponde entro due minuti e mezzo non risponderà: meglio dirlo.
const ATTESA_MS = 150_000

export class ErroreIA extends Error {
  constructor(msg: string, public status = 502) { super(msg) }
}

/**
 * La domanda al motore scelto. Con le immagini Qwen usa il modello che le legge (vision).
 */
export async function chiediIA(
  scelta: Fornitore, apiKey: string, testo: string, foto: string[],
  opz: { sistema?: string; etichetta: string },
): Promise<Esito> {
  const sistema = opz.sistema ?? ''
  return scelta === 'gemini' ? await chiediGemini(apiKey, testo, foto, sistema, opz.etichetta)
    : scelta === 'qwen' ? await chiediCompatibile('Qwen', DASHSCOPE_URL, apiKey,
                            foto.length ? MODELLO_QWEN_VL : MODELLO_QWEN, testo, foto, sistema)
    : scelta === 'groq' ? await chiediGroq(apiKey, testo, foto, sistema, opz.etichetta)
    :                     await chiediClaude(apiKey, testo, foto, sistema)
}

export type Esito = { testo: string; modello: string }

async function chiediClaude(apiKey: string, testo: string, foto: string[], sistema: string): Promise<Esito> {
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
    ...(sistema ? { system: sistema } : {}),
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
 * ⚠️ Le foto qui vanno INLINE in base64: Gemini non scarica da sé un indirizzo
 * qualsiasi. Sono al massimo quattro, già ridotte a 1600 px — qualche centinaio
 * di KB l'una.
 */
async function chiediGemini(apiKey: string, testo: string, foto: string[], sistema: string, etichetta: string): Promise<Esito> {
  // deno-lint-ignore no-explicit-any
  const parti: any[] = []
  for (const url of foto) {
    const img = await scaricaBase64(url, etichetta)
    if (img) parti.push({ inline_data: { mime_type: img.mime, data: img.data } })
  }
  parti.push({ text: testo })
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(MODELLO_GEMINI)}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
    body: JSON.stringify({
      ...(sistema ? { systemInstruction: { parts: [{ text: sistema }] } } : {}),
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
  nome: string, base: string, apiKey: string, modello: string, testo: string, foto: string[], sistema: string,
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
      messages: [...(sistema ? [{ role: 'system', content: sistema }] : []), { role: 'user', content: contenuto }],
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
async function chiediGroq(apiKey: string, testo: string, foto: string[], sistema: string, etichetta: string): Promise<Esito> {
  const conFoto = foto.length > 0
  const vl = conFoto ? await scegliModelloGroq(apiKey, true, etichetta) : null
  if (vl) return chiediCompatibile('Groq', GROQ_URL, apiKey, vl, testo, foto, sistema)
  const modello = await scegliModelloGroq(apiKey, false, etichetta)
  if (!modello) throw new ErroreIA('Groq: nessun modello di testo disponibile per questa chiave.')
  const esito = await chiediCompatibile('Groq', GROQ_URL, apiKey, modello, testo, [], sistema)
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

async function scegliModelloGroq(apiKey: string, vision: boolean, etichetta: string): Promise<string | null> {
  const ids = await modelliGroq(apiKey)
  const leggeFoto = (id: string) => /llama-4|vision|scout|maverick/i.test(id)
  const adatti = ids.filter((id) => vision ? leggeFoto(id) : !/compound/i.test(id))
  if (!adatti.length) return null
  // Il secret vince, ma solo se quel modello c'è ancora: un secret vecchio non
  // deve rompere la funzione più di quanto la rompesse un nome nel codice.
  const scelto = vision ? GROQ_VL_MODEL_SCELTO : GROQ_MODEL_SCELTO
  if (scelto) {
    if (adatti.includes(scelto)) return scelto
    console.warn(`[${etichetta}] Groq: il modello «${scelto}» del secret non c'è più, ne scelgo un altro`)
  }
  for (const id of vision ? GROQ_PREFERITI_VL : GROQ_PREFERITI) if (adatti.includes(id)) return id
  return [...adatti].sort((a, b) => taglia(b) - taglia(a))[0]
}

async function scaricaBase64(url: string, etichetta: string): Promise<{ mime: string; data: string } | null> {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(30_000) })
    if (!r.ok) return null
    const buf = new Uint8Array(await r.arrayBuffer())
    let bin = ''
    for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode(...buf.subarray(i, i + 0x8000))
    return { mime: r.headers.get('content-type')?.split(';')[0] || 'image/jpeg', data: btoa(bin) }
  } catch (e) {
    console.warn(`[${etichetta}] immagine non scaricata:`, (e as Error).message)
    return null
  }
}
