package com.garsal.smartblocker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Rettangolo "tieni premuto per confermare": a riposo è vuoto (solo bordo colorato),
 * mentre viene tenuto premuto si riempie progressivamente del colore.
 * L'invio (onConfirm) scatta SOLO se il rilascio avviene dopo il riempimento completo;
 * un rilascio anticipato annulla senza inviare nulla.
 */
fun buildHoldConfirmButton(
    ctx: Context,
    handler: Handler,
    label: String,
    colorHex: String,
    holdMs: Long,
    onConfirm: () -> Unit
): FrameLayout {
    val color = Color.parseColor(colorHex)

    val container = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setStroke(6, color)
            setColor(Color.TRANSPARENT)
        }
    }

    val fill = View(ctx).apply {
        setBackgroundColor(color)
        scaleX = 0f
        pivotX = 0f
    }
    container.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    container.addView(TextView(ctx).apply {
        text = label
        textSize = 20f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    var animator: ValueAnimator? = null
    var fillRunnable: Runnable? = null
    var filled = false

    container.setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                filled = false
                animator?.cancel()
                fillRunnable?.let { handler.removeCallbacks(it) }

                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = holdMs
                    addUpdateListener { a -> fill.scaleX = a.animatedValue as Float }
                    start()
                }
                val r = Runnable { filled = true }
                fillRunnable = r
                handler.postDelayed(r, holdMs)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasFilled = filled
                animator?.cancel()
                fillRunnable?.let { handler.removeCallbacks(it) }
                fill.scaleX = 0f
                filled = false
                if (wasFilled) onConfirm()
                true
            }
            else -> false
        }
    }

    return container
}
