package com.garsal.appsphere.peso

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.RigaScorrevole
import java.time.LocalDate

/**
 * L'andamento del peso, disegnato a mano su un `Canvas` scorrevole.
 *
 * Nel web è Chart.js con zoom e scorrimento; qui è un disegno, ma con lo
 * stesso scorrimento — comincia dall'**inizio dell'obiettivo** e arriva alla
 * sua **fine**, futuro compreso, perché la spezzata del target racconta tutto
 * il piano e non solo il pezzo già passato. All'apertura è centrato su oggi
 * con un paio di settimane prima e dopo in vista; da lì si scorre col dito.
 *
 * ⚠️ **Di ogni giornata si disegna la PRIMA pesata e la media mobile**
 * (APK 1.0.118, gemello di `disegnaGraficoPeso` del web v4.13.6): il valore
 * del giorno è la prima pesata — la stessa della tabella e di `ps_daily_ema`
 * — con un **pallino pieno** su ogni pesata vera e una linea sottile; la
 * **media mobile** è la linea viola spessa, con un **pallino vuoto** nei
 * giorni stimati col trend. Minimo e massimo della giornata, e il taglio
 * verde/rosso sul target, non ci sono più: con la bilancia che balla era la
 * media a dire come si sta andando.
 *
 * ⚠️ **Il disegno prende tutta l'altezza che avanza** (`weight(1f)`) invece di
 * un'altezza fissa, e sotto non c'è più niente scritto: le due righe di
 * spiegazione che stavano lì raccontavano quello che si vede — che si scorre,
 * che la linea piena è il peso e la tratteggiata il target — e per dirlo si
 * prendevano un terzo dello schermo del telefono. Quel che serve davvero
 * (legenda, minimo e massimo del periodo) sta ora in **una riga sola sopra**
 * il grafico, che **scorre di lato** invece di andare a capo: coi caratteri
 * di sistema grandi tre voci di legenda e due numeri non ci stanno, ed è la
 * stessa regola delle righe di pulsanti.
 *
 * ⚠️ **Le misure del disegno sono in `dp`, mai in pixel grezzi.** Prima
 * spessori, margini e la fascia delle date erano numeri in px (`4f`, `34f`):
 * su uno schermo denso valgono un terzo di quello che sembrano, e infatti le
 * date sotto l'asse venivano tagliate a metà — la fascia era alta 34 px, cioè
 * meno dell'altezza del testo che ci andava scritto dentro.
 */
@Composable
fun VistaGrafico(stato: PesoState) {
    val obiettivo = stato.obiettivo
    val inizio = obiettivo?.let { PesoRegole.giornoDa(it.inizio) }
    val fine = obiettivo?.let { PesoRegole.giornoDa(it.fine) }

    if (obiettivo == null || inizio == null || fine == null || !fine.isAfter(inizio)) {
        Text(
            "Serve un obiettivo con una curva di traguardi per disegnare il grafico.",
            color = Palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        )
        return
    }

    val traguardi = obiettivo.traguardi
    val magra = stato.massaMagra
    val oggiG = LocalDate.now()

    val targetTotale = { g: LocalDate -> PesoRegole.targetInterpolato(traguardi, g.toString()) }
    // La media mobile di una serie, dall'inizio a oggi (`ps_daily_ema`).
    fun media(valore: (MediaGiorno) -> Double?, stimato: (MediaGiorno) -> Boolean): List<PuntoMedia> =
        stato.medie.values.mapNotNull { m ->
            val g = PesoRegole.giornoDa(m.giorno) ?: return@mapNotNull null
            if (g.isBefore(inizio) || g.isAfter(oggiG)) return@mapNotNull null
            val v = valore(m) ?: return@mapNotNull null
            PuntoMedia(g, v, stimato(m))
        }.sortedBy { it.giorno }

    // ⚠️ Sulla massa grassa i grafici sono TRE, come `updateWeightChart` del web
    // (v4.10.0): ⚖️ peso totale coi traguardi, 🧈 massa grassa col grasso
    // previsto — il target dei punti — e 📊 % di grasso con la % prevista dal
    // piano ((W − magra) / W). Stanno uno sotto l'altro e **scorrono insieme**
    // ([ScalaGrafico]), così le date restano in colonna, e il pizzico li
    // allarga tutti. Senza massa grassa resta il grafico unico di sempre.
    val mediaPeso = media({ it.emaPeso }, { it.pesoStimato })
    val serie: List<Serie> = if (magra == null) {
        listOf(Serie(null, primePesate(stato.pesate, inizio) { it.peso }, mediaPeso,
            spezzata(inizio, fine, traguardi) { it }, targetTotale))
    } else {
        val aGrasso = { t: Double -> maxOf(t - magra, 0.0) }
        val aPerc = { t: Double -> if (t > 0.0) maxOf(t - magra, 0.0) / t * 100.0 else 0.0 }
        listOf(
            Serie(
                "⚖️ Peso totale (kg)",
                primePesate(stato.pesate, inizio) { it.peso },
                mediaPeso,
                spezzata(inizio, fine, traguardi) { it },
                targetTotale,
            ),
            Serie(
                "🧈 Massa grassa (kg)",
                primePesate(stato.vista, inizio) { it.peso },
                media({ it.emaGrassoKg }, { it.grassoStimato }),
                spezzata(inizio, fine, traguardi, aGrasso),
                { g -> targetTotale(g)?.let(aGrasso) },
            ),
            Serie(
                "📊 Grasso (%)",
                primePesate(stato.vista, inizio) { it.grasso },
                media({ it.emaGrassoPct }, { it.grassoStimato }),
                // La % prevista non è lineare fra due traguardi: un punto al giorno.
                spezzataGiornaliera(inizio, fine, traguardi, aPerc),
                { g -> targetTotale(g)?.let(aPerc) },
            ),
        )
    }

    if (serie.none { it.punti.size >= 2 || it.media.size >= 2 }) {
        Text(
            "Servono almeno due pesate per disegnare la curva.",
            color = Palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        )
        return
    }

    val oggi = LocalDate.now()
    val giorniTotali = (fine.toEpochDay() - inizio.toEpochDay()).coerceAtLeast(1L)

    // Quanto è largo un giorno, quanto è largo il riquadro e dove si è
    // scorso: uno per tutti i grafici, perché scorrano e si allarghino insieme.
    val scrollState = rememberScrollState()
    val scala = remember(scrollState) { ScalaGrafico(scrollState) }
    val density = LocalDensity.current

    LaunchedEffect(scala.viewportPx) {
        if (scala.viewportPx <= 0) return@LaunchedEffect
        val giorniDaInizio = (oggi.toEpochDay() - inizio.toEpochDay()).coerceIn(0, giorniTotali)
        val pxPerGiorno = with(density) { scala.dpGiorno.dp.toPx() }
        val centro = pxPerGiorno * giorniDaInizio
        withFrameNanos { }
        val offset = (centro - scala.viewportPx / 2f).toInt().coerceIn(0, scala.scroll.maxValue)
        scala.scroll.scrollTo(offset)
    }

    LaunchedEffect(scala.dpGiorno) {
        val centro = scala.centroDaTenere ?: return@LaunchedEffect
        // Un frame di attesa: la larghezza nuova del disegno — e con lei il
        // massimo dello scorrimento — si conosce solo dopo che è stata
        // misurata, e scrollTo su un massimo vecchio finirebbe corto.
        withFrameNanos { }
        val pxPerGiorno = with(density) { scala.dpGiorno.dp.toPx() }
        val offset = (centro * pxPerGiorno - scala.viewportPx / 2f).toInt()
        scala.scroll.scrollTo(offset.coerceIn(0, scala.scroll.maxValue))
        scala.centroDaTenere = null
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // La legenda, in una riga sola che **scorre** invece di andare a capo
        // — gemella di `.chart-legenda` del web v4.13.7.
        RigaScorrevole(
            disposizione = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Legenda("Prima pesata del giorno", VerdePeso, segno = "●")
            Legenda("Media mobile ${stato.emaGiorni} gg", Viola, segno = "━")
            Legenda("Media stimata (nessuna pesata)", Viola, segno = "○")
            Legenda("Target", Palette.muted, segno = "┄")
        }

        if (serie.size == 1) {
            GraficoSerie(
                serie = serie.first(),
                scala = scala,
                inizio = inizio,
                fine = fine,
                oggi = oggi,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // In basso resta il posto del FAB «Pesati ancora», che
                    // altrimenti si siederebbe proprio sopra le date dell'asse.
                    .padding(bottom = 76.dp),
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                serie.forEach { s ->
                    Text(
                        text = s.titolo.orEmpty(),
                        color = Palette.dark,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (s.punti.size >= 2 || s.media.size >= 2) {
                        GraficoSerie(
                            serie = s,
                            scala = scala,
                            inizio = inizio,
                            fine = fine,
                            oggi = oggi,
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                        )
                    } else {
                        Text(
                            "Servono almeno due pesate col grasso corporeo per questo grafico.",
                            color = Palette.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                // Il posto del FAB, sotto l'ultimo grafico.
                Box(Modifier.height(80.dp))
            }
        }
    }
}

/**
 * Un grafico: la colonna delle etichette a sinistra e il disegno che scorre.
 * La scala ([ScalaGrafico]) è condivisa, quindi più grafici uno sotto l'altro
 * scorrono e si allargano insieme.
 */
@Composable
private fun GraficoSerie(
    serie: Serie,
    scala: ScalaGrafico,
    inizio: LocalDate,
    fine: LocalDate,
    oggi: LocalDate,
    modifier: Modifier,
) {
    val punti = serie.punti
    val target = serie.target
    val misuratore = rememberTextMeasurer()
    val giorniTotali = (fine.toEpochDay() - inizio.toEpochDay()).coerceAtLeast(1L)

    val media = serie.media
    val conMedia = media.isNotEmpty()
    // Con la media a schermo il valore del giorno si sbiadisce: è la media a
    // dire la tendenza, come nel web (`conEma`).
    val coloreValore = if (conMedia) VerdePeso.copy(alpha = 0.45f) else VerdePeso

    val valori = punti.map { it.valore } + media.map { it.valore } + target.map { it.second }
    val minimo = valori.min()
    val massimo = valori.max()
    // Un filo di aria sopra e sotto: con la curva appiccicata al bordo non si
    // capisce se ha smesso di scendere o se è finito lo spazio.
    val margine = ((massimo - minimo) * 0.12).coerceAtLeast(0.3)
    val basso = minimo - margine
    val alto = massimo + margine
    val ampiezza = (alto - basso).coerceAtLeast(0.1)
    // Cinque righe di riferimento invece di tre: con tre, fra una riga e
    // l'altra ci sono anche cinque chili e l'altezza della curva si legge a
    // occhio invece che sul righello.
    val livelli = List(5) { basso + ampiezza * it / 4.0 }

    val density = LocalDensity.current
    // Allargato al massimo il periodo intero sta in una schermata, e da lì in
    // poi il disegno non si stringe più: un `Canvas` più stretto del riquadro
    // che lo contiene lascerebbe una fascia vuota a destra, che sembra un
    // difetto e non una scala.
    val larghezzaRiquadro = with(density) { scala.viewportPx.toDp() }
    val larghezzaGrafico =
        (scala.dpGiorno.dp * (giorniTotali.toInt() + 1)).coerceAtLeast(larghezzaRiquadro)

    Row(modifier) {
        Canvas(Modifier.width(44.dp).fillMaxHeight()) {
            val fondo = size.height - ASSE.toPx()
            livelli.forEach { valore ->
                val yy = (fondo * (1.0 - (valore - basso) / ampiezza)).toFloat()
                val etichetta = misuratore.measure(
                    text = kg(valore),
                    style = TextStyle(color = Palette.muted, fontSize = 12.sp),
                )
                drawText(
                    textLayoutResult = etichetta,
                    topLeft = Offset(
                        x = size.width - etichetta.size.width - 6.dp.toPx(),
                        y = yy - etichetta.size.height / 2f,
                    ),
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.cardBg)
                .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
                .onSizeChanged { scala.viewportPx = it.width }
                // ⚠️ Il pizzico si scrive a mano invece di usare
                // `detectTransformGestures` o `transformable`: quelli
                // prendono anche il trascinamento a un dito e lo
                // consumano, cioè si mangerebbero lo scorrimento del
                // grafico. Qui si guarda l'evento solo quando le dita
                // sono **almeno due**, e solo allora lo si consuma —
                // con un dito solo l'evento passa oltre e arriva a
                // `horizontalScroll`, che continua a funzionare come
                // prima.
                //
                // ⚠️ E si guarda nel passaggio **`Initial`**, non in
                // quello normale. `Main` arriva prima al modifier più
                // interno, cioè allo scorrimento, che tratterebbe il
                // pizzico come un trascinamento e farebbe scivolare il
                // grafico mentre lo si stringe; `Initial` scende invece
                // dall'esterno, quindi qui si può togliere l'evento di
                // mano allo scorrimento prima che lo veda.
                .pointerInput(giorniTotali) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        do {
                            val evento = awaitPointerEvent(PointerEventPass.Initial)
                            if (evento.changes.size >= 2) {
                                val fattore = evento.calculateZoom()
                                if (fattore != 1f && fattore > 0f) {
                                    // Il giorno al centro si legge
                                    // **prima** di cambiare scala, e solo
                                    // la prima volta del gesto: rileggerlo
                                    // a ogni evento userebbe la posizione
                                    // già spostata dall'evento precedente.
                                    val pxPerGiorno = scala.dpGiorno.dp.toPx()
                                    if (scala.centroDaTenere == null) {
                                        scala.centroDaTenere =
                                            (scala.scroll.value + scala.viewportPx / 2f) / pxPerGiorno.toDouble()
                                    }
                                    // Il minimo non è un numero scritto
                                    // qui: è la scala a cui **tutto il
                                    // periodo sta in una schermata**, e
                                    // dipende quindi da quanto è largo il
                                    // telefono e da quanto dura
                                    // l'obiettivo.
                                    val minimoDp =
                                        if (scala.viewportPx > 0)
                                            (scala.viewportPx.toDp().value / (giorniTotali + 1))
                                                .toFloat()
                                                .coerceAtLeast(DP_GIORNO_MINIMO)
                                        else DP_GIORNO_MINIMO
                                    scala.dpGiorno = (scala.dpGiorno * fattore)
                                        .coerceIn(minimoDp, DP_GIORNO_MASSIMO)
                                }
                                evento.changes.forEach { it.consume() }
                            }
                        } while (evento.changes.any { it.pressed })
                    }
                }
                .horizontalScroll(scala.scroll)
        ) {
            Canvas(Modifier.width(larghezzaGrafico).fillMaxHeight()) {
                val fondo = size.height - ASSE.toPx()
                val bordo = 10.dp.toPx()

                fun x(giorno: LocalDate): Float =
                    bordo + (giorno.toEpochDay() - inizio.toEpochDay()) * (size.width - 2 * bordo) / giorniTotali
                fun y(peso: Double): Float =
                    (fondo * (1.0 - (peso - basso) / ampiezza)).toFloat()

                // Le stesse righe di riferimento della colonna delle
                // etichette, qui per intera larghezza.
                livelli.forEach { valore ->
                    drawLine(
                        color = Palette.border,
                        start = Offset(0f, y(valore)),
                        end = Offset(size.width, y(valore)),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // Una tacca e la data ogni settimana: senza, in un disegno
                // che può essere lungo mesi non si capirebbe mai a che
                // punto del periodo si sta scorrendo.
                //
                // ⚠️ Il passo segue lo zoom. A grafico stretto le date di
                // ogni settimana finirebbero una sopra l'altra: illeggibili
                // e pure più fitte delle tacche, che è il modo migliore per
                // far sembrare rotto un disegno che sta funzionando.
                val passoGiorni = when {
                    scala.dpGiorno >= 12f -> 7L
                    scala.dpGiorno >= 6f -> 14L
                    else -> 28L
                }
                var cursore: LocalDate = inizio
                while (!cursore.isAfter(fine)) {
                    val px = x(cursore)
                    drawLine(
                        color = Palette.border,
                        start = Offset(px, 0f),
                        end = Offset(px, fondo),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val data = misuratore.measure(
                        text = dataItaliana(cursore.toString()).take(5),
                        style = TextStyle(color = Palette.muted, fontSize = 11.sp),
                    )
                    // La data si scrive **centrata sulla tacca**, e la
                    // fascia sotto l'asse è alta abbastanza da contenerla:
                    // è il taglio che si vedeva prima.
                    drawText(
                        textLayoutResult = data,
                        topLeft = Offset(px - data.size.width / 2f, fondo + 6.dp.toPx()),
                    )
                    cursore = cursore.plusDays(passoGiorni)
                }

                // Oggi, marcata: è il punto da cui si parte per leggere
                // il grafico, prima ancora del bordo sinistro.
                if (!oggi.isBefore(inizio) && !oggi.isAfter(fine)) {
                    val px = x(oggi)
                    drawLine(
                        color = Palette.primary.copy(alpha = 0.7f),
                        start = Offset(px, 0f),
                        end = Offset(px, fondo),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                        ),
                    )
                    val oggiTesto = misuratore.measure(
                        text = "Oggi",
                        style = TextStyle(
                            color = Palette.light,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    // Su una targhetta piena invece che in aria: sopra le
                    // righe della griglia la scritta si leggeva a fatica.
                    drawRoundRect(
                        color = Palette.primary,
                        topLeft = Offset(px + 3.dp.toPx(), 2.dp.toPx()),
                        size = Size(
                            width = oggiTesto.size.width + 10.dp.toPx(),
                            height = oggiTesto.size.height + 4.dp.toPx(),
                        ),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    drawText(
                        textLayoutResult = oggiTesto,
                        topLeft = Offset(px + 8.dp.toPx(), 4.dp.toPx()),
                    )
                }

                // La curva del target, tratteggiata e dritta fra un
                // traguardo e l'altro: è una promessa, non un fatto.
                if (target.size >= 2) {
                    val strada = Path().apply {
                        target.forEachIndexed { indice, (giorno, peso) ->
                            val px = x(giorno)
                            val py = y(peso)
                            if (indice == 0) moveTo(px, py) else lineTo(px, py)
                        }
                    }
                    drawPath(
                        path = strada,
                        color = GrigioTarget,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(5.dp.toPx(), 5.dp.toPx())
                            ),
                        ),
                    )
                }

                // Il valore del giorno — la prima pesata — con una linea
                // sottile e un pallino PIENO su ogni pesata vera. Dove manca
                // la linea passa diritta: il futuro non si disegna.
                if (punti.size >= 2) {
                    val strada = Path().apply {
                        punti.forEachIndexed { indice, p ->
                            if (indice == 0) moveTo(x(p.giorno), y(p.valore)) else lineTo(x(p.giorno), y(p.valore))
                        }
                    }
                    drawPath(
                        path = strada,
                        color = coloreValore,
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                val raggio = if (scala.dpGiorno < 8f) 2.dp.toPx() else 3.5.dp.toPx()
                punti.forEach { p ->
                    drawCircle(coloreValore, radius = raggio, center = Offset(x(p.giorno), y(p.valore)))
                }

                // La media mobile: la linea viola spessa. Un giorno stimato col
                // trend ha un pallino VUOTO: la linea passa di lì, ma lì
                // nessuno si è pesato.
                if (media.size >= 2) {
                    val strada = Path().apply {
                        media.forEachIndexed { indice, m ->
                            if (indice == 0) moveTo(x(m.giorno), y(m.valore)) else lineTo(x(m.giorno), y(m.valore))
                        }
                    }
                    drawPath(
                        path = strada,
                        color = Viola,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                if (scala.dpGiorno >= 6f) {
                    media.filter { it.stimato }.forEach { m ->
                        val c = Offset(x(m.giorno), y(m.valore))
                        drawCircle(Color.White, radius = 3.dp.toPx(), center = c)
                        drawCircle(Viola, radius = 3.dp.toPx(), center = c, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }

                // L'ultimo valore della media (o del giorno, senza media) col
                // numero scritto accanto: il valore di oggi non si deve
                // leggere sul righello delle etichette.
                val ultimoPunto = media.lastOrNull()?.let { it.giorno to it.valore }
                    ?: punti.lastOrNull()?.let { it.giorno to it.valore }
                if (ultimoPunto != null) {
                    val coloreUltimo = if (conMedia) Viola else VerdePeso
                    val fine2 = Offset(x(ultimoPunto.first), y(ultimoPunto.second))
                    drawCircle(Palette.cardBg, radius = 6.dp.toPx(), center = fine2)
                    drawCircle(coloreUltimo, radius = 4.dp.toPx(), center = fine2)
                    val valore = misuratore.measure(
                        text = kg(ultimoPunto.second),
                        style = TextStyle(color = coloreUltimo, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    )
                    drawText(
                        textLayoutResult = valore,
                        topLeft = Offset(
                            x = (fine2.x + 8.dp.toPx())
                                .coerceAtMost(size.width - valore.size.width - 2.dp.toPx()),
                            y = (fine2.y - valore.size.height - 8.dp.toPx()).coerceAtLeast(0f),
                        ),
                    )
                }
            }
        }
    }
}

/** Una serie da disegnare: titolo (nel caso dei tre grafici), punti e target. */
private class Serie(
    val titolo: String?,
    val punti: List<PuntoGiorno>,
    val media: List<PuntoMedia>,
    val target: List<Pair<LocalDate, Double>>,
    val targetAl: (LocalDate) -> Double?,
)

/**
 * Lo stato condiviso dai grafici: lo scorrimento, la scala e la larghezza del
 * riquadro. ⚠️ Uno solo per tutti, o i tre grafici della massa grassa
 * scorrerebbero ciascuno per conto suo e le date non starebbero più in colonna.
 */
private class ScalaGrafico(val scroll: ScrollState) {
    // Quanto è largo un giorno: è la variabile che il **pizzico** cambia.
    // Non si ricava dallo schermo — così il grafico parte uguale su telefoni
    // diversi — e da lì in poi la scala la decide il dito.
    var dpGiorno by mutableFloatStateOf(DP_GIORNO_INIZIALE)
    var viewportPx by mutableIntStateOf(0)
    // Il giorno che stava al centro quando il pizzico è cominciato: dopo aver
    // cambiato scala si torna lì. Senza, stringendo le dita il grafico
    // scivolerebbe via da sé e si perderebbe il punto che si stava guardando.
    var centroDaTenere by mutableStateOf<Double?>(null)
}

/**
 * La spezzata del target è quella dei traguardi, non i valori interpolati
 * giorno per giorno — quelli restano nella tabella. Copre **tutto** il
 * periodo dell'obiettivo, futuro compreso: agli estremi, se non cadono già su
 * un traguardo, un solo valore interpolato completa la linea. [conv] porta il
 * peso totale nella grandezza del grafico (la massa grassa è lineare nel peso,
 * quindi i soli traguardi bastano).
 */
private fun spezzata(
    inizio: LocalDate,
    fine: LocalDate,
    traguardi: List<Traguardo>,
    conv: (Double) -> Double,
): List<Pair<LocalDate, Double>> = buildList {
    traguardi.forEach { t ->
        val giorno = PesoRegole.giornoDa(t.giorno) ?: return@forEach
        if (!giorno.isBefore(inizio) && !giorno.isAfter(fine)) add(giorno to conv(t.peso))
    }
    if (none { it.first == inizio }) {
        PesoRegole.targetInterpolato(traguardi, inizio.toString())?.let { add(inizio to conv(it)) }
    }
    if (none { it.first == fine }) {
        PesoRegole.targetInterpolato(traguardi, fine.toString())?.let { add(fine to conv(it)) }
    }
    sortBy { it.first }
}

/** Il target giorno per giorno, per una grandezza che non è lineare nel peso (la %). */
private fun spezzataGiornaliera(
    inizio: LocalDate,
    fine: LocalDate,
    traguardi: List<Traguardo>,
    conv: (Double) -> Double,
): List<Pair<LocalDate, Double>> = buildList {
    var g = inizio
    while (!g.isAfter(fine)) {
        PesoRegole.targetInterpolato(traguardi, g.toString())?.let { add(g to conv(it)) }
        g = g.plusDays(1)
    }
}

/**
 * La PRIMA pesata di ogni giornata (la più vecchia per `timestamp`) che ha
 * [valore], dall'inizio dell'obiettivo — la stessa della tabella e della media.
 */
private fun primePesate(
    pesate: List<Pesata>,
    inizio: LocalDate,
    valore: (Pesata) -> Double?,
): List<PuntoGiorno> = pesate
    .groupBy { it.giorno }
    .mapNotNull { (g, righe) ->
        val giorno = PesoRegole.giornoDa(g) ?: return@mapNotNull null
        if (giorno.isBefore(inizio)) return@mapNotNull null
        val prima = righe.filter { valore(it) != null }.minByOrNull { it.timestamp } ?: return@mapNotNull null
        PuntoGiorno(giorno, valore(prima)!!)
    }
    .sortedBy { it.giorno }

/** Una giornata sul grafico: il valore della prima pesata. */
private data class PuntoGiorno(val giorno: LocalDate, val valore: Double)

/** Un giorno della media mobile; `stimato` = nessuna pesata, pallino vuoto. */
private data class PuntoMedia(val giorno: LocalDate, val valore: Double, val stimato: Boolean)

/** L'altezza della fascia sotto l'asse, dove vanno le date. */
private val ASSE = 26.dp

/**
 * Il verde del peso quando sta **dentro** il piano.
 *
 * ⚠️ Non è lo stesso [Verde] della spezzata del target, di un passo più
 * scuro: le due linee si incrociano di continuo, e con lo stesso identico
 * verde nel punto in cui si toccano non si distinguerebbe più quale delle due
 * si sta guardando. È lo stesso ritocco di contrasto fatto in
 * `obiettivi.html` per le barre delle azioni.
 */
private val VerdePeso = Color(0xFF00967A)

/** Il viola della media mobile, lo stesso `#6C5CE7` del web. */
private val Viola = Color(0xFF6C5CE7)

/** Il grigio della spezzata del target, come `#666` del web. */
private val GrigioTarget = Color(0xFF666666)

/**
 * La scala di partenza e i suoi estremi, in dp per giorno.
 *
 * Il minimo vero lo decide lo schermo — è la scala a cui tutto il periodo sta
 * in una schermata — e questa costante è solo il pavimento sotto cui non si
 * scende comunque, per un obiettivo lunghissimo su un telefono stretto. Il
 * massimo è il dettaglio di un giorno alla volta: oltre, un grafico lungo un
 * chilometro non è uno zoom, è un modo di perdersi.
 */
private const val DP_GIORNO_INIZIALE = 16f
private const val DP_GIORNO_MINIMO = 1.2f
private const val DP_GIORNO_MASSIMO = 48f

@Composable
private fun Legenda(testo: String, colore: Color, segno: String = "▬") {
    Text(
        text = "$segno $testo",
        color = colore,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyMedium,
    )
}
