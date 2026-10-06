package com.garsal.sos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// ── Misure ──────────────────────────────────────────────────────────────────
fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun colore(hex: String, fallback: Int = Color.parseColor("#EE334E")): Int =
    try { Color.parseColor(hex) } catch (_: Exception) { fallback }

/** Lo stesso colore, più scuro: serve per gli sfondi, dove il colore pieno del SOS
    accecherebbe e renderebbe illeggibile il countdown sopra. */
fun scurisci(color: Int, fattore: Float): Int = Color.rgb(
    (Color.red(color)   * fattore).toInt().coerceIn(0, 255),
    (Color.green(color) * fattore).toInt().coerceIn(0, 255),
    (Color.blue(color)  * fattore).toInt().coerceIn(0, 255)
)

fun sfondoTondo(color: Int, radiusPx: Int, strokePx: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

// ── Testi ───────────────────────────────────────────────────────────────────
/**
 * ⚠️ Niente altezze fisse attorno al testo, da nessuna parte in questa app.
 * Sul telefono l'ingrandimento dei caratteri di sistema è alto: una riga sola è
 * un'ipotesi, non un dato, e un contenitore alto quanto la scritta di anteprima
 * taglia via quel che avanza. Qui si usa sempre WRAP_CONTENT o una minima.
 */
fun testo(
    ctx: Context,
    txt: String,
    sizeSp: Float,
    color: Int = Color.WHITE,
    bold: Boolean = false,
    center: Boolean = true,
    alpha: Float = 1f
) = TextView(ctx).apply {
    text = txt
    textSize = sizeSp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    if (center) gravity = Gravity.CENTER
    this.alpha = alpha
    setLineSpacing(0f, 1.15f)
}

fun LinearLayout.aggiungi(v: View, topDp: Int = 0, ctx: Context = context) {
    val lp = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.topMargin = ctx.dp(topDp)
    addView(v, lp)
}

// ── «Tieni premuto» ─────────────────────────────────────────────────────────
/**
 * Rettangolo che si riempie mentre lo si tiene premuto e conferma **al rilascio**,
 * solo se il riempimento è arrivato in fondo. È l'unica via d'uscita dal countdown:
 * deve costare un gesto voluto, non un tocco distratto sul bordo dello schermo.
 */
fun buildHoldButton(
    ctx: Context,
    handler: Handler,
    label: String,
    labelPronto: String,
    color: Int,
    holdMs: Long,
    onConfirm: () -> Unit
): FrameLayout {
    val container = FrameLayout(ctx).apply {
        background = sfondoTondo(Color.TRANSPARENT, ctx.dp(14), ctx.dp(2), color)
        minimumHeight = ctx.dp(56)
    }

    val fill = View(ctx).apply {
        background = sfondoTondo(color, ctx.dp(14))
        alpha = 0.55f
        scaleX = 0f
        pivotX = 0f
    }
    container.addView(fill, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val tv = testo(ctx, label, 16f, Color.WHITE, bold = true).apply {
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
    }
    container.addView(tv, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

    var animator: ValueAnimator? = null
    var pronto: Runnable? = null
    var pieno = false

    container.setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pieno = false
                animator?.cancel()
                pronto?.let { handler.removeCallbacks(it) }
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = holdMs
                    addUpdateListener { a -> fill.scaleX = a.animatedValue as Float }
                    start()
                }
                val r = Runnable { pieno = true; tv.text = labelPronto }
                pronto = r
                handler.postDelayed(r, holdMs)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val eraPieno = pieno
                animator?.cancel()
                pronto?.let { handler.removeCallbacks(it) }
                fill.scaleX = 0f
                pieno = false
                tv.text = label
                if (eraPieno) onConfirm()
                true
            }
            else -> false
        }
    }
    return container
}
