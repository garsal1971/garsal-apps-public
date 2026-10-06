package com.garsal.appsphere.tafiri

import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

// ── Righe ────────────────────────────────────────────────────────────────
//
// Le colonne sono quelle di `20260704150000_sfide_challenge_tables.sql`, non
// ricavate a occhio dalla pagina: qui una migration c'è, al contrario delle
// `el_*` di Events Log. `ignoreUnknownKeys` è attivo nel serializer di
// supabase-kt, quindi `user_id` e `created_at` — che si leggono con
// `Columns.ALL` ma qui non servono — non danno fastidio.

@Serializable
data class SfSfida(
    val id: String,
    val title: String = "",
    val objective: String? = null,
    @SerialName("start_date") val startDate: String = "",
    @SerialName("duration_days") val durationDays: Int = 1,
    @SerialName("end_date") val endDate: String = "",
    /** `time` di Postgres: torna come `20:00:00`, si mostra come `20:00`. */
    @SerialName("checkin_time") val checkinTime: String = "20:00:00",
    @SerialName("total_points") val totalPoints: Int = 0,
    @SerialName("scoring_type") val scoringType: String = "all_or_nothing",
    val status: String = "active",
    @SerialName("final_score") val finalScore: Int? = null,
) {
    val ora: String get() = checkinTime.take(5)

    val attiva: Boolean get() = status == "active"

    /** L'i-esimo giorno della sfida, contando da 0 come fa `renderDayGrid`. */
    fun giorno(indice: Int): String = LocalDate.parse(startDate).plusDays(indice.toLong()).toString()
}

/** Una riga di `cm_notification_rules`, letta per sapere chi la regola ce l'ha già. */
@Serializable
private data class RigaRegola(@SerialName("entity_id") val entityId: String? = null)

@Serializable
data class SfCheckin(
    val id: String,
    @SerialName("challenge_id") val challengeId: String = "",
    @SerialName("day_date") val dayDate: String = "",
    /** `pending` | `done` | `not_done`. */
    val status: String = "pending",
)

/** Quello che il form compila: i sette campi del modale di `ta-firi.html`. */
data class BozzaSfida(
    val titolo: String = "",
    val obiettivo: String = "",
    val inizio: LocalDate = LocalDate.now(),
    val durata: Int = 21,
    val ora: String = "20:00",
    val punti: Int = 100,
    val punteggio: String = "all_or_nothing",
) {
    val valida: Boolean get() = titolo.isNotBlank() && durata >= 1

    val fine: LocalDate get() = inizio.plusDays((durata - 1).toLong())

    companion object {
        fun da(s: SfSfida) = BozzaSfida(
            titolo = s.title,
            obiettivo = s.objective.orEmpty(),
            inizio = LocalDate.parse(s.startDate),
            durata = s.durationDays,
            ora = s.ora,
            punti = s.totalPoints,
            punteggio = s.scoringType,
        )
    }
}

object TaFiriRepository {

    private val db get() = Supabase.client().postgrest

    data class Esito(val ok: Boolean, val errore: String?)

    // ── Lettura ──────────────────────────────────────────────────────────

    suspend fun sfide(): List<SfSfida> = withContext(Dispatchers.IO) {
        db.from("sf_challenges")
            .select(Columns.ALL) { order("start_date", Order.DESCENDING) }
            .decodeList<SfSfida>()
    }

    /** Le spunte raggruppate per sfida, la forma in cui le usa la griglia. */
    suspend fun checkin(): Map<String, List<SfCheckin>> = withContext(Dispatchers.IO) {
        db.from("sf_checkins")
            .select(Columns.ALL) { order("day_date", Order.ASCENDING) }
            .decodeList<SfCheckin>()
            .groupBy { it.challengeId }
    }

    // ── Scrittura della sfida ────────────────────────────────────────────

    /**
     * Crea la sfida **e i suoi giorni**, uno per riga in `sf_checkins`: è la
     * griglia che si vede sulla scheda, e senza quelle righe la sfida
     * esisterebbe senza nessun giorno da spuntare.
     *
     * L'id lo genera il client, come fa Events Log con `crypto.randomUUID()`:
     * serve subito per le righe dei giorni e per la regola di promemoria,
     * senza doverlo rileggere dal database.
     */
    suspend fun crea(b: BozzaSfida): SfSfida = withContext(Dispatchers.IO) {
        val sfida = SfSfida(
            id = UUID.randomUUID().toString(),
            title = b.titolo.trim(),
            objective = b.obiettivo.trim(),
            startDate = b.inizio.toString(),
            durationDays = b.durata,
            endDate = b.fine.toString(),
            checkinTime = b.ora,
            totalPoints = b.punti,
            scoringType = b.punteggio,
            status = "active",
        )

        db.from("sf_challenges").insert(
            buildJsonObject {
                put("id", sfida.id)
                put("title", sfida.title)
                put("objective", sfida.objective)
                put("start_date", sfida.startDate)
                put("duration_days", sfida.durationDays)
                put("end_date", sfida.endDate)
                put("checkin_time", sfida.checkinTime)
                put("total_points", sfida.totalPoints)
                put("scoring_type", sfida.scoringType)
            }
        )

        val giorni = (0 until b.durata).map { i ->
            buildJsonObject {
                put("challenge_id", sfida.id)
                put("day_date", b.inizio.plusDays(i.toLong()).toString())
                put("status", "pending")
            }
        }
        db.from("sf_checkins").insert(giorni)

        sfida
    }

    /**
     * Modifica una sfida che esiste già.
     *
     * **Data di inizio e durata non si toccano**, come nel web: i giorni sono
     * già righe in `sf_checkins`, e cambiare il periodo vorrebbe dire
     * aggiungerne, toglierne e decidere cosa fare delle spunte già date.
     */
    suspend fun aggiorna(id: String, b: BozzaSfida) = withContext(Dispatchers.IO) {
        db.from("sf_challenges").update(
            buildJsonObject {
                put("title", b.titolo.trim())
                put("objective", b.obiettivo.trim())
                put("checkin_time", b.ora)
                put("total_points", b.punti)
                put("scoring_type", b.punteggio)
            }
        ) { filter { eq("id", id) } }
        Unit
    }

    /** I giorni se ne vanno con la sfida: `sf_checkins` ha ON DELETE CASCADE. */
    suspend fun elimina(id: String) = withContext(Dispatchers.IO) {
        db.from("sf_challenges").delete { filter { eq("id", id) } }
        Unit
    }

    // ── Check-in ─────────────────────────────────────────────────────────

    /**
     * Il check-in **di oggi**: passa dalla RPC `sf_checkin_set`, che oltre a
     * scrivere il giorno sposta la regola di promemoria al giorno dopo (o la
     * cancella se la sfida è finita).
     *
     * ⚠️ Solo per oggi. Per correggere un giorno passato c'è [cambiaGiorno],
     * che scrive e basta: la stessa divisione di `ta-firi.html`, dove
     * `cycleCheckin` non tocca il promemoria — spostarlo indietro perché si è
     * corretto un giorno di tre giorni fa farebbe suonare la sveglia nel
     * passato.
     */
    suspend fun checkinDiOggi(sfidaId: String, stato: String, oggi: LocalDate): Esito =
        rpc("sf_checkin_set") {
            put("p_challenge_id", sfidaId)
            put("p_status", stato)
            put("p_today", oggi.toString())
        }

    suspend fun cambiaGiorno(sfidaId: String, giorno: String, stato: String) =
        withContext(Dispatchers.IO) {
            db.from("sf_checkins").update(
                buildJsonObject {
                    put("status", stato)
                    put("checked_at", java.time.Instant.now().toString())
                }
            ) {
                filter {
                    eq("challenge_id", sfidaId)
                    eq("day_date", giorno)
                }
            }
            Unit
        }

    /**
     * Chiude una sfida scaduta e le assegna il punteggio.
     *
     * Il conto — punti pieni o niente, oppure proporzionale ai giorni riusciti
     * — vive nella RPC e **non si rifà qui**: è la stessa regola dei task, e
     * per la stessa ragione. Due implementazioni dello stesso punteggio sono
     * due punteggi diversi il giorno che una delle due cambia.
     */
    suspend fun finalizza(id: String): Esito = rpc("sf_finalize_challenge") {
        put("p_challenge_id", id)
    }

    private suspend fun rpc(
        nome: String,
        parametri: JsonObjectBuilder.() -> Unit,
    ): Esito = withContext(Dispatchers.IO) {
        val risposta = db.rpc(nome, buildJsonObject(parametri)).decodeAs<JsonObject>()
        Esito(ok = testo(risposta, "ok") == "true", errore = testo(risposta, "error"))
    }

    /** Il campo come testo, o null se manca o è `null` nel JSON. */
    private fun testo(o: JsonObject, chiave: String): String? =
        (o[chiave] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    // ── Smart Block ──────────────────────────────────────────────────────
    //
    // ⚠️ Lo stesso giro di `upsertSmartBlockRule` / `deleteSmartBlockRule` in
    // `ta-firi.html`, e va tenuto allineato a quello: una sfida creata da qui
    // deve far comparire il blocco sul telefono esattamente come una creata di
    // là. Senza questa parte la sfida esisterebbe e non chiederebbe mai niente.

    /**
     * `time` letto in Europe/Rome, salvato come istante UTC.
     *
     * L'app Android Smart Blocker legge `due_at` come istante UTC e basta,
     * quindi la conversione va fatta qui. Il web se la calcola a mano
     * (`dateTimeToRomeUTC`, con le ultime domeniche di marzo e ottobre scritte
     * in JavaScript); qui la fa `ZoneId`, che il cambio d'ora lo conosce già.
     */
    private fun istanteRoma(giorno: String, ora: String): String =
        LocalDate.parse(giorno)
            .atTime(LocalTime.parse(ora.take(5)))
            .atZone(ZoneId.of("Europe/Rome"))
            .toInstant()
            .toString()

    suspend fun scriviRegola(sfida: SfSfida, giorno: String) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: return@withContext
        db.from("cm_notification_rules").upsert(
            buildJsonObject {
                put("user_id", utente)
                put("app", "ta_firi")
                put("entity_id", sfida.id)
                // `task` e non `challenge`: il CHECK su cm_notification_rules
                // ammette solo valori noti, e questo è quello già valido per le
                // regole smart_block a `due_at` singolo. Stessa scelta del web.
                put("entity_type", "task")
                put("entity_title", sfida.title)
                put("reminder_presets", buildJsonObject { put("due_at", istanteRoma(giorno, sfida.ora)) })
                put("notification_spec", buildJsonObject { })
                put("channel", "smart_block")
                put("enabled", true)
            }
        ) { onConflict = "user_id,app,entity_id,channel" }
        rigeneraCoda()
    }

    suspend fun cancellaRegola(sfidaId: String) = withContext(Dispatchers.IO) {
        db.from("cm_notification_rules").delete {
            filter {
                eq("app", "ta_firi")
                eq("entity_id", sfidaId)
                eq("channel", "smart_block")
            }
        }
        rigeneraCoda()
    }

    /** Gli id delle sfide che una regola smart_block ce l'hanno già. */
    suspend fun sfideConRegola(): Set<String> = withContext(Dispatchers.IO) {
        db.from("cm_notification_rules")
            .select(Columns.raw("entity_id")) {
                filter {
                    eq("app", "ta_firi")
                    eq("channel", "smart_block")
                }
            }
            .decodeList<RigaRegola>()
            .mapNotNullTo(mutableSetOf()) { it.entityId }
    }

    /**
     * Rigenera subito `cm_notification_queue` invece di aspettare il cron, che
     * gira ogni sei ore — e una sfida che parte stasera alle 20 non può
     * aspettarle. Se la chiamata non riesce non è un errore da mostrare: la
     * regola è scritta, e il cron la raccoglie al giro dopo.
     */
    private suspend fun rigeneraCoda() {
        // La graffa vuota non è un vezzo: `invoke` ha tre versioni e senza un
        // corpo da passare quella con le intestazioni e quella con il
        // costruttore della richiesta sarebbero indistinguibili. Con la lambda
        // si sceglie la seconda, che è la POST nuda che serve qui.
        runCatching { Supabase.client().functions.invoke("fill-notification-queue") {} }
    }
}
