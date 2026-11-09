package com.garsal.pronuncia

/** Un suono e com'è andato finora: quante volte è stato valutato, la media,
 *  quante volte è finito sotto [ProfiloSuoni.SOGLIA_SBAGLIATO] e in quali parole. */
data class StatSuono(val fonema: String, val volte: Int, val media: Double, val sbagliate: Int, val esempi: List<String>)

/**
 * Il profilo dei suoni (v1.33.0): quali fonemi vengono peggio, ricavato dai
 * voti che Azure dà a ogni fonema di ogni parola detta.
 *
 * Fino alla v1.32.0 quei voti si usavano per il solo tentativo in corso (il
 * «suono peggiore» del `Giudizio`) e poi si buttavano: lo storico teneva parole
 * e frasi. Da qui si salvano accanto alla parola (`ParolaTentativo.fonemi`, e
 * la chiave `f` nel `dettaglio` di `pr_tentativi`), così si può dire «il tuo
 * /θ/ sta a 52 di media» e scegliere le parole nuove che lo contengono.
 *
 * ⚠️ Nessuna tabella di contatori: come «📚 Le mie parole», tutto si ricava
 * ogni volta dalle righe dello storico. Cancellando lo storico si azzera.
 * ⚠️ I tentativi di prima della v1.33.0 non hanno fonemi: il profilo si
 * riempie da qui in avanti.
 */
object ProfiloSuoni {
    /** Sotto queste rilevazioni un suono non entra fra i deboli: tre voti bassi
     *  su una parola sola non sono ancora un'abitudine. */
    const val MIN_VOLTE = 5
    /** La media sotto cui un suono è «da curare». */
    const val SOGLIA_DEBOLE = 75.0
    /** Il voto sotto cui una singola rilevazione conta come sbagliata. */
    const val SOGLIA_SBAGLIATO = 60.0
    /** Quanti suoni deboli guidano la scelta della sessione. */
    const val QUANTI = 3
    /** Quante parole dello storico si leggono, dalle più recenti: il profilo
     *  deve seguire come si pronuncia adesso, non un anno fa. */
    const val RIGHE = 3000

    /**
     * I fonemi di una parola in una riga di testo: «θ=45 ɪ=80». ⚠️ Spazio e
     * «=» perché in un fonema IPA non ci sono mai; i due punti sì (Azure a
     * volte scrive la lunghezza così). `null` = niente da salvare.
     */
    fun codifica(fonemi: List<Pair<String, Double>>): String? = fonemi
        .filter { (f, v) -> f.isNotBlank() && ' ' !in f && '=' !in f && !v.isNaN() }
        .joinToString(" ") { (f, v) -> "$f=${Math.round(v)}" }
        .ifEmpty { null }

    fun decodifica(s: String): List<Pair<String, Int>> = s.split(' ').mapNotNull { pezzo ->
        val i = pezzo.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val v = pezzo.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
        pezzo.substring(0, i) to v
    }

    /**
     * Il profilo, dal suono peggiore in su. [righe]: parola → fonemi codificati.
     * I fonemi si contano senza segni di lunghezza e accento (`Grafema.base`):
     * /iː/ e /i/ sono lo stesso suono da curare.
     */
    fun calcola(righe: List<Pair<String, String>>): List<StatSuono> {
        val per = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        righe.forEach { (parola, cod) ->
            decodifica(cod).forEach { (f, v) ->
                val b = Grafema.base(f)
                if (b.isNotEmpty()) per.getOrPut(b) { mutableListOf() }.add(v to parola)
            }
        }
        return per.map { (f, l) ->
            val male = l.filter { it.first < SOGLIA_SBAGLIATO }
            StatSuono(f, l.size, l.map { it.first }.average(), male.size, male.map { it.second }.distinct().take(3))
        }.sortedWith(compareBy<StatSuono> { it.media }.thenByDescending { it.volte })
    }

    /** I suoni da curare: valutati abbastanza volte e con la media bassa. */
    fun deboli(profilo: List<StatSuono>, quanti: Int = QUANTI): List<StatSuono> =
        profilo.filter { it.volte >= MIN_VOLTE && it.media < SOGLIA_DEBOLE }.take(quanti)

    /** Per ogni suono, le parole in cui Azure lo ha sentito: lì c'è di sicuro. */
    fun paroleCon(righe: List<Pair<String, String>>): Map<String, Set<String>> {
        val per = HashMap<String, MutableSet<String>>()
        righe.forEach { (parola, cod) ->
            decodifica(cod).forEach { (f, _) -> per.getOrPut(Grafema.base(f)) { mutableSetOf() }.add(parola) }
        }
        return per
    }

    /**
     * Le grafie che tradiscono un suono quasi senza sbagliare («th» → /θ/).
     * ⚠️ Di proposito poche: servono a pescare parole MAI dette che contengano
     * il suono, e una grafia ambigua (la «a», la «o») porterebbe parole a caso.
     * Per le vocali brevi non c'è niente: lì valgono le sole parole già sentite
     * ([paroleCon]). «^» = solo a inizio parola.
     */
    private val SPIE: Map<String, List<String>> = mapOf(
        "θ" to listOf("th"), "ð" to listOf("th"),
        "ʃ" to listOf("sh"), "tʃ" to listOf("ch"), "dʒ" to listOf("j", "dge"),
        "ŋ" to listOf("ng"), "h" to listOf("^h"), "w" to listOf("^w"),
        "v" to listOf("v"), "z" to listOf("z"), "r" to listOf("r"), "ɹ" to listOf("r"),
        "ɜ" to listOf("ir", "ur"), "aʊ" to listOf("ou", "ow"), "ɔɪ" to listOf("oi", "oy"),
        "eɪ" to listOf("ai", "ay"), "i" to listOf("ee", "ea"), "u" to listOf("oo"),
        "ɑ" to listOf("ar"), "ɔ" to listOf("aw", "au", "or"), "əʊ" to listOf("oa"), "oʊ" to listOf("oa"),
    )

    /** [testo] (una parola o una frase) contiene con buona probabilità [fonema]:
     *  una sua parola è fra quelle [viste] con quel suono, o ne ha la grafia. */
    fun contiene(testo: String, fonema: String, viste: Set<String>): Boolean {
        val spie = SPIE[fonema].orEmpty()
        return testo.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }.any { p ->
            p in viste || spie.any { s -> if (s.startsWith("^")) p.startsWith(s.substring(1)) else s in p }
        }
    }

    /** Le grafie tipiche di [fonema], per dirle a schermo («th»); vuoto = non si sa. */
    fun grafie(fonema: String): List<String> = SPIE[fonema].orEmpty().map { it.removePrefix("^") }
}
