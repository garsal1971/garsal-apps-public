package com.garsal.appsphere.calorie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.Destinazione
import com.garsal.appsphere.core.GarsalTopBar
import androidx.compose.ui.graphics.vector.ImageVector
import com.garsal.appsphere.core.IconeLinea
import com.garsal.appsphere.core.InterruttorePesoCalorie
import com.garsal.appsphere.core.VoceConIcona
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Scorciatoia

/** Il verde di Calorie (`--c-primary` nel CSS della pagina). */
internal val VerdeCalorie = Color(0xFF16A34A)
internal val AmbraCalorie = Color(0xFFD97706)
internal val RossoCalorie = Color(0xFFDC2626)

private enum class Vista(val etichetta: String, val icona: ImageVector) {
    DASHBOARD("Dashboard", IconeLinea.dashboard),
    DIARIO("Diario", IconeLinea.diario),
}

/**
 * Calorie — il diario alimentare, in nativo.
 *
 * ⚠️ Gemella di `calorie.html`, sulle stesse tabelle `al_*` e sullo stesso
 * obiettivo di «Ti pisasti?». Qui ci sono le due pagine che si aprono col
 * telefono in mano — 📊 **Dashboard**, che dice come sta andando, e 📓
 * **Diario**, dove si segna quel che si è mangiato — più il **+ galleggiante**,
 * che è la ragione per cui l'app sta sul telefono: si segna l'alimento nel
 * momento in cui lo si mangia, non la sera al computer.
 *
 * Restano sul web 🍎 **Alimenti** e ⚙️ **Impostazioni**: curare il catalogo,
 * configurare i pasti e scegliere il fattore di attività sono cose che si fanno
 * da seduti e una volta sola — e che questa schermata **legge**, senza poterle
 * cambiare. Anche la dieta resta di là: l'obiettivo si crea in «Ti pisasti?»,
 * qui si legge e basta, esattamente come fa la pagina.
 *
 * Le regole del conto non sono riscritte a occhio: stanno in [CalorieRegole],
 * ricalcate una per una dalla pagina.
 */
@Composable
fun CalorieScreen(
    onIndietro: () -> Unit,
    onApriPeso: () -> Unit,
    vm: CalorieViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var vista by remember { mutableStateOf(Vista.DASHBOARD) }
    var aggiungiAperto by remember { mutableStateOf(false) }

    // 🍽️ La scorciatoia «Segna un pasto» della home del telefono apre
    // direttamente il ➕: è il gesto per cui esiste. Chiudendolo si resta qui,
    // sul diario, e l'indietro riporta alla home come sempre.
    val scorciatoia by Scorciatoia.inArrivo.collectAsStateWithLifecycle()
    LaunchedEffect(scorciatoia) {
        if (scorciatoia == Destinazione.PASTO) {
            Scorciatoia.consuma()
            aggiungiAperto = true
        }
    }

    // Il ➕ prende lo schermo intero invece di aprire un dialogo: ci stanno una
    // ricerca, un elenco di risultati e la scelta della porzione, e coi
    // caratteri di sistema grandi un dialogo sarebbe una feritoia. È la stessa
    // scelta di GestioneObiettivoScreen in «Ti pisasti?».
    if (aggiungiAperto) {
        AggiungiAlimentoScreen(
            stato = stato,
            vm = vm,
            onChiudi = {
                vm.azzeraRicerca()
                aggiungiAperto = false
            },
        )
        return
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Calorie",
                onIndietro = onIndietro,
                // ⚖️ Peso ⇄ 🍽️ Calorie, lo stesso interruttore di «Ti pisasti?».
                azioni = { InterruttorePesoCalorie(suCalorie = true, onCambia = onApriPeso) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { aggiungiAperto = true },
                containerColor = VerdeCalorie,
                contentColor = Palette.light,
            ) { Text("➕ Alimento") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (stato.caricamento && stato.righe.isEmpty() && stato.alimenti.isEmpty()) {
                CircularProgressIndicator(
                    color = VerdeCalorie,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    SelettoreVista(vista) { vista = it }
                    when (vista) {
                        Vista.DASHBOARD -> VistaDashboard(
                            stato = stato,
                            onApriGiorno = { giorno ->
                                vm.vaiA(giorno)
                                vista = Vista.DIARIO
                            },
                        )
                        Vista.DIARIO -> VistaDiario(stato = stato, vm = vm)
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
                        Text(messaggio, color = Palette.light, modifier = Modifier.weight(1f))
                        Text(
                            "✕",
                            color = Palette.light,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable { vm.messaggioMostrato() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelettoreVista(scelta: Vista, onScegli: (Vista) -> Unit) {
    // Scorre invece di stringere le voci: coi caratteri di sistema grandi due
    // etichette con l'emoji davanti non ci stanno su 360 px, e schiacciarle
    // sotto il polpastrello le renderebbe non toccabili.
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
                    .background(if (attiva) VerdeCalorie else Palette.inputBg)
                    .clickable { onScegli(vista) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                VoceConIcona(vista.icona, vista.etichetta, if (attiva) Palette.light else Palette.dark, attiva)
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   I PEZZI COMUNI ALLE DUE VISTE
   ═══════════════════════════════════════════════════════════════════════ */

/** Un riquadro bianco col suo titolo, l'equivalente della `.card` del CSS. */
@Composable
internal fun Riquadro(
    titolo: String? = null,
    modifier: Modifier = Modifier,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.cardBg),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            titolo?.let {
                Text(it, fontWeight = FontWeight.Bold, color = Palette.dark)
            }
            contenuto()
        }
    }
}

/**
 * Un numero con la sua etichetta.
 *
 * ⚠️ Non è la griglia di riquadri del web, ed è voluto: **le griglie si
 * sfilacciano** coi caratteri di sistema grandi — tre celle affiancate con
 * testi di lunghezza diversa vanno a capo un numero diverso di volte e perdono
 * l'allineamento. Una riga per numero, etichetta a sinistra e valore a destra,
 * resta leggibile a qualunque ingrandimento e non taglia niente.
 */
@Composable
internal fun Valore(
    etichetta: String,
    valore: String,
    nota: String? = null,
    colore: Color = Palette.dark,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(etichetta, color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            nota?.let {
                Text(it, color = Palette.muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            valore,
            color = colore,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** La barra: quanto del target è già stato mangiato. */
@Composable
internal fun BarraTarget(quota: Double, colore: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Palette.inputBg),
    ) {
        Box(
            Modifier
                .fillMaxWidth((quota / 100.0).toFloat().coerceIn(0f, 1f))
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(colore),
        )
    }
}

/** Il riquadro di nota — spiegazioni, avvisi, e il perché di un numero. */
@Composable
internal fun Nota(testo: String, colore: Color = Palette.muted) {
    Text(
        testo,
        color = colore,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.inputBg)
            .padding(10.dp),
    )
}
