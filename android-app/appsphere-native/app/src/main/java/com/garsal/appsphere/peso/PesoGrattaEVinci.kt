package com.garsal.appsphere.peso

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Il «gratta e vinci» di una soglia raggiunta — `openPrizeDraw()` +
 * `buildScratchFoil()` nel web. Il premio è già deciso quando il biglietto
 * si apre (già vinto in precedenza, o pescato a caso qui): grattare scopre
 * quello che c'è sotto, non lo estrae mano a mano.
 *
 * ⚠️ Non replica il biglietto di prova (`openPrizeDraw(null, true)`): sul
 * web è raggiungibile solo dalla console, non da un pulsante — qui non c'è
 * quindi niente da cui aprirlo.
 */
@Composable
fun DialogoGrattaEVinci(
    soglia: Int,
    vinto: PremioVinto?,
    onRivelato: (PremioCibo) -> Unit,
    onMangiato: (Boolean) -> Unit,
    onChiudi: () -> Unit,
) {
    val premio = remember(soglia) { vinto?.let { premioDa(it.id) } ?: PREMI_CIBO.random() }
    var rivelato by remember(soglia) { mutableStateOf(vinto != null) }
    // Non è uno stato locale: arriva da [PesoState.premi], che si aggiorna in
    // modo ottimistico appena si tocca il pulsante. Tenerne una copia qui
    // vorrebbe dire due verità sullo stesso premio.
    val mangiatoIl = vinto?.mangiatoIl

    Dialog(onDismissRequest = onChiudi) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF16213E))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "🎁 Premio per < $soglia kg",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = when {
                    mangiatoIl != null -> "Premio già mangiato il ${dataIt(mangiatoIl)}."
                    rivelato -> "Premio già grattato per questa soglia."
                    else -> "Gratta l'argento col dito e scopri cosa hai vinto."
                },
                color = Color(0xFF9AA5C4),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

            Biglietto(
                premio = premio,
                rivelato = rivelato,
                onRivelato = {
                    if (!rivelato) {
                        rivelato = true
                        onRivelato(premio)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            if (rivelato) {
                Text(
                    "🎉 ${premio.nome}!",
                    color = Color(0xFFFFD700),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    premio.messaggio,
                    color = Color(0xFFCFD6EA),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (mangiatoIl != null) {
                    Text(
                        "🍽️ Mangiato il ${dataIt(mangiatoIl)}",
                        color = Color(0xFF00B894),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // La domanda che il biglietto pone dopo il gratta: il premio
                // resta vinto, ma si spegne. Reversibile — un tocco per sbaglio
                // non deve costare un cannolo — e per questo qui non c'è la
                // conferma che il web chiede con un `confirm()`.
                Bottone(
                    testo = if (mangiatoIl != null) "↺ Non l'ho ancora mangiato" else "🍽️ Mangiato !!!",
                    modifier = Modifier.fillMaxWidth(),
                    colore = if (mangiatoIl != null) Color(0xFF2E3A5E) else Color(0xFF00B894),
                ) { onMangiato(mangiatoIl == null) }
            } else {
                Bottone("👆 Scopri tutto", Modifier.fillMaxWidth(), Color(0xFFFFD700)) {
                    rivelato = true
                    onRivelato(premio)
                }
            }

            Bottone(
                testo = if (rivelato) "Chiudi" else "Più tardi",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colore = Color(0xFF2E3A5E),
                onClick = onChiudi,
            )
        }
    }
}

@Composable
private fun Biglietto(
    premio: PremioCibo,
    rivelato: Boolean,
    onRivelato: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .border(3.dp, Color(0xFFFFD700), RoundedCornerShape(16.dp)),
    ) {
        Image(
            painter = painterResource(premio.immagine),
            contentDescription = premio.nome,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (!rivelato) {
            PatinaArgento(onGrattatoAbbastanza = onRivelato, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * La patina d'argento sopra la foto: un bitmap disegnato una volta
 * ([disegnaPatina]) e forato via [gratta] a ogni movimento del dito, con
 * `PorterDuff.Mode.CLEAR` — l'equivalente di `globalCompositeOperation =
 * 'destination-out'` nel canvas 2D del web.
 *
 * Si scopre da sé oltre il 55 % grattato durante il gesto (controllato ogni
 * 6 mosse, come nel web) o il 42 % al rilascio del dito.
 */
@Composable
private fun PatinaArgento(onGrattatoAbbastanza: () -> Unit, modifier: Modifier = Modifier) {
    var dimensioni by remember { mutableStateOf(IntSize.Zero) }
    var patina by remember { mutableStateOf<Bitmap?>(null) }
    var versione by remember { mutableStateOf(0) }
    var scoperta by remember { mutableStateOf(false) }
    var mosse by remember { mutableStateOf(0) }

    LaunchedEffect(dimensioni) {
        if (dimensioni.width > 0 && dimensioni.height > 0 && patina == null) {
            patina = disegnaPatina(dimensioni.width, dimensioni.height)
            versione++
        }
    }

    Box(
        modifier
            .onSizeChanged { dimensioni = it }
            .pointerInput(patina) {
                val bmp = patina ?: return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        gratta(bmp, offset.x, offset.y, null, null)
                        versione++
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        gratta(bmp, change.position.x, change.position.y, change.previousPosition.x, change.previousPosition.y)
                        versione++
                        mosse++
                        if (!scoperta && mosse % 6 == 0 && progressoGrattato(bmp) > 0.55f) scoperta = true
                    },
                    onDragEnd = {
                        if (!scoperta && progressoGrattato(bmp) > 0.42f) scoperta = true
                    },
                )
            },
    ) {
        patina?.let { bmp ->
            key(versione) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    LaunchedEffect(scoperta) {
        if (scoperta) onGrattatoAbbastanza()
    }
}

/** Fora la patina con un cerchio (e, se c'è un punto precedente, la linea che li unisce). */
private fun gratta(bitmap: Bitmap, x: Float, y: Float, daX: Float?, daY: Float?) {
    val tela = AndroidCanvas(bitmap)
    val raggio = maxOf(16f, bitmap.width * 0.075f)
    val pennello = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeWidth = raggio * 2
        strokeCap = Paint.Cap.ROUND
    }
    if (daX != null && daY != null) tela.drawLine(daX, daY, x, y, pennello)
    tela.drawCircle(x, y, raggio, pennello)
}

/** Percentuale già grattata, campionando un pixel ogni 8 — come nel web. */
private fun progressoGrattato(bitmap: Bitmap): Float {
    val w = bitmap.width
    val h = bitmap.height
    if (w == 0 || h == 0) return 0f
    val pixel = IntArray(w * h)
    bitmap.getPixels(pixel, 0, w, 0, 0, w, h)
    var trasparenti = 0
    var totale = 0
    var i = 0
    while (i < pixel.size) {
        totale++
        if ((pixel[i] ushr 24) and 0xFF < 40) trasparenti++
        i += 8
    }
    return if (totale > 0) trasparenti.toFloat() / totale else 0f
}

/** La patina d'argento — gradiente diagonale, righine, «GRATTA QUI col dito». */
private fun disegnaPatina(w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val tela = AndroidCanvas(bmp)

    val sfondo = Paint().apply {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                AndroidColor.parseColor("#9AA3AD"),
                AndroidColor.parseColor("#D9DEE5"),
                AndroidColor.parseColor("#F2F5F8"),
                AndroidColor.parseColor("#C3CAD3"),
                AndroidColor.parseColor("#8F98A3"),
            ),
            floatArrayOf(0f, 0.28f, 0.46f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    tela.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sfondo)

    val riga = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(56, 255, 255, 255)
        strokeWidth = 2f
    }
    var x = -h.toFloat()
    while (x < w) {
        tela.drawLine(x, h.toFloat(), x + h, 0f, riga)
        x += 11f
    }

    val testo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(158, 35, 42, 60)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = maxOf(15f, w * 0.075f)
    }
    tela.drawText("GRATTA QUI", w / 2f, h / 2f - h * 0.06f, testo)
    testo.isFakeBoldText = false
    testo.textSize = maxOf(11f, w * 0.045f)
    tela.drawText("col dito 👆", w / 2f, h / 2f + h * 0.11f, testo)

    return bmp
}

/** «2026-08-24» → «24/08/2026», come `fmtDateIt()` nel web. */
private fun dataIt(iso: String): String {
    val p = iso.split("-")
    return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
}
