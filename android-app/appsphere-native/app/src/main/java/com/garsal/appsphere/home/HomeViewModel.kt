package com.garsal.appsphere.home

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.core.ModalitaNascosta
import com.garsal.appsphere.premi.PremiRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class BollaSalvata(
    val htmlFile: String,
    val nome: String,
    val descrizione: String,
    val punteggio: Int,
    val colore: String,
    val riservata: Boolean,
    val route: String,
    // Le bolle salvate prima della v1.0.92 non ce l'hanno: `false` vuol dire
    // che per quel primo avvio il numero sotto la bolla non si scrive, finché
    // non arrivano i dati freschi. Il contrario sommerebbe dei conteggi.
    val contaPunti: Boolean = false,
)

data class HomeState(
    val bolle: List<Bolla> = emptyList(),
    val avvisi: List<Avviso> = emptyList(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val modalitaNascosta: Boolean = false,
    val avvisiChiusi: Boolean = false,
    /** La sequenza da indovinare, letta da `cm_settings`. Vuota = codice spento. */
    val sequenza: List<String> = emptyList(),
    /** Il widget del codice è aperto e le bolle si toccano per il colore. */
    val inCattura: Boolean = false,
    /** I colori toccati finora, uno per casella. */
    val coloriCatturati: List<String> = emptyList(),
    /**
     * Punti guadagnati: la somma dei punteggi di **tutte** le app attive, meno
     * quelle il cui numero è un conteggio e non un punteggio ([Bolla.contaPunti]).
     */
    val totaleLordo: Int = 0,
    /** Punti già spesi in premi (`cm_rewards_log`). */
    val puntiSpesi: Int = 0,
    /**
     * La somma dei numeri delle app riservate, per il riquadro rosso scuro che
     * compare accanto al totale **solo in modalità nascosta**. Non entra in
     * [totaleLordo] e non compra niente.
     *
     * ⚠️ **Non si tiene in cache**, come le bolle riservate e per la stessa
     * ragione: al prossimo avvio la modalità nascosta è spenta, e un numero
     * delle app nascoste rimasto nelle preferenze è un pezzo di quel che la
     * modalità esiste per non far vedere.
     */
    val totaleRiservate: Int = 0,
    /** Quante app riservate ci sono: a zero il riquadro non si disegna. */
    val appRiservate: Int = 0,
) {
    /**
     * Quello che il riquadro in basso mostra, e con cui si comprano i premi:
     * guadagnati meno spesi, anche negativo (il debito si vede). È la stessa cifra di
     * `updateScorePanel()` nel web — se le due divergono, un premio comprabile
     * da una parte non lo è dall'altra.
     */
    val totaleNetto: Int get() = totaleLordo - puntiSpesi

    /**
     * Quante caselle disegnare. Le tre del web (`hiddenSeqLength || 3`) sono il
     * ripiego: finché la sequenza non è arrivata il widget si apre lo stesso,
     * ma il confronto lo farà su una sequenza vuota e non aprirà niente.
     */
    val caselle: Int get() = sequenza.size.takeIf { it > 0 } ?: 3
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val prefs = app.getSharedPreferences("appsphere", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Chiude il widget del codice se nessuno lo tocca più: i 15 s del web. */
    private var chiusuraCattura: Job? = null

    init {
        // Le bolle in cache si disegnano subito, prima ancora di parlare col
        // database: all'avvio si vede la home popolata invece di una rotella.
        // Poi i dati veri le sostituiscono.
        leggiCache()
        ricarica()
        caricaSequenza()

        // La modalità nascosta vive fuori dal ViewModel — la leggono anche le
        // altre schermate — ma la home deve ridisegnarsi quando cambia.
        viewModelScope.launch {
            ModalitaNascosta.attiva.collect { attiva ->
                if (attiva != _state.value.modalitaNascosta) {
                    _state.value = _state.value.copy(modalitaNascosta = attiva)
                    ricarica()
                }
            }
        }
    }

    private fun caricaSequenza() {
        viewModelScope.launch {
            val seq = HomeRepository.sequenzaNascosta()
            _state.value = _state.value.copy(sequenza = seq)
        }
    }

    fun ricarica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val dati = HomeRepository.carica(_state.value.modalitaNascosta)
                val avvisi = HomeRepository.avvisi()
                val spesi = PremiRepository.puntiSpesi()
                _state.value = _state.value.copy(
                    bolle = dati.bolle,
                    avvisi = avvisi,
                    caricamento = false,
                    totaleLordo = dati.totaleLordo,
                    puntiSpesi = spesi,
                    totaleRiservate = dati.totaleRiservate,
                    appRiservate = dati.appRiservate,
                )
                salvaCache(dati.bolle, dati.totaleLordo, spesi)
            } catch (e: Exception) {
                Log.w("AppSphereHome", "caricamento home fallito", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    // Se in cache c'era qualcosa le bolle restano a schermo:
                    // meglio dei punteggi di ieri che una pagina vuota.
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    // ── Codice a colori ─────────────────────────────────────────────────────
    //
    // Gli stessi quattro passaggi del launcher: pressione lunga sullo sfondo →
    // si toccano le bolle e ognuna mette il **suo colore** nella casella
    // successiva → la freccia confronta. Il codice non è scritto da nessuna
    // parte nell'app: sta in `cm_settings`, e senza quella riga non si apre.

    /** Pressione lunga sul campo delle bolle: apre il widget del codice. */
    fun apriCattura() {
        _state.value = _state.value.copy(inCattura = true, coloriCatturati = emptyList())
        chiusuraCattura?.cancel()
        chiusuraCattura = viewModelScope.launch {
            delay(ATTESA_CATTURA)
            chiudiCattura()
        }
    }

    fun chiudiCattura() {
        chiusuraCattura?.cancel()
        chiusuraCattura = null
        _state.value = _state.value.copy(inCattura = false, coloriCatturati = emptyList())
    }

    /**
     * Una bolla toccata a widget aperto: il suo colore entra nella casella
     * successiva. Piene tutte le caselle, il tocco dopo **ricomincia da capo**
     * invece di ignorare — sbagliando il primo colore, altrimenti, si sarebbe
     * costretti a chiudere e riaprire.
     */
    fun catturaColore(colore: String) {
        val stato = _state.value
        val presi = if (stato.coloriCatturati.size >= stato.caselle) emptyList()
        else stato.coloriCatturati
        _state.value = stato.copy(coloriCatturati = presi + colore)
        // Ogni tocco rimanda la chiusura: il tempo serve a chi sta digitando,
        // non a chi ha aperto il widget per sbaglio.
        chiusuraCattura?.cancel()
        chiusuraCattura = viewModelScope.launch {
            delay(ATTESA_CATTURA)
            chiudiCattura()
        }
    }

    /**
     * Confronta e, se torna, **inverte** la modalità nascosta — il codice la
     * accende e la rispegne, come `checkSequence()`. Un confronto sbagliato non
     * dice niente: chiude il widget e basta.
     */
    fun verificaCodice() {
        val stato = _state.value
        val colori = stato.coloriCatturati
        chiudiCattura()
        if (stato.sequenza.isEmpty()) {
            Log.w("AppSphereHome", "nessuna sequenza in cm_settings: il codice non può aprire niente")
            return
        }
        val torna = colori.size == stato.sequenza.size &&
            colori.zip(stato.sequenza).all { (a, b) -> a.equals(b, ignoreCase = true) }
        if (torna) ModalitaNascosta.cambia()
    }

    /** L'occhio: spegne la modalità nascosta senza rifare il codice. */
    fun spegniModalitaNascosta() {
        chiudiCattura()
        ModalitaNascosta.spegni()
    }

    fun chiudiAvvisi() {
        _state.value = _state.value.copy(avvisiChiusi = true)
    }

    /**
     * Rilegge i soli punti spesi, al ritorno dal catalogo premi.
     *
     * I punteggi delle app non possono essere cambiati nel frattempo — di là
     * non si tocca nessuna app — e rifarli vorrebbe dire una `score_query` per
     * riga per vedere gli stessi numeri di un momento fa.
     */
    fun ricaricaPuntiSpesi() {
        viewModelScope.launch {
            val spesi = PremiRepository.puntiSpesi()
            _state.value = _state.value.copy(puntiSpesi = spesi)
            salvaCache(_state.value.bolle, _state.value.totaleLordo, spesi)
        }
    }

    private fun leggiCache() {
        val grezzo = prefs.getString(CHIAVE_CACHE, null) ?: return
        try {
            val bolle = json.decodeFromString<List<BollaSalvata>>(grezzo).map {
                Bolla(it.htmlFile, it.nome, it.descrizione, it.punteggio, it.colore, it.riservata, it.route, it.contaPunti)
            }
            // Una bolla in cache che nel frattempo non è più portata va buttata,
            // o si aprirebbe una rotta che non esiste più.
            val valide = bolle.filter { PortedApps.perHtmlFile.containsKey(it.htmlFile) }
            _state.value = _state.value.copy(
                bolle = valide,
                // Il totale si tiene in cache insieme alle bolle: mostrare il
                // solo lordo mentre i punti spesi arrivano darebbe un numero
                // più alto del vero, che poi cala sotto gli occhi.
                totaleLordo = prefs.getInt(CHIAVE_LORDO, 0),
                puntiSpesi = prefs.getInt(CHIAVE_SPESI, 0),
            )
        } catch (e: Exception) {
            Log.w("AppSphereHome", "cache bolle illeggibile, la butto: ${e.message}")
            prefs.edit().remove(CHIAVE_CACHE).apply()
        }
    }

    private fun salvaCache(bolle: List<Bolla>, lordo: Int, spesi: Int) {
        // Le riservate non finiscono in cache: al prossimo avvio la modalità
        // nascosta è spenta, e comparirebbero prima che qualcuno la riaccenda.
        val daSalvare = bolle.filterNot { it.riservata }.map {
            BollaSalvata(it.htmlFile, it.nome, it.descrizione, it.punteggio, it.colore, it.riservata, it.route, it.contaPunti)
        }
        try {
            prefs.edit()
                .putString(CHIAVE_CACHE, json.encodeToString(daSalvare))
                .putInt(CHIAVE_LORDO, lordo)
                .putInt(CHIAVE_SPESI, spesi)
                .apply()
        } catch (e: Exception) {
            Log.w("AppSphereHome", "cache bolle non salvata: ${e.message}")
        }
    }

    private companion object {
        const val CHIAVE_CACHE = "bolle_cache"
        const val CHIAVE_LORDO = "totale_lordo"
        const val CHIAVE_SPESI = "punti_spesi"

        /** I 15 secondi di inattività dopo cui il widget si richiude (web: `captureTimeout`). */
        const val ATTESA_CATTURA = 15_000L
    }
}
