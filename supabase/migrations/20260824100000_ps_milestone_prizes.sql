-- Premi cibo e punti dei traguardi intermedi di «Ti pisasti?» — sincronizzati
-- fra weight-quest.html e l'app nativa (android-app/appsphere-native).
--
-- Prima di questa migration i due dati vivevano solo sul dispositivo:
-- `localStorage` sul web (`wq_prizes_<id>`, `wq_mpts_<id>`) e SharedPreferences
-- nel nativo (PesoPremi). Erano quindi due verità diverse sulla stessa
-- stellina: il premio grattato dal telefono non si vedeva sul PC, e i punti
-- sotto la stellina potevano essere diversi a seconda dell'app da cui la si
-- guardava. Ora il DB è la fonte di verità e il locale resta solo cache.
--
-- `objective_id` è **testo** e non una FK su `ps_objectives`: quella tabella
-- non sta in nessuna migration (nata a mano prima della cartella) e la sua
-- colonna `id` può essere bigint o uuid a seconda del progetto — la stessa
-- ragione per cui il nativo la legge come stringa.
-- ---------------------------------------------------------------------------
-- 1. ps_milestone_prizes — un premio per soglia raggiunta
--    `consumed_on` è **l'unica verità** sull'«ho usufruito»: una colonna
--    booleana accanto alla data sarebbe un secondo modo di dire la stessa
--    cosa, e le due divergono il giorno che una delle due si scrive da sola.
--    Usufruito = `consumed_on IS NOT NULL`.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ps_milestone_prizes (
  user_id      uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  objective_id text NOT NULL,
  -- La soglia in kg: la stellina «< 78 kg» è la riga threshold = 78.
  threshold    integer NOT NULL,
  -- L'id del premio pescato: 'torta_savoia' | 'cannolo' | 'pizza' |
  -- 'cioccolata' | 'biscotti' (PRIZES nel web, PREMI_CIBO nel nativo).
  prize_id     text NOT NULL,
  won_on       date NOT NULL DEFAULT CURRENT_DATE,
  -- Quando il premio è stato davvero mangiato. NULL = ancora da usufruire.
  consumed_on  date,
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, objective_id, threshold)
);
ALTER TABLE ps_milestone_prizes ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "ps_milestone_prizes_own" ON ps_milestone_prizes;
CREATE POLICY "ps_milestone_prizes_own" ON ps_milestone_prizes
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- 2. ps_milestone_points — «⭐ Punti Totali Traguardi Intermedi», una riga per
--    obiettivo. Il totale si distribuisce fra le soglie con la stessa formula
--    nelle due app (getMilestonePtsDistribution / PesoRegole.distribuzionePunti):
--    qui si archivia il totale, mai la distribuzione, che è calcolata.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ps_milestone_points (
  user_id      uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  objective_id text NOT NULL,
  total_points integer NOT NULL DEFAULT 0,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, objective_id)
);
ALTER TABLE ps_milestone_points ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "ps_milestone_points_own" ON ps_milestone_points;
CREATE POLICY "ps_milestone_points_own" ON ps_milestone_points
  FOR ALL USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
