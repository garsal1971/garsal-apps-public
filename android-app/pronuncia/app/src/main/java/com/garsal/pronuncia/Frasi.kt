package com.garsal.pronuncia

import android.content.Context
import org.json.JSONObject

/** Una frase da ripetere. `id` è stabile: dalla fase 5 lo storico la cita per id. */
data class Frase(val id: String, val livello: String, val tema: String, val testo: String)

/**
 * Le frasi stanno in `assets/phrases.json`, nell'APK: funzionano senza rete e
 * non costano niente. ⚠️ Gli `id` non si cambiano e non si riusano — dalla fase 5
 * lo storico li cita, e un id riassegnato attribuirebbe a una frase nuova gli
 * errori di un'altra.
 */
object Frasi {
    val LIVELLI = listOf("A1", "A2", "B1", "B2")
    val TEMI = linkedMapOf("viaggio" to "✈️ Viaggio", "politica" to "🏛️ Politica", "storia" to "📜 Storia")

    @Volatile private var tutte: List<Frase>? = null

    fun tutte(ctx: Context): List<Frase> = tutte ?: synchronized(this) {
        tutte ?: leggi(ctx).also { tutte = it }
    }

    private fun leggi(ctx: Context): List<Frase> {
        val testo = ctx.assets.open("phrases.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(testo).getJSONArray("phrases")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Frase(o.getString("id"), o.getString("level"), o.getString("theme"), o.getString("text"))
        }
    }

    /** Una frase a caso fra quelle del filtro, diversa da [esclusa] quando si può.
     *  `null` nel filtro = tutti. */
    fun aCaso(ctx: Context, livello: String?, tema: String?, esclusa: String? = null): Frase? {
        val scelte = tutte(ctx).filter {
            (livello == null || it.livello == livello) && (tema == null || it.tema == tema)
        }
        return scelte.filter { it.id != esclusa }.randomOrNull() ?: scelte.randomOrNull()
    }
}
