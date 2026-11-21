// ============================================================
// ia-tunnel — IA Tunnel
// v1.1.0 — 2026-09-27  (la pagina è «Chiedi a Pino», chiedi-a-pino.html)
//
// Riceve { prompt: string, provider?: 'gemini'|'qwen'|'groq'|'claude', tag_ids?: uuid[],
//          message_id?: uuid, image_paths?: string[] }
// col JWT dell'utente, manda il prompt COSÌ COM'È all'IA scelta e archivia
// domanda, risposta, motore e modello in ia_tunnel_messages, con i tag in
// ia_tunnel_message_tags.
//
// { action: 'fornitori' } risponde con le IA che hanno la chiave nei Secrets,
// come in pv-ai: la tendina offre solo quelle.
//
// Le chiamate ai quattro motori stanno in _shared/ia.ts, comuni con pv-ai e af-ai.
//
// ⚠️ Le immagini (fino a 4) le carica la PAGINA nel bucket privato
// `ia-tunnel-images`, in `<utente>/<message_id>/…`, prima di chiamare: qui si
// controlla che ogni percorso stia nella cartella di QUESTO utente e di QUESTA
// domanda, se ne fanno indirizzi firmati di dieci minuti e si mandano all'IA.
// La domanda nasce con l'id che la pagina ha scelto, e solo dopo le righe di
// ia_tunnel_images. Senza quel controllo sul percorso, un utente potrebbe
// farsi leggere dall'IA un file di un altro.
//
// ⚠️ Nessun prompt di sistema: è un tunnel, e quel che arriva all'IA è quel che
// si è scritto. Un'istruzione nascosta cambierebbe le risposte senza dirlo.
//
// ⚠️ Letture e scritture passano col JWT dell'utente, quindi sotto RLS.
//
// Segreti (gli stessi di pv-ai, basta averne uno):
//   GEMINI_API_KEY, DASHSCOPE_API_KEY, GROQ_API_KEY, ANTHROPIC_API_KEY
//   (più GEMINI_MODEL, QWEN_MODEL, GROQ_MODEL, DASHSCOPE_BASE_URL facoltativi)
// ============================================================

import Anthropic from 'npm:@anthropic-ai/sdk'
import { chiediIA, disponibili, ErroreIA, FORNITORI, type Fornitore } from '../_shared/ia.ts'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const ANON_KEY     = Deno.env.get('SUPABASE_ANON_KEY')!

const MAX_PROMPT = 20_000
const MAX_FOTO   = 4
const BUCKET     = 'ia-tunnel-images'

const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, 'Content-Type': 'application/json' } })

// Vuoto di proposito: vedi l'intestazione (chiediIA non manda nessun prompt di sistema).
const SISTEMA = ''

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS })
  if (req.method !== 'POST') return json({ ok: false, error: 'Method Not Allowed' }, 405)

  const auth = req.headers.get('Authorization') ?? ''
  if (!auth) return json({ ok: false, error: 'serve un login' }, 401)

  let corpo: { prompt?: string; provider?: string; action?: string; tag_ids?: unknown; message_id?: unknown; image_paths?: unknown }
  try { corpo = await req.json() } catch { return json({ ok: false, error: 'corpo non leggibile' }, 400) }

  if (corpo.action === 'fornitori') {
    return json({ ok: true, fornitori: disponibili().map(f => ({ id: f.id, nome: f.nome })) })
  }

  const liberi = disponibili()
  if (!liberi.length) return json({ ok: false, error: 'Nessuna IA configurata: metti GEMINI_API_KEY, DASHSCOPE_API_KEY, GROQ_API_KEY o ANTHROPIC_API_KEY nei Secrets di Supabase.' }, 500)
  // Una scelta senza chiave si DICE, invece di rispondere di nascosto con un'altra IA.
  const scelta = (corpo.provider || liberi[0].id) as Fornitore
  const fornitore = FORNITORI.find(f => f.id === scelta)
  if (!fornitore) return json({ ok: false, error: `IA sconosciuta: ${scelta}` }, 400)
  const apiKey = Deno.env.get(fornitore.segreto)
  if (!apiKey) return json({ ok: false, error: `${fornitore.nome} non è configurata: manca ${fornitore.segreto} nei Secrets di Supabase.` }, 400)

  const prompt = (corpo.prompt ?? '').trim()
  if (!prompt) return json({ ok: false, error: 'scrivi un prompt' }, 400)
  if (prompt.length > MAX_PROMPT) return json({ ok: false, error: `prompt troppo lungo (max ${MAX_PROMPT} caratteri)` }, 400)
  const tagIds = Array.isArray(corpo.tag_ids)
    ? [...new Set(corpo.tag_ids.filter((t): t is string => typeof t === 'string' && /^[0-9a-f-]{36}$/i.test(t)))]
    : []

  const sb = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } })
  const { data: utente, error: errUtente } = await sb.auth.getUser(auth.replace(/^Bearer\s+/i, ''))
  if (errUtente || !utente?.user) return json({ ok: false, error: 'login non valido' }, 401)
  const uid = utente.user.id

  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  const messageId = typeof corpo.message_id === 'string' && UUID.test(corpo.message_id) ? corpo.message_id : null
  const percorsi = Array.isArray(corpo.image_paths)
    ? corpo.image_paths.filter((p): p is string => typeof p === 'string' && p.length > 0)
    : []
  if (percorsi.length > MAX_FOTO) return json({ ok: false, error: `al massimo ${MAX_FOTO} immagini` }, 400)
  if (percorsi.length && !messageId) return json({ ok: false, error: 'immagini senza message_id' }, 400)
  const cartella = `${uid}/${messageId}/`
  if (percorsi.some(p => !p.startsWith(cartella) || p.includes('..') || p.slice(cartella.length).includes('/'))) {
    return json({ ok: false, error: 'percorso di immagine non valido' }, 400)
  }
  const foto: string[] = []
  for (const path of percorsi) {
    const { data, error } = await sb.storage.from(BUCKET).createSignedUrl(path, 600)
    if (error || !data?.signedUrl) return json({ ok: false, error: `immagine non leggibile (${path.split('/').pop()}): ${error?.message ?? 'nessun indirizzo'}` }, 400)
    foto.push(data.signedUrl)
  }

  let risposta: string, modello: string
  try {
    ;({ testo: risposta, modello } =
      await chiediIA(scelta, apiKey, prompt, foto, { sistema: SISTEMA, etichetta: 'ia-tunnel' }))
  } catch (e) {
    if (e instanceof ErroreIA) return json({ ok: false, error: e.message }, e.status)
    if (e instanceof Anthropic.RateLimitError) return json({ ok: false, error: 'IA sovraccarica, riprova fra poco.' }, 429)
    if (e instanceof Anthropic.APIError)       return json({ ok: false, error: `Claude: ${e.message}` }, 502)
    if ((e as Error).name === 'TimeoutError')  return json({ ok: false, error: `${fornitore.nome} non ha risposto in tempo, riprova.` }, 504)
    return json({ ok: false, error: `${fornitore.nome} non raggiungibile: ` + (e as Error).message }, 502)
  }

  if (!risposta) return json({ ok: false, error: 'risposta vuota' }, 502)

  const { data: salvata, error: errSalva } = await sb.from('ia_tunnel_messages')
    .insert({ ...(messageId ? { id: messageId } : {}), prompt, answer: risposta, provider: scelta, model: modello })
    .select('id, created_at')
    .single()
  if (errSalva) console.error('[ia-tunnel] salvataggio:', errSalva)

  // ⚠️ I tag si collegano solo se la domanda è stata salvata; un tag che non è
  // dell'utente la RLS lo rifiuta, e la risposta lo dice invece di tacere.
  let tagSalvati = !tagIds.length
  if (salvata && tagIds.length) {
    const { error: errTag } = await sb.from('ia_tunnel_message_tags')
      .insert(tagIds.map(tag_id => ({ message_id: salvata.id, tag_id })))
    if (errTag) console.error('[ia-tunnel] tag:', errTag)
    tagSalvati = !errTag
  }

  let immaginiSalvate = !percorsi.length
  if (salvata && percorsi.length) {
    const { error: errImg } = await sb.from('ia_tunnel_images')
      .insert(percorsi.map((storage_path, position) => ({ message_id: salvata.id, storage_path, position })))
    if (errImg) console.error('[ia-tunnel] immagini:', errImg)
    immaginiSalvate = !errImg
  }

  return json({
    ok: true,
    answer: risposta,
    id: salvata?.id ?? null,
    created_at: salvata?.created_at ?? null,
    saved: !errSalva,
    tags_saved: tagSalvati,
    images_saved: immaginiSalvate,
    photos: foto.length,
    provider: scelta,
    model: modello,
  })
})
