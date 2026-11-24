package com.garsal.solstizio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.garsal.solstizio.Tema
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Le illustrazioni delle carte, disegnate a mano su Canvas: niente file, niente
 * rete. ⚠️ Il caso è seminato con la DATA, quindi la carta di un giorno è
 * sempre la stessa — riaprendola fra un mese si ritrova lo stesso cielo.
 */

val Oro1 = Color(0xFF8C6A12)
val Oro2 = Color(0xFFD4A62A)
val Oro3 = Color(0xFFFFE08A)

@Composable
fun Illustrazione(tema: Tema, giorno: LocalDate, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = Random(giorno.toEpochDay() * 31 + tema.ordinal)
        when (tema) {
            Tema.SOLE -> sole(r)
            Tema.STELLE -> stelle(r)
            Tema.MARE -> mare(r)
        }
    }
}

private fun Random.tra(a: Float, b: Float) = a + nextFloat() * (b - a)

private val CIELI_GIORNO = listOf(
    listOf(Color(0xFF4FC3F7), Color(0xFFB3E5FC), Color(0xFFFFF3E0)),
    listOf(Color(0xFFFF8A65), Color(0xFFFFCC80), Color(0xFFFFF8E1)),
    listOf(Color(0xFF7E57C2), Color(0xFFFF8A80), Color(0xFFFFE0B2)),
    listOf(Color(0xFF29B6F6), Color(0xFFFFE082), Color(0xFFFFFDE7)),
)

private fun DrawScope.sole(r: Random) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(CIELI_GIORNO[r.nextInt(CIELI_GIORNO.size)]))
    val c = Offset(w * r.tra(0.3f, 0.7f), h * r.tra(0.3f, 0.45f))
    val rag = min(w, h) * r.tra(0.13f, 0.18f)
    // alone
    drawCircle(Brush.radialGradient(listOf(Color(0x88FFF59D), Color.Transparent), c, rag * 3.2f), rag * 3.2f, c)
    // raggi
    val n = 10 + r.nextInt(8)
    val giro = r.tra(0f, 360f)
    rotate(giro, c) {
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            val lungo = if (i % 2 == 0) 2.1f else 1.6f
            drawLine(
                Color(0xFFFFC107), Offset(c.x + cos(a).toFloat() * rag * 1.25f, c.y + sin(a).toFloat() * rag * 1.25f),
                Offset(c.x + cos(a).toFloat() * rag * lungo, c.y + sin(a).toFloat() * rag * lungo),
                strokeWidth = rag * 0.14f, cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF9C4), Color(0xFFFFC107), Color(0xFFFF8F00)), c, rag), rag, c)
    // colline
    val toni = listOf(Color(0xFF7CB342), Color(0xFF558B2F), Color(0xFF33691E))
    for ((k, tono) in toni.withIndex()) {
        val base = h * (0.68f + k * 0.1f)
        val p = Path().apply {
            moveTo(0f, h); lineTo(0f, base)
            val fase = r.tra(0f, 6.28f)
            val onde = r.tra(1f, 2.5f)
            var x = 0f
            while (x <= w) {
                lineTo(x, base - sin(x / w * onde * 2 * PI + fase).toFloat() * h * 0.05f)
                x += w / 40
            }
            lineTo(w, h); close()
        }
        drawPath(p, tono)
    }
}

private fun DrawScope.stelle(r: Random) {
    val w = size.width
    val h = size.height
    val notti = listOf(
        listOf(Color(0xFF020617), Color(0xFF0B1D3A), Color(0xFF1E3A8A)),
        listOf(Color(0xFF0F0524), Color(0xFF2E1065), Color(0xFF4C1D95)),
        listOf(Color(0xFF00111A), Color(0xFF0C4A6E), Color(0xFF155E75)),
    )
    drawRect(Brush.verticalGradient(notti[r.nextInt(notti.size)]))
    // via lattea: una fascia di polvere chiara
    if (r.nextBoolean()) {
        repeat(260) {
            val t = r.nextFloat()
            val x = w * t
            val y = h * (0.15f + t * 0.5f) + r.tra(-1f, 1f) * h * 0.08f
            drawCircle(Color.White.copy(alpha = r.tra(0.05f, 0.25f)), r.tra(0.5f, 1.6f) * density, Offset(x, y))
        }
    }
    repeat(90 + r.nextInt(60)) {
        val p = Offset(r.nextFloat() * w, r.nextFloat() * h * 0.85f)
        drawCircle(Color.White.copy(alpha = r.tra(0.4f, 1f)), r.tra(0.6f, 2.2f) * density, p)
    }
    // qualche stella grande a quattro punte
    repeat(4 + r.nextInt(4)) {
        val c = Offset(r.nextFloat() * w, r.nextFloat() * h * 0.75f)
        val s = r.tra(6f, 13f) * density
        val col = listOf(Color.White, Color(0xFFFFF59D), Color(0xFFB3E5FC))[r.nextInt(3)]
        val p = Path().apply {
            moveTo(c.x, c.y - s); lineTo(c.x + s * 0.22f, c.y - s * 0.22f); lineTo(c.x + s, c.y)
            lineTo(c.x + s * 0.22f, c.y + s * 0.22f); lineTo(c.x, c.y + s); lineTo(c.x - s * 0.22f, c.y + s * 0.22f)
            lineTo(c.x - s, c.y); lineTo(c.x - s * 0.22f, c.y - s * 0.22f); close()
        }
        drawCircle(col.copy(alpha = 0.25f), s * 0.9f, c)
        drawPath(p, col)
    }
    // la luna, una falce
    val mc = Offset(w * r.tra(0.2f, 0.8f), h * r.tra(0.18f, 0.35f))
    val mr = min(w, h) * r.tra(0.09f, 0.13f)
    val disco = Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc, mr)) }
    val ombra = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(mc.x + mr * r.tra(0.35f, 0.6f), mc.y - mr * 0.2f), mr))
    }
    drawCircle(Color(0x33FFF8E1), mr * 1.8f, mc)
    drawPath(Path.combine(PathOperation.Difference, disco, ombra), Color(0xFFFFF8E1))
    // una stella cadente, a volte
    if (r.nextInt(3) == 0) {
        val a = Offset(w * r.tra(0.5f, 0.9f), h * r.tra(0.05f, 0.25f))
        val b = Offset(a.x - w * 0.3f, a.y + h * 0.15f)
        drawLine(
            Brush.linearGradient(listOf(Color.White, Color.Transparent), a, b), a, b,
            strokeWidth = 2.5f * density, cap = StrokeCap.Round,
        )
    }
    // sagoma di colline scure
    val p = Path().apply {
        moveTo(0f, h); lineTo(0f, h * 0.88f)
        val fase = r.tra(0f, 6.28f)
        var x = 0f
        while (x <= w) { lineTo(x, h * 0.88f - sin(x / w * 3 * PI + fase).toFloat() * h * 0.04f); x += w / 40 }
        lineTo(w, h); close()
    }
    drawPath(p, Color(0xFF020617))
}

private fun DrawScope.mare(r: Random) {
    val w = size.width
    val h = size.height
    val tramonto = r.nextBoolean()
    val cielo = if (tramonto) listOf(Color(0xFF5E35B1), Color(0xFFFF7043), Color(0xFFFFCA28))
    else listOf(Color(0xFF0288D1), Color(0xFF81D4FA), Color(0xFFE1F5FE))
    val orizzonte = h * r.tra(0.48f, 0.58f)
    drawRect(Brush.verticalGradient(cielo, 0f, orizzonte), size = Size(w, orizzonte))
    // il sole sull'orizzonte
    val sc = Offset(w * r.tra(0.3f, 0.7f), orizzonte - (if (tramonto) 0f else h * 0.18f))
    val sr = min(w, h) * r.tra(0.1f, 0.14f)
    drawCircle(Color(if (tramonto) 0xFFFFE082 else 0xFFFFF59D), sr, sc)
    // gabbiani
    repeat(r.nextInt(4)) {
        val g = Offset(w * r.tra(0.1f, 0.9f), orizzonte * r.tra(0.2f, 0.6f))
        val s = r.tra(6f, 11f) * density
        val p = Path().apply {
            moveTo(g.x - s, g.y); quadraticTo(g.x - s / 2, g.y - s * 0.6f, g.x, g.y)
            quadraticTo(g.x + s / 2, g.y - s * 0.6f, g.x + s, g.y)
        }
        drawPath(p, Color(0xFF263238), style = Stroke(1.6f * density, cap = StrokeCap.Round))
    }
    // il mare
    val acqua = if (tramonto) listOf(Color(0xFF283593), Color(0xFF1A237E)) else listOf(Color(0xFF0277BD), Color(0xFF01579B))
    drawRect(Brush.verticalGradient(acqua, orizzonte, h), Offset(0f, orizzonte), Size(w, h - orizzonte))
    // il riflesso del sole: strisce che si stringono verso l'orizzonte
    for (i in 0 until 9) {
        val y = orizzonte + (h - orizzonte) * (i + 0.5f) / 9
        val l = sr * (0.6f + i * 0.35f) * r.tra(0.7f, 1.2f)
        drawLine(
            Color(0xFFFFE082).copy(alpha = 0.75f - i * 0.06f),
            Offset(sc.x - l, y), Offset(sc.x + l, y), strokeWidth = 2.5f * density, cap = StrokeCap.Round,
        )
    }
    // onde
    for (k in 0 until 6) {
        val base = orizzonte + (h - orizzonte) * (0.2f + k * 0.16f)
        val fase = r.tra(0f, 6.28f)
        val ampiezza = (h - orizzonte) * 0.02f * (1 + k * 0.4f)
        val p = Path().apply {
            moveTo(0f, base)
            var x = 0f
            while (x <= w) { lineTo(x, base + sin(x / w * (6 + k) * PI + fase).toFloat() * ampiezza); x += w / 60 }
        }
        drawPath(p, Color.White.copy(alpha = 0.35f), style = Stroke((1.2f + k * 0.3f) * density))
    }
    // una vela, a volte
    if (r.nextInt(3) != 0) {
        val bx = w * r.tra(0.15f, 0.85f)
        val by = orizzonte + (h - orizzonte) * 0.12f
        val s = min(w, h) * 0.06f
        drawPath(Path().apply { moveTo(bx, by - s * 1.6f); lineTo(bx, by); lineTo(bx + s, by); close() }, Color.White)
        drawPath(Path().apply { moveTo(bx - s * 0.5f, by); lineTo(bx + s * 1.2f, by); lineTo(bx + s * 0.9f, by + s * 0.3f); lineTo(bx - s * 0.3f, by + s * 0.3f); close() }, Color(0xFF3E2723))
    }
}

/** Il retro della carta: oro, con un sole inciso al centro. Uguale per tutte. */
@Composable
fun RetroDorato(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.linearGradient(listOf(Oro1, Oro2, Oro3, Oro2, Oro1), Offset.Zero, Offset(w, h)))
        // trama a rombi
        val passo = min(w, h) / 9
        var x = -h
        while (x < w + h) {
            drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x + h, h), strokeWidth = 1.2f * density)
            drawLine(Color(0x22000000), Offset(x + h, 0f), Offset(x, h), strokeWidth = 1.2f * density)
            x += passo
        }
        // cornice
        val m = 10f * density
        drawRect(
            Oro1, Offset(m, m), Size(w - 2 * m, h - 2 * m),
            style = Stroke(3f * density),
        )
        drawRect(
            Color(0x88FFF3C4), Offset(m * 1.8f, m * 1.8f), Size(w - 3.6f * m, h - 3.6f * m),
            style = Stroke(1.2f * density),
        )
        // il sole inciso
        val c = Offset(w / 2, h / 2)
        val rr = min(w, h) * 0.16f
        for (i in 0 until 16) {
            val a = 2 * PI * i / 16
            val l = if (i % 2 == 0) 2.0f else 1.6f
            drawLine(
                Oro1, Offset(c.x + cos(a).toFloat() * rr * 1.2f, c.y + sin(a).toFloat() * rr * 1.2f),
                Offset(c.x + cos(a).toFloat() * rr * l, c.y + sin(a).toFloat() * rr * l),
                strokeWidth = rr * 0.12f, cap = StrokeCap.Round,
            )
        }
        drawCircle(Brush.radialGradient(listOf(Oro3, Oro2, Oro1), c, rr), rr, c)
        drawCircle(Oro1, rr, c, style = Stroke(2f * density))
    }
}
