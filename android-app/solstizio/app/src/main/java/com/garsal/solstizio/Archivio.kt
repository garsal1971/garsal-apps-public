package com.garsal.solstizio

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate
import kotlin.random.Random

/** Le tre facce della carta. Il tema di un giorno è fisso: lo decide la data. */
enum class Tema(val nome: String, val emoji: String) {
    SOLE("Il Sole", "☀️"),
    STELLE("Le Stelle", "✨"),
    MARE("Il Mare", "🌊");

    companion object {
        /** Il giorno di un solstizio o di un equinozio è sempre del Sole: è la sua festa. */
        fun di(giorno: LocalDate, evento: Boolean): Tema =
            if (evento) SOLE else entries[Random(giorno.toEpochDay()).nextInt(entries.size)]
    }
}

/**
 * Tutto quel che l'app ricorda sta qui, sul telefono e da nessun'altra parte:
 * quali carte sono state scoperte e, per ciascuna, le temperature di quel
 * giorno. ⚠️ Le temperature si **congelano** alla scoperta: le previsioni di
 * Open-Meteo coprono pochi giorni indietro, e senza la fotografia una carta
 * del mese scorso non saprebbe più che tempo faceva.
 */
object Archivio {

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("solstizio", Context.MODE_PRIVATE)

    fun scoperte(ctx: Context): Set<LocalDate> =
        prefs(ctx).getStringSet("scoperte", emptySet())!!.mapNotNull {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }.toSet()

    fun scopri(ctx: Context, giorno: LocalDate) {
        val p = prefs(ctx)
        val s = p.getStringSet("scoperte", emptySet())!!.toMutableSet()
        s += giorno.toString()
        p.edit().putStringSet("scoperte", s).apply()
    }

    fun temperatureDi(ctx: Context, giorno: LocalDate): Temperature? =
        prefs(ctx).getString("carta_$giorno", null)?.let { runCatching { Temperature.da(JSONObject(it)) }.getOrNull() }

    fun congela(ctx: Context, giorno: LocalDate, t: Temperature) {
        prefs(ctx).edit().putString("carta_$giorno", t.json().toString()).apply()
    }
}
