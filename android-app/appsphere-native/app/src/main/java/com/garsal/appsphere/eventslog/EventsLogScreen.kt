package com.garsal.appsphere.eventslog

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.coloreDaHex

@Composable
fun EventsLogScreen(
    onIndietro: () -> Unit,
    vm: EventsLogViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var eventoDaAnnotare by remember { mutableStateOf<ElEvento?>(null) }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Events Log",
                onIndietro = onIndietro,
                azioni = {
                    // Il 🙈/👁 compare **solo a modalità nascosta accesa**, come
                    // il FAB del web: a modalità spenta non c'è niente da
                    // alzare, e un pulsante che non fa niente è un invito a
                    // chiedersi perché.
                    if (stato.modalitaNascosta) {
                        Text(
                            text = if (stato.soloRiservate) "👁" else "🙈",
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.cambiaFiltroRiservate() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                stato.caricamento && stato.gruppi.isEmpty() ->
                    CircularProgressIndicator(
                        color = Palette.success,
                        modifier = Modifier.align(Alignment.Center),
                    )

                stato.gruppi.isEmpty() ->
                    Text(
                        text = if (stato.soloRiservate) "Nessun gruppo riservato."
                               else "Nessun gruppo di eventi.",
                        color = Palette.muted,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> Column(Modifier.fillMaxSize()) {
                    SchedeGruppi(
                        gruppi = stato.gruppi,
                        attivo = stato.gruppoAttivo,
                        // Il 🙈 sulla scheda si vede solo a modalità accesa, che
                        // è l'unico momento in cui un gruppo riservato sta in
                        // fila con gli altri e va distinto da loro.
                        segnaRiservati = stato.modalitaNascosta,
                        onScegli = { vm.scegliGruppo(it) },
                    )

                    FilaEventi(
                        eventi = stato.eventiDelGruppo,
                        valoriDaSelect = stato.valoriDaSelect,
                        onRegistra = { vm.registra(it, null) },
                        onAnnota = { eventoDaAnnotare = it },
                    )

                    // Il gruppo nel titolo: l'elenco qui sotto è solo il suo, e
                    // senza dirlo una categoria senza registrazioni sembrerebbe
                    // un registro vuoto invece che un registro filtrato.
                    Text(
                        text = stato.nomeGruppoAttivo?.let { "Registro · $it" } ?: "Registro",
                        fontWeight = FontWeight.Bold,
                        color = Palette.dark,
                        modifier = Modifier.padding(start = 14.dp, top = 8.dp),
                    )

                    val righe = stato.logDelGruppo
                    if (righe.isEmpty()) {
                        Text(
                            "Nessuna registrazione in questa categoria.",
                            color = Palette.muted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                        )
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(righe) { riga ->
                            RigaLog(
                                riga = riga,
                                evento = stato.evento(riga.eventId),
                                onCancella = { vm.cancella(riga) },
                            )
                        }
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

    eventoDaAnnotare?.let { evento ->
        var nota by remember(evento.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { eventoDaAnnotare = null },
            title = { Text("${evento.icon.orEmpty()} ${evento.name}".trim()) },
            text = {
                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text("Nota") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.registra(evento, nota)
                    eventoDaAnnotare = null
                }) { Text("Registra") }
            },
            dismissButton = {
                TextButton(onClick = { eventoDaAnnotare = null }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun SchedeGruppi(
    gruppi: List<ElGruppo>,
    attivo: String?,
    segnaRiservati: Boolean,
    onScegli: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gruppi.forEach { gruppo ->
            val scelto = gruppo.id == attivo
            val colore = coloreDaHex(gruppo.color) ?: Palette.accent
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (scelto) colore else Palette.inputBg)
                    .clickable { onScegli(gruppo.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Il segno, l'icona e il nome uno accanto all'altro: un pezzo
                // che manca non deve lasciare lo spazio che lo separava dal
                // prossimo, e un'icona vuota è la normalità.
                val etichetta = listOfNotNull(
                    "🙈".takeIf { segnaRiservati && gruppo.riservato == true },
                    gruppo.icon?.takeIf { it.isNotBlank() },
                    gruppo.name.takeIf { it.isNotBlank() },
                ).joinToString(" ")
                Text(
                    text = etichetta,
                    color = if (scelto) Palette.light else Palette.dark,
                    fontWeight = if (scelto) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Gli eventi del gruppo scelto: un tocco registra, una pressione lunga apre la
 * nota. Gli eventi `DA_SELECT` non si toccano — si contano da soli — e mostrano
 * il valore corrente.
 *
 * **Una sola fila, badge tutti della stessa misura, frecce per scorrere.**
 * Prima era un `FlowRow` che andava a capo: le schede si disponevano su due
 * righe e ognuna era alta quanto il suo nome, così «Corsa» veniva bassa e «In
 * bici e a piedi a San Luca» il doppio, con la fila sotto sfalsata.
 *
 * L'altezza uguale **non** si ottiene fissandola — coi caratteri di sistema
 * grandi una misura in `dp` taglia il testo — ma riservando sempre due righe al
 * nome (`minLines = 2` insieme a `maxLines = 2`): ogni scheda ha allora icona +
 * due righe + punti, e vengono identiche da sole, a qualunque ingrandimento.
 * Per la stessa ragione la larghezza segue `fontScale` invece di stare ferma a
 * 104 dp: quel valore andava bene solo coi caratteri normali.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaEventi(
    eventi: List<ElEvento>,
    valoriDaSelect: Map<String, Int>,
    onRegistra: (ElEvento) -> Unit,
    onAnnota: (ElEvento) -> Unit,
) {
    if (eventi.isEmpty()) {
        Text(
            "Nessun evento in questo gruppo.",
            color = Palette.muted,
            modifier = Modifier.padding(14.dp),
        )
        return
    }

    val densita = LocalDensity.current
    val scala = densita.fontScale.coerceIn(1f, 1.6f)
    val larghezza = 120.dp * scala
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Un badge alla volta: con le frecce si vuole sapere dove si finisce, e a
    // caratteri grandi in fila ce ne stanno due, quindi una "pagina" salterebbe
    // quasi tutto quello che si sta guardando.
    val passo = with(densita) { (larghezza + 8.dp).roundToPx() }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Freccia(
            segno = "‹",
            attiva = scroll.value > 0,
            onClick = {
                scope.launch { scroll.animateScrollTo((scroll.value - passo).coerceAtLeast(0)) }
            },
        )

        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(scroll)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            eventi.forEach { evento ->
                val automatico = evento.eventType == "DA_SELECT"
                val colore = coloreDaHex(evento.color) ?: Palette.success

                Column(
                    Modifier
                        .width(larghezza)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (automatico) colore.copy(alpha = 0.35f) else colore)
                        .then(
                            if (automatico) Modifier
                            else Modifier.combinedClickable(
                                onClick = { onRegistra(evento) },
                                onLongClick = { onAnnota(evento) },
                            )
                        )
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(evento.icon ?: "•", fontSize = 24.sp)
                    Text(
                        text = evento.name,
                        color = Palette.light,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (automatico) "conta: ${valoriDaSelect[evento.id] ?: "—"}"
                               else "+${evento.points}",
                        color = Palette.light.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Freccia(
            segno = "›",
            attiva = scroll.value < scroll.maxValue,
            onClick = {
                scope.launch {
                    scroll.animateScrollTo((scroll.value + passo).coerceAtMost(scroll.maxValue))
                }
            },
        )
    }
}

/**
 * Freccia di scorrimento. Resta al suo posto anche quando non c'è più niente da
 * scorrere — solo sbiadita e non premibile: farla sparire cambierebbe la
 * larghezza della fila proprio mentre la si sta guardando scorrere.
 *
 * La misura è in `sp` e non in `dp` per il verso giusto: le `sp` seguono già da
 * sole l'ingrandimento dei caratteri di sistema, quindi la freccia cresce col
 * resto senza che nessuno la moltiplichi per niente — moltiplicarla ancora per
 * `fontScale`, come si fa con le `dp`, la ingrandirebbe due volte.
 */
@Composable
private fun Freccia(segno: String, attiva: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = attiva, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = segno,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = if (attiva) Palette.dark else Palette.muted.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun RigaLog(riga: ElLog, evento: ElEvento?, onCancella: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.inputBg)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(evento?.icon ?: "•", fontSize = 18.sp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                text = evento?.name ?: "Evento rimosso",
                fontWeight = FontWeight.SemiBold,
                color = Palette.dark,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = riga.loggedAt.replace('T', ' ') +
                    (riga.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted,
            )
        }
        Text(
            "+${riga.pointsAtLog}",
            fontWeight = FontWeight.Bold,
            color = Palette.success,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text("🗑", modifier = Modifier.clickable(onClick = onCancella))
    }
}
