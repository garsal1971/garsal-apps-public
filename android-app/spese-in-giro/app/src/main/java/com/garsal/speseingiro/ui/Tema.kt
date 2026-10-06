package com.garsal.speseingiro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Il tema: una strada di campagna al mattino.
 *
 * Il verde è quello della salita e della segnaletica ciclabile, l'ocra è lo
 * sterrato, il fondo è la carta di una mappa. Il chiaro è quello che si guarda
 * col sole in faccia — cioè il caso normale, in bici — e lo scuro esiste perché
 * il telefono lo decide da sé la sera.
 */
private val VerdeSalita   = Color(0xFF0F6E4F)
private val VerdeChiaro   = Color(0xFF63C69C)
private val OcraSterrato  = Color(0xFF9A6B12)
private val OcraChiara    = Color(0xFFE0AE55)
private val CartaMappa    = Color(0xFFF5F3EC)
private val InchiostroDi  = Color(0xFF15201B)
private val NotteStrada   = Color(0xFF12211B)
private val Rosso         = Color(0xFFB3261E)
private val RossoChiaro   = Color(0xFFFFB4AB)

private val Chiaro = lightColorScheme(
    primary = VerdeSalita,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEBDD),
    onPrimaryContainer = Color(0xFF06301F),
    secondary = OcraSterrato,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E5C4),
    onSecondaryContainer = Color(0xFF3A2A05),
    background = CartaMappa,
    onBackground = InchiostroDi,
    surface = Color.White,
    onSurface = InchiostroDi,
    surfaceVariant = Color(0xFFE7E4DA),
    onSurfaceVariant = Color(0xFF4B5651),
    outline = Color(0xFFB9C2BB),
    error = Rosso,
    onError = Color.White,
)

private val Scuro = darkColorScheme(
    primary = VerdeChiaro,
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF17503A),
    onPrimaryContainer = Color(0xFFCFEBDD),
    secondary = OcraChiara,
    onSecondary = Color(0xFF3A2A05),
    secondaryContainer = Color(0xFF4E3A0C),
    onSecondaryContainer = Color(0xFFF6E5C4),
    background = NotteStrada,
    onBackground = Color(0xFFE9EEE9),
    surface = Color(0xFF18211D),
    onSurface = Color(0xFFE9EEE9),
    surfaceVariant = Color(0xFF2A342E),
    onSurfaceVariant = Color(0xFF9AA79F),
    outline = Color(0xFF3B4841),
    error = RossoChiaro,
    onError = Color(0xFF690005),
)

@Composable
fun TemaSpeseInGiro(scuro: Boolean = isSystemInDarkTheme(), contenuto: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (scuro) Scuro else Chiaro, content = contenuto)
}

/* ── I formati ─────────────────────────────────────────────────────────────
   Le date si archiviano ISO e si mostrano all'italiana, come in tutta la suite. */

private val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY)
private val IT  = SimpleDateFormat("d MMM yyyy", Locale.ITALY)
private val IT_CORTA = SimpleDateFormat("d MMM", Locale.ITALY)

fun euro(v: Double): String = String.format(Locale.ITALY, "%,.2f €", v)

fun dataIt(iso: String?, corta: Boolean = false): String {
    if (iso.isNullOrBlank()) return ""
    val d = runCatching { ISO.parse(iso.take(10)) }.getOrNull() ?: return iso
    return (if (corta) IT_CORTA else IT).format(d)
}

/** Un istante del registro: giorno e ora, perché «chi ha fatto cosa» senza
 *  «quando» non è un registro. */
fun istanteIt(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val giorno = dataIt(iso, corta = true)
    val ora = iso.substringAfter('T', "").take(5)
    return if (ora.isBlank()) giorno else "$giorno · $ora"
}
