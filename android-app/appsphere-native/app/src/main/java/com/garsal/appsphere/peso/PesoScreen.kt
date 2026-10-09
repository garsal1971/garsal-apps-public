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
            onConferma = { ora, peso, grasso ->
                vm.pesati(giorno, ora, peso, grasso)
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
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // La domanda che dà il nome all'app, in cima e in chiaro: la
            // risposta è un sì o un no, non un numero da cercare fra i badge.
            val fatto = stato.pesatoOggi
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (fatto) Verde.copy(alpha = 0.12f) else Palette.warning.copy(alpha = 0.14f))
                    .clickable(enabled = !fatto, onClick = onPesati)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (fatto) "✅ Oggi ti sei pesato" else "⚖️ Oggi non ti sei ancora pesato",
                    fontWeight = FontWeight.Bold,
                    color = Palette.dark,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (fatto) "${kg(stato.minimoOggi)} kg" else "Tocca qui per segnarlo",
                    color = if (fatto) Verde else Palette.muted,
                    fontWeight = if (fatto) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        // Le due schede delle pesate, una sotto l'altra: al posto dei riquadri
        // «Minimo oggi / Target oggi / Mancano al target», come nel web v4.2.0.
        // Una sotto l'altra e non affiancate: coi caratteri di sistema grandi
        // due schede da sette righe in mezza larghezza vanno a capo ovunque.
        if (stato.schede.isEmpty()) {
            item {
                SchedaVuota(
                    titolo = "⚖️ Oggi",
                    righe = listOf("Minimo" to "${kg(stato.minimoOggi)} kg"),
                    nota = "Nessun obiettivo in corso: niente target.",
                )
            }
        } else {
            items(stato.schede, key = { "${it.tipo}-${it.giorno}" }) { SchedaPesataCard(it) }
            if (stato.schede.none { it.tipo == TipoScheda.DOPO }) {
                item {
                    SchedaVuota(
                        titolo = "➡️ Prossima",
                        righe = emptyList(),
                        nota = "Nessun'altra pesata: il piano finisce il ${stato.finePiano?.let { dataItaliana(it) } ?: "–"}.",
                    )
                }
            }
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
@Composable
private fun SchedaPesataCard(s: SchedaPesata) {
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
    if (s.tipo == TipoScheda.DOPO) {
        val gg = PesoRegole.giornoDa(s.giorno)?.toEpochDay()?.minus(LocalDate.now().toEpochDay())
        if (gg != null) quando += if (gg == 1L) " · domani" else " · fra $gg gg"
    }
    val manca = s.manca
    val coloreManca = when {
        manca == null -> Palette.muted
        PesoRegole.arrotonda(manca, 1) <= 0 -> Verde
        else -> Palette.danger
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
        Text("$titolo · $quando", fontWeight = FontWeight.Bold, color = Palette.dark)
        RigaScheda("Minimo", "${kg(s.peso)} kg")
        // Sulla massa grassa il totale e la % della pesata, a titolo informativo.
        s.info?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        s.nota?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        RigaScheda("Target", "${kg(s.target)} kg", Verde)
        s.infoTarget?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
        RigaScheda("Mancano", if (manca == null) "–" else "${conSegno(manca)} kg", coloreManca)
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
 * griglia 2 × 3 (peso · grasso kg · %) — sopra la **prima pesata** del giorno,
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
    val stimF = media?.grassoStimato == true
    val haMediaStimata = (media?.emaPeso != null || media?.emaGrassoKg != null) && (stimW || stimF)
    val grassoObj = riga.notaTarget != null
    // Il valore che fa i punti si colora contro il target: la media del grasso
    // sugli obiettivi a massa grassa, il peso del giorno sugli altri.
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
        val righeGriglia = listOf(
            Triple("Peso", num(riga.primaPeso) to num(media?.emaPeso), Triple(if (grassoObj) null else colore(riga.primaPeso), null as Color?, stimW)),
            Triple("Grasso", num(riga.primaGrassoKg) to num(media?.emaGrassoKg), Triple(null as Color?, if (grassoObj) colore(media?.emaGrassoKg) else null, stimF)),
            Triple("%", num(riga.primaGrassoPct) to num(media?.emaGrassoPct), Triple(null as Color?, null as Color?, stimF)),
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
            text = "sopra il giorno (prima pesata) · sotto la media",
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
    onConferma: (LocalTime, Double, Double?) -> Unit,
) {
    val context = LocalContext.current
    var testo by remember { mutableStateOf("") }
    // Il grasso corporeo, facoltativo — `insertWeightFat` del web: vuoto =
    // non misurato (NULL), non zero. Sta fra 3 e 70, come là.
    var testoGrasso by remember { mutableStateOf("") }
    val grasso = testoGrasso.replace(',', '.').toDoubleOrNull()
    val grassoValido = testoGrasso.isBlank() || (grasso != null && grasso >= 3.0 && grasso <= 70.0)
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
                OutlinedTextField(
                    value = testoGrasso,
                    onValueChange = { nuovo ->
                        testoGrasso = nuovo.filter { it.isDigit() || it == ',' || it == '.' }.take(5)
                    },
                    label = { Text("🧈 % grasso (facoltativa)") },
                    singleLine = true,
                    isError = !grassoValido,
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
                if (!grassoValido) {
                    Text(
                        "La % di grasso sta fra 3 e 70, oppure lasciala vuota.",
                        color = Palette.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valido && grassoValido,
                onClick = { peso?.let { onConferma(ora, it, if (testoGrasso.isBlank()) null else grasso) } },
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
