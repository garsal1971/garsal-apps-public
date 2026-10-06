package com.garsal.appsphere.frz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.widget.MediaController
import android.widget.VideoView
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.larghezzaPulsanti
import kotlinx.coroutines.delay
import java.io.File

/**
 * Il Forziere sul telefono.
 *
 * ⚠️ **Cinque cose e non di più**: sbloccare, sfogliare, aprire un documento,
 * metterne dentro uno, buttarne via. Creazione del forziere, collaudo delle 24 parole, export
 * `.7z` e cambio della passphrase restano su `forziere.html` — si fanno una
 * volta, vanno fatte bene, e il telefono è il posto sbagliato per ciascuna.
 *
 * ⚠️ **Il forziere si chiude quando l'app va in secondo piano**, oltre che dopo
 * dieci minuti. È l'equivalente del `visibilitychange` della pagina: uno
 * schermo lasciato acceso su un elenco di documenti è esattamente la cosa da
 * cui il forziere protegge. Rientrare costa la passphrase o l'impronta, cioè
 * due secondi.
 */
@Composable
fun ForziereScreen(onIndietro: () -> Unit) {
    val ctx = LocalContext.current
    val vm: ForziereViewModel = viewModel()
    val s by vm.stato.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.avvia(ctx) }

    // ⚠️ Non basta il timer: un'app messa via non lo fa scorrere più in fretta,
    // e resterebbe aperta finché non la si riapre. `ON_STOP` è il momento in cui
    // lo schermo non è più davanti a nessuno.
    val ciclo = LocalLifecycleOwner.current
    DisposableEffect(ciclo) {
        val oss = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) vm.chiudi(ctx, "l'app è passata in secondo piano")
        }
        ciclo.lifecycle.addObserver(oss)
        onDispose { ciclo.lifecycle.removeObserver(oss) }
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "🔐 Forziere",
                onIndietro = onIndietro,
                azioni = {
                    if (s.fase == FaseForziere.APERTO) {
                        Text(
                            "Chiudi",
                            color = Palette.light,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { vm.chiudi(ctx, "l'hai chiuso tu") }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (s.fase == FaseForziere.APERTO && s.visione == null) FabMetti(vm)
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (s.fase) {
                FaseForziere.CARICO -> Attesa()
                FaseForziere.ASSENTE -> Assente(s.errore)
                FaseForziere.CHIUSO -> Sblocco(vm, s)
                FaseForziere.APERTO -> {
                    val v = s.visione
                    if (v == null) Dentro(vm, s) else Visore(vm, v)
                }
            }
            // Sta qui e non dentro `Dentro` perché si chiede anche dal visore.
            val docs = s.daCancellare
            if (docs != null) DialogoCancella(vm, s, docs)
        }
    }
}

// ── Il pulsante che mette dentro ────────────────────────────────────────────

@Composable
private fun FabMetti(vm: ForziereViewModel) {
    val ctx = LocalContext.current
    val scelta = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.metti(ctx, uri)
    }
    ExtendedFloatingActionButton(
        onClick = { vm.tocca(); vm.apriUnaFinestraDiSistema(); scelta.launch("*/*") },
        containerColor = Palette.dark,
        contentColor = Palette.light,
    ) { Text("＋ Metti dentro") }
}

// ── Le tre schermate ────────────────────────────────────────────────────────

@Composable
private fun Attesa() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun Assente(errore: String?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Qui non c'è ancora un forziere", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Il forziere si crea dal computer, su forziere.html: si scelgono le 24 parole, " +
                "si fa il collaudo del recupero e si mette la passphrase. Sono cose che si " +
                "fanno una volta sola e vanno fatte con calma — il telefono è il posto " +
                "sbagliato per ciascuna.",
            color = Palette.muted,
        )
        if (errore != null) Avviso("⚠️ $errore", Palette.danger)
    }
}

@Composable
private fun Sblocco(vm: ForziereViewModel, s: ForziereState) {
    val ctx = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var parole by remember { mutableStateOf("") }
    var conParole by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🔐 Il forziere è chiuso", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (s.motivoChiusura != null) Text(s.motivoChiusura, color = Palette.muted)

        if (!conParole) {
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.apriConPassphrase(pw); pw = "" },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.success),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apri il forziere") }

            if (s.improntaDisponibile && s.improntaRegistrata) {
                Button(
                    onClick = {
                        val cipher = ForziereBiometria.decifratore(ctx)
                        if (cipher == null) {
                            vm.togliImpronta(ctx)
                        } else {
                            chiediImpronta(
                                ctx, "Apri il forziere", cipher,
                                onOk = { c -> vm.apriConImpronta(ctx, c) },
                                onNiente = {},
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("👆 Sblocca con l'impronta") }
            }

            TextButton(onClick = { conParole = true }) { Text("Ho dimenticato la passphrase — uso le 24 parole") }
        } else {
            Text("Tutto minuscolo, separate da uno spazio.", color = Palette.muted)
            OutlinedTextField(
                value = parole,
                onValueChange = { parole = it },
                label = { Text("Le 24 parole") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.apriConParole(parole); parole = "" },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.success),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apri il forziere") }
            // ⚠️ Entrando con le parole la passphrase è persa, e si rifà **dal
            // PC**: cambiarla vuol dire riscrivere `scorciatoia.gpg`, che è una
            // delle operazioni che di proposito non stanno qui.
            Avviso(
                "Sei entrato con le 24 parole: vuol dire che la passphrase non la ricordi più. " +
                    "Rifalla dal computer, su forziere.html → Impostazioni.",
                Palette.warning,
            )
            TextButton(onClick = { conParole = false }) { Text("Torna alla passphrase") }
        }

        if (s.stato != null) Text(s.stato, color = Palette.muted)
        if (s.errore != null) Avviso("❌ ${s.errore}", Palette.danger)
    }
}

@Composable
private fun Dentro(vm: ForziereViewModel, s: ForziereState) {
    val ctx = LocalContext.current

    // ⚠️ L'indietro esce dalla selezione invece di uscire dal forziere: è il
    // gesto con cui su Android si annulla quel che si è appena aperto, e senza
    // questo si perderebbe il forziere per aver voluto togliere una spunta.
    BackHandler(enabled = s.inSelezione) { vm.azzeraSelezione() }

    Column(Modifier.fillMaxSize()) {

        if (s.avanzamento != null || s.stato != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (s.stato != null) Text(s.stato, color = Palette.muted, fontSize = 13.sp)
                if (s.avanzamento != null) {
                    LinearProgressIndicator(
                        progress = { s.avanzamento },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
        if (s.errore != null) {
            Box(Modifier.padding(horizontal = 12.dp)) { Avviso("❌ ${s.errore}", Palette.danger) }
        }
        if (s.indiciRotti) {
            Box(Modifier.padding(horizontal = 12.dp)) {
                Avviso(
                    "Gli indici su Drive non si sono riscritti. Il documento è dentro e la riga " +
                        "c'è: a mancare è la copia che serve a orientarsi col solo Drive. " +
                        "Si rifà dal computer, Impostazioni → 🔄 Rifai gli indici.",
                    Palette.warning,
                )
            }
        }

        // ⚠️ La barra degli scomparti **scorre e non va a capo**: coi caratteri
        // di sistema grandi le pillole andrebbero su tre righe mangiandosi
        // l'elenco. Stessa regola delle righe di pulsanti.
        RigaScorrevole(
            Arrangement.spacedBy(8.dp),
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Pillola("Tutti", if (s.filtro == Filtro.Tutti) Palette.dark else Palette.muted) {
                vm.filtra(Filtro.Tutti)
            }
            s.scomparti.forEach { b ->
                Pillola(
                    "${b.emoji} ${b.nome}",
                    if (s.filtro == Filtro.Uno(b.id)) Palette.dark else Palette.muted,
                ) { vm.filtra(Filtro.Uno(b.id)) }
            }
            // ⚠️ «Senza scomparto» compare **solo se serve**: senza nessuno
            // scomparto sarebbe un doppione di «Tutti».
            if (s.scomparti.isNotEmpty()) {
                Pillola(
                    "Senza scomparto",
                    if (s.filtro == Filtro.Senza) Palette.dark else Palette.muted,
                ) { vm.filtra(Filtro.Senza) }
            }
        }

        // ⚠️ L'impronta si registra **da dentro**, con le 24 parole già in
        // memoria: chiederle di nuovo per registrarle sarebbe farsele riscrivere
        // per niente. E si toglie da qui, che è dove si vede che c'è.
        if (s.improntaDisponibile) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (s.improntaRegistrata) {
                    // ⚠️ `weight(1f)` sulla scritta: coi caratteri di sistema
                    // grandi, senza, la frase spinge «Togli» fuori dallo schermo.
                    Text(
                        "👆 L'impronta apre questo forziere",
                        color = Palette.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.togliImpronta(ctx) }) { Text("Togli") }
                } else {
                    TextButton(onClick = {
                        val cipher = runCatching { ForziereBiometria.cifratore() }.getOrNull()
                        if (cipher != null) chiediImpronta(
                            ctx, "Registra l'impronta", cipher,
                            onOk = { c -> vm.registraImpronta(ctx, c) },
                            onNiente = {},
                        )
                    }) { Text("👆 Aprilo con l'impronta la prossima volta") }
                }
            }
        }

        // ⚠️ La riga dei pulsanti **non va a capo**: scorre col dito, e le due
        // larghezze si misurano invece di essere costanti in `dp` — coi
        // caratteri di sistema grandi una costante o taglia «Butta via» o
        // lascia «Annulla» in un pulsante largo il doppio.
        if (s.inSelezione) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    if (s.selezione.size == 1) "1 documento scelto"
                    else "${s.selezione.size} documenti scelti",
                    fontWeight = FontWeight.SemiBold,
                )
                val largo = larghezzaPulsanti(listOf("Annulla", "🗑 Butta via"))
                RigaScorrevole(Arrangement.spacedBy(8.dp), Modifier.padding(top = 6.dp)) {
                    Pillola("🗑 Butta via", Palette.danger, largo) { vm.chiediCancellaSelezione() }
                    Pillola("Annulla", Palette.muted, largo) { vm.azzeraSelezione() }
                }
            }
        }

        OutlinedTextField(
            value = s.cerca,
            onValueChange = { vm.cerca(it) },
            label = { Text("Cerca fra i nomi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )

        val elenco = s.visibili
        if (elenco.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (s.documenti.isEmpty()) "Il forziere è vuoto." else "Nessun documento qui.",
                    color = Palette.muted,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(elenco, key = { it.id }) { d ->
                    SchedaDocumento(
                        d = d,
                        mini = s.miniature[d.id],
                        selezionato = d.id in s.selezione,
                        inSelezione = s.inSelezione,
                        onApri = { vm.apriDocumento(ctx, d) },
                        onSeleziona = { vm.seleziona(d.id) },
                    )
                }
            }
        }
    }
}

/**
 * ⚠️ **La selezione si apre con la pressione lunga**, e solo allora il tocco
 * spunta invece di aprire: il gesto che si fa cento volte è aprire un
 * documento, e una casella disegnata su ogni scheda sarebbe rumore permanente
 * per un gesto che si fa di rado. È la stessa scelta della griglia di
 * Events Log.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SchedaDocumento(
    d: FrzDocumento,
    mini: ByteArray?,
    selezionato: Boolean,
    inSelezione: Boolean,
    onApri: () -> Unit,
    onSeleziona: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.cardBg),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                if (selezionato) 3.dp else 1.dp,
                if (selezionato) Palette.accent else Palette.border,
                RoundedCornerShape(12.dp),
            )
            .combinedClickable(
                onClick = { if (inSelezione) onSeleziona() else onApri() },
                onLongClick = onSeleziona,
            ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.4f).background(Palette.inputBg),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = remember(mini) {
                mini?.let {
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                }
            }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text(iconaDi(d.meta.tipo), fontSize = 34.sp)
            }
            // ⚠️ Il segno sta **sopra la miniatura** e non accanto al nome: su
            // una foto il bordo colorato da solo si confonde con la cornice, e
            // quel che si sta per buttare via va visto senza andarlo a cercare.
            if (inSelezione) {
                Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopEnd) {
                    Text(
                        if (selezionato) "☑️" else "⬜",
                        fontSize = 22.sp,
                    )
                }
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                d.meta.nome.ifBlank { "(senza nome)" },
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(peso(d.meta.size ?: d.pesoCifrato), color = Palette.muted, fontSize = 12.sp)
        }
    }
}

// ── Buttare via ─────────────────────────────────────────────────────────────

/**
 * ⚠️ **Costa la passphrase**, e non è prudenza generica: qui il forziere è già
 * aperto, e chi trova la scrivania incustodita non deve poter buttare via
 * trenta documenti con due tocchi. È la stessa scelta del 🧹 Svuota e della
 * 💣 zona rossa della pagina, e la verifica è la stessa — si apre davvero
 * `scorciatoia.gpg`, non si confronta con qualcosa che sta in memoria.
 *
 * ⚠️ **Le 24 parole restano una via**: chi è entrato con loro la passphrase non
 * la ricorda più, e senza questa uscita la cancellazione gli resterebbe chiusa
 * per sempre senza che niente glielo spieghi.
 *
 * ⚠️ **Dire cosa NON succede è metà della finestra**: davanti a «butto via»
 * senza altro scritto si preme Annulla anche quando si voleva premere l'altro.
 */
@Composable
private fun DialogoCancella(vm: ForziereViewModel, s: ForziereState, docs: List<FrzDocumento>) {
    val ctx = LocalContext.current
    var pw by remember { mutableStateOf("") }
    var parole by remember { mutableStateOf("") }
    var conParole by remember { mutableStateOf(false) }

    val totale = docs.sumOf { it.meta.size ?: it.pesoCifrato }
    val nomi = docs.take(5).map { it.meta.nome.ifBlank { "(senza nome)" } }

    AlertDialog(
        onDismissRequest = { vm.annullaCancella() },
        title = {
            Text(
                if (docs.size == 1) "🗑 Butto via un documento"
                else "🗑 Butto via ${docs.size} documenti",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avviso(
                    "Da qui non si torna indietro. Se ne " +
                        (if (docs.size == 1) "va " else "vanno ") + peso(totale) +
                        " da Drive e dal database, con le miniature.",
                    Palette.danger,
                )
                nomi.forEach { Text("• $it", fontSize = 13.sp) }
                if (docs.size > nomi.size) {
                    Text("…e altri ${docs.size - nomi.size}", color = Palette.muted, fontSize = 13.sp)
                }

                Text("Quello che non succede:", color = Palette.muted, fontSize = 13.sp)
                Text(
                    "• lo scomparto resta dov'è, vuoto o no\n" +
                        "• gli altri documenti non si toccano\n" +
                        "• il forziere resta quello di prima: le 24 parole aprono ancora tutto",
                    fontSize = 13.sp,
                )

                if (!conParole) {
                    OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { conParole = true; pw = "" }) {
                        Text("Non la ricordo — uso le 24 parole")
                    }
                } else {
                    OutlinedTextField(
                        value = parole,
                        onValueChange = { parole = it },
                        label = { Text("Le 24 parole") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { conParole = false; parole = "" }) {
                        Text("Torna alla passphrase")
                    }
                }

                if (s.stato != null) Text(s.stato, color = Palette.muted, fontSize = 13.sp)
                if (s.cancellaErrore != null) Avviso("❌ ${s.cancellaErrore}", Palette.danger)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.confermaCancella(ctx, if (conParole) parole else pw, conParole) }) {
                Text("Butta via", color = Palette.danger, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.annullaCancella() }) { Text("Annulla") }
        },
    )
}

// ── Il visore ───────────────────────────────────────────────────────────────

@Composable
private fun Visore(vm: ForziereViewModel, v: Visione) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                v.doc.meta.nome.ifBlank { "(senza nome)" },
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // ⚠️ Il cestino sta **qui e non sulla scheda in griglia**: si butta
            // via dopo aver visto cos'è, quindi non si sbaglia documento — e la
            // scheda, coi caratteri di sistema grandi, è già piena di suo.
            // Un'emoji sola invece di «Butta via» perché la riga deve restare
            // una, col titolo che ha `weight(1f)`: due etichette lunghe qui lo
            // spingerebbero fuori.
            TextButton(onClick = { vm.chiediCancella(listOf(v.doc)) }) {
                Text("🗑", fontSize = 20.sp)
            }
            TextButton(onClick = { vm.chiudiVisore(ctx) }) { Text("Chiudi") }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                v.immagine != null -> {
                    val bmp = remember(v.immagine) {
                        android.graphics.BitmapFactory.decodeByteArray(v.immagine, 0, v.immagine.size)
                    }
                    if (bmp != null) {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else NonSiApre("questa immagine il telefono non la sa decodificare")
                }
                v.pdf != null -> VisorePdf(v.pdf)
                v.media != null -> VisoreMedia(vm, v.media, v.video)
                v.testo != null -> Text(
                    v.testo,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
                else -> NonSiApre(v.nonSiApre ?: "")
            }
        }
    }
}

/**
 * ⚠️ Il PDF si disegna **dentro l'app** con `PdfRenderer`, non passandolo a un
 * altro programma: un `Intent` ne farebbe una copia in chiaro dentro un'app che
 * non è questa, e da lì il forziere non la riprende più. Il temporaneo sta nella
 * cache privata e se ne va chiudendo il visore.
 */
@Composable
private fun VisorePdf(file: File) {
    val pagine = remember(file) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    (0 until r.pageCount).map { i ->
                        r.openPage(i).use { p ->
                            val larghezza = 1240
                            val altezza = (larghezza.toFloat() / p.width * p.height).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(larghezza, altezza, Bitmap.Config.ARGB_8888).also { b ->
                                b.eraseColor(android.graphics.Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        }.getOrNull()
    }
    if (pagine == null) {
        NonSiApre("questo PDF non si lascia disegnare qui: aprilo dal computer")
    } else {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pagine) { b ->
                Image(
                    b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().border(1.dp, Palette.border),
                )
            }
        }
    }
}

/**
 * Video e audio, col player del sistema.
 *
 * ⚠️ **`VideoView` e non una libreria in più.** Qui il file è locale e già
 * decifrato: non c'è niente da mettere in streaming, niente DRM, nessun
 * formato adattivo. `media3`/ExoPlayer costerebbe qualche MiB nel DEX — su un
 * APK che è già a 43 MiB contro i 50 oltre cui dà noia — per fare esattamente
 * questo. E il `MediaController` porta con sé play, pausa e la barra di
 * scorrimento senza scriverne una riga.
 *
 * ⚠️ **Il file non esce di qui**: nessun `Intent`, nessun player di sistema —
 * che ne farebbe una copia in chiaro in un'app che non è questa. È lo stesso
 * ragionamento del PDF.
 *
 * ⚠️ **Guardare un film È usare il forziere.** Il blocco a dieci minuti conta i
 * tocchi, e in mezz'ora di video non ce n'è nessuno: senza questo battito il
 * forziere si chiuderebbe da sé a metà. Si rimanda **solo mentre riproduce** —
 * in pausa il conto riparte, com'è giusto — e `ON_STOP` chiude comunque:
 * uscire dall'app resta uscire dall'app.
 *
 * ⚠️ `stopPlayback()` all'uscita, o l'audio continua a suonare su un visore che
 * non c'è più.
 */
@Composable
private fun VisoreMedia(vm: ForziereViewModel, file: File, video: Boolean) {
    var player by remember(file) { mutableStateOf<VideoView?>(null) }

    LaunchedEffect(file) {
        while (true) {
            delay(60_000)
            if (player?.isPlaying == true) vm.tocca()
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!video) {
            Text(
                "🎵 Audio",
                color = Palette.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setVideoPath(file.absolutePath)
                    setMediaController(MediaController(c).also { it.setAnchorView(this) })
                    setOnPreparedListener { start() }
                    player = this
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        )
    }
}

@Composable
private fun NonSiApre(perche: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Qui non si apre", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Il telefono mostra immagini, testo, PDF, video e audio. Per il resto ($perche) il " +
                "documento si guarda dal computer. Il file è dentro e sta bene, e non è nemmeno " +
                "stato scaricato: qui manca il modo di mostrarlo, non il documento.",
            color = Palette.muted,
        )
    }
}

// ── Utilità di schermo ──────────────────────────────────────────────────────

@Composable
private fun Avviso(testo: String, colore: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(colore.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, colore.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) { Text(testo, color = Palette.dark, fontSize = 13.sp) }
}

private fun iconaDi(tipo: String): String = when {
    tipo.startsWith("image/") -> "🖼️"
    tipo == "application/pdf" -> "📕"
    tipo.startsWith("video/") -> "🎬"
    tipo.startsWith("audio/") -> "🎵"
    tipo.startsWith("text/") -> "📄"
    else -> "📦"
}

private fun peso(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1_048_576 -> "${n / 1024} kB"
    n < 1_073_741_824 -> String.format("%.1f MB", n / 1_048_576.0)
    else -> String.format("%.2f GB", n / 1_073_741_824.0)
}

/**
 * L'impronta con un `CryptoObject`: senza un'autenticazione fresca quel
 * `Cipher` non produce un byte.
 *
 * ⚠️ Solo `BIOMETRIC_STRONG`: legare la chiave al PIN del telefono vorrebbe
 * dire che il forziere si apre con quello che si digita davanti a chiunque in
 * autobus.
 */
private fun chiediImpronta(
    ctx: Context,
    titolo: String,
    cipher: javax.crypto.Cipher,
    onOk: (javax.crypto.Cipher) -> Unit,
    onNiente: () -> Unit,
) {
    val activity = ctx as? FragmentActivity ?: return onNiente()
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) onOk(c) else onNiente()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onNiente()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("🔐 Forziere")
            .setSubtitle(titolo)
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Annulla")
            .build(),
        BiometricPrompt.CryptoObject(cipher),
    )
}
