-- ============================================================================
-- Piante — una pianta archiviata ESCE dai suoi gruppi, e resta scritto di quali
-- faceva parte (`pv_plants.gruppi_archiviati`)
--
-- Archiviando una pianta le app cancellano le sue righe di pv_plant_groups (le
-- azioni di gruppo smettono di valere per lei) e scrivono qui le ETICHETTE dei
-- gruppi di allora, emoji compresa («🌿 Balcone»): testo e non id, perché
-- l'indicazione deve restare anche se il gruppo poi si rinomina o si cancella.
-- NULL = nessun gruppo. Togliendo la pianta dall'archivio si azzera: nei gruppi
-- la si rimette a mano, dal form.
-- ============================================================================

ALTER TABLE pv_plants ADD COLUMN IF NOT EXISTS gruppi_archiviati text[];

-- Le piante già archiviate che stanno ancora in un gruppo: stessa regola.
UPDATE pv_plants p
   SET gruppi_archiviati = sub.etichette
  FROM (
    SELECT pg.plant_id,
           array_agg(COALESCE(g.emoji || ' ', '👥 ') || g.name ORDER BY g.name) AS etichette
      FROM pv_plant_groups pg
      JOIN pv_groups g ON g.id = pg.group_id
     GROUP BY pg.plant_id
  ) sub
 WHERE p.id = sub.plant_id
   AND p.archived
   AND p.gruppi_archiviati IS NULL;

DELETE FROM pv_plant_groups pg
 USING pv_plants p
 WHERE p.id = pg.plant_id
   AND p.archived;
