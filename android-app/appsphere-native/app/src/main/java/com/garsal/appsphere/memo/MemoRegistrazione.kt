package com.garsal.appsphere.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * La raccolta delle misure di un diario — il modale `#entryModal` del web.
 *
 * ⚠️ **Una misura non toccata non finisce in `measures`**, e non ci finisce
 * come zero: «non l'ho misurata» e «vale zero» sono due cose diverse, e uno
 * slider lasciato a metà scriverebbe la seconda al posto della prima. Una
 * misura non toccata mostra `—`, e il pulsante *Non l'ho misurata* la toglie
 * anche a posteriori.
 *
 * ⚠️ **Il titolo è obbligatorio qui, non nel database**: l'elenco delle
 * registrazioni è fatto di date tutte uguali, e senza una riga di testo non si
 * ritrova più il giorno che si cerca. In `mm_diary_entries` la colonna resta
 * `NOT NULL DEFAULT ''`, perché le registrazioni fatte prima di esistere un
 * titolo non ce l'hanno e un vincolo le renderebbe impossibili perfino da
 * correggere.
 */
@Composable
fun MemoRegistrazione(
    scheda: MmScheda,
    misure: List<MmMisura>,
    registrazione: MmRegistrazione?,
    onAnnulla: () -> Unit,
    onSalva: (String?, String, String, String, Map<String, JsonPrimitive>) -> Unit,
) {
    var titolo by remember { mutableStateOf(registrazione?.titolo.orEmpty()) }
    var data by remember {
        mutableStateOf(
            registrazione?.data?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()
        )
    }
    var nota by remember { mutableStateOf(registrazione?.nota.orEmpty()) }
    var avviso by remember { mutableStateOf<String?>(null) }

    // I valori raccolti. Una chiave assente = misura non registrata: è la
    // rappresentazione, non un caso da gestire dopo.
    var valori by remember {
        mutableStateOf(
            registrazione?.misure
                ?.filterKeys { id -> misure.any { it.id == id } }
                .orEmpty()
        )
    }

    fun scrivi(misura: MmMisura, valore: JsonPrimitive?) {
        valori = if (valore == null) valori - misura.id else valori + (misura.id to valore)
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = if (registrazione == null) "Nuova registrazione"
                else "Modifica registrazione",
                onIndietro = onAnnulla,
                azioni = {
                    Text(
                        text = "Registra",
                        color = Palette.light,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                when {
                                    titolo.isBlank() ->
                                        avviso = "Dai un titolo a questa registrazione."
                                    data.isBlank() -> avviso = "Scegli una data."
                                    valori.isEmpty() && nota.isBlank() ->
                                        avviso = "Registra almeno una misura o scrivi un commento."
                                    else -> onSalva(
                                        registrazione?.id, titolo.trim(), data, nota.trim(), valori,
                                    )
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            avviso?.let { messaggio ->
                Text(
                    text = messaggio,
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.inputBg)
                        .clickable { avviso = null }
                        .padding(10.dp),
                )
            }

            // Lo scopo del diario si rilegge qui: è il promemoria di come vanno
            // lette le misure, e senza si finisce a dare voti a caso dopo un mese.
            if (scheda.anteprima.isNotBlank()) {
                Text(
                    text = remember(scheda.contenuto) {
                        runCatching { AnnotatedString.fromHtml(scheda.contenuto) }
                            .getOrElse { AnnotatedString(scheda.anteprima) }
                    },
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.inputBg)
                        .padding(12.dp),
                )
            }

            OutlinedTextField(
                value = titolo,
                onValueChange = { titolo = it },
                label = { Text("Titolo breve") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = data,
                onValueChange = { data = it },
                label = { Text("Data (AAAA-MM-GG)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            misure.forEach { misura ->
                Misura(
                    misura = misura,
                    valore = valori[misura.id],
                    onScrivi = { scrivi(misura, it) },
                )
            }

            OutlinedTextField(
                value = nota,
                onValueChange = { nota = it },
                label = { Text("Commento") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
        }
    }
}

/** Il riquadro di una misura al momento di registrarla. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Misura(
    misura: MmMisura,
    valore: JsonPrimitive?,
    onScrivi: (JsonPrimitive?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.inputBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = misura.nome,
                color = Palette.dark,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = misura.etichettaValore(valore),
                color = if (valore == null) Palette.muted else BluMemo,
                fontWeight = FontWeight.Bold,
            )
        }

        // La nota della misura ricompare **qui**, sotto il nome, che è l'unico
        // momento in cui serve davvero.
        if (misura.nota.isNotBlank()) {
            Text(
                text = misura.nota,
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when (misura.tipo) {
            TipoMisura.SCALA -> {
                val minimo = (misura.minimo ?: 1.0).toFloat()
                val massimo = (misura.massimo ?: 10.0).toFloat()
                val corrente = misura.numerico(valore)?.toFloat()
                    ?: ((minimo + massimo) / 2f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(numero(minimo.toDouble()), color = Palette.muted, fontSize = 12.sp)
                    Slider(
                        value = corrente.coerceIn(minimo, massimo),
                        onValueChange = { onScrivi(intero(it.roundToLong())) },
                        valueRange = minimo..massimo,
                        // Uno scatto per unità: una scala 1-20 non ha mezzi punti.
                        steps = (massimo - minimo).toInt().minus(1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(numero(massimo.toDouble()), color = Palette.muted, fontSize = 12.sp)
                }
            }

            TipoMisura.NUMERO -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = misura.numerico(valore)?.let { numero(it) } ?: "",
                    onValueChange = { testo ->
                        val n = testo.replace(',', '.').toDoubleOrNull()
                        onScrivi(if (testo.isBlank() || n == null) null else decimale(n))
                    },
                    label = { Text("Valore") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (misura.unita.isNotBlank()) {
                    Text(
                        text = misura.unita,
                        color = Palette.muted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            TipoMisura.BOOL -> {
                val si = valore?.content?.toBooleanStrictOrNull() == true
                val no = valore?.content?.toBooleanStrictOrNull() == false
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Ripremendo si annulla: è l'unico modo per tornare a «non
                    // l'ho misurata» senza cercare il pulsante qui sotto.
                    Etichetta("Sì", attiva = si, colore = Palette.success) {
                        onScrivi(if (si) null else JsonPrimitive(true))
                    }
                    Etichetta("No", attiva = no, colore = Palette.danger) {
                        onScrivi(if (no) null else JsonPrimitive(false))
                    }
                }
            }

            TipoMisura.SCELTA -> {
                // Le opzioni sono pulsanti e non una tendina: coi caratteri di
                // sistema grandi una tendina taglia le etichette proprio dove
                // la scelta si fa. Si archivia l'**id**, mai l'etichetta.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    misura.opzioni.forEach { opzione ->
                        val scelta = valore?.content == opzione.id
                        Etichetta(opzione.etichetta, attiva = scelta) {
                            onScrivi(if (scelta) null else JsonPrimitive(opzione.id))
                        }
                    }
                }
            }
        }

        if (valore != null) {
            Etichetta("Non l'ho misurata", attiva = false) { onScrivi(null) }
        }
    }
}

/**
 * Un intero come intero, non come `7.0`: quello che finisce nel jsonb è quello
 * che il web ci scriverebbe, e le due implementazioni leggono lo stesso storico.
 */
private fun intero(n: Long) = JsonPrimitive(n)

/** Un numero libero tiene i decimali, ma perde lo `.0` quando è tondo. */
private fun decimale(n: Double): JsonPrimitive =
    if (n == Math.floor(n) && !n.isInfinite()) JsonPrimitive(n.toLong()) else JsonPrimitive(n)
