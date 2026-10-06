package com.garsal.appsphere.memo

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette

/**
 * Una lista aperta: la nota della scheda, la barra di avanzamento, le voci da
 * spuntare e il campo per aggiungerne.
 *
 * ⚠️ **Le spunte sono ottimistiche con rollback**, come sul web e come
 * Spuntiamola: la voce cambia subito e torna indietro se il database rifiuta,
 * così non resta a schermo una spunta finta che sparisce al ricarico. Il giro
 * vive in `MemoViewModel.spuntaVoce`, non qui.
 */
@Composable
fun MemoListaView(
    scheda: MmScheda,
    vista: VistaScheda,
    onIndietro: () -> Unit,
    onModifica: () -> Unit,
    onFissa: () -> Unit,
    onSpunta: (MmVoce, Boolean) -> Unit,
    onAggiungi: (String) -> Unit,
    onElimina: (MmVoce) -> Unit,
) {
    var nascondiFatte by remember { mutableStateOf(false) }
    var nuova by remember { mutableStateOf("") }
    var daEliminare by remember { mutableStateOf<MmVoce?>(null) }

    val totale = vista.voci.size
    val fatte = vista.vociFatte

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = scheda.titolo.ifBlank { "Lista senza titolo" },
                onIndietro = onIndietro,
                azioni = {
                    Text(
                        text = if (scheda.fissata) "📌" else "📍",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onFissa)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        text = "✏️",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onModifica)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (scheda.anteprima.isNotBlank()) {
                    Text(
                        text = remember(scheda.contenuto) {
                            runCatching { AnnotatedString.fromHtml(scheda.contenuto) }
                                .getOrElse { AnnotatedString(scheda.anteprima) }
                        },
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    text = if (totale > 0) "$fatte di $totale fatte" else "Nessuna voce",
                    color = Palette.dark,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Palette.border)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (totale > 0) fatte.toFloat() / totale else 0f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Palette.success)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = nascondiFatte, onCheckedChange = { nascondiFatte = it })
                    Text(
                        text = "Nascondi le fatte",
                        color = Palette.dark,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { nascondiFatte = !nascondiFatte },
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val elenco = if (nascondiFatte) vista.voci.filterNot { it.fatta } else vista.voci
                when {
                    vista.caricamento -> CircularProgressIndicator(
                        color = BluMemo,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    elenco.isEmpty() -> Text(
                        text = if (totale > 0) "Tutte fatte 🎉"
                        else "Nessuna voce: aggiungine una qui sotto.",
                        color = Palette.muted,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(elenco, key = { it.id }) { voce ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = voce.fatta,
                                    onCheckedChange = { onSpunta(voce, it) },
                                )
                                Text(
                                    text = voce.testo,
                                    color = if (voce.fatta) Palette.muted else Palette.dark,
                                    textDecoration = if (voce.fatta) TextDecoration.LineThrough
                                    else TextDecoration.None,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .weight(1f)
                                        // Tutta la riga spunta, non il solo
                                        // quadratino: col dito è il bersaglio
                                        // che si centra davvero.
                                        .clickable { onSpunta(voce, !voce.fatta) }
                                        .padding(vertical = 10.dp),
                                )
                                Text(
                                    text = "✕",
                                    color = Palette.muted,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { daEliminare = voce }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = nuova,
                    onValueChange = { nuova = it },
                    label = { Text("Nuova voce") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "➕",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (nuova.isNotBlank()) {
                                onAggiungi(nuova)
                                nuova = ""
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }

    daEliminare?.let { voce ->
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare la voce?") },
            text = { Text("«${voce.testo}» viene tolta dalla lista.") },
            confirmButton = {
                TextButton(onClick = { daEliminare = null; onElimina(voce) }) {
                    Text("Elimina", color = Palette.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { daEliminare = null }) {
                    Text("Annulla", color = Palette.muted)
                }
            },
        )
    }
}
