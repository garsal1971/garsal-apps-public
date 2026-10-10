package com.garsal.appsphere.finanza

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.coloreDaHex
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// ── Formati ─────────────────────────────────────────────────────────────────

private val EUR = NumberFormat.getCurrencyInstance(Locale.ITALY)
private val EUR0 = NumberFormat.getCurrencyInstance(Locale.ITALY).apply { maximumFractionDigits = 0 }
private val NUM = NumberFormat.getNumberInstance(Locale.ITALY).apply { maximumFractionDigits = 4 }
private val DATA_IT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun eur(v: Double?) = v?.let { EUR.format(it) } ?: "—"
private fun eur0(v: Double?) = v?.let { EUR0.format(it) } ?: "—"
private fun pct(v: Double?) = v?.let { String.format(Locale.ITALY, "%+.2f %%", it) } ?: "—"
private fun dataIt(iso: String) = runCatching { LocalDate.parse(iso.take(10)).format(DATA_IT) }.getOrDefault(iso)
private val VERDE_FONDO = Color(0xFFDCFCE7)
private val VERDE_BORDO = Color(0xFF16A34A)

private fun coloreSegno(v: Double?) = when {
    v == null || v == 0.0 -> Palette.muted
    v > 0 -> Palette.success
    else -> Palette.danger
}

/** L'ora dell'ultimo aggiornamento dello snapshot, in ora locale. */
private fun ora(iso: String?): String? = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()
}

// ── Schermata ───────────────────────────────────────────────────────────────

@Composable
fun FinanzaScreen(onIndietro: () -> Unit, vm: FinanzaViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val aperto = s.portafoglioAperto?.let { id -> s.ultimo?.dettaglioPortafogli?.firstOrNull { it.id == id } }

    // Dal dettaglio di un portafoglio l'indietro torna all'elenco, non alla home.
    BackHandler(enabled = s.portafoglioAperto != null) { vm.apriPortafoglio(null) }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = aperto?.nome ?: "💰 Finanza",
                onIndietro = { if (s.portafoglioAperto != null) vm.apriPortafoglio(null) else onIndietro() },
                azioni = {
                    if (s.aggiornamento) {
                        CircularProgressIndicator(color = Palette.light, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            "⟳", color = Palette.light, fontSize = 22.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { vm.carica(rifaiSnapshot = true, conPrezzi = true) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFFF3F4F6))) {
            when {
                s.caricamento && s.ultimo == null ->
                    CircularProgressIndicator(color = Palette.topBar, modifier = Modifier.align(Alignment.Center))

                s.ultimo == null -> Text(
                    s.errore
                        ?: "Nessuno snapshot in archivio: aprite Finanza sul web una volta.",
                    color = Palette.muted, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                ) {
                    if (aperto == null) item { SchedeViste(s.vista, vm::vista) }
                    item { Intestazione(s) }
                    when {
                        aperto != null -> dettaglioPortafoglio(aperto, s, vm)
                        s.vista == VistaFinanza.DASHBOARD -> dashboard(s, vm)
                        s.vista == VistaFinanza.SVILUPPO -> sviluppo(s, vm)
                        else -> portafogli(s, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedeViste(attiva: VistaFinanza, onScegli: (VistaFinanza) -> Unit) {
    RigaScorrevole(Arrangement.spacedBy(8.dp)) {
        VistaFinanza.entries.forEach { v ->
            val sel = v == attiva
            Text(
                v.etichetta,
                color = if (sel) Palette.light else Palette.dark,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (sel) Palette.topBar else Palette.light)
                    .border(1.dp, if (sel) Palette.topBar else Palette.border, RoundedCornerShape(20.dp))
                    .clickable { onScegli(v) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Di che giorno sono i numeri, e se lo snapshot di oggi non si è potuto rifare. */
@Composable
private fun Intestazione(s: FinanzaState) {
    val u = s.ultimo ?: return
    val oggi = u.data == LocalDate.now().toString()
    Column {
        Text(
            buildString {
                append(if (oggi) "Snapshot di oggi" else "Snapshot del ${dataIt(u.data)}")
                ora(u.aggiornato)?.let { append(" · ore $it") }
                append(" · valori lordi")
            },
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
        )
        s.fase?.let { Text("⏳ $it", color = Palette.topBar, style = MaterialTheme.typography.bodySmall) }
        s.avvisi.forEach {
            Text("⚠️ $it", color = Palette.warning, style = MaterialTheme.typography.bodySmall)
        }
        s.errore?.let { Text("⚠️ $it", color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
    }
}

// ── Mattoni ─────────────────────────────────────────────────────────────────

@Composable
private fun Scheda(modifier: Modifier = Modifier, bordoColore: Color? = null, contenuto: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.light)
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (bordoColore != null) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(bordoColore))
        }
        contenuto()
    }
}

@Composable
private fun Titolo(t: String) = Text(t, fontWeight = FontWeight.Bold, color = Palette.dark, style = MaterialTheme.typography.titleMedium)

/** Etichetta a sinistra, valore a destra: coi caratteri grandi va a capo da sé, e i due restano leggibili. */
@Composable
private fun Riga(
    etichetta: String,
    valore: String,
    colore: Color = Palette.dark,
    grande: Boolean = false,
    sotto: String? = null,
    sottoColore: Color = Palette.muted,
    migliore: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(etichetta, color = Palette.muted, modifier = Modifier.weight(1f).padding(end = 8.dp))
        // Il migliore della colonna sta in un box verde, come `td.snap-top` nel web.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = if (!migliore) Modifier else Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(VERDE_FONDO)
                .border(2.dp, VERDE_BORDO, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                valore, color = colore, fontFamily = FontFamily.Monospace,
                fontWeight = if (grande) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = if (grande) 20.sp else 15.sp, textAlign = TextAlign.End,
            )
            sotto?.let { Text(it, color = sottoColore, fontSize = 12.sp, textAlign = TextAlign.End) }
        }
    }
}

/** La variazione rispetto allo snapshot precedente, come `deltaHtml` nel web. */
private fun variazione(ora: Double, prima: Double?): Pair<String, Color>? {
    if (prima == null) return null
    val d = ora - prima
    if (abs(d) < 0.5) return null
    val segno = if (d > 0) "▲ +" else "▼ −"
    return "$segno${EUR0.format(abs(d))} dal giorno prima" to coloreSegno(d)
}

@Composable
private fun Pallino(colore: String?) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(coloreDaHex(colore) ?: Palette.muted))
}

// ── 📊 Dashboard ────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.dashboard(s: FinanzaState, vm: FinanzaViewModel) {
    val u = s.ultimo ?: return
    val p = s.precedente
    item {
        Scheda {
            val vPn = variazione(u.patrimonioNetto, p?.patrimonioNetto)
            Riga("Patrimonio netto", eur(u.patrimonioNetto), Palette.dark, grande = true, sotto = vPn?.first, sottoColore = vPn?.second ?: Palette.muted)
            Riga("Asset totali", eur(u.asset), Palette.dark)
            Riga("Debiti totali", eur(u.debiti), Palette.danger)
        }
    }
    item {
        Scheda {
            Titolo("💼 Portafogli")
            val vP = variazione(u.portafogli, p?.portafogli)
            Riga("Valore totale (quota)", eur0(u.portafogli), Palette.success, grande = true, sotto = vP?.first, sottoColore = vP?.second ?: Palette.muted)
            if (u.dettaglioPortafogli.isEmpty()) Text("Nessun portafoglio.", color = Palette.muted)
            u.dettaglioPortafogli.forEach { pf ->
                val v = variazione(pf.valore, s.valorePrecedente(pf.id))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { vm.vista(VistaFinanza.PORTAFOGLI); vm.apriPortafoglio(pf.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pallino(pf.colore)
                    Text(pf.nome, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(eur0(pf.valore), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        v?.let { Text(it.first.removeSuffix(" dal giorno prima"), color = it.second, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    item {
        Scheda {
            Titolo("🏠 Prestiti e mutui")
            Riga("Debito residuo (quota)", eur(u.debiti), Palette.danger, grande = true)
            if (u.prestiti.isEmpty()) Text("Nessun prestito.", color = Palette.muted)
            u.prestiti.forEach { Riga(it.nome, eur(it.valore), Palette.danger) }
        }
    }
    item {
        Scheda {
            Titolo("💎 Altri asset")
            Riga("Valore stimato (quota)", eur(u.altriAssetTotale), Palette.success, grande = true)
            if (u.altriAsset.isEmpty()) Text("Nessun altro asset.", color = Palette.muted)
            u.altriAsset.forEach { Riga(it.nome, eur(it.valore), Palette.success) }
        }
    }
}

// ── 📈 Sviluppo ─────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.sviluppo(s: FinanzaState, vm: FinanzaViewModel) {
    item {
        Scheda {
            Titolo("Patrimonio netto nel tempo")
            Tendina(
                etichetta = "Periodo",
                scelto = s.periodoSviluppo.etichetta,
                voci = Periodo.entries.map { it.name to it.etichetta },
            ) { vm.periodoSviluppo(Periodo.valueOf(it)) }
            val punti = s.storicoDelPeriodo
            Grafico(punti.map { it.data to it.patrimonioNetto }, Palette.topBar)
        }
    }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Interruttore("📅 Uno per mese", s.soloMensili) { vm.soloMensili(true) }
            Interruttore("📋 Tutti", !s.soloMensili) { vm.soloMensili(false) }
            Spacer(Modifier.weight(1f))
        }
    }
    val top = s.migliori
    items(s.storicoMostrato, key = { it.data }) { sn ->
        Scheda {
            Text(dataIt(sn.data), fontWeight = FontWeight.Bold, color = Palette.dark)
            Riga("Patrimonio netto", eur(sn.patrimonioNetto), grande = true, migliore = sn.patrimonioNetto == top.patrimonio)
            Riga("Portafogli", eur(sn.portafogli), Palette.success, migliore = sn.portafogli == top.portafogli)
            Riga("Asset totali", eur(sn.asset), migliore = sn.asset == top.asset)
            Riga("Debiti totali", eur(sn.debiti), Palette.danger, migliore = sn.debiti == top.debiti)
        }
    }
}

@Composable
private fun Interruttore(testo: String, acceso: Boolean, onClick: () -> Unit) {
    Text(
        testo, maxLines = 1,
        color = if (acceso) Palette.light else Palette.dark, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (acceso) Palette.topBar else Palette.light)
            .border(1.dp, if (acceso) Palette.topBar else Palette.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Una linea sola, un punto per snapshot. ⚠️ L'asse x è **a indici e non a date**, come
 * nel web: un giorno senza snapshot non è un giorno a zero, e la linea unisce quelli
 * che ci sono. ⚠️ Un valore `null` (portafoglio che quel giorno non c'era) spezza la
 * linea invece di farla scendere a zero.
 */
@Composable
private fun Grafico(punti: List<Pair<String, Double?>>, colore: Color) {
    val validi = punti.mapNotNull { it.second }
    if (validi.size < 2) {
        Text(
            if (punti.isEmpty()) "Nessuno snapshot nel periodo." else "Servono almeno due giorni per una linea.",
            color = Palette.muted,
        )
        return
    }
    val min = validi.min()
    val max = validi.max()
    val campo = (max - min).takeIf { it > 0 } ?: 1.0
    Column {
        Text(eur0(max), color = Palette.muted, fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 4.dp)) {
            val passo = size.width / (punti.size - 1).coerceAtLeast(1)
            fun y(v: Double) = (size.height * (1 - (v - min) / campo)).toFloat()
            // la riga di base, tenue
            drawLine(Palette.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            val path = Path()
            var aperto = false
            punti.forEachIndexed { i, (_, v) ->
                if (v == null) { aperto = false; return@forEachIndexed }
                val x = i * passo
                if (!aperto) { path.moveTo(x, y(v)); aperto = true } else path.lineTo(x, y(v))
            }
            drawPath(path, colore, style = Stroke(width = 2.5.dp.toPx()))
            // Il pallino si stringe quando i giorni sono fitti, o coprirebbe la linea.
            val r = if (punti.size > 60) 1.5.dp.toPx() else 3.dp.toPx()
            punti.forEachIndexed { i, (_, v) -> if (v != null) drawCircle(colore, r, Offset(i * passo, y(v))) }
        }
        Text(eur0(min), color = Palette.muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth()) {
            Text(dataIt(punti.first().first), color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(dataIt(punti.last().first), color = Palette.muted, fontSize = 12.sp)
        }
        Text("${validi.size} giorni in archivio", color = Palette.muted, fontSize = 12.sp)
    }
}

// ── 💼 Portafogli ───────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.portafogli(s: FinanzaState, vm: FinanzaViewModel) {
    val elenco = s.ultimo?.dettaglioPortafogli.orEmpty()
    if (elenco.isEmpty()) item { Text("Nessun portafoglio.", color = Palette.muted) }
    items(elenco, key = { it.id }) { pf ->
        Scheda(Modifier.clickable { vm.apriPortafoglio(pf.id) }, bordoColore = coloreDaHex(pf.colore) ?: Palette.muted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pf.nome, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("→", color = Palette.muted)
            }
            Text("Possesso: ${NUM.format(pf.possessoPct)} %", color = Palette.muted, fontSize = 13.sp)
            val v = variazione(pf.valore, s.valorePrecedente(pf.id))
            Riga("Valore (quota)", eur(pf.valore), grande = true, sotto = v?.first, sottoColore = v?.second ?: Palette.muted)
            Riga("P&L", "${eur(pf.pnl)}  ${pct(pf.pnlPct)}", coloreSegno(pf.pnl))
            if ((pf.liquidita ?: 0.0) > 0.0) Riga("di cui liquidità", eur(pf.liquidita))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dettaglioPortafoglio(pf: PortafoglioSnap, s: FinanzaState, vm: FinanzaViewModel) {
    item {
        Scheda(bordoColore = coloreDaHex(pf.colore) ?: Palette.muted) {
            val v = variazione(pf.valore, s.valorePrecedente(pf.id))
            Riga("Valore portafoglio (quota)", eur(pf.valore), grande = true, sotto = v?.first, sottoColore = v?.second ?: Palette.muted)
            Riga("Liquidità", eur(pf.liquidita))
            Riga("Investito", eur(pf.costo))
            Riga("P&L", eur(pf.pnl), coloreSegno(pf.pnl), sotto = pct(pf.pnlPct), sottoColore = coloreSegno(pf.pnl))
            Text("Possesso: ${NUM.format(pf.possessoPct)} % · P&L sui soli titoli: la liquidità non guadagna né perde.",
                color = Palette.muted, fontSize = 12.sp)
        }
    }
    item {
        Scheda {
            Titolo("Andamento del valore (quota)")
            Tendina(
                etichetta = "Periodo",
                scelto = s.periodoPortafoglio.etichetta,
                voci = Periodo.entries.map { it.name to it.etichetta },
            ) { vm.periodoPortafoglio(Periodo.valueOf(it)) }
            val serie = s.serie
            when {
                serie == null -> CircularProgressIndicator(color = Palette.topBar, modifier = Modifier.size(24.dp))
                s.serieErrore != null -> Text("⚠️ ${s.serieErrore}", color = Palette.danger)
                else -> Grafico(
                    serie.map { sn -> sn.data to sn.dettaglioPortafogli.firstOrNull { it.id == pf.id }?.valore },
                    coloreDaHex(pf.colore) ?: Palette.topBar,
                )
            }
        }
    }
    item { Titolo("Posizioni (${pf.posizioni.size})") }
    if (pf.posizioni.isEmpty()) item { Text("Nessun titolo: solo liquidità.", color = Palette.muted) }
    items(pf.posizioni, key = { it.simbolo + it.nome }) { h ->
        Scheda {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.simbolo, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    if (h.nome != h.simbolo) Text(h.nome, color = Palette.muted, fontSize = 13.sp)
                }
                h.tipo?.let { Text(it, color = Palette.muted, fontSize = 12.sp) }
            }
            // ⚠️ Quantità e prezzi restano interi, solo gli importi vanno in quota — come il web.
            Riga("Quantità", h.quantita?.let { NUM.format(it) } ?: "—")
            Riga("Costo medio", eur(h.costoMedio))
            Riga("Prezzo", eur(h.prezzo))
            Riga("Valore (quota)", eur(h.valoreQuota), grande = true)
            Riga("P&L", eur(h.pnl), coloreSegno(h.pnl), sotto = pct(h.pnlPct), sottoColore = coloreSegno(h.pnl))
        }
    }
    item { Spacer(Modifier.width(1.dp).height(24.dp)) }
}
