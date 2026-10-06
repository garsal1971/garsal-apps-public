-- Calorie (calorie.html) — la bolla nel launcher AppSphere.
--
-- ⚠️ Il numero di questa bolla NON è un punteggio: è una STRISCIA DI GIORNI, cioè un conteggio.
-- Va quindi aggiunto a `APP_SENZA_PUNTI` in index.html e a `AppSenzaPunti` in PortedApps.kt, o
-- i giorni si sommerebbero ai punti che pagano i premi — e un giorno sforato *abbasserebbe* il
-- saldo spendibile, cioè un premio che va e viene da sé. Non si scrive sotto il nome della
-- bolla: continua però a dimensionarla, che è il punto — la bolla cresce finché il diario regge
-- e si sgonfia il giorno che si sfora.
--
-- Perché la striscia e non «le calorie che restano oggi»: `sizeOf()` nel launcher normalizza
-- sul punteggio più alto fra tutte le app, e un numero dell'ordine delle migliaia di kcal
-- schiaccerebbe ogni altra bolla al minimo di 6 cm². Una striscia sta sulle decine, come i
-- giorni di Spuntiamola.
--
-- La striscia si ferma a IERI: oggi non è finito, e alle nove del mattino si è dentro il target
-- per forza — contarlo direbbe che è andata bene una giornata che deve ancora andare.
-- Un giorno senza righe segnate la interrompe come un giorno sforato: il diario non tenuto non
-- è una giornata riuscita.

INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT 'Calorie',
       'Diario alimentare e calorie della giornata',
       $q$
WITH giorni AS (
  SELECT g.d::date AS day
    FROM generate_series((CURRENT_DATE - 365)::timestamp,
                         (CURRENT_DATE - 1)::timestamp,
                         interval '1 day') AS g(d)
),
mangiato AS (
  SELECT day, SUM(kcal_100g * grams / 100) AS kcal
    FROM al_log
   WHERE user_id = auth.uid()
   GROUP BY day
),
esito AS (
  SELECT gi.day,
         (m.kcal IS NOT NULL AND d.kcal_target IS NOT NULL AND m.kcal <= d.kcal_target) AS ok
    FROM giorni gi
    LEFT JOIN mangiato m ON m.day = gi.day
    LEFT JOIN al_days  d ON d.user_id = auth.uid() AND d.day = gi.day
)
SELECT COALESCE((
  SELECT COUNT(*)
    FROM esito
   WHERE ok
     AND day > COALESCE((SELECT MAX(day) FROM esito WHERE NOT ok), DATE '1900-01-01')
), 0)::int
$q$,
       -- Ambra: #16a34a è già di Casa Terrasini, e due bolle dello stesso colore renderebbero
       -- ambiguo il codice a colori della modalità nascosta.
       '#d97706', true, 'calorie.html', false
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE html_file = 'calorie.html');
