package com.garsal.pronuncia.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.garsal.pronuncia.Frase
import com.garsal.pronuncia.Servizi

/**
 * Il testo inglese di una frase: tenendolo premuto compare sopra un fumetto con
 * la traduzione italiana, che sparisce al rilascio.
 *
 * ⚠️ Il fumetto è un `Popup` non focalizzabile: deve stare sopra tutto senza
 * rubare il tocco, o al rilascio il dito finirebbe sul popup e il fumetto non
 * si chiuderebbe più.
 */
@Composable
fun TestoConTraduzione(f: Frase, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    val mappa by Servizi.traduzioni.mappa.collectAsState()
    LaunchedEffect(Unit) { Servizi.traduzioni.aggiorna() }
    var premuto by remember { mutableStateOf(false) }

    Box {
        Text(
            f.testo,
            style = style,
            modifier = Modifier.pointerInput(f.id) {
                detectTapGestures(onPress = {
                    premuto = true
                    try { tryAwaitRelease() } finally { premuto = false }
                })
            },
        )
        if (premuto) {
            Popup(
                popupPositionProvider = SopraIlTesto,
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 6.dp,
                    modifier = Modifier.widthIn(max = 360.dp),
                ) {
                    Text(
                        "🇮🇹 " + (mappa[f.id] ?: "Traduzione non disponibile"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Il fumetto va SOPRA il testo, che deve restare leggibile mentre lo si
 *  confronta; se sopra non c'è posto, sotto. Mai fuori dallo schermo. */
private object SopraIlTesto : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val margine = 16
        val x = anchorBounds.left.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val sopra = anchorBounds.top - popupContentSize.height - margine
        val y = if (sopra >= 0) sopra else anchorBounds.bottom + margine
        return IntOffset(x, y)
    }
}
