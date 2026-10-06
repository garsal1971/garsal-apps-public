package com.garsal.appsphere.abituati

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.coloreDaHex
import com.garsal.appsphere.core.larghezzaPulsanti
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ════════════════════════════════════════════════════════════════════════════
// Le pagine di `habit-tracker.html` che il nativo non aveva: 📊 Statistiche,
// 🏷️ Categorie, ⚙️ Impostazioni, e lo storico di finestre e settimane.
//
// ⚠️ Nessuna regola nuova: le statistiche sono le stesse somme di
// `renderStats()` sui dati già letti, e lo storico disegna le righe che
// `hb_reconcile` ha già scritto. Chi decide resta il database.
// ════════════════════════════════════════════════════════════════════════════

// ── 📊 Statistiche ──────────────────────────────────────────────────────────

/** Gemella di `renderStats()`: quattro numeri, l'andamento mensile, la classifica. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Statistiche(stato: AbituatiState) {
    val mesi = stato.andamentoMensile()
    val massimo = (mesi.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // ⚠️ Riquadri che vanno a capo con un'altezza minima comune, non
            // fissa: coi caratteri grandi «Completamenti Totali» va a capo.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Kpi("${stato.completamentiTotali}", "Completamenti totali")
                Kpi("${stato.streakMedia}", "Streak media")
                Kpi("${stato.attive}", "Abitudini attive")
                Kpi("${stato.categorie.size}", "Categorie")
            }
        }

        item {
            Riquadro("Andamento mensile") {
                // Le colonne si disegnano con dei Box, come le `.chart-bar` del web.
                Row(
                    Modifier.fillMaxWidth().height(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    mesi.forEach { (mese, quante) ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Text("$quante", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((110.dp * (quante.toFloat() / massimo)).coerceAtLeast(2.dp))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(NeroAbituati),
                            )
                            Text(
                                mese.month.getDisplayName(TextStyle.SHORT, Locale.ITALIAN),
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        item {
            Riquadro("Classifica abitudini") {
                if (stato.classifica.isEmpty()) {
                    Text("Nessuna abitudine disponibile", color = Palette.muted)
                }
                stato.classifica.forEachIndexed { i, a ->
                    val medaglia = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}." }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(medaglia, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Column(Modifier.weight(1f)) {
                            Text(a.nome, fontWeight = FontWeight.Bold, color = Palette.dark)
                            stato.categoria(a.categoriaId)?.let {
                                Text(it.etichetta, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${stato.streakDi(a)}", fontWeight = FontWeight.Black, color = Palette.primary, fontSize = 20.sp)
                            Text("di fila", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Kpi(valore: String, etichetta: String) {
    Column(
        Modifier
            .width(150.dp)
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.cardBg)
            .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(valore, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Palette.dark)
        Text(etichetta, color = Palette.muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Riquadro(titolo: String, contenuto: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.cardBg)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titolo, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            contenuto()
        }
    }
}

// ── 🏷️ Categorie ────────────────────────────────────────────────────────────

/**
 * Gemella di `renderCategories()`: le categorie ordinate per quante abitudini
 * attive le usano, con 📋 Vedi abitudini, ✏️ Modifica e 🗑 Elimina — quest'ultimo
 * **solo se nessuna abitudine attiva la usa**, come nel web.
 *
 * ⚠️ `cm_categories` è **condivisa con Tasks**: cambiare qui nome o colore li
 * cambia anche ai task, e l'avviso in cima lo dice.
 */
@Composable
internal fun Categorie(
    stato: AbituatiState,
    onModifica: (HbCategoria) -> Unit,
    onElimina: (HbCategoria) -> Unit,
) {
    var vedi by remember { mutableStateOf<HbCategoria?>(null) }
    val elenco = stato.categorie.sortedByDescending { stato.abitudiniDi(it.id).size }
    val etichette = listOf("📋 Vedi abitudini", "✏️ Modifica", "🗑 Elimina")
    val larghezza = larghezzaPulsanti(etichette)

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Le categorie sono le stesse di Tasks: cambiandole qui cambiano anche là.",
                color = Palette.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (elenco.isEmpty()) item { Text("Nessuna categoria. Creane una col +", color = Palette.muted) }
        items(elenco, key = { it.id }) { cat ->
            val n = stato.abitudiniDi(cat.id).size
            val colore = coloreDaHex(cat.colore) ?: Palette.muted
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.cardBg)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(44.dp).background(colore),
                            contentAlignment = Alignment.Center,
                        ) { Text(cat.icona.ifBlank { "📌" }, fontSize = 20.sp) }
                        Column(Modifier.weight(1f)) {
                            Text(cat.nome, fontWeight = FontWeight.Bold, color = Palette.dark)
                            Text("$n ${if (n == 1) "abitudine" else "abitudini"}", color = Palette.muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                        Pillola(etichette[0], Palette.accent, larghezza) { vedi = cat }
                        Pillola(etichette[1], Palette.primary, larghezza) { onModifica(cat) }
                        if (n == 0) Pillola(etichette[2], Palette.danger, larghezza) { onElimina(cat) }
                    }
                }
            }
        }
    }

    vedi?.let { cat ->
        val abitudini = stato.abitudiniDi(cat.id)
        AlertDialog(
            onDismissRequest = { vedi = null },
            title = { Text(cat.etichetta) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (abitudini.isEmpty()) Text("Nessuna abitudine attiva in questa categoria.", color = Palette.muted)
                    abitudini.forEach { a -> Text("• ${a.nome} — 🔥 ${stato.streakDi(a)}") }
                }
            },
            confirmButton = { TextButton(onClick = { vedi = null }) { Text("Chiudi") } },
        )
    }
}

/** I sei colori proposti dal modale del web. */
private val COLORI_CATEGORIA = listOf("#FF3366", "#6C5CE7", "#00B894", "#F39C12", "#E74C3C", "#2563EB")

/** Il modale «Nuova / Modifica categoria»: nome, icona, colore. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormCategoria(
    categoria: HbCategoria?,
    onAnnulla: () -> Unit,
    onSalva: (String, String, String) -> Unit,
) {
    var nome by remember { mutableStateOf(categoria?.nome.orEmpty()) }
    var icona by remember { mutableStateOf(categoria?.icona.orEmpty()) }
    var colore by remember { mutableStateOf(categoria?.colore ?: COLORI_CATEGORIA.first()) }
    // Un colore già scritto che non è fra i sei resta sceglibile, o salvando lo si perderebbe.
    val colori = (COLORI_CATEGORIA + listOfNotNull(categoria?.colore)).distinctBy { it.uppercase() }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(if (categoria == null) "Nuova categoria" else "Modifica categoria") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(nome, { nome = it }, label = { Text("Nome *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(icona, { icona = it.take(8) }, label = { Text("Icona (emoji)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Colore", color = Palette.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    colori.forEach { hex ->
                        val scelto = hex.equals(colore, ignoreCase = true)
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(coloreDaHex(hex) ?: Palette.muted)
                                .border(if (scelto) 4.dp else 0.dp, Palette.dark, CircleShape)
                                .clickable { colore = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = nome.isNotBlank(), onClick = { onSalva(nome, icona, colore) }) {
                Text("Salva", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla", color = Palette.muted) } },
    )
}

// ── ⚙️ Impostazioni ─────────────────────────────────────────────────────────

/** Gemella della «🐛 Console Debug» del web: i contatori e il registro delle operazioni. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Impostazioni(stato: AbituatiState, onAggiorna: () -> Unit, onSvuota: () -> Unit) {
    val oggi = LocalDate.now().toString()
    val contatori = listOf(
        "Abitudini" to "${stato.abitudini.size}",
        "Attive" to "${stato.attive}",
        "Interrotti" to "${stato.abitudini.count { it.stato == "stopped" }}",
        "Categorie" to "${stato.categorie.size}",
        "Completamenti" to "${stato.spunte.size}",
        "Oggi" to "${stato.spunte.count { it.quando.startsWith(oggi) }}",
        "Promemoria" to "${stato.regole.map { it.abitudineId }.distinct().size}",
        "Logs" to "${stato.registro.size}",
    )
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Riquadro("🐛 Console debug") {
                RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                    Pillola("🔄 Aggiorna", Palette.secondary) { onAggiorna() }
                    Pillola("🗑 Svuota", Palette.accent) { onSvuota() }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    contatori.forEach { (k, v) ->
                        Text(
                            "$k: $v",
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.inputBg)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Color(0xFF0A0A0A)).padding(8.dp),
                ) {
                    if (stato.registro.isEmpty()) Text("(vuoto)", color = Color(0xFF00FF00), fontSize = 11.sp)
                    stato.registro.forEach { Text(it, color = Color(0xFF00FF00), fontSize = 11.sp) }
                }
            }
        }
    }
}

// ── 📜 Lo storico di finestre e settimane ───────────────────────────────────

/**
 * TUTTE le finestre di una «N volte in M giorni», in una riga che scorre di lato
 * e si apre su quella in corso — la `renderCountWindowStack()` del web.
 *
 * ⚠️ Sta in 📋 Tutte e non in 🎯 Oggi, di proposito: là si risponde a «cosa
 * faccio adesso» e c'è la sola finestra in corso, coi suoi ＋ e −. Qui si
 * guarda com'è andata, quindi **niente pulsanti**, e tornano i **pallini rossi**
 * delle finestre chiuse (i mancanti fino a N, solo a finestra chiusa: prima non
 * sono mancate, sono cose che si possono ancora fare).
 */
@Composable
internal fun StoricoFinestre(abitudine: HbAbitudine, stato: AbituatiState, oggi: LocalDate) {
    val quante = abitudine.obiettivo.coerceAtLeast(1)
    val corrente = abitudine.finestraDi(oggi).coerceIn(0, quante - 1)
    val lista = rememberLazyListState(initialFirstVisibleItemIndex = corrente)
    LazyRow(state = lista, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0 until quante).toList()) { k -> SchedaFinestra(abitudine, stato, oggi, k) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedaFinestra(abitudine: HbAbitudine, stato: AbituatiState, oggi: LocalDate, k: Int) {
    val inizio = abitudine.inizioFinestra(k) ?: return
    val fine = inizio.plusDays((abitudine.m - 1).toLong())
    val passata = fine.isBefore(oggi)
    val inCorso = !inizio.isAfter(oggi) && !fine.isBefore(oggi)
    val fatte = stato.fatteNellaFinestra(abitudine, inizio)
    val mancanti = if (passata) (abitudine.n - fatte).coerceAtLeast(0) else 0
    val bordo = when {
        fatte >= abitudine.n -> Palette.success
        passata -> Palette.danger
        inCorso -> NeroAbituati
        else -> Palette.border
    }
    Column(
        Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, bordo, RoundedCornerShape(10.dp))
            .background(Palette.inputBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("FINESTRA ${k + 1}" + if (inCorso) " · ora" else "", color = Palette.muted,
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text("${giornoMese(inizio)} → ${giornoMese(fine)} · $fatte/${abitudine.n}",
            color = Palette.dark, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(abitudine.caselle) { i ->
                val colore = when {
                    i < fatte -> Palette.success
                    i < fatte + mancanti -> Palette.danger
                    else -> Palette.light
                }
                Box(Modifier.size(18.dp).clip(CircleShape).background(colore).border(1.dp, Palette.border, CircleShape))
            }
        }
        if (mancanti > 0) {
            Text("$mancanti non fatt${if (mancanti == 1) "a" else "e"}", color = Palette.danger,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * TUTTE le settimane di una settimanale, dal lunedì della settimana che
 * contiene l'inizio per `obiettivo` settimane — la `renderWeeklyHabitStack()`
 * del web — coi sette pallini e gli stessi colori di `SettimanaPallini`.
 * Sola lettura, per la stessa ragione di [StoricoFinestre].
 */
@Composable
internal fun StoricoSettimane(abitudine: HbAbitudine, stato: AbituatiState, oggi: LocalDate) {
    val inizio = abitudine.giornoInizio ?: return
    val primoLunedi = inizio.minusDays((inizio.dayOfWeek.value - 1).toLong())
    val quante = abitudine.obiettivo.coerceAtLeast(1)
    val lunediOggi = oggi.minusDays((oggi.dayOfWeek.value - 1).toLong())
    val corrente = (java.time.temporal.ChronoUnit.WEEKS.between(primoLunedi, lunediOggi).toInt()).coerceIn(0, quante - 1)
    val lista = rememberLazyListState(initialFirstVisibleItemIndex = corrente)
    val iniziali = listOf("L", "M", "M", "G", "V", "S", "D")

    LazyRow(state = lista, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed((0 until quante).toList()) { _, i ->
            val lunedi = primoLunedi.plusWeeks(i.toLong())
            val questa = lunedi == lunediOggi
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, if (questa) NeroAbituati else Palette.border, RoundedCornerShape(10.dp))
                    .background(Palette.inputBg)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("SETTIMANA ${i + 1}" + if (questa) " · ora" else "", color = Palette.muted,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text("dal ${giornoMese(lunedi)}", color = Palette.dark, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(7) { d ->
                        val giorno = lunedi.plusDays(d.toLong())
                        val previsto = (giorno.dayOfWeek.value % 7) in abitudine.giorniSettimana
                        val stat = if (!previsto || giorno.isBefore(inizio)) "off"
                        else (stato.statoDi(abitudine.id, giorno) ?: "todo")
                        val riempimento = when (stat) {
                            "off" -> GrigioNonPrevisto
                            "completed" -> Palette.success
                            "failed" -> BluJolly
                            "missed" -> Palette.danger
                            else -> Palette.light
                        }
                        val bordo = if (riempimento == Palette.light) Palette.muted else riempimento
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(riempimento).border(2.dp, bordo, CircleShape))
                            Text(iniziali[d], color = if (giorno == oggi) Palette.dark else Palette.muted,
                                fontWeight = if (giorno == oggi) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
