package com.garsal.appsphere.notifiche

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.larghezzaPulsanti
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FATTO    = "✅ Fatto"
private const val RINVIA   = "⏸ Rinvia"
private const val CANCELLA = "🗑 Cancella"

/**
 * 🔔 Le notifiche ricevute e non ancora gestite: è l'archivio di [Notifiche]
 * letto per intero, cioè le stesse che tornano nella tendina a ogni apertura.
 *
 * ⚠️ Qui non si decide niente: ogni pulsante passa da [Notifiche.esegui], la
 * stessa strada dei pulsanti sulla notifica. Una voce sparisce dall'elenco
 * quando il server ha detto sì, non al tocco — un'azione fallita resta qui.
 */
@Composable
fun NotificheScreen(onIndietro: () -> Unit) {
    val context = LocalContext.current
    val cambiamenti by Notifiche.cambiamenti.collectAsState()
    val voci = remember(cambiamenti) { Notifiche.archivio(context) }
    // Tutte le etichette che la riga può mostrare, anche quelle che una data
    // scheda non ha: la larghezza non deve ballare fra una scheda e l'altra.
    val larghezza = larghezzaPulsanti(listOf(FATTO, RINVIA, CANCELLA))

    Scaffold(
        topBar = { GarsalTopBar(titolo = "🔔 Notifiche", onIndietro = onIndietro) },
    ) { padding ->
        if (voci.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    "Nessuna notifica in sospeso. Quelle che arrivano restano qui " +
                        "finché non le chiudi con un'azione o col 🗑.",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "${voci.size} in sospeso",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(voci, key = { it.queueId }) { voce ->
                SchedaNotifica(voce, larghezza) { azione, minuti ->
                    Notifiche.esegui(context, voce.queueId, azione, minuti)
                }
            }
        }
    }
}

@Composable
private fun SchedaNotifica(
    voce: Notifiche.Archiviata,
    larghezza: Dp,
    onAzione: (azione: String, minuti: Int?) -> Unit,
) {
    var menuRinvio by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .background(Palette.cardBg, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(voce.titolo, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        if (voce.testo.isNotBlank()) {
            Text(voce.testo, style = MaterialTheme.typography.bodyMedium)
        }
        if (voce.ricevuta > 0) {
            Text(
                "Arrivata " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(voce.ricevuta)),
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Le azioni a sinistra in ordine d'uso, la distruttiva per ultima.
        RigaScorrevole(disposizione = Arrangement.spacedBy(8.dp)) {
            if (voce.completa) {
                Pillola(FATTO, Palette.success, larghezza) { onAzione("complete", null) }
            }
            Box {
                Pillola(RINVIA, Palette.accent, larghezza) { menuRinvio = true }
                DropdownMenu(expanded = menuRinvio, onDismissRequest = { menuRinvio = false }) {
                    Notifiche.RINVII.forEach { (minuti, etichetta) ->
                        DropdownMenuItem(
                            text = { Text("⏸ $etichetta") },
                            onClick = { menuRinvio = false; onAzione("snooze", minuti) },
                        )
                    }
                    if (voce.annulla) {
                        DropdownMenuItem(
                            text = { Text("❌ Annulla il promemoria") },
                            onClick = { menuRinvio = false; onAzione("cancel", null) },
                        )
                    }
                }
            }
            Pillola(CANCELLA, Palette.danger, larghezza) {
                onAzione(AzioniNotifica.AZIONE_CANCELLA, null)
            }
        }
    }
}
