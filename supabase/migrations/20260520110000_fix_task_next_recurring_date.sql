-- Ridefinisce task_next_recurring_date per gestire recurring_days_of_week
-- e recurring_day_of_month come text[] (o integer[]).
-- La versione precedente usava jsonb_array_elements() su quelle colonne,
-- causando l'errore 42846 "cannot cast type text[] to jsonb" in task_complete.
-- unnest(col)::integer funziona sia su text[] che integer[].

CREATE OR REPLACE FUNCTION task_next_recurring_date(
  p_task  ts_tasks,
  p_base  date
)
RETURNS date
LANGUAGE plpgsql
AS $$
DECLARE
  v_freq     text    := p_task.recurring_frequency;
  v_interval integer := COALESCE(p_task.recurring_interval, 1);

  v_dow_arr  integer[];
  v_dom_arr  integer[];
  v_dates    text[];

  v_cur_dow  integer;
  v_sun_base date;
  v_test     date;
  v_day      integer;
  v_yr       integer;
  v_parts    text[];
  v_d        integer;
  v_m        integer;
  v_str      text;
  i          integer;
BEGIN

  -- ── daily ──────────────────────────────────────────────────────────────
  IF v_freq = 'daily' THEN
    RETURN p_base + (v_interval || ' days')::interval;

  -- ── weekly ─────────────────────────────────────────────────────────────
  ELSIF v_freq = 'weekly' THEN
    IF p_task.recurring_days_of_week IS NOT NULL
       AND array_length(p_task.recurring_days_of_week, 1) > 0
    THEN
      -- unnest() + ::integer è sicuro sia su text[] che su integer[]
      SELECT ARRAY(SELECT unnest(p_task.recurring_days_of_week)::integer ORDER BY 1)
        INTO v_dow_arr;
    ELSE
      v_dow_arr := ARRAY[extract(dow FROM p_task.start_date::date)::integer];
    END IF;

    v_cur_dow := extract(dow FROM p_base)::integer;

    -- Cerca nella settimana corrente (da domani in poi)
    FOR i IN 1..(7 - v_cur_dow) LOOP
      v_test := p_base + i;
      IF extract(dow FROM v_test)::integer = ANY(v_dow_arr) THEN
        RETURN v_test;
      END IF;
    END LOOP;

    -- Salta alla prossima domenica + (interval-1) settimane
    DECLARE
      v_days_to_sun integer := (7 - v_cur_dow) % 7;
    BEGIN
      IF v_days_to_sun = 0 THEN v_days_to_sun := 7; END IF;
      v_sun_base := p_base + v_days_to_sun + (v_interval - 1) * 7;
    END;

    FOR i IN 0..6 LOOP
      IF extract(dow FROM (v_sun_base + i))::integer = ANY(v_dow_arr) THEN
        RETURN v_sun_base + i;
      END IF;
    END LOOP;

    RETURN NULL;

  -- ── monthly ────────────────────────────────────────────────────────────
  ELSIF v_freq = 'monthly' THEN
    IF p_task.recurring_day_of_month IS NOT NULL
       AND array_length(p_task.recurring_day_of_month, 1) > 0
    THEN
      SELECT ARRAY(SELECT unnest(p_task.recurring_day_of_month)::integer ORDER BY 1)
        INTO v_dom_arr;
    ELSE
      v_dom_arr := ARRAY[extract(day FROM p_task.start_date::date)::integer];
    END IF;

    -- Cerca un giorno valido nel mese corrente (dopo oggi)
    FOREACH v_day IN ARRAY v_dom_arr LOOP
      IF v_day > extract(day FROM p_base)::integer THEN
        BEGIN
          RETURN make_date(
            extract(year  FROM p_base)::integer,
            extract(month FROM p_base)::integer,
            v_day
          );
        EXCEPTION WHEN OTHERS THEN NULL;
        END;
      END IF;
    END LOOP;

    -- Vai al mese target (interval mesi avanti)
    v_test := (date_trunc('month', p_base) + (v_interval || ' months')::interval)::date;
    FOREACH v_day IN ARRAY v_dom_arr LOOP
      BEGIN
        RETURN make_date(
          extract(year  FROM v_test)::integer,
          extract(month FROM v_test)::integer,
          v_day
        );
      EXCEPTION WHEN OTHERS THEN NULL;
      END;
    END LOOP;

    RETURN NULL;

  -- ── yearly ─────────────────────────────────────────────────────────────
  ELSIF v_freq = 'yearly' THEN
    v_yr := extract(year FROM p_base)::integer;

    IF p_task.recurring_dates IS NOT NULL
       AND array_length(p_task.recurring_dates, 1) > 0
    THEN
      v_dates := p_task.recurring_dates;           -- formato "DD-MM"
    ELSIF p_task.recurring_day_of_year IS NOT NULL
       AND p_task.recurring_month IS NOT NULL
    THEN
      v_dates := ARRAY[
        lpad(p_task.recurring_day_of_year::text, 2, '0') || '-' ||
        lpad(p_task.recurring_month::text,       2, '0')
      ];
    ELSE
      RETURN NULL;
    END IF;

    -- Cerca una data dopo oggi nell'anno corrente
    FOREACH v_str IN ARRAY v_dates LOOP
      v_parts := string_to_array(v_str, '-');
      v_d := v_parts[1]::integer;
      v_m := v_parts[2]::integer;
      BEGIN
        v_test := make_date(v_yr, v_m, v_d);
        IF v_test > p_base THEN
          RETURN v_test;
        END IF;
      EXCEPTION WHEN OTHERS THEN NULL;
      END;
    END LOOP;

    -- Tutte le date dell'anno corrente sono passate: vai all'anno + interval
    v_parts := string_to_array(v_dates[1], '-');
    v_d := v_parts[1]::integer;
    v_m := v_parts[2]::integer;
    BEGIN
      RETURN make_date(v_yr + v_interval, v_m, v_d);
    EXCEPTION WHEN OTHERS THEN
      RETURN NULL;
    END;

  END IF;

  RETURN NULL;
END;
$$;
