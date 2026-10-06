package com.garsal.appsphere.obiettivi

import com.garsal.appsphere.core.Supabase
import com.garsal.appsphere.tasks.booleano
import com.garsal.appsphere.tasks.listaTesti
import com.garsal.appsphere.tasks.numero
import com.garsal.appsphere.tasks.testo
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Le azioni di Obiettivi ───────────────────────────────────────────────────
//
// `ob_actions` è la gemella di `ts_tasks` — stessi sei tipi, stesse colonne
// nome per nome — e si legge come **JsonObject** per la stessa ragione per cui
// si legge così `ts_tasks`: `categories` è un array di uuid, `workflow_steps`
// un jsonb, e con una `data class` serializzata una sola colonna di forma
// inattesa non darebbe un'azione storta ma la **schermata vuota**. Qui un
// valore inatteso costa quel campo e nient'altro.

/**
 * ⚠️ **Il giorno di un istante si legge in ora locale, non tagliando la
 * stringa.** È la copia di `localDay()` in `obiettivi.html`, e qui la
 * differenza morde davvero: la pagina scrive `next_occurrence_date` con
 * `toISOString()`, cioè in UTC col fuso dentro, quindi un'azione delle 00:30
 * italiane sta in archivio come le 22:30 **del giorno prima**. Prendendo i
 * primi dieci caratteri — come fa `giornoDa()` in Tasks, dove i timestamp sono
 * scritti già locali e senza fuso — finirebbe nel riquadro sbagliato, e su
 * un'azione di oggi vorrebbe dire vederla fra le arretrate.
 */
fun giornoLocale(iso: String?): LocalDate? {
    if (iso.isNullOrBlank()) return null
    // Con un fuso in coda (`Z`, `+02:00`, `+00`) è un istante e va portato qui;
    // senza, è già un orario locale — come lo scrivono le pagine più vecchie.
    istanteLocale(iso)?.let { return it.toLocalDate() }
    return runCatching { LocalDate.parse(iso.trim().take(10)) }.getOrNull()
}

/** L'ora di un istante, letta come [giornoLocale]. Vuota se non c'è. */
fun oraLocale(iso: String?): String =
    istanteLocale(iso)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""

private fun istanteLocale(iso: String?): LocalDateTime? {
    if (iso.isNullOrBlank()) return null
    val normalizzato = iso.trim().replace(' ', 'T')
    runCatching {
        OffsetDateTime.parse(conFusoCompleto(normalizzato))
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
    }.getOrNull()?.let { return it }
    return runCatching { LocalDateTime.parse(normalizzato) }.getOrNull()
}

/**
 * Postgres scrive `+00`, che `OffsetDateTime` non accetta: vuole `+00:00`.
 *
 * ⚠️ Si tocca **solo** una stringa che ha già la `T`: su un `2026-09-02` secco
 * quel `-02` finale sembrerebbe un fuso, e la data verrebbe storpiata.
 */
private fun conFusoCompleto(iso: String): String =
    if (!iso.contains('T')) iso
    else Regex("([+-]\\d{2})$").replace(iso) { "${it.groupValues[1]}:00" }

/** `2026-09-02` → `02/09/2026`, il formato di tutte le pagine. */
fun dataItalianaDa(giorno: LocalDate?): String =
    giorno?.let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: "—"

/** Un'azione: il «cosa faccio» di un obiettivo. */
data class ObAzione(
    val id: String,
    val obiettivoId: String?,
    val titolo: String,
    val descrizione: String?,
    val tipo: String,
    val stato: String,
    val categorie: List<String>,
    val prioritaId: String?,
    val dataInizio: String?,
    val prossimaOccorrenza: String?,
    val ultimaVolta: String?,
    val stepFatti: Int,
    val stepTotali: Int,
) {
    /** Com'è finita lo dice lo storico; lo stato dice solo **se** è finita. */
    val viva: Boolean get() = stato != "terminated"

    /** Una libera ripetizione non ha una prossima volta: si fa quando capita. */
    val libera: Boolean get() = tipo == "free_repeat"

    val workflow: Boolean get() = tipo == "workflow"

    /**
     * Quando cade: la prossima occorrenza, o la partenza se non ce l'ha.
     * ⚠️ Su una libera ripetizione resta `null` e **non si ripiega su
     * `start_date`**: lì è quando l'azione è nata, e scriverla la farebbe
     * sembrare una scadenza.
     */
    val giorno: LocalDate? get() = if (libera) null else giornoLocale(prossimaOccorrenza ?: dataInizio)

    val ora: String get() = if (libera) "" else oraLocale(prossimaOccorrenza ?: dataInizio)

    /**
     * Il giorno a cui appartiene l'occorrenza che si sta chiudendo — la data
     * della rilevazione che verrà chiesta dopo.
     *
     * ⚠️ Si legge **prima** di chiamare la RPC: subito dopo la riga è già stata
     * spostata sulla volta successiva, e quella che si stava chiudendo non è
     * più leggibile da nessuna parte. Una libera ripetizione un'occorrenza non
     * ce l'ha, e allora vale oggi.
     */
    fun occorrenza(oggi: LocalDate): LocalDate = giornoLocale(prossimaOccorrenza) ?: oggi

    companion object {
        fun da(o: JsonObject): ObAzione {
            val steps = o["workflow_steps"] as? JsonArray
            val stati = steps.orEmpty().mapNotNull { (it as? JsonObject)?.let { s -> testo(s, "status") } }
            return ObAzione(
                id = testo(o, "id") ?: "",
                obiettivoId = testo(o, "objective_id"),
                titolo = testo(o, "title") ?: "(senza titolo)",
                descrizione = testo(o, "description"),
                tipo = testo(o, "type") ?: "single",
                stato = testo(o, "status") ?: "active",
                categorie = listaTesti(o, "categories"),
                prioritaId = testo(o, "priority_id"),
                dataInizio = testo(o, "start_date"),
                prossimaOccorrenza = testo(o, "next_occurrence_date"),
                ultimaVolta = testo(o, "last_completed_date"),
                stepFatti = stati.count { it == "completed" || it == "failed" },
                stepTotali = stati.size,
            )
        }

        /** Le stesse etichette di `TYPE_LABEL` in `obiettivi.html`. */
        val ETICHETTA_TIPO = mapOf(
            "single" to "singola",
            "recurring" to "ricorrente",
            "simple_recurring" to "ogni N giorni",
            "multiple" to "date multiple",
            "free_repeat" to "libera ripetizione",
            "workflow" to "workflow",
        )

        /**
         * ⚠️ `PUO_SALTARE` del web, copiato: si salta solo ciò che ha una
         * prossima volta a cui rimandare. Un'azione **non si fallisce** — o la
         * si fa, o la si sposta — quindi qui non c'è nessun *Fallisci*, come
         * di là dalla v1.8.0.
         */
        val PUO_SALTARE = setOf("recurring", "simple_recurring", "multiple", "single")
    }
}

/** Quali metriche un'azione dovrebbe muovere: una riga di `ob_action_metrics`. */
data class ObCollegamentoMetrica(val azioneId: String, val metricaId: String)

/**
 * Le letture e le due scritture del 📆 Piano quotidiano.
 *
 * ⚠️ Il ciclo di vita passa **solo** dalle RPC `ob_action_complete` /
 * `ob_action_skip`, come sul web e come i task passano da `task_complete` /
 * `task_skip`: qui non si calcola nessuna prossima occorrenza. Due
 * implementazioni della stessa ricorrenza sono due date diverse il giorno che
 * una delle due cambia.
 */
object PianoRepository {

    private val db get() = Supabase.client().postgrest

    suspend fun azioni(): List<ObAzione> = withContext(Dispatchers.IO) {
        db.from("ob_actions").select(Columns.ALL)
            .decodeList<JsonObject>()
            .map { ObAzione.da(it) }
    }

    suspend fun collegamentiMetriche(): List<ObCollegamentoMetrica> = withContext(Dispatchers.IO) {
        db.from("ob_action_metrics").select(Columns.ALL)
            .decodeList<JsonObject>()
            .mapNotNull { riga ->
                val azione = testo(riga, "action_id") ?: return@mapNotNull null
                val metrica = testo(riga, "metric_id") ?: return@mapNotNull null
                ObCollegamentoMetrica(azione, metrica)
            }
    }

    suspend fun completa(id: String, oggi: LocalDate): Esito = rpc("ob_action_complete") {
        put("p_action_id", id)
        put("p_today", oggi.toString())
    }

    /** `p_days` conta solo per le singole: per gli altri tipi la RPC lo ignora. */
    suspend fun salta(id: String, giorni: Int): Esito = rpc("ob_action_skip") {
        put("p_action_id", id)
        put("p_days", giorni)
    }

    private suspend fun rpc(
        nome: String,
        parametri: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Esito =
        withContext(Dispatchers.IO) {
            val risposta = db.rpc(nome, buildJsonObject(parametri)).decodeAs<JsonObject>()
            Esito(
                ok = booleano(risposta, "ok") ?: false,
                azione = testo(risposta, "action"),
                punti = numero(risposta, "points"),
                errore = testo(risposta, "error"),
            )
        }

    data class Esito(val ok: Boolean, val azione: String?, val punti: Int?, val errore: String?) {
        /**
         * Solo un successo apre la finestra delle rilevazioni: dopo un salto o
         * un fallimento non è il momento di chiedere un numero.
         */
        val riuscita: Boolean get() = azione == "completed" || azione == "completed_late"
    }
}
