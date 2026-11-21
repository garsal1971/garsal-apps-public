-- Colonne che nessuno legge né scrive più (manutenzione 2026-11-21).
--
-- fnz_coverage_items.revaluation_pct: la rivalutazione per voce è stata sostituita da quella
--   unica della ⏳ macchina del tempo (cm_settings.fnz_rivalutazione). Verificato: 0 righe con
--   un valore, nessuna funzione o vista la cita, nessun client la nomina.
-- al_profile.birth_date / height_cm / sex: i dati anagrafici si leggono da cm_profile; di
--   al_profile si usa solo activity. Nessuna funzione o vista le cita, i client leggono
--   al_profile con select=* (web, relazione) o col solo activity (nativo).

ALTER TABLE public.fnz_coverage_items DROP COLUMN IF EXISTS revaluation_pct;

ALTER TABLE public.al_profile DROP COLUMN IF EXISTS birth_date;
ALTER TABLE public.al_profile DROP COLUMN IF EXISTS height_cm;
ALTER TABLE public.al_profile DROP COLUMN IF EXISTS sex;
