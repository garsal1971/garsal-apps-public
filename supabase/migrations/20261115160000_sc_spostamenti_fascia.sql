-- 🧱 Spostare un'attività in un'altra fascia (scuola-ada.js v1.9.0).
-- sc_spostamenti.fascia = la fascia scelta sul giorno `a`, scritta come la chiave
-- dei blocchi del piano ('15:00-17:00'); NULL = la fascia la decide il piano.
-- Uno spostamento nello stesso giorno è una riga «rimandato» con a = da.
ALTER TABLE public.sc_spostamenti ADD COLUMN IF NOT EXISTS fascia text;
