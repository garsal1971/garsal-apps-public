package com.garsal.pronuncia

/**
 * Dal suono alle lettere: dato il fonema che Azure ha giudicato peggiore e la
 * sua posizione nella parola, trova il pezzo di parola che lo scrive («th» in
 * «think») e lo sa dire lettera per lettera in italiano («ti acca»).
 *
 * ⚠️ Azure dice QUALE suono e IN CHE POSIZIONE, non quali lettere: il legame
 * lo fa [CANDIDATI], una tabella dei modi in cui l'inglese scrive ogni fonema.
 * Fra le occorrenze nella parola vince quella più vicina a dove il suono cade
 * (posizione del fonema riportata sulla lunghezza della parola), e a pari
 * distanza la più lunga — «tch» prima di «t».
 *
 * ⚠️ Se niente di plausibile cade abbastanza vicino si torna `null`, e chi
 * parla dice solo la parola: un grafema inventato insegnerebbe la lettera
 * sbagliata. Le vocali sono il punto debole — /ə/ si scrive in mille modi.
 */
object Grafema {
    private val CANDIDATI: Map<String, List<String>> = mapOf(
        "θ" to listOf("th"), "ð" to listOf("th"),
        "ʃ" to listOf("sh", "ti", "ci", "ch", "ss", "s"),
        "ʒ" to listOf("si", "s", "ge", "g"),
        "tʃ" to listOf("tch", "ch", "tu", "t"),
        "dʒ" to listOf("dge", "ge", "gi", "j", "g", "d"),
        "ŋ" to listOf("ng", "n"),
        "p" to listOf("pp", "p"), "b" to listOf("bb", "b"),
        "t" to listOf("tt", "ed", "t"), "d" to listOf("dd", "ed", "d"),
        "k" to listOf("ck", "ch", "qu", "k", "c", "q", "x"),
        "g" to listOf("gg", "gu", "gh", "g"), "ɡ" to listOf("gg", "gu", "gh", "g"),
        "f" to listOf("ff", "ph", "gh", "f"), "v" to listOf("v", "f"),
        "s" to listOf("ss", "sc", "s", "c", "x"), "z" to listOf("zz", "z", "s", "x"),
        "h" to listOf("wh", "h"), "m" to listOf("mm", "mb", "m"),
        "n" to listOf("nn", "kn", "gn", "n"), "l" to listOf("ll", "l"),
        "r" to listOf("rr", "wr", "r"), "ɹ" to listOf("rr", "wr", "r"),
        "j" to listOf("y", "u", "i", "e"), "w" to listOf("wh", "w", "u", "o"),
        "æ" to listOf("a"),
        "ɑ" to listOf("ar", "al", "a", "o"),
        "ɒ" to listOf("o", "a"),
        "ʌ" to listOf("ou", "u", "o"),
        "ɪ" to listOf("i", "y", "e", "a"),
        "i" to listOf("ee", "ea", "ie", "ei", "ey", "e", "i", "y"),
        "ʊ" to listOf("oo", "ou", "u", "o"),
        "u" to listOf("oo", "ou", "ue", "ew", "ui", "u", "o"),
        "e" to listOf("ea", "e", "a"), "ɛ" to listOf("ea", "e", "a"),
        "ɜ" to listOf("ear", "er", "ir", "ur", "or"), "ɝ" to listOf("ear", "er", "ir", "ur", "or"),
        "ə" to listOf("ou", "er", "or", "ar", "a", "e", "o", "u", "i"),
        "ɚ" to listOf("er", "or", "ar", "ur"),
        "eɪ" to listOf("eigh", "ai", "ay", "ei", "ey", "ea", "a"),
        "aɪ" to listOf("igh", "ie", "ai", "uy", "i", "y"),
        "ɔɪ" to listOf("oi", "oy"),
        "əʊ" to listOf("ough", "oa", "ow", "oe", "o"), "oʊ" to listOf("ough", "oa", "ow", "oe", "o"),
        "o" to listOf("oa", "ow", "o"),
        "aʊ" to listOf("ou", "ow"),
        "ɪə" to listOf("ear", "eer", "ere", "ier", "eir"), "ɪr" to listOf("ear", "eer", "ere", "ier"),
        "eə" to listOf("air", "are", "ear", "ere"), "ɛr" to listOf("air", "are", "ear", "ere"),
        "ʊə" to listOf("oor", "our", "ure"), "ʊr" to listOf("oor", "our", "ure"),
        "ɔ" to listOf("augh", "ough", "aw", "au", "oor", "our", "ore", "or", "al", "oa", "o", "a"),
    )

    private val NOMI = mapOf(
        'a' to "a", 'b' to "bi", 'c' to "ci", 'd' to "di", 'e' to "e", 'f' to "effe", 'g' to "gi",
        'h' to "acca", 'i' to "i", 'j' to "i lunga", 'k' to "cappa", 'l' to "elle", 'm' to "emme",
        'n' to "enne", 'o' to "o", 'p' to "pi", 'q' to "cu", 'r' to "erre", 's' to "esse", 't' to "ti",
        'u' to "u", 'v' to "vu", 'w' to "doppia vu", 'x' to "ics", 'y' to "ipsilon", 'z' to "zeta",
    )

    /** Il fonema senza segni di lunghezza e accento, come lo vuole [CANDIDATI]. */
    private fun base(f: String) = f.filterNot { it == 'ː' || it == 'ˑ' || it == 'ˈ' || it == 'ˌ' || it == ':' }

    /** Dove sta, in [parola], il fonema [fonema] che è il numero [indice] su [totale]. */
    fun trova(parola: String, fonema: String, indice: Int, totale: Int): IntRange? {
        val lista = CANDIDATI[base(fonema)] ?: return null
        // Le sole lettere, con l'indice che avevano nella parola originale.
        val pos = parola.indices.filter { parola[it].isLetter() }
        if (pos.isEmpty() || totale <= 0) return null
        val lettere = pos.map { parola[it].lowercaseChar() }.joinToString("")
        val atteso = (indice + 0.5) / totale * lettere.length
        val tolleranza = maxOf(2.0, lettere.length / 3.0)
        var meglio: Pair<IntRange, Double>? = null
        lista.forEach { g ->
            var da = lettere.indexOf(g)
            while (da >= 0) {
                val distanza = Math.abs(da + g.length / 2.0 - atteso) - g.length * 0.01
                if (distanza <= tolleranza && (meglio == null || distanza < meglio!!.second))
                    meglio = (da until da + g.length) to distanza
                da = lettere.indexOf(g, da + 1)
            }
        }
        val r = meglio?.first ?: return null
        return pos[r.first]..pos[r.last]
    }

    /** «th» → «ti acca». */
    fun lettere(g: String): String = g.lowercase().mapNotNull { NOMI[it] }.joinToString(" ")
}
