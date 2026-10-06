package com.garsal.appsphere.memo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.core.ModalitaNascosta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

enum class Ordinamento(val etichetta: String) {
    AGGIORNATE("Ultime modificate"),
    CREATE("Ultime create"),
    TITOLO("Titolo"),
}

/**
 * Le voci e le misure di una scheda aperta in vista, più le registrazioni del
 * diario. Si caricano **solo aprendo la scheda**: nell'elenco bastano i
 * conteggi, che arrivano già con la query delle schede.
 */
data class VistaScheda(
    val schedaId: String,
    val voci: List<MmVoce> = emptyList(),
    val misure: List<MmMisura> = emptyList(),
    val registrazioni: List<MmRegistrazione> = emptyList(),
    val caricamento: Boolean = true,
) {
    val vociFatte: Int get() = voci.count { it.fatta }

    /** Le registrazioni dalla più vecchia: il riepilogo le vuole in ordine di tempo. */
    val storico: List<MmRegistrazione> get() = registrazioni.reversed()
}

data class MemoState(
    val schede: List<MmScheda> = emptyList(),
    val categorie: List<CmCategoria> = emptyList(),
    val ricerca: String = "",
    val filtroCategoria: String? = null,
    val ordinamento: Ordinamento = Ordinamento.AGGIORNATE,
    /** La Tab aperta. `null` = 📌 Fissa, l'unica che attraversa i quattro tipi. */
    val tipo: TipoScheda? = TipoScheda.NOTA,
    /** Le foto già caricate, per scheda: si leggono solo quando serve. */
    val immagini: Map<String, List<MmImmagine>> = emptyMap(),
    val vista: VistaScheda? = null,
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
    /** La modalità nascosta di AppSphere: qui si legge, non si accende. */
    val modalitaNascosta: Boolean = false,
    /** Il filtro 👁: **solo** le riservate. Vale solo a modalità accesa. */
    val soloRiservate: Boolean = false,
) {
    val soloFissate: Boolean get() = tipo == null

    /**
     * Le schede da mostrare — gli stessi passaggi di `getFilteredCards()`:
     * filtro per tipo, per categoria, ricerca su titolo **e testo**,
     * ordinamento.
     *
     * Le fissate restano in cima anche quando si ordina per titolo o per data
     * di creazione: sul web è l'ordine che arriva dal database, qui va
     * riscritto a mano perché l'ordinamento lo rifà il client.
     */
    val visibili: List<MmScheda>
        get() {
            // 📌 Fissa attraversa i quattro tipi, ed è la ragione per cui il
            // segno del tipo resta sulla scheda anche ora che ogni Tab ne
            // mostra uno solo.
            var elenco = if (tipo == null) schede.filter { it.fissata }
            else schede.filter { it.tipo == tipo }

            filtroCategoria?.let { cat -> elenco = elenco.filter { cat in it.categorie } }

            val q = ricerca.trim().lowercase()
            if (q.isNotEmpty()) {
                elenco = elenco.filter {
                    it.titolo.lowercase().contains(q) || it.anteprima.lowercase().contains(q)
                }
            }

            val perOrdine: Comparator<MmScheda> = when (ordinamento) {
                Ordinamento.AGGIORNATE -> compareByDescending { it.aggiornata.orEmpty() }
                Ordinamento.CREATE -> compareByDescending { it.creata.orEmpty() }
                Ordinamento.TITOLO -> compareBy { it.titolo.lowercase() }
            }
            return elenco.sortedWith(compareByDescending<MmScheda> { it.fissata }.then(perOrdine))
        }

    fun categoria(id: String): CmCategoria? = categorie.firstOrNull { it.id == id }

    /**
     * Quante schede ha una categoria **nella Tab aperta**: con «Liste» attiva
     * una categoria che ha solo note deve mostrare 0, non il totale.
     */
    fun quante(categoriaId: String): Int =
        schede.count { s ->
            categoriaId in s.categorie && (if (tipo == null) s.fissata else s.tipo == tipo)
        }

    /** Quante schede ci sono in tutto nella Tab aperta. */
    val quanteInTab: Int
        get() = schede.count { if (tipo == null) it.fissata else it.tipo == tipo }

    /**
     * La somma dei punti di tutti i 🏅 premiati: è lo stesso numero che la
     * `score_query` di `cm_apps` calcola sul database per la bolla in home,
     * rifatto qui sulle schede già caricate perché si legga subito.
     *
     * ⚠️ Conta **tutti** i premiati e non quelli filtrati: una ricerca in
     * corso non deve cambiare il totale, o quel numero direbbe una cosa
     * diversa da quella che si legge in home. Gemello di `totalePunti()` in
     * `memo.html`.
     */
    val totalePunti: Int
        get() = schede.filter { it.tipo == TipoScheda.PREMIATO }.sumOf { it.punteggio }

    fun scheda(id: String?): MmScheda? = schede.firstOrNull { it.id == id }
}

class MemoViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MemoState())
    val state: StateFlow<MemoState> = _state.asStateFlow()

    init {
        carica()
        // La modalità nascosta la accende la home col codice a colori: qui si
        // osserva, e quando cambia le schede si rileggono — il filtro sta nella
        // query, quindi non basta ridisegnare.
        viewModelScope.launch {
            ModalitaNascosta.attiva.collect { attiva ->
                _state.value = _state.value.copy(
                    modalitaNascosta = attiva,
                    // Spegnendo la modalità cade anche il filtro 👁, o l'elenco
                    // resterebbe a chiedere solo le riservate che non può vedere.
                    soloRiservate = if (attiva) _state.value.soloRiservate else false,
                )
                carica()
            }
        }
    }

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val stato = _state.value
                val schede = MemoRepository.schede(stato.modalitaNascosta, stato.soloRiservate)
                val categorie = MemoRepository.categorie()
                _state.value = _state.value.copy(
                    schede = schede,
                    categorie = categorie,
                    caricamento = false,
                )
            } catch (e: Exception) {
                Log.w(TAG, "caricamento schede fallito", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    fun cerca(testo: String) { _state.value = _state.value.copy(ricerca = testo) }

    fun filtraCategoria(id: String?) { _state.value = _state.value.copy(filtroCategoria = id) }

    fun ordina(ordinamento: Ordinamento) {
        _state.value = _state.value.copy(ordinamento = ordinamento)
    }

    /**
     * Cambia Tab. Il filtro categoria **non sopravvive**: le categorie di un
     * tipo non sono quelle di un altro, e una Tab che si aprisse già filtrata su
     * una categoria che lì non esiste sembrerebbe vuota.
     */
    fun apriTab(tipo: TipoScheda?) {
        _state.value = _state.value.copy(tipo = tipo, filtroCategoria = null)
    }

    /**
     * Alza o abbassa il filtro 👁 — **solo** a modalità nascosta accesa, come
     * `toggleHiddenMode()`, che a modalità spenta non fa niente.
     */
    fun cambiaFiltroRiservate() {
        if (!_state.value.modalitaNascosta) return
        _state.value = _state.value.copy(soloRiservate = !_state.value.soloRiservate)
        carica()
    }

    fun scartaMessaggi() { _state.value = _state.value.copy(errore = null, messaggio = null) }

    /**
     * Le foto di una scheda, chieste quando la si apre.
     *
     * Non si caricano insieme all'elenco: gli URL sono firmati e scadono in due
     * ore, e chiederli per tutte le schede a ogni apertura dell'app sarebbe una
     * richiesta per foto per niente. Nell'elenco basta il conteggio, che arriva
     * già con la scheda.
     */
    fun caricaImmagini(schedaId: String) {
        viewModelScope.launch {
            try {
                val foto = MemoRepository.immagini(schedaId)
                _state.value = _state.value.copy(
                    immagini = _state.value.immagini + (schedaId to foto),
                )
            } catch (e: Exception) {
                Log.w(TAG, "foto non caricate per $schedaId", e)
            }
        }
    }

    // ── Vista lista / diario / link ─────────────────────────────────────────

    /** Apre una lista, un diario o un link e ne carica le righe figlie. */
    fun apriVista(scheda: MmScheda) {
        _state.value = _state.value.copy(vista = VistaScheda(schedaId = scheda.id))
        viewModelScope.launch {
            try {
                val vista = when (scheda.tipo) {
                    TipoScheda.LISTA -> VistaScheda(
                        schedaId = scheda.id,
                        voci = MemoRepository.voci(scheda.id),
                        caricamento = false,
                    )
                    TipoScheda.DIARIO -> VistaScheda(
                        schedaId = scheda.id,
                        misure = MemoRepository.misure(scheda.id),
                        registrazioni = MemoRepository.registrazioni(scheda.id),
                        caricamento = false,
                    )
                    // Un link non ha righe figlie da leggere: l'url arriva già
                    // con l'elenco, dentro la query delle schede.
                    TipoScheda.LINK -> VistaScheda(scheda.id, caricamento = false)
                    // Un premiato nemmeno: i suoi punti stanno sulla riga
                    // della scheda, e si apre in lettura come una nota.
                    TipoScheda.PREMIATO, TipoScheda.NOTA ->
                        VistaScheda(scheda.id, caricamento = false)
                }
                // Nel frattempo la vista potrebbe essere stata chiusa: scrivere
                // adesso la riaprirebbe sotto le dita di chi è già tornato indietro.
                if (_state.value.vista?.schedaId == scheda.id) {
                    _state.value = _state.value.copy(vista = vista)
                }
            } catch (e: Exception) {
                Log.w(TAG, "vista non caricata per ${scheda.id}", e)
                _state.value = _state.value.copy(
                    vista = _state.value.vista?.copy(caricamento = false),
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    fun chiudiVista() { _state.value = _state.value.copy(vista = null) }

    /**
     * La spunta di una voce, **ottimistica con rollback** come sul web: la voce
     * cambia subito e torna indietro se il database rifiuta, così non resta a
     * schermo una spunta finta che sparisce al ricarico.
     */
    fun spuntaVoce(voce: MmVoce, fatta: Boolean) {
        val vista = _state.value.vista ?: return
        val prima = vista.voci
        aggiornaVoci(vista.voci.map {
            if (it.id == voce.id)
                it.copy(fatta = fatta, fattaIl = if (fatta) Instant.now().toString() else null)
            else it
        })
        viewModelScope.launch {
            try {
                MemoRepository.spuntaVoce(voce.id, fatta)
                ricontaSchede()
            } catch (e: Exception) {
                Log.w(TAG, "spunta non salvata", e)
                aggiornaVoci(prima)
                _state.value = _state.value.copy(
                    errore = "⚠️ Non sono riuscito a salvare la spunta",
                )
            }
        }
    }

    fun aggiungiVoce(testo: String) {
        val vista = _state.value.vista ?: return
        val pulito = testo.trim()
        if (pulito.isEmpty()) return
        viewModelScope.launch {
            try {
                val posizione = (vista.voci.maxOfOrNull { it.posizione } ?: -1) + 1
                val nuova = MemoRepository.aggiungiVoce(vista.schedaId, pulito, posizione)
                aggiornaVoci(_state.value.vista?.voci.orEmpty() + nuova)
                ricontaSchede()
            } catch (e: Exception) {
                Log.w(TAG, "voce non aggiunta", e)
                _state.value = _state.value.copy(errore = e.message ?: "Voce non aggiunta")
            }
        }
    }

    fun eliminaVoce(voce: MmVoce) {
        viewModelScope.launch {
            try {
                MemoRepository.eliminaVoce(voce.id)
                aggiornaVoci(_state.value.vista?.voci.orEmpty().filterNot { it.id == voce.id })
                ricontaSchede()
            } catch (e: Exception) {
                Log.w(TAG, "voce non eliminata", e)
                _state.value = _state.value.copy(errore = e.message ?: "Voce non eliminata")
            }
        }
    }

    private fun aggiornaVoci(voci: List<MmVoce>) {
        _state.value = _state.value.copy(vista = _state.value.vista?.copy(voci = voci))
    }

    /**
     * Scrive una registrazione e rilegge quelle del diario.
     *
     * `misure` porta **solo** ciò che è stato misurato davvero: una misura non
     * toccata non ha una chiave qui e non ne prende una a zero.
     */
    fun salvaRegistrazione(
        id: String?,
        titolo: String,
        data: String,
        nota: String,
        misure: Map<String, JsonPrimitive>,
        onFatto: () -> Unit,
    ) {
        val vista = _state.value.vista ?: return
        viewModelScope.launch {
            try {
                MemoRepository.salvaRegistrazione(id, vista.schedaId, titolo, data, nota, misure)
                val registrazioni = MemoRepository.registrazioni(vista.schedaId)
                _state.value = _state.value.copy(
                    vista = _state.value.vista?.copy(registrazioni = registrazioni),
                    messaggio = "✅ Registrazione salvata",
                )
                onFatto()
                ricontaSchede()
            } catch (e: Exception) {
                Log.w(TAG, "registrazione non salvata", e)
                _state.value = _state.value.copy(errore = e.message ?: "Registrazione non salvata")
            }
        }
    }

    fun eliminaRegistrazione(registrazione: MmRegistrazione) {
        viewModelScope.launch {
            try {
                MemoRepository.eliminaRegistrazione(registrazione.id)
                _state.value = _state.value.copy(
                    vista = _state.value.vista?.copy(
                        registrazioni = _state.value.vista?.registrazioni.orEmpty()
                            .filterNot { it.id == registrazione.id }
                    ),
                    messaggio = "🗑 Registrazione eliminata",
                )
                ricontaSchede()
            } catch (e: Exception) {
                Log.w(TAG, "registrazione non eliminata", e)
                _state.value = _state.value.copy(errore = e.message ?: "Eliminazione non riuscita")
            }
        }
    }

    /**
     * Rilegge le sole schede, per i conteggi in elenco (☑️ 3/7, 📊 12) dopo una
     * modifica fatta dalla vista: senza, restano fermi finché non si ricarica.
     */
    private fun ricontaSchede() {
        viewModelScope.launch {
            runCatching {
                val stato = _state.value
                MemoRepository.schede(stato.modalitaNascosta, stato.soloRiservate)
            }.onSuccess { _state.value = _state.value.copy(schede = it) }
        }
    }

    // ── Scrittura della scheda ──────────────────────────────────────────────

    /**
     * Salva la scheda e **poi** le righe figlie e le foto, come `saveCard()`:
     * voci, misure e foto hanno bisogno dell'id della scheda, quindi una scheda
     * nuova va scritta prima.
     *
     * Una foto che non si carica non fa fallire il salvataggio — il testo è già
     * al sicuro — ma lo dice, invece di sparire in silenzio.
     */
    fun salva(
        id: String?,
        bozza: BozzaScheda,
        fotoNuove: List<FotoInAttesa>,
        fotoDaTogliere: List<MmImmagine>,
        onFatto: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val schedaId = MemoRepository.salva(
                    id = id,
                    titolo = bozza.titolo.trim(),
                    contenutoHtml = MemoHtml.aHtml(bozza.testo),
                    tipo = bozza.tipo,
                    riservato = bozza.riservato && bozza.tipo == TipoScheda.DIARIO,
                    fissata = bozza.fissata,
                    colore = bozza.colore,
                    categorie = bozza.categorie,
                    punteggio = bozza.puntiDaSalvare,
                )

                when (bozza.tipo) {
                    TipoScheda.LISTA ->
                        MemoRepository.salvaVoci(schedaId, bozza.voci, bozza.vociTolte)
                    TipoScheda.DIARIO ->
                        MemoRepository.salvaMisure(schedaId, bozza.misure, bozza.misureTolte)
                    TipoScheda.LINK ->
                        MemoRepository.salvaLink(schedaId, bozza.linkUrl.trim())
                    // Un premiato non ha righe figlie: i suoi punti stanno
                    // sulla riga della scheda, scritti qui sopra.
                    TipoScheda.PREMIATO, TipoScheda.NOTA -> Unit
                }

                fotoDaTogliere.forEach { foto ->
                    runCatching { MemoRepository.eliminaImmagine(foto) }
                        .onFailure { Log.w(TAG, "foto non eliminata: ${foto.id}", it) }
                }

                var fallite = 0
                fotoNuove.forEach { foto ->
                    runCatching {
                        val dati = MemoFoto.byte(getApplication(), foto.uri)
                        MemoRepository.caricaImmagine(schedaId, dati, foto.nome, foto.mime)
                    }.onFailure {
                        fallite++
                        Log.w(TAG, "foto non caricata: ${foto.nome}", it)
                    }
                }

                _state.value = _state.value.copy(
                    messaggio = if (fallite == 0) "✅ Scheda salvata"
                    else "Scheda salvata, ma $fallite foto non sono state caricate",
                    // Le foto in memoria sono quelle di prima: si buttano, e la
                    // scheda le richiede aprendosi. Tenerle mostrerebbe ancora
                    // quella appena tolta.
                    immagini = _state.value.immagini - schedaId,
                )
                onFatto()
                carica()
                caricaImmagini(schedaId)
                // Una vista aperta sulla scheda appena salvata mostra ancora le
                // voci o le misure di prima: si rileggono.
                if (_state.value.vista?.schedaId == schedaId) apriVistaPerId(schedaId)
            } catch (e: Exception) {
                Log.w(TAG, "salvataggio non riuscito", e)
                _state.value = _state.value.copy(errore = e.message ?: "Salvataggio non riuscito")
            }
        }
    }

    /** Ricarica una vista quando la scheda non è (ancora) nell'elenco in memoria. */
    private fun apriVistaPerId(schedaId: String) {
        viewModelScope.launch {
            runCatching {
                VistaScheda(
                    schedaId = schedaId,
                    voci = MemoRepository.voci(schedaId),
                    misure = MemoRepository.misure(schedaId),
                    registrazioni = MemoRepository.registrazioni(schedaId),
                    caricamento = false,
                )
            }.onSuccess { vista ->
                if (_state.value.vista?.schedaId == schedaId) {
                    _state.value = _state.value.copy(vista = vista)
                }
            }
        }
    }

    fun elimina(id: String, onFatto: () -> Unit) {
        viewModelScope.launch {
            try {
                MemoRepository.elimina(id)
                _state.value = _state.value.copy(
                    messaggio = "🗑 Scheda eliminata",
                    immagini = _state.value.immagini - id,
                    vista = _state.value.vista?.takeIf { it.schedaId != id },
                )
                onFatto()
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "eliminazione non riuscita", e)
                _state.value = _state.value.copy(errore = e.message ?: "Eliminazione non riuscita")
            }
        }
    }

    /**
     * Fissa o libera una scheda dall'elenco, senza aprirla.
     *
     * ⚠️ Il contenuto si riscrive **com'è**, senza passare dalla conversione in
     * marcatori e ritorno: un giro andata-e-ritorno si porterebbe via la
     * formattazione senza che nessuno abbia toccato il testo. Vale anche per
     * `kind` e `riservato`, che si rimandano identici.
     */
    fun cambiaFissata(scheda: MmScheda) {
        viewModelScope.launch {
            try {
                MemoRepository.salva(
                    id = scheda.id,
                    titolo = scheda.titolo,
                    contenutoHtml = scheda.contenuto,
                    tipo = scheda.tipo,
                    riservato = scheda.riservato,
                    fissata = !scheda.fissata,
                    colore = scheda.colore,
                    categorie = scheda.categorie,
                    // ⚠️ I punti si riscrivono com'erano: `salva` riscrive la
                    // riga intera, e un 📌 su un premiato ne azzererebbe il
                    // punteggio — cioè farebbe calare la bolla in AppSphere
                    // per un gesto che con quel numero non c'entra niente.
                    punteggio = scheda.punteggio,
                )
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "pin non cambiato", e)
                _state.value = _state.value.copy(errore = e.message ?: "Operazione non riuscita")
            }
        }
    }

    private companion object {
        const val TAG = "AppSphereMemo"
    }
}
