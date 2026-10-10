# CLAUDE.md — garsal-apps

This file provides context for AI assistants working in this repository.

---

## Project Overview

**garsal-apps** is a collection of personal productivity web applications deployed to Cloudflare Pages (`garsal.men`). Each app is a **single self-contained HTML file** with no build step, no package manager, and no external source files. All styling and JavaScript live inline within the HTML.

The suite is branded **AppSphere** and the UI language is **Italian**.

---

## Utenti — applicazione familiare

**garsal-apps non è un prodotto multi-tenant generico: è un'applicazione familiare**, pensata per e usata esclusivamente da un piccolo gruppo di persone reali. Informazione informale, utile per capire richieste che parlano di persone specifiche per nome (es. notifiche solo per una persona, dati condivisi solo con alcuni):

| Persona | Ruolo |
|---|---|
| **Salvatore** | Utente principale / maggiore utilizzatore (l'account con cui interagisce Claude in questa repo) |
| **Teresa** | Condivide con Salvatore i dati di Finanza (`ta-firi`/finanza) e ora anche di Analisi Costi |
| **Rosa** | Condivide con Salvatore parte dei dati di Finanza |
| **Ada** | Solo utente di Analisi Costi (non ha accesso a Finanza) |

Questo elenco può cambiare nel tempo — se una richiesta menziona una persona non elencata qui, chiedere chiarimenti invece di assumere un ruolo.

---

## ⚠️ La repo: `garsal-apps-public`, PUBBLICA dal 6 ottobre 2026

Si lavora qui. `garsal1971/garsal-apps` resta **privata e ferma**, come archivio con tutto lo
storico: non ci si fa più niente.

**Perché il trasloco.** Da privata la vecchia repo aveva i minuti di Actions e lo spazio degli
artifact contati, e a inizio ottobre 2026 li aveva finiti: le build restavano in coda e venivano
cancellate senza log. Da pubblica i minuti sono gratis. Ma la vecchia repo **non si poteva
rendere pubblica così com'era**: nello storico (e in parte ancora nei file) c'erano dati veri —
un dump di Finanza con IBAN, redditi 2017-2025, pensione, acquisti, email di famiglia. Uno
storico git non si ripulisce davvero, quindi si è fatta una repo **nuova, senza storico**, coi
soli file di oggi ripuliti.

**Cosa è rimasto fuori**, di proposito:

| Fuori | Perché |
|---|---|
| Lo storico git | Conteneva i dati personali; sta nella repo vecchia |
| Le APK (`releases/*.apk`) | Si scaricano da R2 (`apk.garsal.men`); le schede `-latest.json` restano |
| `dati_migrazione_auto.sql` e gli script `.ps1` di migrazione | Dump con dati veri |
| 34 migration con dati personali (redditi, pensione, acquisti, viaggi, partecipanti dei fondi, email, test con dati veri) | Già applicate in produzione: il deploy crea da sé un segnaposto per le versioni remote senza file (*Apply Supabase migrations*). ⚠️ Ne discende che **un database nuovo non si ricostruisce più dalle sole migration di qui**: per quello c'è il dump settimanale |
| `wso-corriere-lavoro-99912.html` | Articolo copiato |
| `claude.yml` e `modifiche-issue-chiusa.yml` | Servivano a Modifiche, poi tolta del tutto |

⚠️ **Le regole nuove che ne discendono:**

- **Niente dati personali nella repo, mai**: né importi veri, né IBAN, né email di famiglia, né
  migration che inseriscono dati intestati a qualcuno. Una migration di dati si scrive, si applica
  e **non si committa** — o la si mette in un posto che non è questa repo. Gli esempi nei commenti
  e in questo file usano cifre inventate e tonde.
- **Il sito pubblica solo le pagine**: `scripts/build-sito.sh` esclude ora, oltre alle APK, anche
  `.github`, `android-app`, `supabase`, `scripts`, `tests`, `netlify`, `migrazioni-in-attesa` e
  ogni `*.md`, `*.sql`, `*.ps1`, `*.sh`, `*.py`, `*.bat`. Fino al trasloco copiava tutta la radice,
  quindi `CLAUDE.md`, le migration e il dump erano scaricabili da `garsal.men`.
- **I secrets di GitHub sono stati copiati** dalla vecchia repo con un workflow una tantum (i
  secrets non si rileggono, si possono solo riscrivere da un job che li riceve) — keystore delle
  APK compreso, quindi le APK nuove si installano sopra le vecchie. Il workflow è stato tolto.
- **Modifiche e Comandi sono stati TOLTI** (6 ottobre 2026): `modifiche.html`,
  `comandi.html`, `oauth-callback-modifiche.html`, l'APK Modifiche col suo progetto e workflow, e
  la Edge Function `modifiche-issue`. Su una repo pubblica una issue con le schermate di Finanza
  la leggerebbe chiunque. Tabelle `mod_*` e bucket
  `mod-immagini` cancellati da `20261006180000_mod_rimozione.sql`.
- **Cloudflare Pages è collegato a questa repo.** Il deploy è quello di sempre: push su un ramo
  `claude/**`, `deploy.yml` fonde su master.

Le sezioni più sotto che parlano della repo **privata** (il 13 settembre 2026, i minuti contati,
la ragione dei backup su Drive) sono la storia di prima del trasloco: dove contraddicono questa
sezione, vale questa.

---

## Client supportati

Le app sono progettate per funzionare su:

- **App Android** (`android-app/`) — WebView nativa con `AndroidBridge` JavascriptInterface. Supporta OCR via ML Kit, biometria, camera, condivisione immagini da altre app.
- **Browser desktop** (Chrome/Firefox/Safari su PC/Mac) — funzionalità complete incluso OCR via Tesseract.js.

**Non supportato**: browser mobile (Chrome/Safari su smartphone/tablet). Tesseract.js causa problemi di rete su WebView mobile e browser mobile in generale. Su mobile usare esclusivamente l'app Android.

### ⚠️ Sul telefono i caratteri di sistema sono molto grandi

Non è un caso limite da verificare alla fine: **è la condizione normale in cui queste app vengono
usate**. Salvatore tiene l'ingrandimento dei caratteri di Android su un valore alto, quindi ogni
scritta arriva a schermo parecchio più grande di come si vede in anteprima o sul desktop. Va
messo in conto **mentre** si scrive l'interfaccia, non dopo:

- **niente altezze fisse attorno al testo.** `height(56.dp)` su una barra che contiene una scritta
  è un ritaglio che aspetta il momento giusto per succedere — al primo ingrandimento il testo è più
  alto del contenitore e quel che avanza sparisce. Si usa `heightIn(min = …)` (Compose) o
  `min-height` (CSS), mai `height`;
- **una riga sola è un'ipotesi, non un dato.** Titoli, etichette dei pulsanti e voci di menù vanno
  a capo: o si lascia spazio per due righe, o si mette `maxLines` + ellissi decidendo *cosa* è più
  importante che resti leggibile;
- **le icone in `dp` non crescono col testo.** Accanto a una scritta ingrandita sembrano rimpicciolite:
  dove stanno in fila con del testo conviene scalarle con `fontScale` (con un tetto, vedi
  `GarsalTopBar`);
- **le griglie si sfilacciano**: celle affiancate con testi di lunghezza diversa vanno a capo un
  numero diverso di volte e perdono l'allineamento. Serve un'altezza minima comune, non una fissa.

---

## Repository Structure

```
garsal-apps/
├── index.html           # AppSphere — main entry point / app launcher (served at "/")
├── tasks.html           # Tasks v19.17.12 — task management
├── habit-tracker.html   # Habit Stack Tracker — habit tracking with gamification
├── events-log.html      # Events Log v2.0 — event/activity logging
├── weight-quest.html    # Peso e Calorie v4.11.5 — peso, obiettivi e diario alimentare
├── calorie.html         # rimando a weight-quest.html#diario (era il diario alimentare)
├── piante.html          # Piante v1.0.0 — diario di cura, azioni coi promemoria, IA, desideri
├── chiedi-a-pino.html   # Chiedi a Pino v1.1.0 — un prompt (con immagini), il motore IA scelto, risposte archiviate coi tag
├── _headers / _redirects # Header e riscritture per Cloudflare Pages
```

There is **no** `package.json`, `node_modules`, `build/`, or `dist/` directory. Every app ships as-is.

**Nota storica**: esisteva anche un `app-launcher.html`, copia quasi identica del launcher usata come `start_url` della PWA installabile. La PWA non è più in uso: il file è stato rimosso e `manifest.json` punta ora a `/` come ogni altro client (browser, app Android).

---

## Technology Stack

| Concern | Solution |
|---|---|
| Language | Vanilla HTML + CSS + JavaScript (ES2020+) |
| Backend / Auth | [Supabase](https://supabase.com) (PostgreSQL BaaS) |
| Deployment | Cloudflare Pages (`garsal.men`), APK su R2 (`apk.garsal.men`) |
| Charts | Chart.js v4.4.0 + chartjs-plugin-zoom (weight-quest only) |
| Touch gestures | Hammer.js (weight-quest only) |
| Fonts | Google Fonts — DM Sans / DM Mono (launcher), Space Mono / Darker Grotesque (other apps) |
| Supabase JS SDK | `@supabase/supabase-js@2` via jsDelivr CDN (most apps); weight-quest uses a custom minimal inline client |

---

## Architecture Patterns

### Single-file HTML apps
Each `.html` file is fully standalone: HTML structure, `<style>` CSS, and `<script>` JavaScript all in one file. There are no imports, no modules (except for the CDN scripts), and no transpilation.

Due eccezioni dichiarate: `/vendor/` (le librerie del Forziere) e `spese-famiglia.js`, le regole e la vista di Spese Famiglia condivise da tre pagine.

### Supabase backend
All apps share **one Supabase project**:

```js
const SUPABASE_URL = 'https://jajlmmdsjlvzgcxiiypk.supabase.co';
const SUPABASE_KEY = '<anon public key>'; // safe to be public; RLS controls access
```

Apps use the Supabase JavaScript client initialised from the CDN:
```js
const sb = window.supabase.createClient(SUPABASE_URL, SUPABASE_KEY);
```

**weight-quest.html** is the exception — it ships its own minimal inline `SupabaseClient` class (no CDN dependency) and queries Google Fit directly via OAuth.

### Authentication flow
1. `index.html` handles Google OAuth via Supabase Auth
2. On successful login, tokens are stored in `sessionStorage`:
   - `sb_token` — Supabase JWT access token
   - `google_token` — raw Google OAuth provider token (for Google Fit API)
3. When the launcher opens a child app (`window.open`), it passes the Google token via `postMessage`:
   ```js
   win.postMessage({ type: 'GOOGLE_TOKEN', token: googleToken }, '*');
   ```
4. Child apps listen for `GOOGLE_TOKEN` messages and store the token in `localStorage` for persistence.

### Navigation pattern (Tasks, Habit Tracker, Events Log)
Apps use a **sidebar nav** with `data-section` buttons toggling `.page` sections:
```
sidebar nav-item (click) → showPage(sectionName) → hide all sections, show target
```
Layout: `grid-template-columns: 280px 1fr` — sidebar left, main content right. Responsive mobile menu via a hamburger toggle.

---

## Database Schema

Tables are namespaced by app prefix:

### Shared (`cm_`)
| Table | Purpose |
|---|---|
| `cm_apps` | App registry for the launcher (title, description, html_file, score_query, active) |
| `cm_categories` | Shared category taxonomy used by Tasks and Habit Tracker |
| `cm_profile` | La scheda personale, **una riga per utente**: `nome`, `cognome`, `data_nascita`, `sesso` (`M`\|`F`\|`Altro`), `altezza_cm`, più patologie, farmaci e note mediche. Si compila da AppSphere → ☰ → 👤 Profilo, o direttamente da **`/#profilo`** |
| `cm_institutions` | Censimento di banche e broker. `connectable = false` per chi non è nel catalogo Enable Banking (broker senza PSD2): sta in anagrafica ma non si collega |
| `cm_bank_connections` | Un conto per riga, nato da un consenso. `uses text[]` dice a cosa serve |
| `cm_sync_log` | Storico delle sincronizzazioni bancarie |
| `cm_push_devices` | I telefoni a cui mandare le notifiche push. Una riga per **installazione**: `token` è la chiave, e l'app lo riscrive a ogni avvio |

Le due tabelle bancarie nascono come `ca_bank_connections` / `ca_sync_log` (Analisi Costi) e
sono state rinominate quando i conti sono diventati condivisi con i Fondi
(`20260803120000_cm_bank_connections_and_fondo_sync.sql`). Le viste di compatibilità con il
vecchio nome sono state eliminate da `20260807100000_cm_institutions_and_account_uses.sql`.

**Il collegamento di un conto è in tre momenti distinti** (tutti in `finanza.html` →
Configurazione → 🏦 Banche e Conti):

1. **censimento** — si aggiunge l'istituto a `cm_institutions`, cercandolo nel catalogo
   Enable Banking (o a mano, non collegabile);
2. **consenso** — `enable-banking-connect` riceve solo `{ institutionId, replaceConnectionId }`:
   nome ASPSP e paese vengono dall'anagrafica e non si compila nient'altro;
3. **battesimo** — i conti restituiti dalla banca nascono con `display_name NULL` e
   `uses '{}'`, e l'utente dà a ciascuno un nome e uno o più usi.

`cm_bank_connections.uses` ammette `'cost_analysis'` (Spese Famiglia), `'contribuzione'`,
`'spese_ada'`, `'spese_sal'` (Spese Personali), `'fondo'`, `'conto_risparmio'`, `'casa_rosa'` e
`'danaro_rosa'`: **un conto può
servire a più moduli** — il conto
delle spese comuni è insieme `cost_analysis` e `contribuzione` — e `uses` vuoto significa «scoperto
ma non ancora battezzato» — il conto esiste e si vede, ma nessun modulo lo elenca. Ha sostituito
la vecchia colonna `module`, che ne ammetteva uno solo e andava scelta *prima* del consenso.
`display_name` è **solo** il nome dato dall'utente: nessuna Edge Function lo riempie più
d'ufficio con l'IBAN, che ha la sua colonna `iban`.

### Tasks (`ts_`)
| Table | Purpose |
|---|---|
| `ts_tasks` | All tasks |
| `ts_history` | Audit log of task state changes |
| `ts_priorities` | Configurable priority levels (read-only from tasks.html) |
| `ts_settings` | Key-value app settings |

**Task types** in `ts_tasks.type`:
- `single` — one-off task with a `start_date`
- `recurring` — repeats on a schedule with `next_occurrence_date`
- `simple_recurring` — simpler recurrence variant
- `multiple` — task with multiple scheduled dates
- `free_repeat` — repeatable without a fixed schedule
- `workflow` — multi-step workflow task

### Habit Tracker (`hb_`)
| Table | Purpose |
|---|---|
| `hb_habits` | Habit definitions |
| `hb_completions` | Daily completion records |
| `hb_user_points` | Gamification points balance |
| `hb_points_transactions` | Points ledger |
| `hb_archived_stacks` | Archived habit stacks |

### Events Log (`el_`)
| Table | Purpose |
|---|---|
| `el_groups` | Event category groups; `riservato` li tiene fuori dagli elenchi |
| `el_events` | Event definitions |
| `el_logs` | Event log entries |

Il **riservato sta sul gruppo e non sull'evento**: eventi e registrazioni seguono il loro gruppo,
e nascondere un evento lasciando visibile il gruppo che lo contiene direbbe comunque di cosa si
sta parlando. ⚠️ La colonna è arrivata **dopo** le righe (`el_*` non sta in nessuna migration):
i gruppi di prima ce l'hanno **NULL**, quindi il filtro va sempre scritto
`riservato.eq.false,riservato.is.null` — con la sola uguaglianza sparirebbero tutti.

### Fondi (`fnz_fund*`)
| Table | Purpose |
|---|---|
| `fnz_funds` | Fondi comuni; `bank_connection_id` = conto da cui importare i movimenti |
| `fnz_fund_participants` | Anagrafica partecipanti di un fondo: `name`, `iban` (chiave di match del sync), `active` |
| `fnz_fund_contributions` | Movimenti: importo **con segno** (`type` `'versamento'` > 0 / `'prelievo'` < 0), `status`, `participant_id`, `external_id` |
| `fnz_foi_index` | Indice ISTAT FOI (media annua) per la rivalutazione |

`fnz_fund_contributions.status` decide se il movimento conta: `auto` (abbinato dal sync) e
`confermato` entrano nelle quote, `da_rivedere` (controparte non riconosciuta) e `escluso` no.
`participant` (testo) resta popolato accanto a `participant_id`: i fondi nati prima
dell'anagrafica non hanno partecipanti anagrafici e continuano a funzionare così.

I movimenti del fondo possono arrivare da tre strade: inseriti a mano, importati dal conto
bancario via `enable-banking-fondo-sync`, oppure presi dai movimenti del Conto Risparmio già
in `cntrs_transactions_terr` (pulsante *⬇️ Importa dal Conto Risparmio*). In quest'ultimo caso
`external_id` vale `cntrs:<id della riga di origine>` e il partecipante si riconosce
verificando che il **suo IBAN compaia** nel testo del movimento (confronto senza spazi né
punteggiatura), col nome come ripiego — nessuna estrazione dell'IBAN con regex dalla causale.

`amount_adjusted` è una **cache** della rivalutazione, riempita da
`fnz_fund_recalc_adjusted(p_fund_id)`; il valore mostrato a schermo lo ricalcola comunque
la RPC `fnz_quote_fondi` (vedi sotto). Chiamare la RPC dopo ogni scrittura sui movimenti o
sull'indice FOI, altrimenti la colonna resta indietro rispetto a quello che si vede nell'app.

✅ **Le quote sono UNA regola, nel database** (25 settembre 2026,
`20260925100000_fnz_quote_fondi.sql`): **`fnz_quote_fondi(p_fund_id)`** dà, per fondo, anno di
riferimento, totali nominale e rivalutato, l'avviso sugli indici mancanti, il coefficiente di
ogni anno e i partecipanti con la loro percentuale. La leggono `finanza.html`
(`computeFundShares`, via `loadQuoteFondi` a ogni load/reload) e `situazione-teresa.html`
(`pfQuote`), che fino a lì portavano due copie a mano dello stesso conto. **Una modifica alla
regola si fa nel SQL e basta.** ⚠️ `SECURITY INVOKER`: Teresa vede il solo fondo del Conto
Risparmio. ⚠️ L'indice FOI è quello del **proprietario del fondo**, come in
`fnz_fund_recalc_adjusted`. ⚠️ Due cose restano nella pagina, di proposito: il filtro
`FUND_COUNTED_STATUS`, che decide solo come si *disegna* una riga (lo stesso filtro sta nel SQL,
e cambiandolo va cambiato in tutt'e due), e `foiCoefficient`, che serve alla sola **anteprima**
del form di un movimento non ancora salvato. Verificato prima di pubblicare: 873 confronti contro
le due copie JavaScript, nessuna differenza (scarto 3,6·10⁻¹²).

### Danaro di Rosa (`fnz_other_assets` + `fnz_rosa_transactions`)
| Table | Purpose |
|---|---|
| `fnz_other_assets` con `asset_type = 'danaro_rosa'` | Le voci di Rosa (BTP, Conto Arancio, altro): `value` è il saldo/valore di mercato, `valuation_date` la data cui si riferisce |
| `fnz_rosa_transactions` | Movimenti del conto di Rosa letti dalla banca: `amount` **con segno**, `external_id` unico per `bank_connection_id` |

**Nessuna categoria, di proposito**: questa pagina tiene il registro del conto e il saldo, non
analizza la spesa come Spese Famiglia o Spese Ada. Una colonna categoria che nessuno riempie è
solo un invito a reimplementare la categorizzazione una quarta volta.

Il conto si collega come tutti gli altri (`finanza.html` → Configurazione → 🏦 Banche e Conti) e
si spunta come `'danaro_rosa'` in `cm_bank_connections.uses`. Da lì *🏦 Leggi dal conto* chiama
`enable-banking-transactions` — che legge e basta — e la stessa finestra fa due cose distinte:
archivia i movimenti scelti e, se lo si conferma, riscrive `value` e `valuation_date` di **una**
voce di Danaro di Rosa col saldo letto. La voce si sceglie nella tendina: il conto non è legato a
una riga di `fnz_other_assets`, perché accanto al Conto Arancio ci sono un BTP e altre voci che
con quel saldo non c'entrano.

Rosa vede le voci in `situazione-rosa.html` (via `cm_guest_access`), **non i movimenti**:
`fnz_rosa_transactions` ha la sola policy owner.

### Reddito (`fnz_income`)
| Table | Purpose |
|---|---|
| `fnz_income` | Reddito percepito, **una riga per anno e per tipo**: `year`, `kind`, `amount`, `notes` |

Si compila a mano da `finanza.html` → 💶 **Reddito** (voce di menù propria): è l'unico dato di
Finanza che non viene da nessuna banca. **Non entra in nessun totale del patrimonio** — il
patrimonio è quello che c'è, il reddito è quello che è passato — quindi né lo snapshot né la
dashboard lo guardano.

Una riga per anno *e* per tipo, non una riga per anno con colonne fisse: i tipi vivono in
`INCOME_SECTIONS` dentro `finanza.html` e aggiungerne uno è una riga di JavaScript, non una
migration. `kind` è testo libero di proposito; il vincolo che conta è
`UNIQUE (user_id, year, kind)`, su cui la pagina scrive in **upsert** — senza, ricompilare un
anno raddoppierebbe il totale in silenzio. Una casella lasciata vuota **cancella** la sua riga
invece di salvare zero: «dato assente» e «zero euro» sono due cose diverse, e un importo tolto
che restasse scritto continuerebbe a fare totale.

Nella sidebar è una **voce di menù a sé** (sottotitolo *Reddito*), non più insieme a 💎 Asset
sotto *Patrimonio*: quelli sono quello che c'è, il reddito è quello che è passato.

La pagina è in **due sezioni**, una tabella ciascuna: redditi (`lavoro`, `pensione`,
`altri_lordi`, `altri_netti`) e liquidazione della dichiarazione (`liq_imponibile`,
`liq_imposta_lorda`, `liq_imposta_netta`). Il ✎ e il ✕ agiscono **sulla singola sezione** di un
anno, non sull'anno intero: le due tabelle vengono da documenti diversi.

⚠️ **Due colonne della liquidazione sono calcolate** e vivono in `INCOME_CALC`, non in
`fnz_income`:

| Colonna | Formula |
|---|---|
| Detrazioni | `liq_imposta_lorda − liq_imposta_netta` |
| Reddito netto | `liq_imponibile − liq_imposta_netta` |

Si riconoscono dal **«(calc.)» in intestazione**, non compaiono nel form e non si archiviano: una
grandezza calcolata *e* archiviata sono due verità sullo stesso numero, che divergono il giorno
che una delle due cambia. Tornano `null` — non zero — se manca un ingrediente, quindi il reddito
netto del 2017 resta un trattino (manca l'imponibile). Le detrazioni tornano **esatte su tutti e
sette gli anni**, verificate contro il vecchio `liq_detrazioni` archiviato.

⚠️ Il **reddito netto così calcolato non toglie le addizionali** regionale e comunale, che sul 730
stanno fuori dall'imposta netta: è il reddito al netto della sola IRPEF, non l'accredito in banca.

`liq_reddito_netto` **non è più un kind** — la colonna era stata aperta il 16 agosto e mai
compilata. Netto in busta (lordo − ritenute), totale lavoro dipendente (lordo + pensione) e totale
trattenuto erano calcolati pure loro e restano fuori: le formule sono nella storia di git
(`e78fd62` e precedenti).

⚠️ **I kind usciti dalle tabelle sono stati cancellati dal database**, su richiesta esplicita:
`20260816110000_fnz_income_cancella_kind_ritirati.sql` ha tolto 86 righe su 112 in 19 kind —
ritenute in busta paga (`rit_*`), contributi previdenziali (`prev_*`), premi/welfare/cedolare
(`prem_*`, `ced_*`), cinque voci della liquidazione (`liq_detrazioni`, `liq_imposta_netta`,
`liq_acconti`, `liq_esito`, `liq_reddito_rif`) e tre dei redditi (`fabbricati`,
`abitazione_principale`, `reddito_complessivo`). In `fnz_income` restano solo i kind mostrati.
**La via di recupero è la migration dei dati storici**, dove gli importi sono scritti in chiaro:
le migration girano in ordine anche su un database nuovo, quindi il risultato coincide con la
produzione. Togliere una voce da `INCOME_SECTIONS` resta reversibile in sé, ma **la colonna
riaperta è vuota** finché una migration non ne ricopia gli importi da lì: è quello che
`20260817100000_fnz_income_ripristina_imposta_netta.sql` fa per `liq_imposta_netta`, rimessa in
tabella coi suoi sette anni (2017, 2019-2022, 2024-2025). Le altre quattro voci della
liquidazione restano cancellate: `liq_detrazioni` è tornata a vedersi **come colonna calcolata**,
quindi senza rimettere in piedi la sua riga, e acconti, esito e reddito di riferimento restano
fuori del tutto.

I **due riquadri in cima** (UniCredit lordo, Reddito netto) passano dalla stessa `incomeCell()`
delle celle della tabella, calcolate comprese: il riquadro non può mostrare un numero diverso da
quello che si legge nella riga sotto. Mostrano l'ultimo anno che ha quella colonna, e la
variazione **solo se ce l'ha anche l'anno prima** — cioè fra due cifre ricavate allo stesso modo.

La tabella arriva **fino all'anno scorso** e non a quello in corso: un anno incompleto messo in
colonna accanto agli altri sembrerebbe un crollo del reddito. Gli anni senza dati restano
visibili come righe da riempire — un anno saltato deve vedersi, non sparire dall'elenco.

I dati storici 2017-2025 ricavati dai 730 e dalle CU sono in
`20260815160000_fnz_income_dati_storici.sql`: l'insert è idempotente (`ON CONFLICT … DO UPDATE`)
e intestato all'utente cercato per email, e salta senza fallire se quell'utente non esiste
(progetto dev).

### Possibili soluzioni (`fnz_coverage_items`)
| Table | Purpose |
|---|---|
| `fnz_coverage_items` | Le voci di copertura, **una riga per voce**: `side` (`fabbisogno`\|`dotazione`), `item_key`, `amount`, `periodicity`, `revaluation_pct`, `linked_other_asset_id`, `excluded`, `note` |

Si compila da `finanza.html` → Piano pensione → 🛡️ **Possibili soluzioni**, la voce di menù
sotto 🏛️ Simulazione INPS. Quello che servirà, quello con cui ci si arriva, e quanto manca.
(La sezione della barra si chiamava *Previdenza* e la voce *Coperture per esigenze future*: sono
solo etichette, `data-view` resta `coperture` e la tabella `fnz_coverage_items`.)

⚠️ **Il fabbisogno non è un numero solo: si conta fino a una data, e le date sono quattro.**
«Costo della vita» e «contribuzione alla pensione» sono flussi che durano finché non si smette
di lavorare, quindi valgono di più uscendo a vecchiaia che cinque anni prima — ed è esattamente
la differenza che la pagina esiste per mostrare.

| Scenario | Data |
|---|---|
| 🌿 Uscita naturale | Anticipata **− `USCITA_NATURALE_ANNI`, costante fissa = 5 anni** |
| 🧭 Uscita concordata | Anticipata **− la somma di `uscita()`** (di partenza 5 + 1 + 2 = 8 anni) |
| 🚪 Pensione anticipata | `fnz_pension_forecast.pension_date`, scenario `anticipata` |
| 🏛️ Pensione di vecchiaia | `fnz_pension_forecast.pension_date`, scenario `vecchiaia` |

⚠️ **La prima colonna è il METRO e non un piano**, ed è l'unica col fondo azzurro (`.cov-rif`,
sulle celle `.cov-val` e sulla colonna del riepilogo): risponde a «uscendo cinque anni prima
dell'anticipata, senza aver trattato niente con nessuno, quanto servirebbe?». I suoi 5 anni sono
una **costante** e non la somma di `uscita()` — legarla alle durate della trattativa la farebbe
muovere insieme a quello che dovrebbe misurare, e cambiando 5+1+2 dal ✎ si sposterebbero tutt'e
due le colonne invece della sola concordata. ⚠️ Sta **per prima** anche se la sua data cade
**dopo** quella dell'uscita concordata (−5 contro −8): l'ordine delle colonne è quello di
lettura — prima il riferimento, poi i piani — non quello del calendario.

⚠️ **🧭 Uscita concordata è l'unica delle quattro che si decide, e non è un numero solo.** Fino alla
v1.7.0 era la costante `COVERAGE_ANNI_ACCOMPAGNAMENTO = 5`; ora sono **tre durate che si
sommano** — accompagnamento dell'azienda, garden leave che l'azienda concede **in più**, e
aspettativa non retribuita che ci si prende da sé — perché sono tre cose diverse, si trattano
con interlocutori diversi e schiacciarle in un numero solo le rendeva impossibili da negoziare
una per una. Le tre durate si cambiano dal ✎ **sotto il titolo della sua colonna** nel
riepilogo (`renderUscitaNota`), etichettato *setta parametri*. ⚠️ Sta lì e non sopra la tabella
(dov'era fino alla v1.14.0): è una proprietà di quella colonna, e in cima parlava di una colonna
che il lettore doveva ancora trovare. Nella cella ci sono quindi **due ✎**, uno per cosa: prima
le durate, poi la descrizione scritta a mano (`renderScenarioNota`). ⚠️ L'etichetta è due parole
e **non l'addizione per esteso** (v1.15.1): «5 anni di accompagnamento + 1 di garden leave + 2 di
aspettativa = 8 anni prima dell'anticipata» nell'intestazione di una colonna stretta andava a capo
tre volte e spingeva giù i nomi delle altre colonne. Gli addendi restano **dentro la finestra**,
che è il posto dove si cambiano; la data risultante si legge nella riga *Data*, un rigo sotto.

⚠️ **I tre numeri vivono in `cm_settings`, chiave `fnz_uscita_anticipata`** (JSON
`{accompagnamento, garden_leave, aspettativa}`), non in una costante e **non in
`localStorage`**: sono un'ipotesi di trattativa — cioè esattamente il dato che si vuole cambiare
senza toccare il codice — e la stessa domanda ci si fa dal PC e dal telefono. Chiave assente o
casella vuota valgono `USCITA_DEFAULT` (5 + 1 + 2) e **non zero**: zero direbbe che non si esce
affatto prima, ed è la stessa scelta di `amount` NULL.

⚠️ **Le tre durate si sommano e non si sovrappongono**, ed è la sostanza del piano: l'azienda
accompagna, il garden leave viene in più, l'aspettativa si aggiunge ancora. Per la stessa
ragione `spostaAnni()` sposta di **mesi** e non di anni interi — le durate si scrivono a mano e
niente vieta mezzo anno di preavviso, che con `setFullYear` darebbe una data invalida (NaN)
invece di sei mesi.

⚠️ **Ogni colonna porta un sottotitolo che si scrive a mano** (`renderScenarioNota`, il ✎
nell'intestazione del riepilogo): una riga per ricordarsi *cos'è* quel piano, sotto il nome breve.
Vive in `cm_settings`, chiave **`fnz_scenari_note`** (JSON `{naturale, accompagnamento,
anticipata, vecchiaia}`), letta insieme alle altre due da `loadUscita()` — non in `localStorage`,
perché è un pezzo del piano e la stessa frase si rilegge dal telefono. Compare **solo nel
riepilogo**: negli elenchi di Fabbisogno e Dotazioni la stessa scritta si ripeterebbe su ogni
voce. ⚠️ Vuoto **non sparisce**, resta un «+ descrizione» sbiadito: il ✎ da solo sarebbe un
pulsante senza niente accanto. E svuotarla **toglie la chiave** invece di salvare la stringa
vuota, come la casella vuota di `fnz_income`.

### ⏳ La macchina del tempo

In cima alla pagina una barra sposta il **punto di osservazione** di mese in mese, da oggi fino
alla più lontana delle quattro date: `⏮ Oggi · ‹ · settembre 2026 · ›`, un cursore, e la
**percentuale di rivalutazione**. Tutta la pagina risponde allora alla domanda «e se me lo
chiedessi da lì?».

⚠️ **Le quattro date NON si spostano.** Sono la pensione che dice l'INPS e le durate della
trattativa, e non dipendono da quando ci si fa la domanda. A spostarsi è **da dove si contano
gli anni** (`anniFinoA` parte da `coverageOggi()`), e da lì si accorciano da soli tutti i flussi
del fabbisogno.

⚠️ **A «oggi» la pagina mostra ESATTAMENTE i numeri di prima**: `coverageMesi()` vale 0, la data
di osservazione è oggi e `coverageFattore()` torna `1` dal ramo corto — non `Math.pow(x, 0)`. È
la garanzia che la barra non sposti nessun conto finché non la si muove; senza, ogni apertura
della pagina direbbe qualcosa di diverso dal giorno prima senza che nessuno l'abbia deciso.

| Cosa | Alla data scelta |
|---|---|
| 🏠 Estinzione mutuo | **si ricalcola** col piano di ammortamento (`computeLoanValue(l, data)`) |
| 🎓 Università di Ada | si **consuma**: resta la quota dei mesi ancora da fare, e quella si rivaluta |
| 👛 Pensione Ada (dotazione) | si **accorcia**: importo annuo × i mesi che restano fino alla fine del corso regolare |
| Tutto il resto — fabbisogno **e** dotazioni | si **rivaluta** di `(1+r)^anni` |

⚠️ **Il mutuo si ricalcola e non si rivaluta**, ed è l'unica eccezione: il piano di ammortamento
sa già quanto sarà il residuo quel mese, e moltiplicarlo anche per l'inflazione lo conterebbe due
volte in senso opposto — un debito che scende non è un costo che sale. Lo dice `allaData: true`
sulla voce in `COVERAGE_ITEMS`, ed è l'unica che ce l'ha.

### ⏳ 🎓 L'università di Ada si consuma coi mesi (v1.21.0)

È l'unica voce che porta un **periodo** — `periodo: () => adaUniversita()`, cioè **sette anni da
settembre, 84 mesi** — e l'importo scritto nel form è il **totale di quei sette anni**.
Spostando la ⏳ macchina del tempo dentro quel periodo, i mesi già passati **sono già stati
spesi**: quel che resta da coprire è la sola coda, `coverageResiduo(item).frazione`. Fino all'inizio
vale intero (e quindi **a barra ferma la pagina mostra esattamente i numeri di prima**), a
settembre 2032 vale 48/84, a corso finito vale **zero** — non `null`: quel fabbisogno non c'è più,
non è un dato che manca. Senza, osservando dal 2033 la pagina chiederebbe di coprire
un'università già fatta per metà.

⚠️ **Passa da `coverageBase`, lo stesso varco della rivalutazione e della deduzione**: totale,
scopertura, capitale da assicurare e badge «al posto di X» devono dire tutti quella cifra lì —
sono lo stesso `value`. E **si moltiplica** per il fattore della barra invece di sostituirlo: quel
che resta da spendere è pur sempre in euro di allora.

⚠️ **Il form continua a chiedere il totale**, e lo dice: scrivendoci a mano il residuo lo si
conterebbe due volte, e l'importo in archivio smetterebbe di voler dire quello che vuol dire. È la
stessa ragione per cui la casella mostra `row.amount` grezzo e il «Finanza dice X» non è
proiettato.

⚠️ **Si conta a MESI e non ad anni accademici** (`mesiFra`): la barra si muove di mese, e una voce
che resta ferma per undici mesi su dodici si legge come una voce che non risponde. **Il mese
parziale entra per la sua frazione**, come l'ultimo anno di `sommaFlusso`: fermarsi al mese intero
farebbe valere zero una voce a tre settimane dalla fine.

⚠️ **Solo su un `una_tantum`**: messa `annuo` dal ✎ l'importo è un flusso, che `sommaFlusso` già
moltiplica per gli anni — applicare anche la frazione lo conterebbe due volte in senso opposto.

⚠️ **La riga lo scrive** (`notaResiduo`): *già cominciata: restano 48 mesi su 84 (57 %
dell'importo)*. Un fabbisogno che cala senza che niente spieghi perché è indistinguibile da un
conto sbagliato. La nota **non compare sotto il mezzo mese consumato**: arrotondata direbbe «84
mesi su 84 (100 %)», cioè un avviso che annuncia di non aver tolto niente.

⚠️ **La rivalutazione passa da UN varco solo, `coverageBase()`**, e da nessun'altra parte: è la
ragione per cui totale, scopertura, capitale da assicurare e il badge «al posto di X» dicono
tutti la stessa cifra — sono lo stesso `value`. L'unica voce fuori è 💼 Redditi da lavoro perso,
che non passa di lì e si rivaluta in `renderCoverageRedditoLavoro`.

⚠️ **Il form dell'importo legge `coverageAuto`, non `coverageBase`**: il database parla in euro
di **oggi**, e un «Finanza dice X» col numero già proiettato inviterebbe a ricopiarlo a mano —
per poi rivalutarlo una seconda volta. Per la stessa ragione la casella mostra `row.amount`
grezzo.

⚠️ **Il mese scelto vive in memoria (`S.covMesi`) e non in `cm_settings`**: è una lente, non un
pezzo del piano. Ritrovarla spostata di tre anni riaprendo la pagina vorrebbe dire leggere numeri
proiettati credendoli quelli di oggi, che è il modo peggiore di sbagliarsi. La **percentuale**
invece è un'ipotesi come le durate dell'uscita, e sta in `cm_settings` chiave
**`fnz_rivalutazione`** (JSON `{pct}`, di partenza 2), letta da `loadUscita()` con le altre tre.
⚠️ **Non è la colonna `revaluation_pct`** di `fnz_coverage_items`, che resta inutilizzata: quella
era una percentuale per voce, questa è una sola per tutta la pagina.

### 🎯 «Scopertura a zero» — il bottone che muove la barra al posto tuo

Nell'**intestazione di ogni colonna** del riepilogo, sotto i due ✎, c'è un pulsante **più grande
di loro**: porta la ⏳ macchina del tempo al mese in cui la **scopertura di quel piano** si
annulla. È la domanda «e allora quando potrei?», a cui la barra risponde già ma solo a forza di
trascinarla.

⚠️ **Non c'è sulla 🌿 Uscita naturale**: quella colonna è il **metro** e non un piano, e un
pulsante che la porta a zero inviterebbe ad aspettare per un piano che nessuno sta facendo. Non
c'è nemmeno su una colonna **senza data**: là non c'è nessun orizzonte su cui cercare, e un
pulsante che risponde «non si può» dopo essere stato premuto è un pulsante che non si mette.

⚠️ **È più grande dei ✎ e non è un vezzo**: quelli cambiano un parametro del piano, questo
risponde alla domanda per cui la pagina esiste. Alla stessa taglia sarebbe un terzo ✎.

⚠️ **Su una colonna già coperta è SPENTO, non tolto** (v1.21.2): con la scopertura ≤ 0 la domanda
«e allora quando?» ha già risposta «adesso», e premendolo la barra resterebbe dov'è con una nota
che annuncia di non aver spostato niente. Farlo sparire lascerebbe però un'intestazione diversa
dalle altre senza dire perché — così resta grigio, col `title` che lo spiega, come una voce
esclusa che resta a schermo sbiadita. ⚠️ **Segue la cella e non un secondo conto**: legge la
stessa `scopertura` che il riepilogo scrive due righe sotto (`renderZeroBtn(s, conti[k].scopertura)`),
quindi si spegne esattamente quando quella cella diventa verde — **anche a barra mossa**, che è
quel che si sta guardando. Un criterio suo — le voci ancora da compilare, per dire — lo
accenderebbe e spegnerebbe per una ragione che in quella colonna non si vede.

⚠️ **Il mese si CERCA valutando `coverageScopertura`, non si ricava con una formula**: fra la
scopertura e i mesi ci stanno il piano di ammortamento del mutuo, i flussi troncati all'anno
parziale e la rivalutazione composta — invertirli sarebbe una seconda implementazione della
scopertura, cioè due risposte diverse alla stessa domanda il giorno che una cambia.

⚠️ **La ricerca è in DUE passate** (`coverageMeseZero`): di anno in anno per trovare i dodici mesi
buoni, poi mese per mese lì dentro. Ogni valutazione rifà l'intero conto — portafogli, mutui,
asset, accordo — e i vent'anni del cursore a passo di mese sarebbero 241 giri, cioè la pagina
ferma per secondi; così sono fra le 2 e le 53. ⚠️ `coverageScoperturaA` sposta `S.covMesi` per
leggere e lo **rimette in un `finally`**: è la lente della pagina, e lasciarla spostata da una
ricerca farebbe leggere numeri proiettati credendoli quelli di oggi.

⚠️ **Vince il PRIMO mese in cui la scopertura è coperta**, non quello in cui è più vicina a zero:
la domanda è «da quando basta». Quando non si annulla mai entro il cursore si va al mese che ci
va più vicino — e lì la ricerca **affina attorno al migliore** (± 12 mesi), o il «più vicino»
sarebbe il più vicino *fra i multipli di dodici guardati*, che è un'altra cosa.

⚠️ **La nota sotto il titolo si mostra una volta e si consuma** (`S.covZeroNota`, letta e azzerata
da `renderCoverageRiepilogo`): commenta il tocco appena fatto, e lasciata lì parlerebbe di una
barra che intanto si è spostata a mano. È verde quando la scopertura si è annullata e ambra
quando il cursore non ci arriva — sono due risposte diverse, non un dettaglio.

⚠️ **Una colonna la cui data è già passata resta a schermo, sbiadita** (`.cov-past`, badge *già
passata*): i suoi flussi valgono zero — zero anni davanti, per il `Math.max(0, …)` di
`anniFinoA` — e i capitali restano scritti. Sparire sarebbe il modo peggiore di dirlo: un piano
in meno senza niente che spieghi perché.

⚠️ **Il cursore ridisegna la pagina su `change` e non su `input`**: ridisegnando a ogni `input`
l'elemento verrebbe sostituito sotto le dita e il trascinamento si fermerebbe al primo mese.
Trascinando si aggiorna il **solo mese scritto**; le frecce ‹ › danno la precisione del mese, che
un cursore su un telefono non dà.

⚠️ **La data di osservazione si scrive coi campi locali** (`isoLocale`) e non con `toISOString()`,
che converte in UTC — una mezzanotte italiana lì dentro è il giorno prima. E il giorno si limita
all'ultimo del mese di arrivo: `setMonth` da solo porta il 31 gennaio + 1 mese al **3 marzo**,
cioè sposta di un mese e due giorni.

⚠️ **Le quattro date si leggono da `coverageDateScenari()`, un posto solo**: le usa
`coverageScenarios` per disegnare le colonne e `coverageMesiMax` per sapere fin dove arriva il
cursore. Due copie sarebbero due elenchi che divergono il giorno che se ne aggiunge una.

⚠️ **Una data che manca non si inventa**: la colonna resta vuota e lo dice. Un fabbisogno contato
su un orizzonte immaginario è peggio di un fabbisogno che non c'è, perché sembra un numero.
Per la stessa ragione `coverageValoreA` torna `null` — non zero — su un flusso senza orizzonte.

⚠️ **Le voci non stanno nel database**: vivono in `COVERAGE_ITEMS` dentro `finanza.html`, come
`INCOME_SECTIONS` per il reddito, e aggiungerne una è una riga di JavaScript, non una migration.
`item_key` è testo libero di proposito; il vincolo che conta è `UNIQUE (user_id, side, item_key)`,
su cui la pagina scrive in **upsert** — senza, ricompilare una voce raddoppierebbe il totale in
silenzio. È la stessa scelta di `fnz_income`.

⚠️ **Quattro fonti diverse, e la differenza è la funzionalità**:

| `fonte` | Da dove viene il numero |
|---|---|
| `manuale` | Lo scrive l'utente e basta (università, costo della vita, rendita per Ada, varie ed eventuali) |
| `auto` | Si legge **dal vivo** dai dati di Finanza: debito residuo dei mutui (`computeLoanValue`), valore quota dei portafogli **al netto delle tasse** (`portfolioStats`), contribuzione alla pensione (33 % del lordo di 📋 Estratto conto), Pensione INPS da 💶 Reddito |
| `asset` | Come `auto`, ma la riga di `fnz_other_assets` la sceglie l'utente in tendina (TFR, Casa Rosa, Casa Mia) |
| `calcolata` | Non si scrive e non si archivia: è la **scopertura** dello scenario. Vale per la sola assicurazione |

⚠️ **🏛️ Contribuzione alla pensione è il 33 % del lordo, non l'importo della pensione**
(v1.19.2). `coverageContribuzionePensione()` prende il **lordo dell'ultimo anno pieno** di
📋 Estratto conto contributivo — lo stesso `redditoEstrattoConto()` che legge la deduzione, quindi
un reddito solo e non due — e ne conta il **33 %** (`ALIQUOTA_CONTRIBUTIVA`, l'aliquota IVS del
lavoro dipendente). Essendo `periodicita: 'annuo'`, la colonna vale poi *quell'importo × gli anni
che mancano alla sua data*.

⚠️ **Fino alla v1.19.1 leggeva `fnz_pension_forecast.gross_amount`**, cioè l'importo lordo
dell'ultima simulazione INPS: è la pensione che si **incassa**, non il contributo che si **versa**
— due grandezze diverse, e nessuna ragione perché coincidano. `coveragePensioneLorda` è stata
tolta; la simulazione INPS resta quello per cui esiste, cioè le **date** dei due scenari
(`coverageDateScenari`).

⚠️ **I lordo dell'anno si sommano**: l'estratto conto spezza l'anno per datore di lavoro e per
tipo di contribuzione — il 2015 e il 2022 hanno due righe a testa — e prenderne una sola conterebbe
una frazione di stipendio. ⚠️ Il **33 % è una costante scritta in `finanza.html`**, come gli
scaglioni IRPEF, e la riga la scrive accanto al numero.

⚠️ **🏛️ Contribuzione alla pensione è l'unica voce DEDUCIBILE**, e la spunta lo dice.
`applica_detrazione` (`20260908160000_...`) sta sulla riga come `excluded`, per la stessa ragione:
è una proprietà di quella voce, non una preferenza di lettura. Accesa, la voce si conta al **netto
dell'IRPEF risparmiata** — la contribuzione volontaria abbassa l'imponibile (art. 10 TUIR), quindi
versare X costa meno di X.

| Cosa | Da dove |
|---|---|
| Reddito lordo | 📋 **Estratto conto contributivo**, somma degli `income_amount` dell'ultimo anno **pieno** |
| Risparmio | `IRPEF(reddito) − IRPEF(reddito − X)`, scaglioni `IRPEF_SCAGLIONI` (23 % ≤ 28k · 35 % ≤ 50k · 43 % oltre) |

⚠️ **Il risparmio si calcola per DIFFERENZA e non moltiplicando per l'aliquota marginale**: una
deduzione grossa scavalca lo scaglione, e la parte che scende sotto i 50.000 € vale il 35 % e non
il 43 %. Con la moltiplicazione il risparmio uscirebbe più alto del vero — cioè il **fabbisogno più
basso del vero**, che è l'errore che questa pagina non deve fare. Su un reddito di 60.000 €:
10.000 € deducono al 43 % pieno, 20.000 € al 39,2 %, 35.000 € al 36,5 %.

⚠️ **«Anno pieno» è l'anno il cui ultimo periodo finisce il 31 dicembre**, non l'anno con 52
settimane utili: un anno part-time ne ha meno e resta intero lo stesso. E non
è «l'ultimo anno in tabella», che a metà anno è mezzo — cioè uno scaglione più basso e un risparmio inventato. È la stessa regola di «Pensione Ada» e
dell'🤝 accordo con l'azienda. Le righe dello stesso anno **si sommano**: l'estratto conto spezza
l'anno per datore di lavoro e per tipo di contribuzione.

⚠️ **La deduzione passa da `coverageBase`, lo stesso varco della rivalutazione**, e si applica
**prima** del fattore della ⏳ macchina del tempo: l'IRPEF si conta in euro di oggi contro un
reddito di oggi. Vale anche sull'importo **scritto a mano** — la deducibilità è del versamento, non
della fonte da cui il numero viene — e `saveCoverage` deve nominarla nell'upsert
(`tieni('applica_detrazione', false)`), o il ☑️/🚫 toglierebbe da sé una deduzione appena chiesta.

⚠️ **Gli scaglioni sono una costante scritta in `finanza.html`, non un dato**: cambiando la legge
si cambia quella riga. Sono quelli in vigore dal 2025, e la pagina li scrive accanto al numero come
fa col 27 % del TFR. ⚠️ **Non toglie le addizionali** regionale e comunale né le detrazioni da
lavoro dipendente: è il risparmio sulla sola IRPEF, ed è lo stesso caveat del «Reddito netto»
calcolato in 💶 Reddito.

⚠️ **Spunta accesa e conto impossibile non è «al lordo in silenzio»**: senza un anno pieno in
estratto conto, o senza un importo, la riga lo **dice** e la voce resta al lordo — è la stessa
scelta di `assetTax().motivo`, e `coverageDetrazione` torna un oggetto col solo `motivo` invece di
`null`, perché «non l'ho chiesto» e «l'ho chiesto e non si può» sono due cose diverse.

⚠️ **«Pensione Ada» è `auto` e non `asset`**: si legge da 💶 Reddito, riquadro *Redditi*, riga
**Pensione INPS** dell'anno più recente **che ce l'ha** — non dell'ultimo anno in tabella, o un
anno appena aperto e ancora da compilare azzererebbe la voce in silenzio.

⚠️ **È un importo ANNUO, e la dotazione è quanto se ne incasserà in tutto**: si moltiplica per gli
anni che mancano alla fine del **corso regolare** di Ada, l'ultimo in cui quella pensione spetta a
un figlio studente. Sono due durate diverse in `adaScuola()` e non è una svista: `anniUniversita`
(7) è il mantenimento agli studi che decide il **fabbisogno** — laurea più specializzazione —
mentre `anniCorsoRegolare` (3+2) è la durata legale del corso, che decide fino a quando la
pensione si incassa (inizio + 5). Usarne una sola sbaglierebbe per eccesso l'una o
per difetto l'altra. La fine si **ricava** e non si scrive: un anno messo a mano fra due anni
direbbe ancora lo stesso anno. A corso finito la voce vale zero — che è quello che sarà.

⚠️ **Il conto è a MESI e non ad anni interi** (v1.21.1, `mesiFra(coverageOggi(), aCorsoRegolare)`,
lo stesso metro della 🎓 università): fino alla v1.21.0 era `fine del corso − anno in corso`, che si rifà a
mente ma lasciava la voce **ferma per undici mesi su dodici** — una dotazione che non risponde alla
⏳ barra accanto a un fabbisogno che risponde, e la scopertura si muoveva a scalini annuali. Il
mese parziale entra per la sua frazione, e l'etichetta scrive i mesi che restano (`mesiTxt`), che è
il modo di rifare il conto a mente adesso. ⚠️ Ne discende che `coverageAnno()` **non esiste più**:
la data di osservazione si legge in un modo solo.

⚠️ **Il fabbisogno ha quattro colonne, le 🧰 Dotazioni UNA SOLA** (v1.18.0): quello che servirà
dipende da quando si smette di lavorare, quello con cui ci si arriva no. La colonna si etichetta
*Valore oggi* — o *Valore a &lt;mese&gt;* quando la ⏳ macchina del tempo è mossa, perché è a quella
data che quel numero è scritto — e vive in `coverageColonnaOggi()`, che **non è uno scenario ma un
istante**: `anni` vale `null`, così una riga con `periodicity` annua rimasta da un salvataggio
vecchio darebbe `null` e non zero, come ogni flusso senza orizzonte.

⚠️ **Fino alla v1.17.0 le colonne erano quattro anche qui**, e la ragione era una voce sola:
🤝 «Accordo con azienda», l'unica che valesse diverso da una colonna all'altra. Per ogni altra
voce quelle quattro colonne ripetevano quattro volte lo stesso numero — una tabella larga che non
diceva niente più di una cella — quindi **l'accordo si è spostato nel riepilogo** (v1.19.0), dove
le quattro colonne ci sono già, invece di tenerne in piedi altre quattro qui per lui solo.

### 🤝 Accordo con azienda — una riga del riepilogo, non una voce delle Dotazioni

Quello che l'azienda metterebbe per l'uscita: la retribuzione di un anno per gli anni che si
riesce a farsi riconoscere. Sta nel riquadro 📅 *Quanto manca, e per quando*, **fra Dotazioni e
Scopertura**, e vive in `coverageAccordoAzienda(sc)` / `coverageAccordo(sc)` — non in
`COVERAGE_ITEMS` e non in `fnz_coverage_items`.

| Ingrediente | Da dove |
|---|---|
| Retribuzione di un anno | 💶 Reddito, riquadro *Redditi*, riga **UniCredit lordo** dell'anno più recente **che ce l'ha** — stessa regola di «Pensione Ada» |
| Netto | − `TAX_TFR_SEPARATA` (27 %): l'incentivo all'esodo è tassato a tassazione **separata**, non con l'IRPEF della busta |
| Anni | `accordoAnni()`, **uno per scenario** (di partenza 0 · 3 · 0 · 0), in `cm_settings` chiave `fnz_accordo_azienda_anni` |

⚠️ **Entra nella scopertura e NON nel capitale da assicurare**: la scopertura dice quanto manca al
netto di quel che si incasserebbe, quindi l'accordo si somma alle dotazioni prima di sottrarle al
fabbisogno; il capitale copre il fabbisogno per intero perché **la polizza paga comunque e un
accordo da trattare no** — è la stessa prudenza per cui nemmeno TFR, case e portafogli si
sottraggono.

⚠️ **Gli anni si scrivono per colonna e non si ricavano dalle durate di `uscita()`**: legarli alla
loro somma scriverebbe lo stesso importo in tutte le colonne, che è l'opposto di quel che la riga
esiste per mostrare. **Zero è una risposta buona**, non un dato mancante: nelle due colonne della
pensione — e nella 🌿 uscita naturale, dove non si è trattato niente — dice «qui l'azienda non
mette niente», e la cella lo scrive. Resta comunque **modificabile dal ✎**, che sta **sulla riga
dell'accordo** (`edit-accordo` → `openAccordoModal`) e apre una casella per colonna: è accanto ai
numeri che cambia, non nell'intestazione di una colonna sola.

⚠️ **Il valore si rivaluta in `coverageAccordo` e non in `coverageBase`**: non è una riga di
`fnz_coverage_items`, quindi non ha un importo scritto a mano da coprire né una periodicità da
leggere — è la stessa strada di 💼 Redditi da lavoro perso.

⚠️ Il 27 % è la **stessa stima dichiarata** del TFR (vedi il regime fiscale degli asset), con lo
stesso caveat: l'aliquota vera dipende dal reddito di riferimento degli ultimi cinque anni.
L'etichetta della riga la scrive accanto al numero, insieme all'anno del reddito da cui viene.

⚠️ **La sua vecchia riga `accordo_azienda` di `fnz_coverage_items` resta in archivio e non la
legge più nessuno**: un importo scritto a mano lì dentro non copre più niente. Gli anni per
scenario invece si leggono ancora, dalla stessa chiave `cm_settings`.

⚠️ Il form di una dotazione **non chiede la periodicità**: una dotazione è quello che c'è oggi e
la sua colonna non ha un orizzonte su cui moltiplicare, quindi `annuo` lì darebbe `null` e basta.
Un importo **scritto a mano** resta un override come su tutte le altre voci, col badge che dice
*al posto di X* — ora che la colonna è una sola, X è una cifra e non quattro.

⚠️ **L'asset si collega per id e non si indovina dal titolo**: una voce agganciata al nome
smetterebbe di leggere il giorno che qualcuno rinomina «Casa Rosa» in «Casa di Rosa», e lo
farebbe **in silenzio** — il totale scenderebbe senza che niente lo dica. `linked_other_asset_id`
è `ON DELETE SET NULL`: cancellato l'asset la voce resta e torna a chiedere un importo, invece di
sparire dal totale.

⚠️ **`periodicity` decide se l'importo è un capitale o un flusso**, e le colonne del database sono
**nullable di proposito**: `NULL` significa «vale quello che dice `COVERAGE_ITEMS`». Un DEFAULT
scritto nel database congelerebbe lì una scelta che vive nella pagina, e cambiarla nella pagina
non avrebbe più effetto sulle righe già salvate — che è il modo più silenzioso di far divergere
le due. Per togliere la rivalutazione si scrive **0**, che è una cosa diversa da «non l'ho deciso
io».

| `periodicity` | Come si conta |
|---|---|
| `una_tantum` | Un capitale: **stesso importo** su tutti gli orizzonti |
| `annuo` | Un flusso: **importo × anni** che mancano alla data (`sommaFlusso`) |

⚠️ **Tutto è al valore della DATA DI OSSERVAZIONE** — cioè in euro di oggi finché la ⏳ macchina
del tempo sta ferma su «oggi», che è il caso normale: un flusso è importo per anni, senza
rivalutazione composta lungo la sua durata. Le dotazioni sono in euro di oggi per costruzione — sono quello che c'è — e
rivalutare il solo fabbisogno metterebbe a confronto due grandezze che non si possono sottrarre.
Un domani più caro si tiene in conto scrivendo un importo annuo più alto: una scelta visibile nel
form, non un moltiplicatore nascosto nel codice. Fino alla v1.4.0 il flusso si rivalutava di una
percentuale composta, e il fabbisogno usciva in euro del giorno in cui sarebbe stato speso.
⚠️ L'ultimo anno parziale entra per la sua frazione: fermarsi all'anno intero perderebbe fino a
undici mesi di spesa.

⚠️ La colonna **`revaluation_pct` esiste ancora in tabella e non la legge più nessuno**
(`20260901160000_...`): va tolta con una migration quando si tocca di nuovo questa parte — una
colonna che nessuno riempie è un invito a reimplementare la rivalutazione una seconda volta.
⚠️ La rivalutazione della ⏳ macchina del tempo **non è lei**: è una percentuale sola per tutta la
pagina, in `cm_settings` chiave `fnz_rivalutazione`.

⚠️ **Dell'assicurazione ci sono DUE numeri, e sono grandezze diverse.** Il **premio** è una voce
del fabbisogno come le altre — un costo annuo, moltiplicato per gli anni che mancano — e sta in
fondo all'elenco, `periodicita: 'annuo'`, `fonte: 'manuale'`. Il **capitale** è il parametro del
contratto, si calcola (`coverageCapitale`) e vive nel riepilogo, non nell'elenco: sommarlo al
fabbisogno sarebbe come sommare l'affitto al prezzo della casa.

⚠️ Il capitale copre **tutte le voci di fabbisogno che stanno sopra, premio escluso**, e **le
dotazioni non si sottraggono**: è la scelta prudente, perché TFR, case e portafogli il giorno che
servissero potrebbero non essere né liquidi né disponibili, mentre la polizza paga comunque.
Quanto manca al netto di quello che c'è già lo dice la riga **Scopertura**, due righe più su: le
due domande convivono nel riepilogo invece di essere schiacciate in un numero solo. La voce
dell'assicurazione va **per ultima** in `COVERAGE_ITEMS` e non è un caso — il capitale è la somma
di quelle che la precedono.

⚠️ **Le dotazioni non si proiettano da sé**: un TFR o un portafoglio proiettati sarebbero un
rendimento inventato messo accanto a numeri veri — ed è la ragione per cui il loro riquadro ha
una colonna sola. Spostando la ⏳ macchina del tempo si rivalutano anche loro —
ma è una scelta **visibile**, fatta muovendo una barra e con la percentuale scritta accanto, non
un moltiplicatore che gira di nascosto.

⚠️ **Le dotazioni sono al NETTO delle imposte**, ed è l'unico posto in Finanza dove il netto
prende il posto del lordo invece di affiancarlo: una dotazione è quello con cui ci si arriva
davvero, e l'imposta si paga vendendo — cioè proprio nel momento in cui quella dotazione
servirebbe. Vale per «Finanza» (`coverageFinanza`, somma dei `netValue` dei portafogli) e per le
voci collegate a un asset, che passano da `assetTax()` (vedi il regime fiscale degli asset).
L'etichetta della riga porta sempre il lordo accanto, e quando l'imposta non si può stimare lo
dice invece di tacere.

⚠️ **Tutta la catena del calcolo passa dallo SCENARIO e non dai soli anni che mancano**
(`coverageValoreA(item, sc)`, `coverageTotaleA(side, sc)`, `coverageCapitale(sc)`,
`coverageAuto(item, sc)`): oggi tutte le **voci** guardano il solo `sc.anni`, ma una fonte che
deve distinguere **quale colonna** si sta disegnando sul filo dei soli anni non potrebbe farlo —
è il caso di 🤝 «Accordo con azienda», e la ragione per cui non è una voce ma una riga del
riepilogo. Passare lo scenario intero non costa niente e tiene la porta aperta. Alle **dotazioni**
si passa invece la colonna unica di `coverageColonnaOggi()`, che ha la stessa forma: sono quello
che c'è oggi, uguale in ogni piano.

⚠️ **Una voce si può togliere dal conto senza cancellarla** (`excluded`,
`20260902110000_...`): il ☑️/🚫 accanto al ✎ la spegne e la riaccende, su tutte le voci — sia
fabbisogno sia dotazioni. Una voce esclusa **resta a schermo**, sbiadita e con gli importi
barrati, e sparisce dai totali, dalla scopertura e dal capitale da assicurare; non si conta
nemmeno fra le «da compilare», perché un importo che non entra nel conto non è un importo che
manca. Sparire sarebbe il modo peggiore di escluderla — un totale più basso senza niente che
dica perché — e **zero direbbe un'altra cosa**: «questa dotazione non c'è» invece di «c'è ma
non la conto». È il terzo stato accanto ad `amount` NULL, e sono tre cose diverse.

⚠️ Il flag sta **sulla riga della voce** e non in `cm_settings`: è una proprietà di quella
voce, e lontano da lei un'esclusione sopravvivrebbe alla voce che descrive. ⚠️ `saveCoverage`
deve nominarlo nell'upsert (`tieni('excluded', false)`), o **il ✎ rimetterebbe nel conto una
voce appena tolta**: l'upsert riscrive la riga intera e quel che non si nomina torna al DEFAULT.
Per la stessa ragione il prompt 💬 dell'assicurazione parte da `coverageVociAttive()`: deve
contenere gli stessi numeri che la pagina mostra.

⚠️ **`amount` NULL non è zero**, ed è la stessa scelta di `fnz_income` e delle misure di Memo. Su
una voce manuale dice «non l'ho ancora scritto»; su una automatica dice «vale il dato di Finanza»
— l'importo scritto a mano è un **override** che vince, si vede col badge *scritto a mano* accanto
al dato che ha coperto, e si toglie col ↺. Un override silenzioso resterebbe fermo mentre il dato
vero cambia sotto. Le voci senza valore si **contano accanto al totale** invece di sparirci dentro.

**Quando comincia l'università di Ada si ricava, non si scrive**: `adaScuola()` dice classe e anno
scolastico in corso e `adaUniversita()` ne ricava inizio, fine, anni che mancano e le **due date in ISO** (`da`/`a`) su cui la ⏳ macchina del tempo
conta i mesi già spesi. Scritto a mano, fra due anni direbbe ancora lo stesso anno senza che niente lo
segnali.

⚠️ **Anno scolastico e classe NON stanno nel codice** (v1.27.4): la repo è pubblica e quei due
numeri dicono l'età di Ada. Vivono in `cm_settings`, chiave **`ada_scuola`** (JSON
`{anno_scolastico, classe, anni_universita, anni_corso_regolare}`), letta da `loadUscita()` con le
altre, e si scrivono dal **🎓✎** sulla voce dell'università (`openAdaModal`). ⚠️ **Senza anno o
classe non c'è nessun ripiego**: università e pensione di Ada restano senza date e lo dicono
(`ADA_MANCA`) — un valore di partenza scritto nella pagina rimetterebbe il dato nella repo. Le due
durate invece ripiegano su 7 e 5 (`ADA_DEFAULT`), che sono il piano e non dicono niente di lei;
una casella vuota toglie il campo invece di salvare zero. È il posto anche per i prossimi dati
personali che oggi stessero scritti in una pagina: una chiave in `cm_settings`, non una costante.

I due 💬 (università di Ada, assicurazione) aprono un popup col **prompt già scritto** da
incollare in una chat con l'IA. Quello dell'assicurazione ci mette dentro i numeri che la pagina
già conosce — le quattro date, il fabbisogno voce per voce **su ciascun orizzonte**, il
capitale da assicurare, le dotazioni, le scoperture — perché riscriverli a memoria è il modo più
semplice di far ragionare l'IA su cifre sbagliate, e chiede come prima cosa **il premio annuo**,
che è il numero che serve per compilare la voce. ⚠️ La voce dell'assicurazione **resta fuori dal
proprio prompt**: il premio è quello che si sta chiedendo, e il capitale è la somma delle altre.

⚠️ **💼 Redditi da lavoro perso è un terzo riquadro, e sta FUORI da ogni conto.** Sotto
Dotazioni, con le **quattro colonne** del fabbisogno — non con la colonna unica delle dotazioni,
perché la domanda a cui risponde è proprio «fino a quale data»: quanto si continuerebbe a
guadagnare lavorando fino a ciascuna. Non entra nel totale delle dotazioni, né nella scopertura,
né nel capitale da assicurare — sommarlo conterebbe due volte l'🤝 accordo con l'azienda, che
quello stesso periodo lo copre già dalla sua parte. È un metro accanto agli altri, non una voce di
`fnz_coverage_items`: non si compila, non si esclude e non si scrive a mano.

Il netto di un anno è 💶 Reddito → 🧾 Liquidazione, colonna **calcolata** *Reddito netto*
(`INCOME_CALC.reddito_netto`, imponibile − imposta netta) dell'anno più recente **che ce l'ha** —
stessa regola di «Pensione Ada».

⚠️ **Le quattro colonne non rispondono tutte alla stessa domanda**, ed è voluto: sulle due uscite
si conta quello che **manca** rispetto a lavorare fino alla pensione, sulle due pensioni il netto
per gli anni che restano da lavorare.

| Colonna | Conto | Con netto 50.000 e aspettativa 1 anno |
|---|---|---|
| 🌿 Uscita naturale | `20 % × 5 anni di scivolo` | 50.000 |
| 🧭 Uscita concordata | lo stesso, **più** `uscita().aspettativa` a reddito intero | 100.000 |
| 🚪 Anticipata · 🏛️ Vecchiaia | `netto × sc.anni` | 9,4 anni → 470.000 |

⚠️ **La percentuale è quella che si PERDE, non quella che si prende**: durante lo scivolo
l'azienda paga l'80 %, quindi ne manca il 20 — `SCIVOLO_PERSO_PCT = 20`. Scritta al contrario
darebbe un numero quattro volte più grande e altrettanto plausibile.

⚠️ **Il garden leave NON entra**: quello l'azienda lo paga per intero, quindi non è reddito
perso. L'aspettativa sì, è non retribuita, e vale il netto pieno — sono due cose diverse proprio
come le tiene distinte `uscita()`, e schiacciarle in «tutti gli anni oltre i cinque» conterebbe
come persa una retribuzione che invece si prende.

⚠️ **Le due uscite non dipendono dalla data ma dalle durate**, quindi il conto si fa anche senza
la simulazione INPS; le due colonne della pensione sì, e senza `sc.anni` tornano `null` — non
zero — come i flussi del fabbisogno.

⚠️ **I 5 anni sono gli STESSI di `USCITA_NATURALE_ANNI`** e non una seconda costante uguale per
caso: l'uscita naturale *è* l'anticipata meno lo scivolo, e la concordata quello scivolo se lo
porta dentro. Uno `SCIVOLO_ANNI = 5` scritto accanto sarebbero due verità sullo stesso dato.

⚠️ **Sotto ogni importo la cella scrive da quali anni viene** (`notaRedditoLavoro`): «5 anni di
scivolo al 20 % + 1 di aspettativa al 100 %». Un numero che non si può rifare a mente è un numero
di cui non ci si fida.

⚠️ **Il netto si legge da `INCOME_CALC` e non si riscrive qui**: una seconda formula per lo
stesso numero sono due redditi diversi il giorno che una delle due cambia. Ne eredita anche il
caveat — **non toglie le addizionali** regionale e comunale — e la cella lo dice. Senza
imponibile o imposta netta il riquadro non inventa niente: dice dove compilarli.

⚠️ **Le voci NON sono una tabella, sono schede** (`.cov-item` + `.cov-vals`): tre colonne di
importi accanto a una voce che è prosa, coi caratteri di sistema grandi, danno righe alte mezzo
schermo col testo oltre il bordo destro — provato e ritirato. I tre importi sono una griglia
etichettata che **va a capo da sé** (`auto-fit` con la soglia in `rem`, quindi cresce col testo)
invece di scorrere di lato: un numero oltre il bordo è un numero che non si legge. È la stessa
scelta della tabella di «Ti pisasti?» in nativo. Il **riepilogo** resta una tabella — poche righe
ed etichette corte — con l'anno in intestazione e il nome breve sotto.

### Memorandum (`mm_`)
| Table | Purpose |
|---|---|
| `mm_cards` | Le schede. `kind` vale `'nota'`, `'lista'`, `'diario'`, `'link'` o `'premiato'`; `punteggio` sono i punti di un 🏅 premiato (0 su ogni altro tipo); `riservato` le tiene fuori dagli elenchi |
| `mm_attachments` | Quel che una scheda porta con sé: oggi il solo indirizzo di un 🔗 Link (`tipo` `'link'`\|`'file'`, `url`, `position`) |
| `mm_card_categories` | Associazione scheda ↔ `cm_categories` |
| `mm_images` | Metadati delle foto (i file stanno nel bucket `mm-images`) |
| `mm_list_items` | Voci di una lista: `text`, `done`, `position`, `done_at` |
| `mm_diary_metrics` | Le misure di un diario: `kind` `'scala'` (con `min_value`/`max_value`) \| `'numero'` (con `unit`) \| `'bool'` \| `'scelta'` (con `options`); `hint` spiega cosa vuol dire il punteggio |
| `mm_diary_entries` | Le registrazioni: `title` (obbligatorio lato app), `entry_date`, `note`, e `measures` jsonb |

**Una scheda è una riga sola in tutt'e cinque i casi**: il tipo è una colonna, non una tabella a
parte, quindi ricerca, categorie, colore, 📌 e foto valgono uguale per note, liste, diari, link e
premiati. `kind` ha `DEFAULT 'nota'`: le schede nate prima della colonna restano quello che erano.

### 🏅 Premiati — l'unico tipo che fa PUNTI

Una scheda `premiato` è **una nota più un numero** (`20260913100000_mm_cards_premiato_punteggio.sql`):
stesso editor, stessa formattazione, stesse foto, più la casella *Punteggio*. La **somma dei
punteggi** è il numero della bolla di Memo in AppSphere e **si aggiunge al totale che paga i
premi** — è l'unico numero di Memo che sia un punteggio, e prima di lei la bolla contava le schede.

⚠️ **Cambiare la `score_query` di Memo da conteggio a punteggio voleva dire mettere a posto anche
`cm_apps.conta_punti`** della stessa riga — oggi è la stessa `UPDATE`. Fino al 24 settembre 2026
«sono punti?» stava in tre elenchi a mano (`index.html`, `home/PortedApps.kt`,
`scripts/backup-report.mjs`), e quella modifica andava fatta in quattro posti.

⚠️ **`punteggio` è `NOT NULL DEFAULT 0` e sta su `mm_cards`**, non su una tabella a parte: è un
numero per scheda, e una riga in più da leggere sarebbe una join per un intero. È la scelta
opposta a `amount` NULL di `fnz_income` ed è voluta: qui «non l'ho ancora deciso» e «vale zero»
sono la stessa cosa — una scheda premiato senza punti non muove il totale, e basta guardarla per
vederlo (il badge dei punti sulla scheda diventa grigio).

⚠️ **Il punteggio si riscrive a ogni salvataggio, 0 fuori dai premiati**: l'update di `saveCard`
riscrive la riga intera, e una scheda che cambiasse tipo si porterebbe dietro punti che nessuno
vede più — è la stessa ragione per cui `saveCoverage` deve nominare `excluded` nel suo upsert.

⚠️ **Il `COALESCE` nella `score_query` non è prudenza generica**: senza nessun premiato la `SUM`
torna NULL, e `run_score_query` si aspetta un intero — la bolla sparirebbe invece di restare al
minimo.

⚠️ **Il totale in cima alla Tab e quello della bolla sono due conti dello stesso numero**, e non è
una divergenza: `totalePunti()` in `memo.html` somma le schede già caricate perché il titolo lo
mostri subito, la `score_query` lo chiede al database. Conta **tutti** i premiati e non quelli
filtrati — una ricerca in corso non deve cambiare il totale, o quel numero direbbe una cosa diversa
da quella che si legge in home.

⚠️ **Esistono anche in nativo** (APK v1.0.80): `TipoScheda.PREMIATO`, la Tab 🏅 Premiati, la
casella *Punteggio* nel form e i punti sulla scheda. Per una giornata sono stati **solo nel web**, e
non sono diventati note per sbaglio: `TipoScheda.daONull()` torna `null` su un kind che non conosce
e la scheda **non viene proprio caricata** — è la guardia che aveva già coperto i 🔗 link, e che
coprirà il prossimo tipo nuovo. La bolla della home nativa mostrava già la somma dei punti anche
allora: quel numero lo dà la `score_query`, non l'app.

⚠️ **Le regole da tenere allineate fra le due implementazioni sono tre**, e sono tutte sul
punteggio: si scrive **sempre** e vale 0 fuori dai premiati (`puntiDaSalvare` /
`MemoRepository.salva`, gemelli del `payload.punteggio` di `saveCard()`); la casella **vuota vale
0** ma una parola scritta lì dentro **non diventa zero in silenzio**; e il totale conta **tutti** i
premiati e non quelli filtrati (`MemoState.totalePunti` / `totalePunti()`).

⚠️ **Nel nativo anche il 📌 riscrive la riga intera** (`cambiaFissata` chiama `salva`), quindi deve
rimandare indietro `scheda.punteggio`: senza, fissare un premiato ne azzererebbe i punti — cioè
farebbe calare la bolla in home per un gesto che con quel numero non c'entra niente. Nel web il 📌
passa da un `update` del solo campo e il caso non si pone.

### 🔗 Link — la scheda che nasce dal tasto «Condividi»

Una scheda `link` è **UNA cosa condivisa** (un video, un articolo), non una raccolta: raccogliere
lo fanno già categorie, ricerca e 📌, che valgono per tutti i kind, e una scheda contenitore
reimplementerebbe la lista. È l'unico tipo con un **campo obbligatorio** — senza indirizzo non
porta da nessuna parte, cioè è rotta, non vuota.

⚠️ **In `mm_attachments` c'è l'URL e basta.** Id del video, miniatura e nome del sito si
**ricavano** da lui ogni volta (`ytIdDa`, `thumbDa`, `hostDa` in `memo.html`): archiviarli sarebbe
una seconda verità sullo stesso dato, che diverge il giorno che una delle due cambia. È la stessa
scelta delle colonne calcolate della liquidazione in `fnz_income`.

⚠️ **La copertina di YouTube non costa niente**: è un url pubblico di `img.youtube.com`, quindi
nessuna API, nessuna chiave e **nessun byte nel bucket** — mille link condivisi stanno in qualche
decina di KB di tabella. Si usa `hqdefault`, che c'è su ogni video: `maxresdefault` manca sui video
vecchi e darebbe un riquadro rotto. Un link che una copertina non ce l'ha mostra il nome del sito,
non un'anteprima inventata.

⚠️ **Nessuna colonna per Drive** (`storage_path`, `mime`, `size_bytes`): oggi non le riempirebbe
nessuno, e una colonna che nessuno riempie è un invito a reimplementare due volte la stessa cosa —
le aggiunge la migration dei file, quando ci saranno i file. `tipo` ammette già `'file'` proprio
perché quel giorno non si debba toccare anche il vincolo.

#### La condivisione arriva dall'APK NATIVA, e da lì soltanto

`com.garsal.appsphere` dichiara `ACTION_SEND` + `text/plain`: `gestisciCondivisione()` in
`MainActivity.kt` mette testo e oggetto in `core/Condivisione.kt`, la navigazione porta a Memo, e
`MemoScreen` apre l'editor con una scheda 🔗 Link **già compilata**
(`BozzaScheda.daCondivisione`).

⚠️ **L'intent-filter lo dichiara UNA sola APK.** I due APK convivono sullo stesso telefono con lo
stesso logo, distinti solo dal fondo bianco/nero: dichiarandolo anche in `com.garsalapps`, nel
menù «Condividi» comparirebbero **due voci indistinguibili** e toccherebbe indovinare ogni volta. È
la stessa ragione per cui hanno due schemi OAuth diversi.

⚠️ **La voce si è SPOSTATA, non aggiunta** (nativa v1.0.69, WebView v1.1.1). Fino alla v1.1.0 la
riceveva l'APK WebView — `handleSharedText` apriva `memo.html`, che con `openMemoFromSharedLink()`
compilava la scheda — e la ragione dello spostamento è **l'interfaccia**: quella scheda si
compilava dentro un WebView, in una pagina pensata per il PC. Nel WebView non ci sono più né
l'intent-filter né `handleSharedText` né l'iniezione in `onPageFinished`, e in `memo.html` al
posto di `openMemoFromSharedLink()` (e di `primoUrlIn`) c'è il commento che dice dov'è finita.
⚠️ Non sono due strade che convivono: aggiungerne indietro una qualsiasi rimette le due voci
indistinguibili nel menù «Condividi».

⚠️ **Fra il tocco su «Salva in Memo» e la scheda ci stanno la biometrica e, la prima volta, il
login nel browser.** Per questo la condivisione vive in un oggetto in memoria e non nell'intent:
`getIntent()` lo restituirebbe a ogni ricreazione dell'Activity, riaprendo la stessa scheda giorni
dopo senza che nessuno abbia condiviso niente — è la stessa ragione per cui il deep link dell'OAuth
si azzera appena letto. E per la stessa ragione **non sta nelle preferenze**: sopravviverebbe al
riavvio dell'app, che è l'opposto di quel che serve.

⚠️ **Il titolo arriva da `EXTRA_SUBJECT`**, che YouTube riempie col titolo del video: è già pronto,
non costa nessuna chiamata di rete e funziona anche offline.

**Quando quel campo non c'è, il titolo si ricava dal link**, in quattro gradini:

| Gradino | Da dove | Quando |
|---|---|---|
| `EXTRA_SUBJECT` | l'app che condivide | quando c'è: è esatto e gratis |
| `titoloYouTube()` | oEmbed di YouTube | solo sui link YouTube, nessuna chiave né API |
| `titoloDaSlug()` | l'indirizzo stesso | tutto il resto, senza rete |
| `hostDa()` / `Link.sito()` | il nome del sito | ultima spiaggia |

⚠️ **La scaletta esiste in due posti e va cambiata in tutt'e due**: `Link.titolo()` in
`memo/MemoData.kt`, per la condivisione, e `riempiTitoloSeVuoto()` in `memo.html`, dove il caso che
conta non è la condivisione ma il **link incollato a mano dal PC** — lì `EXTRA_SUBJECT` non esiste
affatto, e senza questi gradini ogni video si chiamerebbe «youtu.be».

⚠️ **Non sovrascrive mai un titolo che c'è**, né quello scritto a mano né `EXTRA_SUBJECT`: un
ripiego che prende il posto del dato esatto è un peggioramento silenzioso.

⚠️ **Nel web è in due tempi, in nativo no, e non è una divergenza**: là il campo è già a schermo
mentre si scrive l'url, quindi prima lo slug (immediato) e poi l'oEmbed che rimpiazza il
provvisorio — e solo **se il campo porta ancora esattamente quel provvisorio**, perché una risposta
che arriva dopo un cambio di url metterebbe il titolo di un altro video (`titoloToken`). Qui la
scheda si apre **già compilata**, quindi si aspetta la risposta *prima* di aprirla — e solo nel
caso raro in cui l'oggetto manchi *e* il link sia di YouTube. Il risultato è lo stesso, l'ordine
dei gradini pure.

⚠️ **`titoloYouTube` fallisce in silenzio.** Senza rete, o il giorno che quell'endpoint chiudesse,
la scheda nasce lo stesso col ripiego: un titolo è una comodità, non una condizione. In nativo i
tetti di attesa stanno **sulla connessione** e non solo su `withTimeoutOrNull`: una lettura
bloccante non si annulla, e senza di loro l'attesa la deciderebbe il socket — con la scheda che non
si apre intanto.

⚠️ **Uno slug non è un titolo**, ed è per questo che viene *dopo* gli altri due: è quel che il sito
ha scritto nell'indirizzo per i motori di ricerca, spesso troncato. Su YouTube non si usa affatto —
lì lo slug è l'id del video (`jNQXAC9IVRw`), che come titolo è peggio del nome del sito. La pulizia
dell'id in coda vuole **almeno tre cifre**: senza quella soglia «Covid-19» diventerebbe «Covid».

⚠️ **La scheda non si salva da sola**: si apre l'editor già compilato e il salvataggio resta un
gesto. Il tasto «Condividi» sta accanto a mille altri, e una condivisione per sbaglio non deve
lasciare una riga che poi qualcuno deve andare a cercare per cancellarla. Un testo condiviso **senza
url** non si butta via: diventa una nota, e il form lo dice (`avvisoIniziale`) invece di lasciarlo
scoprire.

⚠️ **Il punto nel nome del sito è il controllo che conta** (`Link.valido`, `urlValido` nel web):
senza, `URI` accetta `https://ciao` come indirizzo validissimo, e una parola secca condivisa da
un'altra app diventerebbe una scheda link che non porta da nessuna parte, invece della nota che è.

⚠️ **Una condivisione arrivata a form aperto ha bisogno della `key`** in `MemoScreen`: `MemoForm`
tiene la bozza in un `remember` senza chiave, quindi senza di lei resterebbe a schermo quel che si
stava scrivendo e la scheda condivisa non si vedrebbe affatto.

#### I link ci sono anche in nativo

`memo/` legge `mm_attachments` e ha `TipoScheda.LINK`: la Tab 🔗 Link, la copertina sulla scheda in
elenco, la vista propria col pulsante *▶️ Apri* e il campo **Indirizzo** nel form. Le regole
duplicate da tenere allineate col web sono tre, e sono le stesse che valgono di là:

- **dall'url non si archivia nient'altro**: `Link.idYouTube` / `Link.copertina` / `Link.sito`
  in `MemoData.kt` sono i gemelli di `ytIdDa` / `thumbDa` / `hostDa` in `memo.html`, e vanno
  cambiati insieme;
- **una scheda = un allegato, aggiornato e non ricreato**: `MemoRepository.salvaLink` ricalca
  `syncLinkAttachment()` — il giorno che gli allegati saranno più d'uno, `position` deve
  sopravvivere al salvataggio;
- **un link è il suo indirizzo**: `BozzaScheda.linkValido` è l'unico campo obbligatorio di tutto
  Memo, col **punto nel nome del sito** come controllo che conta — senza, «ciao» passerebbe per un
  indirizzo validissimo. Il controllo però sta in `Link.valido` e `linkValido` ci si appoggia: la
  regola è una sola, e la usa anche la condivisione per decidere se quel che è arrivato è un link
  o una nota. Stessa regola di `urlValido` nel web.

⚠️ **`TipoScheda.daONull()` resta anche adesso che nessun tipo è sconosciuto**, e non è codice
morto: distingue «tipo che non conosco» da «tipo che non c'è». Sul primo torna `null` e la scheda
**non viene proprio caricata**; chiave assente o vuota resta `NOTA`, che è il `DEFAULT` del
database e sono le schede nate prima della colonna. È la guardia che ha impedito alle schede
`'link'`, prima che il nativo le conoscesse, di comparire come note senza indirizzo e di
**diventarlo davvero** al primo salvataggio — e che farà lo stesso col prossimo tipo nuovo.

⚠️ **La condivisione è di questa APK** (vedi sopra): l'intent-filter `ACTION_SEND` + `text/plain`
lo dichiara solo `com.garsal.appsphere`, e da lì nasce una scheda 🔗 Link già compilata. Il WebView
i link li mostra e basta.

⚠️ **Il titolo ricavato dal link c'è anche di qua, ma solo per la condivisione**: `Link.titoloDaSlug`
e `Link.titoloYouTube` sono i gemelli di `titoloDaSlug()` e `titoloYouTube()` di `memo.html` e vanno
cambiati insieme. **Scrivendo un link a mano nel form il titolo non si compila da sé**: là lo fa
`onLinkUrlInput` con la sua pausa di digitazione, qui il campo resta come lo si scrive — è una
comodità che manca, non una divergenza di dati.

`mm_diary_entries.measures` è `{ "<id della misura>": numero|booleano }`. Le **misure sono righe
vere e i valori no**, ed è voluto: una misura deve sopravvivere alle registrazioni che la citano
(cambiarle nome non deve riscrivere lo storico), mentre i valori si leggono e si scrivono sempre
tutti insieme, quindi una tabella in più sarebbe solo una join in più. Per la stessa ragione le
misure si **aggiornano riga per riga e non si cancellano per ricrearle** (`syncDiaryMetrics`):
ricreandole cambierebbero id e tutte le registrazioni passate resterebbero senza nome.

⚠️ **Una misura non registrata non sta in `measures`**, non ci sta come zero: «non l'ho misurata»
e «vale zero» sono due cose diverse, e uno slider lasciato a metà scriverebbe la seconda al posto
della prima. Nella schermata di registrazione una misura non toccata mostra `—` e non finisce
nell'archivio; il pulsante *Non l'ho misurata* la toglie anche a posteriori. È la stessa scelta
della casella vuota in `fnz_income`.

Togliere una misura da un diario **non cancella le registrazioni**: i valori restano scritti in
`measures` con una chiave che non ha più una riga, e l'app li mostra come *misura tolta* invece di
farli sparire. L'avviso prima di togliere lo dice.

**Le misure `'scelta'` sono combo configurabili** (es. *origine*: sociale, autorità, sbagliare,
giudizio). Le opzioni stanno in `mm_diary_metrics.options` come `[{id, label}]` e la registrazione
archivia l'**id**, mai l'etichetta: rinominare un'opzione deve rileggere anche lo storico col nome
nuovo, mentre archiviando la parola una rinomina spaccherebbe la stessa origine in due categorie
che ai conteggi sembrano diverse. È la stessa ragione per cui le misure si aggiornano riga per
riga invece di essere ricreate, un livello più sotto. Un'opzione tolta segue la regola della
misura tolta: le registrazioni restano e mostrano *opzione tolta*.

Una combo **deve avere almeno un'opzione** (vincolo `mm_diary_metrics_scelta_check`): senza, la
misura non chiederebbe niente — è rotta, non vuota. Le opzioni lasciate in bianco si scartano da
sé al salvataggio.

⚠️ Una scelta **non ha un numero** e `numericValue()` torna `null`: l'ordine delle opzioni è un
elenco, non una scala, e farne una media o una spezzata vorrebbe dire trattare *giudizio* come il
doppio di *autorità*. Nel riepilogo al posto dello storico in miniatura c'è la **distribuzione** —
quante volte è uscita ciascuna opzione — che è la domanda che una combo pone davvero.

### Weight Quest (`ps_`)
| Table | Purpose |
|---|---|
| `ps_weight_tracking` | Weight measurement entries |
| `ps_objectives` | Obiettivi di peso (nata a mano, non sta in nessuna migration) |
| `ps_milestone_prizes` | Il premio cibo grattato per una soglia: `prize_id`, `won_on` e `consumed_on` (il «Mangiato !!!») |
| `ps_milestone_points` | `⭐ Punti Totali Traguardi Intermedi`, una riga per obiettivo |

Le ultime due (`20260824100000_ps_milestone_prizes.sql`) esistono perché premi e punti stavano in
`localStorage` sul web e nelle preferenze del telefono nel nativo: erano **due verità diverse
sulla stessa stellina**. Ora il DB è la fonte di verità e il locale resta cache. `consumed_on` è
l'unica colonna sul «Mangiato !!!» — niente booleano accanto alla data, che sarebbe un secondo modo
di dire la stessa cosa.

### 🧈 La massa grassa (`body_fat_pct`, `use_fat_mass`, `fat_pct`)

(5 ottobre 2026: `20261005140000_ps_massa_grassa.sql`; web v4.8.0, APK WebView v1.2.4.)
**Massa grassa = peso × %grasso / 100.** Il grasso corporeo lo scrive la bilancia (Renpho) in
Health Connect come `BodyFatRecord`, e `HealthConnectBridge` lo legge accanto al peso e lo abbina
alla pesata più vicina entro 10 minuti; dalle pesate manuali si può scrivere la % (facoltativa).

⚠️ **Ha preso il posto del peso a secco**, nato e ritirato lo stesso giorno
(`20261005100000` / `20261005120000`): **Renpho in Health Connect scrive peso e grasso ma NON
l'acqua**, e una categoria che nessuna app scrive in Health Connect non compare nemmeno. La
migration ha tolto `water_kg`, `use_dry_weight` e `water_pct`.

| Colonna | Cosa |
|---|---|
| `ps_weight_tracking.body_fat_pct` | Grasso corporeo (%) di quella pesata. **NULL = non misurato**, non zero |
| `ps_objectives.use_fat_mass` | L'obiettivo conta punti e chiusura sulla massa grassa. `DEFAULT false` |
| `ps_objectives.fat_pct` | La % di grasso **di partenza**, al peso del primo traguardo (3-70). Senza, si conta sul totale |

⚠️ **I traguardi si scrivono in peso TOTALE**, come sempre, e **i chili persi sono tutti grasso**
(web v4.9.0, `20261005160000_ps_massa_grassa_magra_ferma.sql`): la massa magra si ricava dal
primo traguardo — peso × (1 − `fat_pct`/100) — e resta ferma, quindi il grasso previsto a un peso
W è **W − massa magra**. Da 80 kg al 26 % (magra 59,2) a 74 kg: grasso 14,8 kg, cioè il **20 %**.
Fino alla v4.8.1 era peso × % fissa, che a 74 kg avrebbe chiesto ancora il 26 %. Il form mostra
accanto a ogni traguardo grasso previsto e %, e la colonna *Previsto* della tabella il peso totale
di piano e la % sotto il grasso. `target_weight` sulle righe resta il target **totale** congelato
(`targetTotale()` nel web) e il fattore si applica leggendolo.

| Cosa | Su che peso |
|---|---|
| Punti giornalieri, chiusura (minimo di oggi / massimo del periodo) | **massa grassa** contro target × fattore |
| ⭐ Stelline dei traguardi intermedi | **totale** (soglie intere fra peso iniziale e finale) |
| Diario calorie (`al_conto`) | **totale**, come prima |

⚠️ **Si conta la massa grassa in kg e non la %**: la percentuale può scendere senza perdere
grasso (perdendo acqua o muscolo) e salire mentre si dimagrisce. ⚠️ Il grasso di una bilancia a
impedenza **balla** con idratazione e pasti — ±1 punto di % su 90 kg è quasi un chilo — quindi i
punti giornalieri oscillano più che sul peso: conviene pesarsi sempre nelle stesse condizioni.

⚠️ **La formula sta in due posti da cambiare insieme**: `ps_peso_conta`, `ps_massa_magra` e
`ps_grasso_previsto` (usate da `ps_punti` / `ps_chiudi_obiettivo`) nel database, e `massaGrassa()` /
`massaMagra()` / `aGrasso()` in `weight-quest.html`. `getInterpolatedTarget()` restituisce il target **in vista** (in grasso se
serve); per scrivere sulle righe si usa `targetTotale()`.

⚠️ **Sulla massa grassa, una pesata senza grasso NON conta**: per i punti è come non essersi
pesati (ricostruita senza N, malus con N). La pagina la toglie dalla vista con `pesiVista()`.

⚠️ **`pesiVista()` restituisce COPIE e `userData.weights` resta il dato vero**: il dettaglio del
giorno riceve le righe vere, perché modificare una pesata deve riscrivere il peso **totale**; e
la celebrazione delle stelline legge le righe vere, perché le stelline sono sul totale.

⚠️ **Su un obiettivo sulla massa grassa i grafici sono TRE** (web v4.10.0, `updateWeightChart` →
`disegnaGraficoPeso`): ⚖️ peso totale coi traguardi, 🧈 massa grassa (kg) col grasso previsto — il
target dei punti — e 📊 % di grasso con la % prevista dal piano ((W − magra) / W). Stanno uno sotto
l'altro **nello stesso riquadro che scorre**, quindi le date restano in colonna, e lo zoom li allarga
insieme (`larghezzaGrafici`). Il primo (`weightChart`) è quello a cui si agganciano scorrimento e
reset. Senza massa grassa resta il grafico unico di sempre. Nella tabella anche la colonna MAX
porta sotto il totale e la % di quella pesata, come la MIN.

⚠️ **La sincronizzazione fa DUE upsert, con e senza grasso**: PostgREST vuole le stesse chiavi su
tutte le righe, e mandare `body_fat_pct: null` a una pesata letta senza grasso (permesso non
concesso) cancellerebbe il grasso già salvato. Il permesso del grasso non è fatale: senza, le
pesate arrivano lo stesso e la pagina lo dice.

⚠️ **C'è anche nell'APK nativa** (1.0.109): spunta e % nel form (col grasso previsto accanto a
ogni traguardo), % facoltativa nella pesata a mano, lettura di `BodyFatRecord` da Health Connect
(stesso abbinamento entro 10 minuti, stessi due upsert, permesso del grasso mai fatale e chiesto
una volta per sessione), schede e tabella sulla massa grassa con «totale · grasso %» sotto
minimo e massimo e «di X kg · Y %» sotto il previsto, e i tre grafici che scorrono insieme
(`ScalaGrafico`). Le regole sono gemelle e vanno cambiate insieme: `massaMagra` / `aGrasso` /
`massaGrassa` / `pesiVista` / `notaTotale` in `PesoRegole.kt` contro le omonime del web; la
`PesoState.vista` è il `pesiInVista()` di là, e `PesoState.pesate` resta il dato vero.

### 📈 La media mobile esponenziale (`ps_daily_ema`)

(9 ottobre 2026, `20261009100000_ps_media_mobile.sql`, web v4.12.0; dall'APK 1.0.118 la legge anche il
nativo: tabella con prima pesata e medie, grafici con prima pesata + media e legenda.) La bilancia a impedenza balla troppo per dare punti sulla singola pesata. Una
riga per giorno con il valore del giorno — la **prima pesata** della giornata — e le medie mobili
esponenziali di **peso**, **massa grassa (kg)** e **% di grasso**, più il loro trend.

- ⚠️ **La calcola il database e basta**: `ps_ema_ricalcola()` rifà la tabella dell'utente da
  capo, e la chiama un trigger **per istruzione** su `ps_weight_tracking` (un errore lì non fa
  fallire la pesata). La pagina legge e disegna.
- **N** sta in `cm_settings`, chiave `ps_ema_days` (di partenza 7, fra 2 e 90), α = 2/(N+1). Si
  cambia da ⚙️ Impostazioni, che poi chiama `ps_ema_ricalcola`.
- **Un giorno senza pesata si stima col trend** (media + trend, riga `*_stimato`). Un buco di più
  di 14 giorni non si stima: la serie riparte alla pesata dopo.
- La massa grassa media è la **media dei kg giornalieri**, non peso medio × % media.
- Sugli obiettivi **`use_fat_mass`**: i punti dei giorni di pesata si danno sulla **media della
  massa grassa** contro il grasso previsto (giorno passato senza nessun valore = malus); le
  ⭐ stelline sul **peso giornaliero vero** (prima pesata, giorni stimati esclusi); la chiusura
  «perdere» sulla media di oggi, «mantenere» sulla media più alta del periodo. Gli obiettivi sul
  peso totale non cambiano.
- Nei grafici la media è la linea viola e le pesate restano sbiadite.
- **La tabella delle pesate ha tre colonne** (v4.13.0): Data · Misurazioni · Target. Misurazioni
  è una griglia 2×3 (peso · grasso kg · %): sopra la **prima pesata** del giorno, sotto le tre
  medie (viola, in corsivo se stimate). Target porta sotto il totale e la % di piano, poi
  «punti · cumulato». MIN e MAX del giorno non ci sono più. Un giorno di pesata saltato ma con la
  media stimata si legge ○ «media stimata col trend», non «non pesato». I giorni di premio
  (`giornoDiPesata`) hanno il fondo azzurro chiaro, tutti gli altri grigio (v4.13.1). Ogni giorno
  passato senza nessuna pesata ha comunque la sua riga, con medie stimate e target, su fondo **rosa**
  (v4.13.2); oggi no finché non ci si pesa. I giorni futuri (e oggi da pesare) sono bianchi (v4.13.3).
- **La tabella si rifà a ogni pesata nuova o corretta** (v4.13.4): un `loadData()` chiamato mentre
  un altro è in corso non si salta più ma si **ripete appena finisce** (`ricaricaDopo`), e
  `caricaEma` confronta le righe di `ps_daily_ema` con le pesate appena lette (`emaAllineata`):
  se non tornano chiama `ps_ema_ricalcola` una volta e rilegge. Medie prima, punti dopo.

### ⚖️ Pesarsi ogni N giorni, e il promemoria della pesata

`ps_objectives.weigh_every_days` (`20260922100000_ps_objectives_pesata_ogni_n.sql`) dice ogni
quanti giorni ci si pesa. **NULL = la regola di sempre**: punti ogni giorno e giorni senza pesata
ricostruiti. Con N i punti si danno **solo nei giorni fissati** — inizio, inizio+N, … —
(`giornoDiPesata()` in `weight-quest.html`): bonus/malus sul peso di quel giorno, **malus** se il
giorno è passato senza pesata, **zero** in tutti gli altri giorni, anche se ci si pesa. ⚠️ Oggi
senza pesata non è malus: la giornata non è finita. ⚠️ La tabella delle pesate **non ha più una
copia sua** del conto: legge `buildScoreRows()` come badge, cronologia e chiusura.

### ✅ Punti e chiusura sono UNA regola, nel database

(25 settembre 2026, `20260925120000_ps_punti_rpc.sql`.) Il conto dei punti giorno per giorno, i
traguardi intermedi raggiunti e il punteggio di chiusura li danno due RPC, che chiamano **tutt'e
due** le implementazioni — fino a lì erano `buildScoreRows()` / `milestoneAwards()` /
`closeObjective()` nel web e `righePunti` / `puntiTraguardiRaggiunti` / `preparaChiusura` in
Kotlin, due copie da tenere allineate a mano. **Una modifica a quelle regole si fa nel SQL e
basta.**

| Funzione | Cosa fa |
|---|---|
| `ps_punti(p_objective_id, p_oggi)` | Righe giorno per giorno (`date`, `interp`, `missed`, `weight`, `target`, `points`, `cum`), `total`, e i traguardi (`threshold`, `points`, `reached`, `first_date`) con `reached_points` |
| `ps_chiudi_obiettivo(p_objective_id, p_stato, p_oggi, p_conferma)` | I due controlli del successo, poi il conto: giornalieri + traguardi raggiunti (solo col successo) ± bonus/malus finale. Con `p_conferma = false` è l'anteprima della conferma; con `true` scrive `status` e `total_score` |

↩️ **Un obiettivo chiuso si riapre** con `ps_riapri_obiettivo(p_objective_id)`
(`20261003100000_ps_riapri_obiettivo.sql`), chiamata dal pulsante *↩️ Riapri* del web
(`reopenObjective`) e del nativo (`PesoViewModel.riapriObiettivo`): `status` torna `active` e
`total_score` a 0, cioè i punti della chiusura escono dal totale. ⚠️ **Rifiutata se c'è già un
altro obiettivo attivo**: il diario delle calorie legge l'attivo più recente. I promemoria della
pesata li ricrea il client dalle impostazioni rimaste sulla riga.

⚠️ **Le pesate che contano vanno da 90 giorni prima dell'inizio alla FINE**, per un obiettivo
aperto come per uno chiuso: prima il web guardava fin dove aveva caricato, e un obiettivo chiuso
riaperto in tendina poteva contare giorni diversi da quelli del giorno della chiusura. Un
traguardo è **raggiunto** solo con un minimo fra inizio e fine.

⚠️ **I punti dei traguardi si leggono da `ps_milestone_points` salvato**, non dalla casella del
form: una cifra scritta e non salvata non può cambiare un punteggio che finisce in archivio.

⚠️ **«Oggi» lo passa il client** (`p_oggi`), come `p_today` in `task_complete`: il database sta
in UTC. ⚠️ Il **peso ricostruito** esce in `numeric` e non in virgola mobile, quindi può
differire dal vecchio JavaScript di un centesimo; i punti no — verificato su 24 obiettivi, 2153
righe, 15655 confronti: nessuna differenza.

⚠️ **Restano nel client, di proposito**: soglie e distribuzione dei punti della barra di
stelline (anteprima dal vivo mentre si scrive il totale nel form), e la ricostruzione dei giorni
per il **disegno** della tabella e del grafico (`getInterpolatedWeightFromSeries` /
`PesoRegole.tabella`), che però prende punti e cumulativo dalla RPC.

⚠️ **La tabella delle pesate è il CALENDARIO del piano** (v4.2.0): una riga per ogni giorno di
pesata dall'inizio alla **fine** della dieta — senza N, una al giorno — col peso previsto nella
colonna *Previsto*. I giorni futuri (e oggi, finché non ci si pesa) sono 📅 *da fare* e si
riempiono da sé a ogni pesata; con N un giorno passato senza pesata è ✗ *non pesato* col malus;
una pesata vera in un giorno che il piano non prevede compare lo stesso, marcata *fuori
calendario* e con «—» nei punti. ⚠️ **Contiene le sole giornate dell'obiettivo**, dalla data
d'inizio alla fine, **con l'inizio in cima** (v4.5.1 / APK 1.0.105): le pesate prima dell'inizio
o dopo la fine non sono storico di quell'obiettivo. Il filtro è di sola lettura — nel nativo
`PesoRegole.storico`, mentre `tabella()` resta dalla più recente perché la leggono anche grafico e
badge.

⚠️ **I tre riquadri «Minimo oggi / Target oggi / Mancano al target» non ci sono più**: al loro
posto due schede (`renderPesate`) — **oggi + la prossima** se oggi è giorno di pesata, altrimenti
**la precedente + la prossima** — ognuna con minimo, target e quanto manca. Un giorno senza
pesata prende l'**ultimo peso di prima** e lo scrive; la prossima prende l'ultimo peso
registrato. Il target di un giorno pesato è quello **congelato sulla riga**, come nei punti.

⚠️ **Ogni scheda porta anche le calorie** (v4.3.0, `righeCaloriePesata`): *Fabbisogno* (basale ×
attività), *Kcal da piano* (`targetTrattoAl`, il target del tratto «se stai sul piano») e *Kcal con
andamento* (`calcolaTarget` / `targetDelGiorno`, cioè col recupero dello scarto — il numero del
📓 Diario, congelato se `al_days` ce l'ha). **Non si ricalcola niente qui**: si leggono le funzioni
della metà «calorie», e finché quella non ha caricato (`S.pronto`) le righe non compaiono —
`avviaCalorie` ridisegna le schede appena arriva. Sulla prossima pesata il peso è l'ultimo noto.
Niente numeri se l'obiettivo guardato non è quello attivo del diario (`S.objective`).

⚠️ **C'è anche in nativo** (APK 1.0.88), e le regole sono gemelle da cambiare insieme:
`PesoRegole.giornoDiPesata` / `prossimaPesata` / `pesataPrecedente` / `tabella` (il calendario
del piano, coi punti letti da `ps_punti`), le schede
in `PesoState.schede` (`renderPesate` / `righeCaloriePesata`, calorie lette da `CalorieRegole`), il
form in `GestioneObiettivo.kt` (`PesataEPromemoria`, le regole di `leggiCampiPesata()`) e il
promemoria in `PesoRepository.sincronizzaPromemoria` + `entitaPesata`, gemelli di
`syncPromemoriaPesata()` / `pesataEntityId()`. Chiudendo o eliminando dal telefono le regole si
tolgono come dal web. Differenze di forma: le due schede stanno **una sotto l'altra** (coi
caratteri grandi affiancate andrebbero a capo ovunque) e la tabella resta a schede, dalla data
d'inizio in cima.

Il promemoria sono `reminder_enabled`, `reminder_time` (ora di Roma) e `reminder_channels`
(`telegram` | `android` | `smart_block`) sulla riga, più **una regola `cm_notification_rules`
per canale** con `app = 'weight'`, `entity_type = 'task'` (quello che `chk_entity_type` ammette) e
`reminder_presets = {kind:'pesata', every_days, start_date, end_date, time}`.
`fill-notification-queue` ha il ramo `isPesata` (`generatePesataEntries`) che genera le sole
date dovute, convertendo l'ora di Roma in UTC — su smart_block la sola prossima.
⚠️ `entity_id` è uuid in produzione e l'id dell'obiettivo è un intero: `pesataEntityId()` ne
ricava uno **fisso** (`7e160000-0000-4000-8000-<id in esadecimale>`), così la stessa riga si
ritrova senza archiviare niente. Chiudendo o eliminando l'obiettivo le regole si tolgono. Il ✅
Fatto della notifica la chiude e basta: la pesata si segna nell'app.

### Diario alimentare (`al_`)

✅ **Il conto delle calorie è UNA regola, nel database** (25 settembre 2026,
`20260925140000_al_conto_calorie_rpc.sql`). Basale, deficit a due addendi, peso di piano, tratti,
target congelato e giorno per giorno col saldo spalmato — tutto quel che si legge qui sotto — li
fanno le RPC, e li leggono **tutt'e due** le implementazioni (`weight-quest.html` v4.4.0, APK
nativa 1.0.94). Fino a lì erano `calcolaTarget` / `stimaTratti` / `giorniDellaDieta` nel web e
`CalorieRegole` in Kotlin, due copie da tenere uguali. **Una modifica a quelle regole si fa nel SQL
e basta.**

| Funzione | Cosa fa |
|---|---|
| `al_conto(p_oggi)` | Tutto quel che le app mostrano: per ogni giorno da oggi−120 a oggi+62 il conto di adesso (`calcolato`), quello da mostrare (`target`, congelato se c'è) e il target del tratto (`tratto`); i `tratti`; il `piano` con arco, righe, saldo e fetta al giorno |
| `al_calcola_target(p_giorno)` | Il conto di un giorno solo, com'è adesso |
| `al_congela_giorno(p_giorno)` | Scrive la riga di `al_days` se non c'è (mai un upsert), e torna il target |
| `al_ricalcola_giorno(p_giorno)` | La riscrive, su richiesta esplicita |

⚠️ **La riga di `al_days` la scrive la RPC**: il numero congelato nasce dalla stessa regola che lo
mostra. `objective_id` si scrive solo se l'id dell'obiettivo è davvero un uuid — la colonna è uuid
e l'id di `ps_objectives` può essere un intero, e prima il congelamento falliva in silenzio.
⚠️ **Gli avvisi tornano come codice + soglia** (`negativo`, `sotto_soglia`, `deficit_alto`) e il
testo lo scrivono le app (`testiAvviso` nel web, `Conto.testiAvviso` in Kotlin, gemelli).
⚠️ **Il peso di ripiego si cerca in TUTTE le pesate** e non nei soli 120 giorni caricati, ed è il
minimo dell'ultimo giorno pesato (`al_peso_al`). ⚠️ «Oggi» lo passa il client. ⚠️ I numeri escono
in `numeric`: rispetto al vecchio JavaScript cambia solo qualche arrotondamento al centesimo del
peso di piano (verificato: 211.902 confronti, differenze solo lì). ⚠️ Dopo ogni scrittura (una
riga del diario, una pesata) le app **rileggono `al_conto`**: il conto non si aggiorna da sé.
⚠️ **Le pesate si leggono UNA volta** (`20260929100000_al_conto_piu_veloce.sql`): `al_contesto()`
porta con sé `pesi` (da `al_pesi()`) e `al_peso_ctx` cerca lì, con la stessa regola di
`al_peso_al`. Chiamando `al_peso_al` giorno per giorno `al_conto` rileggeva tutta
`ps_weight_tracking` centinaia di volte e andava in *statement timeout* (7,5 s su ~2.900 pesate,
contro gli 8 concessi; ora 0,2 s, risultato identico). Dentro un ciclo sui giorni non si chiama
`al_peso_al`.
⚠️ **Nel nativo gli errori si mostrano con `messaggioBreve()`** (`core/Errori.kt`): il messaggio
delle eccezioni di supabase-kt porta in coda URL e header, **token compreso**, e finiva a schermo.
Oggi lo usano Calorie e Peso; le altre schermate hanno ancora `e.message`.

| Table | Purpose |
|---|---|
| `al_profile` | Una riga per utente. ⚠️ Di questa tabella si usa **solo `activity`** (fattore LAF): data di nascita, altezza e sesso si leggono da `cm_profile` |
| `al_foods` | Gli alimenti conosciuti. `source` `'base'` (voci generiche di partenza) \| `'off'` (Open Food Facts, col `barcode`) \| `'usda'` \| `'manuale'`; valori **per 100** di `unit` (`'g'` \| `'ml'`). `default_grams` è la porzione abituale e `portion_label`/`portion_label_plural` come si chiama. `verified` = i numeri li ha guardati l'utente |
| `al_log` | Le righe del diario: `day`, `meal`, `grams`, `unit`, e i valori per 100 **congelati sulla riga** |
| `al_days` | Il target di calorie di una giornata, **congelato**, con gli ingredienti del conto (`weight_kg`, `bmr`, `tdee`, `deficit_kcal`) |

⚠️ **I dati anagrafici stanno in `cm_profile` e da qui si leggono soltanto.** Chiederli di nuovo
in ogni app che ne ha bisogno vuol dire due altezze diverse il giorno che una delle due si
corregge, e nessun modo di sapere quale è quella giusta. La pagina li mostra in sola lettura con
il pulsante che apre la scheda (`/#profilo`, che `index.html` intercetta all'avvio per aprire il
modale invece di lasciare sulla home); `al_profile.activity` resta di qua perché è una scelta di
questa pagina e in `cm_profile` non c'è. Le colonne `birth_date`, `height_cm` e `sex` di
`al_profile` non vengono più né lette né scritte — restano lì in attesa di una migration che le
tolga.

⚠️ `cm_profile.sesso` ammette anche `'Altro'`, che Mifflin-St Jeor non prevede: le sue due
varianti differiscono di una costante (+5 contro −161), quindi lì si usa la **via di mezzo** e la
pagina lo scrive nella spiegazione del target, col pavimento più alto dei due (1500). Fermare il
conto sarebbe la scelta pulita e inservibile — l'app non calcolerebbe più niente per un campo che
non cambia l'ordine di grandezza del risultato. La casella **vuota** invece ferma il conto per
davvero: non dice quale variante usare.

⚠️ **Il target non si archivia in nessuna impostazione, si ricava.** `calorie.html` legge
l'ultimo obiettivo **attivo** di `ps_objectives` e le sue milestone, e ne ricava:

```
basale (Mifflin-St Jeor su età, altezza, peso, sesso)
  × al_profile.activity                       = consumo stimato
  − deficit                                   = target del giorno
```

⚠️ **Il deficit è la somma di DUE addendi, e la separazione è la funzionalità.** I traguardi di un
obiettivo non sono equidistanti — chi ne mette tre nel primo mese e uno nei due successivi vuole
perdere in fretta e poi tenere — quindi:

| Addendo | Formula | Perché |
|---|---|---|
| **Ritmo del tratto** | `kg/giorno del tratto in corso × 7700` | Un piano che chiede 0,70 kg a settimana vuole ~770 kcal al giorno. Spalmare la perdita residua su tutto il tempo residuo dà lo **stesso numero nei due tratti** e fa restare indietro proprio dove il piano correva |
| **Recupero dello scarto** | `(peso vero − peso di piano) × 7700 / giorni che restano **in tutto**` | È l'addendo che stringe il target da sé quando si è indietro e lo allarga quando si è avanti. Sui giorni del **solo tratto** sarebbe feroce a ridosso di un traguardo: due chili in cinque giorni non sono un obiettivo |

Senza il primo il piano non si sente, senza il secondo non ci si accorge di essere rimasti
indietro. La somma **non scende sotto zero**: essere molto avanti allenta il target fino al
mantenimento, non oltre — un deficit negativo sarebbe l'app che invita a mangiare di più per
tornare sulla curva. `segmentoDi()` trova il tratto; senza curva (meno di due traguardi) o a
piano finito si ripiega sulla media fino alla fine, che è tutto quel che si può dire.

⚠️ **L'ultimo traguardo vale come peso finale solo se i traguardi sono almeno due**: uno solo è il
punto di partenza, non la meta, e prenderlo per tale fa credere l'obiettivo già raggiunto (peso di
oggi ≤ «finale») e azzera il deficit senza dire niente. Con meno di due si usa `ob.end_weight`.

⚠️ Fino alla v1.0.1 il deficit era la sola media `(peso di oggi − peso finale) / giorni che
restano`. Su un piano a due velocità (3 kg nel primo mese, 1 kg nei due dopo) dava 322 kcal nel
tratto che ne chiedeva 770: il ritmo del piano non si sentiva affatto.

⚠️ `pesoPianoAl(giorno, traguardi)` è **l'unica copia** di questa interpolazione, e
`getInterpolatedTarget()` della metà «peso» la chiama. Fino all'unione delle due pagine erano due
implementazioni della stessa formula — una per file — che andavano cambiate insieme: bastava
toccarne una perché il diario alimentare e la tabella delle pesate dessero due traguardi diversi
per lo stesso giorno. ⚠️ I traguardi si **passano** invece di leggerli da `S`: le due metà ne
guardano due elenchi diversi, e non è una svista — il diario parla sempre dell'obiettivo attivo,
la tabella delle pesate anche di uno già chiuso, perché la sua tendina lo permette.

⚠️ **Il target non ha pavimenti: è quello che il piano richiede, per basso che sia** — anche
negativo, che è il modo più chiaro di dire che in quei giorni quel peso non ci si arriva nemmeno
digiunando. `KCAL_ATTENZIONE` (1500 M / 1200 F) fa comparire un **avviso** e nient'altro: la
decisione di allargare il piano è di chi legge, non della pagina. Fino alla v1.1.0 era un
pavimento vero e il target ci si fermava sopra.

In ⚙️ Impostazioni il riquadro **📐 Stima delle calorie** è in due parti: il riepilogo (peso di
oggi, peso di piano, scarto, kg e giorni che restano, basale, consumo, target di oggi) e la
tabella dei **tratti**, una riga per coppia di traguardi, con periodo, pesi di partenza e fine,
target, ritmo, deficit e consumo. ⚠️ Ogni tratto si calcola sul **peso medio che il piano prevede
lì** e non su quello di oggi — il basale cala col peso, quindi a parità di ritmo l'ultimo tratto
chiede meno del primo — e **senza il recupero dello scarto**: sono i valori «se stai sul piano»,
e nei tratti futuri lo scarto sarebbe un'invenzione. Il conto vero di oggi, recupero compreso,
sta nel riquadro del Diario. ⚠️ L'ordine delle colonne è quello di lettura: sul telefono la
tabella scorre dentro il suo riquadro, quindi il **target** viene subito dopo i pesi e ritmo,
deficit e consumo possono uscire dal bordo.

`al_days` è la stessa scelta di `ps_weight_tracking.target_weight`, per la stessa ragione: il
target si **congela** alla prima riga segnata quel giorno, e spostare un traguardo in «Ti pisasti?»
domani non deve riscrivere il giudizio su un giorno già passato. Si ricalcola **solo su richiesta
esplicita**, un giorno per volta. Per lo stesso motivo `al_log` porta i valori nutrizionali sulla
riga e non solo `food_id`: correggere un alimento non riscrive quel che si è mangiato il mese
scorso, e cancellarlo non fa sparire le calorie già contate (`food_id` va a NULL, la riga resta).

⚠️ **La porzione abituale è tre colonne, non una**: `default_grams` dice *quanto* (un uovo 55 g,
una pizza 300 g, un cucchiaio d'olio 10 g), `portion_label` e `portion_label_plural` *come si
chiama*. Il singolare e il plurale sono due colonne perché **in italiano il plurale non si ricava
a regola proprio dove serve di più** — uovo → uova — e una parola sbagliata a schermo si legge
come un difetto dell'app; nemmeno una colonna sola con dentro `'uovo|uova'`, che sarebbe un
formato da interpretare dentro un campo di testo. Il plurale è **facoltativo** e ripiega sul
singolare, e senza nessuna etichetta si legge «porzione / porzioni», vero per qualunque cosa.
⚠️ Un'etichetta **non si inventa**: chi la legge scritta se la crede, e sono le calorie della
giornata — `20260830120000_al_porzione_etichetta.sql` ne compila 17 sulle 44 voci base, e le
altre (petto di pollo, insalata, zucchine) restano senza perché «1 porzione» è già quel che sono.

⚠️ **`unit` dice se quell'alimento si pesa o si versa** (`20260901120000_al_unita_g_ml.sql`):
`'g'` = i valori sono per 100 g, `'ml'` = per 100 ml, che è come l'etichetta scrive il latte,
l'olio, il vino e una spremuta. **Non è una conversione e nessuna densità entra in gioco**: il
conto resta la stessa moltiplicazione — 250 ml × (kcal per 100 ml) / 100 — e convertire i ml in
grammi vorrebbe dire inventare un numero (l'olio sta a 0,91, il miele a 1,42) per rimoltiplicarlo
poi per valori che quella densità l'avevano già dentro. Le colonne restano `*_100g` e `grams` coi
loro nomi: dicono «per cento unità di questo alimento», e `unit` dice quali — rinominarle sarebbe
una migration che tocca due implementazioni e una Edge Function senza cambiare nessun numero.

⚠️ **Sta anche su `al_log`, e non è un doppione**: la riga porta già i valori congelati, e
l'unità è parte di quei valori — senza, cambiare un alimento da ml a g (o cancellarlo, che porta
`food_id` a NULL) riscriverebbe all'indietro l'etichetta di righe già segnate. È la stessa scelta
dei valori nutrizionali sulla riga, un passo più in là. ⚠️ **`NOT NULL DEFAULT 'g'` e nessun
backfill «intelligente»**: indovinare i liquidi dal nome marcherebbe in ml anche il latte in
polvere e l'olio di semi di una tabella di composizione, che in grammi ci stanno di proposito.

La scelta si fa **da 🍎 Alimenti → ✏️** (i due pulsanti *⚖️ Grammi* / *🥛 Millilitri*), e da lì
in poi si legge dappertutto: la finestra della porzione chiede «Quantità (millilitri)» invece di
«Peso (grammi)» e offre i tagli dei liquidi (`ML_RAPIDI`: 50, 100, 150, 200, 250, 330, 500 — un
bicchiere, una lattina, una bottiglietta), le righe del diario si leggono «250 ml», e nella
tabella di 🍎 Alimenti un liquido porta il badge `100 ml` accanto al nome. ⚠️ **Il badge compare
solo sui liquidi**, per la stessa ragione del ✅: i grammi sono quel che è quasi tutto, e un «g»
su ogni riga smetterebbe di distinguere qualcosa.

⚠️ **Nel form dell'alimento le due scelte vicine sono diverse e si comportano al contrario**: la
misura (g/ml) è una proprietà dell'alimento e **si riapre su quel che c'è in archivio**; l'unità
dei valori (100 / 1 porzione) è una dichiarazione sui numeri scritti adesso e **riparte sempre da
100** — ricordarla farebbe dividere i valori una seconda volta al primo salvataggio.

⚠️ **L'unità di un prodotto di rete la legge `al-food-search`, non la pagina**: viene da
`product_quantity_unit`, `serving_quantity_unit` o dalla quantità in chiaro («1 l», «33 cl»),
cioè è **scritta sul prodotto** e non dedotta dal nome. Senza nessuna unità dichiarata resta
`'g'`. È la stessa regola per cui la normalizzazione vive solo nella Edge Function.

⚠️ **`verified` non è una seconda `source`, ed è ortogonale a lei**
(`20260831120000_al_foods_verified.sql`): `source` dice **da dove viene** un numero, `verified`
dice **se qualcuno l'ha guardato**. Un prodotto letto da Open Food Facts e confrontato con
l'etichetta che si ha in mano è verificato; una voce scritta a mano di fretta non lo è —
ricavarla dalla fonte le farebbe dire una cosa diversa da quella per cui esiste. È la domanda
che ha lasciato la «Pizza condita» in archivio a 1225 kcal per 100 g: la riga sembrava una riga
qualunque, e non c'era nessun posto in cui dire «questa l'ho controllata».

⚠️ **`NOT NULL DEFAULT false` e nessun backfill, nemmeno sulle voci `'base'`**: la spunta
significa «l'ho controllato io», e metterla d'ufficio su righe che nessuno ha mai riguardato
direbbe il falso proprio nella colonna che esiste per dire se ci si può fidare — un catalogo
tutto verificato al primo avvio non distingue più niente. Le voci base restano quel che dice il
riquadro di 🍎 Alimenti: valori indicativi delle tabelle di composizione pubbliche. Per la stessa
ragione è un **booleano e non una data di verifica**: la domanda è «me ne fido?», e una data
sarebbe un secondo dato da tenere aggiornato per rispondere alla stessa.

La spunta si mette dal form ✏️ dell'alimento e si vede in due posti: **nelle righe di ricerca del
📓 Diario** (badge *✅ verificato*), che è il momento in cui si sceglie quale numero far entrare
nella giornata, e **nella tabella di 🍎 Alimenti**, accanto al nome. ⚠️ **Solo sulle righe del
catalogo**: un risultato di rete non è ancora in archivio, quindi nessuno ha potuto verificarlo e
un ✅ lì direbbe il falso — è la stessa distinzione 📗/🌐 che l'icona in testa alla riga già fa. E
**solo quando c'è**: «non verificato» è lo stato normale di quasi tutto, e un segno su ogni riga
smetterebbe di distinguere qualcosa. ⚠️ Nella tabella il ✅ sta **accanto al nome e non in una
colonna sua**: l'ultima colonna di una tabella che scorre di lato sta oltre il bordo destro, ed è
la stessa ragione per cui ✏️ e 🗑 stanno in testa alla riga.

In 🍎 Alimenti c'è il filtro **✅ Solo verificati** (`S.soloVerificati`), che restringe l'elenco a
quelli con la spunta; l'intestazione dice sempre quanti sono sul totale, che è la domanda «quanto
manca» senza bisogno di accenderlo. Tre cose volute:
- è un **AND col testo**, non una ricerca a sé: accendendolo mentre si cerca, l'elenco si
  accorcia invece di ripartire da capo;
- **vive in memoria e non in `localStorage`**, a differenza del filtro per carta di Spese
  Personali: è il taglio di una sessione di lavoro, e ritrovarlo acceso domani farebbe sembrare
  mezzo vuoto un catalogo pieno;
- ⚠️ **col filtro acceso la ricerca in rete non parte proprio**: un risultato di Open Food Facts
  non è in archivio, quindi nessuno l'ha verificato e non verrebbe comunque mostrato — e OFF le
  ricerche testuali le conta, una decina al minuto. Per la stessa ragione l'elenco vuoto **dice
  che il filtro è acceso**: «nessun alimento» sarebbe indistinguibile da un catalogo vuoto.

⚠️ **Una casella vuota resta vuota e non vale zero**, né in `al_foods` né in `al_log`: «non so
quante fibre ha» e «non ha fibre» sono due cose diverse, e uno zero falso farebbe sembrare magro
un alimento di cui non si sa niente. È la stessa scelta di `fnz_income` e delle misure di Memo.

### Spese Ada (`ada_`)
| Table | Purpose |
|---|---|
| `ada_super_categories` | Super-categorie: **l'unico livello che porta il `color`** |
| `ada_categories` | Voci di spesa (nome unico per utente, `icon`, `super_id`); `color` non è più usato |
| `ada_transactions` | Movimenti importati dal conto: `amount` **con segno**, **una sola** `category_id`, `card_identification`, `external_id` (unico per `bank_connection_id`) |
| `ada_merchant_map` | Negozi imparati: `merchant_key` normalizzato → categoria, **per fonte** (`source` `'bank'`\|`'excel'`, unico su `(user_id, source, merchant_key)`) |

Sono volutamente **separate dalle `ca_*`**: le spese di Ada non devono entrare nei totali di
Spese Famiglia, e una separazione per campo sarebbe retta solo finché ogni query si ricorda di
filtrarla.

### Spese Personali (`sal_`)
| Table | Purpose |
|---|---|
| `sal_super_categories` | Super-categorie: **l'unico livello che porta il `color`** |
| `sal_categories` | Voci di spesa (nome unico per utente, `icon`, `super_id`) |
| `sal_transactions` | Movimenti del conto personale: `amount` **con segno** (entrate comprese), **una sola** `category_id`, `card_identification`, `external_id` (unico per `bank_connection_id`) |
| `sal_merchant_map` | Negozi imparati: `merchant_key` normalizzato → categoria, **per fonte** (`source` `'bank'`\|`'excel'`, unico su `(user_id, source, merchant_key)`) |

Stesso schema delle `ada_*` e stessa ragione per cui è separato dalle `ca_*`: le spese del conto
personale di Salvatore non devono entrare nei totali di Spese Famiglia. **L'unica differenza è
cosa ci finisce dentro**: qui si archiviano anche le entrate, perché la pagina tiene il saldo del
periodo — nessuna colonna in più, `amount` è già con segno.

### Spuntiamola (`sp_`)
| Table | Purpose |
|---|---|
| `sp_settings` | Una riga per utente: traguardo, emoji, periodo (`start_date` → `end_date`), `skip_weekend`, `mood` |
| `sp_checks` | Una riga per giorno spuntato (`UNIQUE (user_id, day)`); `emoji` è quella pescata a caso alla spunta |
| `sp_key_days` | Giornate chiave (`UNIQUE (user_id, day)`); `label` è l'etichetta libera mostrata alla spunta |
| `sp_stecche` | Archivio delle stecche chiuse: traguardo e periodo com'erano, `mood`, `total_days`/`done_days`, `satisfaction` (1-100), `note`, e la fotografia jsonb di `checks` e `key_days` |

`mood` (`20260901100000_sp_mood_stecca.sql`) dice **con che voce l'app commenta la griglia**, e
ammette due valori: `'attesa'` — il tempo che passa avvicina qualcosa, quindi ogni spunta è un
giorno tolto di mezzo — e `'bei_giorni'` — il tempo che passa porta via qualcosa, quindi ogni
spunta è un bel giorno che se ne va e l'ultimo giorno è un addio, non un arrivo.

⚠️ **La colonna sta sulla STECCA e non sull'utente**: si aspettano le ferie e nel frattempo le
si vive, e le due cose convivono nel tempo senza essere la stessa. Sta anche in `sp_stecche`
perché riaprendo l'archivio non si saprebbe più di che stecca si trattava. Le righe nate prima
restano `'attesa'`, che è quello che erano davvero: l'app sapeva parlare in un modo solo.

### Forziere (`frz_*`)
| Table | Purpose |
|---|---|
| `frz_vault` | Una riga per utente: dov'è la cartella su Drive e i suoi due oggetti di servizio, più le date di collaudo ed export |
| `frz_files` | Una riga per file: `drive_file_id`, `meta_enc` (nome, tipo, dimensione vera, note — **tutto cifrato**), peso del cifrato |
| `frz_thumbs` | Le miniature, cifrate. Tabella a parte perché l'elenco si legge a ogni apertura e deve restare leggero |
| `frz_boxes` | Gli **scomparti**: `meta_enc` (nome ed emoji, cifrati), `position`, `drive_folder_id` (la sua cartella su Drive, che si chiama con un uuid). `frz_files.box_id` li collega |

⚠️ **Gli scomparti sono ORGANIZZAZIONE, non separazione**, e il nome inganna: due scomparti
**non si proteggono a vicenda** — le 24 parole sono una sola per tutto il forziere, quindi chi
lo apre li apre tutti. Servono a tenere in ordine (Documenti, Casa, Ada). La separazione vera —
un forziere che l'altro non apre — vorrebbe dire **24 parole per forziere**, cioè N segreti da
ricordare per sempre: è un'altra funzionalità e va decisa sapendo che costa quello.

⚠️ **Il nome dello scomparto è cifrato** come quello dei file: uno scomparto «Divorzio» scritto
in chiaro in una colonna racconta la storia da solo, che è precisamente ciò che
`frz_files.meta_enc` esiste per evitare. Ne discende che l'elenco degli scomparti si legge solo
a forziere aperto — e va bene, perché prima non c'è comunque niente da vedere.

⚠️ **Su Drive uno scomparto È una cartella, e si chiama con un uuid** (v1.3.0,
`20260905180000_frz_boxes_drive_folder.sql`). Fino alla v1.2.2 non c'erano sottocartelle
affatto, e la ragione scritta allora era che una cartella «Divorzio» rimetterebbe in chiaro il
nome appena cifrato. La ragione era giusta e la conclusione no: **il problema non erano le
cartelle, era il loro NOME**. Con un uuid chi guarda il Drive vede quello che vedeva prima —
quante cartelle, quanti file, i pesi, le date — e nient'altro.

⚠️ **`drive_folder_id` NULL è uno stato buono**: «lo scomparto c'è ma la sua cartella non è
ancora stata fatta». Ci nascono tutti gli scomparti già in archivio, e la riempie
**📁 Riordina il Drive** (Impostazioni) o il primo file che ci si carica dentro
(`assicuraCartella`). Un NOT NULL avrebbe voluto dire una migration che inventa un id di Drive,
cioè un id che non apre nessuna cartella.

⚠️ **`box_id` è `ON DELETE SET NULL`, non CASCADE**: cancellare uno scomparto non porta via i
file, che tornano in «Senza scomparto» — un contenitore che si porta dietro il contenuto è il
modo più veloce di perdere un documento per sbaglio. NULL è uno stato buono e non un dato
mancante, ed è per questo che le righe già in archivio non hanno avuto bisogno di nessuna
migrazione. La conferma di eliminazione **dice cosa non succede**, o davanti a trenta documenti
si preme Annulla.

⚠️ **In queste tabelle non c'è niente da attaccare, ed è la scelta che regge tutto.** Il
segreto sono **24 parole** che stanno solo nella testa di Salvatore; i file cifrati stanno su
Google Drive e le due chiavi di servizio pure. Chi si portasse via un dump del database non
avrebbe nemmeno *su cosa* provare a indovinare.

⚠️ **Il database NON è indispensabile**, ed è la proprietà per cui il Forziere esiste: ogni
`.gpg` si riapre da solo con `gpg -d` e le 24 parole, e porta dentro di sé il proprio nome
originale (pacchetto *literal* di OpenPGP). Perso il database non si perde un byte — si perde
la comodità di sfogliare, cercare e vedere le miniature. Nessuna modifica futura deve togliere
questa proprietà.

⚠️ **Il formato è OpenPGP simmetrico e non un formato nostro.** La procedura di recupero
dev'essere **ricordabile a mente** — `gpg -d documento.gpg`, poi le 24 parole — e una ricetta
nostra (normalizzazione, sale, giri, HKDF) sarebbe fragile proprio dove non può esserlo: basta
sbagliare un dettaglio e non si apre più niente, e quei dettagli non stanno in nessuna testa.
Il file si porta dentro algoritmo, sale e giri, e il programma li legge da sé. Una prima
versione del progetto aveva quella ricetta ed è stata **ritirata prima di scrivere una riga**.

**Gli oggetti su Drive**, nella cartella `AppSphere/rooms` (che la Edge Function si crea
da sé, e **non** è quella dei backup — una rotazione sbagliata cancellerebbe il forziere):

```
AppSphere/                una cartella sola per tutta la suite
├─ backups/               il dump settimanale e la relazione (`GDRIVE_FOLDER_ID`)
└─ rooms/                 il forziere
   ├─ indice.gpg          la chiave dell'indice        (24 parole)
   ├─ scorciatoia.gpg     le 24 parole                 (passphrase — l'eccezione)
   ├─ scomparti.gpg       quale cartella è quale scomparto   (24 parole)
   ├─ contenuto.gpg       i file che non stanno in nessuno scomparto   (24 parole)
   ├─ <uuid>.gpg          …e quei file
   └─ <uuid-cartella>/    una per scomparto, nome uuid
      ├─ contenuto.gpg    i file di questo scomparto   (24 parole)
      └─ <uuid>.gpg
```

⚠️ **La cartella si chiama `rooms` e non «Forziere»**, ed è la stessa ragione per cui la bolla
è riservata in home: un nome che annuncia un forziere a chi scorre l'elenco del Drive è metà
del lavoro buttato — dentro i nomi sono già tutti uuid e i file tutti cifrati. Fino al
6 settembre 2026 erano due cartelle sciolte nella radice, `Forziere AppSphere` e
`AppSphere_backups`.

⚠️ **Il nome si cerca su Drive, quindi rinominare la cartella e cambiare `NOME_CARTELLA` in
`forziere-drive` sono la STESSA modifica**: fatta una sola delle due, la Edge Function non
trova più niente. L'ordine è: prima il deploy, poi il rename.

⚠️ **La cartella la crea SOLO `init`, cioè la creazione del forziere** (v4, 10 settembre
2026). Fino alla v3 la creava chiunque non la trovasse, e per la Edge Function «non trovata»
comprendeva anche **ricerca fallita**: l'`if (r.ok)` lasciava cadere l'errore e si finiva a
creare, quindi un 401, un 500 di Drive o una rete storta diventavano un secondo `rooms`
vuoto. È successo il 10 settembre 2026, nel mezzo del pasticcio col client OAuth: due
`rooms` dentro `AppSphere`, e il forziere che rispondeva ❌ *«quel file non sta nella
cartella del forziere»* **coi documenti tutti al loro posto**. Una cartella nuova è un
forziere nuovo: non è una cosa che si fa per ripiego. Ora una ricerca fallita alza l'errore
vero, e ogni azione che non sia `init` dice che la cartella non c'è invece di inventarla.

⚠️ **Fra più cartelle vince la PIÙ VECCHIA** (`orderBy=createdTime`), non la prima che Drive
restituisce: l'ordine di quella risposta non è garantito, quindi «la prima» era un sorteggio
che si rifaceva a ogni chiamata — il forziere si apriva o non si apriva a seconda del tiro,
che è il modo peggiore in cui un difetto si presenta. Il forziere vero è sempre il primo
nato. ⚠️ Il rimedio a un doppione resta **buttarlo dal Drive**: la funzione lo aggira e lo
scrive nei log, non lo cancella — cancellare cartelle è precisamente il potere che questa
funzione non deve avere.

⚠️ **Il sintomo di quel giorno va riconosciuto**: «quel file non sta nella cartella del
forziere» **non vuol dire che il file è perso**. Se lo dice, la funzione il file lo *vede*
(altrimenti direbbe `Drive get: HTTP 404`): è la cartella a essere quella sbagliata. Prima
di toccare qualunque cosa si contano le `rooms` su Drive.

⚠️ **`GDRIVE_APPSPHERE_FOLDER_ID` è FACOLTATIVO e dice solo DOVE NASCE una cartella nuova**:
la ricerca resta per nome e fra i risultati *preferisce* quello dentro `AppSphere` senza
pretenderlo. Pretendendolo, il segreto messo prima dello spostamento farebbe nascere un
secondo `rooms` vuoto proprio nel momento in cui si sta riordinando. Senza il segreto,
`rooms` nasce nella radice e tutto il resto funziona uguale.

| Oggetto | Cos'è | Chiuso con |
|---|---|---|
| `<uuid>.gpg` | un documento | le 24 parole |
| `indice.gpg` | la chiave dell'indice (32 byte casuali) | le 24 parole |
| `scomparti.gpg` | id, nome, emoji e cartella di ogni scomparto | le 24 parole |
| `contenuto.gpg` | nome, tipo, dimensione e data dei file di **quella** cartella | le 24 parole |
| `scorciatoia.gpg` | le 24 parole | la passphrase quotidiana |

⚠️ **Tutto su Drive è cifrato con le 24 parole, con UNA eccezione dichiarata**:
`scorciatoia.gpg`, che le 24 parole le *contiene* — cifrarlo con loro sarebbe circolare e non
aprirebbe niente a chi la passphrase ce l'ha e le parole no. È il suo mestiere.

⚠️ **I due indici esistono perché il database non sia indispensabile nemmeno per ORIENTARSI.**
Prima la proprietà era «perso il database, ogni `.gpg` si riapre lo stesso» — vera, ma erano
trecento file di nome `<uuid>.gpg` e l'unico modo di sapere cosa fossero era aprirli a uno a
uno. Ora `gpg -d contenuto.gpg` restituisce l'elenco di quella cartella e `gpg -d scomparti.gpg`
dice quale cartella è quale scomparto. Sono una **comodità**: senza, i documenti si aprono lo
stesso e ciascuno porta dentro il proprio nome originale.

⚠️ **LA VERITÀ RESTA IL DATABASE.** Gli indici sono una copia, riscritta dopo ogni caricamento,
eliminazione, spostamento e modifica di uno scomparto (`scriviIndici`). Se una riscrittura non
passa **la pagina lo dice** — un avviso giallo col pulsante *Rifalli adesso* — e in Impostazioni
c'è **🔄 Rifai gli indici**. Senza quel rimedio sarebbero due verità che divergono in silenzio,
che è il difetto pagato altrove con lo snapshot del patrimonio. Per la stessa ragione
`scriviIndici` **non blocca e non fa fallire l'operazione vera**: il file è già caricato e la
riga già scritta.

⚠️ **Gli indici si scrivono per NOME (`perNome`) e non per id**: il loro id non sta in nessuna
colonna, e il giorno che il database non c'è più dev'essere il Drive a dire qual è. Lo chiedono
**solo** gli indici — sui documenti, dove il nome è un uuid e due file non si chiamano mai
uguale, un upsert per nome non aggiungerebbe niente e aggiungerebbe un modo di sovrascrivere
un documento per sbaglio.

⚠️ **`rmdir` si rifiuta di cancellare una cartella non vuota**, e non è prudenza generica:
Drive butta via una cartella **col suo contenuto**, mentre `box_id` è `ON DELETE SET NULL` —
nell'ordine sbagliato la riga sopravvive e il file no. Eliminando uno scomparto la pagina
sposta prima i suoi file in radice, poi toglie il solo `contenuto.gpg` di quella cartella
(che `rmdir` conterebbe come contenuto), poi `rmdir`, e **solo alla fine** cancella la riga.

⚠️ **`dentroLaCartella` scende di UN livello e si ferma lì.** Il controllo esiste perché un id
qualsiasi non raggiunga un file fuori dal forziere — i backup compresi — e un albero da
percorrere è un controllo che prima o poi lascia passare qualcosa. Gli scomparti sono piatti
per la stessa ragione per cui sono organizzazione e non separazione.

⚠️ **`indice.gpg` fa anche da PROVA**: se si apre, le parole sono quelle giuste; se no, no. Un
verificatore a parte sarebbe una seconda verità sulla stessa domanda — e un oracolo in più per
chi prova a indovinare.

⚠️ **I metadati non passano da OpenPGP ma da AES-256-GCM** sotto la chiave dell'indice: ogni
apertura OpenPGP con una password rifà la derivazione lenta, e per trecento nomi di file
vorrebbe dire trecento derivazioni, cioè minuti. La chiave dell'indice è 32 byte casuali —
non si indovina, quindi non serve renderla lenta.

⚠️ **La ricerca è lato client e non può essere altrimenti**: il server i nomi non li può
leggere. È il prezzo dell'E2EE ed è giusto pagarlo qui.

⚠️ **Quel che resta visibile** a chi guarda il database o il Drive: **quanti** file ci sono,
**quanto pesano** e **quando** sono stati caricati. Non si può nascondere senza complicazioni
sproporzionate, e va detto invece di lasciarlo credere protetto.

### SOS (`sos_*`)
| Table | Purpose |
|---|---|
| `sos_types` | I diversi SOS. `current_seconds` è la durata del **prossimo** giro, `base_seconds` quella di partenza, `min_seconds`/`max_seconds` gli estremi entro cui le percentuali possono muoverla |
| `sos_outcomes` | Le risposte a «com'è andata?»: `points` (anche negativi) e `time_delta_pct` (positiva allunga, negativa accorcia) |
| `sos_messages` | Le frasi che scorrono sotto il countdown; `sos_type_id NULL` = vale per tutti i SOS |
| `sos_sessions` | Un giro: `planned_seconds`, `completed` (false = countdown interrotto), la risposta scelta e `seconds_before`/`seconds_after` |
| `sos_devices` | Il codice con cui l'APK si accoppia. `token` **è** la credenziale |

⚠️ **La regola del tempo sta nelle RPC, non nel client** — è la stessa scelta di `task_complete`
e `sf_finalize_challenge`, per la stessa ragione: due implementazioni della stessa formula sono
due durate diverse il giorno che una delle due cambia.

| Funzione | Chi la chiama | Cosa fa |
|---|---|---|
| `sos_config(token)` | APK | Tutti i SOS attivi con risposte e frasi, in una chiamata sola |
| `sos_session_start(token, type_id)` | APK | Apre il giro e **dice quanto dura** — la durata la decide il server |
| `sos_session_finish(token, session_id, outcome_id, …)` | APK | Applica punti e percentuale: `next = clamp(current × (1 + pct/100), min, max)` |
| `sos_session_log(token, type_id, outcome_id, …)` | APK | Il giro fatto senza rete, rispedito dopo: crea la sessione già chiusa e delega a `sos_session_finish` |
| `sos_device_create(name)` | `sos.html` | Genera un codice di accoppiamento |

`sos_session_finish` è **idempotente**: una sessione già chiusa risponde con quello che era stato
deciso allora invece di riapplicare punti e percentuale. Senza, il rinvio della coda offline
dell'APK conterebbe due volte gli stessi punti e sposterebbe il tempo due volte.

Le quattro RPC dell'APK sono eseguibili dalla **anon key**: il controllo è il codice di
accoppiamento (`sos_devices.token`), non il ruolo. Le tabelle restano dietro la RLS del
proprietario — da PostgREST con la anon key non si legge niente — e `sos_user_by_token`, che il
codice lo risolve, ha l'EXECUTE revocato ai client.

⚠️ **Le risposte si aggiornano riga per riga e non si cancellano per ricrearle**
(`salvaSos()` in `sos.html`): `sos_sessions.outcome_id` le cita, e ricreandole ogni giro passato
resterebbe agganciato a una riga che non esiste più. È la stessa scelta delle misure di un diario
in Memo. Le **frasi** invece si riscrivono da capo: nessuno le cita, sono testo in un ordine.

### Spese in giro — viaggio in bici (`vg_*`)
| Table | Purpose |
|---|---|
| `vg_viaggi` | Un giro in bici: nome, date, valuta e il **codice di invito** (unico) |
| `vg_partecipanti` | I due telefoni, uno per persona: `nome` e `token`, che **è** la credenziale |
| `vg_voci` | Spese e restituzioni: importo, `da_id` (chi ha messo i soldi), `per_chi`, `beneficiario_id`, `scontrino_path` e lo `stato` |
| `vg_categorie` | Le categorie di spesa di **quel** viaggio: `chiave` (quella scritta nelle voci), `emoji`, `nome`, `posizione` |
| `vg_log` | Il registro: ogni creazione, correzione, conferma, richiesta e cancellazione |

⚠️ **Nessuna di queste tabelle ha `user_id`, e non è una dimenticanza**: il viaggio non appartiene
a un account Supabase ma a un **codice**. La RLS resta accesa e **senza policy** — da PostgREST con
la anon key non si legge e non si scrive niente — e tutto passa dalle funzioni `SECURITY DEFINER`,
che riconoscono chi chiama dal token del suo telefono. È la stessa scelta di `sos_devices`, portata
un passo più in là: là il token indirizza a un utente vero, qui non c'è nessun utente dietro.

⚠️ **Due partecipanti e non più di due**, e il vincolo sta sulla tabella (trigger
`trg_vg_partecipanti_max_due`) e non nella sola RPC: tutta l'app — il saldo, «l'altro», la
conferma — è scritta su due persone, e una terza riga renderebbe ambiguo ogni «l'altro» senza dare
nessun errore.

### 🏷️ Le categorie sono del VIAGGIO, e stanno nel database

Fino alla v1.0.4 erano un elenco fisso in Kotlin (`CATEGORIE` in `Model.kt`), come
`COVERAGE_ITEMS` e `INCOME_SECTIONS` in Finanza. Dalla v1.0.5 vivono in `vg_categorie` e si
gestiscono da ⚙️ Impostazioni: se ne aggiunge una, si cambia la descrizione a una che c'è, e si
toglie una che nessuna voce usa.

⚠️ **Nel database e non nelle preferenze del telefono**, ed è la ragione per cui la tabella
esiste: le voci sono **condivise fra i due telefoni**, quindi una categoria inventata da uno e
tenuta in locale, sull'altro comparirebbe come chiave grezza («pedaggi» invece di
«🛣️ Pedaggi») — e il controllo «non usata» guarderebbe le sole voci che quel telefono ha già
scaricato. È la stessa ragione per cui il saldo si calcola in `vg_saldo` e non in Kotlin.

⚠️ **`chiave` è quel che sta scritto nella voce, `nome` è quel che si legge, e la chiave NON si
cambia mai.** È il perno per cui rinominare una categoria tocca una riga sola e tutte le voci si
rileggono col nome nuovo; seguendo il nome, ogni voce già segnata resterebbe agganciata a
qualcosa che non esiste più. È la stessa scelta delle opzioni di una combo in Memo, che
archiviano l'**id** e mai l'etichetta. Il form quel campo non lo mostra affatto: un campo che
non si può cambiare è un campo che non si mette. La chiave si ricava dal nome una volta sola
(`vg_chiave`, accenti traslitterati) e si differenzia con un `_2` se è già presa.

⚠️ **`vg_voci.categoria` resta testo libero e senza chiave esterna**: una voce non deve poter
sparire, né rifiutarsi di salvarsi, perché qualcuno ha tolto una categoria. Una chiave che non
ha più la sua riga si mostra com'è scritta — la *misura tolta* dei diari di Memo
(`categoriaDi(chiave, elenco)`), e sul form di una voce che c'è già **resta la sua**: prendendo
la prima dell'elenco, correggere l'importo cambierebbe di nascosto la categoria.

⚠️ **Si toglie solo una che nessuna voce usa, e le CANCELLATE contano.** Una voce cancellata
resta a schermo, barrata, e continua a mostrare la sua categoria: toglierla lascerebbe una
chiave grezza in una riga che nessuno può più correggere. Quando gli usi sono tutti di voci
cancellate il messaggio lo dice invece di suggerire un rimedio che non esiste — «cambia
categoria a quelle» manderebbe a cercare un pulsante che su una voce cancellata non c'è.
⚠️ **L'ultima non si toglie**: senza nessuna categoria il form di una spesa non avrebbe niente
da scegliere — sarebbe rotto, non vuoto.

⚠️ **Il conteggio degli usi lo fa il server** e arriva dentro `vg_stato` (`categorie[].usi`):
i due telefoni vedono le stesse voci ma non nello stesso momento, e senza quel numero l'app
offrirebbe un 🗑 che risponde «non si può» solo dopo essere stato premuto. Chi **decide** resta
`vg_categoria_elimina`; il numero in pagina è un'anticipazione, non la regola.

⚠️ **Le sette di partenza sono scritte in due posti** — `vg_categorie_semina` nel database e
`CATEGORIE_DI_PARTENZA` in `Model.kt` — e vanno tenute uguali chiave per chiave. In Kotlin sono
un **ripiego** per un `vg_stato` che non le mandi ancora: un elenco vuoto lascerebbe il form di
una spesa senza niente da scegliere, ed è la stessa scelta di `totale_atteso` assente che vale
il totale confermato. Non hanno `id`, quindi da lì non si gestiscono — è quello che sono.

⚠️ La semina non riguarda solo le sette: prende anche **ogni categoria che le voci di quel
viaggio usano già** e che non è fra loro. `vg_voci.categoria` è testo libero, e una chiave senza
la sua riga sarebbe l'unica categoria ingestibile proprio nella schermata che le gestisce.

⚠️ **`vg_crea` semina, `vg_entra` no**: un viaggio nasce con le sue sette, e chi entra entra in
un viaggio che ce le ha già. I viaggi nati prima della migration sono stati seminati da lei.

⚠️ **Il registro le nomina** (`categoria_creata`, `categoria_cambiata`, `categoria_tolta`): sono
del viaggio, quindi chi ne cambia una la cambia **anche all'altro telefono**, e il registro è il
posto dove quel cambiamento si vede.

⚠️ **`vg_voci.stato` è il cuore dell'app**, e i quattro valori sono quattro momenti diversi:

| `stato` | Chi può fare cosa |
|---|---|
| `in_attesa` | **Solo chi l'ha scritta** la corregge o la toglie; la conferma la dà **solo l'altro** |
| `confermata` | Non si modifica più. Per toglierla si **chiede** |
| `cancellazione_richiesta` | Decide **l'altro**: approva o rifiuta (e allora torna `confermata`) |
| `cancellata` | Fuori dal saldo, ma **resta a schermo**, barrata |

⚠️ **Una voce cancellata non sparisce dalla tabella.** Sparire sarebbe il modo peggiore di dirlo —
un totale più basso senza niente che spieghi perché — ed è la stessa scelta di `excluded` nelle
🛡️ Possibili soluzioni e delle righe `missed` di Abituati.

⚠️ **«Speso in tutto» ha due misure accanto, come il saldo** (`totale_viaggio` /
`totale_atteso`, `20260908120000_vg_totale_atteso.sql`): il primo conta le sole voci
**confermate**, il secondo comprende quelle in attesa. Con un viaggio ancora tutto da confermare
il primo vale zero — vero, e illeggibile sotto un «ti dovrebbe 314,61 €» nella riga sopra: nello
stesso riquadro convivevano due misure diverse senza che si vedesse. La seconda cifra **compare
solo quando differisce**, o ripeterebbe la prima a ogni viaggio già confermato. ⚠️ Nell'APK
`totale_atteso` **assente ripiega sul totale confermato e non su zero** (`statoDa` in `Model.kt`):
contro un server che non manda ancora quella chiave, uno zero scriverebbe «col non confermato
0,00 €», che è il falso — è la stessa scelta di `amount` NULL in Finanza.

⚠️ **Il SALDO si calcola in `vg_saldo`, non nei due telefoni**: è il numero per cui l'app esiste, e
due implementazioni della stessa divisione sono due debiti diversi il giorno che un telefono si
aggiorna e l'altro no. Contano le sole voci **confermate**; `vg_stato` restituisce accanto anche il
saldo che comprende quelle in attesa (`saldo_atteso`), dichiarato come tale — un debito che si
muove prima che l'altro abbia detto sì è una proposta, ma nasconderla farebbe sembrare fermo un
conto che sta per cambiare.

⚠️ **`per_chi` sta sulla spesa e non sulla restituzione**: con due partecipanti «a chi restituisco»
ha una risposta sola, e una colonna che può avere un valore solo è un valore che prima o poi
contraddice l'altro. Il CHECK `vg_voci_per_chi_ok` lo impone. Una spesa `per_chi = 'uno'` con
`beneficiario_id = da_id` è la spesa personale: resta nel registro del viaggio e **vale zero** nel
saldo.

⚠️ **`vg_entra` fa entrare _e_ rientrare**: se il viaggio ha già due partecipanti e il nome è
quello di uno dei due, si riceve un token nuovo per quella stessa persona. È la via per il telefono
cambiato o l'app reinstallata — senza, quel viaggio resterebbe chiuso per sempre, perché il token
sta solo nelle preferenze di quel telefono e non c'è nessun account da cui recuperarlo. Il prezzo è
dichiarato: **il codice è il segreto condiviso**, quindi chi ce l'ha può presentarsi come uno dei
due.

⚠️ Le RPC dell'APK sono eseguibili dalla **anon key** — il controllo è il token, non il ruolo —
mentre `vg_chi`, `vg_altro`, `vg_saldo`, `vg_scrivi_log` e le due della Edge Function
(`vg_viaggio_del_token`, `vg_scontrino_del_token`) hanno l'EXECUTE **revocato**: restituiscono
righe di `vg_partecipanti` (token compreso) o servono a chi ha già il service role.

⚠️ **La revoca è a `PUBLIC`, non ad `anon, authenticated`**, ed è la differenza fra chiuso e
aperto: in PostgreSQL l'EXECUTE di una funzione nasce concesso a PUBLIC e i due ruoli lo ereditano
da lì — togliendolo a loro soltanto, la funzione resta eseguibile e la revoca *sembra* fatta.
Qui non è teoria: `vg_altro` restituisce la riga dell'altro partecipante **col suo token**, e chi
conosce l'id del viaggio (lo conosce ognuno dei due) potrebbe prenderselo e **confermarsi da solo
le proprie voci** — cioè esattamente la cosa che l'app esiste per impedire. Revocato a PUBLIC, il
service role va poi rinominato esplicitamente nelle due GRANT della Edge Function, perché quella
revoca porta via anche la sua.

⚠️ **Le foto stanno nel bucket privato `vg-scontrini`, in una cartella per viaggio**, e non le
tocca nessun client: le scrive e le firma la Edge Function `spese-in-giro-foto`. Il bucket lo crea la
migration, ma **se quell'INSERT non passa la migration non muore**: lo crea al primo caricamento la
Edge Function (`assicuraBucket`) — un deploy fermo su una riga di storage si porterebbe dietro
tutto il resto.

### Piante (`pv_`)
| Table | Purpose |
|---|---|
| `pv_plants` | Le piante: nome, specie, posizione, `acquired_on`, e le informazioni di cura in chiaro (`light`, `watering`, `soil`, `fertilizer`, `notes`); `archived` le toglie dall'elenco senza cancellarne il diario; `parent_id` = 🌿 **discende da** (la pianta madre, `ON DELETE SET NULL`) |
| `pv_diary` | Il diario cronologico: `entry_date` e `comment` |
| `pv_images` | Le foto (bucket **privato** `pv-images`, `<user>/<pianta>/<uuid>.jpg`). `diary_id` NULL = foto della **scheda**, la prima per `position` è la copertina |
| `pv_actions` / `pv_action_history` | Le azioni di cura: gemelle di `ts_tasks` / `ob_actions`, stessi sei tipi, con `plant_id` |
| `pv_wishlist` | Le piante che si vorrebbero: `priority` 1-3, `status` `desiderata`\|`presa`\|`scartata`, `plant_id` = la pianta nata quando la si è presa |
| `pv_groups` / `pv_plant_groups` | I 👥 **gruppi** (nome, emoji) e quali piante ci stanno: una pianta può stare in **più** gruppi |
| `pv_ai_answers` | Domande e risposte di «Chiedi all'IA», per pianta (`plant_id` NULL = domanda generale sulla lista dei desideri). `provider` (`gemini`\|`qwen`\|`groq`\|`claude`, NULL = Claude, le risposte nate prima) e `model` dicono chi ha risposto |

Migration: `20260926100000_pv_piante.sql`. Si compila da `piante.html` e dall'APK nativa (`piante/`).

⚠️ **Un'azione è di UNA pianta OPPURE di UN gruppo** (`pv_actions.group_id`, CHECK
`pv_actions_pianta_o_gruppo`, `20260929120000_pv_gruppi.sql`). L'azione di gruppo è **una riga
sola**: un promemoria e un ✅ Fatto valgono per tutte le piante del gruppo, anche per quelle che ci
entrano dopo, e compare nella Tab ✅ Azioni di ognuna con l'etichetta del gruppo
(`azioniDellaPianta()`). Le RPC non sono cambiate: la riga di storico ha `plant_id` NULL e si
ritrova da `action_id`. Una pianta in due gruppi con la stessa azione la vede due volte. Cancellare
un gruppo porta via le sue azioni ma **non le piante**.

⚠️ **I gruppi ci sono anche in nativo** (APK 1.0.106): sezione 👥 Gruppi, pagina del gruppo con
«＋ Nuova azione per il gruppo», form del gruppo (emoji, nome, piante, 🗑), pillole dei gruppi e
«＋ Crea» nel form della pianta, e le azioni di gruppo nella Tab ✅ Azioni, in 📆 Da fare e nelle
«Ultime volte». Le regole sono gemelle e **vanno cambiate insieme**: `gruppiDi` / `pianteDi` /
`azioniDellaPianta` / `storiaDellaPianta` in `PianteState`, `salvaGruppo` /
`sincronizzaGruppiPianta` in `PianteRepository` (tolgono le sole righe non più scelte e aggiungono
le nuove), il promemoria intitolato «azione — gruppo», il nuovo gruppo dal form della pianta che
**nasce subito** e si collega col Salva, e le archiviate offerte nel form del gruppo solo se già
dentro.

⚠️ **Nessuna colonna «copertina»**: la copertina è la prima foto della scheda, e se la scheda
non ne ha la più recente del diario (`copertinaDi()` / `PianteState.copertina`). Una colonna
accanto sarebbe una seconda verità su quale foto viene prima.

⚠️ **Le azioni stanno su tabelle proprie e non su `ts_tasks`**, per la stessa ragione di
`ob_actions`: i task li leggono tasks.html, il planner, due APK e le notifiche.

⚠️ **A differenza di `ob_action_*`, qui i promemoria ci sono** — Telegram, telefono e Smart
Block — quindi le RPC fanno come le `task_*`: spostano `due_at` delle regole
(`cm_notification_rules`, `app = 'plants'`, tutti i canali) e le cancellano quando l'azione
finisce. **Il client non tocca le regole dopo un ✅ o un ⏭**: le scrive solo salvando il form.

| Funzione | Chi | Cosa |
|---|---|---|
| `pv_action_complete(p_action_id, p_today)` / `pv_action_skip(p_action_id, p_days)` | web e nativo, col JWT | Le porte dell'utente: `auth.uid()` obbligatorio |
| `pv_action_complete_core` / `pv_action_skip_core` (`p_user`) | **solo service_role** | Il nucleo; lo chiama `notification-action` col proprietario della riga di coda, perché lì `auth.uid()` è NULL |
| `pv_smart_block_complete(p_action_id, p_today)` | l'APK Smart Blocker, **anon key** | Chiude l'azione **solo se ha davvero un blocco `smart_block` in coda** (`pending`/`sent`) — senza, la anon key basterebbe a completare qualunque azione di cui si conosca l'id |
| `pv__sposta_promemoria` | interna | Nuova scadenza su tutte le regole, o regole e coda pending via |

⚠️ **Un'azione cancellata si porta via i suoi promemoria da sé** (trigger
`trg_pv_actions_togli_promemoria`): la cancellano il web, il nativo e la cascata di una pianta
eliminata, e una regola orfana suonerebbe per un'azione che non c'è più.

⚠️ **I promemoria hanno la forma dei task** (`reminders` + `due_at`) con
`telegram_complete_button: true` **anche sulla riga `android`**: è il segnale da cui
`fill-notification-queue` mette nella coda `completion_update: { app: 'plants' }`, e senza la
notifica sul telefono non avrebbe il ✅ Fatto. `notification-action` traduce quel `plants` in
`pv_action_complete_core`. `entity_title` è «azione — pianta», perché il promemoria dica di
quale pianta si parla.

⚠️ **Il `workflow` non si crea da nessuna delle due app**: lo schema lo ammette (le RPC lo
gestiscono come le altre gemelle), ma un'azione di cura a passi non ha ancora un form. Un
`workflow` non ha il ✅ Fatto: si chiuderebbe dai suoi step.

⚠️ **La bolla è un punteggio vero**: la `score_query` somma `pv_action_history.points`
(fatta +10, saltata −2, in ritardo −2, cambiabili azione per azione) e `conta_punti` resta al
DEFAULT `true`. Il colore si sceglie fra cinque verdi saltando quelli già usati — il codice della
modalità nascosta è una sequenza di colori.

### Chiedi a Pino (`ia_tunnel_`)
| Table | Purpose |
|---|---|
| `ia_tunnel_messages` | Una riga per domanda: `prompt`, `answer`, `provider` (`gemini`\|`qwen`\|`groq`\|`claude`), `model` |
| `ia_tunnel_tags` | I tag, unici per utente senza badare a maiuscole e spazi |
| `ia_tunnel_message_tags` | Quali tag porta una domanda (**più d'uno**), `ON DELETE CASCADE` da tutt'e due i lati |
| `ia_tunnel_images` | Le immagini allegate a una domanda (fino a 4): `storage_path`, `position`. I file stanno nel bucket **privato** `ia-tunnel-images`, `<user>/<message_id>/<uuid>.jpg` |

⚠️ **L'app si chiamava «IA Tunnel»** e dal 27 settembre 2026 è **«Chiedi a Pino»**
(`20260927140000_ia_tunnel_immagini_chiedi_a_pino.sql`): cambiano il file (`chiedi-a-pino.html`),
il titolo e la bolla. **I nomi interni restano `ia_tunnel_*` e `ia-tunnel`**, di proposito:
rinominarli sarebbe una migration e un deploy in più senza cambiare niente di quel che si vede.

⚠️ **Le immagini salgono PRIMA che la domanda esista**: il loro percorso porta l'id della domanda,
quindi l'id lo sceglie la pagina (`crypto.randomUUID()`) e la Edge Function crea la riga con quello.
La funzione **controlla che ogni percorso stia in `<utente>/<message_id>/`** prima di firmarlo —
senza, si potrebbe far leggere all'IA il file di un altro. Se l'IA non risponde, o la domanda non si
salva, la pagina **ricancella i file**: nessuna riga li nominerebbe. Cancellando una domanda si
tolgono **prima i file e poi la riga**, come in Memo.

Migration: `20260927120000_ia_tunnel.sql`. Si compila da `chiedi-a-pino.html`, e la risposta la scrive
la Edge Function `ia-tunnel` col JWT dell'utente (sotto RLS), tag compresi.

⚠️ **Tabella di collegamento e non un array**: i tag stanno in una migration, quindi la chiave
esterna c'è davvero e cancellando un tag i collegamenti spariscono da sé — la stessa scelta di
`ob_action_metrics`. Cancellare un tag **non** cancella le domande, e la conferma lo dice.

⚠️ **La bolla è un conteggio** (le domande fatte), quindi `cm_apps.conta_punti = false`.

⚠️ **Nessun prompt di sistema**: è un tunnel, e quel che arriva all'IA è quel che si è scritto.

### Obiettivi (`ob_`)
| Table | Purpose |
|---|---|
| `ob_objectives` | Obiettivi, gerarchia a due livelli (`annual` → `quarterly`) via `parent_id` |
| `ob_metrics` | Metriche di un obiettivo (1..N) |
| `ob_measurements` | Rilevazioni: `value` e `note`, una per metrica e per giorno |
| `ob_milestones` | Curva attesa (`expected_value` per data) — base del semaforo |
| `ob_actions` | Le azioni: il «cosa faccio». Gemella di `ts_tasks`, stessi sei tipi |
| `ob_action_history` | Storico e punti delle azioni. Gemella di `ts_history`. `occurrence_date` = il giorno **per cui** l'azione era in calendario, accanto al `timestamp` che dice quando è stata chiusa |
| `ob_action_metrics` | Quali metriche un'azione dovrebbe muovere (anche più d'una) |

`ob_metrics.role` distingue **`primary`** (il risultato, alimenta la barra e il semaforo — **una sola per
obiettivo**, vincolo `uq_ob_metrics_one_primary`), **`control`** (secondo riscontro lagging) e **`leading`**
(lo sforzo).

⚠️ **`ob_metrics.kind` ha due soli valori, e ciascuno compila le sue colonne**
(`20260827110000_ob_metriche_semplificate.sql`):

| `kind` | Colonne che compila | Colonne che lascia NULL |
|---|---|---|
| `autovalutazione` | `min_value`, `max_value` (di norma 1-10) | `baseline`, `target`, `unit` |
| `automisurazione` | `baseline`, `target`, `unit` | `min_value`, `max_value` |
| `test` | `min_value` = 1, `max_value` = 100 (fissi) | `baseline`, `target` |

Il vincolo `ob_metrics_scala_per_tipo` lo impone: senza, una metrica potrebbe portare una scala
*e* un target, e quale dei due comanda sarebbe una scelta arbitraria dentro il codice. Gli estremi
si leggono quindi **da un posto solo** — la RPC `ob_metric_scale`, ricalcata in `scaleOf()`
(`obiettivi.html`) e in `ObMetrica.scala` (nativo) — e l'avanzamento resta la stessa formula per
tutt'e due: `(corrente − da) / (a − da)`.

⚠️ **Il `test` di valutazione** (`20261007100000_ob_metriche_test_valutazione.sql`, web v1.12.0,
APK 1.0.111) è il punteggio di un test esterno — es. GoFluent — su una scala **fissa** 1-100 che il
form non chiede, più un **giudizio scritto per ogni rilevazione** in `ob_measurements.giudizio`
(NULL = nessun giudizio). Colonna a sé e non `note`, che nella finestra delle rilevazioni è una
sola per tutte le metriche dell'azione. La scala sta in `min_value`/`max_value` come quella
dell'autovalutazione, quindi `ob_metric_scale`, `scaleOf()` e `ObMetrica.scala` la leggono dallo
stesso ramo; `ob_record_measurement` ha il parametro `p_giudizio` (DEFAULT NULL) e rifiuta un
punteggio fuori scala anche sul test. ⚠️ Nato per sbaglio sulla repo vecchia e deployato da lì il
7 ottobre 2026 (la migration era quindi già applicata quando è arrivata qui).

Il **`gofluent`** (`20261007120000_ob_metriche_gofluent.sql`, web v1.13.0, APK 1.0.112) è il
livello del test GoFluent, scelto da una **tendina** fra ventuno gradini — A1.1–A1.4 · A2.1–A2.4 ·
B1.1–B1.4 · B2.1–B2.4 · C1.1–C1.4 · C2 — e archiviato come **posizione** (A1.1 = 1 … C2 = 21)
in una scala fissa `min_value` 1 / `max_value` 21 (erano dodici gradini con PRE-A1 e i «+»:
`20261007180000_ob_gofluent_21_livelli.sql` ha riportato rilevazioni e milestone sui livelli nuovi): barra e semaforo restano la stessa formula.
L'etichetta la scrivono le app (`GOFLUENT_LIVELLI` / `fmtVal()` nel web, `GoFluentLivelli` /
`testoValore()` in `ObiettiviModel.kt`), e porta il `giudizio` come il test. ⚠️ **L'ordine dei
livelli è il dato**: spostarne uno cambierebbe il significato delle rilevazioni già salvate.
`ob_record_measurement` rifiuta un livello non intero.

`ob_metrics.descrizione` è **come votare** per un'autovalutazione (*1 = …, 10 = …*) e **cosa si
misura** per un'automisurazione, e viene riproposta a ogni rilevazione: se cambia il metro la serie
storica non è più confrontabile.

⚠️ Prima c'erano quattro `kind` (`state`, `cumulative`, `checklist`, `rubric`), la colonna
`direction`, la finestra `period`, `source_query` e una tabella di criteri pesati
(`ob_rubric_criteria`) con i punteggi in `ob_measurements.detail`: **tutto cancellato**, insieme ai
criteri e ai pesi che conteneva. Le rilevazioni no — il valore di una rubrica era già la media
pesata, e su una scala 1-5 resta quello che era: le rubriche sono diventate autovalutazioni 1-5, le
altre tre automisurazioni. `direction` in particolare era un secondo modo di dire quel che gli
estremi già dicono, e poteva contraddirlo.

#### Le azioni — tabelle proprie, non un collegamento a Tasks

`ob_actions` è la gemella di `ts_tasks`: **gli stessi sei tipi** (`single`, `recurring`,
`simple_recurring`, `multiple`, `free_repeat`, `workflow`), le stesse colonne nome per nome, gli
stessi punti (successo / fallimento / salto / ritardo), le categorie `cm_categories` e le priorità
`cm_priorities` **condivise con i task** — una terza tassonomia da tenere allineata a mano sarebbe
il difetto, non la separazione.

⚠️ **Tabelle separate e non un campo `objective_id` su `ts_tasks`**: i task sono letti da
`tasks.html`, dal planner, dall'APK WebView, da AppSphere nativa e dalle notifiche Smart Block. Un
campo da filtrare regge finché **ogni** query si ricorda di filtrarlo, ed è la stessa ragione per
cui le spese di Ada stanno nelle `ada_*` e non nelle `ca_*`.

⚠️ **Niente FOREIGN KEY verso `cm_categories` e `cm_priorities`**: quelle due tabelle non stanno in
nessuna migration (nascono a mano in produzione), e una FK farebbe fallire il `db push` su un
database nuovo. Se non si leggono, la pagina resta usabile senza invece di non aprirsi affatto.

⚠️ **`ob_action_history.action_id` è `ON DELETE SET NULL`, non CASCADE**, e la riga porta con sé
`action_title`: cancellare un'azione non deve riscrivere all'indietro i punti già presi, e una riga
che resta senza dire di che cosa parlava non si legge più. È la stessa scelta di `al_log`, che porta
i valori nutrizionali sulla riga invece del solo `food_id`.

⚠️ **Com'è finita un'azione lo dice lo storico, non lo `status`**: `terminated` lo diventa anche
fallendo (`ob_action_fail`). `esitoDi()` nella pagina legge l'ultima riga di storico che non sia
`terminated`, ed è la stessa lettura che fa `ob_objective_progress` per la barra dell'esecuzione.

#### Un'azione può muovere più metriche

`ob_action_metrics` (`20260827150000_ob_action_metrics.sql`) collega un'azione alle metriche che
la sua fatica dovrebbe spostare: **anche più d'una**, perché una lezione di conversazione muove
insieme la scioltezza e il numero di pause. Serve a dire *cosa* dovrebbe cambiare, e nient'altro —
non registra rilevazioni e non muove nessuna barra.

Tabella di collegamento e **non** una colonna `metric_ids uuid[]` come `categories`: lì l'array è
obbligato, perché `cm_categories` non sta in nessuna migration e una FK farebbe fallire il
`db push`; `ob_metrics` invece c'è, quindi la chiave esterna si può avere davvero — e fa un lavoro
vero, perché cancellando una metrica i suoi collegamenti spariscono da sé invece di restare come
id che puntano al nulla.

⚠️ **Si collegano le sole metriche del proprio obiettivo.** Il form offre solo quelle, ma
l'obiettivo di un'azione si può cambiare, e in quel momento i collegamenti di prima parlano di
metriche che non c'entrano più: il trigger `trg_ob_action_metrics_pulisci` **li toglie** invece di
rifiutare la modifica — spostare un'azione sotto un altro obiettivo è legittimo, ed è il
collegamento a non avere più senso, non lo spostamento. `onActionObjectiveChange()` fa lo stesso
nella pagina, così quel che si vede è già quel che verrà salvato.

⚠️ **I collegamenti si riscrivono da capo a ogni salvataggio** (`salvaCollegamentiMetriche`): si
cancellano tutti quelli dell'azione e si reinseriscono quelli scelti. È la stessa scelta delle
categorie di una scheda in Memo, per la stessa ragione — senza la cancellazione una metrica tolta
resterebbe attaccata per sempre, e l'azione continuerebbe a dire di muovere qualcosa che non muove.

`ob_task_links` — il collegamento a `ts_tasks` / `hb_habits` — **è stata eliminata**
(`20260827130000_ob_actions.sql`) con i collegamenti già salvati. Diceva soltanto che un task
esisteva altrove: da Obiettivi non si poteva né crearlo, né completarlo, né sapere com'era andato.
I task e le abitudini che citava restano dove sono, intatti.

---

## Funzioni RPC Supabase — Task lifecycle

Le operazioni sul ciclo di vita dei task (complete, skip, fail) sono implementate come funzioni PostgreSQL `SECURITY DEFINER` nel DB. **Il client JavaScript deve sempre delegare a queste RPC e non reimplementare mai la logica lato client.**

### Regola fondamentale

> Tutta la logica di transizione di stato dei task (calcolo prossima occorrenza, aggiornamento `ts_tasks`, inserimento in `ts_history`, aggiornamento/eliminazione `cm_notification_rules`) vive esclusivamente nelle RPC server-side. Il JS chiama la RPC e poi ricarica i dati.

### Funzioni disponibili

| Funzione | File migration | Parametri | Descrizione |
|---|---|---|---|
| `task_complete` | `20260518100000_task_complete.sql` | `p_task_id uuid, p_today date` | Completa un task |
| `task_skip` | `20260619100000_task_skip.sql` | `p_task_id uuid, p_days integer DEFAULT 1` | Salta un task alla prossima occorrenza |
| `task_fail` | `20260619110000_task_fail.sql` | `p_task_id uuid` | Segna un task come fallito |
| `task_next_recurring_date` | `20260520110000_fix_task_next_recurring_date.sql` | `p_task ts_tasks, p_base date` | Calcola la prossima data per task `recurring` |

Stessa regola per **Obiettivi**: il calcolo del progresso vive in `ob_objective_progress`, e
`ob_record_measurement` è l'**unico punto di scrittura** di una rilevazione — è lei a rifiutare un
voto fuori dalla scala della metrica, non il client
(`20260827110000_ob_metriche_semplificate.sql`), non il JS.
`ob_metric_current` è invece volutamente `SECURITY INVOKER`: riceve una riga `ob_metrics` dal chiamante,
quindi la RLS su `ob_measurements` deve restare attiva.

E vale **anche per le azioni di Obiettivi**, che sono la copia dei task
(`20260827130000_ob_actions.sql`):

| Funzione | Gemella di | Descrizione |
|---|---|---|
| `ob_action_complete(p_action_id, p_today)` | `task_complete` | Completa un'azione, tipo per tipo |
| `ob_action_skip(p_action_id, p_days)` | `task_skip` | La manda alla prossima volta |
| `ob_action_fail(p_action_id)` | `task_fail` | La segna fallita |
| `ob_action_next_recurring_date(p_action, p_base)` | `task_next_recurring_date` | Prossima data di una ricorrente |

⚠️ **La prossima occorrenza si calcola dalla vecchia occorrenza, non da oggi**, in tutt'e tre le
RPC e per tutti i tipi: `COALESCE(next_occurrence_date, start_date)` è la base, e `p_today` serve
solo a decidere se una singola è stata chiusa in ritardo. Verificato su un'azione scaduta dal 7
agosto e chiusa il 28: la ricorrente settimanale va al **14 agosto** (non al venerdì dopo oggi),
la «ogni 7 giorni» al 14, la singola saltata di 3 giorni al 10, la multipla alla data seguente del
suo elenco. Calcolarla da oggi salterebbe le volte arretrate senza dirlo.

Il comportamento per tipo è quello dei task, riga per riga. Due differenze **volute**, da non
"correggere" indietro:

1. la riga si cerca con `AND user_id = auth.uid()`. Le `task_*` sono `SECURITY DEFINER` e la RLS
   lì dentro non vale: senza quel filtro basta l'id di una riga altrui per completarla. Le `task_*`
   quel controllo non ce l'hanno, ma non è un motivo per rifare lo stesso buco;
2. si toccano le sole regole con `app = 'objectives'`: cancellare regole per `app = 'tasks'` da
   qui spegnerebbe le notifiche di un task che non c'entra.

⚠️ **Dal 7 ottobre 2026 le azioni HANNO i promemoria** (`20261007140000_ob_azioni_promemoria.sql`,
web v1.14.0, Smart Blocker 1.6.4), con lo stesso schema delle Piante: 📱 Telegram e 📲 Telefono coi
loro anticipi e 🔐 Smart Block all'ora dell'azione, una regola `cm_notification_rules` per canale
con `app = 'objectives'` e `entity_type = 'task'`, scritta da `sincronizzaRegole()` in
`obiettivi.html` (gemella di quella di `piante.html`) solo salvando il form. Dopo un ✅ o un ⏭ la
nuova scadenza la scrivono le RPC: `ob_action_complete` / `ob_action_skip` sono ora porte su
`ob_action_complete_core` / `ob_action_skip_core` (`p_user`, solo service_role, chiamata da
`notification-action` per il ✅ Fatto), e `ob__sposta_promemoria` sposta o toglie le regole.
`ob_smart_block_complete` è la porta dell'APK Smart Blocker (anon key, solo con un blocco in coda),
e `trg_ob_actions_togli_promemoria` toglie le regole di un'azione cancellata. Un'azione a libera
ripetizione, o salvata come conclusa, non ha promemoria. `ob_action_fail` non li tocca: non la
chiama più nessuno.

⚠️ **Le categorie delle azioni sono state tolte dalla pagina** (form, schede e filtro, web
v1.14.0): la colonna `ob_actions.categories` resta e chi l'aveva la conserva, ma non si legge e
non si scrive più.

Scrivere un'azione è invece un `insert`/`update` diretto e **non** una RPC, e non è un'eccezione:
le RPC governano il ciclo di vita — dove va la prossima occorrenza — non com'è fatta l'azione. È la
stessa divisione di `saveTask()` nel web e di `TaskForm` nel nativo.

Tutte le funzioni restituiscono `jsonb` con la struttura:
```json
{ "ok": true, "action": "completed|skipped|failed", "points": 10, "type": "single", "next": "<timestamptz>" }
```
In caso di errore: `{ "ok": false, "error": "messaggio" }`.

### Comportamento per tipo di task

#### `task_complete`
| Tipo | Comportamento |
|---|---|
| `single` | status → `terminated`, inserisce record `terminated` in history, elimina notification rules |
| `simple_recurring` | next = current + `repeat_after_days`, status → `completed`, aggiorna notification |
| `recurring` | chiama `task_next_recurring_date()`; se null → `terminated`; altrimenti → `completed` + aggiorna notification |
| `multiple` | trova prossima data in `multiple_dates[]`; se esiste → `completed`; altrimenti → `terminated` + elimina notification |
| `workflow` | controlla tutti gli step; se tutti done → `terminated`; se parziale → risponde senza modificare status |
| `free_repeat` | status → `completed`, aggiorna `last_completed_date`, nessuna prossima occorrenza |

#### `task_skip`
`p_days` è usato solo per il tipo `single` (quanti giorni spostare). Per tutti gli altri tipi viene ignorato.

| Tipo | Comportamento |
|---|---|
| `single` | next = current + `p_days`, status → `skipped`, aggiorna notification |
| `simple_recurring` | next = current + `repeat_after_days`, status → `skipped`, aggiorna notification |
| `recurring` | chiama `task_next_recurring_date()`; se null → errore; altrimenti status → `skipped` + aggiorna notification |
| `multiple` | trova prossima data in `multiple_dates[]`; se esiste → `skipped`; se era l'ultima → `terminated` + elimina notification |
| `free_repeat` | restituisce errore (non supporta skip) |

#### `task_fail`
| Tipo | Comportamento |
|---|---|
| `single` | status → `terminated`, inserisce record `terminated` in history, elimina notification rules |
| `simple_recurring` | next = current + `repeat_after_days`, status → `failed`, aggiorna notification |
| `recurring` | chiama `task_next_recurring_date()`; se null → `terminated`; altrimenti → `failed` + aggiorna notification |
| `multiple` | trova prossima data in `multiple_dates[]`; se esiste → `failed`; se era l'ultima → `terminated` + elimina notification |

### Pattern JS corretto (tasks.html)

```js
// CORRETTO — delega tutto al server
const { data: result, error } = await sb.rpc('task_skip', { p_task_id: id, p_days: 3 });
if (error || !result?.ok) { alert('Errore: ' + (error?.message || result?.error)); return; }
await loadTasks();
await loadHistory();
if (result.next) await updateSmartBlockFireAt(id, new Date(result.next).toISOString());

// SBAGLIATO — non calcolare mai la prossima data lato JS
// const nextDate = new Date(task.next_occurrence_date);
// nextDate.setDate(nextDate.getDate() + task.repeat_after_days);  // ← da non fare
```

### Dettaglio tecnico: estrazione data da `next_occurrence_date` per tipo `multiple`

Nelle RPC che gestiscono il tipo `multiple`, la data corrente viene estratta così:

```sql
-- CORRETTO
v_cur_str := COALESCE(v_task.next_occurrence_date::date::text, '');
-- → '2026-06-19'  ✓ confrontabile con multiple_dates[]

-- SBAGLIATO (non usare)
v_cur_str := split_part(v_task.next_occurrence_date::text, 'T', 1);
-- PostgreSQL formatta timestamptz come '2026-06-19 08:00:00+00' (spazio, non 'T')
-- → split_part restituisce l'intera stringa → confronto con 'YYYY-MM-DD' fallisce sempre
-- → v_cur_idx rimane NULL → il task viene terminato alla prima occorrenza (bug critico)
```

### Dettaglio tecnico: `v_time_of_day`

Tutte le RPC preservano l'orario originale del task quando calcolano la prossima occorrenza:
```sql
v_time_of_day := COALESCE(v_task.start_date, now())
                 - date_trunc('day', COALESCE(v_task.start_date, now()));
-- poi: v_next_ts := v_next_date::timestamptz + v_time_of_day;
```
Questo garantisce che un task impostato alle 09:00 rimanga alle 09:00 su ogni occorrenza successiva.

### Aggiornamento migration

Le migration vengono applicate **automaticamente** al push su `claude/**` tramite `.github/workflows/deploy.yml` (step `Apply Supabase migrations` → `supabase db push`). Non è necessaria nessuna azione manuale.

---

## Edge Functions e job schedulati

Le Edge Functions stanno in `supabase/functions/<nome>/index.ts` (Deno + TypeScript) e vengono
deployate **automaticamente** al push su `claude/**`: il workflow deploya solo le funzioni toccate
dal commit. I job periodici sono `pg_cron` + `net.http_post`, creati da migration con la service
role key letta dal vault (vedi `20260724320000_ca_revolut_auto_categorize_cron.sql` come modello).

| Funzione | Job | Cosa fa |
|---|---|---|
| `get-prices` | orario | Aggiorna `fnz_price_cache` (un trigger propaga su `fnz_price_history`). Una fonte diversa per tipo: BTPi→SoldiOnline, BTP→rendimentibtp, ETF→JustETF (scheda HTML)/Yahoo/Investing, crypto→CoinGecko in batch + Coinbase come ripiego, azioni→TwelveData/GoogleFinance |
| `enable-banking-connect` / `-callback` / `-aspsps` / `-refresh-accounts` | — | Collegamento di un conto: catalogo banche, avvio del consenso (`connect` riceve solo `institutionId` e legge banca e paese da `cm_institutions`), redirect di ritorno e rilettura dei conti di una sessione già ottenuta. Il callback crea i conti anonimi e riporta sempre su `finanza.html`, con il `session_id` del consenso perché la pagina apra subito il battesimo |
| `enable-banking-sync` | manuale (da `cost-analysis.html`) | Importa le transazioni di un conto in `ca_transactions` (Spese Famiglia) |
| `enable-banking-fondo-sync` | manuale (da `finanza.html`, scheda fondo) | Importa i bonifici di un conto in `fnz_fund_contributions`: CRDT → versamento (controparte = debtor), DBIT → prelievo (controparte = creditor), match su IBAN e poi su nome; senza match la riga entra come `da_rivedere` |
| `enable-banking-transactions` | manuale (da `conto-risparmio-teresa.html`, `conto-spese-teresa.html`, `spese-ada.html`, `casarosa.html` e da `finanza.html` → 🌹 Danaro di Rosa) | **Legge e basta**: restituisce movimenti (importo con segno, `card` quando la banca espone la carta usata) e saldi normalizzati di un conto, senza scrivere niente. Destinazione, categorie e controllo dei doppioni restano al chiamante — Conto Risparmio, Contribuzione e Spese Ada hanno già i propri |
| `notification-action` | manuale (da `telegram-webhook` e dall'APK nativo) | Che cosa fa un pulsante di un promemoria: ✅ Fatto, ⏸ rinvia, ❌ annulla. **L'unica implementazione**, chiamata sia dal bot sia dal telefono |
| `forziere-drive` | manuale (da `forziere.html`) | Il ponte col Drive del Forziere: crea la cartella (e una sottocartella per scomparto, con `mkdir`/`rmdir`/`move`), apre i caricamenti, restituisce e cancella i file. ⚠️ **Non vede mai niente in chiaro** — tutto quel che le passa davanti è già cifrato con OpenPGP dalle 24 parole, che qui non arrivano né adesso né mai. Il caricamento **non passa di qui**: `upload-url` chiede a Google un indirizzo ripristinabile e i byte vanno dal browser a Google diretti (con ripiego su `put` per i file piccoli, se quella strada è bloccata). Lo scaricamento passa — Drive non ha indirizzi firmati — ma **in flusso** |
| `al-food-search` | manuale (da `calorie.html`) | **Legge e basta**: cerca un alimento per nome o per codice a barre nelle banche dati pubbliche e lo restituisce **già normalizzato**. Fonti in ordine: Open Food Facts Search-a-licious, la vecchia `/cgi/search.pl` come ripiego, e USDA FoodData Central se c'è il secret `USDA_API_KEY`. Ogni fonte torna col suo esito (HTTP, tempo, errore) |
| `pv-ai` | manuale (da `piante.html` e dall'APK nativa) | «Chiedi all'IA» delle Piante: col JWT dell'utente (quindi sotto RLS) raccoglie scheda, ultime 15 voci di diario, azioni in corso e fino a **quattro foto** (copertina + le più recenti del diario, come indirizzi firmati di dieci minuti), le manda all'IA **scelta nella tendina** — **Gemini** (`GEMINI_API_KEY`, modello `GEMINI_MODEL`, di partenza `gemini-2.5-flash`; le foto vanno inline in base64), **Qwen** via Alibaba DashScope internazionale, compatibile OpenAI (`DASHSCOPE_API_KEY`; `qwen-plus`, o `qwen-vl-max` quando ci sono foto; `QWEN_MODEL`/`QWEN_VL_MODEL`/`DASHSCOPE_BASE_URL` per cambiarli), **Groq** (piano gratuito, `GROQ_API_KEY`; il modello **non è scritto da nessuna parte**: `scegliModelloGroq` chiede a Groq l'elenco `/models`, cache di un'ora, e prende il primo dei preferiti che c'è ancora o altrimenti il più grande; con le foto un modello *vision*, e se non ce n'è nessuno risponde sul solo testo dicendolo. `GROQ_MODEL`/`GROQ_VL_MODEL` restano facoltativi e valgono solo se quel modello è ancora nell'elenco — Groq toglie i modelli senza preavviso) — Qwen e Groq passano dalla stessa `chiediCompatibile`, perché parlano tutt'e due il dialetto OpenAI — o **Claude** (`ANTHROPIC_API_KEY`, `claude-opus-5`, thinking adattivo, effort `medium`, fallback lato server) — e salva la risposta in `pv_ai_answers` con `provider` e `model`. `{action:'fornitori'}` dice quali IA hanno la chiave: la tendina offre solo quelle. ⚠️ Un'IA scelta senza chiave **lo dice** e non ripiega su un'altra di nascosto. Senza `plant_id` il contesto è la lista dei desideri più le piante che si hanno già |
| `ia-tunnel` | manuale (da `chiedi-a-pino.html`) | Il prompt di Chiedi a Pino, **così com'è** e con le sue immagini (indirizzi firmati di dieci minuti), al motore scelto (Gemini, Qwen, Groq, Claude — stessi Secrets di `pv-ai`); archivia domanda, risposta, motore, modello e tag in `ia_tunnel_*`. `{action:'fornitori'}` come in `pv-ai`. ⚠️ Le chiamate ai quattro motori sono **copiate** da `pv-ai`, foto comprese: cambiando un fornitore là, portalo qui |
| `spese-in-giro-foto` | manuale (dall'APK Spese in giro) | Gli scontrini di «Spese in giro»: carica una foto, ne firma l'URL per un'ora e la cancella. ⚠️ È il **solo** ponte fra un'APK senza login e lo Storage: risolve il token del telefono con `vg_viaggio_del_token` (eseguibile dal solo service role) e scrive dentro la cartella di **quel** viaggio, `<viaggio_id>/…`. Per leggere si passa l'id della **voce**, mai il percorso: dove sta quella foto lo dice il database |
| `save-snapshot` | `fnz-save-snapshot`, 21:00 UTC, e da `finanza.html` | Chiama `get-prices`, poi calcola e salva lo snapshot del patrimonio in `fnz_dashboard_snapshots` per ogni utente che ha dati di Finanza. Chiamata col JWT di un utente (Finanza all'apertura e dal 💾) scrive il **suo solo** snapshot e non aggiorna i prezzi; la chiave anon riceve 401. È l'**unica** a scrivere lo snapshot |

### ⚠️ Un prezzo può essere insieme plausibile e sbagliato

Il modo peggiore in cui `get-prices` si rompe non è restare senza prezzo — quello si vede, la
riga in Finanza → Prezzi diventa arancione «Non oggi». È **scrivere in cache un numero che
sembra un prezzo e non lo è**: la riga resta verde «OK» e il patrimonio è falso senza che da
nessuna parte compaia un errore.

È successo il 13 agosto 2026 con **BTP10** (Amundi Ita BTP 10y, quota ≈157 €), finito in cache a
poche unità di euro. La causa: `/api/etfs/{ISIN}/performance` di JustETF veniva letto come un
listino, ma il suo `latestValue` — come i `positions[].value` — è una **performance in
percentuale**, e `valuation=NAV` non cambia la natura di quei numeri. Era la stessa trappola già
annotata nel codice per il `latestValue` della pagina HTML: **lo stesso nome di campo, sulla
stessa fonte, vuol dire due cose diverse dal prezzo.** L'endpoint è stato tolto (v5.18.0): per
gli ETF resta lo scraping della scheda HTML, poi Yahoo, Investing.com ed Euronext.

Due regole che ne discendono, entrambe già in codice:

- **ogni prezzo entra da un varco solo**, `pushPrice()`, che arrotonda, confronta con l'ultimo
  prezzo noto e scarta chi se ne discosta di oltre il 50 % (`MAX_PRICE_DEVIATION`; le crypto sono
  escluse, quel movimento lo fanno davvero). Una fonte scartata non ferma il giro: si passa alla
  successiva, e se non risponde nessuna resta il prezzo di ieri — un buco è visibile, un numero
  sbagliato no;
- **il confronto si disarma da sé** dopo tre giorni senza scritture (`PREV_PRICE_MAX_AGE_MS`),
  altrimenti si morde la coda: un valore sbagliato in cache (o uno split vero) farebbe scartare
  per sempre i prezzi giusti che arrivano dopo. Finché una fonte risponde `updated_at` si
  riscrive ogni ora anche a mercati chiusi, quindi tre giorni non sono un fine settimana.

Aggiungendo una fonte nuova: il prezzo va restituito e passato a `pushPrice()`, mai messo in
`rows` a mano — e prima di fidarsi di un campo JSON conviene guardare **cosa misura**, non come
si chiama.

### ⚡ get-prices v5.21+: fonte ricordata, simboli in parallelo

- **`fnz_price_cache.source` / `source_ref`** (`20261007220000_fnz_price_cache_fonte.sql`):
  la fonte che ha dato l'ultimo prezzo e l'identificativo usato lì (ticker Yahoo trovato
  dall'ISIN, simbolo Twelve Data risolto, mercato Euronext). Al giro dopo
  `provaFonteRicordata()` prova **prima quella e da sola**; se non risponde si rifà la catena
  completa nello stesso ordine di sempre, e la fonte che vince diventa la nuova ricordata.
  ⚠️ Non vale per il ticker Yahoo fissato a mano (che resta in testa comunque), le crypto e i
  BTP di rendimentibtp.it (scaricati in blocco). ⚠️ `source_ref` dipende dall'ISIN: **cambiando
  l'ISIN di un prodotto, azzera `source` della sua riga di cache**, o si continuerebbe a
  chiedere il vecchio ticker (resta a fare da rete solo il controllo del 50 %).
- **`CONCORRENZA` = 4 simboli insieme**; ogni `fetch` esterna ha un tetto di 12 s (`fetchT`).
- ⚠️ **Twelve Data va a turni (`tdTurno`), non a pause**: ogni chiamata a TD (quote,
  symbol_search, cambio valuta) prenota il suo posto a 8 s dall'ultima, in modo sincrono,
  quindi il limite di 8/minuto regge anche coi simboli in parallelo. Una chiamata nuova a TD
  deve passare di lì.
- Se le due colonne mancano (migration non applicata) la funzione lo scrive in `fn_logs` e
  lavora senza, come prima.
- ⚠️ **Bilancio di tempo (v5.22.0)**: il gateway chiude dopo 150 s senza risposta (504
  `IDLE_TIMEOUT`, visto in Finanza al primo giro della v5.21.0). Dopo 105 s non si comincia
  più nessun simbolo (i rimasti li fa il giro dopo, e `fn_logs` li elenca come *rimandati*),
  entro 130 s si chiude anche quel che è in corso: `fetchT` usa come tetto il tempo che resta
  e `tdTurno` non prenota un turno oltre la scadenza.
- **I cambi valuta vengono prima da Yahoo** (`USDEUR=X`), Twelve Data solo come ripiego: ogni
  conversione da TD costava un turno da 8 s.

### ⚠️ Header PSU obbligatori per alcune banche

Il catalogo `/aspsps` di Enable Banking dichiara per ogni banca un `required_psu_headers`:
**UniCredit (IT) richiede `psu-ip-address`**, Revolut no. Tutte le chiamate a Enable Banking
passano quindi gli header PSU ricavati da `x-forwarded-for` (`psuHeaders()`, duplicata in ogni
Edge Function). Un job schedulato non ha un utente davanti: in quel caso l'header non c'è e non
va inventato. Mandarlo è doveroso, ma **non è mai stato la causa del consenso vuoto**: quella è
la whitelist (sezione qui sotto).

Stessa scheda di catalogo: `maximum_consent_validity` (180 giorni per UniCredit) e
`auth_methods` per `psu_type`. Il pulsante *ℹ️ Cosa richiede questa banca* in `finanza.html`
la mostra per intero — è il primo posto da guardare quando un collegamento riesce a metà.

### ⚠️ Restricted mode: i conti vanno messi in whitelist, o la sessione torna vuota

**L'applicazione Enable Banking è in _restricted mode_** (attivata con *«Activate by linking
accounts»*, senza contratto di produzione piena). In quella modalità l'API restituisce
**soltanto i conti collegati nel Control Panel** con *«Link accounts»*: per un conto mai
collegato lì il consenso viene autorizzato, la banca mostra il conto spuntato, e la sessione
torna con `accounts: []` e `access.accounts: null` — **senza nessun errore**, perché per Enable
Banking non è un errore ma il filtro previsto.

#### Collegare un conto nuovo: la whitelist viene prima

Non è una procedura di emergenza, è **il primo passo di ogni collegamento**. Saltarlo costa un
giro di login e SCA in banca per niente.

1. Control Panel di Enable Banking → l'applicazione → **«Link accounts»**
2. autenticazione presso la banca e autorizzazione del conto
3. **solo adesso** il collegamento dall'app (Finanza → Configurazione → 🏦 Banche e Conti)

L'applicazione resta «Restricted» ma «Active»: è lo stato normale finché non c'è un contratto di
produzione piena.

#### Perché è costato tre giorni (agosto 2026)

Revolut funzionava perché quel conto era stato collegato all'attivazione dell'applicazione;
UniCredit no perché non lo era mai stato — e il sintomo era indistinguibile da un bug nostro.
**Prima di cercare la causa nel codice, verificare la whitelist.** Verificato funzionante il
6 agosto 2026: messo il conto UniCredit in whitelist, il consenso rifatto dall'app ha
restituito il conto al primo colpo.

Corollario: nessuna modifica a `enable-banking-connect` / `-callback` può aggirarlo — IBAN in
`access.accounts`, header PSU, `auth_method`, `psu_type` e durata del consenso sono tutti
irrilevanti se il conto non è collegato. Sono già stati provati tutti, uno per uno.

Nota di metodo: la risposta era nella documentazione di Enable Banking
(*Whitelisting own accounts for restricted API usage*). Il primo tentativo di leggerla è
fallito con un 403 del proxy e l'indagine è proseguita per ipotesi sul codice: quando un
sintomo non torna, **cercare nella documentazione del fornitore prima di dedurre dal
comportamento** — e insistere da un'altra fonte se la prima non risponde.

### Consenso vuoto: la richiesta non c'entra

Una sessione `AUTHORIZED` con `access.accounts: null` e zero conti **ha una causa sola, ed è la
whitelist** (sezione qui sopra). Per tre giorni si è cercata nella richiesta: IBAN in
`access.accounts`, header PSU, `auth_method`, `psu_type`, durata del consenso. Le richieste del
5 e del 6 agosto 2026 sono partite con `accounts: [{iban}]` e `psu-ip-address` inviato, e sono
tornate identiche a quelle senza — `access.accounts: null`, `accounts: []`, `accounts_data: []`.
**Sono ipotesi bruciate: non ripercorrerle.**

Di conseguenza `enable-banking-connect` non manda più nessun `access.accounts`, l'IBAN non si
chiede più da nessuna parte e la riga `consent_request` in `cm_sync_log` — che esisteva solo per
distinguere «IBAN spedito» da «IBAN non spedito» — è stata ritirata insieme al resto della
diagnostica (dump della risposta di Enable Banking compreso). `cm_sync_log` è tornato a essere
il registro delle sincronizzazioni.

Se un conto in whitelist continua a non comparire, restano due cose da guardare, entrambe fuori
dal nostro codice: la **selezione dei conti sulla pagina della banca** (confermare senza
spuntare niente dà lo stesso esito) e il **tipo di conto** — PSD2 copre i conti di pagamento, e
un deposito o un libretto vincolato la banca può non esporlo affatto.

`state.replaceConnectionId` (pulsante *🔁 Rifai il consenso*) dice che il nuovo consenso
sostituisce un collegamento rimasto senza `account_id`: il callback sposta i `fnz_funds` sul
nuovo, gli **eredita `display_name`, `uses` e `owner_person_id`** del vecchio e poi lo cancella.
I due passaggi che contano sono spostare i fondi ed ereditare gli usi: a mano ci si dimentica, e
il fondo resta agganciato a una riga che non sincronizzerà mai, oppure il conto rifatto torna
«da battezzare» e sparisce dagli elenchi dei moduli che lo stavano già usando.

### ⚠️ Ora legale nei cron

`pg_cron` lavora in **UTC e non conosce il cambio ora**. Gli schedule sono scritti per l'ora legale
(CEST, UTC+2): a fine ottobre, con il ritorno all'ora solare, vanno spostati avanti di un'ora o i
job scatteranno un'ora prima del previsto. Riguarda `fnz-save-snapshot` (`0 21 * * *` = 23:00 CEST)
e `revolut-auto-categorize`.

### 📈 Lo storico delle valutazioni degli asset (`fnz_other_asset_values`)

(8 ottobre 2026, `20261008100000_fnz_other_asset_values.sql`, Finanza v1.28.0.) Una riga per
valutazione: `asset_id`, `valuation_date`, `value`, `note`. Nel popup dell'asset (💎 Patrimonio →
✎) al posto di «Valore / Data valutazione» c'è la lista delle voci, col grafico dell'andamento da
due voci in su, ✏️/🗑 su ogni riga e «➕ Nuova voce». La lista si tiene in memoria e si scrive col
**Salva** dell'asset (`salvaAssetVals`): «Annulla» butta via anche le voci aggiunte.

⚠️ **`fnz_other_assets.value` / `valuation_date` restano, e li scrive il TRIGGER**
(`trg_fnz_other_asset_values_sync`) con la voce più recente: Dashboard, snapshot, Possibili
soluzioni, Danaro di Rosa e le pagine ospiti leggono la colonna di sempre. La pagina non la scrive
più — nemmeno *🏦 Leggi dal conto* di Danaro di Rosa, che ora aggiunge una voce allo storico.
⚠️ Cancellare tutte le voci **non** azzera l'asset: il trigger non trova righe e lascia la colonna
com'era. La migration ha copiato il valore di ogni asset come prima voce.

### ⚠️ Il regime fiscale degli asset (`fnz_other_assets`)

Due colonne (`20260901180000_...`): **`tax_regime`** dice con quale aliquota, **`cost_basis`** su
quale base. Si compilano dal form dell'asset (💎 Patrimonio), e servono a **un posto solo**: le
**dotazioni** delle 🛡️ Possibili soluzioni, dove il valore di un asset compare al netto. Elenco asset,
Dashboard, snapshot e totali del patrimonio restano al lordo.

| `tax_regime` | Aliquota | Base |
|---|---|---|
| `CAP. GAIN 26%` | 26 % | plusvalenza |
| `AGEVOLATA 12.5%` | 12,5 % | plusvalenza |
| `TFR SEPARATA` | **stima 27 %** | **importo intero** |
| `PIR`, `ESENTE` | 0 % | plusvalenza |
| `ALTRO` | — | nessuna stima |
| NULL | — | nessuna stima |

⚠️ **`TFR SEPARATA` è l'unico che tassa l'importo intero**, ed è il motivo per cui esiste: il TFR
è tassato tutto a tassazione separata, non sulla sola rivalutazione — lì il costo di acquisto non
c'entra e non serve.

⚠️ **Il 27 % è una stima dichiarata, non una costante di legge**: l'aliquota della tassazione
separata è l'aliquota media IRPEF del reddito di riferimento degli ultimi cinque anni, quindi
cambia da persona a persona e sta fra il 23 % e il 30 % per la gran parte dei dipendenti (fonti
consultate il 1° settembre 2026: [centrofiscale.com](https://centrofiscale.com/tassazione-tfr-2026-aliquote-calcolo-netto-esempi/),
[fiscoinvestimenti.it](https://fiscoinvestimenti.it/tassazione-tfr-2026-aliquota-media/) — più
lungo il servizio, più bassa l'aliquota). `TAX_TFR_SEPARATA` la porta in chiaro e la pagina la
scrive accanto al numero: una percentuale che sembra esatta e non lo è sarebbe peggio di una
stima dichiarata. ⚠️ L'imposta sostitutiva del **17 %** sulla rivalutazione annua è un'altra cosa,
già trattenuta anno per anno: non è questa e non si somma.

⚠️ **NULL non vale 26 %, al contrario dei prodotti**: su uno strumento finanziario il 26 % è il
regime ordinario, su una casa o un'auto un default non esiste — e metterlo scriverebbe una tassa
dove non ce n'è nessuna. Regime assente, `ALTRO`, o costo di acquisto mancante su un regime che
tassa la plusvalenza: **nessuna imposta stimata**, il valore resta il lordo e l'etichetta della
dotazione dice *perché* (`assetTax().motivo`). Non si inventa una tassa, e non si tace che manca.

### ⚠️ Il valore netto dei portafogli: si affianca al lordo, non lo sostituisce

`finanza.html` mostra accanto al valore di un portafoglio anche il **valore al netto delle
imposte sulle plusvalenze** — nella Dashboard (riga sotto il totale e colonna *Netto tasse*),
nell'elenco 💼 Portafogli, nel dettaglio di un portafoglio (KPI e le colonne *Tasse* / *Netto* su
ogni posizione) e nel dettaglio di un Dossier.

L'aliquota è il **tag `TASSAZIONE`** di `fnz_products.tags` — lo stesso che si sceglie dal form
del prodotto e che si vede già come colonna:

| Tag | Aliquota |
|---|---|
| `CAP. GAIN 26%` | 26 % |
| `AGEVOLATA 12.5%` | 12,5 % |
| `PIR`, `ESENTE` | 0 % |
| `ALTRO`, o tag assente | **26 %**, e la pagina lo dice |

⚠️ **Un prodotto senza tag si tassa al 26 %, non a zero**: è l'aliquota ordinaria italiana, e uno
0 % silenzioso gonfierebbe il patrimonio proprio dove si sta cercando di essere prudenti. Le
posizioni in guadagno senza tag si contano in un avviso sopra la tabella e portano un ⚠️ nella
cella della tassa: un default che non si vede è un numero sbagliato che sembra vero.

⚠️ **La tassa si calcola posizione per posizione sulla sola plusvalenza, ed è zero dove si è in
perdita**: le minusvalenze **non compensano** le plusvalenze di un'altra posizione. Quella
compensazione passa dallo zainetto fiscale — quando è stata realizzata, entro quanti anni, su
quali strumenti — e nessuno di quei dati sta in questa app: contarla darebbe un numero più bello
e sbagliato. ⚠️ **La liquidità non si tassa**: non è una plusvalenza, sono soldi versati e mai
investiti, quindi entra intera nel netto.

⚠️ **Lo snapshot resta al LORDO, ed è voluto**: `fnz_dashboard_snapshots`, il Patrimonio Netto
della Dashboard, la Edge Function `save-snapshot` e
`fetchPortfolioLiveValue` in `index.html` non conoscono le tasse. `computeHoldings` e
`portfolioStats` hanno campi in più (`taxRate`, `taxDeclared`, `taxDue`, `netValue`,
`taxUndeclared`) ma i totali che la Edge Function replica — `totalValue`, `totalCost`, `pnl` —
sono **identici a prima**: la tripla copia dello snapshot resta allineata senza toccare niente,
e la serie storica continua a confrontare grandezze omogenee. Portando il netto nello snapshot,
i valori salvati fino a oggi diventerebbero non confrontabili con quelli nuovi.

### 📈 L'andamento di un portafoglio, giorno per giorno

Nel dettaglio di un portafoglio (`renderPortafoglioDetail`), sotto le posizioni, un grafico dice
**come il valore è arrivato a quello che è**: una linea sola, un punto per snapshot, col periodo
che si sceglie dalla stessa tendina del grafico del patrimonio (`TIMEFRAME_OPTS`, di partenza
3 mesi).

⚠️ **I numeri non si ricalcolano: sono gli snapshot.** Ogni punto è
`fnz_dashboard_snapshots.details.portfolios[].total_value` di quel giorno — lo stesso `totalValue`
del KPI *Valore portafoglio (quota)*, **liquidità compresa**. Rifare il conto sui prezzi di oggi
darebbe quel che il portafoglio varrebbe **adesso** con le posizioni di allora, che è un'altra cosa
e non è mai stata vera.

⚠️ **`S.dashboardSnapshots` NON basta**, ed è la ragione per cui questa lettura è a sé: quella
query porta le sole colonne di testa (patrimonio, portafogli, asset, debiti) e non il dettaglio per
portafoglio. Quello sta in `details`, che si porta dietro anche **tutte le posizioni di tutti i
portafogli** — un anno di snapshot sono megabyte. `caricaPtfSnapshots()` legge quindi **solo
aprendo questa vista**, **solo sul periodo scelto** (`snapshot_date=gte.…`) e tiene il risultato in
cache (`S.ptfSnaps`) finché il periodo non cambia. Metterlo nel caricamento della pagina vorrebbe
dire pagarlo a ogni apertura di Finanza, per un grafico che magari non si guarda.

⚠️ **Un giorno senza snapshot non è un giorno a zero**: è un giorno in cui nessuno ha aperto
Finanza e il job delle 23:00 non ha scritto. Quei giorni non ci sono affatto e la linea unisce
quelli che ci sono — l'asse x è **a indici e non a date**, come nel grafico del patrimonio, quindi
una settimana saltata si stringe invece di aprire un buco. La didascalia scrive **quanti giorni**
ci sono in archivio, che è il modo di accorgersene.

⚠️ **Un portafoglio che quel giorno non c'era vale `null` e non zero**: il punto non si disegna e
la linea si spezza, invece di dire che valeva niente. È la stessa scelta di `amount` NULL.

⚠️ **Il disegno passa da `_buildSingleSeriesSvg`, la stessa del grafico del patrimonio**, che ha
preso tre opzioni facoltative sul pallino (`valore`, `azione`, `raggio`/`bordo`) coi **default di
prima**. L'andamento di un portafoglio passa `azione: null`: lì un clic aprirebbe lo snapshot
dell'**intero patrimonio**, cioè porterebbe via dal portafoglio che si sta guardando. Il fumetto al
passaggio del mouse resta, perché si aggancia a `.chart-dot[data-snap-date]` e non all'azione.

⚠️ **Il `viewBox` segue la larghezza vera della finestra** (`window.innerWidth − 120`, fra 360 e
900): l'SVG è disegnato con `preserveAspectRatio="none"`, quindi riempie il riquadro
**deformando** quel che c'è dentro, e con una larghezza fissa da 800 su un telefono da 360 px le
date e gli importi si schiacciano a metà e non si leggono più — che è il difetto che il grafico del
patrimonio ha ancora. Si rilegge a ogni ridisegno: ruotando il telefono senza toccare niente resta
quella di prima, ed è l'unica imprecisione che la scelta si porta dietro.

⚠️ **Il pallino si stringe quando i giorni sono fitti** (e sopra i 60 punti perde anche l'anello
bianco): a 90 punti su una riga quelli da 3,5 px si toccano fra loro e coprono la linea — cioè
proprio la cosa che si sta guardando — e l'anello la spezza in trattini, disegnando un andamento a
tratti che nei dati non c'è.

⚠️ **La vista 📈 Portafoglio di `situazione-teresa.html` questo grafico NON ce l'ha**: gli snapshot
sono di Salvatore e la RLS ospite non li comprende. Portarcelo vorrebbe dire aprire
`fnz_dashboard_snapshots` a quella pagina, che è una decisione sui dati e non una modifica di
forma.

⚠️ **Una barra dell'allocazione era stata fatta e ritirata lo stesso giorno** (v1.22.0): una barra
sola col totale e le percentuali dentro le fette. Era un fraintendimento della richiesta — quel che
serviva era l'andamento — e il codice sta nella storia di git (`allocBarHtml` / `allocBarFit`).
Resta invece la correzione che ne è uscita: `drawChart` **ripete** la tavolozza oltre la nona fetta
invece di finirla con uno `slice`, che lasciava senza colore le posizioni dalla decima in poi.

### La descrizione di un prodotto (`fnz_products.description`)

Che cos'è quello strumento e, se è un fondo o un ETF, che cosa contiene. Si scrive dal form del
prodotto (💼 Prodotti → ✎) e si legge **dovunque il prodotto compaia** — posizioni di un
portafoglio, posizioni di un dossier, elenco movimenti, elenco prodotti, pagina Prezzi — come
tooltip appeso a una ℹ️; nella finestra delle transazioni di una posizione, che è già dedicata a
quel prodotto, si legge invece **per esteso** invece che dietro un'icona.

⚠️ **Non è il `title` nativo**: quello al tocco non compare, e sul telefono è lì che serve. Il
riquadro è nostro (`mostraProdTip()`), in `position: fixed` sul body — dentro una tabella che
scorre di lato l'overflow lo ritaglierebbe — e si apre col mouse al passaggio, col dito toccando
la ℹ️, dove **resta aperto** finché non si tocca altrove. ⚠️ La chiusura al clic altrove è in
**cattura**: nella fase di bolla arriverebbe dopo il `case 'prod-info'` che lo riapre, e la ℹ️
toccata due volte non si chiuderebbe mai.

⚠️ **NULL non è stringa vuota**, ed è la stessa scelta delle caselle di `fnz_income`: la casella
lasciata vuota cancella la descrizione invece di salvare `''`, e la ℹ️ **compare solo dove la
descrizione c'è** — un'icona su ogni riga smetterebbe di distinguere le righe che hanno qualcosa
da dire.

⚠️ **I 34 prodotti in archivio nascono già descritti**
(`20260906130000_fnz_products_descrizioni.sql`), e l'UPDATE tocca **solo le righe con
`description` NULL**: una descrizione corretta a mano dall'app è più vera di quella scritta lì, e
un UPDATE cieco la riscriverebbe in silenzio alla prima riesecuzione. Il confronto è sul
**simbolo** e non sull'ISIN — le sei crypto un ISIN non ce l'hanno, e un JOIN per ISIN le
lascerebbe fuori senza dirlo. Le descrizioni non contengono prezzi, valori né giudizi di
convenienza: quelli cambiano, e lì resterebbero scritti per sempre.

⚠️ **Il blocco è duplicato in `situazione-teresa.html`** (`pfInfoProdotto` / `pfMostraTip`, gemelli
di `infoProdotto` / `mostraProdTip`), per la vista 📈 Portafoglio: stesse classi CSS, stesso
comportamento. Se lo cambi in una, portalo nell'altra. Nessun cambio di RLS è servito — le policy
di `fnz_products` sono per riga, e la pagina ospite legge `select=*`.

### ⚠️ Lo snapshot lo scrive un posto solo, il calcolo dal vivo resta in due

✅ **Lo snapshot lo scrive SOLO la Edge Function `save-snapshot`** (v1.2.0, 25 settembre 2026):
il job delle 23:00 per tutti gli utenti, e `finanza.html` — all'apertura (`autoSaveSnapshot`) e
dal 💾 — chiamandola col JWT dell'utente, che le fa scrivere il **suo solo** snapshot e senza
aggiornare i prezzi. Fino alla v1.24.1 la pagina ne costruiva uno suo (`buildSnapshotPayload`),
copia a mano di quello della funzione: due fotografie dello stesso giorno fatte da due
implementazioni. ⚠️ La data la decide il server, in **ora di Roma**: la pagina usava
`toISOString()`, cioè UTC, e dopo mezzanotte scriveva sul giorno prima.

⚠️ **La funzione ora controlla chi la chiama** (`chiChiama`): passano il service role (il cron) e
un JWT `authenticated`; la chiave anon riceve 401. Fino alla v1.1.0 non controllava niente, e con
la sola chiave pubblica chiunque poteva far girare lo snapshot di tutti gli utenti e lo scraping
dei prezzi. La firma la verifica la piattaforma: `verify_jwt` **deve restare acceso** su questa
funzione, o quel controllo leggerebbe il ruolo da un JWT che nessuno ha verificato.

Resta invece in **due copie da tenere allineate** il calcolo che la dashboard mostra dal vivo:

| Dove | Perché |
|---|---|
| `finanza.html` — `portfolioStats`, `computeLoanValue`, `computePricesFromHistory` | Il browser deve calcolare gli stessi numeri per disegnare la dashboard |
| `supabase/functions/save-snapshot/index.ts` — stesse funzioni riscritte in TypeScript | È lei a scrivere lo snapshot |

**Se cambi una di quelle funzioni in `finanza.html`, cambiala anche nella Edge Function**, altrimenti
lo snapshot diverge da quello che l'app mostra a schermo.

✅ **Posizioni e liquidità NON sono più duplicate** (24 settembre 2026,
`20260924140000_fnz_posizioni_liquidita.sql`): quantità e costo medio ponderato li dà la RPC
**`fnz_posizioni`**, la liquidità **`fnz_liquidita`**, e le leggono tutt'e quattro i posti che
prima portavano una copia a mano della regola — `finanza.html` (`computeHoldings`,
`computePortfolioCash`, `portfolioNetSpent`), la Edge Function `save-snapshot`, `index.html`
(`fetchPortfolioLiveValue`) e `situazione-teresa.html` (`pfHoldings`, `pfCash`). **Una modifica a
quella regola si fa nel SQL e basta.**

⚠️ **Nel SQL è scesa la parte sequenziale, non il valore.** Le vendite tolgono costo al costo
**medio del momento**, e la liquidità è versato − speso: sono i due conti che si sbagliano. Il
valore — quantità × prezzo — resta nei client **con la loro fonte di prezzi di sempre**
(`fnz_price_history` in Finanza, nella home e nello snapshot, `fnz_price_cache` nella pagina di
Teresa): unificarla avrebbe spostato di qualche euro i numeri rispetto agli snapshot già in
archivio, cioè una serie storica che non si confronta più con sé stessa.

⚠️ **`SECURITY INVOKER`, con `p_user` per il service role.** Chi chiama vede quel che la RLS gli
concede — Salvatore le sue righe, Teresa il solo Conto Risparmio — mentre la Edge Function, che la
RLS non ce l'ha, passa `p_user` per restringere a un utente. NULL = «quel che vedo».

⚠️ **`fnz_posizioni` porta `ordine`**, cioè i prodotti nell'ordine della loro prima transazione:
è l'ordine in cui la `Map` di `computeHoldings` li ha sempre messi, e con lui le righe di
`details.holdings` nello snapshot.

⚠️ **Senza posizioni lo snapshot NON si scrive**: nella Edge Function una RPC che non risponde
salta quell'utente (e Finanza, che lo snapshot lo chiede a lei, lo dice), e in home
l'avviso del totale non compare. Coi portafogli a zero sarebbe una fotografia falsa in archivio,
che è peggio di nessuna.

⚠️ Verificato prima di pubblicare: su 400 transazioni casuali (acquisti, vendite, commissioni,
dossier, fondi con versamenti di ogni stato) le due RPC danno gli stessi numeri del JavaScript di
Finanza — 98 confronti, scarto massimo 1,5·10⁻¹¹, stesso ordine dei prodotti.

⚠️ **Un'eccezione dichiarata**: `computeLoanValue(loan, aData)` ha un secondo parametro
**facoltativo** — la data a cui calcolare il residuo, che serve alla ⏳ macchina del tempo di
🛡️ Possibili soluzioni. Senza data si resta a oggi, quindi ogni altro chiamante legge
esattamente il numero di prima e la gemella nella Edge Function — che calcola sempre e solo
oggi — **non va toccata**. Le due restano allineate perché il parametro non cambia nessun
risultato di chi non lo passa. La duplicazione è voluta — il
client non può delegare tutto al server perché gli servono comunque i valori live per la dashboard —
ma non è gratis. Il modo per accorgersene: far girare le due implementazioni sugli stessi dati e
confrontare i quattro totali e il JSON `details`, devono coincidere esattamente.

`index.html` (`fetchPortfolioLiveValue`) calcola il valore dei portafogli per l'avviso "Totale
portafogli" della home, leggendo posizioni e liquidità dalle stesse due RPC.

Nel valore di un portafoglio rientra anche la **liquidità**: i soldi versati nel fondo
collegato (`fnz_funds.linked_portfolio_id`) e non ancora usati per comprare titoli. Il
portafoglio non ha un conto di cassa — esistono solo `BUY` e `SELL` — quindi si ricava per
differenza: versamenti nominali che fanno quota (`auto`/`confermato`) meno acquisti con
commissioni, più il netto delle vendite, mai sotto zero — ed è `fnz_liquidita`. Un portafoglio può
essere **tutta liquidità e nessun titolo** (il Conto Risparmio): chi somma i portafogli deve
partire dall'elenco dei portafogli, non da quello delle transazioni, o quel valore sparisce
in silenzio da un totale solo. P&L e variazione
giornaliera restano invece sui soli titoli: la liquidità non guadagna né perde, e sommarla al
valore senza sommarla al costo la farebbe comparire come utile il giorno che entra sul conto.

---

## Notifiche — tre canali, e i pulsanti in un posto solo

Il giro è sempre lo stesso e sta tutto attorno a **una riga per canale**:

```
cm_notification_rules  (una riga per user + app + entità + CANALE)
  → fill-notification-queue (cron, ogni 6 h) riempie cm_notification_queue con un fire_at
      → send-notifications (ogni 5 min) consegna le righe 'telegram' e 'android'
      → l'APK Smart Blocker si prende in polling le righe 'smart_block'
```

| `channel` | Dove arriva | Chi la consegna |
|---|---|---|
| `telegram` | Il bot, coi bottoni inline | `send-notifications` |
| `android` | L'APK **nativo** (`com.garsal.appsphere`), come push FCM | `send-notifications` |
| `smart_block` | L'APK Smart Blocker, che blocca lo schermo | l'APK, in polling |

⚠️ **`telegram` e `android` sono la stessa notifica su due strade e arrivano tutt'e due**: due
righe di coda, nate da due regole, consegnate indipendentemente. Il telefono spento non deve far
sparire il promemoria da Telegram, ed è il motivo per cui non c'è nessuna logica del tipo «se il
telefono ha risposto non mandare al bot».

### ⚠️ I pulsanti hanno UNA implementazione, e non sta nel client

✅ Fatto, ⏸ rinvia e ❌ annulla vivevano dentro `telegram-webhook`. Ora stanno in
`notification-action`, che chiamano **sia il bot sia il telefono**: al webhook resta solo quel che
è di Telegram (rispondere al bottone, togliere i messaggi dalla chat). Riscrivere quelle regole in
Kotlin per la notifica Android avrebbe voluto dire due implementazioni di completamento, punti e
archivi — cioè due esiti diversi per lo stesso promemoria il giorno che una delle due cambia. È la
stessa scelta delle RPC del ciclo di vita dei task.

Dentro `notification-action`:

- **complete** — esegue gli insert di `metadata.completion_update` risolvendo i segnaposto
  (`{{fire_date_local}}`, `{{slot_time}}`, `{{monday_of_week}}`, `{{day_of_week_n}}`), poi chiama
  `task_complete` o `habit_post_completion`. ⚠️ I segnaposto si ricavano dal **`fire_at` in ora di
  Roma** e non dall'istante del clic: un promemoria delle 23:30 chiuso dopo mezzanotte verrebbe
  segnato sul giorno dopo;
- **snooze** — chiude l'occorrenza e ne inserisce una copia più avanti. ⚠️ Una riga **nuova** e non
  un update del `fire_at`: la vecchia resta a dire che quel promemoria è suonato davvero. E la
  copia si fa **per ogni canale** su cui era arrivato, o rimandarlo dal telefono spegnerebbe di
  nascosto Telegram;
- **cancel** — chiude l'occorrenza e basta;
- chi chiama: il **service role** (il webhook) passa senza controlli, un **utente col suo JWT**
  (l'APK) solo sulle proprie righe. Dentro si scrive col service role, dove la RLS non vale:
  senza quel controllo basterebbe l'id di una riga altrui per completarla.

### ⚠️ La stessa occorrenza su due canali ha due `occurrence_id`

`occurrence_id` è `"{rule_id}:{YYYY-MM-DD}:{HH:MM}"` e porta dentro il **rule_id** — ma ogni canale
ha la **sua** regola, quindi la riga Telegram e quella Android dello stesso promemoria hanno due
occurrence_id diversi.

Cercando i fratelli per occurrence_id uguale — com'era finché il canale era uno solo — premere
✅ Fatto sul telefono avrebbe chiuso il task lasciando la riga Telegram in `pending`: il bot avrebbe
suonato per una cosa già fatta, e premendo Fatto anche lì `task_complete` avrebbe chiuso
**l'occorrenza successiva**, cioè la volta dopo, senza che niente lo dicesse. `notification-action`
riconosce quindi la stessa occorrenza da **utente + app + entità + la coda `:giorno:ora`**, che è
la parte che non dipende dalla regola.

### ⚠️ Due canali vogliono dire due righe: chi legge con `maybeSingle()` si rompe

Le pagine che scrivono le regole ne scrivono ora **due**, e ogni lettura che si aspettava una riga
sola va filtrata per canale — `maybeSingle()` su due righe non torna la prima: risponde con un
**errore**, cioè un promemoria che smette di salvarsi senza dire perché. Sistemato in:

| Dove | Cosa |
|---|---|
| `tasks.html` | `scriviRegola(canale)` / `togliRegola(canale)`, un blocco per canale coi propri preset |
| `habit-tracker.html` | `syncHabitNotificationRule` scrive i due canali; le tre letture che salvano la regola prima di ricreare uno stack leggono ora **tutte** le righe, e `migrateHabitNotificationRule` le migra tutte |
| `index.html` | Il promemoria al volo nasce già in coda: una regola **e** una riga di coda per canale. L'elenco raggruppa per `entity_id` (o comparirebbe due volte) e il 🗑 cancella per entità |

### ⚠️ Il telefono è un canale che si sceglie da sé, non un'aggiunta a Telegram

I due canali sono **indipendenti in tutt'e tre le pagine**: si accendono uno per uno, e spegnere
il bot non zittisce il telefono. Dove ciascun canale ha **anticipi propri da scegliere** la
separazione è una **Tab** (Tasks, Abituati); dove il promemoria è un istante solo sono **due
caselle** (il promemoria al volo di AppSphere) — una Tab che contenesse una spunta e basta
sarebbe un clic in più per vedere una spunta.

| Pagina | Come | Cosa ha di suo il telefono |
|---|---|---|
| `tasks.html` v19.25.0 | tre Tab: 📱 Telegram · 📲 Telefono · 🔐 Smart Block | il suo elenco di anticipi (`androidReminders`) |
| `habit-tracker.html` v9.5.0 | due Tab dentro *Promemoria*: 📱 Telegram · 📲 Telefono | il suo elenco di anticipi (`addHabitAndroidIds` / `editHabitAndroidIds`) |
| `index.html` v1.6.1 | due caselle: 📱 Su Telegram · 📲 Sul telefono | niente: un promemoria al volo ha un `fire_at` e basta |

⚠️ **In tutt'e tre la riga BASTA a dire che il canale è acceso**: `reminder_presets.android`
non si scrive più da nessuna parte. Era nato perché l'assenza della regola android valeva anche
per una riga nata prima del canale; ma da quando il telefono si accende da sé quella riga la si
è scelta, e un campo che ripete quel che la riga già dice sono due verità sullo stesso dato. Le
righe salvate prima ce l'hanno già, il telefono acceso: si rileggono così, ed è quello che erano.

⚠️ **In Abituati la regola `android` porta SEMPRE `completion_update`**, anche col bottone Fatto
di Telegram spento: sul telefono i pulsanti li disegna l'app e ci sono comunque, e senza quel
blocco un ✅ Fatto chiamerebbe `habit_post_completion` **senza scrivere la riga in
`hb_completions`** — cioè un'abitudine segnata che non risulta fatta. `telegram_complete_button`
resta invece solo sulla riga Telegram, che è l'unica a cui quel bottone appartenga.

⚠️ **In Abituati l'orario è dell'abitudine e non del canale**: `times`, `days` e `from-to` sono
gli stessi nelle due righe, a cambiare sono i soli anticipi. Per questo la cache in pagina è
`{ telegram, android }` per abitudine (`regolaCanale` / `regolaQualsiasi`): piatta terrebbe
quelli dell'ultima riga letta, e il form riaprirebbe a caso gli anticipi dell'uno o dell'altro.

#### In Tasks: tre Tab

In `tasks.html` i canali sono **tre Tab**: 📱 Telegram, 📲 Telefono, 🔐 Smart Block.
Il telefono ha una spunta sua e un **proprio elenco di orari** (`androidReminders`), che si
riempie dalle stesse pillole ma è un elenco diverso: la regola `android` porta i **suoi**
`reminder_presets.reminders`, non quelli di Telegram.

⚠️ **La ragione è che i due canali si scelgono uno per uno.** Fino alla v19.24.0 il telefono era
una spunta *dentro* la Tab Telegram e ne usava i preset: chi non voleva il bot non poteva avere la
push, e spegnere Telegram zittiva anche il telefono senza che niente lo dicesse. Erano due strade
per la stessa notifica trattate come una sola.

⚠️ **Adesso la riga basta a dire che il canale è acceso**: `androidEnabled = !!anRule`, e
`reminder_presets.android` sulla regola Telegram **non si scrive più**. I task salvati prima ce
l'hanno già, quella riga, coi preset di Telegram — si rileggono come suoi, ed è quello che erano
davvero, quindi nessuna migrazione.

⚠️ **`loadNotificationRulesMap` legge l'UNIONE dei due canali** (`.in('channel', […])`): il badge
🔔 sulla scheda dice «questo task un promemoria ce l'ha», e da quando il telefono si accende da sé
un task può averlo **solo lì** — guardando il solo Telegram sparirebbe, insieme alla sua riga nella
pagina Reminder. `telegram_complete_button` si legge invece dalla sola riga Telegram, che è l'unica
a cui quel pulsante appartenga.

⚠️ **La barra delle Tab scorre di lato e non va a capo**: coi caratteri di sistema grandi tre voci
su 360 px non ci stanno, e schiacciarle le renderebbe non toccabili. È la stessa regola delle righe
di pulsanti in nativo.

⚠️ **Nel promemoria al volo di AppSphere almeno un canale ci vuole**: spente tutt'e due, il
salvataggio si ferma e lo dice — una notifica che non arriva da nessuna parte non è un
promemoria. E le due caselle **ripartono accese a ogni apertura del form**: una lasciata spenta
dalla volta prima spegnerebbe il promemoria dopo senza che nessuno l'abbia deciso.

### Le push: FCM HTTP v1, e i due file che nascono fuori dalla repo

`send-notifications` manda alle righe `android` una push per ogni telefono in `cm_push_devices`.

⚠️ **API HTTP v1, non la vecchia server key**: quella (`key=AAAA…`) è spenta dal 2024. Serve un
access token OAuth2 firmato con l'account di servizio — JWT RS256 con Web Crypto, poi scambiato su
`oauth2.googleapis.com` — e il token si tiene in memoria per un'ora: rifarlo a ogni notifica
sarebbe una firma RSA per ogni riga della coda.

⚠️ **Messaggio di soli `data`, mai una `notification`**: una notification payload di FCM **non può
portare pulsanti**, e senza ✅ Fatto e ⏸ Rinvia il telefono direbbe meno di Telegram. La notifica la
disegna l'app (`notifiche/Notifiche.kt`), che è anche il motivo per cui il servizio viene svegliato
pure ad app chiusa. `priority: high`, perché un promemoria in ritardo di venti minuti non serve più.

⚠️ **Basta un telefono raggiunto perché la notifica sia consegnata**: il tablet spento non deve far
risultare fallito un promemoria arrivato sul telefono in tasca. Un token che FCM dichiara
`UNREGISTERED` spegne la sua riga (`enabled = false`): una riga morta lasciata accesa fa fallire
ogni invio successivo e non lo dice a nessuno.

**Due cose nascono sulla console Firebase e non possono stare qui:**

| Cosa | Dove va | Senza |
|---|---|---|
| `google-services.json` (app Android, package `com.garsal.appsphere`) | `android-app/appsphere-native/app/` | L'APK **si compila lo stesso** e le push restano spente |
| Chiave dell'account di servizio (JSON intero) | secret Supabase `FCM_SERVICE_ACCOUNT` | Le righe `android` finiscono `failed`; Telegram continua a funzionare |

⚠️ **Il plugin `com.google.gms.google-services` si applica SOLO se il file c'è**
(`if (schedaFirebase.exists())` in `app/build.gradle`): applicandolo comunque, la build fallirebbe
con «File google-services.json is missing» — cioè un APK che non si compila più per una
funzionalità che non è ancora accesa. `Push.disponibile()` risponde `false` finché
`FirebaseApp` non è inizializzata, e ogni chiamata a Firebase sta in un `runCatching`.

### La notifica sul telefono (`appsphere-native/…/notifiche/`)

| File | Cosa fa |
|---|---|
| `Push.kt` | Il token di questa installazione e la chiamata a `notification-action` |
| `PushService.kt` | Riceve la push (soli dati) e il token rigenerato |
| `Notifiche.kt` | Canale, disegno e pulsanti |
| `AzioniNotifica.kt` | Il pulsante premuto: chiude la notifica e chiama la Edge Function |
| `RinvioActivity.kt` | Le altre scelte: 30 min / 1 h / 3 ore / domani / annulla |

⚠️ **Android mostra tre pulsanti e basta.** Sulla notifica ci sono ✅ Fatto, ⏸ 1 ora e ⋯ Altro:
mettendo lì tutte e quattro le durate si perderebbe il Fatto, che è quello che si preme davvero.
Le altre stanno nel dialogo di `RinvioActivity` — le stesse quattro del bot, più l'annullamento.

⚠️ **La notifica si chiude PRIMA della risposta del server**: il tocco deve avere un effetto
immediato, o si preme una seconda volta credendo che non sia passato — e due «Fatto» sullo stesso
promemoria sono due chiusure. Se il server rifiuta, il messaggio lo dice.

⚠️ **Ogni pulsante ha un `action` diverso nel suo Intent** (`"$azione:$queueId:$minuti"`): due
`PendingIntent` con lo stesso requestCode e intent che differiscono per i soli **extra** sono lo
stesso PendingIntent, e con `FLAG_UPDATE_CURRENT` il primo si prenderebbe gli extra del secondo —
cioè «Fatto» che rimanda di un'ora.

⚠️ **Il token si riscrive a ogni avvio, in upsert sul token, anche col permesso negato**: FCM lo
rigenera da sé (reinstallazione, ripristino da backup, dati svuotati), e una riga vecchia in
tabella è una notifica che parte e non arriva. Il permesso `POST_NOTIFICATIONS` si chiede **dopo lo
sblocco biometrico**: due finestre di sistema insieme se ne annullano una, e quale dipende dal
telefono.

⚠️ **L'icona piccola dev'essere monocromatica** (`ic_notifica.xml`): Android ne tiene solo il canale
alfa, e il marchio a cinque cerchi diventerebbe una macchia bianca senza forma.

---

## Smart Blocker — due profili, un solo codice

`android-app/smartblocker/` produce **due APK** tramite product flavor Gradle (dimensione `profilo`):

| Flavor | applicationId | APK pubblicato | A chi |
|---|---|---|---|
| `salvatore` | `com.garsal.smartblocker` | `releases/SmartBlocker-latest.apk` | App completa: task, sfide Ta Firi?, Analisi Costi |
| `teresa` | `com.garsal.smartblocker.teresa` | `releases/SmartBlockerTeresa-latest.apk` | Solo le transazioni Revolut fatte con la carta di Teresa |

Le differenze passano **tutte** da tre `buildConfigField` letti in `Config.kt` — niente sorgenti
duplicati, niente `if (nomeUtente == …)` sparsi nel codice:

| Campo | `salvatore` | `teresa` | Effetto |
|---|---|---|---|
| `PROFILE` | `salvatore` | `teresa` | Etichette in `MainActivity` |
| `FIXED_DEVICE_TOKEN` | vuoto | `teresa-smartblock` | Se valorizzato, `BlockerService` lo scrive nelle prefs ad ogni avvio invece di chiamare `get_smart_block_token()` |
| `ONLY_APP` | vuoto | `cost_analysis` | `SupabaseApi.queryQueue` scarta ogni riga con `app` diverso |

### Come viene indirizzata la notifica

`revolut-auto-categorize` accoda **una riga per destinatario** in `cm_notification_queue`, distinte
solo da `metadata.device_token`:

- **Salvatore** — token da `cm_user_notification_settings.smart_block_device_token`, tutte le
  transazioni senza categoria (comportamento storico, invariato);
- **Teresa** — token = costante `TERESA_DEVICE_TOKEN`, solo le transazioni con
  `spender_person_id` = riga `ca_people` di nome *Teresa* (l'attribuzione carta → persona la fa già
  `enable-banking-sync` via `ca_card_person_map`).

⚠️ **`TERESA_DEVICE_TOKEN` è duplicato**: `supabase/functions/revolut-auto-categorize/index.ts` e
`build.gradle` (`FIXED_DEVICE_TOKEN`) devono avere lo stesso valore. Se divergono il telefono di
Teresa smette di ricevere notifiche **senza nessun errore** — semplicemente nessuna riga risulta
"sua". Non è un segreto: la policy `smart_block_anon_select` lascia leggere tutte le righe
`smart_block` a chiunque abbia la anon key, il token serve solo a indirizzare.

Il controllo "esiste già una notifica pending" è **per destinatario**, non per regola: le due righe
condividono lo stesso `rule_id` (l'unica riga ancora di `cm_notification_rules`), quindi un controllo
per regola farebbe sparire la notifica di uno ogni volta che l'altro non ha ancora smaltito la sua.
Ed è **a scadenza**: una pending di più di 6 ore non è una notifica in viaggio, è una riga che
nessuno sta consumando (telefono spento, APK non installato, overlay negato) — e finché resta lì
zittisce ogni notifica successiva di quel destinatario, per sempre. Passate le 6 ore si butta e se
ne accoda una aggiornata: il contenuto è comunque una fotografia rifatta da capo a ogni run.

⚠️ **Il conto da cui parte tutto è quello spuntato `'cost_analysis'` in `cm_bank_connections.uses`**,
non "quello di Revolut". Fino alla v1.5 la Edge Function lo cercava per `aspsp_name = 'Revolut'`,
cioè per nome della banca: da quando il collegamento parte dal censimento degli istituti quel nome
è quello del catalogo Enable Banking, e rifare il consenso bastava a far sparire il conto dalla
query — con lui il sync e tutte le notifiche, senza una riga da nessuna parte. Ora, quando non si
accoda niente, il perché è scritto: in `cm_sync_log` se il sync non parte proprio, nella risposta
JSON (`notifications: [{who, outcome}]`) e nei log della function per ogni destinatario.

Sul profilo `teresa` nessuna schermata chiede mai il PIN (`Prefs.isInfoOnlyBlock` restituisce sempre
`true`): il PIN è di Salvatore e su quel telefono un blocco rosso sarebbe insbloccabile.

### ⚠️ L'icona è del solo profilo `salvatore`, ed è la quarta copia del marchio

Dalla v1.6.0 Smart Blocker porta il **marchio AppSphere col lucchetto**: i cinque cerchi su fondo
nero, com'è l'APK nativa, con un lucchetto argento sovrapposto in alto a destra. Vive in
`src/salvatore/res/` — `drawable/ic_launcher_foreground.xml`, `mipmap-anydpi-v26/ic_launcher.xml`
(e `_round`) e il fondo in `values/ic_launcher_colors.xml`.

⚠️ **Il flavor `teresa` NON la prende**, e non è una dimenticanza: quell'APK è *Revolut Teresa*,
che di blocchi non ne fa nessuno — un lucchetto lì direbbe una cosa falsa. Resta con l'icona di
prima (i PNG di `main/res/mipmap-*`), che nessun altro usa più: `minSdk` è 26, quindi dove c'è
l'adattiva i PNG non li legge nessuno.

⚠️ **I cinque cerchi sono copiati IDENTICI** da `appsphere-native/…/ic_launcher_foreground.xml`,
coordinate e colori compresi: è la **quarta** copia del marchio (vedi *Il marchio vive in tre
posti*, che ora ne conta quattro) e va cambiata insieme alle altre. Quel che distingue l'app nel
cassetto è il lucchetto, non un disegno diverso.

⚠️ **Il lucchetto non sporge dall'angolo come nella foto di riferimento**: la tela adattiva
garantisce solo il **cerchio da 36 dp di raggio**, e il punto peggiore del disegno — l'angolo
alto-destro dell'arco, (78, 28) — cade a 35,4 dp dal centro. Sporgendo, la maschera tonda lo
taglierebbe, e **non si vedrebbe finché non lo si prova su un launcher che la usa**. Per rientrare
copre quasi tutto il cerchio rosso e sfiora il blu: è il prezzo, ed è visibile.

⚠️ **Il colore di fondo si chiama `sb_icon_bg` e non `ic_launcher_background`**: in
`main/res/drawable` esiste già un drawable con quel nome (il viola dell'icona vecchia, che
`teresa` continua a usare), e due risorse omonime di tipo diverso sono un modo di sbagliare
riferimento senza accorgersene.

### ⚠️ ☰ → 📱 Versione app: il terzo giro identico, e due schede perché due APK

Dalla v1.6.0 il ☰ ha una quarta voce, **📱 Versione app**: dice quale versione è installata, quale
è pubblicata, e apre il download nel browser di sistema. `Aggiornamento.kt` è il **gemello riga per
riga** di quello dell'APK WebView (`com.garsalapps`) e di `core/Aggiornamento.kt` del nativo —
stessa scheda `-latest.json`, stesse sette chiavi, stesso dialogo. Serve alla stessa domanda: il
nome dell'APK è fisso (`-latest.apk`), quindi da fuori una build vale l'altra, e scaricare quella
di ieri è indistinguibile da un aggiornamento riuscito.

⚠️ **Due flavor, due schede**: `build-smartblocker.yml` pubblica `SmartBlocker-latest.json` **e**
`SmartBlockerTeresa-latest.json`, e quale leggere lo dice `BuildConfig.RELEASE_BASE` — la quarta
differenza che passa da `Config`/`BuildConfig` invece che da un `if (PROFILE == …)` sparso nel
codice. I due APK hanno `applicationId` diversi: con una scheda sola, il telefono di Teresa si
vedrebbe offrire un pacchetto che **non si installa sopra al suo**.

⚠️ **Le schede si aggiornano solo insieme agli APK**: `builtAt` cambia a ogni run, e commetterle
da sole annuncerebbe una build nuova per un pacchetto identico. È la stessa guardia di
`build-appsphere-native.yml`, con la differenza che qui i file da confrontare sono due.

⚠️ **La versione nell'intestazione si legge da `BuildConfig.VERSION_NAME`** e non si riscrive più a
mano in `MainActivity`: scritta due volte, prima o poi una delle due resta indietro — e quella che
si legge a schermo è proprio quella sbagliata. La regola del versioning per le app Android resta
quella (bump di `versionName` e `versionCode` in `build.gradle`), meno il terzo posto.

---

## SOS — il bottone rosso e il countdown che blocca il telefono

`android-app/sos/` è un modulo del progetto Gradle di `android-app/` (come `smartblocker`, non
come i progetti standalone), `applicationId` `com.garsal.sos`, APK
`releases/Sos-latest.apk`. **È nato da Smart Blocker** e ne riusa il pezzo che conta: la
finestra `TYPE_APPLICATION_OVERLAY` tenuta viva da un servizio in primo piano.

Una schermata sola: un SOS per pagina, si sfoglia con lo **swipe destra/sinistra**
(`ViewPager2`), e in ogni pagina c'è un cerchio rosso col diametro ricavato dallo schermo
(72 % della larghezza, con un tetto) e la scritta in **autosize** — così l'ingrandimento dei
caratteri di sistema non la fa uscire dal cerchio. Si preme, parte il countdown, il telefono
resta bloccato, e alla fine arriva la domanda «com'è andata?».

Le cose che *sono* la funzionalità, e che vanno cambiate insieme al database:

- **il blocco è l'overlay, non un'Activity.** `SosOverlay` è una finestra di sistema: sta sopra
  qualsiasi app, launcher compreso, non se ne va col tasto Home (non è nello stack) e i tocchi si
  fermano lì. Non c'è nessun servizio di accessibilità come in Smart Blocker, e non serve: là
  rilanciava un'*Activity* che l'utente poteva scavalcare, qui non c'è niente da rilanciare;
- **il countdown vive nel servizio** (`SosSessionService`, foreground `specialUse`), non
  nell'Activity. Uno schermo che si spegne o una rotazione non devono poter accorciare un blocco;
- **una via d'uscita c'è, e costa.** *Tieni premuto per arrenderti* (3 secondi, conferma al
  rilascio) interrompe il giro — che finisce comunque in archivio, con `completed = false`, e la
  domanda si fa lo stesso. Un blocco senza uscita, su un telefono che è anche il modo per
  chiamare qualcuno, sarebbe un rischio e non una funzionalità;
- **il bottone parte anche senza rete.** La configurazione sta in cache (`Prefs`), il countdown
  parte sulla durata che il telefono conosce e `sos_session_start` va in parallelo: la durata del
  server si adotta solo nei primi quattro secondi, dopo di che allungare quel che si sta già
  guardando sembrerebbe un difetto. Il giro chiuso senza rete finisce in **coda** e si rispedisce
  con `sos_session_log` alla prossima apertura;
- ⚠️ **in coda va solo ciò che può ancora riuscire.** Un errore di merito — codice revocato,
  risposta cancellata dalla configurazione — fallirebbe identico ad ogni rinvio e resterebbe lì
  per sempre: si riprova solo quando il server non ha risposto affatto;
- **punti e durata del prossimo giro non si calcolano in Kotlin.** Li dà
  `sos_session_finish`, e quello che l'overlay mostra è la sua risposta.

Il **testo motivante scorre su una riga sola** invece di andare a capo: con i caratteri di
sistema grandi un paragrafo spingerebbe fuori schermo proprio il countdown. La larghezza del
`TextView` si **misura** (`paint.measureText`) invece di lasciarla a `WRAP_CONTENT`, che dentro
un contenitore largo quanto lo schermo si ferma al bordo e manda il testo a capo — cioè proprio
quello che non deve fare.

⚠️ **L'APK non fa il login Google.** Si accoppia una volta col codice generato da
`sos.html` → 📱 Telefoni e da lì in poi parla con quattro RPC che lo riconoscono da quel codice.
La ragione è la stessa per cui il bottone è grande: si preme in un momento di crisi, e in quel
momento non si può inciampare in una sessione scaduta o in una schermata di Google che chiede di
riautenticarsi.

### ⚠️ L'icona è il marchio AppSphere col badge, ed è la quinta copia

Dalla v1.1.0 l'icona sono i **cinque cerchi di AppSphere su fondo nero** con un **badge rosso
`SOS`** in alto a destra (`src/main/res/drawable/ic_launcher_foreground.xml`). Fino alla v1.0.1
era un cerchio bianco spesso su fondo rosso, e la ragione scritta allora era che «una scritta SOS
a questa dimensione sarebbe illeggibile»: vale per una scritta a tutta icona — dentro un badge
alto 23 dp le tre lettere ne prendono 10,7, cioè quasi la metà, e si leggono anche a 48 dp.

⚠️ **I cinque cerchi sono copiati IDENTICI** da `appsphere-native/…/ic_launcher_foreground.xml`:
è la **quinta** copia del marchio (vedi *Il marchio vive in quattro posti*, che ora ne conta
cinque) e va cambiata insieme alle altre.

⚠️ **Il fondo è nero e non più `sos_red`**: il marchio è pensato su fondo scuro, e sul rosso il
suo cerchio rosso sparirebbe nel fondo. Il rosso resta il colore dell'app — lo porta il badge,
che è la cosa che la distingue.

⚠️ **L'alone scuro attorno al badge non è decorazione**: il badge è `#EE334E` e sta sopra al
cerchio rosso del marchio (`#CA372E`). Senza, i due si toccherebbero senza un bordo e a 48 dp nel
cassetto si leggerebbero come una macchia sola. È la stessa scelta del lucchetto di Smart Blocker.

⚠️ **Il badge non sporge dall'angolo come nella foto di riferimento**: la tela adattiva
garantisce solo il **cerchio da 36 dp di raggio**, e il punto peggiore — l'angolo alto-destro
dell'alone, (80, 30) — cade a 35,4 dp dal centro. Sporgendo, la maschera tonda lo taglierebbe, e
**non si vedrebbe finché non lo si prova su un launcher che la usa**.

⚠️ **Le lettere sono tracciati e non testo**: un `<vector>` non sa disegnare una stringa e un
font qui non c'è. La S sono due archi da 225° agganciati nel mezzo, la O un'ellisse — cambiando
la dimensione del badge vanno rifatte le coordinate, non c'è un `textSize` da toccare.

### ⚠️ ⚙️ → 📱 Versione app: il quarto giro identico

Dalla v1.1.0 il dialogo Impostazioni ha una voce in più, **📱 Versione app**, fra il permesso
overlay e lo scollegamento: dice quale versione è installata, quale è pubblicata, e apre il
download nel browser di sistema. `Aggiornamento.kt` è il **gemello riga per riga** di quello di
Smart Blocker, dell'APK WebView e del nativo — stessa scheda `-latest.json`, stesse sette chiavi,
stesso dialogo.

⚠️ **Qui l'APK è uno solo**, quindi il nome della scheda è una costante e non un `BuildConfig`:
in Smart Blocker sono due perché sono due pacchetti con `applicationId` diversi.

⚠️ **Il `TextView` del dialogo NON fissa un colore, ed è la ragione per cui il gemello funziona
in tutt'e due le app**: `Theme.Sos` è scuro e `Theme.SmartBlocker` è
`Theme.AppCompat.Light`, quindi lasciando decidere al tema il testo si legge di qua e di là.
Scrivendoci `Color.WHITE` per «uniformarlo» al resto di SOS, in Smart Blocker sparirebbe sul
bianco — e viceversa. È l'unico punto dei quattro `Aggiornamento.kt` dove *non* mettere una
costante è la scelta.

⚠️ **La versione non si scrive anche nel riepilogo delle Impostazioni**: la dice il dialogo, e
due posti che mostrano la stessa cifra sono due cifre che divergono il giorno che una delle due
resta indietro.

⚠️ **Nel dialogo il testo è BIANCO**, e non è una preferenza: `Theme.Sos` estende
`Theme.AppCompat.NoActionBar`, che è il tema **scuro** — la finestra ha il fondo grigio antracite,
e i due testi scritti a mano ci stavano sopra in `#444444` e `#1F2937`, cioè quasi invisibili
(v1.1.1). Il default di `testo()` era già `Color.WHITE`: erano le due sole chiamate dell'app che
lo scavalcavano. ⚠️ Un testo **secondario** si segna con l'`alpha` e non con un colore più scuro:
su fondo scuro «più scuro» vuol dire «meno leggibile», non «meno importante».

---

## Spese in giro — le spese di un viaggio in bici, divise in due

`android-app/spese-in-giro/` è un **progetto Gradle standalone** (come `appsphere-native/` e
`situazione-rosa/`, e non un modulo di `android-app/`), `applicationId`
`com.garsal.speseingiro`, APK `releases/SpeseInGiro-latest.apk`. Kotlin + Compose, Material 3, ML Kit per
l'OCR. **Non ha un gemello web.**

⚠️ **Si chiamava «Tandem» per mezza giornata, l'8 settembre 2026**, e la rinomina è stata
completa — pacchetto, cartella, APK, Edge Function — perché è arrivata **prima che l'APK fosse
installato da qualcuno**: dopo non si sarebbe più potuto, e sarebbe rimasto il compromesso di
*AppSfera Web*, che il nome nel cassetto ce l'ha nuovo e `applicationId` vecchio. Due cose portano
ancora il nome di allora, e non è una svista:

- le tabelle restano **`vg_*`** (da *viaggi*), che non nominava Tandem nemmeno prima;
- la **migration `20260908100000_vg_tandem_viaggio_bici.sql`** conserva il nome nel titolo e nei
  commenti. Una migration già applicata è **storia e non si riscrive**: rimaneggiarla non
  cambierebbe niente sul database — `db push` la salta perché la versione risulta già applicata —
  e in cambio farebbe divergere quel che c'è in produzione da quel che si legge nel file.

⚠️ **La vecchia Edge Function `tandem-foto` resta deployata su Supabase e non la chiama più
nessuno**: il workflow deploya le funzioni toccate dal commit, non cancella quelle sparite dal
repo. Si toglie a mano dalla dashboard; finché c'è non fa danni — vuole comunque un token valido.

⚠️ **È l'unica app della repo che sta fuori dalla suite, e lo è in tutti i sensi**: nessuna riga in
`cm_apps` (quindi nessuna bolla in home, né web né nativa), nessun punteggio, nessun login Google,
nessuna tabella condivisa, e un'**icona propria** — una bicicletta, non il marchio a cinque cerchi
(vedi *Il marchio vive in cinque posti*). I suoi dati non appartengono a un account: appartengono a
un **codice**.

### Come si accoppiano i due telefoni

Uno **apre il viaggio** e riceve un codice (`ABCD-EFGH`); l'altro **lo digita** e da lì in poi i
due telefoni parlano con le sole RPC `vg_*`, che li riconoscono dal token ricevuto allora
(dettagli nello schema `vg_*`). Non c'è nessuna registrazione, e non è una scorciatoia: l'app si
apre in mezzo alla strada, spesso con poca rete e meno pazienza, e inciampare in una sessione
scaduta o in una schermata di Google che chiede di riautenticarsi è il modo peggiore di segnare uno
scontrino. È la stessa ragione per cui SOS non fa il login.

⚠️ **Il token sta nelle preferenze di quel telefono e in nessun altro posto** (`Prefs`): non c'è un
account da cui recuperarlo. Perso il telefono si **rientra col codice**, ed è la ragione per cui il
codice si tiene scritto accanto al viaggio invece di buttarlo dopo l'accoppiamento. Nome del
viaggio e nomi delle persone si tengono in locale per un'altra ragione ancora: l'elenco dei viaggi
si deve poter aprire **senza rete**, e «viaggio 7f3a-…» a chi non ha campo non dice niente.

⚠️ **Più viaggi per telefono**, uno per riga in `Prefs`, e la chiave è il **viaggio** e non il
token: rientrando col codice il token è nuovo ma il viaggio è lo stesso, e restare in elenco due
volte lo farebbe scegliere a caso.

### Le due registrazioni

| Tipo | Cosa chiede |
|---|---|
| 🧾 **Spesa** | quanto, quando, cos'era, categoria, **chi l'ha pagata** e **per chi vale** |
| 💸 **Restituzione** | quanto, quando, e **chi restituisce**. «A chi» non si chiede: in un viaggio in due ha una risposta sola |

⚠️ **I due default sono la funzionalità**: «l'ha pagata» parte dal proprietario del telefono e «per
chi» da *tutti e due*. Sono i due casi di gran lunga più frequenti, e ogni tocco risparmiato davanti
a una cassa è una spesa che si segna invece di rimandarla a stasera — cioè invece di dimenticarla.
Restano tutt'e due modificabili: una spesa la può aver fatta l'altro, e non tutto quel che si compra
è di tutt'e due.

⚠️ **Una spesa «solo per chi l'ha pagata» resta nel registro e vale zero nel saldo.** Non è un caso
inutile: è il modo di tenere il conto di quanto è costato il viaggio senza che una cosa personale
finisca nel debito dell'altro.

⚠️ **La categoria si sceglie fra quelle del viaggio, che si gestiscono da ⚙️ Impostazioni** (v1.0.5):
si aggiunge, si rinomina e si toglie quel che non serve. Vivono in `vg_categorie` e non in Kotlin —
le regole che contano (chiave che non cambia, cancellazione solo se non usata) stanno nello schema
`vg_*`.

### Il giro delle conferme

**Ogni voce vale quando l'altro la conferma.** Prima è una proposta: la scrive uno, e finché è
`in_attesa` **solo lui** la corregge o la toglie. Confermata **non si modifica più**; per toglierla
si chiede, con un motivo, e **decide l'altro**. Gli stati e chi può fare cosa stanno nello schema
`vg_*` — e stanno **nelle RPC**, non in Kotlin: due telefoni con la stessa app che decidono per
conto proprio chi può cancellare cosa sono due patti diversi il giorno che uno dei due si aggiorna
e l'altro no. È la stessa regola di `task_complete` e `sos_session_finish`.

⚠️ **Chi aspetta cosa sta scritto sulla scheda** («In attesa che Anna confermi», «Decidi tu»): uno
stato che si deduce dai pulsanti che non ci sono è uno stato che nessuno capisce.

### Lo scontrino, e l'importo letto

Si scatta (o si pesca dalla galleria), si riduce a 1600 px di lato, **ML Kit** ci legge il testo e
`Ocr.totale()` ne ricava una **proposta** di importo.

⚠️ **Quel numero non è mai un dato**: finisce nella casella dell'importo, già modificabile, e
**non sovrascrive mai** una cifra scritta a mano — un ripiego che prende il posto del dato esatto è
un peggioramento silenzioso. Resta archiviato a parte in `vg_voci.scontrino_letto`, accanto
all'importo confermato: così quando la lettura sbaglia si **vede** che ha sbagliato. Un importo
letto da una foto e dato per buono è un debito fra due persone deciso da un OCR.

⚠️ **La regola è euristica e sta in un posto solo** (`Ocr.totale`): si cercano le righe con
`TOTALE`/`IMPORTO`, si scartano quelle con `SUBTOTALE`, `IVA`, `RESTO`, `SCONTO`, e se sulla riga
l'importo non c'è si guarda quella dopo — sugli scontrini stretti il valore va a capo sempre. Fra
più «totale» vince il più alto; senza nessuna parola chiave resta l'importo più grande. I decimali
sono **obbligatori** nel riconoscimento: senza, il «3» di «3 x 1,20» diventerebbe un totale.

⚠️ **La foto si carica PRIMA di salvare la voce, e se il salvataggio non passa si butta**: senza,
resterebbe nel bucket un'immagine che nessuna riga nomina — un file che nessuno sa più cos'è e che
dall'app non si può più togliere. È lo stesso ordine dell'eliminazione nel Forziere, letto al
contrario.

⚠️ **ML Kit nella variante non incorporata** (`play-services-mlkit-text-recognition`), come in
appsphere-native e per la stessa ragione: quella che si porta il modello dentro l'APK per tutte e
quattro le ABI là aveva portato il pacchetto da 30 a 73 MB.

⚠️ **Nessun permesso per la fotocamera nel manifest**, e non è una dimenticanza: la foto la scatta
l'app della fotocamera (`ACTION_IMAGE_CAPTURE`) e torna in un file nostro, quindi il permesso non
serve — ma **dichiarandolo Android lo pretenderebbe concesso a runtime** proprio per quell'Intent,
cioè una richiesta in più davanti alla cassa per una cosa che funziona senza. Stessa ragione per
cui non c'è `READ_MEDIA_IMAGES`: il selettore di sistema restituisce la sola foto scelta.

⚠️ **L'EXIF si legge e la foto si raddrizza** prima dell'OCR: una foto verticale arriva coricata, e
ML Kit su un testo ruotato di 90° non legge quasi niente — la si crederebbe una lettura fallita
invece che una foto storta.

### Il registro

📜 **Registro** elenca tutto: chi ha segnato, corretto, confermato, chiesto e cancellato, e quando.
⚠️ **Lo scrive il database** (`vg_scrivi_log`) a ogni operazione, non l'app: un registro tenuto dai
due telefoni sarebbero due registri diversi. E ogni riga porta con sé **descrizione e importo** della
voce di cui parla, così resta leggibile anche quando quella voce non c'è più — è la stessa scelta di
`ob_action_history.action_title`.

### Le cose di forma, che sono di sostanza

- **le righe di pulsanti non vanno a capo**: scorrono col dito, e le larghezze si **misurano**
  (`RigaScorrevole` + `larghezzaPulsanti` in `ui/Comuni.kt`) su **tutte** le etichette che quella
  riga può mostrare, comprese quelle che in quel momento non si vedono. ⚠️ Sono ricalcate da
  `core/PulsantiTendine.kt` di appsphere-native e non importate: sono due progetti Gradle separati,
  che non condividono sorgenti — è la stessa duplicazione dichiarata di `ForziereBiometria` /
  `ForziereKeystore`. Se la regola cambia là, va cambiata anche qui;
- **una scelta fra poche opzioni è sempre una tendina**, mai una fila di pillole — e la
  tendina passa da `ExposedDropdownMenuBox` con la sua ▾, non da un campo disabilitato con un
  `clickable` appeso al modifier. ⚠️ Fino alla v1.0.1 era così, e i due difetti si sommavano:
  senza freccia «l'ha pagata» si leggeva come un dato scritto invece che come una scelta, e
  l'apertura dipendeva da un tocco su un campo spento. La ▾ compare **solo con più di
  un'opzione**: da soli nel viaggio le due tendine hanno una voce sola, e il form lo dice col
  codice da dettare invece di lasciar credere che la scelta sia negata;
- **l'indietro di Android riporta alla home** e non fa uscire dall'app buttando via quel che si
  stava scrivendo (`BackHandler`): è la stessa regola della `guardiaIndietroPopup` delle pagine web;
- **il FAB è uno solo per le due strade** (spesa / restituzione), con un menù ancorato: due FAB
  affiancati, coi caratteri di sistema grandi, non hanno spazio garantito. È la stessa scelta del
  FAB di «Ti pisasti?»;
- **le date si scrivono e si leggono in locale**: il DatePicker restituisce la mezzanotte **UTC**
  del giorno scelto, e letta col fuso locale è il giorno prima — `isoDaMillis` / `millisDaIso`
  fanno il giro nei due sensi. È lo stesso inciampo di `localDay()` in Obiettivi;
- **«Esci dal viaggio» dice cosa NON succede**: il viaggio resta, l'altro continua a vedere tutto,
  e col codice ci si rientra. Davanti a un viaggio intero di spese, «esci» senza spiegazioni si
  legge come «cancella» — è la stessa scelta della conferma che elimina uno scomparto del Forziere.

---

## AppSphere nativa — l'unico modulo Android che non è un WebView

`android-app/appsphere-native/` è un progetto Gradle standalone (come `situazione-rosa/`)
e **l'unica app Android della repo scritta davvero in nativo**: schermate in
Kotlin/Compose, dati da PostgREST via `supabase-kt`. Tutti gli altri moduli caricano una pagina
del sito (`garsal.men`) dentro una `WebView`.

**Non sostituisce l'APK WebView, gli si affianca.** `applicationId` è `com.garsal.appsphere`
(contro `com.garsalapps`), quindi i due si installano insieme sullo stesso telefono e leggono lo
stesso database. Per tutto ciò che non è ancora nativo si continua ad aprire quello WebView.

⚠️ **I due APK si chiamano diversamente sul telefono**: il nativo è **AppSphere**, il WebView è
**AppSfera Web** (`app_name` in `strings.xml`; si chiamava *GarsalApps*). ⚠️ Il nome che si legge
nel cassetto delle app è l'unica cosa che è cambiata: `applicationId` resta `com.garsalapps`, e
non si tocca — cambiarlo farebbe di quell'APK **un'app diversa**, che si installerebbe accanto
alla vecchia invece di aggiornarla, lasciando indietro dati e permessi. Per la stessa ragione
restano `releases/GarsalApps-latest.apk` e `.json`: gli APK già installati interrogano
**quei** percorsi per sapere se c'è un aggiornamento, e rinominarli li lascerebbe su un 404 per
sempre. Segue `app_name` anche il messaggio di `HealthConnectBridge`, che dice sotto quale nome
cercare l'app in Health Connect — se restasse indietro manderebbe a cercare un nome che lì non
esiste.

⚠️ **Le due icone portano lo stesso disegno e si distinguono per il fondo**: i cinque cerchi di
AppSphere — quattro che si toccano a due a due (arancio, rosso / verde, viola) e il blu al centro,
sopra a tutti — su **fondo bianco per la WebView** e **nero per la nativa**. È l'unica cosa che le
distingue in un cassetto delle app, dove il nome è lo stesso e sta scritto piccolo. Il disegno vive
in `drawable/ic_launcher_foreground.xml`, **identico nei due progetti**, e il fondo in
`values/colors.xml` (`ic_launcher_background`): cambiando il disegno va copiato in tutt'e due, o le
due app smettono di sembrare la stessa app.

⚠️ **Il raggio dei cerchi non è scelto a occhio.** Un'icona adattiva è una tela da 108 dp di cui il
launcher garantisce solo il **cerchio centrale da 36 dp di raggio**: col disegno a `r = 14` dp
l'angolo più esterno cade a `r·(1+√2) = 33,8` dp dal centro, dentro quel cerchio. Allargandolo per
riempire di più la maschera quadrata, la maschera **tonda** taglierebbe i quattro cerchi esterni —
e non si vedrebbe finché non lo si prova su un launcher che la usa.

| Cosa | Dove |
|---|---|
| Home a bolle, avvisi, riquadro del totale, login, biometria | `home/`, `MainActivity.kt`, `core/` |
| Catalogo premi (riscossione, gestione, cronologia) | `premi/` |
| Notifiche push dei promemoria (canale `android`) | `notifiche/` — vedi *Notifiche, tre canali* |
| App portate | `spuntiamola/`, `eventslog/`, `tasks/`, `tafiri/`, `peso/`, `memo/`, `abituati/`, `calorie/`, `obiettivi/` (la bolla apre il 📆 **Piano quotidiano**), `frz/` (Forziere: bolla 🙈 riservata), `finanza/` (Finanza **in sola lettura**: bolla 🙈 riservata) |

### ⚠️ Righe di pulsanti e liste di scelta: due componenti condivisi, non uno per schermata

Due pattern per i caratteri di sistema grandi, nati in `tasks/Panoramica.kt` e `tasks/Gestione.kt`
e poi duplicati a mano schermata per schermata, ora vivono **una sola volta** in
`core/PulsantiTendine.kt` e si importano invece di reinventarli:

- **`RigaScorrevole` + `larghezzaPulsanti(testi)` + `Pillola`** — una riga di pulsanti-azione
  (Salva/Elimina, Fatto/Fallito, Modifica/Cancella…) **non va mai a capo**: scorre col dito su una
  riga sola, e i pulsanti sono **larghi uguale**, misurati con `larghezzaPulsanti()` sullo stile e
  l'ingrandimento correnti — mai una costante in `dp`. `larghezzaPulsanti` vuole **tutte** le
  etichette che possono comparire in quella riga, non solo quelle mostrate in un dato momento,
  altrimenti un pulsante condizionale farebbe traballare la larghezza degli altri. Dentro una
  `RigaScorrevole` (che scorre, quindi ha larghezza indefinita) **non si può usare `weight(1f)`**
  per pareggiare le larghezze — va bene solo in un `Row` normale, non scorrevole — serve sempre la
  larghezza misurata.
- **`Tendina` / `TendinaFacoltativa`** — una scelta singola fra poche opzioni fisse (tipo, stato,
  priorità, categoria…) è **sempre una tendina**, mai una fila di pillole che va a capo o si
  accorcia: un campo chiuso col valore scelto, che si apre in un `DropdownMenu` al tocco.
  `TendinaFacoltativa` è la stessa cosa con una voce «tutte/nessuna» in cima, per i filtri e le
  scelte facoltative. `Tendina` accetta anche `abilitata = false` per i campi che esistono ma non
  si possono cambiare (es. il tipo di un task o di un obiettivo già esistente): mostra il valore
  attuale fisso, invece di sparire o restare comunque cliccabile.

Quello che **resta** una `FlowRow` che va a capo, di proposito: le **liste a scelta multipla**
(giorni della settimana/mese, categorie di un task) — dove più valori possono essere scelti
insieme, quindi non c'entra una tendina — passano comunque da `RigaScorrevole` con la larghezza
misurata (restano selezionabili in multipla, semplicemente scorrono anziché andare a capo); le
**barre di icone** (formattazione testo di Memo, tavolozza colori) restano `FlowRow`, perché sono
toolbar e non scelte da ricordare; e la scelta di un'opzione `SCELTA` in un diario di Memo resta
volutamente pillole invece di tendina — è un'eccezione documentata in `MemoRegistrazione.kt`,
perché lì una tendina taglierebbe l'etichetta proprio dove la scelta si fa.

### ⚠️ Tasks nativo: le RPC valgono anche qui, e i workflow no

`tasks/` porta in nativo **quattro schede**: *Panoramica*, *Gestione*, *Mese*, *Settimana* — le tre
del planner del web più la pagina ✏️ Gestione. **Si apre sulla Panoramica** — fino alla
v1.0.81 era il Mese, ed è l'issue #22: la domanda con cui si apre Tasks è «cosa devo fare
adesso», e la panoramica la risponde coi pulsanti che agiscono subito; il mese dice com'è
messo il periodo, che è una domanda che ci si fa dopo. È anche la vista su cui si apre
`tasks.html`, quindi le due implementazioni partono dallo stesso posto. Il **+ galleggiante** apre
`TaskForm`, che crea un task (tutti i tipi tranne i `workflow`, i cui step hanno dipendenze fra
loro e non si riducono a un elenco); da Gestione lo stesso form **modifica** un task che esiste
già, e ne fa una **copia**. Il tipo di un task esistente non si cambia: decide quali colonne quel
task ha, e cambiarlo lascerebbe dietro quelle del tipo di prima — `multiple_dates` su un
ricorrente — che nessuno ripulisce. Scrivere un task è un `insert`/`update` diretto e non una RPC,
e non è un'eccezione alla regola: le RPC governano il ciclo di vita, cioè dove va la prossima
occorrenza, non com'è fatto il task — `saveTask()` nel web fa lo stesso. Restano su `tasks.html`
reminder, storico, impostazioni e l'import di un backup JSON.

La **Gestione** (`tasks/Gestione.kt`) è l'elenco completo con i due riquadri richiudibili del web —
📊 Vista (*espandi tutti*, *raggruppa per* categoria/priorità/tipo) e 🔎 Cerca e filtra (testo,
stato, priorità, categoria, tipo) — il conteggio *Trovati N task* e, su ogni scheda, *Vedi*,
*Modifica*, *Clona*, *Cancella*, e *Riattiva* su terminati e archiviati. ⚠️ Filtri e ordinamento
sono quelli di `applyTaskFilters()` ricalcati in `TasksState.gestione()`: stessi quattro stati vivi
sotto «Attivi», ricerca su titolo **e** descrizione, ordine per prossima occorrenza con chi non ce
l'ha in fondo. Per via di questa scheda `TasksRepository.task()` carica **anche archiviati e
terminati** (prima li scartava): le due voci della tendina che li chiedono sarebbero altrimenti
vuote per sempre.

La **Panoramica** è invece la copia di `renderDashboard()`, sezione per sezione
(`tasks/Panoramica.kt`): ⚠️ SCADUTI, 🎯 OGGI, 📅 PROSSIMI, 🔄 A LIBERA RIPETIZIONE e
👁️ NON IN PANORAMICA, ciascuna nel suo riquadro con la striscia colorata a sinistra, e la scheda
con segno del tipo, data e ora, etichette delle categorie (le prime due, poi «+N»), titolo e i
pulsanti che agiscono subito. **Quali pulsanti compaiono è la regola del web**: *Completa* sempre,
*Fallisci* su tutto tranne i `free_repeat`, *Salta* solo su `recurring`, `simple_recurring` e
`multiple`, che sono gli unici con una prossima occorrenza. Tre cose vanno lette dallo stesso posto
del web, o le due panoramiche divergono in silenzio: `show_in_panoramica` (falso = ultima sezione,
non sparito), `cm_categories.show_in_dashboard` (decide quali gruppi di `free_repeat` si vedono) e
`ts_settings.dashboard_upcoming_days` (quanti giorni guarda *PROSSIMI*, 10 se manca). Un task con
due categorie compare in tutt'e due i gruppi, come là, ma il conteggio della sezione resta quello
dei task.

Le file della scheda — segno e data, etichette, titolo, pulsanti — stanno **ognuna su una
riga sola che scorre col dito** (`RigaScorrevole`), e non vanno a capo. È l'eccezione voluta alla
regola sui caratteri di sistema grandi: andando a capo, con l'ingrandimento alto tre pulsanti
diventano tre righe e il titolo altre tre, e in uno schermo ci sta un task e mezzo. Niente è
nascosto — il testo tagliato dal bordo si trascina — **a patto che quel che conta di più stia a
sinistra**: la data prima delle etichette, *Completa* prima di *Salta*. Lo scorrimento è
orizzontale e quello della lista verticale, quindi i due gesti non si contendono niente e il tocco
sulla scheda apre comunque il dialogo col dettaglio, i punti di ogni azione e l'eliminazione.
I tre pulsanti sono **larghi uguali**, e quella larghezza si **misura** (`larghezzaPulsanti()`,
`rememberTextMeasurer`) invece di essere una costante in `dp`: coi caratteri di sistema grandi una
costante o taglia «Completa», o lascia «Salta» in un pulsante largo il doppio del necessario.

Una differenza di sostanza col web: un **ricorrente senza `next_occurrence_date`** ripiega su
`start_date`, quindi finisce fra gli scaduti; là quel ripiego sta solo in `isTaskDueToday`, e un
task così non compare in nessuna sezione.

Le due viste calendario non sono la copia pixel per pixel di `renderMonthView` e `renderWeekView`,
e non possono esserlo: quelle scrivono i titoli dentro le celle di una griglia 7×6 e in sette
colonne da 150 px con scorrimento orizzontale, che coi caratteri di sistema grandi sono illeggibili
prima ancora di essere tagliati. Nel **mese** la cella porta il numero del giorno e dei pallini
colorati, uno per task, e il titolo si legge toccando il giorno; la **settimana** è un elenco
verticale di sette giorni invece di sette colonne. **Quello che resta identico è la regola**:
`TsTask.cadeIl()` ricalca `getTasksForDate()` colonna per colonna, la settimana parte di lunedì,
il mese ha sempre sei righe e le fasce orarie sono le stesse quattro.

Due differenze di sostanza, volute:

- i task **`multiple` compaiono**. Nel web passano da `JSON.parse(task.multiple_dates)` dentro un
  try/catch: quando la colonna è già una lista quella chiamata solleva, il catch la scarta, e quei
  task non si vedono mai in calendario;
- **niente doppioni**: il web concatena task vivi e storico senza guardarli, quindi un task
  completato oggi che ha ancora oggi come prossima occorrenza compare due volte.

Il calendario legge anche `ts_history`: senza, un mese indietro sarebbe vuoto, perché la prossima
occorrenza di un ricorrente si sposta in avanti e le volte già fatte non stanno più in `ts_tasks`.

- **Il ciclo di vita passa solo dalle RPC**, come sul web: `TasksViewModel` chiama
  `task_complete` / `task_skip` / `task_fail` e poi rilegge. Nessun calcolo di prossima
  occorrenza in Kotlin — sarebbe una seconda regola per lo stesso task, diversa a seconda
  dell'app da cui lo tocchi.
- **I `workflow` si vedono e si completano**, come tutti gli altri: quella parte la fa la RPC,
  che sa già come trattare i loro step.
- **`ts_tasks` si legge come `JsonObject`, non come `data class` serializzata.** La tabella non
  sta in nessuna migration e alcune colonne sono ambigue di natura — `recurring_days_of_week` è
  `text[]` *o* `integer[]`, `recurring_day_of_month` può essere una lista o un numero solo. Con
  una data class, una colonna del tipo sbagliato non darebbe un task storto: farebbe fallire la
  decodifica dell'intera lista, cioè la schermata vuota.
- **I giorni della settimana sono numerati come `extract(dow)` di Postgres** (0 = domenica), che
  è quello che `task_next_recurring_date` confronta. Il form li mostra da lunedì e li salva con
  quella numerazione: se si toccasse una delle due parti senza l'altra, le ricorrenze
  settimanali scatterebbero il giorno sbagliato senza nessun errore.
- I task **riservati non compaiono**: la modalità nascosta del web qui non c'è, e una schermata
  che si apre senza chiedere niente è il posto sbagliato per mostrarli.
| Build APK | `.github/workflows/build-appsphere-native.yml` → `releases/AppSphereNative-latest.apk` |

### ⚠️ Il pulsante di download deve dire quale versione scarica

Il nome del file è fisso (`-latest.apk`), quindi da fuori una build vale l'altra. L'11 agosto 2026
la 1.0.4 è stata scaricata e installata **al posto della 1.0.5 pubblicata pochi minuti dopo**, e il
sintomo — login fermo dopo la scelta dell'account Google — era identico al difetto che la 1.0.5
aveva appena chiuso: dal solo comportamento non c'era modo di distinguere «la correzione non
funziona» da «la correzione non è a bordo».

Il workflow scrive quindi accanto all'APK una `releases/AppSphereNative-latest.json` (versione,
`versionCode`, data di build, commit, peso, SHA-256) e il pannello di `comandi.html` la mostra sul
pulsante, aggiungendo `?v=<versione>` al link perché il browser non riproponga il pacchetto già
scaricato. La scheda si aggiorna **solo insieme all'APK** — `builtAt` cambia a ogni run, e
pubblicarla da sola annuncerebbe una build nuova per un pacchetto identico — e `_headers` la
tiene fuori dalla cache, altrimenti la pagina annuncerebbe una versione e ne farebbe scaricare
un'altra.

Dal telefono la controprova è la schermata di login, che stampa `Versione nativa · v…`, e da
dentro l'app il ⚙️ in home: `DialogoAggiornamento` legge la stessa scheda, confronta il suo
`versionCode` con `BuildConfig.VERSION_CODE` e apre il download nel browser. È l'unico modo per
sapere **prima** di scaricare se c'è davvero qualcosa di nuovo — la domanda che l'11 agosto non
aveva risposta da nessuna parte.

⚠️ **Lo stesso giro esiste ora anche nell'APK WebView**, ed è voluto che siano due copie parallele:
`build-android.yml` pubblica `releases/GarsalApps-latest.json` con le stesse sette chiavi, e
`android-app/app/.../Aggiornamento.kt` la legge da **☰ → 📱 Versione app** nel launcher. Le
differenze sono solo di forma — lì Compose e `BuildConfig`, qui un `AlertDialog` di AppCompat e la
versione letta dal **pacchetto installato** (`packageManager.getPackageInfo`), che è quella vera e
non quella che il codice credeva di essere. La voce di menù compare **solo dentro l'APK**: il
launcher la mostra se `window.AndroidBridge.checkUpdate` esiste, quindi resta nascosta sul PC e
negli APK precedenti a questo ponte.

⚠️ **E da Smart Blocker, che fa tre** (v1.6.0): `build-smartblocker.yml` pubblica **due** schede —
una per flavor, perché sono due APK con `applicationId` diversi — e la voce sta nel suo ☰ come
nell'APK WebView.

⚠️ **E da SOS, che fa quattro** (v1.1.0): `build-sos.yml` pubblica `Sos-latest.json` e la voce sta
nel suo dialogo **⚙️ Impostazioni**, che è il menù che quell'app ha.

⚠️ **E da «Spese in giro», che fa cinque** (v1.0.0): `build-spese-in-giro.yml` pubblica
`SpeseInGiro-latest.json` e la
voce sta in **⚙️ Impostazioni → 📱 Versione app**. Qui il gemello è in **Compose** e non un
`AlertDialog` di AppCompat — la scheda, le sette chiavi e il giro sono gli stessi, cambia solo con
che cosa è disegnato il dialogo. Cambiando la forma della scheda in un workflow, cambiala
**negli altri quattro** e in `mostraVersione()` di `comandi.html`, che ora disegna tutt'e sei i
pulsanti.

⚠️ **Il pulsante di download c'è SEMPRE, anche quando la scheda non si legge** (v1.0.3): la
versione nel link è allora quella installata. Fino alla v1.0.2 il pulsante era legato alla
scheda — `if (scheda != null)` — quindi una rete lenta, un 404 o un JSON storto **toglievano di
mezzo il download**, cioè la sola cosa per cui quel dialogo esiste, e per giunta in silenzio,
perché `getOrNull()` si mangiava anche il motivo. Ora `Rilascio.scheda()` torna un `Result` e il
dialogo scrive *perché* non l'ha letta, come fa SOS da sempre.

⚠️ **`CATEGORY_BROWSABLE` e il try/catch attorno a `startActivity` non sono prudenza generica**:
sono il gemello di `apriNelBrowser` in SOS. Senza la categoria l'intent può non agganciare nessun
browser, e senza il catch quel caso non è un download mancato ma **l'app che si chiude in
faccia**. Non trovando nessun browser il dialogo lo dice e resta aperto.

### ⚠️ Ta Firi? nativo: il punteggio sta nella RPC, e il promemoria si scrive da qui

`tafiri/` porta in nativo le due voci della sidebar del web, che qui sono due schede —
**Dashboard** (banner del check-in di oggi + sfide attive) e **Storico** (sfide concluse, con
badge, giorni fatti e punteggio finale) — più il **+ galleggiante** che apre `TaFiriForm`, lo
stesso modale di `ta-firi.html`: titolo, obiettivo, inizio, durata, orario di check-in, punti in
palio e schema di punteggio. I punti guadagnati stanno nella top bar invece che nel riquadro
della sidebar.

Le tre regole che **sono** la funzionalità, e che vanno cambiate nelle due implementazioni
insieme:

- **il punteggio finale non si calcola nel client, mai.** *Tutto o niente* e *proporzionale*
  vivono in `sf_finalize_challenge`, che il ViewModel chiama all'apertura su ogni sfida con
  l'ultimo giorno passato e poi rilegge l'elenco. È la stessa regola delle RPC dei task, per la
  stessa ragione: due implementazioni dello stesso punteggio sono due punteggi diversi il giorno
  che una delle due cambia;
- **il check-in di oggi e la correzione di un giorno passato sono due cose diverse.** Il banner
  passa da `sf_checkin_set`, che oltre a scrivere il giorno **sposta il promemoria al giorno
  dopo** (o lo cancella se la sfida è finita); toccare una pallina della griglia gira fra i tre
  stati con un `update` diretto e **non tocca il promemoria** — spostarlo indietro perché si è
  corretto un giorno di tre giorni fa farebbe suonare la sveglia nel passato. È la stessa
  divisione fra `submitTodayCheckin` e `cycleCheckin` nel web;
- **la regola Smart Block la scrive anche il nativo.** Alla creazione, alla modifica (riagganciata
  al prossimo giorno ancora da segnare, non alla data di partenza, che di solito è passata) e alla
  cancellazione, più la rete di sicurezza all'avvio per le sfide attive che ne sono prive —
  `upsertSmartBlockRule` / `ensureSmartBlockRules` riga per riga. Senza questa parte una sfida
  creata dal telefono esisterebbe e non chiederebbe mai niente. Subito dopo si chiama
  `fill-notification-queue` (è l'unica Edge Function invocata dall'APK, e la sola ragione per cui
  `functions-kt` è fra le dipendenze): il cron gira ogni sei ore, e una sfida che parte stasera
  alle 20 non può aspettarle.

`checkin_time` è un orario di Roma e `due_at` un istante UTC: la conversione qui la fa `ZoneId`,
mentre il web se la calcola a mano in `dateTimeToRomeUTC` con le ultime domeniche di marzo e
ottobre scritte in JavaScript. Stesso risultato, e da questa parte il cambio d'ora non è una cosa
da ricordarsi.

Due differenze di forma, volute, entrambe per i caratteri di sistema grandi: sulla scheda ogni
cosa sta **su una riga sua** (nel web titolo, badge e icone condividono una riga che
all'ingrandimento si sfalda da sé, con le icone spinte a capo per prime — cioè proprio quelle che
servono per toccare), e le palline della griglia hanno un diametro che segue `fontScale` invece
dei 32 px fissi del CSS, altrimenti il numero dentro verrebbe tagliato.

### ⚠️ «Ti pisasti?» nativo: pesata, obiettivi e bilancia sì, il resto no

`peso/` porta in nativo **le cose che si fanno col telefono in mano**:
segnare la pesata (il pulsante ⚖️, che scrive in `ps_weight_tracking`), la **Tabella**
giorno per giorno, il **Grafico** dell'andamento, da **✏️ Gestisci**
(`GestioneObiettivoScreen`) creare/modificare/chiudere/cancellare un obiettivo — lo stesso form di
*🎯 Gestione Obiettivo* nel web, campo per campo: nome, tipo (perdere/mantenere), punti
bonus/malus giornalieri e finali, milestone progressive (o le tre voci di «mantenere»: data
inizio, settimane, peso) — e da **⚖️ Salute** aprire la bilancia Renpho e sincronizzare le pesate
lette da Health Connect (`Salute.kt`). La scheda *Oggi* apre con la domanda che dà il nome
all'app — *oggi ti sei pesato?* — e sotto i **sei riquadri della pagina**, nello stesso ordine:
Minimo oggi, Target oggi, Mancano al target, Kg alla fine, Punteggio, Punti oggi.

**Il FAB è un solo pulsante per le due strade della pesata**: il tocco apre un `DropdownMenu`
ancorato al FAB stesso con «➕ Inserisci a mano» (il dialogo di sempre) e «🔄 Sincronizza
(Renpho)» — non due pulsanti separati in barra, che coi caratteri di sistema grandi non
avrebbero spazio garantito.

**«🔄 Sincronizza (Renpho)»** ricalca `openRenpho()` + `onAndroidResume()` + `syncFitNative()` +
`processWeights()` del web, ma **senza passare da un `JavascriptInterface`**: l'app nativa chiama
l'SDK Health Connect direttamente da una coroutine, quindi niente `ExecutorService` con timeout
da 25 s — quella complicazione nel bridge Kotlin dell'APK WebView esiste solo perché lì la
chiamata parte da un thread JS sincrono. La voce apre Renpho (`com.renpho.health`, o lo Store
se non è installata) e arma un flag locale; al **ritorno in primo piano** (`DisposableEffect` +
`Lifecycle.Event.ON_RESUME`) — e solo se il flag è armato, altrimenti ogni ritorno in foreground
per qualunque motivo lancerebbe una sync — legge Health Connect (90 giorni + 25 ore di margine,
stessa finestra del web) e scrive: **peso troncato a un decimale** (`Math.floor`, non
arrotondato), target interpolato sull'obiettivo che si sta guardando, e le pesate **manuali**
degli stessi giorni tolte prima di scrivere quelle vere — altrimenti resterebbero a fare da
doppione. Il permesso si chiede col contratto ufficiale `PermissionController` (l'unico modo per
cui Health Connect sblocchi le letture successive, come nel web) e, quando manca, la
sincronizzazione si rilancia da sola dopo la concessione.

#### ⚠️ Health Connect legge solo 30 giorni indietro, se non glielo si chiede

Senza il permesso `android.permission.health.READ_HEALTH_DATA_HISTORY`, Health Connect lascia
leggere **soltanto i dati scritti nei 30 giorni prima della concessione**. La finestra di 90
giorni che le due app chiedono torna quindi tagliata **senza nessun errore**: la
sincronizzazione sembra funzionare benissimo, su un terzo dei dati.

È esattamente quel che si vedeva il 24 agosto 2026 — la stessa bilancia, lo stesso telefono,
lo stesso istante: **290 pesate** dall'APK WebView (permesso concesso mesi prima, quindi con
tutto lo storico da lì in poi) e **37** dal nativo, che il permesso l'aveva avuto da poco. Il
codice delle due letture era identico, e infatti non era lì. Il permesso è ora dichiarato e
richiesto in tutt'e due (`PERMESSI_SALUTE` in `Salute.kt`, `HC_PERMISSIONS` in `MainActivity.kt`
dell'APK WebView); su un dispositivo dove non esiste resta semplicemente non concesso.

Corollario: **il conteggio da solo non dice niente**. La sincronizzazione nativa mostra ora lo
stesso riepilogo del `showSyncResult()` del web — quante pesate, da quando a quando, l'ultima —
perché la domanda «è la finestra che mi aspetto?» abbia una risposta a schermo. Ed entrambe
seguono ora il `pageToken` di `readRecords`, che ne restituisce al massimo mille per volta:
fermarsi alla prima pagina non darebbe un errore, darebbe qualche pesata in meno.

La **barra di stelline** con `⭐ Punti Totali Traguardi Intermedi` e il **gratta e vinci** ci sono
(`BarraTraguardi` in `GestioneObiettivo.kt`, `DialogoGrattaEVinci` in `PesoGrattaEVinci.kt`), e
dall'agosto 2026 **premi e punti sono gli stessi del web**: stanno in `ps_milestone_prizes` /
`ps_milestone_points` (vedi lo schema `ps_`), non più nelle preferenze del telefono. Un premio
grattato qui si ritrova sul PC già scoperto, e dopo il gratta si può dire **«Mangiato !!!»** —
il premio resta vinto ma si spegne (emoji sbiadita col ✓), ed è reversibile perché un tocco per
sbaglio non deve costare un cannolo. Le vecchie righe rimaste nelle preferenze salgono sul DB da
sé al primo avvio (`PesoPremi.premi`), altrimenti l'aggiornamento avrebbe fatto sparire premi già
vinti. **Restano su `weight-quest.html`**: le statistiche e *genera dieta*.

Le due regole di chiusura stanno nella RPC `ps_chiudi_obiettivo` (APK 1.0.93), che
`PesoViewModel.preparaChiusura()` chiama in anteprima e `chiudiObiettivo()` con la conferma —
la stessa del web, quindi non c'è più niente da tenere allineato:

- «perdere» — il **minimo di oggi** (nessun ripiego sull'ultima pesata nota, a differenza del
  riquadro *Minimo oggi*) deve stare sotto il peso finale, o la chiusura è bloccata;
- «mantenere» — il **massimo del periodo** `[start_date, end_date]` deve stare sotto il peso
  stabilito.

Il punteggio di chiusura (`+final_bonus` sul successo, `-final_malus` sul fallimento, sommato ai
punti giornalieri già maturati) si mostra **prima di confermare**, come il `confirm()` del web.
Elimina non ha questo blocco — un obiettivo chiuso si cancella lo stesso.

Le regole di calcolo stanno tutte in `peso/PesoRegole.kt`, ricalcate una per una dalla pagina, e
**vanno cambiate nelle due implementazioni insieme**:

- **il target di un giorno è interpolato fra i traguardi** (`getInterpolatedTarget`): con meno di
  due traguardi non esiste e diventa un trattino, prima del primo e dopo l'ultimo vale il valore
  estremo, in mezzo è lineare arrotondata a due decimali;
- **i giorni senza pesata contano lo stesso.** Vengono ricostruiti interpolando fra la pesata prima
  e quella dopo (`getInterpolatedWeightFromSeries`) e prendono punti come gli altri: toglierli
  cambierebbe il punteggio. Sono marcati «giorno ricostruito» invece di sembrare una pesata vera;
- **il confronto peso/target si fa a un decimale** (`Math.round(x*10)/10`): 74,04 contro un target
  di 74,0 è una giornata vinta, e a piena precisione sarebbe persa;
- **il target si congela nella riga** al momento della pesata (`target_weight`) e non si ricalcola
  mai: spostare i traguardi domani non deve riscrivere il giudizio sui giorni già passati;
- **`timestamp` (millisecondi di giorno + ora) è la chiave** della pesata: l'upsert ci si appoggia,
  quindi ripesarsi alla stessa ora riscrive la riga di prima invece di aggiungerne una.

Due differenze di forma. La **tabella non è una tabella**: sei colonne coi caratteri di sistema
grandi si tagliano o vanno a capo ognuna per conto suo, quindi ogni giornata è una scheda con la
data e il peso in cima. E il **grafico è disegnato a mano su un `Canvas`** invece che con Chart.js:
restano le curve che si guardano davvero — il peso e il target — perché un grafico fitto di
etichette su uno schermo di telefono è illeggibile prima ancora di essere utile. La spezzata del
target è quella dei **traguardi presi diretti**, non i valori interpolati giorno per giorno che
restano nella tabella: fra un traguardo e l'altro serve la retta vera, non tanti segmenti
arrotondati a due decimali che sembrano seghettati.

⚠️ **Del peso si disegnano minimo e massimo della giornata, non il solo minimo.** Il minimo è la
pesata del mattino — quella che fa punti, e per questo è la linea piena — mentre il massimo è una
linea più sottile e sbiadita; la **fascia fra le due** è quanto il peso è ballato quel giorno, che
con la sola linea del minimo non si vedeva affatto. ⚠️ Un giorno **ricostruito** un massimo non ce
l'ha (`RigaGiorno.massimo` è `null`): lì il massimo ripiega sul minimo e la fascia si chiude su sé
stessa — inventarne una vorrebbe dire disegnare un'oscillazione che nessuno ha misurato, ed è la
stessa regola del pallino che sui giorni ricostruiti non si disegna.

⚠️ **Il colore dice se si sta dentro il piano: sotto o pari al target è verde, sopra è rosso** —
fascia, linee e pallini insieme. Non è una decorazione, è la **stessa** domanda che decide i punti
della giornata (`PesoRegole.punti`, confronto a un decimale), quindi una giornata verde nel grafico
è una giornata guadagnata nella tabella. Il taglio si fa **ritagliando sulla spezzata del target
già disegnata** (`clipPath` sulle due regioni sopra e sotto di lei) e non spezzando le curve a
mano: il confine cade così esattamente sulla linea che l'occhio confronta, e una giornata a cavallo
del target viene per metà verde e per metà rossa — il minimo dentro il piano e il massimo fuori è
il caso normale, non un difetto. ⚠️ Senza curva di traguardi (meno di due) non c'è nessun target da
superare e il peso resta del suo colore neutro: un verde o un rosso lì sarebbe un giudizio
inventato. ⚠️ Il verde del peso (`VerdePeso`, `#00967A`) è **un passo più scuro** di quello della
spezzata del target: le due linee si incrociano di continuo, e con lo stesso identico verde nel
punto in cui si toccano non si distinguerebbe più quale si sta guardando.

⚠️ **Il grafico copre l'intero periodo dell'obiettivo, futuro compreso, ed è scorrevole.** La
curva del peso comincia dal giorno di inizio (non dal mese di respiro che il caricamento tiene
prima per la prima interpolazione) e si ferma all'ultima pesata — il futuro non si disegna, solo
il piano lo promette. La spezzata del target invece arriva fino alla **fine** dell'obiettivo anche
se quel giorno non è ancora passato: è l'unico modo per vedere a colpo d'occhio quanto manca al
traguardo finale, non solo quanto già fatto. Il `Canvas` del disegno è largo quanto l'intero
periodo (16.dp per giorno) dentro un `Box` con `horizontalScroll`, mentre la colonna delle
etichette in kg a sinistra resta fissa e non scorre — altrimenti scorrendo si perderebbe subito il
riferimento dell'altezza della curva. Il passo è quello **di partenza**: si stringe e si allarga
col **pizzico** (`pinch`, due dita), fra la scala che fa stare tutto il periodo in una schermata e
un massimo di 48 dp al giorno. ⚠️ Il gesto è scritto a mano e intercettato nel passaggio
**`Initial`**: `detectTransformGestures` e `transformable` prendono anche il trascinamento a un
dito — cioè si mangerebbero lo scorrimento — e `Main` arriverebbe prima allo scorrimento, che
tratterebbe il pizzico come un trascinamento. Il giorno al centro si legge prima di cambiare scala
e si ritorna lì dopo, o stringendo le dita il grafico scivolerebbe via da sé; il passo delle date
segue lo zoom (settimana, due settimane, quattro) e i pallini spariscono sotto gli 8 dp al giorno,
dove si impasterebbero. In **altezza** prende tutto quello che avanza
(`weight(1f)`), meno il posto del FAB in basso: sotto il disegno non c'è più niente scritto — le
due righe che spiegavano come leggerlo si prendevano un terzo dello schermo per dire quel che si
vede, e legenda e minimo del periodo stanno ora in una riga sola **sopra**.

⚠️ **Le misure del disegno sono in `dp`, mai in pixel grezzi.** Spessori, margini e la fascia
sotto l'asse erano numeri in px (`4f`, `34f`): su uno schermo denso valgono un terzo di quello che
sembrano, e le date sotto l'asse venivano **tagliate a metà** — la fascia era più bassa del testo
che ci andava dentro. Un pallino segna ogni pesata **vera** e non i giorni ricostruiti (che la
linea attraversa lo stesso): un pallino su un giorno interpolato sembrerebbe una misura che non
c'è mai stata. All'apertura la vista si centra da sé su **oggi** (marcato
da una riga tratteggiata verticale con l'etichetta «Oggi»), con circa due settimane prima e dopo
in vista: lo scorrimento iniziale si calcola con un `LaunchedEffect` sulla larghezza reale del
riquadro (`onSizeChanged`), disponibile solo dopo il primo posizionamento — prima di allora il
riquadro è 0×0 e non c'è niente da centrare.

`ps_weight_tracking` e `ps_objectives` non stanno in nessuna migration e si leggono come
`JsonObject`, non come `data class` serializzate: è la stessa scelta di `ts_tasks` e per la stessa
ragione — `id` può essere un numero o un uuid e `weight` un intero o un decimale, e con una data
class una colonna del tipo inatteso non darebbe un campo storto ma la schermata vuota.

### ⚠️ Memo nativo: il contenuto è HTML, e il giro deve chiudersi

`memo/` porta in nativo le schede di `memo.html` — **note, liste, diari, link e 🏅 premiati**: le
**sei Tab** del web (📄 Note, ☑️ Liste, 📊 Diari, 🔗 Link, 🏅 Premiati, 📌 Fissa), ricerca su titolo e testo, filtro per categoria,
ordinamento (ultime modificate / ultime create / titolo), il **dettaglio in lettura** e la
**modifica** con foto e OCR. Le tabelle sono le stesse (`mm_cards`, `mm_card_categories`,
`mm_images`, `mm_list_items`, `mm_diary_metrics`, `mm_diary_entries`) più le categorie condivise
`cm_categories`, e si leggono come `JsonObject`: non stanno in nessuna migration — nascono dal SQL
che la pagina mostra in Impostazioni — quindi vale la stessa scelta di `ts_tasks`.

**Un 🏅 premiato si apre in lettura come una nota** (`MemoDettaglio`, coi punti sopra il testo): è
una nota più un numero, e non ha niente da spuntare né da registrare. Tre differenze di forma,
volute, tutte per i caratteri di sistema grandi: il **totale sta sopra l'elenco** e non nel titolo
della barra (che porta già il nome della Tab, e una seconda scritta accanto verrebbe tagliata); i
punti sulla scheda sono un **segno accanto a quello del tipo**, grigio a zero; e accanto alla
casella c'è un **±**, perché `KeyboardType.Number` apre un tastierino che su parecchi telefoni **il
meno non ce l'ha** — e un punteggio negativo è un malus, non un errore di battitura.

Come sul web, **una nota si apre in modifica e una lista o un diario no**: hanno una vista propria
(`MemoListaView`, `MemoDiarioView`), perché la cosa che si fa più spesso su di loro — spuntare una
voce, aggiungere una registrazione — non è modificare la scheda. All'editor si arriva da lì col ✏️,
e il **+ crea una scheda del tipo della Tab** invece di chiedere quale. **📌 Fissa è l'unica Tab che
attraversa i tre tipi**, ed è la ragione per cui il segno del tipo resta sulla scheda anche ora che
ogni Tab ne mostra uno solo.

**Il punto delicato è `content`, che è HTML** scritto da un `contenteditable` con `execCommand`. Qui
un contenteditable non c'è, e il giro è in due tempi (`MemoHtml`): l'HTML diventa **testo con
marcatori** (`**grassetto**`, `*corsivo*`, `__sottolineato__`, `~~barrato~~`, `#`/`##` per i
titoli, `- ` e `1. ` per gli elenchi, `---` per la linea, `[testo](url)`, `![](url)` per le
immagini incorporate), si modifica quello, e al salvataggio **si ritorna in HTML**. I pulsanti
della barra infilano i marcatori attorno a quel che è selezionato e l'occhio 👁 mostra il
risultato vero; in lettura si rende l'HTML com'è (`AnnotatedString.fromHtml`), non i marcatori.

⚠️ **Ogni voce della barra del web deve avere il suo marcatore qui.** Aggiungendone una là senza
aggiungerla qui, una scheda modificata dal telefono perde quella formattazione **in silenzio** —
il giro non si chiude più. Un tag sconosciuto invece non fa danni: si scarta il tag e si tiene il
testo, come `stripHtml()`.

Le altre regole copiate riga per riga, da cambiare nelle due implementazioni insieme:

- **le categorie si riscrivono da capo a ogni salvataggio** (`delete` di tutte le righe della
  scheda e `insert` di quelle scelte, come `saveCard()`): senza la cancellazione una categoria
  tolta resterebbe attaccata per sempre;
- **cancellando si tolgono prima i file dal bucket, poi la riga**: `mm_images` sparisce da sé per
  cascata, ma il bucket quel vincolo non lo conosce, e nell'ordine inverso i file resterebbero
  senza più nessuna riga che dica dove sono;
- **una scheda vale se ha il titolo *oppure* il contenuto**, non per forza tutt'e due;
- **le foto si caricano dopo la scheda**, perché il percorso nel bucket è
  `utente/scheda/file` e per una scheda nuova l'id non esiste prima; il tetto è 5 MB a foto come
  nel web.

L'OCR è **ML Kit**, come nell'APK WebView (là passa da `AndroidBridge.performOcr`, qui si chiama
direttamente), ma nella variante **non incorporata**
(`com.google.android.gms:play-services-mlkit-text-recognition`): il modello lo tiene Google Play
Services e lo scarica all'installazione, grazie al `meta-data com.google.mlkit.vision.DEPENDENCIES`
nel manifest. ⚠️ La variante `com.google.mlkit:text-recognition`, che il modello se lo porta dentro
per tutte e quattro le ABI, ha portato l'APK da 30 a **73 MB** in un colpo solo (v1.0.18, ritirata
subito): è lo stesso difetto di `material-icons-extended` con un'altra faccia, e con l'APK
committato a ogni build ci va di mezzo anche il peso della repo. Il codice è identico nelle due
varianti — cambia solo dove sta il modello. Il ripiego su Tesseract non c'è — serve al
browser desktop, che ML Kit non ce l'ha. Il testo estratto si accoda dopo una linea `---`, come sul
web. Fissare una scheda dall'elenco (📌) **non passa dalla conversione**: riscrive `content` com'è,
o un giro andata-e-ritorno si porterebbe via la formattazione senza che nessuno abbia toccato il
testo.

Restano su `memo.html` la tavolozza del colore personalizzato (qui ci sono i sette campioni) e la
scala dei caratteri delle schede, che su Android la decide già il sistema.

#### ⚠️ `riservato`: il filtro sta nella query, non nel disegno

La spunta 🙈 **Riservato** (`mm_cards.riservato`) si vede **solo nella definizione di un diario**,
come sul web: su una nota o una lista il campo non c'è e il salvataggio manda `false`. La colonna è
però su `mm_cards` e il filtro vale per tutti i tipi, quindi estenderla è una riga di Compose.

⚠️ Fuori dalla modalità nascosta **la riga non viene proprio letta**: il filtro è nella `select` di
`MemoRepository.schede()`, non nel rendering. Nasconderla solo a schermo la lascerebbe in chiaro a
chiunque guardi il traffico o la memoria dell'app, che è esattamente ciò da cui la modalità
protegge. Gli stessi tre stati del web: spenta si vedono solo le schede normali, accesa si vede
tutto, accesa col 👁 si vedono **solo** le riservate — e il pulsante 🙈/👁 compare solo a modalità
già accesa, perché a modalità spenta non c'è niente da alzare.

Una differenza dal web, e vale la pena saperla: là il ramo dei NULL (`riservato.is.null`) è scritto
per prudenza, qui c'è la sola uguaglianza. La colonna nasce `NOT NULL DEFAULT false`, quindi una
riga senza valore non può esistere e i due filtri dicono la stessa cosa.

#### ⚠️ Liste e diari: le stesse tre regole di qua

Le tre cose che **sono** la funzionalità, e che vanno cambiate nelle due implementazioni insieme:

- **le spunte di una lista sono ottimistiche con rollback** (`MemoViewModel.spuntaVoce`, come
  `toggleViewItem()` e come Spuntiamola): la voce cambia subito e torna indietro se il database
  rifiuta, così non resta a schermo una spunta finta che sparisce al ricarico;
- **le misure si aggiornano riga per riga e non si cancellano per ricrearle**
  (`MemoRepository.salvaMisure`, come `syncDiaryMetrics()`): le registrazioni le citano per id
  dentro `measures`, e ricreandole tutto lo storico resterebbe senza nome. Per la stessa ragione
  **le opzioni di una combo tengono il loro id** mentre se ne cambia l'etichetta, e la
  registrazione archivia l'**id**, mai l'etichetta;
- ⚠️ **una misura non toccata non finisce in `measures`**, e non ci finisce come zero: «non l'ho
  misurata» e «vale zero» sono due cose diverse, e uno slider lasciato a metà scriverebbe la
  seconda al posto della prima. Una misura non registrata mostra `—`, e *Non l'ho misurata* la
  toglie anche a posteriori.

Ne discendono le stesse conseguenze del web, già in codice: una **misura tolta** non cancella le
registrazioni (i valori restano con una chiave che non ha più una riga, e si mostrano come *misura
tolta*), un'**opzione tolta** si dice invece di far sparire il valore, e una **scelta non ha un
numero** — `MmMisura.numerico()` torna `null` — quindi nel riepilogo al posto dello storico in
miniatura c'è la **distribuzione**, che è la domanda che una combo pone davvero.

Due differenze di forma, volute, entrambe per i caratteri di sistema grandi: le **opzioni di una
combo sono pulsanti e non una tendina** (una tendina taglia le etichette proprio dove la scelta si
fa), e lo storico in miniatura è disegnato con dei `Box` invece che con le `.spark` del CSS —
questa schermata non carica nessuna libreria di grafici, come la pagina.

Il **titolo della registrazione è obbligatorio nell'app, non nel database**, esattamente come sul
web: `mm_diary_entries.title` resta `NOT NULL DEFAULT ''` perché le registrazioni fatte prima della
colonna un titolo non ce l'hanno, e un vincolo che le rifiutasse renderebbe impossibile perfino
aprirle per correggerle.

### ⚠️ Abituati nativo: le regole stanno nel database, non in Kotlin

`abituati/` porta in nativo le abitudini di `habit-tracker.html`: la scheda
🎯 **Oggi** con le spunte (fatto / fallito, e una riga per orario sulle abitudini a più
slot), 📋 **Tutte** con creazione, modifica, **interruzione, ripresa** ed eliminazione, 📦 **Archivio**
degli stack finiti, più le due cerimonie del web — lo stack vinto e il game over, ciascuna con
*Ricomincia da una data* oppure basta.

⚠️ **Dalla APK 1.0.108 ci sono anche le altre pagine del web**, nella stessa riga di viste che
scorre di lato: 📊 **Statistiche** (i quattro numeri, l'andamento degli ultimi sei mesi, la
classifica per streak — gemelle di `renderStats()` / `renderMonthlyChart()`), 🏷️ **Categorie**
(crea, modifica, elimina `cm_categories`, l'🗑 solo se nessuna abitudine attiva la usa; ⚠️ sono
**condivise con Tasks** e la pagina lo dice), ⚙️ **Impostazioni** (la console di debug: contatori
e registro delle operazioni della sessione) e i 🔔 **promemoria nel form**: orario di riferimento
e le due Tab 📱 Telegram / 📲 Telefono coi loro anticipi. Le regole le scrive
`AbituatiRepository.sincronizzaPromemoria`, **gemella campo per campo di
`syncHabitNotificationRule()`** — `completion_update` sempre sulla riga android, solo col
pulsante su quella Telegram, `from-to` = inizio → inizio + obiettivo − 1 — e va cambiata insieme a
lei. ⚠️ La classifica ordina per lo streak della RPC (quello mostrato), non per la colonna
`current_streak` che legge il web.

⚠️ **Un'abitudine `count_window` in 🎯 Oggi non ha i due pulsanti ma le FINESTRE** (`Finestre` /
`Finestra` in `AbituatiScreen.kt`, la copia di `renderCountWindowStack` / `renderWindowCard`): una
scheda per finestra con dentro `max(N, M)` pallini, il ＋ che ne colora uno di verde e il − che
riporta a bianco l'ultimo. Le stesse regole del web — rossi solo a finestra chiusa, ＋ e − solo
sulla finestra in corso, ＋ spento al tetto giornaliero — e i tre campi nel form con lo stesso
vincolo **N ≤ M × P**.

⚠️ **Il ＋ è l'unica scrittura su `hb_completions` che non passa da una RPC**, e non è uno
strappo alla regola: `hb_set_completion` cerca la riga per `period_key = <giorno>` e con più
spunte nello stesso giorno **aggiornerebbe la prima** invece di aggiungerne una — il secondo tocco
non farebbe niente di visibile. La chiave porta quindi il progressivo (`2026-09-16#2`), ed è la
stessa ragione per cui nel web `cwAggiungi()` non passa da `setDayState()`. Jolly, streak e
chiusura restano dove sono: li rifà `hb_reconcile` alla lettura successiva.

⚠️ **Il progressivo si LEGGE DALLA CHIAVE e non si conta**, e il − toglie la riga col
**(giorno, progressivo) più alto** e non col `completed_at` più grande. Tutte le spunte dello
stesso giorno nascono a mezzogiorno, quindi «l'ultima» per istante è un sorteggio: il − poteva
togliere `#1` lasciando `#2`, e il ＋ successivo — che contava le righe — riprovava `#2`, che
c'era già. Il sintomo era `duplicate key value violates unique constraint
"hb_completions_habit_period_unique"` a schermo, su un gesto che dall'esterno sembrava
legittimo. ⚠️ **E una scrittura per volta, per abitudine** (`inVolo` in `AbituatiViewModel`,
`cwInVolo` nel web): un tocco può arrivare doppio, e due giri che leggono lo stesso stato
scrivono due volte la stessa chiave. Le tre regole sono gemelle di `cwProgressivo` /
`cwUltimoProgressivo` / `cwTogli` in `habit-tracker.html` e **vanno cambiate insieme**.

⚠️ **Si vede la SOLA finestra in cui cade oggi** (APK 1.0.86), larga quanto la scheda. Fino alla
v1.0.85 c'erano tutte, una riga che scorreva di lato e si apriva su quella in corso — ma quella in
corso è l'unica su cui si possa fare qualcosa, e con un obiettivo di trenta finestre le altre
ventinove erano trenta schede da trascinare per tornare dov'eri. È la stessa scelta dei sette
pallini della settimanale, che qui mostrano la sola settimana in corso mentre il web le sfoglia
tutte: 🎯 Oggi risponde a «cosa devo fare adesso», non a «com'è andata finora». **Il web resta
com'è** (`renderCountWindowStack` le elenca tutte): là lo schermo è largo.

⚠️ **Il prezzo è dichiarato**: pallini rossi, bordo rosso e la nota «N non fatte» vivono sulle
finestre **chiuse**, quindi in nativo non si vedono più — quante se ne siano perse lo dice il
`🔥 N di M finestre` sopra. Per questo `Finestra` non disegna più nessun rosso: la finestra in
corso non è mai una finestra persa. ⚠️ **Lo storico c'è, in 📋 Tutte** (APK 1.0.108,
`StoricoFinestre` in `AbituatiViste.kt`): tutte le finestre in una riga che scorre e si apre su
quella in corso, coi pallini rossi delle chiuse e senza pulsanti. Lo stesso per le settimane
(`StoricoSettimane`, gemella di `renderWeeklyHabitStack()`).

⚠️ **Oggi può cadere FUORI da ogni finestra** — prima che l'abitudine cominci, o dopo l'ultima
della stecca — e allora la scheda lo scrive invece di non disegnare niente: un riquadro che
sparisce si legge come un difetto.

### ⚠️ Un pulsante solo per tutte le frequenze: `PulsanteSimbolo` e `RiquadroSpunte`

⚠️ **I quattro simboli sono lo STESSO pulsante** (`PulsanteSimbolo`, APK 1.0.87): `✓`, `✕`, `−` e
`＋` hanno forma, taglia, angoli e carattere identici, e a cambiare sono i soli **colori**. Fino
alla v1.0.86 erano due famiglie diverse a schermo — due pillole chiare con dentro un'emoji sulla
giornaliera, due rettangoli pieni coi glifi sulla finestra — e le due schede della stessa app si
leggevano come due app.

⚠️ **I simboli sono GLIFI e non emoji**, ed è la metà che conta di quell'omogeneità: un'emoji si
disegna coi suoi colori, quindi ✅ e ❌ accanto a un `−` e a un `＋` monocromi restavano di un'altra
famiglia comunque si pareggiassero forma e taglia. Sono gli stessi **✓ e ✕ del web**.

⚠️ **Il colore vuol dire due cose diverse, e la differenza è voluta**: su `✓`/`✕` dice **quale
stato è scelto** — acceso col suo colore, spento sul fondo chiaro, e premibili tutt'e due — su
`−`/`＋` dice **se si può premere**, perché quelle sono azioni e non stati. ⚠️ Il pulsante spento
porta un **bordo da 1 dp**: dentro un riquadro già chiaro un fondo bianco quasi non si distingue, e
un pulsante che non si vede è un pulsante che non si preme. Sotto un fondo pieno quel bordo
scompare da sé.

⚠️ **Le spunte stanno in un RIQUADRO, come una finestra** (`RiquadroSpunte`): stesso bordo da 2 dp,
stessi angoli, stesso fondo e stesse spaziature, per **tutte** le frequenze — una giornaliera, una
settimanale (col riepilogo dei sette pallini dentro) e **un riquadro per orario** su una
`daily_multiple`, con l'ora al posto di «FINESTRA 1». In un riquadro solo, due righe di pulsanti si
leggerebbero come quattro pulsanti di una cosa sola.

⚠️ **Il bordo del riquadro non è decorazione, dice com'è andata oggi** (`bordoDiStato`): verde
fatto, rosso mancato o fallito, nero ancora da fare — ed è lo stesso segno del «finestra piena →
verde». Prima quell'esito lo diceva solo il colore acceso di un pulsante, che su un giorno
`missed` non è acceso affatto.

⚠️ **La riga dei simboli è centrata, e vuole un `Row` normale e non una `RigaScorrevole`**
(`RigaSimboli`): là il contenuto si misura a larghezza illimitata, quindi la riga è larga quanto
quel che porta e `Arrangement.Center` non ha spazio da distribuire — è lo stesso motivo per cui lì
dentro non si può usare `weight(1f)`. Con due sole icone non c'è più niente da nascondere, quindi
lo scorrimento non serve.

⚠️ **La larghezza minima di un pulsante a simbolo solo è in `dp`** (`TOCCABILE`, 64.dp), ed è
l'eccezione alla regola di `larghezzaPulsanti`: un glifo darebbe una pillola da una quarantina di
dp, cioè sotto il minimo toccabile. Il dito è grande uguale a qualunque ingrandimento dei
caratteri, quindi quello è una misura **fisica** — è la stessa ragione per cui il pavimento delle
bolle in home è in `dp`. Sopra il minimo comanda comunque il testo misurato. ⚠️ E il simbolo si
disegna **col carattere con cui quella larghezza è stata misurata** (`bodyMedium`): scrivendolo più
grande, a caratteri di sistema molto grandi uscirebbe dal pulsante.

⚠️ **Senza etichetta serve il `contentDescription`**: un simbolo da solo, letto da TalkBack, non
dice cosa fa il pulsante.

⚠️ **Un pulsante spento resta a schermo, sbiadito**: farlo sparire cambierebbe l'altezza della
scheda a ogni spunta, e un pulsante che scompare si legge come un difetto. È la stessa scelta del
🎯 «scopertura a zero» di Finanza.

⚠️ **Nel web niente di tutto questo cambia**: là la riga d'azione non è stretta, le etichette
restano e le schede non hanno quel riquadro.

⚠️ **Una SETTIMANALE ha i due pulsanti _e_ i sette pallini** (`SettimanaPallini`, APK 1.0.84):
sotto i pulsanti, lunedì → domenica, coi cinque stati e i colori di `weekDotStyle()` nel web —
verde fatto, blu jolly, rosso mancato, bianco col bordo da fare, **grigio pieno un giorno che
l'abitudine non prevede** (o precedente al suo inizio). ⚠️ Quel grigio non è «non fatto», ed è la
distinzione che regge la riga: senza, una settimanale di tre giorni si leggerebbe come quattro
giorni saltati.

⚠️ **C'è la sola settimana in corso**, mentre il web le sfoglia tutte: 🎯 Oggi risponde a «cosa
devo fare adesso», e una striscia di settimane dentro la scheda di una lista che scorre in
verticale sarebbe uno scorrimento dentro l'altro — le settimane passate si guardano in 📋 Tutte. ⚠️ I
pallini sono **22.dp, quelli delle finestre**, e la riga **scorre** invece di andare a capo: coi
caratteri di sistema grandi le sette lettere sotto i pallini sono più larghe dei pallini stessi.
⚠️ **Gli stati sono quelli del web perché lo sono anche i pulsanti**: dalla v1.0.85 sotto i
pallini ci sono il solo ✅ Fatto e ❌ Fallito, e `skipped` non si può più comporre (vedi il
paragrafo qui sotto).

⚠️ **Dalla v1.0.86 quei due pulsanti portano il SOLO simbolo, centrati**, e dalla v1.0.87 lo
portano **su tutte le frequenze** e non più sulla sola settimanale: `✅ Fatto` / `❌ Fallito` sono
diventati `✓` / `✕`, gli stessi `PulsanteSimbolo` del `−` e del `＋` — vedi il paragrafo *Un
pulsante solo per tutte le frequenze* qui sopra. Sulla settimanale le parole ripetevano quel che i
sette pallini già dicono; sulle altre due a spiegare il simbolo c'è ora il colore, che è lo stesso
di una finestra.

⚠️ **I rossi sono immediati**, come nel web e al contrario di `count_window`: là la finestra si
può ancora riempire in un altro giorno, qui il giorno dovuto è quello e basta. Il rosso però lo
dice la riga `missed` di `hb_reconcile` e non un conto in Kotlin — un giorno passato che la
riconciliazione non ha ancora toccato resta bianco, ed è quel che è.

⚠️ **I pulsanti di un periodo sono DUE e non tre** (v1.0.85): ✅ Fatto e ❌ Fallito, come il ✓ e
il ✕ del web — dove quel ✕ si chiama «Saltato (Jolly)», cioè **saltare un giorno *è* `failed`** ed
è il jolly a pagarlo. Fino alla v1.0.84 ce n'era un terzo, **⏭ Saltato**, che scriveva
`status = 'skipped'` e **falliva sempre**, su ogni frequenza: il CHECK
`hb_completions_status_check` non ammette quel valore — `hb_completions` è nata a mano e conosce
`completed`, `failed` e `missed` — quindi il tocco restituiva l'errore del database a schermo.

⚠️ **E non era un valore da aggiungere al CHECK**: nelle RPC `skipped` non conta niente — non è un
jolly (`hb_fallimenti` somma `failed` e `missed`) e non è un giorno fatto — ma rende il giorno
**«risolto»**, quindi `hb_reconcile` non ci scrive sopra `missed`. Sarebbe un giorno che passa
liscio senza costare un jolly, cioè esattamente il buco che il jolly esiste per chiudere.
⚠️ `hb_set_completion` quel valore **lo ammette ancora** (`p_stato NOT IN (…, 'skipped', …)`) e non
lo passa più nessuno: toglierlo di lì è una modifica alla RPC, e il CHECK della tabella lo ferma
comunque un gradino più sotto.

📋 **Tutte** ha la stessa **tendina sullo stato** della pagina (*Attive / Interrotte / Tutte*,
`FiltroStato`), e parte da «Attive» come là: le interrotte sono memoria, non lavoro di oggi, e un
elenco che le mescola alle vive fa sembrare da spuntare qualcosa che è fermo. Sulla scheda di
un'interrotta la **modifica non si offre** — quel che serve è rimetterla in moto — e i pulsanti
stanno in una `RigaScorrevole` con le larghezze misurate su **tutte e quattro** le etichette
possibili, comprese quelle che quella scheda non mostra.

⚠️ **Riprendere chiede da quale data ripartire e riscrive `started_at`** (proposta: il **giorno
dopo l'ultima spunta**), esattamente
come nel web: con la data originale il primo `hb_reconcile` marcherebbe `missed` ogni giorno passato
dall'interruzione — guarda da `started_at` a ieri — i jolly finirebbero sul posto e il game over
scatterebbe prima ancora di rivedere la scheda. Quanto costa la data scelta lo dice
`hb_giorni_da_recuperare`, **la stessa RPC che chiama la pagina**, riletta a ogni cambio di data; se
i jolly non bastano il pulsante non si blocca, cambia scritta in *Riprendi lo stesso*.
`current_failures` torna a zero perché è una cache che la riconciliazione ricalcola.

Interrompere e riprendere sono un `update` diretto su `status` in tutt'e due le implementazioni, e
non è un'eccezione alla regola qui sotto: le RPC governano dove va la prossima occorrenza, non se
l'abitudine è in corso. L'interruzione **annulla anche le notifiche già in coda** prima di
cancellare la regola (una partita dopo chiederebbe di spuntare un'abitudine ferma): stesso ordine di
`deleteHabitNotificationRule()`.

**Qui non c'è nessuna regola.** Streak, jolly, giorni mancati e chiusura degli stack vivevano solo
nel JavaScript della pagina; riscriverli in Kotlin avrebbe voluto dire due copie della stessa
formula, e in ballo ci sono punti e archivi. Sono invece scesi nel database
(`20260815120000_hb_regole_rpc.sql`), che è la stessa scelta di `task_complete` / `task_skip`:

| Funzione | Cosa fa |
|---|---|
| `hb_streak` / `hb_giorni_fatti` / `hb_fallimenti` | Le tre misure. Sola lettura, `SECURITY INVOKER` perché la RLS resti in mezzo |
| `hb_set_completion` | **L'unica strada per segnare un periodo**: scrive la riga e ricalcola i jolly |
| `hb_reconcile` | Il giro che il web fa a ogni disegno della dashboard: periodi passati senza riga → `missed`, jolly riallineati, stack completati archiviati, stack scaduti chiusi. Torna cosa è successo, perché il client mostri le sue cerimonie |
| `hb_clona` / `hb_chiudi_stack` | Le due uscite del game over: *Ricomincia* e *Interrompi* |
| `hb_giorni_da_recuperare` | Quanto costa **riprendere** un'abitudine interrotta da una certa data: un jolly per giorno dovuto senza spunta. Sola lettura, la chiamano tutt'e due i client |
| `hb_giorno_risolto` | Di quel giorno resta qualcosa in sospeso? Ogni periodo dovuto ha la sua riga, comunque sia andata — **non** «è andato bene», che è `hb_giorno_fatto`. È la condizione che lascia chiudere uno stack già l'ultimo giorno |

Tre cose che *sono* la funzionalità:

- **la fonte di verità sono i completamenti.** `current_failures` non si incrementa e non si
  decrementa: si **ricalcola** da `hb_completions` a ogni scrittura. È quello che il JS già faceva
  nel vecchio `checkMissedDays`, ed è ciò che permette a due client di segnare lo stesso giorno senza
  contare due volte lo stesso jolly;
- **«oggi» lo passa il client** (`p_oggi`), come `p_today` in `task_complete`: il database sta in
  UTC e fra mezzanotte e le due sarebbe ancora ieri;
- **lo stack che ha esaurito i jolly si segnala e basta.** La chiusura è una cerimonia con una
  scelta dentro, e la scelta la fa l'utente: `hb_reconcile` la annuncia, `hb_chiudi_stack` la
  esegue dopo.

✅ **Il giro è chiuso da tutt'e due le parti** (web v9.6.0, 24 settembre 2026): anche
`habit-tracker.html` chiama `hb_set_completion`, `hb_reconcile` e `hb_chiudi_stack`, e la sua copia
in JavaScript (`checkMissedDays`, `checkCompletedStacks`, `checkExpiredStacks`, `handleFailure`,
`restoreJolly`, `isDayResolved`) non c'è più. **Una modifica alle regole si fa nel SQL e basta.**
Nella pagina restano `riconcilia()` — chiama `hb_reconcile` a ogni `renderDashboard()` e mostra le
cerimonie che la RPC le restituisce — e `hbSegna()`, l'unica strada per segnare un periodo.

⚠️ Tre cose restano nel client, di proposito: il **＋/− di count_window** (la ragione è sotto:
`hb_set_completion` aggiornerebbe la prima spunta del giorno invece di aggiungerne una), lo **streak
a schermo** (`computeHabitStreak`, solo disegno: non chiude più niente) e la **ricreazione di un
ciclo già tolto** (`ricreaCiclo`): uno stack scaduto `hb_reconcile` lo archivia e cancella, quindi
`hb_clona` non ha più la riga da copiare, e il ciclo nuovo si scrive dalla `scheda` che la RPC
restituisce. Per questo `hb_reconcile` porta `regole` — **tutte** le regole di promemoria, una per
canale — accanto a `regola`, che resta per l'APK già installata
(`20260924120000_hb_clona_count_window_e_regole.sql`).

⚠️ **Le cerimonie delle abitudini 🙈 riservate non si mostrano fuori dalla modalità nascosta**: la
RPC gira su tutte le righe, e il nome finirebbe in un modale. Un game over da jolly esauriti si
ripresenta al giro dopo; uno stack già archiviato resta in archivio.

⚠️ **`max_failures` vuoto vale 3**, e non nessun limite: il web l'ha sempre letto così
(`|| 3`), mentre le RPC ci vedevano zero, cioè niente game over. La migration di qui sopra ha
riempito le righe NULL e il form non ne scrive più. ⚠️ **Zero invece resta zero**, e per le RPC
vuol dire «nessun game over da jolly»: nel vecchio JavaScript `|| 3` trasformava anche quello in 3.

⚠️ `hb_clona` copia ora anche `times_target`, `window_days` e `max_per_day`: fino al
24 settembre 2026 «Ricomincia» dal telefono su una count_window dava una copia senza N né M.

### ⚠️ Calorie nativo: due pagine, e il conto è quello della pagina

⚠️ **Dal 9 settembre 2026 Calorie NON ha più una bolla in home**: peso e calorie sono un'app sola
— sul web una pagina sola (`weight-quest.html`, sette viste) — e qui si arriva dal 🍽️ nella barra
di «Ti pisasti?» (`PesoScreen`, parametro `onApriCalorie`). `Route.CALORIE` resta e `CalorieScreen`
non è cambiata; a sparire è la voce `calorie.html` in `PortedApps.perHtmlFile`, insieme alla riga
spenta in `cm_apps`.

⚠️ **È la stessa distanza che hanno sul web**, dove ⚖️ Peso e 📓 Diario sono due voci della stessa
barra: un tocco, di là come di qua.

⚠️ **Dall'APK 1.0.110 il passaggio è l'interruttore ⚖️ Peso | 🍽️ Calorie nella barra blu**
(`InterruttorePesoCalorie` in `core/IconeLinea.kt`), gemello del `.modo-switch` del web e
**uguale nelle due schermate**: da Calorie torna al peso con un `popBackStack` se si arriva da lì,
altrimenti (scorciatoia «Segna un pasto») apre il peso al posto di Calorie — o le due si
impilerebbero a ogni tocco. Le pillole delle viste, il FAB «Pesati» e la voce «Sincronizza
(Renpho)» portano le **icone a tratto** di `IconeLinea`, copia tracciato per tracciato dello
sprite `#i-…` di `weight-quest.html`: cambiando un disegno di là, va cambiato anche qui. Senza quell'icona le schermate di `calorie/` sarebbero rimaste
scritte e irraggiungibili — la bolla era la loro unica via d'accesso.

⚠️ **Le due implementazioni restano due**: qui `calorie/` e `peso/` sono due moduli, e il target
interpolato lo dà `PesoRegole.targetInterpolato` (già l'unico posto di qua). Sul web invece la
formula è tornata **una sola** con l'unione dei due file, quindi la regola da tenere allineata è
fra web e nativo, non più anche fra due file web.

### 🍽️ La scorciatoia «Segna un pasto» (APK 1.0.89)

Tenendo premuta l'icona di AppSphere compare **🍽️ Segna un pasto**, che si trascina sulla home come
icona a sé: apre Calorie col ➕ «Aggiungi alimento» **già aperto**, dopo lo sblocco con l'impronta.
Chiudendo il ➕ si resta sul diario, e l'indietro riporta alla home.

| Pezzo | Dove |
|---|---|
| La voce | `res/xml/shortcuts.xml` (statica), agganciata in `AndroidManifest.xml` |
| L'intent | azione `com.garsal.appsphere.SCORCIATOIA_PASTO` → `MainActivity.gestisciScorciatoia` |
| L'attesa | `core/Scorciatoia.kt`, gemello di `Condivisione` |
| L'apertura | `Navigazione()` porta a `Route.CALORIE`, `CalorieScreen` apre il ➕ e la consuma |

⚠️ **Sta in memoria e non nelle preferenze**, per la stessa ragione della condivisione: deve
sopravvivere alla biometria, ma non al riavvio — o l'icona normale riaprirebbe il ➕ giorni dopo.
E l'azione dell'intent si **riscrive** appena letta, o ogni ricreazione dell'Activity la rieseguirebbe.

⚠️ **L'azione è un contratto fra due file**: `shortcuts.xml` e `Destinazione` in `Scorciatoia.kt`.
Cambiata in uno solo, la scorciatoia apre l'app sulla home senza nessun errore. E `targetPackage`
in `shortcuts.xml` è l'`applicationId` scritto a mano: un xml non legge `build.gradle`.

Aggiungere un'altra scorciatoia = una voce in `shortcuts.xml`, una in `Destinazione` e chi la
esegue. Android ne mostra al massimo quattro o cinque.

**E c'è una seconda icona nel cassetto, «AppSphere pasto»** (APK 1.0.90, nome e icona dalla
1.0.91): il marchio di AppSphere su fondo nero **col badge verde delle posate** in alto a destra,
accanto ad AppSphere. È un `activity-alias` (`.SegnaPasto`) di `MainActivity`, non
un'Activity nuova: stesso processo, stesso login, stessa impronta. `gestisciScorciatoia` la
riconosce dal **nome del componente** (`Destinazione.alias`) invece che dall'azione, perché dal
cassetto l'azione è `MAIN` come per l'icona normale.

⚠️ **Il nome del componente resta `.SegnaPasto`** anche se l'icona si chiama «AppSphere pasto»:
a cambiare è la sola etichetta (`@string/alias_pasto`), e rinominare l'alias sarebbe toccare il
contratto con `Destinazione.PASTO` per niente. La scorciatoia della pressione lunga resta
«Segna pasto»: lì sta già sotto l'icona di AppSphere, e il nome dell'app non serve a distinguerla.

⚠️ **Tre guardie contro il ➕ che si riapre da solo**, tutte in `gestisciScorciatoia`: l'intent si
consuma riscrivendo azione **e** componente; all'avvio si legge solo con `savedInstanceState`
nullo (una ricreazione del sistema ripresenta l'intent di partenza); e si ignora
`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, perché riaprendo dalle Recenti un task nato dall'icona
«AppSphere pasto» Android ripresenta quell'intent lì.

⚠️ **L'icona è il marchio, ed è una copia in più** (vedi *Il marchio vive in sei posti*): i cinque
cerchi di `ic_pasto_foreground.xml` sono identici a quelli di `ic_launcher_foreground.xml`, e il
badge ha la geometria di quello di SOS — alone scuro compreso, che lo stacca dal cerchio rosso.
⚠️ **Le posate nel badge sono i tracciati di `ic_scorciatoia_pasto.xml`**, rimpiccioliti con un
`<group>`: cambiando la forchetta in uno, va cambiata anche nell'altro.

`calorie/` porta in nativo le due schermate che si aprono **col telefono in mano**:
📊 **Dashboard** — le cinque sezioni della pagina nello stesso ordine (⚖️ le pesate, 🔥 le calorie
di oggi, 📐 le calorie per tratto, 📈 come sta andando, e il giorno per giorno dell'intera dieta) —
e 📓 **Diario**, un giorno per volta coi pasti configurati, la barra del target, il riquadro che
spiega da dove esce il numero, *📋 Ricopia da ieri*, i macro e ✏️/🗑 su ogni riga. Più il
**+ galleggiante**, che è la ragione per cui questa app sta sul telefono: si segna l'alimento nel
momento in cui lo si mangia, non la sera al computer.

**Restano sul web 🍎 Alimenti e ⚙️ Impostazioni**, e non è una mancanza: curare il catalogo,
configurare i pasti e scegliere il fattore di attività sono cose che si fanno da seduti e una
volta sola. Il nativo quelle scelte le **legge** — `cm_settings.al_pasti`, `al_profile.activity`,
`cm_profile`, e `al_foods.unit` — e non le può cambiare. ⚠️ L'unità è fra queste: un liquido si
segna in ml anche dal telefono (la casella dice «Quantità (millilitri)» e i tagli sono quelli dei
liquidi), ma **quale alimento sia un liquido si decide di là**. Il `put("unit", …)` sulle righe
del diario però c'è, e non è facoltativo: senza, una riga segnata dal telefono nascerebbe in
grammi per il valore di DEFAULT della colonna, cioè con un'etichetta sbagliata e nessun errore.

⚠️ **I due pulsanti g/ml nella schermata della porzione sono stati provati e tolti** (v1.0.63,
ritirata dalla 1.0.64): quella schermata risponde a una domanda sola — «quanto ne hai mangiato?» —
e una scelta su com'è fatto l'alimento, con la riga che la spiega, si prendeva mezzo schermo per
una cosa che lì non si sta chiedendo; coi caratteri di sistema grandi il campo della quantità
finiva sotto la piega. Il posto di quella scelta è una schermata degli alimenti, che in nativo non
c'è — non il caricamento del pasto. Restano di là anche il **codice a barre** (qui non c'è né
`BarcodeDetector` né una fotocamera accesa per questo) e lo *scrivilo a mano*, che è il form di
🍎 Alimenti: sono le due strade per mettere in dispensa. Come sul web, la **dieta non si decide
qui**: l'obiettivo si crea in «Ti pisasti?» e da questa parte si legge soltanto.

Le regole **non stanno più qui** (APK 1.0.94): `calorie/CalorieRegole.kt` legge `al_conto`, la
stessa RPC del web — vedi *Diario alimentare*. Sono queste: deficit in due addendi (ritmo del tratto + recupero
dello scarto, mai sotto zero), l'ultimo traguardo che vale come peso finale solo se i traguardi
sono almeno due, il target **congelato** in `al_days` alla prima riga del giorno e ricalcolabile
solo su richiesta esplicita, il saldo spalmato su **tutti** i giorni che restano, un giorno senza
righe che non entra nel saldo, e da **oggi** solo lo sforo — perché un diario a metà non è un
digiuno.

⚠️ **Il nativo è ancora in due moduli**, `calorie/` e `peso/`, mentre sul web sono una pagina
sola dal 9 settembre 2026: il target interpolato lo dà `PesoRegole.targetInterpolato`, che è già
l'unico posto di qua — quindi la regola è una per implementazione, ma le implementazioni restano
due e vanno cambiate insieme. Per la stessa ragione l'obiettivo attivo si
decodifica con `Obiettivo.da` del modulo `peso`, che è già l'unico posto in cui `ps_objectives` e
le sue milestone si leggono.

Il **➕ prende lo schermo intero** invece di aprire un dialogo — ci stanno una ricerca, un elenco di
risultati e la scelta della porzione, e coi caratteri di sistema grandi un dialogo sarebbe una
feritoia — ed è in due passi, come nella pagina: si cerca (📗 catalogo e 🌐 rete sotto **due
intestazioni**, mai concatenati) e poi si dice quanto (½/1/2 con l'etichetta dell'alimento, i
pulsanti che **sommano**, l'↺ che azzera). La ricerca in rete passa dalla Edge Function
`al-food-search` con la stessa pausa di digitazione del web: **Open Food Facts limita le ricerche
testuali a una decina al minuto**, e cercare a ogni tasto premuto fa bandire l'indirizzo IP.

Due differenze di forma, volute, entrambe per i caratteri di sistema grandi. I **numeri non sono
una griglia di riquadri** ma una riga ciascuno, etichetta a sinistra e valore a destra: tre celle
affiancate con testi di lunghezza diversa vanno a capo un numero diverso di volte e perdono
l'allineamento. E un **tratto è una scheda, non una riga di tabella** — sette colonne o si tagliano
o vanno a capo ognuna per conto suo — con l'ordine di lettura della pagina conservato: il
**target** subito dopo i pesi, perché è la risposta per cui quella tabella esiste. Il grafico delle
calorie è disegnato a mano su un `Canvas` scorrevole, con le misure in **dp e mai in pixel
grezzi**; il grafico del peso resta di là, perché il peso è materia di «Ti pisasti?» e qui compare
già nei riquadri e nel giorno per giorno.

### ⚠️ Obiettivi nativo: il solo 📆 Piano quotidiano

`obiettivi/` porta in nativo **una pagina sola di `obiettivi.html`**, ed è quella che la pagina
apre per prima: il 📆 **Piano quotidiano** (`PianoScreen.kt`). Tutte le azioni ancora da fare,
giorno per giorno — le arretrate in un riquadro solo in cima con quanti giorni ha la più vecchia,
poi oggi, poi i giorni che vengono, e in fondo quelle a libera ripetizione — e da lì si chiudono.
È la copia di `renderPianoPage()` riquadro per riquadro, comprese le due etichette *Domani* e
*Dopodomani* che valgono **solo per due giorni** (oltre, «fra 9 giorni» costringe a fare il conto
e la data no).

La **bolla in home apre il Piano** e non l'elenco degli obiettivi: le azioni si chiudono nel
momento in cui si sono fatte, non la sera al computer, ed è la ragione per cui questa app sta sul
telefono. L'elenco che c'era già (`ObiettiviScreen`, metriche e rilevazioni) resta un passo più
in là, dal 🎯 nella barra: `Route.OBIETTIVI` porta al Piano, `Route.OBIETTIVI_ELENCO` a lui.
Fino alla v1.0.67 Obiettivi era **sospesa in home** proprio perché il nativo le azioni non le
leggeva affatto.

⚠️ **Restano sul web** ✅ Azioni, 📊 Andamento, il Dettaglio di un obiettivo, le Esecuzioni e le
Impostazioni: da qui un'azione non si crea, non si modifica e non si cancella. Il piano dice cosa
resta da fare — le **concluse non ci sono**, di qua come di là: una riga che non si può più
toccare è memoria, e si guarda in 📊 Andamento.

Le regole che valgono qui sono le stesse del web e **vanno cambiate insieme**:

- **il ciclo di vita passa solo dalle RPC** `ob_action_complete` / `ob_action_skip`, e poi si
  rilegge — nessun calcolo di prossima occorrenza in Kotlin. `p_days` si chiede **solo** per le
  `single`, perché per gli altri tipi la RPC lo ignora e chiedere un numero che il server butta
  via sarebbe una bugia;
- ⚠️ **un'azione non si fallisce**: o la si fa, o la si sposta. Nessun *Fallisci*, come di là
  dalla v1.8.0. *Salta* compare solo su `PUO_SALTARE`, cioè su ciò che ha una prossima volta a
  cui rimandare;
- **completando con successo si aprono le rilevazioni** (`DialogoRilevazioni`), una riga per
  metrica collegata: **dopo** che la RPC ha chiuso l'azione — il ciclo di vita non deve dipendere
  dal fatto che uno si ricordi il numero — solo su `completed`/`completed_late`, e con la **data
  di occorrenza letta prima della RPC**, che subito dopo la riga è già sulla volta successiva.
  Una misura lasciata su *non adesso* **non si registra e non vale zero**, e ogni metrica passa
  da `ob_record_measurement`, che è l'unico punto di scrittura e l'unico a dire se un voto sta
  dentro la scala;
- ⚠️ **il giorno di un istante si legge in ora locale** (`giornoLocale()`, la copia di
  `localDay()`): `obiettivi.html` scrive `next_occurrence_date` con `toISOString()`, cioè in UTC,
  quindi un'azione delle 00:30 sta in archivio come le 22:30 **del giorno prima**. Tagliare i
  primi dieci caratteri — come fa `giornoDa()` in Tasks, dove i timestamp sono scritti già locali
  — la metterebbe fra le arretrate. È l'unico punto in cui i due moduli leggono le date in modo
  diverso, e non è una svista.

🕘 **Esecuzioni recenti** (APK 1.0.117, l'icona nella barra del Piano): le ultime 40 esecuzioni di
tutte le azioni, concluse comprese, con ✏️ (giorno e ora, esito fra completata e in ritardo, punti,
rilevazioni di quel giorno) e ↩️ sull'ultima di ogni azione, che chiama `ob_action_undo`. Gemelle
di `apriModificaEsecuzione()` / `salvaModificaEsecuzione()` / `annullaEsecuzione()` di
`obiettivi.html` (`PianoRepository.correggi` / `annulla`, `DialogoCorrezione`): vanno cambiate
insieme. Il 🗑 della singola esecuzione resta solo sul web.

⚠️ **Un `workflow` non ha il *Completa* e da qui non si chiude**: il pulsante *Step* mostra a che
punto è (`n/N step`) e dice di andare sul web. Non è una dimenticanza — chiudere uno step vuol
dire riscrivere `workflow_steps`, sbloccare chi dipendeva da lui, scrivere la riga di storico coi
suoi punti e poi richiamare `ob_action_complete`: è la regola di `chiudiStep()`, che oggi vive in
un posto solo. Copiarla in Kotlin sarebbe una seconda regola su punti e archivi. Il giorno che
scendesse in una RPC come le altre, il pulsante si accende qui senza altro lavoro.

⚠️ **`ob_actions` si legge come `JsonObject`**, non come `data class` serializzata: `categories`
è un array di uuid e `workflow_steps` un jsonb, e una sola colonna di forma inattesa non darebbe
un'azione storta ma la **schermata vuota**. È la stessa scelta di `ts_tasks`. Categorie e
priorità invece **si leggono da `TasksRepository`**: `cm_categories` e `cm_priorities` sono
condivise coi task, e un secondo decoder per le stesse due tabelle sarebbe una seconda verità.

### ⚠️ Finanza nativa: solo visualizzazione, e i numeri sono gli SNAPSHOT

`finanza/` (APK 1.0.121) porta tre viste di `finanza.html` — 📊 **Dashboard**, 📈 **Sviluppo**,
💼 **Portafogli** (elenco e dettaglio con posizioni e andamento) — e **non scrive niente**. La
bolla è riservata in `cm_apps`, quindi compare solo in modalità nascosta.

⚠️ **Non calcola niente in Kotlin**: all'apertura chiama `save-snapshot` col JWT — la
stessa chiamata di `autoSaveSnapshot()` nel web, che rifà lo snapshot di **oggi** coi prezzi in
cache — poi legge `fnz_dashboard_snapshots` e disegna quello. Così `portfolioStats` /
`computeLoanValue` / `computePricesFromHistory` restano in due copie e non tre. Se la funzione non
risponde si mostra l'ultimo snapshot in archivio, e la pagina dice di che giorno è.

Il **⟳ in alto aggiorna anche i prezzi** (APK 1.0.122): prima `get-prices` — la stessa chiamata
di «⟳ Aggiorna prezzi» nel web — poi `save-snapshot`, poi rilegge. ⚠️ Le due funzioni si chiamano
con `HttpURLConnection` e non con `functions.invoke`: `get-prices` può durare fino a ~130 s e il
client di supabase-kt ha un timeout più corto (stessa scelta di `pv-ai`). Mentre lavora la pagina
scrive la fase sotto la barra; se i prezzi non si aggiornano lo dice e lo snapshot si rifà coi
prezzi di prima.

⚠️ **Valori lordi, di proposito**: il netto delle tasse non c'è (lo snapshot non lo conosce, ed è
stato chiesto così). La variazione «dal giorno prima» è contro lo snapshot precedente. Il dettaglio
dei portafogli (`details`) per l'andamento si legge **solo aprendo un portafoglio e solo sul
periodo scelto**, come `caricaPtfSnapshots()`. ⚠️ Se cambia la forma di `details` in
`save-snapshot`, va cambiato `Snapshot.da` / `PortafoglioSnap.da` in `FinanzaData.kt`.

### ⚠️ Forziere nativo: cinque cose, e i formati provati prima di scriverli

`frz/` porta sul telefono **sbloccare, sfogliare, aprire un documento, metterne dentro uno,
buttarne via**.
La bolla ha `riservato = true` in `cm_apps`, quindi **si vede solo in modalità nascosta**, come
Finanza — un forziere annunciato in home a chiunque guardi lo schermo da sopra la spalla è metà
del lavoro buttato.

⚠️ **Restano su `forziere.html`, e non è una mancanza da colmare col tempo**: la **creazione** del
forziere, il **collaudo** delle 24 parole, l'**export `.7z`** e il **cambio della passphrase**.
Sono le quattro operazioni che si fanno una volta e vanno fatte bene, con le parole davanti e
senza fretta; un forziere creato a metà da un telefono in autobus è il modo peggiore di
cominciare. Il nativo, se il forziere non c'è, **lo dice** invece di offrirsi di farlo.

#### ⚠️ Qui la cosa duplicata sono i FORMATI, non le regole

Nelle altre otto app gemelle a divergere sono le regole di calcolo. Qui no: le regole stanno tutte
nel formato dei file, e un errore **non si recupera** — un `.gpg` scritto male non lo riapre più
nessuno, nemmeno con le parole giuste.

| Cosa | Dove | Deve valere identico |
|---|---|---|
| Documenti e indici | `ForzierePgp.kt` / `cifraFile`, `cifraTesto` | OpenPGP simmetrico, **AES-256**, **MDC (SEIPD v1)**, S2K iterato SHA-256 byte **224** |
| Metadati e miniature | `ForziereCripto.kt` / `cifraMeta`, `cifraBytes` | AES-256-GCM, tag 128 bit, sul filo `base64(iv[12] ‖ cifrato‖tag)` |
| Le 24 parole | `ForziereCripto.normParole` / `normParole()` | NFC, spazi collassati a uno, trim, minuscolo |

⚠️ **`aeadProtect = false` di là è `setWithIntegrityPacket(true)` di qua**, ed è la stessa scelta:
SEIPD **v1** col vecchio MDC, che `gpg` legge da vent'anni, e non l'AEAD di RFC 9580, che vuole
GnuPG **2.5**. Se una delle due implementazioni passasse all'AEAD, i suoi file si aprirebbero solo
da lei.

⚠️ **Il byte S2K è 224 da tutt'e due le parti** — 16 MiB di SHA-256 per tentativo. Non è
un'impostazione estetica: è quanto costa a chi prova a indovinare le parole. Un numero più basso
di qua renderebbe più economico attaccare i file scritti dal telefono, **senza che niente lo
dica**.

⚠️ **Provato, non dedotto.** Prima di scrivere una riga di Kotlin i due sensi sono stati verificati
su una JVM — OpenPGP.js scrive e BouncyCastle rilegge, poi il contrario, byte identici e nome
originale ritrovato — e per soprammercato **GnuPG 2.4.4** apre tutt'e due con
`--use-embedded-filename`. Lo stesso per AES-GCM: WebCrypto scrive, JCE rilegge, e viceversa. In
un forziere non è il posto dove fidarsi della documentazione: chi tocca questi formati **rifaccia
la prova**.

⚠️ **BouncyCastle, variante `jdk15to18`**, e le sue tre parti servono tutte: `bcpg` si porta
dietro `bcprov` e `bcutil`, e senza `bcutil` la prima decifratura muore con un
`NoClassDefFoundError` su `CryptlibObjectIdentifiers`. Si usa l'**API leggera** (le classi `Bc*`),
che non registra nessun provider JCE — è il modo che funziona su Android. ⚠️ **Pesa**, e con
`minifyEnabled false` finisce intera nel DEX: è la stessa faccenda dell'avviso su
`material-icons-extended`, con la differenza che qui non c'è un'alternativa leggera — l'unica
sarebbe riscrivere OpenPGP a mano, che in un forziere è precisamente ciò che non si fa.

#### Le altre cose che sono la funzionalità

- **Il forziere si chiude da sé**: dieci minuti come nella pagina (`MINUTI_BLOCCO`), **e appena
  l'app passa in secondo piano** (`ON_STOP`) — è l'equivalente del `visibilitychange` di là. Uno
  schermo lasciato acceso su un elenco di documenti è la cosa da cui il forziere protegge.
  ⚠️ **Il selettore dei file è un'altra Activity**, quindi manda questa in `ON_STOP`: senza una
  scusa esplicita (`apriUnaFinestraDiSistema`, che vale **una volta sola**) il forziere si
  chiuderebbe proprio nel gesto con cui si sta per metterci dentro qualcosa. La biometria invece
  non c'entra — `BiometricPrompt` è una finestra di questa stessa Activity.
- ⚠️ **Un'operazione lunga non si interrompe a metà** (`occupato`, come `S.occupato`): chiudendo
  mentre un file sta salendo, le parole sparirebbero a caricamento avviato e il file resterebbe su
  Drive **senza la sua riga** — un pacchetto cifrato che nessuno sa più cos'è e che dall'app non si
  può più togliere.
- ⚠️ **Aprire non è scaricare**, ed è il punto anche qui. Immagini e testo non toccano il disco:
  stanno in memoria come il blob della pagina. **PDF, video e audio** hanno bisogno di un
  descrittore di file — né `PdfRenderer` né `MediaPlayer` prendono i byte dalla memoria — quindi
  si scrive un temporaneo nella **cache privata** dell'app e lo si mostra **dentro l'app**, mai
  passandolo a un altro programma con un `Intent`, che ne farebbe una copia in chiaro in un'app
  che non è questa. Il temporaneo se ne va chiudendo il visore, e la cache si ripulisce all'avvio
  e a ogni chiusura del forziere.
  ⚠️ **Video e audio col `VideoView` del sistema, non con una libreria in più**: il file è locale
  e già decifrato, quindi non c'è niente da mettere in streaming, e `media3`/ExoPlayer costerebbe
  qualche MiB nel DEX di un APK già a 43 MiB contro i 50 oltre cui dà noia. Il `MediaController`
  porta play, pausa e barra di scorrimento senza scriverne una riga.
  ⚠️ **Guardare un film È usare il forziere**: il blocco a dieci minuti conta i tocchi, e in
  mezz'ora di video non ce n'è nessuno — senza un battito che rimanda **mentre riproduce** (e solo
  allora: in pausa il conto riparte) il forziere si chiuderebbe da sé a metà. `ON_STOP` chiude
  comunque: uscire dall'app resta uscire dall'app.
  ⚠️ **Il cifrato scende su un FILE e non in memoria**: un video di mezzo giga letto in un
  `ByteArray` e poi decifrato in un secondo `ByteArray` sono un giga di heap, cioè l'app che muore
  prima di mostrare qualcosa. In flusso la dimensione non conta, e il `.gpg` temporaneo si cancella
  sempre.
  ⚠️ **Quel che resta fuori lo dice** (archivi, fogli, documenti Office) e **non si scarica
  nemmeno**: sarebbero minuti di rete per finire su un riquadro che dice «qui non si apre».
  **Lo scaricamento non c'è affatto**, e non è una dimenticanza: è l'unico gesto che porta un
  documento *fuori* dal forziere, e sul telefono finirebbe in una cartella condivisa con tutte le
  app.
- ⚠️ **Gli indici su Drive li riscrive anche il telefono** (`contenuto.gpg` dello scomparto in cui
  il file è entrato). Senza, un caricamento dal telefono lascerebbe l'indice indietro **in
  silenzio**, che è il difetto che gli indici esistono per non avere. E come di là **non bloccano e
  non fanno fallire il caricamento**: il file è già su Drive e la riga già scritta, quindi si
  segnala e basta — `🔄 Rifai gli indici` sta sul PC.
- ⚠️ **La miniatura si fa PRIMA di cifrare**, dall'originale: dopo non c'è più niente da guardare.
  320 px di lato e JPEG 0,72, gli stessi numeri di `miniatura()`. Una miniatura che non entra non
  fa fallire un caricamento riuscito.
- ⚠️ **Da «Tutti» il file nasce fuori da ogni scomparto**, non nel primo della lista: una scelta
  presa dall'app al posto di chi carica si scopre cercando il file altrove. Stessa regola di là.
- ⚠️ **Buttare via COSTA LA PASSPHRASE** (v1.0.79), come il 🧹 Svuota e la 💣 zona rossa della
  pagina e per la stessa ragione: qui il forziere è **già aperto** — passphrase o impronta le ha
  superate chi è arrivato a quella schermata — e senza quella domanda una scrivania lasciata un
  momento basta a far sparire trenta documenti con due tocchi. Si verifica come di là
  (`verifica`, gemella di `passphraseGiusta`): **aprendo davvero `scorciatoia.gpg`** e
  confrontando quel che ne esce con le parole in uso, non con una variabile in memoria — un
  confronto con una variabile è un controllo che passa sempre. Le **24 parole restano una via**,
  per chi è entrato con loro e la passphrase non la ricorda più.
  ⚠️ **«Non ho potuto controllare» non è «è sbagliata»**: col Drive irraggiungibile la finestra lo
  dice, invece di mandare a riscrivere all'infinito la passphrase giusta.
  ⚠️ **`scorc` si tiene in memoria** (`S.scorc` di là): non è un segreto — su Drive sta
  esattamente così — e senza, la conferma dipenderebbe dalla rete.
- ⚠️ **Il 🗑 del singolo sta NEL VISORE, non sulla scheda in griglia**: si butta via dopo aver
  visto cos'è, quindi non si sbaglia documento — e la scheda, coi caratteri di sistema grandi, è
  già piena fra miniatura, nome su due righe e peso. È l'unico punto in cui il nativo si scosta
  dalla pagina, dove il 🗑 sta sulla scheda. Nel visore è un'emoji sola e non «Butta via»: la riga
  è quella del titolo, che ha `weight(1f)`, e due etichette lunghe lo spingerebbero fuori.
- ⚠️ **Più documenti si scelgono con la PRESSIONE LUNGA**, e solo da lì in poi il tocco spunta
  invece di aprire: il gesto che si fa cento volte è aprire, e una casella disegnata su ogni
  scheda sarebbe rumore permanente per un gesto che si fa di rado (stessa scelta della griglia di
  Events Log). Tolta l'ultima spunta si esce da sé, e l'**indietro di Android annulla la
  selezione** invece di uscire dal forziere. ⚠️ La selezione **si azzera cambiando scomparto o
  ricerca**: una selezione che sopravvive al filtro è un 🗑 che butta via documenti che in quel
  momento non si vedono — cioè esattamente quel che una conferma non riesce a raccontare.
  ⚠️ La **conferma nomina i documenti** (i primi cinque e quanti altri), dice il peso e **dice
  cosa NON succede** — lo scomparto resta, gli altri non si toccano, le 24 parole aprono ancora
  tutto: davanti a un «butto via» senza altro scritto si preme Annulla anche quando si voleva
  premere l'altro.
- ⚠️ **Si cancella UNO PER VOLTA, prima Drive e poi la riga**, come `elimina()` nella pagina: nel
  verso opposto una rete che cade lascia su Drive un `.gpg` che nessuno sa più cos'è e che
  dall'app non si può più togliere. Uno per volta perché quel che è andato è andato — l'elenco a
  schermo è già quel che resta, e ripremendo si riprende invece di ricominciare; la riga dice
  «ne ho buttati 3 su 7» invece di tacere. La **miniatura se ne va da sé** (`frz_thumbs.file_id`
  è `ON DELETE CASCADE`), e gli indici si riscrivono per i **soli scomparti toccati** — anche a
  cancellazione interrotta a metà, perché `contenuto.gpg` deve dire quel che c'è adesso.
- ⚠️ **Lo scaricamento continua a non esserci**, ed è un'altra cosa: buttare via toglie un
  documento *dal* forziere e non lo porta *fuori*. Resta sul PC.
- ⚠️ **La ricerca è lato client**, e non può essere altrimenti: il server i nomi non li può
  leggere. È il prezzo dell'E2EE.
- ⚠️ **`ForziereBiometria` è il gemello di `ForziereKeystore`** dell'APK WebView, riga per riga —
  due progetti Gradle separati non condividono sorgenti. **Cambiando uno, cambia anche l'altro.**
  Il blob avvolto è però di questa installazione: ogni APK ha la sua chiave nel TEE, quindi le due
  registrazioni sono indipendenti, e va bene così. Qui l'impronta si **registra da dentro**, col
  forziere già aperto, che è l'unico momento in cui le parole ci sono già e non si fa riscrivere
  niente. E lo sblocco passa comunque da `indice.gpg`: il Keystore dice **chi sei**, non che quelle
  parole aprano *questo* forziere.
- ⚠️ **Il token si guarda in faccia prima di mandarlo** (v1.0.78, `ForziereDrive.token()`, gemello
  di `tokenVivo()` in `forziere.html`). `ForziereDrive` è **l'unico posto dell'app** che legge
  `accessToken` a mano e parla per conto suo (`HttpURLConnection`): tutto il resto passa da
  supabase-kt, che il rinnovo se lo fa da sé. Fino alla v1.0.77 qui si guardava se un token
  *c'era*, non se valeva ancora qualcosa — e un access token di Supabase dura **un'ora**, mentre
  la sessione in archivio non scade mai. Con l'app rimasta aperta si finiva a mandarne uno morto:
  il 10 settembre 2026 il Forziere ha risposto ❌ *«Drive: … serve un login valido»* e ha mandato
  a cercare il guasto dal Drive, che non c'entrava niente. Tre cose ne discendono:
  - `Jwt.vivo()` legge `exp` **dal token che si sta per mandare**, col margine di 30 secondi:
    partire con uno che scade a metà operazione vuol dire le 24 parole già scritte e un file già a
    metà. È lo stesso mezzo minuto del web, e si legge lo stesso dato — non un campo accanto
    (`UserSession.expiresAt`) che il giorno che i due divergono direbbe un'altra cosa;
  - ⚠️ **dopo un rinnovo il token si usa e non si ricontrolla**: `Jwt.vivo` guarda l'orologio del
    telefono, e con l'ora sbagliata un secondo controllo direbbe «scaduto» anche su un token
    appena nato — cioè il forziere chiuso da un orologio storto. Al più si rinnova una volta di
    troppo; a dire se quel token vale resta il server;
  - ⚠️ **il 401 si riprova UNA volta, dopo un rinnovo** (`post()`). Arriva da due posti che valgono
    la stessa cosa — `chiChiama()` dentro la funzione, e il **cancello** di Supabase che rifiuta il
    JWT prima ancora che la funzione parta (`UNAUTHORIZED_ASYMMETRIC_JWT`) — e il secondo capita
    anche con un `exp` che a noi sembra buonissimo, per esempio dopo un cambio delle chiavi di
    firma del progetto: `token()` da solo non se ne accorgerebbe mai. In ciclo no: se il secondo
    giro torna 401 il problema non è il token, e riprovare terrebbe il forziere su una rotella
    invece di dire cos'è successo. Il 403 resta quel che è — *non autorizzato*, cioè l'account
    sbagliato — e non si traveste da sessione scaduta.
  - ⚠️ **Il prefisso «Drive: » è nostro**, non di Google: appiccicato a un errore che il Drive non
    ha mai visto è precisamente ciò che il 10 settembre ha fatto perdere tempo. Dove la risposta
    porta il suo `error`, si scrive quello e basta (`motivo()`), e la sessione scaduta lo dice con
    parole sue senza nominare il Drive.

### Cosa compare in home: il registro `PortedApps`

Il web mostra tutte le righe attive di `cm_apps`; qui si mostrano **solo le app che esistono in
nativo**, decise da `home/PortedApps.kt`. Titolo, descrizione, colore e punteggio continuano ad
arrivare dal database (`cm_apps` + RPC `run_score_query`): il registro decide soltanto *se* la
bolla si disegna e *dove* porta il tap. **Portare una quarta app = una riga lì più le sue
schermate.** I valori di ripiego servono perché non tutte le righe di `cm_apps` nascono da una
migration — `events-log.html` non compare in nessun file SQL — e senza quelli la bolla sparirebbe
in silenzio.

Le bolle sono disegnate come sul web: **cerchio col bordo nero** (`border: 4px solid #111`) e
dentro il nome col **punteggio** sotto, che a zero non si scrive — una bolla al minimo con uno «0»
sotto sembra rotta, non vuota.

#### ⚠️ Il colore della bolla sta in `cm_apps.color`, e il ripiego non va allineato: va tolto di mezzo

Il colore lo dà la riga, uguale per tutt'e due le home. Quando quella casella è **vuota** entra
però un ripiego, e i due ripieghi non sono lo stesso: il web fa `OLYMPIC[i % 5]` (`index.html`),
il nativo `Palette.olimpici.first()` (`HomeScreen.kt`).

⚠️ **Allinearli non basterebbe**, ed è la ragione per cui la cura è riempire la colonna e non
toccare il codice: il ripiego del web è **posizionale**, e la posizione non è la stessa nelle due
home — il nativo mostra le sole app di `PortedApps`, il web tutte quelle attive. Lo stesso
servizio ha quindi un `i` diverso, cioè due colori diversi, senza che niente lo dica. È l'issue
#19, chiusa da `20260914120000_cm_apps_colori_bolle.sql`.

⚠️ **E sul web quella posizione non è nemmeno ferma**: `i` è l'indice nella risposta di `cm_apps`
(`order=id.asc`) **filtrata dalla modalità nascosta**, quindi una bolla senza colore ne cambia uno
accendendo la modalità nascosta, e lo ricambia il giorno che un'app viene attivata o spenta.
`loadBadgeRows()` ha per giunta un **terzo** ripiego ancora (`'#EE334E'`): tre modi di rispondere
alla stessa domanda.

⚠️ **I colori devono restare DISTINTI**, ed è un requisito e non un'estetica: il codice della
modalità nascosta *è* una sequenza di colori, e due bolle dello stesso colore lo rendono ambiguo
(è la ragione già scritta in `20260826120000_calorie_app_bolla.sql`). La migration scorre la sua
tavolozza **saltando ogni colore già in uso**, e se li esaurisce lascia la riga com'è dicendolo,
invece di ripeterne uno.

⚠️ **Un colore che c'è non si tocca, nemmeno per «normalizzarlo»**: `checkSequence()` confronta
`cm_settings.hidden_mode_sequence` **stringa per stringa**, quindi portare `#EE334E` a `#ee334e`
non muove una bolla di un pixel e spacca il codice. Maiuscole e spazi non fanno differenza per
nessuno dei due client — `coloreDaHex()` fa `trim()` e legge l'esadecimale come viene. Quel che
invece **va** riscritto è un colore che il nativo non sa leggere affatto: `coloreDaHex()` vuole
**sei** cifre, quindi `#abc` e `abc123` senza cancelletto passano sul web e di qua cadono nel
ripiego — stesso dato, due colori, cioè l'issue scritta più in piccolo.

⚠️ **Una bolla che era senza colore ora ce l'ha, e se faceva parte del codice della modalità
nascosta quel codice non apre più.** Non è riconoscibile da dentro il database — in archivio la
riga aveva NULL, e nella sequenza sta scritto il colore *disegnato* dal web, cioè proprio il
numero che la migration esiste per togliere di mezzo. Il rimedio è riscrivere
`hidden_mode_sequence` coi colori nuovi, che le `NOTICE` della migration elencano uno per uno.

⚠️ **Il nome dentro la bolla non si taglia mai: il carattere si misura, non si stima**
(`misuraTesto` in `HomeScreen.kt`, la ricerca binaria di `fitFontSize()` in `index.html`). I due
vincoli sono gli stessi di là — la **parola più lunga** dentro `diametro × 0,64` senza essere
spezzata, e l'**altezza del nome mandato a capo** dentro quel che resta tolto il posto del
punteggio, misurato pure lui. Fino alla v1.0.66 era una stima (larghezza utile ÷ lunghezza della
parola più lunga) con un `maxLines = 3` a fare da rete: ma la stima è in `sp`, che **il sistema
moltiplica ancora per `fontScale`**, quindi coi caratteri di sistema grandi il nome andava a capo
una volta in più del previsto e la riga in eccesso spariva — ritagliata dal `maxLines` o dal
`clip(CircleShape)` della bolla, e **senza nemmeno i puntini** a dire che mancava qualcosa.

⚠️ **Il pavimento del carattere è in `dp`, non in `sp`**, ed è l'unica misura di testo dell'app che
lo sia: la bolla ha una dimensione **fisica** — il pavimento di 6 cm² non cresce coi caratteri di
sistema — quindi un minimo in `sp` crescerebbe con loro, e a ingrandimento doppio un nome lungo non
ci starebbe **nemmeno al minimo**, cioè il taglio tornerebbe proprio nel caso per cui quel conto
esiste.

### ⚠️ Non tutti i numeri di `score_query` sono punti

`cm_apps.score_query` restituisce un numero, ma per alcune app quel numero **conta delle cose**:
Spuntiamola dà i giorni che mancano al traguardo, Obiettivi gli obiettivi attivi, le app del conto
familiare le transazioni in archivio. Quei numeri **non si scrivono sotto il nome
della bolla e non entrano nel totale che paga i premi** — un conteggio sommato ai punti è un saldo
che nessuno può rifare a mano, e un traguardo spuntato che *abbassa* i punti spendibili è un premio
che va e viene da sé.

**La risposta sta sulla riga, in `cm_apps.conta_punti`** (`NOT NULL DEFAULT true`,
`20260924130000_cm_apps_conta_punti.sql`), accanto alla `score_query` di cui parla, e la leggono
tutt'e tre: `contaComePunti` in `index.html`, `Bolla.contaPunti` in `HomeRepository.kt` e
`backup-report.mjs`. Fino al 24 settembre 2026 erano **tre elenchi scritti a mano**
(`APP_SENZA_PUNTI`, `AppSenzaPunti`, `SENZA_PUNTI`) da tenere uguali, e se divergevano le due home e
la relazione mostravano tre totali diversi. Un'app nuova **fa punti**; dire che il suo numero è un
conteggio è la scelta da fare esplicitamente, con una `UPDATE` di quella colonna. Comprende anche
app che in nativo non hanno una bolla: i loro punti entrano comunque nel totale.

⚠️ **Conta solo un `true` esplicito**, in tutt'e tre: le bolle in cache salvate prima della
colonna non ce l'hanno, e una riga letta prima che la migration passi nemmeno. In quel caso il
numero sotto la bolla non si scrive e il totale esce più **basso** — l'errore innocuo dei due:
leggendo l'assenza come «punti» si sommerebbero per un istante giorni e transazioni al saldo dei
premi. ⚠️ Nel web, se la colonna manca, `loadApps()` riprova **senza di lei ma col filtro
`riservato`**: cadere nel ripiego più vecchio toglierebbe quel filtro, e le bolle riservate
comparirebbero a modalità nascosta spenta.

⚠️ **Un punteggio può essere negativo**, ed è un'informazione, non un errore: una risposta di SOS
toglie punti, una decisione sbagliata pure. La bolla lo scrive **col segno** — la condizione è
«diverso da zero», non «maggiore di zero», in tutt'e due le home — perché una bolla senza numero
si legge come «zero», che è il modo peggiore di dire −40. **Solo lo zero resta muto**: una bolla al
minimo con uno «0» sotto sembra rotta, non vuota. Anche il **saldo spendibile** può essere
negativo (web v1.9.5, APK 1.0.103: `updateScorePanel()`, `HomeState.totaleNetto` e
`PremiState.puntiDisponibili`, tutti `lordo − spesi` senza clamp): con più premi riscattati che
punti il riquadro rosso mostra il debito invece di uno 0 che sembra «pari». I premi restano non
riscattabili, perché un saldo negativo è sempre sotto il costo.
`20260824190000_score_query_ammette_negativi.sql` ha tolto i `GREATEST(0, …)` che schiacciavano a
zero il totale di SOS e Decisioni — con quelli, da −40 a 0 la bolla mostrava lo stesso numero e il
malus non esisteva. Le altre `score_query` non stanno in nessuna migration: se una ha lo stesso
clamp, si toglie dal pannello ⚙️ delle badge query in `index.html`.

⚠️ **Il pavimento di 6 cm² è in «cm» del web, non col righello** (`sizeOf()` in `index.html`,
`BubbleLayout.diametro` nel nativo): `sizeOf` lavora in CSS px e li converte a 96 dpi, ma su
Android un CSS px **è** un dp — 1/160 di pollice — quindi quei 6 cm² valgono ~2,1 cm² veri, cioè
un pavimento di **104,5 dp** di diametro. Il nativo usava la densità vera (`160f`) e otteneva un
pavimento di 6 cm² fisici, **174 dp**: su uno schermo da 393 dp ci finivano sopra quasi tutte le
bolle, e la home diventava una fila di cerchi uguali — fra 10 punti e 5952 correva il 26 % invece
del 110 %. La proporzionalità *è* il senso delle bolle, quindi il fattore è quello del web,
`96f`: qui si convertono dp, non centimetri.

⚠️ **Il massimo su cui si normalizza è quello delle bolle mostrate**, non di tutte le app: il web
le mostra tutte, il nativo solo le portate, quindi ciascuna home scala sulla sua bolla più
grande. È voluto — con il massimo globale, in nativo la bolla più grande non arriverebbe mai a
220 dp — ma è la ragione per cui le stesse app possono avere due diametri diversi nei due APK.

⚠️ Il numero continua invece a **dimensionare** la bolla, anche quando non si vede: quella di
Spuntiamola che si sgonfia man mano che i giorni finiscono è la cosa che rende utile guardarla, e
con l'area a zero resterebbe al minimo per sempre. Un punteggio negativo dà la bolla **al minimo**,
in tutt'e due le implementazioni: l'area si ferma al pavimento di 6 cm² che tiene la bolla
toccabile. ⚠️ Le due cache (`localStorage` sul web, le preferenze nel nativo) portano le bolle
dell'avvio precedente, e quelle salvate prima di `conta_punti` il campo non ce l'hanno: per quel
primo avvio il numero sotto la bolla non si scrive, mentre il totale si rifà coi dati freschi.

### ⚠️ Il totale in home e i premi: la stessa cifra da tutt'e due le parti

In basso a sinistra c'è il **riquadro rosso del totale** (`PannelloTotale`, il `#score-panel` del
web), e toccarlo è l'unico modo per arrivare al **catalogo premi** — di qua come di là. Il numero
è **guadagnati meno spesi**, anche negativo: `HomeState.totaleNetto` qui, `updateScorePanel()` sul
web. Se le due formule divergono, un premio comprabile da una parte non lo è dall'altra.

⚠️ **Il lordo è la somma dei punteggi di *tutte* le app attive, non delle sole app portate.**
`HomeRepository.carica()` fa girare la `score_query` di ogni riga di `cm_apps`, comprese quelle che
qui non hanno una bolla: quei punti sono guadagnati lo stesso, e un premio costa uguale da tutt'e
due gli APK. Sommare le sole bolle darebbe un saldo diverso a seconda di che app si è aperta.
Restano fuori dal totale le app il cui numero **non è un punteggio** (sezione qui sopra) e quelle
**riservate**, che hanno un riquadro loro (sezione qui sotto).

Le bolle **scansano il riquadro** (`BubbleLayout.Pannello`, portato da `pushFromPanel`), e
l'ingombro si **misura** (`onSizeChanged`) invece di essere un rettangolo scritto nel codice: coi
caratteri di sistema grandi quel riquadro è alto il doppio, e una misura fissa sarebbe sbagliata
proprio dove serve.

#### 🙈 Il secondo riquadro: quanto c'è dietro la modalità nascosta

Accanto al totale, **solo a modalità nascosta accesa**, c'è un riquadro **rosso scuro**
(`#7F1D1D`) etichettato *NASCOSTE*: la somma dei numeri delle app riservate — oggi Finanza, Casa
Rosa, Contabilità, Casa Terrasini e il Forziere. `PannelloNascoste` in `HomeScreen.kt`,
`#hidden-panel` in `index.html`, e i due conti sono `HomeRepository.carica` e `puntiNascosti()`.
È la issue #20.

⚠️ **La divisione è per VISIBILITÀ e non per «sono punti o no»**, e sono due domande diverse che
prima si sovrapponevano per caso: `contaComePunti` dice se quel numero è un punteggio,
`riservato` se quell'app si vede sempre. Oggi **tutte** le riservate hanno anche
`conta_punti = false`, quindi il totale non cambia di un'unità; ma un'app riservata che portasse punti
veri li metterebbe nel riquadro scuro e non nel totale — ed è quello che questo riquadro esiste
per dire.

⚠️ **Ne discende che il saldo che paga i premi non dipende più da come si sta guardando la home.**
Prima il lordo sommava anche le riservate, quindi accendendo il codice a colori il totale poteva
salire: un premio comprabile a modalità accesa e non a modalità spenta. Adesso il taglio è lo
stesso di qua e di là (`puntiLordi` / `HomeRepository.carica`) — **se cambia in una, cambialo
nell'altra**, o un premio comprabile da una parte non lo è dall'altra.

⚠️ **È un numero solo anche se somma grandezze diverse** — le transazioni di Finanza e i file del
Forziere stanno nella stessa cifra. È una scelta presa sapendo cosa costa: dice «quanto c'è dietro
la modalità nascosta» e non quanto vale, ed è la ragione per cui sta in un riquadro **suo** invece
che dentro al totale, dove sarebbe un saldo che nessuno può rifare a mano.

⚠️ **Non si tocca**, mentre il totale accanto sì: quei punti non si spendono, quindi non porta al
catalogo premi — niente `cursor:pointer` e niente `:hover` nel web, niente `clickable` in nativo.
Un tocco che non fa niente si leggerebbe come un riquadro rotto, e uno che aprisse i premi direbbe
che quei punti si spendono.

⚠️ **Senza nemmeno un'app riservata il riquadro NON si disegna**, anche a modalità accesa: un box
che dice 0 annuncerebbe l'esistenza di quel che la modalità nascosta serve a non annunciare. E in
nativo il numero **non si tiene in cache**, come le bolle riservate e per la stessa ragione — al
prossimo avvio la modalità è spenta, e quel numero rimasto nelle preferenze è un pezzo di quel che
non si deve vedere.

⚠️ **I due riquadri vanno a capo invece di uscire dallo schermo**: `FlowRow` con un tetto di
larghezza in nativo (`LARGHEZZA_PANNELLI`, 0,74 dello schermo), `flex-wrap` nel web. Coi caratteri
di sistema grandi due riquadri affiancati non ci stanno, ed è la condizione normale — il tetto
serve a lasciare il posto al **widget del codice**, che in modalità nascosta sta nell'angolo
opposto sulla stessa riga.

⚠️ **Quel che le bolle scansano è il contenitore dei due, non il primo riquadro**: in nativo la
misura (`onSizeChanged`) sta sul `FlowRow`, nel web `getScorePanelRect()` misura `#score-panels`.
Misurando il solo totale, le bolle passerebbero sopra il riquadro scuro. Il contenitore si stringe
sul contenuto (`width:auto` col solo `left` impostato), quindi quando il riquadro è uno solo il
rettangolo è esattamente quello di prima.

⚠️ **E il riquadro va acceso PRIMA di collocare le bolle, non dopo** (v1.7.1): nel web
`updateHiddenPanel()` si chiama in cima a `render()`, non solo dentro `updateScorePanel()` come il
totale. Le bolle si dispongono in `render()` scansando il rettangolo di *quel* momento, mentre
`updateScorePanel()` arriva in coda perché il totale netto aspetta `fetchPtsSpent()`: accendendo il
riquadro solo lì, alla prima accensione della modalità nascosta le bolle risultavano già piazzate
contro il rettangolo stretto — cioè **disegnate sopra il riquadro scuro**, e senza ridisegnare la
home non si rimettevano più a posto. Il conteggio delle nascoste non dipende da nessuna chiamata di
rete, quindi si può accendere subito. ⚠️ In nativo il caso non si pone e non per fortuna:
`posizioni` è `remember(bolle, w, h, pannello)`, quindi l'ingombro che cresce **ricalcola** le
posizioni da sé — è la stessa ragione per cui la misura sta sul contenitore e non su una costante.

⚠️ **E nel web il rettangolo va tradotto nelle coordinate di `#field`** (v1.7.2): le bolle sono
`position:absolute` dentro `#field`, che comincia **sotto la barra blu** (`top: 56px`), quindi la
loro `y` si conta da lì; `getBoundingClientRect()` dà invece le coordinate della **finestra**.
Restituendole come sono, il rettangolo da scansare risultava **56 px più in basso** di dov'era
davvero e le bolle gli passavano sopra per quella fascia — in tutt'e tre i punti che lo usano
(collocazione iniziale, assestamento, trascinamento). ⚠️ Il difetto **c'era da prima** dei due
riquadri: con un riquadro solo la fascia scoperta era la stessa, ma più stretta e in un angolo, e
si leggeva come una bolla messa male. ⚠️ In nativo il caso non si pone: là `BubbleLayout` riceve
l'ingombro misurato **dentro lo stesso contenitore** in cui le bolle sono collocate, quindi un
sistema di riferimento solo.

Il catalogo (`premi/`) ha le tre viste del modale web — premi da ritirare, 🛠 gestione,
📋 cronologia — sulle stesse tabelle `cm_rewards` e `cm_rewards_log`, lette come `JsonObject` e non
come `data class` serializzate (non stanno in nessuna migration: stessa scelta di `ts_tasks`). Le
due regole che **sono** la funzionalità, da cambiare nelle due implementazioni insieme:

- **il riscatto è in due passi, in quest'ordine**: prima la riga in `cm_rewards_log` — che è quella
  che scala i punti — poi l'aggiornamento del premio. Al contrario, una rete che cade lascerebbe un
  premio segnato come ritirato senza che nessun punto sia stato speso;
- **una tantum e ripetibile finiscono diversamente**: il primo esce dal catalogo (`is_redeemed`),
  il secondo resta e **rincara** di `points_per_use` a ogni uso, contandoli in `use_count`.
  ⚠️ **`points_per_use` a zero è legittimo** — è il premio ripetibile che costa sempre uguale — e
  va distinto dal campo lasciato in bianco, che resta non valido. Il web ci era cascato con
  `parseInt(…) || null`, che trasformava lo zero in «non impostato»: il premio si salvava senza
  incremento e il form si riapriva vuoto. Le due implementazioni ammettono `>= 0` e all'elenco
  scrivono *costo fisso* invece di tacere, perché un incremento taciuto è indistinguibile da un
  incremento che nessuno ha scritto.

### ⚠️ Modalità nascosta: si accende col codice a colori, e vive in memoria

La modalità nascosta è **una sola per l'app** (`core/ModalitaNascosta`, l'equivalente di
`sessionStorage.hidden_mode`): l'accende la home, e le altre schermate la leggono — oggi Memo, che
quando è accesa mostra il proprio 🙈/👁.

⚠️ **Sta in memoria e basta, di proposito.** Sul web muore con la scheda del browser, qui col
processo. Scriverla nelle preferenze la farebbe ritrovare accesa al risveglio dell'app — cioè le
schede riservate a schermo senza che nessuno abbia rifatto il codice, che è esattamente ciò che la
modalità esiste per impedire.

Si accende con lo stesso gesto del launcher, in quattro passi: **pressione lunga sul campo delle
bolle** apre il widget delle caselle, si **toccano le bolle** e ognuna mette **il proprio colore**
nella casella successiva, la **freccia ›** (o una casella già piena) confronta, e l'occhio 👁 —
che compare solo a modalità accesa — la rispegne senza rifare il codice. Un confronto che torna
**inverte** la modalità, come `checkSequence()`; uno sbagliato non dice niente e chiude il widget.
Il widget si richiude da sé dopo **15 secondi** di inattività, e ogni tocco rimanda la chiusura.

Tre cose che sono il motivo per cui il codice funziona:

- **il codice non è scritto da nessuna parte nell'app**: sta in `cm_settings`, riga
  `hidden_mode_sequence`, la stessa che legge `loadHiddenSequence()` nel launcher. Senza quella
  riga il confronto esce subito e non apre niente — come sul web. Fino alla v1.0.20 bastava invece
  una pressione lunga sull'icona ↻, che era una modalità nascosta con la chiave sotto lo zerbino;
- **è la bolla a dare il colore**, quindi il codice cambia da sé se un giorno cambiano i colori
  delle app, e non c'è una tastiera da guardare mentre si digita;
- **a widget aperto le bolle non si aprono e non si trascinano**: il tocco vale solo come cifra. È
  la stessa scelta del web, dove `onDown` esce prima di armare `onUp` e `launchApp` non scatta mai
  — senza, ogni cifra del codice aprirebbe un'app.

Il fumetto degli avvisi di `index.html` ha una **settima sezione, 🎯 Obiettivi**
(`loadHomeAlertObiettivi`): le azioni da svolgere oggi, cioè quelle che cadono oggi più quelle
rimaste indietro, marcate con ⚠️. ⚠️ Le **prossime non entrano**: il fumetto è un promemoria di
quel che si può chiudere adesso, non l'agenda — quella è il 📆 Piano quotidiano dentro l'app. Le
azioni a libera ripetizione restano fuori per costruzione, perché `next_occurrence_date` è nullo.

E un'**ottava, 🌱 Piante** (web v1.9.6, APK 1.0.106: `loadHomeAlertPiante` / `avvisiPiante()` in
`HomeRepository.kt`, gemelle): le azioni di cura di oggi più le arretrate (⚠️), «azione — pianta».
⚠️ **Fuori le piante archiviate**; le azioni di **gruppo** restano, col nome del gruppo al posto
della pianta — in nativo il tocco apre Piante, che quelle azioni non le mostra ancora. ⚠️ Il
confine è la mezzanotte **locale** di domani, non quella UTC.

Gli avvisi in home nativa hanno per ora **tre fonti, Spuntiamola, Ta Firi? e le azioni delle Piante**: le altre quattro del web
(decisioni, task urgenti, totale portafogli, abitudini) porterebbero a schermate che qui non
esistono, e un avviso che non apre niente è peggio di nessun avviso. Quello di Ta Firi? elenca le
sfide in corso oggi con il loro orario di check-in, ed è la copia di `loadHomeAlertChallenges` —
compreso il fatto che **compare anche se la sfida di oggi è già spuntata**: lì è un promemoria di
cosa c'è in ballo, e la domanda vera la fa il banner dentro l'app.

### ⚠️ Deep link: schema proprio, pagina-ponte https, browser completo

Il login usa **`garsalnative://oauth`**, non `garsalapps://oauth`: con lo stesso schema Android
chiederebbe a ogni login quale delle due app aprire.

Ma **quello schema non è il `redirect_to`**, e non va messo in whitelist: Supabase non lo vede
mai. Il giro che funziona ha tre pezzi, tutti e tre già pagati una volta nell'APK WebView e
reimparati dal nativo l'11 agosto 2026 (v1.0.6):

1. **`redirect_to` è una pagina https**, `oauth-callback-native.html` — è lei che va fra i
   Redirect URLs. Chrome **blocca il salto automatico** da una pagina di login a uno schema
   custom: dando `garsalnative://oauth` come `redirect_to`, il login finisce e resta lì nel
   browser, senza tornare nell'app e senza un errore da nessuna parte.
2. **La pagina-ponte non fa auto-redirect**: rilancia l'app solo col **tap sull'ultimo pulsante**.
   L'auto-navigazione verso lo schema custom viene rimbalzata da Chrome e produce lo stesso
   sintomo; un gesto dell'utente passa sempre. Sta scritto anche in `oauth-callback.html`, che
   ci era già arrivata.
3. **Il login si apre nel browser completo** (`Intent.ACTION_VIEW` + `CATEGORY_BROWSABLE` +
   `FLAG_ACTIVITY_NEW_TASK`), **non in una Custom Tab**: da una Custom Tab aperta dall'app quel
   tap non rilancia l'app. Per questo `AuthRepo.loginConGoogle` costruisce l'URL da sé invece di
   chiamare `signInWith(Google)`, che aprirebbe una Custom Tab.

`oauth-callback-native.html` e `oauth-callback.html` sono **gemelle a meno dello schema**
(`garsalnative://`, `garsalapps://`). Due pagine e non una con un parametro perché ciascuna va in
whitelist esattamente com'è, e una query string nella whitelist di Supabase è un modo in più di
sbagliare. Se modifichi una, guarda anche l'altra.

⚠️ Nota storica: fino alla 1.0.5 un commento in `Supabase.kt` diceva che la Custom Tab era
«l'unico modo per cui Google non rifiuti il login come user agent non sicuro», attribuendola
all'APK WebView. Leggeva male quel file: l'APK WebView usa il **browser completo** e tiene la
Custom Tab solo come ripiego. Quello che Google rifiuta è il WebView incorporato, non il browser
di sistema.

### ⚠️ Il rientro dal login non passa da `parseFragmentAndImportSession`

Quella funzione di supabase-kt fa il lavoro dentro `authScope`, lo scope interno della libreria,
che è un `CoroutineScope(dispatcher)` — quindi con un `Job()` normale, **non** un `SupervisorJob`.
La prima cosa che fa lì dentro è una chiamata di rete (`retrieveUser`), nell'istante esatto in cui
l'app sta rientrando in primo piano dal browser: se quella tira un'eccezione, l'eccezione risale al
Job e **cancella `authScope` per tutta la vita del processo**. Da lì in poi nessun import di
sessione, nessun rinnovo del JWT e nessuna rilettura dall'archivio vanno più a segno, senza che
venga stampato niente — l'app resta sul pulsante di login e ripremerlo non cambia nulla, perché il
token torna e non lo raccoglie più nessuno.

`AuthRepo.completaConFragment` legge quindi il fragment con `parseSessionFromFragment` (pubblica e
senza rete) e chiama `importSession` **dallo scope di `AuthRepo`**, dentro un try/catch.
`retrieveUserForCurrentSession` si chiama dopo e il suo esito non conta: id ed email si leggono dal
JWT (`Jwt.claim`, claim `sub`), che è lo stesso valore che le RLS vedono come `auth.uid()`.

Due corollari, entrambi già in codice e da non disfare:

- **`enableLifecycleCallbacks = false`** in `Supabase.kt`. L'osservatore che supabase-kt installa da
  sé riporta `sessionStatus` a `Initializing` a ogni `onStop` — e aprire la Custom Tab del login *è*
  andare in background, come lo è il PIN chiesto dalla biometria — e al rientro rilegge la sessione
  dall'archivio **in parallelo** all'import del token appena ricevuto. Il rinnovo del JWT non ne ha
  bisogno: il job parte da `importSession` e dorme fino all'80 % della scadenza, come
  `startTokenRefresh()` sul web.
- **Il deep link si consuma una volta sola.** `getIntent()` continua a restituire quello di partenza
  per tutta la vita dell'Activity: a ogni ricreazione `onCreate` si ritroverebbe lo stesso
  `access_token`, ormai scaduto o già speso, e lo rimetterebbe al posto di una sessione buona.
  `gestisciDeepLink` azzera `intent.data` appena l'ha letto.

### ⚠️ Il marchio vive in sei posti, e vanno cambiati insieme

Il logo di AppSphere sono **cinque cerchi** — arancio, rosso, verde e viola che si toccano a due a
due, e il blu al centro sopra a tutti — e sta scritto in sei file:

| Dove | File |
|---|---|
| Icone di lancio dei due APK AppSphere | `app/…/ic_launcher_foreground.xml` e `appsphere-native/…/ic_launcher_foreground.xml` (fondo bianco per il WebView, nero per il nativo: è il segno che distingue le due app sul telefono) |
| Icona di lancio di Smart Blocker (**solo** flavor `salvatore`) | `smartblocker/src/salvatore/res/drawable/ic_launcher_foreground.xml` — gli stessi cinque cerchi su fondo nero, **più il lucchetto** |
| Icona di lancio di SOS | `sos/src/main/res/drawable/ic_launcher_foreground.xml` — gli stessi cinque cerchi su fondo nero, **più il badge rosso `SOS`** |
| Seconda icona dell'APK nativa, «AppSphere pasto» | `appsphere-native/…/ic_pasto_foreground.xml` — gli stessi cinque cerchi su fondo nero, **più il badge verde delle posate** |
| Tutto il nativo (barra, login, biometria) | `appsphere-native/…/core/Logo.kt` — `LogoAppSphere`, disegnata su `Canvas` |
| Barra in alto delle pagine | l'SVG in linea dentro `#garsal-top-bar` (e `#user-bar` / `.login-top-bar-icon` in `index.html`) |

⚠️ **È già successo che divergessero**: le icone di lancio erano passate al marchio nuovo e le
barre mostravano ancora i cerchi olimpici — cioè il logo di due generazioni prima. Cambiando il
marchio si toccano tutti e cinque.

⚠️ **Tre APK portano lo stesso disegno e si distinguono per quel che ci sta sopra o sotto**: il
fondo (bianco / nero) fra i due AppSphere, il lucchetto per Smart Blocker, il badge `SOS` per SOS.
È il segno che li distingue in un cassetto delle app, dove i nomi stanno scritti piccoli.

⚠️ **«Spese in giro» NON lo porta**: la sua icona è una
bicicletta (`android-app/spese-in-giro/…/ic_launcher_foreground.xml`). Non è una dimenticanza — quella
non è un'app della suite: non ha una bolla in `cm_apps`, non fa il login Google, non legge nessuna
tabella delle altre e i suoi dati non appartengono a un account. Mettere lì il marchio direbbe il
falso proprio nel posto dove il marchio serve a dire di che famiglia è un'app. Il giorno che
la sua icona cambia, **non** si tocca nient'altro; e cambiando il marchio, lei resta com'è.

⚠️ **Nella barra il marchio sta su un disco bianco**, e non è decorazione: la barra è `#0081C8` e
il cerchio centrale del marchio è `#067BC0`, quindi senza fondo il pezzo che regge il disegno
sparisce nel colore della barra. Sul launcher il fondo ce l'ha già.

⚠️ **I cerchi olimpici sono spariti dall'interfaccia**: `CerchiOlimpici` non esiste più —
`LogoAppSphere` ha preso il suo posto anche sull'avvio e sulla schermata della biometria, che
erano gli ultimi due punti rimasti indietro. **`Palette.olimpici` invece resta**, ed è un'altra
cosa: è il ripiego dei colori delle bolle della home quando `cm_apps.color` è vuoto.

⚠️ **Nel nativo il marchio è una funzione sola, non un drawable per contesto**: `LogoAppSphere`
prende un parametro `disco`, e il fondo bianco si accende solo dove serve. Cinque cerchi disegnati
su `Canvas` si adattano a qualsiasi dimensione senza un asset per densità — è la stessa scelta che
era già stata fatta per i cerchi olimpici.

⚠️ **Le barre delle pagine non sono tutte lo stesso marchio**: quelle che dicono *Garsal Apps* e
rimandano a `/` portano il logo; `calorie.html` (🍽️), `finanza.html` e figlie (*GarsalFinanza*),
`spese-ada.html`, `spese-personali.html` e le pagine ospiti hanno un'identità propria e **non**
vanno allineate.

### ⚠️ `material-icons-extended` non va reintrodotto

Quel pacchetto contiene **migliaia di icone compilate come codice Kotlin**: senza
minificazione finiscono tutte nel DEX, e da solo portava l'APK a **51 MB** (~50 dei quali di
bytecode) contro i ~10 di adesso. Un APK così si installa male — la verifica della firma e
l'ottimizzazione costano un multiplo del pacchetto, e su un telefono pieno o in risparmio
energetico l'installazione fallisce senza dire perché.

Le icone in uso sono quattro (`ArrowBack`, `Close`, `Refresh`, `Settings`) e stanno tutte in
`material-icons-core`, che arriva già con `material3`. Se ne serve una che lì non c'è, si
disegna a mano come `CerchiOlimpici` in `core/Logo.kt`. Il workflow avvisa se l'APK supera i
25 MB, così la cosa non può ripresentarsi in silenzio.

### ⚠️ Kotlin 2.1.20, diverso dagli altri moduli

`supabase-kt 3.1.4` è compilata con stdlib 2.1.20 e i suoi metadata non si leggono con il 2.0.21
usato dagli altri progetti Android. **Le due versioni sono agganciate**: aggiornando supabase-kt va
guardata la sua `kotlin-stdlib` e allineato il `build.gradle` di root.

### ⚠️ Dieci app ora esistono in due implementazioni

`spuntiamola.html`, `obiettivi.html`, `events-log.html`, `ta-firi.html`, `weight-quest.html`,
`memo.html`, `habit-tracker.html`, `calorie.html` e `forziere.html` hanno un gemello Kotlin che
lavora sulle
**stesse tabelle e sugli stessi campi**. È voluto — si spunta un giorno dal nativo e lo si ritrova
sul web con la sua emoji — ma non è gratis: **cambiare le regole di una senza l'altra le fa
divergere in silenzio**, esattamente come per lo snapshot del patrimonio.
I punti dove la regola *è* la funzionalità, e non un dettaglio:

- **Spuntiamola** — la chiusura della stecca scrive **prima** in `sp_stecche` e cancella **dopo**
  (`SpuntiamolaRepository.chiudiStecca`, come `dbCloseStecca()`); le spunte sono ottimistiche con
  rollback; frasi, emoji e messaggi dei traguardi sono copiati parola per parola.
  ⚠️ **L'umore della stecca c'è da tutt'e due le parti** (`sp_settings.mood` / `sp_stecche.mood`,
  vedi lo schema `sp_`): `MOODS` in `spuntiamola.html` e la `data class Mood` +
  `MOODS`/`moodDi()` in `SpuntiamolaModel.kt` sono **la stessa tabella scritta due volte** — frasi,
  emoji, traguardi, messaggi di chiusura, etichette e `fuochiAl100` — e **vanno cambiate insieme**,
  o le due implementazioni commentano la stessa stecca in due modi diversi. Il modo per
  accorgersene: far stampare a tutt'e due la propria tabella e confrontarla campo per campo — i
  **quattordici** campi per umore devono coincidere alla lettera, comprese le 31 frasi dell'attesa
  e le 30 dei bei giorni. ⚠️ Anche il **sottotitolo delle due schede** sta in `MOODS` (`sub` /
  `sottotitolo`) e non nel markup: scritto a mano nell'HTML era una seconda copia di quei testi,
  ed è già divergito una volta dalla riga che spiega cosa cambia (`hint` / `spiegazione`), che è
  una cosa diversa e sta sotto le schede. Da tutt'e due la voce
  della **chiusura si legge prima** che lo stato torni al campo libero (parametro `voce` nel web,
  `val voce = stato.mood` nel nativo): dopo, l'umore è già tornato all'attesa e dei bei giorni
  verrebbero salutati con la voce sbagliata.
  ⚠️ **Le giornate chiave sono facoltative e devono cadere dentro il periodo** in tutt'e due:
  `perchePuoNonEsserci()` è ricalcata riga per riga in `SpuntiamolaModel.kt`, coi due motivi
  distinti (fuori dall'intervallo / sabato o domenica su una stecca che salta i fine settimana) e
  la marcatura in rosso di una chiave che il periodo, accorciandosi, ha lasciato fuori. Differenza
  di forma: sul web la data si sceglie da un calendarietto con `min`/`max`, nel nativo si scrive a
  mano — quindi lì il motivo compare **solo su una data già completa**, o si leggerebbe un errore
  a ogni carattere digitato.
- **Obiettivi** — il progresso resta in `ob_objective_progress` e la scrittura in
  `ob_record_measurement`, che è anche l'unica a dire se un voto sta dentro la scala. Gli estremi
  di una metrica si leggono da `ob_metric_scale`, ricalcata in `scaleOf()` e in `ObMetrica.scala`:
  se cambia il modo di ricavarli, va cambiato in tutt'e tre. Le due barre non si fondono mai in una
  media.
  ⚠️ **Delle azioni il nativo porta il solo 📆 Piano quotidiano** (v1.0.68): si vedono quelle
  ancora da fare e si chiudono con *Completa* / *Salta*, che passano dalle RPC come sul web, e il
  successo apre le stesse rilevazioni. Restano di là ✅ Azioni, 📊 Andamento, il Dettaglio e le
  Esecuzioni, e da qui un'azione non si crea né si modifica. Le etichette sotto la barra
  dell'esecuzione, nella schermata dell'elenco, parlano ancora dei soli sotto-obiettivi e
  milestone. Dettagli nella sezione qui sopra.
- **Events Log** — le tabelle `el_*` non sono in nessuna migration: le colonne dei `data class`
  sono ricavate da come `events-log.html` le scrive. Gli eventi `DA_SELECT` registrano **solo se il
  conteggio è cresciuto** rispetto all'ultimo `count:N`. Il **registro mostra solo il gruppo
  scelto** in tutt'e due: `el_logs` non porta il gruppo, quindi il filtro passa per gli id degli
  eventi di quel gruppo (`logDelGruppo` nel nativo, le stesse due righe in `renderLogPage()`).
  Conseguenza in entrambe: la riga di un evento cancellato non sta in nessun gruppo e non si vede
  più da nessuna parte, pur restando sul database. Differenza voluta: il
  web si ferma agli 8 più recenti, il nativo li elenca tutti perché lì la lista scorre.
  ⚠️ **I gruppi 🙈 riservati hanno gli stessi tre stati da tutt'e due le parti** — modalità
  nascosta spenta: solo i normali; accesa: tutti; accesa col 👁: solo i riservati — e **il filtro
  sta nella query**, come in Memo: fuori dalla modalità nascosta la riga non si legge proprio.
  Fino all'agosto 2026 il nativo la colonna la decodificava e non la guardava: un gruppo riservato
  si vedeva sempre, con i suoi eventi e le sue registrazioni. Differenza di forma: il web ha un
  FAB, il nativo il 🙈/👁 nella top bar (e a modalità accesa marca col 🙈 la scheda del gruppo
  riservato, che il web non fa).
- **Ta Firi?** — il punteggio finale resta in `sf_finalize_challenge`; il check-in di oggi passa
  da `sf_checkin_set` e la correzione di un giorno passato no; la regola Smart Block si scrive da
  tutt'e due. Dettagli nella sezione qui sopra.
- **Ti pisasti?** (`weight-quest.html`) — target interpolato fra i traguardi, giorni senza pesata
  ricostruiti e contati lo stesso, confronto peso/target **a un decimale**, `target_weight`
  congelato nella riga; le due condizioni di `closeObjective('success')` (minimo di oggi / massimo
  del periodo sotto il peso finale) e il punteggio di chiusura; la sincronizzazione con Health
  Connect e Renpho (finestra di 90 giorni, permesso sullo storico, peso troncato a un decimale,
  pesate manuali tolte quando arriva il dato vero). **Premi dei traguardi e punti stanno sulle
  stesse righe** (`ps_milestone_prizes`, `ps_milestone_points`): soglie, distribuzione dei punti,
  id dei cinque premi e il «Mangiato !!!» vanno cambiati nelle due implementazioni insieme.
  **Punti giornalieri e chiusura no**: li danno le RPC `ps_punti` / `ps_chiudi_obiettivo`,
  chiamate da tutt'e due (APK 1.0.93). Il
  nativo porta pesata, tabella, grafico, Gestione Obiettivo, gratta e vinci e Salute: statistiche
  e dieta restano di là. Dettagli nella sezione qui sopra.
- **Memo** — note, liste, diari, 🔗 link e 🏅 premiati esistono in tutt'e due. Sui premiati le regole
  da tenere allineate sono tre e stanno tutte sul punteggio — si scrive sempre e vale 0 fuori dai
  premiati, la casella vuota vale 0 ma una parola no, il totale conta tutti i premiati e non quelli
  filtrati — più il 📌 del nativo, che riscrivendo la riga intera deve rimandare indietro i punti.
  Il contenuto è **HTML**: il nativo lo
  converte in marcatori per modificarlo e lo riconverte salvando (`MemoHtml`), quindi ogni voce
  della barra del web deve avere il suo marcatore di qua. ⚠️ **La condivisione (`ACTION_SEND` +
  `text/plain`) è del solo nativo** dal settembre 2026: la scaletta del titolo vive quindi in
  `Link.titolo()` di qua e in `riempiTitoloSeVuoto()` di là, e va cambiata insieme. Le altre regole da tenere allineate:
  spunte ottimistiche con rollback, misure aggiornate riga per riga e mai ricreate, opzioni di una
  combo archiviate per **id**, misura non toccata che **non** finisce in `measures`, categorie
  riscritte da capo a ogni salvataggio, file del bucket cancellati prima della riga. Il filtro
  `riservato` sta nella query da tutt'e due le parti. Dettagli nella sezione qui sopra.
- **Abituati** — è l'unica dove le regole **non** sono duplicate: streak, jolly, giorni mancati e
  chiusura degli stack stanno nelle RPC `hb_*`, che dalla web v9.6.0 chiamano **tutt'e due**
  le implementazioni — la pagina non ha più nessuna copia in JavaScript di quelle regole. Interrompi, Riprendi ed Elimina ci sono ora da tutt'e
  due le parti, con la stessa regola sulla data di ripartenza. Anche la quarta frequenza
  `count_window` c'è da tutt'e due: le regole stanno nelle RPC, ma la **scheda a finestre coi
  pallini** è disegnata due volte (`renderWindowCard()` e `Finestra()` in `AbituatiScreen.kt`) e
  va cambiata insieme — compreso il `max(N, M)` dei pallini e il ＋ che non passa dalla RPC.
  Dettagli nella sezione qui sopra.

- **Calorie** — il conto del target **non è più duplicato**: lo fa `al_conto` nel database, e
  `CalorieRegole` lo legge soltanto (APK 1.0.94). Le regole: deficit in due
  addendi (ritmo del tratto + recupero dello scarto, mai sotto zero), peso finale solo con almeno
  due traguardi, target congelato in `al_days` e ricalcolabile solo su richiesta, saldo spalmato
  sui giorni che restano, giorno senza righe che non è un digiuno, e da oggi solo lo sforo. Il
  nativo porta 📊 Dashboard, 📓 Diario e il ➕ che segna un alimento; 🍎 Alimenti e ⚙️ Impostazioni
  restano di là, e da qui si leggono soltanto. Dettagli nella sezione qui sopra.

- **Forziere** — qui la cosa da tenere allineata **sono i formati**, non le regole: OpenPGP
  simmetrico AES-256 con **MDC (SEIPD v1)** e S2K iterato **224** da una parte, e
  `base64(iv[12] ‖ cifrato‖tag)` in AES-256-GCM per i metadati dall'altra. Un byte di
  differenza e il telefono legge «nome illeggibile» su tutto quel che ha scritto il PC — o,
  peggio, scrive `.gpg` che il PC (e `gpg`) non aprono. Il nativo porta **sbloccare, sfogliare,
  aprire (immagini, testo, PDF, video e audio), mettere dentro, buttare via**; creazione, collaudo, export
  `.7z` e cambio passphrase restano di là.
  Dettagli nella sezione qui sopra.

- **Piante** — il ciclo di vita delle azioni sta nelle RPC `pv_action_*` (che spostano anche i
  promemoria), e l'IA nella Edge Function `pv-ai`: queste non sono duplicate. Restano da tenere
  allineati a mano **la scrittura delle regole** (`sincronizzaRegole()` in `piante.html` /
  `PianteRepository.scriviRegole`: una riga per canale, `telegram_complete_button` anche su
  `android`), **le foto** (`<user>/<pianta>/<uuid>.jpg`, 1600 px JPEG 0,82, prima il file e poi la
  riga in tutt'e due i versi), la regola «una voce vale se ha il commento **oppure** una foto», i
  gruppi della panoramica (in ritardo / oggi / prossime / quando capita) e le etichette di
  `descriviRicorrenza()`. Il nativo porta tutto: elenco, scheda con foto, diario, azioni coi
  promemoria, IA, lista dei desideri e 👥 gruppi (vedi lo schema `pv_`). ⚠️ La chiamata a `pv-ai` in nativo passa da
  `HttpURLConnection` col timeout di lettura a tre minuti: supabase-kt ha un timeout più corto, e
  una risposta con le foto può durare più di un minuto.

(`tasks.html` è la decima, ma ha una sezione tutta sua: le RPC del ciclo di vita.)

---

## App Details

### `index.html` — AppSphere
- Draggable **bubble/circle UI** — each app is a coloured circle sized proportionally to its `score`
- Score is computed at load time by calling the Supabase RPC `run_score_query` with the SQL stored in `cm_apps.score_query`
- Circle placement uses an iterative collision-resolution algorithm (no overlap, viewport-clamped)
- Tap = launch app; drag = reposition circle
- **🗄️ Cassetto** (v1.10.0): una bolla **trascinata col dito sopra il cassetto** — il quadrato
  in alto a destra sotto la barra blu — sparisce dalla home e ci finisce dentro (v1.10.8 / APK
  1.0.120; fino alla v1.10.7 / 1.0.119 era il **doppio tocco**, ritirato perché si confondeva con
  altri gesti e ritardava di 300 ms l'apertura di ogni app: ora il tocco apre subito). Dal cassetto
  un tocco la rimette in home. ⚠️ **Conta il DITO e non la bolla** (`ditoSulCassetto` nel web,
  gemella in `CampoBolle` nel nativo): la bolla scansa il cassetto (`getScorePanelRect()` /
  `BubbleLayout.Pannello`) e non ci entrerebbe mai. ⚠️ **Durante il trascinamento il cassetto
  compare anche vuoto** (tratteggiato nel web, con 🗄️) e si accende di giallo quando il dito ci
  passa sopra — senza, la prima bolla non avrebbe dove andare. Dentro, una bollicina per app del
  colore della sua bolla in una griglia di ⌈√n⌉ colonne (cresce e resta quadrato). I mousedown
  sintetici dopo un touch si ignorano (`ultimoTocco`), o un tocco aprirebbe l'app due volte.
  ⚠️ Il cassetto sta in `localStorage` (`appsphere_cassetto`, id di `cm_apps`) e nelle preferenze
  del telefono (`home_cassetto`, per `htmlFile`): è una preferenza del dispositivo, e **i punti
  dell'app nel cassetto continuano a contare**. I due cassetti sono indipendenti: forma e regole
  vanno cambiate insieme.
- Color palette: Olympic rings colors (`#0081C8`, `#FCB131`, `#1A1A1A`, `#00A651`, `#EE334E`)
- **☰ → 🏆 Punti** (v1.9.0): guadagnati, spesi e saldo di un periodo (*ultimo mese · trimestre ·
  semestre · anno · dall'inizio*), il totale **per app** (pallino col colore della bolla, emoji e
  nome: toccandolo si filtra) e la **cronologia** di guadagni e premi riscattati, dal più recente.
  ⚠️ **Un registro unico dei punti non esiste**: ogni lettore di `PT_LETTORI` rilegge **le stesse
  righe** che somma la `score_query` di quella app in `cm_apps` (è scritta sopra ciascuno).
  Cambiando una `score_query` va cambiato il suo lettore — su «Dall'inizio» la finestra confronta
  le due cifre e scrive *⚠️ la bolla dice X* quando non tornano. Le app sono quelle di
  `puntiLordi` (niente riservate, niente `conta_punti = false`), quindi «spendibili adesso» è il numero
  del riquadro rosso. Righe di task, gruppi di eventi e schede Memo **riservati** contano i punti ma
  fuori dalla modalità nascosta si leggono *🙈 riservato*. ⚠️ I premiati di Memo portano la data
  della **scheda**: Memo non registra quando cambia un punteggio. ⚠️ Le letture passano da
  `ptLeggi`, che pagina a 1000 righe: fermarsi alla prima pagina darebbe un totale più basso senza
  errori.

### `tasks.html` — Tasks
- Full task lifecycle: create, edit, complete, skip, fail, clone, delete
- Calendar/planner view with recurring task support
- European date format display (`dd/mm/yyyy`) with ISO storage
- Sidebar sections: Dashboard, Gestione (tasks only), Planner, Reminder, Impostazioni
- FAB `+` apre direttamente la creazione task
- **🙈 Nascondi** (web v19.26.0, APK 1.0.115, `20261007160000_ts_tasks_nascosto_fino.sql`): sui task
  ⚠️ SCADUTI un pulsante toglie il task dagli scaduti per N giorni (7 di partenza, oggi compreso),
  scrivendo **solo** `ts_tasks.nascosto_fino` — l'ultimo giorno incluso, NULL = visibile. In quei
  giorni sta nella sezione **🙈 NASCOSTI** (con *👁 Mostra* per riportarlo subito) e dal giorno dopo
  torna da sé fra gli scaduti. ⚠️ **È un filtro di lettura e basta**: scadenza, prossima
  occorrenza, regole e promemoria non si toccano, e nessuna RPC lo legge. ⚠️ Non è
  `show_in_panoramica` (👁️ NON IN PANORAMICA), che è per sempre. Gemelli da cambiare insieme:
  `isNascosto` / `nascondiTask` / `scriviNascostoFino` in `tasks.html`, `TasksState.scadutiNascosti`
  / `TasksViewModel.nascondi` / `TasksRepository.nascondiFino` nel nativo.
- `cm_priorities` e `cm_categories` sono **sola lettura** in tasks.html — la gestione CRUD è in AppSphere → Dati Comuni
- Significant file (~8 500 lines); sections delineated by `// ========================================` comments

### `habit-tracker.html` — Habit Stack Tracker
- Stack-based habits with daily completion tracking
- Gamification: points, multipliers, streaks
- ⚠️ **Uno stack in corso NON fa punti** (`20260930100000_hb_score_senza_stack_in_corso.sql`):
  la `score_query` sommava anche `(spunte / goal) × points_reward` delle abitudini attive, cioè
  un premio pagato a rate prima di averlo vinto. ⚠️ E contava **due volte** il premio di uno
  stack completato: `hb_reconcile` mette l'abitudine a `status = 'completed'` **e** scrive la
  riga d'archivio con `points_earned = points_reward`, e la query sommava tutt'e due. Ora conta
  la penalità dei falliti più `points_earned` dell'archivio, e basta. Stessa regola in tre posti
  da cambiare insieme: la `score_query`, il lettore di ☰ → 🏆 Punti in `index.html` e
  `recalculateTotalScore()` qui.
- Imports/exports via JSON backup
- **Interrompi ↔ Riprendi** sono le due direzioni di `hb_habits.status` fra `active` e `stopped`, e
  non passano dall'archivio: un'abitudine interrotta resta in `hb_habits` con tutte le sue spunte,
  ma esce dalla dashboard e dalla riconciliazione, quindi da lì in poi non genera `missed`, non
  consuma jolly e non può né vincere né fallire.
  ⚠️ **Riprendere chiede da quale data ripartire e riscrive `started_at`**. La finestra si apre sul
  **giorno dopo l'ultima spunta** (`defaultResumeDateStr()` / `AbituatiState.ripartenzaSuggerita()`),
  che è dove l'abitudine si era fermata davvero; le righe `missed` non contano come spunte — le
  scrive la riconciliazione, non l'utente — senza nessuna spunta si ripiega su oggi, e la proposta
  **non va mai oltre oggi**, perché uno `started_at` nel futuro è un'abitudine che non cade mai.
  Con la
  data originale, `hb_reconcile` — che guarda da `started_at` a ieri — marcherebbe `missed` ogni
  giorno passato dall'interruzione: i jolly finirebbero sul posto e il game over scatterebbe prima
  ancora di rivedere la scheda. La finestra conta quei giorni **prima** di scrivere
  (`countMissedIfResumed`, cioè la RPC `hb_giorni_da_recuperare` — un jolly per giorno mancato, non
  per sessione) e lo dice; se anche così i jolly non bastano, chiede conferma invece di impedirlo.
  ⚠️ Il **promemoria non torna**: `stopHabit()` cancella la riga di `cm_notification_rules`, e ora
  che è cancellata orario e canale non stanno più da nessuna parte — si riscrive da MODIFICA, sul
  web come nel nativo (dalla APK 1.0.108).
  ⚠️ **Il conteggio dei giorni da recuperare non sta nel client**: è la RPC
  `hb_giorni_da_recuperare`, che chiamano sia la pagina sia il nativo — due copie di quella formula
  sarebbero due avvisi diversi sullo stesso game over. Torna `null` (non zero) se la chiamata non
  riesce: «non lo so» e «non costa niente» sono due cose diverse.
- **Interrompi, Riprendi ed Elimina esistono ora in tutt'e due le implementazioni**, con la stessa
  regola sulla data. In Gestione il filtro *Attive / Interrotti / Tutte* è la tendina `#filterStatus`
  del web e `FiltroStato` nel nativo, e parte da **Attive** in tutt'e due: le interrotte sono
  memoria, non lavoro di oggi. Fino all'agosto 2026 la pagina non aveva l'eliminazione
  (`deleteHabit()` esisteva ma nessun pulsante la chiamava) e il nativo non aveva né interruzione né
  ripresa: erano due elenchi che facevano cose diverse sulla stessa tabella.
- ⚠️ **Uno stack si chiude anche l'ultimo giorno, non solo il giorno dopo.** Le due strade per
  chiuderlo si perdevano proprio il giorno in cui la stecca finisce, e su una **giornaliera a più
  orari** si vedeva bene: segnati tutti gli appuntamenti dell'ultimo giorno — chi fatto, chi saltato
  col jolly — non succedeva niente fino all'indomani. L'obiettivo raggiunto conta i soli giorni
  **fatti per intero** (`countCompletedDaysInPeriod`, `hb_giorni_fatti`), e un giorno in cui un
  appuntamento è stato saltato non ci entra: per quanti giorni si segnino, il conto non arriva mai
  al traguardo. La scadenza del calendario, che i giorni saltati li copre coi jolly
  (`completato_con_jolly`), scattava invece da `oggi > ultimo giorno`. Ora scatta **anche l'ultimo
  giorno, ma solo quando di quel giorno non resta niente in sospeso** (`hb_giorno_risolto()`, per
  tutt'e due i client dalla web v9.6.0): chiudere l'ultimo giorno a stecca ancora da segnare la
  archivierebbe come persa mentre c'è tutto il tempo per finirla. ⚠️ «Risolto» non è «fatto»: vuol
  dire che **ogni periodo di quel giorno ha la sua riga**, comunque sia andata (`completed`,
  `failed`, `skipped`, `missed`); un giorno non dovuto è risolto per definizione.
  ⚠️ Non ci si può appoggiare allo streak per chiudere: quello del web (`computeHabitStreak`) conta
  le righe di **qualunque** stato ma **salta il primo giorno** — riceve `started_at` a mezzogiorno e
  lo confronta con giorni a mezzanotte — quindi l'ultimo giorno vale sempre `goal - 1`.
- ⚠️ **Il nuovo ciclo si propone da domani, non da oggi** — in tutt'e due le cerimonie (stack vinto
  e game over) e in tutt'e due le implementazioni (`tomorrowDateStr()` nel web,
  `LocalDate.now().plusDays(1)` in `Festa`/`GameOver`): la stecca che si è appena chiusa **si è
  presa oggi** — l'ultimo giorno è proprio quello che l'ha chiusa — e un ciclo che ripartisse dallo
  stesso giorno nascerebbe con la prima giornata già spesa, o già segnata dalla stecca di prima.
  Resta una proposta, il campo si cambia.
- ⚠️ **`hb_habits.frequency` ha quattro valori**: `daily`, `daily_multiple`, `weekly` e
  `count_window` (il quarto **solo web**, vedi il punto sotto). La
  tendina offriva anche *Personalizzata* (`custom`), che però **nessuna riga di codice leggeva** —
  la stringa compariva una volta sola, nell'`<option>`. Un'abitudine così cadeva nel ramo `else`
  di ogni funzione, cioè si comportava da giornaliera, **tranne nel controllo dei giorni
  mancati**: `checkMissedDays` (ora tolto) e `hb_reconcile` (che ha un `ELSE false` esplicito) non la
  guardavano, quindi nessun giorno diventava `missed`, nessun jolly si consumava e lo stack non
  poteva fallire. La voce è stata tolta e
  `20260824180000_hb_frequenza_custom_a_daily.sql` ha riportato quelle righe a `daily`.
  ⚠️ Aggiungendo una frequenza nuova, i posti da toccare sono **quattro**: le due tendine della
  pagina, `hb_reconcile`/`hb_periodo_key` e `FREQUENZE` + `cadeIl()` nel
  nativo — dove una frequenza sconosciuta vale `false`, cioè l'abitudine **non compare mai in
  🎯 Oggi**. La tendina di modifica ripiega su `daily` quando la riga porta un valore che non
  conosce: prima il select restava senza selezione e il salvataggio scriveva `frequency: ''`.
- ⚠️ **La quarta frequenza `count_window` — «N volte in M giorni dal giorno di inizio» — c'è
  in tutt'e due le implementazioni** (`habit-tracker.html` v9.5.4, APK nativa v1.0.82; la migration
  `20260915120000_hb_habits_count_window.sql` aggiunge `times_target` (N) e `window_days` (M) a
  `hb_habits`, nullable come `weekdays`/`daily_times`). Le **finestre** si ripetono dal giorno di
  inizio — [inizio, inizio+M-1], [inizio+M, inizio+2M-1], … — e una finestra è soddisfatta con
  almeno N spunte `'completed'` dentro; chiusa con meno di N è **una mancata sola** (`period_key`
  `w<inizio>`, un jolly, game over come le altre). Le spunte restano **per giorno** (`period_key`
  = data più un progressivo), ma streak, obiettivo e
  mancate si contano **a finestre** — `goal` è il numero di finestre da soddisfare. Tutto passa
  dagli helper `cw*` (`cwWindowStart`, `cwWindowSatisfied`, `cwSatisfiedWindows`, `cwStreak`) e
  da rami **additivi con return anticipato**, così le tre frequenze storiche non cambiano di una
  riga. ⚠️ **Nelle RPC `hb_*` è arrivata dopo** (`20260916120000_hb_count_window_rpc.sql`,
  16 settembre 2026): fino a lì cadeva nel loro ramo sconosciuto e in nativo l'abitudine **non
  compariva affatto** — `cadeIl()` tornava `false` e 🎯 Oggi non la elencava mai. I «cinque posti»
  della regola qui sopra sono ora coperti tutti.
  ⚠️ **Nelle RPC il periodo è la FINESTRA e non il giorno**, e sono quattro funzioni a saperlo:
  `hb_streak` conta le finestre soddisfatte consecutive (la finestra in corso non piena non
  spezza, come «oggi» per le giornaliere), `hb_giorni_fatti` **cambia grandezza e non nome** —
  torna finestre, perché è con `goal` che si confronta — `hb_giorno_risolto` guarda la finestra
  che contiene il giorno, e `hb_reconcile` scrive le mancate a finestra chiusa e allunga la
  scadenza a `inizio + goal × M − 1`. Col conto in giorni una stecca di 4 finestre da 7 scadrebbe
  al quarto giorno.
  ⚠️ **`hb_giorni_da_recuperare` ha un ramo suo**: riprendendo da una data le finestre ripartono
  da lì, e quel che costa sono i **pallini rossi** di quelle che si richiuderebbero prima di oggi.
  Senza, la finestra della ripresa direbbe «non costa niente» su un'abitudine che i jolly li
  consuma.
- ⚠️ **`count_window` si disegna a FINESTRE, non a giorni** (v9.5.6): una scheda per finestra
  (`renderCountWindowStack` / `renderWindowCard`), e dentro **`max(N, M)` pallini**, uno per
  giorno — bianchi col bordo. Il **＋** ne colora uno di verde, il **−** riporta a bianco l'ultimo. ⚠️ I
  pallini **rossi compaiono solo a finestra CHIUSA** (i mancanti fino a N): prima non sono
  mancate, sono cose che si possono ancora fare. `goal` qui sono **finestre**, non giorni, e
  l'etichetta del campo lo dice.
- ⚠️ **N PUÒ superare M, e i pallini sono `max(N, M)`** (v9.5.7): col tetto giornaliero a 1 le
  volte non possono superare i giorni, ma a 3 al giorno «10 volte in 5 giorni» è una richiesta
  legittima — il vincolo vero è **N ≤ M × P**, ed è quello che il form controlla. Sotto `max(N, M)`
  il traguardo non ci starebbe nei pallini disegnati: una finestra si leggerebbe piena a metà, e il
  ＋ si spegnerebbe prima di aver segnato quel che chiede. Con N ≤ M — il caso normale — i pallini
  restano M, cioè uno per giorno, e non cambia niente.
- ⚠️ **`hb_habits.max_per_day` è il tetto giornaliero**
  (`20260916100000_hb_habits_max_per_day.sql`, nullable, di partenza 1): quante spunte si possono
  segnare **nello stesso giorno**. Senza, le N volte si potrebbero fare tutte in un pomeriggio,
  che è l'opposto di quel che «N volte in M giorni» chiede — raggiunto il tetto il ＋ si spegne
  fino a domani e la scheda scrive perché.
- ⚠️ **Il ＋ NON passa da `setDayState`** (`cwAggiungi`/`cwTogli`): quella cerca la riga per
  `period_key = data` e con più spunte nello stesso giorno **aggiornerebbe la prima** invece di
  aggiungerne una. La chiave porta quindi il progressivo del giorno (`2026-09-16#2`), così due
  spunte sono due righe. Il **−** toglie l'ultima spunta della **sola finestra in corso**: quelle
  chiuse hanno già consumato i jolly, e rimetterci mano riscriverebbe un conto già fatto.
- ⚠️ **Il progressivo si legge dalla CHIAVE (`cwProgressivo`/`cwUltimoProgressivo`) e non si
  conta**, e «l'ultima» che il − toglie è quella col **(giorno, progressivo) più alto**: dentro un
  giorno il `completed_at` è lo stesso per tutte, quindi ordinarle per lui è un sorteggio — tolta
  `#1` e lasciata `#2`, un ＋ che contasse le righe riproverebbe `#2` e il database lo rifiuterebbe
  (`hb_completions_habit_period_unique`). `cwInVolo` tiene poi **una scrittura per volta per
  abitudine**, perché un tocco può arrivare doppio e i due giri leggerebbero lo stesso stato. Vale
  identico in nativo (`progressivoDi` / `ultimoProgressivo` / `inVolo`).
- ⚠️ **I pallini della SETTIMANALE sono grandi come quelli di `count_window`** (20 px,
  `weekDotStyle` / `.cw-dot`, v9.5.9): sono la stessa cosa — lo stato di un periodo letto un
  giorno per volta — e a 16 px la riga dei sette si leggeva come una decorazione invece che come
  il riepilogo della settimana. ⚠️ Il **passo di `.week-dots` è sceso a 2 px**: sette da 20 più
  sei spazi devono stare dentro i 200 px della scheda, e un pallino che andasse a capo spezzerebbe
  la settimana in due righe. ⚠️ Qui i **rossi restano immediati** — un giorno configurato e
  passato senza spunta è rosso subito — e non a periodo chiuso come in `count_window`: là la
  finestra si può ancora riempire in un altro giorno, qui il giorno dovuto è quello e basta.
  ⚠️ **La misura è scritta inline in `weekDotStyle()`** e non in `.week-dot`, che di suo non ha
  dimensione: è l'unico posto da toccare, e lo legge anche l'aggiornamento dal vivo di un singolo
  pallino.
- ⚠️ **I sette pallini ci sono anche in NATIVO** (APK 1.0.84, `SettimanaPallini` in
  `AbituatiScreen.kt`), sotto i pulsanti di una settimanale in 🎯 Oggi: stessi cinque stati e
  stessi colori di `weekDotStyle()`, e **vanno cambiati insieme**. Tre differenze volute:
  ⚠️ **c'è la sola settimana in corso** — il web le sfoglia tutte, ma 🎯 Oggi risponde a «cosa
  devo fare adesso», e una striscia di settimane dentro la scheda di una lista che scorre in
  verticale sarebbe uno scorrimento dentro l'altro; ⚠️ i pallini sono **22.dp, quelli delle
  finestre in nativo**, e la riga **scorre** invece di andare a capo, perché coi caratteri di
  sistema grandi le sette lettere sono più larghe dei pallini; ⚠️ i pallini portano i **tre soli
  stati che il database ammette** (`completed`, `failed`, `missed`), che dalla v1.0.85 sono anche
  gli unici che il nativo sa comporre.
  ⚠️ **`off` non è «non fatto»**, di qua come di là: grigio pieno è un giorno che l'abitudine non
  prevede (o precedente al suo inizio), bianco col bordo è il giorno dovuto e ancora da fare —
  senza quella distinzione una settimanale di tre giorni si leggerebbe come quattro giorni
  saltati.
- ⚠️ **Una finestra mancata costa UN JOLLY PER PALLINO ROSSO**, non uno per finestra: chiusa a 2
  su 6 sono **quattro** mancate e quattro jolly. `hb_reconcile` scrive quindi una riga `missed`
  per ciascuna, con la chiave `w<inizio>#<n>` — il progressivo serve a riconoscere quelle già
  scritte senza ricontarle a ogni giro.

### `events-log.html` — Events Log
- Groups → Events → Logs hierarchy
- Quick-log UI: select event, tap to log with timestamp
- **Un gruppo può essere 🙈 riservato** (`el_groups.riservato`, spunta nel modale del gruppo): è
  la stessa modalità nascosta del launcher e di `memo.html`, coi soliti tre stati. La accende
  **AppSphere, non questa pagina** (`sessionStorage.hidden_mode`, `postMessage`,
  `BroadcastChannel`); qui il FAB 🙈/👁 compare solo a modalità già accesa e alza o abbassa il
  solo filtro. ⚠️ **Il filtro sta nella query** (`loadGroups()`): i gruppi riservati non si
  leggono proprio. Eventi e registrazioni non hanno un flag proprio — seguono il gruppo
  (`filterEventsByVisibleGroups()`, poi i log per eventi visibili).

### `obiettivi.html` — Obiettivi
- Obiettivi annuali con sotto-obiettivi trimestrali (`parent_id`, due soli livelli)
- ⚠️ **Dettaglio e Andamento sono due finestre con due mestieri**, e la separazione è la
  funzionalità: **Dettaglio** dice *com'è definito* l'obiettivo — metriche, sotto-obiettivi,
  milestone e azioni, che da lì **si aggiungono** ma non si chiudono, e non ha né il pulsante
  *Rileva* né una riga di storico. **📊 Andamento** dice *come sta andando*. Le azioni si
  completano da ✅ Azioni.
- ⚠️ **La Tab 📈 Rilevazioni non esiste più** (v1.7.0), e con lei `openMeasure()` e la finestra
  della rilevazione singola: **un numero si registra chiudendo l'azione che dovrebbe muoverlo**, e
  si guarda in 📊 Andamento. Una pagina che chiedeva un valore slegato da quel che si era fatto
  invitava a inventarlo — e la data lì si sceglieva a mano, che è proprio ciò che
  `giornoDiChiusura` toglie di mezzo.
- **📊 Andamento** (pulsante accanto a *Dettaglio* sulla scheda): quattro numeri di testa
  (risultato, esecuzione, punti presi, giorni alla scadenza), **una curva per metrica** e lo stato
  delle azioni.
- **Due barre affiancate, mai fuse in una media**: *risultato* (metrica `primary` dell'obiettivo) e
  *esecuzione* (% figli + milestone completati). Il progresso del padre **non** è la media dei figli:
  quando le due barre divergono di ≥ 25 punti l'app mostra un avviso esplicito, perché è il segnale
  che il piano viene eseguito ma il metodo non funziona.
- Semaforo (`on_track` / `at_risk` / `off_track`) confrontando il risultato con l'ultima milestone scaduta
- **Una metrica chiede due cose, non otto**: un ruolo e un tipo. I due tipi sono i due modi veri di
  rispondere a «come va?» — **autovalutazione**, dove ti dai un voto dentro una scala che scegli tu
  (minimo e massimo, proposti 1-10) con scritto accanto cosa vuol dire votare basso e cosa alto; e
  **automisurazione**, dove misuri un numero, con scritto cosa si misura, da dove parti e dove vuoi
  arrivare. Il form mostra la scala *oppure* partenza/obiettivo, mai entrambe, com'è il vincolo un
  livello sotto.
- Il voto si dà con uno **slider che vive dentro la scala della metrica**: un voto fuori scala lo
  rifiuterebbe comunque `ob_record_measurement`, ma di lì non si può nemmeno comporre.
- Formula unica di avanzamento per tutt'e due i `kind`, e regge anche una scala che scende:
  `(corrente − da) / (a − da)` — es. pause, partenza 14 → obiettivo 3, corrente 6 ⇒ 73 %
- **📆 Piano quotidiano** è la pagina che si apre per prima: **tutte** le azioni ancora da fare,
  giorno per giorno — le arretrate in un riquadro solo in cima (con quanti giorni ha la più
  vecchia), poi oggi, poi i giorni che vengono, e in fondo quelle a libera ripetizione. Da lì si
  chiudono. ⚠️ Le **concluse non ci sono**: un piano dice cosa resta da fare, e una riga che non si
  può più toccare è memoria — si guarda in 📊 Andamento. È la stessa materia di ✅ Azioni letta in
  un altro modo: lì si cerca e si filtra, qui si scorre il calendario.
- ⚠️ **Una libera ripetizione non mostra una data**: `actionDay()` ripiega su `start_date`, e
  scriverla la farebbe sembrare una scadenza. Di lei conta l'ultima volta che è stata fatta.
- ⚠️ **Il Piano quotidiano esiste anche in nativo**
  (`android-app/appsphere-native/app/.../obiettivi/PianoScreen.kt`): è l'**unica** pagina di
  Obiettivi portata, e la bolla della home nativa apre lei. Sezioni, etichette dei giorni, quali
  pulsanti compaiono e la finestra delle rilevazioni sono ricalcate riga per riga e **vanno
  cambiate nelle due implementazioni insieme** — dettagli in *AppSphere nativa → Obiettivi
  nativo*.
- **✅ Azioni** è una voce di menù a sé, oltre alla sezione dentro il dettaglio di ogni obiettivo.
  L'elenco è raggruppato come la panoramica dei task — ⚠️ Scadute, 🎯 Oggi, 📅 Prossime,
  🔄 A libera ripetizione, 🏁 Concluse — con i filtri per obiettivo, tipo, priorità, categoria,
  stato e testo.
- **Quali pulsanti compaiono**: *Completa* sempre, *Salta* solo su ciò che ha una prossima volta a
  cui rimandare (`single`, `recurring`, `simple_recurring`, `multiple`). ⚠️ Un `workflow` mostra
  **Step** al posto di *Completa*: si chiude dai suoi step, e un pulsante che lo chiudesse di forza
  salterebbe quelli ancora aperti.
- **Esecuzioni** (v1.10.0): ogni scheda della pagina ✅ Azioni porta il pulsante *Esecuzioni*, col
  numero di volte che l'azione è stata chiusa, e apre l'elenco di quelle volte — **programmata**,
  **eseguita**, com'è andata, quanti punti — con un 🗑 per ciascuna. ⚠️ È l'**unico pulsante che compare anche su
  un'azione conclusa**: è lì che le esecuzioni ci sono, ed è la scheda che di pulsanti non ne ha
  nessun altro. Resta invece fuori dal 📆 Piano quotidiano e dal Dettaglio dell'obiettivo — un
  piano dice cosa resta da fare e il Dettaglio com'è definito l'obiettivo: le volte già fatte sono
  memoria.
  ⚠️ **Le due date sono due cose diverse e vanno lette insieme**
  (`20260831100000_ob_action_history_occurrence_date.sql`): lo storico diceva *quando* un'azione
  era stata chiusa (`timestamp`) e non *per quando* era programmata, e su un'occorrenza arretrata
  chiusa tre settimane dopo il ritardo non si vedeva da nessuna parte — l'etichetta lo dice solo
  per una `single` con scadenza (`completed_late`). Ora `ob_action_history.occurrence_date` porta
  il giorno di calendario, e la riga marca in rosso lo scarto (*24 gg dopo*).
  ⚠️ **La data si legge PRIMA che la RPC sposti `next_occurrence_date`**, dentro le tre
  `ob_action_*`: subito dopo la riga è già sulla volta successiva e quella che si stava chiudendo
  non è più leggibile da nessuna parte. È la stessa ragione per cui `occorrenzaDi()` nella pagina
  la legge prima di chiamare la RPC.
  ⚠️ **Niente backfill sulle righe già in archivio, e NULL su ogni libera ripetizione**: la data
  che le vecchie righe avevano non è più ricostruibile, e riempirla col `timestamp` direbbe che
  sono state tutte puntuali; una libera ripetizione un giorno programmato non ce l'ha per
  costruzione — `start_date` lì è quando è nata. In tutt'e due i casi la pagina mostra un
  trattino: un dato che non c'è si vede, un dato inventato no. È la stessa scelta delle caselle
  vuote di `fnz_income` e delle misure non registrate di Memo.
  ⚠️ **Le righe `terminated` non sono esecuzioni e non si elencano**, come in `esitoDi()`:
  chiudendo una singola la RPC ne scrive due nello stesso istante — l'esito, coi suoi punti, e la
  chiusura, sempre a zero punti e che nessun conto guarda — e mostrarle tutt'e due farebbe sembrare
  chiusa due volte un'azione chiusa una volta sola. Cancellando l'esecuzione se ne va con lei anche
  la sua riga di chiusura (`chiusuraGemella`, riconosciuta dallo stesso istante: i due INSERT
  stanno nella stessa transazione, quindi `now()` è identico), o resterebbe una riga che non si
  vede da nessuna parte e che nessuno può più togliere.
  ✏️ **Un'esecuzione si corregge** (web v1.15.0): giorno e ora eseguiti, esito (solo fra
  completata e in ritardo — un salto non diventa un completamento correggendolo), punti e le
  rilevazioni di quel giorno, che passano comunque da `ob_record_measurement`. È un `update`
  diretto su `ob_action_history`, e sposta anche la gemella `terminated` (stesso istante).
  ↩️ **L'ultima esecuzione si annulla** con la RPC `ob_action_undo`
  (`20261007200000_ob_action_annulla.sql`): la prossima occorrenza torna a `occurrence_date`, lo
  stato a `from_status`, la riga se ne va con la gemella e i promemoria si spostano. Solo
  l'ultima, mai su un workflow né su una riga senza `occurrence_date`; le rilevazioni restano. Se
  l'azione era conclusa le regole dei promemoria erano già cancellate e si rimettono dal form.
  ⚠️ **Cancellare un'esecuzione non riporta indietro l'azione**: i punti spariscono con la riga e
  la barra dell'esecuzione si rifà senza — `ob_objective_progress` conta le azioni che hanno una
  riga `completed`/`completed_late` — ma la **prossima occorrenza resta dov'è**, perché l'ha
  spostata la RPC quando la riga è nata e rifarne il conto all'indietro vorrebbe dire riapplicare
  in ordine tutta la storia. È la stessa scelta della cancellazione di un giro in SOS, e la
  conferma lo dice prima.
- ⚠️ **Un'azione non si fallisce** (v1.8.0): o la si fa, o la si sposta. Il *Fallisci* chiudeva una
  singola per sempre con un malus, e l'unica cosa che serviva davvero — «oggi no» — la fa già il
  salto. Via il pulsante, la funzione `failAction` e il campo *Fallimento* dal form;
  `failure_points` si salva a **zero**, perché un valore che non si può più prendere è meglio a
  zero che scritto e finto. ⚠️ La RPC `ob_action_fail` **resta nel database** e nessuno la chiama:
  toglierla è una modifica di schema, e i punteggi già presi restano nello storico.
  In 📊 Andamento il segmento si chiama **«non riuscite»** e non «fallite» — ci finisce solo chi è
  chiuso senza esserci riusciti, per esempio una multipla arrivata in fondo alle sue date — e
  compare solo se ce n'è almeno una.
- **🔔 Promemoria** nel form di un'azione: Telegram, Telefono e Smart Block, come in Piante (vedi
  *Le azioni — tabelle proprie*). Sulla scheda i canali accesi si leggono come 📱📲🔐.
- **I punti di partenza di un'azione**: successo **+10**, salto **−2**, in ritardo **−2**. Il
  ritardo valeva +3 (un premio ridotto per averla fatta comunque) ed è diventato una penalità come
  il salto. I punti restano dentro l'app: Obiettivi ha `cm_apps.conta_punti = false`.
- ⚠️ **Il tipo di un'azione che esiste già non si cambia** (la tendina è bloccata in modifica):
  decide quali colonne quell'azione ha, e cambiandolo resterebbero dietro quelle del tipo di prima
  — le date multiple su una ricorrente — che nessuno ripulisce. È la stessa scelta di `TaskForm`
  nel nativo.
- ⚠️ **I giorni della settimana si mostrano da lunedì ma si salvano con la numerazione `extract(dow)`
  di Postgres** (0 = domenica), che è quella che `ob_action_next_recurring_date` confronta. Toccando
  una delle due parti senza l'altra le ricorrenze scatterebbero il giorno sbagliato, senza nessun
  errore.
- ⚠️ **Le date si scrivono e si rileggono in ora locale.** `localDay()` passa da `Date` invece di
  tagliare la stringa ISO: `slice(0,10)` darebbe il giorno UTC, e un'azione di mezzanotte finirebbe
  nel giorno prima. In salvataggio `localToISO()` fa il giro inverso, così l'ora scritta è quella
  che si rilegge.
- ⚠️ **Scrivendo il nome di uno step si aggiornano le pillole che lo citano, senza ridisegnare
  l'elenco**: il ridisegno sostituisce i campi di testo e chi sta scrivendo si vede sparire il
  cursore da sotto le dita.
- **Un'azione si collega alle metriche che dovrebbe muovere**, anche più d'una: si spuntano nel
  form fra quelle dell'obiettivo, si vedono sulla scheda dell'azione (📈 col bordo, per non
  confonderle con le categorie) e sotto ogni metrica nel dettaglio dell'obiettivo, e l'elenco
  azioni si filtra per metrica.
- **Completando un'azione con successo si apre la finestra delle rilevazioni** (`chiediRilevazioni`),
  una riga per metrica collegata — slider per un'autovalutazione, casella per un'automisurazione —
  con la descrizione della metrica sopra, una data e una nota per tutte. È il momento in cui il
  numero lo si sa. Tre regole che sono la funzionalità:
  - ⚠️ **si apre solo al successo** (`completed` / `completed_late`, e alla chiusura di un
    workflow): dopo un fallimento o un salto non è il momento di chiedere un numero;
  - ⚠️ **si apre DOPO che la RPC ha chiuso l'azione**, non prima: il ciclo di vita non deve
    dipendere dal fatto che uno si ricordi il numero. Chiudendo la finestra senza registrare
    niente, l'azione resta completata lo stesso;
  - ⚠️ **una misura lasciata su «non adesso» non si registra**, e non si registra come zero — è la
    stessa scelta delle misure di un diario in Memo e delle caselle vuote di `fnz_income`. La riga
    resta visibile ma spenta, così si vede che c'era;
  - ⚠️ **la data non si sceglie ed è la _data di occorrenza_**, non il giorno del clic: la
    rilevazione appartiene alla volta che si sta chiudendo. Si legge da `next_occurrence_date`
    **prima** di chiamare la RPC (`occorrenzaDi`), perché subito dopo la riga è già stata spostata
    sulla volta successiva; una `free_repeat` non ha un'occorrenza e allora vale oggi.
    ⚠️ Su un'azione scaduta occorrenza e oggi sono giorni diversi, e datare tutto a oggi vorrebbe
    dire che **due occorrenze arretrate chiuse nello stesso pomeriggio si sovrascrivono a
    vicenda** — l'unicità è `(metric_id, measured_on)`.
  Ogni metrica passa da `ob_record_measurement`, una chiamata per metrica; se una fallisce le altre
  restano registrate e la finestra resta aperta dicendo quali non sono passate. L'unicità
  `(metric_id, measured_on)` fa sì che registrare due volte lo stesso giorno **corregga** invece di
  aggiungere.
- In ⚙️ Impostazioni c'è una **zona pericolosa** che svuota Obiettivi: le sole sette tabelle
  `ob_*`, cancellate **dal figlio al padre** (`TABELLE_DA_SVUOTARE`). ⚠️ Categorie e priorità
  restano fuori di proposito: `cm_categories` e `cm_priorities` sono **condivise con Tasks**, e
  cancellarle da qui lascerebbe i task senza — un danno in un'app che non si sta nemmeno
  guardando. Si fa scrivere `CANCELLA` invece di un `confirm()` con l'OK a portata di clic, come
  in `spese-personali.html`, e la finestra dice **quante righe** stai per perdere e offre
  l'esportazione prima. ⚠️ Ogni DELETE porta il filtro `user_id`, ridondante rispetto alla RLS e
  voluto: una DELETE senza condizione è una riga che, il giorno che una policy cambia, cancella
  più di quel che dice. Le cascate basterebbero partendo da `ob_objectives`, ma una riga rimasta
  orfana resterebbe lì senza che nessuno la veda più.

#### ⚠️ I caratteri di sistema grandi: il difetto che si vedeva solo sul telefono

Il 28 agosto 2026 il Dettaglio si apriva **tagliato a sinistra** sul telefono, e da PC non si
riproduceva. La causa erano due cose che si sommavano:

1. `.section-title` è un flex `space-between` con l'etichetta e il suo pulsante: coi caratteri
   ingranditi non ci stavano su una riga, e il pulsante sbordava. **`flex-wrap: wrap`** — «una riga
   sola è un'ipotesi, non un dato»;
2. `.modal-content` aveva `overflow-y: auto` e nessun `overflow-x`. ⚠️ Per specifica CSS, con uno
   dei due su `visible` e l'altro no, il primo **si calcola `auto`**: bastava un pulsante largo e
   l'intera finestra diventava scorrevole di lato. Ora `overflow-x: hidden` è scritto, e quel che è
   davvero largo (le tabelle dell'Andamento) scorre **dentro il proprio riquadro** (`.trend-scroll`).

Da lì la passata su tutto il resto, verificata a 220 % di carattere su 360 px: `min-width: 0` sul
titolo del modale e `flex-shrink: 0` sulla ✕ (il titolo lungo la spingeva fuori); `.form-row` con
**`minmax(9rem, 1fr)` invece di `1fr 1fr`** — la soglia in `rem` cresce col testo, quindi le due
colonne diventano una sola da sé, senza una media query che guarda lo schermo invece del testo;
`flex-wrap` sulle righe degli step e delle misure; e **`overflow-wrap: anywhere` su `body`**,
perché a quella dimensione una parola sola può essere più larga del riquadro — spezzarla è meglio
che tagliarla, e a carattere normale non cambia niente.

#### I grafici dell'Andamento

Sono **SVG scritti a mano**: la pagina non carica nessuna libreria di grafici, e per due spezzate e
una barra non vale mezzo megabyte dal CDN — è la stessa scelta delle miniature di Memo.

⚠️ **L'asse verticale di una metrica è la sua scala** (`da → a`), non il minimo e il massimo
osservati: così l'altezza della curva si legge come «quanto manca», e due rilevazioni vicine non
sembrano un terremoto. Se un valore esce dalla scala l'asse si allarga per contenerlo, invece di
tagliarlo. Ne discende che il traguardo di norma **coincide col bordo**: la riga dell'obiettivo si
disegna solo quando cade *dentro* il grafico, altrimenti ripeterebbe l'etichetta dell'asse
sovrapponendocisi — al suo posto la parola «obiettivo» marca quale dei due estremi è la meta.

⚠️ **Gli spessori sono in pixel veri** (`vector-effect="non-scaling-stroke"`): il viewBox scala col
riquadro, e senza quello la linea sarebbe sottile sul telefono e grassa sul PC.

⚠️ **I tre colori delle azioni sono colori di stato, non di categoria**: non si riusano per «la
serie 4» e vanno sempre con l'etichetta accanto, mai il colore da solo. Il verde
(`#00967A`) è un passo più scuro di `--success` perché a 2,5:1 sul bianco la barra non si
distingueva dal fondo; il giallo invece resta quello della pagina — scurendolo **collassa sul
rosso** (ΔE 12 contro i 18 che servono). Le azioni ancora aperte non prendono un colore: sono il
**fondo** della barra, cioè quel che resta da fare. La legenda porta i conti a parole e sotto c'è
la tabella per tipo, che è anche il rimedio dovuto al giallo, sotto il rapporto di contrasto 3:1.

- **La barra «Esecuzione» conta anche le azioni** (`ob_objective_progress`):
  `(sotto-obiettivi raggiunti + milestone centrate + azioni riuscite) / totale`. ⚠️ Entrano le
  sole azioni che possono **finire** (`single`, `multiple`, `workflow`): una ricorrente e una a
  libera ripetizione non finiscono mai, al denominatore resterebbero per sempre tenendo
  l'esecuzione sotto il 100 % a piano concluso — e sarebbero un rimprovero per un'abitudine che
  sta funzionando. ⚠️ Al numeratore ci va l'azione **riuscita**, non quella chiusa: `terminated`
  lo diventa anche fallendo, e un piano fallito che riempie la barra direbbe il contrario di quel
  che è successo.

### `weight-quest.html` — Peso e Calorie

⚠️ **Dal 9 settembre 2026 questo file è DUE app**: «Ti pisasti?» (il peso) e il diario
alimentare, che stava in `calorie.html`. Erano due pagine che parlavano dello stesso obiettivo —
la seconda leggeva `ps_objectives` scritto dalla prima — e la formula che interpola il peso di
piano fra due traguardi esisteva in due copie, con l'avviso di cambiarle insieme. Adesso ce n'è
una.

| | com'era | com'è |
|---|---|---|
| file | `weight-quest.html` + `calorie.html` | `weight-quest.html`; `calorie.html` è il rimando |
| viste | 3 + 4, due barre, due router | **7**, una barra sola, `navigate()` |
| versione | v3.7.3 + v1.18.0 | **v4.0.0**, una |
| peso di piano | `getInterpolatedTarget()` + `pesoPianoAl()` | `pesoPianoAl(giorno, traguardi)` |
| toast | `.wq-toast` + `#toast` | `showToast(msg, durata)` |
| sessione | due letture di `sb_token`, due `BroadcastChannel` | una, in `showMain()` |
| Supabase | `CONFIG` con `_IS_DEV` + due costanti fisse sulla produzione | `CONFIG`, per tutt'e due |

⚠️ **Il nome del file NON è cambiato**, e non è pigrizia: lo nominano `cm_apps.html_file`,
`PortedApps.kt` dell'APK nativa (che su quella chiave decide quale schermata aprire),
`manifest.json` e i segnalibri. Rinominarlo avrebbe voluto dire toccare tutti quelli **e** i
telefoni già installati, per un guadagno di sola forma.

⚠️ **I due `<script>` restano due e in quest'ordine**: prima la metà «peso», che crea il client
Supabase e legge il token, poi la metà «calorie», che da `showMain()` viene avviata. Al
contrario, la seconda chiederebbe una sessione che nessuno ha ancora letto. In fondo al secondo
c'è `avvia()`, l'unico avvio della pagina.

⚠️ **Anche il CSS resta in due blocchi.** Il primo è lo shell (barra, viste, form, tabelle) e
governa la pagina; il secondo è quel che «peso» si portava dietro, **non riscritto** — rifarne
l'aspetto sarebbe stata un'altra modifica mescolata a questa, e nessuna delle due si sarebbe
potuta verificare da sola. Le tre viste che vengono di là portano la classe `.wq`, che tiene il
carattere di sistema su cui erano state disegnate.

⚠️ **I tre nomi di classe che si scontravano sono stati RINOMINATI, non scopati sotto un
antenato**: `.card`, `.btn`, `.btn-primary` esistevano in tutt'e due e da «peso» sono diventati
`.wq-card`, `.wq-btn`, `.wq-btn-primary` (più `.wq-btn-secondary`, per non lasciare un prefisso a
metà). Scrivendo `.wq .card` sarebbero passate le proprietà che «peso» non riscrive — `display:
inline-flex`, `gap`, `border-radius` — quindi un pulsante largo tutto sarebbe diventato
inline-flex col testo spostato a sinistra: un difetto che si vede su qualche pulsante e che
nessun controllo automatico segnala.

⚠️ **Le sette viste hanno un nome leggibile dall'ancora** (`#diario`, `#peso`, …), letto una
volta all'avvio da `vistaDallAncora()`. Serve a `calorie.html`, che rimanda a
`weight-quest.html#diario` — chi apriva «Calorie» voleva segnare quel che ha mangiato, non
pesarsi. ⚠️ L'ancora **non si riscrive** a ogni `navigate()`: scrivendola, ogni cambio di scheda
lascerebbe una voce in cronologia e l'indietro di Android girerebbe fra le sette viste invece di
uscire dalla pagina — che è quel che `guardiaIndietroPopup` dà per scontato.

⚠️ **Nella barra blu c'è l'interruttore ⚖️ Peso ⇄ 🍽️ Calorie** (v4.11.1, prima stava in testa
alla barra di icone): decide quali icone mostra la barra sotto. In modo Peso
⚖️ Peso, 📉 Statistiche e le sincronizzazioni; in modo Calorie 🥗 Dieta, 📊, 📓 e 🍎; ⚙️
Impostazioni sta in tutt'e due. ⚠️ Il modo **si deduce dalla vista aperta** (`modoDi()` in
`navigate()`) e non si archivia: un collegamento o l'ancora `#diario` portano con sé il modo
giusto. Filtra la sola barra di icone del telefono: la sidebar del desktop resta com'è.

⚠️ **Le icone della navigazione sono a tratto, non emoji** (v4.11.2): un `<svg>` di simboli
(`#i-peso` è una bilancia pesapersone, non a piatti; `#i-dieta`, …) in cima al body, usato con `<use>` sia nella barra del telefono sia
nella sidebar — un disegno solo per voce, che le due non possono far divergere. Sono in `em`,
quindi crescono coi caratteri di sistema, e prendono il colore della voce (`currentColor`):
grigie a riposo, bianche sulla voce attiva. Anche la voce Sincronizza (Renpho, prima «Salute») usa le due frecce `#i-sync` invece del logo Renpho (v4.11.4; rinominata nella v4.11.5).

⚠️ **Si apre su ⚖️ Peso e non sulla dashboard delle calorie**: l'app si apre col telefono in mano
appena scesi dalla bilancia. Le quattro viste del diario **non si disegnano finché i loro dati non
sono arrivati** (`S.pronto`): i due caricamenti corrono in parallelo, e `renderDiario()` su un
profilo ancora nullo non darebbe una pagina a metà, darebbe un'eccezione — quindi si dice che si
sta aspettando.

⚠️ **Il profilo incompleto ora si DICE e basta.** Fino all'unione il diario saltava d'ufficio su
Impostazioni quando mancavano data di nascita, altezza o sesso: aveva senso in una pagina che era
solo il diario, non in una che si apre sulla pesata. Resta il toast, che dice dove si compila.

⚠️ **La guardia dell'indietro adesso copre anche i sette popup della metà «peso»**, che non ne
aveva affatto: là dentro l'indietro di Android usciva dalla pagina. Riconoscere «aperto» dallo
stile calcolato invece che dalla classe serve proprio a questo — quelli si aprono scrivendo
`style.display`, quelli del diario togliendo una classe.

⚠️ **Chart.js sta nel `<head>`** (lo vuole subito la vista che si apre per prima) e
`ensureChartLibs()` lo **salta se `Chart` c'è già**, caricando solo Hammer e il plugin zoom: due
copie della libreria vorrebbero dire che il plugin si registra sull'una e i grafici nascono
sull'altra.

- Chart.js weight graph centred on today (30-day window, scrollable)
- Google Fit integration via OAuth token
- Minimal inline Supabase client (no CDN); milestone and objective tracking
- **Premio cibo alle soglie di peso**: ogni stellina accesa (`.ms-th.reached`) dà, oltre ai punti,
  un premio da scoprire **grattando un gratta e vinci** — 🍰 Torta Savoia, 🥐 Cannolo, 🍕 Pizza,
  🍫 Tavoletta di cioccolata, 🍪 Tazza di biscotti, fotografie in `risorse/premi/*.jpg`
  (quadrate, 460 px). Sotto la patina d'argento (un `<canvas>` disegnato da `buildScratchFoil()`
  e consumato in `destination-out`) c'è **già** la foto del premio: il sorteggio avviene
  all'apertura del biglietto, grattare scopre e basta. Il canvas si ridimensiona **ad ogni
  apertura e solo a overlay visibile** — prima il biglietto è 0×0 e la patina verrebbe stesa su
  niente. Si scopre da sé oltre il 55 % grattato (42 % se si alza il dito), e il pulsante
  *👆 Scopri tutto* fa la stessa cosa: serve da scorciatoia e da ripiego se il tocco non passa.
  Finché non la si tocca la stellina resta **evidenziata in
  rosso col 🎁 che pulsa**; dopo l'estrazione mostra l'emoji del premio vinto e ritoccandola lo
  rimostra già scoperto. Il biglietto di prova non ha più un pulsante: la modalità resta in
  `openPrizeDraw(null, true)`, richiamabile dalla console, e non salva niente.
  I premi stanno su Supabase (`ps_milestone_prizes`), come i punti dei traguardi
  (`ps_milestone_points`), e `localStorage` (`wq_prizes_<id>`, `wq_mpts_<id>`) è rimasto **solo
  cache**, perché la barra compaia subito senza aspettare la rete: il premio è quindi lo stesso
  da ogni dispositivo e dall'app nativa. Le righe che stavano solo in locale salgono sul DB al
  primo caricamento (`fetchPrizes`).
- ⚠️ **I punti dei traguardi si incassano alla chiusura, e solo col successo**: al
  `total_score` si somma la distribuzione delle **soglie raggiunte** — raggiunte, non
  grattate: dimenticarsi di toccare una stellina non deve costare punti — mentre su un
  obiettivo chiuso come fallito valgono zero. Prima di questa regola i «+N» sotto le
  stelline non entravano in nessun totale: erano una promessa scritta a schermo e mai
  incassata. Il conto sta nella RPC `ps_chiudi_obiettivo` (vedi *Punti e chiusura sono UNA
  regola*), e la conferma di chiusura mostra i tre addendi separati.
- **Le caselle «Punteggio» e «Punti oggi» si cliccano** e aprono 📜 *Cronologia dei punti*,
  **una per casella e mai mescolate**: il «Punteggio» elenca i soli obiettivi **chiusi**
  (nome, periodo, esito, punteggio), i «Punti oggi» il solo obiettivo **aperto** —
  traguardi raggiunti, quanto varrebbe chiuderlo oggi da vincitore o da perdente, e il
  giorno per giorno con peso, target, punti e totale progressivo. Mescolarli vorrebbe dire
  far cercare il proprio numero fra righe che non c'entrano. Non ricalcola niente per conto
  suo — legge `buildScoreRows()` e `milestoneAwards()`, gli stessi due conti dei badge e
  della chiusura.
- ⚠️ **La casella «Punti oggi» somma i giornalieri e i traguardi già raggiunti**
  (`puntiOggiMostrati` nel nativo): è quel che si porta a casa chiudendo bene, al netto del
  bonus finale. La **chiusura non legge quel numero** — la RPC somma i due addendi per conto
  suo, o i traguardi conterebbero due volte.
- **Dopo il gratta si dice se il premio è stato mangiato** (`🍽️ Mangiato !!!`, che scrive
  `consumed_on`): il premio resta vinto — la stellina non si spegne — ma il cibo sbiadisce col ✓ e
  il biglietto scrive quando. È **reversibile** (`↺ Non l'ho ancora mangiato`), perché un tocco
  per sbaglio non deve costare un cannolo. ⚠️ Stesso pulsante nell'app nativa, sulla stessa riga
  del database.

### `calorie.html` — la pagina di rimando

⚠️ **Non è più un'app**: dal 9 settembre 2026 il diario alimentare vive dentro
`weight-quest.html` (vedi la sezione qui sopra) e questo file è il rimando a
`weight-quest.html#diario`. Tutto quel che si legge qui sotto descrive le **quattro viste del
diario dentro l'app unita**, non un file a sé.

⚠️ **Il file resta, e non è una dimenticanza**: lo nominano la riga di `cm_apps` che disegna la
bolla, `PortedApps.kt` dell'APK nativa, i collegamenti scritti in `index.html` e in `memo.html`,
e i segnalibri. Cancellandolo finirebbero tutti su un 404, e la bolla sparirebbe senza dire
perché. Il rimando usa `location.replace` e non `href` — con `href` l'indietro tornerebbe qui e
il rimando ripartirebbe da capo, cioè un indietro che non torna indietro — e porta con sé
l'ancora, con `#diario` come ripiego.

- Il **diario alimentare** e il target di calorie che ne discende. È la vista 📓 **Diario**
  dell'app unita, insieme a 📊 Dashboard, 🍎 Alimenti e ⚙️ Impostazioni.
- **Non decide niente sull'obiettivo**: quello sta in «Ti pisasti?» e qui si legge — nessun numero
  da riscrivere di qua, i traguardi si spostano di là. Senza obiettivo attivo il target è il
  semplice **mantenimento**, e la pagina lo dice invece di far finta che ci sia un piano.
- **Il riquadro spiega sempre da dove esce il target**, per intero (basale × attività − deficit,
  coi chili che mancano e i giorni che restano): un numero calcolato da quattro grandezze che
  nessuno vede è un numero di cui non ci si fida, e alla prima sorpresa si smette di seguirlo.
- **La navigazione è una barra di icone, come in «Ti pisasti?»**, non un menù ☰. Sul telefono le
  quattro pagine si vedono tutte insieme e ci si sposta con **un tocco solo** invece di tre (apri
  il cassetto, scegli, il cassetto si chiude); sopra i 768 px la barra sparisce e resta la
  sidebar, dove le voci hanno il posto per il loro nome. La voce accesa si aggiorna in tutt'e due
  da `navigate()`, con un selettore solo: chi disegna non deve sapere quale delle due è a schermo.
  ⚠️ La barra **scorre di lato e non stringe le icone**: coi caratteri di sistema grandi le voci
  su 360 px ci stanno appena, e schiacciarle sotto il polpastrello le renderebbe non toccabili — è
  la stessa regola delle righe di pulsanti che non vanno mai a capo.
  ⚠️ **Barra e sidebar elencano le sole quattro pagine di Calorie** (v1.17.2): i due collegamenti
  in coda — ⚖️ Ti pisasti?, 🏠 AppSphere, col loro separatore — sono stati tolti da tutt'e due.
  Erano navigazione che porta *fuori* mescolata a quella che porta *dentro*, e il 🏠 ripeteva
  quello che la barra blu, che sta sopra ed è in ogni app, fa già. A «Ti pisasti?» si va da
  AppSphere o dai collegamenti scritti dove si parla dell'obiettivo — che è il punto in cui serve
  andarci. Rimettendone uno, va rimesso in tutt'e due: sono la stessa navigazione, mostrata in due
  modi a seconda della larghezza.
  ⚠️ `min-width` è in `em`, quindi si misura sul font dell'**icona** e non su quello della pagina:
  a 3.2em faceva 74 px a voce e la barra scorreva già a carattere normale. Resta in `em` di
  proposito — il bersaglio da toccare deve crescere col testo di sistema — ma il valore va provato
  a 360 px, non calcolato a mente.
- Quattro schede: 📊 **Dashboard** (la prima che si apre), 📓 **Diario** (giorno per giorno, coi
  pasti configurati), 🍎 **Alimenti**, ⚙️ **Impostazioni** (dati anagrafici, attività, pasti, stima
  delle calorie, obiettivo). ⚠️ **📈 Andamento non esiste più**: il suo contenuto — i quattro KPI,
  le colonne delle calorie contro la linea del target, il peso con la curva del piano e il giorno
  per giorno — è dentro la Dashboard.
- La **📊 Dashboard** è in cinque sezioni, nell'ordine delle domande che ci si fa aprendo l'app:
  ⚖️ **le pesate** (peso minimo di oggi, peso che il piano chiede oggi, quanto manca al peso
  finale), 🔥 **le calorie di oggi** (target, mangiate, restano), 📐 **le calorie per tratto**,
  📈 **come sta andando**, e il **giorno per giorno**.
  ⚠️ **Tutto qui dentro copre l'arco della dieta, non una finestra di N giorni**: il selettore
  «ultimi 30 giorni» dell'Andamento è sparito perché il periodo *è* il piano, e due archi diversi
  sulla stessa pagina sarebbero due risposte diverse alla stessa domanda. `arcoDellaDieta()` tira
  però il primo giorno in avanti fino alla finestra di caricamento, per la stessa ragione del
  fondo del diario, e quando lo fa la pagina lo dice: una riga «niente segnato» per un giorno di
  cui non si sono lette le righe è una bugia.
- ⚠️ **Il giorno per giorno arriva fino alla FINE del piano, e i giorni futuri hanno un target
  previsto**: si parte da quello del **tratto** (`targetTrattoAl`, il valore «se stai sul piano»,
  senza recupero) e ci si porta dietro lo **scarto del giorno prima** — sforato ieri, oggi ce n'è
  di meno; rimasto sotto, oggi ce n'è di più. Il riporto si mostra fra parentesi accanto al
  numero. ⚠️ Il saldo accumulato si **spalma su TUTTI i giorni che restano** fino alla fine del
  piano, e ogni giorno futuro prende la stessa fetta — che quindi non si ricalcola giorno per
  giorno, così il numero si controlla a mente («900 in 80 giorni, undici al giorno»). Fino alla
  v1.12.1 si scaricava tutto sul giorno dopo: uno sforo di 900 kcal faceva scendere il giorno
  seguente a 288 kcal, cioè un target che non si può seguire — e un target che non si può seguire
  non lo si segue. Un giorno **senza righe non entra nel saldo** — non è un digiuno, è un giorno
  non segnato — ed è la stessa regola delle colonne mancanti nel grafico.
  ⚠️ **Da OGGI entra nel saldo solo lo sforo, mai il risparmio.** Oggi è l'unico giorno chiuso a
  metà: alle sette di sera il diario non è finito, e leggere quel che non è ancora stato segnato
  come un risparmio regalava calorie che nessuno si era guadagnato — un picco visibile nel
  grafico, corretto in v1.12.1. Lo sforo invece è già successo e non si disfa. È la stessa regola
  del «non lo so ≠ zero» che governa tutta la pagina: un diario a metà non è un digiuno.
  ⚠️ **Il saldo del KPI e quello che si spalma sono lo STESSO numero**, calcolato una volta sola
  in `giorniDellaDieta()` e restituito con la fetta al giorno: due conti separati sarebbero due
  verità sullo stesso dato — «−932 in meno» in alto e una fetta che non ci corrisponde sotto — e
  chi legge non saprebbe quale credere.
  ⚠️ Nel futuro il **peso non si trascina**: `pesoAl()` ripiegherebbe sull'ultima pesata nota, e
  una linea piatta fino alla fine del piano sembrerebbe una previsione che nessuno ha fatto.
- ⚠️ **La tabella dei tratti vive in `tabellaTratti()`, un posto solo**, perché la disegnano due
  schermate (Dashboard e Impostazioni): due copie sarebbero due tabelle che divergono il giorno
  che una colonna cambia — lo stesso difetto che la vista Spese Famiglia aveva quando stava copiata in due pagine.
- ⚠️ **`drawChart()` sopravvive a Chart.js che non arriva**: la libreria viene dal CDN, e prima
  l'eccezione partiva e basta. Era sopportabile con i grafici in una pagina secondaria; ora sono
  nella Dashboard, cioè la prima cosa che si apre, e un'eccezione lì fermerebbe il disegno di
  tutto quel che viene dopo. Al posto del grafico si scrive perché manca: i numeri stanno già
  tutti nelle tabelle, quindi senza la libreria si perde la forma, non il dato.
- ⚠️ **Il diario non va più indietro del primo giorno della dieta** (`primoGiornoDiario()`): il ‹
  si spegne, l'input date porta il `min`, e la stretta è ripetuta nei gestori perché il pulsante
  spento è un suggerimento e da tastiera ci si arriva lo stesso. Prima il ‹ scendeva all'infinito
  e **dal 121° giorno indietro ogni giornata compariva vuota** — non perché lo fosse, ma perché
  `GIORNI_STORICO` non le aveva caricate: un archivio che sembra svuotarsi da solo è peggio di un
  pulsante spento. Il fondo è `max(inizio dell'obiettivo attivo, finestra di caricamento)`: il
  `max` garantisce che non si finisca mai su un giorno di cui non si hanno le righe, e con una
  dieta più corta di `GIORNI_STORICO` — il caso normale — non morde mai. Senza obiettivo attivo
  resta la sola finestra, che è tutto quel che si sa.
- **📋 Ricopia da ieri** porta nel giorno aperto tutte le righe del giorno prima, e **compare solo
  se ieri ha davvero qualcosa** (col conteggio sul pulsante): uno che c'è sempre e quasi mai fa
  qualcosa si smette di guardarlo. Resta offerto anche se oggi ha già delle righe — mangiare due
  volte la stessa cosa capita — ma allora **aggiunge**, e la conferma lo dice invece di lasciarlo
  scoprire dal totale. ⚠️ Si copiano i valori **congelati sulla riga di partenza**, non quelli di
  `al_foods` di adesso: la riga è il dato, l'alimento è solo da dove veniva — così la copia
  funziona anche per un alimento cancellato nel frattempo (`food_id` già NULL, valori sulla riga)
  e non cambia di nascosto perché qualcuno ha corretto una scheda. `times_used` non si tocca: è
  il contatore che ordina i «più usati», e un tocco che lo alza di cinque lo farebbe diventare la
  classifica di chi ricopia invece di chi mangia.
- ⚠️ **I riquadri dei pasti ci sono in ogni giorno, anche vuoti e anche nel passato.** Fino alla
  v1.9.1 un pasto senza righe si nascondeva nei giorni passati, per non fare rumore: ma il
  riquadro non è solo un riepilogo, porta il ➕ con cui si aggiunge a *quel* pasto. In un giorno
  passato ancora tutto da segnare — cioè proprio il caso in cui un giorno passato si apre — non
  ne restava nemmeno uno e la pagina finiva dopo la scritta «I pasti», che si legge come «qui non
  si può scrivere». Segnare ieri quel che si è mangiato ieri è il caso normale, non quello strano.
- **Quanti pasti al giorno lo decide l'utente** (⚙️ Impostazioni → 🍽️ I pasti della giornata):
  si spuntano i momenti in cui si mangia davvero e si rinominano. La configurazione sta in
  `cm_settings`, chiave `al_pasti`, come JSON — così è la stessa dal PC e dal telefono, dove
  `localStorage` sarebbe di quel browser e basta. ⚠️ **Gli id dei sei momenti sono fissi**
  (`colazione`, `spuntino_mattina`, `pranzo`, `spuntino_pomeriggio`, `cena`, `fuori_pasto`):
  `al_log.meal` ha un CHECK con esattamente quei valori, quindi si configura *quali* dei sei si
  usano e *come* si chiamano, non l'insieme dei valori possibili — per un settimo serve una
  migration. ⚠️ **Togliere un pasto non cancella niente**: le righe già segnate lì dentro restano,
  contano nel totale della giornata e si vedono marcate «pasto tolto» (senza il ➕), ed è la stessa
  regola della *misura tolta* nei diari di Memo — sparire dallo schermo restando nel conto sarebbe
  il modo peggiore di nasconderle. Il pasto già scelto compare sempre nella tendina anche se
  spento, o riaprire una riga vecchia la sposterebbe di pasto al primo salvataggio.
- Gli alimenti arrivano da tre parti, in **una tabella sola**: le voci generiche di partenza
  (`'base'`, seminate dalla migration), i prodotti confezionati letti da **Open Food Facts** col
  codice a barre o cercandoli per nome, e quelli scritti a mano. È lo stesso alimento visto da tre
  parti: tre tabelle vorrebbero dire tre ricerche per rispondere a «quante calorie ha».
- ⚠️ **La pagina non parla più con le banche dati: passa tutto dalla Edge Function
  `al-food-search`.** Quattro cose che dal browser non si potevano avere — CORS smette di essere
  un problema (quindi si possono usare servizi che gli header giusti non li mandano, e quali li
  mandino non lo decidiamo noi), le chiavi stanno nei Secrets invece che in chiaro nell'HTML, più
  fonti restituiscono **una forma sola**, e ogni fonte torna col suo esito. ⚠️ **La
  normalizzazione vive SOLO nella Edge Function**: la pagina non ha più una copia di `daOFF()` —
  due implementazioni della stessa conversione sono due valori diversi per lo stesso prodotto il
  giorno che una delle due cambia.
- ⚠️ **La ricerca per nome e la lettura di un codice a barre passano da due servizi diversi**, e
  si rompono una per volta:

  | Cosa | Dove | Stato |
  |---|---|---|
  | Prodotto per codice a barre | `it.openfoodfacts.org/api/v2/product/<code>.json` | in servizio |
  | Ricerca per nome | `search.openfoodfacts.org/search` (Search-a-licious) | in servizio |
  | Ricerca per nome, vecchia | `/cgi/search.pl` | ⚠️ **deprecata, 503 globale da aprile 2026** |

  Fino alla v1.3.0 la ricerca per nome passava dalla vecchia, ed è la ragione per cui non
  trovava mai niente. Ora si prova Search-a-licious e **solo se non risponde** si ripiega sulla
  vecchia: costa una richiesta in più e solo quando la prima è già fallita, e serve a coprire il
  caso in cui sia il servizio nuovo a essere giù.
- ⚠️ **La forma della risposta non si dà per scontata**: Search-a-licious risponde con `hits`
  (è Elasticsearch sotto), l'API vecchia con `products` — `prodottiDaRisposta()` accetta tutt'e
  due più l'array nudo, perché un cambio di chiave deve dare un errore, non «nessun risultato».
  Per la stessa ragione i campi di testo passano da `testoOff()`: su Search-a-licious
  `product_name` è indicizzato **per lingua** e arriva come oggetto (`{it, en, main}`), dall'API
  dei prodotti come stringa.
- Una stringa di **sole cifre lunga come un codice a barre** non viene cercata come testo: si
  legge il prodotto. Copre l'incollare un codice nel campo di ricerca.
- In ⚙️ Impostazioni c'è **🔌 Banche dati alimenti → Prova la connessione**. ⚠️ Le prove le fa la
  **Edge Function**, non il browser: è lei a parlare coi servizi, quindi è il suo esito che conta —
  provare dal browser direbbe se il browser ci arriva, che non è più la domanda. Un servizio
  esterno che smette di rispondere si vede altrimenti solo come «non trova niente», che è
  indistinguibile da «quel prodotto non c'è».
- Da 🍎 Alimenti si può **importare una tabella di composizione** (📄 Importa una tabella): un
  foglio .xlsx/.csv entra nel catalogo come alimenti `'base'`. ⚠️ Le colonne si riconoscono dalle
  **intestazioni** e non dalla posizione — un foglio di composizione ha decine di colonne in un
  ordine che cambia da edizione a edizione, e leggere «la terza» importa il fosforo al posto dei
  grassi senza che nessuno se ne accorga; l'anteprima dice quali colonne ha usato e quali no.
  ⚠️ Le **parentesi dell'intestazione non si buttano via**: «Energia (kcal)» e «Energia (kJ)» si
  distinguono solo per quelle, e togliendole l'energia veniva letta in kJ come se fossero kcal —
  un alimento con quattro volte le calorie che ha. ⚠️ In un CSV il **separatore si riconosce
  contando** (`;` nelle tabelle italiane, dove la virgola è il decimale): dando per scontata la
  virgola ogni riga finisce in una cella sola e le colonne non si riconoscono più. E «tr»
  (tracce), «-» e «n.d.» tornano **null e non zero**, come ogni casella vuota di questa app.
- ⚠️ **Open Food Facts limita le ricerche testuali a una decina al minuto** (la lettura di un
  singolo prodotto molto meno): cercare a ogni tasto premuto fa **bandire l'indirizzo IP**, quindi
  la chiamata parte solo dopo una pausa di digitazione (`RITARDO_RICERCA`). Da browser lo
  `User-Agent` non si può impostare e OFF chiede di identificarsi: si usano `app_name` /
  `app_version` / `app_uuid` in query string, che è il sostituto previsto apposta.
- ⚠️ **Le calorie non sono sempre dove ci si aspetta**: `energy-kcal_100g` c'è quasi sempre, ma
  dove l'etichetta è stata inserita in kJ si converte, e `energy_100g` senza suffisso è ambiguo —
  si guarda `energy_unit` invece di dare per scontato che siano kJ. Un prodotto senza nessuna delle
  tre **non è un prodotto a zero calorie**: torna `null` e la pagina lo dice.
- **Quel che il catalogo non ha, la pagina va a prenderlo.** Dal 📓 Diario si cerca un alimento e,
  passata la pausa di digitazione, ai risultati locali si aggiungono quelli di Open Food Facts;
  scegliendone uno il prodotto entra in `al_foods` **insieme alla riga del diario**, non appena lo
  si apre — aprire un risultato per sbaglio riempirebbe il catalogo di prodotti mai mangiati. Da
  🍎 **Alimenti** la stessa ricerca ha invece un *➕ Aggiungi* che archivia e basta: lì si mette in
  dispensa prima di mangiarlo. Aggiungendone uno gli altri risultati **restano** — da una ricerca
  sola se ne prendono spesso due o tre, e azzerarla costerebbe una chiamata in più a OFF, che le
  ricerche le conta.
- ⚠️ **Da dove viene un alimento si vede prima di sceglierlo, e la distinzione che conta è una
  sola**: è già nel tuo catalogo, oppure sta arrivando adesso da una banca dati pubblica? Il primo
  è un dato tuo, già guardato e correggibile; il secondo l'ha scritto qualcun altro, può mancare,
  può essere sbagliato e quando lo scegli entra in archivio. La porta l'**icona in testa alla
  riga** (📗 catalogo / 🌐 in rete), che è dove cade l'occhio per primo, e il **colore del badge**
  la ripete (ambra per la rete, l'unico colore che nessuna fonte interna usa); il badge dice
  invece *quale* fonte, che è l'informazione di secondo livello. Nell'elenco dei risultati le due
  provenienze stanno sotto **due intestazioni appiccicate in cima** (`.search-group`) e non
  concatenate: una fila unica in cui il catalogo sfuma nella rete le fa sembrare la stessa cosa.
  Fino alla v1.5.0 la fonte era una parola in coda alla riga dei valori, dopo le calorie e con lo
  stesso grigio: si leggeva solo andandola a cercare.
  ⚠️ Il nome della fonte di rete si legge da **`a.source`** e non è scritto a mano: `al-food-search`
  risponde `'off'` o `'usda'`, e la stringa fissa «Open Food Facts» attribuiva a Open Food Facts
  anche i risultati USDA — cioè diceva il falso proprio nel punto che esiste per dire da dove
  viene un numero. Tutto passa da `fonteDi()` / `iconaFonte()` / `badgeFonte()`, in un posto solo.
- ⚠️ **`FONTI_SALVABILI` è lo specchio del CHECK su `al_foods.source` e va tenuto uguale**: una
  fonte che il vincolo non ammette non entra in archivio, e l'insert lo rifiuta il database.
  `20260830120100_al_foods_source_usda.sql` ci ha aggiunto `'usda'`, che prima restava fuori — un
  prodotto USDA si poteva mangiare ma non mettere in dispensa, e andava ricercato ogni volta. La
  costante **decide insieme il messaggio e il salvataggio**: la finestra della porzione scrive «lo
  aggiungo al catalogo» solo dove succede davvero, e altrove dice che i valori restano sulla sola
  riga del diario. Due condizioni scritte separatamente sono la finestra che promette una cosa e
  il pulsante che ne fa un'altra, il giorno che una delle due cambia.
  ⚠️ `salvaAlimentoDiRete()` archivia la fonte **com'è arrivata** e non riscritta a `'off'`: fino
  alla v1.7.0 era una costante, e un prodotto USDA finiva in archivio dichiarato Open Food Facts —
  un dato falso proprio nella colonna che esiste per dire da dove viene un numero, e senza nessun
  modo, poi, di sapere quali righe correggere.
- ⚠️ **Nel form dell'alimento si dice se i valori sono per 100 g o per una porzione**, perché
  moltissime etichette li danno per confezione e ricopiarli dividendo a mente è il modo migliore
  per sbagliare: la «Pizza condita» stava in archivio a **1225 kcal per 100 g** — impossibile, il
  grasso puro ne fa 899 — perché quei numeri erano dell'intera pizza, e la riga sembrava una riga
  qualunque. ⚠️ **In `al_foods` i valori restano SEMPRE per 100 g**: la scelta non cambia dove
  finiscono, cambia come si leggono quelli scritti nel form, e la conversione si fa una volta
  sola al salvataggio. Una colonna «unità» sulla riga vorrebbe dire che ogni conto della pagina
  (diario, target, grafico, Edge Function) deve ricordarsi di guardarla, e il giorno che uno se
  ne dimentica il totale è sbagliato senza nessun errore.
  ⚠️ La scelta è una **dichiarazione sui numeri, non una trasformazione**: le caselle restano
  come sono. Per questo sotto c'è l'**anteprima** di quel che finirà in archivio — senza, un
  tocco per sbaglio su «1 porzione» dividerebbe valori già giusti e lo si scoprirebbe settimane
  dopo. E per la stessa ragione riaprendo un alimento si parte **sempre da «100 g»**, che è quel
  che c'è davvero in archivio: ricordare «porzione» sulla riga farebbe dividere una seconda volta
  al primo salvataggio. Senza i grammi della porzione l'opzione è spenta e il salvataggio si
  ferma — non c'è niente per cui dividere. `KCAL_IMPOSSIBILI` (900) è il **tetto fisico**, non un
  vincolo di gusto: sopra quel valore si chiede conferma, perché è un errore di unità e non un
  alimento insolito.
- ⚠️ **La porzione abituale si vede e si preme, non è più un campo precompilato in silenzio.**
  `al_foods.default_grams` (un uovo 55 g, una pizza 300 g, un cucchiaio d'olio 10 g) c'è nel
  catalogo da sempre, e la finestra della porzione ci si apriva sopra senza dirlo: il campo
  arrivava già scritto «55» e niente diceva che quei 55 g sono un uovo. Un numero che compare da
  solo si legge come un valore di ripiego, quindi lo si riscriveva a mano ogni volta — cioè
  esattamente il lavoro che la colonna esiste per togliere. Ora la finestra scrive la porzione e
  offre **½ / 1 / 2** (`porzioniDi()`): sono i tagli che si usano davvero — «due uova», «mezza
  pizza» — e una scaletta di multipli più fitta chiederebbe di leggere invece di far scegliere.
  Col nome della porzione si legge **«1 uovo · 55 g»**, «2 uova · 110 g», «½ pizza · 150 g»:
  singolare e plurale vengono dalle due colonne (vedi lo schema `al_`), e il **½ si scrive col
  simbolo** perché non ha genere — vale per l'uovo come per la pizza senza dover archiviare anche
  quello. Il nome si scrive da 🍎 Alimenti → ✏️ e senza di esso si legge «1 porzione».
  ⚠️ **Ogni pulsante SI SOMMA a quel che c'è nel campo, non lo sostituisce**, e accanto al campo
  c'è un **↺** per ricominciare da zero. Tre uova si segnano toccando «1 uovo» tre volte: non
  esiste un pulsante per ogni quantità che si possa voler mangiare, e sostituendo il secondo
  tocco non faceva niente di visibile — si leggeva come un tocco non passato. Il campo parte
  comunque dalla porzione abituale, che è il caso di gran lunga più frequente e non costa nessun
  tocco. La somma si arrotonda a un decimale: gli scalini sono interi, ma il campo si scrive
  anche a mano (12,5 g d'olio) e senza arrotondare verrebbero fuori le code binarie.
  ⚠️ La **scaletta fissa** (`GRAMMI_RAPIDI`) si toglie i valori che le porzioni già coprono: due
  pulsanti «150 g» uno accanto all'altro sembrano due scelte diverse e non lo sono. Un alimento
  **senza** porzione non se ne inventa una — una porzione inventata chi la legge se la crede, e
  sono le calorie della giornata: si parte da 100 g e la finestra dice dove scriverla.
- ⚠️ **Esiste anche in nativo** (`android-app/appsphere-native/app/.../calorie/`), ma solo per
  📊 Dashboard e 📓 Diario più il ➕ che segna un alimento: 🍎 Alimenti e ⚙️ Impostazioni stanno
  qui e basta, e di là si leggono. Il conto del target lo fanno le RPC `al_*` per tutt'e due —
  dettagli in *Diario alimentare*.
- Il **codice a barre** porta a due posti diversi a seconda di dove si parte (`S.scanPer`): dal
  diario finisce sulla porzione, dal catalogo archivia la voce e basta. La finestra dello scanner
  è la stessa.
- Lo **scanner del codice a barre** usa `BarcodeDetector` dove c'è (Chrome e la WebView di
  Android) e altrove chiede il codice a mano — nessuna libreria dal CDN: mezzo megabyte per una
  funzione che non tutti i browser possono usare non vale il peso della pagina.
- Nel grafico un giorno **senza colonna è un giorno non segnato, non un giorno a zero**, e per la
  stessa ragione il saldo del periodo somma i soli giorni segnati: contarci i giorni saltati come
  digiuni darebbe un deficit enorme e falso.
- ⚠️ **La sua bolla è SPENTA dal 9 settembre 2026** (`20260909100000_peso_calorie_una_bolla.sql`):
  peso e calorie sono un'app sola, quindi in home c'è **una** bolla — quella di
  `weight-quest.html`, che porta i punti — e non due porte sulla stessa stanza. La riga di
  `cm_apps` si **spegne** (`active = false`) e non si cancella: una DELETE porterebbe via anche la
  sua `score_query`, che è l'unico posto in cui la striscia è scritta. ⚠️ Le due `score_query`
  **non si sommano**: una dà punti veri e l'altra un conteggio, e sommarle darebbe un saldo che
  nessuno può rifare a mano. ⚠️ Spegnere la riga e togliere la voce da `PortedApps.perHtmlFile`
  sono la **stessa modifica**: nel nativo la seconda bolla apriva `CalorieScreen`, che ora si
  raggiunge dal 🍽️ nella barra di «Ti pisasti?». Quel che segue descrive la bolla com'era.
- Aveva una **bolla in AppSphere** (`20260826120000_calorie_app_bolla.sql`, ambra `#d97706`). Il suo
  numero è la **striscia di giorni di fila chiusi dentro il target**, e ⚠️ **non è un punteggio**:
  ha `cm_apps.conta_punti = false`, quindi non si scrive sotto il nome e non entra nel
  totale che paga i premi — un giorno sforato che *abbassasse* il saldo spendibile sarebbe un
  premio che va e viene da sé. ⚠️ `calorie.html` **resta a `false`** anche adesso che la
  riga è spenta: se un giorno tornasse accesa la risposta dev'essere ancora questa. Dimensiona però la bolla, che è il punto: cresce finché il diario
  regge e si sgonfia al primo sforo. **Non «le calorie che restano oggi»**: `sizeOf()` normalizza
  sul punteggio più alto fra tutte le app, e un numero sulle migliaia schiaccerebbe ogni altra
  bolla al minimo di 6 cm². La striscia **si ferma a ieri** — alle nove del mattino si è dentro
  il target per forza, e contarlo direbbe che è andata bene una giornata che deve ancora andare —
  e un giorno senza righe la interrompe come un giorno sforato.

### `memo.html` — Memorandum
- Schede con testo formattato, foto con OCR, categorie condivise, colore e 📌 in evidenza.
- **Cinque tipi di scheda** (`mm_cards.kind`), uno per Tab, e il + crea quello della Tab aperta:
  - **📄 Nota** — quello che c'era prima, e resta il default;
  - **☑️ Lista** — la nota diventa facoltativa e la scheda porta delle voci spuntabili
    (`mm_list_items`);
  - **📊 Diario** — il contenuto è lo **scopo** («perché sto misurando»), e la scheda porta le
    **misure** da raccogliere (`mm_diary_metrics`) e le **registrazioni** (`mm_diary_entries`).
    Una misura è una **scala** (minimo e massimo), un **numero** libero con unità, un **sì/no** o
    una **scelta** fra opzioni configurabili.
  - **🔗 Link** — una cosa condivisa, col suo indirizzo (`mm_attachments`);
  - **🏅 Premiato** — una nota **più un punteggio** (`mm_cards.punteggio`), ed è l'unico tipo che
    fa punti: la somma dei punteggi è il numero della bolla in AppSphere e si aggiunge al totale
    che paga i premi. Dettagli nello schema `mm_`.
- **Una nota si apre in modifica, una lista e un diario no**: hanno una vista propria
  (`openListView` / `openDiaryView`), perché la cosa che si fa più spesso su di loro — spuntare una
  voce, aggiungere una registrazione — non è modificare la scheda. All'editor si arriva da lì con
  *✏️ Modifica scheda*. `openCard()` è il bivio.
- **Cinque Tab nella barra laterale, una per tipo** — 📄 Note, ☑️ Liste, 📊 Diari, 🔗 Link,
  🏅 Premiati — accanto a
  📌 Fissa e ⚙️ Impostazioni. Non esiste una vista che li mescola: si apre sulle Note, ogni Tab ha
  la sua ricerca, il suo ordinamento e il suo filtro categoria (i conteggi delle categorie seguono
  il tipo aperto), e il **+ crea una scheda del tipo della Tab** invece di chiedere quale.
  **📌 Fissa è l'unica che attraversa i cinque tipi**, ed è la ragione per cui il badge del tipo
  resta sulla scheda anche ora che ogni Tab ne mostra uno solo. ⚠️ Sulla Tab 🏅 Premiati il titolo
  porta il **totale dei punti**, che è il numero della bolla: ricavarlo sommando le schede a mente
  sarebbe il modo migliore per non guardarlo mai.
- **Un diario può essere 🙈 riservato** (`mm_cards.riservato`), con la spunta nella sua
  definizione. È la stessa modalità nascosta di `events-log.html` e del launcher, tre stati:
  spenta si vedono solo le schede normali, accesa si vede tutto, accesa col 👁 si vedono **solo**
  le riservate. ⚠️ **Il filtro sta nella query, non nel rendering**: fuori dalla modalità nascosta
  la riga non viene proprio letta — nasconderla a schermo la lascerebbe in chiaro a chiunque apra
  gli strumenti del browser. Vale anche per 📌 Fissa, che quindi non la mostra.
- La modalità la accende **AppSphere, non questa pagina**: arriva da `sessionStorage.hidden_mode`
  (il launcher naviga con `window.location.href`), da `postMessage` e dal `BroadcastChannel`
  `appsphere_auth`. Il pulsante 🙈/👁 compare solo quando è già accesa e alza o abbassa il solo
  filtro; `toggleHiddenMode()` a modalità spenta non fa niente.
- ⚠️ La spunta si legge **solo dove si vede**: `saveCard()` la considera solo per i diari, perché
  su una nota il campo non c'è e leggerlo scriverebbe il valore rimasto dall'ultima volta. La
  colonna però è su `mm_cards` e il filtro vale per tutti i tipi, quindi estenderla a note e liste
  è una riga di HTML.
- ⚠️ Il filtro categoria **non sopravvive al cambio di Tab**: le categorie di un tipo non sono
  quelle di un altro, e una Tab che si aprisse già filtrata su una categoria che lì non esiste
  sembrerebbe vuota.
- **Le spunte di una lista sono ottimistiche con rollback** (come Spuntiamola): la voce cambia
  subito e torna indietro se il DB rifiuta, così non resta a schermo una spunta finta che sparisce
  al ricarico.
- **La registrazione di un diario rimostra lo scopo della scheda** prima di chiedere le misure: è
  il promemoria di come vanno lette, e senza si finisce a dare voti a caso dopo un mese. Una scala
  si dà con lo slider o con la casella, un numero con la casella e la sua unità, un sì/no con due
  pulsanti che si ripremono per annullare, una scelta con una combo la cui prima voce
  (*— non l'ho misurata*) toglie il valore invece di archiviarne uno finto.
- **Ogni misura porta una nota che spiega il punteggio** (`hint`, es. *1 = per niente, 20 = non
  riesco a pensare ad altro*): si scrive nella definizione e ricompare **sotto il nome della
  misura al momento di registrare**, che è l'unico momento in cui serve. È facoltativa, e si
  cambia senza toccare lo storico — vive sulla riga della misura, che non viene mai ricreata.
- **La registrazione vuole un titolo breve**, obbligatorio: l'elenco delle registrazioni è fatto
  di date tutte uguali, e senza una riga di testo non si ritrova più il giorno che si cerca.
  ⚠️ L'obbligo è **nell'app, non nel database**: `title` è `NOT NULL DEFAULT ''` perché le
  registrazioni fatte prima della colonna un titolo non ce l'hanno, e un vincolo che le rifiutasse
  renderebbe impossibile perfino aprirle per correggerle.
- Il riepilogo per misura mostra l'ultimo valore e le **ultime 12 registrazioni in miniatura**
  (`.spark`, div e non Chart.js — questa pagina non lo carica). Il fondo scala è quello della
  misura per le scale, quello osservato per i numeri liberi. **Per una scelta al posto della
  miniatura c'è la distribuzione** (`.dist`): un numero non ce l'ha, e quello che si vuole sapere
  è quante volte è uscita ciascuna opzione.

### `forziere.html` — Forziere
- I file che devono stare al sicuro: cifrati **sul computer di Salvatore** prima di partire, e
  archiviati su Google Drive. Si apre dalla bolla in AppSphere, che però ha `riservato = true`:
  **si vede solo in modalità nascosta**, come Finanza. Un forziere annunciato in home a chiunque
  guardi lo schermo da sopra la spalla è metà del lavoro buttato.
- **Due segreti, non uno.** Le **24 parole** (che le sceglie l'utente, non l'app) sono la chiave
  vera: con loro si cifra e si decifra. La **passphrase** è solo una scorciatoia per l'uso
  quotidiano. ⚠️ **Non è la passphrase a cifrare i file** — se lo fosse, cambiarla vorrebbe dire
  ricifrare l'archivio; invece si rifà solo `scorciatoia.gpg`, una manciata di byte, e i file non
  si toccano nemmeno con 10 GB dentro.
- ⚠️ **Le 24 parole non si possono cambiare**: sono la password OpenPGP di ogni file già scritto.
  Si scelgono una volta sola. Il form controlla quello che conta davvero — **almeno 12 parole,
  almeno 10 diverse, nessuna ripetuta più di due volte** — e **non** la lunghezza delle singole
  parole, che non c'entra quasi niente: 24 parole casuali valgono ~250 bit anche se sono tutte da
  tre lettere. Quel che toglie forza è il senso compiuto (indovinata una, la successiva viene da
  sé), la ripetizione, e l'essere ricavate da nomi o date di famiglia.
- ⚠️ **La normalizzazione dev'essere rifacibile a mente davanti a un terminale**, perché le stesse
  parole si riscrivono dentro gpg, che non normalizza niente e prende i byte che digiti. Quindi la
  forma canonica è la più semplice che esista — **tutto minuscolo, una parola dopo l'altra separate
  da uno spazio solo** — e la pagina la **mostra** alla creazione perché sia quella che si ricorda.
  Qualsiasi regola più furba sarebbe irripetibile. `normParole()` vive in due posti,
  `forziere.html` e `APRI-QUESTO.html`, e **vanno cambiati insieme**.
- ⚠️ **`openpgp.config.aeadProtect = false` non è un ripiego: è la compatibilità.** OpenPGP.js v6
  userebbe di suo SEIPDv2 (AEAD, RFC 9580), che GnuPG legge solo dalla **2.5** in poi — cioè non la
  versione installata oggi sulla gran parte dei computer. Con SEIPDv1 il file lo apre qualunque gpg
  degli ultimi vent'anni, ed è tutto il punto di aver scelto questo formato. **Verificato**: file
  prodotto dalla pagina, aperto da **GnuPG 2.4.4** con `--use-embedded-filename`, byte identici.
- ⚠️ **QUESTA PAGINA NON CARICA NIENTE DA TERZI** (v1.2.0), ed è la regola che la protegge più di
  ogni altra: mentre è aperta tiene in memoria le 24 parole, quindi **qualunque** script che gira
  qui dentro può leggerle e spedirle senza che niente lo dica. Un `<script>` servito da un CDN è
  codice che qualcun altro può cambiare quando vuole.
  ⚠️ Fino alla v1.1.0 la regola era **scritta e non rispettata**: accanto a OpenPGP e 7-Zip
  vendorizzati c'era `supabase-js@2` da jsDelivr — per giunta non fissato a una versione, cioè
  «l'ultima 2.x che servono oggi». Al suo posto c'è ora un **client PostgREST minimale** scritto
  nella pagina, con la stessa forma di chiamata di supabase-js (`db('tab').select().eq(…)`,
  risposta `{data, error}`) così chi arriva dalle altre app non impara un secondo dialetto.
  ⚠️ `esegui()` **rifiuta una PATCH o una DELETE senza filtri**: qui non capita, ma il giorno che
  una riga si scrivesse senza `.eq()` il danno sarebbe silenzioso e completo. È lo stesso
  ragionamento dei DELETE col `user_id` ridondante in Obiettivi.
  ⚠️ Via anche **Google Fonts**: un CSS non legge una variabile JavaScript, quindi il rischio era
  di un altro ordine — ma «da terzi non si carica niente» è una regola che regge solo senza
  eccezioni, e i caratteri di sistema qui non costano niente.
- **Le due librerie stanno in `/vendor/` sul nostro dominio**, ferme e verificabili. ⚠️ È una
  **deroga** alla regola «ogni app è un file HTML solo», ed è l'unica ragione per cui la si
  accetta. 7-Zip si carica **solo premendo Esporta**, così l'uso quotidiano resta leggero.
- **Gli scomparti** (v1.2.0): una barra di pillole sopra l'elenco — `Tutti · 📁 nome · Senza
  scomparto · ➕`. Il ➕ carica **nello scomparto aperto**, il 📁 su una scheda sposta un file,
  e l'export continua a fare quel che l'elenco mostra, quindi esportare un solo scomparto viene
  gratis. ⚠️ Da «Tutti» il file nasce **fuori** da ogni scomparto invece che nel primo della
  lista: una scelta presa dall'app al posto di chi carica si scopre cercando il file altrove.
  ⚠️ «Senza scomparto» compare **solo se serve**: senza nessuno scomparto sarebbe un doppione di
  «Tutti», e a zero file una pillola che non apre niente. Dettagli nello schema `frz_*`.
- **Una cartella su Drive per scomparto, e due indici cifrati** (v1.3.0): la cartella si chiama
  con un **uuid** — non col nome dello scomparto, che resta cifrato — e accanto ai file ci sono
  `scomparti.gpg` (quale cartella è quale scomparto) e un `contenuto.gpg` per cartella (nome,
  tipo, dimensione e data dei suoi file). Servono a **orientarsi col solo Drive**: prima erano
  trecento `<uuid>.gpg` che si aprivano tutti e non dicevano niente finché non li si apriva.
  ⚠️ La verità resta il database, gli indici sono una copia riscritta a ogni operazione, e
  quando una riscrittura non passa la pagina lo dice con un avviso e un pulsante. Dettagli
  (compresi `perNome`, `rmdir` su cartella non vuota e il controllo a un livello solo) nello
  schema `frz_*`.
- **🧹 Svuota lo scomparto** (v1.6.0, il terzo pulsante nel ✎ dello scomparto): cancella per
  sempre i file che contiene — Drive, righe e miniature — e **lo scomparto resta**, vuoto, con
  la sua cartella su Drive. ⚠️ È l'esatto contrario del 🗑 **Elimina** che gli sta accanto, dove
  sparisce lo scomparto e i file si salvano in «Senza scomparto»: due esiti opposti, quindi due
  pulsanti: uno solo che chiedesse «e i file?» metterebbe la risposta distruttiva a un clic da
  quella prudente. ⚠️ Il 🧹 **compare solo se dentro c'è qualcosa**, e porta il numero scritto
  sopra: a zero file non c'è niente da svuotare, e quel che si sta per perdere va letto prima di
  premere e non nella finestra dopo.
  ⚠️ **Costa CANCELLA _e_ la passphrase**, come la 💣 zona rossa e per la stessa ragione: il
  forziere è già aperto, e chi trova la scrivania incustodita non deve poter buttare via trenta
  documenti scrivendo sette lettere. Stessa verifica (`passphraseGiusta`, aprendo davvero la
  scorciatoia) e stessa via d'uscita delle 24 parole per chi la passphrase l'ha scordata.
  ⚠️ **La finestra dice cosa NON succede** — lo scomparto resta, gli altri non si toccano, il
  forziere è quello di prima — o «svuota» ed «elimina» si leggono come lo stesso pulsante e si
  preme Annulla su tutt'e due. E **l'export si offre lì dentro**, perché di quei file è l'unica
  copia che sopravvive al pulsante: da qui l'elenco è già filtrato su quello scomparto.
  ⚠️ **Un file per volta, prima Drive e poi la riga**, come in `elimina()`: una rete che cade
  lascia lo scomparto svuotato a metà — quel che è andato è andato, l'elenco a schermo è già
  quel che resta, e ripremendo si riprende invece di ricominciare. Alla fine si riscrive il solo
  `contenuto.gpg` di quella cartella (vuoto, che è quel che adesso è vero): `scomparti.gpg` non
  si tocca, perché nessuno scomparto è cambiato.
- **📁 Riordina il Drive** (Impostazioni), una-tantum: crea le cartelle che mancano, porta ogni
  file dentro quella del suo scomparto e riscrive tutti gli indici. Serve ai forzieri nati prima
  della v1.3.0 e come rimedio a un'operazione fallita a metà. ⚠️ È un **pulsante** e non una
  cosa che parte aprendo la pagina: su trecento file sono trecento chiamate a Drive, e un'app
  che ci mette due minuti ad aprirsi senza dire perché si smette di aprirla.
- **💣 Cancella tutto** (Impostazioni → zona rossa, v1.4.0): non svuota l'archivio, **fa
  smettere di esistere il forziere**. Se ne vanno i documenti su Drive, le miniature, gli
  scomparti con le loro cartelle, i due indici e — questa è la differenza — anche
  `indice.gpg` e `scorciatoia.gpg`, più le righe di tutt'e quattro le tabelle `frz_*`.
  ⚠️ Le 24 parole non aprono più niente **non perché siano sbagliate ma perché non c'è più
  niente da aprire**, e la finestra lo dice con quelle parole prima, non dopo.
  ⚠️ **Costa CANCELLA _e_ la passphrase**, che è più di quanto chiedono `spese-personali` e
  `obiettivi` (lì basta la parola): qui il forziere è già aperto, e chi trova la scrivania
  incustodita non deve poterlo svuotare scrivendo sette lettere. La passphrase si verifica
  con `passphraseGiusta` — aprendo davvero la scorciatoia, come lo scaricamento — e le 24
  parole restano la via per chi l'ha dimenticata.
  ⚠️ **L'export si offre lì dentro**, col numero di giorni dall'ultimo (e in rosso se non
  se n'è mai fatto uno): è l'unica copia che sopravvive a quel pulsante, e ricordarsene
  dopo non serve a niente.
  ⚠️ **Prima Drive, poi il database**, come in `elimina()`: nell'ordine inverso resterebbero
  `.gpg` che nessuno sa più cosa siano e che dall'app non si possono più togliere. Una rete
  che cade lascia un forziere a metà, e **ripremendo si riprende** invece di ricominciare —
  la finestra lo dice.
  ⚠️ **L'elenco da cancellare lo dà Drive e non il database** (`list` con `dentro`): così se
  ne vanno anche i file di righe perdute, che il database non nominerebbe affatto. È
  l'opposto di 📁 Riordina il Drive, che parte dal database perché lì la verità è quella.
  ⚠️ Le cartelle si tolgono **dopo** i file, o `rmdir` le rifiuta; e la cartella
  «AppSphere/rooms» **resta, vuota** — `rmdir` si rifiuta sulla radice per costruzione, e
  il forziere successivo la ritrova per nome.
- ⚠️ **F12 non si disabilita, e non servirebbe.** Da una pagina web non si può, e provarci copre
  solo un tasto: restano il menù del browser, le altre scorciatoie, gli strumenti già aperti,
  `view-source:`, un proxy — e il codice che blocca il tasto è a sua volta JavaScript, che si
  mette in pausa dagli strumenti stessi. Ma soprattutto **chi preme F12 è già seduto al computer
  col forziere aperto**: può leggere lo schermo. Il forziere protegge da chi ha il database, il
  Drive o un backup; per il resto valgono il blocco automatico e il blocco schermo del sistema.
  Il rischio vero della stessa famiglia era il CDN, ed è quello che si è chiuso.
- ⚠️ **«Drive: Invalid JWT» NON è un problema di Drive** (v1.6.2, 10 settembre 2026): è
  Supabase che rifiuta il token **prima** che la Edge Function parta
  (`{"code":"UNAUTHORIZED_ASYMMETRIC_JWT","message":"Invalid JWT"}`, HTTP 401), e il
  prefisso «Drive: » di `drive()` lo faceva sembrare tale — mandando a cercare il guasto
  dalla parte sbagliata. Il forziere **non ha un suo login**: usa il token della suite,
  che dura **un'ora** mentre `localStorage.sb_token` non scade mai. Tre cose ne
  discendono, tutte in codice:
  - **`drive()` riconosce 401 e 403** e li passa a `handleAuthError`, che c'era già e
    chiude il forziere riportando al login: la usavano le sole chiamate al database, e la
    Edge Function le passava accanto;
  - **all'apertura il token si controlla** (`tokenVivo`, `exp` con 30 s di margine) invece
    di fidarsi di quel che c'è in archivio: senza, la pagina si apriva come se la sessione
    ci fosse e lo scopriva la prima chiamata. Il margine evita di partire con un token che
    scade **nel mezzo dello sblocco**, cioè con le 24 parole già scritte;
  - **un `TOKEN_REFRESH` che arriva a pagina ferma sul login la fa ripartire**
    (`if (!sbPronto) initWithToken(…)`): prima il token nuovo si scriveva e basta, e
    l'overlay restava lì con la sessione già buona sotto.

  ⚠️ **Lo stesso difetto era nell'APK nativa, ed è stato chiuso lo stesso giorno** (v1.0.78,
  `ForziereDrive.token()`): là il sintomo era ❌ *«Drive: … serve un login valido»* — la stessa
  sessione scaduta, riconosciuta dalla funzione invece che dal cancello. Le due correzioni sono
  gemelle e vanno cambiate insieme; il dettaglio sta in *AppSphere nativa → Forziere nativo*.

- **La chiave dell'indice è una `CryptoKey` con `extractable: false`**: esiste nel browser ma da
  JavaScript non se ne leggono i byte. Le 24 parole invece devono stare in una stringa in memoria
  (servono a OpenPGP per cifrare i file nuovi) — ⚠️ e per questo il forziere si **chiude da sé**
  dopo `MINUTI_BLOCCO`, alla chiusura della scheda e all'uscita da AppSphere (`LOGOUT` sul
  `BroadcastChannel`). Niente `localStorage`, niente `sessionStorage`, mai.
- ⚠️ **Il timer da solo non basta: si chiude anche quando la scheda se ne va o passa in secondo
  piano** (v1.2.1, `pagehide` + `visibilitychange`). Una scheda che se ne va può **tornare
  indietro dalla cache di navigazione del browser** (bfcache) col forziere ancora aperto — e in
  quello stato i timer sono congelati, quindi i dieci minuti non passano mai: era l'unico stato
  in cui il forziere restava aperto per ore senza che nessuno lo guardasse. Rientrare costa la
  passphrase, cioè due secondi.
  ⚠️ **`pagehide` e non `beforeunload`**: il secondo non scatta affatto su iOS e su Android
  quando la scheda viene semplicemente messa via, che è il caso per cui questo serve.
  ⚠️ **Un'operazione lunga in corso non si interrompe a metà** (`S.occupato`, alzato da
  caricamento, apertura, eliminazione ed export): chiudendo il forziere mentre un file sta
  salendo, le 24 parole e la chiave dell'indice sparirebbero a caricamento avviato — il file
  resterebbe su Drive **senza la sua riga**, cioè un pacchetto cifrato che nessuno sa più cos'è
  e che dall'app non si può più togliere. Nascosta la scheda si aspetta la fine (un
  `setInterval`, che in una scheda nascosta il browser rallenta a uno scatto al minuto: qui va
  bene, non si sta contando niente). Su `pagehide` invece si chiude comunque — la pagina se ne
  sta andando e con lei tutto quello che c'era da salvare.
- ⚠️ **Un file si APRE nella pagina, non si scarica** (v1.1.0): il tocco sull'anteprima o su
  👁 Apri lo decifra e lo mostra in un visore — immagini, PDF, video, audio e testo. Scaricare
  è rimasto, ma dentro il visore e come **seconda** scelta dichiarata: un documento sceso nella
  cartella Download **esce dal forziere** e resta lì in chiaro finché qualcuno non se ne
  ricorda, che è l'opposto di quel che questa app fa. Il file decifrato vive in un blob in
  memoria e si butta chiudendo.
  ⚠️ **Il blob si revoca in `chiudiModale()`** e non in un pulsante: da quella finestra si esce
  anche col ✕, con l'indietro di Android e col blocco automatico, e un blob non revocato resta
  raggiungibile per tutta la vita della pagina.
  ⚠️ **Il tipo con cui si costruisce il blob lo decide `comeMostrare()`, non `meta.tipo`**: un
  file dichiarato `text/html` dentro un `<iframe>` girerebbe **nella nostra origine**, cioè
  nella pagina che in quel momento tiene le 24 parole in memoria. L'iframe si usa quindi solo
  per il PDF, forzando `application/pdf`; un SVG passa da `<img>`, dove gli script non partono;
  tutto il resto che il browser non sa mostrare **lo dice e offre lo scaricamento**, invece di
  restare un riquadro nero.
- ⚠️ **Scaricare costa la passphrase** (v1.2.2): il ⬇️ dentro il visore la chiede **ogni
  volta**, prima di far uscire il file. È l'unico gesto che porta un documento **fuori** dal
  forziere, in chiaro e per sempre, e la domanda serve a dire che al computer ci sei tu
  *adesso* — ricordarla anche solo per pochi minuti toglierebbe la protezione proprio nel
  caso per cui esiste, la scrivania lasciata un momento.
  ⚠️ **Si verifica APRENDO la scorciatoia** (`passphraseGiusta`), non confrontandola con
  qualcosa in memoria: in memoria non c'è — allo sblocco serve ad aprire `scorciatoia.gpg`
  e viene buttata subito, e deve restare così. Se ne escono **esattamente le 24 parole che
  il forziere sta usando**, è quella: è lo stesso controllo dello sblocco, e distingue
  perfino la scorciatoia di un *altro* forziere, che si aprirebbe benissimo portando però
  parole diverse. Un confronto con una variabile sarebbe un controllo che passa sempre.
  ⚠️ **La scorciatoia cifrata si tiene in memoria** (`S.scorc`, riempita allo sblocco, alla
  creazione e al cambio passphrase): non è un segreto — su Drive sta esattamente così — e
  senza di lei la verifica chiederebbe la rete, cioè col Drive irraggiungibile la pagina
  mostrerebbe il file e non lo farebbe scaricare.
  ⚠️ **La domanda sta DENTRO il visore**, al posto della riga dei pulsanti, e non in una
  seconda finestra: `#modalBody` è uno solo, quindi un `apriModale` lì sopra cancellerebbe
  il visore e `chiudiModale` revocherebbe il blob — cioè proprio il file che si sta per
  scaricare.
  ⚠️ **Le 24 parole restano una via**, come nella schermata di sblocco: chi è entrato con
  loro e ha risposto «Dopo» al cambio passphrase ha su Drive una scorciatoia che si apre
  con quella **vecchia e dimenticata**, e senza questa via lo scaricamento gli resterebbe
  chiuso per sempre senza che niente glielo spieghi.
  ⚠️ Il controllo sta in `scaricaDalVisore()` e **non** in `salvaSulDisco()`, che è
  condivisa con l'export `.7z` — che una password la chiede già di suo — e con
  📄 Scarica APRI-QUESTO, che di segreti non ne porta nessuno.
- **Export `.7z`** (💾): i documenti dentro stanno **in chiaro**, protetti dalla cifratura AES-256
  del 7z stesso — un archivio pieno di `.gpg` sarebbe una scatola dentro una scatola e non
  risparmierebbe niente a chi lo apre. ⚠️ **`-mhe=on` cifra anche l'elenco dei nomi**: senza, la
  lista dei file si legge senza password, e i nomi sono metà del segreto. La password sono le 24
  parole, ma se ne può mettere un'altra per dare quella copia a qualcuno senza consegnare anche il
  forziere. **Verificato** con 7-Zip vero: senza password nemmeno l'elenco si apre.
- ⚠️ **L'export è la risposta a «perdo l'account Google»**, che il progetto inizialmente non
  copriva: i file stanno su Drive, quindi perso l'account sono persi — le 24 parole aprirebbero
  benissimo qualcosa che non c'è più. La pagina dice da quanti giorni non lo si fa, e lo dice a
  gran voce finché non se n'è fatto **nemmeno uno**.
- **`APRI-QUESTO.html`** è la via d'uscita che non dipende da questa app: apre i `.gpg` **offline**,
  con OpenPGP.js **incorporato dentro** (nessuna `fetch`, nessun CDN — si può staccare la rete e
  controllare). Si genera con `scripts/build-apri-questo.py` da `vendor/openpgp.min.js`
  e va **rigenerato quando la libreria si aggiorna**.
- **Il collaudo del primo giorno**: appena creato, la pagina fa riscrivere le 24 parole e le prova
  **come le proverebbe un recupero vero** (aprendo `indice.gpg`), non confrontandole con quelle che
  ha in memoria — un collaudo che si confronta con sé stesso passerebbe sempre. Sapere che il
  recupero funziona costa un minuto oggi; scoprire che non funziona costa il forziere fra dieci
  anni. ⚠️ Quando le parole non aprono, la pagina **non dice quale** è sbagliata — non può, o
  vedrebbe le parole — ma **dice che è successo**, ed è tutta la differenza fra un errore di
  battitura e credere di aver perso tutto.
- ⚠️ **Una riga di `frz_files` che non si decifra non fa saltare l'elenco**: si mostra e si dice
  che c'è. Sparire sarebbe il modo peggiore di segnalare un problema — un file in meno e nessuno
  che spieghi perché. Stessa regola per una miniatura illeggibile.
- ⚠️ **Cancellando, prima il file su Drive e poi la riga.** Al contrario, una rete che cade
  lascerebbe su Drive un file cifrato che nessuno sa più cos'è e che dall'app non si può più
  togliere. È la stessa scelta delle foto di Memo.
- ⚠️ **Il numero della bolla è un conteggio di file e non un punteggio**: `cm_apps.conta_punti`
  della sua riga è `false`.
- **👆 Sblocco con l'impronta** (v1.5.0, la **fase 2**): le 24 parole avvolte nel **Keystore di
  Android** e riaperte con l'impronta, così sul telefono non si digita più niente per aprire. Vive
  **nell'APK WebView** (`com.garsalapps` v1.2.0), non nel nativo: `ForziereKeystore.kt` fa la
  crittografia, `AndroidBridge` espone cinque metodi, e `forziere.html` li usa **solo se li
  trova** — sul PC la pagina resta identica a prima.
  ⚠️ **Apre il forziere e NIENT'ALTRO**: ⬇️ scaricare un file e la 💣 zona pericolosa continuano
  a chiedere la passphrase scritta. Là la domanda non è «chi sei» ma «sei sicuro», e un dito
  appoggiato è il gesto meno deliberato che ci sia — chi tocca `scaricaDalVisore` o
  `zonaPericolosa` non ci porti l'impronta senza rifare quel ragionamento.
  ⚠️ **`ForziereKeystore` NON è `showBiometricPrompt()`, e riusare quello sarebbe un buco.** Il
  cancello dell'app chiede l'impronta e poi *chiama una funzione* — e su un telefono senza
  impronta né PIN apre lo stesso, di proposito. Qui invece non c'è nessuna decisione da
  scavalcare: la chiave nasce con `setUserAuthenticationRequired(true)` e il `Cipher` arriva al
  prompt dentro un `CryptoObject`, quindi senza autenticazione fresca non produce un byte.
  **Fallisce chiuso.**
  ⚠️ **Solo `BIOMETRIC_STRONG`, mai `DEVICE_CREDENTIAL`**: legare la chiave al PIN del telefono
  vorrebbe dire che il forziere si apre con quello che si digita davanti a chiunque in autobus —
  e sotto API 30 quella coppia con un `CryptoObject` non è nemmeno ammessa.
  ⚠️ **`setInvalidatedByBiometricEnrollment(true)`**: un'impronta nuova sul telefono **uccide la
  chiave**, e la registrazione va rifatta. Senza, chi si fa aggiungere il proprio dito a un
  telefono lasciato sbloccato si porterebbe via il forziere. La pagina lo dice com'è —
  «di solito è un'impronta nuova» — invece di far leggere un guasto.
  ⚠️ **Le parole NON passano da `evaluateJavascript`**: là finirebbero dentro una stringa di
  codice sorgente. Il ponte risponde solo «è andata», e il JS le va a prendere con
  `forzierePrendiParole()`, che **le restituisce e le cancella** — è il giro di
  `getPendingImageBase64` + `clearPendingImage`.
  ⚠️ **Si registra dalle Impostazioni, a forziere aperto**: lì le 24 parole sono già in memoria e
  già verificate, quindi non si fa riscrivere niente. Sulla schermata di sblocco non ci sono
  ancora.
  ⚠️ **Lo sblocco passa comunque da `apriConParole`**: il Keystore dice che sei tu, non che quelle
  parole aprano *questo* forziere — sono due domande diverse, e la seconda la fa `indice.gpg`.
  ⚠️ Il blob avvolto sta **solo su quel telefono**, nelle preferenze: la chiave che lo apre non
  esce dal TEE, quindi altrove è rumore. Non va su Drive né nel database, che sarebbero un
  secondo bersaglio per niente. E **💣 Cancella tutto se lo porta via** (`bioDimentica()`), o
  resterebbe un «👆 Sblocca» che riapre le parole di un forziere che non esiste più.
- **Esiste anche in nativo** (`android-app/appsphere-native/app/.../frz/`), ma per **cinque cose
  sole**: sbloccare, sfogliare, aprire un documento, metterne dentro uno, buttarne via. Creazione del forziere,
  collaudo delle 24 parole, export `.7z` e cambio della passphrase **restano qui** — dettagli in
  *AppSphere nativa → Forziere nativo*.

### `piante.html` — Piante
- Il diario di cura, **pianta per pianta**: si apre sull'elenco delle piante (copertina, specie,
  posizione, prossima azione), e ogni pianta ha quattro Tab — 📋 **Scheda** (informazioni di cura
  e foto), 📔 **Diario** (voci con data, commento e foto, dalla più recente), ✅ **Azioni** (coi
  promemoria) e 🤖 **Chiedi all'IA**. Nel menù ci sono anche 📆 **Da fare** (le azioni di tutte le
  piante, dalla più urgente) e 💚 **Desideri** (la lista delle piante che si vorrebbero curare).
- Le azioni sono «prese da Tasks»: stessi tipi (una volta, ogni N giorni, ricorrente, date
  precise, quando capita), stessa regola «il tipo di un'azione che c'è già non si cambia», stessi
  giorni della settimana numerati come `extract(dow)`, e ✅ / ⏭ passano **solo** dalle RPC. Un'azione
  **non si fallisce**, come in Obiettivi: o la si fa o la si rimanda.
- 🔔 **Promemoria**: 📱 Telegram e 📲 Telefono coi loro anticipi (da `cm_reminder_presets`),
  🔐 Smart Block all'ora dell'azione. La riga di `cm_notification_rules` **basta** a dire che il
  canale è acceso, come in Tasks; un canale acceso senza anticipi si spegne e lo dice.
- ⚠️ **Claude e Qwen chiedono conferma a ogni domanda** (v1.2.1, `AVVISO_COSTO` in `piante.html` e in `PianteViewModel.kt`, gemelli): si pagano — Qwen dopo il credito iniziale — e la tendina ricorda l'ultima scelta, quindi senza la domanda si pagherebbe senza accorgersene. Gemini e Groq no.
- 🤖 **Chiedi all'IA** (v1.2.0) ha una **tendina Gemini / Qwen / Groq / Claude** accanto a «Chiedi», con le sole IA che hanno la chiave nei Secrets; l'ultima scelta resta nel browser (`localStorage.pv_ia_fornitore`) e nelle preferenze del telefono, e ogni risposta porta il nome di chi l'ha data. Risponde **dentro l'app** (Edge Function `pv-ai`) e le risposte restano
  salvate nella pianta, col loro 🗑. Il Markdown della risposta si disegna da **testo già
  escapato** (`markdownSemplice`): l'HTML di una risposta non entra mai nella pagina.
- ⤴️ **Risposta nel prompt** (v1.3.1, APK 1.0.100): accanto al 🗑 di ogni risposta di «Chiedi all'IA», la risposta **sostituisce** il testo del prompt per correggerla e rilanciarla. `rispostaNelPrompt()` in `piante.html` / `onNelPrompt` in `PianteScreen.kt`, gemelle; in nativo il prompt è salito da `BoxIA` a `ChiediIA`/`Desideri` perché lo storico lo possa scrivere.
- 🌿 **Discende da** (v1.3.0, `20260927100000_pv_plants_discende_da.sql`): una tendina con le piante già presenti, e nella scheda la madre e le 🌱 figlie si toccano per aprirle. ⚠️ La tendina **non offre la pianta stessa né le sue discendenti** (`discendentiDi()` / `PianteState.discendenti`, gemelle): il CHECK del database ferma solo il caso diretto, i giri più lunghi li tiene fuori lei. ⚠️ `ON DELETE SET NULL`: cancellando la madre la figlia resta.
- 📋 **Clona** (v1.3.0, accanto a ✏️ nella scheda) copia la **sola scheda** — nome «(copia)», arrivata oggi, stessa madre — e **apre il form invece di salvare da sé**: foto, diario e azioni sono di quella pianta e non di questa. `clonaPianta()` / `FormPianta(clonaDa=…)`, gemelle.
- 💚 **«🌱 L'ho presa»** apre il form di una pianta nuova già compilato col desiderio, e il
  desiderio passa a `presa` **solo quando la pianta è salvata davvero**. Le scartate restano, nel
  loro filtro.
- ⚠️ **Eliminare una pianta** porta via diario, foto e azioni: la conferma lo dice coi numeri e
  propone l'**archiviazione**, che la toglie dall'elenco lasciandone il diario.
- ⚠️ Le foto si riducono a 1600 px prima di partire (`riduci()`, tre gradini come in Modifiche) e
  il form di una voce **si riprende** se una foto non passa: la bozza si tiene l'id appena nato.
- **Esiste anche in nativo** (`android-app/appsphere-native/app/.../piante/`, APK 1.0.95), con
  tutte le funzioni: vedi *Dieci app ora esistono in due implementazioni*.

### `chiedi-a-pino.html` — Chiedi a Pino (era IA Tunnel)
- Una pagina sola: prompt, fino a **4 immagini** allegate (📎, ridotte a 1600 px come in Piante; si rivedono nello storico e si aprono a tutto schermo), **tag** da una tendina (anche più d'uno, con «➕ Nuovo tag…» in fondo e
  ⚙️ per rinominarli ed eliminarli), **motore IA** fra quelli che hanno la chiave nei Secrets, e
  🚀 Invia. Sotto, lo storico di domande e risposte col **filtro per tag**, e su ogni voce 🗑,
  📋 Copia, ↩️ Riusa il prompt, ⤴️ Risposta nel prompt (per rilanciarla) e 🏷️ Tag (che riscrive i collegamenti da capo).
- ⚠️ **Claude e Qwen chiedono conferma a ogni invio** (`AVVISO_COSTO`, gemello di quello di
  `piante.html`): la tendina ricorda l'ultima scelta (`localStorage.iat_fornitore`), e senza la
  domanda si pagherebbe senza accorgersene.
- ⚠️ **Il prompt si svuota solo quando la risposta c'è**: un errore non deve costare il testo
  appena scritto. I tag scelti restano per l'invio dopo.

### `casarosa.html` — Cassa Casa Rosa
- Movimenti e saldo della cassa di Casa Rosa (`cntrs_transactions`, `cntrs_categories`,
  `cntrs_saldi`). Si apre dal collegamento *🏠 Casa* nella sidebar di `finanza.html`.
- **Gemello di `conto-risparmio-teresa.html`**, che lavora sullo stesso schema con le tabelle
  `_terr`: import da Excel/CSV, `guessCategory()` sulle causali numeriche UniCredit
  (048, 008, 219, 034, 018) e revisione dei conflitti prima di scrivere.
- L'import dalla banca passa da `enable-banking-transactions` sul conto spuntato come
  `'casa_rosa'` in `cm_bank_connections.uses` — uso a sé e non `'conto_risparmio'` riusato,
  altrimenti le due pagine si troverebbero ciascuna il conto dell'altra nella tendina.
  La causale numerica non passa dall'API PSD2: `guessCategoryFromBank()` riconosce le stesse
  categorie dal testo, e ciò che non riconosce va in *da attribuire*.
- ⚠️ **Il blocco di import dalla banca è lo stesso di `conto-risparmio-teresa.html`**, a meno dei
  nomi delle tabelle, dell'uso in `uses` e di una categoria (`AFFITTO CONTANTE ROSA` invece di
  `AFFITTO CONTANTE TERRASINI`). Se lo modifichi in uno, guarda anche l'altro.

### `spese-ada.html` — Spese Ada
- Import dal conto, categorie e dashboard per le spese di Ada. Si apre dal collegamento
  *🧾 Spese Ada* nella sidebar di `finanza.html`.
- **Tabelle proprie `ada_*`, non le `ca_*`** (`20260808130000_ada_spese_tables.sql`):
  `ada_categories`, `ada_transactions` (una sola categoria per movimento), `ada_merchant_map`.
  La separazione è per tabella e non per campo perché le `ca_*` sono lette da quattro punti
  diversi (`cost-analysis.html`, la vista in `finanza.html` e in `situazione-teresa.html`,
  `revolut-auto-categorize`): un campo da filtrare avrebbe mescolato le spese di Ada nei totali
  di famiglia il giorno che una query se ne fosse dimenticata.
- **Due livelli di categoria** (`20260808140000_ada_super_categories.sql`): super-categoria → voce.
  Il **colore vive solo sulla super-categoria** e la voce lo eredita; nella ciambella per voce le
  voci della stessa super si distinguono per sfumatura (`shade()`), così le due ciambelle
  affiancate si leggono come una cosa sola. Tabella separata e non un `parent_id`: una voce senza
  super deve poter esistere (sono le sei di partenza) e con l'autoreferenza sarebbe
  indistinguibile da una super-categoria. `super_id` è `ON DELETE SET NULL` — cancellare una
  super non porta via le voci, e quindi nessun movimento perde la categoria.
- Il conto è quello spuntato come `'spese_ada'` in `cm_bank_connections.uses` — lo stesso conto
  UniCredit può servire anche ad altri moduli. I movimenti arrivano da
  `enable-banking-transactions`: solo le **uscite**, con un **filtro per carta** (la funzione
  restituisce `card.identification` quando la banca la espone) per isolare le spese di Ada su un
  conto con più carte. La carta scelta resta in `localStorage` (`ada_card_filter`).
- ⚠️ **Da agosto 2026 Spese Ada è allineata a `spese-personali.html`**: import da Excel/CSV,
  riconoscimento dei doppioni fra le due fonti, pagina 🧠 **Attribuzione**, jolly `%`, popup della
  regola quando si associa, «tutti gli anni», assegnazione in blocco e zona pericolosa **sono le
  stesse e vanno lette nella sezione di Spese Personali qui sotto** — il codice è lo stesso a meno
  dei nomi delle tabelle. Le differenze che restano sono tre: qui si archiviano **le sole spese**
  (le entrate entrano solo spuntando *Mostra anche le entrate*, e la casella vale sia per l'import
  dal conto sia per quello da file), il **filtro per carta non lascia passare i movimenti senza
  carta** (là sono le entrate che servono al saldo, qui si cerca una carta e basta), e non c'è
  nessun saldo del periodo.
- Le regole si scrivono associando una categoria a un movimento, e da lì in poi valgono in tre
  momenti — sulle righe proposte in anteprima, sugli altri movimenti già in archivio
  (`applyLearnedMerchants`) e col pulsante *✨ Applica i negozi imparati*.
  **Tocca solo i movimenti senza categoria**: quello che è già classificato non si sovrascrive.
- Quando la voce giusta non c'è ancora, la tendina del movimento chiude con **«➕ Nuova voce…»**:
  si crea la voce e **nella stessa finestra si scrive la regola**, con il conteggio dal vivo di
  quanti movimenti aggancerebbe. Le due cose nascono insieme perché è lì che si sa qual è il
  negozio — la chiave proposta è quella che si imparerebbe da sola (`normalizeMerchant`), ma
  accorciarla è il punto dell'operazione. Togliendo la spunta la voce si crea senza regola.
  In quel caso `setTxCategory(tx, id, { learn: false })`: la regola l'ha appena scritta l'utente,
  e impararne una seconda con la descrizione intera ne aggiungerebbe una che nessuno ha chiesto.
  Vale sia sui movimenti in archivio sia sull'anteprima di import; lì il form **prende il posto
  dell'elenco** invece di aprire un secondo modale (`#modal-body` è uno solo) e le spunte già
  fatte vengono prima messe al sicuro in `_import.items` da `syncImportSelections()`.
- Il confronto è **«la descrizione contiene la chiave»** (con `%` come jolly, vedi Spese
  Personali), non un'uguaglianza, e la chiave si ricava da **quello che UniCredit scrive dopo
  l'importo**, che è l'esercente:
  `PAGAMENTO POS … del 15/07/2026 CARTA 31342819 DI EUR 1,50 SAN DONATO ALIMENTARI. BOLOGNA`
  → chiave `SAN DONATO ALIMENTARI. BOLOGNA` (`POS_MERCHANT_RE` in `merchantText()`). Tutto quello
  che precede l'importo — modalità di pagamento, data, numero di carta, importo — cambia a ogni
  movimento. Se il pezzo non c'è (addebiti, bonifici, altre banche) si tiene la descrizione
  intera e la regola si **accorcia a mano** da Categorie → Negozi imparati, dove il campo mostra
  in tempo reale quanti movimenti aggancerebbe.
  Fra più regole che agganciano **vince la più pesante**, cioè la più specifica (i caratteri al
  netto dei jolly): senza quella precedenza una regola corta imparata prima si prenderebbe i
  movimenti di una più specifica.
- La stessa causale porta il numero di carta (`CARTA 31342819`): `cardFromDescription()` lo usa
  come ripiego quando l'API non espone la carta nei campi strutturati, altrimenti il filtro per
  carta — l'unico modo per isolare le spese di Ada su un conto condiviso — resterebbe vuoto.
- L'import da file (📊 Importa da Excel) è arrivato con la v1.2.0: la banca via PSD2 espone solo
  gli ultimi mesi, e lo storico più vecchio sta solo nell'estratto conto scaricato dal sito. Da lì
  discende `ada_merchant_map.source` (`20260818120000_ada_merchant_map_source.sql`): **due elenchi
  di regole separati**, perché le due fonti scrivono la stessa spesa in modo diverso.
- **Nessuna categorizzazione da MCC**: `enable-banking-transactions` non restituisce il
  `merchant_category_code` (lo estrae solo `enable-banking-sync`, per Spese Famiglia). Qui
  l'automatismo è tutto nei negozi imparati.

### `spese-personali.html` — Spese Personali
- Il conto personale di Salvatore: import dal conto, categorie a due livelli, negozi imparati e
  dashboard. Si apre dal collegamento *💳 Spese Personali* nella sidebar di `finanza.html`
  (sezione **Salvatore**).
- **Gemello di `spese-ada.html`**, che dall'agosto 2026 ha le stesse funzioni (import da file,
  Attribuzione, jolly, popup della regola, «tutti gli anni», assegnazione in blocco, zona
  pericolosa) — ⚠️ **se modifichi una delle due, guarda anche l'altra**. Restano diverse per tre
  cose:
  1. **tabelle `sal_*`** invece di `ada_*` (`20260809120000_sal_spese_personali_tables.sql`),
     separate dalle `ca_*` per la stessa ragione: le spese personali non devono entrare nei
     totali di Spese Famiglia;
  2. **entrate comprese**, non solo le uscite. `amount` è già con segno, quindi lo schema non
     cambia: cambia cosa la pagina propone all'import (una casella *Solo le uscite*, spenta di
     default) e cosa somma — KPI uscite / entrate / **saldo del periodo**, andamento mensile a
     due serie, e una tabella *Dettaglio entrate* a parte. Le ciambelle restano sulle **sole
     uscite**: mescolare entrate e uscite in una torta dà percentuali che non vogliono dire
     niente;
  3. il filtro per carta lascia comunque passare i **movimenti senza carta** (bonifici,
     accrediti, addebiti). Su Ada si cerca una carta e basta; qui quei movimenti sono
     esattamente le entrate che servono per il saldo, e nasconderli col filtro attivo
     falserebbe il totale.
- L'import da file **sbocca nella stessa anteprima** dell'import dal conto — spunte, doppioni,
  categorie, «➕ Nuova voce…», filtro per carta — perché quel che si fa dopo aver letto le righe
  non cambia a seconda di dove sono nate. `_import.source` (`'bank'` \| `'excel'`) decide solo la
  riga di intestazione dell'anteprima e cosa finisce in `sal_transactions.import_source`;
  `bank_connection_id` ed `external_id` restano **NULL** per le righe da file.
  Tre cose che sono la ragione per cui il codice è fatto così:
  - ⚠️ **un foglio non porta l'external_id**, quindi l'indice unico
    `(bank_connection_id, external_id)` non protegge — in Postgres due NULL sono distinti — e il
    controllo dei doppioni sta tutto nella pagina (`indiceDoppioni()`, vedi sotto). Rileggere due
    volte lo stesso file è la normalità, e le righe già in archivio arrivano in anteprima
    **deselezionate**;
  - ⚠️ **un CSV si legge diversamente da un xlsx**: leggendo un CSV SheetJS interpreta i numeri
    all'americana e «-12,90» diventa **-1290**, «1.850,00» diventa **1,85** — importi plausibili
    e falsi, che in anteprima non si notano. `raw: true` glielo impedisce e a leggerli è
    `parseImporto`, che riconosce la notazione dall'ultimo separatore invece di dare per scontata
    la lingua. Il testo passa da `testoDelFile()`, che prova UTF-8 in modo severo e ripiega su
    **Windows-1252** (in cui UniCredit scrive i suoi CSV) invece di storpiare gli accenti — e con
    loro la chiave del negozio;
  - la **descrizione si archivia com'è**, senza cucirci davanti la causale: è quella che i negozi
    imparati leggono, e cambiandola `normalizeMerchant` darebbe una chiave diversa da quella
    delle righe entrate dalla banca — lo stesso negozio verrebbe imparato due volte.
  Le intestazioni non sono nella prima riga (preambolo con intestatario, IBAN e saldi):
  `trovaIntestazioni()` cerca nei primi 60 righe di ogni foglio una riga che porti insieme data,
  descrizione e importo — o la coppia entrate/uscite — **su tre colonne diverse**, perché un CSV
  spezzato male finisce tutto in una cella sola che quelle tre parole le contiene per forza.
  SheetJS si carica dal CDN **al primo uso** e non all'apertura della pagina.
- ⚠️ **Lo stesso movimento può entrare da due strade, e le due strade non lo scrivono uguale**: la
  banca gli mette accanto l'`external_id` e cuce nella descrizione il nome della controparte, il
  foglio nessuna delle due cose. `indiceDoppioni()` è quindi **una sola rete a tre maglie**, usata
  da entrambi gli import: `external_id` (certezza), `data + importo + descrizione` (stessa riga
  dall'altra strada, stesso testo) e — quella che prende davvero il caso incrociato —
  **`importo` esatto + data entro un margine**. ⚠️ In quest'ultima **il testo non entra affatto**,
  ed è la scelta che regge tutto: la descrizione è proprio ciò che le due strade scrivono in modo
  diverso, quindi confrontarla non aggiunge nulla e toglie aggancio. La **data invece balla** —
  il foglio porta la registrazione, la banca la contabile, e su una carta si scostano di qualche
  giorno — da cui il margine (`dupMargine()`, 3 giorni di default, si cambia in Impostazioni e
  vive in `localStorage` come il filtro per carta). Non essendo una certezza, la riga si **segnala
  come sospetta** mostrando accanto il movimento più vicino e di quanti giorni si scosta: il
  confronto delle descrizioni lo fa l'occhio, che è l'unico che può farlo. Tutti e tre i casi
  arrivano **deselezionati**. Fino alla v1.0.3 la rete era una sola per fonte e un movimento
  entrato da Excel tornava dal sync col suo `external_id`, che nessuno aveva mai visto: la spesa
  entrava **due volte**, e in un totale non si vedeva.
- ⚠️ **Le regole di attribuzione sono due elenchi separati, uno per fonte, e non si parlano**
  (`20260817160000_sal_merchant_map_source.sql`): un movimento consulta solo le regole della
  propria `sal_transactions.import_source`. È la conseguenza diretta del punto qui sopra — le due
  fonti scrivono la stessa spesa in modo diverso — quindi una chiave imparata di qua non vale di
  là, e tenerle insieme voleva dire che ogni correzione fatta da una parte sballava l'altra. Lo
  stesso negozio si insegna **due volte, una per elenco**: sono due scritture, non un doppione.
  `merchantRule`, `merchantCategory`, `merchantMatchCount`, `learnMerchant` e `saveMerchantRule`
  vogliono tutte la fonte, e `applyLearnedMerchants` confronta ogni movimento con l'elenco della
  **sua**. In Impostazioni le due cancellazioni sono **indipendenti**, una per elenco
  (`source=eq.<fonte>` nella DELETE): rifare le regole da Excel non deve far ricominciare da capo
  anche quelle del conto. Le regole nate prima della colonna sono `'bank'`, che è quello che sono davvero: non
  sono state copiate nel set `'excel'` di proposito, perché contengono pezzi di testo che in un
  foglio non compaiono e riempirebbero l'elenco nuovo di regole inerti.
- In **Categorie** il nome di una voce (e di una super-categoria) è **cliccabile** e apre la
  statistica nel tempo: KPI, *per anno* e *per mese-anno*, ciascuno con grafico e tabella. I due
  tagli sono quelli — **non un taglio per «mese» qualunque**: gennaio 2024 e gennaio 2026 sommati
  sarebbero lo stesso errore delle dodici colonne della dashboard con «tutti gli anni». I mesi
  senza movimenti non compaiono (una fila di zeri direbbe solo che il calendario ha dodici mesi) e
  la *media al mese* divide per i soli mesi in cui la voce è comparsa. ⚠️ Una voce di **sole
  entrate** (uno stipendio, un rimborso) grafica le entrate invece della spesa, con l'etichetta
  che lo dice: disegnarne la spesa vorrebbe dire una fila di zeri chiamata statistica.
  Il blocco `openStatCategoria` è **identico nelle due app** — gli importi si leggono con due
  helper locali invece che con quelli della pagina, che lì hanno nomi diversi (`uscitaDi`/
  `entrataDi` contro `spesaDi`): se lo modifichi in una, riportalo nell'altra.
- ⚠️ **La regola si scrive quando si associa, in un popup, e non più di nascosto.** Assegnare una
  categoria a un movimento apre `openRegolaDaMovimento`, che mostra la descrizione per intero,
  propone la chiave (la descrizione normalizzata) e la lascia **accorciare col conteggio dal
  vivo** di quanti movimenti aggancerebbe; *Solo questo movimento* non scrive niente.
  `setTxCategory` ora **assegna e basta**. Prima imparava da sé con la descrizione intera, che è
  esattamente la regola che non aggancerà mai più niente — la banca infila in ogni acquisto un
  riferimento diverso — e la si scopriva inerte settimane dopo, andandola a cercare. Il popup non
  si apre quando una regola manda già quella descrizione in quella categoria (non c'è niente da
  imparare) né quando si toglie la categoria. ⚠️ **Nell'anteprima di import resta l'apprendimento
  silenzioso** a conferma (`confirmImport`): lì si categorizza a blocchi e una finestra per riga
  sarebbe inservibile.
- ⚠️ **`%` è il jolly della chiave**, come nel `LIKE` di SQL: `AMAZON%IT` prende *AMAZON.IT*,
  *AMAZON EU IT* e *AMAZON DE ... IT* con una regola sola (`matcherRegola`, con cache per chiave —
  `merchantRule` gira su ogni movimento per ogni regola). Il confronto di base resta «contiene»,
  quindi i `%` agli estremi non aggiungono niente. **Il jolly non è `*`**: l'asterisco compare per
  davvero nelle causali (`AMAZON.IT *MK7HG2LO3`, `ESSELUNGA *12345`) e prenderlo come jolly
  spaccherebbe di colpo ogni regola che ce l'ha dentro sul serio; `%` in una causale non si vede
  mai. Ne discende che **la precedenza non è più la lunghezza ma il peso** (`pesoRegola`: i
  caratteri al netto dei jolly), o `%A%` scavalcherebbe `ABC` pur non chiedendo quasi niente — per
  una chiave senza jolly peso e lunghezza coincidono, quindi le regole vecchie si ordinano come
  prima. La soglia minima di 3 caratteri è sul peso.
- La pagina **🧠 Attribuzione** (`renderRegole`) è dove le regole si leggono e si tarano: la
  spiegazione in sei passi di come si sceglie la categoria, un **banco di prova** che su una
  descrizione incollata mostra chiave, regole che agganciano e quale vince senza toccare niente, e
  l'elenco delle regole con quello che ciascuna fa davvero all'archivio. I due elenchi si aprono
  con le linguette 🏦/📊 (`S.ruleSource`), e **tutto quello che c'è nella pagina segue la linguetta
  aperta**: conteggi, banco di prova, filtri e la regola creata con *+ Nuova regola*. Una regola
  però **non cambia mai elenco**, nemmeno modificandola: una chiave scritta per una fonte, messa a
  valere su descrizioni fatte in un altro modo, è una regola che non aggancia più niente. Due colonne che non vanno
  confuse: **aggancia** sono i movimenti la cui descrizione contiene la chiave, **vince** quelli
  che la regola si prende davvero — una regola che aggancia e non vince mai è *coperta* da una più
  specifica, e senza la distinzione sembrerebbe funzionante. **In conflitto** sono i movimenti che
  la regola vince ma a cui è stata data un'altra categoria a mano: restano come sono — le regole
  non sovrascrivono mai — e ⚖️ li riallinea **solo su richiesta esplicita**, con la conferma che
  dice quanti e verso quale voce.
- Nei **Movimenti** l'anno è un **filtro a sé** (`S.filters.anno`) e non `S.selectedYear` della
  dashboard: i due sono indipendenti di proposito, così si guarda tutto l'archivio nell'elenco
  tenendo la dashboard sull'anno in corso. Tutt'e due ammettono **«tutti gli anni»** (`''`;
  `null` = mai impostato, e allora si parte dall'anno più recente).
- ⚠️ Con «tutti gli anni» la dashboard **cambia grafico**: *Andamento mensile* diventa *Andamento
  per anno*, una colonna per annata invece di dodici mesi. Le dodici colonne sommerebbero il luglio
  2024 e il luglio 2026 nella stessa barra — un numero che non vuol dire niente e che a guardarlo
  sembra vero. Il resto (KPI, ciambelle, tabelle) attraversa gli anni senza bisogno di niente, e
  *uscita media mensile* resta giusta perché `mesiConDati` conta i mesi distinti, non dodici.
- **🏷️ Assegna i N senza categoria** nei Movimenti dà la stessa categoria a tutti i movimenti
  senza categoria **fra quelli che i filtri stanno mostrando** — il filtro è la selezione. ⚠️ **Non
  è una regola e non ne scrive nessuna**: tocca quelle righe una volta sola, e i movimenti futuri
  degli stessi negozi continueranno ad arrivare senza categoria. Sistemare un arretrato e insegnare
  un negozio sono due operazioni diverse, e la seconda passa dal popup di `openRegolaDaMovimento`.
  La scrittura va in blocchi da 100 (`patchCategorie`, usata anche da `applyLearnedMerchants`): gli
  id sono uuid da 36 caratteri e PostgREST li vuole nell'URL con `in.(…)`, che oltre qualche
  centinaio di righe sfonda la lunghezza massima e fa fallire tutto il giro.
- La **zona pericolosa** in Impostazioni ha **tre comandi distinti**, che passano dalla stessa
  finestra di conferma (`openCancellazione`): *cancella tutti i movimenti* — categorie,
  super-categorie e regole restano — e **una cancellazione per elenco di regole**, indipendenti
  fra loro, che invece non toccano i movimenti: le categorie già assegnate restano dove sono,
  perché le regole valgono sui movimenti che una categoria non ce l'hanno. Si fa scrivere
  `CANCELLA` invece di un `confirm()` con l'OK a portata di clic: di qui non si torna indietro, e
  la banca ripropone solo il periodo che espone ancora.
- Il conto è quello spuntato come `'spese_sal'` in `cm_bank_connections.uses` — uso a sé e non
  `'spese_ada'` riusato, altrimenti le due pagine si troverebbero ciascuna il conto dell'altra
  nella tendina dell'import. Il conto UniCredit personale è già collegato: basta spuntare
  l'uso da Finanza → Configurazione → 🏦 Banche e Conti.

### `conto-spese-teresa.html` — Contribuzione
- Chi ha versato quanto sul conto delle spese comuni, e quanto dovrebbe aver versato: quote 2/5–3/5
  fino al 2023, 1/3–2/3 dal 2024. Dati in `acct_transactions` (`persona` `TERESA`|`SALVATORE`,
  `tipo` `BONIFICO`|`ALTRO`|`MENSA`, `importo` sempre positivo).
- Si apre dal collegamento *💰 contribuzione* nella sidebar di `finanza.html`.
- **Si alimenta dallo stesso conto che alimenta Spese Famiglia**, che va spuntato anche come
  `'contribuzione'` in `cm_bank_connections.uses` (gli usi si sommano sullo stesso conto: il CHECK
  è stato allargato da `20260808120000_cm_bank_connections_uses_contribuzione.sql`) — via
  `enable-banking-transactions`, che legge e basta: filtro
  (solo **entrate** riconosciute come bonifici), attribuzione a Teresa o Salvatore e controllo dei
  doppioni stanno nella pagina. L'import da CSV Revolut resta come ripiego per lo storico più
  vecchio di quanto la banca espone.
- L'attribuzione scende per gradini — IBAN della controparte, poi nome della controparte, poi testo
  della causale — e quello che non torna resta *da attribuire*: si sceglie a mano nell'anteprima,
  non si indovina. Gli indizi (IBAN o pezzi di nome) stanno in `localStorage` (`cst_person_hints`),
  modificabili da Impostazioni: sono una preferenza di lettura, non un dato della contabilità.
- I doppioni si riconoscono su data + importo: identici anche nella descrizione sono la stessa riga,
  stessa data e stesso importo con descrizione diversa è quasi sempre la stessa contribuzione già
  entrata da CSV. Entrambi i casi partono **deselezionati** — qui un doppione raddoppia una quota.

### `cost-analysis.html` — Analisi Costi
- **Non compare più tra le bolle della home**: `cm_apps.active = false` (migration
  `20260802180000_ca_readonly_teresa_and_hide_app.sql`). Resta l'unica app dove si importa, si
  categorizza e si configura (categorie, persone, regole, viaggi) — si apre dal
  collegamento *🛠️ Spese Famiglia — gestione* nella sidebar di `finanza.html` o dalla notifica
  Smart Block.
- **Non configura più i conti bancari**: censimento degli istituti, collegamento, consenso,
  battesimo dei conti ed eliminazione stanno in `finanza.html` → Configurazione →
  🏦 Banche e Conti, perché gli stessi conti servono anche ai Fondi e al Conto Risparmio. Qui
  compaiono in sola lettura i conti con `'cost_analysis'` in `uses`, nella pagina
  *Sincronizza e Carte*: il sync di Spese Famiglia non si può spostare perché subito dopo
  l'import fa girare merchant appresi, regole e attribuzione per carta, che vivono solo lì.
- La **consultazione** è stata spostata dentro le due app dove serve, come vista in sola lettura:
  `finanza.html` (Salvatore) e `situazione-teresa.html` (Teresa) — vedi sotto.
- **Negozi imparati** (pagina *Regole*, in cima): l'elenco di `ca_merchant_map` +
  `ca_merchant_map_categories`, che fino alla v1.0.71 si scriveva da solo e **non si vedeva da
  nessuna parte**. Ogni riga porta la chiave, la categoria, quante transazioni in archivio
  aggancia e quante di quelle hanno una categoria *diversa*; da lì si modifica, si dimentica e si
  propaga allo storico (🔁, che riusa il modale di `previewMerchantCategoryPropagation`).
  Filtri: *Da allineare*, *Senza categoria*, *Mai usati*.
  ⚠️ Tre cose che sono la ragione per cui la pagina esiste:
  - **il confronto è per uguaglianza sull'intera descrizione normalizzata**, non «contiene» come
    in `spese-ada.html` / `spese-personali.html`. Accorciare una chiave non allarga l'aggancio:
    la restringe a zero. Per allargare servono le **Regole**, che cercano il testo *dentro* la
    descrizione. Il campo del modale mostra dal vivo la chiave che verrà salvata e quante
    transazioni aggancia, perché una chiave «quasi giusta» altrimenti si scopre inerte solo dopo;
  - **le righe orfane** (negozio in `ca_merchant_map` senza figlia in
    `ca_merchant_map_categories`, che `learnMerchantCategories(desc, null)` lascia dietro) non
    categorizzano niente e prima erano invisibili: ora si vedono col filtro *Senza categoria*;
  - **imparare non è propagare.** Salvare un negozio vale per le transazioni future; quelle già in
    archivio si toccano solo confermando l'anteprima — la stessa regola per cui
    `ca_smart_block_set_category` impara e non propaga.
- **Navigazione con hamburger** come le altre app del conto familiare: su desktop la sidebar da
  280 px resta fissa, sotto i 768 px diventa un cassetto a scomparsa aperto dal ⬛ nella top bar
  (prima era una striscia di sole icone incollata sotto la barra blu, senza etichette). Il menù
  elenca le pagine dell'app e, dopo un separatore, i collegamenti alle altre pagine del conto
  familiare (Finanza, contribuzione, conto risparmio, Spese Ada, Casa Rosa): da qui non si
  tornava a nessun'altra pagina senza passare da `finanza.html`.

### Spese Famiglia: regole e vista in sola lettura — `spese-famiglia.js`
*(la vista si chiamava "Analisi Costi"; nelle due pagine è etichettata **Spese Famiglia**, mentre
l'app di gestione `cost-analysis.html` conserva il nome storico)*

✅ **Un file solo per tre pagine** (24 settembre 2026, `spese-famiglia.js` v1.0.0). Fino a lì le
stesse regole stavano scritte **tre volte**: un blocco CSS + JS identico in `finanza.html` e in
`situazione-teresa.html`, e una terza copia in `cost-analysis.html`, con l'avviso di cambiarle
insieme. Il file ha due parti, e le usano pagine diverse:

| Parte | Cosa | Chi la usa |
|---|---|---|
| `SF` | Le **regole**, funzioni pure che ricevono i dati: periodi, movimenti esclusi dai totali (`excluded_from_totals` della principale), totale di una categoria con o senza le figlie, riquadro Ricarica, torta per categoria, spesa degli ultimi sei mesi e per persona | tutt'e tre |
| `CA` / `ca*()` + `caStili()` | La **vista in sola lettura**: dashboard (spesa per categoria, andamento mensile, spesa per persona) e transazioni (filtri, elenco raggruppato per titolo, riepilogo voci entrate/spese). Nessuna scrittura sul DB | `finanza.html`, `situazione-teresa.html` |

**Una modifica alle regole si fa in `SF` e basta**: `cost-analysis.html` ne tiene i vecchi nomi
(`isTxExcludedFromTotals`, `dashboardTransactions`, `topCategoryId`) come involucri di una riga,
perché il resto della pagina li chiama già. Verificato prima di pubblicare: su 3.000 movimenti
casuali, cinque periodi e tutte le cifre della dashboard, le funzioni di `SF` danno gli stessi
numeri del blocco di prima (46 confronti, nessuna differenza).

⚠️ **È una deroga dichiarata alla regola «un'app = un file HTML»**, come `/vendor/` del Forziere,
e la ragione è la stessa del perché esiste: tre copie di una regola divergono in silenzio. ⚠️ Per
la stessa ragione **non va in cache**: `_headers` gli dà il `no-store`
delle pagine (su `/*`), o una pagina nuova girerebbe sulle regole vecchie.

⚠️ **Le regole ricevono i dati, non li leggono da uno stato globale**: ogni pagina tiene
l'associazione movimento → categorie a modo suo (`CA.txCatIdx` di qua, `txCategories` in
`cost-analysis.html`), quindi la si passa come funzione (`catIdsOf`). È quel che permette a una
sola copia di servire tre pagine.

⚠️ **La data dei periodi si scrive coi campi locali** (`SF.isoLocale`): `cost-analysis.html` la
scriveva con `toISOString()`, cioè in UTC, e il suo «Questo mese» cominciava **il giorno prima**
degli altri due — la mezzanotte italiana del primo del mese in UTC è l'ultimo giorno del mese
prima. Col file condiviso le tre dashboard dicono la stessa cifra, e quella di `cost-analysis.html`
può spostarsi di un giorno di movimenti rispetto a prima: è la correzione, non un difetto.

⚠️ **La vista dipende dalla pagina ospite solo al momento di aprirsi** (`caOpen`, mai al
caricamento del file): `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `tok()`, Chart.js e le classi
`.card` / `.kpi-*` / `.mono` / `.text-muted`. Le classi `.ca-*` invece le porta il file
(`caStili()`), una volta sola: erano 30 righe di CSS identiche nelle due pagine.

Teresa legge i dati via RLS con lo stesso meccanismo del resto della sua pagina
(`cm_guest_access` + `has_page_access('situazione-teresa.html')`). Le policy di lettura sulle
tabelle `ca_*` sono ristrette alle righe di Salvatore con `garsal_user_id()`: quelle tabelle **non
sono monoutente** — Ada ha i propri dati di Analisi Costi e non devono comparire.

### 📈 Portafoglio Conto Risparmio in `situazione-teresa.html` — logica portata da Finanza

La voce 📈 **Portafoglio** mostra a Teresa il portafoglio **CONTO RISPARMIO** di Finanza — valore,
liquidità, investito, P&L, le posizioni titolo per titolo e le quote del fondo collegato — con un
selettore `👥 Tutto · Teresa · Salvatore` che rifà ogni importo sulla quota scelta.

⚠️ **Le percentuali sono le QUOTE DEL FONDO, non `fnz_portfolios.ownership_percentage`**: quel
campo lo riscrive `syncFundOwnership()` con la sola quota del partecipante di riferimento, e
usarlo qui la conterebbe due volte. «Tutto» è quindi il portafoglio **intero**, che in Finanza non
si legge da nessuna parte — lì il valore è già in quota.

⚠️ **Quantità e prezzi restano interi, solo gli importi vanno in quota** — è la stessa scelta di
`renderPortafoglioDetail()` in `finanza.html`: il prezzo di un titolo è quello che è, e mostrarne
il 55 % sarebbe un numero che non esiste da nessuna parte.

⚠️ **Posizioni e liquidità vengono da `fnz_posizioni` / `fnz_liquidita`**, le stesse RPC di
Finanza, e la RLS ospite le restringe al Conto Risparmio. **Le quote pure**: `pfQuote` legge la
RPC `fnz_quote_fondi`, la stessa di `computeFundShares`, e non ne è più una copia.

⚠️ **I prezzi si leggono da `fnz_price_cache` e non da `fnz_price_history`**: è una riga per
simbolo e porta già la chiusura precedente, quindi dice le stesse cifre di Finanza (dove
`computePricesFromHistory` ricava lo storico da quella stessa cache, via trigger) senza scaricare
mesi di quotazioni su una pagina che le userebbe per una riga sola.

⚠️ **Niente colonna delle tasse**: sarebbe una terza copia delle aliquote, e qui nessuno la chiede.

⚠️ **La RLS è ristretta al solo Conto Risparmio**
(`20260905120000_guest_teresa_portafoglio_conto_risparmio.sql`): il perno è
`teresa_cr_portfolios()`, che torna i portafogli con `name ILIKE '%conto risparmio%'`;
da lì discendono movimenti, prodotti, simboli dei prezzi, il fondo collegato e i suoi versamenti.
`fnz_foi_index` si legge intera perché è l'indice ISTAT, un dato pubblico. ⚠️ **Rinominando quel
portafoglio senza la parola «risparmio» la sezione si svuota senza nessun errore**: non è un
difetto della pagina, è il filtro che non aggancia più.

### `spuntiamola.html` — Spuntiamola
- Conto alla rovescia "a spunte": si imposta un periodo **dal giorno X al giorno Y** e si spunta
  un giorno alla volta fino al traguardo
- Griglia dei giorni raggruppata per mese; ogni cella è cliccabile (spunta / de-spunta)
- Stati della cella: *fatto* (verde), *oggi* (bordo viola), *saltato* (rosso, giorno passato non
  spuntato), *da fare* (grigio)
- **Ogni spunta assegna un'emoji casuale al giorno** (resta lì, salvata) e mostra un **toast di
  ~2,5 s** con una frase simpatica + confetti; a 25/50/75/100 % scattano messaggi di traguardo
  dedicati (toast più grande, 4,5 s)
- Opzione "salta sabato e domenica" per contare solo i giorni feriali
- **Giornate chiave** (`sp_key_days`): giorni "che contano" segnati dalle impostazioni, con
  etichetta libera. Nella griglia sono dorate con una ★; alla spunta parte `fuochiArtificio()`
  — sei scoppi scaglionati, ognuno con lampo centrale e raggiera di scintille/stelle con
  gravità — più un toast dorato da 5 s. Le giornate chiave si modificano su una copia
  (`tmpKeyDays`) e si applicano solo con "Salva", così "Annulla" butta via tutto davvero.
  ⚠️ **Sono facoltative**, e la stecca si salva senza. Il tasto *Aggiungi* è un `btn-ghost`
  **spento finché non c'è una data** e non un `btn-primary` che a data vuota rispondeva
  `alert('Errore: scegli la data della giornata chiave.')`: sta a due righe dal *Salva* ed era
  identico a lui, quindi premuto per sbaglio al posto di quello quell'errore si leggeva come
  «non mi fa salvare se non metto una giornata chiave».
  ⚠️ **Una giornata chiave fuori dal periodo si salva e poi NON C'È**: la griglia disegna i
  giorni di `giorniDelPeriodo()`, e quel giorno lì dentro non compare — non si può spuntare e i
  fuochi non partiranno mai, senza che niente lo dica. `perchePuoNonEsserci()` la rifiuta
  all'aggiunta, spiegando quale dei due motivi è (fuori dall'intervallo, oppure un sabato o una
  domenica su una stecca che salta i fine settimana: la conseguenza è la stessa), e
  `inKeyDate` porta `min`/`max` così il calendarietto si apre già sul periodo. Il periodo si
  legge dai **campi del form** e non da `S`: lì si sta ancora scegliendo, e `S` porta quello di
  prima. Il periodo però si può accorciare **dopo**, e allora una chiave già salvata resta fuori:
  nell'elenco delle Impostazioni quella riga si marca in rosso invece di sparire in silenzio.
  ⚠️ **`.btn:disabled` non aveva nessuno stile**: un pulsante spento era identico a uno acceso —
  si premeva, non succedeva niente, e si leggeva come un tocco andato a vuoto. Riguardava anche
  *Chiudi la stecca* e *Cancella il traguardo*.
- **Memoria delle stecche** (`sp_stecche`, migration `20260810150000_sp_stecche.sql`): una stecca
  finita non sparisce più. Quando non c'è più niente da spuntare — tutti i giorni fatti *oppure*
  l'ultimo giorno passato — il pulsante della hero diventa *🏁 Chiudi la stecca* e parte una
  cerimonia in tre passi: **l'ultima spunta** (un bersaglio grosso che si preme una volta sola e
  lascia la sua emoji come sigillo), la **barra della soddisfazione da 1 a 100**, la **nota**.
  Solo allora la stecca finisce in archivio e il messaggio finale — pescato per fascia da
  `msgChiusura` dell'umore, dal consolatorio (`.toast.dolce`) al complimento vivo — arriva con i
  fuochi d'artificio di `fuochiFinali()`, da 8 a 24 scoppi più il gran finale a seconda di quanto
  si è soddisfatti. Le stecche chiuse si rileggono nella card *🏅 Le stecche chiuse*.
- **L'umore della stecca** (`sp_settings.mood`, prima scelta nelle Impostazioni): ⏳ **Attesa** o
  🌅 **Bei giorni**. La griglia, le spunte, i traguardi e la chiusura restano identici — cambia
  **con che voce l'app li commenta**, perché il tempo che passa avvicina qualcosa oppure lo porta
  via, e sono due letture opposte dello stesso numero. Seguono l'umore: le 30 frasi della spunta,
  le emoji che restano sul giorno, l'etichetta sotto il numero grande (*giorni che mancano* /
  *giorni che restano*), il badge in cima alla card, le due etichette del form (*Cosa stai
  aspettando?* / *Cosa stai vivendo?*), il banner di oggi, i quattro traguardi 25/50/75/100 % e i
  sei messaggi di chiusura.
  ⚠️ **Tutto passa da `MOODS`, una tabella di testi e non due rami di codice**: nessuna delle due
  voci è scritta a mano da un'altra parte, e un terzo umore sarebbe una voce lì dentro. `mood()`
  legge quello della stecca in corso; `moodValido()` riporta ad `'attesa'` un valore che non
  conosce — ed è per questo che **l'elenco dei due id è scritto dentro quella funzione** e non in
  una costante accanto a `MOODS`: la chiama anche l'inizializzazione di `S`, che gira prima che
  quelle `var` siano assegnate.
  ⚠️ **Al 100 % i fuochi d'artificio dipendono dall'umore** (`fuochiAl100`): su una stecca di bei
  giorni l'ultimo giorno è un addio, e festeggiarlo coi fuochi suonerebbe come una presa in giro —
  i coriandoli restano, che sono un saluto.
  ⚠️ **`festeggiaChiusura(sodd, voce)` riceve l'umore come parametro e non da `mood()`**: gira
  dopo che la stecca è stata archiviata, quando `S` descrive già il campo libero, e salutare dei
  bei giorni con la voce dell'attesa sarebbe il modo peggiore di finirli.
- **La chiusura scrive prima e cancella dopo**: `dbCloseStecca()` inserisce in `sp_stecche` e solo
  se l'insert riesce svuota `sp_checks`, `sp_key_days` e `sp_settings`. Non è l'ottimismo con
  rollback usato per le spunte, di proposito: nell'ordine inverso una rete che cade cancellerebbe
  il periodo senza averne salvato la memoria, cioè il difetto che la funzione esiste per togliere.
  Finita la chiusura non c'è più nessun periodo (`S.configured = false`) e `salvaCache()` **svuota
  le chiavi `sp_*` di localStorage**, altrimenti al riavvio `dbLoad()` scambierebbe la cache per
  dati locali da ricaricare e resusciterebbe la stecca appena archiviata.
- **Cancellare un traguardo è l'opposto di chiuderlo**, e sono due comandi diversi:
  *🗑️ Cancella il traguardo* (Impostazioni → zona rossa, `dbDeleteGoal()`) butta via periodo,
  spunte e giornate chiave **senza scrivere niente in `sp_stecche`** — è l'uscita per un traguardo
  sbagliato o abbandonato; il cestino su ogni scheda di *🏅 Le stecche chiuse* (`dbDeleteStecca()`)
  toglie invece una stecca già archiviata. Tutt'e due cancellano **prima sul DB e poi in locale**,
  al contrario dell'ottimismo con rollback delle spunte: sparita solo dall'app, la riga tornerebbe
  da sé al primo `dbLoad()` e sembrerebbe che la cancellazione non funzioni. Cancellato il
  traguardo, `S` torna ai valori di partenza (`goal`, `emoji`, date) e non solo a
  `configured = false`: le impostazioni si riaprono su un foglio bianco invece di riproporre
  quello appena buttato. ⚠️ **Ci sono anche in nativo** (APK 1.0.107): «🗑️ Cancella il traguardo»
  in fondo alle Impostazioni (solo se un traguardo c'è) e il 🗑️ in testa a ogni stecca chiusa —
  `SpuntiamolaRepository.cancellaTraguardo` / `cancellaStecca`, gemelle di `dbDeleteGoal` /
  `dbDeleteStecca`, con lo stesso ordine (prima il DB) e gli stessi testi di conferma.
  «🧹 Cancella tutte le spunte» invece resta solo nel web.
- **Avviso "oggi non spuntato"** in due punti: banner giallo dentro l'app e sezione dedicata nel
  fumetto avvisi di AppSphere (`loadHomeAlertSpuntiamola` in `index.html`). Se oggi è una
  giornata chiave entrambi gli avvisi lo dicono esplicitamente.
- **Top bar standard** `#garsal-top-bar` come le altre app (barra fissa blu `#0081C8` alta 56 px,
  logo a cerchi olimpici + "Garsal Apps" con `href="/"`); a destra restano badge di sincronizzazione,
  versione e il pulsante ⚙️ Impostazioni
- **Dati su Supabase** (`sp_settings` + `sp_checks`, migration `20260728100000_sp_spuntiamola_tables.sql`):
  il DB è la fonte di verità, `localStorage` (chiavi `sp_*`) resta come cache offline così la
  griglia compare subito all'apertura. Al primo avvio dopo l'aggiornamento le spunte già presenti
  in locale vengono caricate sul DB una volta sola.
- Le scritture sono **ottimistiche con rollback**: se la chiamata al DB fallisce la spunta viene
  tolta e compare un toast di errore, così non resta una spunta finta che sparisce al reload
- Registrata in `cm_apps` da `20260727230000_spuntiamola_app.sql`; la `score_query` (aggiornata
  dalla migration `sp_`) conta i **giorni che mancano**, quindi la bolla nel launcher è proporzionata.
  ⚠️ Quel numero **dimensiona la bolla ma non si scrive più sotto il nome e non fa punti**: è un
  conteggio, e la sua riga di `cm_apps` ha `conta_punti = false` — vedi *AppSphere nativa → Non tutti i
  numeri di `score_query` sono punti*

### `sos.html` — SOS
- **Non è l'app: è la sua configurazione.** Il SOS si preme sul telefono
  (`android-app/sos/`); qui si decide che cosa dice, quanto dura e quanto vale.
- Tre schede: **🆘 I miei SOS** (creazione e modifica), **📱 Telefoni** (i codici di
  accoppiamento dell'APK), **📊 Storico** (KPI e l'elenco dei giri).
- Un SOS ha un nome, che cosa si sta fronteggiando, una durata **di partenza** e due estremi.
  Nella stessa finestra si scrivono le **risposte** alla domanda finale — emoji, testo, punti,
  percentuale — e le **frasi** che scorrono sotto il countdown, una per riga.
- ⚠️ **Modificare un SOS non tocca `current_seconds`**: è dove l'hanno portato gli esiti, e
  riscriverlo dal form butterebbe via la storia dei giri fatti. Si riporta alla partenza col
  pulsante *↺ Riporta a N min*, che lo dice e chiede conferma. Se però il nuovo minimo/massimo
  esclude il valore corrente, quello viene riportato dentro gli estremi — altrimenti il CHECK
  della tabella rifiuterebbe il salvataggio.
- Le durate si scrivono **in minuti interi** e si archiviano in secondi: mm:ss in un form si
  sbaglia, e i secondi servono solo perché le percentuali li producono (−10 % di 10 minuti = 9:00).
- Il **codice del telefono si vede per intero una volta sola**, alla creazione. Dopo restano le
  ultime quattro lettere: è quanto basta per riconoscere *quale* codice è, che è la sola domanda
  a cui serve rispondere guardando l'elenco. Un telefono perso si chiude revocando il codice.
- Nello **Storico ogni giro si cancella** (🗑 in fondo alla riga, `eliminaGiro`). ⚠️ Non è pulizia
  di schermo: la `score_query` di SOS somma i punti dei giri chiusi, quindi la riga che sparisce
  si porta via i suoi punti dalla bolla in home e dal totale che paga i premi — la conferma lo
  dice con la cifra davanti. ⚠️ **La durata del prossimo giro non torna indietro**: la percentuale
  della risposta è stata applicata a `sos_types.current_seconds` quando il giro si è chiuso, e
  quel valore è dove l'hanno portato *tutti* i giri, non solo quello. Rifarne il conto
  all'indietro vorrebbe dire riapplicare in ordine tutta la storia; il tempo si riporta alla
  partenza col pulsante *↺ Riporta a N min*, che esiste apposta. La cancellazione è per riga: si
  vedono (e si cancellano) i soli giri **chiusi**, non le sessioni rimaste aperte per una app
  chiusa a metà countdown.
- ⚠️ **Il numero di SOS è un punteggio vero**, non un conteggio: la sua riga ha `conta_punti = true`,
  quindi si scrive nella bolla e fa parte del totale (vedi *AppSphere nativa → Non tutti i numeri
  di `score_query` sono punti*). È la ragione per cui cancellare un giro ha un prezzo.

---

## Backup settimanale — il dump e la relazione

`.github/workflows/backup.yml` gira **ogni domenica** (cron `0 2 * * 0`, cioè le 04:00 in ora
legale) e si può lanciare a mano da Actions. Fa due cose diverse:

| Cosa | Come | A che serve |
|---|---|---|
| **Dump** | `supabase db dump` in tre file gzippati: `schema.sql.gz`, `dati.sql.gz`, `ruoli.sql.gz` | Rimettere in piedi il database |
| **Relazione** | `scripts/backup-report.mjs` → `relazione.html` | Sapere **cosa c'era dentro** quella settimana |

### ⚠️ Nel repository non resta niente, e la ragione adesso è il SITO

⚠️ **Dal 6 ottobre 2026 si lavora su `garsal-apps-public`, che è pubblica** (vedi *La repo*,
in cima), e `build-sito.sh` pubblica le sole pagine. Quel che segue è com'era prima, e la regola
resta: in repo non va niente che non si vorrebbe leggere su GitHub da chiunque.

⚠️ **`garsal1971/garsal-apps` è PRIVATA dal 13 settembre 2026.** Fino a quel giorno era
pubblica, ed era quella la ragione scritta qui. La regola però **non è cambiata**, perché la
ragione era doppia e la seconda metà vale ancora, anzi di più: **quel che sta nella repo sta
anche sul sito**. `scripts/build-sito.sh` copia la radice dentro `dist` escludendo le sole
`*.apk` (e `netlify.toml` pubblicava già `publish = "."`), quindi ogni file committato è
scaricabile da `garsal.men` — che è pubblico e lo resta. Un redirect non lo impedisce: un file
fisico ha la precedenza.

Patrimonio, spese, reddito e task non ci vanno quindi **né su master né su un ramo a parte**. Un
ramo `backups` era stato scritto e ritirato prima di girare una sola volta: è la soluzione che
sembra prudente e non lo è — e oggi sarebbe *meno* visibile ma non meno pubblicata, perché a
pubblicare è il build del sito e non la pagina del repo.

⚠️ **Ne discende che «la repo è privata» non è mai una risposta sufficiente** alla domanda «ci
posso mettere questo?». La domanda giusta è: lo voglio scaricabile da `garsal.men`? Se no, non
va committato — o va escluso in `build-sito.sh` come le APK, e allora l'esclusione è parte
della stessa modifica.

Cosa è cambiato davvero col passaggio a privata: i **minuti di GitHub Actions** smettono di
essere illimitati (~1.000 al mese misurati, contro i 2.000 del piano Free — le build APK sono la
parte grossa), e le **issue** diventano leggibili solo da chi ha accesso alla repo: era la
condizione per cui esisteva 🐙 *Manda a Claude* in `modifiche.html`, ora tolta. Il resto non si accorge di
niente: il sito lo costruisce Cloudflare Pages anche da una repo privata, le APK si scaricano da
R2 e le schede `-latest.json` da `garsal.men` — nessun telefono chiede niente a GitHub.

Dump e relazione salgono quindi su **Google Drive** (`scripts/backup-drive.py`), in
`AppSphere/backups` sull'account di Salvatore — sorella di `AppSphere/rooms`, che è il
Forziere. ⚠️ **Le due restano due cartelle e non si mescolano**: qui ci sono dump del
database in chiaro-gzip e c'è una **rotazione che cancella**, e un forziere finito sotto
quella rotazione sparirebbe. La cartella si punta **per id** (`GDRIVE_FOLDER_ID`), quindi
rinominarla o spostarla su Drive non tocca niente — il forziere invece si cerca per nome, e
lì rename e codice vanno insieme. ⚠️ **Senza i segreti di Drive il job si ferma e non c'è
nessun ripiego**: lasciarli «per intanto» nel repo sarebbe esattamente la cosa che questo passo esiste
per impedire.

⚠️ **Un refresh token, non un account di servizio.** Un service account non ha spazio proprio su
Drive: caricando in una cartella condivisa da un account Google personale il file resterebbe di
sua proprietà e la richiesta fallisce con `storageQuotaExceeded`. Funziona solo su un Drive
condiviso, che è roba di Workspace. Col refresh token dell'account personale i file nascono
**suoi**, nel suo spazio.

⚠️ **I nomi sono piatti** (`2026-09-06-relazione.html`), non una cartella per data: la data si
legge dal nome, l'elenco arriva già ordinato e la rotazione è un confronto di stringhe. Si
tengono le ultime 12 **date** (`QUANTE_TENERE`) — le date e non i file, o un giro che ha
prodotto un file solo farebbe sparire una fotografia intera.

⚠️ **Un dump fallito non porta giù la relazione.** Il passo del dump non fa morire il job: segna
`DUMP_ESITO=ko`, la relazione si scrive lo stesso, su Drive sale quel che c'è, e il verdetto
arriva nell'ultimo passo — così il run è rosso *e* i dati leggibili ci sono comunque.

### Come si legge: `relazione.html` + `backup-drive`

`relazione.html` (voce **🗄️ Backup** nel ☰ di AppSphere) elenca le fotografie e apre la relazione
scelta. Non parla con Drive: passa dalla Edge Function **`backup-drive`**.

⚠️ **La pagina non può parlare con Drive da sé**, e non è una complicazione gratuita: servirebbe
un token Google con lo scope `drive.readonly` — il permesso di leggere **tutto** il Drive di
Salvatore, chiesto al login di ogni app — per arrivare a una cartella sola. Con la Edge Function
la credenziale sta nei Secrets e la pagina presenta il suo JWT Supabase.

⚠️ **Il JWT si verifica contro Supabase** (`/auth/v1/user`), non si decodifica e basta: un JWT si
scrive a mano in dieci secondi, e questa funzione apre il patrimonio di famiglia. Passa il solo
`BACKUP_EMAIL` — Teresa, Rosa e Ada hanno un login valido e qui non c'entrano.

⚠️ **Si legge e basta**: nessuna scrittura, nessuna cancellazione. La rotazione la fa il workflow,
che è l'unico posto dove qualcosa si cancella — una funzione raggiungibile dal browser che sa
cancellare i backup è un backup che un giorno non c'è più. E il download controlla che il file
stia **in quella cartella**: senza il controllo sul padre, un id qualsiasi aprirebbe qualunque
file del Drive, cioè proprio quello che si è evitato non chiedendo `drive.readonly`.

⚠️ **Dalla funzione passa solo la relazione, non i dump**: un gzip restituito come testo sarebbe
una stringa rotta e un megabyte di JSON per niente. I dump si scaricano da Drive, e la pagina ci
mette il collegamento. La relazione si apre in un `iframe` con `srcdoc` e `sandbox`: porta il suo
CSS e non si mescola con quello della pagina.

⚠️ **Dentro `srcdoc` ci vuole `<base href="about:srcdoc">`, o l'indice non scorre: naviga.** Un
documento in `srcdoc` non ha un indirizzo proprio e **eredita quello della pagina che lo
contiene**, quindi un `#tasks` dell'indice si risolve in `…/relazione.html#tasks` e l'iframe va
lì — cioè ricarica `relazione.html` dentro sé stessa, dove la sandbox non concede JavaScript:
resta su «Carico…» per sempre, e si legge come una sezione che non finisce di caricare. La base
la scrive `backup-report.mjs` dalla v1.0.2, e `conBase()` in `relazione.html` la aggiunge alle
relazioni **già su Drive**, che non ce l'hanno: senza, una fotografia vecchia resterebbe non
sfogliabile per sempre.

### ⚠️ La relazione NON contiene i riservati

Le schede di Memo, i gruppi di Events Log e i task marcati `riservato` si **contano e non si
elencano**. La modalità nascosta esiste perché quella roba non si legga di sfuggita. Nel **dump**
ci sono per forza — quello è un backup, non una lettura.

⚠️ Il filtro è nella **query** e non nel disegno (`filtroRiservato()`), come in `memo.html` e in
`events-log.html`, e va scritto `or=(riservato.eq.false,riservato.is.null)`: la colonna è arrivata
dopo le righe, quindi le vecchie hanno NULL e con la sola uguaglianza sparirebbero tutte.

⚠️ **`cm_apps.riservato` è un'altra cosa e non filtra niente qui**: quella spunta nasconde la
*bolla* nel launcher, non i dati — Finanza è marcata così — e la relazione di Finanza è
esattamente ciò per cui questa relazione esiste.

### ⚠️ Lo schema si legge, non si indovina

Metà di queste tabelle non sta in nessuna migration (`ts_*`, `hb_*`, `el_*`, `ps_*`,
`cm_categories`, `cm_priorities`, `cm_rewards`…): sono nate a mano in produzione, e i nomi delle
colonne non sono verificabili dal repo. Lo script legge quindi lo **spec OpenAPI di PostgREST**
(`GET /rest/v1/`) e chiede solo colonne che esistono davvero; dove il nome può variare passa da
`campo(riga, 'title', 'name', 'nome')`. Una tabella che non c'è dà una **riga che lo dice** nella
sezione *⚠️ Cosa non è stato letto*, non una relazione che si interrompe a metà.

Per la stessa ragione c'è la sezione **📊 Inventario**, che conta le righe di **ogni** tabella
dello schema: è la rete che impedisce a una tabella nuova di restare invisibile finché qualcuno
non la aggiunge a mano alle sezioni.

⚠️ **Ogni sezione è indipendente**: una che salta non porta giù le altre. Una relazione parziale
che dice cosa manca vale più di nessuna relazione.

### ⚠️ I punti passano da `backup_scores`, non da una seconda formula

`cm_apps.score_query` è SQL scritto in tabella che parla di `auth.uid()`. Il backup gira con la
**service key**: lì `auth.uid()` è NULL — ogni conteggio tornerebbe zero — e `run_score_query`
rifiuta comunque chi non presenta un JWT con l'email di Salvatore.
`backup_scores(p_user)` (`20260904120000_backup_scores.sql`) esegue **la stessa** query di
`cm_apps` sostituendo `auth.uid()` con l'uuid passato: non è un secondo modo di calcolare i punti,
che sarebbero due punteggi diversi il giorno che uno dei due cambia.

⚠️ **EXECUTE al solo `service_role`**: la funzione esegue SQL arbitrario preso da
`cm_apps.score_query`, esattamente come `run_score_query_unrestricted`, e la guardia non è un
controllo dentro la funzione ma il permesso. Da `anon` e `authenticated` non si raggiunge affatto.

⚠️ **Quali numeri sono punti lo legge da `cm_apps.conta_punti`**, la stessa colonna delle due
home: fino al 24 settembre 2026 era una terza copia a mano dell'elenco, e se divergeva il totale
della relazione non era quello della home.

### I segreti, e i due posti in cui vanno

| Segreto | GitHub | Supabase | Serve a |
|---|---|---|---|
| `SUPABASE_ACCESS_TOKEN` | ✔ | — | `supabase db dump` (già usato da `deploy.yml`) |
| `SUPABASE_SERVICE_KEY` | ✔ | — | Leggere i dati per la relazione |
| `GDRIVE_CLIENT_ID` | ✔ | ✔ | Client OAuth di Google Cloud |
| `GDRIVE_CLIENT_SECRET` | ✔ | ✔ | idem |
| `GDRIVE_REFRESH_TOKEN` | ✔ | ✔ | Caricare e leggere **come Salvatore** |
| `GDRIVE_FOLDER_ID` | ✔ | ✔ | La cartella dei backup, cioè `AppSphere/backups` (dall'indirizzo di Drive) |
| `GDRIVE_APPSPHERE_FOLDER_ID` | — | ✔ | La cartella `AppSphere` che le tiene insieme. **Solo Supabase e facoltativo**: lo legge `forziere-drive` per sapere dove nasce una cartella nuova |

⚠️ **Gli stessi quattro valori vanno in tutt'e due i posti**: il workflow carica, la Edge Function
legge. Se divergono, i backup si scrivono in una cartella e la pagina ne guarda un'altra — e non
lo dice nessuno, perché l'elenco torna semplicemente vuoto.

Il refresh token si ottiene **una volta sola** con `scripts/drive-refresh-token.py`, da
eseguire sul proprio PC: chiede id e segreto del client *Applicazione desktop*, apre il browser
per il consenso e stampa il token, dopo averlo **provato**. Non scrive niente da nessuna parte.

⚠️ **L'app va portata in Produzione** (Google Auth Platform → Pubblico → *Pubblica app*): in
stato «Test» Google fa scadere i refresh token dopo **7 giorni**, e il backup fallirebbe la
seconda domenica con `invalid_grant` senza che il codice c'entri niente.

⚠️ `drive.file` basta e avanza — dà accesso ai **soli file creati da quel client**, cioè i backup,
e non a tutto il Drive; ed è non sensibile, quindi pubblicare non chiama in causa la verifica di
Google, che `drive.readonly` invece richiederebbe.

⚠️ Nello script `access_type=offline` **e** `prompt=consent` ci sono tutt'e due: senza il primo
Google non manda nessun refresh token, senza il secondo non lo manda **dalla seconda volta in
poi** — e si guarda una risposta che sembra riuscita e non ha il campo che serve. Il redirect è
un **loopback su porta a caso**, che per un client desktop non va registrato: il vecchio
`urn:ietf:wg:oauth:2.0:oob` è dismesso e risponde 400.

Nessuna password del database: come in `deploy.yml`, la CLI si crea da sola un ruolo di login
temporaneo, e **non si usa `supabase link`** — chiama `/v1/projects/{ref}/api-keys`, che dal
7 agosto 2026 risponde con un errore del suo stesso schema e porterebbe giù tutto il job. I due
file che `link` metteva in `supabase/.temp` si scrivono a mano.

`backup.sh` e `backup.ps1` restano quello che erano: il dump **a mano** dal proprio PC, che chiede
la password. Non c'entrano con questo giro e non vanno tenuti allineati.

---

## Development Workflow

### No build step
Edit the HTML file directly. Refresh the browser. Done.

```bash
# Open a file locally — no server required for most features
open tasks.html

# Or use a local HTTP server for auth redirect flows
python3 -m http.server 8080
```

### Ambiente di sviluppo (dev environment)

Il repository include un ambiente dev separato dalla produzione:

**Server locale:**
```bash
bash server.sh        # avvia su http://localhost:8080
bash server.sh 3000   # porta personalizzata
```
Quando si accede da `localhost`, le app rilevano automaticamente `_IS_DEV = true` e usano il progetto Supabase dev.

**Credenziali dev nei file HTML:**
Ogni file HTML contiene un blocco `_IS_DEV` che switcha le credenziali Supabase in base all'hostname:
```js
const _IS_DEV = ['localhost', '127.0.0.1', '0.0.0.0'].includes(window.location.hostname)
             || (window.location.hostname.endsWith('.pages.dev')
                && window.location.hostname.split('.').length > 3);
const SUPABASE_URL = _IS_DEV ? 'https://DEV_SUPABASE_PROJECT_REF.supabase.co' : 'https://jajlmmdsjlvzgcxiiypk.supabase.co';
const SUPABASE_KEY = _IS_DEV ? 'DEV_SUPABASE_ANON_KEY' : '<PROD_KEY>';
```
**I placeholder `DEV_SUPABASE_PROJECT_REF` e `DEV_SUPABASE_ANON_KEY` devono essere sostituiti** con le credenziali reali del progetto Supabase dev creato su [supabase.com](https://supabase.com).

**Setup iniziale del progetto Supabase dev (una tantum):**
1. Creare un nuovo progetto su [supabase.com](https://supabase.com) (piano gratuito va bene)
2. Replicare lo schema: `supabase db push --project-ref <DEV_PROJECT_REF>`
3. In Auth → URL Configuration → Redirect URLs, aggiungere: `http://localhost:8080`

**Branch naming:**
- `claude/<descrizione>-<id>` — produzione, auto-merge su master

Le anteprime di Cloudflare Pages (`<ramo>.<progetto>.pages.dev`) usano Supabase dev.

### ⚠️ Il trasloco su Cloudflare e il dominio `garsal.men`

Netlify è passato ai **crediti**: il piano Free dà **300 crediti al mese con tetto rigido**, e un
**deploy di produzione ne costa 15** — cioè **20 pubblicazioni al mese**. Questa repo ne fa ~65
(38 merge su master + 27 commit di APK, misurati su 30 giorni), quindi la dotazione finisce in
poco più di una settimana e da lì il sito resta **congelato all'ultima build riuscita** — le
pagine restano online, ma quello che si mette su master non esce più. È successo il 10 settembre
2026 con la v1.6.0 del Forziere.

⚠️ **Quel che costa è la pubblicazione su master, non il lavoro**: deploy preview, branch deploy
e deploy falliti **non consumano crediti**. Banda e richieste sono briciole (1,4 e 0,1 crediti
contro i 300 dei deploy): il peso degli APK **non c'entra**, i loro *commit* sì.

**Il vincolo che decideva tutto non era Netlify: era il dominio.** `garsal.netlify.app` stava
scritto dentro ogni APK — l'indirizzo che la WebView apre, il controllo aggiornamenti, il
callback OAuth e gli App Links del manifest — quindi cambiare host voleva dire lasciare ogni
telefono già installato su un indirizzo che non è più il sito. Con un dominio proprio
(**`garsal.men`**, ~12 €/anno contro i ~108 $/anno del piano Personal) l'hosting torna una cosa
che si cambia senza toccare le app.

⚠️ **Il modo in cui si romperebbe è il peggiore**: il sito Netlify **resta online** anche a
crediti finiti — è quello che i crediti operativi fanno — congelato alla versione vecchia. Un
APK che continuasse a guardarlo non vedrebbe un errore: leggerebbe per sempre la vecchia scheda
`-latest.json` e direbbe **«sei aggiornato»**.

**L'ordine dei passaggi, e cosa NON sta nella repo:**

1. il dominio su Cloudflare, e il sito su **Pages** collegato a questa repo;
2. ⚠️ **Supabase → Auth → Redirect URLs**: aggiungere `garsal.men` **prima**, e togliere il
   vecchio **dopo** che tutti i telefoni sono passati — al contrario, gli APK ancora vecchi non
   riescono più a fare il login;
3. gli APK nuovi si installano **a mano** (si scaricano dalla repo, dove ogni build li committa):
   ⚠️ **non serve nessun ultimo deploy da Netlify**, che a crediti finiti non si potrebbe
   nemmeno fare;
4. ⚠️ **prima che `garsal.men` serva davvero il sito, gli APK nuovi non vanno installati**:
   aprirebbero un indirizzo che non risponde.

⚠️ **Cloudflare Pages ha un limite di 25 MiB per file, ed è CONFERMATO**: il primo deploy si è
fermato con `Error: Pages only supports files up to 25 MiB in size`. Due APK ci stanno sopra —
`GarsalApps` 57,5 MB e `AppSphereNative` 45,5 MB — e il file troppo grosso **non fallisce da
solo: fallisce l'intero deploy**, cioè il sito non esce affatto.

⚠️ **Le APK sono quindi tutte fuori dal sito e vivono su R2**, `apk.garsal.men`. Tutte e non le
sole due grosse: una regola «le due grosse» aspetterebbe solo il giorno in cui una terza passa i
25 MiB, di nuovo con l'intero sito che non si pubblica. Le schede `-latest.json` restano invece
sul sito — sono qualche centinaio di byte, e `_headers` gli dà già il `no-store` che serve perché
non raccontino la build di ieri.

⚠️ **Due indirizzi, e la divisione è precisa**: la **scheda** dice quale versione c'è e sta sul
sito (`garsal.men/releases/<Nome>-latest.json`), il **pacchetto** si scarica da R2
(`apk.garsal.men/<Nome>-latest.apk`). Nei cinque `Aggiornamento.kt` sono due costanti accanto,
`SITO` e `APK`; in `comandi.html` la scheda si legge con `fetch('/releases/…')` e il pulsante
punta a `apk.garsal.men`. Sul bucket i nomi sono **piatti**, senza cartelle: gli APK già
installati chiedono quell'indirizzo lì, e un prefisso sarebbe un altro indirizzo.

⚠️ **Le carica `scripts/apk-su-r2.sh`, chiamato dai nove workflow di build**, e il passo sta
**prima** della pubblicazione su master: il commit pubblica la scheda che ANNUNCIA quella
versione, e annunciarla prima che il pacchetto sia scaricabile vuol dire un aggiornamento che si
offre e poi dà 404. È lo stesso ordine di «prima Drive, poi la riga» del Forziere. In tre
workflow (Smart Blocker, SOS, Spese in giro) la copia in `/tmp` stava *dentro* il passo di
pubblicazione ed è stata spostata prima, o il caricamento non troverebbe nessun file.

⚠️ **L'APK non si carica più come ARTIFACT di Actions** (20 settembre 2026): era una **terza
copia** — le due che contano sono R2, da cui si scarica, e il commit su master, da cui si legge
in archivio — durava 30 giorni e non la scaricava nessuno. Da quando la repo è privata lo spazio
degli artifact è **contato** (da pubblica era illimitato), e con nove workflow che ci lasciavano
30-70 MB a build si è riempito: `upload-artifact` ha cominciato a rispondere *«Artifact storage
quota has been hit»* — cioè a **far morire il job**.

⚠️ **E il guaio è stato l'ORDINE, non la copia persa**: quel passo stava **prima** del
caricamento su R2 e del commit su master, quindi morendo se li portava dietro tutt'e due — l'APK
era compilata e non pubblicata, e la scheda `-latest.json` continuava ad annunciare la versione
di prima. Dal telefono si legge come **«non c'è nessun aggiornamento»**, che è indistinguibile da
una build mai partita: è successo alla v1.0.85 del nativo. Un passo che non serve a nessuno non
va messo davanti a quelli che pubblicano.

⚠️ **Resta un artifact solo, ed è l'eccezione dichiarata**: `keystore-setup` in
`build-android.yml`, che ha un `if:` davanti e gira **solo** quando il keystore viene generato al
volo, cioè mai da quando i quattro secrets ci sono. È l'unica via d'uscita per quella chiave —
senza, l'APK sarebbe firmata con un keystore che nessuno può più recuperare, e il prossimo
aggiornamento non si installerebbe sopra al precedente. (`user-tests.yml` carica i propri
risultati e non c'entra: sono kilobyte.)

⚠️ **`--remote` in `wrangler r2 object put` non è facoltativo**: senza, wrangler scrive nella
copia locale simulata e il comando **riesce** senza che sul bucket vero arrivi niente — cioè un
caricamento che non si vede fallire.

⚠️ **E nemmeno `--cache-control 'no-cache'`** (13 settembre 2026): il nome sul bucket non cambia
mai — `Modifiche-latest.apk` è sempre lo stesso indirizzo — quindi senza quell'header Cloudflare
serve l'oggetto della build **precedente**, e una build nuova resta invisibile dietro un indirizzo
identico. Dal telefono si vede come **«ho aggiornato e scende sempre la stessa versione»**, che è
indistinguibile da una build non partita: la v1.0.1 di Modifiche era su R2 (run riuscito, scheda
pubblicata, 2.929.106 byte) e il telefono scaricava la v1.0.0. ⚠️ **Non spegne la cache**: obbliga
a **rivalidare**, quindi appena l'ETag cambia scende il pacchetto nuovo e per il resto la banda si
risparmia. Il `?v=<versione>` che i client mettono in coda resta la seconda rete — ma vale solo
per chi passa dal pulsante, non per un indirizzo dettato a voce.

⚠️ **`.github/workflows/apk-su-r2.yml` è a mano e fa due mestieri**: la **semina** — il giorno
del trasloco il bucket è vuoto e le APK già in archivio nessuno le ricompila, quindi senza un
giro esplicito `apk.garsal.men` risponderebbe 404 su tutto — e il **rimedio**, quando il
caricamento dentro una build fallisce e la scheda sul sito annuncia una versione che non si
scarica. Non gira su push: rifare tutte le APK a ogni commit sarebbe ~190 MB per niente.

⚠️ **I due segreti sono `CLOUDFLARE_API_TOKEN` (permesso *Workers R2 Storage: Edit*) e
`CLOUDFLARE_ACCOUNT_ID`**, su GitHub. Senza, il passo fallisce e il run è rosso — che è quel che
deve fare: un caricamento saltato in silenzio è una scheda che annuncia un 404.

⚠️ **`finanza.html` linka `releases/GarsalFinanza-latest.apk`, che non esiste e non è mai
esistito**: nessun workflow lo produce e non sta in `releases/`. Era già un 404 prima del
trasloco ed è rimasto tale — non è stato puntato su R2 di proposito, perché lì quel file non c'è
e cambiargli host direbbe che c'è.

⚠️ **L'esclusione la fa `scripts/build-sito.sh`**, non un'impostazione della dashboard: su Pages
il *build command* è `bash scripts/build-sito.sh` e la *build output directory* è `dist`. Lo
script copia la radice con `tar` — `cp -r` non sa escludere e rsync non è garantito
sull'immagine di build — e alla fine **elenca le APK che ha lasciato fuori**, col loro peso: una
regola che toglie file dal sito senza dire quali è una regola che un giorno toglie quello
sbagliato.

**`_headers` e `_redirects`** erano il gemello di `netlify.toml`, che è stato **tolto il
6 ottobre 2026** insieme a `deploy-dev.yml`: ora sono gli unici. Una scelta voluta: il `no-store` è su `/*` e non su `/*.html`, perché
che Cloudflare accetti un jolly a metà percorso non è verificato — e sbagliare da quella parte
vuol dire pagine in cache, cioè la WebView ferma su una versione vecchia col login rotto. Con
`/*` il caso peggiore è qualcosa che non va in cache: si perde velocità, non si rompe niente.

⚠️ **Il riconoscimento dell'ambiente comprende `.pages.dev`, ma SOLO LE ANTEPRIME**, ed è la
modifica più insidiosa di tutto il trasloco — in tutt'e due i sensi. Le anteprime di Pages si
chiamano `<ramo>.<progetto>.pages.dev` e con la sola regola `dev--*.netlify.app` sarebbero cadute
nel ramo **produzione**, cioè si sarebbe provato scrivendo sui dati veri senza che niente lo
dicesse. Ma **il sito ha lo stesso suffisso**, `garsal-apps.pages.dev`, e col solo `endsWith` ci
cadeva dentro pure lui — in mezzo al guado. Le si distingue **contando le parti**: quattro
l'anteprima, **tre il sito**.

```js
|| (h.endsWith('.pages.dev') && h.split('.').length > 3)
```

⚠️ **Il sintomo non nomina niente di tutto questo**, ed è la ragione per cui va scritto qui: dal
sito su `pages.dev` la password giusta veniva **rifiutata**, perché l'app parlava col database di
sviluppo e lì quell'utente non esiste. Non un errore di rete, non un login rotto: l'archivio
sbagliato. È il difetto del 14 settembre 2026, e si vedeva **solo da dietro un proxy** — `.men` è
un dominio che parecchi filtri aziendali bloccano in blocco, quindi `garsal-apps.pages.dev` è
l'unica via da quel computer e nessun altro ci passava mai.

⚠️ **Ne discende che quell'indirizzo è una seconda porta sui dati veri**, pubblica come
`garsal.men`, e perché il login ci funzioni va messo fra i **Redirect URLs** di Supabase
(produzione): `https://garsal-apps.pages.dev` per il Google di AppSphere. Non è un passo che
sta nella repo.

Vale in **17 pagine**; `finanza.html` è l'eccezione e non è una dimenticanza — lì `_IS_DEV` è
**solo un'etichetta** (il badge DEV/PROD) e le credenziali sono fisse sulla produzione.

⚠️ **«Spese in giro» è l'app che dipende meno dal sito**: non ne carica nessuna pagina — parla
solo con le RPC `vg_*` e con la sua Edge Function — e del sito usa il solo controllo
aggiornamenti. Un trasloco non la ferma. Ma il **token del viaggio sta solo nelle preferenze di
quel telefono** e non c'è nessun account da cui recuperarlo: **a viaggio in corso su quel
telefono non si installa niente**.

### Deployment
Cloudflare Pages pubblica a ogni push su `master`: *build command* `bash scripts/build-sito.sh`,
*output* `dist`, header e riscritture in `_headers` / `_redirects`. La radice `/` è `index.html`.
⚠️ Netlify non c'è più: `netlify.toml` e `deploy-dev.yml` sono stati tolti il 6 ottobre 2026, e
l'account è stato chiuso. `enable-banking-callback` riporta su `garsal.men`. Il controllo
`.netlify.app` di `_IS_DEV` è stato tolto dalle pagine; resta solo il nome «scaricabile via
Netlify» di un passo in tre workflow di build.

### Git workflow
- `master` — production branch (pubblicato da Cloudflare Pages)
- `claude/<description>-<id>` — feature branch → auto-merge to master → produzione
- Commit message prefixes used in this repo:
  - `feat:` — new feature
  - `fix:` — bug fix
  - `ui:` — visual / layout change
  - `refactor:` — code restructure without behaviour change
  - `chore:` — tooling, config, or non-functional change

### Deploy automatico
Pushing to a `claude/**` branch triggers `.github/workflows/deploy.yml` which:
1. Merges the branch into `master` automatically (no PR needed)
2. Cloudflare Pages picks up the master push and deploys

⚠️ **Il push su master si riprova, perché può essere rifiutato senza nessun conflitto.** Dallo
stesso push partono anche le due build APK, che a lavoro finito committano il pacchetto **su
master**: su un commit che tocca insieme le pagine e il codice Android arrivano mentre il deploy
è ancora al checkout, e il suo push trova un master più avanti — `! [rejected] (fetch first)`.
È successo il 24 agosto 2026 (run #1400): il merge era riuscito, il push no, e **tutti i passi
successivi sono stati saltati**, migration comprese — il codice non era in produzione e l'unico
posto dove si vedeva era Actions. Il passo *Merge branch into master and push* rilegge quindi
master e rifà il merge fino a cinque volte. **Un conflitto vero non si riprova**: `git merge`
esce diverso da zero, e con `bash -e` il passo muore lì — un conflitto lo risolve una persona.

⚠️ **La stessa rete ce l'hanno tutt'e otto i workflow di build**, che a lavoro finito committano
l'APK su master e si contendono lo stesso ref. Tre non l'avevano — fra cui
`build-situazione-rosa-apk` e `build-situazione-teresa-apk`, che facevano un `git push` secco — e
il 10 settembre 2026 Situazione Rosa ci è cascata: `cannot lock ref … is at ae64430 but expected
1a2250f`, con l'APK già compilato e buttato via. Il passo che carica su R2 aggiunge ~25 secondi
prima del push, quindi la finestra fra il `fetch` e il `push` si è allargata e l'ha reso
frequente invece che raro. ⚠️ **Il fallimento è solo il commit**: il caricamento su R2 viene
prima ed era già passato, quindi quell'APK era scaricabile ma non risultava in archivio — cioè
il contrario del difetto che l'ordine dei due passi esiste per evitare.

⚠️ **Il workflow non usa `supabase link`.** Fra le altre cose `link` chiama
`GET /v1/projects/{ref}/api-keys`, che dal 7 agosto 2026 risponde con un errore di validazione
del suo stesso schema (`SchemaError` su `inserted_at`): l'intero deploy moriva lì, portandosi
dietro migration ed Edge Function, che con le API keys non c'entrano niente. Lo step
*Prepare Supabase connection* scrive a mano i due file che `link` metteva in `supabase/.temp`
(`project-ref` e `pooler-url`, quest'ultimo letto dall'API del pooler e forzato in session
mode), poi `db push --linked` e `functions deploy --project-ref` funzionano da soli. La
password del database non serve: senza `DB_PASSWORD` la CLI si crea da sola un ruolo di login
temporaneo. **Se un giorno l'endpoint torna sano, `link` resta comunque superfluo.**

**Claude cannot push directly to `master`** (HTTP 403 — server-side branch protection).
The only path to production is: push to `claude/**` → GitHub Actions merges → Cloudflare Pages deploys.

### Versioning — regola obbligatoria
**Ad ogni modifica a qualsiasi file** (HTML o Android), Claude deve aggiornare la versione **nello stesso commit** delle modifiche, non dopo.

#### File HTML
1. **Incrementare il patch version** (`APP_VERSION`) — es. `v3.1.1` → `v3.1.2`
2. **Aggiornare `BUILD_TIME`** con il timestamp UTC corrente — es. `'2026-02-24T20:00:00Z'`
3. **Verificare che la versione compaia in**:
   - `<title>` tag della pagina
   - `var APP_VERSION` nello script
   - `var BUILD_TIME` nello script
   - `console.log` stilizzato visibile nei DevTools del browser
   - Log dell'app (funzione `log()`)

#### App Android (`android-app/smartblocker/`)
1. **Incrementare `versionName`** in `build.gradle` — es. `"1.2.3"` → `"1.2.4"`
2. **Incrementare `versionCode`** di 1 — es. `14` → `15`
3. **Aggiornare la stringa versione in `MainActivity.kt`** — es. `"v1.2.3 · PIN: …"` → `"v1.2.4 · PIN: …"`

Struttura versioning in `weight-quest.html` (righe ~782–787):
```js
var APP_VERSION = 'v3.1.2';
var BUILD_TIME  = '2026-02-24T20:00:00Z';
console.log('%c WEIGHT QUEST ' + APP_VERSION + ' %c build: ' + BUILD_TIME,
    'background:#4caf50;color:#fff;font-weight:bold;padding:2px 6px;border-radius:3px 0 0 3px',
    'background:#222;color:#aaa;padding:2px 6px;border-radius:0 3px 3px 0');
```

E nel blocco START (righe ~3280):
```js
console.log('%c⚖ Weight Quest ' + APP_VERSION, 'color:#00B894;font-size:16px;font-weight:bold;');
console.log('%cbuild: ' + BUILD_TIME, 'color:#888;font-size:11px;');
log('===========================================');
log('WEIGHT QUEST ' + APP_VERSION + ' — build: ' + BUILD_TIME);
log('===========================================');
```
---

## Key Conventions

### CSS variables (Tasks, Habit Tracker, Events Log)
All three share an identical CSS custom property palette:
```css
:root {
  --primary: #FF3366;
  --secondary: #6C5CE7;
  --success: #00B894;
  --warning: #F39C12;
  --danger: #E74C3C;
  --dark: #1F2937;
  --light: #FFFFFF;
  --muted: #6B7280;
  --accent: #2563EB;
  --border: #E5E7EB;
  --card-bg: #FFFFFF;
  --input-bg: #F9FAFB;
}
```

### ⚠️ Il tasto «indietro» di Android dentro un popup

Su Android l'indietro è il gesto con cui si chiude qualunque cosa si sia aperta. In una pagina web
che non fa niente per governarlo, dentro un popup **esce dalla pagina**: la WebView non ha niente
in cronologia e torna ad AppSphere, buttando via quel che si stava scrivendo.

Il rimedio è il blocco `guardiaIndietroPopup`, **identico in tutte e nove le app** che hanno dei
popup (`index`, `weight-quest`, `finanza`, `obiettivi`, `casarosa`, `conto-risparmio-teresa`,
`conto-spese-teresa`, `spese-ada`, `spese-personali`) — in fondo
al loro script,
e l'unica cosa che cambia è l'elenco dei popup. ⚠️ **Se lo correggi in una, portalo nelle altre**:
è la stessa duplicazione voluta dello snapshot del patrimonio.

Quattro cose che *sono* il funzionamento:

- ⚠️ **L'apertura non si intercetta avvolgendo le funzioni che aprono i popup.** In queste pagine
  sono decine, alcune sono `onclick` scritti nell'HTML e altre non hanno nemmeno un nome: si
  osserva invece quando l'**overlay diventa visibile** (`MutationObserver` su `class` e `style`),
  che è l'unica cosa che tutti i popup hanno in comune comunque siano stati aperti.
- ⚠️ **Visibile si decide dallo stile calcolato**, non dal nome della classe: nelle varie pagine è
  ora `hidden`, ora `active`, ora `open`. Tutte nascondono con `display:none`, quindi
  `getComputedStyle(el).display !== 'none'` è il test che vale ovunque. Nell'elenco va però
  l'**overlay** e non la scheda interna: in `casarosa` e `conto-risparmio-teresa` la classe
  `.modal` è la scheda dentro l'overlay, e lo stile calcolato di un figlio non sa che il padre è
  nascosto — un `.modal` messo lì risulterebbe sempre visibile. In `obiettivi`, invece,
  `.modal` *è* l'overlay ed è giusto usarla.
- ⚠️ **Anche il ✕ consuma la voce di cronologia**, altrimenti ogni apri-e-chiudi ne lascerebbe una
  dietro di sé e dopo cinque popup servirebbero cinque indietro per uscire dalla pagina. Il
  consumo controlla prima `history.state`: se nel frattempo la pagina ha spinto una voce sua (un
  cambio di vista) la nostra non è più in cima, e un `history.back()` cieco tornerebbe indietro di
  una schermata invece di togliere la voce del popup.
- ⚠️ **La voce si spinge una volta sola** anche con più popup aperti uno sopra l'altro (dalla
  ricerca alla porzione, dall'elenco al form): sono contenuti nella stessa finestra, non
  schermate, e due voci vorrebbero dire due indietro per chiudere una cosa sola. L'indietro
  chiude l'ultimo aperto.

⚠️ **In `calorie.html` da un popup si esce solo col ✕, con un pulsante o con l'indietro** — il
tocco sul velo scuro e l'Escape non chiudono più (v1.14.0). Su un telefono la scheda occupa quasi
tutto lo schermo, il velo è la striscia ai bordi, e il dito ci finisce sopra mentre si scorre un
elenco o si mira a un campo in alto: il popup spariva portandosi via i grammi appena scritti, e
sembrava un tocco andato a vuoto più che una chiusura. Il ✕ sta nell'HTML della finestra e non
dipende da cosa ci si mette dentro, quindi togliendo quelle due vie non si resta mai chiusi
dentro. ⚠️ **Le altre app chiudono ancora col tocco sul velo**: chi le tocca valuti se portare
anche questo.

Restano **fuori dall'elenco** il velo di caricamento e il cassetto del menù (`loading-overlay`,
`mobile-nav-overlay`), più il fumetto degli avvisi di `index.html`: sono tendine e non finestre.
Le quattro pagine che avevano già un `popstate` (`conto-spese-teresa`, `finanza`, `spese-ada`,
`spese-personali`) lo governava per le **sole viste** e continua a farlo: la guardia esce subito
quando non c'è nessun popup aperto, così l'indietro fa quel che ha sempre fatto.

### ⚠️ ✏️ e 🗑 stanno a SINISTRA del record

In un elenco le icone di modifica ed eliminazione vanno **in testa alla riga**, mai in coda. In
una tabella che scorre di lato — cioè ogni tabella su un telefono — l'ultima colonna sta oltre il
bordo destro: i pulsanti esistono, ma per raggiungerli bisogna già sapere che c'è dell'altro da
trascinare. La prima colonna è l'unica che si vede sempre, comunque sia largo lo schermo.

Ordine dentro il gruppo: **✏️ prima, 🗑 dopo** — la più usata per prima, e la distruttiva non sul
bordo, dov'è più facile prenderla di striscio.

Applicato in `calorie.html` (la tabella 🍎 Alimenti e le righe del 📓 Diario). ⚠️ **Le altre app
hanno ancora le icone in coda**: chi le tocca le sposti.

### Date handling
- Dates are stored as ISO strings (`YYYY-MM-DD`) in Supabase
- Displayed in European format (`dd/mm/yyyy`) in the UI
- **Critical**: avoid UTC conversion when extracting local dates — use `new Date(str)` carefully or split the ISO string directly to prevent off-by-one day bugs

### Supabase error handling pattern
```js
const { data, error } = await sb.from('table').select('*');
if (error) {
    console.error('Error:', error);
    alert('Errore: ' + (error.message || 'Unknown error'));
} else {
    // use data
}
```

### Version in title
App versions are tracked in the `<title>` tag and displayed in the sidebar (e.g. `Tasks v19.17.12`). Increment the patch version on meaningful changes.

### No TypeScript / no linting
There is no TypeScript, ESLint, Prettier, or any linting/formatting tool configured. Code style follows existing patterns in each file.

### Italian language
All user-facing strings, comments, and variable names (where contextual) are in Italian. Commit messages are also often in Italian. Match the existing language when adding code.

---

## Common Pitfalls

1. **CDN dependency**: Apps require internet access to load Supabase JS, Chart.js, Google Fonts, etc. They will not work fully offline.
2. **Auth token scope**: The Supabase anon key is public but Row Level Security (RLS) on Supabase controls access. Do not assume tables are publicly writable — the user must be authenticated.
3. **weight-quest auth**: Unlike other apps, weight-quest does NOT use the Supabase JS SDK for auth; it uses raw Google OAuth + its own minimal client. Token is received via `postMessage` from the launcher or retrieved from `localStorage`.
4. **Large file sizes**: `tasks.html` is ~445 KB and ~8 900 lines. When editing, use search to navigate to the relevant section. Sections are marked with `// ========================================` banners.
5. **No hot reload**: There is no dev server. After editing, hard-refresh the browser (`Cmd/Ctrl+Shift+R`).
6. **Duplicate `renderTaskCard`**: `tasks.html` defines `renderTaskCard` in two places (dashboard view and categories/management view). Both must be kept in sync when changing card rendering logic.
7. **Calcolo del patrimonio dal vivo duplicato**: lo snapshot lo scrive solo `save-snapshot`, ma `portfolioStats` / `computeLoanValue` / `computePricesFromHistory` esistono sia in `finanza.html` (dashboard dal vivo) sia nella Edge Function. Modificarne una sola fa dire allo snapshot un numero diverso da quello a schermo — dettagli in *Edge Functions e job schedulati*. Posizioni, liquidità e quote dei fondi no: stanno nelle RPC `fnz_posizioni` / `fnz_liquidita` / `fnz_quote_fondi`.
8. **Spese Famiglia sta in `spese-famiglia.js`**: regole (`SF`) e vista in sola lettura (`ca*`) le caricano `finanza.html`, `situazione-teresa.html` e `cost-analysis.html`. Non ricopiarle dentro una pagina — erano tre copie che divergevano in silenzio. Dettagli in *App Details → Spese Famiglia: regole e vista in sola lettura*.
9. **Snapshot solo all'apertura di Finanza**: `fnz_dashboard_snapshots` viene scritto quando si apre l'app (`autoSaveSnapshot`, che lo chiede a `save-snapshot`), e dal job delle 23:00. Chi legge lo snapshot come "valore attuale" durante il giorno ottiene un dato fermo alla notte precedente: per il valore aggiornato bisogna ricalcolarlo sui prezzi correnti.
10. **«Sono punti?» sta in `cm_apps.conta_punti`**: le due home e la relazione settimanale lo leggono da lì. Non reintrodurre un elenco a mano di `html_file` — erano tre copie che divergevano in silenzio.
11. **Dieci app esistono anche in Kotlin**: Spuntiamola, Obiettivi, Events Log, Ta Firi?, Ti pisasti? (Weight Quest), Memo, Abituati, Calorie, Forziere e Piante hanno un gemello nativo in `android-app/appsphere-native/` che scrive sulle stesse tabelle (Tasks pure, con la sua sezione a parte). Cambiare le regole in uno solo dei due li fa divergere in silenzio — dettagli in *AppSphere nativa*.

---

## Regola obbligatoria — Modifiche a tabelle o campi JSON

**PRIMA di qualsiasi modifica** a:
- struttura di una tabella Supabase (aggiunta/rimozione/rinomina colonne)
- struttura di un campo JSON/JSONB esistente (aggiunta/rimozione/rinomina chiavi)

Claude **deve avvisare esplicitamente** l'utente e attendere conferma. Non procedere mai in autonomia con queste modifiche.

Esempi che richiedono avviso preventivo:
- aggiungere un campo `smart_block_fire_at` dentro `reminder_presets`
- rinominare una colonna `due_at` → `fire_at`
- aggiungere una colonna `notification_spec` a `cm_notification_rules`

Se il codice necessita di un campo che non esiste ancora nel DB, proporre la migration SQL all'utente e **non inventare campi nuovi senza chiedere**.
