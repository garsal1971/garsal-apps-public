-- Forziere — i file che devono stare al sicuro, cifrati end-to-end.
--
-- ⚠️ QUI DENTRO NON C'È NIENTE DA ATTACCARE, ed è la scelta che regge tutto il resto.
-- Il segreto sono 24 parole che stanno solo nella testa di Salvatore; i file cifrati stanno
-- su Google Drive in formato OpenPGP, e le due chiavi di servizio (la scorciatoia della
-- passphrase e la chiave dell'indice) stanno anch'esse su Drive. In queste tabelle restano
-- gli id dei file su Drive e i metadati cifrati: chi si portasse via un dump del database
-- non avrebbe nemmeno su cosa provare a indovinare.
--
-- ⚠️ IL DATABASE NON È INDISPENSABILE. Serve a sfogliare in fretta, a cercare e a mostrare
-- le miniature. Ogni file `.gpg` si riapre da solo con `gpg -d` e le 24 parole, e porta
-- dentro di sé il proprio nome originale (pacchetto literal di OpenPGP). Perso il database
-- non si perde un byte: si perde la comodità. È la proprietà per cui questa funzionalità
-- esiste, e nessuna modifica futura deve toglierla.
--
-- ⚠️ IL FORMATO È OpenPGP SIMMETRICO, non un formato nostro. La procedura di recupero
-- dev'essere ricordabile a mente — `gpg -d documento.gpg`, poi le 24 parole — e una ricetta
-- nostra (normalizzazione, sale, giri, HKDF) sarebbe fragile proprio dove non può esserlo:
-- basta sbagliare un dettaglio e non si apre più niente. Il file si porta dentro algoritmo,
-- sale e giri, e il programma li legge da sé.

-- ══════════════════════════════════════════════════════════════════════════════
-- frz_vault — una riga per utente. Dove sta il forziere, non cosa contiene.
-- ══════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS frz_vault (
  user_id            uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  -- La cartella su Drive e i due oggetti di servizio che ci stanno dentro.
  -- Sono id, non credenziali: senza il login Google di Salvatore non aprono niente,
  -- e senza le 24 parole non dicono niente comunque.
  drive_folder_id    text,
  drive_indice_id    text,   -- indice.gpg  → la chiave dell'indice, cifrata con le 24 parole
  drive_scorciatoia_id text, -- scorciatoia.gpg → le 24 parole, cifrate con la passphrase
  -- ⚠️ `indice.gpg` fa anche da PROVA: se si apre, le parole sono quelle giuste. Un secondo
  -- oggetto solo per verificare sarebbe una seconda verità sulla stessa domanda.
  creato_il          timestamptz NOT NULL DEFAULT now(),
  collaudato_il      timestamptz,  -- quando il recupero è stato provato per davvero
  esportato_il       timestamptz,  -- l'ultimo export .7z: la pagina dice da quanto manca
  aggiornato_il      timestamptz NOT NULL DEFAULT now()
);

-- ══════════════════════════════════════════════════════════════════════════════
-- frz_files — una riga per file. Il nome NON è in chiaro.
-- ══════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS frz_files (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  drive_file_id  text NOT NULL,
  -- ⚠️ Nome, tipo, dimensione vera e note stanno in UN SOLO blob cifrato, non in colonne
  -- separate: «divorzio-avvocato.pdf» in una colonna di testo racconta già tutta la storia,
  -- e una colonna per campo sarebbe un invito ad aggiungerne una in chiaro «solo per
  -- comodità». AES-256-GCM sotto la chiave dell'indice, base64.
  meta_enc       text NOT NULL,
  -- Il peso del CIFRATO, che su Drive si vede comunque: nasconderlo qui non nasconderebbe
  -- niente e servirebbe solo a non poter mostrare un totale.
  size_bytes     bigint NOT NULL DEFAULT 0,
  creato_il      timestamptz NOT NULL DEFAULT now(),
  aggiornato_il  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS frz_files_user_idx ON frz_files (user_id, creato_il DESC);

-- ══════════════════════════════════════════════════════════════════════════════
-- frz_thumbs — le miniature, cifrate come tutto il resto.
-- ⚠️ Tabella a parte e non una colonna di `frz_files`: l'elenco si legge a ogni apertura e
-- deve restare leggero, mentre le miniature si prendono solo per quello che è a schermo.
-- ══════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS frz_thumbs (
  file_id    uuid PRIMARY KEY REFERENCES frz_files(id) ON DELETE CASCADE,
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  thumb_enc  text NOT NULL
);

-- ══════════════════════════════════════════════════════════════════════════════
-- RLS — solo il proprietario, e nient'altro. Nessun accesso ospite, mai:
-- `cm_guest_access` qui non entra, ed è voluto.
-- ══════════════════════════════════════════════════════════════════════════════
ALTER TABLE frz_vault  ENABLE ROW LEVEL SECURITY;
ALTER TABLE frz_files  ENABLE ROW LEVEL SECURITY;
ALTER TABLE frz_thumbs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS frz_vault_owner  ON frz_vault;
DROP POLICY IF EXISTS frz_files_owner  ON frz_files;
DROP POLICY IF EXISTS frz_thumbs_owner ON frz_thumbs;

CREATE POLICY frz_vault_owner  ON frz_vault  FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY frz_files_owner  ON frz_files  FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY frz_thumbs_owner ON frz_thumbs FOR ALL
  USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

-- ══════════════════════════════════════════════════════════════════════════════
-- La bolla in AppSphere.
--
-- ⚠️ `riservato = true`: la bolla si vede SOLO in modalità nascosta, come Finanza. Un
-- forziere annunciato in home a chiunque guardi lo schermo da sopra la spalla è metà del
-- lavoro buttato — e la modalità nascosta esiste già, col suo codice a colori.
--
-- ⚠️ Il numero è un CONTEGGIO di file e non un punteggio: va aggiunto a `APP_SENZA_PUNTI`
-- in index.html, a `AppSenzaPunti` in PortedApps.kt e in scripts/backup-report.mjs, o i
-- file si sommerebbero ai punti che pagano i premi.
-- ══════════════════════════════════════════════════════════════════════════════
INSERT INTO cm_apps (title, description, score_query, color, active, html_file, riservato)
SELECT '🔐 Forziere',
       'I file al sicuro, cifrati end-to-end',
       $q$SELECT COUNT(*)::int FROM frz_files WHERE user_id = auth.uid()$q$,
       '#1F2937', true, 'forziere.html', true
WHERE NOT EXISTS (SELECT 1 FROM cm_apps WHERE html_file = 'forziere.html');
