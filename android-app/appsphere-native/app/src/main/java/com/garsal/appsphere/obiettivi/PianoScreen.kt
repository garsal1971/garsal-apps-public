package com.garsal.appsphere.obiettivi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.coloreDaHex
import com.garsal.appsphere.core.larghezzaPulsanti
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 📆 **Piano quotidiano** — la pagina che `obiettivi.html` apre per prima.
 *
 * Tutte le azioni ancora da fare, giorno per giorno: le arretrate in un
 * riquadro solo in cima, poi oggi, poi i giorni che vengono, e in fondo quelle
 * a libera ripetizione. Da qui si chiudono.
 *
 * ⚠️ È la sola pagina di Obiettivi portata in nativo: ✅ Azioni, 📊 Andamento,
 * il Dettaglio e le Impostazioni restano sul web. Le regole che valgono qui
 * sono le stesse di là e non sono riscritte a occhio — il ciclo di vita passa
 * dalle RPC, la rilevazione si chiede solo dopo un successo ed è datata
 * all'occorrenza chiusa.
 */
@Composable
fun PianoScreen(
    onIndietro: () -> Unit,
    onApriObiettivi: () -> Unit,
    vm: PianoViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    val oggi = LocalDate.now()
    var daSaltare by remember { mutableStateOf<ObAzione?>(null) }
    var daGuardare by remember { mutableStateOf<ObAzione?>(null) }
    val avvisi = remember { SnackbarHostState() }
    val scalaIcone = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

    stato.messaggio?.let { testo ->
        LaunchedEffect(testo) {
            avvisi.showSnackbar(testo)
            vm.messaggioMostrato()
        }
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Piano quotidiano",
                onIndietro = onIndietro,
                azioni = {
                    // Gli obiettivi con le loro metriche stanno un passo più in
                    // là: il piano dice cosa fare oggi, non com'è definito il
                    // piano. Le icone seguono `fontScale` con un tetto, come in
                    // home: in `dp` fisse, accanto a un titolo ingrandito,
                    // sarebbero piccole da centrare col dito.
                    Text(
                        text = "🎯",
                        fontSize = 20.sp * scalaIcone,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable(onClick = onApriObiettivi),
                    )
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Ricarica",
                        tint = Palette.light,
                        modifier = Modifier.size(26.dp * scalaIcone).clickable { vm.carica() },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(avvisi) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val sezioni = stato.sezioni(oggi)
            when {
                stato.caricamento && stato.azioni.isEmpty() ->
                    CircularProgressIndicator(
                        color = Palette.secondary,
                        modifier = Modifier.align(Alignment.Center),
                    )

                stato.errore != null ->
                    Text(
                        stato.errore ?: "",
                        color = Palette.danger,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                sezioni.isEmpty() ->
                    Text(
                        "Niente in programma.\nUn obiettivo senza azioni è un desiderio.",
                        color = Palette.muted,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                else -> {
                    // ⚠️ `larghezzaPulsanti` vuole **tutte** le etichette che
                    // possono comparire nella riga, non solo quelle mostrate su
                    // una data scheda: senza, un pulsante condizionale farebbe
                    // traballare la larghezza degli altri da una scheda all'altra.
                    val larghezza = larghezzaPulsanti(listOf(COMPLETA, SALTA, STEP))
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(sezioni.size, key = { sezioni[it].titolo }) { i ->
                            Sezione(
                                sezione = sezioni[i],
                                stato = stato,
                                larghezza = larghezza,
                                oggi = oggi,
                                onCompleta = vm::completa,
                                onSalta = { daSaltare = it },
                                onStep = { daGuardare = it },
                            )
                        }
                    }
                }
            }
        }
    }

    daSaltare?.let { azione ->
        DialogoSalta(
            azione = azione,
            onAnnulla = { daSaltare = null },
            onConferma = { giorni ->
                daSaltare = null
                vm.salta(azione, giorni)
            },
        )
    }

    daGuardare?.let { azione ->
        DialogoWorkflow(azione = azione, onChiudi = { daGuardare = null })
    }

    stato.daRilevare?.let { richiesta ->
        DialogoRilevazioni(
            richiesta = richiesta,
            onAnnulla = vm::rilevazioniChiuse,
            onSalva = { valori, nota -> vm.salvaRilevazioni(valori, nota) },
        )
    }
}

private const val COMPLETA = "Completa"
private const val SALTA = "Salta"
private const val STEP = "Step"

/**
 * Il riquadro bianco con la striscia colorata a sinistra, come le sezioni della
 * panoramica dei task. La striscia è uno sfondo dietro la colonna e non un
 * `Box` di altezza fissa: coi caratteri di sistema grandi l'altezza del
 * contenuto è l'unica misura che non si può decidere prima.
 */
@Composable
private fun Sezione(
    sezione: SezionePiano,
    stato: PianoState,
    larghezza: Dp,
    oggi: LocalDate,
    onCompleta: (ObAzione) -> Unit,
    onSalta: (ObAzione) -> Unit,
    onStep: (ObAzione) -> Unit,
) {
    val colore = when {
        sezione.titolo.startsWith("⚠️") -> Palette.danger
        sezione.titolo.startsWith("🎯") -> Palette.primary
        sezione.titolo.startsWith("🔄") -> Palette.secondary
        else -> Palette.accent
    }
    val larghezzaStriscia = with(LocalDensity.current) { 4.dp.toPx() }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.cardBg)
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            .drawBehind { drawRect(colore, size = Size(larghezzaStriscia, size.height)) }
            .padding(start = 4.dp)
            .padding(12.dp),
    ) {
        // Titolo e conteggio su una riga sola che scorre: il titolo di un
        // giorno porta già la data, e con l'ingrandimento alto insieme al
        // conteggio non ci starebbero.
        RigaScorrevole(Arrangement.Start) {
            Text(
                text = "${sezione.titolo} (${sezione.righe.size})",
                color = colore,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
        sezione.nota?.let {
            Text(
                text = it,
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (sezione.righe.isEmpty()) {
            Text(
                text = sezione.seVuota.orEmpty(),
                color = Palette.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        sezione.righe.forEach { azione ->
            SchedaAzione(azione, stato, larghezza, oggi, onCompleta, onSalta, onStep)
        }
    }
}

/**
 * La scheda di un'azione, con le stesse cose del web: tipo, data e ora,
 * obiettivo, descrizione, etichette (priorità, categorie, metriche collegate) e
 * i pulsanti che agiscono subito.
 *
 * Ogni fila sta **su una riga sola che scorre col dito** e non va a capo: è la
 * stessa scelta della panoramica dei task — andando a capo, coi caratteri
 * ingranditi in uno schermo ci starebbe un'azione e mezza — e quel che conta di
 * più sta a sinistra.
 */
@Composable
private fun SchedaAzione(
    azione: ObAzione,
    stato: PianoState,
    larghezza: Dp,
    oggi: LocalDate,
    onCompleta: (ObAzione) -> Unit,
    onSalta: (ObAzione) -> Unit,
    onStep: (ObAzione) -> Unit,
) {
    val obiettivo = stato.obiettivoDi(azione.obiettivoId)
    val priorita = stato.prioritaDi(azione.prioritaId)
    val categorie = azione.categorie.mapNotNull { stato.categoriaDi(it) }
    val metriche = stato.metricheDiAzione(azione.id)
    val colore = coloreDaHex(priorita?.colore) ?: coloreDaHex(obiettivo?.color) ?: Palette.secondary
    val inRitardo = azione.giorno?.isBefore(oggi) == true

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.inputBg)
            .border(1.dp, colore.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        RigaScorrevole(Arrangement.Start) {
            Text(
                text = azione.titolo,
                color = Palette.dark,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }

        RigaScorrevole(Arrangement.Start, Modifier.padding(top = 4.dp)) {
            Text(
                text = rigaMeta(azione, obiettivo),
                color = if (inRitardo) Palette.danger else Palette.muted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (inRitardo) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }

        azione.descrizione?.let {
            Text(
                text = it,
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (priorita != null || categorie.isNotEmpty() || metriche.isNotEmpty()) {
            RigaScorrevole(Arrangement.spacedBy(6.dp), Modifier.padding(top = 6.dp)) {
                priorita?.let {
                    Etichetta("🎯 ${it.nome}", coloreDaHex(it.colore) ?: Palette.secondary)
                }
                categorie.forEach {
                    Etichetta(it.etichetta, coloreDaHex(it.colore) ?: Palette.muted)
                }
                // Le metriche portano il 📈 e un colore diverso, per non
                // confonderle con le categorie: dicono cosa l'azione dovrebbe
                // muovere, non di che cosa parla.
                metriche.forEach { Etichetta("📈 ${it.name}", Palette.accent) }
            }
        }

        RigaScorrevole(Arrangement.spacedBy(8.dp), Modifier.padding(top = 8.dp)) {
            // ⚠️ Un workflow non ha il *Completa*: si chiude dai suoi step, e un
            // pulsante che lo chiudesse di forza salterebbe quelli ancora aperti.
            if (azione.workflow) {
                Pillola(STEP, Palette.accent, larghezza) { onStep(azione) }
            } else {
                Pillola(COMPLETA, Palette.success, larghezza) { onCompleta(azione) }
            }
            if (azione.tipo in ObAzione.PUO_SALTARE) {
                Pillola(SALTA, Palette.warning, larghezza) { onSalta(azione) }
            }
        }
    }
}

/** Tipo · data e ora · obiettivo — e per una libera ripetizione l'ultima volta. */
private fun rigaMeta(azione: ObAzione, obiettivo: ObObiettivo?): String {
    val pezzi = mutableListOf(ObAzione.ETICHETTA_TIPO[azione.tipo] ?: azione.tipo)
    if (azione.workflow && azione.stepTotali > 0) {
        pezzi[0] = pezzi[0] + " · ${azione.stepFatti}/${azione.stepTotali} step"
    }
    if (azione.libera) {
        pezzi += azione.ultimaVolta?.let { "ultima: ${dataItalianaDa(giornoLocale(it))}" } ?: "mai fatta"
    } else {
        azione.giorno?.let { pezzi += (dataItalianaDa(it) + " " + azione.ora).trim() }
    }
    obiettivo?.let { pezzi += it.title }
    return pezzi.joinToString(" · ")
}

@Composable
private fun Etichetta(testo: String, colore: Color) {
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

/**
 * Di quanti giorni spostare. Lo chiede **solo** per le singole: per tutti gli
 * altri tipi `ob_action_skip` ignora `p_days` e va alla prossima occorrenza
 * loro, quindi chiedere un numero che il server butta via sarebbe una bugia.
 */
@Composable
private fun DialogoSalta(azione: ObAzione, onAnnulla: () -> Unit, onConferma: (Int) -> Unit) {
    if (azione.tipo != "single") {
        AlertDialog(
            onDismissRequest = onAnnulla,
            title = { Text("Saltare?") },
            text = { Text("L'azione va alla sua prossima occorrenza: il numero di giorni non si applica.") },
            confirmButton = { TextButton(onClick = { onConferma(1) }) { Text("Salta") } },
            dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
        )
        return
    }

    var giorni by remember { mutableStateOf("1") }
    val numero = giorni.toIntOrNull()

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Di quanti giorni?") },
        text = {
            Column {
                OutlinedTextField(
                    value = giorni,
                    onValueChange = { giorni = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Giorni") },
                    singleLine = true,
                )
                Text(
                    text = numero?.let {
                        "Nuova data: " + dataItalianaDa(
                            (azione.giorno ?: LocalDate.now()).plusDays(it.toLong())
                        )
                    } ?: "Serve un numero di giorni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = numero != null, onClick = { onConferma(numero ?: 1) }) {
                Text("Salta")
            }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

/**
 * Gli step di un workflow, **in sola lettura**.
 *
 * ⚠️ Da qui uno step non si chiude, e non è una dimenticanza: chiuderlo vuol
 * dire riscrivere `workflow_steps`, sbloccare chi dipendeva da lui, scrivere la
 * riga di storico coi suoi punti e poi richiamare `ob_action_complete` — cioè
 * copiare in Kotlin una regola che oggi vive in un posto solo, `chiudiStep()`
 * in `obiettivi.html`. Finché quella regola non scende in una RPC come le
 * altre, il workflow si chiude dal web e qui si guarda a che punto è.
 */
@Composable
private fun DialogoWorkflow(azione: ObAzione, onChiudi: () -> Unit) {
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(azione.titolo) },
        text = {
            Column {
                Text(
                    text = if (azione.stepTotali > 0)
                        "${azione.stepFatti} step chiusi su ${azione.stepTotali}."
                    else "Questo workflow non ha step.",
                    color = Palette.dark,
                )
                Text(
                    text = "Gli step si chiudono da obiettivi.html: la regola che sblocca " +
                        "quelli che dipendono da loro vive lì, e riscriverla qui vorrebbe " +
                        "dire tenerne allineate due.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onChiudi) { Text("Chiudi") } },
    )
}

/**
 * Le rilevazioni delle metriche che l'azione appena chiusa dovrebbe muovere.
 *
 * ⚠️ **La data non si sceglie**: è la data di occorrenza, cioè la volta che si
 * è appena chiusa. Su un'azione scaduta quel giorno e oggi sono diversi, e
 * datare tutto a oggi vorrebbe dire che due occorrenze arretrate chiuse nello
 * stesso pomeriggio si sovrascrivono a vicenda — l'unicità è
 * `(metric_id, measured_on)`.
 *
 * ⚠️ Una misura lasciata su **non adesso** non si registra, e non si registra
 * come zero: è la stessa scelta delle misure di un diario in Memo e delle
 * caselle vuote di `fnz_income`.
 */
@Composable
private fun DialogoRilevazioni(
    richiesta: RilevazioniDaChiedere,
    onAnnulla: () -> Unit,
    onSalva: (Map<String, Double>, String) -> Unit,
) {
    val saltate = remember(richiesta.azioneId) { mutableStateMapOf<String, Boolean>() }
    val valori = remember(richiesta.azioneId) {
        mutableStateMapOf<String, String>().apply {
            richiesta.metriche.forEach { m ->
                val (da, a) = m.scala
                val ultima = richiesta.ultime[m.id]?.value
                put(m.id, when {
                    ultima != null -> numeroBreve(ultima)
                    m.kind == "autovalutazione" -> numeroBreve(((da + a) / 2).roundToInt().toDouble())
                    else -> ""
                })
            }
        }
    }
    var nota by remember(richiesta.azioneId) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("Com'è andata?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(richiesta.titoloAzione, fontWeight = FontWeight.SemiBold, color = Palette.dark)
                Text(
                    text = "Rilevazione del ${dataItalianaDa(richiesta.giorno)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                richiesta.metriche.forEach { metrica ->
                    val salta = saltate[metrica.id] == true
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = metrica.name,
                                fontWeight = FontWeight.SemiBold,
                                color = if (salta) Palette.muted else Palette.dark,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                            )
                            Pillola(
                                testo = if (salta) "misuro" else "non adesso",
                                sfondo = if (salta) Palette.accent else Palette.muted,
                            ) { saltate[metrica.id] = !salta }
                        }
                        metrica.descrizione?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (!salta) CampoMisura(metrica, valori[metrica.id].orEmpty()) {
                            valori[metrica.id] = it
                        }
                    }
                }

                OutlinedTextField(
                    value = nota,
                    onValueChange = { nota = it },
                    label = { Text("Nota (vale per tutte)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val daScrivere = richiesta.metriche
                    .filter { saltate[it.id] != true }
                    .mapNotNull { m ->
                        valori[m.id]?.replace(',', '.')?.toDoubleOrNull()?.let { m.id to it }
                    }
                    .toMap()
                onSalva(daScrivere, nota.trim())
            }) { Text("Registra") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Non adesso") } },
    )
}

/**
 * Lo slider di un'autovalutazione o la casella di un'automisurazione: il form
 * mostra la scala **oppure** partenza e obiettivo, mai entrambe, com'è il
 * vincolo `ob_metrics_scala_per_tipo` un livello sotto. Gli estremi si leggono
 * da [ObMetrica.scala], che è la copia della RPC `ob_metric_scale`.
 */
@Composable
private fun CampoMisura(metrica: ObMetrica, valore: String, onCambia: (String) -> Unit) {
    val (da, a) = metrica.scala
    if (metrica.kind == "autovalutazione" && a != da) {
        val corrente = valore.replace(',', '.').toFloatOrNull() ?: ((da + a) / 2).toFloat()
        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = corrente.coerceIn(
                    minOf(da, a).toFloat(),
                    maxOf(da, a).toFloat(),
                ),
                onValueChange = { onCambia(it.roundToInt().toString()) },
                valueRange = minOf(da, a).toFloat()..maxOf(da, a).toFloat(),
                steps = (kotlin.math.abs(a - da).toInt() - 1).coerceAtLeast(0),
            )
            Text(
                text = "${numeroBreve(da)} → ${numeroBreve(a)} · scelto: ${valore.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted,
            )
        }
    } else {
        OutlinedTextField(
            value = valore,
            onValueChange = onCambia,
            label = { Text("Valore" + (metrica.unit?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        )
        Text(
            text = "Da ${numeroBreve(da)} a ${numeroBreve(a)}.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.muted,
        )
    }
}

/** `7.0` → `7`, `7.5` → `7,5`: un decimale finto non si scrive. */
private fun numeroBreve(v: Double): String =
    if (v == v.roundToInt().toDouble()) v.roundToInt().toString()
    else "%.2f".format(v).trimEnd('0').trimEnd('.').replace('.', ',')
