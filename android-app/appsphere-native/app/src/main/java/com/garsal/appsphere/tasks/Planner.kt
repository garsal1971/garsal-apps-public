package com.garsal.appsphere.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.coloreDaHex
import java.time.LocalDate
import java.time.temporal.WeekFields

/**
 * Le due viste del planner: **mese** e **settimana**.
 *
 * ⚠️ **Non sono la copia pixel per pixel di `renderMonthView` e
 * `renderWeekView`**, e non possono esserlo: quelle disegnano i titoli dei
 * task dentro le celle di una griglia 7×6 e sette colonne larghe 150 px con
 * scorrimento orizzontale. Su questo telefono, con i caratteri di sistema
 * ingranditi, un titolo dentro una cella di calendario è illeggibile prima
 * ancora di essere tagliato.
 *
 * Cosa si è tenuto identico: **quali** task cadono in un giorno
 * (`TsTask.cadeIl`, colonna per colonna come `getTasksForDate`), lo storico
 * per i giorni passati, la settimana che parte di lunedì, le sei righe del
 * mese e le quattro fasce orarie 00-08 / 08-14 / 14-20 / 20-24.
 *
 * Cosa è cambiato, e perché:
 * - nel **mese** la cella mostra il numero del giorno e dei pallini colorati,
 *   uno per task; il titolo si legge toccando il giorno. Tre pallini stanno
 *   in una cella a qualunque ingrandimento, tre titoli no;
 * - la **settimana** è un elenco verticale di sette giorni invece di sette
 *   colonne affiancate. Colonne di testo affiancate sono esattamente la cosa
 *   che si sfilaccia coi caratteri grandi, e sarebbero da scorrere in
 *   orizzontale per leggerne una alla volta.
 */

private val NOMI_MESE = listOf(
    "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
    "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
)

private val INIZIALI_GIORNI = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")

private val NOMI_GIORNI = listOf(
    "Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica",
)

/** Le stesse quattro di `renderWeekView`. */
private val FASCE = listOf(
    Triple(0, 8, "00-08"),
    Triple(8, 14, "08-14"),
    Triple(14, 20, "14-20"),
    Triple(20, 24, "20-24"),
)

/** Il lunedì della settimana che contiene [giorno], come `getStartOfWeek`. */
fun lunediDi(giorno: LocalDate): LocalDate =
    giorno.minusDays(((giorno.dayOfWeek.value + 6) % 7).toLong())

private fun coloreDelTask(voce: VoceGiorno, stato: TasksState): Color {
    val task = voce.task ?: return Palette.muted
    // La prima categoria che ha un colore, come `categories.find(...)` nel web.
    val categoria = task.categorie.firstNotNullOfOrNull { stato.categoriaDi(it) }
    return coloreDaHex(categoria?.colore) ?: Palette.primary
}

// ── Barra di navigazione del periodo ─────────────────────────────────────

@Composable
fun BarraPeriodo(etichetta: String, onIndietro: () -> Unit, onAvanti: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FrecciaPeriodo("‹", onIndietro)
        Text(
            text = etichetta,
            fontWeight = FontWeight.Bold,
            color = Palette.dark,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        FrecciaPeriodo("›", onAvanti)
    }
}

@Composable
private fun FrecciaPeriodo(segno: String, onClick: () -> Unit) {
    Text(
        text = segno,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        color = Palette.topBar,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

fun etichettaMese(mese: LocalDate): String =
    "${NOMI_MESE[mese.monthValue - 1]} ${mese.year}"

fun etichettaSettimana(giorno: LocalDate): String {
    val lunedi = lunediDi(giorno)
    val domenica = lunedi.plusDays(6)
    val settimana = lunedi.get(WeekFields.ISO.weekOfWeekBasedYear())
    return "%02d/%02d – %02d/%02d · sett. %d".format(
        lunedi.dayOfMonth, lunedi.monthValue, domenica.dayOfMonth, domenica.monthValue, settimana
    )
}

// ── Vista mese ───────────────────────────────────────────────────────────

@Composable
fun VistaMese(
    mese: LocalDate,
    stato: TasksState,
    onGiorno: (LocalDate) -> Unit,
) {
    val primo = mese.withDayOfMonth(1)
    val partenza = lunediDi(primo)
    // Sei righe fisse come nel web: così l'altezza del mese non cambia
    // passando da un mese di cinque settimane a uno di sei.
    val settimane = (0 until 6).map { riga ->
        (0 until 7).map { colonna -> partenza.plusDays((riga * 7 + colonna).toLong()) }
    }
    val oggi = LocalDate.now()

    // Scorrevole: sei righe di celle che crescono coi caratteri di sistema
    // possono non entrare in altezza, e una griglia tagliata in fondo
    // nasconderebbe l'ultima settimana del mese senza dire niente.
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            INIZIALI_GIORNI.forEach { nome ->
                Text(
                    text = nome,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
            }
        }

        settimane.forEach { settimana ->
            Row(Modifier.fillMaxWidth()) {
                settimana.forEach { giorno ->
                    CellaGiorno(
                        giorno = giorno,
                        voci = stato.vociDel(giorno),
                        stato = stato,
                        delMese = giorno.monthValue == mese.monthValue,
                        oggi = giorno == oggi,
                        modifier = Modifier.weight(1f),
                        onClick = { onGiorno(giorno) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CellaGiorno(
    giorno: LocalDate,
    voci: List<VoceGiorno>,
    stato: TasksState,
    delMese: Boolean,
    oggi: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .padding(1.dp)
            // `heightIn` e non `height`: la cella cresce se i pallini vanno a
            // capo o se il numero del giorno diventa più alto.
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (oggi) Palette.primary.copy(alpha = 0.10f) else Palette.inputBg)
            .then(
                if (oggi) Modifier.border(1.5.dp, Palette.primary, RoundedCornerShape(6.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${giorno.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (oggi) FontWeight.Bold else FontWeight.Normal,
            color = when {
                oggi -> Palette.primary
                delMese -> Palette.dark
                // I giorni del mese prima e dopo restano visibili ma smorzati,
                // come `.other-month` nel CSS.
                else -> Palette.muted.copy(alpha = 0.45f)
            },
        )
        if (voci.isNotEmpty()) {
            Pallini(voci, stato)
        }
    }
}

/**
 * Fino a tre pallini, poi il numero di quelli che restano.
 *
 * I pallini sono `Box` con una misura in `dp`: qui è voluto e non contraddice
 * la regola sulle altezze fisse, perché non c'è testo dentro — un pallino non
 * ha niente da tagliare.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pallini(voci: List<VoceGiorno>, stato: TasksState) {
    FlowRow(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        voci.take(3).forEach { voce ->
            Box(
                Modifier
                    .padding(1.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        coloreDelTask(voce, stato)
                            .copy(alpha = if (voce.completato) 0.35f else 1f)
                    )
            )
        }
        if (voci.size > 3) {
            Text(
                text = "+${voci.size - 3}",
                fontSize = 8.sp,
                color = Palette.muted,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

// ── Vista settimana ──────────────────────────────────────────────────────

@Composable
fun VistaSettimana(
    giornoNellaSettimana: LocalDate,
    stato: TasksState,
    onTocca: (TsTask) -> Unit,
) {
    val lunedi = lunediDi(giornoNellaSettimana)
    val oggi = LocalDate.now()

    LazyColumn(
        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(7) { indice ->
            val giorno = lunedi.plusDays(indice.toLong())
            GiornoDellaSettimana(
                nome = NOMI_GIORNI[indice],
                giorno = giorno,
                voci = stato.vociDel(giorno),
                stato = stato,
                oggi = giorno == oggi,
                onTocca = onTocca,
            )
        }
    }
}

@Composable
private fun GiornoDellaSettimana(
    nome: String,
    giorno: LocalDate,
    voci: List<VoceGiorno>,
    stato: TasksState,
    oggi: Boolean,
    onTocca: (TsTask) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (oggi) Palette.primary.copy(alpha = 0.08f) else Palette.inputBg)
            .padding(12.dp),
    ) {
        Text(
            text = "$nome ${giorno.dayOfMonth}/${giorno.monthValue}",
            fontWeight = FontWeight.Bold,
            color = if (oggi) Palette.primary else Palette.dark,
        )

        if (voci.isEmpty()) {
            Text(
                "Niente in programma.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }

        // Solo le fasce che hanno qualcosa: quattro intestazioni di cui tre
        // vuote, moltiplicate per sette giorni, sarebbero mezzo schermo di
        // etichette senza contenuto.
        FASCE.forEach { (da, a, etichetta) ->
            val dellaFascia = voci.filter { it.ora >= da && it.ora < a }
            if (dellaFascia.isEmpty()) return@forEach
            Text(
                text = etichetta,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.muted,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            dellaFascia.forEach { voce ->
                RigaVoce(voce, coloreDelTask(voce, stato), onTocca)
            }
        }
    }
}

/**
 * Una voce del giorno. Si può toccare solo se dietro c'è un task vivo: le
 * righe che vengono dal solo storico raccontano una cosa già fatta, su cui
 * non c'è nessuna azione da offrire.
 */
@Composable
fun RigaVoce(voce: VoceGiorno, colore: Color, onTocca: (TsTask) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colore.copy(alpha = if (voce.completato) 0.35f else 1f))
            .then(
                voce.task?.let { t -> Modifier.clickable { onTocca(t) } } ?: Modifier
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = voce.titolo,
            color = Palette.light,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (voce.completato) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (voce.ora > 0) {
            Text(
                text = "%02d".format(voce.ora),
                color = Palette.light.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
