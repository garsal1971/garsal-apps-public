-- Spuntiamola — l'umore della stecca
--
-- Fino a qui ogni stecca era un'attesa: il tempo che passa è impazienza, e
-- ogni spunta un giorno in meno fra sé e il traguardo. Ma lo stesso conto
-- alla rovescia si fa anche stando dentro a qualcosa di bello — una vacanza,
-- una visita, un periodo che finirà — e lì il tempo che passa non è attesa
-- ma consumo: l'ultimo giorno non è un arrivo, è un addio.
--
-- Sono due modi opposti di leggere la stessa griglia, quindi le frasi, le
-- emoji e i messaggi dei traguardi devono sapere in quale dei due si sta.
-- Da qui la colonna: una scelta per stecca, non una preferenza dell'utente —
-- si aspettano le ferie e nel frattempo si vivono, e le due cose convivono
-- nel tempo senza essere la stessa.
--
--   'attesa'     — il tempo che passa avvicina qualcosa: impazienza e ansia
--   'bei_giorni' — il tempo che passa porta via qualcosa: dolcezza e nostalgia
--
-- Le stecche già in archivio restano 'attesa', che è quello che erano
-- davvero: prima di questa colonna l'app sapeva parlare in un modo solo.

ALTER TABLE sp_settings
  ADD COLUMN IF NOT EXISTS mood text NOT NULL DEFAULT 'attesa';

ALTER TABLE sp_stecche
  ADD COLUMN IF NOT EXISTS mood text NOT NULL DEFAULT 'attesa';

-- Il vincolo sta qui e non solo nella tendina della pagina: un umore che il
-- codice non conosce non ripiega su niente di sensato — le frasi verrebbero
-- da una tabella che non esiste, e la stecca resterebbe muta.
ALTER TABLE sp_settings DROP CONSTRAINT IF EXISTS sp_settings_mood_ok;
ALTER TABLE sp_settings
  ADD CONSTRAINT sp_settings_mood_ok CHECK (mood IN ('attesa', 'bei_giorni'));

ALTER TABLE sp_stecche DROP CONSTRAINT IF EXISTS sp_stecche_mood_ok;
ALTER TABLE sp_stecche
  ADD CONSTRAINT sp_stecche_mood_ok CHECK (mood IN ('attesa', 'bei_giorni'));
