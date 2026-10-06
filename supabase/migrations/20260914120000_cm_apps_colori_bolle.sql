-- Le bolle devono avere lo stesso colore nella home web e in quella nativa (issue #19).
--
-- Il colore di una bolla viene da `cm_apps.color`. Dove quella casella è vuota entra un
-- RIPIEGO, e i due ripieghi non sono lo stesso:
--
--   | Dove   | Cosa fa quando `color` è vuoto                               |
--   |--------|--------------------------------------------------------------|
--   | Web    | `OLYMPIC[i % 5]` (index.html) — ruota PER POSIZIONE           |
--   | Nativo | `Palette.olimpici.first()` (HomeScreen.kt) — sempre il primo  |
--
-- ⚠️ Allinearli non basterebbe, ed è la ragione per cui si riempie la colonna invece di
-- toccare il codice: il ripiego del web è POSIZIONALE, e la posizione non è la stessa nelle
-- due home — il nativo mostra le sole app portate (`PortedApps`), il web tutte quelle attive.
-- Lo stesso servizio ha quindi un `i` diverso, e due colori diversi, senza che niente lo dica.
--
-- ⚠️ Peggio: sul web quella posizione non è nemmeno FERMA. `i` è l'indice nella risposta di
-- `cm_apps` (ordine `id.asc`) filtrata dalla modalità nascosta, quindi una bolla senza colore
-- CAMBIA COLORE accendendo la modalità nascosta, e lo ricambia il giorno che un'app viene
-- attivata o spenta. Il badge di `loadBadgeRows()` ha per giunta un terzo ripiego ancora
-- (`'#EE334E'`): tre modi di rispondere alla stessa domanda.
--
-- Con la colonna piena nessuno dei tre ripieghi morde più: web, nativo e badge leggono la
-- stessa riga. È la stessa scelta di `display_name` dei conti — il dato sta scritto dove
-- qualcuno l'ha deciso, non ricavato d'ufficio da chi lo mostra.
--
-- ⚠️ IL CODICE DELLA MODALITÀ NASCOSTA È FATTO DI COLORI. `checkSequence()` confronta i colori
-- delle bolle toccate con `cm_settings.hidden_mode_sequence`, **stringa per stringa**. Se una
-- delle bolle del codice era senza colore, il suo colore cambia qui e il codice non apre più.
-- Non è una cosa che si possa riconoscere da dentro il database: in archivio quella riga
-- aveva NULL, e nella sequenza sta scritto il colore che il web aveva *disegnato* — cioè
-- proprio il numero che questa migration esiste per togliere di mezzo. Il rimedio è
-- riscrivere `hidden_mode_sequence` coi colori nuovi, che le NOTICE qui sotto elencano.
--
-- ⚠️ Per la stessa ragione NON si tocca un colore che c'è già, nemmeno per «normalizzarlo»:
-- portare `#EE334E` a `#ee334e` non cambia una bolla di un pixel e spaccherebbe quel codice,
-- che confronta le stringhe e non i colori. Maiuscole e spazi del resto non fanno differenza
-- per nessuno dei due client — `coloreDaHex()` fa `trim()` e legge l'esadecimale come viene,
-- il web fa `.trim()` e lascia fare al CSS.
--
-- ⚠️ I colori restano DISTINTI, ed è un requisito e non un'estetica: due bolle dello stesso
-- colore rendono ambiguo quel codice (è la ragione scritta in `20260826120000_calorie_app_bolla`).
-- La tavolozza si scorre saltando ogni colore già in uso da un'altra riga.
--
-- Idempotente: rigirandola non trova più nessuna casella da riempire e non tocca niente.

DO $$
DECLARE
  -- Tavolozza di ripiego: colori distinti e distinguibili a occhio, perché il codice della
  -- modalità nascosta si compone guardando le bolle. I primi quattro sono i cerchi olimpici
  -- che il web già disegnava, così dove capita di riassegnarli la home resta com'era.
  tavolozza text[] := ARRAY[
    '#0081C8', '#FCB131', '#1A1A1A', '#00A651',
    '#E67E22', '#1ABC9C', '#34495E', '#C0392B',
    '#7F8C8D', '#8D6E63', '#2980B9', '#D35400',
    '#F39C12', '#145A32', '#6D214F', '#455A64'
  ];
  riga    record;
  cand    text;
  scelto  text;
  vecchio text;
  quante  int := 0;
BEGIN
  -- 1. Un colore che C'È ma che il nativo non sa leggere: `coloreDaHex()` in Theme.kt vuole
  --    SEI cifre esadecimali e torna `null` su tutto il resto, quindi `#abc` e `abc123` senza
  --    cancelletto passano di là e cadono nel ripiego di qua. Stesso dato, due colori: è lo
  --    stesso difetto dell'issue, scritto più in piccolo. Si riscrive lo STESSO colore in una
  --    forma che leggono tutt'e due — le maiuscole restano dove sono.
  UPDATE cm_apps
     SET color = '#' || btrim(color)
   WHERE color ~* '^\s*[0-9a-f]{6}\s*$';

  UPDATE cm_apps                                   -- #abc → #aabbcc, che è lo stesso colore
     SET color = '#'
       || repeat(substr(btrim(color), 2, 1), 2)
       || repeat(substr(btrim(color), 3, 1), 2)
       || repeat(substr(btrim(color), 4, 1), 2)
   WHERE color ~* '^\s*#[0-9a-f]{3}\s*$';

  -- 2. Quel che resta senza un colore leggibile si assegna dalla tavolozza. L'ordine è `id`,
  --    lo stesso con cui il web le chiede: così il risultato è lo stesso anche rigirando le
  --    migration da capo su un database nuovo.
  FOR riga IN
    SELECT id, title, color AS colore
      FROM cm_apps
     WHERE color IS NULL
        OR btrim(color) !~* '^#[0-9a-f]{6}$'
     ORDER BY id
  LOOP
    scelto := NULL;

    FOREACH cand IN ARRAY tavolozza LOOP
      IF NOT EXISTS (
        SELECT 1 FROM cm_apps
         WHERE id <> riga.id
           AND lower(btrim(coalesce(color, ''))) = lower(cand)
      ) THEN
        scelto := cand;
        EXIT;
      END IF;
    END LOOP;

    IF scelto IS NULL THEN
      -- Finiti i colori distinti. Non si fallisce e non si ripete un colore: la riga resta
      -- com'è e lo si dice — un colore doppio spaccherebbe il codice della modalità nascosta,
      -- e un deploy fermo su una bolla si porterebbe dietro tutto il resto del commit.
      RAISE NOTICE '⚠️  cm_apps «%»: tavolozza esaurita, colore lasciato com''è (%)',
                   riga.title, coalesce(riga.colore, 'NULL');
      CONTINUE;
    END IF;

    vecchio := coalesce(nullif(btrim(coalesce(riga.colore, '')), ''), 'NULL');
    UPDATE cm_apps SET color = scelto WHERE id = riga.id;
    quante := quante + 1;
    RAISE NOTICE 'cm_apps «%»: % → %', riga.title, vecchio, scelto;
  END LOOP;

  IF quante = 0 THEN
    RAISE NOTICE 'cm_apps: nessuna bolla senza colore, niente da fare.';
  ELSE
    RAISE NOTICE 'cm_apps: % bolle hanno ora un colore scritto in archivio. ⚠️  Se una di '
                 'loro faceva parte del codice della modalità nascosta, quel codice va '
                 'riscritto in cm_settings.hidden_mode_sequence coi colori qui sopra.', quante;
  END IF;
END $$;
