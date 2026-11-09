package com.garsal.pronuncia

import android.content.Context
import com.garsal.pronuncia.ui.SOGLIA_BUONA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gli elementi di una sessione, scelti TUTTI prima di cominciare (v1.22.0).
 *
 * Dalla v1.33.0 la regola sta in `Ripasso.componi`, che non conosce Android e
 * si prova coi test: metà mai fatti — fino a metà dei quali scelti per i suoni
 * deboli del `ProfiloSuoni` — e metà già fatti, fra quelli il cui intervallo è
 * scaduto, da come vanno ADESSO (gli ultimi due voti) e non più dal voto più
 * basso mai preso. Qui si leggono solo catalogo e storico.
 *
 * ⚠️ Nessuna tabella: «già fatto», voti e suoni deboli si leggono dallo
 * storico, quindi cancellando lo storico tutto torna «mai fatto».
 */
object Sessione {
    suspend fun scegli(ctx: Context, livello: String?, tema: String?, soloParole: Boolean, n: Int): List<Scelta> =
        withContext(Dispatchers.IO) {
            val tema = Frasi.temaValido(tema)
            val dao = Storico.dao(ctx)
            val scelte = Frasi.catalogo(ctx, soloParole).filter {
                (livello == null || it.livello == livello) && (tema == null || it.tema == tema)
            }
            val righe = dao.fonemiRecenti(ProfiloSuoni.RIGHE).map { it.parola to it.fonemi }
            val deboli = ProfiloSuoni.deboli(ProfiloSuoni.calcola(righe)).map { it.fonema }
            val viste = ProfiloSuoni.paroleCon(righe)
            Ripasso.componi(
                scelte, dao.storia(), n, System.currentTimeMillis(), SOGLIA_BUONA, deboli,
                contiene = { f, d -> ProfiloSuoni.contiene(f.testo, d, viste[d].orEmpty()) },
            )
        }
}
