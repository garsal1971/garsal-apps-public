package com.garsal.appsphere.premi

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Perché qui non ci sono @Serializable data class ──────────────────────────
//
// `cm_rewards` e `cm_rewards_log` non stanno in nessuna migration: come
// `ts_tasks` e `ps_weight_tracking` sono tabelle create a mano prima che
// esistesse `supabase/migrations/`, e le colonne si conoscono solo da come
// `index.html` le scrive. `id` per esempio è indistinguibile fra bigint e uuid
// dal codice della pagina, che lo interpola in una stringa (`id=eq.${id}`) e
// funziona con tutti e due.
//
// Con una data class serializzata una sola colonna del tipo inatteso farebbe
// fallire la decodifica **dell'intera lista**: non un premio storto, ma la
// schermata vuota. Qui si legge un JsonObject e si converte campo per campo.

/** Un premio del catalogo (`cm_rewards`). */
data class Premio(
    val id: String,
    val nome: String,
    val tipo: String,
    val costo: Int,
    val puntiPerUso: Int?,
    val usi: Int,
    val ritirato: Boolean,
) {
    val ripetibile: Boolean get() = tipo == RIPETIBILE

    /** 🔄 o ⭐, come nel web. */
    val emoji: String get() = if (ripetibile) "🔄" else "⭐"

    companion object {
        const val UNA_TANTUM = "una_tantum"
        const val RIPETIBILE = "ripetibile"

        fun da(o: JsonObject): Premio? {
            val id = testo(o, "id") ?: return null
            return Premio(
                id = id,
                nome = testo(o, "name").orEmpty(),
                tipo = testo(o, "type") ?: UNA_TANTUM,
                costo = numero(o, "points_cost") ?: 0,
                puntiPerUso = numero(o, "points_per_use"),
                usi = numero(o, "use_count") ?: 0,
                ritirato = testo(o, "is_redeemed")?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

/** Una riga della cronologia dei riscatti (`cm_rewards_log`). */
data class Riscatto(
    val nome: String,
    val punti: Int,
    val quando: String,
) {
    companion object {
        fun da(o: JsonObject) = Riscatto(
            nome = testo(o, "reward_name").orEmpty(),
            punti = numero(o, "points_spent") ?: 0,
            quando = dataOra(testo(o, "redeemed_at")),
        )
    }
}

internal fun testo(o: JsonObject, chiave: String): String? =
    (o[chiave] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun numero(o: JsonObject, chiave: String): Int? =
    testo(o, chiave)?.toDoubleOrNull()?.toInt()

/**
 * `redeemed_at` nel fuso del telefono, come `new Date(...)` nel web.
 *
 * La colonna può portare l'offset (`timestamptz`) o non portarlo: nel secondo
 * caso JavaScript lo legge come ora locale, e qui si fa lo stesso. Se non si
 * legge affatto si mostra il valore grezzo — una data storta è meglio di una
 * riga che manca.
 */
private fun dataOra(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    return runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(formato)
    }.recoverCatching {
        LocalDateTime.parse(iso).format(formato)
    }.getOrDefault(iso)
}
