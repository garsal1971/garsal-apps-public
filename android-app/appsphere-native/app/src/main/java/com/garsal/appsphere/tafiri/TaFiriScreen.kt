package com.garsal.appsphere.tafiri

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.larghezzaPulsanti

/** Il viola di Ta Firi? (`--challenge` nel CSS della pagina). */
internal val Viola = Color(0xFF8E44AD)

/** Le due voci della sidebar del web, che qui sono due schede. */
private enum class Vista(val etichetta: String) {
    DASHBOARD("Dashboard"),
    STORICO("Storico"),
}

/**
 * Ta Firi? in nativo: sfide a tempo con check-in giornaliero.
 *
 * ⚠️ Gemella di `ta-firi.html`, sulle stesse tabelle `sf_*` e sulle stesse
 * RPC. Le regole che **sono** la funzionalità stanno tutte nel ViewModel e nel
 * repository — il punteggio finale nella RPC, il check-in di oggi diverso dalla
 * correzione di un giorno passato, la regola Smart Block — e vanno cambiate
 * nelle due implementazioni insieme.
 */
@Composable
fun TaFiriScreen(
    onIndietro: () -> Unit,
    vm: TaFiriViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()

    // Come in Tasks: il form sta qui e non nel ViewModel, perché è roba della
    // schermata. La coppia distingue la sfida nuova (id nullo) da quella che si
    // sta modificando.
    var inCompilazione by remember { mutableStateOf<Pair<BozzaSfida, String?>?>(null) }
    var vista by remember { mutableStateOf(Vista.DASHBOARD) }
    var daEliminare by remember { mutableStateOf<SfSfida?>(null) }

    inCompilazione?.let { (bozza, id) ->
        TaFiriForm(
            bozzaIniziale = bozza,
            id = id,
            onAnnulla = { inCompilazione = null },
            onSalva = { compilata -> vm.salva(compilata, id) { inCompilazione = null } },
        )
        return
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Ta Firi?",
                onIndietro = onIndietro,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { inCompilazione = BozzaSfida() to null },
                containerColor = Viola,
                contentColor = Palette.light,
            ) { Text("+", fontSize = 26.sp) }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                stato.caricamento && stato.sfide.isEmpty() ->
                    CircularProgressIndicator(
                        color = Viola,
                        modifier = Modifier.align(Alignment.Center),
                    )

                else -> Column(Modifier.fillMaxSize()) {
                    SelettoreVista(vista) { vista = it }

                    val elenco = if (vista == Vista.DASHBOARD) stato.attive else stato.concluse

                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (vista == Vista.DASHBOARD) {
                            val daSpuntare = stato.daSpuntareOra()
                            if (daSpuntare.isNotEmpty()) {
                                item {
                                    BannerCheckin(
                                        sfide = daSpuntare,
                                        onFatto = { vm.spuntaOggi(it, "done") },
                                        onNonFatto = { vm.spuntaOggi(it, "not_done") },
                                    )
                                }
                            }
                        }

                        if (elenco.isEmpty()) {
                            item {
                                Text(
                                    text = if (vista == Vista.DASHBOARD)
                                        "Nessuna sfida attiva. Tocca + per crearne una nuova."
                                    else "Nessuna sfida conclusa ancora.",
                                    color = Palette.muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                                )
                            }
                        }

                        items(elenco, key = { it.id }) { sfida ->
                            SchedaSfida(
                                sfida = sfida,
                                stato = stato,
                                onModifica = { inCompilazione = BozzaSfida.da(sfida) to sfida.id },
                                onElimina = { daEliminare = sfida },
                                onTocca = { giorno -> vm.cambiaGiorno(sfida, giorno) },
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

    daEliminare?.let { sfida ->
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare la sfida?") },
            text = {
                Text(
                    "«${sfida.title}» sparisce per sempre, con tutti i suoi giorni " +
                        "e il punteggio che ha portato."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.elimina(sfida); daEliminare = null }) {
                    Text("Elimina", color = Palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { daEliminare = null }) { Text("Annulla") }
            },
        )
    }
}

/**
 * Le due schede. Come in Tasks non si dividono lo schermo in parti uguali:
 * ognuna è larga quanto il suo nome e la fila scorre, così coi caratteri di
 * sistema grandi nessuna etichetta va a capo a metà parola.
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
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (attiva) Viola else Palette.inputBg)
                    .clickable { onScegli(vista) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * «Hai fatto la sfida oggi?» — le sfide che a quest'ora aspettano una risposta.
 *
 * I due pulsanti stanno **sotto** il titolo e non accanto: nel web sono in
 * fila a destra, ma qui, coi caratteri di sistema grandi, «✅ Fatto» e
 * «❌ Non fatto» accanto a un titolo lungo non ci starebbero mai.
 */
@Composable
private fun BannerCheckin(
    sfide: List<SfSfida>,
    onFatto: (SfSfida) -> Unit,
    onNonFatto: (SfSfida) -> Unit,
) {
    val larghezza = larghezzaPulsanti(listOf("✅ Fatto", "❌ Non fatto"))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.warning.copy(alpha = 0.12f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "🔔 Check-in di oggi",
            fontWeight = FontWeight.Bold,
            color = Palette.dark,
        )
        sfide.forEach { sfida ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sfida.title, color = Palette.dark)
                RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                    Pulsante("✅ Fatto", Palette.success, larghezza) { onFatto(sfida) }
                    Pulsante("❌ Non fatto", Palette.danger, larghezza) { onNonFatto(sfida) }
                }
            }
        }
    }
}

@Composable
private fun Pulsante(testo: String, colore: Color, larghezza: Dp? = null, onClick: () -> Unit) {
    Text(
        text = testo,
        color = Palette.light,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .then(larghezza?.let { Modifier.width(it) } ?: Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(colore)
            .clickable(onClick = onClick)
            .padding(horizontal = if (larghezza == null) 14.dp else 0.dp, vertical = 10.dp),
    )
}

/**
 * La scheda di una sfida: titolo, obiettivo, punteggio in palio, periodo e la
 * griglia dei giorni.
 *
 * Tutto in colonna, una cosa per riga: nel web titolo, badge e icone stanno
 * sulla stessa riga, ma quella riga con l'ingrandimento dei caratteri diventa
 * una colonna sbilenca da sé, con le icone spinte a capo per prime — cioè
 * esattamente quelle che servono per toccare.
 */
@Composable
private fun SchedaSfida(
    sfida: SfSfida,
    stato: TaFiriState,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
    onTocca: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.cardBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = sfida.title,
            fontWeight = FontWeight.Bold,
            color = Palette.dark,
            style = MaterialTheme.typography.titleMedium,
        )

        sfida.objective?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
        }

        Etichetta(
            testo = "${etichettaPunteggio(sfida.scoringType)} · 🎯 ${sfida.totalPoints}",
            colore = Palette.secondary,
        )

        if (sfida.attiva) {
            Text(
                text = "📅 ${dataItaliana(sfida.startDate)} · ${sfida.durationDays} giorni · " +
                    "⏰ ${sfida.ora}",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val fatti = stato.giorniFatti(sfida.id)
            Etichetta(
                testo = if (sfida.status == "completed") "Completata" else "Fallita",
                colore = if (sfida.status == "completed") Palette.success else Palette.danger,
            )
            Text(
                text = "📅 ${dataItaliana(sfida.startDate)} → ${dataItaliana(sfida.endDate)}",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "✅ $fatti/${sfida.durationDays} giorni fatti · " +
                    "🏅 ${sfida.finalScore ?: 0}/${sfida.totalPoints} punti",
                color = Palette.dark,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        GrigliaGiorni(sfida, stato, onTocca)

        val larghezza = larghezzaPulsanti(listOf("✏️ Modifica", "🗑 Elimina"))
        RigaScorrevole(Arrangement.spacedBy(8.dp)) {
            // La modifica c'è solo sulle sfide attive, come nel web: su una
            // conclusa cambierebbe i punti in palio di una gara già giocata.
            if (sfida.attiva) Pulsante("✏️ Modifica", Viola, larghezza, onModifica)
            Pulsante("🗑 Elimina", Palette.danger, larghezza, onElimina)
        }
    }
}

@Composable
private fun Etichetta(testo: String, colore: Color) {
    Text(
        text = testo,
        color = Palette.light,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colore)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Un pallino per giorno, come `renderDayGrid`: ✓ fatto, ✕ non fatto, il numero
 * del giorno se ancora da segnare. I giorni futuri sono sbiaditi e non si
 * toccano — non si può spuntare domani.
 *
 * Il diametro segue l'ingrandimento dei caratteri (`fontScale`) invece di
 * stare fermo a 32 dp come nel CSS: il numero dentro è in `sp` e cresce da sé,
 * e in un cerchio fisso finirebbe tagliato al primo ingrandimento.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrigliaGiorni(sfida: SfSfida, stato: TaFiriState, onTocca: (String) -> Unit) {
    val scala = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val lato = 34.dp * scala
    val oggi = java.time.LocalDate.now().toString()

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        (0 until sfida.durationDays).forEach { indice ->
            val giorno = sfida.giorno(indice)
            val futuro = giorno > oggi
            val statoGiorno = stato.statoDi(sfida.id, giorno)

            val sfondo = when {
                futuro -> Palette.inputBg
                statoGiorno == "done" -> Palette.success
                statoGiorno == "not_done" -> Palette.danger
                else -> Palette.warning
            }
            val testo = when (statoGiorno) {
                "done" -> "✓"
                "not_done" -> "✕"
                else -> "${indice + 1}"
            }

            Box(
                modifier = Modifier
                    .size(lato)
                    .clip(CircleShape)
                    .background(sfondo)
                    .then(
                        if (futuro) Modifier
                        else Modifier.clickable { onTocca(giorno) }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = testo,
                    color = if (futuro) Palette.muted.copy(alpha = 0.5f) else Palette.light,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun etichettaPunteggio(tipo: String): String =
    if (tipo == "proportional") "Proporzionale" else "Tutto o niente"

/** `2026-08-14` → `14/08/2026`, come ovunque nelle app di casa. */
internal fun dataItaliana(iso: String): String {
    val pezzi = iso.take(10).split("-")
    if (pezzi.size != 3) return iso
    return "${pezzi[2]}/${pezzi[1]}/${pezzi[0]}"
}
