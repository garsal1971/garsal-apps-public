package com.garsal.appsphere.core

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * La stessa palette che le app HTML dichiarano come custom property CSS
 * (blocco `:root` documentato nel CLAUDE.md), tradotta in Material 3.
 *
 * Le app web sono tutte in tema chiaro e non hanno una variante scura: qui si
 * fa lo stesso e `isSystemInDarkTheme()` viene deliberatamente ignorato, invece
 * di inventare colori scuri che sul web non esistono.
 *
 * I font (DM Sans / Space Mono / Darker Grotesque) non sono replicati: sono
 * Google Fonts caricati via CDN dalle pagine, e imbarcarli nell'APK è una
 * scelta a sé. Per ora vale la tipografia di sistema.
 */
object Palette {
    val primary = Color(0xFFFF3366)
    val secondary = Color(0xFF6C5CE7)
    val success = Color(0xFF00B894)
    val warning = Color(0xFFF39C12)
    val danger = Color(0xFFE74C3C)
    val dark = Color(0xFF1F2937)
    val light = Color(0xFFFFFFFF)
    val muted = Color(0xFF6B7280)
    val accent = Color(0xFF2563EB)
    val border = Color(0xFFE5E7EB)
    val cardBg = Color(0xFFFFFFFF)
    val inputBg = Color(0xFFF9FAFB)

    /** Blu della barra in alto, comune a tutte le pagine (#garsal-top-bar). */
    val topBar = Color(0xFF0081C8)

    /** Colori dei cerchi olimpici: fallback quando `cm_apps.color` è vuoto. */
    val olimpici = listOf(
        Color(0xFF0081C8),
        Color(0xFFFCB131),
        Color(0xFF1A1A1A),
        Color(0xFF00A651),
        Color(0xFFEE334E),
    )

    /** Oro delle giornate chiave di Spuntiamola. */
    val oro = Color(0xFFD4A017)
}

private val schema = lightColorScheme(
    primary = Palette.primary,
    onPrimary = Palette.light,
    secondary = Palette.secondary,
    onSecondary = Palette.light,
    tertiary = Palette.accent,
    onTertiary = Palette.light,
    background = Palette.inputBg,
    onBackground = Palette.dark,
    surface = Palette.cardBg,
    onSurface = Palette.dark,
    surfaceVariant = Palette.inputBg,
    onSurfaceVariant = Palette.muted,
    outline = Palette.border,
    error = Palette.danger,
    onError = Palette.light,
)

@Composable
fun AppSphereTheme(content: @Composable () -> Unit) {
    // Nessuna variante scura, di proposito: vedi il commento in cima.
    MaterialTheme(colorScheme = schema, content = content)
}

/** `#RRGGBB` come lo scrive `cm_apps.color`; se non si legge, torna null. */
fun coloreDaHex(hex: String?): Color? {
    val pulito = hex?.trim()?.removePrefix("#") ?: return null
    if (pulito.length != 6) return null
    val v = pulito.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}
