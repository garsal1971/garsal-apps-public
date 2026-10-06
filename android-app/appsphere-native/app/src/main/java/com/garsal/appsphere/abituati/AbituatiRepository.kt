package com.garsal.appsphere.abituati

import android.util.Log
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.time.LocalDate

/**
 * Abituati: letture dirette, **scritture solo via RPC**.
 *
 * ⚠️ È la stessa regola dei task, e qui vale ancora di più: streak, jolly,
 * chiusura degli stack e giorni mancati vivono in `hb_streak`,
 * `hb_set_completion`, `hb_reconcile` e `hb_chiudi_stack`
 * (`20260815120000_hb_regole_rpc.sql`), che `habit-tracker.html` chiama
 * esattamente come questo file. **Nessun calcolo di streak o di jolly in
 * Kotlin**: sarebbero una seconda regola per lo stesso stack, diversa a
 * seconda dell'app da cui lo tocchi, e in ballo ci sono punti e archivi.
 *
 * Scrivere l'abitudine — crearla, modificarla — è invece un `insert`/`update`
 * diretto, come `saveTask()`: le RPC governano il ciclo di vita, non com'è
 * fatta l'abitudine.
 */
object AbituatiRepository {

    private const val TAG = "AppSphereAbituati"
    private val db get() = Supabase.client().postgrest

    // ── Letture ──────────────────────────────────────────────────────────

    /**
     * Le abitudini vive. Come nel web si prendono `active` e `stopped`, e le
     * riservate restano fuori: la modalità nascosta qui non c'è, e una
     * schermata che si apre senza chiedere niente è il posto sbagliato per
     * mostrarle.
     */
    suspend fun abitudini(): List<HbAbitudine> = withContext(Dispatchers.IO) {
        db.from("hb_habits")
            .select(Columns.ALL) {
                filter { isIn("status", listOf("active", "stopped")) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<JsonObject>()
            .mapNotNull { HbAbitudine.da(it) }
            .filterNot { it.riservata }
    }

    suspend fun spunte(): List<HbSpunta> = withContext(Dispatchers.IO) {
        db.from("hb_completions")
            .select(Columns.ALL) { order("completed_at", Order.DESCENDING) }
            .decodeList<JsonObject>()
            .mapNotNull { HbSpunta.da(it) }
    }

    suspend fun archivio(): List<HbArchiviato> = withContext(Dispatchers.IO) {
        db.from("hb_archived_stacks")
            .select(Columns.ALL) { order("ended_at", Order.DESCENDING) }
            .decodeList<JsonObject>()
            .mapNotNull { HbArchiviato.da(it) }
    }

    suspend fun categorie(): List<HbCategoria> = withContext(Dispatchers.IO) {
        db.from("cm_categories")
            .select(Columns.raw("id,name,icon,color")) { order("name", Order.ASCENDING) }
            .decodeList<JsonObject>()
            .mapNotNull { HbCategoria.da(it) }
    }

    /** Le regole di promemoria delle abitudini, una per canale. Se non si leggono, niente promemoria. */
    suspend fun regole(): List<HbRegola> = withContext(Dispatchers.IO) {
        runCatching {
            db.from("cm_notification_rules")
                .select(Columns.raw("entity_id,channel,reminder_presets")) {
                    filter { eq("app", "habits"); eq("enabled", true) }
                }
                .decodeList<JsonObject>()
                .mapNotNull { HbRegola.da(it) }
        }.onFailure { Log.w(TAG, "regole promemoria", it) }.getOrDefault(emptyList())
    }

    suspend fun preset(): List<HbPreset> = withContext(Dispatchers.IO) {
        runCatching {
            db.from("cm_reminder_presets")
                .select(Columns.raw("int_id,label,offset_minutes,sort_order")) {
                    filter { eq("active", true) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<JsonObject>()
                .mapNotNull { p ->
                    val id = numero(p, "int_id") ?: return@mapNotNull null
                    HbPreset(id, testo(p, "label") ?: "$id", numero(p, "offset_minutes") ?: 0)
                }
        }.getOrDefault(emptyList())
    }

    /** Lo streak di un'abitudine, calcolato dal database e non qui. */
    suspend fun streak(abitudineId: String, oggi: LocalDate): Int = withContext(Dispatchers.IO) {
        runCatching {
            db.rpc(
                "hb_streak",
                buildJsonObject {
                    put("p_habit_id", abitudineId)
                    put("p_oggi", oggi.toString())
                }
            ).decodeAs<Int>()
        }.getOrDefault(0)
    }

    /**
     * Quanti jolly costerebbe riprendere un'abitudine interrotta da una certa
     * data: uno per ogni giorno dovuto senza spunta completata, fra `da` e
     * ieri.
     *
     * ⚠️ Il conto lo fa il database (`hb_giorni_da_recuperare`), non questo
     * file — lo stesso avviso serve a `habit-tracker.html`, e due copie della
     * formula sono due avvisi diversi il giorno che una delle due cambia. È la
     * stessa ragione per cui lo streak non si calcola qui.
     *
     * Torna `null` — non zero — se la chiamata non riesce: «non lo so» e
     * «nessun giorno da recuperare» sono due cose diverse, e la seconda al
     * posto della prima farebbe riprendere un'abitudine dicendo che non costa
     * niente.
     */
    suspend fun giorniDaRecuperare(
        abitudineId: String,
        da: LocalDate,
        oggi: LocalDate = LocalDate.now(),
    ): Int? = withContext(Dispatchers.IO) {
        runCatching {
            db.rpc(
                "hb_giorni_da_recuperare",
                buildJsonObject {
                    put("p_habit_id", abitudineId)
                    put("p_da", da.toString())
                    put("p_oggi", oggi.toString())
                }
            ).decodeAs<Int>()
        }.getOrNull()
    }

    // ── Scritture: solo RPC ──────────────────────────────────────────────

    /**
     * Segna un periodo: `completed`, `failed` o `none` (toglie la
     * spunta). `orario` serve solo alle abitudini a più slot.
     *
     * La risposta dice anche se lo stack è arrivato all'obiettivo o se i jolly
     * sono finiti: sono i due segnali che aprono una cerimonia, e la cerimonia
     * la fa il client — la RPC non archivia niente da sé.
     */
    suspend fun segna(
        abitudineId: String,
        giorno: LocalDate,
        stato: String,
        orario: String? = null,
        oggi: LocalDate = LocalDate.now(),
    ): JsonObject = withContext(Dispatchers.IO) {
        db.rpc(
            "hb_set_completion",
            buildJsonObject {
                put("p_habit_id", abitudineId)
                put("p_giorno", giorno.toString())
                put("p_stato", stato)
                if (orario != null) put("p_orario", orario)
                put("p_oggi", oggi.toString())
            }
        ).decodeAs<JsonObject>()
    }

    /**
     * Il giro di riconciliazione, da fare all'apertura come fa il web a ogni
     * disegno della dashboard: giorni mancati, jolly riallineati, stack
     * completati e scaduti. Torna cosa è successo.
     */
    suspend fun riconcilia(oggi: LocalDate = LocalDate.now()): Pair<List<HbEsito>, List<HbEsito>> =
        withContext(Dispatchers.IO) {
            val esito = db.rpc(
                "hb_reconcile",
                buildJsonObject { put("p_oggi", oggi.toString()) }
            ).decodeAs<JsonObject>()

            val completati = (esito["completati"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let { r -> HbEsito.da(r) } }
                .orEmpty()
            val gameOver = (esito["game_over"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let { r -> HbEsito.da(r) } }
                .orEmpty()
            completati to gameOver
        }

    /**
     * Le due uscite del «game over»: `nuovoInizio` nullo interrompe lo stack,
     * una data lo riapre da lì.
     */
    suspend fun chiudiStack(
        abitudineId: String,
        nuovoInizio: LocalDate?,
        oggi: LocalDate = LocalDate.now(),
    ): JsonObject = withContext(Dispatchers.IO) {
        db.rpc(
            "hb_chiudi_stack",
            buildJsonObject {
                put("p_habit_id", abitudineId)
                put("p_oggi", oggi.toString())
                if (nuovoInizio != null) put("p_nuovo_inizio", nuovoInizio.toString())
            }
        ).decodeAs<JsonObject>()
    }

    /** Riapre uno stack appena completato, dalla data scelta. */
    suspend fun nuovoCiclo(abitudineId: String, inizio: LocalDate): Unit =
        withContext(Dispatchers.IO) {
            db.rpc(
                "hb_clona",
                buildJsonObject {
                    put("p_habit_id", abitudineId)
                    put("p_inizio", inizio.toString())
                }
            )
            Unit
        }

    /**
     * Il **＋** di un'abitudine a finestre: una riga `completed` in più su
     * oggi.
     *
     * ⚠️ **L'unica scrittura su `hb_completions` che non passa da una RPC**, e
     * non è uno strappo alla regola: `hb_set_completion` cerca la riga per
     * `period_key = <giorno>` e con più spunte nello stesso giorno
     * aggiornerebbe la prima invece di aggiungerne una — la seconda volta non
     * succederebbe niente di visibile. La chiave porta quindi il progressivo
     * (`2026-09-16#2`), esattamente come `cwAggiungi()` nel web, che per la
     * stessa ragione non passa da `setDayState()`.
     *
     * Le regole restano dove sono: jolly, streak e chiusura li rifà
     * `hb_reconcile` alla lettura successiva.
     */
    suspend fun aggiungiSpunta(
        abitudineId: String,
        giorno: LocalDate,
        progressivo: Int,
    ) = withContext(Dispatchers.IO) {
        db.from("hb_completions").insert(
            buildJsonObject {
                put("habit_id", abitudineId)
                // Mezzogiorno come ogni altra riga scritta qui: la data resta
                // quella comunque la si rilegga, fuso compreso.
                put("completed_at", "${giorno}T12:00:00")
                put("status", "completed")
                put("period_key", "${giorno}#$progressivo")
            }
        )
        Unit
    }

    /** Il **−**: toglie una spunta per id. */
    suspend fun togliSpunta(spuntaId: String) = withContext(Dispatchers.IO) {
        db.from("hb_completions").delete { filter { eq("id", spuntaId) } }
        Unit
    }

    // ── L'abitudine in sé ────────────────────────────────────────────────

    /**
     * Crea o modifica un'abitudine. Il **tipo non si cambia** su una che
     * esiste già, come per i task: la frequenza decide quali colonne quella
     * riga usa — `daily_times` su una settimanale non la pulisce nessuno — e i
     * giorni già spuntati sono stati contati con la regola di prima.
     */
    suspend fun salva(id: String?, b: BozzaAbitudine): String = withContext(Dispatchers.IO) {
        val riga = buildJsonObject {
            put("name", b.nome.trim())
            put("description", b.descrizione.trim())
            b.categoriaId?.let { put("category_id", it) }
            put("goal", b.obiettivo)
            put("max_failures", b.jolly)
            put("points_reward", b.puntiPremio)
            put("points_penalty", b.puntiPenalita)
            if (id == null) {
                put("frequency", b.frequenza)
                put("started_at", b.inizio.toString())
                put("status", "active")
                put("current_failures", 0)
                put("riservato", false)
                if (b.frequenza == "weekly") {
                    put("weekdays", buildJsonArray { b.giorniSettimana.sorted().forEach { add(it) } })
                }
                if (b.frequenza == "daily_multiple") {
                    put("daily_times", buildJsonArray { b.orari.sorted().forEach { add(it) } })
                }
                if (b.frequenza == "count_window") {
                    put("times_target", b.volte)
                    put("window_days", b.giorniFinestra)
                    put("max_per_day", b.maxAlGiorno)
                }
            } else {
                // Su un'abitudine che esiste si aggiornano anche i giorni e gli
                // orari, che non cambiano il *tipo*: cambiano quando la si
                // spunta, ed è una modifica che il web permette.
                if (b.frequenza == "weekly") {
                    put("weekdays", buildJsonArray { b.giorniSettimana.sorted().forEach { add(it) } })
                }
                if (b.frequenza == "daily_multiple") {
                    put("daily_times", buildJsonArray { b.orari.sorted().forEach { add(it) } })
                }
                // Le tre grandezze di una finestra si cambiano anche dopo, come
                // i giorni e gli orari: non sono il *tipo*, sono quanto chiede.
                if (b.frequenza == "count_window") {
                    put("times_target", b.volte)
                    put("window_days", b.giorniFinestra)
                    put("max_per_day", b.maxAlGiorno)
                }
            }
        }

        if (id == null) {
            db.from("hb_habits").insert(riga) { select(Columns.raw("id")) }
                .decodeList<JsonObject>().firstOrNull()?.let { testo(it, "id") }
                ?: error("L'abitudine non ha restituito un id.")
        } else {
            db.from("hb_habits").update(riga) { filter { eq("id", id) } }
            id
        }
    }

    // ── 🔔 Promemoria ────────────────────────────────────────────────────

    /**
     * Scrive le regole di promemoria di un'abitudine: **gemella di
     * `syncHabitNotificationRule()`** in `habit-tracker.html`, campo per campo,
     * e va cambiata insieme a lei.
     *
     * ⚠️ Telegram e telefono sono due righe indipendenti, ciascuna coi suoi
     * anticipi; l'orario invece è dell'abitudine ed è lo stesso nelle due.
     * ⚠️ La riga `android` porta SEMPRE `completion_update`: sul telefono i
     * pulsanti li disegna l'app e ci sono comunque, e senza quel blocco un
     * ✅ Fatto chiamerebbe `habit_post_completion` senza scrivere la riga in
     * `hb_completions`. `telegram_complete_button` sta solo su Telegram.
     * ⚠️ La lettura della riga esistente filtra per canale: con due righe una
     * lettura «singola» risponderebbe con un errore.
     */
    suspend fun sincronizzaPromemoria(id: String, b: BozzaAbitudine) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: return@withContext
        val idsTg = b.anticipiTelegram.sorted()
        val idsAn = b.anticipiTelefono.sorted()
        val orario = b.orarioPromemoria.trim()

        // C'è qualcosa da far suonare? L'orario è dell'abitudine, non del canale.
        val haOrario = b.frequenza == "daily_multiple" || orario.isNotEmpty() ||
            idsTg.isNotEmpty() || idsAn.isNotEmpty()
        if (!haOrario || (!b.telegram && !b.telefono)) {
            db.from("cm_notification_rules").delete {
                filter { eq("app", "habits"); eq("entity_id", id); eq("user_id", utente) }
            }
            riempiCoda()
            return@withContext
        }

        // Fine = inizio + obiettivo − 1 giorni, come nel web.
        val fine = b.inizio.plusDays((maxOf(1, b.obiettivo) - 1).toLong()).toString()
        val inizio = b.inizio.toString()
        val chiavePeriodo = when (b.frequenza) {
            "daily_multiple" -> "{{fire_date_local}}-{{slot_time}}"
            "weekly" -> "{{monday_of_week}}-{{day_of_week_n}}"
            else -> "{{fire_date_local}}"
        }
        val completamento = buildJsonObject {
            put("app", "habits")
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("op", "insert")
                    put("table", "hb_completions")
                    put("fields", buildJsonObject {
                        put("habit_id", id)
                        put("completed_at", "{{fire_date_local}}T{{slot_time}}:00")
                        put("status", "completed")
                        put("period_key", chiavePeriodo)
                    })
                })
            })
        }
        fun presets(ids: List<Int>, bottone: Boolean, conCompletamento: Boolean) = buildJsonObject {
            put("reminders", buildJsonArray { ids.forEach { add(it) } })
            when (b.frequenza) {
                "daily_multiple" -> put("times", buildJsonArray { b.orari.sorted().forEach { add(it) } })
                "weekly" -> {
                    put("days", buildJsonArray { b.giorniSettimana.sorted().forEach { add(it) } })
                    put("times", buildJsonArray { if (orario.isNotEmpty()) add(orario) })
                }
                else -> put("times", buildJsonArray { if (orario.isNotEmpty()) add(orario) })
            }
            put("from-to", buildJsonArray { add(inizio); add(fine) })
            if (bottone) put("telegram_complete_button", true)
            if (conCompletamento) put("completion_update", completamento)
        }
        suspend fun scrivi(canale: String, p: JsonObject) {
            val esistente = db.from("cm_notification_rules").select(Columns.raw("id")) {
                filter { eq("user_id", utente); eq("app", "habits"); eq("entity_id", id); eq("channel", canale) }
            }.decodeList<JsonObject>().firstOrNull()?.let { testo(it, "id") }
            val regola = buildJsonObject {
                put("user_id", utente); put("app", "habits"); put("entity_id", id)
                put("entity_type", b.frequenza); put("entity_title", b.nome.trim())
                put("reminder_presets", p); put("channel", canale); put("enabled", true)
            }
            if (esistente != null) db.from("cm_notification_rules").update(regola) { filter { eq("id", esistente) } }
            else db.from("cm_notification_rules").insert(regola)
        }
        suspend fun togli(canale: String) {
            db.from("cm_notification_rules").delete {
                filter { eq("user_id", utente); eq("app", "habits"); eq("entity_id", id); eq("channel", canale) }
            }
        }

        // Il pulsante Fatto su Telegram ha senso solo con almeno un anticipo, come nel form web.
        val bottone = b.bottoneFatto && idsTg.isNotEmpty()
        if (b.telegram) scrivi("telegram", presets(idsTg, bottone, bottone)) else togli("telegram")
        if (b.telefono) scrivi("android", presets(idsAn, false, true)) else togli("android")
        riempiCoda()
    }

    /** Il cron gira ogni sei ore: un promemoria per stasera non può aspettarlo. */
    private suspend fun riempiCoda() {
        runCatching { Supabase.client().functions.invoke("fill-notification-queue") {} }
            .onFailure { Log.w(TAG, "fill-notification-queue: ${it.message}") }
    }

    // ── Categorie (`cm_categories`, condivise con Tasks) ─────────────────

    /** Come `addCategory()` / `updateCategory()` del web: nome, icona e colore. */
    suspend fun salvaCategoria(id: String?, nome: String, icona: String, colore: String) =
        withContext(Dispatchers.IO) {
            val riga = buildJsonObject {
                put("name", nome.trim()); put("icon", icona.trim().ifBlank { "📌" }); put("color", colore)
            }
            if (id == null) db.from("cm_categories").insert(riga)
            else db.from("cm_categories").update(riga) { filter { eq("id", id) } }
            Unit
        }

    suspend fun eliminaCategoria(id: String) = withContext(Dispatchers.IO) {
        db.from("cm_categories").delete { filter { eq("id", id) } }
        Unit
    }

    /**
     * INTERROMPI: l'abitudine passa a `stopped`. Non si cancella niente — la
     * riga e le sue spunte restano — ma esce dalla riconciliazione, quindi da
     * lì in poi non genera `missed`, non consuma jolly e non può né vincere né
     * fallire.
     *
     * Il promemoria se ne va con lo stack: una regola che continuasse a
     * chiedere di spuntare un'abitudine ferma sarebbe solo una sveglia da
     * spegnere. È quello che fa `stopHabit()` nel web.
     */
    suspend fun interrompi(id: String) = withContext(Dispatchers.IO) {
        db.from("hb_habits")
            .update(buildJsonObject { put("status", "stopped") }) { filter { eq("id", id) } }
        runCatching {
            // Prima le notifiche già in coda, poi la regola: una notifica
            // partita dopo l'interruzione chiederebbe di spuntare un'abitudine
            // ferma, e la regola cancellata non la fermerebbe — è già uscita.
            // Stesso ordine di `deleteHabitNotificationRule()` nel web.
            db.from("cm_notification_queue").update(
                buildJsonObject { put("status", "cancelled") }
            ) {
                filter {
                    eq("app", "habits")
                    eq("entity_id", id)
                    isIn("status", listOf("pending", "snoozed"))
                }
            }
            db.from("cm_notification_rules").delete {
                filter {
                    eq("app", "habits")
                    eq("entity_id", id)
                }
            }
        }
        Unit
    }

    /**
     * RIPRENDI: l'abitudine torna `active` **da una data scelta**, che diventa
     * il suo `started_at`.
     *
     * ⚠️ La data non è un vezzo. Tenendo quella di partenza, il primo giro di
     * `hb_reconcile` — che guarda da `started_at` a ieri — marcherebbe `missed`
     * ogni giorno passato dall'interruzione: i jolly finirebbero sul posto e il
     * game over scatterebbe prima ancora di rivedere la scheda. Quanto costi
     * una certa data lo dice `giorniDaRecuperare()`, prima di scrivere.
     *
     * `current_failures` torna a zero perché è una **cache**: la
     * riconciliazione la ricalcola dai completamenti al primo giro.
     */
    suspend fun riprendi(id: String, inizio: LocalDate) = withContext(Dispatchers.IO) {
        db.from("hb_habits").update(
            buildJsonObject {
                put("status", "active")
                put("started_at", inizio.toString())
                put("current_failures", 0)
            }
        ) { filter { eq("id", id) } }
        Unit
    }

    /**
     * Elimina un'abitudine e la sua regola di promemoria.
     *
     * Le spunte se ne vanno per cascata; l'archivio no, ed è giusto — uno
     * stack finito è memoria, non dipende dalla riga viva.
     */
    suspend fun elimina(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            db.from("cm_notification_rules").delete {
                filter {
                    eq("app", "habits")
                    eq("entity_id", id)
                }
            }
        }
        db.from("hb_habits").delete { filter { eq("id", id) } }
        Unit
    }
}
