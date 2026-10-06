package com.garsal.appsphere.obiettivi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.coloreDaHex
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun ObiettiviScreen(
    onIndietro: () -> Unit,
    vm: ObiettiviViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var metricaDaRilevare by remember { mutableStateOf<ObMetrica?>(null) }

    Scaffold(
        topBar = { GarsalTopBar(titolo = "Obiettivi", onIndietro = onIndietro) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                stato.caricamento && stato.obiettivi.isEmpty() ->
                    CircularProgressIndicator(
                        color = Palette.secondary,
                        modifier = Modifier.align(Alignment.Center),
                    )

                stato.annuali.isEmpty() ->
                    Text(
                        "Nessun obiettivo attivo.",
                        color = Palette.muted,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(stato.annuali) { obiettivo ->
                        SchedaObiettivo(
                            obiettivo = obiettivo,
                            stato = stato,
                            onRileva = { metricaDaRilevare = it },
                        )
                    }
                }
            }

            stato.errore?.let {
                Text(
                    it,
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
            }

            stato.messaggio?.let { messaggio ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Palette.dark),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                ) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(messaggio, color = Palette.light)
                        Text(
                            "✕",
                            color = Palette.light,
                            modifier = Modifier.clickable { vm.messaggioMostrato() },
                        )
                    }
                }
            }
        }
    }

    metricaDaRilevare?.let { metrica ->
        RilevazioneDialog(
            metrica = metrica,
            onAnnulla = { metricaDaRilevare = null },
            onConferma = { valore, giorno, nota ->
                vm.registra(metrica, valore, giorno, nota)
                metricaDaRilevare = null
            },
        )
    }
}

@Composable
private fun SchedaObiettivo(
    obiettivo: ObObiettivo,
    stato: ObiettiviState,
    onRileva: (ObMetrica) -> Unit,
) {
    var aperto by remember { mutableStateOf(false) }
    val avanzamento = stato.avanzamenti[obiettivo.id]
    val colore = coloreDaHex(obiettivo.color) ?: Palette.secondary

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Semaforo(avanzamento?.status ?: "unknown")
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(obiettivo.title, fontWeight = FontWeight.Bold, color = Palette.dark)
                    Text(
                        text = (if (obiettivo.horizon == "annual") "Annuale" else "Trimestrale") +
                            " · scade il ${obiettivo.dueDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted,
                    )
                }
                Text(
                    if (aperto) "▲" else "▼",
                    color = Palette.muted,
                    modifier = Modifier.clickable { aperto = !aperto },
                )
            }

            if (obiettivo.why.isNotBlank()) {
                Text(
                    "«${obiettivo.why}»",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                )
            }

            // ── Le due barre, affiancate e mai fuse in una media ──
            if (avanzamento != null) {
                Barra("risultato", avanzamento.result, avanzamento.hasResult, colore)
                Barra("esecuzione", avanzamento.execution, avanzamento.hasExecution, Palette.accent)

                if (avanzamento.divergenza) {
                    // Il segnale che il piano viene eseguito ma il metodo non
                    // funziona (o viceversa). Una media dei due numeri lo
                    // nasconderebbe proprio quando conta vederlo.
                    Text(
                        text = if (avanzamento.execution > avanzamento.result)
                            "⚠️ Il piano procede ma il risultato no: il metodo non sta funzionando."
                        else
                            "⚠️ Il risultato si muove più del piano: forse il piano è troppo prudente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.warning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.warning.copy(alpha = 0.12f))
                            .padding(8.dp),
                    )
                }

                Text(
                    text = "${avanzamento.childrenDone}/${avanzamento.childrenTotal} sotto-obiettivi · " +
                        "${avanzamento.milestonesHit}/${avanzamento.milestonesTotal} milestone",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                )
            }

            if (aperto) {
                stato.metricheDi(obiettivo.id).forEach { metrica ->
                    Metrica(
                        metrica = metrica,
                        ultima = stato.ultimeRilevazioni[metrica.id],
                        onRileva = { onRileva(metrica) },
                    )
                }

                val milestone = stato.milestoneDi(obiettivo.id)
                if (milestone.isNotEmpty()) {
                    Text("Milestone", fontWeight = FontWeight.SemiBold, color = Palette.dark)
                    milestone.forEach { m ->
                        Text(
                            text = "${segnoMilestone(m.status)} ${m.dueDate}" +
                                (m.label?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") +
                                (m.expectedValue?.let { " · attesi $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.muted,
                        )
                    }
                }

                // I sotto-obiettivi trimestrali, con le loro due barre.
                stato.figliDi(obiettivo.id).forEach { figlio ->
                    Box(Modifier.padding(start = 12.dp)) {
                        SchedaObiettivo(obiettivo = figlio, stato = stato, onRileva = onRileva)
                    }
                }
            }
        }
    }
}

@Composable
private fun Metrica(metrica: ObMetrica, ultima: ObRilevazione?, onRileva: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.inputBg)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${etichettaRuolo(metrica.role)} · ${metrica.name}",
                fontWeight = FontWeight.SemiBold,
                color = Palette.dark,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRileva) { Text("Rileva") }
        }
        val (da, a) = metrica.scala
        Text(
            text = "da $da a $a ${metrica.unit.orEmpty()}".trim() + " · ${metrica.kind}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.muted,
        )
        ultima?.let {
            Text(
                "ultima: ${it.value} il ${it.measuredOn}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.dark,
            )
        }
    }
}

@Composable
private fun Barra(etichetta: String, valore: Int, presente: Boolean, colore: Color) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(etichetta, style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            Text(
                if (presente) "$valore%" else "—",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (presente) Palette.dark else Palette.muted,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Palette.border)
        ) {
            if (presente) {
                Box(
                    Modifier
                        .fillMaxWidth((valore / 100f).coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colore)
                )
            }
        }
    }
}

@Composable
private fun Semaforo(stato: String) {
    val colore = when (stato) {
        "on_track" -> Palette.success
        "at_risk" -> Palette.warning
        "off_track" -> Palette.danger
        else -> Palette.border
    }
    Box(Modifier.size(14.dp).clip(CircleShape).background(colore))
}

private fun etichettaRuolo(ruolo: String) = when (ruolo) {
    "primary" -> "🎯 risultato"
    "control" -> "🔁 riscontro"
    else -> "⚙️ sforzo"
}

private fun segnoMilestone(stato: String) = when (stato) {
    "hit" -> "✅"
    "missed" -> "❌"
    else -> "•"
}

/**
 * La finestra di rilevazione.
 *
 * Un'**autovalutazione** si dà con lo slider, che vive dentro la scala della
 * metrica: un voto fuori scala lo rifiuterebbe comunque `ob_record_measurement`,
 * ma di qui non si può nemmeno comporre. Un'**automisurazione** ha un campo
 * numerico. La descrizione — come votare, o cosa si misura — è sempre in vista:
 * se cambia il metro la serie storica non è più confrontabile.
 */
@Composable
private fun RilevazioneDialog(
    metrica: ObMetrica,
    onAnnulla: () -> Unit,
    onConferma: (Double, LocalDate, String) -> Unit,
) {
    val voto = metrica.kind == "autovalutazione"
    val minimo = metrica.minValue ?: 1.0
    val massimo = metrica.maxValue ?: 10.0

    var punteggio by remember { mutableStateOf(((minimo + massimo) / 2).roundToInt()) }
    var valore by remember { mutableStateOf("") }
    var nota by remember { mutableStateOf("") }
    var giorno by remember { mutableStateOf(LocalDate.now().toString()) }

    val giornoValido = runCatching { LocalDate.parse(giorno) }.isSuccess
    val valoreValido = voto || valore.trim().toDoubleOrNull() != null

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(metrica.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                metrica.descrizione?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        (if (voto) "Come votare: " else "Cosa si misura: ") + it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.inputBg)
                            .padding(8.dp),
                    )
                }

                if (voto) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Il tuo voto", style = MaterialTheme.typography.bodyMedium)
                        Text("$punteggio", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Slider(
                        value = punteggio.toFloat(),
                        onValueChange = {
                            punteggio = it.roundToInt()
                                .coerceIn(minimo.roundToInt(), massimo.roundToInt())
                        },
                        valueRange = minimo.toFloat()..massimo.toFloat(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${minimo.roundToInt()}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                        Text("${massimo.roundToInt()}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    }
                } else {
                    OutlinedTextField(
                        value = valore,
                        onValueChange = { valore = it },
                        label = { Text("Valore ${metrica.unit.orEmpty()}".trim()) },
                        singleLine = true,
                        isError = valore.isNotBlank() && valore.trim().toDoubleOrNull() == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = giorno,
                    onValueChange = { giorno = it },
                    label = { Text("Giorno (aaaa-mm-gg)") },
                    singleLine = true,
                    isError = !giornoValido,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text("Nota") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = giornoValido && valoreValido,
                onClick = {
                    onConferma(
                        if (voto) punteggio.toDouble() else valore.trim().toDouble(),
                        LocalDate.parse(giorno),
                        nota,
                    )
                },
            ) { Text("Registra") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}
