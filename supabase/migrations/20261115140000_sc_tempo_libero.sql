-- ⏳ Tempo libero di Ada (studio-ada.html v1.9.0).
-- • sc_impostazioni.tempo_libero: i minuti guadagnati oggi con «Finito prima»
--   ({giorno, minuti}); un giorno diverso da oggi vale zero, quindi si azzera da sé.
-- • sc_sessioni.tipo ammette 'libero': il tempo libero usato col cronometro.
ALTER TABLE public.sc_impostazioni ADD COLUMN IF NOT EXISTS tempo_libero jsonb;

ALTER TABLE public.sc_sessioni DROP CONSTRAINT IF EXISTS sc_sessioni_tipo_check;
ALTER TABLE public.sc_sessioni ADD CONSTRAINT sc_sessioni_tipo_check
  CHECK (tipo IN ('compito', 'argomento', 'pausa', 'libero'));
