package com.garsal.appsphere.core

import java.time.LocalDate

/**
 * Date multiple con l'orario di ciascuna data — gemello delle funzioni `md*`
 * di `tasks.html`, `obiettivi.html` e `piante.html`: cambiali insieme.
 *
 * Un elemento di `multiple_dates` è `YYYY-MM-DD`, `YYYY-MM-DD HH:MM` oppure
 * `YYYY-MM-DD HH:MM-HH:MM` (la fine è solo informativa). Senza ora vale
 * l'orario generale, come prima. La prossima occorrenza la calcolano le RPC
 * (`md_prossima`), non questo file.
 */
object DateMultiple {
    private val MESI = mapOf(
        "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
        "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6, "lug" to 7, "ago" to 8,
        "set" to 9, "sett" to 9, "ott" to 10, "nov" to 11, "dic" to 12,
    )
    private val GIORNI = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")
    private val MESI_BREVI = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")

    private val RE_ORA = Regex("""^\d{4}-\d{2}-\d{2}[ T](\d{2}:\d{2})""")
    private val RE_FINE = Regex("""^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}-(\d{2}:\d{2})""")

    fun giorno(v: String): String = v.trim().take(10)
    fun ora(v: String): String = RE_ORA.find(v.trim())?.groupValues?.get(1).orEmpty()
    fun fine(v: String): String = RE_FINE.find(v.trim())?.groupValues?.get(1).orEmpty()
    fun componi(giorno: String, ora: String, fine: String = ""): String =
        giorno + if (ora.isNotBlank()) " $ora" + (if (fine.isNotBlank()) "-$fine" else "") else ""

    /** `Lun 7 dic 2026 · 11:00–12:00` */
    fun etichetta(v: String): String {
        val d = runCatching { LocalDate.parse(giorno(v)) }.getOrNull() ?: return giorno(v)
        val o = ora(v)
        val f = fine(v)
        return "${GIORNI[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MESI_BREVI[d.monthValue - 1]} ${d.year}" +
            if (o.isNotBlank()) " · $o" + (if (f.isNotBlank()) "–$f" else "") else ""
    }

    private fun hhmm(h: String, m: String): String? {
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return "%02d:%02d".format(hh, mm)
    }

    class Lettura(val voci: List<String>, val errori: List<String>)

    /**
     * Una data per riga: «Lunedì 7 dicembre 2026: 11:00 - 12:00», «07/12/2026 11:00»,
     * «2026-12-07 11:00-12:00». Le righe che non si capiscono tornano in `errori`.
     */
    fun leggiTesto(testo: String): Lettura {
        val voci = mutableListOf<String>()
        val errori = mutableListOf<String>()
        for (grezza in testo.lines()) {
            val riga = grezza.trimStart(' ', '\t', '•', '*', '·', '-', '–', '—').trim()
            if (riga.isEmpty()) continue
            val low = riga.lowercase()
            var a = 0; var me = 0; var gi = 0; var resto = ""
            val iso = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(.*)$""").find(low)
            val num = Regex("""(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})(.*)$""").find(low)
            val let = Regex("""(\d{1,2})\s+([a-zàèéìòù]+)\.?\s+(\d{4})(.*)$""").find(low)
            when {
                iso != null -> iso.groupValues.let { a = it[1].toInt(); me = it[2].toInt(); gi = it[3].toInt(); resto = it[4] }
                num != null -> num.groupValues.let { a = it[3].toInt(); me = it[2].toInt(); gi = it[1].toInt(); resto = it[4] }
                let != null && MESI[let.groupValues[2]] != null ->
                    let.groupValues.let { a = it[3].toInt(); me = MESI.getValue(it[2]); gi = it[1].toInt(); resto = it[4] }
                else -> { errori += riga; continue }
            }
            val d = runCatching { LocalDate.of(a, me, gi) }.getOrNull()
            if (d == null) { errori += riga; continue }
            val t = Regex("""(\d{1,2})[:.](\d{2})(?:\s*(?:-|–|—|alle|a)\s*(\d{1,2})[:.](\d{2}))?""").find(resto)
            val o = t?.let { hhmm(it.groupValues[1], it.groupValues[2]) } ?: ""
            val f = t?.takeIf { it.groupValues[3].isNotEmpty() }?.let { hhmm(it.groupValues[3], it.groupValues[4]) } ?: ""
            if (t != null && (hhmm(t.groupValues[1], t.groupValues[2]) == null ||
                    (t.groupValues[3].isNotEmpty() && f.isEmpty()))) { errori += riga; continue }
            voci += componi(d.toString(), o, f)
        }
        return Lettura(voci, errori)
    }

    /** Unisce senza doppioni di giorno: una data già in elenco prende l'orario nuovo. */
    fun unisci(elenco: List<String>, nuove: List<String>): List<String> {
        val perGiorno = LinkedHashMap<String, String>()
        elenco.forEach { perGiorno[giorno(it)] = it }
        nuove.forEach { perGiorno[giorno(it)] = it }
        return perGiorno.values.sorted()
    }

    /** L'orario da usare per un elemento: il suo, o quello generale. */
    fun oraOGenerale(v: String, generale: String): String = ora(v).ifBlank { generale }
}
