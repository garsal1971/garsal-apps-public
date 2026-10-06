-- Coperture per esigenze future — il fabbisogno si conta FINO A UNA DATA
--
-- Una voce di fabbisogno non è più un numero solo: «costo della vita» e «contribuzione alla
-- pensione» sono flussi che durano finché non si smette di lavorare, quindi valgono di più se
-- si esce a 67 anni che se si esce cinque anni prima. La pagina calcola ora il fabbisogno su
-- tre orizzonti (accompagnamento = anticipata − 5 anni, anticipata, vecchiaia), e per farlo deve
-- sapere due cose in più su ogni voce.
--
-- ⚠️ Le due colonne sono NULLABLE, e NULL non è un valore di ripiego: significa «vale quello che
-- dice COVERAGE_ITEMS per questa voce». Un DEFAULT scritto qui congelerebbe nel database una
-- scelta che vive nella pagina, e cambiarla nella pagina non avrebbe più effetto sulle righe già
-- salvate — che è il modo più silenzioso di far divergere le due. Per togliere la rivalutazione
-- si scrive 0, che è una cosa diversa da «non l'ho deciso io».

ALTER TABLE fnz_coverage_items
  ADD COLUMN IF NOT EXISTS periodicity     text,
  ADD COLUMN IF NOT EXISTS revaluation_pct numeric(5,2);

ALTER TABLE fnz_coverage_items DROP CONSTRAINT IF EXISTS fnz_coverage_items_periodicity_check;
ALTER TABLE fnz_coverage_items
  ADD CONSTRAINT fnz_coverage_items_periodicity_check
  CHECK (periodicity IS NULL OR periodicity IN ('una_tantum', 'annuo'));

COMMENT ON COLUMN fnz_coverage_items.periodicity IS
  'Come si conta l''importo: ''una_tantum'' = un capitale, uguale su tutti e tre gli orizzonti; ''annuo'' = un flusso, sommato anno per anno fino alla data dello scenario. NULL = vale la periodicità scritta in COVERAGE_ITEMS dentro finanza.html.';
COMMENT ON COLUMN fnz_coverage_items.revaluation_pct IS
  'Rivalutazione annua composta di una voce ''annuo'', in percentuale (2 = +2% all''anno). 0 = nessuna rivalutazione; NULL = vale quella scritta in COVERAGE_ITEMS. Gli anni sono tutti futuri, quindi l''indice FOI (che è storico) qui non si può usare: il form ne mostra la media degli ultimi anni come spunto, e il tasso resta una scelta.';
