-- ============================================================================
-- Tasks: il template di default di una categoria.
--
-- Un preset dei campi di un task — tipo, punti, «mostra in panoramica» e i
-- promemoria per canale — legato a una categoria. Aprendo un nuovo task si
-- sceglie prima la categoria, e il form si riempie col suo template.
--
-- ⚠️ Tabella dei Tasks (ts_) e non una colonna di cm_categories: le categorie
-- sono condivise con Abituati, Memo, Decisioni e Obiettivi, e si gestiscono
-- solo da AppSphere. Il template è roba dei Tasks e resta qui.
--
-- `template` (jsonb), scritto e letto solo da tasks.html:
--   { type, success_points, failure_points, skip_points, late_points,
--     workflow_points: { step_success, step_failure, task_success, task_failure },
--     show_in_panoramica,
--     promemoria: { telegram, telegram_ids[], telegram_complete_button,
--                   android, android_ids[], browser, browser_ids[], smart_block } }
-- I promemoria per int_id di cm_reminder_presets, non per etichetta.
--
-- ts_tasks.default_category_id: QUALE default il task rispetta. NULL = nessun
-- default applicato, oppure un campo del template modificato a mano dopo.
-- Si riaccende solo applicando di nuovo il default (form o «forza default»):
-- cambiare il template NON aggiorna da sé i task che lo rispettavano.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.ts_category_templates (
  category_id uuid PRIMARY KEY REFERENCES public.cm_categories(id) ON DELETE CASCADE,
  template    jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.ts_category_templates ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS ts_category_templates_garsal_only ON public.ts_category_templates;
CREATE POLICY ts_category_templates_garsal_only ON public.ts_category_templates
  FOR ALL USING (is_garsal()) WITH CHECK (is_garsal());

ALTER TABLE public.ts_tasks
  ADD COLUMN IF NOT EXISTS default_category_id uuid
    REFERENCES public.cm_categories(id) ON DELETE SET NULL;

COMMENT ON COLUMN public.ts_tasks.default_category_id IS
  'Categoria il cui template di default il task rispetta; NULL = nessuno o modificato a mano';
