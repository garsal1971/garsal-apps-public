-- ============================================================================
-- Auto-formazione — esercizi corretti da un'IA, e un'azione di Obiettivi chiusa.
--
--   • af_exercise_types  un tipo di esercizio (oggi «Esercizi inglese mail»):
--                        il prompt di correzione, salvato, e l'azione di
--                        Obiettivi che la ✅ Conferma completa
--   • af_exercises       un esercizio confermato: testo, prompt usato,
--                        correzione, voto 1-10, motore e modello
--
-- ⚠️ La correzione la chiede la Edge Function `af-ai`, che NON scrive niente:
-- la riga nasce solo alla ✅ Conferma, dalla pagina, col JWT dell'utente.
--
-- ⚠️ `prompt` si COPIA sulla riga dell'esercizio: il prompt del tipo si
-- cambia, e un voto dato con un metro diverso deve dire con quale.
--
-- ⚠️ `ob_action_id` è ON DELETE SET NULL: cancellare un'azione in Obiettivi
-- non porta via gli esercizi né il tipo.
--
-- Nessuna bolla in AppSphere, di proposito: la pagina ha un login suo.
-- ============================================================================

CREATE TABLE IF NOT EXISTS af_exercise_types (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  key           text NOT NULL CHECK (length(trim(key)) > 0),
  name          text NOT NULL CHECK (length(trim(name)) > 0),
  prompt        text NOT NULL DEFAULT '',
  ob_action_id  uuid REFERENCES ob_actions(id) ON DELETE SET NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, key)
);
ALTER TABLE af_exercise_types ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS af_exercise_types_owner ON af_exercise_types;
CREATE POLICY af_exercise_types_owner ON af_exercise_types
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

CREATE TABLE IF NOT EXISTS af_exercises (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  type_id       uuid NOT NULL REFERENCES af_exercise_types(id) ON DELETE CASCADE,
  text          text NOT NULL CHECK (length(trim(text)) > 0),
  prompt        text NOT NULL,
  answer        text NOT NULL,
  -- NULL = l'IA non l'ha dato in modo leggibile. Oggi la pagina blocca la
  -- conferma senza voto, ma «non lo so» non è un numero e non si scrive come tale.
  voto          integer CHECK (voto BETWEEN 1 AND 10),
  provider      text,            -- gemini | qwen | groq | claude
  model         text,            -- il modello che ha risposto davvero
  ob_action_id  uuid REFERENCES ob_actions(id) ON DELETE SET NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS af_exercises_idx ON af_exercises (user_id, type_id, created_at DESC);
ALTER TABLE af_exercises ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS af_exercises_owner ON af_exercises;
CREATE POLICY af_exercises_owner ON af_exercises
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
