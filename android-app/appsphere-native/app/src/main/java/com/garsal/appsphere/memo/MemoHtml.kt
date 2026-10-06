package com.garsal.appsphere.memo

/**
 * Il contenuto delle schede è **HTML**, scritto sul web da un `contenteditable`
 * con `document.execCommand`. Qui non c'è un contenteditable, e riscriverne uno
 * in Compose vorrebbe dire tenere a mano gli stili mentre il testo cambia sotto
 * le dita — la parte in cui un editor sbaglia in silenzio.
 *
 * La scelta è un giro in due tempi: **l'HTML si converte in testo con
 * marcatori** (`**grassetto**`, `*corsivo*`, `__sottolineato__`, `~~barrato~~`,
 * `#` per i titoli, `- ` e `1. ` per gli elenchi, `---` per la linea), si
 * modifica quello, e al salvataggio si **ritorna in HTML**. La barra
 * dell'editor non fa altro che infilare quei marcatori attorno a quello che si
 * è selezionato, e l'anteprima mostra il risultato vero.
 *
 * ⚠️ **Il giro deve chiudersi**: quello che il web sa scrivere — le voci della
 * sua barra sono grassetto, corsivo, sottolineato, barrato, H1, H2, elenchi
 * puntati e numerati, link e linea separatrice — ha un marcatore qui, e
 * ritorna HTML uguale. Aggiungendo una voce alla barra del web va aggiunta
 * anche qui, o modificando una scheda dal telefono quella formattazione si
 * perde senza che nessuno se ne accorga. Le immagini incorporate nel testo
 * (`<img>`) sono l'unico caso senza pulsante: hanno lo stesso un marcatore
 * (`![](url)`) proprio per non essere buttate via da un giro di modifica.
 *
 * Un tag che non conosciamo non fa saltare niente: si scarta il tag e si tiene
 * il testo che c'è dentro, come fa `stripHtml()` nel web.
 */
object MemoHtml {

    private val TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9]*)([^>]*)>")
    private val SRC = Regex("""src\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val VUOTE = Regex("\n{3,}")
    private val NUMERATA = Regex("""^\d+[.)]\s+""")

    /** HTML → testo con marcatori, quello che si modifica nell'editor. */
    fun aMarcatori(html: String): String = converti(html, marcatori = true)

    /** HTML → testo nudo, per l'anteprima nella scheda e per la ricerca. */
    fun aTestoSemplice(html: String): String = converti(html, marcatori = false)

    // ── HTML → testo ────────────────────────────────────────────────────

    private fun converti(html: String, marcatori: Boolean): String {
        if (html.isBlank()) return ""
        val sb = StringBuilder()
        // Il tipo di elenco aperto e, per i numerati, a che numero è arrivato.
        val elenchi = ArrayDeque<String>()
        val numeri = ArrayDeque<Int>()
        val link = ArrayDeque<String>()
        var ultimo = 0

        fun aCapo() {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        }

        for (m in TAG.findAll(html)) {
            sb.append(decodifica(html.substring(ultimo, m.range.first)))
            ultimo = m.range.last + 1

            val chiusura = m.groupValues[1] == "/"
            val nome = m.groupValues[2].lowercase()
            val attributi = m.groupValues[3]

            when (nome) {
                "br" -> sb.append('\n')

                "p", "div", "blockquote" -> if (chiusura) sb.append('\n') else aCapo()

                "h1", "h2", "h3", "h4", "h5", "h6" -> if (chiusura) {
                    sb.append('\n')
                } else {
                    aCapo()
                    // Oltre l'H3 non si scende: il web ha solo H1 e H2, e un
                    // H4 arrivato da altrove diventa il titolo più piccolo che
                    // sappiamo scrivere invece di sparire.
                    if (marcatori) {
                        val livello = minOf(nome.substring(1).toInt(), 3)
                        sb.append("#".repeat(livello)).append(' ')
                    }
                }

                "ul", "ol" -> if (chiusura) {
                    elenchi.removeLastOrNull()
                    numeri.removeLastOrNull()
                    sb.append('\n')
                } else {
                    aCapo()
                    elenchi.addLast(nome)
                    numeri.addLast(0)
                }

                "li" -> if (chiusura) {
                    sb.append('\n')
                } else {
                    aCapo()
                    val numerato = elenchi.lastOrNull() == "ol"
                    if (numerato) {
                        val n = (numeri.removeLastOrNull() ?: 0) + 1
                        numeri.addLast(n)
                        sb.append("$n. ")
                    } else {
                        sb.append(if (marcatori) "- " else "• ")
                    }
                }

                "b", "strong" -> if (marcatori) sb.append("**")
                "i", "em" -> if (marcatori) sb.append("*")
                "u" -> if (marcatori) sb.append("__")
                "s", "strike", "del" -> if (marcatori) sb.append("~~")

                "hr" -> {
                    aCapo()
                    sb.append(if (marcatori) "---" else "———").append('\n')
                }

                "a" -> if (marcatori) {
                    if (chiusura) {
                        sb.append("](").append(link.removeLastOrNull().orEmpty()).append(')')
                    } else {
                        link.addLast(HREF.find(attributi)?.groupValues?.get(1).orEmpty())
                        sb.append('[')
                    }
                }

                "img" -> if (!chiusura) {
                    val src = SRC.find(attributi)?.groupValues?.get(1).orEmpty()
                    if (marcatori) sb.append("![](").append(src).append(')')
                    else sb.append("🖼")
                }

                // Ogni altro tag si scarta e si tiene quello che contiene.
                else -> Unit
            }
        }
        sb.append(decodifica(html.substring(ultimo)))

        // Lo spazio unificatore che arriva da `&#160;` diventa uno spazio
        // normale: a schermo è identico, ma nella ricerca no — cercando
        // «via roma» una scheda che dentro ha quel carattere non si troverebbe.
        return VUOTE.replace(sb.toString().replace("\u00A0", " "), "\n\n").trim()
    }

    private fun decodifica(grezzo: String): String {
        if ('&' !in grezzo) return grezzo
        var s = grezzo
        // `&amp;` per ultima, o `&amp;lt;` diventerebbe `<`.
        s = s.replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return s.replace("&amp;", "&")
    }

    // ── testo con marcatori → HTML ──────────────────────────────────────

    /**
     * Riga per riga, come le scrive `execCommand`: `<p>` per i paragrafi,
     * `<h1>`/`<h2>`/`<h3>` per i titoli, `<ul>`/`<ol>` per gli elenchi (righe
     * consecutive dello stesso tipo stanno nello stesso elenco), `<hr>` per la
     * linea. Una riga vuota chiude l'elenco e non produce niente: nell'HTML la
     * separazione la fanno già i blocchi.
     */
    fun aHtml(marcato: String): String {
        val sb = StringBuilder()
        var elenco: String? = null

        fun chiudiElenco() {
            elenco?.let { sb.append("</").append(it).append(">") }
            elenco = null
        }

        fun apriElenco(tipo: String) {
            if (elenco != tipo) {
                chiudiElenco()
                sb.append("<").append(tipo).append(">")
                elenco = tipo
            }
        }

        marcato.replace("\r\n", "\n").split("\n").forEach { riga ->
            val r = riga.trim()
            when {
                r.isEmpty() -> chiudiElenco()

                r == "---" || r == "***" || r == "___" -> {
                    chiudiElenco()
                    sb.append("<hr>")
                }

                r.startsWith("### ") -> {
                    chiudiElenco()
                    sb.append("<h3>").append(inline(r.removePrefix("### "))).append("</h3>")
                }

                r.startsWith("## ") -> {
                    chiudiElenco()
                    sb.append("<h2>").append(inline(r.removePrefix("## "))).append("</h2>")
                }

                r.startsWith("# ") -> {
                    chiudiElenco()
                    sb.append("<h1>").append(inline(r.removePrefix("# "))).append("</h1>")
                }

                r.startsWith("- ") || r.startsWith("* ") -> {
                    apriElenco("ul")
                    sb.append("<li>").append(inline(r.drop(2))).append("</li>")
                }

                NUMERATA.containsMatchIn(r) -> {
                    apriElenco("ol")
                    sb.append("<li>").append(inline(NUMERATA.replace(r, ""))).append("</li>")
                }

                else -> {
                    chiudiElenco()
                    sb.append("<p>").append(inline(r)).append("</p>")
                }
            }
        }
        chiudiElenco()
        return sb.toString()
    }

    /**
     * I marcatori dentro una riga.
     *
     * L'ordine non è casuale: prima si mette al sicuro il testo (`escape`),
     * così quello che l'utente ha scritto non diventa mai un tag; poi immagini
     * e link, che hanno una parentesi con dentro un URL; poi il doppio
     * asterisco prima del singolo, altrimenti `**grassetto**` verrebbe letto
     * come due corsivi vuoti.
     */
    private fun inline(testo: String): String {
        var s = escape(testo)
        s = Regex("""!\[]\((.*?)\)""").replace(s) { "<img src=\"${it.groupValues[1]}\">" }
        s = Regex("""\[(.+?)]\((.*?)\)""").replace(s) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        s = Regex("""\*\*(.+?)\*\*""").replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""").replace(s) {
            "<i>${it.groupValues[1]}</i>"
        }
        s = Regex("""__(.+?)__""").replace(s) { "<u>${it.groupValues[1]}</u>" }
        s = Regex("""~~(.+?)~~""").replace(s) { "<s>${it.groupValues[1]}</s>" }
        return s
    }

    private fun escape(testo: String) = testo
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
