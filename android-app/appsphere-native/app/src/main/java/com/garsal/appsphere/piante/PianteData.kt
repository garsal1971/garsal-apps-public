package com.garsal.appsphere.piante

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.garsal.appsphere.BuildConfig
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Jwt
import com.garsal.appsphere.core.Supabase
import com.garsal.appsphere.obiettivi.giornoLocale
import com.garsal.appsphere.obiettivi.oraLocale
import com.garsal.appsphere.tasks.booleano
import com.garsal.appsphere.tasks.listaNumeri
import com.garsal.appsphere.tasks.listaTesti
import com.garsal.appsphere.tasks.numero
import com.garsal.appsphere.tasks.testo
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.hours

// ════════════════════════════════════════════════════════════════════════════
// 🌱 Piante — il gemello nativo di `piante.html`, sulle stesse tabelle `pv_*`.
//
// ⚠️ Le regole che vanno tenute uguali fra le due implementazioni:
//   • il ciclo di vita di un'azione passa SOLO da `pv_action_complete` /
//     `pv_action_skip`, che spostano anche i promemoria: qui nessuna data si
//     calcola in Kotlin;
//   • i promemoria sono UNA riga di `cm_notification_rules` per canale, con
//     `app = 'plants'`, `entity_type = 'task'` e la forma dei task
//     (`reminders` + `due_at`), `telegram_complete_button` su telegram E android
//     — gemella di `sincronizzaRegole()`;
//   • le foto: percorso `<user>/<pianta>/<uuid>.jpg`, ridotte a 1600 px JPEG
//     0,82; si carica il file PRIMA della riga e si cancella il file PRIMA della
//     riga, come in Memo;
//   • una voce di diario vale se ha il commento OPPURE una foto.
//
// Le righe si leggono come JsonObject, come `ts_tasks` e `ob_actions`: una
// colonna di forma inattesa costa quel campo, non la schermata vuota.
// ════════════════════════════════════════════════════════════════════════════

data class PvPianta(
    val id: String,
    val nome: String,
    val specie: String?,
    val posizione: String?,
    val arrivataIl: String?,
    val luce: String?,
    val annaffiatura: String?,
    val terriccio: String?,
    val concime: String?,
    val note: String?,
    val archiviata: Boolean,
    /** 🌿 Discende da: la pianta madre (talea, pollone, seme). NULL = nessuna. */
    val madreId: String? = null,
) {
    companion object {
        fun da(o: JsonObject) = PvPianta(
            id = testo(o, "id") ?: "",
            nome = testo(o, "name") ?: "(senza nome)",
            specie = testo(o, "species"),
            posizione = testo(o, "location"),
            arrivataIl = testo(o, "acquired_on"),
            luce = testo(o, "light"),
            annaffiatura = testo(o, "watering"),
            terriccio = testo(o, "soil"),
            concime = testo(o, "fertilizer"),
            note = testo(o, "notes"),
            archiviata = booleano(o, "archived") ?: false,
            madreId = testo(o, "parent_id"),
        )
    }
}

data class PvVoce(
    val id: String,
    val piantaId: String,
    val giorno: String,
    val commento: String?,
    val creata: String,
) {
    companion object {
        fun da(o: JsonObject) = PvVoce(
            id = testo(o, "id") ?: "",
            piantaId = testo(o, "plant_id") ?: "",
            giorno = testo(o, "entry_date") ?: LocalDate.now().toString(),
            commento = testo(o, "comment"),
            creata = testo(o, "created_at") ?: "",
        )
    }
}

data class PvFoto(
    val id: String,
    val piantaId: String,
    val voceId: String?,
    val percorso: String,
    val posizione: Int,
    val creata: String,
    val url: String = "",
) {
    companion object {
        fun da(o: JsonObject) = PvFoto(
            id = testo(o, "id") ?: "",
            piantaId = testo(o, "plant_id") ?: "",
            voceId = testo(o, "diary_id"),
            percorso = testo(o, "storage_path") ?: "",
            posizione = numero(o, "position") ?: 0,
            creata = testo(o, "created_at") ?: "",
        )
    }
}

data class PvAzione(
    val id: String,
    /** "" su un'azione di gruppo: lì vale `gruppoId`. */
    val piantaId: String,
    val titolo: String,
    val descrizione: String?,
    val tipo: String,
    val stato: String,
    val inizio: String?,
    val prossima: String?,
    val scadenza: String?,
    val ultimaVolta: String?,
    val puntiOk: Int,
    val puntiSalto: Int,
    val puntiRitardo: Int,
    val frequenza: String?,
    val intervallo: Int,
    val giorniSettimana: List<Int>,
    val giorniMese: List<Int>,
    val dateAnno: List<String>,
    val ogniGiorni: Int?,
    val dateMultiple: List<String>,
    /**
     * 👥 L'azione è di UN gruppo e non di una pianta (`pv_actions.group_id`):
     * una riga sola che vale per tutte le piante del gruppo — un promemoria e un
     * ✅ Fatto per tutte, anche per quelle che ci entrano dopo.
     */
    val gruppoId: String? = null,
) {
    val viva: Boolean get() = stato != "terminated"
    val libera: Boolean get() = tipo == "free_repeat"

    /** Una libera ripetizione non ha data: non si ripiega su `start_date`. */
    val giorno: LocalDate? get() = if (libera) null else giornoLocale(prossima ?: inizio)
    val ora: String get() = if (libera) "" else oraLocale(prossima ?: inizio)

    companion object {
        fun da(o: JsonObject) = PvAzione(
            id = testo(o, "id") ?: "",
            piantaId = testo(o, "plant_id") ?: "",
            titolo = testo(o, "title") ?: "(senza titolo)",
            descrizione = testo(o, "description"),
            tipo = testo(o, "type") ?: "single",
            stato = testo(o, "status") ?: "started",
            inizio = testo(o, "start_date"),
            prossima = testo(o, "next_occurrence_date"),
            scadenza = testo(o, "deadline"),
            ultimaVolta = testo(o, "last_completed_date"),
            puntiOk = numero(o, "success_points") ?: 10,
            puntiSalto = numero(o, "skip_points") ?: -2,
            puntiRitardo = numero(o, "late_points") ?: -2,
            frequenza = testo(o, "recurring_frequency"),
            intervallo = numero(o, "recurring_interval") ?: 1,
            giorniSettimana = listaNumeri(o, "recurring_days_of_week"),
            giorniMese = listaNumeri(o, "recurring_day_of_month"),
            dateAnno = listaTesti(o, "recurring_dates"),
            ogniGiorni = numero(o, "repeat_after_days"),
            dateMultiple = listaTesti(o, "multiple_dates"),
            gruppoId = testo(o, "group_id"),
        )

        /** Stesse etichette di `TIPI` in `piante.html`. */
        val TIPI = listOf(
            "single" to "Una volta",
            "simple_recurring" to "Ogni N giorni",
            "recurring" to "Ricorrente (settimana, mese, anno)",
            "multiple" to "In date precise",
            "free_repeat" to "Quando capita (senza data)",
        )

        /** `PUO_SALTARE` del web: si salta solo ciò che ha una prossima volta. */
        val PUO_SALTARE = setOf("single", "recurring", "simple_recurring", "multiple")
    }
}

data class PvStoria(
    val piantaId: String?,
    /** Le righe di un'azione di gruppo hanno `plant_id` NULL: si ritrovano da qui. */
    val azioneId: String?,
    val titolo: String,
    val azione: String,
    val punti: Int,
    val quando: String,
) {
    companion object {
        fun da(o: JsonObject) = PvStoria(
            piantaId = testo(o, "plant_id"),
            azioneId = testo(o, "action_id"),
            titolo = testo(o, "action_title") ?: "",
            azione = testo(o, "action") ?: "",
            punti = numero(o, "points") ?: 0,
            quando = testo(o, "timestamp") ?: "",
        )
    }
}

data class PvDesiderio(
    val id: String,
    val nome: String,
    val specie: String?,
    val note: String?,
    val priorita: Int,
    val stato: String,
    val piantaId: String?,
) {
    companion object {
        fun da(o: JsonObject) = PvDesiderio(
            id = testo(o, "id") ?: "",
            nome = testo(o, "name") ?: "",
            specie = testo(o, "species"),
            note = testo(o, "notes"),
            priorita = numero(o, "priority") ?: 2,
            stato = testo(o, "status") ?: "desiderata",
            piantaId = testo(o, "plant_id"),
        )
    }
}

data class PvRisposta(
    val id: String,
    val piantaId: String?,
    val domanda: String,
    val risposta: String,
    val creata: String,
    /** 'gemini' | 'qwen' | 'claude'; NULL sulle risposte nate prima della colonna, che erano di Claude. */
    val fornitore: String? = null,
) {
    companion object {
        fun da(o: JsonObject) = PvRisposta(
            id = testo(o, "id") ?: "",
            piantaId = testo(o, "plant_id"),
            domanda = testo(o, "question") ?: "",
            risposta = testo(o, "answer") ?: "",
            creata = testo(o, "created_at") ?: "",
            fornitore = testo(o, "provider"),
        )
    }
}

/** Una regola di promemoria: canale e anticipi. */
/** 👥 Un gruppo di piante (`pv_groups`). */
data class PvGruppo(val id: String, val nome: String, val emoji: String?) {
    /** Come `nomeGruppo()` in `piante.html`. */
    val etichetta: String get() = (emoji?.let { "$it " } ?: "👥 ") + nome

    companion object {
        fun da(o: JsonObject) = PvGruppo(
            id = testo(o, "id") ?: "",
            nome = testo(o, "name") ?: "(senza nome)",
            emoji = testo(o, "emoji"),
        )
    }
}

/** Una pianta in un gruppo (`pv_plant_groups`): una pianta può stare in più gruppi. */
data class PvLegame(val piantaId: String, val gruppoId: String)

data class PvRegola(val azioneId: String, val canale: String, val anticipi: List<Int>)

/** Un anticipo di `cm_reminder_presets`. */
data class PvPreset(val id: Int, val etichetta: String, val minuti: Int)

/** Una foto scelta dal telefono e non ancora caricata. */
data class PvFotoNuova(val uri: Uri)

/** Quel che si chiede nel form di un'azione. */
data class BozzaAzione(
    val id: String? = null,
    /** "" quando l'azione è di un gruppo. */
    val piantaId: String,
    /** 👥 Il gruppo dell'azione; null = è della pianta. */
    val gruppoId: String? = null,
    val titolo: String = "",
    val descrizione: String = "",
    val tipo: String = "simple_recurring",
    val giorno: LocalDate = LocalDate.now(),
    val ora: String = "09:00",
    val scadenza: LocalDate? = null,
    val ogniGiorni: Int = 7,
    val frequenza: String = "weekly",
    val intervallo: Int = 1,
    val giorniSettimana: List<Int> = emptyList(),
    val giorniMese: String = "",
    val dateAnno: String = "",
    val dateMultiple: List<LocalDate> = emptyList(),
    val puntiOk: Int = 10,
    val puntiSalto: Int = -2,
    val puntiRitardo: Int = -2,
    val telegram: Boolean = false,
    val anticipiTelegram: Set<Int> = emptySet(),
    val telefono: Boolean = false,
    val anticipiTelefono: Set<Int> = emptySet(),
    val smartBlock: Boolean = false,
    val riaperta: Boolean = false,
)

object PianteRepository {

    const val APP = "plants"
    private const val BUCKET = "pv-images"
    private const val LATO_MAX = 1600
    private const val QUALITA = 82
    private const val TAG = "Piante"

    private val db get() = Supabase.client().postgrest
    private val bucket get() = Supabase.client().storage.from(BUCKET)
    private val json = Json { ignoreUnknownKeys = true }

    private fun utente(): String = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")

    // ── letture ─────────────────────────────────────────────────────────────

    suspend fun piante(): List<PvPianta> = withContext(Dispatchers.IO) {
        db.from("pv_plants").select(Columns.ALL) { order("name", Order.ASCENDING) }
            .decodeList<JsonObject>().map { PvPianta.da(it) }
    }

    suspend fun voci(): List<PvVoce> = withContext(Dispatchers.IO) {
        db.from("pv_diary").select(Columns.ALL) {
            order("entry_date", Order.DESCENDING)
            order("created_at", Order.DESCENDING)
        }.decodeList<JsonObject>().map { PvVoce.da(it) }
    }

    /** Le foto, ciascuna col suo indirizzo firmato (bucket privato, due ore). */
    suspend fun foto(): List<PvFoto> = withContext(Dispatchers.IO) {
        val righe = db.from("pv_images").select(Columns.ALL) { order("position", Order.ASCENDING) }
            .decodeList<JsonObject>().map { PvFoto.da(it) }
        righe.map { f ->
            f.copy(url = runCatching { bucket.createSignedUrl(f.percorso, 2.hours) }.getOrDefault(""))
        }
    }

    suspend fun azioni(): List<PvAzione> = withContext(Dispatchers.IO) {
        db.from("pv_actions").select(Columns.ALL) { order("created_at", Order.ASCENDING) }
            .decodeList<JsonObject>().map { PvAzione.da(it) }
    }

    /**
     * I gruppi: se le tabelle non si leggono (migration non ancora passata) la
     * pagina resta usabile senza, come nel web.
     */
    suspend fun gruppi(): List<PvGruppo> = withContext(Dispatchers.IO) {
        runCatching {
            db.from("pv_groups").select(Columns.ALL) { order("name", Order.ASCENDING) }
                .decodeList<JsonObject>().map { PvGruppo.da(it) }
        }.onFailure { Log.w(TAG, "gruppi", it) }.getOrDefault(emptyList())
    }

    suspend fun legami(): List<PvLegame> = withContext(Dispatchers.IO) {
        runCatching {
            db.from("pv_plant_groups").select(Columns.raw("plant_id,group_id"))
                .decodeList<JsonObject>().mapNotNull { r ->
                    val p = testo(r, "plant_id") ?: return@mapNotNull null
                    val g = testo(r, "group_id") ?: return@mapNotNull null
                    PvLegame(p, g)
                }
        }.onFailure { Log.w(TAG, "legami dei gruppi", it) }.getOrDefault(emptyList())
    }

    suspend fun storia(): List<PvStoria> = withContext(Dispatchers.IO) {
        db.from("pv_action_history").select(Columns.ALL) {
            order("timestamp", Order.DESCENDING)
            limit(1000)
        }.decodeList<JsonObject>().map { PvStoria.da(it) }
    }

    suspend fun desideri(): List<PvDesiderio> = withContext(Dispatchers.IO) {
        db.from("pv_wishlist").select(Columns.ALL) {
            order("priority", Order.ASCENDING)
            order("created_at", Order.ASCENDING)
        }.decodeList<JsonObject>().map { PvDesiderio.da(it) }
    }

    suspend fun risposte(): List<PvRisposta> = withContext(Dispatchers.IO) {
        db.from("pv_ai_answers").select(Columns.ALL) {
            order("created_at", Order.DESCENDING)
            limit(300)
        }.decodeList<JsonObject>().map { PvRisposta.da(it) }
    }

    /** Le regole non sono di questa app: se non si leggono, la pagina resta usabile. */
    suspend fun regole(): List<PvRegola> = withContext(Dispatchers.IO) {
        runCatching {
            db.from("cm_notification_rules").select(Columns.raw("entity_id,channel,reminder_presets")) {
                filter { eq("app", APP) }
            }.decodeList<JsonObject>().mapNotNull { r ->
                val id = testo(r, "entity_id") ?: return@mapNotNull null
                val canale = testo(r, "channel") ?: return@mapNotNull null
                val presets = when (val rp = r["reminder_presets"]) {
                    is JsonObject -> rp
                    is JsonPrimitive -> runCatching { json.parseToJsonElement(rp.content).jsonObject }.getOrNull()
                    else -> null
                }
                PvRegola(id, canale, presets?.let { listaNumeri(it, "reminders") }.orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    suspend fun preset(): List<PvPreset> = withContext(Dispatchers.IO) {
        runCatching {
            db.from("cm_reminder_presets").select(Columns.raw("int_id,label,offset_minutes,sort_order")) {
                filter { eq("active", true) }
                order("sort_order", Order.ASCENDING)
            }.decodeList<JsonObject>().mapNotNull { p ->
                val id = numero(p, "int_id") ?: return@mapNotNull null
                PvPreset(id, testo(p, "label") ?: "$id", numero(p, "offset_minutes") ?: 0)
            }
        }.getOrDefault(emptyList())
    }

    // ── piante ──────────────────────────────────────────────────────────────

    suspend fun salvaPianta(id: String?, campi: JsonObject, daDesiderio: String?): String =
        withContext(Dispatchers.IO) {
            val riga = buildJsonObject {
                campi.forEach { (k, v) -> put(k, v) }
                put("updated_at", Instant.now().toString())
            }
            if (id != null) {
                db.from("pv_plants").update(riga) { filter { eq("id", id) } }
                id
            } else {
                val nuovo = db.from("pv_plants").insert(buildJsonObject {
                    riga.forEach { (k, v) -> put(k, v) }
                    put("user_id", utente())
                }) { select(Columns.raw("id")) }
                    .decodeList<JsonObject>().firstOrNull()?.let { testo(it, "id") }
                    ?: error("La pianta non ha restituito un id.")
                // Il desiderio passa a «presa» solo quando la pianta c'è davvero.
                if (daDesiderio != null) {
                    db.from("pv_wishlist").update(buildJsonObject {
                        put("status", "presa")
                        put("plant_id", nuovo)
                    }) { filter { eq("id", daDesiderio) } }
                }
                nuovo
            }
        }

    /** ⚠️ Prima i file, poi la riga: la cascata porta via le righe, non i file. */
    suspend fun eliminaPianta(id: String, foto: List<PvFoto>) = withContext(Dispatchers.IO) {
        val percorsi = foto.filter { it.piantaId == id }.map { it.percorso }.filter { it.isNotBlank() }
        percorsi.chunked(100).forEach { runCatching { bucket.delete(it) } }
        db.from("pv_plants").delete { filter { eq("id", id) } }
        Unit
    }

    // ── diario ──────────────────────────────────────────────────────────────

    suspend fun salvaVoce(id: String?, piantaId: String, giorno: LocalDate, commento: String?): String =
        withContext(Dispatchers.IO) {
            val riga = buildJsonObject {
                put("entry_date", giorno.toString())
                put("comment", commento)
            }
            if (id != null) {
                db.from("pv_diary").update(riga) { filter { eq("id", id) } }
                id
            } else {
                db.from("pv_diary").insert(buildJsonObject {
                    riga.forEach { (k, v) -> put(k, v) }
                    put("user_id", utente())
                    put("plant_id", piantaId)
                }) { select(Columns.raw("id")) }
                    .decodeList<JsonObject>().firstOrNull()?.let { testo(it, "id") }
                    ?: error("La voce non ha restituito un id.")
            }
        }

    suspend fun eliminaVoce(id: String, foto: List<PvFoto>) = withContext(Dispatchers.IO) {
        val percorsi = foto.filter { it.voceId == id }.map { it.percorso }.filter { it.isNotBlank() }
        if (percorsi.isNotEmpty()) runCatching { bucket.delete(percorsi) }
        db.from("pv_diary").delete { filter { eq("id", id) } }
        Unit
    }

    // ── foto ────────────────────────────────────────────────────────────────

    /**
     * Carica una foto scelta dal telefono. Prima il file, poi la riga: al
     * contrario una rete che cade lascerebbe una riga che punta al nulla.
     */
    suspend fun caricaFoto(context: Context, uri: Uri, piantaId: String, voceId: String?, posizione: Int) =
        withContext(Dispatchers.IO) {
            val dati = riduci(context, uri)
            val percorso = "${utente()}/$piantaId/${UUID.randomUUID()}.jpg"
            try {
                bucket.upload(percorso, dati) { upsert = false }
            } catch (e: Exception) {
                val m = (e.message ?: "").lowercase()
                throw IllegalStateException(
                    when {
                        "bucket not found" in m -> "Il bucket «pv-images» non esiste: va creato a mano dalla dashboard di Supabase."
                        "row-level" in m || "unauthorized" in m -> "Le regole del bucket «pv-images» non permettono il caricamento."
                        else -> e.message ?: "Caricamento non riuscito"
                    }, e,
                )
            }
            db.from("pv_images").insert(buildJsonObject {
                put("user_id", utente())
                put("plant_id", piantaId)
                put("diary_id", voceId)
                put("storage_path", percorso)
                put("position", posizione)
            })
            Unit
        }

    suspend fun eliminaFoto(foto: PvFoto) = withContext(Dispatchers.IO) {
        if (foto.percorso.isNotBlank()) runCatching { bucket.delete(listOf(foto.percorso)) }
        db.from("pv_images").delete { filter { eq("id", foto.id) } }
        Unit
    }

    /**
     * Le stesse misure di `riduci()` nel web: 1600 px di lato, JPEG 0,82, e
     * l'orientamento dell'EXIF applicato — o la foto arriva coricata.
     */
    private fun riduci(context: Context, uri: Uri): ByteArray {
        val risolutore = context.contentResolver
        val limiti = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        risolutore.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limiti) }
        val latoMax = maxOf(limiti.outWidth, limiti.outHeight)
        var campione = 1
        while (latoMax / (campione * 2) >= LATO_MAX) campione *= 2
        val opzioni = BitmapFactory.Options().apply { inSampleSize = campione }
        var bmp = risolutore.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opzioni) }
            ?: error("Foto non leggibile")

        val rotazione = runCatching {
            risolutore.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val scala = minOf(1f, LATO_MAX.toFloat() / maxOf(bmp.width, bmp.height))
        if (scala < 1f || rotazione != 0f) {
            val m = Matrix().apply {
                postScale(scala, scala)
                postRotate(rotazione)
            }
            val nuova = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (nuova !== bmp) bmp.recycle()
            bmp = nuova
        }
        val uscita = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITA, uscita)
        bmp.recycle()
        return uscita.toByteArray()
    }

    // ── azioni ──────────────────────────────────────────────────────────────

    private fun iso(giorno: LocalDate, ora: String): String {
        val h = ora.take(2).toIntOrNull() ?: 9
        val m = ora.drop(3).take(2).toIntOrNull() ?: 0
        return LocalDateTime.of(giorno, java.time.LocalTime.of(h, m))
            .atZone(ZoneId.systemDefault()).toInstant().toString()
    }

    /**
     * Crea o aggiorna un'azione, poi i suoi promemoria. Stesse colonne e
     * stesse regole di `salvaAzione()` nel web.
     *
     * ⚠️ Anche in modifica partenza e prossima volta si riscrivono dalla data
     * del form: è la risposta a «da quando riparte».
     */
    suspend fun salvaAzione(b: BozzaAzione, nomeDi: String, stavaFinita: Boolean): String =
        withContext(Dispatchers.IO) {
            val inizio = iso(b.giorno, b.ora)
            var prossima: String? = inizio
            var partenza: String? = inizio
            val riga = buildJsonObject {
                put("title", b.titolo.trim())
                put("description", b.descrizione.trim().ifBlank { null })
                put("success_points", b.puntiOk)
                put("skip_points", b.puntiSalto)
                put("late_points", b.puntiRitardo)
                put("deadline", if (b.tipo == "single") b.scadenza?.toString() else null)
                put("repeat_after_days", if (b.tipo == "simple_recurring") maxOf(1, b.ogniGiorni) else null)
                if (b.tipo == "recurring") {
                    put("recurring_frequency", b.frequenza)
                    put("recurring_interval", maxOf(1, b.intervallo))
                    put("recurring_days_of_week",
                        if (b.frequenza == "weekly") buildJsonArray { b.giorniSettimana.sorted().forEach { add(it) } } else JsonNull)
                    val gm = numeriDa(b.giorniMese, 1, 31)
                    put("recurring_day_of_month",
                        if (b.frequenza == "monthly" && gm.isNotEmpty()) buildJsonArray { gm.forEach { add(it) } } else JsonNull)
                    val da = dateAnno(b.dateAnno)
                    put("recurring_dates",
                        if (b.frequenza == "yearly" && da.isNotEmpty()) buildJsonArray { da.forEach { add(it) } } else JsonNull)
                } else {
                    put("recurring_frequency", null as String?)
                    put("recurring_interval", null as Int?)
                    put("recurring_days_of_week", JsonNull)
                    put("recurring_day_of_month", JsonNull)
                    put("recurring_dates", JsonNull)
                }
                if (b.tipo == "multiple") {
                    val date = b.dateMultiple.distinct().sorted()
                    put("multiple_dates", buildJsonArray { date.forEach { add(it.toString()) } })
                    val oggi = LocalDate.now()
                    partenza = iso(date.first(), b.ora)
                    prossima = iso(date.firstOrNull { !it.isBefore(oggi) } ?: date.last(), b.ora)
                } else {
                    put("multiple_dates", JsonNull)
                }
                if (b.tipo == "free_repeat") {
                    prossima = null
                    partenza = if (b.id == null) Instant.now().toString() else null
                }
                partenza?.let { put("start_date", it) }
                put("next_occurrence_date", prossima)
                // Riaprendo un'azione conclusa la si rimette in calendario.
                if (b.id != null && stavaFinita && b.tipo != "free_repeat") put("status", "started")
            }
            val id = if (b.id != null) {
                db.from("pv_actions").update(riga) { filter { eq("id", b.id) } }
                b.id
            } else {
                db.from("pv_actions").insert(buildJsonObject {
                    riga.forEach { (k, v) -> put(k, v) }
                    put("type", b.tipo)
                    put("user_id", utente())
                    // ⚠️ Una pianta OPPURE un gruppo (CHECK `pv_actions_pianta_o_gruppo`).
                    put("plant_id", if (b.gruppoId != null) null else b.piantaId)
                    put("group_id", b.gruppoId)
                }) { select(Columns.raw("id")) }
                    .decodeList<JsonObject>().firstOrNull()?.let { testo(it, "id") }
                    ?: error("L'azione non ha restituito un id.")
            }
            // «azione — pianta» o «azione — gruppo»: il promemoria dice di chi si parla.
            scriviRegole(id, b, if (nomeDi.isBlank()) b.titolo.trim() else "${b.titolo.trim()} — $nomeDi", prossima)
            id
        }

    /**
     * I promemoria: una riga per canale, gemella di `sincronizzaRegole()`.
     *
     * ⚠️ `telegram_complete_button` anche sul telefono: è il segnale da cui
     * `fill-notification-queue` mette il completamento nella coda, e senza la
     * notifica sul telefono non avrebbe il ✅ Fatto.
     */
    private suspend fun scriviRegole(id: String, b: BozzaAzione, titolo: String, dueAt: String?) {
        val senzaData = b.tipo == "free_repeat" || dueAt == null
        val tg = if (!senzaData && b.telegram) b.anticipiTelegram.toList() else emptyList()
        val an = if (!senzaData && b.telefono) b.anticipiTelefono.toList() else emptyList()
        val sbk = !senzaData && b.smartBlock
        suspend fun togli(canale: String) {
            db.from("cm_notification_rules").delete {
                filter {
                    eq("app", APP); eq("entity_id", id); eq("user_id", utente()); eq("channel", canale)
                }
            }
        }
        suspend fun scrivi(canale: String, presets: JsonObject) {
            val esistente = db.from("cm_notification_rules").select(Columns.raw("id")) {
                filter { eq("app", APP); eq("entity_id", id); eq("user_id", utente()); eq("channel", canale) }
            }.decodeList<JsonObject>().firstOrNull()?.let { testo(it, "id") }
            val regola = buildJsonObject {
                put("user_id", utente()); put("app", APP); put("entity_id", id)
                put("entity_type", "task"); put("entity_title", titolo)
                put("reminder_presets", presets); put("notification_spec", buildJsonObject {})
                put("channel", canale); put("enabled", true)
            }
            if (esistente != null) db.from("cm_notification_rules").update(regola) { filter { eq("id", esistente) } }
            else db.from("cm_notification_rules").insert(regola)
        }
        fun presets(anticipi: List<Int>, completa: Boolean) = buildJsonObject {
            put("reminders", buildJsonArray { anticipi.sorted().forEach { add(it) } })
            put("due_at", dueAt)
            if (completa) put("telegram_complete_button", true)
        }
        if (tg.isNotEmpty()) scrivi("telegram", presets(tg, true)) else togli("telegram")
        if (an.isNotEmpty()) scrivi("android", presets(an, true)) else togli("android")
        if (sbk) scrivi("smart_block", presets(emptyList(), false)) else togli("smart_block")
        if (tg.isNotEmpty() || an.isNotEmpty() || sbk) riempiCoda()
    }

    // ── gruppi ──────────────────────────────────────────────────────────────

    /** Crea un gruppo col solo nome (dal form di una pianta): serve subito il suo id. */
    suspend fun creaGruppo(nome: String): PvGruppo = withContext(Dispatchers.IO) {
        db.from("pv_groups").insert(buildJsonObject {
            put("name", nome); put("user_id", utente())
        }) { select(Columns.ALL) }.decodeList<JsonObject>().firstOrNull()?.let { PvGruppo.da(it) }
            ?: error("Il gruppo non ha restituito un id.")
    }

    /**
     * Crea o aggiorna un gruppo e riscrive le sue piante: toglie quelle non più
     * scelte e aggiunge le nuove, senza toccare quelle che restano. Gemella di
     * `salvaGruppo()` nel web.
     */
    suspend fun salvaGruppo(id: String?, nome: String, emoji: String?, prima: Set<String>, scelte: Set<String>): String =
        withContext(Dispatchers.IO) {
            val riga = buildJsonObject { put("name", nome); put("emoji", emoji) }
            val gid = if (id != null) {
                db.from("pv_groups").update(riga) { filter { eq("id", id) } }
                id
            } else {
                db.from("pv_groups").insert(buildJsonObject {
                    riga.forEach { (k, v) -> put(k, v) }
                    put("user_id", utente())
                }) { select(Columns.raw("id")) }
                    .decodeList<JsonObject>().firstOrNull()?.let { testo(it, "id") }
                    ?: error("Il gruppo non ha restituito un id.")
            }
            val via = prima - scelte
            val nuove = scelte - prima
            if (via.isNotEmpty()) db.from("pv_plant_groups").delete {
                filter { eq("group_id", gid); isIn("plant_id", via.toList()) }
            }
            if (nuove.isNotEmpty()) db.from("pv_plant_groups").insert(nuove.map { p ->
                buildJsonObject { put("plant_id", p); put("group_id", gid); put("user_id", utente()) }
            })
            gid
        }

    /** I gruppi di una pianta, come `sincronizzaGruppiPianta()` nel web. */
    suspend fun sincronizzaGruppiPianta(piantaId: String, prima: Set<String>, scelti: Set<String>) =
        withContext(Dispatchers.IO) {
            val via = prima - scelti
            val nuovi = scelti - prima
            if (via.isNotEmpty()) db.from("pv_plant_groups").delete {
                filter { eq("plant_id", piantaId); isIn("group_id", via.toList()) }
            }
            if (nuovi.isNotEmpty()) db.from("pv_plant_groups").insert(nuovi.map { g ->
                buildJsonObject { put("plant_id", piantaId); put("group_id", g); put("user_id", utente()) }
            })
            Unit
        }

    /** Le azioni del gruppo se ne vanno con lui (cascata); le piante restano. */
    suspend fun eliminaGruppo(id: String) = withContext(Dispatchers.IO) {
        db.from("pv_groups").delete { filter { eq("id", id) } }
        Unit
    }

    /** I promemoria li toglie il trigger del database, qualunque app cancelli. */
    suspend fun eliminaAzione(id: String) = withContext(Dispatchers.IO) {
        db.from("pv_actions").delete { filter { eq("id", id) } }
        Unit
    }

    suspend fun completa(id: String, oggi: LocalDate): Esito = rpc("pv_action_complete") {
        put("p_action_id", id)
        put("p_today", oggi.toString())
    }

    /** `p_days` conta solo per le «una volta»: per gli altri tipi la RPC lo ignora. */
    suspend fun salta(id: String, giorni: Int): Esito = rpc("pv_action_skip") {
        put("p_action_id", id)
        put("p_days", giorni)
    }

    private suspend fun rpc(
        nome: String,
        parametri: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Esito = withContext(Dispatchers.IO) {
        val r = db.rpc(nome, buildJsonObject(parametri)).decodeAs<JsonObject>()
        Esito(
            ok = booleano(r, "ok") ?: false,
            punti = numero(r, "points"),
            prossima = testo(r, "next"),
            errore = testo(r, "error"),
        ).also { if (it.ok) riempiCoda() }
    }

    data class Esito(val ok: Boolean, val punti: Int?, val prossima: String?, val errore: String?)

    /** Il cron gira ogni sei ore: un promemoria per stasera non può aspettarlo. */
    private suspend fun riempiCoda() {
        runCatching { Supabase.client().functions.invoke("fill-notification-queue") {} }
            .onFailure { Log.w(TAG, "fill-notification-queue: ${it.message}") }
    }

    // ── desideri ────────────────────────────────────────────────────────────

    suspend fun salvaDesiderio(id: String?, nome: String, specie: String?, note: String?, priorita: Int) =
        withContext(Dispatchers.IO) {
            val riga = buildJsonObject {
                put("name", nome); put("species", specie); put("notes", note); put("priority", priorita)
            }
            if (id != null) db.from("pv_wishlist").update(riga) { filter { eq("id", id) } }
            else db.from("pv_wishlist").insert(buildJsonObject {
                riga.forEach { (k, v) -> put(k, v) }
                put("user_id", utente())
            })
            Unit
        }

    suspend fun statoDesiderio(id: String, stato: String) = withContext(Dispatchers.IO) {
        db.from("pv_wishlist").update(buildJsonObject { put("status", stato) }) { filter { eq("id", id) } }
        Unit
    }

    suspend fun eliminaDesiderio(id: String) = withContext(Dispatchers.IO) {
        db.from("pv_wishlist").delete { filter { eq("id", id) } }
        Unit
    }

    suspend fun eliminaRisposta(id: String) = withContext(Dispatchers.IO) {
        db.from("pv_ai_answers").delete { filter { eq("id", id) } }
        Unit
    }

    // ── IA ──────────────────────────────────────────────────────────────────

    /**
     * «Chiedi all'IA»: la stessa Edge Function del web, `pv-ai`, che raccoglie
     * da sé scheda, diario, azioni e foto e salva la risposta.
     *
     * ⚠️ HttpURLConnection e non `functions.invoke`: la risposta può metterci
     * più di un minuto, e il client di supabase-kt ha un timeout più corto.
     * Il token si controlla prima di mandarlo, come in `ForziereDrive`.
     */
    suspend fun chiediIA(piantaId: String?, domanda: String, fornitore: String?): String = withContext(Dispatchers.IO) {
        val o = chiamaPvAi(buildJsonObject {
            put("plant_id", piantaId)
            put("question", domanda)
            if (fornitore != null) put("provider", fornitore)
        })
        testo(o, "answer") ?: error("Risposta vuota")
    }

    /**
     * Le IA che hanno la chiave nei Secrets (id, nome), nell'ordine della
     * tendina. Se la domanda non passa torna null: la tendina offre allora
     * tutte e tre, e sarà la funzione a dire quale chiave manca.
     */
    suspend fun fornitori(): List<Pair<String, String>>? = withContext(Dispatchers.IO) {
        runCatching {
            val o = chiamaPvAi(buildJsonObject { put("action", "fornitori") })
            (o["fornitori"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                val id = testo(f, "id") ?: return@mapNotNull null
                id to (testo(f, "nome") ?: id)
            }
        }.getOrNull()
    }

    private suspend fun chiamaPvAi(richiesta: JsonObject): JsonObject {
        val corpo = richiesta.toString().toByteArray(Charsets.UTF_8)

        fun manda(token: String): HttpURLConnection =
            (URL(BuildConfig.SUPABASE_URL + "/functions/v1/pv-ai").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 180_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                outputStream.use { it.write(corpo) }
            }

        var c = manda(token())
        if (c.responseCode == 401) {
            c.disconnect()
            c = manda(rinnova() ?: error("Sessione scaduta: rientra e riprova."))
        }
        val codice = c.responseCode
        val testoRisposta = (if (codice in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        val o = runCatching { json.parseToJsonElement(testoRisposta).jsonObject }.getOrNull()
        if (codice !in 200..299 || o == null || booleano(o, "ok") != true) {
            error(o?.let { testo(it, "error") } ?: "IA: HTTP $codice")
        }
        return o
    }

    private suspend fun token(): String {
        val corrente = Supabase.client().auth.currentSessionOrNull()?.accessToken
        if (corrente != null && Jwt.vivo(corrente)) return corrente
        return rinnova() ?: error("Sessione scaduta: rientra e riprova.")
    }

    private suspend fun rinnova(): String? {
        val auth = Supabase.client().auth
        if (auth.currentSessionOrNull() == null) return null
        return runCatching { auth.refreshCurrentSession(); auth.currentSessionOrNull()?.accessToken }.getOrNull()
    }
}

/** «1, 15» → [1, 15], dentro gli estremi. */
fun numeriDa(testo: String, min: Int, max: Int): List<Int> =
    testo.split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in min..max }.distinct().sorted()

/** «15-3, 1-09» → ["15-03", "01-09"]: il formato `DD-MM` di `recurring_dates`. */
fun dateAnno(testo: String): List<String> =
    testo.split(',', ';', ' ').map { it.trim() }
        .filter { Regex("^\\d{1,2}-\\d{1,2}$").matches(it) }
        .map { d -> d.split('-').joinToString("-") { it.padStart(2, '0') } }
