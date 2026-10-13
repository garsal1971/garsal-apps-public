-- cm_categories: un nome solo per categoria (fusione dei doppioni + indice unico)
--
-- La tabella non ha mai avuto un vincolo sul nome, e le tre strade che creano una
-- categoria (habit-tracker.html, decisions.html, Abituati nativo) fanno un insert
-- senza guardare se il nome c'è già: così sono nate due «Salute» (❤️ e 💪).
--
-- 1. FUSIONE: per ogni gruppo di categorie con lo stesso nome (senza badare a
--    maiuscole e spazi, e per utente se la colonna user_id c'è) ne resta UNA.
--    Vince quella usata di più; a parità, la prima per id. Eccezione dichiarata:
--    fra le «Salute» vince quella con ❤️, come chiesto.
--    I riferimenti delle perdenti passano alla vincente, poi le perdenti si cancellano:
--      - array  ts_tasks.categories, dc_decisions.categories, ob_actions.categories
--      - colonne hb_habits.category_id, hb_archived_stacks.category_id
--      - mm_card_categories (chiave primaria (card_id, category_id): prima si tolgono
--        le righe che diventerebbero doppie)
--      - ogni altra FOREIGN KEY a colonna singola verso cm_categories(id)
--    Tabelle e colonne si cercano in information_schema: metà di queste non stanno in
--    nessuna migration, e una che manca si salta invece di far fallire il deploy.
--    Il tipo delle colonne `categories` (uuid[], text[] o jsonb) si legge, non si indovina.
--
-- 2. INDICE UNICO su lower(btrim(name)), con user_id davanti se la colonna esiste.

DO $$
DECLARE
  v_has_user  boolean;
  v_gruppo    text;
  r           record;
  v_tipo      text;
  v_elem      text;
  v_usi       text;
  v_fusi      int := 0;
  fk          record;
BEGIN
  IF to_regclass('public.cm_categories') IS NULL THEN
    RAISE NOTICE 'cm_categories non esiste: niente da fare';
    RETURN;
  END IF;

  SELECT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='cm_categories' AND column_name='user_id')
    INTO v_has_user;
  v_gruppo := CASE WHEN v_has_user THEN 'coalesce(user_id::text, '''') || ''|'' || ' ELSE '' END
              || 'lower(btrim(name))';

  -- Conteggio degli usi di ogni categoria, costruito sulle sole tabelle che esistono
  v_usi := '0';
  FOR r IN SELECT * FROM (VALUES ('ts_tasks','categories'), ('dc_decisions','categories'),
                                  ('ob_actions','categories'), ('hb_habits','category_id'),
                                  ('hb_archived_stacks','category_id'),
                                  ('mm_card_categories','category_id')) AS x(t, c)
  LOOP
    SELECT data_type INTO v_tipo FROM information_schema.columns
     WHERE table_schema='public' AND table_name=r.t AND column_name=r.c;
    CONTINUE WHEN v_tipo IS NULL;
    IF v_tipo = 'ARRAY' THEN
      v_usi := v_usi || format(' + (SELECT count(*) FROM %I x WHERE k.id::text = ANY(x.%I::text[]))', r.t, r.c);
    ELSIF v_tipo = 'jsonb' THEN
      v_usi := v_usi || format(' + (SELECT count(*) FROM %I x WHERE x.%I @> jsonb_build_array(k.id::text))', r.t, r.c);
    ELSE
      v_usi := v_usi || format(' + (SELECT count(*) FROM %I x WHERE x.%I::text = k.id::text)', r.t, r.c);
    END IF;
  END LOOP;

  DROP TABLE IF EXISTS _cm_cat_fusione;
  EXECUTE format($q$
    CREATE TEMP TABLE _cm_cat_fusione AS
    WITH k AS (SELECT id, name, icon, %s AS gruppo FROM cm_categories WHERE name IS NOT NULL),
         u AS (SELECT k.*, %s AS usi FROM k),
         o AS (SELECT u.*, row_number() OVER (
                 PARTITION BY gruppo
                 ORDER BY (lower(btrim(name)) = 'salute' AND icon = '❤️') DESC, usi DESC, id::text
               ) AS rn FROM u)
    SELECT p.id AS perdente, v.id AS vincente, p.name, p.icon AS icona_perdente,
           v.icon AS icona_vincente, p.usi
      FROM o p JOIN o v ON v.gruppo = p.gruppo AND v.rn = 1
     WHERE p.rn > 1
  $q$, v_gruppo, v_usi);

  FOR r IN SELECT * FROM _cm_cat_fusione LOOP
    RAISE NOTICE 'Fondo «%» % (% usi) in %', r.name, r.icona_perdente, r.usi, r.icona_vincente;
    v_fusi := v_fusi + 1;
  END LOOP;
  IF v_fusi = 0 THEN
    RAISE NOTICE 'Nessun doppione in cm_categories';
  END IF;

  -- Array di id: si sostituisce la perdente con la vincente, togliendo il doppione
  -- che nascerebbe se l'elemento portava già tutt'e due
  FOR r IN SELECT * FROM (VALUES ('ts_tasks'), ('dc_decisions'), ('ob_actions')) AS x(t) LOOP
    SELECT data_type, udt_name INTO v_tipo, v_elem FROM information_schema.columns
     WHERE table_schema='public' AND table_name=r.t AND column_name='categories';
    CONTINUE WHEN v_tipo IS NULL OR v_fusi = 0;
    IF v_tipo = 'ARRAY' THEN
      v_elem := ltrim(v_elem, '_');  -- _uuid -> uuid, _text -> text
      EXECUTE format($q$
        UPDATE %1$I x SET categories = (
          SELECT array_agg(DISTINCT coalesce(f.vincente::text, e)::%2$s)
            FROM unnest(x.categories::text[]) e
            LEFT JOIN _cm_cat_fusione f ON f.perdente::text = e)
         WHERE x.categories::text[] && (SELECT array_agg(perdente::text) FROM _cm_cat_fusione)
      $q$, r.t, v_elem);
    ELSIF v_tipo = 'jsonb' THEN
      EXECUTE format($q$
        UPDATE %1$I x SET categories = (
          SELECT coalesce(jsonb_agg(DISTINCT coalesce(f.vincente::text, e)), '[]'::jsonb)
            FROM jsonb_array_elements_text(x.categories) e
            LEFT JOIN _cm_cat_fusione f ON f.perdente::text = e)
         WHERE jsonb_typeof(x.categories) = 'array'
           AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(x.categories) e
                        JOIN _cm_cat_fusione f ON f.perdente::text = e)
      $q$, r.t);
    ELSE
      RAISE NOTICE '%.categories ha tipo %: non lo tocco', r.t, v_tipo;
    END IF;
  END LOOP;

  -- mm_card_categories: chiave (card_id, category_id), prima via le righe che diventerebbero doppie
  IF to_regclass('public.mm_card_categories') IS NOT NULL AND v_fusi > 0 THEN
    DELETE FROM mm_card_categories m USING _cm_cat_fusione f
     WHERE m.category_id = f.perdente
       AND EXISTS (SELECT 1 FROM mm_card_categories m2
                    WHERE m2.card_id = m.card_id AND m2.category_id = f.vincente);
    UPDATE mm_card_categories m SET category_id = f.vincente
      FROM _cm_cat_fusione f WHERE m.category_id = f.perdente;
  END IF;

  -- Colonne singole note (anche se non hanno una FOREIGN KEY)
  FOR r IN SELECT * FROM (VALUES ('hb_habits'), ('hb_archived_stacks')) AS x(t) LOOP
    CONTINUE WHEN v_fusi = 0 OR NOT EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema='public' AND table_name=r.t AND column_name='category_id');
    EXECUTE format('UPDATE %I x SET category_id = f.vincente FROM _cm_cat_fusione f
                     WHERE x.category_id::text = f.perdente::text', r.t);
  END LOOP;

  -- Ogni altra FOREIGN KEY a colonna singola verso cm_categories
  FOR fk IN
    SELECT c.conrelid::regclass AS tabella, a.attname AS colonna
      FROM pg_constraint c
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
     WHERE c.contype = 'f' AND c.confrelid = 'public.cm_categories'::regclass
       AND array_length(c.conkey, 1) = 1
       AND c.conrelid::regclass::text NOT IN ('mm_card_categories', 'hb_habits', 'hb_archived_stacks')
  LOOP
    CONTINUE WHEN v_fusi = 0;
    RAISE NOTICE 'Sposto anche %.%', fk.tabella, fk.colonna;
    EXECUTE format('UPDATE %s x SET %I = f.vincente FROM _cm_cat_fusione f WHERE x.%I = f.perdente',
                   fk.tabella, fk.colonna, fk.colonna);
  END LOOP;

  DELETE FROM cm_categories c USING _cm_cat_fusione f WHERE c.id = f.perdente;
  DROP TABLE _cm_cat_fusione;

  -- L'indice unico
  IF v_has_user THEN
    CREATE UNIQUE INDEX IF NOT EXISTS uq_cm_categories_nome
      ON cm_categories (user_id, lower(btrim(name)));
  ELSE
    CREATE UNIQUE INDEX IF NOT EXISTS uq_cm_categories_nome
      ON cm_categories (lower(btrim(name)));
  END IF;
  RAISE NOTICE 'Indice uq_cm_categories_nome creato (per utente: %)', v_has_user;
END $$;
