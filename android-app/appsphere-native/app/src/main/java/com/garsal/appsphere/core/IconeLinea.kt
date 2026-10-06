package com.garsal.appsphere.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Le icone a tratto di «Ti pisasti?» e Calorie — le stesse dello sprite `<symbol id="i-…">` in
 * cima a `weight-quest.html`, tracciato per tracciato (viewBox 24, tratto 2, estremi tondi).
 *
 * ⚠️ Sono la COPIA di quei simboli: cambiando un disegno di là, va cambiato anche qui, o le due
 * app smettono di sembrare la stessa. `rect` e `circle` sono riscritti come tracciati, perché
 * [addPathNodes] legge solo la sintassi di `d`.
 *
 * ⚠️ Disegnate a mano e non prese da `material-icons-extended`, che non va reintrodotto (vedi
 * CLAUDE.md): sono una manciata di stringhe, non migliaia di classi nel DEX.
 */
object IconeLinea {
    private fun rett(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}H${x + w - r}a$r $r 0 0 1 $r ${r}V${y + h - r}a$r $r 0 0 1 -$r ${r}" +
            "H${x + r}a$r $r 0 0 1 -$r -${r}V${y + r}a$r $r 0 0 1 $r -${r}Z"

    private fun icona(nome: String, vararg tracciati: String): ImageVector {
        val b = ImageVector.Builder(
            name = nome,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        tracciati.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** Bilancia pesapersone (`#i-peso`). */
    val peso: ImageVector by lazy {
        icona("peso", rett(3f, 3f, 18f, 18f, 4f), "M7.5 10.5a4.5 4.5 0 0 1 9 0z", "m12 10.5 1.8-2.8")
    }

    /** Andamento (`#i-statistiche`). */
    val statistiche: ImageVector by lazy {
        icona("statistiche", "M22 17L13.5 8.5L8.5 13.5L2 7", "M16 17L22 17L22 11")
    }

    /** Tabella: la stessa famiglia di tratti, per la vista a righe. */
    val tabella: ImageVector by lazy {
        icona("tabella", rett(3f, 3f, 18f, 18f, 2f), "M3 9h18", "M3 15h18", "M12 3v18")
    }

    /** Ciotola (`#i-dieta`): il segno di Calorie. */
    val dieta: ImageVector by lazy {
        icona(
            "dieta",
            "M7 21h10",
            "M12 21a9 9 0 0 0 9-9H3a9 9 0 0 0 9 9Z",
            "M11.38 12a2.4 2.4 0 0 1-.4-4.77 2.4 2.4 0 0 1 3.2-2.77 2.4 2.4 0 0 1 3.47-.63 " +
                "2.4 2.4 0 0 1 3.37 3.37 2.4 2.4 0 0 1-1.1 3.7 2.51 2.51 0 0 1 .03 1.1",
            "m13 12 4-4",
            "M10.9 7.25A3.99 3.99 0 0 0 4 10c0 .73.2 1.41.54 2",
        )
    }

    /** Riquadri (`#i-dashboard`). */
    val dashboard: ImageVector by lazy {
        icona(
            "dashboard",
            rett(3f, 3f, 7f, 9f, 1f), rett(14f, 3f, 7f, 5f, 1f),
            rett(14f, 12f, 7f, 9f, 1f), rett(3f, 16f, 7f, 5f, 1f),
        )
    }

    /** Libro aperto (`#i-diario`). */
    val diario: ImageVector by lazy {
        icona("diario", "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z", "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z")
    }

    /** Due frecce tonde che si rincorrono (`#i-sync`). */
    val sync: ImageVector by lazy {
        icona(
            "sync",
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        )
    }
}

/**
 * La misura di un'icona che sta in fila con del testo: segue `fontScale` col tetto di
 * [GarsalTopBar], perché in `dp` fisse accanto a una scritta ingrandita sembrerebbe rimpicciolita.
 */
@Composable
fun misuraIcona(base: Dp): Dp = base * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

/** Una voce «icona + parola» di un selettore a pillole. */
@Composable
fun VoceConIcona(icona: ImageVector, testo: String, colore: Color, grassetto: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icona, contentDescription = null, tint = colore, modifier = Modifier.size(misuraIcona(20.dp)))
        Text(
            text = testo,
            color = colore,
            fontWeight = if (grassetto) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * L'interruttore ⚖️ Peso ⇄ 🍽️ Calorie nella barra blu, gemello del `.modo-switch` di
 * `weight-quest.html`: il modo attivo pieno di bianco, l'altro sbiadito. Il tocco porta
 * all'altro modo — qui sono due schermate, di là due gruppi di viste della stessa pagina.
 *
 * ⚠️ Sta in tutt'e due le schermate, uguale: un interruttore che c'è da una parte sola si legge
 * come un pulsante, e da Calorie non si saprebbe come tornare al peso senza l'indietro.
 */
@Composable
fun InterruttorePesoCalorie(suCalorie: Boolean, onCambia: () -> Unit) {
    val forma = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(forma)
            .border(1.dp, Color.White.copy(alpha = 0.6f), forma)
            .clickable(onClick = onCambia)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segmento(IconeLinea.peso, "Peso", attivo = !suCalorie)
        Segmento(IconeLinea.dieta, "Calorie", attivo = suCalorie)
    }
}

@Composable
private fun Segmento(icona: ImageVector, testo: String, attivo: Boolean) {
    val colore = if (attivo) Palette.topBar else Color.White.copy(alpha = 0.75f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (attivo) Color.White else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icona, contentDescription = null, tint = colore, modifier = Modifier.size(misuraIcona(18.dp)))
        Text(
            text = testo,
            color = colore,
            fontSize = 13.sp,
            fontWeight = if (attivo) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}
