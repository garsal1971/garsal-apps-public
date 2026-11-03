package com.garsal.pronuncia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un elemento della sessione; [peggiore] è il voto più basso preso finora (null = mai fatto). */
data class Scelta(val frase: Frase, val peggiore: Double?)

/**
 * Gli elementi di una sessione, scelti TUTTI prima di cominciare (v1.22.0, al
 * posto del ripasso): metà mai fatti, a caso, e metà già fatti, dal voto più
 * basso mai preso in su. Con N dispari quelli nuovi sono uno in più. Se un
 * gruppo non basta si completa con l'altro, così la sessione resta di N.
 *
 * ⚠️ Nessuna tabella: «già fatto» e «voto più basso» si leggono dallo storico,
 * quindi cancellando lo storico tutto torna «mai fatto».
 */
object Sessione {
    suspend fun scegli(ctx: Context, livello: String?, tema: String?, soloParole: Boolean, n: Int): List<Scelta> =
        withContext(Dispatchers.IO) {
            val tema = Frasi.temaValido(tema)
            val peggiori = Storico.dao(ctx).punteggi().groupBy({ it.fraseId }, { it.pronuncia }).mapValues { it.value.min() }
            val scelte = Frasi.catalogo(ctx, soloParole).filter {
                (livello == null || it.livello == livello) && (tema == null || it.tema == tema)
            }
            val nuove = scelte.filter { it.id !in peggiori }.shuffled().map { Scelta(it, null) }
            val vecchie = scelte.filter { it.id in peggiori }.sortedBy { peggiori[it.id] }.map { Scelta(it, peggiori[it.id]) }
            var quanteNuove = minOf((n + 1) / 2, nuove.size)
            val quanteVecchie = minOf(n - quanteNuove, vecchie.size)
            quanteNuove = minOf(n - quanteVecchie, nuove.size)
            val a = nuove.take(quanteNuove)
            val b = vecchie.take(quanteVecchie)
            // Alternate, una nuova e una già fatta: le difficili non tutte in fila.
            (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
        }
}
