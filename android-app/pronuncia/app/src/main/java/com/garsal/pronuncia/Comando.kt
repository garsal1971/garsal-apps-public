package com.garsal.pronuncia

/**
 * I comandi a voce del ciclo a mani libere — e dei tasti delle cuffie, che
 * passano dallo stesso posto (`Ciclo.comanda`).
 *
 * ⚠️ Si dicono AL POSTO della frase, quando tocca a te: non c'è un secondo
 * microfono sempre aperto. Costerebbe minuti di riconoscimento a ogni frase
 * letta, e capterebbe la voce dell'app stessa.
 */
enum class Comando { RIPETI, AVANTI, LENTO, FERMA;

    companion object {
        /** Le parole riconosciute come comando. In inglese: il riconoscimento è
         *  en-GB, e una parola italiana tornerebbe storpiata. */
        val PAROLE = listOf("repeat", "again", "next", "skip", "slower", "slowly", "stop")

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
                else -> null
            }
        }

        private fun parole(s: String) = s.lowercase()
            .split(Regex("[^a-z']+")).filter { it.isNotBlank() }
    }
}
