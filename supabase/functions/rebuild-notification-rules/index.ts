// ============================================================
// Edge Function: rebuild-notification-rules
// Scopo: pulizia regole orfane in cm_notification_rules.
//
// In precedenza leggeva ts_tasks.reminders per ricostruire le regole;
// ora cm_notification_rules è la fonte autoritativa (scritto dal frontend).
// Questa funzione si limita a eliminare le righe orfane
// (task terminati o non più esistenti).
//
// Parametri (body JSON, tutti opzionali):
//   dry_run  boolean (default false) — stampa cosa farebbe, senza scrivere
//   user_id  string  (opzionale)     — limita l'operazione a un singolo utente
//
// Esempio di chiamata:
//   POST /functions/v1/rebuild-notification-rules
//   Headers: Authorization: Bearer <SERVICE_ROLE_KEY>
//   Body: {}
// ============================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SUPABASE_URL     = Deno.env.get('SUPABASE_URL')!
const SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

const sb = createClient(SUPABASE_URL, SERVICE_ROLE_KEY)

Deno.serve(async (req) => {
  try {
    let dry_run        = false
    let filter_user_id: string | null = null

    try {
      const body = await req.json()
      if (typeof body.dry_run  === 'boolean') dry_run        = body.dry_run
      if (typeof body.user_id  === 'string')  filter_user_id = body.user_id
    } catch { /* body assente o non JSON → usa default */ }

    console.log(`[rebuild-rules] avvio — dry_run=${dry_run} filter_user_id=${filter_user_id ?? 'tutti'}`)

    // -----------------------------------------------------------------------
    // 1. Carica i task attivi (non terminati)
    // -----------------------------------------------------------------------
    const { data: tasksData, error: tasksErr } = await sb
      .from('ts_tasks')
      .select('id')
      .not('status', 'eq', 'terminated')

    if (tasksErr) throw tasksErr

    const validEntityIds = new Set((tasksData ?? []).map(t => String(t.id)))
    console.log(`[rebuild-rules] task attivi: ${validEntityIds.size}`)

    // -----------------------------------------------------------------------
    // 2. Carica le regole tasks esistenti
    // -----------------------------------------------------------------------
    let rulesQuery = sb
      .from('cm_notification_rules')
      .select('id, entity_id, user_id')
      .eq('app', 'tasks')

    if (filter_user_id) rulesQuery = rulesQuery.eq('user_id', filter_user_id)

    const { data: rulesData, error: rulesErr } = await rulesQuery
    if (rulesErr) throw rulesErr

    const rules = rulesData ?? []
    console.log(`[rebuild-rules] regole trovate: ${rules.length}`)

    // -----------------------------------------------------------------------
    // 3. Identifica e cancella le regole orfane
    // -----------------------------------------------------------------------
    const orphanIds = rules
      .filter(r => !validEntityIds.has(String(r.entity_id)))
      .map(r => r.id as string)

    console.log(`[rebuild-rules] regole orfane: ${orphanIds.length}`)

    let deleted = 0
    if (orphanIds.length > 0 && !dry_run) {
      const { error: delErr, count } = await sb
        .from('cm_notification_rules')
        .delete({ count: 'exact' })
        .in('id', orphanIds)

      if (delErr) throw delErr
      deleted = count ?? 0
    } else if (dry_run) {
      deleted = orphanIds.length
    }

    const result = { ok: true, dry_run, active_tasks: validEntityIds.size, rules_checked: rules.length, deleted }
    console.log('[rebuild-rules] completato —', result)
    return json(result)

  } catch (err) {
    console.error('[rebuild-rules] fatal:', err)
    return json({ ok: false, error: String(err) }, 500)
  }
})

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
