-- Terzo tipo di prestito: PERSONALE, calcolato come un MUTUO (rata, numero di rate, prima rata).
-- PRESTITO resta quello a rimborsi scritti a mano, e nelle pagine si chiama «Altro prestito».
-- I prestiti già in archivio non si toccano.
ALTER TABLE public.fnz_loans DROP CONSTRAINT IF EXISTS loans_type_check;
ALTER TABLE public.fnz_loans ADD CONSTRAINT loans_type_check
  CHECK (type = ANY (ARRAY['MUTUO'::text, 'PRESTITO'::text, 'PERSONALE'::text]));
