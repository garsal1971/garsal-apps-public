package com.garsal.appsphere.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.DialogoAggiornamento
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.coloreDaHex
import com.garsal.appsphere.premi.PremiScreen
import com.garsal.appsphere.premi.RossoPremi
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Quanto il riquadro del totale sta staccato dagli angoli (web: 20 px). */
private val MARGINE_PANNELLO = 16.dp

/**
 * Quanto dello schermo possono prendersi i riquadri in basso a sinistra.
 *
 * ⚠️ **È un tetto e non una larghezza**: i riquadri restano stretti quanto il
 * loro contenuto — è quella misura che le bolle scansano — e il tetto serve
 * solo a lasciare il posto al widget del codice, che in modalità nascosta sta
 * nell'angolo opposto **sulla stessa riga**. Superato il tetto il secondo
 * riquadro va a capo invece di finirgli sopra: coi caratteri di sistema
 * grandi due riquadri affiancati non ci stanno, ed è la condizione normale,
 * non il caso limite.
 */
private const val LARGHEZZA_PANNELLI = 0.74f

/**
 * Il rosso scuro del riquadro delle app nascoste: un passo sotto il
 * [RossoPremi] del totale, perché i due riquadri stanno affiancati e con lo
 * stesso rosso si leggerebbero come un riquadro solo spezzato in due. Scuro e
 * non un altro colore: quel numero è comunque un punteggio, solo di roba che
 * non si vede.
 */
private val RossoNascoste = Color(0xFF7F1D1D)

/**
 * Il bordo nero di bolle e riquadro. Nel CSS sono 4 px, che su questo schermo
 * varrebbero un dp e mezzo: qui è in dp perché resti spesso come si vede sul
 * monitor, dove quel bordo è il segno grafico dell'app.
 */
private val BORDO_BOLLA = 3.dp

/**
 * Quanto del cerchio può occupare il nome, in larghezza e in altezza: è il
 * `fill` di `fitFontSize()` in `index.html` per la vista a bolle (0,64).
 */
private const val RIEMPIMENTO_BOLLA = 0.64f

/**
 * Il carattere più piccolo a cui il nome di una bolla può scendere.
 *
 * ⚠️ **È in `dp` e non in `sp`, ed è l'unica misura di testo dell'app che lo
 * sia.** La bolla ha una dimensione **fisica** — il pavimento di 6 cm² non
 * cresce coi caratteri di sistema — quindi un pavimento in `sp` crescerebbe
 * con loro: a ingrandimento doppio 8 sp sono 16 dp veri, e un nome lungo non
 * ci starebbe **nemmeno al minimo**, cioè il taglio tornerebbe proprio nel
 * caso per cui questo conto esiste. Convertito con `toSp()` resta 8 dp a
 * qualunque ingrandimento: il nome si stringe, il resto dell'app no.
 */
private val MIN_TESTO_BOLLA = 8.dp

/** Il `#111` del web, non il nero pieno. */
private val NeroBordo = Color(0xFF111111)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onApriApp: (String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var mostraVersione by remember { mutableStateOf(false) }
    var mostraPremi by remember { mutableStateOf(false) }
    var mostraCassetto by remember { mutableStateOf(false) }

    fun chiudiPremi() {
        mostraPremi = false
        // Un premio ritirato ha speso dei punti: il riquadro in basso deve
        // dirlo appena si torna, non al prossimo avvio.
        vm.ricaricaPuntiSpesi()
    }

    // Il catalogo premi non è una destinazione di navigazione ma una schermata
    // che prende il posto della home, quindi il tasto indietro va intercettato
    // qui: senza, chiuderebbe l'app invece del catalogo.
    BackHandler(enabled = mostraPremi) { chiudiPremi() }

    if (mostraPremi) {
        PremiScreen(lordo = stato.totaleLordo, onIndietro = { chiudiPremi() })
        return
    }

    // Le icone in `dp` non seguono l'ingrandimento dei caratteri: accanto a un
    // punteggio scritto grande resterebbero minuscole, e soprattutto piccole da
    // centrare col dito. Stesso tetto della top bar.
    val scala = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = if (stato.modalitaNascosta) "AppSphere ·" else "AppSphere",
                azioni = {
                    // Il totale non sta più qui ma nel riquadro in basso, come
                    // sul web: due volte la stessa cifra, con i caratteri di
                    // sistema grandi, sono solo una riga in meno per il titolo.
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Ricarica",
                        tint = Palette.light,
                        modifier = Modifier
                            .size(26.dp * scala)
                            .clickable { vm.ricarica() },
                    )
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Versione e aggiornamento",
                        tint = Palette.light,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .size(26.dp * scala)
                            .clickable { mostraVersione = true },
                    )
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (!stato.avvisiChiusi && stato.avvisi.isNotEmpty()) {
                FumettoAvvisi(
                    avvisi = stato.avvisi,
                    onApri = onApriApp,
                    onChiudi = { vm.chiudiAvvisi() },
                )
            }

            stato.errore?.let { messaggio ->
                Text(
                    text = messaggio,
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Box(Modifier.fillMaxSize()) {
                // Il riquadro del totale galleggia sopra il campo delle bolle,
                // che lo scansano: qui se ne misura l'ingombro, perché con i
                // caratteri di sistema grandi è alto il doppio e un rettangolo
                // scritto a mano nel codice sarebbe sbagliato proprio lì.
                var ingombroPannello by remember { mutableStateOf(IntSize.Zero) }
                // 🗄️ Il cassetto in alto a destra: anche lui si scansa, e si misura.
                var ingombroCassetto by remember { mutableStateOf(IntSize.Zero) }
                val nelCassetto = stato.bolleNelCassetto

                if (stato.bolle.isEmpty() && stato.caricamento) {
                    CircularProgressIndicator(
                        color = Palette.topBar,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (stato.bolleInHome.isEmpty() && stato.bolle.isEmpty()) {
                    Text(
                        text = "Nessuna app da mostrare.",
                        color = Palette.muted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    CampoBolle(
                        bolle = stato.bolleInHome,
                        ingombroPannello = ingombroPannello,
                        ingombroCassetto = if (nelCassetto.isEmpty()) IntSize.Zero else ingombroCassetto,
                        inCattura = stato.inCattura,
                        onApri = onApriApp,
                        onCatturaColore = vm::catturaColore,
                        onPressioneLunga = vm::apriCattura,
                        onDoppioTocco = { vm.mettiNelCassetto(it.htmlFile) },
                    )
                }

                if (nelCassetto.isNotEmpty()) {
                    Cassetto(
                        bolle = nelCassetto,
                        onClick = { mostraCassetto = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(MARGINE_PANNELLO)
                            .onSizeChanged { ingombroCassetto = it },
                    )
                }

                // Il tetto sta sul Box e la misura sul FlowRow: il primo
                // lascia il posto al widget del codice, la seconda è quella
                // che le bolle scansano e deve restare l'ingombro vero.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(MARGINE_PANNELLO)
                        .fillMaxWidth(LARGHEZZA_PANNELLI)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.onSizeChanged { ingombroPannello = it },
                    ) {
                        PannelloTotale(
                            totale = stato.totaleNetto,
                            onClick = { mostraPremi = true },
                        )

                        // ⚠️ Solo a modalità nascosta accesa, e solo se
                        // un'app riservata c'è davvero: fuori di lì quel
                        // numero non esiste, e un riquadro che dice 0
                        // annuncerebbe l'esistenza di quel che la modalità
                        // nascosta serve a non annunciare.
                        if (stato.modalitaNascosta && stato.appRiservate > 0) {
                            PannelloNascoste(totale = stato.totaleRiservate)
                        }
                    }
                }

                // Il widget del codice: si vede mentre si digita e, a modalità
                // accesa, resta lì con l'occhio — che è l'unico segno che la
                // modalità è su, oltre al punto nel titolo.
                if (stato.inCattura || stato.modalitaNascosta) {
                    WidgetCodice(
                        caselle = stato.caselle,
                        colori = stato.coloriCatturati,
                        inCattura = stato.inCattura,
                        modalitaNascosta = stato.modalitaNascosta,
                        onVerifica = { vm.verificaCodice() },
                        onSpegni = { vm.spegniModalitaNascosta() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(MARGINE_PANNELLO),
                    )
                }
            }
        }
    }

    if (mostraVersione) {
        DialogoAggiornamento(onChiudi = { mostraVersione = false })
    }

    val dentroIlCassetto = stato.bolleNelCassetto
    if (mostraCassetto && dentroIlCassetto.isNotEmpty()) {
        DialogoCassetto(
            bolle = dentroIlCassetto,
            onRimetti = {
                vm.togliDalCassetto(it.htmlFile)
                // Tolta l'ultima, il dialogo si chiude da sé (come closeDrawer() nel web).
                if (dentroIlCassetto.size <= 1) mostraCassetto = false
            },
            onChiudi = { mostraCassetto = false },
        )
    }
}

/**
 * 🗄️ Il cassetto (APK 1.0.119), gemello di `#drawer-chip` di `index.html`: un quadrato grigio
 * chiaro col bordo nero e dentro una bollicina per app, del colore della sua bolla, in una
 * griglia di ⌈√n⌉ colonne — cresce col numero e resta quadrato, mai sotto i 64 dp.
 */
@Composable
private fun Cassetto(bolle: List<Bolla>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mostrate = bolle.take(MAX_BOLLICINE)
    val altre = bolle.size - mostrate.size
    val celle = mostrate.size + if (altre > 0) 1 else 0
    val colonne = kotlin.math.ceil(kotlin.math.sqrt(celle.toDouble())).toInt().coerceAtLeast(1)
    val lato = maxOf(64.dp, BOLLICINA * colonne + 4.dp * (colonne - 1) + 12.dp * 2 + 4.dp * 2)
    Box(
        modifier
            .size(lato)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFE5E7EB))
            .border(4.dp, NeroBordo, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val celleList: List<Bolla?> = mostrate + if (altre > 0) listOf(null) else emptyList()
            celleList.chunked(colonne).forEach { riga ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    riga.forEach { b ->
                        if (b == null) {
                            Text("+$altre", color = NeroBordo, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Box(
                                Modifier
                                    .size(BOLLICINA)
                                    .clip(CircleShape)
                                    .background(coloreDaHex(b.colore) ?: Palette.olimpici.first())
                                    .border(2.dp, NeroBordo, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** L'elenco delle app nel cassetto: un tocco la rimette in home. */
@Composable
private fun DialogoCassetto(bolle: List<Bolla>, onRimetti: (Bolla) -> Unit, onChiudi: () -> Unit) {
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("🗄️ Cassetto") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Tocca un'app per rimetterla nella home. Per mettercene un'altra: doppio tocco sulla sua bolla.",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                bolle.forEach { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.inputBg)
                            .clickable { onRimetti(b) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(coloreDaHex(b.colore) ?: Palette.olimpici.first())
                                .border(3.dp, NeroBordo, CircleShape)
                        )
                        Text(b.nome, color = Palette.dark, modifier = Modifier.weight(1f))
                        Text("↩ in home", color = Palette.accent, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text("Chiudi") } },
    )
}

private val BOLLICINA = 16.dp
private const val MAX_BOLLICINE = 24

/**
 * L'area delle bolle: dimensioni e collocazione le decide [BubbleLayout],
 * qui si disegnano e si trascinano.
 *
 * `ingombroPannello` è quanto misura il riquadro del totale, che sta in basso
 * a sinistra sopra quest'area: serve a ricavarne il rettangolo da scansare.
 *
 * Con `inCattura` acceso le bolle **non si aprono più**: il tocco registra il
 * loro colore nel codice. È la stessa scelta del web, dove `onDown` esce prima
 * di armare `onUp` e `launchApp` non scatta mai — senza, ogni cifra del codice
 * aprirebbe un'app.
 */
@Composable
private fun CampoBolle(
    bolle: List<Bolla>,
    ingombroPannello: IntSize,
    ingombroCassetto: IntSize,
    inCattura: Boolean,
    onApri: (String) -> Unit,
    onCatturaColore: (String) -> Unit,
    onPressioneLunga: () -> Unit,
    onDoppioTocco: (Bolla) -> Unit,
) {
    val densita = LocalDensity.current.density
    val marginePx = with(LocalDensity.current) { MARGINE_PANNELLO.toPx() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // La pressione lunga sullo sfondo apre il widget, come sul `#field`
            // del web. Sta sul campo e non su un pulsante: un pulsante
            // etichettato «modalità nascosta» sarebbe una modalità nascosta
            // annunciata.
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onPressioneLunga() })
            }
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        val pannello = remember(ingombroPannello, ingombroCassetto, w, h, marginePx) {
            buildList {
                if (ingombroPannello != IntSize.Zero) add(BubbleLayout.Pannello(
                    x = marginePx,
                    y = h - marginePx - ingombroPannello.height,
                    w = ingombroPannello.width.toFloat(),
                    h = ingombroPannello.height.toFloat(),
                ))
                // 🗄️ Il cassetto, in alto a destra.
                if (ingombroCassetto != IntSize.Zero) add(BubbleLayout.Pannello(
                    x = w - marginePx - ingombroCassetto.width,
                    y = marginePx,
                    w = ingombroCassetto.width.toFloat(),
                    h = ingombroCassetto.height.toFloat(),
                ))
            }
        }

        val raggi = remember(bolle, w, h, densita) {
            val massimo = bolle.maxOfOrNull { it.punteggio } ?: 0
            bolle.map { BubbleLayout.diametro(it.punteggio, massimo, densita) / 2f }
        }

        // Le posizioni si ricalcolano solo quando cambiano le bolle, la
        // dimensione dell'area o quella del pannello: un trascinamento non deve
        // rimescolare tutto.
        var posizioni by remember(bolle, w, h, pannello) {
            mutableStateOf(
                buildList {
                    val nodi = mutableListOf<BubbleLayout.Nodo>()
                    raggi.forEachIndexed { i, r ->
                        val (x, y) = BubbleLayout.collocazioneIniziale(r, nodi, w, h)
                        nodi += BubbleLayout.Nodo(i, x, y, r)
                    }
                    BubbleLayout.assesta(nodi, w, h, pannello)
                    addAll(nodi.map { Offset(it.x, it.y) })
                }
            )
        }

        bolle.forEachIndexed { i, bolla ->
            if (i >= posizioni.size || i >= raggi.size) return@forEachIndexed
            val r = raggi[i]
            val centro = posizioni[i]

            BollaCerchio(
                bolla = bolla,
                raggio = r,
                centro = centro,
                inCattura = inCattura,
                onApri = {
                    if (inCattura) onCatturaColore(bolla.colore) else onApri(bolla.route)
                },
                onDoppioTocco = { onDoppioTocco(bolla) },
                onTrascina = { spostamento ->
                    val nodi = posizioni.mapIndexed { j, p ->
                        BubbleLayout.Nodo(j, p.x, p.y, raggi[j])
                    }.toMutableList()
                    nodi[i].x += spostamento.x
                    nodi[i].y += spostamento.y
                    BubbleLayout.risolviTrascinamento(nodi, i, w, h, pannello)
                    posizioni = nodi.map { Offset(it.x, it.y) }
                },
            )
        }
    }
}

// Il nome è BollaCerchio e non Bolla per non accavallarsi al data class Bolla
// di questo stesso package: la chiamata finirebbe sul costruttore.
@Composable
private fun BollaCerchio(
    bolla: Bolla,
    raggio: Float,
    centro: Offset,
    inCattura: Boolean,
    onApri: () -> Unit,
    onDoppioTocco: () -> Unit,
    onTrascina: (Offset) -> Unit,
) {
    val densita = LocalDensity.current.density
    val coloreCerchio = coloreDaHex(bolla.colore) ?: Palette.olimpici.first()
    // Testo bianco o scuro secondo quanto è chiaro lo sfondo: il giallo
    // olimpico con scritta bianca sopra non si legge.
    val coloreTesto = if (coloreCerchio.luminance() > 0.6f) Palette.dark else Palette.light
    val diametroDp = (raggio * 2f / densita).dp
    // Il numero si scrive solo se è un punteggio: quello di Spuntiamola sono i
    // giorni che mancano, quello di Obiettivi gli obiettivi attivi, e sotto il
    // nome di una bolla un conteggio si legge come punti (vedi
    // [Bolla.contaPunti], cioè `cm_apps.conta_punti`). Una bolla in cache
    // salvata prima della v1.0.92 il campo non ce l'ha e vale `false`: per
    // quel primo avvio il numero non si scrive, che è l'errore innocuo dei due.
    //
    // ⚠️ **La condizione è «diverso da zero», non «maggiore di zero».** Il
    // totale di un'app può essere **negativo** — una risposta di SOS toglie
    // punti, un'abitudine fallita pure — e con `> 0` quel numero spariva dalla
    // bolla: la stessa app mostrava −40 sul web e niente qui, che è il modo
    // peggiore di dirlo, perché una bolla senza numero si legge come «zero».
    // Solo lo zero resta muto, come sul web (`app.score ? … : ''`): una bolla
    // al minimo con uno «0» sotto sembra rotta, non vuota.
    val conPunteggio = bolla.punteggio != 0 && bolla.contaPunti
    // `size * 0.13` come il web, con lo stesso minimo di 9.
    val dimensionePunteggio = ((raggio * 2f * 0.13f) / densita).coerceAtLeast(9f).sp
    // Lo stile del nome si scrive una volta sola e serve a due cose: misurare
    // e disegnare. Misurare con uno stile diverso da quello che poi va a
    // schermo vuol dire misurare un altro testo.
    val stileNome = LocalTextStyle.current.copy(
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val dimensioneTesto = misuraTesto(
        nome = bolla.nome,
        punteggio = if (conPunteggio) "${bolla.punteggio}" else null,
        dimensionePunteggio = dimensionePunteggio,
        diametro = raggio * 2f,
        stile = stileNome,
    )

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (centro.x - raggio).roundToInt(),
                    y = (centro.y - raggio).roundToInt(),
                )
            }
            .size(diametroDp)
            .clip(CircleShape)
            .background(coloreCerchio)
            // Il bordo nero del web (`border: 4px solid #111`), che stacca le
            // bolle scure dallo sfondo bianco e quelle chiare fra loro.
            .border(BORDO_BOLLA, NeroBordo, CircleShape)
            // A codice aperto le bolle non si trascinano: il gesto serve tutto
            // a toccarle una per una, e una bolla che scivola sotto il dito
            // mentre si digita il codice fa perdere la cifra.
            .pointerInput(bolla.htmlFile, inCattura) {
                if (inCattura) return@pointerInput
                detectDragGestures { cambiamento, spostamento ->
                    cambiamento.consume()
                    onTrascina(spostamento)
                }
            }
            // 🗄️ Doppio tocco → nel cassetto (APK 1.0.119). ⚠️ detectTapGestures col doppio
            // tocco aspetta un attimo prima di dare il tocco singolo, come il web (DOPPIO_MS).
            // A codice aperto il doppio tocco non c'è: ogni tocco è una cifra, subito.
            .pointerInput(bolla.htmlFile, inCattura) {
                detectTapGestures(
                    onDoubleTap = if (inCattura) null else ({ _: Offset -> onDoppioTocco() }),
                    onTap = { onApri() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = (diametroDp.value * 0.12f).dp),
        ) {
            // Nessun `maxLines`: il tetto tagliava i nomi lunghi — coi
            // caratteri di sistema grandi il testo andava a capo una volta in
            // più e la riga in eccesso spariva, senza nemmeno i puntini. Ora ci
            // sta per costruzione, perché [misuraTesto] lo ha misurato davvero.
            Text(
                text = bolla.nome,
                color = coloreTesto,
                style = stileNome.copy(
                    fontSize = dimensioneTesto,
                    lineHeight = dimensioneTesto * 1.2f,
                ),
            )
            // Il punteggio a zero non si scrive, come sul web: una bolla al
            // minimo con uno «0» sotto sembra rotta, non vuota.
            if (conPunteggio) {
                Text(
                    text = "${bolla.punteggio}",
                    color = coloreTesto.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    fontSize = dimensionePunteggio,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Il widget del codice a colori — il `#code-entry` del web: una casella per
 * colore da indovinare, la freccia che verifica e l'occhio che spegne.
 *
 * Le caselle si riempiono toccando le bolle: **è la bolla a dare il colore**,
 * quindi il codice non si può leggere da nessuna parte nell'app, e cambia da
 * sé se un giorno cambiano i colori delle app. Toccare una casella già piena
 * verifica, come sul web, dove serve a chi ha finito di digitare senza dover
 * centrare la freccia.
 */
@Composable
private fun WidgetCodice(
    caselle: Int,
    colori: List<String>,
    inCattura: Boolean,
    modalitaNascosta: Boolean,
    onVerifica: () -> Unit,
    onSpegni: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF2FFFFFF))
            .border(2.dp, NeroBordo, RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (inCattura) {
            repeat(caselle) { i ->
                val colore = colori.getOrNull(i)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colore?.let { coloreDaHex(it) } ?: Palette.inputBg)
                        .border(2.dp, NeroBordo, RoundedCornerShape(6.dp))
                        .clickable(enabled = colori.isNotEmpty()) { onVerifica() },
                )
            }
            Text(
                text = "›",
                color = Palette.dark,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onVerifica() }
                    .padding(horizontal = 8.dp),
            )
        }

        if (modalitaNascosta) {
            // L'occhio compare solo a modalità accesa: è insieme il segno che
            // è accesa e il modo per spegnerla senza rifare il codice.
            Text(
                text = "👁",
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSpegni() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Il riquadro rosso in basso a sinistra: i punti che restano da spendere.
 *
 * È il `#score-panel` del web, compreso il fatto che si tocca — ed è l'unico
 * modo per arrivare al catalogo premi, di là come di qua.
 */
@Composable
private fun PannelloTotale(
    totale: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    RiquadroPunteggio(
        etichetta = "TOTALE",
        totale = totale,
        fondo = RossoPremi,
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * Il riquadro rosso scuro accanto al totale: il numero delle app **riservate**,
 * quelle che compaiono solo col codice a colori.
 *
 * ⚠️ **Non si tocca e non porta da nessuna parte**: quel numero non compra
 * niente. Il totale accanto si tocca perché è l'unica via per il catalogo
 * premi; qui un tocco che non fa niente si leggerebbe come un riquadro rotto,
 * e uno che aprisse i premi direbbe che quei punti si spendono.
 *
 * ⚠️ **È un numero solo anche se somma grandezze diverse** — le transazioni di
 * Finanza e i file del Forziere stanno nella stessa cifra. È una scelta presa
 * sapendo cosa costa: dice «quanto c'è dietro la modalità nascosta» e non
 * quanto vale, ed è la ragione per cui sta in un riquadro **suo** invece che
 * dentro al totale.
 */
@Composable
private fun PannelloNascoste(
    totale: Int,
    modifier: Modifier = Modifier,
) {
    RiquadroPunteggio(
        etichetta = "NASCOSTE",
        totale = totale,
        fondo = RossoNascoste,
        modifier = modifier,
    )
}

/**
 * La forma comune dei due riquadri: etichetta piccola e numero grande.
 *
 * Uno solo e non due copie: stanno affiancati, quindi una differenza di
 * riempimento o di corpo del carattere fra loro si vedrebbe come un difetto —
 * l'unica cosa che li distingue dev'essere il colore del fondo.
 */
@Composable
private fun RiquadroPunteggio(
    etichetta: String,
    totale: Int,
    fondo: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        // ⚠️ Il `clip` viene **prima** del `clickable`, o l'onda del tocco
        // si disegnerebbe sul rettangolo invece che dentro gli angoli tondi.
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fondo)
            .border(BORDO_BOLLA, NeroBordo, RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // `heightIn` implicito: niente altezza fissa attorno a due scritte
            // che coi caratteri di sistema grandi crescono da sole.
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = etichetta,
            color = Palette.light.copy(alpha = 0.7f),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = String.format(Locale.ITALY, "%,d", totale),
            color = Palette.light,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * Dimensione del testo dentro la bolla, **misurata e non stimata**.
 *
 * È la ricerca binaria di `fitFontSize()` in `index.html`, con gli stessi due
 * vincoli: la **parola più lunga** deve stare dentro `diametro × 0,64` senza
 * essere spezzata, e l'**altezza del nome mandato a capo** dentro quel che
 * resta in verticale. Si tiene il carattere più grande che soddisfa entrambi.
 *
 * ⚠️ **La stima non poteva funzionare, e il difetto si vedeva solo sul
 * telefono.** Prima si tirava a indovinare (larghezza utile ÷ lunghezza della
 * parola più lunga × 0,58) e poi si tagliava con `maxLines = 3`: la stima è in
 * `sp`, che **il sistema moltiplica ancora per `fontScale`**, quindi coi
 * caratteri di sistema grandi il nome andava a capo una volta in più di quel
 * che il conto prevedeva e la riga in eccesso veniva ritagliata via — dal
 * `maxLines` o dal `clip(CircleShape)` della bolla — senza nemmeno i puntini a
 * dire che mancava qualcosa. Misurando, l'ingrandimento è già dentro il
 * risultato: il carattere si stringe da sé invece di essere tagliato.
 *
 * ⚠️ **Le parole si misurano una per una e senza andare a capo**: misurando il
 * nome intero dentro un vincolo di larghezza, Compose restituisce comunque una
 * larghezza pari al vincolo, e una parola più larga del cerchio risulterebbe
 * larga quanto lui — cioè il caso che si sta cercando passerebbe inosservato.
 * È la stessa doppia misura del web (`el` senza a capo, `elH` col vincolo).
 *
 * ⚠️ **Il posto del punteggio si toglie misurandolo**, non con un coefficiente:
 * anche lui è in `sp` e cresce coi caratteri di sistema, quindi una frazione
 * fissa dell'altezza sarebbe giusta a un solo ingrandimento.
 *
 * Il risultato è dentro un `remember`: le bolle si ridisegnano a ogni
 * spostamento del dito, e una ricerca binaria per fotogramma sarebbe pagata
 * proprio mentre si trascina.
 */
@Composable
private fun misuraTesto(
    nome: String,
    punteggio: String?,
    dimensionePunteggio: TextUnit,
    diametro: Float,
    stile: TextStyle,
): TextUnit {
    val misuratore = rememberTextMeasurer()
    val densita = LocalDensity.current
    return remember(nome, punteggio, dimensionePunteggio, diametro, stile, densita, misuratore) {
        val parole = nome.split(Regex("\\s+")).filter { it.isNotBlank() }.ifEmpty { listOf(nome) }
        val larghezzaUtile = (diametro * RIEMPIMENTO_BOLLA).roundToInt()
        val altezzaPunteggio = punteggio?.let {
            misuratore.measure(it, stile.copy(fontSize = dimensionePunteggio)).size.height +
                with(densita) { 2.dp.toPx() }
        } ?: 0f
        val altezzaUtile = diametro * RIEMPIMENTO_BOLLA - altezzaPunteggio

        // Il tetto è quello del web (`circleSize * 0.55`), qui riportato in sp
        // perché `toSp()` tiene già conto dell'ingrandimento di sistema.
        val tetto = with(densita) { (diametro * 0.55f).toSp().value }
        val pavimento = with(densita) { MIN_TESTO_BOLLA.toSp().value }
        var basso = pavimento
        var alto = max(tetto, pavimento)
        repeat(12) {
            val mezzo = (basso + alto) / 2f
            val provato = stile.copy(fontSize = mezzo.sp, lineHeight = (mezzo * 1.2f).sp)
            val parolaPiuLarga = parole.maxOf {
                misuratore.measure(it, provato, softWrap = false).size.width
            }
            val altezza = misuratore.measure(
                text = nome,
                style = provato,
                constraints = Constraints(maxWidth = larghezzaUtile),
            ).size.height
            if (parolaPiuLarga <= larghezzaUtile && altezza <= altezzaUtile) basso = mezzo
            else alto = mezzo
        }
        basso.sp
    }
}

@Composable
private fun FumettoAvvisi(
    avvisi: List<Avviso>,
    onApri: (String) -> Unit,
    onChiudi: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E0)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    text = "Avvisi",
                    fontWeight = FontWeight.Bold,
                    color = Palette.dark,
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Chiudi avvisi",
                    tint = Palette.muted,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(20.dp)
                        .clickable(onClick = onChiudi),
                )
            }
            avvisi.forEach { avviso ->
                Text(
                    text = avviso.testo,
                    color = Palette.dark,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onApri(avviso.route) },
                )
            }
        }
    }
}
