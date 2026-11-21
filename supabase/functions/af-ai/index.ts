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
// Le chiamate ai quattro motori stanno in _shared/ia.ts, comuni con pv-ai e ia-tunnel.
//
// Segreti (gli stessi di pv-ai, basta averne uno):
//   GEMINI_API_KEY, DASHSCOPE_API_KEY, GROQ_API_KEY, ANTHROPIC_API_KEY
// ============================================================

import Anthropic from 'npm:@anthropic-ai/sdk'
import { chiediIA, disponibili, ErroreIA, FORNITORI, type Fornitore } from '../_shared/ia.ts'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const ANON_KEY     = Deno.env.get('SUPABASE_ANON_KEY')!

const MAX_PROMPT = 10_000
const MAX_TESTO  = 20_000

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
      await chiediIA(scelta, apiKey, richiesta, [], { sistema: SISTEMA, etichetta: 'af-ai' }))
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
