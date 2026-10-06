package com.garsal.appsphere.premi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette

/** Rosso del riquadro del totale (#EE334E), lo stesso di `#score-panel`. */
internal val RossoPremi = Color(0xFFEE334E)

private enum class Vista(val etichetta: String) {
    CATALOGO("🎁 Premi"),
    GESTISCI("🛠 Gestisci"),
    CRONOLOGIA("📋 Cronologia"),
}

/**
 * Il catalogo premi — le tre viste del modale di `index.html` (premi da
 * ritirare, gestione, cronologia), qui a tutto schermo come gli altri form
 * dell'app.
 *
 * `lordo` sono i punti guadagnati, cioè la somma dei punteggi di tutte le app
 * della home: arriva da fuori perché è la home a calcolarlo, e ricalcolarlo qui
 * vorrebbe dire rifare tutte le `score_query` una seconda volta.
 */
@Composable
fun PremiScreen(
    lordo: Int,
    onIndietro: () -> Unit,
    vm: PremiViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var vista by remember { mutableStateOf(Vista.CATALOGO) }
    // Il form sta qui e non nel ViewModel, come in Tasks e Ta Firi?: la coppia
    // distingue il premio nuovo (id nullo) da quello che si sta modificando.
    var inCompilazione by remember { mutableStateOf<Pair<BozzaPremio, String?>?>(null) }
    var daRiscattare by remember { mutableStateOf<Premio?>(null) }
    var daEliminare by remember { mutableStateOf<Premio?>(null) }

    val disponibili = stato.puntiDisponibili(lordo)

    LaunchedEffect(Unit) { vm.carica() }

    LaunchedEffect(vista) {
        if (vista == Vista.CRONOLOGIA) vm.caricaCronologia()
    }

    // Col form aperto il tasto indietro lo chiude, invece di chiudere il
    // catalogo e buttare via quello che si stava scrivendo.
    BackHandler(enabled = inCompilazione != null) { inCompilazione = null }

    inCompilazione?.let { (bozza, id) ->
        PremiForm(
            bozzaIniziale = bozza,
            id = id,
            onAnnulla = { inCompilazione = null },
            onSalva = { b ->
                val costo = b.costoValido
                if (costo != null) {
                    vm.salva(id, b.nome.trim(), b.tipo, costo, b.puntiPerUsoValidi)
                    inCompilazione = null
                }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "🎁 Premi",
                onIndietro = onIndietro,
            )
        },
        floatingActionButton = {
            if (vista == Vista.GESTISCI) {
                FloatingActionButton(
                    onClick = { inCompilazione = BozzaPremio() to null },
                    containerColor = RossoPremi,
                    contentColor = Palette.light,
                ) { Text("+", fontSize = 26.sp) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            SelettoreVista(vista) { vista = it }

            RigaPunti(disponibili = disponibili, spesi = stato.spesi)

            stato.errore?.let { messaggio ->
                Text(
                    text = messaggio,
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { vm.scartaErrore() },
                )
            }

            Box(Modifier.fillMaxSize()) {
                if (stato.caricamento && stato.premi.isEmpty()) {
                    CircularProgressIndicator(
                        color = RossoPremi,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else when (vista) {
                    Vista.CATALOGO -> Catalogo(
                        premi = stato.daRitirare,
                        disponibili = disponibili,
                        onRitira = { daRiscattare = it },
                    )

                    Vista.GESTISCI -> Gestisci(
                        premi = stato.premi,
                        onModifica = { premio ->
                            inCompilazione = BozzaPremio.da(premio) to premio.id
                        },
                        onElimina = { daEliminare = it },
                    )

                    Vista.CRONOLOGIA -> Cronologia(stato.cronologia)
                }
            }
        }
    }

    daRiscattare?.let { premio ->
        Conferma(
            titolo = "Ritirare il premio?",
            testo = "«${premio.nome}» per ${premio.costo} punti.",
            azione = "Ritira",
            colore = RossoPremi,
            onConferma = { vm.riscatta(premio); daRiscattare = null },
            onAnnulla = { daRiscattare = null },
        )
    }

    daEliminare?.let { premio ->
        Conferma(
            titolo = "Eliminare il premio?",
            testo = "«${premio.nome}» sparisce dal catalogo. " +
                "I riscatti già fatti restano nella cronologia.",
            azione = "Elimina",
            colore = Palette.danger,
            onConferma = { vm.elimina(premio.id); daEliminare = null },
            onAnnulla = { daEliminare = null },
        )
    }
}

@Composable
private fun SelettoreVista(scelta: Vista, onScegli: (Vista) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Vista.entries.forEach { v ->
            val attiva = v == scelta
            Text(
                text = v.etichetta,
                color = if (attiva) Palette.light else Palette.dark,
                fontWeight = if (attiva) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (attiva) RossoPremi else Palette.inputBg)
                    .clickable { onScegli(v) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}

/** I due riquadri del web: punti disponibili e punti spesi. */
@Composable
private fun RigaPunti(disponibili: Int, spesi: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Riquadro("Punti disponibili", disponibili, Palette.success, Modifier.weight(1f))
        Riquadro("Punti spesi", spesi, Palette.muted, Modifier.weight(1f))
    }
}

@Composable
private fun Riquadro(etichetta: String, valore: Int, colore: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colore.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = "$valore", color = colore, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(text = etichetta, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Catalogo(premi: List<Premio>, disponibili: Int, onRitira: (Premio) -> Unit) {
    if (premi.isEmpty()) {
        Vuoto("🎁", "Nessun premio disponibile.\nAggiungine uno da Gestisci!")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(premi, key = { it.id }) { premio ->
            val abbastanza = disponibili >= premio.costo
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Palette.cardBg),
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${premio.emoji} ${premio.nome}",
                        color = Palette.dark,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${premio.costo} punti" +
                            if (premio.ripetibile && premio.usi > 0) " · usato ${premio.usi}×" else "",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Il pulsante sta su una riga sua e non accanto al nome:
                    // coi caratteri di sistema grandi, affiancati, il nome
                    // andrebbe a capo tre volte per far posto a «Ritira».
                    Pulsante(
                        testo = if (abbastanza) "Ritira" else "Mancano ${premio.costo - disponibili} punti",
                        colore = if (abbastanza) RossoPremi else Palette.border,
                        coloreTesto = if (abbastanza) Palette.light else Palette.muted,
                        attivo = abbastanza,
                        modifier = Modifier.fillMaxWidth(),
                    ) { onRitira(premio) }
                }
            }
        }
    }
}

@Composable
private fun Gestisci(
    premi: List<Premio>,
    onModifica: (Premio) -> Unit,
    onElimina: (Premio) -> Unit,
) {
    if (premi.isEmpty()) {
        Vuoto("🎁", "Nessun premio. Aggiungine uno col +")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(premi, key = { it.id }) { premio ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Palette.cardBg),
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${premio.emoji} ${premio.nome}",
                        color = Palette.dark,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = buildString {
                            append("${premio.costo} punti · ")
                            append(if (premio.ripetibile) "ripetibile" else "una tantum")
                            // A 0 — o non impostato, com'erano i ripetibili prima che la
                            // colonna esistesse — il premio non rincara: tacerlo sarebbe
                            // indistinguibile da «non lo so». Stessa riga in renderRewards().
                            if (premio.ripetibile) {
                                val p = premio.puntiPerUso ?: 0
                                append(if (p > 0) " · +$p/uso" else " · costo fisso")
                            }
                            if (premio.ritirato) append(" · già ritirato")
                        },
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pulsante("✏️ Modifica", RossoPremi, Palette.light, Modifier.weight(1f)) {
                            onModifica(premio)
                        }
                        Pulsante("🗑 Elimina", Palette.danger, Palette.light, Modifier.weight(1f)) {
                            onElimina(premio)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cronologia(righe: List<Riscatto>) {
    if (righe.isEmpty()) {
        Vuoto("📋", "Nessun premio riscattato ancora.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(righe) { riga ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Palette.cardBg),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(text = riga.nome, color = Palette.dark, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = riga.quando,
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "−${riga.punti} punti",
                        color = RossoPremi,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Vuoto(icona: String, testo: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = icona, fontSize = 40.sp)
        Text(
            text = testo,
            color = Palette.muted,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
internal fun Pulsante(
    testo: String,
    colore: Color,
    coloreTesto: Color = Palette.light,
    modifier: Modifier = Modifier,
    attivo: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = testo,
        color = coloreTesto,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colore)
            .clickable(enabled = attivo, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun Conferma(
    titolo: String,
    testo: String,
    azione: String,
    colore: Color,
    onConferma: () -> Unit,
    onAnnulla: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(titolo) },
        text = { Text(testo) },
        confirmButton = {
            TextButton(onClick = onConferma) {
                Text(azione, color = colore, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text("Annulla", color = Palette.muted) }
        },
    )
}
