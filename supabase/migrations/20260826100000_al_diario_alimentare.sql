-- Diario alimentare (calorie.html) — quanto si mangia contro quanto si dovrebbe mangiare.
--
-- L'app non decide da sola quanto si deve dimagrire: quella decisione sta già in «Ti pisasti?»
-- (ps_objectives + le sue milestone), e qui si legge. Il target di calorie è la conseguenza di
-- tre cose — il metabolismo, il peso di oggi e quanto manca al peso finale nel tempo che resta —
-- quindi non è un numero da scrivere a mano da nessuna parte.
--
-- Quattro tabelle: chi sono (al_profile), cosa esiste (al_foods), cosa ho mangiato (al_log),
-- e cosa dovevo mangiare quel giorno (al_days).

-- ── Il profilo: quel che serve al metabolismo basale ────────────────
-- Una riga per utente, non una tabella di impostazioni chiave/valore: questi quattro campi
-- entrano tutti nella stessa formula, e come righe separate una mancante darebbe un basale
-- sbagliato invece di un basale assente.
--
-- ⚠️ La data di nascita e non l'età: un'età scritta a mano è giusta un anno solo e poi comincia
-- a mentire in silenzio. L'anno che passa non deve richiedere che qualcuno se ne ricordi.
CREATE TABLE IF NOT EXISTS al_profile (
  user_id     uuid PRIMARY KEY DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  birth_date  date,
  height_cm   numeric(5,1),
  sex         text CHECK (sex IN ('M', 'F')),
  -- Fattore LAF: 1.2 sedentario … 1.9 molto intenso. Numero e non etichetta perché è quello che
  -- moltiplica il basale; le etichette stanno nella pagina, dove si leggono.
  -- ⚠️ Tre decimali, non due: 1.375 (il livello «leggero») in numeric(4,2) diventerebbe 1.38 —
  -- un valore che non è in nessuna voce della tendina, che si riaprirebbe quindi sulla voce
  -- sbagliata dopo ogni salvataggio.
  activity    numeric(4,3) NOT NULL DEFAULT 1.375 CHECK (activity BETWEEN 1.0 AND 2.5),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

-- ── Gli alimenti conosciuti ─────────────────────────────────────────
-- Tre provenienze in una tabella sola (`source`): 'base' sono le voci generiche di partenza
-- (pasta, pane, pollo), 'off' i prodotti confezionati letti da Open Food Facts col codice a
-- barre, 'manuale' quelli scritti a mano. È lo stesso alimento visto da tre parti: separarli in
-- tre tabelle vorrebbe dire tre ricerche e tre join per rispondere a «quante calorie ha».
--
-- Tutti i valori sono per 100 g di prodotto, che è come li scrivono sia l'etichetta europea sia
-- le tabelle di composizione: così un numero è già la sua percentuale in peso e non c'è nessuna
-- conversione da ricordarsi.
CREATE TABLE IF NOT EXISTS al_foods (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  name          text NOT NULL,
  brand         text,
  barcode       text,
  source        text NOT NULL DEFAULT 'manuale' CHECK (source IN ('base', 'off', 'manuale')),
  kcal_100g     numeric(7,2) NOT NULL,
  proteins_100g numeric(6,2),
  fat_100g      numeric(6,2),
  sat_fat_100g  numeric(6,2),
  carbs_100g    numeric(6,2),
  sugars_100g   numeric(6,2),
  fiber_100g    numeric(6,2),
  salt_100g     numeric(6,2),
  -- La porzione che si usa di solito: un vasetto di yogurt è 125 g e riscriverlo ogni volta è
  -- il modo migliore per smettere di segnarlo.
  default_grams numeric(7,1),
  favorite      boolean NOT NULL DEFAULT false,
  times_used    integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_al_foods_user_name ON al_foods(user_id, lower(name));
-- Lo stesso prodotto non entra due volte scansionandolo due volte. L'indice non è parziale di
-- proposito: ON CONFLICT non si abbina a un indice parziale, e le righe senza codice a barre
-- restano comunque distinte fra loro (NULL non è uguale a NULL).
CREATE UNIQUE INDEX IF NOT EXISTS uq_al_foods_user_barcode ON al_foods(user_id, barcode);

-- ── Il diario ───────────────────────────────────────────────────────
-- ⚠️ La riga porta i valori nutrizionali CONGELATI, non solo il rimando all'alimento: correggere
-- domani le calorie di un alimento non deve riscrivere quel che si è mangiato il mese scorso. È
-- la stessa scelta di ps_weight_tracking.target_weight, per la stessa ragione — un giudizio già
-- dato su una giornata passata non si tocca. `food_id` resta come rimando (per la statistica e
-- per riproporre l'alimento), ma non è da lì che si leggono le calorie di una riga.
CREATE TABLE IF NOT EXISTS al_log (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  day           date NOT NULL,
  meal          text NOT NULL DEFAULT 'pranzo'
                CHECK (meal IN ('colazione', 'spuntino_mattina', 'pranzo', 'spuntino_pomeriggio', 'cena', 'fuori_pasto')),
  food_id       uuid REFERENCES al_foods(id) ON DELETE SET NULL,
  name          text NOT NULL,
  brand         text,
  grams         numeric(7,1) NOT NULL CHECK (grams > 0),
  kcal_100g     numeric(7,2) NOT NULL,
  proteins_100g numeric(6,2),
  fat_100g      numeric(6,2),
  sat_fat_100g  numeric(6,2),
  carbs_100g    numeric(6,2),
  sugars_100g   numeric(6,2),
  fiber_100g    numeric(6,2),
  salt_100g     numeric(6,2),
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_al_log_user_day ON al_log(user_id, day DESC);

-- ── Il target del giorno ────────────────────────────────────────────
-- Una riga per giornata, scritta la prima volta che si segna qualcosa e mai riscritta da sé.
-- Senza, spostare un traguardo in «Ti pisasti?» cambierebbe il verdetto su tutti i giorni già
-- passati — e una giornata vinta diventerebbe persa senza che nessuno abbia mangiato niente.
--
-- Gli ingredienti (peso, basale, consumo, deficit) stanno accanto al risultato perché la domanda
-- che si fa guardando un giorno storto è «da dove veniva quel numero», e ricalcolarla non si può:
-- il peso di quel giorno oggi è un altro.
CREATE TABLE IF NOT EXISTS al_days (
  user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
  day           date NOT NULL,
  kcal_target   numeric(7,1) NOT NULL,
  weight_kg     numeric(6,2),
  bmr           numeric(7,1),
  tdee          numeric(7,1),
  deficit_kcal  numeric(7,1),
  objective_id  uuid,
  note          text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, day)
);

-- ── RLS: ognuno vede solo le proprie righe ──────────────────────────
ALTER TABLE al_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE al_foods   ENABLE ROW LEVEL SECURITY;
ALTER TABLE al_log     ENABLE ROW LEVEL SECURITY;
ALTER TABLE al_days    ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS al_profile_own ON al_profile;
CREATE POLICY al_profile_own ON al_profile FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS al_foods_own ON al_foods;
CREATE POLICY al_foods_own ON al_foods FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS al_log_own ON al_log;
CREATE POLICY al_log_own ON al_log FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS al_days_own ON al_days;
CREATE POLICY al_days_own ON al_days FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

COMMENT ON TABLE al_log IS
  'Diario alimentare. I valori per 100 g sono congelati sulla riga: correggere un alimento non riscrive il passato.';
COMMENT ON TABLE al_days IS
  'Il target di calorie di una giornata, congelato. Spostare i traguardi di Ti pisasti non tocca i giorni già passati.';

-- ── Alimenti generici di partenza ───────────────────────────────────
-- Una pagina che si apre senza nessun alimento costringe a scrivere a mano il piatto di pasta
-- prima ancora di poter segnare qualcosa, ed è lì che si smette. I prodotti confezionati arrivano
-- da Open Food Facts col codice a barre; questi sono l'altra metà — quel che un codice a barre non
-- ce l'ha: la pasta pesata dal pacco, il petto di pollo, la mela.
--
-- ⚠️ Valori INDICATIVI per 100 g di parte edibile, arrotondati dalle tabelle di composizione
-- pubbliche. La fonte autorevole per l'Italia è il CREA (alimentinutrizione.it): dove un numero
-- conta davvero va confrontato con la tabella e corretto nella pagina, che è il motivo per cui
-- queste righe sono modificabili come tutte le altre e non una tabella di sistema.
--
-- Si creano solo per Salvatore e solo se non ce n'è già nessuno, così rilanciare la migration su
-- un database che le ha già non duplica niente.
INSERT INTO al_foods (user_id, name, source, kcal_100g, proteins_100g, fat_100g, sat_fat_100g, carbs_100g, sugars_100g, fiber_100g, salt_100g, default_grams)
SELECT public.garsal_user_id(), v.name, 'base', v.kcal, v.p, v.g, v.gs, v.c, v.z, v.f, v.s, v.porz
FROM (VALUES
  ('Pasta di semola, cruda',        353, 10.9,  1.4,  0.3, 79.1,  3.2,  2.7, 0.01,  80),
  ('Pasta di semola, cotta',        158,  4.9,  0.6,  0.1, 35.4,  1.4,  1.2, 0.01, 250),
  ('Riso bianco, crudo',            332,  6.7,  0.4,  0.1, 80.4,  0.2,  1.0, 0.01,  80),
  ('Riso bianco, cotto',            121,  2.5,  0.1,  0.0, 29.3,  0.1,  0.4, 0.01, 220),
  ('Pane bianco',                   275,  8.1,  0.5,  0.1, 63.5,  2.0,  3.2, 1.30,  50),
  ('Pane integrale',                224,  7.5,  1.3,  0.3, 48.5,  2.0,  6.5, 1.20,  50),
  ('Fette biscottate',              410, 11.3,  6.0,  1.2, 78.0,  6.0,  3.5, 1.00,  20),
  ('Patate',                         85,  2.1,  1.0,  0.2, 17.9,  0.4,  1.6, 0.01, 200),
  ('Petto di pollo',                100, 23.3,  0.8,  0.2,  0.0,  0.0,  0.0, 0.15, 150),
  ('Fesa di tacchino',              107, 24.0,  1.2,  0.4,  0.0,  0.0,  0.0, 0.15, 150),
  ('Manzo magro',                   130, 21.3,  5.0,  2.0,  0.0,  0.0,  0.0, 0.15, 150),
  ('Uovo di gallina, intero',       128, 12.4,  8.7,  2.6,  0.0,  0.0,  0.0, 0.35,  55),
  ('Tonno al naturale, sgocciolato',103, 25.2,  0.3,  0.1,  0.0,  0.0,  0.0, 0.90,  80),
  ('Merluzzo',                       71, 17.0,  0.3,  0.1,  0.0,  0.0,  0.0, 0.20, 150),
  ('Salmone fresco',                185, 18.4, 12.0,  2.5,  0.0,  0.0,  0.0, 0.15, 120),
  ('Gamberi',                        71, 13.6,  0.6,  0.2,  2.0,  0.0,  0.0, 0.40, 120),
  ('Prosciutto crudo, sgrassato',   224, 26.0, 13.9,  4.8,  0.0,  0.0,  0.0, 5.00,  50),
  ('Prosciutto cotto',              215, 19.8, 14.7,  5.2,  0.8,  0.8,  0.0, 2.20,  50),
  ('Bresaola',                      151, 33.1,  2.0,  0.8,  0.4,  0.4,  0.0, 4.00,  50),
  ('Mozzarella vaccina',            253, 18.7, 19.5, 11.5,  0.7,  0.7,  0.0, 0.60, 125),
  ('Parmigiano',                    387, 33.5, 28.1, 18.0,  0.0,  0.0,  0.0, 1.60,  20),
  ('Ricotta vaccina',               146,  8.8, 10.9,  6.5,  3.5,  3.5,  0.0, 0.30, 100),
  ('Latte parzialmente scremato',    46,  3.3,  1.5,  1.0,  5.0,  5.0,  0.0, 0.10, 200),
  ('Yogurt bianco intero',           66,  3.8,  3.9,  2.4,  4.3,  4.3,  0.0, 0.10, 125),
  ('Yogurt greco 0%',                57, 10.0,  0.4,  0.2,  3.6,  3.6,  0.0, 0.10, 150),
  ('Olio extravergine di oliva',    899,  0.0, 99.9, 14.5,  0.0,  0.0,  0.0, 0.00,  10),
  ('Burro',                         758,  0.8, 83.4, 51.0,  1.1,  1.1,  0.0, 0.02,  10),
  ('Mela',                           45,  0.3,  0.1,  0.0, 11.0, 10.5,  2.0, 0.00, 150),
  ('Banana',                         65,  1.2,  0.3,  0.1, 15.4, 12.8,  1.8, 0.00, 120),
  ('Arancia',                        34,  0.7,  0.2,  0.0,  7.8,  7.8,  1.6, 0.00, 150),
  ('Pomodori',                       19,  1.0,  0.2,  0.0,  3.5,  3.5,  1.0, 0.01, 150),
  ('Insalata',                       19,  1.8,  0.4,  0.1,  2.2,  2.2,  1.5, 0.01,  80),
  ('Zucchine',                       11,  1.3,  0.1,  0.0,  1.4,  1.4,  1.2, 0.01, 200),
  ('Melanzane',                      18,  1.1,  0.4,  0.1,  2.6,  2.6,  2.6, 0.01, 200),
  ('Fagioli borlotti, secchi',      291, 20.2,  2.0,  0.4, 47.5,  2.7, 17.3, 0.02,  60),
  ('Ceci in scatola, sgocciolati',  120,  7.0,  2.4,  0.3, 16.0,  0.8,  6.0, 0.50, 150),
  ('Noci',                          654, 14.7, 65.2,  6.1,  5.1,  2.6,  6.7, 0.01,  30),
  ('Mandorle',                      603, 22.0, 55.3,  4.2,  4.6,  3.7, 12.7, 0.01,  30),
  ('Vino rosso',                     76,  0.2,  0.0,  0.0,  0.3,  0.3,  0.0, 0.01, 125),
  ('Birra chiara',                   34,  0.2,  0.0,  0.0,  3.5,  0.1,  0.0, 0.01, 330),
  ('Zucchero',                      392,  0.0,  0.0,  0.0,100.0,100.0,  0.0, 0.00,   5),
  ('Biscotti secchi',               416,  6.6, 13.8,  6.0, 68.0, 21.0,  2.5, 0.50,  30),
  ('Cioccolato fondente',           515,  6.0, 30.0, 18.0, 55.0, 48.0,  8.0, 0.02,  25),
  ('Pizza margherita',              271,  5.6,  6.6,  2.0, 47.0,  2.0,  2.0, 1.20, 300)
) AS v(name, kcal, p, g, gs, c, z, f, s, porz)
WHERE public.garsal_user_id() IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM al_foods WHERE user_id = public.garsal_user_id());
