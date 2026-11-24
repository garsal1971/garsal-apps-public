package com.garsal.solstizio.ui

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garsal.solstizio.Archivio
import com.garsal.solstizio.Astro
import com.garsal.solstizio.DialogoAggiornamento
import com.garsal.solstizio.Luogo
import com.garsal.solstizio.Tema
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val IT = Locale.ITALIAN
private val MESE = DateTimeFormatter.ofPattern("MMMM yyyy", IT)
private val DATA_EVENTO = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'alle' HH:mm", IT)
private val GIORNO_BREVE = DateTimeFormatter.ofPattern("d MMMM", IT)

private val Notte = Color(0xFF0B1D3A)
private val NotteChiara = Color(0xFF16305C)
private val Testo2 = Color(0xFFB8C4D6)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Home(oggi: LocalDate) {
    val ctx = LocalContext.current
    val periodo = remember(oggi) { Astro.periodo(oggi) }
    var scoperte by remember { mutableStateOf(Archivio.scoperte(ctx)) }
    var aperta by remember { mutableStateOf<LocalDate?>(null) }
    var menu by remember { mutableStateOf(false) }
    var versione by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }

    val giorni = periodo.giorni
    val mancano = ChronoUnit.DAYS.between(oggi, periodo.ultimo)
    val fatti = giorni.count { it in scoperte }
    val passati = giorni.count { !it.isAfter(oggi) }
    // Le caselle crescono coi caratteri di sistema, con un tetto: il numero
    // dentro non deve mai essere più grande della casella.
    val scala = LocalDensity.current.fontScale.coerceIn(1f, 1.8f)
    val lato = 44.dp * scala

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF020617), Notte, NotteChiara)))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── barra in alto ───────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "☀️ Solstizio", Modifier.weight(1f), color = Oro3,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box {
                Text(
                    "⚙️", Modifier.clip(CircleShape).clickable { menu = true }.padding(10.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("ℹ️ Come funziona") }, { menu = false; info = true })
                    DropdownMenuItem({ Text("📱 Versione app") }, { menu = false; versione = true })
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 24.dp)) {
            // ── il conto alla rovescia ──────────────────────────────────
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x22FFFFFF))
                        .border(1.dp, Oro2.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val p = periodo.prossimo
                    Text("${p.tipo.emoji} ${p.tipo.nome}", color = Oro3, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(p.istante.format(DATA_EVENTO), color = Testo2, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (mancano == 0L) "È oggi!" else "$mancano",
                        color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center,
                    )
                    if (mancano > 0) Text(if (mancano == 1L) "giorno che manca" else "giorni che mancano",
                        color = Testo2, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { passati.toFloat() / giorni.size },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = Oro2, trackColor = Color(0x33FFFFFF),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("🃏 $fatti carte scoperte su ${giorni.size} giorni", color = Testo2,
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    val s = Astro.sole(oggi)
                    Text("📍 ${Luogo.NOME} · oggi ${s.minutiLuce / 60} h ${"%02d".format(s.minutiLuce % 60)} min di luce",
                        color = Testo2, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                if (oggi !in scoperte) {
                    Text(
                        "✨ La carta di oggi ti aspetta: tocca la casella dorata",
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Oro2)
                            .clickable { aperta = oggi }
                            .padding(14.dp),
                        color = Color(0xFF2B1F04), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ── la stecca, mese per mese ────────────────────────────────
            items(giorni.groupBy { it.withDayOfMonth(1) }.toList(), key = { it.first.toString() }) { (mese, gg) ->
                Text(
                    mese.format(MESE).replaceFirstChar { it.uppercase() },
                    Modifier.padding(top = 8.dp, bottom = 6.dp),
                    color = Oro3, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    gg.forEach { g ->
                        Casella(
                            g, oggi, lato,
                            scoperta = g in scoperte,
                            evento = g == periodo.ultimo,
                            onTocca = {
                                when {
                                    g in scoperte || g == oggi -> aperta = g
                                    g.isAfter(oggi) -> Toast.makeText(ctx,
                                        "Questa carta si scopre il ${g.format(GIORNO_BREVE)}", Toast.LENGTH_SHORT).show()
                                    else -> Toast.makeText(ctx,
                                        "Il ${g.format(GIORNO_BREVE)} è passato senza carta", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    aperta?.let { g ->
        DialogoCarta(
            g, oggi, periodo, scoperta = g in scoperte,
            onScopri = { Archivio.scopri(ctx, g); scoperte = Archivio.scoperte(ctx) },
            onChiudi = { aperta = null },
        )
    }
    if (versione) DialogoAggiornamento { versione = false }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        title = { Text("☀️ Come funziona") },
        text = {
            Text(
                "Ogni casella è un giorno, fino al prossimo solstizio o equinozio. " +
                    "Ogni giorno si scopre una carta: tocca la casella dorata, poi tocca " +
                    "il retro d'oro per girarla. Il Sole, le Stelle o il Mare, con alba, " +
                    "tramonto, ore di luce e temperature di ${Luogo.NOME}.\n\n" +
                    "Le carte già scoperte si rivedono quando vuoi; un giorno passato " +
                    "senza girarla resta grigio. Il giorno dopo l'evento la stecca riparte " +
                    "da sola verso il successivo.\n\nTemperature: Open-Meteo.",
            )
        },
        confirmButton = { TextButton({ info = false }) { Text("Ho capito") } },
    )
}

@Composable
private fun Casella(
    g: LocalDate,
    oggi: LocalDate,
    lato: androidx.compose.ui.unit.Dp,
    scoperta: Boolean,
    evento: Boolean,
    onTocca: () -> Unit,
) {
    val eOggi = g == oggi
    val forma = RoundedCornerShape(10.dp)
    val pulsa = if (eOggi && !scoperta) {
        val t = rememberInfiniteTransition(label = "oggi")
        t.animateFloat(1f, 1.1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "battito").value
    } else 1f
    val fondo: Brush = when {
        scoperta -> Brush.linearGradient(listOf(Oro1, Oro2, Oro3))
        eOggi -> Brush.linearGradient(listOf(Oro2, Oro3, Oro2))
        g.isBefore(oggi) -> Brush.linearGradient(listOf(Color(0xFF39414F), Color(0xFF39414F)))
        else -> Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF)))
    }
    Box(
        Modifier
            .size(lato)
            .scale(pulsa)
            .clip(forma)
            .background(fondo)
            .border(
                if (evento || eOggi) 2.dp else 1.dp,
                when { evento -> Color(0xFFFFF59D); eOggi -> Color.White; else -> Color(0x33FFFFFF) },
                forma,
            )
            .clickable(onClick = onTocca),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                scoperta -> Tema.di(g, evento).emoji
                evento -> "🌟"
                else -> "${g.dayOfMonth}"
            },
            color = when {
                scoperta || eOggi -> Color(0xFF2B1F04)
                g.isBefore(oggi) -> Color(0xFF8A93A3)
                else -> Color.White
            },
            fontWeight = if (eOggi) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
    }
}
