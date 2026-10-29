package com.garsal.pronuncia

import android.content.Context
import com.garsal.pronuncia.ui.SOGLIA_BUONA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fase 6: quali frasi riproporre, letto dallo storico.
 *
 * Una frase è **da ripassare** se almeno un tentativo è andato sotto
 * [SOGLIA_BUONA] e gli ultimi DUE non sono entrambi sopra: due riuscite di
 * fila la rimettono fra le normali. Ci entrano anche le frasi **mai fatte** che
 * contengono una delle parole più sbagliate, perché il problema è la parola e
 * non quella frase.
 *
 * ⚠️ Non c'è nessuna tabella «da ripassare»: si ricava ogni volta dai
 * tentativi, così non può dire una cosa diversa dallo storico che si legge.
 */
object Ripasso {
    const val OGNI = 3
    private const val RIUSCITE_PER_USCIRE = 2
    private const val PAROLE = 10

    suspend fun prossima(ctx: Context, livello: String?, tema: String?, esclusa: String? = null, soloParole: Boolean = false): Frase? =
        withContext(Dispatchers.IO) { candidate(ctx, livello, tema, soloParole).filter { it.id != esclusa }.randomOrNull() }

    /** Con [soloParole] lo stesso giro sulle parole sole: una parola sbagliata
     *  torna finché non riesce due volte di fila, e quelle mai fatte entrano se
     *  sono fra le più sbagliate nelle frasi. */
    suspend fun candidate(ctx: Context, livello: String?, tema: String?, soloParole: Boolean = false): List<Frase> = withContext(Dispatchers.IO) {
        val dao = Storico.dao(ctx)
        // Dal più recente: i primi due per frase sono gli ultimi due tentativi.
        val perFrase = dao.punteggi().groupBy({ it.fraseId }, { it.pronuncia })
        val daRipassare = perFrase.filter { (_, p) ->
            p.any { it < SOGLIA_BUONA } &&
                !(p.size >= RIUSCITE_PER_USCIRE && p.take(RIUSCITE_PER_USCIRE).all { it >= SOGLIA_BUONA })
        }.keys
        val difficili = dao.paroleDifficiliOra(SOGLIA_BUONA, PAROLE).map { it.parola }.toSet()
        Frasi.catalogo(ctx, soloParole).filter { f ->
            (livello == null || f.livello == livello) && (tema == null || f.tema == tema) &&
                (f.id in daRipassare ||
                    (f.id !in perFrase && f.testo.split(Regex("\\s+")).any { Storico.normalizza(it) in difficili }))
        }
    }
}
