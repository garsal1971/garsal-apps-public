package com.garsal.speseingiro.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.garsal.speseingiro.Stati
import com.garsal.speseingiro.UiState
import com.garsal.speseingiro.Voce
import com.garsal.speseingiro.Categoria
import com.garsal.speseingiro.categoriaDi

/**
 * La schermata che si apre: **a che punto siamo**, e l'elenco di quel che è
 * stato segnato.
 *
 * ⚠️ Il saldo lo dice il server (`vg_stato`), qui si scrive e basta: due
 * telefoni che se lo calcolano per conto proprio sono due debiti diversi il
 * giorno che uno dei due si aggiorna e l'altro no.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchermataHome(
    ui: UiState,
    onNuova: (String) -> Unit,
    onModifica: (Voce) -> Unit,
    onConferma: (String) -> Unit,
    onElimina: (String) -> Unit,
    onChiediCanc: (String, String) -> Unit,
    onRisolvi: (String, Boolean) -> Unit,
    onRegistro: () -> Unit,
    onImpostazioni: () -> Unit,
    onRicarica: () -> Unit,
    scontrino: suspend (String) -> ByteArray?,
) {
    val stato = ui.stato
    var menuNuova by remember { mutableStateOf(false) }
    var vediScontrino by remember { mutableStateOf<Voce?>(null) }
    var chiediCancDi by remember { mutableStateOf<Voce?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stato?.viaggio?.nome ?: "Spese in giro", maxLines = 2)
                        stato?.altro?.let {
                            Text(
                                "${stato.io.nome} e ${it.nome}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onRicarica) { Text("↻") }
                    TextButton(onClick = onRegistro) { Text("📜") }
                    TextButton(onClick = onImpostazioni) { Text("⚙️") }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menuNuova = true },
                    text = { Text("Segna") },
                    icon = { Text("➕") },
                )
                // Un pulsante solo per le due strade, come il FAB di «Ti
                // pisasti?»: due FAB affiancati, coi caratteri di sistema
                // grandi, non hanno spazio garantito.
                DropdownMenu(expanded = menuNuova, onDismissRequest = { menuNuova = false }) {
                    DropdownMenuItem(
                        text = { Text("🧾 Una spesa") },
                        onClick = { menuNuova = false; onNuova("spesa") },
                    )
                    DropdownMenuItem(
                        text = { Text("💸 Una restituzione") },
                        onClick = { menuNuova = false; onNuova("restituzione") },
                    )
                }
            }
        },
    ) { pad ->
        if (stato == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                if (ui.caricamento) CircularProgressIndicator()
                else Text("Niente da mostrare. Tira giù per riprovare.", Modifier.padding(24.dp))
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { RiquadroSaldo(ui) }

            if (stato.altro == null) {
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Sei ancora da solo", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Detta il codice ${stato.viaggio.codice} all'altro: finché non entra, " +
                                    "una spesa non si può dividere né confermare.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (stato.voci.isEmpty()) {
                item {
                    Text(
                        "Ancora niente. Il primo caffè lo segni col ➕.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            items(stato.voci, key = { it.id }) { v ->
                SchedaVoce(
                    categorie = stato.categorie,
                    v = v,
                    ioId = stato.io.id,
                    altroNome = stato.altro?.nome ?: "l'altro",
                    onConferma = { onConferma(v.id) },
                    onModifica = { onModifica(v) },
                    onElimina = { onElimina(v.id) },
                    onChiediCanc = { chiediCancDi = v },
                    onRisolvi = { ok -> onRisolvi(v.id, ok) },
                    onScontrino = { vediScontrino = v },
                )
            }
        }
    }

    vediScontrino?.let { v ->
        DialogoScontrino(v, scontrino) { vediScontrino = null }
    }

    chiediCancDi?.let { v ->
        var motivo by remember(v.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { chiediCancDi = null },
            title = { Text("Chiedere di cancellarla?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "La voce è confermata, quindi non si toglie da soli: la decisione passa " +
                            "all'altro, che la vedrà con questo motivo scritto accanto.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = motivo, onValueChange = { motivo = it },
                        label = { Text("Perché") },
                        placeholder = { Text("l'ho segnata due volte") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onChiediCanc(v.id, motivo.trim()); chiediCancDi = null }) {
                    Text("Chiedi")
                }
            },
            dismissButton = { TextButton(onClick = { chiediCancDi = null }) { Text("Lascia stare") } },
        )
    }
}

@Composable
private fun RiquadroSaldo(ui: UiState) {
    val s = ui.stato ?: return
    val altro = s.altro?.nome ?: "l'altro"
    val pari = kotlin.math.abs(s.saldo) < 0.005
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (pari) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    pari -> "Siete pari 🤝"
                    s.saldo > 0 -> "$altro ti deve"
                    else -> "Devi a $altro"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!pari) {
                Text(
                    euro(kotlin.math.abs(s.saldo)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            // ⚠️ Il saldo che conta è quello delle voci confermate. Quello che
            // comprende le altre si scrive sotto e si dichiara: un debito che
            // si muove prima che l'altro abbia detto sì non è un debito, è una
            // proposta — ma nasconderla farebbe sembrare fermo un conto che
            // sta per cambiare.
            if (kotlin.math.abs(s.saldoAtteso - s.saldo) >= 0.005) {
                val a = s.saldoAtteso
                Text(
                    "Con quel che non è ancora confermato: " + when {
                        kotlin.math.abs(a) < 0.005 -> "sareste pari"
                        a > 0 -> "$altro ti dovrebbe ${euro(a)}"
                        else -> "dovresti ${euro(-a)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // ⚠️ Il gemello della riga del saldo, e per la stessa ragione: due
            // misure accanto, ciascuna etichettata per quel che è. «Speso in
            // tutto 0,00 €» era corretto — conta le sole voci confermate — ma
            // sotto un «ti dovrebbe 314,61 €» si legge come un errore, perché
            // nello stesso riquadro convivevano due misure diverse senza che
            // si vedesse.
            Text(
                "Speso in tutto ${euro(s.totaleViaggio)}" +
                    (if (kotlin.math.abs(s.totaleAtteso - s.totaleViaggio) >= 0.005)
                        " · ${euro(s.totaleAtteso)} col non confermato" else "") +
                    (if (s.daConfermare > 0) " · ${s.daConfermare} da confermare" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SchedaVoce(
    v: Voce,
    categorie: List<Categoria>,
    ioId: String,
    altroNome: String,
    onConferma: () -> Unit,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
    onChiediCanc: () -> Unit,
    onRisolvi: (Boolean) -> Unit,
    onScontrino: () -> Unit,
) {
    val mia = v.creataDa == ioId
    val cancellata = v.stato == Stati.CANCELLATA
    val cat = categoriaDi(v.categoria, categorie)

    // ⚠️ Le larghezze si misurano su TUTTE le etichette che quella riga può
    // mostrare, non solo su quelle di adesso: un pulsante che compare dopo
    // farebbe traballare la larghezza degli altri.
    val largh = larghezzaPulsanti("Conferma", "Correggi", "Togli", "Approva", "Rifiuta", "Scontrino")

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (cancellata) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            RigaScorrevole {
                Text("${cat.emoji} ${if (v.spesa) cat.nome else "Restituzione"}",
                     style = MaterialTheme.typography.labelLarge)
                Text("· ${dataIt(v.data, corta = true)}",
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                EtichettaStato(v)
            }

            Text(
                v.descrizione.ifBlank { if (v.spesa) cat.nome else "Restituzione" },
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (cancellata) TextDecoration.LineThrough else null,
            )

            Text(
                euro(v.importo),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (cancellata) TextDecoration.LineThrough else null,
            )

            Text(
                if (v.spesa) {
                    val perChi = when {
                        v.perChi == "entrambi" -> "per tutti e due"
                        v.beneficiarioId == v.daId -> "solo per ${v.daNome}"
                        else -> "solo per ${v.beneficiarioNome ?: altroNome}"
                    }
                    "Pagata da ${v.daNome} · $perChi"
                } else {
                    "${v.daNome} ha restituito"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (v.stato == Stati.CANC_RICHIESTA) {
                Text(
                    "Cancellazione chiesta" + if (!v.cancMotivo.isNullOrBlank()) ": ${v.cancMotivo}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Chi aspetta cosa, scritto: uno stato che si deduce dai pulsanti
            // che non ci sono è uno stato che nessuno capisce.
            val attesa = when {
                cancellata -> "Cancellata"
                v.stato == Stati.IN_ATTESA && mia -> "In attesa che $altroNome confermi"
                v.stato == Stati.IN_ATTESA -> "L'ha segnata ${v.creataDaNome}: tocca a te"
                v.stato == Stati.CANC_RICHIESTA && v.cancChiestaDa == ioId -> "In attesa che $altroNome decida"
                v.stato == Stati.CANC_RICHIESTA -> "Decidi tu"
                else -> null
            }
            attesa?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            RigaScorrevole(Modifier.padding(top = 4.dp)) {
                when {
                    cancellata -> Unit
                    v.stato == Stati.IN_ATTESA && mia -> {
                        PulsanteVuoto("Correggi", largh, onClick = onModifica)
                        PulsanteVuoto("Togli", largh, MaterialTheme.colorScheme.error, onElimina)
                    }
                    v.stato == Stati.IN_ATTESA -> PulsantePieno("Conferma", largh, onClick = onConferma)
                    v.stato == Stati.CONFERMATA ->
                        PulsanteVuoto("Cancella…", largh, MaterialTheme.colorScheme.error, onChiediCanc)
                    v.stato == Stati.CANC_RICHIESTA && v.cancChiestaDa != ioId -> {
                        PulsantePieno("Approva", largh) { onRisolvi(true) }
                        PulsanteVuoto("Rifiuta", largh, onClick = { onRisolvi(false) })
                    }
                }
                if (v.haScontrino) PulsanteVuoto("Scontrino", largh, onClick = onScontrino)
            }
        }
    }
}

@Composable
private fun EtichettaStato(v: Voce) {
    val c = MaterialTheme.colorScheme
    when (v.stato) {
        Stati.CONFERMATA -> Etichetta("✓ confermata", c.primaryContainer, c.onPrimaryContainer)
        Stati.IN_ATTESA -> Etichetta("in attesa", c.secondaryContainer, c.onSecondaryContainer)
        Stati.CANC_RICHIESTA -> Etichetta("cancellazione chiesta", c.errorContainer, c.onErrorContainer)
        else -> Etichetta("cancellata", c.surfaceVariant, c.onSurfaceVariant)
    }
}

/** Lo scontrino si guarda **dentro l'app**: l'URL è firmato e dura un'ora, e
 *  passarlo a un'altra app ne farebbe una copia dove nessuno la cerca più. */
@Composable
private fun DialogoScontrino(v: Voce, carica: suspend (String) -> ByteArray?, onChiudi: () -> Unit) {
    var bytes by remember(v.id) { mutableStateOf<ByteArray?>(null) }
    var fallito by remember(v.id) { mutableStateOf(false) }

    LaunchedEffect(v.id) {
        val b = carica(v.id)
        if (b == null) fallito = true else bytes = b
    }

    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("🧾 Scontrino") },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                val b = bytes
                when {
                    b != null -> {
                        val bmp = remember(b) { BitmapFactory.decodeByteArray(b, 0, b.size) }
                        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Scontrino",
                                               modifier = Modifier.fillMaxWidth())
                        else Text("Immagine illeggibile")
                    }
                    fallito -> Text("Non sono riuscito a scaricarlo. Senza rete lo scontrino non c'è.")
                    else -> CircularProgressIndicator()
                }
            }
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text("Chiudi") } },
    )
}
