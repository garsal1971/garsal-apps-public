package com.garsal.appsphere.spuntiamola

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import java.time.LocalDate

private val MESI = listOf(
    "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
    "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
)

@Composable
fun SpuntiamolaScreen(
    onIndietro: () -> Unit,
    vm: SpuntiamolaViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var impostazioniAperte by remember { mutableStateOf(false) }
    var chiusuraAperta by remember { mutableStateOf(false) }
    var confermaTraguardo by remember { mutableStateOf(false) }
    var steccaDaCancellare by remember { mutableStateOf<SpStecca?>(null) }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Spuntiamola",
                onIndietro = onIndietro,
                azioni = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Impostazioni",
                        tint = Palette.light,
                        modifier = Modifier.clickable { impostazioniAperte = true },
                    )
                },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            if (stato.caricamento && stato.impostazioni == null) {
                CircularProgressIndicator(
                    color = Palette.secondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Hero(
                            stato = stato,
                            onImposta = { impostazioniAperte = true },
                            onSpuntaOggi = { vm.togglaGiorno(LocalDate.now()) },
                            onChiudiStecca = { chiusuraAperta = true },
                        )
                    }

                    if (stato.giorni.isEmpty()) {
                        item { Vuoto() }
                    } else {
                        // Un blocco per mese, come sul web: i giorni di un
                        // periodo lungo altrimenti diventano un muro unico.
                        items(stato.giorni.groupBy { it.year to it.monthValue }.toList()) { (chiave, giorni) ->
                            BloccoMese(
                                anno = chiave.first,
                                mese = chiave.second,
                                giorni = giorni,
                                spunte = stato.spunte,
                                giornateChiave = stato.giornateChiave,
                                onSpunta = { vm.togglaGiorno(it) },
                            )
                        }
                    }

                    if (stato.stecche.isNotEmpty()) {
                        item { ArchivioStecche(stato.stecche) { steccaDaCancellare = it } }
                    }
                }
            }

            stato.errore?.let { messaggio ->
                Text(
                    text = messaggio,
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
            }

            // Coriandoli e fuochi vanno sopra a tutto, e non devono intercettare i tocchi.
            FestaOverlay(festa = stato.festa, onFinita = { vm.festaMostrata(it) })

            ToastBox(
                toast = stato.toast,
                onFinito = { vm.toastMostrato(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (impostazioniAperte) {
        ImpostazioniDialog(
            stato = stato,
            onAnnulla = { impostazioniAperte = false },
            onSalva = { traguardo, emoji, mood, inizio, fine, weekend, chiave ->
                vm.salvaImpostazioni(traguardo, emoji, mood, inizio, fine, weekend)
                vm.salvaGiornateChiave(chiave)
                impostazioniAperte = false
            },
            onCancellaTraguardo = { confermaTraguardo = true },
        )
    }

    if (confermaTraguardo) {
        val impostazioni = stato.impostazioni
        val fatti = stato.spuntati
        val chiavi = stato.giornateChiave.size
        AlertDialog(
            onDismissRequest = { confermaTraguardo = false },
            title = { Text("Cancellare il traguardo?") },
            text = {
                Text(
                    "«${impostazioni?.goal ?: ""}»\n\nSpariscono il periodo, $fatti " +
                        (if (fatti == 1) "spunta" else "spunte") + " e $chiavi " +
                        (if (chiavi == 1) "giornata chiave" else "giornate chiave") + ".\n" +
                        "Non finisce fra le stecche chiuse: è come se non ci fosse mai stato."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confermaTraguardo = false
                    impostazioniAperte = false
                    vm.cancellaTraguardo()
                }) { Text("Cancella", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confermaTraguardo = false }) { Text("Annulla") } },
        )
    }

    steccaDaCancellare?.let { stecca ->
        AlertDialog(
            onDismissRequest = { steccaDaCancellare = null },
            title = { Text("Cancellare la stecca?") },
            text = {
                Text("«${stecca.goal.ifBlank { "senza nome" }}»\n\nSpunte, soddisfazione e nota spariscono per sempre.")
            },
            confirmButton = {
                TextButton(onClick = { steccaDaCancellare = null; vm.cancellaStecca(stecca.id) }) {
                    Text("Cancella", color = Palette.danger)
                }
            },
            dismissButton = { TextButton(onClick = { steccaDaCancellare = null }) { Text("Annulla") } },
        )
    }

    if (chiusuraAperta) {
        ChiusuraDialog(
            stato = stato,
            onRimanda = { chiusuraAperta = false },
            onChiudi = { soddisfazione, nota, emojiFinale, quando ->
                vm.chiudiStecca(soddisfazione, nota, emojiFinale, quando)
                chiusuraAperta = false
            },
        )
    }
}

@Composable
private fun Hero(
    stato: SpuntiamolaState,
    onImposta: () -> Unit,
    onSpuntaOggi: () -> Unit,
    onChiudiStecca: () -> Unit,
) {
    val impostazioni = stato.impostazioni
    val oggi = LocalDate.now()
    val oggiNelPeriodo = stato.giorni.contains(oggi)
    val oggiSpuntato = stato.spunte.containsKey(oggi.toString())
    val chiaveOggi = stato.giornateChiave.containsKey(oggi.toString())

    // Column esplicita: dentro un `item` di LazyColumn due figli affiancati non
    // hanno un layout che li impili.
    Column {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.secondary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ⚠️ Il badge compare solo quando una stecca c'è: senza periodo
            // direbbe con che voce parlerebbe l'app di una cosa che non esiste.
            if (impostazioni != null) {
                Text(
                    text = stato.mood.badge,
                    color = Palette.light,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Palette.light.copy(alpha = 0.22f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            Text(
                text = if (impostazioni != null) "${impostazioni.emoji} ${impostazioni.goal}"
                       else "🎯 Nessun traguardo",
                color = Palette.light,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )

            Text(
                text = if (stato.totali > 0) stato.mancano.toString() else "—",
                color = Palette.light,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
            )
            Text(
                // «mancano» e «restano» sono lo stesso numero letto nei due versi
                text = if (stato.totali == 0) "imposta il periodo per iniziare"
                       else stato.mood.etichettaConto(stato.mancano),
                color = Palette.light.copy(alpha = 0.85f),
            )
            if (impostazioni != null && stato.totali > 0) {
                Text(
                    text = "dal ${LocalDate.parse(impostazioni.startDate).italiana()}" +
                        " al ${LocalDate.parse(impostazioni.endDate).italiana()}" +
                        if (impostazioni.skipWeekend) " · solo feriali" else "",
                    color = Palette.light.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            BarraProgresso(stato.percentuale)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${stato.spuntati} di ${stato.totali} spuntati",
                    color = Palette.light.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${stato.percentuale}%",
                    color = Palette.light,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Statistica("fatti", stato.spuntati)
                Statistica(stato.mood.etichettaRestano, stato.mancano)
                Statistica("serie", stato.serie)
                Statistica("totale", stato.totali)
            }

            // Il pulsante dice sempre l'unica cosa che ha senso fare adesso.
            Box(Modifier.padding(top = 16.dp)) {
                when {
                    stato.totali == 0 ->
                        Button(onClick = onImposta) { Text("Imposta il periodo ⚙️") }
                    stato.daChiudere ->
                        Button(onClick = onChiudiStecca) { Text("🏁 Chiudi la stecca") }
                    !oggiNelPeriodo ->
                        Button(onClick = {}, enabled = false) {
                            Text(
                                if (impostazioni != null && oggi > LocalDate.parse(impostazioni.endDate))
                                    "Periodo concluso 🏁" else "Oggi non è nel periodo"
                            )
                        }
                    oggiSpuntato ->
                        Button(onClick = {}, enabled = false) {
                            Text("Oggi è già spuntato ${stato.spunte[oggi.toString()]}")
                        }
                    else ->
                        Button(onClick = onSpuntaOggi) { Text("Spunta oggi!") }
                }
            }
        }
    }

    // L'avviso "oggi non spuntato", lo stesso che compare in home su AppSphere.
    if (oggiNelPeriodo && !oggiSpuntato) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E0)),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                text = if (chiaveOggi) {
                    val etichetta = stato.giornateChiave[oggi.toString()].orEmpty()
                    "⭐ Oggi è una GIORNATA CHIAVE" +
                        (if (etichetta.isNotBlank()) " — $etichetta" else "") +
                        " e non l'hai spuntata!"
                } else {
                    stato.mood.banner(oggi.italiana())
                },
                color = Palette.dark,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
    }
}

@Composable
private fun BarraProgresso(percentuale: Int) {
    val riempimento by animateFloatAsState(
        targetValue = percentuale / 100f,
        label = "progresso",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Palette.light.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(riempimento.coerceIn(0f, 1f))
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Palette.success)
        )
    }
}

@Composable
private fun Statistica(etichetta: String, valore: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$valore", color = Palette.light, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            etichetta,
            color = Palette.light.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BloccoMese(
    anno: Int,
    mese: Int,
    giorni: List<LocalDate>,
    spunte: Map<String, String>,
    giornateChiave: Map<String, String>,
    onSpunta: (LocalDate) -> Unit,
) {
    val fatti = giorni.count { spunte.containsKey(it.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${MESI[mese - 1]} $anno",
                    fontWeight = FontWeight.Bold,
                    color = Palette.dark,
                )
                Text("$fatti/${giorni.size}", color = Palette.muted)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                giorni.forEach { giorno ->
                    Cella(
                        giorno = giorno,
                        emoji = spunte[giorno.toString()],
                        chiave = giornateChiave.containsKey(giorno.toString()),
                        onClick = { onSpunta(giorno) },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Gli stessi quattro stati del web: *fatto* (verde), *oggi* (bordo viola),
 * *saltato* (rosso, giorno passato non spuntato), *da fare* (grigio).
 * Le giornate chiave sono dorate con la stella, e vincono sugli altri colori.
 */
@Composable
private fun Cella(
    giorno: LocalDate,
    emoji: String?,
    chiave: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val oggi = LocalDate.now()
    val fatto = emoji != null
    val saltato = !fatto && giorno < oggi

    val sfondo = when {
        chiave && fatto -> Palette.oro
        chiave -> Palette.oro.copy(alpha = 0.22f)
        fatto -> Palette.success
        saltato -> Palette.danger.copy(alpha = 0.18f)
        else -> Palette.inputBg
    }
    val testo = if (fatto) Palette.light else Palette.dark

    Box(
        modifier = modifier
            .then(if (giorno == oggi) Modifier.border(2.dp, Palette.secondary, RoundedCornerShape(10.dp)) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(sfondo)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = emoji ?: if (chiave) "★" else " ",
                fontSize = 14.sp,
                color = if (chiave && !fatto) Palette.oro else testo,
            )
            Text(
                text = "${giorno.dayOfMonth}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = testo,
            )
        }
    }
}

@Composable
private fun Vuoto() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🤷", fontSize = 34.sp)
            Text(
                "Nessun giorno da spuntare.\nApri le impostazioni ⚙️ e scegli il periodo.",
                color = Palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ArchivioStecche(stecche: List<SpStecca>, onCancella: (SpStecca) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "🏅 Le stecche chiuse  ·  ${stecche.size}",
                fontWeight = FontWeight.Bold,
                color = Palette.dark,
            )
            stecche.forEach { stecca ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.inputBg)
                        .padding(12.dp)
                ) {
                    // 🗑️ in testa alla riga, come le altre icone di modifica.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🗑️",
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onCancella(stecca) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        Text(
                            "${stecca.finalEmoji} ${stecca.goal}",
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.dark,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "${stecca.doneDays} di ${stecca.totalDays} giorni · soddisfazione ${stecca.satisfaction}/100",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted,
                    )
                    Text(
                        etichettaSoddisfazione(stecca.satisfaction),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted,
                    )
                    // Con che voce è stata vissuta: senza, riaprendo l'archivio
                    // non si saprebbe più se erano giorni aspettati o vissuti.
                    Text(
                        moodDi(stecca.mood).badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Palette.muted,
                    )
                    if (stecca.note.isNotBlank()) {
                        Text(
                            "«${stecca.note}»",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.dark,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastBox(toast: Toast?, onFinito: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (toast == null) return

    androidx.compose.runtime.LaunchedEffect(toast.id) {
        kotlinx.coroutines.delay(toast.durataMs)
        onFinito(toast.id)
    }

    val sfondo = when (toast.tono) {
        TonoToast.CHIAVE -> Palette.oro
        TonoToast.GRANDE -> Palette.secondary
        TonoToast.DOLCE -> Palette.muted
        TonoToast.NORMALE -> Palette.dark
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = sfondo),
        modifier = modifier.fillMaxWidth().padding(16.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(toast.emoji, fontSize = if (toast.tono == TonoToast.NORMALE) 22.sp else 30.sp)
            Text(
                text = toast.testo,
                color = Palette.light,
                fontWeight = if (toast.tono == TonoToast.NORMALE) FontWeight.Normal else FontWeight.SemiBold,
            )
        }
    }
}
