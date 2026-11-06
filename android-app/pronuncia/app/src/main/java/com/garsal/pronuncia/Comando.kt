package com.garsal.pronuncia

/**
 * I comandi a voce del ciclo a mani libere — e dei tasti delle cuffie, che
 * passano dallo stesso posto (`Ciclo.comanda`).
 *
 * ⚠️ Si dicono AL POSTO della frase, quando tocca a te: non c'è un secondo
 * microfono sempre aperto. Costerebbe minuti di riconoscimento a ogni frase
 * letta, e capterebbe la voce dell'app stessa.
 */
enum class Comando { RIPETI, AVANTI, LENTO, FERMA,
    /** v1.23.0: lo spelling della parola, lettera per lettera, con la voce
     *  inglese. Sulle frasi non c'è: lo dice e rilegge la frase. */
    SPELLING,
    /** v1.23.0: la traduzione italiana (`pr_traduzioni`), con la voce italiana. */
    TRADUCI;

    companion object {
        /** v1.31.0: i comandi si riconoscono SOLO sul telefono, confrontando la
         *  voce con le registrazioni (`ComandiVoce`): i comandi inglesi detti ad
         *  Azure («spell», «command spell») sono stati tolti, perché Azure li
         *  forzava sulla parola attesa. Il comando arriva al giro dentro
         *  `Esito.riconosciuto` con questo segno, che nessuna voce può produrre. */
        private const val SEGNO = "§comando:"

        fun frase(c: Comando): String = SEGNO + c.name

        /** Il comando riconosciuto in locale, o `null` = è un tentativo. */
        @Suppress("UNUSED_PARAMETER")
        fun da(riconosciuto: String, riferimento: String): Comando? =
            if (riconosciuto.startsWith(SEGNO)) entries.firstOrNull { it.name == riconosciuto.removePrefix(SEGNO) } else null
    }
}
