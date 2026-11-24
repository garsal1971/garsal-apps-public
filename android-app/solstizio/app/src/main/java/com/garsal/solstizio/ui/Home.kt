package com.garsal.solstizio.ui

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garsal.solstizio.Archivio
import com.garsal.solstizio.Astro
import com.garsal.solstizio.DialogoAggiornamento
import com.garsal.solstizio.Rilascio
import com.garsal.solstizio.Luogo
import com.garsal.solstizio.Tema
import com.garsal.solstizio.oreLuce
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val IT = Locale.ITALIAN
private val GIORNO_LUNGO = DateTimeFormatter.ofPattern("EEEE d MMMM", IT)
private val DATA_EVENTO = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'alle' HH:mm", IT)

private val Notte = Color(0xFF0B1D3A)
private val NotteChiara = Color(0xFF16305C)
private val Testo2 = Color(0xFFB8C4D6)

@Composable
fun Home(oggi: LocalDate) {
    val ctx = LocalContext.current
    val periodo = remember(oggi) { Astro.periodo(oggi) }
    var scoperte by remember { mutableStateOf(Archivio.scoperte(ctx)) }
    var aperta by remember { mutableStateOf<LocalDate?>(null) }
    var menu by remember { mutableStateOf(false) }
    var versione by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var elenco by remember { mutableStateOf(false) }
    // Una versione più nuova di quella installata, se il controllo all'avvio la trova.
    var nuova by remember { mutableStateOf<Rilascio.Scheda?>(null) }
    LaunchedEffect(Unit) {
        val s = Rilascio.scheda().getOrNull() ?: return@LaunchedEffect
        if (s.versionCode > Rilascio.installata(ctx).second) nuova = s
    }

    val giorni = periodo.giorni
    val mancano = ChronoUnit.DAYS.between(oggi, periodo.ultimo)
    val passati = giorni.count { !it.isAfter(oggi) }

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
            // ── l'avviso di aggiornamento ───────────────────────────────
            nuova?.let { n ->
                item {
                    Text(
                        "⬇ C'è la v${n.version} — tocca per aggiornare",
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Oro2)
                            .clickable { versione = true }
                            .padding(14.dp),
                        color = Color(0xFF2B1F04), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
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
                    val s = Astro.sole(oggi)
                    val sl = Astro.sole(periodo.ultimo)
                    Text("☀️ Oggi ${oreLuce(s.minutiLuce)} di luce · ${periodo.prossimo.tipo.al} ${oreLuce(sl.minutiLuce)}",
                        color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Text("📍 ${Luogo.NOME}", color = Testo2, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))

                // ── i giorni passati: un rettangolo solo ────────────────
                val prima = giorni.filter { it.isBefore(oggi) }
                val carteVecchie = prima.count { it in scoperte }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x22FFFFFF))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                        .clickable {
                            if (carteVecchie > 0) elenco = true
                            else Toast.makeText(ctx, "Nessuna carta scoperta nei giorni passati", Toast.LENGTH_SHORT).show()
                        }
                        .padding(16.dp),
                ) {
                    Text("📜 ${prima.size} " + (if (prima.size == 1) "giorno passato" else "giorni passati"),
                        color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "🃏 $carteVecchie " + (if (carteVecchie == 1) "carta scoperta" else "carte scoperte") +
                            (if (carteVecchie > 0) " · tocca per rivederle" else ""),
                        color = Testo2, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(16.dp))

                // ── la carta di oggi ────────────────────────────────────
                CartaDiOggi(oggi, scoperta = oggi in scoperte, evento = oggi == periodo.ultimo) { aperta = oggi }
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
    if (elenco) AlertDialog(
        onDismissRequest = { elenco = false },
        title = { Text("🃏 Le carte scoperte") },
        text = {
            // Dalla più recente: è quella che si cerca più spesso.
            val carte = giorni.filter { it.isBefore(oggi) && it in scoperte }.sortedDescending()
            LazyColumn {
                items(carte, key = { it.toString() }) { g ->
                    val t = Tema.di(g, g == periodo.ultimo)
                    Text(
                        "${t.emoji}  ${g.format(GIORNO_LUNGO).replaceFirstChar { it.uppercase() }} · ${t.nome}",
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { elenco = false; aperta = g }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton({ elenco = false }) { Text("Chiudi") } },
    )
    if (versione) DialogoAggiornamento { versione = false }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        title = { Text("☀️ Come funziona") },
        text = {
            Text(
                "Conto alla rovescia fino al prossimo solstizio o equinozio. " +
                    "Ogni giorno si scopre una carta: tocca la carta dorata di oggi, poi " +
                    "tocca il retro d'oro per girarla. Il Sole, le Stelle o il Mare, con alba, " +
                    "tramonto, ore di luce e temperature di ${Luogo.NOME}.\n\n" +
                    "Le carte dei giorni passati si rivedono dal riquadro «giorni passati»: " +
                    "la carta di un giorno si scopre solo quel giorno. Il giorno dopo " +
                    "l'evento il conto riparte da solo verso il successivo.\n\nTemperature: Open-Meteo.",
            )
        },
        confirmButton = { TextButton({ info = false }) { Text("Ho capito") } },
    )
}

/** La carta di oggi, in piccolo: dorata e pulsante finché non è scoperta. */
@Composable
private fun CartaDiOggi(oggi: LocalDate, scoperta: Boolean, evento: Boolean, onTocca: () -> Unit) {
    val forma = RoundedCornerShape(16.dp)
    val pulsa = if (!scoperta) {
        val t = rememberInfiniteTransition(label = "oggi")
        t.animateFloat(1f, 1.03f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "battito").value
    } else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .scale(pulsa)
            .clip(forma)
            .background(Brush.linearGradient(listOf(Oro1, Oro2, Oro3, Oro2)))
            .border(2.dp, if (evento) Color(0xFFFFF59D) else Color.White, forma)
            .clickable(onClick = onTocca)
            .heightIn(min = 88.dp)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (scoperta) Tema.di(oggi, evento).emoji else "✨",
            style = MaterialTheme.typography.displaySmall,
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                if (scoperta) "La carta di oggi" else "La carta di oggi ti aspetta",
                color = Color(0xFF2B1F04), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
            )
            Text(
                oggi.format(GIORNO_LUNGO).replaceFirstChar { it.uppercase() } +
                    if (scoperta) " · tocca per rivederla" else " · tocca per girarla",
                color = Color(0xFF3E2C06), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
