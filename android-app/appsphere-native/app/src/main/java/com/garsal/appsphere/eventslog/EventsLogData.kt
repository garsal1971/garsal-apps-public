package com.garsal.appsphere.eventslog

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.ModalitaNascosta
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// ── Righe ────────────────────────────────────────────────────────────────
//
// ⚠️ Le tabelle el_* non compaiono in nessuna migration della repo: furono
// create a mano su Supabase prima che esistesse la cartella `migrations/`, e
// events-log.html le legge con `select('*')`. Le colonne qui sotto sono
// ricavate da come la pagina le scrive (i literal passati a insert/update),
// non indovinate. `ignoreUnknownKeys` è attivo nel serializer di supabase-kt,
// quindi una colonna in più sul database non rompe niente; una in meno sì, e
// si vedrebbe subito come errore di decodifica al primo caricamento.

@Serializable
data class ElGruppo(
    val id: String,
    val name: String = "",
    val icon: String? = null,
    val color: String? = null,
    val description: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val riservato: Boolean? = null,
)

@Serializable
data class ElEvento(
    val id: String,
    val name: String = "",
    val icon: String? = null,
    val description: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    /** `MANUALE` (si tocca per registrare) o `DA_SELECT` (conta da solo). */
    @SerialName("event_type") val eventType: String = "MANUALE",
    val points: Int = 1,
    val color: String? = null,
    @SerialName("score_query") val scoreQuery: String? = null,
)

@Serializable
data class ElLog(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("event_id") val eventId: String = "",
    val note: String? = null,
    @SerialName("logged_at") val loggedAt: String = "",
    @SerialName("points_at_log") val pointsAtLog: Int = 0,
)

object EventsLogRepository {

    private val db get() = Supabase.client().postgrest

    /** Lo stesso formato che scrive la pagina web (`localDateTimeStr`). */
    private val FORMATO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun adesso(): String = LocalDateTime.now().format(FORMATO)

    /**
     * I gruppi, coi **tre stati** di `loadGroups()` nel web:
     *
     * - modalità nascosta spenta → solo i gruppi normali;
     * - accesa → tutti;
     * - accesa col filtro 👁 → **solo** i riservati.
     *
     * ⚠️ **Il filtro sta nella query, non nel disegno.** Fuori dalla modalità
     * nascosta la riga non si legge proprio: nasconderla solo a schermo la
     * lascerebbe in chiaro a chiunque guardi il traffico o la memoria
     * dell'app, che è esattamente ciò da cui la modalità protegge. È la stessa
     * scelta di `MemoRepository.schede()`.
     *
     * ⚠️ Il ramo dei NULL **non è prudenza** come in Memo: `el_groups` non sta
     * in nessuna migration e la colonna `riservato` è arrivata dopo, quindi i
     * gruppi nati prima ce l'hanno NULL. Con la sola uguaglianza sparirebbero
     * tutti — ed è per questo che il web scrive
     * `riservato.eq.false,riservato.is.null`.
     */
    suspend fun gruppi(
        modalitaNascosta: Boolean,
        soloRiservate: Boolean,
    ): List<ElGruppo> = withContext(Dispatchers.IO) {
        db.from("el_groups")
            .select(Columns.ALL) {
                filter {
                    when {
                        soloRiservate -> eq("riservato", true)
                        !modalitaNascosta -> or {
                            eq("riservato", false)
                            exact("riservato", null)
                        }
                        else -> Unit
                    }
                }
                order("sort_order", Order.ASCENDING)
                order("name", Order.ASCENDING)
            }
            .decodeList<ElGruppo>()
    }

    /**
     * Gli eventi dei soli gruppi visibili — `filterEventsByVisibleGroups()` del
     * web, ma fatto **nella query**: il nome di un evento dice quasi sempre di
     * cosa parla il suo gruppo, e leggerlo per poi scartarlo è la mezza misura
     * che la modalità nascosta esiste per evitare.
     *
     * Senza gruppi visibili non si chiede niente: `in.()` con l'elenco vuoto
     * non è una richiesta valida per PostgREST.
     */
    suspend fun eventi(gruppiVisibili: List<String>): List<ElEvento> =
        withContext(Dispatchers.IO) {
            if (gruppiVisibili.isEmpty()) return@withContext emptyList()
            db.from("el_events")
                .select(Columns.ALL) {
                    filter { isIn("group_id", gruppiVisibili) }
                    order("sort_order", Order.ASCENDING)
                    order("name", Order.ASCENDING)
                }
                .decodeList<ElEvento>()
        }

    /**
     * Il registro dei soli eventi visibili, come `getGroupFilteredLogs()` nel
     * web — anche qui nella query, per la stessa ragione: una nota di una
     * registrazione è il contenuto, non l'etichetta.
     *
     * Conseguenza da conoscere: le righe il cui evento è stato **cancellato**
     * (quelle che l'elenco mostrava come «Evento rimosso») non si leggono più.
     * Non si vedevano comunque — il registro è sempre quello di un gruppo, e
     * quelle righe un gruppo non ce l'hanno — e sul database restano dove
     * sono.
     */
    suspend fun log(eventiVisibili: List<String>, limite: Long = 200): List<ElLog> =
        withContext(Dispatchers.IO) {
            if (eventiVisibili.isEmpty()) return@withContext emptyList()
            db.from("el_logs")
                .select(Columns.ALL) {
                    filter { isIn("event_id", eventiVisibili) }
                    order("logged_at", Order.DESCENDING)
                    limit(limite)
                }
                .decodeList<ElLog>()
        }

    /**
     * L'id lo genera il client, come fa la pagina web con `crypto.randomUUID()`:
     * così la riga appena scritta si riconosce senza rileggerla.
     */
    suspend fun registra(evento: ElEvento, nota: String?, punti: Int, quando: String): ElLog =
        withContext(Dispatchers.IO) {
            val riga = ElLog(
                id = UUID.randomUUID().toString(),
                userId = AuthRepo.userId(),
                eventId = evento.id,
                note = nota?.takeIf { it.isNotBlank() },
                loggedAt = quando,
                pointsAtLog = punti,
            )
            db.from("el_logs").insert(riga)
            riga
        }

    suspend fun cancellaLog(id: String) = withContext(Dispatchers.IO) {
        db.from("el_logs").delete { filter { eq("id", id) } }
        Unit
    }

    /** Il valore corrente di un evento DA_SELECT, dalla sua `score_query`. */
    suspend fun valoreDaSelect(sql: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            db.rpc("run_score_query", buildJsonObject { put("query", sql) }).decodeAs<Int>()
        }.getOrNull()
    }
}

data class EventsLogState(
    val gruppi: List<ElGruppo> = emptyList(),
    val eventi: List<ElEvento> = emptyList(),
    val log: List<ElLog> = emptyList(),
    val gruppoAttivo: String? = null,
    val valoriDaSelect: Map<String, Int> = emptyMap(),
    /** La modalità nascosta di AppSphere: qui si legge, non si accende. */
    val modalitaNascosta: Boolean = false,
    /** Il filtro 👁: **solo** i gruppi riservati. Vale solo a modalità accesa. */
    val soloRiservate: Boolean = false,
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
) {
    val eventiDelGruppo: List<ElEvento>
        get() = eventi.filter { it.groupId == gruppoAttivo }

    /**
     * Il registro del gruppo scelto.
     *
     * Il filtro passa dagli eventi e non dal log, perché `el_logs` non porta il
     * gruppo: tiene solo `event_id`. Conseguenza da conoscere — una riga il cui
     * evento è stato cancellato (quella che l'elenco mostra come «Evento
     * rimosso») non appartiene più a nessun gruppo, quindi qui non compare da
     * nessuna parte. Continua a esistere sul database e a contare nei punti in
     * alto, che restano il totale di tutto.
     */
    val logDelGruppo: List<ElLog>
        get() {
            val delGruppo = eventi.filter { it.groupId == gruppoAttivo }
                .mapTo(mutableSetOf()) { it.id }
            return log.filter { it.eventId in delGruppo }
        }

    val nomeGruppoAttivo: String?
        get() = gruppi.firstOrNull { it.id == gruppoAttivo }?.name

    fun evento(id: String): ElEvento? = eventi.firstOrNull { it.id == id }

    val puntiTotali: Int get() = log.sumOf { it.pointsAtLog }
}

class EventsLogViewModel : ViewModel() {

    private val _state = MutableStateFlow(EventsLogState())
    val state: StateFlow<EventsLogState> = _state.asStateFlow()

    init {
        // La modalità nascosta la accende la home col codice a colori: qui si
        // osserva, e quando cambia si rilegge tutto — il filtro sta nella
        // query, quindi non basta ridisegnare. Il `collect` su uno StateFlow
        // parte subito col valore corrente, quindi è anche il primo
        // caricamento: chiamare `carica()` qui accanto ne farebbe due.
        viewModelScope.launch {
            ModalitaNascosta.attiva.collect { attiva ->
                _state.value = _state.value.copy(
                    modalitaNascosta = attiva,
                    // Spegnendo la modalità cade anche il filtro 👁, o
                    // l'elenco resterebbe a chiedere solo i riservati che non
                    // può vedere.
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
                val gruppi = EventsLogRepository.gruppi(stato.modalitaNascosta, stato.soloRiservate)
                val eventi = EventsLogRepository.eventi(gruppi.map { it.id })
                val log = EventsLogRepository.log(eventi.map { it.id })
                _state.value = _state.value.copy(
                    gruppi = gruppi,
                    eventi = eventi,
                    log = log,
                    // Il gruppo aperto resta quello **solo se è ancora fra i
                    // visibili**: alzando il filtro 👁 quello di prima non c'è
                    // più, e senza questo controllo il registro sarebbe vuoto
                    // con le schede di un gruppo che non è selezionato.
                    gruppoAttivo = stato.gruppoAttivo?.takeIf { id -> gruppi.any { it.id == id } }
                        ?: gruppi.firstOrNull()?.id,
                    caricamento = false,
                )
                controllaDaSelect()
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
     * Gli eventi che si contano da soli.
     *
     * Si registra un log **solo se il conteggio è cresciuto** rispetto
     * all'ultimo `count:N` annotato: senza quel confronto ogni apertura
     * dell'app aggiungerebbe una riga identica alla precedente.
     */
    private fun controllaDaSelect() {
        viewModelScope.launch {
            val daSelect = _state.value.eventi.filter {
                it.eventType == "DA_SELECT" && !it.scoreQuery.isNullOrBlank()
            }
            if (daSelect.isEmpty()) return@launch

            var valori = _state.value.valoriDaSelect
            var qualcosaScritto = false

            daSelect.forEach { evento ->
                val corrente = EventsLogRepository.valoreDaSelect(evento.scoreQuery!!) ?: return@forEach
                val totale = corrente * evento.points
                valori = valori + (evento.id to corrente)

                val ultimo = _state.value.log.firstOrNull { it.eventId == evento.id }
                val ultimoConteggio = ultimo?.note
                    ?.let { Regex("count:(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

                if (ultimoConteggio == null || corrente > ultimoConteggio) {
                    runCatching {
                        EventsLogRepository.registra(
                            evento = evento,
                            nota = "count:$corrente",
                            punti = totale,
                            quando = EventsLogRepository.adesso(),
                        )
                    }.onSuccess { qualcosaScritto = true }
                        .onFailure { Log.w(TAG, "DA_SELECT non registrato: ${it.message}") }
                }
            }

            _state.value = _state.value.copy(valoriDaSelect = valori)
            if (qualcosaScritto) {
                runCatching { EventsLogRepository.log(_state.value.eventi.map { it.id }) }
                    .onSuccess { _state.value = _state.value.copy(log = it) }
            }
        }
    }

    /**
     * Alza o abbassa il filtro 👁 — **solo** a modalità nascosta accesa, come
     * `toggleHiddenMode()` nel web, che a modalità spenta esce subito.
     */
    fun cambiaFiltroRiservate() {
        if (!_state.value.modalitaNascosta) return
        _state.value = _state.value.copy(soloRiservate = !_state.value.soloRiservate)
        carica()
    }

    fun scegliGruppo(id: String) {
        _state.value = _state.value.copy(gruppoAttivo = id)
    }

    fun registra(evento: ElEvento, nota: String?) {
        viewModelScope.launch {
            try {
                val riga = EventsLogRepository.registra(
                    evento = evento,
                    nota = nota,
                    punti = evento.points,
                    quando = EventsLogRepository.adesso(),
                )
                _state.value = _state.value.copy(
                    log = listOf(riga) + _state.value.log,
                    messaggio = "${evento.icon.orEmpty()} ${evento.name} registrato (+${evento.points})".trim(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "log non registrato", e)
                _state.value = _state.value.copy(
                    messaggio = "Non registrato: ${e.message ?: "connessione assente"}"
                )
            }
        }
    }

    fun cancella(log: ElLog) {
        viewModelScope.launch {
            val prima = _state.value.log
            _state.value = _state.value.copy(log = prima.filterNot { it.id == log.id })
            try {
                EventsLogRepository.cancellaLog(log.id)
            } catch (e: Exception) {
                Log.w(TAG, "cancellazione non riuscita", e)
                _state.value = _state.value.copy(
                    log = prima,
                    messaggio = "Non cancellato: ${e.message ?: "connessione assente"}",
                )
            }
        }
    }

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    private companion object {
        const val TAG = "EventsLog"
    }
}
