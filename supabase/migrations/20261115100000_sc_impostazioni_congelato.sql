-- ❄️ Congela di studio-ada.html sincronizzato fra dispositivi (v1.8.0).
-- {tipo, rif, titolo, giorno}: cosa era acceso quando si è congelato.
-- NULL = niente di congelato. Vale solo per il giorno scritto dentro.
ALTER TABLE public.sc_impostazioni ADD COLUMN IF NOT EXISTS congelato jsonb;
