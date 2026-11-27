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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.TendinaFacoltativa
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
// Le quantità crypto arrivano all'ottavo decimale (1 satoshi).
private val QTA = NumberFormat.getNumberInstance(Locale.ITALY).apply { maximumFractionDigits = 8 }
private val QTA_CASELLA = NumberFormat.getNumberInstance(Locale.ITALY).apply { maximumFractionDigits = 8; isGroupingUsed = false }
private val DATA_IT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// ── $ Importi nascosti ──────────────────────────────────────────────────────
// Il FAB $ in basso a destra nasconde ogni importo in €, come il FAB di finanza.html
// (v1.32): percentuali, quantità e date restano leggibili. Qui gli importi passano tutti da
// eur()/eur0()/variazione(), quindi basta che leggano lo stato: è uno stato Compose, e chi li
// chiama si ridisegna da sé. Si mascherano e non si sfocano: Modifier.blur c'è solo da
// Android 12, e su un telefono più vecchio un «nascosto» che si legge benissimo è il peggio.
// La scelta resta su questo telefono (preferenze `finanza`, chiave `offusca`), indipendente
// da quella del browser.
private const val MASCHERA = "••••• €"
private var nascondiImporti by mutableStateOf(false)

private fun eur(v: Double?) = v?.let { if (nascondiImporti) MASCHERA else EUR.format(it) } ?: "—"
private fun eur0(v: Double?) = v?.let { if (nascondiImporti) MASCHERA else EUR0.format(it) } ?: "—"
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
    // ✏️ La finestra della quantità: (portafoglio, moneta già scelta o null).
    var dialogo by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var daEliminare by remember { mutableStateOf<SaldoRiga?>(null) }

    dialogo?.let { (pf, prod) ->
        DialogoSaldo(s, pf, prod, onAnnulla = { dialogo = null }) { prodotto, q, nota ->
            vm.salvaSaldo(pf, prodotto, q, nota) { dialogo = null }
        }
    }
    daEliminare?.let { r ->
        val m = s.monete.firstOrNull { it.id == r.prodotto }
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare l'aggiornamento?") },
            text = { Text("${m?.simbolo ?: "Moneta"} · ${QTA.format(r.quantita)}. Per quella moneta tornerà a valere la quantità precedente.") },
            confirmButton = { TextButton({ vm.eliminaSaldo(r.id); daEliminare = null }) { Text("Elimina", color = Palette.danger) } },
            dismissButton = { TextButton({ daEliminare = null }) { Text("Annulla") } },
        )
    }

    val prefs = LocalContext.current.getSharedPreferences("finanza", android.content.Context.MODE_PRIVATE)
    // Si legge PRIMA del primo disegno (non in un LaunchedEffect): altrimenti per un istante
    // gli importi comparirebbero in chiaro proprio a chi aveva chiesto di nasconderli.
    remember(prefs) { nascondiImporti = prefs.getBoolean("offusca", false); Unit }

    // Dal dettaglio di un portafoglio l'indietro torna all'elenco, non alla home.
    BackHandler(enabled = s.portafoglioAperto != null) { vm.apriPortafoglio(null) }
    // Dal titolo si torna al Σ cumulativo, da lì all'elenco.
    BackHandler(enabled = s.portafoglioAperto == null && s.cumulAperto) {
        if (s.titoloCumul != null) vm.apriTitoloCumul(null) else vm.apriCumulativo(false)
    }
    val inCumul = s.portafoglioAperto == null && s.cumulAperto

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = aperto?.nome ?: if (inCumul) (s.titoloCumul ?: "Σ Cumulativo") else "💰 Finanza",
                onIndietro = {
                    when {
                        s.portafoglioAperto != null -> vm.apriPortafoglio(null)
                        inCumul && s.titoloCumul != null -> vm.apriTitoloCumul(null)
                        inCumul -> vm.apriCumulativo(false)
                        else -> onIndietro()
                    }
                },
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    nascondiImporti = !nascondiImporti
                    prefs.edit().putBoolean("offusca", nascondiImporti).apply()
                },
                containerColor = if (nascondiImporti) Color(0xFF374151) else Palette.topBar,
                contentColor = Palette.light,
            ) { SimboloDollaro(barrato = nascondiImporti) }
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
                    // In fondo c'è posto per il FAB $, che altrimenti coprirebbe l'ultima riga.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
                ) {
                    if (aperto == null && !inCumul) item { SchedeViste(s.vista, vm::vista) }
                    item { Intestazione(s) }
                    when {
                        aperto != null -> dettaglioPortafoglio(aperto, s, vm,
                            onAggiorna = { prod -> dialogo = aperto.id to prod },
                            onElimina = { daEliminare = it })
                        inCumul && s.titoloCumul != null -> titoloCumulativo(s, vm)
                        inCumul -> cumulativo(s, vm)
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

/** $ = importi in chiaro, $ barrato = nascosti (🙈 è già della modalità nascosta). Gemello del FAB di finanza.html. */
@Composable
private fun SimboloDollaro(barrato: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
        Text("$", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.light)
        if (barrato) Canvas(Modifier.size(26.dp)) {
            drawLine(Color(0xFFFCA5A5), Offset(size.width * 0.12f, size.height * 0.12f),
                Offset(size.width * 0.88f, size.height * 0.88f), strokeWidth = 2.6.dp.toPx())
        }
    }
}

/** La variazione rispetto allo snapshot precedente, come `deltaHtml` nel web. */
private fun variazione(ora: Double, prima: Double?): Pair<String, Color>? {
    if (prima == null) return null
    val d = ora - prima
    if (abs(d) < 0.5) return null
    val segno = if (d > 0) "▲ +" else "▼ −"
    return "$segno${if (nascondiImporti) MASCHERA else EUR0.format(abs(d))} dal giorno prima" to coloreSegno(d)
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
            // ⚠️ I soli titoli: la liquidità si sposta nella scheda 💶 (come la dashboard web
            // v1.36.0). Il confronto toglie la liquidità anche dallo snapshot precedente, e se
            // quello non la porta non si confronta.
            val cash = s.liquiditaDi(u) ?: 0.0
            val prevCash = s.liquiditaDi(p)
            val vP = if (p != null && prevCash != null) variazione(u.portafogli - cash, p.portafogli - prevCash) else null
            Riga("Valore titoli (quota)", eur0(u.portafogli - cash), Palette.success, grande = true, sotto = vP?.first, sottoColore = vP?.second ?: Palette.muted)
            Text("Titoli, liquidità a parte", color = Palette.muted, fontSize = 12.sp)
            if (u.dettaglioPortafogli.isEmpty()) Text("Nessun portafoglio.", color = Palette.muted)
            u.dettaglioPortafogli.forEach { pf ->
                val prima = s.precedente?.dettaglioPortafogli?.firstOrNull { it.id == pf.id }
                val lOra = pf.liquidita
                val lPrima = prima?.liquidita
                val v = if (prima != null && lOra != null && lPrima != null)
                    variazione(pf.valore - lOra, prima.valore - lPrima) else null
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { vm.vista(VistaFinanza.PORTAFOGLI); vm.apriPortafoglio(pf.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pallino(pf.colore)
                    Text(pf.nome, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(eur0(pf.valore - (pf.liquidita ?: 0.0)), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        v?.let { Text(it.first.removeSuffix(" dal giorno prima"), color = it.second, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    item {
        Scheda {
            Titolo("💶 Liquidità")
            // Liquidità dei portafogli (in quota) + saldo del Conto Spese Personali, che lo
            // snapshot conta già in asset e patrimonio (save-snapshot v1.5.0).
            val cash = s.liquiditaDi(u) ?: 0.0
            val sal = s.salSaldo?.importo ?: 0.0
            Riga("Totale", eur0(cash + sal), Palette.success, grande = true)
            u.dettaglioPortafogli.filter { (it.liquidita ?: 0.0) >= 0.5 }.forEach { pf ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pallino(pf.colore)
                    Text(pf.nome, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text(eur(pf.liquidita), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
            }
            val ss = s.salSaldo
            Riga("💳 Conto Spese Personali", if (ss != null) eur(ss.importo) else "—",
                sotto = ss?.data?.let { "saldo del ${dataIt(it)}" } ?: "da leggere in Spese Personali sul web")
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
    else item {
        val c = s.cumulativo
        Scheda(Modifier.clickable { vm.apriCumulativo(true) }, bordoColore = Color(0xFF0F172A)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Σ CUMULATIVO", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("→", color = Palette.muted)
            }
            Text("Virtuale · tutti i portafogli · valore intero (non per quota)", color = Palette.muted, fontSize = 13.sp)
            Riga("Valore", eur(c.totale), grande = true)
            Riga("P&L", "${eur(c.pnl)}  ${pct(c.pnlPct)}", coloreSegno(c.pnl))
            Riga("di cui liquidità", eur(c.liquidita))
        }
    }
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
            if (pf.id in s.saldi) Text("Quantità scritte a mano · investito = versato nel fondo", color = Palette.muted, fontSize = 12.sp)
            if ((pf.liquidita ?: 0.0) > 0.0) Riga("di cui liquidità", eur(pf.liquidita))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dettaglioPortafoglio(
    pf: PortafoglioSnap, s: FinanzaState, vm: FinanzaViewModel,
    onAggiorna: (String?) -> Unit, onElimina: (SaldoRiga) -> Unit,
) {
    // ⚠️ Portafoglio a saldi (il CRYPTO): le quantità si scrivono a mano, niente costo né P&L.
    val saldi = pf.id in s.saldi
    item {
        Scheda(bordoColore = coloreDaHex(pf.colore) ?: Palette.muted) {
            val v = variazione(pf.valore, s.valorePrecedente(pf.id))
            Riga("Valore portafoglio (quota)", eur(pf.valore), grande = true, sotto = v?.first, sottoColore = v?.second ?: Palette.muted)
            // Un portafoglio a saldi non ha liquidità: il fondo collegato è tutto in crypto.
            if (!saldi) Riga("Liquidità", eur(pf.liquidita))
            if (saldi) {
                // L'investito è il versato nel fondo collegato: lo scrive `save-snapshot` in total_cost.
                Riga("Investito (versato nel fondo)", eur(pf.costo))
                Riga("P&L", eur(pf.pnl), coloreSegno(pf.pnl), sotto = pct(pf.pnlPct), sottoColore = coloreSegno(pf.pnl))
                Text("Possesso: ${NUM.format(pf.possessoPct)} % · Le quantità si scrivono a mano; il P&L è sul portafoglio intero, non moneta per moneta.",
                    color = Palette.muted, fontSize = 12.sp)
                Pillola(if (s.salvataggio) "Salvo…" else "✏️ Aggiorna quantità", Palette.topBar) {
                    if (!s.salvataggio) onAggiorna(null)
                }
            } else {
                Riga("Investito", eur(pf.costo))
                Riga("P&L", eur(pf.pnl), coloreSegno(pf.pnl), sotto = pct(pf.pnlPct), sottoColore = coloreSegno(pf.pnl))
                Text("Possesso: ${NUM.format(pf.possessoPct)} % · P&L sui soli titoli: la liquidità non guadagna né perde.",
                    color = Palette.muted, fontSize = 12.sp)
            }
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
            Riga("Quantità", h.quantita?.let { (if (saldi) QTA else NUM).format(it) } ?: "—")
            if (!saldi) Riga("Costo medio", eur(h.costoMedio))
            Riga("Prezzo", eur(h.prezzo))
            Riga("Valore (quota)", eur(h.valoreQuota), grande = true)
            if (!saldi) Riga("P&L", eur(h.pnl), coloreSegno(h.pnl), sotto = pct(h.pnlPct), sottoColore = coloreSegno(h.pnl))
            if (saldi) {
                val m = s.monete.firstOrNull { it.simbolo == h.simbolo }
                if (m != null) Pillola("✏️ Aggiorna quantità", Palette.topBar) { if (!s.salvataggio) onAggiorna(m.id) }
            }
        }
    }
    if (saldi) registroSaldi(pf, s, onElimina)
    item { Spacer(Modifier.width(1.dp).height(24.dp)) }
}

// ── Σ Cumulativo ────────────────────────────────────────────────────────────
// Gemello di renderPortafoglioCumulativo / renderTitoloCumulativo in finanza.html, in sola
// lettura e sui numeri dell'ultimo snapshot. ⚠️ Niente grafici dei prezzi: il nativo non legge
// fnz_price_history.

private val TIPI = mapOf("stock" to "Azione", "etf" to "ETF", "bond" to "Obbl.", "crypto" to "Crypto", "fund" to "Fondo", "other" to "Altro")
private fun tipoTesto(t: String?) = t?.let { TIPI[it] ?: it } ?: "—"
private fun pesoTesto(v: Double, tot: Double) = if (tot > 0) String.format(Locale.ITALY, "%.2f %%", v / tot * 100) else "—"

private fun androidx.compose.foundation.lazy.LazyListScope.cumulativo(s: FinanzaState, vm: FinanzaViewModel) {
    val c = s.cumulativo
    item {
        Scheda(bordoColore = Color(0xFF0F172A)) {
            Text("Per intero, non per quota · tutti i portafogli", color = Palette.muted, fontSize = 13.sp)
            Riga("Valore", eur(c.totale), grande = true)
            Riga("Titoli", eur(c.totTitoli))
            Riga("di cui liquidità", eur(c.liquidita))
            Riga("Investito", eur(c.investito))
            Riga("P&L", eur(c.pnl), coloreSegno(c.pnl), sotto = pct(c.pnlPct), sottoColore = coloreSegno(c.pnl))
        }
    }
    // Filtro per tipo: cambia solo le righe, i numeri qui sopra restano sul totale.
    val tipi = c.titoli.mapNotNull { it.tipo }.distinct().sorted()
    val tipo = s.cumulTipo?.takeIf { it in tipi }
    val righe = if (tipo != null) c.titoli.filter { it.tipo == tipo } else c.titoli
    item {
        TendinaFacoltativa(
            etichetta = "Tipo", tutte = "Tutti i tipi", scelto = tipo,
            voci = tipi.map { it to tipoTesto(it) },
        ) { vm.cumulTipo(it) }
    }
    items(righe, key = { "cum-" + it.simbolo }) { r ->
        Scheda(Modifier.clickable { vm.apriTitoloCumul(r.simbolo) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.simbolo, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    if (r.nome != r.simbolo) Text(r.nome, color = Palette.muted, fontSize = 13.sp)
                }
                Text(tipoTesto(r.tipo), color = Palette.muted, fontSize = 12.sp)
            }
            Riga("Valore", eur(r.valore), grande = true, sotto = "peso ${pesoTesto(r.valore, c.totale)}")
            Riga("P&L", eur(r.pnl), coloreSegno(r.pnl), sotto = pct(r.pnlPct), sottoColore = coloreSegno(r.pnl))
            Text(r.dettaglio.map { it.first }.distinct().joinToString(" · "), color = Palette.muted, fontSize = 12.sp)
        }
    }
    // Liquidità: fuori dal filtro per tipo, che non è un tipo di prodotto.
    if (tipo == null) item {
        Scheda {
            Riga("💶 Liquidità fondi", eur(c.liquiditaFondi), sotto = "peso ${pesoTesto(c.liquiditaFondi, c.totale)}")
            if (c.contoSal != 0.0) Riga("💳 Conto Spese Personali", eur(c.contoSal), sotto = "peso ${pesoTesto(c.contoSal, c.totale)}")
        }
    }
    item {
        val v = righe.sumOf { it.valore }
        val conPnl = righe.filter { it.pnl != null }
        Scheda {
            Text("Totale (${righe.size} titoli)", fontWeight = FontWeight.Bold, color = Palette.dark)
            Riga("Valore", eur(v), grande = true, sotto = "peso ${pesoTesto(v, c.totale)}")
            if (conPnl.isNotEmpty()) Riga("P&L", eur(conPnl.sumOf { it.pnl ?: 0.0 }), coloreSegno(conPnl.sumOf { it.pnl ?: 0.0 }))
        }
    }
    item { Spacer(Modifier.width(1.dp).height(24.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.titoloCumulativo(s: FinanzaState, vm: FinanzaViewModel) {
    val c = s.cumulativo
    val r = c.titoli.firstOrNull { it.simbolo == s.titoloCumul }
    if (r == null) { item { Text("Titolo non più in portafoglio.", color = Palette.muted) }; return }
    item {
        Scheda {
            Text(r.nome, fontWeight = FontWeight.Bold, color = Palette.dark)
            Text("${tipoTesto(r.tipo)} · per intero, non per quota", color = Palette.muted, fontSize = 13.sp)
            Riga("Azioni / quote", QTA.format(r.quantita))
            Riga("Prezzo attuale", eur(r.prezzo))
            Riga("Valore", eur(r.valore), grande = true, sotto = "peso ${pesoTesto(r.valore, c.totale)}")
            Riga("Costo medio", eur(r.costoMedio))
            Riga("P&L", eur(r.pnl), coloreSegno(r.pnl), sotto = pct(r.pnlPct), sottoColore = coloreSegno(r.pnl))
        }
    }
    item { Titolo("Nei portafogli") }
    items(r.dettaglio, key = { "det-" + it.first }) { (nome, q, v) ->
        Scheda {
            Text(nome, fontWeight = FontWeight.SemiBold)
            Riga("Quantità", QTA.format(q))
            Riga("Valore", eur(v))
        }
    }
    item { Spacer(Modifier.width(1.dp).height(24.dp)) }
}

/** Il registro degli aggiornamenti, dal più recente, con la variazione sulla volta prima. */
private fun androidx.compose.foundation.lazy.LazyListScope.registroSaldi(
    pf: PortafoglioSnap, s: FinanzaState, onElimina: (SaldoRiga) -> Unit,
) {
    val righe = s.registro.filter { it.portafoglio == pf.id }
    // Si scorre dal più vecchio ricordando l'ultima quantità vista per moneta.
    val prima = HashMap<String, Double?>()
    val vista = HashMap<String, Double>()
    righe.asReversed().forEach { r -> prima[r.id] = vista[r.prodotto]; vista[r.prodotto] = r.quantita }
    item { Titolo("📝 Registro delle quantità (${righe.size})") }
    if (righe.isEmpty()) item { Text("Nessun aggiornamento ancora.", color = Palette.muted) }
    items(righe, key = { "reg-" + it.id }) { r ->
        val m = s.monete.firstOrNull { it.id == r.prodotto }
        val p = prima[r.id]
        val d = p?.let { r.quantita - it }
        Scheda {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ✕ a sinistra, come le altre icone di eliminazione della suite.
                Text("✕", color = Palette.danger, fontSize = 18.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { if (!s.salvataggio) onElimina(r) }
                        .padding(horizontal = 8.dp, vertical = 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(m?.simbolo ?: "—", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(quando(r.quando), color = Palette.muted, fontSize = 12.sp)
                }
            }
            Riga("Quantità", QTA.format(r.quantita),
                sotto = if (d == null) "iniziale" else (if (d > 0) "+" else "") + QTA.format(d),
                sottoColore = if (d == null) Palette.muted else coloreSegno(d))
            r.nota?.let { Text(it, color = Palette.muted, fontSize = 13.sp) }
        }
    }
}

private fun quando(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
}.getOrDefault(iso)

/**
 * ✏️ Aggiorna quantità: la nuova quantità TOTALE di una moneta. Con la moneta già scelta la
 * casella parte da quella in vigore; senza, si sceglie in tendina (le crypto prima). Zero vuol
 * dire «non ne ho più». Gemella di `openSaldoModal` in finanza.html.
 */
@Composable
private fun DialogoSaldo(
    s: FinanzaState, portafoglio: String, prodottoFisso: String?,
    onAnnulla: () -> Unit, onSalva: (String, Double, String?) -> Unit,
) {
    var prodotto by remember { mutableStateOf(prodottoFisso) }
    var testo by remember {
        mutableStateOf(prodottoFisso?.let { s.quantitaInVigore(portafoglio, it) }?.let { QTA_CASELLA.format(it) } ?: "")
    }
    var nota by remember { mutableStateOf("") }
    // Virgola o punto come separatore decimale; con la virgola i punti sono delle migliaia.
    val quantita = testo.trim().let { t -> if (',' in t) t.replace(".", "").replace(',', '.') else t }
        .toDoubleOrNull()?.takeIf { it >= 0 }
    val voci = s.monete.sortedWith(compareBy({ it.tipo != "crypto" }, { it.simbolo }))
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("✏️ Aggiorna quantità") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prodottoFisso != null) {
                    val m = s.monete.firstOrNull { it.id == prodottoFisso }
                    Text("${m?.simbolo ?: ""} — ${m?.nome ?: ""}", fontWeight = FontWeight.Bold)
                    Text("In vigore: " + (s.quantitaInVigore(portafoglio, prodottoFisso)?.let { QTA.format(it) } ?: "—"),
                        color = Palette.muted, fontSize = 13.sp)
                } else {
                    Tendina(
                        etichetta = "Moneta",
                        scelto = voci.firstOrNull { it.id == prodotto }?.let { "${it.simbolo} — ${it.nome}" } ?: "— Scegli —",
                        voci = voci.map { m ->
                            val q = s.quantitaInVigore(portafoglio, m.id)
                            m.id to ("${m.simbolo} — ${m.nome}" + (q?.let { " (ora ${QTA.format(it)})" } ?: ""))
                        },
                    ) { prodotto = it }
                    Text("Se la moneta non c'è, aggiungila da 📋 Prodotti nel web.", color = Palette.muted, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = testo, onValueChange = { testo = it },
                    label = { Text("Nuova quantità totale") },
                    singleLine = true,
                    isError = testo.isNotBlank() && quantita == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nota, onValueChange = { nota = it },
                    label = { Text("Note (facoltative)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { val p = prodotto; if (p != null && quantita != null) onSalva(p, quantita, nota) },
                enabled = prodotto != null && quantita != null && !s.salvataggio,
            ) { Text(if (s.salvataggio) "Salvo…" else "Salva") }
        },
        dismissButton = { TextButton(onAnnulla) { Text("Annulla") } },
    )
}
