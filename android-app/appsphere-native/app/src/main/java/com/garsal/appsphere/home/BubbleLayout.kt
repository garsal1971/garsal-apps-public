package com.garsal.appsphere.home

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Posizionamento delle bolle, portato da `index.html` (`sizeOf`,
 * `initialPlace`, `settlePositions`, `resolveCollisions`, `pushFromPanel`).
 *
 * Il pannello del totale galleggia sopra la stessa area delle bolle, in basso a
 * sinistra: i cerchi devono scansarlo, o coprirebbero l'unica cifra che il
 * riquadro esiste per mostrare. È [Pannello], e vale per tutti — anche per la
 * bolla che si sta trascinando, come nel web, dove il pannello ha «l'ultima
 * parola» sia nell'assestamento sia durante il trascinamento.
 */
object BubbleLayout {

    private const val PAD = 12f
    private const val GAP = 8f

    /** Una bolla in movimento: raggio fisso, centro che cambia. */
    data class Nodo(
        val indice: Int,
        var x: Float,
        var y: Float,
        val r: Float,
    )

    /** Il rettangolo occupato dal riquadro del totale, in pixel. */
    data class Pannello(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * Diametro proporzionale al punteggio: `area = (score/maxScore) * MAX_AREA`,
     * con un minimo di 6 cm² perché la bolla resti toccabile anche a punteggio
     * zero. Identico al web, densità inclusa.
     *
     * ⚠️ **I «6 cm²» sono quelli del web, e il web non li misura col righello.**
     * `sizeOf()` lavora in CSS px e li converte a 96 dpi — ma su Android un CSS
     * px *è* un dp, e un dp è 1/160 di pollice: quei 6 cm² valgono in realtà
     * ~2,1 cm² veri, cioè un pavimento di **104,5 dp** di diametro. Prendendo
     * `160f` — la densità vera — il pavimento diventava di 6 cm² fisici, cioè
     * **174 dp**: un terzo più largo, e su uno schermo da 393 dp ci finivano
     * sopra quasi tutte le bolle. Il risultato era una home di cerchi tutti
     * uguali, in cui 10 punti e 5952 differivano del 26 % invece che del 110 %,
     * e la proporzionalità — che è tutto il senso delle bolle — non si vedeva
     * più. Il fattore giusto è quindi quello del web, `96f`, non la densità
     * dello schermo: qui si stanno convertendo dp, non centimetri.
     */
    fun diametro(punteggio: Int, punteggioMax: Int, densita: Float): Float {
        val pxPerCm = densita * 96f / 2.54f       // il "cm" del web: 96 dp per pollice
        val areaMin = 6f * pxPerCm * pxPerCm
        val diametroMax = 220f * densita
        val areaMax = PI.toFloat() * (diametroMax / 2f) * (diametroMax / 2f)
        val max = max(punteggioMax, 1)
        val area = max(areaMin, (punteggio.toFloat() / max) * areaMax)
        return 2f * sqrt(area / PI.toFloat())
    }

    /**
     * Prima collocazione: si parte stretti al centro e si allarga il raggio di
     * ricerca finché non si trova un posto che non tocca nessuno.
     */
    fun collocazioneIniziale(
        r: Float,
        gia: List<Nodo>,
        w: Float,
        h: Float,
        random: Random = Random.Default,
    ): Pair<Float, Float> {
        val cx = w / 2f
        val cy = h / 2f
        repeat(200) { tentativo ->
            val ampiezza = r + tentativo * (r * 0.5f)
            repeat(30) {
                val angolo = random.nextFloat() * 2f * PI.toFloat()
                val distanza = random.nextFloat() * ampiezza
                val x = (cx + cos(angolo) * distanza).coerceIn(r + PAD, max(r + PAD, w - r - PAD))
                val y = (cy + sin(angolo) * distanza).coerceIn(r + PAD, max(r + PAD, h - r - PAD))
                val libero = gia.all { hypot(x - it.x, y - it.y) >= r + it.r + GAP }
                if (libero) return x to y
            }
        }
        return cx to cy
    }

    /**
     * Assesta le posizioni dopo il primo disegno: si separano le coppie che si
     * toccano finché nessuno si muove più (o dopo 300 giri).
     */
    fun assesta(nodi: List<Nodo>, w: Float, h: Float, pannello: Pannello? = null) {
        repeat(300) {
            val prima = nodi.map { it.x to it.y }
            separaCoppie(nodi, fissato = null)
            // Prima il bordo dello schermo, poi il pannello: l'ultima parola è
            // sua, altrimenti il limite lo rimetterebbe sotto il riquadro.
            nodi.forEach { limita(it, w, h); scansaPannello(it, pannello) }
            val spostamento = nodi.mapIndexed { i, n ->
                abs(n.x - prima[i].first) + abs(n.y - prima[i].second)
            }.sum()
            if (spostamento < 0.01f) return
        }
    }

    /**
     * Collisioni durante il trascinamento, in due fasi alternate:
     * prima si spingono via gli altri tenendo fermo quello in mano, poi si
     * misura quanto gli altri respingono lui e lo si sposta di conseguenza.
     *
     * La convergenza si misura sullo spostamento netto rispetto allo scatto di
     * inizio giro, non sulla singola spinta: una bolla schiacciata fra quella
     * trascinata e il bordo riceve spinte non nulle che si annullano fra loro,
     * e senza questo accorgimento il ciclo non finirebbe mai.
     */
    fun risolviTrascinamento(
        nodi: List<Nodo>,
        trascinato: Int,
        w: Float,
        h: Float,
        pannello: Pannello? = null,
    ) {
        val dn = nodi.firstOrNull { it.indice == trascinato } ?: return

        // Anche la bolla in mano scansa il pannello, come nel web: il dito può
        // portarla lì sopra, e il riquadro del totale resterebbe coperto.
        scansaPannello(dn, pannello)

        repeat(8) {
            // ── Fase 1: si spostano gli altri, il trascinato sta fermo ──
            // L'etichetta serve a uscire davvero dal ciclo appena si è
            // assestato: un `return@repeat` salterebbe solo il giro corrente.
            run fase1@{
                repeat(60) {
                    val prima = nodi.map { it.x to it.y }
                    separaCoppie(nodi, fissato = trascinato)
                    nodi.forEach {
                        if (it.indice != trascinato) {
                            limita(it, w, h)
                            scansaPannello(it, pannello)
                        }
                    }
                    val spostamento = nodi.mapIndexed { i, n ->
                        if (n.indice == trascinato) 0f
                        else abs(n.x - prima[i].first) + abs(n.y - prima[i].second)
                    }.sum()
                    if (spostamento < 0.01f) return@fase1
                }
            }

            // ── Fase 2: tutte le spinte sul trascinato, sommate insieme ──
            // Sommarle e applicarle in blocco evita che l'ordine con cui si
            // scorrono le bolle sbilanci la direzione.
            var spintaX = 0f
            var spintaY = 0f
            nodi.forEach { n ->
                if (n.indice == trascinato) return@forEach
                val minima = dn.r + n.r + GAP
                val dx = n.x - dn.x
                val dy = n.y - dn.y
                val d = hypot(dx, dy).takeIf { it > 0f } ?: 0.01f
                if (d < minima) {
                    val sovrapposizione = minima - d
                    spintaX -= (dx / d) * sovrapposizione
                    spintaY -= (dy / d) * sovrapposizione
                }
            }
            if (hypot(spintaX, spintaY) < 0.01f) return   // è libero: finito

            val primaX = dn.x
            val primaY = dn.y
            dn.x += spintaX
            dn.y += spintaY
            limita(dn, w, h)
            scansaPannello(dn, pannello)
            if (abs(dn.x - primaX) + abs(dn.y - primaY) < 0.01f) return
        }
    }

    /**
     * Un giro di separazione su tutte le coppie che si sovrappongono.
     * Se una delle due è quella in mano, si sposta solo l'altra e per l'intera
     * sovrapposizione; altrimenti si divide a metà.
     */
    private fun separaCoppie(nodi: List<Nodo>, fissato: Int?) {
        for (a in nodi.indices) {
            for (b in a + 1 until nodi.size) {
                val na = nodi[a]
                val nb = nodi[b]
                val minima = na.r + nb.r + GAP
                val dx = nb.x - na.x
                val dy = nb.y - na.y
                val d = hypot(dx, dy).takeIf { it > 0f } ?: 0.01f
                if (d >= minima) continue
                val sovrapposizione = minima - d
                val nx = dx / d
                val ny = dy / d
                when (fissato) {
                    na.indice -> { nb.x += nx * sovrapposizione; nb.y += ny * sovrapposizione }
                    nb.indice -> { na.x -= nx * sovrapposizione; na.y -= ny * sovrapposizione }
                    else -> {
                        na.x -= nx * sovrapposizione * 0.5f
                        na.y -= ny * sovrapposizione * 0.5f
                        nb.x += nx * sovrapposizione * 0.5f
                        nb.y += ny * sovrapposizione * 0.5f
                    }
                }
            }
        }
    }

    /**
     * Sposta la bolla fuori dal riquadro del totale, se ci finisce sopra —
     * `pushFromPanel` del web, caso limite compreso.
     *
     * Il caso normale è spingere il centro lontano dal punto più vicino del
     * rettangolo. Quando invece il centro è **dentro** il rettangolo quella
     * direzione non esiste (distanza zero): si esce dal lato con la
     * penetrazione minore, che è lo spostamento più corto.
     */
    private fun scansaPannello(n: Nodo, p: Pannello?) {
        if (p == null) return
        val vicinoX = n.x.coerceIn(p.x, max(p.x, p.x + p.w))
        val vicinoY = n.y.coerceIn(p.y, max(p.y, p.y + p.h))
        val dx = n.x - vicinoX
        val dy = n.y - vicinoY
        val distanza = hypot(dx, dy)
        val minima = n.r + GAP
        if (distanza >= minima) return

        if (distanza > 0.001f) {
            val fattore = (minima - distanza) / distanza
            n.x += dx * fattore
            n.y += dy * fattore
            return
        }

        val sinistra = n.x - p.x
        val destra = (p.x + p.w) - n.x
        val sopra = n.y - p.y
        val sotto = (p.y + p.h) - n.y
        when (min(min(sinistra, destra), min(sopra, sotto))) {
            sinistra -> n.x = p.x - n.r - GAP
            destra -> n.x = p.x + p.w + n.r + GAP
            sopra -> n.y = p.y - n.r - GAP
            else -> n.y = p.y + p.h + n.r + GAP
        }
    }

    /** Tiene il centro dentro lo schermo, bordo e margine compresi. */
    private fun limita(n: Nodo, w: Float, h: Float) {
        val minX = n.r + PAD
        val maxX = max(minX, w - n.r - PAD)
        val minY = n.r + PAD
        val maxY = max(minY, h - n.r - PAD)
        n.x = min(maxX, max(minX, n.x))
        n.y = min(maxY, max(minY, n.y))
    }
}
