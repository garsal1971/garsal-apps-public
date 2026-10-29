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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garsal.pronuncia.Accento
import com.garsal.pronuncia.DialogoAggiornamento
import com.garsal.pronuncia.Giudizio
import com.garsal.pronuncia.LinguaFeedback
import com.garsal.pronuncia.Microfono
import com.garsal.pronuncia.Rilascio
import com.garsal.pronuncia.Servizi
import com.garsal.pronuncia.Voce

/**
 * Login, ciclo a mani libere (fase 3), esercizio a mano (fase 2), impostazioni,
 * e da lì lo 📈 storico (fase 5).
 *
 * ⚠️ Coi caratteri di sistema grandi: niente altezze fisse attorno al testo
 * (`heightIn(min)`), e le righe di pulsanti scorrono di lato invece di andare a capo.
 */
@Composable
fun SchermataPrincipale() {
    val connesso by Servizi.connesso.collectAsState()
    var storico by rememberSaveable { mutableStateOf(false) }
    if (connesso && storico) { SchermataStorico(onChiudi = { storico = false }); return }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("🗣️ Pronuncia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (connesso) Connesso(onStorico = { storico = true }) else Accesso()
        }
    }
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

@Composable
private fun Connesso(onStorico: () -> Unit) {
    val prefs = Servizi.prefs
    val ctx = LocalContext.current
    val (versione, _) = remember { Rilascio.installata(ctx) }

    var voce by remember { mutableStateOf(prefs.voce) }
    var accento by remember { mutableStateOf(prefs.accento) }
    var sParola by remember { mutableStateOf(prefs.sogliaParola) }
    var sSuono by remember { mutableStateOf(prefs.sogliaSuono) }
    var sRitmo by remember { mutableStateOf(prefs.sogliaRitmo) }
    var microfono by remember { mutableStateOf(prefs.microfono) }
    var feedback by remember { mutableStateOf(prefs.linguaFeedback) }
    var dialogoVersione by remember { mutableStateOf(false) }
    var parole by remember { mutableStateOf(prefs.parole) }

    Text(
        "Connesso come ${Servizi.auth.email() ?: "?"}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(onClick = onStorico, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("📈 Storico")
    }

    // Si sceglie prima di cominciare, e vale per tutt'e due: il ciclo a mani
    // libere la rilegge a ogni elemento nuovo.
    // L'accento vale per tutto: voci, valutazione e feedback in inglese. Sta
    // qui e non nelle impostazioni perché si sceglie prima di cominciare.
    Tendina("Pronuncia", accento, Accento.entries, { it.etichetta }) { accento = it; prefs.accento = it }

    Tendina("Esercitati su", parole, listOf(false, true), { if (it) "🔤 Parole singole" else "💬 Frasi intere" }) {
        parole = it; prefs.parole = it
    }

    ManiLibere(parole)

    // La chiave riparte da capo l'esercizio quando si cambia: una frase
    // rimasta a schermo in modalità parole sarebbe una scelta non rispettata.
    key(parole) { Esercizio(parole) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚙️ Impostazioni", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Tendina("Voce", voce, Voce.entries, { it.etichetta(accento) }) { voce = it; prefs.voce = it }
            Tendina("Microfono", microfono, Microfono.entries, { it.etichetta }) {
                microfono = it; prefs.microfono = it
            }
            Tendina("Lingua del feedback", feedback, LinguaFeedback.entries, { it.etichetta }) {
                feedback = it; prefs.linguaFeedback = it
            }
            Text("Severità", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Oltre al voto complessivo (almeno 80), boccia se anche solo una parola, un suono o il ritmo resta sotto la sua soglia. Valgono per l'esercizio e per le mani libere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Tendina("Parola peggiore", sParola, Giudizio.SOGLIE, ::soglia) { sParola = it; prefs.sogliaParola = it }
            Tendina("Suono peggiore", sSuono, Giudizio.SOGLIE, ::soglia) { sSuono = it; prefs.sogliaSuono = it }
            Tendina("Ritmo e intonazione", sRitmo, Giudizio.SOGLIE, ::soglia) { sRitmo = it; prefs.sogliaRitmo = it }
            if (sRitmo != null && accento == Accento.BRITANNICO) {
                Text(
                    "⚠️ Microsoft garantisce ritmo e intonazione solo per l'inglese americano: con il britannico, se Azure non li valuta, il controllo non boccia e te lo dice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Lingua del feedback e microfono valgono per il ciclo a mani libere. Con le cuffie Bluetooth come microfono il telefono le usa come in una chiamata: anche la voce si sente in qualità telefonica.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    }
    Spacer(Modifier.heightIn(min = 24.dp))

    if (dialogoVersione) DialogoAggiornamento(onChiudi = { dialogoVersione = false })
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
