package com.garsal.appsphere.memo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import kotlinx.serialization.json.JsonPrimitive

/**
 * Un diario aperto: lo scopo, il riepilogo per misura e le registrazioni.
 *
 * Il riepilogo mostra l'ultimo valore e le **ultime 12 registrazioni in
 * miniatura**, come le `.spark` del web. ⚠️ Per una misura a **scelta** al
 * posto della miniatura c'è la **distribuzione** — quante volte è uscita
 * ciascuna opzione: una scelta non ha un numero, e disegnarne la spezzata
 * vorrebbe dire trattare la quarta opzione come il doppio della seconda.
 */
@Composable
fun MemoDiarioView(
    scheda: MmScheda,
    vista: VistaScheda,
    onIndietro: () -> Unit,
    onModifica: () -> Unit,
    onFissa: () -> Unit,
    onSalvaRegistrazione: (
        String?, String, String, String, Map<String, JsonPrimitive>, () -> Unit,
    ) -> Unit,
    onEliminaRegistrazione: (MmRegistrazione) -> Unit,
) {
    var inRegistrazione by remember { mutableStateOf<MmRegistrazione?>(null) }
    var nuovaRegistrazione by remember { mutableStateOf(false) }
    var daEliminare by remember { mutableStateOf<MmRegistrazione?>(null) }
    var avviso by remember { mutableStateOf<String?>(null) }

    // Il tasto indietro deve chiudere **la registrazione**, non il diario: questo
    // BackHandler si compone dopo quelli di MemoScreen, e l'ultimo acceso vince.
    BackHandler(enabled = nuovaRegistrazione || inRegistrazione != null) {
        nuovaRegistrazione = false
        inRegistrazione = null
    }

    if (nuovaRegistrazione || inRegistrazione != null) {
        MemoRegistrazione(
            scheda = scheda,
            misure = vista.misure,
            registrazione = inRegistrazione,
            onAnnulla = { nuovaRegistrazione = false; inRegistrazione = null },
            onSalva = { id, titolo, data, nota, misure ->
                onSalvaRegistrazione(id, titolo, data, nota, misure) {
                    nuovaRegistrazione = false
                    inRegistrazione = null
                }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = scheda.titolo.ifBlank { "Diario senza titolo" },
                onIndietro = onIndietro,
                azioni = {
                    if (scheda.riservato) Text("🙈", fontSize = 16.sp)
                    Text(
                        text = if (scheda.fissata) "📌" else "📍",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onFissa)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        text = "✏️",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onModifica)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // Senza misure una registrazione non raccoglierebbe niente:
                    // si manda a definirle invece di aprire un modulo vuoto.
                    if (vista.misure.isEmpty()) {
                        avviso = "Prima aggiungi almeno una misura (✏️ in alto)"
                    } else {
                        nuovaRegistrazione = true
                    }
                },
                containerColor = BluMemo,
                contentColor = Palette.light,
            ) { Text("+", fontSize = 26.sp) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            avviso?.let { messaggio ->
                Text(
                    text = messaggio,
                    color = Palette.dark,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Palette.inputBg)
                        .clickable { avviso = null }
                        .padding(12.dp),
                )
            }

            if (vista.caricamento) {
                Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(
                        color = BluMemo,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (scheda.anteprima.isNotBlank()) {
                    item {
                        Text(
                            text = remember(scheda.contenuto) {
                                runCatching { AnnotatedString.fromHtml(scheda.contenuto) }
                                    .getOrElse { AnnotatedString(scheda.anteprima) }
                            },
                            color = Palette.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                item {
                    Text(
                        text = "📊 Le misure",
                        color = Palette.dark,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                if (vista.misure.isEmpty()) {
                    item {
                        Text(
                            text = "Questo diario non misura ancora niente. Apri ✏️ e " +
                                "aggiungi almeno una misura.",
                            color = Palette.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    items(vista.misure, key = { it.id }) { misura ->
                        RiepilogoMisura(misura, vista.storico)
                    }
                }

                item {
                    Text(
                        text = "🗒 Le registrazioni (${vista.registrazioni.size})",
                        color = Palette.dark,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (vista.registrazioni.isEmpty()) {
                    item {
                        Text(
                            text = "Nessuna registrazione. Premi + in basso a destra.",
                            color = Palette.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    items(vista.registrazioni, key = { it.id }) { registrazione ->
                        RigaRegistrazione(
                            registrazione = registrazione,
                            misure = vista.misure,
                            onModifica = { inRegistrazione = registrazione },
                            onElimina = { daEliminare = registrazione },
                        )
                    }
                }
            }
        }
    }

    daEliminare?.let { registrazione ->
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare la registrazione?") },
            text = {
                Text(
                    "Quella del ${giorno(registrazione.data)}" +
                        registrazione.titolo.takeIf { it.isNotBlank() }
                            ?.let { " — «$it»" }.orEmpty() + " viene eliminata."
                )
            },
            confirmButton = {
                TextButton(onClick = { daEliminare = null; onEliminaRegistrazione(registrazione) }) {
                    Text("Elimina", color = Palette.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { daEliminare = null }) {
                    Text("Annulla", color = Palette.muted)
                }
            },
        )
    }
}

/** Il riquadro di una misura: nome, che cos'è, la nota, l'ultimo valore e lo storico. */
@Composable
private fun RiepilogoMisura(misura: MmMisura, storico: List<MmRegistrazione>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.inputBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val valori = storico.mapNotNull { it.misure[misura.id] }

        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = misura.nome,
                    color = Palette.dark,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = misura.descrizione,
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (misura.nota.isNotBlank()) {
                    Text(
                        text = misura.nota,
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                text = misura.etichettaValore(valori.lastOrNull()),
                color = BluMemo,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (misura.tipo == TipoMisura.SCELTA) Distribuzione(misura, valori)
        else Spezzata(misura, valori)
    }
}

/**
 * Lo storico in miniatura: le ultime 12 registrazioni, una barretta ciascuna.
 *
 * Il fondo scala è quello della misura per le scale, quello osservato per i
 * numeri liberi — come nel web.
 */
@Composable
private fun Spezzata(misura: MmMisura, valori: List<JsonPrimitive>) {
    val serie = valori.mapNotNull { misura.numerico(it) }.takeLast(12)
    if (serie.isEmpty()) return

    val basso: Double
    val alto: Double
    when (misura.tipo) {
        TipoMisura.SCALA -> {
            basso = misura.minimo ?: 0.0
            alto = misura.massimo ?: 1.0
        }
        TipoMisura.NUMERO -> {
            basso = minOf(serie.min(), 0.0)
            alto = maxOf(serie.max(), 1.0)
        }
        else -> { basso = 0.0; alto = 1.0 }
    }
    val ampiezza = (alto - basso).takeIf { it != 0.0 } ?: 1.0

    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        serie.forEach { valore ->
            val quota = ((valore - basso) / ampiezza).coerceIn(0.0, 1.0).toFloat()
            Box(
                Modifier
                    .weight(1f)
                    // Una barretta a fondo scala minimo resta comunque visibile:
                    // a zero sembrerebbe un giorno non misurato, che è un'altra cosa.
                    .fillMaxHeight(maxOf(quota, 0.06f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(BluMemo)
            )
        }
    }
}

/**
 * Quante volte è uscita ciascuna opzione — la domanda che una combo pone
 * davvero. Prima le opzioni definite, nel loro ordine; poi quelle **tolte** che
 * qualche registrazione cita ancora, che si dicono invece di sparire.
 */
@Composable
private fun Distribuzione(misura: MmMisura, valori: List<JsonPrimitive>) {
    val conteggi = valori.groupingBy { it.content }.eachCount()
    val massimo = maxOf(1, conteggi.values.maxOrNull() ?: 1)
    val tolte = conteggi.keys.filter { id -> misura.opzioni.none { it.id == id } }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (misura.opzioni.map { it.id to it.etichetta } + tolte.map { it to null })
            .forEach { (id, etichetta) ->
                val quante = conteggi[id] ?: 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = etichetta ?: "opzione tolta",
                        color = if (etichetta == null) Palette.muted else Palette.dark,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .weight(1.4f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Palette.border)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(quante.toFloat() / massimo)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(BluMemo)
                        )
                    }
                    Text(
                        text = "$quante",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(28.dp).padding(start = 8.dp),
                    )
                }
            }
    }
}

/** Una registrazione in elenco: data, titolo, i valori e la nota. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RigaRegistrazione(
    registrazione: MmRegistrazione,
    misure: List<MmMisura>,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.cardBg)
            .clickable(onClick = onModifica)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = giorno(registrazione.data),
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (registrazione.titolo.isNotBlank()) {
                    Text(
                        text = registrazione.titolo,
                        color = Palette.dark,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Text(
                text = "🗑",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onElimina)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (registrazione.misure.isEmpty()) {
            Text(
                text = "nessuna misura registrata",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                registrazione.misure.forEach { (id, grezzo) ->
                    val misura = misure.firstOrNull { it.id == id }
                    Text(
                        // Una misura tolta dopo: il valore c'è ancora ma non ha
                        // più un nome. Si dice, invece di farlo sparire.
                        text = if (misura == null) "misura tolta: ${grezzo.content}"
                        else "${misura.nome} ${misura.etichettaValore(grezzo)}",
                        color = if (misura == null) Palette.muted else Palette.dark,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Palette.inputBg)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (registrazione.nota.isNotBlank()) {
            Text(
                text = registrazione.nota,
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
