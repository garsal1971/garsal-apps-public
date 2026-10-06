package com.garsal.appsphere.obiettivi

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.tasks.CmCategoria
import com.garsal.appsphere.tasks.CmPriorita
import com.garsal.appsphere.tasks.TasksRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Un riquadro del piano: un giorno, le arretrate, o «quando capita». */
data class SezionePiano(
    val titolo: String,
    val nota: String? = null,
    val righe: List<ObAzione> = emptyList(),
    /** Testo da mostrare al posto delle righe quando non ce n'è nessuna. */
    val seVuota: String? = null,
)

/** Le metriche da chiedere dopo aver chiuso un'azione con successo. */
data class RilevazioniDaChiedere(
    val azioneId: String,
    val titoloAzione: String,
    val giorno: LocalDate,
    val metriche: List<ObMetrica>,
    val ultime: Map<String, ObRilevazione>,
)

data class PianoState(
    val azioni: List<ObAzione> = emptyList(),
    val obiettivi: List<ObObiettivo> = emptyList(),
    val metriche: List<ObMetrica> = emptyList(),
    val collegamenti: List<ObCollegamentoMetrica> = emptyList(),
    val ultimeRilevazioni: Map<String, ObRilevazione> = emptyMap(),
    val categorie: List<CmCategoria> = emptyList(),
    val priorita: List<CmPriorita> = emptyList(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
    val daRilevare: RilevazioniDaChiedere? = null,
) {
    fun obiettivoDi(id: String?): ObObiettivo? = obiettivi.firstOrNull { it.id == id }
    fun categoriaDi(id: String): CmCategoria? = categorie.firstOrNull { it.id == id }
    fun prioritaDi(id: String?): CmPriorita? = priorita.firstOrNull { it.id == id }

    fun metricheDiAzione(azioneId: String): List<ObMetrica> {
        val ids = collegamenti.filter { it.azioneId == azioneId }.map { it.metricaId }.toSet()
        return metriche.filter { it.id in ids }
    }

    /**
     * Il piano, riquadro per riquadro — la copia di `renderPianoPage()`.
     *
     * ⚠️ Le azioni **concluse non ci sono**: un piano dice cosa resta da fare, e
     * una riga che non si può più toccare è memoria, non piano. Le arretrate
     * stanno tutte in un riquadro solo, in cima, perché sono «da recuperare» e
     * non un'agenda di giorni passati da sfogliare.
     */
    fun sezioni(oggi: LocalDate): List<SezionePiano> {
        val vive = azioni.filter { it.viva }
        if (vive.isEmpty()) return emptyList()

        val libere = vive.filter { it.libera }
        val perGiorno = vive.filterNot { it.libera }
            .groupBy { it.giorno ?: oggi }
            .toSortedMap()

        val arretrate = perGiorno.filterKeys { it < oggi }.values.flatten()
            .sortedBy { it.giorno ?: oggi }
        val diOggi = perGiorno[oggi].orEmpty()
        val futuri = perGiorno.filterKeys { it > oggi }

        val sezioni = mutableListOf<SezionePiano>()
        if (arretrate.isNotEmpty()) {
            sezioni += SezionePiano(
                titolo = "⚠️ Rimaste indietro",
                nota = "la più vecchia è di ${giorniFa(arretrate.first().giorno, oggi)}",
                righe = arretrate,
            )
        }
        sezioni += SezionePiano(
            titolo = "🎯 Oggi · ${dataItalianaDa(oggi)}",
            righe = diOggi,
            seVuota = "Niente in programma per oggi.",
        )
        futuri.forEach { (giorno, righe) ->
            sezioni += SezionePiano(titolo = etichettaGiorno(giorno, oggi), righe = righe)
        }
        if (libere.isNotEmpty()) {
            sezioni += SezionePiano(
                titolo = "🔄 Quando capita",
                nota = "senza data: si fanno quando si può",
                righe = libere,
            )
        }
        return sezioni
    }
}

/**
 * «domani» e «dopodomani» si leggono meglio di una data, ma **solo per due
 * giorni**: oltre, «fra 9 giorni» costringe a fare il conto e la data no.
 */
private fun etichettaGiorno(giorno: LocalDate, oggi: LocalDate): String =
    when (ChronoUnit.DAYS.between(oggi, giorno)) {
        1L -> "📅 Domani · ${dataItalianaDa(giorno)}"
        2L -> "📅 Dopodomani · ${dataItalianaDa(giorno)}"
        else -> "📅 ${dataItalianaDa(giorno)}"
    }

private fun giorniFa(giorno: LocalDate?, oggi: LocalDate): String {
    val g = giorno ?: return "data ignota"
    val quanti = ChronoUnit.DAYS.between(g, oggi)
    return if (quanti == 1L) "ieri" else "$quanti giorni fa"
}

/**
 * Il 📆 **Piano quotidiano**: tutte le azioni ancora da fare, giorno per
 * giorno, e i due comandi con cui si chiudono.
 *
 * ⚠️ Completa e Salta passano **solo** dalle RPC (`ob_action_complete` /
 * `ob_action_skip`) e poi si rilegge, come `runActionRpc()` nella pagina.
 */
class PianoViewModel : ViewModel() {

    private val _state = MutableStateFlow(PianoState())
    val state: StateFlow<PianoState> = _state.asStateFlow()

    init { carica() }

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                _state.value = _state.value.copy(
                    azioni = PianoRepository.azioni(),
                    obiettivi = ObiettiviRepository.obiettivi(),
                    metriche = ObiettiviRepository.metriche(),
                    collegamenti = PianoRepository.collegamentiMetriche(),
                    ultimeRilevazioni = ObiettiviRepository.ultimeRilevazioni(),
                    // Categorie e priorità sono **condivise con Tasks**: si
                    // leggono dal loro repository invece di riscriverne un
                    // secondo decoder per le stesse due tabelle.
                    categorie = runCatching { TasksRepository.categorie() }.getOrDefault(emptyList()),
                    priorita = runCatching { TasksRepository.priorita() }.getOrDefault(emptyList()),
                    caricamento = false,
                )
            } catch (e: Exception) {
                Log.w(TAG, "caricamento del piano fallito", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    /**
     * ⚠️ L'occorrenza si legge **prima** della RPC: subito dopo la riga è già
     * sulla volta successiva, e la rilevazione finirebbe datata alla prossima
     * invece che a quella appena chiusa.
     */
    fun completa(azione: ObAzione) {
        val oggi = LocalDate.now()
        val occorrenza = azione.occorrenza(oggi)
        viewModelScope.launch {
            val esito = chiama("✅ Completata") { PianoRepository.completa(azione.id, oggi) }
            // ⚠️ La finestra si apre **dopo** che la RPC ha chiuso l'azione: il
            // ciclo di vita non deve dipendere dal fatto che uno si ricordi il
            // numero. Chiudendola senza registrare niente, l'azione resta
            // completata lo stesso.
            if (esito?.riuscita == true) chiediRilevazioni(azione, occorrenza)
        }
    }

    fun salta(azione: ObAzione, giorni: Int) {
        viewModelScope.launch {
            chiama("⏭ Saltata") { PianoRepository.salta(azione.id, giorni) }
        }
    }

    private suspend fun chiama(
        verbo: String,
        azione: suspend () -> PianoRepository.Esito,
    ): PianoRepository.Esito? = try {
        val esito = azione()
        if (!esito.ok) {
            _state.value = _state.value.copy(messaggio = "❌ " + (esito.errore ?: "errore sconosciuto"))
            null
        } else {
            val punti = esito.punti?.let { " · ${if (it > 0) "+" else ""}$it pt" }.orEmpty()
            _state.value = _state.value.copy(messaggio = verbo + punti)
            ricaricaAzioni()
            esito
        }
    } catch (e: Exception) {
        Log.w(TAG, "RPC non riuscita", e)
        _state.value = _state.value.copy(messaggio = "❌ " + (e.message ?: "errore"))
        null
    }

    /** Dopo una RPC basta rileggere le azioni: il resto non si è mosso. */
    private suspend fun ricaricaAzioni() {
        runCatching { PianoRepository.azioni() }
            .onSuccess { _state.value = _state.value.copy(azioni = it) }
    }

    private fun chiediRilevazioni(azione: ObAzione, occorrenza: LocalDate) {
        val metriche = _state.value.metricheDiAzione(azione.id)
        if (metriche.isEmpty()) return          // niente metriche, niente da chiedere
        _state.value = _state.value.copy(
            daRilevare = RilevazioniDaChiedere(
                azioneId = azione.id,
                titoloAzione = azione.titolo,
                giorno = occorrenza,
                metriche = metriche,
                ultime = _state.value.ultimeRilevazioni,
            )
        )
    }

    fun rilevazioniChiuse() {
        _state.value = _state.value.copy(daRilevare = null)
    }

    /**
     * Registra le misure scelte, **una chiamata per metrica**: `ob_record_measurement`
     * è l'unico punto di scrittura di una rilevazione ed è lei a rifiutare un
     * voto fuori scala.
     *
     * ⚠️ Le metriche lasciate su «non adesso» non arrivano qui dentro e non si
     * registrano — e non si registrano come zero: «non l'ho misurata» e «vale
     * zero» sono due cose diverse. Un errore su una non butta via le altre: la
     * finestra resta aperta e dice quali non sono passate.
     */
    fun salvaRilevazioni(valori: Map<String, Double>, nota: String) {
        val richiesta = _state.value.daRilevare ?: return
        if (valori.isEmpty()) { rilevazioniChiuse(); return }
        viewModelScope.launch {
            val errori = mutableListOf<String>()
            var fatte = 0
            valori.forEach { (metricaId, valore) ->
                val nome = richiesta.metriche.firstOrNull { it.id == metricaId }?.name ?: metricaId
                try {
                    val esito = ObiettiviRepository.registraRilevazione(
                        metricaId = metricaId,
                        valore = valore,
                        giorno = richiesta.giorno,
                        nota = nota,
                    )
                    if (esito.ok) fatte++ else errori += "$nome: ${esito.error ?: "errore"}"
                } catch (e: Exception) {
                    errori += "$nome: ${e.message ?: "errore"}"
                }
            }
            _state.value = if (errori.isEmpty()) {
                _state.value.copy(
                    daRilevare = null,
                    messaggio = "📈 $fatte " + if (fatte == 1) "rilevazione registrata" else "rilevazioni registrate",
                    ultimeRilevazioni = runCatching { ObiettiviRepository.ultimeRilevazioni() }
                        .getOrDefault(_state.value.ultimeRilevazioni),
                )
            } else {
                _state.value.copy(messaggio = "❌ " + errori.joinToString(" · "))
            }
        }
    }

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    private companion object {
        const val TAG = "Piano"
    }
}
