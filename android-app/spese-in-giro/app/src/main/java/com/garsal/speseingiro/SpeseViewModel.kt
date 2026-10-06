package com.garsal.speseingiro

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val caricamento: Boolean = false,
    val viaggi: List<Prefs.Viaggio> = emptyList(),
    val attivo: Prefs.Viaggio? = null,
    val stato: StatoViaggio? = null,
    val messaggio: String? = null,
)

/**
 * Lo stato di quel che si vede, e nient'altro.
 *
 * ⚠️ Nessun conto si fa qui: saldo, chi può confermare e chi può cancellare
 * arrivano già decisi da `vg_stato` e dalle altre RPC. Dopo ogni scrittura si
 * **rilegge**, esattamente come `TasksViewModel` dopo `task_complete`: è il
 * modo per non avere mai a schermo un numero che il database non conosce.
 */
class SpeseViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        Foto.pulisci(app)
        rileggiElenco()
        ricarica()
    }

    private fun rileggiElenco() {
        _ui.value = _ui.value.copy(viaggi = prefs.viaggi(), attivo = prefs.attivo())
    }

    fun messaggioVisto() {
        _ui.value = _ui.value.copy(messaggio = null)
    }

    private fun avvisa(t: String) {
        _ui.value = _ui.value.copy(messaggio = t)
    }

    fun ricarica() {
        val token = prefs.attivo()?.token ?: run {
            _ui.value = _ui.value.copy(stato = null)
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(caricamento = true)
            val e = Api.stato(token)
            val s = e.body?.let { statoDa(it) }
            if (e.ok && s != null) {
                // I nomi in locale seguono quelli veri: il viaggio si è potuto
                // rinominare, e l'altro è entrato dopo che questo telefono aveva
                // già salvato la sua riga.
                prefs.aggiornaNome(s.viaggio.id, s.viaggio.nome, s.viaggio.codice, s.io.nome)
                _ui.value = _ui.value.copy(caricamento = false, stato = s, viaggi = prefs.viaggi(),
                                           attivo = prefs.attivo())
            } else {
                // Senza rete si resta su quello che si stava guardando: un
                // elenco che si svuota perché manca il campo si legge come un
                // archivio perso.
                _ui.value = _ui.value.copy(caricamento = false, messaggio = e.messaggio)
            }
        }
    }

    fun crea(viaggio: String, io: String, inizio: String?, fine: String?, poi: () -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(caricamento = true)
            val e = Api.crea(viaggio, io, inizio, fine)
            _ui.value = _ui.value.copy(caricamento = false)
            val b = e.body
            if (!e.ok || b == null) { avvisa(e.messaggio); return@launch }
            prefs.salva(
                Prefs.Viaggio(
                    token = b.optString("token"), viaggioId = b.optString("viaggio_id"),
                    nome = viaggio, codice = b.optString("codice"), io = io,
                )
            )
            rileggiElenco(); ricarica(); poi()
        }
    }

    fun entra(codice: String, io: String, poi: () -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(caricamento = true)
            val e = Api.entra(codice.trim().uppercase(), io)
            _ui.value = _ui.value.copy(caricamento = false)
            val b = e.body
            if (!e.ok || b == null) { avvisa(e.messaggio); return@launch }
            prefs.salva(
                Prefs.Viaggio(
                    token = b.optString("token"), viaggioId = b.optString("viaggio_id"),
                    nome = "Viaggio", codice = codice.trim().uppercase(), io = io,
                )
            )
            rileggiElenco(); ricarica(); poi()
            if (b.optBoolean("rientro")) avvisa("Rientrato nel viaggio come $io")
        }
    }

    fun scegli(token: String) {
        prefs.tokenAttivo = token
        _ui.value = _ui.value.copy(stato = null)
        rileggiElenco(); ricarica()
    }

    /** Toglie il viaggio da QUESTO telefono. Non cancella niente sul database:
     *  l'altro continua a vedere tutto, e col codice si rientra. La finestra di
     *  conferma lo dice prima, non dopo. */
    fun esci(viaggioId: String) {
        prefs.dimentica(viaggioId)
        _ui.value = _ui.value.copy(stato = null)
        rileggiElenco(); ricarica()
    }

    private fun token(): String? = _ui.value.attivo?.token

    /**
     * Salva la voce, e se c'è una foto la carica **prima**.
     *
     * ⚠️ Se il salvataggio non passa, la foto appena caricata si butta: senza,
     * resterebbe nel bucket un'immagine che nessuna riga nomina — cioè un file
     * che nessuno sa più cos'è e che dall'app non si può più togliere. È lo
     * stesso ordine di `elimina()` nel Forziere, letto al contrario.
     */
    fun salvaVoce(
        voceId: String?, tipo: String, importo: Double, data: String, descrizione: String,
        categoria: String, daId: String, perChi: String?, beneficiario: String?,
        foto: Bitmap?, letto: Double?, poi: () -> Unit,
    ) {
        val t = token() ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(caricamento = true)

            var path: String? = null
            if (foto != null) {
                val f = Api.caricaScontrino(t, Foto.jpeg(foto))
                if (!f.ok) {
                    _ui.value = _ui.value.copy(caricamento = false)
                    avvisa("Scontrino non caricato: ${f.messaggio}")
                    return@launch
                }
                path = f.body?.optString("path")?.ifBlank { null }
            }

            val e = Api.salvaVoce(t, voceId, tipo, importo, data, descrizione, categoria,
                                  daId, perChi, beneficiario, path, letto)
            _ui.value = _ui.value.copy(caricamento = false)
            if (!e.ok) {
                path?.let { Api.cancellaScontrino(t, it) }
                avvisa(e.messaggio)
                return@launch
            }
            avvisa(if (voceId == null) "Segnata. Ora tocca all'altro confermarla." else "Corretta.")
            ricarica(); poi()
        }
    }

    /* ── le categorie del viaggio ───────────────────────────────────────── */

    fun salvaCategoria(id: String?, emoji: String, nome: String) =
        agisci(if (id == null) "Categoria aggiunta" else "Categoria aggiornata") {
            Api.salvaCategoria(it, id, emoji, nome)
        }

    /** ⚠️ Il rifiuto («è usata in N voci») arriva dal server e si mostra com'è:
     *  è l'unica risposta che sa quante voci la citano davvero. */
    fun eliminaCategoria(id: String) =
        agisci("Categoria tolta") { Api.eliminaCategoria(it, id) }

    fun conferma(voceId: String) = agisci { Api.conferma(it, voceId) }
    fun elimina(voceId: String) = agisci { Api.elimina(it, voceId) }
    fun chiediCancellazione(voceId: String, motivo: String) =
        agisci("Richiesta inviata: ora decide l'altro") { Api.chiediCancellazione(it, voceId, motivo) }
    fun risolviCancellazione(voceId: String, approva: Boolean) =
        agisci(if (approva) "Cancellata" else "Richiesta rifiutata") {
            Api.risolviCancellazione(it, voceId, approva)
        }

    private fun agisci(ok: String? = null, azione: suspend (String) -> Api.Esito) {
        val t = token() ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(caricamento = true)
            val e = azione(t)
            _ui.value = _ui.value.copy(caricamento = false)
            if (!e.ok) avvisa(e.messaggio) else ok?.let { avvisa(it) }
            ricarica()
        }
    }

    suspend fun scontrinoDi(voceId: String): ByteArray? {
        val t = token() ?: return null
        val url = Api.urlScontrino(t, voceId) ?: return null
        return Api.scarica(url)
    }
}
