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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garsal.pronuncia.DialogoAggiornamento
import com.garsal.pronuncia.LinguaFeedback
import com.garsal.pronuncia.Microfono
import com.garsal.pronuncia.Rilascio
import com.garsal.pronuncia.Servizi
import com.garsal.pronuncia.SessioneScaduta
import com.garsal.pronuncia.Voce
import kotlinx.coroutines.launch

/**
 * Fase 1: login, prova della voce, impostazioni. Il ciclo a mani libere
 * arriva con la fase 3 e prenderà il posto della «Prova la voce».
 *
 * ⚠️ Coi caratteri di sistema grandi: niente altezze fisse attorno al testo
 * (`heightIn(min)`), e le righe di pulsanti scorrono di lato invece di andare a capo.
 */
@Composable
fun SchermataPrincipale() {
    val connesso by Servizi.connesso.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("🇬🇧 Pronuncia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (connesso) Connesso() else Accesso()
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
private fun Connesso() {
    val prefs = Servizi.prefs
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val (versione, _) = remember { Rilascio.installata(ctx) }

    var frase by remember { mutableStateOf("Could you tell me the way to the railway station, please?") }
    var voce by remember { mutableStateOf(prefs.voce) }
    var microfono by remember { mutableStateOf(prefs.microfono) }
    var feedback by remember { mutableStateOf(prefs.linguaFeedback) }
    var stato by remember { mutableStateOf<String?>(null) }
    var parla by remember { mutableStateOf(false) }
    var dialogoVersione by remember { mutableStateOf(false) }

    fun ascolta(lenta: Boolean) {
        if (parla || frase.isBlank()) return
        parla = true
        stato = "Sto chiedendo la voce ad Azure…"
        scope.launch {
            stato = try {
                Servizi.tts.pronuncia(frase.trim(), voce, lenta)
                "✅ Letta da ${voce.etichetta}${if (lenta) ", lenta" else ""}."
            } catch (e: SessioneScaduta) {
                Servizi.connesso.value = false
                null
            } catch (e: Exception) {
                "⚠️ ${e.message ?: e.javaClass.simpleName}"
            }
            parla = false
        }
    }

    Text(
        "Connesso come ${Servizi.auth.email() ?: "?"}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🔊 Prova la voce", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = frase,
                onValueChange = { frase = it },
                label = { Text("Frase in inglese") },
                modifier = Modifier.fillMaxWidth(),
            )
            Tendina("Voce", voce, Voce.entries, { it.etichetta }) { voce = it; prefs.voce = it }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { ascolta(false) }, enabled = !parla, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("▶ Ascolta")
                }
                OutlinedButton(onClick = { ascolta(true) }, enabled = !parla, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("🐢 Più lenta")
                }
            }
            stato?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚙️ Impostazioni", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Tendina("Microfono", microfono, Microfono.entries, { it.etichetta }) {
                microfono = it; prefs.microfono = it
            }
            Tendina("Lingua del feedback", feedback, LinguaFeedback.entries, { it.etichetta }) {
                feedback = it; prefs.linguaFeedback = it
            }
            Text(
                "Microfono e lingua del feedback si useranno col ciclo a mani libere.",
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

/** Una scelta fra poche opzioni fisse è sempre una tendina, con la sua ▾. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Tendina(
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
