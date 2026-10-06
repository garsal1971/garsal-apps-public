package com.garsal.appsphere.abituati

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AbituatiState(
    val abitudini: List<HbAbitudine> = emptyList(),
    val spunte: List<HbSpunta> = emptyList(),
    val archivio: List<HbArchiviato> = emptyList(),
    val categorie: List<HbCategoria> = emptyList(),
    /** 🔔 Le regole di promemoria (una per canale) e gli anticipi proponibili. */
    val regole: List<HbRegola> = emptyList(),
    val preset: List<HbPreset> = emptyList(),
    /** ⚙️ Il registro delle operazioni, gemello della console di debug del web (ultime 100). */
    val registro: List<String> = emptyList(),
    /** Lo streak per abitudine, calcolato da `hb_streak`. */
    val streak: Map<String, Int> = emptyMap(),
    /** Le cerimonie che aspettano: stack vinti e game over. */
    val daFesteggiare: List<HbEsito> = emptyList(),
    val gameOver: List<HbEsito> = emptyList(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
) {
    fun categoria(id: String?): HbCategoria? = id?.let { c -> categorie.firstOrNull { it.id == c } }

    fun streakDi(abitudine: HbAbitudine): Int = streak[abitudine.id] ?: 0

    /** I canali accesi di un'abitudine, come le campanelle della scheda web. */
    fun canali(abitudineId: String): String {
        val c = regole.filter { it.abitudineId == abitudineId }.map { it.canale }
        return listOfNotNull("📱".takeIf { "telegram" in c }, "📲".takeIf { "android" in c }).joinToString("")
    }

    // ── 📊 Statistiche: gemelle di `renderStats()` / `renderMonthlyChart()` ──

    /** Completamenti totali: tutte le righe di `hb_completions`, come `completions.length` nel web. */
    val completamentiTotali: Int get() = spunte.size

    val attive: Int get() = abitudini.count { it.stato == "active" }

    /** Streak media: la somma degli streak divisa per le sole attive, arrotondata. */
    val streakMedia: Int
        get() = if (attive == 0) 0 else Math.round(abitudini.sumOf { streakDi(it) }.toDouble() / attive).toInt()

    /** Gli ultimi sei mesi, dal più vecchio: (anno-mese, righe in quel mese). */
    fun andamentoMensile(oggi: LocalDate = LocalDate.now()): List<Pair<java.time.YearMonth, Int>> =
        (5 downTo 0).map { i ->
            val ym = java.time.YearMonth.from(oggi).minusMonths(i.toLong())
            val prefisso = ym.toString()
            ym to spunte.count { it.quando.startsWith(prefisso) }
        }

    /** I primi dieci per streak. */
    val classifica: List<HbAbitudine> get() = abitudini.sortedByDescending { streakDi(it) }.take(10)

    /** Quante abitudini ATTIVE usano una categoria: decide l'ordine e se si può eliminare. */
    fun abitudiniDi(categoriaId: String): List<HbAbitudine> =
        abitudini.filter { it.categoriaId == categoriaId && it.stato == "active" }

    /** Lo stato di un periodo: `completed`, `failed`, `missed` o null. */
    fun statoDi(abitudineId: String, giorno: LocalDate, orario: String? = null): String? {
        val g = giorno.toString()
        return spunte.firstOrNull {
            it.abitudineId == abitudineId && it.giorno == g &&
                (orario == null || it.orario == orario)
        }?.stato
    }

    /**
     * L'ultima spunta di un'abitudine.
     *
     * ⚠️ Le righe `missed` non contano: non le ha messe nessuno, le scrive la
     * riconciliazione sui giorni dovuti e mai spuntati. Prendendole per spunte,
     * «il giorno dopo l'ultima spunta» diventerebbe il giorno dopo
     * l'interruzione — cioè il punto in cui l'abitudine si era già fermata, non
     * quello in cui la si stava ancora seguendo. Stessa regola di
     * `lastCheckDateStr()` in `habit-tracker.html`.
     */
    fun ultimaSpunta(abitudineId: String): LocalDate? {
        // Il massimo si cerca a mano: `LocalDate` è `Comparable<ChronoLocalDate>`
        // e non `Comparable<LocalDate>`, quindi `maxOrNull()` qui non tornerebbe
        // un `LocalDate`.
        var ultima: LocalDate? = null
        for (spunta in spunte) {
            if (spunta.abitudineId != abitudineId || spunta.stato == "missed") continue
            val giorno = runCatching { LocalDate.parse(spunta.giorno) }.getOrNull() ?: continue
            if (ultima == null || giorno.isAfter(ultima)) ultima = giorno
        }
        return ultima
    }

    /**
     * La data da cui proporre la ripartenza di un'abitudine interrotta: **il
     * giorno dopo l'ultima spunta**, che è dove si era fermata davvero.
     *
     * Due limiti, entrambi necessari: senza nessuna spunta si ripiega su oggi
     * (non c'è un «dopo» di niente), e in nessun caso si va oltre oggi — un
     * `started_at` nel futuro è un'abitudine che non cade mai, quindi ripresa
     * oggi e muta fino a domani. La stessa `defaultResumeDateStr()` del web.
     */
    fun ripartenzaSuggerita(abitudineId: String, oggi: LocalDate = LocalDate.now()): LocalDate {
        val dopo = ultimaSpunta(abitudineId)?.plusDays(1) ?: return oggi
        return if (dopo.isAfter(oggi)) oggi else dopo
    }

    /** Le abitudini che oggi chiedono qualcosa, come la timeline del web. */
    fun diOggi(oggi: LocalDate = LocalDate.now()): List<HbAbitudine> =
        abitudini.filter { it.stato == "active" && it.cadeIl(oggi) }

    // ── «N volte in M giorni»: i conti a finestre ────────────────────────
    //
    // ⚠️ Questi tre non sono una regola che vive qui: sono la lettura di
    // quello che è già in archivio, per disegnare i pallini senza chiedere al
    // server a ogni ridisegno. Chi **decide** resta il database — `hb_streak`,
    // `hb_giorni_fatti` e `hb_reconcile`, che di finestre sanno tutto
    // (`20260916120000_hb_count_window_rpc.sql`) — e sono i gemelli di
    // `cwWindowDoneCount()` / `cwWindowSatisfied()` / `cwOggiCount()` del web.

    /** Quante spunte completate dentro la finestra che comincia a `inizio`. */
    fun fatteNellaFinestra(a: HbAbitudine, inizio: LocalDate): Int {
        val fine = inizio.plusDays((a.m - 1).toLong())
        return spunte.count { sp ->
            if (sp.abitudineId != a.id || sp.stato != "completed") return@count false
            val g = runCatching { LocalDate.parse(sp.giorno) }.getOrNull() ?: return@count false
            !g.isBefore(inizio) && !g.isAfter(fine)
        }
    }

    /** La finestra è piena: almeno N spunte dentro. */
    fun finestraSoddisfatta(a: HbAbitudine, inizio: LocalDate): Boolean =
        fatteNellaFinestra(a, inizio) >= a.n

    /**
     * Quante ne ho già segnate **oggi**: il tetto `P` si conta sul giorno e non
     * sulla finestra, ed è quello che spegne il ＋ fino a domani.
     */
    fun fatteOggi(a: HbAbitudine, oggi: LocalDate = LocalDate.now()): Int {
        val g = oggi.toString()
        return spunte.count { it.abitudineId == a.id && it.stato == "completed" && it.giorno == g }
    }

    /**
     * Il progressivo scritto nella **chiave** di una spunta di quel giorno.
     *
     * ⚠️ Si legge dalla chiave e non si conta: tutte le spunte dello stesso
     * giorno portano lo stesso `completed_at` (mezzogiorno), quindi contarle —
     * o ordinarle per istante — dà un ordine arbitrario. Tolta `#1` e lasciata
     * `#2`, un ＋ che contasse le righe riproverebbe `#2`, che c'è già:
     * `hb_completions_habit_period_unique` lo rifiuta. Una riga nata prima del
     * progressivo ha la chiave nuda del giorno e vale 1.
     */
    fun progressivoDi(sp: HbSpunta, giorno: String): Int {
        val k = sp.chiave.orEmpty()
        if (k == giorno) return 1
        if (!k.startsWith("$giorno#")) return 0
        return k.removePrefix("$giorno#").toIntOrNull() ?: 0
    }

    /** Il progressivo più alto già scritto per quel giorno: il prossimo è +1. */
    fun ultimoProgressivo(a: HbAbitudine, giorno: String): Int =
        spunte.filter { it.abitudineId == a.id && it.stato == "completed" }
            .maxOfOrNull { progressivoDi(it, giorno) } ?: 0
}

class AbituatiViewModel : ViewModel() {

    private val _state = MutableStateFlow(AbituatiState())
    val state: StateFlow<AbituatiState> = _state.asStateFlow()

    /**
     * ⚠️ Una scrittura per volta, per abitudine. Un tocco può arrivare doppio,
     * e il secondo ＋ partirebbe prima che il primo abbia ricaricato: due giri
     * che leggono lo stesso stato scrivono due volte la stessa chiave.
     */
    private val inVolo = mutableSetOf<String>()

    init { carica() }

    /**
     * Il giro di apertura, nello stesso ordine del web: **prima** la
     * riconciliazione — giorni mancati, jolly, stack completati e scaduti —
     * e poi si legge, perché quello che si legge sia già a posto.
     *
     * La riconciliazione è una RPC sola (`hb_reconcile`), la stessa che chiama
     * `habit-tracker.html`: qui non si decide niente, si mostra quello che ha
     * deciso lei.
     */
    fun carica(oggi: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val (vinti, persi) = runCatching { AbituatiRepository.riconcilia(oggi) }
                    .onFailure { Log.w(TAG, "riconciliazione non riuscita", it) }
                    .getOrDefault(emptyList<HbEsito>() to emptyList())

                val abitudini = AbituatiRepository.abitudini()
                val spunte = AbituatiRepository.spunte()
                val archivio = AbituatiRepository.archivio()
                val categorie = AbituatiRepository.categorie()
                val regole = AbituatiRepository.regole()
                val preset = if (_state.value.preset.isEmpty()) AbituatiRepository.preset() else _state.value.preset
                val streak = streakDi(abitudini, oggi)

                _state.value = _state.value.copy(
                    abitudini = abitudini,
                    spunte = spunte,
                    archivio = archivio,
                    categorie = categorie,
                    regole = regole,
                    preset = preset,
                    streak = streak,
                    daFesteggiare = _state.value.daFesteggiare + vinti,
                    gameOver = _state.value.gameOver + persi,
                    caricamento = false,
                )
                log("Caricate ${abitudini.size} abitudini, ${spunte.size} spunte" +
                    (if (vinti.isNotEmpty() || persi.isNotEmpty()) " · ${vinti.size} vinti, ${persi.size} game over" else ""))
            } catch (e: Exception) {
                Log.w(TAG, "caricamento fallito", e)
                log("❌ Caricamento: ${e.message}")
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    private suspend fun streakDi(abitudini: List<HbAbitudine>, oggi: LocalDate): Map<String, Int> =
        coroutineScope {
            abitudini.map { a ->
                async { a.id to AbituatiRepository.streak(a.id, oggi) }
            }.awaitAll().toMap()
        }

    /**
     * Segna un periodo. La scrittura, i jolly e il conto dello streak li fa la
     * RPC; qui si guarda solo se ha alzato una delle due bandiere — obiettivo
     * raggiunto o jolly finiti — e in quel caso si richiama il giro di
     * riconciliazione, che è l'unico posto dove uno stack si chiude.
     */
    fun segna(
        abitudine: HbAbitudine,
        giorno: LocalDate,
        stato: String,
        orario: String? = null,
        oggi: LocalDate = LocalDate.now(),
    ) {
        viewModelScope.launch {
            try {
                val esito = AbituatiRepository.segna(abitudine.id, giorno, stato, orario, oggi)
                val ok = testo(esito, "ok")?.toBooleanStrictOrNull() ?: false
                if (!ok) {
                    _state.value = _state.value.copy(
                        errore = testo(esito, "error") ?: "Spunta non riuscita",
                    )
                    return@launch
                }
                carica(oggi)
            } catch (e: Exception) {
                Log.w(TAG, "spunta non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Spunta non riuscita")
            }
        }
    }

    /**
     * Il **＋** di un'abitudine a finestre: una spunta in più oggi.
     *
     * ⚠️ **Non passa da `hb_set_completion`**, ed è la stessa ragione per cui
     * nel web `cwAggiungi` non passa da `setDayState`: quella cerca la riga per
     * `period_key = <giorno>` e con più spunte nello stesso giorno
     * aggiornerebbe la prima invece di aggiungerne una. La chiave porta quindi
     * il progressivo del giorno (`2026-09-16#2`), così due spunte sono due
     * righe.
     */
    fun aggiungi(abitudine: HbAbitudine, oggi: LocalDate = LocalDate.now()) {
        val stato = _state.value
        val k = abitudine.finestraDi(oggi)
        if (k < 0) {
            _state.value = stato.copy(messaggio = "Questa abitudine non è ancora cominciata")
            return
        }
        val inizio = abitudine.inizioFinestra(k) ?: return
        // Lo stesso tetto dei pallini disegnati: max(N, M).
        if (stato.fatteNellaFinestra(abitudine, inizio) >= abitudine.caselle) {
            _state.value = stato.copy(messaggio = "La finestra è già piena")
            return
        }
        if (stato.fatteOggi(abitudine, oggi) >= abitudine.p) {
            _state.value = stato.copy(
                messaggio = "Oggi hai già segnato ${abitudine.p}" +
                    (if (abitudine.p == 1) " volta" else " volte"),
            )
            return
        }
        if (!inVolo.add(abitudine.id)) return
        val prossimo = stato.ultimoProgressivo(abitudine, oggi.toString()) + 1
        viewModelScope.launch {
            try {
                AbituatiRepository.aggiungiSpunta(abitudine.id, oggi, prossimo)
                carica(oggi)
            } catch (e: Exception) {
                Log.w(TAG, "spunta non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Spunta non riuscita")
            } finally {
                inVolo.remove(abitudine.id)
            }
        }
    }

    /**
     * Il **−**: toglie l'ULTIMA spunta della finestra **in corso**.
     *
     * ⚠️ Le finestre chiuse non si toccano: i loro pallini rossi hanno già
     * consumato i jolly, e rimetterci mano riscriverebbe un conto già fatto.
     */
    fun togli(abitudine: HbAbitudine, oggi: LocalDate = LocalDate.now()) {
        val stato = _state.value
        val k = abitudine.finestraDi(oggi)
        if (k < 0) return
        val inizio = abitudine.inizioFinestra(k) ?: return
        val fine = inizio.plusDays((abitudine.m - 1).toLong())
        // ⚠️ «L'ultima» è quella col (giorno, progressivo) più alto, non col
        // `quando` più grande: dentro un giorno quell'istante è lo stesso per
        // tutte, quindi ordinarle per lui è un sorteggio — e togliere `#1`
        // lasciando `#2` rompe il ＋ successivo.
        val ultima = stato.spunte
            .filter { sp ->
                if (sp.abitudineId != abitudine.id || sp.stato != "completed") return@filter false
                val g = runCatching { LocalDate.parse(sp.giorno) }.getOrNull() ?: return@filter false
                !g.isBefore(inizio) && !g.isAfter(fine)
            }
            .maxWithOrNull(
                compareBy<HbSpunta>({ it.giorno }, { stato.progressivoDi(it, it.giorno) }),
            ) ?: return
        if (!inVolo.add(abitudine.id)) return
        viewModelScope.launch {
            try {
                AbituatiRepository.togliSpunta(ultima.id)
                carica(oggi)
            } catch (e: Exception) {
                Log.w(TAG, "rimozione non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Rimozione non riuscita")
            } finally {
                inVolo.remove(abitudine.id)
            }
        }
    }

    /** «Ricomincia» e «Interrompi» del game over. */
    fun chiudiStack(esito: HbEsito, nuovoInizio: LocalDate?, oggi: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            try {
                // Uno stack scaduto per calendario `hb_reconcile` l'ha già
                // archiviato ed eliminato: qui resta solo l'eventuale nuovo
                // ciclo, e chiamare la chiusura darebbe «abitudine non trovata».
                if (!esito.giaArchiviato) {
                    AbituatiRepository.chiudiStack(esito.abitudineId, nuovoInizio, oggi)
                }
                scartaGameOver(esito)
                carica(oggi)
            } catch (e: Exception) {
                Log.w(TAG, "chiusura stack non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Chiusura non riuscita")
            }
        }
    }

    /** «Ricomincia» dopo uno stack vinto: l'abitudine c'è ancora, si clona. */
    fun riparti(esito: HbEsito, inizio: LocalDate, oggi: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            try {
                AbituatiRepository.nuovoCiclo(esito.abitudineId, inizio)
                scartaFesta(esito)
                carica(oggi)
            } catch (e: Exception) {
                Log.w(TAG, "nuovo ciclo non riuscito", e)
                _state.value = _state.value.copy(errore = e.message ?: "Nuovo ciclo non riuscito")
            }
        }
    }

    fun scartaFesta(esito: HbEsito) {
        _state.value = _state.value.copy(
            daFesteggiare = _state.value.daFesteggiare.filterNot { it.abitudineId == esito.abitudineId },
        )
    }

    fun scartaGameOver(esito: HbEsito) {
        _state.value = _state.value.copy(
            gameOver = _state.value.gameOver.filterNot { it.abitudineId == esito.abitudineId },
        )
    }

    /**
     * INTERROMPI e RIPRENDI, le due direzioni di `status` fra `active` e
     * `stopped`. Non passano da una RPC — come nel web sono un `update`
     * diretto: le RPC governano dove va la prossima occorrenza, non se
     * l'abitudine è in corso.
     */
    fun interrompi(abitudine: HbAbitudine, oggi: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            try {
                AbituatiRepository.interrompi(abitudine.id)
                carica(oggi)
            } catch (e: Exception) {
                Log.w(TAG, "interruzione non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Interruzione non riuscita")
            }
        }
    }

    fun riprendi(abitudine: HbAbitudine, inizio: LocalDate, oggi: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            try {
                AbituatiRepository.riprendi(abitudine.id, inizio)
                carica(oggi)
            } catch (e: Exception) {
                Log.w(TAG, "ripresa non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Ripresa non riuscita")
            }
        }
    }

    /**
     * Quanto costerebbe riprendere da una certa data. Il conto lo fa il
     * database, qui si passa solo la domanda: `null` vuol dire che non si è
     * potuto chiedere, e non «zero giorni».
     */
    suspend fun giorniDaRecuperare(
        abitudine: HbAbitudine,
        da: LocalDate,
        oggi: LocalDate = LocalDate.now(),
    ): Int? = AbituatiRepository.giorniDaRecuperare(abitudine.id, da, oggi)

    fun salva(id: String?, bozza: BozzaAbitudine, onFatto: () -> Unit) {
        viewModelScope.launch {
            try {
                val salvata = AbituatiRepository.salva(id, bozza)
                // I promemoria dopo l'abitudine: per una nuova l'id nasce lì.
                // Se la regola non passa, l'abitudine resta salvata e lo si dice.
                runCatching { AbituatiRepository.sincronizzaPromemoria(salvata, bozza) }
                    .onFailure {
                        Log.w(TAG, "promemoria non salvato", it)
                        _state.value = _state.value.copy(errore = "Errore salvataggio promemoria: ${it.message}")
                    }
                log("Salvata «${bozza.nome.trim()}»")
                onFatto()
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "salvataggio non riuscito", e)
                _state.value = _state.value.copy(errore = e.message ?: "Salvataggio non riuscito")
            }
        }
    }

    fun elimina(id: String, onFatto: () -> Unit) {
        viewModelScope.launch {
            try {
                AbituatiRepository.elimina(id)
                onFatto()
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "eliminazione non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Eliminazione non riuscita")
            }
        }
    }

    // ── 🏷️ Categorie ─────────────────────────────────────────────────────

    fun salvaCategoria(id: String?, nome: String, icona: String, colore: String, onFatto: () -> Unit) {
        viewModelScope.launch {
            try {
                AbituatiRepository.salvaCategoria(id, nome, icona, colore)
                log((if (id == null) "Creata" else "Modificata") + " la categoria «${nome.trim()}»")
                onFatto()
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "categoria non salvata", e)
                _state.value = _state.value.copy(errore = e.message ?: "Categoria non salvata")
            }
        }
    }

    fun eliminaCategoria(categoria: HbCategoria) {
        viewModelScope.launch {
            try {
                AbituatiRepository.eliminaCategoria(categoria.id)
                log("Eliminata la categoria «${categoria.nome}»")
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "categoria non eliminata", e)
                _state.value = _state.value.copy(errore = e.message ?: "Categoria non eliminata")
            }
        }
    }

    // ── ⚙️ Registro ──────────────────────────────────────────────────────

    private fun log(riga: String) {
        val ora = java.time.LocalTime.now().withNano(0)
        _state.value = _state.value.copy(registro = (_state.value.registro + "[$ora] $riga").takeLast(100))
    }

    fun svuotaRegistro() {
        _state.value = _state.value.copy(registro = emptyList())
        log("Console cleared")
    }

    fun scartaMessaggi() {
        _state.value = _state.value.copy(errore = null, messaggio = null)
    }

    private companion object {
        const val TAG = "AppSphereAbituati"
    }
}
