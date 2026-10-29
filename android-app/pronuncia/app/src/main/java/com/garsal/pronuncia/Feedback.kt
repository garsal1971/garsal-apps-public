package com.garsal.pronuncia

/**
 * Le frasi con cui il ciclo commenta i tentativi. Brevi di proposito: si
 * ascoltano a mani libere, e un commento lungo è tempo tolto alla frase dopo.
 */
object Feedback {
    enum class Cosa { INIZIO, BRAVO, RIPROVA, AVANTI, SILENZIO, PAUSA }

    fun testo(l: LinguaFeedback, c: Cosa): String = when (l) {
        LinguaFeedback.INGLESE -> when (c) {
            Cosa.INIZIO -> "Let's start. Repeat after me."
            Cosa.BRAVO -> "Good!"
            Cosa.RIPROVA -> "Try again. Listen."
            Cosa.AVANTI -> "Let's move on."
            Cosa.SILENZIO -> "I didn't hear you. Say it again."
            Cosa.PAUSA -> "I'll stop here. See you later."
        }
        LinguaFeedback.ITALIANO -> when (c) {
            Cosa.INIZIO -> "Cominciamo. Ripeti dopo di me."
            Cosa.BRAVO -> "Bene!"
            Cosa.RIPROVA -> "Riprova. Ascolta."
            Cosa.AVANTI -> "Passiamo alla prossima."
            Cosa.SILENZIO -> "Non ti ho sentito. Ripeti."
            Cosa.PAUSA -> "Mi fermo qui. A dopo."
        }
    }

    /** Voce e lingua del commento: l'inglese con la voce scelta, l'italiano con
     *  la voce italiana dello stesso genere — una voce inglese che legge
     *  l'italiano lo storpia. */
    fun voce(l: LinguaFeedback, v: Voce, a: Accento): Pair<String, String> = when (l) {
        LinguaFeedback.INGLESE -> v.id(a) to a.lingua
        LinguaFeedback.ITALIANO -> (if (v == Voce.UOMO) "it-IT-DiegoNeural" else "it-IT-ElsaNeural") to "it-IT"
    }
}
