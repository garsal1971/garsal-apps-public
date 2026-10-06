package com.garsal.appsphere.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.TendinaFacoltativa
import com.garsal.appsphere.core.coloreDaHex
import com.garsal.appsphere.core.larghezzaPulsanti

/**
 * La scheda **Gestione**, cioè la pagina ✏️ di `tasks.html`: l'elenco completo
 * dei task con cerca, filtri e raggruppamenti, e su ogni scheda le azioni che
 * là stanno sotto la riga del titolo — Vedi, Modifica, Clona, Cancella, e
 * Riattiva su quelli terminati o archiviati.
 *
 * I due riquadri in cima sono quelli del web: **📊 Vista** (espandi tutti e
 * raggruppa per) e **🔎 Cerca e filtra** (testo, stato, priorità, categoria,
 * tipo), tutti e due richiudibili e chiusi all'apertura — occupati aperti sono
 * mezzo schermo, e la maggior parte delle volte si viene qui per l'elenco.
 *
 * ⚠️ Filtri e ordinamento sono quelli di `applyTaskFilters()`, ricalcati in
 * `TasksState.gestione()`: stessi quattro stati vivi sotto «Attivi», stessa
 * ricerca su titolo **e** descrizione, stesso ordine per prossima occorrenza
 * con chi non ce l'ha in fondo. Se cambia una regola di là, va cambiata anche
 * qui, o i due elenchi si contraddicono a parità di filtri.
 *
 * **Non c'è ⬆️ Importa**: nel web carica un backup JSON e scrive tanti task in
 * un colpo. Serve un selettore di file, e un import a metà su un elenco vero
 * si ripulisce a mano riga per riga — resta su `tasks.html`, dove c'è anche
 * l'esportazione che quel file lo produce.
 */
@Composable
fun VistaGestione(
    stato: TasksState,
    filtri: FiltriGestione,
    onFiltri: (FiltriGestione) -> Unit,
    onNuovo: () -> Unit,
    onVedi: (TsTask) -> Unit,
    onModifica: (TsTask) -> Unit,
    onClona: (TsTask) -> Unit,
    onElimina: (TsTask) -> Unit,
    onRiattiva: (TsTask) -> Unit,
) {
    val trovati = stato.gestione(filtri)
    val azioni = AzioniGestione(onVedi, onModifica, onClona, onElimina, onRiattiva)
    val larghezza = larghezzaPulsanti(listOf(VEDI, MODIFICA, CLONA, CANCELLA, RIATTIVA))

    LazyColumn(
        contentPadding = PaddingValues(10.dp, 8.dp, 10.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "azioni-pagina") {
            RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                Pillola("Nuovo task", Palette.primary) { onNuovo() }
            }
        }

        item(key = "vista") {
            Riquadro("📊 VISTA") {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (filtri.gruppiAperti) "Comprimi tutti" else "Espandi tutti",
                        color = Palette.dark,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(
                        checked = filtri.gruppiAperti,
                        onCheckedChange = { onFiltri(filtri.copy(gruppiAperti = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.topBar),
                    )
                }
                Tendina(
                    etichetta = "Raggruppa per",
                    scelto = filtri.raggruppa.etichetta,
                    voci = Raggruppa.entries.map { it.name to it.etichetta },
                ) { scelta ->
                    onFiltri(filtri.copy(raggruppa = Raggruppa.valueOf(scelta)))
                }
            }
        }

        item(key = "cerca") {
            Riquadro("🔎 CERCA E FILTRA") {
                OutlinedTextField(
                    value = filtri.cerca,
                    onValueChange = { onFiltri(filtri.copy(cerca = it)) },
                    label = { Text("Cerca per titolo o descrizione") },
                    singleLine = true,
                    trailingIcon = {
                        if (filtri.cerca.isNotEmpty()) {
                            Text(
                                "✕",
                                color = Palette.muted,
                                modifier = Modifier
                                    .clickable { onFiltri(filtri.copy(cerca = "")) }
                                    .padding(12.dp),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )

                Tendina(
                    etichetta = "Stato",
                    scelto = FiltriGestione.STATI.first { it.first == filtri.stato }.second,
                    voci = FiltriGestione.STATI,
                ) { scelta -> onFiltri(filtri.copy(stato = scelta)) }

                TendinaFacoltativa(
                    etichetta = "Priorità",
                    tutte = "Tutte le priorità",
                    scelto = filtri.prioritaId,
                    voci = stato.priorita.map { it.id to it.nome },
                ) { scelta -> onFiltri(filtri.copy(prioritaId = scelta)) }

                TendinaFacoltativa(
                    etichetta = "Categoria",
                    tutte = "Tutte le categorie",
                    scelto = filtri.categoriaId,
                    voci = stato.categorie.map { it.id to it.etichetta },
                ) { scelta -> onFiltri(filtri.copy(categoriaId = scelta)) }

                TendinaFacoltativa(
                    etichetta = "Tipo",
                    tutte = "Tutti i tipi",
                    scelto = filtri.tipo,
                    voci = TsTask.TIPI,
                ) { scelta -> onFiltri(filtri.copy(tipo = scelta)) }
            }
        }

        item(key = "conteggio") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.accent.copy(alpha = 0.10f))
                    .border(1.dp, Palette.accent, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "🔍 Trovati ${trovati.size} task" +
                        filtri.cerca.trim().takeIf { it.isNotEmpty() }?.let { " con «$it»" }.orEmpty(),
                    color = Palette.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (trovati.isEmpty()) {
            item(key = "niente") {
                Text(
                    "Nessun task con questi filtri.",
                    color = Palette.muted,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else if (filtri.raggruppa == Raggruppa.NESSUNO) {
            schede(trovati, stato, azioni, larghezza)
        } else {
            stato.gruppiGestione(filtri).forEach { gruppo ->
                gruppoRichiudibile(gruppo, filtri.gruppiAperti, stato, azioni, larghezza)
            }
        }
    }
}

/** Le cinque azioni di una scheda di Gestione, passate in blocco. */
private data class AzioniGestione(
    val vedi: (TsTask) -> Unit,
    val modifica: (TsTask) -> Unit,
    val clona: (TsTask) -> Unit,
    val elimina: (TsTask) -> Unit,
    val riattiva: (TsTask) -> Unit,
)

private fun LazyListScope.schede(
    task: List<TsTask>,
    stato: TasksState,
    azioni: AzioniGestione,
    larghezza: Dp,
) {
    items(task.size, key = { "task-${task[it].id}" }) { indice ->
        SchedaGestione(task[indice], stato, azioni, larghezza)
    }
}

/**
 * Un gruppo con la sua intestazione: si apre e si chiude col tocco, e parte
 * come dice l'interruttore «Espandi tutti».
 *
 * Lo stato aperto/chiuso sta **dentro l'intestazione** e non nei filtri: è una
 * cosa che si fa scorrendo l'elenco, e passarla su e giù per tutta la
 * schermata a ogni tocco vorrebbe dire ricomporre l'elenco intero per aprire
 * un gruppo.
 */
private fun LazyListScope.gruppoRichiudibile(
    gruppo: GruppoCategoria,
    apertoAllInizio: Boolean,
    stato: TasksState,
    azioni: AzioniGestione,
    larghezza: Dp,
) {
    item(key = "gruppo-${gruppo.chiave}") {
        // `rememberSaveable` e non `remember`: LazyColumn butta via gli item
        // che escono dallo schermo, e un gruppo aperto si richiuderebbe da sé
        // appena lo si scorre oltre. La chiave dell'item lo tiene da parte.
        var aperto by rememberSaveable(apertoAllInizio) { mutableStateOf(apertoAllInizio) }
        val colore = coloreDaHex(gruppo.colore) ?: Palette.muted

        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colore.copy(alpha = 0.12f))
                    .clickable { aperto = !aperto }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (aperto) "▾" else "▸", color = colore, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = "${gruppo.etichetta} (${gruppo.task.size})",
                    color = colore,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (aperto) {
                Column(Modifier.padding(top = 8.dp)) {
                    gruppo.task.forEach { SchedaGestione(it, stato, azioni, larghezza) }
                }
            }
        }
    }
}

@Composable
private fun SchedaGestione(
    task: TsTask,
    stato: TasksState,
    azioni: AzioniGestione,
    larghezza: Dp,
) {
    val colore = coloreStato(task.stato)
    val priorita = stato.prioritaDi(task.prioritaId)
    val categorie = task.categorie.mapNotNull { stato.categoriaDi(it) }
    val larghezzaStriscia = with(LocalDensity.current) { 6.dp.toPx() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.cardBg)
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            // La striscia colorata di sinistra dice lo stato. Nel web la parola
            // ci sta dentro, scritta in verticale; qui è un'etichetta normale
            // qui accanto: un testo ruotato non si allarga insieme ai caratteri
            // di sistema, e a quell'ingrandimento uscirebbe dalla striscia.
            .drawBehind { drawRect(colore, size = Size(larghezzaStriscia, size.height)) }
            .padding(start = 6.dp)
            .clickable { azioni.vedi(task) }
            .padding(10.dp),
    ) {
        RigaScorrevole(Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${TsTask.segnoTipo(task.tipo)}  ${dataOraItaliana(task.dataDiRiferimento)}",
                fontWeight = FontWeight.SemiBold,
                color = Palette.dark,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                softWrap = false,
            )
            Targhetta(task.etichettaStato, colore)
        }

        if (priorita != null || categorie.isNotEmpty()) {
            RigaScorrevole(Arrangement.spacedBy(6.dp), Modifier.padding(top = 6.dp)) {
                priorita?.let {
                    Targhetta("🎯 ${it.nome}", coloreDaHex(it.colore) ?: Palette.secondary)
                }
                categorie.take(1).forEach { c ->
                    Targhetta(c.etichetta, coloreDaHex(c.colore) ?: Palette.accent)
                }
                if (categorie.size > 1) Targhetta("+${categorie.size - 1}", Palette.muted)
            }
        }

        RigaScorrevole(Arrangement.Start, Modifier.padding(top = 6.dp, bottom = 8.dp)) {
            Text(
                text = task.titolo,
                color = Palette.dark,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }

        RigaScorrevole(Arrangement.spacedBy(8.dp)) {
            Pillola(VEDI, Palette.accent, larghezza) { azioni.vedi(task) }
            if (task.vivo) {
                // I `workflow` non passano dal form: i loro step hanno
                // dipendenze fra loro, e questo form li appiattirebbe.
                if (task.modificabile) {
                    Pillola(MODIFICA, Palette.primary, larghezza) { azioni.modifica(task) }
                    Pillola(CLONA, Palette.muted, larghezza) { azioni.clona(task) }
                }
            } else {
                Pillola(RIATTIVA, Palette.warning, larghezza) { azioni.riattiva(task) }
            }
            Pillola(CANCELLA, Palette.danger, larghezza) { azioni.elimina(task) }
        }
    }
}

private const val VEDI = "Vedi"
private const val MODIFICA = "Modifica"
private const val CLONA = "Clona"
private const val CANCELLA = "Cancella"
private const val RIATTIVA = "Riattiva"

/** Gli stessi colori di `statusMap` in `renderManagement()`. */
private fun coloreStato(stato: String): Color = when (stato) {
    "completed" -> Palette.success
    "failed" -> Palette.danger
    "skipped" -> Palette.warning
    "terminated" -> Color(0xFF374151)
    "archived" -> Palette.secondary
    else -> Palette.muted
}

// ── Pezzi ────────────────────────────────────────────────────────────────

@Composable
private fun Riquadro(titolo: String, contenuto: @Composable () -> Unit) {
    // Chiuso all'apertura, come nel web: aperti sono mezzo schermo, e qui si
    // viene per l'elenco. `rememberSaveable` perché scorrendo l'elenco l'item
    // esce di scena, e al ritorno un pannello che si è richiuso da solo
    // sembra che l'app abbia annullato il filtro appena scelto.
    var aperto by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.cardBg)
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { aperto = !aperto },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = titolo,
                fontWeight = FontWeight.Bold,
                color = Palette.dark,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(if (aperto) "▾" else "▸", color = Palette.muted)
        }
        if (aperto) {
            Column(Modifier.padding(top = 10.dp)) { contenuto() }
        }
    }
}

@Composable
private fun Targhetta(testo: String, colore: Color) {
    Text(
        text = testo,
        color = Palette.light,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colore)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
