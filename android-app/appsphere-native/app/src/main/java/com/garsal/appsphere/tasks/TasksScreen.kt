package com.garsal.appsphere.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.coloreDaHex
import java.time.LocalDate

@Composable
fun TasksScreen(
    onIndietro: () -> Unit,
    vm: TasksViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()

    // `null` = form chiuso. La coppia distingue il task nuovo (id nullo, e
    // vale anche per la copia di un altro) da quello che si sta modificando.
    // Sta qui e non nel ViewModel perché è roba della schermata: girando il
    // telefono a metà compilazione si riparte da capo, ma un task mezzo
    // scritto che sopravvive alla chiusura dell'app sarebbe peggio — non si
    // saprebbe più da dove viene.
    var inCompilazione by remember { mutableStateOf<Pair<BozzaTask, String?>?>(null) }
    // I filtri di Gestione stanno qui e non dentro la sua vista: cambiando
    // scheda e tornando indietro, ritrovarseli azzerati sembra che l'app abbia
    // dimenticato quello che si stava guardando.
    var filtri by remember { mutableStateOf(FiltriGestione()) }
    var azioniSu by remember { mutableStateOf<TsTask?>(null) }
    // Si apre sulla PANORAMICA e non sul mese (issue #22): la domanda con cui
    // si apre Tasks è «cosa devo fare adesso», e la panoramica la risponde coi
    // pulsanti che agiscono subito — il mese dice invece com'è messo il periodo,
    // che è una domanda che ci si fa dopo. È anche la vista su cui si apre
    // `tasks.html`, quindi le due implementazioni partono dallo stesso posto.
    var vista by remember { mutableStateOf(Vista.PANORAMICA) }
    var periodo by remember { mutableStateOf(LocalDate.now()) }
    var giornoAperto by remember { mutableStateOf<LocalDate?>(null) }
    var daEliminare by remember { mutableStateOf<TsTask?>(null) }
    var daSaltare by remember { mutableStateOf<TsTask?>(null) }
    var daNascondere by remember { mutableStateOf<TsTask?>(null) }

    inCompilazione?.let { (bozza, id) ->
        TaskForm(
            bozzaIniziale = bozza,
            id = id,
            categorie = stato.categorie,
            priorita = stato.priorita,
            onAnnulla = { inCompilazione = null },
            onSalva = { compilata -> vm.salva(compilata, id) { inCompilazione = null } },
        )
        return
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Tasks",
                onIndietro = onIndietro,
            )
        },
        // Il + galleggiante è quello del web, stesso posto e stesso gesto.
        // Sta sullo Scaffold e non dentro la panoramica: da qualunque delle tre
        // schede può venire in mente di segnarsi una cosa da fare.
        floatingActionButton = {
            FloatingActionButton(
                onClick = { inCompilazione = BozzaTask() to null },
                containerColor = Palette.topBar,
                contentColor = Palette.light,
            ) { Text("+", fontSize = 26.sp) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                stato.caricamento && stato.task.isEmpty() ->
                    CircularProgressIndicator(
                        color = Palette.topBar,
                        modifier = Modifier.align(Alignment.Center),
                    )

                stato.task.isEmpty() ->
                    Text(
                        "Nessun task.",
                        color = Palette.muted,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> Column(Modifier.fillMaxSize()) {
                    SelettoreVista(vista) { scelta ->
                        vista = scelta
                        // Cambiando vista si torna a oggi: tornare al mese
                        // dopo aver sfogliato tre settimane avanti, e
                        // ritrovarsi in un mese che non si stava guardando,
                        // sembra che il pulsante abbia sbagliato.
                        periodo = LocalDate.now()
                    }

                    when (vista) {
                        Vista.PANORAMICA -> VistaPanoramica(
                            stato = stato,
                            // Il tocco sulla scheda apre il dettaglio: là ci
                            // sono la descrizione, i punti di ogni azione e
                            // l'eliminazione, che sulla scheda non stanno.
                            onApri = { azioniSu = it },
                            onCompleta = { vm.completa(it.id) },
                            onFallisci = { vm.fallisci(it.id) },
                            onSalta = { daSaltare = it },
                            onNascondi = { daNascondere = it },
                            onMostra = { vm.nascondi(it.id, null) },
                        )

                        Vista.GESTIONE -> VistaGestione(
                            stato = stato,
                            filtri = filtri,
                            onFiltri = { filtri = it },
                            onNuovo = { inCompilazione = BozzaTask() to null },
                            onVedi = { azioniSu = it },
                            onModifica = { inCompilazione = BozzaTask.da(it) to it.id },
                            // La copia nasce **senza id**: si salva come task
                            // nuovo, col titolo numerato come fa `cloneTask()`.
                            onClona = {
                                inCompilazione =
                                    BozzaTask.da(it).copy(titolo = stato.titoloClonato(it)) to null
                            },
                            onElimina = { daEliminare = it },
                            onRiattiva = { vm.riattiva(it) },
                        )

                        Vista.MESE -> {
                            BarraPeriodo(
                                etichetta = etichettaMese(periodo),
                                onIndietro = { periodo = periodo.minusMonths(1) },
                                onAvanti = { periodo = periodo.plusMonths(1) },
                            )
                            VistaMese(periodo, stato) { giornoAperto = it }
                        }

                        Vista.SETTIMANA -> {
                            BarraPeriodo(
                                etichetta = etichettaSettimana(periodo),
                                onIndietro = { periodo = periodo.minusWeeks(1) },
                                onAvanti = { periodo = periodo.plusWeeks(1) },
                            )
                            VistaSettimana(periodo, stato) { azioniSu = it }
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
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
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

    giornoAperto?.let { giorno ->
        val voci = stato.vociDel(giorno)
        AlertDialog(
            onDismissRequest = { giornoAperto = null },
            title = { Text(dataItaliana(giorno.toString())) },
            text = {
                if (voci.isEmpty()) {
                    Text("Niente in programma.", color = Palette.muted)
                } else {
                    Column {
                        voci.forEach { voce ->
                            RigaVoce(voce, coloreVoce(voce, stato)) { task ->
                                giornoAperto = null
                                azioniSu = task
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { giornoAperto = null }) { Text("Chiudi") }
            },
        )
    }

    azioniSu?.let { task ->
        DialogoAzioni(
            task = task,
            onChiudi = { azioniSu = null },
            onCompleta = { vm.completa(task.id); azioniSu = null },
            onSalta = { azioniSu = null; daSaltare = task },
            onFallisci = { vm.fallisci(task.id); azioniSu = null },
            onElimina = { azioniSu = null; daEliminare = task },
        )
    }

    daSaltare?.let { task ->
        DialogoSalta(
            task = task,
            onAnnulla = { daSaltare = null },
            onConferma = { giorni -> vm.salta(task.id, giorni); daSaltare = null },
        )
    }

    daNascondere?.let { task ->
        DialogoNascondi(
            onAnnulla = { daNascondere = null },
            onConferma = { giorni -> vm.nascondi(task.id, giorni); daNascondere = null },
        )
    }

    daEliminare?.let { task ->
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare il task?") },
            text = {
                Text(
                    "«${task.titolo}» sparisce per sempre, con tutta la sua storia. " +
                        "Per toglierlo di mezzo senza perderlo si archivia da tasks.html."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.elimina(task.id); daEliminare = null }) {
                    Text("Elimina", color = Palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { daEliminare = null }) { Text("Annulla") }
            },
        )
    }
}

/** Le tre viste del planner, come i tre pulsanti in cima a `tasks.html`. */
enum class Vista(val etichetta: String) {
    PANORAMICA("Panoramica"),
    GESTIONE("Gestione"),
    MESE("Mese"),
    SETTIMANA("Settimana"),
}

/**
 * Le quattro schede. Da quando sono quattro **non si dividono più lo schermo
 * in parti uguali**: con `weight(1f)` e i caratteri di sistema grandi,
 * «Panoramica» e «Settimana» in un quarto di riga si tagliano a metà parola.
 * Ognuna è larga quanto il suo nome e la fila scorre col dito, come le righe
 * delle schede.
 */
@Composable
private fun SelettoreVista(scelta: Vista, onScegli: (Vista) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Vista.entries.forEach { vista ->
            val attiva = vista == scelta
            Text(
                text = vista.etichetta,
                color = if (attiva) Palette.light else Palette.dark,
                fontWeight = if (attiva) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (attiva) Palette.topBar else Palette.inputBg)
                    .clickable { onScegli(vista) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/** Il colore con cui il planner disegna una voce: quello della sua categoria. */
private fun coloreVoce(voce: VoceGiorno, stato: TasksState) =
    voce.task?.categorie?.firstNotNullOfOrNull { stato.categoriaDi(it) }
        ?.let { coloreDaHex(it.colore) } ?: Palette.primary

/**
 * Le azioni su un task, una per riga.
 *
 * Una riga per azione e non tre pulsanti in fila sulla scheda: coi caratteri
 * di sistema grandi tre pulsanti affiancati diventano illeggibili o si
 * accavallano, e qui ognuno ha la larghezza intera e la sua spiegazione.
 */
@Composable
private fun DialogoAzioni(
    task: TsTask,
    onChiudi: () -> Unit,
    onCompleta: () -> Unit,
    onSalta: () -> Unit,
    onFallisci: () -> Unit,
    onElimina: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(task.titolo) },
        text = {
            Column {
                task.descrizione?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                Voce("✅ Completa", "+${task.puntiSuccesso} punti", Palette.success, onCompleta)
                // `free_repeat` non ha una prossima occorrenza a cui saltare:
                // la RPC `task_skip` risponde con un errore, quindi la voce
                // non si mostra invece di offrire un pulsante che non va.
                if (task.tipo != "free_repeat") {
                    Voce("⏭ Salta", "${task.puntiSalto} punti", Palette.warning, onSalta)
                }
                Voce("✗ Fallito", "${task.puntiFallimento} punti", Palette.danger, onFallisci)
                Voce("🗑 Elimina", null, Palette.danger, onElimina)
                // Il dialogo dice dove stanno le azioni che qui non ci sono,
                // invece di lasciarle cercare per le quattro schede.
                Text(
                    text = "Modifica e copia stanno nella scheda Gestione.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text("Chiudi") } },
    )
}

@Composable
private fun Voce(
    titolo: String,
    dettaglio: String?,
    colore: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(titolo, color = colore, fontWeight = FontWeight.SemiBold)
        dettaglio?.let {
            Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Di quanti giorni spostare. Lo chiede **solo** per i `single`: per tutti gli
 * altri tipi `task_skip` ignora `p_days` e va alla prossima occorrenza sua,
 * quindi chiedere un numero che il server butta via sarebbe una bugia.
 */
@Composable
private fun DialogoSalta(task: TsTask, onAnnulla: () -> Unit, onConferma: (Int) -> Unit) {
    if (task.tipo != "single") {
        AlertDialog(
            onDismissRequest = onAnnulla,
            title = { Text("Saltare?") },
            text = { Text("Il task va alla sua prossima occorrenza.") },
            confirmButton = { TextButton(onClick = { onConferma(1) }) { Text("Salta") } },
            dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
        )
        return
    }

    var giorni by remember { mutableStateOf("1") }
    val numero = giorni.toIntOrNull()

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Di quanti giorni?") },
        text = {
            Column {
                OutlinedTextField(
                    value = giorni,
                    onValueChange = { giorni = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Giorni") },
                    singleLine = true,
                )
                Text(
                    text = numero?.let {
                        "Nuova data: " + dataItaliana(
                            (giornoDa(task.dataDiRiferimento) ?: LocalDate.now())
                                .plusDays(it.toLong()).toString()
                        )
                    } ?: "Serve un numero di giorni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = numero != null && numero >= 1,
                onClick = { numero?.let(onConferma) },
            ) { Text("Sposta") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

/**
 * 🙈 Per quanti giorni togliere un task dagli SCADUTI: 7 di partenza, oggi
 * compreso, come `nascondiTask()` in `tasks.html`. Non sposta la scadenza e
 * non tocca i promemoria: lo dice, perché «nascondi» si potrebbe leggere come
 * «rimanda».
 */
@Composable
private fun DialogoNascondi(onAnnulla: () -> Unit, onConferma: (Int) -> Unit) {
    var giorni by remember { mutableStateOf("7") }
    val numero = giorni.toIntOrNull()

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Nascondere per quanti giorni?") },
        text = {
            Column {
                OutlinedTextField(
                    value = giorni,
                    onValueChange = { giorni = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Giorni") },
                    singleLine = true,
                )
                Text(
                    text = numero?.takeIf { it >= 1 }?.let {
                        "Fra gli scaduti torna il " + dataItaliana(
                            LocalDate.now().plusDays(it.toLong()).toString()
                        ) + ". Scadenza e promemoria non cambiano."
                    } ?: "Serve un numero di giorni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = numero != null && numero >= 1,
                onClick = { numero?.let(onConferma) },
            ) { Text("Nascondi") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}
