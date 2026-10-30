package com.garsal.pronuncia.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import com.garsal.pronuncia.Frasi
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garsal.pronuncia.Accento
import com.garsal.pronuncia.Controllo
import com.garsal.pronuncia.DialogoAggiornamento
import com.garsal.pronuncia.Giudizio
import com.garsal.pronuncia.LinguaFeedback
import com.garsal.pronuncia.Microfono
import com.garsal.pronuncia.Rilascio
import com.garsal.pronuncia.Servizi
import com.garsal.pronuncia.Voce

/**
 * Login, poi quattro schede: 🎯 Esercizio, 🎧 Mani libere, 📈 Storico,
 * ⚙️ Impostazioni. Si passa dall'una all'altra toccando la linguetta o
 * strusciando a destra e sinistra (`HorizontalPager`).
 *
 * ⚠️ Le impostazioni non stanno più nelle schede di esercizio (v1.12.0): lì
 * restano solo i comandi per esercitarsi. Livello, tema, accento e frasi/parole
 * si leggono dalle preferenze, e l'esercizio si rifà da capo quando cambiano.
 *
 * ⚠️ Una pagina del pager non visibile viene smontata: tornando sull'Esercizio
 * la frase è nuova. Il ciclo a mani libere invece non ne risente, vive in
 * `CicloService`.
 *
 * ⚠️ Coi caratteri di sistema grandi: niente altezze fisse attorno al testo
 * (`heightIn(min)`), e le linguette scorrono di lato invece di stringersi.
 */
@Composable
fun SchermataPrincipale() {
    val connesso by Servizi.connesso.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (connesso) Schede()
        else Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("🗣️ Pronuncia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Accesso()
        }
    }
}

private val SCHEDE = listOf("🎯 Esercizio", "🎧 Mani libere", "📈 Storico", "⚙️ Impostazioni")

/** Le scelte che più schede leggono, tenute qui perché cambiandole in
 *  ⚙️ Impostazioni l'Esercizio si rifaccia subito. */
private class Scelte(prefs: com.garsal.pronuncia.Prefs) {
    var accento by mutableStateOf(prefs.accento)
    var parole by mutableStateOf(prefs.parole)
    var livello by mutableStateOf(prefs.livello)
    var tema by mutableStateOf(prefs.tema)
}

@Composable
private fun Schede() {
    val pager = rememberPagerState { SCHEDE.size }
    val scope = rememberCoroutineScope()
    val scelte = remember { Scelte(Servizi.prefs) }
    Column(Modifier.safeDrawingPadding()) {
        Text(
            "🗣️ Pronuncia",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
            SCHEDE.forEachIndexed { i, t ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = { Text(t) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { i ->
            when (i) {
                2 -> SchermataStorico()
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (i) {
                        0 -> key(scelte.parole, scelte.livello, scelte.tema, scelte.accento) {
                            Esercizio(scelte.parole, scelte.livello, scelte.tema)
                        }
                        1 -> ManiLibere(scelte.parole)
                        else -> Impostazioni(scelte)
                    }
                    Spacer(Modifier.heightIn(min = 24.dp))
                }
            }
        }
    }
}

/** Il titolo di un'area delle impostazioni, dentro il suo riquadro. */
@Composable
private fun Area(titolo: String, contenuto: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(titolo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            contenuto()
        }
    }
}

@Composable
private fun Nota(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Tre aree, una sotto l'altra: ⚙️ Generali (vale per tutto), 🎯 Davanti allo
 * schermo (l'esercizio a mano), 🎧 A mani libere (il ciclo). Livello e tema
 * stanno nella seconda ma li legge anche il ciclo: oggi è quel che sono.
 */
@Composable
private fun Impostazioni(scelte: Scelte) {
    val prefs = Servizi.prefs
    val ctx = LocalContext.current
    val (versione, _) = remember { Rilascio.installata(ctx) }

    var voce by remember { mutableStateOf(prefs.voce) }
    // Le soglie che si stanno guardando: frasi o parole singole. Parte da
    // quel che si esercita adesso, che è quasi sempre quel che si vuole tarare.
    var soglieParole by remember { mutableStateOf(scelte.parole) }
    var soglie by remember(soglieParole) {
        mutableStateOf(Controllo.entries.associateWith { prefs.soglia(it, soglieParole) })
    }
    var microfono by remember { mutableStateOf(prefs.microfono) }
    var feedback by remember { mutableStateOf(prefs.linguaFeedback) }
    var riascolto by remember { mutableStateOf(prefs.riascoltoCiclo) }
    var dialogoVersione by remember { mutableStateOf(false) }

    Area("⚙️ Generali") {
        Nota("Connesso come ${Servizi.auth.email() ?: "?"}")
        Tendina("Pronuncia", scelte.accento, Accento.entries, { it.etichetta }) { scelte.accento = it; prefs.accento = it }
        Tendina("Voce", voce, Voce.entries, { it.etichetta(scelte.accento) }) { voce = it; prefs.voce = it }
        // Il ciclo a mani libere la rilegge a ogni elemento nuovo.
        Tendina("Esercitati su", scelte.parole, listOf(false, true), { if (it) "🔤 Parole singole" else "💬 Frasi intere" }) {
            scelte.parole = it; prefs.parole = it
        }
        Text("Severità", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Nota("Oltre al voto complessivo (almeno 80), boccia se anche solo una parola, un suono o il ritmo resta sotto la sua soglia. Le soglie delle frasi e quelle delle parole singole sono separate; valgono per l'esercizio e per le mani libere.")
        Tendina("Soglie per", soglieParole, listOf(false, true), { if (it) "🔤 Parole singole" else "💬 Frasi intere" }) {
            soglieParole = it
        }
        Controllo.entries.forEach { c ->
            Tendina(c.etichetta, soglie[c], Giudizio.SOGLIE, ::soglia) {
                soglie = soglie + (c to it); prefs.impostaSoglia(c, soglieParole, it)
            }
        }
        if (soglie[Controllo.RITMO] != null && scelte.accento == Accento.BRITANNICO) {
            Nota("⚠️ Microsoft garantisce ritmo e intonazione solo per l'inglese americano: con il britannico, se Azure non li valuta, il controllo non boccia e te lo dice.")
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { dialogoVersione = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("📱 Versione app (v$versione)")
            }
            TextButton(
                onClick = { Servizi.auth.esci(); Servizi.connesso.value = false },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Esci") }
        }
    }

    Area("🎯 Davanti allo schermo") {
        Tendina("Livello", scelte.livello, listOf(null) + Frasi.LIVELLI, { it ?: "Tutti" }) {
            scelte.livello = it; prefs.livello = it
        }
        Tendina("Tema", scelte.tema, listOf(null) + Frasi.TEMI.keys, { it?.let { k -> Frasi.TEMI[k] } ?: "Tutti" }) {
            scelte.tema = it; prefs.tema = it
        }
        Nota("Livello e tema valgono anche per il ciclo a mani libere.")
    }

    Area("🎧 A mani libere") {
        Tendina("Microfono", microfono, Microfono.entries, { it.etichetta }) { microfono = it; prefs.microfono = it }
        Tendina("Lingua del feedback", feedback, LinguaFeedback.entries, { it.etichetta }) {
            feedback = it; prefs.linguaFeedback = it
        }
        // Letta a ogni tentativo: vale anche a ciclo acceso.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("🔁 Fammi riascoltare la mia voce prima del voto", Modifier.weight(1f))
            Switch(checked = riascolto, onCheckedChange = { riascolto = it; prefs.riascoltoCiclo = it })
        }
        Nota("Con le cuffie Bluetooth come microfono il telefono le usa come in una chiamata: anche la voce si sente in qualità telefonica.")
    }

    if (dialogoVersione) DialogoAggiornamento(onChiudi = { dialogoVersione = false })
}

@Composable
private fun Accesso() {
    val ctx = LocalContext.current
    var nota by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Per parlare con Azure serve l'accesso Google della suite.")
            Button(
                onClick = { if (!Servizi.auth.apriLogin(ctx)) nota = "Non ho trovato un browser da aprire." },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Accedi con Google") }
            Text(
                "Finito il login, nel browser tocca «Apri Pronuncia».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            nota?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun soglia(s: Int?) = s?.let { "Sotto $it boccia" } ?: "Spento"

/** Una scelta fra poche opzioni fisse è sempre una tendina, con la sua ▾. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Tendina(
    etichetta: String,
    valore: T,
    opzioni: List<T>,
    testo: (T) -> String,
    onScelta: (T) -> Unit,
) {
    var aperta by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = aperta, onExpandedChange = { aperta = it }) {
        OutlinedTextField(
            value = testo(valore),
            onValueChange = {},
            readOnly = true,
            label = { Text(etichetta) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aperta) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = aperta, onDismissRequest = { aperta = false }) {
            opzioni.forEach { o ->
                DropdownMenuItem(text = { Text(testo(o)) }, onClick = { onScelta(o); aperta = false })
            }
        }
    }
}
