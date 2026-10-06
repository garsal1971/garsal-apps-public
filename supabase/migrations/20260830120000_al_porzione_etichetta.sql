-- Diario alimentare — come si chiama una porzione
-- ===========================================================================
-- `al_foods.default_grams` dice da sempre QUANTO è una porzione — un uovo 55 g,
-- una pizza 300 g, un cucchiaio d'olio 10 g — ma non come si chiama, e senza il
-- nome quel numero nella finestra della porzione si legge come un valore di
-- ripiego: lo si riscriveva a mano ogni volta, che è il lavoro che la colonna
-- esiste per togliere. Con l'etichetta si legge «1 uovo · 55 g», che è la cosa
-- che si voleva sapere.
--
-- ⚠️ DUE colonne e non una, perché l'italiano non fa il plurale a regola dove
-- serve di più: uovo → uova. Ricavarlo in JavaScript sbaglierebbe proprio le
-- parole più usate, e una parola sbagliata a schermo si legge come un difetto.
-- Nemmeno una colonna sola con dentro «uovo|uova»: sarebbe un formato da
-- interpretare dentro un campo di testo, cioè due verità in una casella.
--
-- ⚠️ Il plurale è FACOLTATIVO e ripiega sul singolare: per «porzione» ha senso,
-- per «bresaola» non serve, e obbligarlo vorrebbe dire riempire di rumore ogni
-- alimento che al plurale non ci va mai.
--
-- Nessuna delle due è NOT NULL: un alimento senza etichetta è la normalità —
-- l'app mostra «1 porzione», che è vero per qualunque cosa. ⚠️ Un'etichetta non
-- si inventa: chi la legge scritta se la crede, e sono le calorie della
-- giornata.
-- ---------------------------------------------------------------------------

ALTER TABLE al_foods ADD COLUMN IF NOT EXISTS portion_label        text;
ALTER TABLE al_foods ADD COLUMN IF NOT EXISTS portion_label_plural text;

COMMENT ON COLUMN al_foods.portion_label IS
  'Come si chiama una porzione al singolare (uovo, fetta, cucchiaio). NULL = si mostra «porzione».';
COMMENT ON COLUMN al_foods.portion_label_plural IS
  'Lo stesso al plurale (uova, fette, cucchiai). NULL = ripiega sul singolare.';

-- ── I nomi per le voci base ────────────────────────────────────────────────
-- Solo dove la porzione ha davvero un nome proprio: petto di pollo, insalata e
-- zucchine restano senza, perché «1 porzione» è già quel che sono e inventare
-- «1 filetto» direbbe una cosa che quei 150 g non garantiscono.
--
-- ⚠️ L'UPDATE è idempotente e prudente su tre fronti: gira solo sulle righe
-- `base` (le voci scritte a mano dall'utente non si toccano mai), solo dove
-- l'etichetta è ancora NULL (rilanciando la migration non si sovrascrive una
-- correzione fatta a mano) e per utente, perché al_foods non è monoutente.
-- Le voci base nascono da 20260826100000 e si riconoscono dal nome: è la stessa
-- chiave con cui sono state inserite.
UPDATE al_foods AS f
   SET portion_label        = v.sing,
       portion_label_plural = v.plur,
       updated_at           = now()
  FROM (VALUES
    ('Uovo di gallina, intero',        'uovo',       'uova'),
    ('Pane bianco',                    'fetta',      'fette'),
    ('Pane integrale',                 'fetta',      'fette'),
    ('Salmone fresco',                 'filetto',    'filetti'),
    ('Tonno al naturale, sgocciolato', 'scatoletta', 'scatolette'),
    ('Mozzarella vaccina',             'mozzarella', 'mozzarelle'),
    ('Latte parzialmente scremato',    'bicchiere',  'bicchieri'),
    ('Yogurt bianco intero',           'vasetto',    'vasetti'),
    ('Yogurt greco 0%',                'vasetto',    'vasetti'),
    ('Olio extravergine di oliva',     'cucchiaio',  'cucchiai'),
    ('Zucchero',                       'cucchiaino', 'cucchiaini'),
    ('Mela',                           'mela',       'mele'),
    ('Banana',                         'banana',     'banane'),
    ('Arancia',                        'arancia',    'arance'),
    ('Vino rosso',                     'bicchiere',  'bicchieri'),
    ('Birra chiara',                   'bottiglia',  'bottiglie'),
    ('Pizza margherita',               'pizza',      'pizze')
  ) AS v(nome, sing, plur)
 WHERE f.name = v.nome
   AND f.source = 'base'
   AND f.portion_label IS NULL;
