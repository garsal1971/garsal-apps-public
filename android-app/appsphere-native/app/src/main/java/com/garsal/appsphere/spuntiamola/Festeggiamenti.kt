package com.garsal.appsphere.spuntiamola

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val CONFETTI_COLORS = listOf(
    Color(0xFFFF3366), Color(0xFF6C5CE7), Color(0xFF00B894), Color(0xFFF39C12),
    Color(0xFF2563EB), Color(0xFFFFD700), Color(0xFF38EF7D),
)

private val FW_COLORS = listOf(
    Color(0xFFFFD700), Color(0xFFFF3366), Color(0xFF6C5CE7), Color(0xFF00E5A0),
    Color(0xFFFF8A00), Color(0xFF41C7FF), Color(0xFFFF5FD2), Color(0xFFFFFFFF),
)

private class Particella(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val colore: Color,
    val lato: Float,
    var vita: Float,           // da 1 a 0
    val decadimento: Float,
    val coriandolo: Boolean,   // rettangolo che ruota, invece di scintilla tonda
    var rotazione: Float = 0f,
    val giro: Float = 0f,
)

/**
 * Coriandoli e fuochi d'artificio, sopra a tutto il resto.
 *
 * Ogni scoppio è un lampo al centro più una raggiera di scintille che partono
 * di lì e ricadono verso il basso — la stessa meccanica di `scoppio()` in
 * spuntiamola.html. Una differenza: là le scintille sono caratteri (★ ✦ ✧),
 * qui sono forme disegnate, perché scrivere testo a ogni fotogramma su un
 * Canvas costa molto più che disegnare cerchi.
 */
@Composable
fun FestaOverlay(festa: Festa?, onFinita: (Long) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Le dimensioni arrivano dai vincoli e non dal Canvas: scriverle da
        // dentro il disegno significherebbe far ridisegnare ciò che le legge,
        // cioè un ciclo di ricomposizione a ogni fotogramma.
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        CampoParticelle(festa = festa, w = w, h = h, onFinita = onFinita)
    }
}

@Composable
private fun CampoParticelle(festa: Festa?, w: Float, h: Float, onFinita: (Long) -> Unit) {
    val particelle = remember { mutableListOf<Particella>() }
    var battito by remember { mutableIntStateOf(0) }

    LaunchedEffect(festa?.id, w, h) {
        val f = festa ?: return@LaunchedEffect
        if (w <= 0f || h <= 0f) return@LaunchedEffect

        // I coriandoli partono tutti insieme dall'alto e cadono.
        repeat(f.coriandoli) {
            particelle += Particella(
                x = Random.nextFloat() * w,
                y = -Random.nextFloat() * h * 0.3f,
                vx = (Random.nextFloat() - 0.5f) * 2.2f,
                vy = 3.5f + Random.nextFloat() * 4f,
                colore = CONFETTI_COLORS.random(),
                lato = 6f + Random.nextFloat() * 8f,
                vita = 1f,
                decadimento = 0.004f + Random.nextFloat() * 0.004f,
                coriandolo = true,
                giro = (Random.nextFloat() - 0.5f) * 14f,
            )
        }

        // Gli scoppi sono scaglionati: il primo in alto al centro, gli altri
        // sparsi nella metà alta dello schermo.
        val passo = if (f.granFinale) 240L else 320L
        repeat(f.scoppi) { i ->
            val cx = if (i == 0) w / 2f else w * (0.12f + Random.nextFloat() * 0.76f)
            val cy = if (i == 0) h * 0.34f else h * (0.13f + Random.nextFloat() * 0.46f)
            scoppio(particelle, cx, cy)
            if (i < f.scoppi - 1) delay(passo)
        }

        if (f.granFinale) {
            delay(200)
            listOf(0.28f to 0.30f, 0.50f to 0.22f, 0.72f to 0.30f).forEach { (fx, fy) ->
                scoppio(particelle, w * fx, h * fy)
                delay(130)
            }
        }

        // Si anima finché resta qualcosa in aria, poi si avvisa il ViewModel
        // che questa festa è esaurita: senza, una festa identica alla
        // successiva non ripartirebbe.
        while (particelle.isNotEmpty()) {
            withFrameNanos { }
            val iteratore = particelle.iterator()
            while (iteratore.hasNext()) {
                val p = iteratore.next()
                p.x += p.vx
                p.y += p.vy
                p.vy += if (p.coriandolo) 0.06f else 0.16f   // gravità
                p.vx *= 0.995f
                p.rotazione += p.giro
                p.vita -= p.decadimento
                if (p.vita <= 0f || p.y > h + 40f) iteratore.remove()
            }
            battito++
        }
        onFinita(f.id)
    }

    Canvas(Modifier.fillMaxSize()) {
        // Leggere `battito` qui dentro è ciò che fa ridisegnare il Canvas a
        // ogni fotogramma: le particelle stanno in una lista normale, che da
        // sola non farebbe scattare niente.
        @Suppress("UNUSED_EXPRESSION") battito

        particelle.forEach { p ->
            val colore = p.colore.copy(alpha = p.vita.coerceIn(0f, 1f))
            if (p.coriandolo) {
                rotate(degrees = p.rotazione, pivot = Offset(p.x, p.y)) {
                    drawRect(
                        color = colore,
                        topLeft = Offset(p.x - p.lato / 2f, p.y - p.lato / 2f),
                        size = androidx.compose.ui.geometry.Size(p.lato, p.lato * 0.6f),
                    )
                }
            } else {
                drawCircle(color = colore, radius = p.lato / 2f, center = Offset(p.x, p.y))
            }
        }
    }
}

/** Un lampo al centro più una raggiera di scintille con gravità. */
private fun scoppio(particelle: MutableList<Particella>, cx: Float, cy: Float) {
    // il lampo: poche particelle grosse, chiare, che sbiadiscono in fretta
    repeat(6) {
        particelle += Particella(
            x = cx, y = cy,
            vx = (Random.nextFloat() - 0.5f) * 1.5f,
            vy = (Random.nextFloat() - 0.5f) * 1.5f,
            colore = Color.White,
            lato = 26f + Random.nextFloat() * 10f,
            vita = 1f,
            decadimento = 0.06f,
            coriandolo = false,
        )
    }
    // la raggiera
    val quante = 34
    repeat(quante) { i ->
        val angolo = (i.toFloat() / quante) * 2f * PI.toFloat() + Random.nextFloat() * 0.2f
        val velocita = 3.5f + Random.nextFloat() * 5.5f
        particelle += Particella(
            x = cx, y = cy,
            vx = cos(angolo) * velocita,
            vy = sin(angolo) * velocita,
            colore = FW_COLORS.random(),
            lato = 5f + Random.nextFloat() * 6f,
            vita = 1f,
            decadimento = 0.012f + Random.nextFloat() * 0.010f,
            coriandolo = false,
        )
    }
}
