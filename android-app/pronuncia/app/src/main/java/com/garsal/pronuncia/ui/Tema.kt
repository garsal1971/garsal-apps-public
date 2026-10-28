package com.garsal.pronuncia.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Il tema: blu e rosso della bandiera britannica, su fondo carta.
 *
 * L'app si usa quasi sempre a schermo spento: questi colori servono ai pochi
 * momenti in cui la si guarda — avviare, fermare, rileggere lo storico.
 */
private val Blu        = Color(0xFF012169)
private val BluChiaro  = Color(0xFFAFC2FF)
private val Rosso      = Color(0xFFC8102E)
private val RossoChiaro = Color(0xFFFFB3B3)
private val Carta      = Color(0xFFF6F5F1)
private val Notte      = Color(0xFF0F1B33)

private val Chiaro = lightColorScheme(
    primary = Blu,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Rosso,
    onSecondary = Color.White,
    background = Carta,
    onBackground = Color(0xFF14171F),
    surface = Color.White,
    onSurface = Color(0xFF14171F),
    surfaceVariant = Color(0xFFE4E6EE),
    onSurfaceVariant = Color(0xFF4A4F5C),
    outline = Color(0xFFB6BACA),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val Scuro = darkColorScheme(
    primary = BluChiaro,
    onPrimary = Color(0xFF00218C),
    primaryContainer = Color(0xFF1C2E6B),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = RossoChiaro,
    onSecondary = Color(0xFF5F0013),
    background = Notte,
    onBackground = Color(0xFFE6E8F0),
    surface = Color(0xFF172440),
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF26324F),
    onSurfaceVariant = Color(0xFFA6AEC4),
    outline = Color(0xFF3A4766),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun TemaPronuncia(scuro: Boolean = isSystemInDarkTheme(), contenuto: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (scuro) Scuro else Chiaro, content = contenuto)
}
