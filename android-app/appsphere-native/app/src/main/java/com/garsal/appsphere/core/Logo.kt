package com.garsal.appsphere.core

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * Il marchio di AppSphere: **cinque cerchi** — arancio, rosso, verde e viola
 * che si toccano a due a due, e il blu al centro sopra a tutti.
 *
 * Disegnato e non importato come immagine, per la stessa ragione dei cerchi
 * olimpici che c'erano prima: sono cinque cerchi, e così si adattano a
 * qualsiasi dimensione senza portarsi dietro un asset per ogni densità. Una
 * funzione sola per tutto il nativo — la barra in alto, il login, la
 * biometria — invece di un drawable per ogni contesto.
 *
 * ⚠️ **Il disegno vive anche fuori di qui**, e va cambiato insieme: i due
 * `ic_launcher_foreground.xml` (le icone di lancio dei due APK, che *devono*
 * essere XML) e l'SVG in linea nella barra delle pagine web. È già successo
 * che divergessero — i launcher erano passati a questo marchio e le barre
 * mostravano ancora i cerchi olimpici, cioè il logo di due generazioni prima.
 *
 * @param disco un fondo bianco tondo dietro il marchio. ⚠️ Non è decorazione:
 *   sulla barra in alto, che è blu (`Palette.topBar`, #0081C8), il cerchio
 *   centrale del marchio è blu pure lui (#067BC0) e senza fondo il pezzo che
 *   regge tutto il disegno sparisce nel colore della barra. Sulle schermate
 *   chiare — login, biometria — non serve, e il marchio si prende tutta la
 *   tela.
 */
@Composable
fun LogoAppSphere(modifier: Modifier = Modifier, disco: Boolean = false) {
    Canvas(modifier) {
        val lato   = size.minDimension
        val centro = Offset(size.width / 2f, size.height / 2f)

        if (disco) drawCircle(Color.White, radius = lato / 2f, center = centro)

        // Raggio e distanza dal centro coincidono: è così che i quattro cerchi
        // esterni si toccano a due a due. Senza disco il marchio arriva al
        // bordo (r + r = metà lato); col disco si stringe per lasciare il
        // margine bianco — sono gli stessi 12/64 del `logo_appsphere` che
        // questa funzione ha sostituito.
        val r = if (disco) lato * 0.1875f else lato / 4f

        listOf(
            Offset(centro.x - r, centro.y - r) to LogoColori.arancio,
            Offset(centro.x + r, centro.y - r) to LogoColori.rosso,
            Offset(centro.x - r, centro.y + r) to LogoColori.verde,
            Offset(centro.x + r, centro.y + r) to LogoColori.viola,
        ).forEach { (dove, colore) -> drawCircle(colore, radius = r, center = dove) }

        // il blu va disegnato per ultimo: sta sopra a tutti
        drawCircle(LogoColori.blu, radius = r, center = centro)
    }
}

/**
 * I cinque colori del marchio. ⚠️ Sono gli stessi valori dei
 * `ic_launcher_foreground.xml` e dell'SVG delle pagine: se ne cambi uno,
 * cambialo in tutti e tre i posti.
 *
 * Non stanno in `Palette` di proposito: quella è la tavolozza dell'interfaccia
 * — e `Palette.olimpici`, che le bolle della home usano ancora, è un'altra
 * cosa ancora. Questi cinque valori appartengono al marchio e si toccano solo
 * quando cambia il marchio.
 */
private object LogoColori {
    val arancio = Color(0xFFDE8026)
    val rosso   = Color(0xFFCA372E)
    val verde   = Color(0xFF32974C)
    val viola   = Color(0xFF824FA5)
    val blu     = Color(0xFF067BC0)
}
