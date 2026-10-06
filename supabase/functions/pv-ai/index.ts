// ============================================================
// pv-ai — «Chiedi all'IA» delle Piante
// v1.3.0 — 2026-09-27
//
// Riceve { plant_id?: uuid, question: string, provider?: 'gemini'|'qwen'|'groq'|'claude' }
// col JWT dell'utente, raccoglie quel che l'app sa già — la scheda della pianta,
// le ultime voci del diario, le azioni in corso, e le foto più recenti — lo manda
// all'IA scelta, e salva la risposta in pv_ai_answers (con `provider` e `model`)
// perché resti rileggibile.
//
// { action: 'fornitori' } risponde con le IA che hanno la chiave nei Secrets:
// la tendina dell'app offre solo quelle, invece di un'IA che risponde «manca
// la chiave» dopo essere stata scelta.
//
// ⚠️ Tre fornitori, UN contesto: il testo e le foto sono gli stessi per tutti,
// cambia solo come si impacchettano. Così la risposta di Gemini e quella di
// Claude alla stessa domanda si possono confrontare.
//
// Senza plant_id la domanda è generale e il contesto è la LISTA DEI DESIDERI
// più l'elenco delle piante che si hanno già: è la domanda «cosa potrei
// prendere», e la risposta dipende da quel che c'è già in casa.
//
// ⚠️ Tutte le letture passano col JWT dell'utente (client anon + Authorization),
// quindi sotto RLS: la funzione non vede niente che l'utente non veda. Il
// service role qui non serve e non si usa.
//
// ⚠️ Le foto non passano in base64 dalla funzione: si mandano a Claude come
// indirizzi firmati del bucket privato, validi dieci minuti. Scaricarle qui
// vorrebbe dire tenere in memoria qualche megabyte per niente.
//
// Segreti (basta averne uno):
//   GEMINI_API_KEY     — Google AI Studio; modello GEMINI_MODEL (di partenza gemini-2.5-flash)
//   DASHSCOPE_API_KEY  — Alibaba Cloud Model Studio (internazionale); modelli
//                        QWEN_MODEL (qwen-plus) e, con le foto, QWEN_VL_MODEL (qwen-vl-max);
//                        DASHSCOPE_BASE_URL solo se si usa un'altra regione
//   GROQ_API_KEY       — console.groq.com, piano gratuito. Il modello NON si
//                        scrive: si chiede a Groq l'elenco (`/models`) e si
//                        sceglie da lì (vedi scegliModelloGroq). GROQ_MODEL e
//                        GROQ_VL_MODEL restano facoltativi, e valgono solo se
//                        quel modello è ancora nell'elenco
//   ANTHROPIC_API_KEY  — lo stesso di claude-diet
// ============================================================

import Anthropic from 'npm:@anthropic-ai/sdk'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const ANON_KEY     = Deno.env.get('SUPABASE_ANON_KEY')!
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
const FORNITORI = [
  { id: 'gemini', nome: 'Gemini', segreto: 'GEMINI_API_KEY' },
  { id: 'qwen',   nome: 'Qwen',   segreto: 'DASHSCOPE_API_KEY' },
  { id: 'groq',   nome: 'Groq',   segreto: 'GROQ_API_KEY' },
  { id: 'claude', nome: 'Claude', segreto: 'ANTHROPIC_API_KEY' },
] as const
type Fornitore = typeof FORNITORI[number]['id']
const disponibili = () => FORNITORI.filter(f => !!Deno.env.get(f.segreto))

// Un'IA che non risponde entro due minuti e mezzo non risponderà: meglio dirlo.
const ATTESA_MS = 150_000

class ErroreIA extends Error {
  constructor(msg: string, public status = 502) { super(msg) }
}

// Quante voci di diario e quante foto al massimo: abbastanza per vedere come
// sta andando, non tanto da far diventare la domanda un archivio.
const MAX_VOCI = 15
const MAX_FOTO = 4

const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, 'Content-Type': 'application/json' } })

const SISTEMA = `Sei un esperto di giardinaggio e cura delle piante (da appartamento, balcone e giardino).
Rispondi in italiano, in modo pratico e concreto, con consigli che si possono mettere in pratica subito.
Ti vengono dati la scheda della pianta, il suo diario di cura, le azioni programmate e alcune foto recenti:
usali per dare una risposta specifica per QUESTA pianta, non generica.
Se le foto mostrano problemi (foglie gialle, macchie, parassiti, marciume) dillo esplicitamente e proponi cosa fare.
Se un'informazione manca e cambia la risposta, dillo invece di inventarla.
Usa paragrafi brevi ed elenchi puntati; niente tabelle.`

function riga(etichetta: string, valore: unknown): string {
  if (valore === null || valore === undefined || String(valore).trim() === '') return ''
  return `- ${etichetta}: ${String(valore).trim()}\n`
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS })
  if (req.method !== 'POST') return json({ ok: false, error: 'Method Not Allowed' }, 405)

  const auth = req.headers.get('Authorization') ?? ''
  if (!auth) return json({ ok: false, error: 'serve un login' }, 401)

  let corpo: { plant_id?: string | null; question?: string; provider?: string; action?: string }
  try { corpo = await req.json() } catch { return json({ ok: false, error: 'corpo non leggibile' }, 400) }

  if (corpo.action === 'fornitori') {
    return json({ ok: true, fornitori: disponibili().map(f => ({ id: f.id, nome: f.nome })) })
  }

  const liberi = disponibili()
  if (!liberi.length) return json({ ok: false, error: 'Nessuna IA configurata: metti GEMINI_API_KEY, DASHSCOPE_API_KEY o ANTHROPIC_API_KEY nei Secrets di Supabase.' }, 500)
  // Senza scelta si usa la prima disponibile; una scelta senza chiave si DICE,
  // invece di rispondere di nascosto con un'altra IA.
  const scelta = (corpo.provider || liberi[0].id) as Fornitore
  const fornitore = FORNITORI.find(f => f.id === scelta)
  if (!fornitore) return json({ ok: false, error: `IA sconosciuta: ${scelta}` }, 400)
  const apiKey = Deno.env.get(fornitore.segreto)
  if (!apiKey) return json({ ok: false, error: `${fornitore.nome} non è configurata: manca ${fornitore.segreto} nei Secrets di Supabase.` }, 400)

  const domanda = (corpo.question ?? '').trim()
  if (!domanda) return json({ ok: false, error: 'scrivi una domanda' }, 400)
  if (domanda.length > 4000) return json({ ok: false, error: 'domanda troppo lunga (max 4000 caratteri)' }, 400)

  const sb = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } })
  const { data: utente, error: errUtente } = await sb.auth.getUser(auth.replace(/^Bearer\s+/i, ''))
  if (errUtente || !utente?.user) return json({ ok: false, error: 'login non valido' }, 401)

  const plantId = corpo.plant_id || null
  let contesto = ''
  const foto: string[] = []

  if (plantId) {
    const { data: p, error } = await sb.from('pv_plants').select('*').eq('id', plantId).maybeSingle()
    if (error || !p) return json({ ok: false, error: 'pianta non trovata' }, 404)

    contesto += `## La pianta\n`
    contesto += riga('Nome', p.name) + riga('Specie', p.species) + riga('Posizione', p.location)
      + riga('Arrivata il', p.acquired_on) + riga('Luce', p.light) + riga('Annaffiatura', p.watering)
      + riga('Terriccio e vaso', p.soil) + riga('Concime', p.fertilizer) + riga('Note', p.notes)

    const { data: voci } = await sb.from('pv_diary')
      .select('id, entry_date, comment')
      .eq('plant_id', plantId)
      .order('entry_date', { ascending: false })
      .order('created_at', { ascending: false })
      .limit(MAX_VOCI)
    if (voci?.length) {
      contesto += `\n## Diario (dal più recente)\n`
      for (const v of voci) contesto += `- ${v.entry_date}: ${(v.comment ?? '').trim() || '(solo foto)'}\n`
    }

    const { data: azioni } = await sb.from('pv_actions')
      .select('title, type, status, next_occurrence_date, last_completed_date, description')
      .eq('plant_id', plantId)
      .neq('status', 'terminated')
    if (azioni?.length) {
      contesto += `\n## Azioni di cura in corso\n`
      for (const a of azioni) {
        const prossima = a.next_occurrence_date ? String(a.next_occurrence_date).slice(0, 10) : '—'
        const ultima   = a.last_completed_date ? String(a.last_completed_date).slice(0, 10) : 'mai'
        contesto += `- ${a.title} (prossima: ${prossima}, ultima fatta: ${ultima})${a.description ? ' — ' + a.description : ''}\n`
      }
    }

    // La copertina della scheda e le foto più recenti del diario.
    const { data: imgs } = await sb.from('pv_images')
      .select('storage_path, diary_id, created_at, position')
      .eq('plant_id', plantId)
      .order('created_at', { ascending: false })
      .limit(40)
    const copertina = (imgs ?? []).filter(i => !i.diary_id).sort((a, b) => a.position - b.position)[0]
    const recenti   = (imgs ?? []).filter(i => i.diary_id).slice(0, MAX_FOTO - (copertina ? 1 : 0))
    const percorsi  = [...(copertina ? [copertina.storage_path] : []), ...recenti.map(i => i.storage_path)]
    for (const path of percorsi) {
      const { data } = await sb.storage.from('pv-images').createSignedUrl(path, 600)
      if (data?.signedUrl) foto.push(data.signedUrl)
    }
  } else {
    const { data: desideri } = await sb.from('pv_wishlist')
      .select('name, species, notes, priority, status')
      .eq('status', 'desiderata')
      .order('priority')
    const { data: piante } = await sb.from('pv_plants')
      .select('name, species, location').eq('archived', false)
    contesto += `## Le piante che ho già\n`
    for (const p of piante ?? []) contesto += `- ${p.name}${p.species ? ' (' + p.species + ')' : ''}${p.location ? ' — ' + p.location : ''}\n`
    if (!piante?.length) contesto += `- nessuna\n`
    contesto += `\n## Le piante che vorrei curare\n`
    for (const d of desideri ?? []) contesto += `- ${d.name}${d.species ? ' (' + d.species + ')' : ''}${d.notes ? ' — ' + d.notes : ''}\n`
    if (!desideri?.length) contesto += `- nessuna ancora\n`
  }

  const oggi = new Date().toLocaleDateString('sv-SE', { timeZone: 'Europe/Rome' })
  const testo = `Oggi è il ${oggi}.\n\n${contesto}\n## La domanda\n${domanda}`

  let risposta: string, modello: string
  try {
    ;({ testo: risposta, modello } =
      scelta === 'gemini' ? await chiediGemini(apiKey, testo, foto)
      : scelta === 'qwen' ? await chiediCompatibile('Qwen', DASHSCOPE_URL, apiKey,
                              foto.length ? MODELLO_QWEN_VL : MODELLO_QWEN, testo, foto)
      : scelta === 'groq' ? await chiediGroq(apiKey, testo, foto)
      :                     await chiediClaude(apiKey, testo, foto))
  } catch (e) {
    if (e instanceof ErroreIA) return json({ ok: false, error: e.message }, e.status)
    if (e instanceof Anthropic.RateLimitError) return json({ ok: false, error: 'IA sovraccarica, riprova fra poco.' }, 429)
    if (e instanceof Anthropic.APIError)       return json({ ok: false, error: `Claude: ${e.message}` }, 502)
    if ((e as Error).name === 'TimeoutError')  return json({ ok: false, error: `${fornitore.nome} non ha risposto in tempo, riprova.` }, 504)
    return json({ ok: false, error: `${fornitore.nome} non raggiungibile: ` + (e as Error).message }, 502)
  }

  if (!risposta) return json({ ok: false, error: 'risposta vuota' }, 502)

  // La risposta si archivia col JWT dell'utente: è lui a scriverla, sotto RLS.
  const { data: salvata, error: errSalva } = await sb.from('pv_ai_answers')
    .insert({ plant_id: plantId, question: domanda, answer: risposta, model: modello, provider: scelta })
    .select('id, created_at')
    .single()
  if (errSalva) console.error('[pv-ai] salvataggio:', errSalva)

  return json({
    ok: true,
    answer: risposta,
    id: salvata?.id ?? null,
    created_at: salvata?.created_at ?? null,
    saved: !errSalva,
    photos: foto.length,
    provider: scelta,
    model: modello,
  })
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
    system:        SISTEMA,
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
      systemInstruction: { parts: [{ text: SISTEMA }] },
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
      messages: [{ role: 'system', content: SISTEMA }, { role: 'user', content: contenuto }],
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
    console.warn(`[pv-ai] Groq: il modello «${scelto}» del secret non c'è più, ne scelgo un altro`)
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
    console.warn('[pv-ai] foto non scaricata:', (e as Error).message)
    return null
  }
}
