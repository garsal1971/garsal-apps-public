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
        /** v1.29.0: ogni comando comincia con questa parola («command spell»).
         *  Da sola, «spell» Azure la forzava sulla parola attesa («please»):
         *  due parole non si confondono con una parola da pronunciare. */
        const val PREFISSO = "command"

        /** Le parole del comando, dopo il prefisso. In inglese: il riconoscimento
         *  è en-GB, e una parola italiana tornerebbe storpiata. */
        private val VERBI = listOf(
            "repeat", "again", "next", "skip", "slower", "slowly", "stop",
            "spell", "spelling", "translate", "translation",
        )

        /** Le frasi riconosciute come comando, per la `PhraseListGrammar`. */
        val PAROLE = VERBI.map { "$PREFISSO $it" }

        /**
         * Il comando, se [riconosciuto] è SOLO un comando: il prefisso e la parola
         * del comando (al più tre parole, «command repeat please»). Una frase che
         * contiene «stop» in mezzo è un tentativo, non un ordine, e così «spell»
         * senza prefisso. `null` = è un tentativo.
         */
        fun da(riconosciuto: String, riferimento: String): Comando? {
            val p = parole(riconosciuto)
            if (p.size !in 2..3 || p == parole(riferimento) || !prefisso(p[0])) return null
            return when (p[1]) {
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
         * v1.27.0: il comando, anche sentito un po' storpiato («comment spill»,
         * «commands translated»), nella forma canonica che `da` riconosce, o
         * `null`. ⚠️ Solo per il caso in cui della parola attesa non è stato
         * detto (quasi) niente (`Valutatore`). Il prefisso resta obbligatorio.
         */
        fun tollerante(riconosciuto: String, riferimento: String = ""): String? {
            val p = parole(riconosciuto)
            // La parola attesa detta (anche male) non è un comando, anche se somiglia a uno.
            if (p.size !in 2..3 || p == parole(riferimento)) return null
            if (da(riconosciuto, "") != null) return "$PREFISSO ${p[1]}"
            if (!prefissoStorpiato(p[0])) return null
            val w = p[1]
            val verbo = when {
                w in VERBI || w == "slow" -> w
                Regex("^(sp[eiay]l|spe?ll)").containsMatchIn(w) -> "spell"
                w.startsWith("trans") || w.startsWith("tradu") -> "translate"
                w.startsWith("repea") || w.startsWith("repit") -> "repeat"
                else -> return null
            }
            return "$PREFISSO $verbo"
        }

        private fun prefisso(w: String) = w == PREFISSO || w == "commands"

        /** «command» come Azure può sentirlo: «comment», «commend», «comand»… */
        private fun prefissoStorpiato(w: String) =
            prefisso(w) || Regex("^co(m+|n)(a|e|i)n+[dt]?s?$").matches(w)

        private fun parole(s: String) = s.lowercase()
            .split(Regex("[^a-z']+")).filter { it.isNotBlank() }
    }
}
