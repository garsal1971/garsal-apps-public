package com.garsal.appsphere.tafiri

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Tendina
import java.time.LocalDate

/**
 * Creazione e modifica di una sfida — il modale di `ta-firi.html`, qui a tutto
 * schermo come il form dei task.
 *
 * ⚠️ **Data di inizio e durata non si cambiano dopo la creazione** (`id !=
 * null`), esattamente come nel web, dove i due campi si disabilitano. I giorni
 * della sfida sono righe di `sf_checkins` scritte alla creazione: spostare
 * l'inizio o allungare la durata vorrebbe dire aggiungerne, toglierne e
 * decidere cosa fare delle spunte già date — e la sfida non sarebbe più quella
 * che si è cominciata.
 */
@Composable
fun TaFiriForm(
    bozzaIniziale: BozzaSfida,
    id: String?,
    onAnnulla: () -> Unit,
    onSalva: (BozzaSfida) -> Unit,
) {
    var b by remember { mutableStateOf(bozzaIniziale) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = if (id == null) "🎯 Nuova sfida" else "✏️ Modifica sfida",
                onIndietro = onAnnulla,
                azioni = {
                    Text(
                        text = "Salva",
                        color = if (b.valida) Palette.light else Palette.light.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = b.valida) { onSalva(b) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = b.titolo,
                onValueChange = { b = b.copy(titolo = it) },
                label = { Text("Titolo") },
                placeholder = { Text("es. 30 giorni senza zucchero") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = b.obiettivo,
                onValueChange = { b = b.copy(obiettivo = it) },
                label = { Text("Obiettivo") },
                placeholder = { Text("Cosa vuoi ottenere con questa sfida?") },
                modifier = Modifier.fillMaxWidth(),
            )

            Titoletto("Quando comincia e quanto dura")
            if (id == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Bottone(dataItaliana(b.inizio.toString())) {
                        scegliData(context, b.inizio) { b = b.copy(inizio = it) }
                    }
                }
                CampoNumero(b.durata, "Durata (giorni)") { b = b.copy(durata = it.coerceAtLeast(1)) }
            } else {
                Nota(
                    "${dataItaliana(b.inizio.toString())} · ${b.durata} giorni — " +
                        "data di inizio e durata non si cambiano dopo la creazione."
                )
            }
            Nota("Ultimo giorno: ${dataItaliana(b.fine.toString())}")

            Titoletto("Orario dell'avviso di check-in")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Bottone(b.ora) { scegliOra(context, b.ora) { b = b.copy(ora = it) } }
            }
            Nota("A quest'ora ti verrà chiesto se hai fatto la sfida oggi.")

            Titoletto("Punteggio in palio")
            CampoNumero(b.punti, "Punti totali") { b = b.copy(punti = it.coerceAtLeast(0)) }

            Tendina(
                etichetta = "Schema punteggio",
                scelto = SCHEMI.first { it.first == b.punteggio }.second,
                voci = SCHEMI,
            ) { scelta -> b = b.copy(punteggio = scelta) }
            Nota(
                if (b.punteggio == "proportional")
                    "Punti in base ai giorni riusciti."
                else "Punti pieni solo se riesci ogni giorno."
            )

            if (!b.valida) Nota("Manca il titolo.")
        }
    }
}

private val SCHEMI = listOf(
    "all_or_nothing" to "Tutto o niente",
    "proportional" to "Proporzionale",
)

// ── Selettori di sistema ─────────────────────────────────────────────────
//
// Quelli di Android come nel form dei task: già tradotti, già capaci di
// seguire l'ingrandimento dei caratteri, e sono quelli che l'utente conosce.

private fun scegliData(context: Context, iniziale: LocalDate, poi: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, anno, meseZeroBased, giorno -> poi(LocalDate.of(anno, meseZeroBased + 1, giorno)) },
        iniziale.year,
        iniziale.monthValue - 1,
        iniziale.dayOfMonth,
    ).show()
}

private fun scegliOra(context: Context, iniziale: String, poi: (String) -> Unit) {
    val ore = iniziale.take(2).toIntOrNull() ?: 20
    val minuti = iniziale.drop(3).toIntOrNull() ?: 0
    TimePickerDialog(
        context,
        { _, h, m -> poi("%02d:%02d".format(h, m)) },
        ore,
        minuti,
        true,
    ).show()
}

// ── Pezzi ────────────────────────────────────────────────────────────────

@Composable
private fun Titoletto(testo: String) {
    Text(
        text = testo,
        fontWeight = FontWeight.Bold,
        color = Palette.dark,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Nota(testo: String) {
    Text(text = testo, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Bottone(testo: String, onClick: () -> Unit) {
    Text(
        text = testo,
        color = Palette.light,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Viola)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * Un numero, tenuto come testo mentre lo si scrive: convertendo a ogni battuta,
 * cancellare l'ultima cifra darebbe uno 0 che ricompare nel campo e non si
 * riuscirebbe più a svuotarlo. Stesso pezzo del form dei task.
 */
@Composable
private fun CampoNumero(valore: Int, etichetta: String, onCambia: (Int) -> Unit) {
    var testo by remember(valore) { mutableStateOf(valore.toString()) }
    OutlinedTextField(
        value = testo,
        onValueChange = { nuovo ->
            val pulito = nuovo.filter { it.isDigit() }.take(5)
            testo = pulito
            pulito.toIntOrNull()?.let(onCambia)
        },
        label = { Text(etichetta) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

