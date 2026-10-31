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
import com.garsal.pronuncia.StatParola
import com.garsal.pronuncia.Storico
import com.garsal.pronuncia.StoricoRemoto
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
fun SchermataStorico(onChiudi: (() -> Unit)? = null) {
    // Dalla v1.12.0 è una scheda della schermata principale: lì non c'è un
    // «indietro» da offrire, ci si sposta con le linguette o strusciando.
    if (onChiudi != null) BackHandler(onBack = onChiudi)
    val ctx = LocalContext.current
    val dao = remember { Storico.dao(ctx) }
    val giorni by dao.perGiorno(SOGLIA_BUONA, 30).collectAsState(emptyList())
    val parole by dao.paroleDifficili(SOGLIA_BUONA, 20).collectAsState(emptyList())
    val ultimi by dao.ultimi(50).collectAsState(emptyList())
    val totale by dao.totale().collectAsState(0)
    val perParola by dao.perParola().collectAsState(emptyList())
    var svuota by remember { mutableStateOf(false) }
    var erroreSvuota by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (onChiudi != null) TextButton(onClick = onChiudi, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Indietro") }
                Text("📈 Storico", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (totale == 0) {
                Text("Ancora nessun tentativo. Si riempie da sé col ciclo a mani libere e con l'esercizio.")
                return@Column
            }
            Text(
                "$totale tentativi in archivio, sul telefono e nel database.",
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

            Sezione("📚 Le mie parole") { MieParole(perParola, Frasi.parole(ctx).size) }

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

            erroreSvuota?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { svuota = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("🗑 Cancella lo storico")
            }
        }
    }

    if (svuota) {
        AlertDialog(
            onDismissRequest = { svuota = false },
            title = { Text("Cancellare lo storico?") },
            text = { Text("Se ne vanno tutti i $totale tentativi e le loro parole, dal telefono e dal database, e non si recuperano. Le frasi restano.") },
            confirmButton = {
                TextButton(onClick = {
                    svuota = false
                    scope.launch {
                        erroreSvuota = runCatching { StoricoRemoto.svuota(ctx) }.exceptionOrNull()
                            ?.let { "Non ho cancellato niente: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }) { Text("Cancella") }
            },
            dismissButton = { TextButton(onClick = { svuota = false }) { Text("Annulla") } },
        )
    }
}

private enum class Ordine(val etichetta: String) {
    RIPETUTE("Più ripetute"), PEGGIORI("Voto migliore più basso"), MIGLIORI("Voto migliore più alto"), RECENTI("Più recenti")
}

/**
 * Per ogni parola del catalogo provata almeno una volta: quante volte, il
 * voto migliore, l'ultimo e la media (v1.20.0). ⚠️ Si mostrano a blocchi di
 * 30: la schermata scorre già tutta, e duemila righe disegnate insieme
 * costerebbero; il resto si trova coi filtri.
 */
@Composable
private fun MieParole(righe: List<StatParola>, catalogo: Int) {
    var tema by remember { mutableStateOf<String?>(null) }
    var livello by remember { mutableStateOf<String?>(null) }
    var ordine by remember { mutableStateOf(Ordine.RIPETUTE) }
    var quante by remember { mutableStateOf(30) }
    Text(
        "${righe.size} parole provate su $catalogo.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (righe.isEmpty()) {
        Text("Ancora nessuna parola singola: si scelgono con «🔤 Parole singole».")
        return
    }
    Tendina("Tema", tema, listOf(null) + Frasi.TEMI.keys, { it?.let { k -> Frasi.TEMI[k] } ?: "Tutti" }) { tema = it; quante = 30 }
    Tendina("Livello", livello, listOf(null) + Frasi.LIVELLI, { it ?: "Tutti" }) { livello = it; quante = 30 }
    Tendina("Ordina", ordine, Ordine.entries.toList(), { it.etichetta }) { ordine = it; quante = 30 }
    val scelte = righe.filter { (tema == null || it.tema == tema) && (livello == null || it.livello == livello) }
        .let { l ->
            when (ordine) {
                Ordine.RIPETUTE -> l.sortedWith(compareByDescending<StatParola> { it.volte }.thenBy { it.testo })
                Ordine.PEGGIORI -> l.sortedBy { it.migliore }
                Ordine.MIGLIORI -> l.sortedByDescending { it.migliore }
                Ordine.RECENTI -> l.sortedByDescending { it.quando }
            }
        }
    if (scelte.isEmpty()) Text("Nessuna parola provata con questi filtri.")
    scelte.take(quante).forEach { p ->
        Column {
            Text(p.testo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${p.livello} · ${Frasi.TEMI[p.tema] ?: p.tema} · ripetuta ${p.volte} ${if (p.volte == 1) "volta" else "volte"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("migliore ${uno(p.migliore)} · ultimo ${uno(p.ultimo)} · media ${uno(p.media)}", color = colore(p.migliore), fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
    }
    if (scelte.size > quante) {
        OutlinedButton(onClick = { quante += 30 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Mostra altre ${minOf(30, scelte.size - quante)} (di ${scelte.size})")
        }
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
