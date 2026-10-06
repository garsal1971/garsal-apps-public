package com.garsal.appsphere.tafiri

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class TaFiriState(
    val sfide: List<SfSfida> = emptyList(),
    val checkin: Map<String, List<SfCheckin>> = emptyMap(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
) {
    val attive: List<SfSfida> get() = sfide.filter { it.attiva }

    val concluse: List<SfSfida> get() = sfide.filterNot { it.attiva }

    /** I punti guadagnati: la somma dei punteggi finali, come il widget del web. */
    val puntiTotali: Int get() = concluse.sumOf { it.finalScore ?: 0 }

    fun giorno(sfidaId: String, data: String): SfCheckin? =
        checkin[sfidaId]?.firstOrNull { it.dayDate == data }

    fun statoDi(sfidaId: String, data: String): String = giorno(sfidaId, data)?.status ?: "pending"

    fun giorniFatti(sfidaId: String): Int =
        checkin[sfidaId]?.count { it.status == "done" } ?: 0

    /**
     * Le sfide che **adesso** chiedono il check-in: attive, dentro il periodo,
     * con l'orario di avviso già passato e il giorno di oggi ancora da segnare.
     * Sono le stesse condizioni di `getPendingCheckinsNow()`, nello stesso
     * ordine — se una delle due app ne mostrasse una in più o in meno, il
     * banner smetterebbe di essere la stessa domanda.
     */
    fun daSpuntareOra(oggi: LocalDate = LocalDate.now(), adesso: LocalTime = LocalTime.now()): List<SfSfida> {
        val oggiStr = oggi.toString()
        return attive.filter { s ->
            oggiStr >= s.startDate && oggiStr <= s.endDate &&
                !oraDi(s).isAfter(adesso) &&
                giorno(s.id, oggiStr)?.status == "pending"
        }
    }

    /**
     * L'orario di check-in. Se non si legge si prende mezzanotte, cioè «l'ora è
     * passata»: questa funzione gira a ogni ridisegno della schermata, e
     * un'eccezione qui dentro non sarebbe un banner storto ma l'app che si
     * chiude.
     */
    private fun oraDi(sfida: SfSfida): LocalTime =
        runCatching { LocalTime.parse(sfida.ora) }.getOrDefault(LocalTime.MIDNIGHT)
}

class TaFiriViewModel : ViewModel() {

    private val _state = MutableStateFlow(TaFiriState())
    val state: StateFlow<TaFiriState> = _state.asStateFlow()

    init { carica() }

    /**
     * Il giro di apertura, come `loadAll()` nel web e nello stesso ordine:
     * si leggono sfide e spunte, si chiudono quelle scadute, si rimettono le
     * regole di promemoria mancanti, e solo allora si mostra tutto.
     */
    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                var sfide = TaFiriRepository.sfide()
                val checkin = TaFiriRepository.checkin()

                if (chiudiScadute(sfide)) sfide = TaFiriRepository.sfide()

                _state.value = _state.value.copy(
                    sfide = sfide,
                    checkin = checkin,
                    caricamento = false,
                )
                rimettiRegoleMancanti(sfide, checkin)
            } catch (e: Exception) {
                Log.w(TAG, "caricamento fallito", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    /**
     * Chiude le sfide la cui ultima giornata è passata. Torna `true` se ne ha
     * chiusa almeno una, cioè se l'elenco va riletto: il punteggio finale e lo
     * stato li scrive la RPC, e da qui non si sa cosa abbia deciso.
     */
    private suspend fun chiudiScadute(sfide: List<SfSfida>): Boolean {
        val oggi = LocalDate.now().toString()
        val scadute = sfide.filter { it.attiva && it.endDate < oggi }
        scadute.forEach { s ->
            runCatching { TaFiriRepository.finalizza(s.id) }
                .onFailure { Log.w(TAG, "sfida non finalizzata: ${s.id}", it) }
        }
        return scadute.isNotEmpty()
    }

    /**
     * Rete di sicurezza per le sfide attive rimaste senza promemoria — nate
     * prima che ci fosse, o con la regola persa per strada. Il giorno a cui
     * agganciarla è il **primo ancora da segnare**, non la data di partenza,
     * che può essere passata da un pezzo.
     *
     * È la stessa cosa che fa `ensureSmartBlockRules()` nel web. Gira dopo che
     * la schermata è già a video: è manutenzione, e non c'è ragione di far
     * aspettare l'elenco.
     */
    private suspend fun rimettiRegoleMancanti(
        sfide: List<SfSfida>,
        checkin: Map<String, List<SfCheckin>>,
    ) {
        val attive = sfide.filter { it.attiva }
        if (attive.isEmpty()) return
        try {
            val conRegola = TaFiriRepository.sfideConRegola()
            val oggi = LocalDate.now().toString()
            attive.filterNot { it.id in conRegola }.forEach { s ->
                val giorno = prossimoDaSegnare(checkin[s.id], oggi) ?: return@forEach
                TaFiriRepository.scriviRegola(s, giorno)
            }
        } catch (e: Exception) {
            Log.w(TAG, "regole smart_block non verificate", e)
        }
    }

    /**
     * Il primo giorno ancora `pending` da oggi in avanti; se sono tutti nel
     * passato, il primo dell'elenco. Stessa scelta del web: una sfida indietro
     * col check-in deve comunque avere un promemoria a cui agganciarsi.
     */
    private fun prossimoDaSegnare(giorni: List<SfCheckin>?, oggi: String): String? {
        val pendenti = giorni.orEmpty().filter { it.status == "pending" }.map { it.dayDate }.sorted()
        return pendenti.firstOrNull { it >= oggi } ?: pendenti.firstOrNull()
    }

    // ── Check-in ─────────────────────────────────────────────────────────

    /**
     * Il check-in di oggi dal banner: passa dalla RPC, che sposta il
     * promemoria al giorno dopo.
     */
    fun spuntaOggi(sfida: SfSfida, stato: String) {
        viewModelScope.launch {
            try {
                val esito = TaFiriRepository.checkinDiOggi(sfida.id, stato, LocalDate.now())
                if (!esito.ok) {
                    _state.value = _state.value.copy(
                        messaggio = "Non riuscito: ${esito.errore ?: "il server ha detto no"}"
                    )
                    return@launch
                }
                ricaricaCheckin()
            } catch (e: Exception) {
                Log.w(TAG, "check-in non riuscito", e)
                _state.value = _state.value.copy(
                    messaggio = "Non riuscito: ${e.message ?: "connessione assente"}"
                )
            }
        }
    }

    /**
     * Correzione di un giorno dalla griglia: gira fra i tre stati come
     * `cycleCheckin`, e **non tocca il promemoria** — vedi
     * `TaFiriRepository.checkinDiOggi`.
     *
     * Ottimistica con rollback, come le spunte di Spuntiamola: la pallina
     * cambia colore subito e torna indietro se il database dice di no.
     */
    fun cambiaGiorno(sfida: SfSfida, data: String) {
        val prima = _state.value.checkin
        val corrente = _state.value.statoDi(sfida.id, data)
        val nuovo = when (corrente) {
            "pending" -> "done"
            "done" -> "not_done"
            else -> "pending"
        }

        _state.value = _state.value.copy(checkin = conStato(prima, sfida.id, data, nuovo))

        viewModelScope.launch {
            try {
                TaFiriRepository.cambiaGiorno(sfida.id, data, nuovo)
            } catch (e: Exception) {
                Log.w(TAG, "giorno non aggiornato", e)
                _state.value = _state.value.copy(
                    checkin = prima,
                    messaggio = "Non salvato: ${e.message ?: "connessione assente"}",
                )
            }
        }
    }

    private fun conStato(
        mappa: Map<String, List<SfCheckin>>,
        sfidaId: String,
        data: String,
        stato: String,
    ): Map<String, List<SfCheckin>> {
        val giorni = mappa[sfidaId].orEmpty()
        // Un giorno che non c'è non si inventa qui: le righe le crea la sfida.
        if (giorni.none { it.dayDate == data }) return mappa
        return mappa + (sfidaId to giorni.map {
            if (it.dayDate == data) it.copy(status = stato) else it
        })
    }

    // ── Sfide ────────────────────────────────────────────────────────────

    /**
     * Salva la sfida e chiude il form **solo se il database l'ha accettata**:
     * niente ottimismo qui, al contrario delle spunte — quello che si è appena
     * scritto non si rifà in un secondo.
     */
    fun salva(bozza: BozzaSfida, id: String?, poi: () -> Unit) {
        viewModelScope.launch {
            try {
                if (id == null) {
                    val sfida = TaFiriRepository.crea(bozza)
                    TaFiriRepository.scriviRegola(sfida, sfida.startDate)
                    _state.value = _state.value.copy(messaggio = "✅ Sfida creata: ${sfida.title}")
                } else {
                    TaFiriRepository.aggiorna(id, bozza)
                    aggiornaRegola(id, bozza)
                    _state.value = _state.value.copy(messaggio = "✅ Sfida aggiornata: ${bozza.titolo}")
                }
                poi()
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "salvataggio non riuscito", e)
                _state.value = _state.value.copy(
                    messaggio = "Non salvato: ${e.message ?: "connessione assente"}"
                )
            }
        }
    }

    /**
     * Dopo una modifica il promemoria va riagganciato al **prossimo giorno
     * ancora da segnare**, non alla data di partenza — che di solito è passata
     * — e con l'orario nuovo. Se non è rimasto niente da segnare la regola si
     * toglie: stessa logica del web.
     */
    private suspend fun aggiornaRegola(id: String, bozza: BozzaSfida) {
        val sfida = _state.value.sfide.firstOrNull { it.id == id } ?: return
        val giorno = prossimoDaSegnare(_state.value.checkin[id], LocalDate.now().toString())
        if (giorno != null) {
            TaFiriRepository.scriviRegola(sfida.copy(title = bozza.titolo.trim(), checkinTime = bozza.ora), giorno)
        } else {
            TaFiriRepository.cancellaRegola(id)
        }
    }

    /**
     * Eliminazione ottimistica: la scheda sparisce subito e torna se il
     * database dice di no.
     */
    fun elimina(sfida: SfSfida) {
        viewModelScope.launch {
            val prima = _state.value.sfide
            _state.value = _state.value.copy(sfide = prima.filterNot { it.id == sfida.id })
            try {
                TaFiriRepository.elimina(sfida.id)
                TaFiriRepository.cancellaRegola(sfida.id)
                _state.value = _state.value.copy(messaggio = "🗑 Sfida eliminata")
            } catch (e: Exception) {
                Log.w(TAG, "eliminazione non riuscita", e)
                _state.value = _state.value.copy(
                    sfide = prima,
                    messaggio = "Non eliminata: ${e.message ?: "connessione assente"}",
                )
            }
        }
    }

    private suspend fun ricaricaCheckin() {
        runCatching { TaFiriRepository.checkin() }
            .onSuccess { _state.value = _state.value.copy(checkin = it) }
            .onFailure { Log.w(TAG, "ricarica delle spunte non riuscita", it) }
    }

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    private companion object {
        const val TAG = "TaFiri"
    }
}
