package com.garsal.pronuncia

import com.garsal.pronuncia.ui.SOGLIA_BUONA
import java.util.Locale

/** Com'è andata: [buona] decide «Good» o «riprova», [motivi] dicono perché no,
 *  [note] quel che non si è potuto controllare, [daCurare] le parole da far
 *  riascoltare piano. */
data class Verdetto(
    val buona: Boolean,
    val motivi: List<String>,
    val note: List<String>,
    val daCurare: List<Parola>,
)

/**
 * Il giudizio su un tentativo, uno per l'esercizio e per il ciclo a mani libere.
 *
 * Il voto complessivo di Azure da solo è indulgente: è una media, e un suono
 * sbagliato in una parola lo copre il resto della frase. Sopra di lui, tre
 * controlli che si accendono dalle impostazioni, ciascuno con la sua soglia:
 *
 * - **parola peggiore**: basta una parola sotto soglia (o saltata) per bocciare;
 * - **suono peggiore**: basta un fonema sotto soglia, e si dice quale;
 * - **ritmo e intonazione**: il `ProsodyScore` di Azure.
 *
 * ⚠️ Il ritmo Microsoft lo documenta solo per l'inglese americano. Se Azure non
 * lo restituisce (`Esito.ritmo == null`) il controllo NON boccia: lo dice in
 * [Verdetto.note]. Un controllo che non si può fare non è un controllo fallito.
 *
 * ⚠️ Storico e ripasso restano sul voto complessivo: cambiare quel criterio
 * riscriverebbe all'indietro quali frasi erano «riuscite».
 */
object Giudizio {
    const val PAROLA = 70
    const val SUONO = 60
    const val RITMO = 70

    /** Le soglie fra cui si sceglie; `null` = controllo spento. */
    val SOGLIE: List<Int?> = listOf(null, 50, 60, 70, 80, 90)

    fun di(e: Esito, p: Prefs): Verdetto {
        val motivi = mutableListOf<String>()
        val note = mutableListOf<String>()
        val curare = linkedSetOf<Parola>()

        if (e.pronuncia < SOGLIA_BUONA) motivi += "voto complessivo ${uno(e.pronuncia)}, sotto ${SOGLIA_BUONA.toInt()}"

        // Le parole della frase: le inserite non ci sono, quindi non contano.
        val dette = e.parole.filter { it.errore != "Insertion" }

        p.sogliaParola?.let { s ->
            dette.firstOrNull { it.errore == "Omission" }?.let {
                motivi += "parola saltata: «${it.testo}»"; curare += it
            }
            dette.filter { it.errore != "Omission" }.minByOrNull { it.accuratezza }
                ?.takeIf { it.accuratezza < s }
                ?.let { motivi += "«${it.testo}» ${uno(it.accuratezza)}, sotto $s"; curare += it }
        }

        p.sogliaSuono?.let { s ->
            dette.filter { it.errore != "Omission" }
                .flatMap { w -> w.fonemi.map { Triple(w, it.first, it.second) } }
                .minByOrNull { it.third }
                ?.takeIf { it.third < s }
                ?.let { (w, f, v) -> motivi += "suono /$f/ in «${w.testo}» ${uno(v)}, sotto $s"; curare += w }
        }

        p.sogliaRitmo?.let { s ->
            val r = e.ritmo
            when {
                r == null -> note += "Ritmo e intonazione: Azure non li ha valutati" +
                    if (p.accento == Accento.BRITANNICO) " (per l'inglese britannico spesso non sono disponibili)." else "."
                r < s -> motivi += "ritmo e intonazione ${uno(r)}, sotto $s"
            }
        }

        val buona = motivi.isEmpty()
        val daCurare = if (buona) emptyList() else (curare.toList() + e.peggiori()).distinct().take(2)
        return Verdetto(buona, motivi, note, daCurare)
    }

    private fun uno(v: Double) = String.format(Locale.ITALY, "%.0f", v)
}
