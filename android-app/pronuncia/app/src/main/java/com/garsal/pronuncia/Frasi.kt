package com.garsal.pronuncia

import android.content.Context
import org.json.JSONObject

/** Una frase — o una parola sola — da ripetere. `id` è stabile: dalla fase 5
 *  lo storico la cita per id. Le parole hanno l'id che comincia con `w-`. */
data class Frase(val id: String, val livello: String, val tema: String, val testo: String, val parola: Boolean = false)

/**
 * Le frasi stanno in `assets/phrases.json`, le parole sole in
 * `assets/words.json`, nell'APK: funzionano senza rete e non costano niente.
 * ⚠️ Gli `id` non si cambiano e non si riusano — lo storico li cita, e un id
 * riassegnato attribuirebbe a una frase nuova gli errori di un'altra.
 */
object Frasi {
    val LIVELLI = listOf("A1", "A2", "B1", "B2")
    val TEMI = linkedMapOf(
        "viaggio" to "✈️ Viaggio", "politica" to "🏛️ Politica", "storia" to "📜 Storia",
        "natura" to "🌿 Piante e animali", "citta" to "🏙️ Città e luoghi", "famosi" to "⭐ Personaggi famosi",
        "cibo" to "🍽️ Cibo", "casa" to "🏠 Casa e famiglia", "lavoro" to "💼 Lavoro", "salute" to "🩺 Salute",
    )

    /** ⚠️ Le frasi hanno i soli primi tre temi, le parole (v1.20.0) tutti e
     *  dieci: un tema senza frasi lascerebbe l'esercizio senza niente da dire. */
    val TEMI_FRASI = setOf("viaggio", "politica", "storia")

    fun temi(soloParole: Boolean): List<String> =
        if (soloParole) TEMI.keys.toList() else TEMI.keys.filter { it in TEMI_FRASI }

    @Volatile private var frasi: List<Frase>? = null
    @Volatile private var parole: List<Frase>? = null

    fun tutte(ctx: Context): List<Frase> = frasi ?: synchronized(this) {
        frasi ?: leggi(ctx, "phrases.json", "phrases", false).also { frasi = it }
    }

    fun parole(ctx: Context): List<Frase> = parole ?: synchronized(this) {
        parole ?: leggi(ctx, "words.json", "words", true).also { parole = it }
    }

    /** Le frasi o le parole, secondo quel che si è scelto di esercitare. */
    fun catalogo(ctx: Context, soloParole: Boolean) = if (soloParole) parole(ctx) else tutte(ctx)

    private fun leggi(ctx: Context, file: String, chiave: String, parola: Boolean): List<Frase> {
        val testo = ctx.assets.open(file).bufferedReader().use { it.readText() }
        val arr = JSONObject(testo).getJSONArray(chiave)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Frase(o.getString("id"), o.getString("level"), o.getString("theme"), o.getString("text"), parola)
        }
    }

    /** Una frase (o parola) a caso fra quelle del filtro, diversa da [esclusa]
     *  quando si può. `null` nel filtro = tutti. */
    fun aCaso(ctx: Context, livello: String?, tema: String?, esclusa: String? = null, soloParole: Boolean = false): Frase? {
        val scelte = catalogo(ctx, soloParole).filter {
            (livello == null || it.livello == livello) && (tema == null || it.tema == tema)
        }
        return scelte.filter { it.id != esclusa }.randomOrNull() ?: scelte.randomOrNull()
    }
}
