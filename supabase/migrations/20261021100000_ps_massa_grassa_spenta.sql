-- La massa grassa esce da «Peso e Calorie» (web v4.16.0): la bilancia a impedenza
-- la misura troppo male per farci punti. Gli obiettivi che contavano sulla massa
-- grassa tornano a contare sul peso totale.
--
-- ⚠️ Le colonne (body_fat_pct, use_fat_mass, fat_pct, ema_fat_*) RESTANO: l'APK
-- nativa le legge e le scrive ancora, e toglierle farebbe fallire i suoi salvataggi.
-- Si tolgono con una migration a parte, insieme alla modifica dell'APK.
UPDATE ps_objectives
   SET use_fat_mass = false,
       fat_pct = NULL
 WHERE use_fat_mass IS TRUE;
