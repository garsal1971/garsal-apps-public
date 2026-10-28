package com.garsal.pronuncia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.garsal.pronuncia.Frasi
import com.garsal.pronuncia.ParolaTentativo
import com.garsal.pronuncia.Storico
import com.garsal.pronuncia.Tentativo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Fase 5: lo storico. Per giorno, le parole più difficili e gli ultimi
 * tentativi (toccandone uno si vedono le sue parole).
 *
 * ⚠️ Le righe sono righe di testo e non tabelle: coi caratteri di sistema
 * grandi cinque colonne affiancate andrebbero a capo ognuna per conto suo.
 */
@Composable
fun SchermataStorico(onChiudi: () -> Unit) {
    BackHandler(onBack = onChiudi)
    val ctx = LocalContext.current
    val dao = remember { Storico.dao(ctx) }
    val giorni by dao.perGiorno(SOGLIA_BUONA, 30).collectAsState(emptyList())
    val parole by dao.paroleDifficili(SOGLIA_BUONA, 20).collectAsState(emptyList())
    val ultimi by dao.ultimi(50).collectAsState(emptyList())
    val totale by dao.totale().collectAsState(0)
    var svuota by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onChiudi, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Indietro") }
                Text("📈 Storico", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (totale == 0) {
                Text("Ancora nessun tentativo. Si riempie da sé col ciclo a mani libere e con l'esercizio.")
                return@Column
            }
            Text(
                "$totale tentativi in archivio, su questo telefono.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Sezione("📅 Per giorno") {
                giorni.forEach { g ->
                    Column {
                        Text(dataBella(g.giorno), fontWeight = FontWeight.Bold)
                        Text(
                            "${g.frasi} frasi · ${g.tentativi} tentativi · ${g.riuscite} riusciti",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "media ${uno(g.media)}/100",
                            color = colore(g.media),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    HorizontalDivider()
                }
            }

            Sezione("🧩 Parole più difficili") {
                if (parole.isEmpty()) Text("Nessuna parola sbagliata finora. 👏")
                parole.forEach { p ->
                    Column {
                        Text(p.parola, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "sbagliata ${p.sbagliate} volte su ${p.volte} · media ${uno(p.media)}/100",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colore(p.media),
                        )
                    }
                }
            }

            Sezione("🕘 Ultimi tentativi") {
                ultimi.forEach { t -> RigaTentativo(t) }
            }

            OutlinedButton(onClick = { svuota = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("🗑 Cancella lo storico")
            }
        }
    }

    if (svuota) {
        AlertDialog(
            onDismissRequest = { svuota = false },
            title = { Text("Cancellare lo storico?") },
            text = { Text("Se ne vanno tutti i $totale tentativi e le loro parole, e non si recuperano. Le frasi restano.") },
            confirmButton = {
                TextButton(onClick = { svuota = false; scope.launch { dao.svuota() } }) { Text("Cancella") }
            },
            dismissButton = { TextButton(onClick = { svuota = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun Sezione(titolo: String, contenuto: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(titolo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            contenuto()
        }
    }
}

@Composable
private fun RigaTentativo(t: Tentativo) {
    val ctx = LocalContext.current
    var aperta by remember { mutableStateOf(false) }
    var parole by remember { mutableStateOf<List<ParolaTentativo>?>(null) }
    LaunchedEffect(aperta) { if (aperta && parole == null) parole = Storico.dao(ctx).paroleDi(t.id) }

    Column(Modifier.fillMaxWidth().clickable { aperta = !aperta }.padding(vertical = 4.dp)) {
        Text(
            "${ora(t.quando)} · ${if (t.fonte == "mani_libere") "🎧" else "🎯"} ${t.livello} · ${Frasi.TEMI[t.tema] ?: t.tema}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(t.testo, style = MaterialTheme.typography.bodyLarge)
        Text("${uno(t.pronuncia)}/100", color = colore(t.pronuncia), fontWeight = FontWeight.Bold)
        if (aperta) {
            Text(
                "Accuratezza ${uno(t.accuratezza)} · fluidità ${uno(t.fluidita)} · completezza ${uno(t.completezza)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (t.riconosciuto.isNotBlank()) Text("Sentito: «${t.riconosciuto}»", style = MaterialTheme.typography.bodySmall)
            parole?.filter { it.errore != "None" || it.accuratezza < SOGLIA_BUONA }?.let { male ->
                if (male.isEmpty()) Text("Tutte le parole a posto.", style = MaterialTheme.typography.bodySmall)
                else Text(
                    "Da curare: " + male.joinToString(" · ") { p ->
                        when (p.errore) {
                            "Omission" -> "${p.parola} (saltata)"
                            "Insertion" -> "${p.parola} (in più)"
                            else -> "${p.parola} ${uno(p.accuratezza)}"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    HorizontalDivider()
}

private fun dataBella(iso: String): String {
    val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val oggi = LocalDate.now()
    return when (d) {
        oggi -> "Oggi"
        oggi.minusDays(1) -> "Ieri"
        else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALY))
    }
}

private fun ora(ms: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(ms))
