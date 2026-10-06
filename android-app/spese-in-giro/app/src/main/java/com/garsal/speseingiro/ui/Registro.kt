package com.garsal.speseingiro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garsal.speseingiro.RigaLog
import com.garsal.speseingiro.StatoViaggio

/**
 * Il registro: chi ha fatto cosa, e quando.
 *
 * ⚠️ Lo scrive il **database** a ogni operazione (`vg_scrivi_log`), non l'app:
 * un registro tenuto dai due telefoni sarebbe due registri diversi. E porta
 * dentro la riga descrizione e importo della voce di cui parla, così resta
 * leggibile anche quando quella voce non c'è più — è la stessa scelta di
 * `ob_action_history.action_title`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchermataRegistro(stato: StatoViaggio, onIndietro: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📜 Registro") },
                navigationIcon = { TextButton(onClick = onIndietro) { Text("‹ Indietro") } },
            )
        }
    ) { pad ->
        if (stato.log.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Ancora niente da raccontare.")
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(stato.log, key = { it.id }) { r -> RigaRegistro(r) }
        }
    }
}

@Composable
private fun RigaRegistro(r: RigaLog) {
    val (emoji, verbo) = when (r.azione) {
        "viaggio_creato" -> "🚲" to "ha aperto il viaggio"
        "entrato" -> "👋" to "è entrato nel viaggio"
        "rientrato" -> "🔑" to "è rientrato da un altro telefono"
        "creata" -> "✍️" to "ha segnato"
        "modificata" -> "✏️" to "ha corretto"
        "confermata" -> "✅" to "ha confermato"
        "eliminata" -> "🗑" to "ha tolto (non era ancora confermata)"
        "cancellazione_chiesta" -> "❓" to "ha chiesto di cancellare"
        "cancellazione_approvata" -> "🚫" to "ha approvato la cancellazione di"
        "cancellazione_rifiutata" -> "↩️" to "ha rifiutato la cancellazione di"
        // Le categorie sono del viaggio, quindi le cambia per tutti e due: chi
        // ne aggiunge una la fa comparire anche sull'altro telefono, e il
        // registro è il posto dove quel cambiamento si vede.
        "categoria_creata" -> "🏷️" to "ha aggiunto una categoria"
        "categoria_cambiata" -> "✏️" to "ha rinominato una categoria"
        "categoria_tolta" -> "🗑" to "ha tolto una categoria"
        // Un'azione che questa versione dell'app non conosce si mostra com'è
        // scritta: sparire sarebbe il modo peggiore di dire che c'è.
        else -> "•" to r.azione.replace('_', ' ')
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$emoji  ${r.chi} $verbo", style = MaterialTheme.typography.bodyMedium)
            if (r.voceTesto.isNotBlank() || r.voceImporto != null) {
                Text(
                    listOfNotNull(
                        r.voceTesto.ifBlank { null },
                        r.voceImporto?.let { euro(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (r.dettaglio.isNotBlank()) {
                Text(r.dettaglio, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(istanteIt(r.quando), style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
