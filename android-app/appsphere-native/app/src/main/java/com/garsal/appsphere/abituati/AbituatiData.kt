package com.garsal.appsphere.abituati

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

// ── Perché qui non ci sono @Serializable data class ──────────────────────────
//
// `hb_habits`, `hb_completions` e `hb_archived_stacks` non stanno in nessuna
// migration: sono nate a mano, come `ts_tasks`. E come là alcune colonne sono
// ambigue di natura — `weekdays` può essere `text[]` o `integer[]`,
// `daily_times` una lista di stringhe o di orari — quindi si legge un
// JsonObject e si converte campo per campo: un valore inatteso costa quel
// campo, non l'intera schermata.

/** Un'abitudine, cioè uno «stack» in corso. */
data class HbAbitudine(
    val id: String,
    val nome: String,
    val descrizione: String?,
    val categoriaId: String?,
    val frequenza: String,
    val giorniSettimana: List<Int>,
    val orari: List<String>,
    val inizio: String?,
    val obiettivo: Int,
    val jollyMassimi: Int,
    val jollyUsati: Int,
    val stato: String,
    val puntiPremio: Int,
    val puntiPenalita: Int,
    val riservata: Boolean,
    val volte: Int,
    val giorniFinestra: Int,
    val maxAlGiorno: Int,
) {
    val aPiuOrari: Boolean get() = frequenza == "daily_multiple" && orari.isNotEmpty()

    val settimanale: Boolean get() = frequenza == "weekly" && giorniSettimana.isNotEmpty()

    /** «N volte in M giorni»: la quarta frequenza, che si conta a finestre. */
    val aFinestre: Boolean get() = frequenza == "count_window"

    /** N — quante volte dentro una finestra. Mai sotto 1, come `cwParams()`. */
    val n: Int get() = maxOf(1, volte)

    /** M — quanti giorni dura una finestra. */
    val m: Int get() = maxOf(1, giorniFinestra)

    /** P — quante spunte al massimo nello stesso giorno. */
    val p: Int get() = maxOf(1, maxAlGiorno)

    /**
     * ⚠️ **I pallini di una finestra sono `max(N, M)`, non M**: col tetto
     * giornaliero sopra 1 le volte possono superare i giorni — «10 volte in 5
     * giorni» è una richiesta legittima — e sotto quel numero il traguardo non
     * ci starebbe dentro: la finestra si leggerebbe piena a metà e il ＋ si
     * spegnerebbe prima di aver segnato quel che chiede. Gemello del
     * `caselle` di `renderWindowCard()`.
     */
    val caselle: Int get() = maxOf(n, m)

    /** Il primo giorno della finestra numero `k`, contando dall'inizio. */
    fun inizioFinestra(k: Int): LocalDate? = giornoInizio?.plusDays(k.toLong() * m)

    /**
     * L'indice della finestra in corso, `-1` se l'abitudine non è ancora
     * cominciata. Gemello di `cwCurrentWindowIndex()`.
     */
    fun finestraDi(giorno: LocalDate): Int {
        val da = giornoInizio ?: return -1
        val diff = java.time.temporal.ChronoUnit.DAYS.between(da, giorno)
        return if (diff < 0) -1 else (diff / m).toInt()
    }

    val giornoInizio: LocalDate? get() = inizio?.take(10)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    /**
     * Se l'abitudine va spuntata in un certo giorno — la stessa domanda di
     * `isDayApplicable()`: le giornaliere sempre, le settimanali solo nei
     * giorni scelti, numerati alla JavaScript (0 = domenica).
     */
    fun cadeIl(giorno: LocalDate): Boolean {
        val da = giornoInizio
        if (da != null && giorno < da) return false
        // `dayOfWeek.value` va da 1 (lunedì) a 7 (domenica); il resto della
        // divisione per sette dà la numerazione di JavaScript, che è quella
        // con cui i giorni sono salvati.
        return if (settimanale) (giorno.dayOfWeek.value % 7) in giorniSettimana
        // ⚠️ `count_window` cade OGNI giorno: dentro la finestra si sceglie
        // quando farla, e un giorno in cui non si può segnare niente non
        // esiste. Prima che il ramo ci fosse quella frequenza finiva
        // nell'`else` sconosciuto e l'abitudine non compariva mai in 🎯 Oggi.
        else frequenza == "daily" || frequenza == "daily_multiple" || aFinestre
    }

    companion object {
        fun da(o: JsonObject): HbAbitudine? {
            val id = testo(o, "id") ?: return null
            return HbAbitudine(
                id = id,
                nome = testo(o, "name").orEmpty(),
                descrizione = testo(o, "description"),
                categoriaId = testo(o, "category_id"),
                frequenza = testo(o, "frequency") ?: "daily",
                giorniSettimana = lista(o, "weekdays").mapNotNull { it.toIntOrNull() },
                orari = lista(o, "daily_times").map { it.take(5) },
                inizio = testo(o, "started_at"),
                obiettivo = numero(o, "goal") ?: 0,
                jollyMassimi = numero(o, "max_failures") ?: 0,
                jollyUsati = numero(o, "current_failures") ?: 0,
                stato = testo(o, "status") ?: "active",
                puntiPremio = numero(o, "points_reward") ?: 0,
                puntiPenalita = numero(o, "points_penalty") ?: 0,
                riservata = testo(o, "riservato")?.toBooleanStrictOrNull() ?: false,
                volte = numero(o, "times_target") ?: 0,
                giorniFinestra = numero(o, "window_days") ?: 0,
                maxAlGiorno = numero(o, "max_per_day") ?: 1,
            )
        }
    }
}

/** Una spunta: `completed`, `failed` o `missed` — i tre che il database ammette. */
data class HbSpunta(
    val id: String,
    val abitudineId: String,
    val quando: String,
    val stato: String,
    val chiave: String?,
) {
    /**
     * Il giorno della spunta, letto dai primi dieci caratteri come fa il web
     * con `completed_at.startsWith(dateStr)`. Non si converte il fuso: la riga
     * è stata scritta a mezzogiorno (o all'ora dello slot) proprio perché la
     * data restasse quella, comunque la si legga.
     */
    val giorno: String get() = quando.take(10)

    /** L'orario `HH:MM`, per le abitudini a più slot. */
    val orario: String get() = quando.drop(11).take(5)

    companion object {
        fun da(o: JsonObject): HbSpunta? {
            val id = testo(o, "id") ?: return null
            return HbSpunta(
                id = id,
                abitudineId = testo(o, "habit_id").orEmpty(),
                quando = testo(o, "completed_at").orEmpty(),
                stato = testo(o, "status") ?: "completed",
                chiave = testo(o, "period_key"),
            )
        }
    }
}

/** Uno stack finito, in archivio. */
data class HbArchiviato(
    val id: String,
    val nome: String,
    val inizio: String?,
    val fine: String?,
    val streak: Int,
    val giorni: Int,
    val completamenti: Int,
    val fallimenti: Int,
    val punti: Int,
    val motivo: String,
) {
    /** Come lo racconta la scheda in archivio nel web. */
    val esito: String get() = when (motivo) {
        "completato" -> "🏆 Completato"
        "completato_con_jolly" -> "🏆 Completato coi jolly"
        "scadenza_calendario" -> "⏰ Scaduto"
        "jolly_esauriti" -> "💀 Jolly esauriti"
        else -> motivo
    }

    companion object {
        fun da(o: JsonObject): HbArchiviato? {
            val id = testo(o, "id") ?: return null
            return HbArchiviato(
                id = id,
                nome = testo(o, "habit_name").orEmpty(),
                inizio = testo(o, "started_at"),
                fine = testo(o, "ended_at"),
                streak = numero(o, "final_streak") ?: 0,
                giorni = numero(o, "total_days") ?: 0,
                completamenti = numero(o, "total_completions") ?: 0,
                fallimenti = numero(o, "total_failures") ?: 0,
                punti = numero(o, "points_earned") ?: 0,
                motivo = testo(o, "reason").orEmpty(),
            )
        }
    }
}

/** Una categoria condivisa (`cm_categories`), la stessa tabella di Tasks. */
data class HbCategoria(val id: String, val nome: String, val icona: String, val colore: String) {
    val etichetta: String get() = if (icona.isBlank()) nome else "$icona $nome"

    companion object {
        fun da(o: JsonObject): HbCategoria? {
            val id = testo(o, "id") ?: return null
            return HbCategoria(
                id = id,
                nome = testo(o, "name").orEmpty(),
                icona = testo(o, "icon").orEmpty(),
                colore = testo(o, "color")?.takeIf { it.isNotBlank() } ?: "#6B7280",
            )
        }
    }
}

/**
 * Un esito che `hb_reconcile` segnala e che vuole una cerimonia: uno stack
 * completato o un game over.
 */
data class HbEsito(
    val abitudineId: String,
    val nome: String,
    val streak: Int,
    val punti: Int,
    val motivo: String,
    val mancati: Int = 0,
    val giaArchiviato: Boolean = true,
) {
    val vinto: Boolean get() = motivo == "completato" || motivo == "completato_con_jolly"

    companion object {
        fun da(o: JsonObject) = HbEsito(
            abitudineId = testo(o, "habit_id").orEmpty(),
            nome = testo(o, "nome").orEmpty(),
            streak = numero(o, "streak") ?: 0,
            punti = numero(o, "punti") ?: 0,
            motivo = testo(o, "motivo").orEmpty(),
            mancati = numero(o, "mancati") ?: 0,
            giaArchiviato = testo(o, "archiviato")?.toBooleanStrictOrNull() ?: true,
        )
    }
}

/**
 * 🔔 La regola di promemoria di un'abitudine su UN canale (`cm_notification_rules`,
 * `app = 'habits'`). Telegram e telefono sono due righe, ciascuna coi suoi
 * anticipi: è la cache `{ telegram, android }` del web.
 */
data class HbRegola(
    val abitudineId: String,
    val canale: String,
    val anticipi: List<Int>,
    val orari: List<String>,
    val bottoneFatto: Boolean,
) {
    companion object {
        fun da(o: JsonObject): HbRegola? {
            val id = testo(o, "entity_id") ?: return null
            val p = o["reminder_presets"] as? JsonObject
            return HbRegola(
                abitudineId = id,
                // Come `loadHabitNotificationRules()`: tutto quel che non è
                // android vale telegram.
                canale = if (testo(o, "channel") == "android") "android" else "telegram",
                anticipi = p?.let { lista(it, "reminders") }.orEmpty().mapNotNull { it.toDoubleOrNull()?.toInt() },
                orari = p?.let { lista(it, "times") }.orEmpty().ifEmpty { listOfNotNull(p?.let { testo(it, "time") }) },
                bottoneFatto = p?.let { testo(it, "telegram_complete_button") }?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

/** Un anticipo proposto (`cm_reminder_presets`): «15 min prima», «all'ora»… */
data class HbPreset(val id: Int, val etichetta: String, val minuti: Int)

internal fun testo(o: JsonObject, chiave: String): String? =
    (o[chiave] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun numero(o: JsonObject, chiave: String): Int? =
    testo(o, chiave)?.toDoubleOrNull()?.toInt()

internal fun lista(o: JsonObject, chiave: String): List<String> =
    (o[chiave] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
        .orEmpty()
