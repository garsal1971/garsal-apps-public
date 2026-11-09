package com.garsal.pronuncia

/** Un tentativo passato, quel che serve al ripasso. */
data class Passato(val fraseId: String, val pronuncia: Double, val quando: Long)

/**
 * Un elemento della sessione. [recente] è la media degli ultimi due voti
 * (`null` = mai fatto); [suono] è il fonema debole per cui è stato scelto,
 * se è stato scelto per quello.
 */
data class Scelta(val frase: Frase, val recente: Double?, val suono: String? = null)

/**
 * Il ripasso (v1.33.0): quali elementi già fatti tornano in una sessione, e
 * quando. Sostituisce il «voto più basso mai preso» della v1.22.0, con cui una
 * frase sbagliata una volta tornava in cima per sempre, anche detta bene dieci
 * volte dopo.
 *
 * - conta **come va adesso**: la media degli ultimi due tentativi ([Stato.recente]);
 * - una frase riuscita non torna subito: dopo 1 riuscita di fila aspetta un
 *   giorno, dopo 2 tre, dopo 3 sette, da 4 in su quattordici ([INTERVALLI]).
 *   Un tentativo sotto soglia azzera la fila, e la frase è di nuovo da fare;
 * - metà sessione sono elementi **mai fatti**, e fra quelli fino a metà si
 *   pescano fra i più adatti ai suoni deboli (`ProfiloSuoni`).
 *
 * Se le scadute non bastano si completa con le mai fatte, e solo in mancanza
 * d'altro con quelle ancora in attesa (dalla più vecchia): la sessione resta di N.
 *
 * ⚠️ Nessuna tabella, come prima: tutto dallo storico, e cancellandolo si azzera.
 * ⚠️ «Riuscita» resta il solo voto complessivo sopra soglia, come nello storico:
 * i controlli severi del `Giudizio` cambiano con le impostazioni, e il ripasso
 * non deve cambiare all'indietro insieme a loro.
 * ⚠️ File senza Android di proposito: si prova coi test (`RipassoTest`).
 */
object Ripasso {
    /** Giorni d'attesa dopo 1, 2, 3, 4+ riuscite di fila. */
    val INTERVALLI = listOf(1, 3, 7, 14)
    private const val ORA = 60 * 60 * 1000L
    private const val GIORNO = 24 * ORA
    /** Chi si esercita la sera e poi il pomeriggio dopo ha fatto «un giorno»:
     *  l'intervallo si accorcia di qualche ora. */
    private const val TOLLERANZA = 6 * ORA

    data class Stato(val recente: Double, val diFila: Int, val ultimo: Long)

    /** [tentativi]: quelli di UNA frase, dal più recente; almeno uno. */
    fun stato(tentativi: List<Passato>, soglia: Double): Stato = Stato(
        recente = tentativi.take(2).map { it.pronuncia }.average(),
        diFila = tentativi.takeWhile { it.pronuncia >= soglia }.size,
        ultimo = tentativi.first().quando,
    )

    fun giorni(diFila: Int): Int = if (diFila <= 0) 0 else INTERVALLI[minOf(diFila, INTERVALLI.size) - 1]

    fun scaduta(s: Stato, ora: Long): Boolean {
        val g = giorni(s.diFila)
        return g == 0 || ora - s.ultimo >= g * GIORNO - TOLLERANZA
    }

    /**
     * Gli [n] elementi di una sessione, alternati nuova / già fatta.
     * [storia] in qualunque ordine; [deboli] i fonemi da curare, dal peggiore;
     * [contiene] dice se un elemento contiene un fonema; [mescola] è il caso
     * (i test ne passano uno fermo).
     */
    fun componi(
        catalogo: List<Frase>, storia: List<Passato>, n: Int, ora: Long, soglia: Double,
        deboli: List<String> = emptyList(),
        contiene: (Frase, String) -> Boolean = { _, _ -> false },
        mescola: (List<Frase>) -> List<Frase> = { it.shuffled() },
    ): List<Scelta> {
        if (n <= 0) return emptyList()
        val stati = storia.groupBy { it.fraseId }
            .mapValues { (_, l) -> stato(l.sortedByDescending { it.quando }, soglia) }
        val mai = mescola(catalogo.filter { it.id !in stati })
        val (scadute, inAttesa) = catalogo.filter { it.id in stati }.partition { scaduta(stati.getValue(it.id), ora) }

        var quanteScadute = minOf(n / 2, scadute.size)
        val quanteNuove = minOf(n - quanteScadute, mai.size)
        quanteScadute = minOf(n - quanteNuove, scadute.size)
        val quanteInAttesa = minOf(n - quanteNuove - quanteScadute, inAttesa.size)

        // Le nuove: fino a metà scelte per un suono debole, il resto a caso.
        val mirate = if (deboli.isEmpty()) emptyList() else mai
            .mapNotNull { f -> deboli.firstOrNull { d -> contiene(f, d) }?.let { Scelta(f, null, it) } }
            .take((quanteNuove + 1) / 2)
        val giaPrese = mirate.map { it.frase.id }.toSet()
        val nuove = mirate + mai.filter { it.id !in giaPrese }.take(quanteNuove - mirate.size).map { Scelta(it, null) }

        val vecchie = (scadute.sortedBy { stati.getValue(it.id).recente }.take(quanteScadute) +
            inAttesa.sortedBy { stati.getValue(it.id).ultimo }.take(quanteInAttesa))
            .map { Scelta(it, stati.getValue(it.id).recente) }

        // Alternate, una nuova e una già fatta: le difficili non tutte in fila.
        return (0 until maxOf(nuove.size, vecchie.size)).flatMap { i -> listOfNotNull(nuove.getOrNull(i), vecchie.getOrNull(i)) }
    }
}
