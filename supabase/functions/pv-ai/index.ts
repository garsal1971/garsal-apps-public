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
import { chiediIA, disponibili, ErroreIA, FORNITORI, type Fornitore } from '../_shared/ia.ts'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const ANON_KEY     = Deno.env.get('SUPABASE_ANON_KEY')!

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
      await chiediIA(scelta, apiKey, testo, foto, { sistema: SISTEMA, etichetta: 'pv-ai' }))
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
