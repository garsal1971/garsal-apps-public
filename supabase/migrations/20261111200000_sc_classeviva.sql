-- ClasseViva di Ada: voti, agenda e argomenti delle lezioni letti dal registro
-- elettronico (Spaggiari) e salvati qui da da-classeviva.html.
--
-- ⚠️ LA LETTURA LA FA IL TELEFONO, NON IL SERVER. Spaggiari sta dietro Akamai,
-- che risponde 403 «Access Denied» a ogni richiesta dai server cloud (provato
-- l'11 novembre 2026 da pg_net e da una Edge Function: anche la pagina di
-- login). Il bottone 🔄 Sincronizza della pagina chiede all'APK AppSfera Web
-- (AndroidBridge.classevivaSincronizza) di leggere le API dell'app ClasseViva,
-- e la pagina scrive qui il risultato. Dal PC si legge l'ultima copia.
--
--   • sc_classeviva    una riga per tipo ('voti' | 'agenda' | 'lezioni'):
--                      `dati` è l'elenco così come lo dà ClasseViva, `letto_il`
--                      quando il telefono l'ha letto
--   • sc_verifiche.cv_id  l'id dell'evento dell'agenda da cui la verifica è
--                      stata aggiunta: la stessa verifica non entra due volte
--
-- ⚠️ Le credenziali di ClasseViva NON stanno qui: restano cifrate nel Keystore
-- del telefono. ⚠️ Nessun dato in questa migration (la repo è pubblica).

CREATE TABLE IF NOT EXISTS sc_classeviva (
  user_id   uuid NOT NULL DEFAULT public.garsal_user_id() REFERENCES auth.users(id) ON DELETE CASCADE,
  tipo      text NOT NULL CHECK (tipo IN ('voti', 'agenda', 'lezioni')),
  dati      jsonb NOT NULL DEFAULT '[]'::jsonb,
  letto_il  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, tipo)
);

ALTER TABLE sc_classeviva ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON sc_classeviva TO authenticated;
DROP POLICY IF EXISTS sc_classeviva_owner ON sc_classeviva;
CREATE POLICY sc_classeviva_owner ON sc_classeviva FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
DROP POLICY IF EXISTS sc_classeviva_ospite ON sc_classeviva;
CREATE POLICY sc_classeviva_ospite ON sc_classeviva FOR ALL
  USING (user_id = public.garsal_user_id() AND public.sc_ospite())
  WITH CHECK (user_id = public.garsal_user_id() AND public.sc_ospite());

ALTER TABLE sc_verifiche ADD COLUMN IF NOT EXISTS cv_id text;
CREATE UNIQUE INDEX IF NOT EXISTS sc_verifiche_cv_id_uq ON sc_verifiche (user_id, cv_id) WHERE cv_id IS NOT NULL;

-- La bolla in AppSphere (di Salvatore; Ada ha la sua in index.html). Il numero
-- è un conteggio — i voti dell'ultima lettura — e non fa punti.
INSERT INTO cm_apps (title, html_file, description, color, active, riservato, conta_punti, score_query)
SELECT 'ClasseViva', 'da-classeviva.html', 'Voti, agenda e lezioni di Ada dal registro', '#D4145A', true, false, false,
       'SELECT COALESCE((SELECT jsonb_array_length(dati) FROM sc_classeviva WHERE user_id = auth.uid() AND tipo = ''voti''), 0)::int'
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE html_file = 'da-classeviva.html');
