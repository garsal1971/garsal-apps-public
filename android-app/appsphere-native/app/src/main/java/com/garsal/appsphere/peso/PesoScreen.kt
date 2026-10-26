package com.garsal.appsphere.peso

import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import com.garsal.appsphere.core.IconeLinea
import com.garsal.appsphere.core.InterruttorePesoCalorie
import com.garsal.appsphere.core.VoceConIcona
import com.garsal.appsphere.core.misuraIcona
import com.garsal.appsphere.core.Palette
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Il verde di Weight Quest (`--success` nel CSS della pagina). */
internal val Verde = Color(0xFF00B894)

private enum class Vista(val etichetta: String, val icona: ImageVector) {
    OGGI("Oggi", IconeLinea.peso),
    TABELLA("Tabella", IconeLinea.tabella),
    GRAFICO("Grafico", IconeLinea.statistiche),
}

/**
 * «Ti pisasti?» — il peso, in nativo.
 *
 * ⚠️ Gemella di `weight-quest.html`, sulle stesse tabelle `ps_*`. Qui ci sono
 * le cose che si fanno col telefono in mano: **segnare la pesata** — dal FAB,
 * che apre la scelta fra «➕ Inserisci a mano» e «🔄 Sincronizza (Renpho)»,
 * quest'ultima con Health Connect ([SaluteRepository]) — guardare **com'è
 * andata** giorno per giorno, vedere **la curva** e, da «✏️ Gestisci»
 * ([GestioneObiettivoScreen]), creare/modificare/chiudere/cancellare un
 * obiettivo. Restano sulla pagina web, che è dove si fa da seduti: le
 * statistiche, «genera dieta» e il gratta e vinci dei premi, che vive in
 * `localStorage` ed è per dispositivo, quindi non avrebbe niente da
 * mostrare qui.
 *
 * Le regole di calcolo non sono riscritte a occhio: stanno in [PesoRegole],
 * ricalcate una per una dalla pagina.
 */
@Composable
fun PesoScreen(
    onIndietro: () -> Unit,
    onApriCalorie: () -> Unit,
    vm: PesoViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var vista by remember { mutableStateOf(Vista.OGGI) }
    var pesataDaFare by remember { mutableStateOf<LocalDate?>(null) }
    var giornoAperto by remember { mutableStateOf<String?>(null) }
    // Come in Ta Firi?: la Gestione Obiettivo sta qui, sostituisce lo schermo
    // intero finché è aperta, e non è un dialogo — i campi sono troppi.
    var gestioneAperta by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Il permesso Health Connect si chiede col contratto ufficiale, come nel
    // bridge Kotlin dell'APK WebView: è l'unico modo per cui HC sblocchi le
    // letture successive. `sincronizzaSalute` torna a chiamarsi da sola dopo
    // la concessione, invece di lasciare l'utente a ripremere il pulsante.
    val richiediPermessiSalute = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { vm.sincronizzaSalute(context) }

    // Premi e punti dei traguardi arrivano dal database, e sono di
    // quell'obiettivo: si rileggono ogni volta che cambia quello aperto. Il
    // context serve solo alla migrazione una-tantum dalle vecchie preferenze.
    LaunchedEffect(stato.obiettivoId) {
        if (stato.obiettivoId != null) vm.caricaPremi(context)
    }

    LaunchedEffect(stato.permessiSaluteRichiesti) {
        if (stato.permessiSaluteRichiesti) {
            vm.permessiSaluteMostrati()
            richiediPermessiSalute.launch(PERMESSI_SALUTE)
        }
    }

    // «Tornato da Renpho»: lo stesso `renphoLaunched` del bridge Kotlin
    // dell'APK WebView. Senza questa guardia ogni ritorno in primo piano
    // dell'app — per qualunque motivo — lancerebbe una sincronizzazione.
    var tornatoDaRenpho by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val osservatore = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME && tornatoDaRenpho) {
                tornatoDaRenpho = false
                vm.sincronizzaSalute(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(osservatore)
        onDispose { lifecycleOwner.lifecycle.removeObserver(osservatore) }
    }

    if (gestioneAperta) {
        GestioneObiettivoScreen(
            stato = stato,
            vm = vm,
            onIndietro = { gestioneAperta = false },
        )
        return
    }

    var menuPesatiAperto by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Ti pisasti?",
                onIndietro = onIndietro,
                azioni = {
                    /* ⚖️ Peso ⇄ 🍽️ Calorie: il diario alimentare è a un tocco da qui, e da
                       nessun'altra parte — dal 9 settembre 2026 in home c'è UNA bolla per tutt'e
                       due. È l'interruttore della barra blu di `weight-quest.html`, e sta uguale
                       anche in [com.garsal.appsphere.calorie.CalorieScreen]. */
                    InterruttorePesoCalorie(suCalorie = false, onCambia = onApriCalorie)
                },
            )
        },
        floatingActionButton = {
            // Un solo FAB per le due strade della pesata — a mano o dalla
            // bilancia — invece di un pulsante a sé in barra: il tocco apre
            // la scelta, come un `DropdownMenu` ancorato al FAB stesso.
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menuPesatiAperto = true },
                    containerColor = Verde,
                    contentColor = Palette.light,
                ) {
                    VoceConIcona(
                        IconeLinea.peso,
                        if (stato.pesatoOggi) "Pesati ancora" else "Pesati",
                        Palette.light,
                        grassetto = true,
                    )
                }
                DropdownMenu(
                    expanded = menuPesatiAperto,
                    onDismissRequest = { menuPesatiAperto = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("➕ Inserisci a mano") },
                        onClick = {
                            menuPesatiAperto = false
                            pesataDaFare = LocalDate.now()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Sincronizza (Renpho)") },
                        leadingIcon = {
                            Icon(IconeLinea.sync, contentDescription = null, modifier = Modifier.size(misuraIcona(20.dp)))
                        },
                        onClick = {
                            menuPesatiAperto = false
                            tornatoDaRenpho = true
                            SaluteRepository.apriRenpho(context)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (stato.caricamento && stato.pesate.isEmpty() && stato.obiettivi.isEmpty()) {
                CircularProgressIndicator(
                    color = Verde,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    SelettoreVista(vista) { vista = it }

                    when (vista) {
                        Vista.OGGI -> VistaOggi(
                            stato = stato,
                            vm = vm,
                            onScegliObiettivo = { vm.scegliObiettivo(it) },
                            onPesati = { pesataDaFare = LocalDate.now() },
                            onGestisci = { gestioneAperta = true },
                        )

                        Vista.TABELLA -> VistaTabella(
                            righe = PesoRegole.storico(stato.righe, stato.obiettivo),
                            onApri = { giornoAperto = it },
                        )

                        Vista.GRAFICO -> VistaGrafico(stato)
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

    pesataDaFare?.let { giorno ->
        DialogoPesata(
            giorno = giorno,
            target = stato.obiettivo?.let {
                PesoRegole.targetInterpolato(it.traguardi, giorno.toString())
            },
            onAnnulla = { pesataDaFare = null },
            onConferma = { ora, peso ->
                vm.pesati(giorno, ora, peso)
                pesataDaFare = null
            },
        )
    }

    giornoAperto?.let { giorno ->
        DialogoGiorno(
            giorno = giorno,
            pesate = vm.pesateDel(giorno),
            onChiudi = { giornoAperto = null },
            onElimina = { vm.elimina(it) },
        )
    }
}

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
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (attiva) Verde else Palette.inputBg)
                    .clickable { onScegli(vista) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                VoceConIcona(vista.icona, vista.etichetta, if (attiva) Palette.light else Palette.dark, attiva)
            }
        }
    }
}

// ── Oggi ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VistaOggi(
    stato: PesoState,
    vm: PesoViewModel,
    onScegliObiettivo: (String) -> Unit,
    onPesati: () -> Unit,
    onGestisci: () -> Unit,
) {
    // v1.0.128: quali esiti sono già stati scoperti lo sanno le preferenze, non lo stato.
    // Dopo un tocco sul ❓ esitiVer cresce e le schede si ricalcolano (il giorno passa da
    // «Prossima» a «Precedente»).
    val contestoSchede = LocalContext.current
    var esitiVer by remember { mutableStateOf(0) }
    // v1.0.148: lo switch del grafo (media mobile / peso reale) resta nelle preferenze.
    var reale by remember { mutableStateOf(prefsEsiti(contestoSchede).getBoolean("grafo_reale", false)) }
    val nodi = remember(stato, esitiVer, reale) {
        val prefs = prefsEsiti(contestoSchede)
        stato.grafo(reale) { o, g -> prefs.contains("esito_${o}_$g") }
    }
    var scelto by remember { mutableStateOf(LocalDate.now().toString()) }
    val dettaglio = remember(stato, esitiVer, scelto) {
        val prefs = prefsEsiti(contestoSchede)
        stato.dettaglio(scelto) { o, g -> prefs.contains("esito_${o}_$g") }
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // v1.0.148: il grafo dei giorni al posto delle tre schede (web v4.19.0),
        // e sotto il dettaglio del giorno toccato.
        item {
            GrafoGiorni(
                nodi = nodi,
                reale = reale,
                scelto = scelto,
                senzaObiettivo = stato.obiettivo == null,
                onReale = { v -> reale = v; prefsEsiti(contestoSchede).edit().putBoolean("grafo_reale", v).apply() },
                onScegli = { scelto = it },
            )
        }
        item {
            val d = dettaglio
            if (d != null) SchedaPesataCard(d, intestazione = intestazioneGiorno(d.giorno, d.conta)) { esitiVer++ }
            else SchedaVuota(
                titolo = "⚖️ " + intestazioneGiorno(scelto, false),
                righe = listOf("Peso" to (nodi.firstOrNull { it.giorno == scelto }?.reale?.let { "${kg(it)} kg" } ?: "–")),
                nota = "Nessun obiettivo in corso: niente target.",
            )
        }

        item {
            // I tre riquadri rimasti, nello stesso ordine della pagina. Con
            // un'altezza **minima** e non fissa: coi caratteri grandi le
            // etichette vanno a capo, e una misura fissa le taglierebbe.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                val meta = Modifier.weight(1f)
                Riquadro("Kg alla fine", conSegno(stato.kgAllaFine), Palette.warning, meta)
                Riquadro("Punteggio", "${stato.punteggio}", Palette.topBar, meta)
                Riquadro("Punti oggi", stato.puntiOggiMostrati?.toString() ?: "–", Palette.primary, meta)
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🎯 Obiettivo", fontWeight = FontWeight.Bold, color = Palette.dark)
                Text(
                    text = "✏️ Gestisci",
                    color = Verde,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onGestisci)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (stato.obiettivi.isNotEmpty()) {
            item {
                SelettoreObiettivo(
                    obiettivi = stato.obiettivi,
                    scelto = stato.obiettivo,
                    onScegli = onScegliObiettivo,
                )
            }
            stato.obiettivo?.let { obiettivo ->
                item { DettaglioObiettivo(obiettivo) }
                // Le stelline dei traguardi intermedi: sola lettura qui, i
                // punti si tarano solo da «✏️ Gestisci» (BarraTraguardi in
                // GestioneObiettivo.kt), ma si toccano lo stesso per grattare
                // un premio già raggiunto.
                item {
                    BarraTraguardi(
                        obiettivo = obiettivo,
                        stato = stato,
                        vm = vm,
                        modificabile = false,
                    )
                }
            }
        } else {
            item {
                Text(
                    "Nessun obiettivo ancora: tocca «Gestisci» per crearne uno.",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Text(
                text = "Statistiche, «genera dieta» e sincronizzazione con la bilancia " +
                    "restano su weight-quest.html.",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp, bottom = 60.dp),
            )
        }
    }
}

/**
 * La tendina degli obiettivi. Non `ExposedDropdownMenuBox` — come in
 * `Gestione.kt` — perché qui non si scrive niente, si sceglie da un elenco
 * chiuso, e quel componente vorrebbe l'opt-in su un'API sperimentale per un
 * campo di testo che non serve.
 *
 * L'elenco arriva già ordinato dal più recente in giù (`obiettivi()`), e
 * quello è anche l'ordine con cui compaiono nel menù.
 */
@Composable
internal fun SelettoreObiettivo(
    obiettivi: List<Obiettivo>,
    scelto: Obiettivo?,
    onScegli: (String) -> Unit,
    // Solo per la Gestione: in cima al menù una voce «✨ Nuovo obiettivo...»,
    // come il `<select>` del web (`populateObjectiveDropdown`).
    mostraNuovo: Boolean = false,
    nuovoScelto: Boolean = false,
    onNuovo: () -> Unit = {},
) {
    var aperto by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.inputBg)
                .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
                .clickable { aperto = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (nuovoScelto) "✨ Nuovo obiettivo..."
                    else scelto?.let { etichettaObiettivo(it) } ?: "Nessun obiettivo",
                color = Palette.dark,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(if (aperto) "︿" else "⌄", color = Palette.muted)
        }
        DropdownMenu(expanded = aperto, onDismissRequest = { aperto = false }) {
            if (mostraNuovo) {
                DropdownMenuItem(
                    text = { Text("✨ Nuovo obiettivo...") },
                    onClick = { aperto = false; onNuovo() },
                )
            }
            obiettivi.forEach { obiettivo ->
                DropdownMenuItem(
                    text = { Text(etichettaObiettivo(obiettivo)) },
                    onClick = {
                        aperto = false
                        onScegli(obiettivo.id)
                    },
                )
            }
        }
    }
}

/** Icona del tipo + nome, con un marcatore per quelli già chiusi. */
internal fun etichettaObiettivo(obiettivo: Obiettivo): String {
    val icona = if (obiettivo.tipo == "mantenere") "⚖️" else "📉"
    val chiuso = when (obiettivo.stato) {
        "success" -> " · 🏆"
        "failed" -> " · 💀"
        else -> ""
    }
    return "$icona ${obiettivo.nome}$chiuso"
}

/**
 * Tutti i dati dell'obiettivo scelto, sotto la tendina: tipo, periodo e peso
 * di partenza/arrivo, come sta andando (o com'è finito), e la curva su cui si
 * regge il calcolo — quella che manca è la ragione più comune per cui target
 * e punti restano vuoti in cima alla pagina.
 */
@Composable
private fun DettaglioObiettivo(obiettivo: Obiettivo) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.inputBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (obiettivo.tipo == "mantenere") "⚖️ Mantenere il peso" else "📉 Perdere peso",
            fontWeight = FontWeight.SemiBold,
            color = Palette.dark,
        )
        Text(
            text = (obiettivo.ogniGiorni?.let { n ->
                "⚖️ Pesata ogni $n giorn${if (n == 1) "o" else "i"}: punti solo nei giorni fissati"
            } ?: "⚖️ Pesata ogni giorno") + if (obiettivo.promemoriaAttivo)
                " · 🔔 ${obiettivo.promemoriaOra ?: ""} (${obiettivo.promemoriaCanali.joinToString { nomeCanale(it) }})"
            else "",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "📅 ${dataItaliana(obiettivo.inizio)} → ${dataItaliana(obiettivo.fine)}" +
                " · ${kg(obiettivo.pesoIniziale)} → ${kg(obiettivo.pesoFinale)} kg",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = when (obiettivo.stato) {
                "success" -> "🏆 Chiuso con successo · ${obiettivo.punteggioFinale ?: 0} punti"
                "failed" -> "💀 Chiuso come fallito · ${obiettivo.punteggioFinale ?: 0} punti"
                else -> "▶️ In corso · +${obiettivo.bonusGiornaliero} se sei sotto il target, " +
                    "−${obiettivo.malusGiornaliero} se sei sopra"
            },
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (obiettivo.traguardi.size >= 2) {
            Text(
                text = "📈 ${obiettivo.traguardi.size} traguardi sulla curva",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Questo obiettivo non ha una curva di traguardi: senza, target e punti " +
                    "non si possono calcolare. Si aggiungono da «✏️ Gestisci».",
                color = Palette.warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Una scheda delle pesate — `schedaPesata()` + `righeCaloriePesata()` del web.
 * Ogni valore sta su una riga sua, etichetta a sinistra e numero a destra, e
 * la riga può andare a capo: sono i caratteri di sistema grandi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedaPesataCard(s: SchedaPesata, intestazione: String? = null, onRivelato: () -> Unit) {
    val colore = when (s.tipo) {
        TipoScheda.OGGI -> Palette.primary
        TipoScheda.DOPO -> Verde
        TipoScheda.PRIMA -> Palette.secondary
    }
    val titolo = when (s.tipo) {
        TipoScheda.OGGI -> "⚖️ Oggi"
        TipoScheda.PRIMA -> "↩️ Precedente"
        TipoScheda.DOPO -> "➡️ Prossima"
    }
    var quando = dataItaliana(s.giorno).take(5)
    if (s.conta) quando += " · conta per i punti"
    if (s.tipo == TipoScheda.DOPO) {
        val gg = PesoRegole.giornoDa(s.giorno)?.toEpochDay()?.minus(LocalDate.now().toEpochDay())
        if (gg != null) quando += when (gg) { 0L -> " · oggi"; 1L -> " · domani"; else -> " · fra $gg gg" }
    }
    // v1.0.127: la prossima pesata è nel futuro — solo i target del piano, centrati e in
    // grande, come `schedaPesata()` del web v4.15.0.
    if (s.tipo == TipoScheda.DOPO) {
        SchedaProssima(s, intestazione ?: "$titolo · $quando", colore, onRivelato)
        return
    }
    val manca = s.manca
    val coloreManca = when {
        manca == null -> Palette.muted
        PesoRegole.arrotonda(manca, 1) <= 0 -> Verde
        else -> Palette.danger
    }
    // v1.0.127: oggi è una pesata che conta e ci si è pesati → l'esito si scopre col ❓
    // (`rivelaPesata()` nel web). Rivelato, resta scritto su questo telefono.
    val contesto = LocalContext.current
    // v1.0.128: il ❓ sta sulla «Prossima»; qui, scoperto, resta scritto l'esito.
    val visto = remember(s.obiettivoId, s.giorno) {
        prefsEsiti(contesto).getString("esito_${s.obiettivoId}_${s.giorno}", null)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(3.dp, colore, RoundedCornerShape(12.dp))
            .background(Palette.light)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(intestazione ?: "$titolo · $quando", fontWeight = FontWeight.Bold, color = Palette.dark)
        // v1.0.148: oggi conta e l'esito non è scoperto → i numeri aspettano il ❓.
        val celato = s.rivela
        // v1.0.128: senza pesata quel giorno, trattini (come il web v4.15.3).
        RigaScheda("⚖️ Pesata del giorno", if (celato) "❓" else if (s.pesato) "${kg(s.peso)} kg" else "–")
        s.nota?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        // v1.0.141: la media mobile è il valore che fa i punti, e si vede (web v4.17.0).
        FlowRow(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFEDE9FE))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("📈 Media peso", color = Palette.dark)
                Text("conta per il target", color = Color(0xFF7C3AED), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (celato) "❓" else s.media?.let { "${kg(it)} kg" } ?: "–",
                color = Color(0xFF6D28D9),
                fontWeight = FontWeight.Bold,
                fontStyle = if (s.mediaStimata) androidx.compose.ui.text.font.FontStyle.Italic else null,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        RigaScheda("Target", "${kg(s.target)} kg", Verde)
        s.infoTarget?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        RigaScheda("Mancano", if (celato) "❓" else if (manca == null) "–" else "${conSegno(manca)} kg", if (celato) Palette.muted else coloreManca)
        if (s.rivela) BottoneRivela(s, onRivelato)
        if (s.pesato && visto != null) {
            val ok = visto == "ok"
            Text(
                if (ok) "🎉 Target rispettato!" else "💪 Questa volta no, si recupera!",
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (ok) Color(0xFFE8FFF8) else Color(0xFFFFF1F3))
                    .padding(6.dp),
                color = if (ok) Color(0xFF00967A) else Palette.danger,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        s.calorie?.let { c ->
            HorizontalDivider(color = Palette.border, modifier = Modifier.padding(vertical = 4.dp))
            if (c.motivo != null) {
                Text("🔥 Calorie: ${c.motivo}.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            } else {
                RigaScheda("Fabbisogno", "${kcal(c.fabbisogno)} kcal")
                RigaScheda("Kcal da piano", c.daPiano?.let { "${kcal(it)} kcal" } ?: "–")
                RigaScheda("Kcal con andamento", "${kcal(c.conAndamento)} kcal", Palette.secondary)
                when {
                    c.congelato -> "target già fissato nel diario per quel giorno"
                    s.tipo == TipoScheda.DOPO -> "con l'ultimo peso noto"
                    else -> null
                }?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ── v1.0.127: la prossima pesata e l'esito della pesata che conta ─────────
// Gemelli di `schedaPesata()` (ramo «dopo»), `rivelaPesata()`, `fuochiArtificio()`
// e `musichettaTriste()` di weight-quest.html v4.15.0: vanno cambiati insieme.

private fun prefsEsiti(context: android.content.Context) =
    context.applicationContext.getSharedPreferences("peso_esiti", android.content.Context.MODE_PRIVATE)

private val FRASI_KO = listOf(
    "Un giorno storto non cambia la strada: domani si riparte.",
    "La bilancia oggi fa la difficile. Tu no: tieni duro!",
    "Il piano è lungo, e una curva non è un precipizio.",
    "Acqua, passi e pazienza: il numero arriva.",
    "Anche i campioni sbagliano un tiro. Il prossimo è tuo.",
)

@Composable
private fun SchedaProssima(s: SchedaPesata, intestazione: String, colore: Color, onRivelato: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(3.dp, colore, RoundedCornerShape(12.dp))
            .background(Palette.light)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(intestazione, fontWeight = FontWeight.Bold, color = Palette.dark, modifier = Modifier.fillMaxWidth())
        Text("🎯 Target attesi dal piano", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        // v1.0.141: il target si confronta con la media mobile del peso, come nel web v4.17.0.
        Gettone("🎯 Target media peso", "${kg(s.pesoAtteso)} kg")
        if (s.rivela) BottoneRivela(s, onRivelato)
    }
}

// ── v1.0.148: il grafo dei giorni ─────────────────────────────────────────
// Gemello di `renderPesate()` / `grafoNodo()` di weight-quest.html v4.19.0:
// una striscia di nodi che si trascina col dito, centrata su oggi all'apertura.

private val VerdeNodo = Color(0xFF00967A)
private val RossoNodo = Color(0xFFE74C3C)
private val BluNodo = Color(0xFF0081C8)
private val ViolaNodo = Color(0xFF6C5CE7)

internal fun intestazioneGiorno(giorno: String, conta: Boolean): String {
    val oggi = LocalDate.now()
    val d = PesoRegole.giornoDa(giorno) ?: return giorno
    val gg = d.toEpochDay() - oggi.toEpochDay()
    val quando = when {
        gg == 0L -> "oggi"
        gg == -1L -> "ieri"
        gg == 1L -> "domani"
        gg < 0 -> "${-gg} giorni fa"
        else -> "fra $gg giorni"
    }
    return dataItaliana(giorno).take(5) + " · " + quando + if (conta) " · conta per i punti" else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrafoGiorni(
    nodi: List<NodoGrafo>,
    reale: Boolean,
    scelto: String,
    senzaObiettivo: Boolean,
    onReale: (Boolean) -> Unit,
    onScegli: (String) -> Unit,
) {
    val oggi = LocalDate.now().toString()
    val lista = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var larghezza by remember { mutableStateOf(0) }
    val densita = LocalDensity.current
    // Centra su oggi all'apertura (e quando cambiano i giorni): serve la larghezza vera.
    val iOggi = nodi.indexOfFirst { it.giorno == oggi }.let { if (it < 0) nodi.lastIndex else it }
    LaunchedEffect(nodi.size, larghezza) {
        if (larghezza > 0 && iOggi >= 0) {
            val nodoPx = with(densita) { 76.dp.roundToPx() }
            lista.scrollToItem(iOggi, -(larghezza - nodoPx) / 2)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(2.dp, ViolaNodo, RoundedCornerShape(50)),
            ) {
                listOf(false to "📈 Media mobile", true to "⚖️ Peso reale").forEach { (v, etichetta) ->
                    Text(
                        etichetta,
                        Modifier
                            .background(if (reale == v) ViolaNodo else Palette.light)
                            .clickable { onReale(v) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (reale == v) Color.White else ViolaNodo,
                        fontWeight = if (reale == v) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Text(
                "⏺ Oggi",
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(2.dp, Palette.primary, RoundedCornerShape(50))
                    .clickable {
                        onScegli(oggi)
                        if (iOggi >= 0) scope.launch {
                            val nodoPx = with(densita) { 76.dp.roundToPx() }
                            lista.animateScrollToItem(iOggi, -(larghezza - nodoPx) / 2)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Palette.primary,
            )
        }
        if (reale) Text("Colori indicativi: i punti si danno sulla media mobile.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        if (senzaObiettivo) Text("Nessun obiettivo in corso: niente target.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.lazy.LazyRow(
            state = lista,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { larghezza = it.width },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(nodi, key = { it.giorno }) { n ->
                NodoGiorno(n, n.giorno == oggi, n.giorno == scelto) { onScegli(n.giorno) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legenda(VerdeNodo, "dentro il target")
            Legenda(RossoNodo, "sopra o non pesato")
            Legenda(BluNodo, "non conta")
            Legenda(Color(0xFFC4C9D2), "da fare")
        }
    }
}

@Composable
private fun Legenda(colore: Color, testo: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colore))
        Text(testo, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NodoGiorno(n: NodoGrafo, oggi: Boolean, scelto: Boolean, onTocca: () -> Unit) {
    val (fondo, bordo, testo) = when (n.stato) {
        StatoNodo.VERDE -> Triple(VerdeNodo, Color(0xFF00806A), Color.White)
        StatoNodo.ROSSO -> Triple(RossoNodo, Color(0xFFC0392B), Color.White)
        StatoNodo.BLU -> Triple(BluNodo, Color(0xFF006AA5), Color.White)
        StatoNodo.ATTESA -> Triple(Color.White, ViolaNodo, ViolaNodo)
        StatoNodo.FUTURO -> Triple(Color.White, Color(0xFFC4C9D2), Color(0xFF9CA3AF))
        StatoNodo.VUOTO -> Triple(Color(0xFFF3F4F6), Color(0xFF9CA3AF), Color(0xFF374151))
    }
    val giorniSett = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")
    val d = PesoRegole.giornoDa(n.giorno)
    val etichetta = if (oggi) "Oggi" else (d?.let { giorniSett[it.dayOfWeek.value - 1] + " " } ?: "") + dataItaliana(n.giorno).take(5)
    // Le misure crescono col testo di sistema (con un tetto), o il numero esce dal cerchio.
    val scala = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val diametro = (if (oggi) 72.dp else 58.dp) * scala
    Column(
        Modifier
            .clickable(onClick = onTocca)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            etichetta,
            color = if (oggi) Palette.primary else Palette.muted,
            fontWeight = if (oggi) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Box(contentAlignment = Alignment.Center) {
            // Il filo che unisce i nodi, dietro al cerchio.
            Box(Modifier.width(diametro + 18.dp).height(3.dp).background(Color(0xFFD1D5DB)))
            Column(
                Modifier
                    .padding(horizontal = 9.dp)
                    .size(diametro)
                    .then(if (scelto) Modifier.border(3.dp, Palette.dark, androidx.compose.foundation.shape.CircleShape).padding(4.dp) else Modifier)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(fondo)
                    .border(3.dp, bordo, androidx.compose.foundation.shape.CircleShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                fun num(v: Double?) = v?.let { String.format(java.util.Locale.ITALY, "%.1f", it) } ?: "—"
                when {
                    n.nascosto -> Text("❓", color = testo, fontWeight = FontWeight.Bold)
                    n.stato == StatoNodo.FUTURO -> {
                        if (n.target != null) {
                            Text("🎯", fontSize = 10.sp)
                            Text(num(n.target), color = testo, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        } else Text("·", color = testo)
                    }
                    else -> {
                        Text(num(n.valore), color = testo, fontWeight = FontWeight.Bold, style = if (oggi) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium)
                        if (n.target != null) Text("🎯 " + num(n.target), color = testo, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Il ❓ della pesata che conta, spento col suo perché finché non si può dire l'esito
 * (gemello di `bottoneRivela()` del web v4.15.2). v1.0.148: sta nel dettaglio di oggi.
 */
@Composable
private fun BottoneRivela(s: SchedaPesata, onRivelato: () -> Unit) {
    val contesto = LocalContext.current
    var mostraEsito by remember { mutableStateOf(false) }
    val mancaR = s.rivelaManca
    val esitoOk = mancaR != null && mancaR <= 0
    val attivo = mancaR != null
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    if (attivo) listOf(Palette.secondary, Palette.primary)
                    else listOf(Color(0xFF9CA3AF), Color(0xFF6B7280))
                )
            )
            .then(
                if (attivo) Modifier.clickable {
                    prefsEsiti(contesto).edit().putString("esito_${s.obiettivoId}_${s.giorno}", if (esitoOk) "ok" else "ko").apply()
                    mostraEsito = true
                    if (!esitoOk) musichettaTriste()
                } else Modifier
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("❓", fontSize = 34.sp, color = Color.White)
        Text(
            if (attivo) "Com'è andata? Tocca per scoprirlo" else (s.rivelaMotivo ?: "Pesati prima, poi tocca qui"),
            color = Color.White, textAlign = TextAlign.Center,
        )
    }
    if (mostraEsito && mancaR != null) {
        DialogoEsito(esitoOk, mancaR) { mostraEsito = false; onRivelato() }
    }
}

@Composable
private fun Gettone(etichetta: String, valore: String) {
    Column(
        Modifier
            .fillMaxWidth(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFE8FFF8), Color(0xFFC6F3E5))))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(etichetta.uppercase(), color = Color(0xFF00806A), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        Text(valore, color = Color(0xFF00967A), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DialogoEsito(ok: Boolean, manca: Double, onChiudi: () -> Unit) {
    val kgTxt = "${kg(kotlin.math.abs(manca))} kg"
    val frase = remember { FRASI_KO.random() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onChiudi,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x73141428))
                .clickable(onClick = onChiudi),
            contentAlignment = Alignment.Center,
        ) {
            if (ok) FuochiArtificio(Modifier.fillMaxSize())
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (ok) "🎉" else "😅", fontSize = 56.sp)
                Text(
                    if (ok) "Target rispettato!" else "Questa volta no…",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                )
                Text(
                    if (ok) (if (manca < 0) "$kgTxt sotto il target. Grande!" else "Esattamente sul target. Grande!")
                    else "$kgTxt sopra il target. $frase",
                    color = Palette.muted, textAlign = TextAlign.Center,
                )
                TextButton(onClick = onChiudi) { Text("OK", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private class Scintilla(var x: Float, var y: Float, var vx: Float, var vy: Float, var vita: Float, val colore: Color)

/** Scoppi colorati per ~4,5 s — `fuochiArtificio()` del web, su un Canvas. */
@Composable
private fun FuochiArtificio(modifier: Modifier) {
    val scintille = remember { mutableListOf<Scintilla>() }
    var tick by remember { mutableStateOf(0L) }
    var larghezza by remember { mutableStateOf(0f) }
    var altezza by remember { mutableStateOf(0f) }
    val densita = LocalDensity.current.density
    LaunchedEffect(Unit) {
        val colori = listOf(Color(0xFFFF3366), Color(0xFFFCB131), Color(0xFF00B894), Color(0xFF0081C8), Color(0xFF6C5CE7), Color(0xFFFFD700))
        val inizio = androidx.compose.runtime.withFrameNanos { it }
        var prossimo = 0L
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            val ms = (t - inizio) / 1_000_000
            if (ms < 3300 && ms >= prossimo && larghezza > 0f) {
                val x = larghezza * (0.15f + Math.random().toFloat() * 0.7f)
                val y = altezza * (0.15f + Math.random().toFloat() * 0.35f)
                val col = colori.random()
                val n = 60 + (Math.random() * 30).toInt()
                repeat(n) { i ->
                    val a = (Math.PI * 2 * i / n).toFloat()
                    val v = (2f + Math.random().toFloat() * 3.5f) * densita * 0.6f
                    scintille += Scintilla(x, y, kotlin.math.cos(a) * v, kotlin.math.sin(a) * v, 1f,
                        if (Math.random() < 0.2) Color.White else col)
                }
                prossimo = ms + 280 + (Math.random() * 320).toLong()
            }
            scintille.removeAll { it.vita <= 0f }
            scintille.forEach {
                it.x += it.vx; it.y += it.vy; it.vy += 0.05f * densita * 0.6f
                it.vx *= 0.985f; it.vy *= 0.985f; it.vita -= 0.012f
            }
            tick = t
            if (ms > 4500 && scintille.isEmpty()) break
        }
    }
    androidx.compose.foundation.Canvas(
        modifier.onSizeChanged { larghezza = it.width.toFloat(); altezza = it.height.toFloat() }
    ) {
        tick
        val r = 2.6.dp.toPx()
        scintille.forEach {
            drawCircle(it.colore.copy(alpha = it.vita.coerceIn(0f, 1f)), radius = r, center = androidx.compose.ui.geometry.Offset(it.x, it.y))
        }
    }
}

/** «Wah wah wah waaah»: quattro note che scendono, l'ultima che si affloscia. */
private fun musichettaTriste() {
    Thread {
        runCatching {
            val sr = 22050
            val note = listOf(392.0 to 0.35, 370.0 to 0.35, 349.0 to 0.35, 330.0 to 1.1)
            val passo = 0.42
            val totale = ((passo * 3 + 1.1 + 0.1) * sr).toInt()
            val buf = FloatArray(totale)
            note.forEachIndexed { i, (f, d) ->
                val da = (i * passo * sr).toInt()
                val n = (d * sr).toInt()
                var fase = 0.0
                for (k in 0 until n) {
                    val t = k.toDouble() / sr
                    val freq = if (i == 3) f * (1 - 0.1 * t / d) + 6 * kotlin.math.sin(2 * Math.PI * 6 * t) else f
                    fase += 2 * Math.PI * freq / sr
                    val tri = (2 / Math.PI) * kotlin.math.asin(kotlin.math.sin(fase))
                    val inviluppo = minOf(1.0, t / 0.04) * kotlin.math.exp(-3 * t / d)
                    if (da + k < totale) buf[da + k] += (tri * inviluppo * 0.3).toFloat()
                }
            }
            val pcm = ShortArray(totale) { (buf[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((totale * 1000L / sr) + 200)
            track.release()
        }
    }.start()
}

@Composable
private fun SchedaVuota(titolo: String, righe: List<Pair<String, String>>, nota: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(3.dp, Palette.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(titolo, fontWeight = FontWeight.Bold, color = Palette.dark)
        righe.forEach { (e, v) -> RigaScheda(e, v) }
        Text(nota, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RigaScheda(etichetta: String, valore: String, colore: Color = Palette.dark) {
    // FlowRow e non Row: coi caratteri grandi il numero va a capo sotto
    // l'etichetta invece di schiacciarla o uscire dal bordo.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(etichetta, color = Palette.dark)
        Text(valore, color = colore, fontWeight = FontWeight.Bold)
    }
}

/** Calorie senza decimali, col punto delle migliaia. */
private fun kcal(valore: Double?): String =
    valore?.let { String.format(java.util.Locale.ITALY, "%,.0f", it) } ?: "–"

@Composable
private fun Riquadro(etichetta: String, valore: String, colore: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colore.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = valore,
            color = colore,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            maxLines = 1,
        )
        Text(
            text = etichetta,
            color = colore.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Le differenze si leggono col segno: «+1,2» è sopra, «−0,4» è sotto. */
private fun conSegno(valore: Double?): String {
    if (valore == null) return "–"
    val testo = kg(kotlin.math.abs(valore))
    return when {
        PesoRegole.arrotonda(valore, 1) > 0 -> "+$testo"
        PesoRegole.arrotonda(valore, 1) < 0 -> "−$testo"
        else -> testo
    }
}

// ── Tabella ──────────────────────────────────────────────────────────────

@Composable
private fun VistaTabella(righe: List<PesoRegole.RigaGiorno>, onApri: (String) -> Unit) {
    if (righe.isEmpty()) {
        Text(
            "Nessuna pesata in questo periodo.",
            color = Palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        )
        return
    }

    // Le sole giornate dell'obiettivo, dalla data d'inizio in cima
    // (`PesoRegole.storico`): si apre sull'inizio, che è la prima riga.
    val lista = rememberLazyListState()
    LazyColumn(
        state = lista,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(righe, key = { it.giorno }) { riga ->
            RigaTabella(riga, onApri)
        }
        item { Box(Modifier.heightIn(min = 60.dp)) }
    }
}

/**
 * Una giornata — gemella della riga di `tableRenderPage()` del web v4.13.
 *
 * Non tre colonne di tabella come nel web, che coi caratteri di sistema grandi
 * si taglierebbero: una scheda con in cima la data e il target, poi la stessa
 * griglia 2 × 3 (peso · grasso kg · %) — sopra l'**ultima pesata** del giorno,
 * sotto le tre **medie mobili** (viola, in corsivo se stimate) — e in fondo
 * punti e cumulato. Fondo come il web: bianco i giorni futuri, rosa i giorni
 * passati senza pesata, azzurro chiaro i giorni di premio, grigio gli altri.
 */
@Composable
private fun RigaTabella(riga: PesoRegole.RigaGiorno, onApri: (String) -> Unit) {
    val stato = riga.stato
    val fondo = when {
        riga.futuro -> Color.White
        riga.senzaPesata -> Color(0xFFFCE4EC)
        riga.giornoPremio -> Color(0xFFE3F2FD)
        else -> Color(0xFFF2F2F2)
    }
    val media = riga.media
    val stimW = media?.pesoStimato == true
    val stimF = false
    val haMediaStimata = media?.emaPeso != null && stimW
    // Il valore che fa i punti si colora contro il target: la media mobile del peso.
    fun colore(v: Double?): Color? {
        val t = riga.target ?: return null
        if (v == null) return null
        return if (PesoRegole.arrotonda(v, 1) <= PesoRegole.arrotonda(t, 1)) Verde else Palette.danger
    }
    val icona = when {
        stato == PesoRegole.StatoRiga.DA_FARE -> "📅 "
        stato == PesoRegole.StatoRiga.NON_PESATO && !haMediaStimata -> "✗ "
        riga.primaPeso == null && (riga.senzaPesata || riga.interpolata) -> "○ "
        else -> ""
    }
    val conPesata = riga.primaPeso != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(fondo)
            .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
            .clickable(enabled = conPesata) { onApri(riga.giorno) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icona + dataItaliana(riga.giorno),
                fontWeight = FontWeight.Bold,
                color = if (icona == "✗ ") Palette.danger else Palette.dark,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "target ${riga.target?.let { kg(it) + " kg" } ?: "—"}",
                    fontWeight = FontWeight.Bold,
                    color = Palette.muted,
                )
                riga.notaTarget?.let {
                    Text(it, color = Color(0xFF8A6D00), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // La griglia: intestazioni, poi giorno (sopra) e media (sotto).
        fun num(v: Double?) = v?.let { kg(it) } ?: "—"
        val viola = Color(0xFF6C5CE7)
        // v1.0.141: niente più grasso; è la MEDIA a fare i punti, quindi è lei a prendere il colore.
        val righeGriglia = listOf(
            Triple("Peso", num(riga.primaPeso) to num(media?.emaPeso), Triple(null as Color?, colore(media?.emaPeso), stimW)),
        )
        Row(Modifier.fillMaxWidth()) {
            righeGriglia.forEach { (titolo, valori, stile) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        titolo,
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CellaTabella(valori.first, stile.first ?: Palette.dark, grassetto = true)
                    CellaTabella(valori.second, stile.second ?: viola, grassetto = stile.second != null, corsivo = stile.third)
                }
            }
        }
        Text(
            text = "sopra il giorno (ultima pesata) · sotto la media, che fa i punti",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            stato == PesoRegole.StatoRiga.NON_PESATO && riga.primaPeso == null && !haMediaStimata ->
                Text("non pesato", color = Palette.danger, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            stato == PesoRegole.StatoRiga.DA_FARE ->
                Text("pesata da fare", color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            (stimW || stimF) && riga.primaPeso == null ->
                Text("media stimata col trend", color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (riga.fuoriCalendario) {
            Text("fuori calendario, niente punti", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }

        if (riga.punti != null || riga.cumulativo != null) {
            Text(
                text = buildString {
                    riga.punti?.let { append(if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0") }
                    riga.cumulativo?.let {
                        if (isNotEmpty()) append(" · ")
                        append("totale ${if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0"}")
                    }
                },
                color = when {
                    (riga.punti ?: 0) > 0 -> Verde
                    (riga.punti ?: 0) < 0 -> Palette.danger
                    else -> Palette.muted
                },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Una cella della griglia della tabella, centrata. */
@Composable
private fun CellaTabella(testo: String, colore: Color, grassetto: Boolean, corsivo: Boolean = false) {
    Text(
        text = testo,
        color = colore,
        fontWeight = if (grassetto) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (corsivo) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Dialoghi ─────────────────────────────────────────────────────────────

/**
 * La pesata: quanto e a che ora.
 *
 * L'ora non è un vezzo — è la **chiave** della riga (`timestamp`), quindi due
 * pesate dello stesso giorno si distinguono per quella. Ripesarsi alla stessa
 * ora riscrive la pesata di prima invece di aggiungerne una: è l'upsert su
 * `timestamp`, uguale al web.
 */
@Composable
private fun DialogoPesata(
    giorno: LocalDate,
    target: Double?,
    onAnnulla: () -> Unit,
    onConferma: (LocalTime, Double) -> Unit,
) {
    val context = LocalContext.current
    var testo by remember { mutableStateOf("") }
    var ora by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    val peso = testo.replace(',', '.').toDoubleOrNull()
    val valido = peso != null && peso >= 30.0 && peso <= 300.0

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("⚖️ ${dataItaliana(giorno.toString())}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = testo,
                    onValueChange = { nuovo ->
                        testo = nuovo.filter { it.isDigit() || it == ',' || it == '.' }.take(6)
                    },
                    label = { Text("Peso (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🕒 %02d:%02d".format(ora.hour, ora.minute),
                        color = Palette.light,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Verde)
                            .clickable {
                                TimePickerDialog(
                                    context,
                                    { _, h, m -> ora = LocalTime.of(h, m) },
                                    ora.hour,
                                    ora.minute,
                                    true,
                                ).show()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    Text(
                        text = target?.let { "target ${kg(it)} kg" } ?: "nessun target",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (testo.isNotBlank() && !valido) {
                    Text(
                        "Un peso sta fra 30 e 300 kg.",
                        color = Palette.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valido,
                onClick = { peso?.let { onConferma(ora, it) } },
            ) {
                Text("Segna")
            }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

/** Le pesate di una giornata, con il cestino su quelle scritte a mano. */
@Composable
private fun DialogoGiorno(
    giorno: String,
    pesate: List<Pesata>,
    onChiudi: () -> Unit,
    onElimina: (Pesata) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(dataItaliana(giorno)) },
        text = {
            if (pesate.isEmpty()) {
                Text("Nessuna pesata in questo giorno.", color = Palette.muted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pesate.forEach { pesata ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${kg(pesata.peso)} kg", fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = pesata.ora ?: "orario ignoto",
                                    color = Palette.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            // Si cancella solo quello che si è scritto a mano:
                            // una pesata arrivata dalla bilancia tornerebbe al
                            // primo sync, e sembrerebbe che il cestino non funzioni.
                            if (pesata.manuale) {
                                Text(
                                    "🗑",
                                    modifier = Modifier
                                        .clickable { onElimina(pesata); onChiudi() }
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text("Chiudi") } },
    )
}

/** Il nome di un canale del promemoria, come nel form del web. */
internal fun nomeCanale(canale: String): String = when (canale) {
    "telegram" -> "📱 Telegram"
    "android" -> "📲 Telefono"
    "smart_block" -> "🔐 Smart Block"
    else -> canale
}
