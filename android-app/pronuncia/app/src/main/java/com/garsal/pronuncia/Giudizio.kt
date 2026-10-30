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
    /** Gli stessi [motivi], ma con i dati: il ciclo ne dice il primo a voce e
     *  la schermata evidenzia le lettere del suono. Stesso ordine. */
    val difetti: List<Difetto> = emptyList(),
)

/** Un controllo che ha bocciato il tentativo. */
sealed interface Difetto {
    data class Voto(val voto: Double) : Difetto
    data class Saltata(val parola: Parola) : Difetto
    data class ParolaBassa(val parola: Parola, val voto: Double, val soglia: Int) : Difetto
    /** [grafema]: dove, in `parola.testo`, stanno le lettere del suono
     *  (`Grafema.trova`); `null` = non trovate, si dice solo la parola. */
    data class Suono(val parola: Parola, val fonema: String, val voto: Double, val soglia: Int, val grafema: IntRange?) : Difetto
    data class Ritmo(val voto: Double, val soglia: Int) : Difetto
}

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
 * Le soglie sono due serie, una per le frasi e una per le parole singole
 * (`Prefs.soglia`, `Controllo`).
 *
 * ⚠️ Il ritmo Microsoft lo documenta solo per l'inglese americano. Se Azure non
 * lo restituisce (`Esito.ritmo == null`) il controllo NON boccia: lo dice in
 * [Verdetto.note]. Un controllo che non si può fare non è un controllo fallito.
 *
 * ⚠️ Storico e ripasso restano sul voto complessivo: cambiare quel criterio
 * riscriverebbe all'indietro quali frasi erano «riuscite».
 */
object Giudizio {
    /** Le soglie fra cui si sceglie; `null` = controllo spento. */
    val SOGLIE: List<Int?> = listOf(null, 50, 60, 70, 80, 90)

    /** [parola]: il tentativo è su una parola singola, e valgono le sue soglie. */
    fun di(e: Esito, p: Prefs, parola: Boolean): Verdetto {
        val motivi = mutableListOf<String>()
        val difetti = mutableListOf<Difetto>()
        val note = mutableListOf<String>()
        val curare = linkedSetOf<Parola>()

        if (e.pronuncia < SOGLIA_BUONA) {
            motivi += "voto complessivo ${uno(e.pronuncia)}, sotto ${SOGLIA_BUONA.toInt()}"; difetti += Difetto.Voto(e.pronuncia)
        }

        // Le parole della frase: le inserite non ci sono, quindi non contano.
        val dette = e.parole.filter { it.errore != "Insertion" }

        p.soglia(Controllo.PAROLA, parola)?.let { s ->
            dette.firstOrNull { it.errore == "Omission" }?.let {
                motivi += "parola saltata: «${it.testo}»"; difetti += Difetto.Saltata(it); curare += it
            }
            dette.filter { it.errore != "Omission" }.minByOrNull { it.accuratezza }
                ?.takeIf { it.accuratezza < s }
                ?.let {
                    motivi += "«${it.testo}» ${uno(it.accuratezza)}, sotto $s"
                    difetti += Difetto.ParolaBassa(it, it.accuratezza, s); curare += it
                }
        }

        p.soglia(Controllo.SUONO, parola)?.let { s ->
            dette.filter { it.errore != "Omission" }
                .flatMap { w -> w.fonemi.mapIndexed { i, f -> Triple(w, i, f) } }
                .minByOrNull { it.third.second }
                ?.takeIf { it.third.second < s }
                ?.let { (w, i, fv) ->
                    val (f, v) = fv
                    val g = Grafema.trova(w.testo, f, i, w.fonemi.size)
                    val dove = g?.let { " (lettere «${w.testo.substring(it).lowercase()}»)" } ?: ""
                    motivi += "suono /$f/$dove in «${w.testo}» ${uno(v)}, sotto $s"
                    difetti += Difetto.Suono(w, f, v, s, g); curare += w
                }
        }

        p.soglia(Controllo.RITMO, parola)?.let { s ->
            val r = e.ritmo
            when {
                r == null -> note += "Ritmo e intonazione: Azure non li ha valutati" +
                    if (p.accento == Accento.BRITANNICO) " (per l'inglese britannico spesso non sono disponibili)." else "."
                r < s -> { motivi += "ritmo e intonazione ${uno(r)}, sotto $s"; difetti += Difetto.Ritmo(r, s) }
            }
        }

        val buona = motivi.isEmpty()
        val daCurare = if (buona) emptyList() else (curare.toList() + e.peggiori()).distinct().take(2)
        return Verdetto(buona, motivi, note, daCurare, difetti)
    }

    private fun uno(v: Double) = String.format(Locale.ITALY, "%.0f", v)
}
