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
        /** Le parole riconosciute come comando. In inglese: il riconoscimento è
         *  en-GB, e una parola italiana tornerebbe storpiata. */
        val PAROLE = listOf(
            "repeat", "again", "next", "skip", "slower", "slowly", "stop",
            "spell", "spelling", "translate", "translation",
        )

        /**
         * Il comando, se [riconosciuto] è SOLO un comando (al più due parole,
         * «repeat please»): una frase che contiene «stop» in mezzo è un
         * tentativo, non un ordine. `null` = è un tentativo.
         */
        fun da(riconosciuto: String, riferimento: String): Comando? {
            val p = parole(riconosciuto)
            if (p.isEmpty() || p.size > 2 || p == parole(riferimento)) return null
            return when (p.first()) {
                "repeat", "again" -> RIPETI
                "next", "skip" -> AVANTI
                "slower", "slowly", "slow" -> LENTO
                "stop" -> FERMA
                "spell", "spelling" -> SPELLING
                "translate", "translation" -> TRADUCI
                else -> null
            }
        }

        /**
         * v1.27.0: la parola del comando, anche sentita un po' storpiata
         * («spill», «spelt», «translated»), o `null`. ⚠️ Solo per il caso in cui
         * della parola attesa non è stato detto niente (`Valutatore`): applicata
         * a ogni tentativo, «still» pronunciato male diventerebbe uno spelling.
         * Torna la parola canonica, così `da` la riconosce dopo.
         */
        fun tollerante(riconosciuto: String, riferimento: String = ""): String? {
            val p = parole(riconosciuto)
            // La parola attesa detta (anche male) non è un comando, anche se somiglia a uno.
            if (p.isEmpty() || p.size > 2 || p == parole(riferimento)) return null
            if (da(riconosciuto, "") != null) return p.first()
            val w = p.first()
            return when {
                Regex("^(sp[eiay]l|spe?ll)").containsMatchIn(w) -> "spell"
                w.startsWith("trans") || w.startsWith("tradu") -> "translate"
                w.startsWith("repea") || w.startsWith("repit") -> "repeat"
                else -> null
            }
        }

        private fun parole(s: String) = s.lowercase()
            .split(Regex("[^a-z']+")).filter { it.isNotBlank() }
    }
}
