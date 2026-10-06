package com.garsal.speseingiro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.garsal.speseingiro.Prefs
import com.garsal.speseingiro.UiState

/**
 * La prima schermata: si crea un viaggio, oppure si entra in quello di chi ha
 * già il codice.
 *
 * ⚠️ Non c'è nessun login, e non è una semplificazione: il viaggio appartiene a
 * un **codice**, non a un account. Chi crea lo legge qui e lo detta all'altro.
 */
@Composable
fun SchermataIngresso(
    ui: UiState,
    onCrea: (String, String, String?, String?) -> Unit,
    onEntra: (String, String) -> Unit,
    onScegli: (Prefs.Viaggio) -> Unit,
) {
    var creo by remember { mutableStateOf(true) }
    var nomeViaggio by remember { mutableStateOf("") }
    var mioNome by remember { mutableStateOf("") }
    var codice by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("🚲", style = MaterialTheme.typography.displaySmall)
        Text("Spese in giro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Le spese di un viaggio in bici, divise fra due. Ogni voce vale quando l'altro la conferma.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (ui.viaggi.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("I tuoi viaggi", fontWeight = FontWeight.SemiBold)
                    ui.viaggi.forEach { v ->
                        ListItem(
                            headlineContent = { Text(v.nome) },
                            supportingContent = { Text("codice ${v.codice} · tu sei ${v.io}") },
                            leadingContent = { Text("🚲") },
                            modifier = Modifier.clickable { onScegli(v) },
                        )
                    }
                }
            }
        }

        // Due schede e non due schermate: la domanda è una sola — «questo
        // viaggio lo apro io o ci entro?» — e vederla intera evita di cercare
        // dove sta l'altra metà.
        TabRow(selectedTabIndex = if (creo) 0 else 1) {
            Tab(selected = creo, onClick = { creo = true },
                text = { Text("Apro un viaggio") })
            Tab(selected = !creo, onClick = { creo = false },
                text = { Text("Entro col codice") })
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (creo) {
                    OutlinedTextField(
                        value = nomeViaggio, onValueChange = { nomeViaggio = it },
                        label = { Text("Che viaggio è") },
                        placeholder = { Text("Bologna–Rimini in bici") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                } else {
                    OutlinedTextField(
                        value = codice, onValueChange = { codice = it.uppercase() },
                        label = { Text("Codice del viaggio") },
                        placeholder = { Text("ABCD-EFGH") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Te lo detta chi ha aperto il viaggio.") },
                    )
                }
                OutlinedTextField(
                    value = mioNome, onValueChange = { mioNome = it },
                    label = { Text("Il tuo nome") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    supportingText = {
                        Text(
                            if (creo) "È il nome con cui l'altro ti vedrà sulle spese."
                            else "Se stai rientrando dopo aver cambiato telefono, scrivi lo stesso nome di prima."
                        )
                    },
                )
                Button(
                    onClick = {
                        if (creo) onCrea(nomeViaggio.trim(), mioNome.trim(), null, null)
                        else onEntra(codice.trim(), mioNome.trim())
                    },
                    enabled = !ui.caricamento && mioNome.isNotBlank() &&
                        (if (creo) nomeViaggio.isNotBlank() else codice.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (creo) "Apri il viaggio" else "Entra") }
            }
        }

        if (ui.caricamento) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }

        Text(
            "Il codice è la chiave del viaggio: chi ce l'ha entra. Vale la stessa fiducia con cui si divide il conto.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(24.dp))
    }
}
