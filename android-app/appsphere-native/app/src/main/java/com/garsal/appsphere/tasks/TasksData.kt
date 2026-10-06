package com.garsal.appsphere.tasks

import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ── Perché qui non ci sono @Serializable data class ──────────────────────────
//
// `ts_tasks` non sta in nessuna migration: è una delle tabelle create a mano
// prima che esistesse `supabase/migrations/`, e `tasks.html` la legge con
// `select('*')`. Le colonne si conoscono solo da come la pagina le scrive, e
// alcune sono ambigue di natura: `recurring_day_of_month` la pagina la scrive
// come lista di numeri, ma i task creati con le versioni vecchie possono avere
// lì un numero singolo.
//
// Con una data class serializzata, una sola colonna del tipo sbagliato fa
// fallire la decodifica **dell'intera lista**: non si vedrebbe un task storto,
// si vedrebbe la schermata vuota. Qui si legge un JsonObject e si converte
// campo per campo con letture tolleranti: un valore inatteso costa quel campo,
// non tutti i task.

/** Un task. I nomi sono quelli delle colonne, tradotti. */
data class TsTask(
    val id: String,
    val titolo: String,
    val descrizione: String?,
    val tipo: String,
    val categorie: List<String>,
    val prioritaId: String?,
    val stato: String,
    val dataInizio: String?,
    val scadenza: String?,
    val prossimaOccorrenza: String?,
    val puntiSuccesso: Int,
    val puntiFallimento: Int,
    val puntiSalto: Int,
    val puntiRitardo: Int,
    val riservato: Boolean,
    val inPanoramica: Boolean,
    val frequenza: String?,
    val intervallo: Int?,
    val giorniSettimana: List<Int>,
    val giorniMese: List<Int>,
    val dateAnnuali: List<String>,
    val ripetiDopoGiorni: Int?,
    val dateMultiple: List<String>,
    val ultimoCompletamento: String?,
    val prossimaScadenza: String?,
) {
    /**
     * Se il task cade in un certo giorno del calendario.
     *
     * Ricalca `getTasksForDate()` di `tasks.html`, colonna per colonna: i
     * `single` guardano `start_date`, i ricorrenti `next_occurrence_date`, i
     * `multiple` cercano il giorno dentro `multiple_dates`, e `next_due` fa da
     * ripiego per i task nati prima che le altre colonne esistessero. Gli
     * stati ammessi sono gli stessi quattro.
     *
     * Differenza voluta rispetto al web: là i `multiple` passano da
     * `JSON.parse(task.multiple_dates)` dentro un try/catch, e quando la
     * colonna è già una lista quella chiamata solleva — il catch la scarta e il
     * task **non compare mai** in calendario. Qui la lista si legge com'è, in
     * qualunque delle due forme sia salvata, quindi quei task si vedono.
     */
    fun cadeIl(giorno: LocalDate): Boolean {
        if (stato !in STATI_IN_CALENDARIO) return false
        val atteso = giorno.toString()
        return when {
            tipo == "single" && dataInizio != null -> giornoDa(dataInizio) == giorno
            (tipo == "recurring" || tipo == "simple_recurring") && prossimaOccorrenza != null ->
                giornoDa(prossimaOccorrenza) == giorno
            tipo == "multiple" && dateMultiple.isNotEmpty() -> atteso in dateMultiple
            prossimaScadenza != null -> giornoDa(prossimaScadenza) == giorno
            else -> false
        }
    }

    /**
     * L'ora del giorno in cui mostrarlo, per le fasce della vista settimana.
     * Senza orario, o a mezzanotte, va nella prima fascia: stessa regola di
     * `getTasksForTimeSlot()`.
     */
    val oraDelGiorno: Int
        get() = when (tipo) {
            "single" -> oraDa(dataInizio)
            "recurring", "simple_recurring" -> oraDa(prossimaOccorrenza)
            else -> oraDa(prossimaScadenza)
        }?.take(2)?.toIntOrNull() ?: 0

    val completato: Boolean get() = stato == "completed" || stato == "terminated"

    /** La data che conta per «quando tocca»: la stessa che guarda `isTaskDueToday`. */
    val dataDiRiferimento: String?
        get() = when (tipo) {
            "single" -> dataInizio
            "free_repeat" -> null
            else -> prossimaOccorrenza ?: dataInizio
        }

    val giornoDiRiferimento: LocalDate? get() = giornoDa(dataDiRiferimento)

    /** I `workflow` non si modificano da qui: vedi `TaskForm`. */
    val modificabile: Boolean get() = tipo != "workflow"

    /** Vivo: i quattro stati su cui il web offre modifica ed eliminazione. */
    val vivo: Boolean get() = stato in STATI_IN_CALENDARIO

    /** Come `statusMap` in `renderManagement()`: nome e colore dello stato. */
    val etichettaStato: String
        get() = when (stato) {
            "completed" -> "Completato"
            "failed" -> "Fallito"
            "skipped" -> "Saltato"
            "terminated" -> "Terminato"
            "archived" -> "Archiviato"
            "cancelled" -> "Annullato"
            else -> "Attivo"
        }

    companion object {
        /** Gli stessi quattro che `getTasksForDate()` lascia passare. */
        val STATI_IN_CALENDARIO = setOf("started", "completed", "failed", "skipped")

        val TIPI = listOf(
            "single" to "Singolo",
            "simple_recurring" to "Ricorrenza semplice",
            "recurring" to "Ricorrente",
            "multiple" to "Date multiple",
            "free_repeat" to "Libera ripetizione",
            "workflow" to "Workflow",
        )

        fun etichettaTipo(tipo: String): String =
            TIPI.firstOrNull { it.first == tipo }?.second ?: tipo

        /**
         * Il segno davanti alla data nella scheda della panoramica: sta al
         * posto di `TASK_TYPE_SVG`, che nel web è un disegno per tipo.
         *
         * Sono glifi di sistema e non icone: `material-icons-extended` è
         * fuori discussione (vedi CLAUDE.md, portava l'APK a 51 MB) e
         * disegnare sei icone a mano per distinguere sei tipi che si leggono
         * comunque dalla scheda non vale il codice che costa.
         */
        fun segnoTipo(tipo: String): String = when (tipo) {
            "single" -> "☑"
            "recurring", "simple_recurring" -> "↻"
            "multiple" -> "▤"
            "free_repeat" -> "↺"
            "workflow" -> "⇊"
            else -> "•"
        }

        fun da(o: JsonObject): TsTask = TsTask(
            id = testo(o, "id") ?: "",
            titolo = testo(o, "title") ?: "(senza titolo)",
            descrizione = testo(o, "description"),
            tipo = testo(o, "type") ?: "single",
            categorie = listaTesti(o, "categories"),
            prioritaId = testo(o, "priority_id"),
            stato = testo(o, "status") ?: "started",
            dataInizio = testo(o, "start_date"),
            scadenza = testo(o, "deadline"),
            prossimaOccorrenza = testo(o, "next_occurrence_date"),
            puntiSuccesso = numero(o, "success_points") ?: 0,
            puntiFallimento = numero(o, "failure_points") ?: 0,
            puntiSalto = numero(o, "skip_points") ?: 0,
            puntiRitardo = numero(o, "late_points") ?: 0,
            riservato = booleano(o, "riservato") ?: false,
            inPanoramica = booleano(o, "show_in_panoramica") ?: true,
            frequenza = testo(o, "recurring_frequency"),
            intervallo = numero(o, "recurring_interval"),
            giorniSettimana = listaNumeri(o, "recurring_days_of_week"),
            giorniMese = listaNumeri(o, "recurring_day_of_month"),
            dateAnnuali = listaTesti(o, "recurring_dates"),
            ripetiDopoGiorni = numero(o, "repeat_after_days"),
            dateMultiple = listaTesti(o, "multiple_dates"),
            ultimoCompletamento = testo(o, "last_completed_date"),
            prossimaScadenza = testo(o, "next_due"),
        )
    }
}

/**
 * Una riga di `ts_history`: serve al calendario per i giorni passati.
 *
 * Senza, un mese indietro sarebbe quasi vuoto: la prossima occorrenza di un
 * task ricorrente si sposta in avanti a ogni completamento, quindi le volte
 * già fatte non stanno più da nessuna parte in `ts_tasks`.
 */
data class TsStorico(
    val id: String,
    val taskId: String,
    val titolo: String,
    val completatoIl: String?,
    val azione: String?,
    val punti: Int,
) {
    companion object {
        fun da(o: JsonObject) = TsStorico(
            id = testo(o, "id") ?: "",
            taskId = testo(o, "task_id") ?: "",
            titolo = testo(o, "task_title") ?: "Task senza titolo",
            completatoIl = testo(o, "completed_at"),
            azione = testo(o, "action"),
            punti = numero(o, "points") ?: 0,
        )
    }
}

data class CmCategoria(
    val id: String,
    val nome: String,
    val icona: String?,
    val colore: String?,
    /**
     * `show_in_dashboard`: decide se la categoria compare fra i gruppi dei
     * task a libera ripetizione. Assente vale **falso**, come nel web
     * (`if (cat && cat.show_in_dashboard)`): una categoria mai configurata
     * non deve portarsi dietro i suoi task senza che nessuno l'abbia chiesto.
     */
    val inDashboard: Boolean,
) {
    val etichetta: String get() = "${icona.orEmpty()} $nome".trim()

    companion object {
        fun da(o: JsonObject) = CmCategoria(
            id = testo(o, "id") ?: "",
            nome = testo(o, "name") ?: "",
            icona = testo(o, "icon"),
            colore = testo(o, "color"),
            inDashboard = booleano(o, "show_in_dashboard") ?: false,
        )
    }
}

data class CmPriorita(val id: String, val nome: String, val colore: String?, val valore: Int) {
    companion object {
        fun da(o: JsonObject) = CmPriorita(
            id = testo(o, "id") ?: "",
            nome = testo(o, "name") ?: "",
            colore = testo(o, "color"),
            valore = numero(o, "value") ?: 0,
        )
    }
}

// ── Letture tolleranti ───────────────────────────────────────────────────────

private fun campo(o: JsonObject, chiave: String) = o[chiave]?.takeIf { it !is JsonNull }

internal fun testo(o: JsonObject, chiave: String): String? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

internal fun numero(o: JsonObject, chiave: String): Int? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

internal fun booleano(o: JsonObject, chiave: String): Boolean? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/**
 * Accetta una lista, un valore solo (che diventa lista di uno) **e una lista
 * salvata come stringa JSON**.
 *
 * L'ultimo caso non è teorico: `multiple_dates` è scritta come array dalla
 * pagina, ma `getTasksForDate()` la rilegge con `JSON.parse`, il che vuol dire
 * che da qualche parte è passata anche come testo. Non sapendo quale delle due
 * forme si troverà, si accettano tutt'e due invece di indovinare.
 */
internal fun listaTesti(o: JsonObject, chiave: String): List<String> =
    when (val v = campo(o, chiave)) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> {
            val testo = v.content.trim()
            if (testo.startsWith("[")) {
                runCatching {
                    (Json.parseToJsonElement(testo) as JsonArray)
                        .mapNotNull { (it as? JsonPrimitive)?.content }
                }.getOrElse { emptyList() }
            } else if (testo.isBlank()) emptyList() else listOf(testo)
        }
        else -> emptyList()
    }

internal fun listaNumeri(o: JsonObject, chiave: String): List<Int> =
    when (val v = campo(o, chiave)) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
        is JsonPrimitive -> listOfNotNull(v.content.toDoubleOrNull()?.toInt())
        else -> emptyList()
    }

// ── Date ─────────────────────────────────────────────────────────────────────
//
// Sul database convivono due forme: `2026-08-12T08:00:00` (quella che scrive
// `localDateTimeStr()` nella pagina) e `2026-08-12 08:00:00+00` (come Postgres
// rende un timestamptz). Nessuna delle due va data in pasto a un parser
// severo: si prendono i primi dieci caratteri per il giorno e i cinque
// dell'orario, che è quello che serve a mostrarli e a confrontarli.

private val FORMATO_SCRITTURA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

fun adessoIso(): String = LocalDateTime.now().format(FORMATO_SCRITTURA)

fun giornoDa(iso: String?): LocalDate? =
    iso?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

fun oraDa(iso: String?): String? =
    iso?.takeIf { it.length >= 16 }?.substring(11, 16)

/** `2026-08-12` → `12/08/2026`, il formato che si legge in tutte le pagine. */
fun dataItaliana(iso: String?): String {
    val g = giornoDa(iso) ?: return "—"
    return "%02d/%02d/%d".format(g.dayOfMonth, g.monthValue, g.year)
}

/**
 * `2026-08-12T22:30:00` → `12-08-2026 22:30`, esattamente come `formatDateTime()`
 * scrive la data in cima a ogni scheda della panoramica. Senza orario resta il
 * solo giorno: una mezzanotte finta è peggio di niente.
 */
fun dataOraItaliana(iso: String?): String {
    val g = giornoDa(iso) ?: return ""
    val giorno = "%02d-%02d-%d".format(g.dayOfMonth, g.monthValue, g.year)
    return oraDa(iso)?.let { "$giorno $it" } ?: giorno
}

fun isoDa(giorno: LocalDate, ora: String): String =
    "%04d-%02d-%02dT%s:00".format(giorno.year, giorno.monthValue, giorno.dayOfMonth, ora)

// ── Repository ───────────────────────────────────────────────────────────────

object TasksRepository {

    private val db get() = Supabase.client().postgrest

    /**
     * Tutti i task non riservati, **compresi archiviati e terminati**.
     *
     * Il filtro per stato non sta qui ma nelle schermate: Gestione ha una
     * tendina che li chiede espressamente («Terminati», «Archiviati»), e
     * scartarli al caricamento renderebbe quelle due voci vuote per sempre.
     * Panoramica e calendario continuano a guardare solo i quattro stati vivi.
     *
     * Il filtro è in Kotlin e non in una `filter { }` di PostgREST: le righe
     * sono poche (le RLS ne lasciano passare solo le proprie) e così la query
     * resta una `select(*)` come quella della pagina, senza dipendere da come
     * la libreria scrive «riservato è falso **oppure** è nullo».
     *
     * Sui riservati si tiene la modalità normale del web: non compaiono. La
     * modalità nascosta qui non c'è, ed è la scelta prudente per una schermata
     * che si apre senza chiedere niente.
     */
    suspend fun task(): List<TsTask> = withContext(Dispatchers.IO) {
        db.from("ts_tasks")
            .select(Columns.ALL)
            .decodeList<JsonObject>()
            .map { TsTask.da(it) }
            .filter { !it.riservato }
    }

    /**
     * Lo storico recente. Il limite è generoso ma c'è: il calendario guarda
     * un mese alla volta, e scaricare anni di righe per disegnarne trenta
     * giorni sarebbe tempo speso a ogni apertura.
     */
    suspend fun storico(limite: Long = 800): List<TsStorico> = withContext(Dispatchers.IO) {
        db.from("ts_history")
            .select(Columns.ALL) {
                order("timestamp", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(limite)
            }
            .decodeList<JsonObject>()
            .map { TsStorico.da(it) }
    }

    suspend fun categorie(): List<CmCategoria> = withContext(Dispatchers.IO) {
        db.from("cm_categories").select(Columns.ALL).decodeList<JsonObject>()
            .map { CmCategoria.da(it) }
            .sortedBy { it.nome.lowercase() }
    }

    /**
     * `ts_settings` come mappa chiave → valore, come `loadSettings()` nel web.
     * Serve per ora a una sola voce, `dashboard_upcoming_days`: quanti giorni
     * in avanti guarda la sezione **PROSSIMI**. Il valore si cambia dalle
     * impostazioni di `tasks.html` e le due app devono leggere lo stesso.
     */
    suspend fun impostazioni(): Map<String, String> = withContext(Dispatchers.IO) {
        db.from("ts_settings").select(Columns.ALL).decodeList<JsonObject>()
            .mapNotNull { riga ->
                val chiave = testo(riga, "key") ?: return@mapNotNull null
                chiave to (testo(riga, "value") ?: "")
            }
            .toMap()
    }

    suspend fun priorita(): List<CmPriorita> = withContext(Dispatchers.IO) {
        db.from("cm_priorities").select(Columns.ALL).decodeList<JsonObject>()
            .map { CmPriorita.da(it) }
            .sortedByDescending { it.valore }
    }

    // ── Ciclo di vita: sempre e solo le RPC ──────────────────────────────
    //
    // ⚠️ Regola della repo, non una preferenza: il calcolo della prossima
    // occorrenza, l'aggiornamento di `ts_tasks`, la riga in `ts_history` e le
    // notifiche vivono **solo** nelle funzioni SQL. Il client chiama e
    // ricarica. Rifare quei conti qui vorrebbe dire avere due regole diverse
    // per lo stesso task a seconda dell'app da cui lo si tocca.

    suspend fun completa(id: String): Esito = rpc("task_complete") {
        put("p_task_id", id)
        put("p_today", LocalDate.now().toString())
    }

    suspend fun salta(id: String, giorni: Int): Esito = rpc("task_skip") {
        put("p_task_id", id)
        put("p_days", giorni)
    }

    suspend fun fallisci(id: String): Esito = rpc("task_fail") {
        put("p_task_id", id)
    }

    private suspend fun rpc(nome: String, parametri: JsonObjectBuilderScope): Esito =
        withContext(Dispatchers.IO) {
            val risposta = db.rpc(nome, buildJsonObject(parametri)).decodeAs<JsonObject>()
            Esito(
                ok = booleano(risposta, "ok") ?: false,
                azione = testo(risposta, "action"),
                punti = numero(risposta, "points"),
                errore = testo(risposta, "error"),
            )
        }

    suspend fun elimina(id: String) = withContext(Dispatchers.IO) {
        db.from("ts_tasks").delete { filter { eq("id", id) } }
        Unit
    }

    /**
     * Scrive un task, nuovo (`id` nullo) o esistente. Non passa da una RPC — e
     * non è un'eccezione alla regola: le RPC governano il **ciclo di vita**
     * (dove va la prossima occorrenza), non com'è fatto il task.
     * `saveTask()` nel web fa lo stesso insert/update.
     */
    suspend fun salva(bozza: BozzaTask, id: String?) = withContext(Dispatchers.IO) {
        val corpo = bozza.aJson()
        if (id == null) db.from("ts_tasks").insert(corpo)
        else db.from("ts_tasks").update(corpo) { filter { eq("id", id) } }
        Unit
    }

    /**
     * Riporta un task terminato o archiviato fra i vivi, come
     * `reactivateTask()`: stato a `started` e una riga in `ts_history`, che è
     * quello che rende la riattivazione leggibile nello storico invece di
     * essere un task che ricompare senza spiegazione.
     */
    suspend fun riattiva(id: String, statoPrecedente: String) = withContext(Dispatchers.IO) {
        db.from("ts_tasks").update(buildJsonObject { put("status", "started") }) {
            filter { eq("id", id) }
        }
        db.from("ts_history").insert(
            buildJsonObject {
                put("task_id", id)
                put("from_status", statoPrecedente)
                put("to_status", "started")
                put("action", "reactivated")
                put("points", 0)
                put("timestamp", adessoIso())
            }
        )
        Unit
    }

    data class Esito(val ok: Boolean, val azione: String?, val punti: Int?, val errore: String?)
}

private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit

/**
 * Quello che il form compila. Separata da [TsTask] perché il form lavora su
 * campi in corso di modifica — testo ancora da convertire, date non scelte —
 * mentre `TsTask` è quello che il database ha già accettato.
 */
data class BozzaTask(
    val titolo: String = "",
    val descrizione: String = "",
    val tipo: String = "single",
    val categorie: List<String> = emptyList(),
    val prioritaId: String? = null,
    val giorno: LocalDate = LocalDate.now(),
    val ora: String = "09:00",
    val scadenza: LocalDate? = null,
    val puntiSuccesso: Int = 10,
    val puntiFallimento: Int = -5,
    val puntiSalto: Int = -2,
    val puntiRitardo: Int = 0,
    val inPanoramica: Boolean = true,
    // ricorrente
    val frequenza: String = "daily",
    val intervallo: Int = 1,
    val giorniSettimana: List<Int> = emptyList(),
    val giorniMese: List<Int> = emptyList(),
    val dateAnnuali: List<String> = emptyList(),
    // ricorrenza semplice
    val ripetiDopoGiorni: Int = 7,
    // date multiple
    val dateMultiple: List<String> = emptyList(),
) {
    val valida: Boolean
        get() = titolo.isNotBlank() && when (tipo) {
            "multiple" -> dateMultiple.isNotEmpty()
            "recurring" -> when (frequenza) {
                "weekly" -> giorniSettimana.isNotEmpty()
                "monthly" -> giorniMese.isNotEmpty()
                "yearly" -> dateAnnuali.isNotEmpty()
                else -> true
            }
            else -> true
        }

    /**
     * Il corpo da scrivere, campo per campo come lo scrive `saveTask()` nella
     * pagina web — compresa la regola che alla creazione
     * `next_occurrence_date` parte uguale a `start_date`, tranne per i task a
     * date multiple, dove è la **prima data** dell'elenco.
     */
    fun aJson(): JsonObject {
        val inizio = isoDa(giorno, ora)
        return buildJsonObject {
            put("title", titolo.trim())
            put("description", descrizione.trim().ifBlank { null })
            put("type", tipo)
            put("categories", buildJsonArray { categorie.forEach { add(it) } })
            put("priority_id", prioritaId)
            put("start_date", inizio)
            put("success_points", puntiSuccesso)
            put("failure_points", puntiFallimento)
            put("skip_points", puntiSalto)
            put("late_points", puntiRitardo)
            put("show_in_panoramica", inPanoramica)
            put("riservato", false)
            put("status", "started")

            when (tipo) {
                "single" -> {
                    put("deadline", scadenza?.let { isoDa(it, ora) })
                    put("next_occurrence_date", inizio)
                }

                "simple_recurring" -> {
                    put("repeat_after_days", ripetiDopoGiorni)
                    put("next_occurrence_date", inizio)
                }

                "recurring" -> {
                    put("recurring_frequency", frequenza)
                    put("recurring_interval", intervallo)
                    put("next_occurrence_date", inizio)
                    when (frequenza) {
                        "weekly" ->
                            put("recurring_days_of_week", buildJsonArray { giorniSettimana.sorted().forEach { add(it) } })
                        "monthly" ->
                            put("recurring_day_of_month", buildJsonArray { giorniMese.sorted().forEach { add(it) } })
                        "yearly" -> {
                            val ordinate = dateAnnuali.sorted()
                            put("recurring_dates", buildJsonArray { ordinate.forEach { add(it) } })
                            // Le colonne vecchie restano allineate alla prima
                            // data: `task_next_recurring_date` le guarda ancora
                            // per i task nati prima di `recurring_dates`.
                            // Letta per indice e non destrutturata: una voce
                            // malformata darebbe un errore invece di un campo
                            // in meno.
                            val pezzi = ordinate.firstOrNull()?.split("-").orEmpty()
                            put("recurring_day_of_year", pezzi.getOrNull(0)?.toIntOrNull())
                            put("recurring_month", pezzi.getOrNull(1)?.toIntOrNull())
                        }
                    }
                }

                "multiple" -> {
                    val ordinate = dateMultiple.sorted()
                    put("multiple_dates", buildJsonArray { ordinate.forEach { add(it) } })
                    put("next_occurrence_date", ordinate.firstOrNull()?.let { "${it}T$ora:00" })
                }

                // `free_repeat` non ha una prossima occorrenza: si ripete
                // quando si vuole, e la pagina web lo tiene fuori da «oggi».
                // `JsonNull` e non `null`: un null nudo qui non saprebbe
                // quale `put` scegliere fra stringa, numero e booleano.
                "free_repeat" -> put("next_occurrence_date", JsonNull)
            }
        }
    }

    companion object {
        /** Riempie il form da un task che esiste già: modifica e clonazione. */
        fun da(t: TsTask): BozzaTask {
            val riferimento = t.dataDiRiferimento ?: t.dataInizio
            return BozzaTask(
                titolo = t.titolo,
                descrizione = t.descrizione.orEmpty(),
                tipo = t.tipo,
                categorie = t.categorie,
                prioritaId = t.prioritaId,
                giorno = giornoDa(riferimento) ?: LocalDate.now(),
                ora = oraDa(riferimento) ?: "09:00",
                scadenza = giornoDa(t.scadenza),
                puntiSuccesso = t.puntiSuccesso,
                puntiFallimento = t.puntiFallimento,
                puntiSalto = t.puntiSalto,
                puntiRitardo = t.puntiRitardo,
                inPanoramica = t.inPanoramica,
                frequenza = t.frequenza ?: "daily",
                intervallo = t.intervallo ?: 1,
                giorniSettimana = t.giorniSettimana,
                giorniMese = t.giorniMese,
                dateAnnuali = t.dateAnnuali,
                ripetiDopoGiorni = t.ripetiDopoGiorni ?: 7,
                dateMultiple = t.dateMultiple,
            )
        }
    }
}
