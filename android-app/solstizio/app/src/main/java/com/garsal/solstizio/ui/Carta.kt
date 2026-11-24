package com.garsal.solstizio.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.garsal.solstizio.Archivio
import com.garsal.solstizio.Astro
import com.garsal.solstizio.Meteo
import com.garsal.solstizio.Periodo
import com.garsal.solstizio.Tema
import com.garsal.solstizio.Temperature
import com.garsal.solstizio.oreLuce
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val IT = Locale.ITALIAN
private val DATA_LUNGA = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", IT)
private val ORA = DateTimeFormatter.ofPattern("HH:mm", IT)

private sealed interface StatoMeteo {
    data object Carico : StatoMeteo
    /** [avviso]: le temperature ci sono ma vengono da una lettura vecchia. */
    data class Pronto(val t: Temperature, val avviso: String? = null) : StatoMeteo
    data class Manca(val motivo: String) : StatoMeteo
}

/**
 * La carta di un giorno. Si apre col retro dorato se non è ancora stata
 * scoperta; un tocco la gira. Una carta già scoperta si apre sul davanti, e
 * toccandola si rigira — è un gioco, non un archivio.
 */
@Composable
fun DialogoCarta(
    giorno: LocalDate,
    oggi: LocalDate,
    periodo: Periodo,
    scoperta: Boolean,
    onScopri: () -> Unit,
    onChiudi: () -> Unit,
) {
    val ctx = LocalContext.current
    val evento = giorno == periodo.ultimo
    val tema = Tema.di(giorno, evento)
    var girata by remember { mutableStateOf(scoperta) }
    val rot by animateFloatAsState(if (girata) 180f else 0f, tween(700), label = "giro")
    var meteo by remember { mutableStateOf<StatoMeteo>(StatoMeteo.Carico) }
    var tentativo by remember { mutableIntStateOf(0) }

    LaunchedEffect(giorno, tentativo) {
        // ⚠️ Prima la fotografia congelata, poi la rete: una carta di ieri deve
        // dire il tempo di ieri, anche quando le previsioni sono già andate oltre.
        val congelata = Archivio.temperatureDi(ctx, giorno)
        meteo = congelata?.let { StatoMeteo.Pronto(it) } ?: StatoMeteo.Carico
        if (giorno == oggi) {
            val l = Meteo.temperature(ctx, giorno, forza = tentativo > 0)
            when {
                l.t != null -> {
                    meteo = StatoMeteo.Pronto(l.t, l.errore?.let { "ultima lettura: $it" })
                    Archivio.congela(ctx, giorno, l.t)
                }
                congelata == null -> meteo = StatoMeteo.Manca(l.errore ?: "dati non disponibili")
            }
        } else if (congelata == null) {
            meteo = StatoMeteo.Manca("questa carta è stata scoperta senza le temperature")
        }
    }

    Dialog(onDismissRequest = onChiudi, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onChiudi)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        rotationY = rot
                        cameraDistance = 14f * density
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        if (!girata && !scoperta) onScopri()
                        girata = !girata
                    },
            ) {
                // Il davanti decide l'altezza della carta; il retro le si appoggia
                // sopra con la stessa misura. È ruotato di 180° in partenza, così a
                // fine giro si legge dritto.
                Fronte(
                    giorno, oggi, periodo, tema, evento, meteo,
                    Modifier
                        .graphicsLayer { rotationY = 180f }
                        .alpha(if (rot >= 90f) 1f else 0f),
                )
                if (rot < 90f) {
                    Box(Modifier.matchParentSize()) {
                        RetroDorato(Modifier.fillMaxSize())
                        Text(
                            if (scoperta) "Tocca per girare" else "✨ Tocca per scoprire la carta di oggi",
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(28.dp),
                            color = Color(0xFF4A3708),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Il riprova sta fuori dalla carta: dentro, un tocco la girerebbe.
                if (giorno == oggi && meteo !is StatoMeteo.Carico &&
                    (meteo is StatoMeteo.Manca || (meteo as? StatoMeteo.Pronto)?.avviso != null)
                ) {
                    OutlinedButton(onClick = { tentativo++ }) { Text("↻ Riprova il meteo", color = Oro3) }
                }
                Button(
                    onClick = onChiudi,
                    colors = ButtonDefaults.buttonColors(containerColor = Oro2, contentColor = Color(0xFF2B1F04)),
                ) { Text("Chiudi") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Fronte(
    giorno: LocalDate,
    oggi: LocalDate,
    periodo: Periodo,
    tema: Tema,
    evento: Boolean,
    meteo: StatoMeteo,
    modifier: Modifier,
) {
    val sole = remember(giorno) { Astro.sole(giorno) }
    val ieri = remember(giorno) { Astro.sole(giorno.minusDays(1)) }
    val mancano = ChronoUnit.DAYS.between(giorno, periodo.ultimo)

    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1D3A))
            .border(3.dp, Oro2, RoundedCornerShape(20.dp)),
    ) {
        Illustrazione(tema, giorno, Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (evento) "${periodo.prossimo.tipo.emoji} ${periodo.prossimo.tipo.nome}" else "${tema.emoji} ${tema.nome}",
                color = Oro3, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
            )
            Text(
                giorno.format(DATA_LUNGA).replaceFirstChar { it.uppercase() },
                color = Color(0xFFCFD8E3), style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when {
                    evento -> "Oggi alle ${periodo.prossimo.istante.format(ORA)}: è il giorno!"
                    mancano == 1L -> "Domani: ${periodo.prossimo.tipo.nome}"
                    else -> "Ancora $mancano giorni a: ${periodo.prossimo.tipo.nome}"
                },
                color = Color(0xFFFFD54F), style = MaterialTheme.typography.bodyMedium,
            )
        }
        RiquadroTemperature(meteo)
        // Il testo che scorre: una riga sola, e scorre invece di andare a capo —
        // coi caratteri grandi un paragrafo spingerebbe la carta fuori schermo.
        Text(
            testoCheScorre(sole, ieri, remember(periodo) { Astro.sole(periodo.ultimo) }, periodo, meteo),
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .background(Color(0xFF06122A))
                .padding(vertical = 10.dp)
                .basicMarquee(iterations = Int.MAX_VALUE),
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun gradi(v: Double?) = v?.let { String.format(IT, "%.1f°", it) } ?: "—"

private fun testoCheScorre(
    sole: com.garsal.solstizio.Sole,
    ieri: com.garsal.solstizio.Sole,
    solstizio: com.garsal.solstizio.Sole,
    periodo: Periodo,
    meteo: StatoMeteo,
): String {
    val diff = sole.minutiLuce - ieri.minutiLuce
    val varia = when {
        diff > 0 -> " (+$diff min da ieri)"
        diff < 0 -> " ($diff min da ieri)"
        else -> " (come ieri)"
    }
    val pezzi = mutableListOf(
        "🌅 Alba ${sole.alba.format(ORA)}",
        "🌇 Tramonto ${sole.tramonto.format(ORA)}",
        "☀️ ${oreLuce(sole.minutiLuce)} di luce$varia",
        "${periodo.prossimo.tipo.emoji} ${periodo.prossimo.tipo.al} ${oreLuce(solstizio.minutiLuce)}",
    )
    if (meteo is StatoMeteo.Pronto) {
        val t = meteo.t
        if (t.min != null || t.max != null) pezzi += "🌡️ Minima ${gradi(t.min)} · Massima ${gradi(t.max)}"
        if (t.media != null) pezzi += "Media del giorno ${gradi(t.media)}"
        if (t.storica != null) pezzi += "Media storica ${gradi(t.storica)}"
    }
    pezzi += "📍 ${com.garsal.solstizio.Luogo.NOME}"
    return pezzi.joinToString("     ·     ") + "          "
}

/**
 * Le temperature in vista, ferme: nel testo che scorre arrivavano per ultime
 * e dopo parecchi secondi, e non si vedevano. Se mancano si dice perché.
 */
@Composable
private fun RiquadroTemperature(meteo: StatoMeteo) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF))
            .padding(12.dp),
    ) {
        when (meteo) {
            StatoMeteo.Carico -> Text("🌡️ Leggo le previsioni…", color = Color(0xFFCFD8E3))
            is StatoMeteo.Manca -> Text("🌡️ Temperature non disponibili: ${meteo.motivo}", color = Color(0xFFFFAB91))
            is StatoMeteo.Pronto -> {
                val t = meteo.t
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🌡️", style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("Minima", color = Color(0xFF90CAF9), style = MaterialTheme.typography.labelMedium)
                        Text(gradi(t.min), color = Color.White, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Massima", color = Color(0xFFFFAB91), style = MaterialTheme.typography.labelMedium)
                        Text(gradi(t.max), color = Color.White, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Text("Media del giorno ${gradi(t.media)} · media storica ${gradi(t.storica)}",
                    color = Color(0xFFCFD8E3), style = MaterialTheme.typography.bodyMedium)
                meteo.avviso?.let {
                    Text("⚠️ $it", color = Color(0xFFFFCC80), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
