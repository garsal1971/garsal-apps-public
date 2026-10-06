package com.garsal.appsphere.calorie

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.core.messaggioBreve
import com.garsal.appsphere.peso.Obiettivo
import com.garsal.appsphere.peso.Pesata
import com.garsal.appsphere.peso.PesoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * ⚠️ Open Food Facts limita le ricerche testuali a una decina al minuto:
 * cercare a ogni tasto premuto fa **bandire l'indirizzo IP**. La chiamata parte
 * quindi solo dopo una pausa di digitazione, come nella pagina.
 */
private const val RITARDO_RICERCA = 700L

/** Quel che si sta cercando, e cosa si è trovato. */
data class Ricerca(
    val testo: String = "",
    val locali: List<Alimento> = emptyList(),
    val rete: List<Alimento> = emptyList(),
    val inCorso: Boolean = false,
    /** Cosa hanno risposto le fonti: si mostra quando non si è trovato niente. */
    val stato: String = "",
)

data class CalorieState(
    val profilo: ProfiloCalorie = ProfiloCalorie(),
    val obiettivo: Obiettivo? = null,
    val pesate: List<Pesata> = emptyList(),
    val alimenti: List<Alimento> = emptyList(),
    val righe: List<RigaDiario> = emptyList(),
    /**
     * Il conto delle calorie della finestra, da `al_conto`: target di ogni
     * giorno, tratti e giorno per giorno. `null` finché non arriva — o se non
     * arriva: ogni giorno dice allora «dato mancante» invece di inventarsi un
     * numero.
     */
    val conto: CalorieRegole.Conto? = null,
    /** I pasti scelti in ⚙️ Impostazioni sul web; `null` = mai configurati. */
    val pastiConfigurati: List<Pasto>? = null,
    /** Il giorno che il 📓 Diario sta guardando. */
    val giorno: String = LocalDate.now().toString(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
    val ricerca: Ricerca = Ricerca(),
) {
    val oggi: String get() = LocalDate.now().toString()

    /**
     * Le righe indicizzate per giorno. `by lazy` e non un getter: le leggono il
     * diario, i cinque riquadri della dashboard e il giorno per giorno, e
     * rifare il raggruppamento a ogni lettura vorrebbe dire ripartire da
     * cinquemila righe a ogni ridisegno.
     */
    val righePerGiorno: Map<String, List<RigaDiario>> by lazy { righe.groupBy { it.day } }

    val righeDelGiorno: List<RigaDiario> get() = righePerGiorno[giorno].orEmpty()

    /** Il piano giorno per giorno, con il saldo e la fetta che si spalma. */
    val piano: CalorieRegole.Piano get() = conto?.piano ?: CalorieRegole.Piano()

    val arco: CalorieRegole.Arco? get() = conto?.arco

    val tratti: List<CalorieRegole.Tratto> get() = conto?.tratti.orEmpty()

    fun target(quando: String): CalorieRegole.Target =
        conto?.target(quando) ?: CalorieRegole.Target(motivo = CalorieRegole.Motivo.FUORI)

    /** I pasti da disegnare per il giorno aperto, quelli tolti compresi. */
    fun pastiDelGiorno(): List<Pasto> =
        Pasti.delGiorno(pastiConfigurati, righeDelGiorno.map { it.meal }.toSet())

    val primoGiorno: String get() = CalorieRegole.primoGiornoDiario(obiettivo, oggi)
}

/**
 * 📊 Dashboard e 📓 Diario di `calorie.html`, in nativo.
 *
 * ⚠️ Il conto non si fa qui: lo danno le RPC `al_conto` / `al_congela_giorno` /
 * `al_ricalcola_giorno`, le stesse del web. Qui si legge, si scrive e si dice
 * com'è andata. Restano sul web
 * 🍎 Alimenti e ⚙️ Impostazioni — il catalogo si cura da seduti, e i pasti e
 * l'attività si configurano una volta.
 */
class CalorieViewModel : ViewModel() {

    private val _state = MutableStateFlow(CalorieState())
    val state: StateFlow<CalorieState> = _state.asStateFlow()

    private var ricercaJob: Job? = null

    init { carica() }

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val da = LocalDate.now().minusDays(GIORNI_STORICO)
                val obiettivo = CalorieRepository.obiettivoAttivo()
                _state.value = _state.value.copy(
                    profilo = CalorieRepository.profilo(),
                    obiettivo = obiettivo,
                    pesate = PesoRepository.pesate(da),
                    alimenti = CalorieRepository.alimenti(),
                    righe = CalorieRepository.righe(da),
                    pastiConfigurati = CalorieRepository.pasti(),
                    caricamento = false,
                )
                ricaricaConto()
            } catch (e: Exception) {
                Log.w(TAG, "caricamento fallito", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.messaggioBreve("Caricamento non riuscito"),
                )
            }
        }
    }

    /** Rilegge le righe e il conto: è quel che cambia scrivendo. */
    private suspend fun ricaricaDiario() {
        val da = LocalDate.now().minusDays(GIORNI_STORICO)
        runCatching { CalorieRepository.righe(da) }
            .onSuccess { _state.value = _state.value.copy(righe = it) }
            .onFailure { Log.w(TAG, "ricarica non riuscita", it) }
        ricaricaConto()
    }

    /** Il conto dal database. Non fallisce mai: senza risposta i giorni dicono «dato mancante». */
    private suspend fun ricaricaConto() {
        runCatching { CalorieRepository.conto(LocalDate.now()) }
            .onSuccess { _state.value = _state.value.copy(conto = it) }
            .onFailure {
                Log.w(TAG, "al_conto non letto", it)
                _state.value = _state.value.copy(messaggio = "⚠️ Calorie non calcolate: ${it.messaggioBreve()}")
            }
    }

    // ── Il giorno che si sta guardando ──────────────────────────────────

    /**
     * Il fondo si rispetta anche qui e non solo nel disegno del pulsante: uno
     * spento è un suggerimento, non una garanzia.
     */
    fun vaiA(quando: String) {
        val stato = _state.value
        if (quando < stato.primoGiorno || quando > stato.oggi) return
        _state.value = stato.copy(giorno = quando)
    }

    fun giornoPrecedente() = vaiA(CalorieRegole.piuGiorni(_state.value.giorno, -1))
    fun giornoSuccessivo() = vaiA(CalorieRegole.piuGiorni(_state.value.giorno, 1))
    fun vaiAOggi() = vaiA(_state.value.oggi)

    // ── Scritture ───────────────────────────────────────────────────────

    /**
     * Segna un alimento nella giornata aperta.
     *
     * L'alimento preso dalla rete entra nel catalogo **adesso**, insieme alla
     * riga del diario, e non quando lo si apre: aprire un risultato per sbaglio
     * riempirebbe il catalogo di prodotti mai mangiati. Prima però si
     * [congela][CalorieRepository.congelaGiorno] il target del giorno, come fa
     * `aggiungiAlDiario()` nella pagina.
     */
    fun aggiungi(alimento: Alimento, grammi: Double, pasto: String) {
        viewModelScope.launch {
            val stato = _state.value
            val giorno = stato.giorno
            try {
                CalorieRepository.congelaGiorno(giorno)

                var id = alimento.id
                var nuovoInCatalogo = false
                if (id == null && alimento.entraInCatalogo) {
                    val esistente = alimento.barcode?.let { codice ->
                        stato.alimenti.firstOrNull { it.barcode == codice }
                    }
                    id = CalorieRepository.salvaAlimentoDiRete(alimento, esistente)
                    nuovoInCatalogo = id != null && esistente == null
                }

                CalorieRepository.aggiungiAlDiario(giorno, pasto, alimento, grammi, id)
                val kcal = alimento.kcal?.times(grammi) ?: 0.0
                _state.value = _state.value.copy(
                    messaggio = "➕ ${alimento.name} · ${kcalIt(kcal / 100)} kcal" +
                        (if (nuovoInCatalogo) " · aggiunto al catalogo" else ""),
                )
                ricaricaDiario()
                if (nuovoInCatalogo) ricaricaAlimenti()
            } catch (e: Exception) {
                Log.w(TAG, "riga non aggiunta", e)
                _state.value = _state.value.copy(
                    messaggio = "Non segnato: ${e.messaggioBreve()}"
                )
            }
        }
    }

    fun cambiaGrammi(riga: RigaDiario, grammi: Double) {
        viewModelScope.launch {
            try {
                CalorieRepository.cambiaGrammi(riga.id, grammi)
                ricaricaDiario()
            } catch (e: Exception) {
                Log.w(TAG, "grammi non cambiati", e)
                _state.value = _state.value.copy(
                    messaggio = "Non salvato: ${e.messaggioBreve()}"
                )
            }
        }
    }

    /**
     * Toglie una riga dal diario. Ottimistica con rollback, come le spunte di
     * Spuntiamola: la riga sparisce subito e torna se il database dice di no,
     * così non resta a schermo una cancellazione finta.
     */
    fun elimina(riga: RigaDiario) {
        viewModelScope.launch {
            val prima = _state.value.righe
            _state.value = _state.value.copy(righe = prima.filterNot { it.id == riga.id })
            try {
                CalorieRepository.eliminaRiga(riga.id)
                _state.value = _state.value.copy(messaggio = "🗑 ${riga.name} tolto dal diario")
            } catch (e: Exception) {
                Log.w(TAG, "riga non eliminata", e)
                _state.value = _state.value.copy(
                    righe = prima,
                    messaggio = "Non eliminata: ${e.messaggioBreve()}",
                )
            }
        }
    }

    /** Le righe del giorno prima: quante sono decide se il pulsante compare. */
    fun righeDiIeri(): List<RigaDiario> =
        _state.value.righePerGiorno[CalorieRegole.piuGiorni(_state.value.giorno, -1)].orEmpty()

    fun ricopiaDaIeri() {
        viewModelScope.launch {
            val stato = _state.value
            val righe = righeDiIeri()
            if (righe.isEmpty()) return@launch
            try {
                CalorieRepository.congelaGiorno(stato.giorno)
                CalorieRepository.ricopia(stato.giorno, righe)
                _state.value = _state.value.copy(
                    messaggio = "📋 Ricopiate ${righe.size} " +
                        (if (righe.size == 1) "riga" else "righe")
                )
                ricaricaDiario()
            } catch (e: Exception) {
                Log.w(TAG, "ricopia non riuscita", e)
                _state.value = _state.value.copy(
                    messaggio = "Non ricopiate: ${e.messaggioBreve()}"
                )
            }
        }
    }

    /**
     * Ricalcola il target di **un** giorno, su richiesta esplicita — mai da sé:
     * automatico riscriverebbe il giudizio su giornate già passate, che è
     * esattamente ciò che il congelamento evita.
     */
    fun ricalcola(quando: String) {
        viewModelScope.launch {
            try {
                val esito = CalorieRepository.ricalcolaGiorno(quando)
                if (!esito.ok) {
                    _state.value = _state.value.copy(messaggio = "Non si può ricalcolare: ${spiegaMotivo(esito.motivo)}")
                    return@launch
                }
                _state.value = _state.value.copy(messaggio = "🔄 Target del giorno ricalcolato")
                ricaricaDiario()
            } catch (e: Exception) {
                Log.w(TAG, "ricalcolo non riuscito", e)
                _state.value = _state.value.copy(
                    messaggio = "Non ricalcolato: ${e.messaggioBreve()}"
                )
            }
        }
    }

    private suspend fun ricaricaAlimenti() {
        runCatching { CalorieRepository.alimenti() }
            .onSuccess { _state.value = _state.value.copy(alimenti = it) }
            .onFailure { Log.w(TAG, "catalogo non riletto", it) }
    }

    // ── La ricerca ──────────────────────────────────────────────────────

    /**
     * La ricerca locale è immediata; quella in rete aspetta la pausa di
     * digitazione. Le due provenienze restano separate anche qui — «è già nel
     * tuo catalogo» e «sta arrivando adesso da una banca dati pubblica» sono
     * due cose diverse da usare, ed è la distinzione che si deve vedere prima
     * di scegliere.
     */
    fun cerca(testo: String) {
        ricercaJob?.cancel()
        val pulito = testo.trim().lowercase()
        val locali = if (pulito.isEmpty()) emptyList()
        else _state.value.alimenti.filter {
            it.name.lowercase().contains(pulito) || (it.brand ?: "").lowercase().contains(pulito)
        }.take(12)

        _state.value = _state.value.copy(
            ricerca = Ricerca(
                testo = testo,
                locali = locali,
                inCorso = pulito.length >= 3,
                stato = "",
            )
        )
        if (pulito.length < 3) return

        ricercaJob = viewModelScope.launch {
            delay(RITARDO_RICERCA)
            try {
                val esito = CalorieRepository.cercaInRete(pulito)
                if (_state.value.ricerca.testo.trim().lowercase() != pulito) return@launch
                // Un prodotto già in archivio non si ripropone come risultato di
                // rete: sarebbe la stessa cosa due volte, una delle quali senza id.
                val noti = _state.value.alimenti.mapNotNull { it.barcode }.toSet()
                val trovati = esito.alimenti.filter { it.barcode == null || it.barcode !in noti }.take(15)
                _state.value = _state.value.copy(
                    ricerca = _state.value.ricerca.copy(
                        rete = trovati,
                        inCorso = false,
                        stato = if (trovati.isNotEmpty()) ""
                        else esito.esiti.ifBlank { "Nessun risultato in rete." },
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "ricerca in rete non riuscita", e)
                _state.value = _state.value.copy(
                    ricerca = _state.value.ricerca.copy(
                        rete = emptyList(),
                        inCorso = false,
                        stato = "La ricerca non ha funzionato: ${e.messaggioBreve()}",
                    )
                )
            }
        }
    }

    /** A campo vuoto si propongono i preferiti e i più usati: nove volte su dieci è lì. */
    fun proposti(): List<Alimento> = _state.value.alimenti.take(12)

    fun azzeraRicerca() {
        ricercaJob?.cancel()
        _state.value = _state.value.copy(ricerca = Ricerca())
    }

    fun pastoDellOra(): String =
        Pasti.dellOra(_state.value.pastiConfigurati, LocalTime.now().hour)

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    private companion object {
        const val TAG = "AppSphereCalorie"
    }
}

/** Perché il target non si può calcolare, detto a chi guarda. */
internal fun spiegaMotivo(motivo: CalorieRegole.Motivo?): String = when (motivo) {
    CalorieRegole.Motivo.PROFILO ->
        "mancano i dati anagrafici — si compilano in AppSphere → ☰ → 👤 Profilo " +
            "(data di nascita, altezza, sesso)"
    CalorieRegole.Motivo.PESO ->
        "manca una pesata — segnane una in «Ti pisasti?», il target si calcola sul peso"
    CalorieRegole.Motivo.FUORI -> "il conto di quel giorno non è arrivato — riapri la schermata"
    else -> "dato mancante"
}
