-- Spuntiamola — memoria delle stecche
-- Fino a qui una stecca finita non lasciava traccia: si cambiava il periodo
-- (o si premeva "cancella tutte le spunte") e il conto alla rovescia appena
-- portato a termine spariva insieme alle sue spunte. Questa tabella è
-- l'archivio: una riga per ogni stecca chiusa, con dentro la fotografia del
-- periodo (spunte e giornate chiave comprese), il livello di soddisfazione
-- e la nota scritta alla chiusura.
--
-- È una tabella nuova: nessuna colonna delle sp_* esistenti viene toccata.
CREATE TABLE IF NOT EXISTS sp_stecche (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,

  -- il traguardo com'era al momento della chiusura
  goal            text NOT NULL DEFAULT '',
  emoji           text NOT NULL DEFAULT '🎯',
  start_date      date NOT NULL,
  end_date        date NOT NULL,
  skip_weekend    boolean NOT NULL DEFAULT false,

  -- quanti giorni contava il periodo e quanti ne sono stati spuntati
  total_days      integer NOT NULL DEFAULT 0,
  done_days       integer NOT NULL DEFAULT 0,

  -- l'ultima spunta: il gesto che chiude la stecca (emoji pescata a caso)
  final_emoji     text NOT NULL DEFAULT '🏁',
  final_check_at  timestamptz,

  -- da 1 a 100, scelto con la barra alla chiusura
  satisfaction    integer NOT NULL DEFAULT 50,
  -- due parole su com'è andata
  note            text NOT NULL DEFAULT '',

  -- fotografia del periodo: { 'YYYY-MM-DD': '🎉' } e { 'YYYY-MM-DD': 'Partenza!' }
  checks          jsonb NOT NULL DEFAULT '{}'::jsonb,
  key_days        jsonb NOT NULL DEFAULT '{}'::jsonb,

  closed_at       timestamptz NOT NULL DEFAULT now(),
  created_at      timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT sp_stecche_period_ok       CHECK (end_date >= start_date),
  CONSTRAINT sp_stecche_satisfaction_ok CHECK (satisfaction BETWEEN 1 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_sp_stecche_user_closed
  ON sp_stecche(user_id, closed_at DESC);

ALTER TABLE sp_stecche ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "sp_stecche_own" ON sp_stecche;
CREATE POLICY "sp_stecche_own" ON sp_stecche FOR ALL USING (user_id = auth.uid());
