package com.garsal.appsphere.peso

import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ── Righe ────────────────────────────────────────────────────────────────
//
// ⚠️ `ps_weight_tracking` e `ps_objectives` non stanno in nessuna migration:
// sono nate a mano prima che esistesse la cartella `migrations/`, e
// `weight-quest.html` le legge con `select=*`. Si decodificano quindi come
// `JsonObject` e non come data class serializzate — la stessa scelta fatta per
// `ts_tasks`, e per la stessa ragione: qui `id` può essere un numero o un uuid
// e `weight` un intero o un decimale, e con una data class una colonna del tipo
// inatteso non darebbe un campo storto ma farebbe fallire la decodifica
// dell'intera lista, cioè la schermata vuota.

/** Una pesata: `timestamp` è l'istante in millisecondi, ed è la chiave. */
data class Pesata(
    val giorno: String,
    val ora: String?,
    val timestamp: Long,
    val peso: Double,
    val target: Double?,
    /** Grasso corporeo in % (`body_fat_pct`): `null` = non misurato, non zero. */
    val grasso: Double? = null,
    /**
     * Solo nelle COPIE di [PesoRegole.pesiVista]: lì [peso] è la massa grassa e
     * il peso vero della bilancia sta qui. Sulle righe del database è `null`.
     */
    val totale: Double? = null,
) {
    /** Vera per le pesate scritte a mano, che sono le uniche modificabili. */
    val manuale: Boolean get() = ora == "Manuale"

    companion object {
        fun da(o: JsonObject): Pesata? {
            val giorno = testo(o, "date") ?: return null
            val peso = decimale(o, "weight") ?: return null
            return Pesata(
                giorno = giorno,
                ora = testo(o, "time"),
                timestamp = intero(o, "timestamp") ?: 0L,
                peso = peso,
                target = decimale(o, "target_weight"),
                grasso = decimale(o, "body_fat_pct"),
            )
        }
    }
}

/** Un traguardo della curva: a quella data si dovrebbe pesare quel tanto. */
data class Traguardo(val giorno: String, val peso: Double)

data class Obiettivo(
    /** Testo e non numero: la colonna può essere `bigint` o `uuid`, e a noi
     *  serve solo per rifiltrare le righe — mai per fare conti. */
    val id: String,
    val nome: String,
    val tipo: String,
    val inizio: String,
    val fine: String,
    val pesoIniziale: Double?,
    val pesoFinale: Double?,
    val bonusGiornaliero: Int,
    val malusGiornaliero: Int,
    val bonusFinale: Int,
    val malusFinale: Int,
    val stato: String,
    val punteggioFinale: Int?,
    val traguardi: List<Traguardo>,
    /**
     * Ogni quanti giorni ci si pesa (`weigh_every_days`). `null` = la regola di
     * sempre: punti ogni giorno e giorni senza pesata ricostruiti. Gemella di
     * `ogniQuantiGiorni()` in `weight-quest.html`.
     */
    val ogniGiorni: Int? = null,
    /** Il promemoria della pesata: spunta, ora di Roma (`HH:MM`) e canali. */
    val promemoriaAttivo: Boolean = false,
    val promemoriaOra: String? = null,
    val promemoriaCanali: List<String> = emptyList(),
    /** `use_fat_mass`: punti e chiusura si contano sulla massa grassa. */
    val usaGrasso: Boolean = false,
    /** `fat_pct`: la % di grasso di PARTENZA, al peso del primo traguardo. */
    val percGrasso: Double? = null,
) {
    val attivo: Boolean get() = stato != "success" && stato != "failed"

    companion object {
        fun da(o: JsonObject): Obiettivo? {
            val id = testo(o, "id") ?: return null
            return Obiettivo(
                id = id,
                nome = testo(o, "objective_name") ?: "Senza nome",
                tipo = testo(o, "objective_type") ?: "perdere",
                inizio = testo(o, "start_date").orEmpty(),
                fine = testo(o, "end_date").orEmpty(),
                pesoIniziale = decimale(o, "start_weight"),
                pesoFinale = decimale(o, "end_weight"),
                // Gli stessi ripieghi del web: `obj.daily_bonus || 10`.
                bonusGiornaliero = intero(o, "daily_bonus")?.toInt()?.takeIf { it != 0 } ?: 10,
                malusGiornaliero = intero(o, "daily_malus")?.toInt()?.takeIf { it != 0 } ?: 5,
                bonusFinale = intero(o, "final_bonus")?.toInt()?.takeIf { it != 0 } ?: 100,
                malusFinale = intero(o, "final_malus")?.toInt()?.takeIf { it != 0 } ?: 50,
                stato = testo(o, "status") ?: "active",
                punteggioFinale = intero(o, "total_score")?.toInt(),
                traguardi = traguardiDa(o["milestones"]),
                ogniGiorni = intero(o, "weigh_every_days")?.toInt()?.takeIf { it >= 1 },
                promemoriaAttivo = (campo(o, "reminder_enabled") as? JsonPrimitive)?.content == "true",
                promemoriaOra = testo(o, "reminder_time")?.take(5),
                promemoriaCanali = (campo(o, "reminder_channels") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty(),
                usaGrasso = (campo(o, "use_fat_mass") as? JsonPrimitive)?.content == "true",
                percGrasso = decimale(o, "fat_pct"),
            )
        }

        /**
         * I traguardi arrivano come **stringa JSON** (`JSON.stringify` nel
         * web), ma una riga scritta a mano potrebbe averli come array jsonb:
         * si accettano tutt'e due invece di fidarsi di come sono stati scritti
         * la prima volta.
         */
        private fun traguardiDa(valore: kotlinx.serialization.json.JsonElement?): List<Traguardo> {
            val array = when {
                valore == null || valore is JsonNull -> return emptyList()
                valore is JsonArray -> valore
                valore is JsonPrimitive && valore.isString ->
                    runCatching { Json.parseToJsonElement(valore.content) as? JsonArray }
                        .getOrNull() ?: return emptyList()
                else -> return emptyList()
            }
            return array.mapNotNull { voce ->
                val o = voce as? JsonObject ?: return@mapNotNull null
                val giorno = testo(o, "date") ?: return@mapNotNull null
                val peso = decimale(o, "weight") ?: return@mapNotNull null
                Traguardo(giorno, peso)
            }.sortedBy { it.giorno }
        }
    }
}

/**
 * Quello che compila il form di Gestione Obiettivo — i campi di
 * `weight-quest.html`, comuni ai due tipi più quelli specifici di
 * «mantenere peso».
 *
 * ⚠️ Non è un dettaglio di forma: `valida` e [PesoRepository.rigaObiettivo]
 * ricalcano `doSaveMilestones()` / `saveMaintainObjective()` — cambiando una
 * regola qui va cambiata anche là.
 */
data class BozzaObiettivo(
    val nome: String = "",
    /** `"perdere"` o `"mantenere"`. */
    val tipo: String = "perdere",
    val bonusGiornaliero: Int = 10,
    val malusGiornaliero: Int = 5,
    val bonusFinale: Int = 100,
    val malusFinale: Int = 50,
    /** Milestone progressive — solo per `"perdere"`. */
    val traguardi: List<Traguardo> = emptyList(),
    /** Campi specifici di `"mantenere"`: N settimane a peso piatto. */
    val mantInizio: LocalDate = LocalDate.now(),
    val mantSettimane: Int = 4,
    val mantPeso: Double? = null,
    /** Ogni quanti giorni ci si pesa; `null` = ogni giorno, la regola di sempre. */
    val ogniGiorni: Int? = null,
    val promemoriaAttivo: Boolean = false,
    val promemoriaOra: String = "07:30",
    /** Di partenza Telegram, come `riempiCampiPesata()` nel web. */
    val promemoriaCanali: Set<String> = setOf("telegram"),
    /** Obiettivo sulla massa grassa, con la % di partenza come si scrive nel form. */
    val usaGrasso: Boolean = false,
    val percGrasso: String = "",
) {
    /** La % di partenza letta dal form; `null` se non è un numero. */
    val percGrassoNum: Double? get() = percGrasso.trim().replace(',', '.').toDoubleOrNull()

    /** Sulla massa grassa la % deve stare fra 3 e 70, come `leggiCampiPesata()`. */
    val grassoValido: Boolean get() = !usaGrasso || (percGrassoNum ?: 0.0).let { it >= 3.0 && it <= 70.0 }

    /** Una notifica che non arriva da nessuna parte non è un promemoria. */
    val promemoriaValido: Boolean get() = !promemoriaAttivo || promemoriaCanali.isNotEmpty()

    val validaPerdere: Boolean get() = nome.isNotBlank() && traguardi.size >= 2
    val validaMantenere: Boolean get() = nome.isNotBlank() && mantSettimane >= 1 && (mantPeso ?: 0.0) > 0.0
    val valida: Boolean get() = promemoriaValido && grassoValido && (if (tipo == "mantenere") validaMantenere else validaPerdere)

    companion object {
        fun nuova() = BozzaObiettivo()

        /** Ricostruita da un obiettivo esistente, per modificarlo. */
        fun da(o: Obiettivo): BozzaObiettivo {
            val inizio = PesoRegole.giornoDa(o.inizio) ?: LocalDate.now()
            // Le settimane non stanno in colonna: si ricavano dal periodo,
            // come fa `loadObjectiveById()` nel web.
            val settimane = PesoRegole.giornoDa(o.fine)?.let { fine ->
                maxOf(1, Math.round((fine.toEpochDay() - inizio.toEpochDay()) / 7.0).toInt())
            } ?: 4
            return BozzaObiettivo(
                nome = o.nome,
                tipo = o.tipo,
                bonusGiornaliero = o.bonusGiornaliero,
                malusGiornaliero = o.malusGiornaliero,
                bonusFinale = o.bonusFinale,
                malusFinale = o.malusFinale,
                traguardi = o.traguardi,
                mantInizio = inizio,
                mantSettimane = settimane,
                mantPeso = o.pesoIniziale,
                ogniGiorni = o.ogniGiorni,
                promemoriaAttivo = o.promemoriaAttivo,
                promemoriaOra = o.promemoriaOra ?: "07:30",
                promemoriaCanali = if (o.promemoriaAttivo) o.promemoriaCanali.toSet() else setOf("telegram"),
                usaGrasso = o.usaGrasso,
                percGrasso = o.percGrasso?.let { PesoRegole.arrotonda(it, 2).toString().removeSuffix(".0").replace('.', ',') }.orEmpty(),
            )
        }
    }
}

// ── Lettura dei campi JSON ───────────────────────────────────────────────

private fun campo(o: JsonObject, chiave: String) = o[chiave]?.takeIf { it !is JsonNull }

internal fun testo(o: JsonObject, chiave: String): String? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

internal fun decimale(o: JsonObject, chiave: String): Double? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.toDoubleOrNull()

internal fun intero(o: JsonObject, chiave: String): Long? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

/**
 * I punti di un obiettivo come li dà `ps_punti`: il conto giorno per giorno,
 * il totale, e i traguardi intermedi raggiunti (soglia → primo giorno).
 * `errore` non nullo = la RPC ha risposto di no.
 */
data class PuntiServer(
    val obiettivoId: String,
    val righe: List<PesoRegole.RigaPunti>,
    val totale: Int,
    val raggiunte: Map<Int, String?>,
    val puntiTraguardi: Int,
    val errore: String? = null,
) {
    companion object {
        fun da(id: String, o: JsonObject): PuntiServer {
            if (o["ok"]?.let { (it as? JsonPrimitive)?.content } != "true") {
                return PuntiServer(id, emptyList(), 0, emptyMap(), 0, testo(o, "error") ?: "risposta non valida")
            }
            val righe = (o["rows"] as? JsonArray).orEmpty().mapNotNull { v ->
                val r = v as? JsonObject ?: return@mapNotNull null
                PesoRegole.RigaPunti(
                    giorno = testo(r, "date") ?: return@mapNotNull null,
                    ricostruita = testo(r, "interp") == "true",
                    nonPesato = testo(r, "missed") == "true",
                    peso = decimale(r, "weight"),
                    target = decimale(r, "target"),
                    punti = intero(r, "points")?.toInt() ?: 0,
                    cumulativo = intero(r, "cum")?.toInt() ?: 0,
                )
            }
            val traguardi = o["traguardi"] as? JsonObject
            val raggiunte = (traguardi?.get("rows") as? JsonArray).orEmpty().mapNotNull { v ->
                val r = v as? JsonObject ?: return@mapNotNull null
                if (testo(r, "reached") != "true") return@mapNotNull null
                val soglia = intero(r, "threshold")?.toInt() ?: return@mapNotNull null
                soglia to testo(r, "first_date")
            }.toMap()
            return PuntiServer(
                obiettivoId = id,
                righe = righe,
                totale = intero(o, "total")?.toInt() ?: 0,
                raggiunte = raggiunte,
                puntiTraguardi = traguardi?.let { intero(it, "reached_points")?.toInt() } ?: 0,
            )
        }
    }
}

/** I tre canali del promemoria della pesata, nell'ordine del form. */
val CANALI_PESATA = listOf("telegram", "android", "smart_block")

object PesoRepository {

    private val db get() = Supabase.client().postgrest

    /**
     * Le pesate, dalla più vecchia alla più recente.
     *
     * Il web pagina a mille per volta perché PostgREST non ne dà di più in una
     * richiesta; qui si chiede una finestra — l'ultimo anno e mezzo, che copre
     * qualunque obiettivo in corso — invece di scaricare tutto lo storico a
     * ogni apertura. Se un giorno servisse più indietro, si allarga di qui.
     */
    suspend fun pesate(da: LocalDate): List<Pesata> = withContext(Dispatchers.IO) {
        db.from("ps_weight_tracking")
            .select(Columns.ALL) {
                filter { gte("date", da.toString()) }
                order("date", Order.ASCENDING)
                limit(5000L)
            }
            .decodeList<JsonObject>()
            .mapNotNull { Pesata.da(it) }
    }

    suspend fun obiettivi(): List<Obiettivo> = withContext(Dispatchers.IO) {
        db.from("ps_objectives")
            .select(Columns.ALL) { order("created_at", Order.DESCENDING) }
            .decodeList<JsonObject>()
            .mapNotNull { Obiettivo.da(it) }
    }

    /**
     * Scrive una pesata, come `saveDayDetailEntry()` nel web: `time` vale
     * sempre `Manuale`, il `timestamp` è l'istante di giorno + ora in
     * millisecondi — la chiave su cui l'upsert si appoggia — e il target è
     * quello interpolato di quel giorno, congelato nella riga.
     *
     * ⚠️ Il target si scrive **al momento della pesata** e non si ricalcola
     * mai: se un domani si spostano i traguardi, i giorni già passati devono
     * restare giudicati con la curva che c'era allora.
     */
    suspend fun salvaPesata(
        giorno: LocalDate,
        ora: LocalTime,
        peso: Double,
        target: Double?,
        grasso: Double? = null,
    ): Pesata = withContext(Dispatchers.IO) {
        val istante = giorno.atTime(ora).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val riga = buildJsonObject {
            put("date", giorno.toString())
            put("time", "Manuale")
            put("timestamp", istante)
            put("weight", Math.round(peso * 100.0) / 100.0)
            put("target_weight", target)
            // Come `saveInsertWeight()`: casella vuota = NULL, cioè «non misurato».
            put("body_fat_pct", grasso?.let { Math.round(it * 100.0) / 100.0 })
        }
        db.from("ps_weight_tracking").upsert(riga) { onConflict = "timestamp" }
        Pesata(giorno.toString(), "Manuale", istante, peso, target, grasso)
    }

    suspend fun eliminaPesata(timestamp: Long) = withContext(Dispatchers.IO) {
        db.from("ps_weight_tracking").delete { filter { eq("timestamp", timestamp) } }
        Unit
    }

    // ── Gestione obiettivi ──────────────────────────────────────────────

    /**
     * La riga da scrivere per un obiettivo, ricalcata da `doSaveMilestones()`
     * / `saveMaintainObjective()`: per «mantenere» le milestone sono due
     * punti allo stesso peso (target piatto per N settimane), per «perdere»
     * sono quelle compilate a mano — la prima e l'ultima decidono
     * `start_date`/`start_weight`/`end_date`/`end_weight`.
     */
    fun rigaObiettivo(bozza: BozzaObiettivo): JsonObject {
        val traguardi = if (bozza.tipo == "mantenere") {
            val peso = bozza.mantPeso ?: 0.0
            val fine = bozza.mantInizio.plusDays(bozza.mantSettimane * 7L)
            listOf(Traguardo(bozza.mantInizio.toString(), peso), Traguardo(fine.toString(), peso))
        } else {
            bozza.traguardi.sortedBy { it.giorno }
        }
        val primo = traguardi.first()
        val ultimo = traguardi.last()

        return buildJsonObject {
            put("objective_name", bozza.nome.trim())
            put("objective_type", bozza.tipo)
            put("start_date", primo.giorno)
            put("start_weight", primo.peso)
            put("end_date", ultimo.giorno)
            put("end_weight", ultimo.peso)
            put("daily_bonus", bozza.bonusGiornaliero)
            put("daily_malus", bozza.malusGiornaliero)
            put("final_bonus", bozza.bonusFinale)
            put("final_malus", bozza.malusFinale)
            put("milestones", milestoniJson(traguardi))
            put("status", "active")
            // La massa grassa — `leggiCampiPesata()`: senza spunta la % si scrive NULL.
            put("use_fat_mass", bozza.usaGrasso)
            put("fat_pct", if (bozza.usaGrasso) bozza.percGrassoNum else null)
            // I campi di `leggiCampiPesata()`: N vuoto si scrive NULL e non 1,
            // che è la regola di sempre e non «ogni giorno per scelta».
            put("weigh_every_days", bozza.ogniGiorni)
            put("reminder_enabled", bozza.promemoriaAttivo)
            put("reminder_time", if (bozza.promemoriaAttivo) bozza.promemoriaOra else null)
            put("reminder_channels", buildJsonArray {
                if (bozza.promemoriaAttivo) CANALI_PESATA.filter { it in bozza.promemoriaCanali }.forEach { add(it) }
            })
        }
    }

    private fun milestoniJson(traguardi: List<Traguardo>): String {
        val array = buildJsonArray {
            traguardi.forEach { t ->
                add(buildJsonObject {
                    put("date", t.giorno)
                    put("weight", t.peso)
                })
            }
        }
        return array.toString()
    }

    /**
     * Crea (`id == null`) o aggiorna un obiettivo — `persistObjective()`.
     * Torna l'id, nuovo o quello passato, perché il chiamante possa
     * riselezionarlo dopo il ricaricamento.
     */
    suspend fun salvaObiettivo(id: String?, riga: JsonObject): String = withContext(Dispatchers.IO) {
        if (id == null) {
            db.from("ps_objectives").insert(riga) { select(Columns.raw("id")) }
                .decodeList<JsonObject>()
                .firstOrNull()
                ?.let { testo(it, "id") }
                ?: error("L'obiettivo non ha restituito un id.")
        } else {
            db.from("ps_objectives").update(riga) { filter { eq("id", id) } }
            id
        }
    }

    /**
     * I punti dell'obiettivo — la RPC `ps_punti`, la stessa che chiama
     * `weight-quest.html`. Fino all'APK 1.0.92 il conto era scritto qui
     * (`PesoRegole.righePunti`) e là (`buildScoreRows`): due copie, cioè due
     * punteggi possibili per lo stesso obiettivo.
     */
    suspend fun punti(id: String, oggi: LocalDate): PuntiServer = withContext(Dispatchers.IO) {
        val risposta = db.rpc(
            "ps_punti",
            buildJsonObject {
                put("p_objective_id", id)
                put("p_oggi", oggi.toString())
            },
        ).decodeAs<JsonObject>()
        PuntiServer.da(id, risposta)
    }

    /**
     * La chiusura di un obiettivo — la RPC `ps_chiudi_obiettivo`, la stessa del
     * web. `conferma = false` controlla e fa il conto senza scrivere, `true`
     * rifà tutto e scrive stato e punteggio.
     */
    suspend fun chiusura(id: String, stato: String, oggi: LocalDate, conferma: Boolean): JsonObject =
        withContext(Dispatchers.IO) {
            db.rpc(
                "ps_chiudi_obiettivo",
                buildJsonObject {
                    put("p_objective_id", id)
                    put("p_stato", stato)
                    put("p_oggi", oggi.toString())
                    put("p_conferma", conferma)
                },
            ).decodeAs<JsonObject>()
        }

    /**
     * La riapertura di un obiettivo chiuso — la RPC `ps_riapri_obiettivo`, la
     * stessa di `reopenObjective()` nel web: stato di nuovo attivo, punteggio a
     * zero, e rifiutata se c'è già un altro obiettivo attivo.
     */
    suspend fun riapertura(id: String): JsonObject = withContext(Dispatchers.IO) {
        db.rpc(
            "ps_riapri_obiettivo",
            buildJsonObject { put("p_objective_id", id) },
        ).decodeAs<JsonObject>()
    }

    suspend fun eliminaObiettivo(id: String) = withContext(Dispatchers.IO) {
        db.from("ps_objectives").delete { filter { eq("id", id) } }
        Unit
    }

    // ── Il promemoria della pesata ──────────────────────────────────────
    //
    // ⚠️ Gemello di `syncPromemoriaPesata()` in `weight-quest.html`, e va tenuto
    // allineato a quello: una regola `cm_notification_rules` per canale con
    // `app = 'weight'`, e `reminder_presets` nella forma che il ramo «pesata»
    // di `fill-notification-queue` sa leggere. Un obiettivo salvato da qui deve
    // suonare esattamente come uno salvato dal PC.

    /**
     * L'id dell'obiettivo è un intero e `entity_id` è uuid in produzione: se ne
     * ricava uno FISSO, così la stessa riga si ritrova senza archiviare niente.
     * Stessa formula di `pesataEntityId()`: cambiandone una, cambia l'altra.
     */
    fun entitaPesata(id: String): String {
        if (Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(id)) return id
        val hex = (id.toLongOrNull() ?: 0L).toString(16).padStart(12, '0')
        return "7e160000-0000-4000-8000-$hex"
    }

    /**
     * Scrive (o toglie) le regole della pesata di un obiettivo. [attivo] falso
     * — obiettivo chiuso o eliminato — le toglie tutte. Le righe già in coda di
     * un canale spento si cancellano, o suonerebbero lo stesso.
     */
    suspend fun sincronizzaPromemoria(
        id: String,
        nome: String,
        inizio: String,
        fine: String,
        ogniGiorni: Int?,
        attivo: Boolean,
        ora: String?,
        canali: Collection<String>,
    ) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: return@withContext
        val entita = entitaPesata(id)
        val scelti = if (attivo && ora != null) canali.filter { it in CANALI_PESATA } else emptyList()
        val presets = buildJsonObject {
            put("kind", "pesata")
            put("every_days", ogniGiorni ?: 1)
            put("start_date", inizio)
            put("end_date", fine)
            put("time", ora.orEmpty().take(5))
        }
        CANALI_PESATA.forEach { canale ->
            if (canale in scelti) {
                db.from("cm_notification_rules").upsert(
                    buildJsonObject {
                        put("user_id", utente)
                        put("app", "weight")
                        put("entity_id", entita)
                        // 'task' e non altro: chk_entity_type ammette un elenco
                        // fisso, e 'task' lo usano già Ta Firi e il web.
                        put("entity_type", "task")
                        put("entity_title", "⚖️ Pesata — $nome")
                        put("reminder_presets", presets)
                        put("notification_spec", buildJsonObject { })
                        put("channel", canale)
                        put("enabled", true)
                    }
                ) { onConflict = "user_id,app,entity_id,channel" }
            } else {
                db.from("cm_notification_rules").delete {
                    filter { eq("app", "weight"); eq("entity_id", entita); eq("channel", canale) }
                }
            }
        }
        val spenti = CANALI_PESATA.filterNot { it in scelti }
        if (spenti.isNotEmpty()) {
            db.from("cm_notification_queue").delete {
                filter {
                    eq("app", "weight"); eq("entity_id", entita); eq("status", "pending")
                    isIn("channel", spenti)
                }
            }
        }
        // Il cron gira ogni sei ore: una pesata di domattina non può aspettarle.
        // Se non riesce non è un errore da mostrare — la regola è scritta.
        runCatching { Supabase.client().functions.invoke("fill-notification-queue") {} }
        Unit
    }

    // ── Sincronizzazione con la bilancia (Health Connect) ────────────────

    /**
     * La riga da scrivere per una pesata letta da Health Connect —
     * `processWeights()` nel web: data e ora locali dell'istante, **peso
     * troncato a un decimale** (non arrotondato — `Math.floor`, come là) e il
     * target interpolato sull'obiettivo che si sta guardando in quel momento.
     */
    fun rigaPuntoSalute(punto: PuntoSalute, obiettivo: Obiettivo?): JsonObject {
        val locale = Instant.ofEpochMilli(punto.timestamp).atZone(ZoneId.systemDefault())
        val giorno = punto.giorno()
        val ora = "%02d:%02d".format(locale.hour, locale.minute)
        val peso = Math.floor(punto.pesoKg * 10.0) / 10.0
        return buildJsonObject {
            put("date", giorno)
            put("time", ora)
            put("timestamp", punto.timestamp)
            put("weight", peso)
            // Il target congelato è sempre il TOTALE, anche sulla massa grassa.
            put("target_weight", obiettivo?.let { PesoRegole.targetInterpolato(it.traguardi, giorno) })
            // Solo se c'è: vedi [sincronizzaPunti], che per questo fa due upsert.
            punto.grasso?.takeIf { it > 0.0 && it < 100.0 }?.let { put("body_fat_pct", Math.round(it * 100.0) / 100.0) }
        }
    }

    /**
     * Scrive le pesate sincronizzate e toglie le pesate manuali che
     * diventano ridondanti — `processWeights()`: prima elimina, poi
     * scrive, così una pesata manuale del giorno non resta a fare da
     * doppione quando arriva quella vera dalla bilancia.
     */
    suspend fun sincronizzaPunti(righe: List<JsonObject>, manualiDaRimuovere: List<Long>) =
        withContext(Dispatchers.IO) {
            if (manualiDaRimuovere.isNotEmpty()) {
                db.from("ps_weight_tracking").delete { filter { isIn("timestamp", manualiDaRimuovere) } }
            }
            // ⚠️ Due upsert, con e senza grasso, come `processWeights()`: PostgREST
            // vuole le stesse chiavi su tutte le righe, e mandare `body_fat_pct:
            // null` a una pesata letta senza grasso (permesso negato) cancellerebbe
            // il grasso salvato da una sincronizzazione precedente.
            val (conGrasso, senza) = righe.partition { it.containsKey("body_fat_pct") }
            if (conGrasso.isNotEmpty()) {
                db.from("ps_weight_tracking").upsert(conGrasso) { onConflict = "timestamp" }
            }
            if (senza.isNotEmpty()) {
                db.from("ps_weight_tracking").upsert(senza) { onConflict = "timestamp" }
            }
            Unit
        }
}
