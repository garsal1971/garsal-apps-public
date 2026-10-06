package com.garsal.appsphere.peso

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.larghezzaPulsanti
import java.time.LocalDate

/**
 * «Gestione Obiettivo» in nativo — lo stesso form di `weight-quest.html`
 * (creare, modificare, chiudere e cancellare un obiettivo), a tutto schermo
 * come [com.garsal.appsphere.tafiri.TaFiriForm].
 *
 * Include anche il gratta-e-vinci dei premi (la barra di stelline con
 * `⭐ Punti Totali Traguardi Intermedi`, [BarraTraguardi]): punti e premi
 * grattati stanno su Supabase ([PesoPremi]), nelle stesse due tabelle che usa
 * `weight-quest.html` — un premio vinto qui si ritrova sul PC. Tutto il resto — nome, tipo, punti,
 * milestone, Salva/Successo/Fallito/Elimina — è lo stesso form, campo per
 * campo. Le regole di calcolo (validazione di chiusura, punteggio finale)
 * stanno nella RPC `ps_chiudi_obiettivo`, la stessa del web.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GestioneObiettivoScreen(
    stato: PesoState,
    vm: PesoViewModel,
    onIndietro: () -> Unit,
) {
    // Come per gli altri form della app: se non c'è ancora nessun obiettivo si
    // parte già sul foglio bianco, altrimenti su quello selezionato altrove.
    var nuovo by remember { mutableStateOf(stato.obiettivi.isEmpty()) }
    val editando = if (nuovo) null else stato.obiettivo
    // La chiave con cui si resettano i campi di testo del form quando cambia
    // l'obiettivo selezionato (o si passa a «nuovo») — mai `valore`, che è
    // anche quello che i campi stessi riscrivono ad ogni cifra digitata.
    val chiaveForm = editando?.id to nuovo

    var bozza by remember(editando?.id, nuovo) {
        mutableStateOf(editando?.let { BozzaObiettivo.da(it) } ?: BozzaObiettivo.nuova())
    }
    var nuovaData by remember(editando?.id, nuovo) { mutableStateOf<LocalDate?>(null) }
    var nuovoPeso by remember(editando?.id, nuovo) { mutableStateOf("") }

    var bloccataChiusura by remember { mutableStateOf<String?>(null) }
    var confermaChiusura by remember { mutableStateOf<Triple<Obiettivo, String, EsitoChiusura.DaConfermare>?>(null) }
    var daEliminare by remember { mutableStateOf<Obiettivo?>(null) }
    var daRiaprire by remember { mutableStateOf<Obiettivo?>(null) }

    val isClosed = editando?.attivo == false
    val context = LocalContext.current

    Scaffold(
        topBar = { GarsalTopBar(titolo = "🎯 Gestione Obiettivo", onIndietro = onIndietro) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Titoletto("Seleziona Obiettivo")
                SelettoreObiettivo(
                    obiettivi = stato.obiettivi,
                    scelto = stato.obiettivo,
                    onScegli = { id -> nuovo = false; vm.scegliObiettivo(id) },
                    mostraNuovo = true,
                    nuovoScelto = nuovo,
                    onNuovo = { nuovo = true },
                )

                if (isClosed) {
                    val successo = editando?.stato == "success"
                    Text(
                        text = if (successo) "🏆 OBIETTIVO CHIUSO CON SUCCESSO" else "💀 OBIETTIVO FALLITO",
                        color = if (successo) Verde else Palette.danger,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background((if (successo) Verde else Palette.danger).copy(alpha = 0.12f))
                            .padding(12.dp),
                    )
                }

                OutlinedTextField(
                    value = bozza.nome,
                    onValueChange = { bozza = bozza.copy(nome = it) },
                    label = { Text("Nome Obiettivo") },
                    placeholder = { Text("Es: Dieta Primavera 2026") },
                    singleLine = true,
                    enabled = !isClosed,
                    modifier = Modifier.fillMaxWidth(),
                )

                Tendina(
                    etichetta = "Tipo Obiettivo",
                    scelto = if (bozza.tipo == "mantenere") "⚖️ Mantenere peso" else "📉 Perdere peso",
                    voci = listOf("perdere" to "📉 Perdere peso", "mantenere" to "⚖️ Mantenere peso"),
                    abilitata = !isClosed,
                ) { scelta -> bozza = bozza.copy(tipo = scelta) }

                if (bozza.tipo == "mantenere") {
                    Titoletto("Data Inizio")
                    Bottone(dataItaliana(bozza.mantInizio.toString()), enabled = !isClosed) {
                        scegliData(context, bozza.mantInizio) { bozza = bozza.copy(mantInizio = it) }
                    }
                    CampoIntero(bozza.mantSettimane, "Numero Settimane", chiaveForm, enabled = !isClosed) {
                        bozza = bozza.copy(mantSettimane = it.coerceAtLeast(1))
                    }
                    CampoDecimale(bozza.mantPeso, "Peso da Mantenere (kg)", chiaveForm, enabled = !isClosed) {
                        bozza = bozza.copy(mantPeso = it)
                    }
                    Nota("Fine: ${dataItaliana(bozza.mantInizio.plusDays(bozza.mantSettimane * 7L).toString())}")
                }

                Titoletto("Punti")
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val meta = Modifier.weight(1f)
                    CampoIntero(bozza.bonusGiornaliero, "Bonus Giornaliero", chiaveForm, meta, !isClosed) {
                        bozza = bozza.copy(bonusGiornaliero = it)
                    }
                    CampoIntero(bozza.malusGiornaliero, "Malus Giornaliero", chiaveForm, meta, !isClosed) {
                        bozza = bozza.copy(malusGiornaliero = it)
                    }
                    CampoIntero(bozza.bonusFinale, "Bonus Finale", chiaveForm, meta, !isClosed) {
                        bozza = bozza.copy(bonusFinale = it)
                    }
                    CampoIntero(bozza.malusFinale, "Malus Finale", chiaveForm, meta, !isClosed) {
                        bozza = bozza.copy(malusFinale = it)
                    }
                }

                MassaGrassa(bozza = bozza, chiave = chiaveForm, enabled = !isClosed) { bozza = it }

                PesataEPromemoria(
                    bozza = bozza,
                    chiave = chiaveForm,
                    enabled = !isClosed,
                    inizio = editando?.inizio
                        ?: if (bozza.tipo == "mantenere") bozza.mantInizio.toString()
                        else bozza.traguardi.firstOrNull()?.giorno,
                    onCambia = { bozza = it },
                )

                if (bozza.tipo == "perdere") {
                    // I traguardi premio si calcolano dal peso salvato
                    // sull'obiettivo, non dalla bozza ancora in modifica —
                    // per un obiettivo nuovo, mai ancora salvato, non c'è
                    // niente da mostrare (come sul web, dove la barra legge
                    // `userData.currentObjective`).
                    editando?.let { obiettivo ->
                        BarraTraguardi(obiettivo = obiettivo, stato = stato, vm = vm)
                    }

                    val magraBozza = PesoRegole.massaMagra(
                        bozza.usaGrasso, bozza.percGrassoNum, bozza.traguardi.firstOrNull()?.peso,
                    )
                    Titoletto("Milestone Progressive")
                    if (bozza.traguardi.isEmpty()) {
                        Nota("Nessuna milestone impostata")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            bozza.traguardi.forEachIndexed { indice, t ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Palette.inputBg)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "📍 ${dataItaliana(t.giorno)} → ${kg(t.peso)} kg",
                                            fontWeight = FontWeight.SemiBold,
                                            color = Palette.dark,
                                        )
                                        // Il grasso previsto a quel traguardo, coi valori
                                        // del form ancora da salvare — come `updateMilestoneList()`.
                                        PesoRegole.notaGrassoPrevisto(t.peso, magraBozza)?.let {
                                            Text("🧈 $it", color = OroScuro, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (!isClosed) {
                                        Text(
                                            "🗑️",
                                            modifier = Modifier
                                                .clickable {
                                                    bozza = bozza.copy(
                                                        traguardi = bozza.traguardi.toMutableList()
                                                            .also { it.removeAt(indice) }
                                                    )
                                                }
                                                .padding(6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isClosed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Bottone(nuovaData?.let { dataItaliana(it.toString()) } ?: "Data") {
                                scegliData(context, nuovaData ?: LocalDate.now()) { nuovaData = it }
                            }
                            OutlinedTextField(
                                value = nuovoPeso,
                                onValueChange = { nuovoPeso = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(6) },
                                label = { Text("Peso (kg)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        val pesoNuovo = nuovoPeso.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
                        Bottone(
                            "➕ Aggiungi Milestone",
                            enabled = nuovaData != null && pesoNuovo != null,
                        ) {
                            val data = nuovaData
                            if (data != null && pesoNuovo != null) {
                                bozza = bozza.copy(
                                    traguardi = (bozza.traguardi + Traguardo(data.toString(), pesoNuovo))
                                        .sortedBy { it.giorno }
                                )
                                nuovaData = null
                                nuovoPeso = ""
                            }
                        }
                    }
                }

                HorizontalDivider(color = Palette.border)

                val larghezzaAzioni = larghezzaPulsanti(
                    listOf("💾 Salva", "🏆 Successo", "💀 Fallito", "🗑️ Elimina", "↩️ Riapri")
                )
                RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                    val meta = Modifier.width(larghezzaAzioni)
                    Bottone("💾 Salva", meta, Verde, enabled = !isClosed && bozza.valida) {
                        vm.salvaObiettivo(editando?.id, bozza)
                    }
                    Bottone("🏆 Successo", meta, Verde, enabled = !isClosed && editando != null) {
                        val obiettivo = editando!!
                        vm.preparaChiusura(obiettivo, "success") { esito ->
                            when (esito) {
                                is EsitoChiusura.Bloccata -> bloccataChiusura = esito.motivo
                                is EsitoChiusura.DaConfermare -> confermaChiusura = Triple(obiettivo, "success", esito)
                            }
                        }
                    }
                    Bottone("💀 Fallito", meta, Palette.danger, enabled = !isClosed && editando != null) {
                        val obiettivo = editando!!
                        vm.preparaChiusura(obiettivo, "failed") { esito ->
                            when (esito) {
                                is EsitoChiusura.Bloccata -> bloccataChiusura = esito.motivo
                                is EsitoChiusura.DaConfermare -> confermaChiusura = Triple(obiettivo, "failed", esito)
                            }
                        }
                    }
                    Bottone("🗑️ Elimina", meta, Palette.muted, enabled = editando != null) {
                        daEliminare = editando
                    }
                    // ↩️ Solo su un obiettivo chiuso: è l'unico gesto che lì si può fare.
                    if (isClosed) {
                        Bottone("↩️ Riapri", meta, Palette.secondary, enabled = true) {
                            daRiaprire = editando
                        }
                    }
                }

                if (!bozza.valida) {
                    Nota(
                        when {
                            !bozza.promemoriaValido -> "Scegli almeno un canale per la notifica."
                            bozza.tipo == "mantenere" -> "Servono nome, settimane e peso da mantenere."
                            else -> "Serve un nome e almeno due milestone (inizio e fine)."
                        }
                    )
                }
            }

            stato.messaggio?.let { messaggio ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Palette.dark),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(messaggio, color = Palette.light)
                        Text(
                            "✕",
                            color = Palette.light,
                            modifier = Modifier.clickable { vm.messaggioMostrato() },
                        )
                    }
                }
            }
        }
    }

    bloccataChiusura?.let { motivo ->
        AlertDialog(
            onDismissRequest = { bloccataChiusura = null },
            title = { Text("Non puoi chiudere l'obiettivo") },
            text = { Text(motivo) },
            confirmButton = { TextButton(onClick = { bloccataChiusura = null }) { Text("Ho capito") } },
        )
    }

    confermaChiusura?.let { (obiettivo, nuovoStato, esito) ->
        val successo = nuovoStato == "success"
        AlertDialog(
            onDismissRequest = { confermaChiusura = null },
            title = { Text(if (successo) "🏆 Chiudere come SUCCESSO?" else "💀 Chiudere come FALLITO?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Punti giornalieri: ${esito.puntiGiornalieri}")
                    Text(
                        "Traguardi raggiunti: ${esito.traguardiRaggiunti}/${esito.traguardiTotali} → " +
                            if (successo) "+${esito.puntiTraguardi}" else "0 (si incassano solo col successo)"
                    )
                    Text("Punti chiusura: ${if (esito.puntiChiusura >= 0) "+" else ""}${esito.puntiChiusura}")
                    Text("TOTALE FINALE: ${esito.totale}", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.chiudiObiettivo(obiettivo, nuovoStato)
                    confermaChiusura = null
                }) { Text("Chiudi", color = if (successo) Verde else Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confermaChiusura = null }) { Text("Annulla") } },
        )
    }

    daRiaprire?.let { obiettivo ->
        val punti = obiettivo.punteggioFinale ?: 0
        AlertDialog(
            onDismissRequest = { daRiaprire = null },
            title = { Text("↩️ Riaprire l'obiettivo?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("«${obiettivo.nome}» torna attivo.")
                    Text("Punti attribuiti alla chiusura: $punti")
                    if (punti != 0) Text("Verranno TOLTI dal totale (il punteggio torna a 0).", fontWeight = FontWeight.Bold)
                    Text("Richiudendolo, il conto si rifà da capo.")
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.riapriObiettivo(obiettivo); daRiaprire = null }) {
                    Text("Riapri", color = Palette.secondary)
                }
            },
            dismissButton = { TextButton(onClick = { daRiaprire = null }) { Text("Annulla") } },
        )
    }

    daEliminare?.let { obiettivo ->
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare l'obiettivo?") },
            text = {
                Text("«${obiettivo.nome}» sparisce per sempre. Questa azione è irreversibile.")
            },
            confirmButton = {
                TextButton(onClick = { vm.eliminaObiettivo(obiettivo); daEliminare = null }) {
                    Text("Elimina", color = Palette.danger)
                }
            },
            dismissButton = { TextButton(onClick = { daEliminare = null }) { Text("Annulla") } },
        )
    }
}

// ── Pesarsi ogni N giorni, e il promemoria ────────────────────────────────

/**
 * I campi di `objectiveWeighEvery` / `objectiveReminder*` del web, nello
 * stesso ordine e con le stesse regole di `leggiCampiPesata()`: N vuoto è la
 * regola di sempre (ogni giorno), e un promemoria acceso vuole almeno un
 * canale — una notifica che non arriva da nessuna parte non è un promemoria.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PesataEPromemoria(
    bozza: BozzaObiettivo,
    chiave: Any?,
    enabled: Boolean,
    inizio: String?,
    onCambia: (BozzaObiettivo) -> Unit,
) {
    val context = LocalContext.current
    var testo by remember(chiave) { mutableStateOf(bozza.ogniGiorni?.toString() ?: "") }

    Titoletto("⚖️ Pesati ogni quanti giorni")
    OutlinedTextField(
        value = testo,
        onValueChange = { nuovo ->
            val pulito = nuovo.filter { it.isDigit() }.take(3)
            testo = pulito
            // Vuoto o zero = la regola di sempre, come `weigh_every_days` NULL.
            onCambia(bozza.copy(ogniGiorni = pulito.toIntOrNull()?.takeIf { it >= 1 }))
        },
        label = { Text("Giorni (vuoto = ogni giorno)") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    val n = bozza.ogniGiorni
    Nota(
        if (n == null) "Vuoto: punti ogni giorno, e i giorni senza pesata si ricostruiscono."
        else buildString {
            append("Punti solo nei giorni di pesata, dal primo giorno dell'obiettivo ogni $n ")
            append(if (n == 1) "giorno" else "giorni")
            append(". Un giorno di pesata passato senza pesarti vale il malus.")
            if (inizio != null) {
                val finto = Obiettivo(
                    id = "", nome = "", tipo = bozza.tipo, inizio = inizio, fine = "",
                    pesoIniziale = null, pesoFinale = null, bonusGiornaliero = 0, malusGiornaliero = 0,
                    bonusFinale = 0, malusFinale = 0, stato = "active", punteggioFinale = null,
                    traguardi = emptyList(), ogniGiorni = n,
                )
                val oggi = LocalDate.now().toString()
                PesoRegole.prossimaPesata(finto, oggi)?.let {
                    append(" Prossima pesata: ${if (it == oggi) "oggi" else dataItaliana(it)}.")
                }
            }
        }
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCambia(bozza.copy(promemoriaAttivo = !bozza.promemoriaAttivo)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = bozza.promemoriaAttivo,
            onCheckedChange = { onCambia(bozza.copy(promemoriaAttivo = it)) },
            enabled = enabled,
        )
        Text("🔔 Ricordami di pesarmi", color = Palette.dark, fontWeight = FontWeight.SemiBold)
    }

    if (bozza.promemoriaAttivo) {
        Nota("Arriva solo nei giorni di pesata, all'orario scelto (ora italiana).")
        Bottone("🕒 ${bozza.promemoriaOra}", enabled = enabled) {
            val (h, m) = bozza.promemoriaOra.split(":").map { it.toIntOrNull() ?: 0 }.let {
                it.getOrElse(0) { 7 } to it.getOrElse(1) { 30 }
            }
            TimePickerDialog(
                context,
                { _, ora, minuti -> onCambia(bozza.copy(promemoriaOra = "%02d:%02d".format(ora, minuti))) },
                h, m, true,
            ).show()
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CANALI_PESATA.forEach { canale ->
                val scelto = canale in bozza.promemoriaCanali
                val cambia = {
                    onCambia(
                        bozza.copy(
                            promemoriaCanali = if (scelto) bozza.promemoriaCanali - canale
                            else bozza.promemoriaCanali + canale
                        )
                    )
                }
                Row(
                    Modifier.clickable(enabled = enabled) { cambia() }.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = scelto, onCheckedChange = { cambia() }, enabled = enabled)
                    Text(nomeCanale(canale), color = Palette.dark)
                }
            }
        }
    }
}

/** Il marrone-oro delle note sulla massa grassa: lo stesso `#8a6d00` del web. */
private val OroScuro = Color(0xFF8A6D00)

/**
 * 🧈 La massa grassa — il riquadro `objectiveFatMass` / `objectiveFatPct` del
 * web, con le stesse regole di `leggiCampiPesata()`: la % sta fra 3 e 70, e
 * senza spunta si salva NULL.
 */
@Composable
private fun MassaGrassa(
    bozza: BozzaObiettivo,
    chiave: Any?,
    enabled: Boolean,
    onCambia: (BozzaObiettivo) -> Unit,
) {
    var testo by remember(chiave) { mutableStateOf(bozza.percGrasso) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCambia(bozza.copy(usaGrasso = !bozza.usaGrasso)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = bozza.usaGrasso,
            onCheckedChange = { onCambia(bozza.copy(usaGrasso = it)) },
            enabled = enabled,
        )
        Text(
            "🧈 Misura sulla massa grassa (peso × % grasso corporeo)",
            color = Palette.dark,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (bozza.usaGrasso) {
        OutlinedTextField(
            value = testo,
            onValueChange = { nuovo ->
                val pulito = nuovo.filter { it.isDigit() || it == ',' || it == '.' }.take(5)
                testo = pulito
                onCambia(bozza.copy(percGrasso = pulito))
            },
            label = { Text("% grasso di partenza (al peso del primo traguardo)") },
            placeholder = { Text("es. 28") },
            singleLine = true,
            enabled = enabled,
            isError = !bozza.grassoValido,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!bozza.grassoValido) {
            Text(
                "Scrivi la % di grasso «normale», fra 3 e 70.",
                color = Palette.danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Nota(
            "Traguardi e peso finale si scrivono come sempre, in peso totale: la massa magra si ricava " +
                "dal primo traguardo e resta ferma, quindi il grasso previsto è peso − massa magra. " +
                "Punti e chiusura si contano sulla massa grassa, le stelline restano sul totale. " +
                "Le pesate senza grasso corporeo non contano."
        )
    }
}

// ── Selettori di sistema ─────────────────────────────────────────────────

private fun scegliData(context: Context, iniziale: LocalDate, poi: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, anno, meseZeroBased, giorno -> poi(LocalDate.of(anno, meseZeroBased + 1, giorno)) },
        iniziale.year,
        iniziale.monthValue - 1,
        iniziale.dayOfMonth,
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
internal fun Bottone(
    testo: String,
    modifier: Modifier = Modifier,
    colore: Color = Verde,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = testo,
        color = if (enabled) Palette.light else Palette.light.copy(alpha = 0.6f),
        fontWeight = FontWeight.SemiBold,
        // Stessa misura di `larghezzaPulsanti()`: se il testo fosse
        // disegnato con uno stile diverso da quello misurato, la larghezza
        // condivisa fra i pulsanti della riga non basterebbe più e la
        // scritta andrebbe a capo. `maxLines`/`softWrap` sono la rete di
        // sicurezza — coi caratteri di sistema grandi tagliano piuttosto che
        // spezzare la scritta su due righe.
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) colore else colore.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * Un numero intero, tenuto come testo mentre lo si scrive — come in
 * `TaFiriForm`. La chiave di reset è [chiave] e non [valore]: `valore` è
 * anche quello che [onCambia] riscrive ad ogni cifra, e tenerlo come chiave
 * farebbe ricomparire uno zero appena si prova a svuotare il campo.
 */
@Composable
private fun CampoIntero(
    valore: Int,
    etichetta: String,
    chiave: Any?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCambia: (Int) -> Unit,
) {
    var testo by remember(chiave) { mutableStateOf(valore.toString()) }
    OutlinedTextField(
        value = testo,
        onValueChange = { nuovo ->
            val pulito = nuovo.filter { it.isDigit() }.take(6)
            testo = pulito
            pulito.toIntOrNull()?.let(onCambia)
        },
        label = { Text(etichetta) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Un peso: virgola o punto, come nel campo della pesata di [DialogoPesata].
 * Stessa ragione di [CampoIntero] per [chiave] invece di [valore]: qui
 * sarebbe anche peggio, perché [kg] arrotonda a un decimale e riscriverebbe
 * «75» in «75.0» mentre lo si sta ancora scrivendo.
 */
@Composable
private fun CampoDecimale(
    valore: Double?,
    etichetta: String,
    chiave: Any?,
    enabled: Boolean = true,
    onCambia: (Double?) -> Unit,
) {
    var testo by remember(chiave) { mutableStateOf(valore?.let { kg(it) } ?: "") }
    OutlinedTextField(
        value = testo,
        onValueChange = { nuovo ->
            val pulito = nuovo.filter { it.isDigit() || it == ',' || it == '.' }.take(6)
            testo = pulito
            onCambia(pulito.replace(',', '.').toDoubleOrNull())
        },
        label = { Text(etichetta) },
        placeholder = { Text("Es: 75.0") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Gratta e vinci dei traguardi ────────────────────────────────────────

/**
 * La barra delle stelline dei traguardi intermedi —
 * `updateMilestoneProgressBar()` nel web.
 *
 * ⚠️ Punti totali e premi grattati **stanno su Supabase**
 * (`ps_milestone_points`, `ps_milestone_prizes`), non più nelle preferenze del
 * telefono: sono le stesse righe che legge e scrive `weight-quest.html`,
 * quindi il premio grattato qui si ritrova sul PC e il numero sotto la
 * stellina è uno solo. Arrivano già caricati in [PesoState] — questa barra li
 * disegna e basta.
 *
 * [modificabile] mostra anche il campo dei punti totali — solo in Gestione
 * Obiettivo, dove ha senso cambiarli. Nella Panoramica («Oggi») la barra
 * compare **sola lettura**: le stelline si toccano lo stesso per grattare un
 * premio già raggiunto, ma i punti si tarano solo da Gestisci.
 */
@Composable
internal fun BarraTraguardi(
    obiettivo: Obiettivo,
    stato: PesoState,
    vm: PesoViewModel,
    modificabile: Boolean = true,
) {
    val soglie = remember(obiettivo.pesoIniziale, obiettivo.pesoFinale) {
        PesoRegole.sogliePremio(obiettivo.pesoIniziale, obiettivo.pesoFinale)
    }
    if (soglie.isEmpty()) return

    val premiVinti = stato.premi
    val puntiPerSoglia = remember(soglie, stato.puntiTraguardi) {
        PesoRegole.distribuzionePunti(stato.puntiTraguardi, soglie.size)
    }
    // Quali soglie sono raggiunte lo dice `ps_punti`, la stessa RPC del web:
    // qui restano solo soglie e distribuzione, per mostrare dal vivo il totale
    // che si sta scrivendo.
    val raggiunte = stato.puntiDelServer?.raggiunte.orEmpty()

    var bigliettoAperto by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (modificabile) {
            CampoIntero(
                valore = stato.puntiTraguardi,
                etichetta = "⭐ Punti Totali Traguardi Intermedi",
                // La chiave cambia solo quando il valore arriva dal database,
                // mai mentre si digita: vedi PesoState.premiVersione.
                chiave = obiettivo.id to stato.premiVersione,
            ) { nuovo -> vm.salvaPuntiTraguardi(nuovo) }
        }

        RigaScorrevole(Arrangement.spacedBy(8.dp)) {
            soglie.forEachIndexed { indice, soglia ->
                val raggiunta = soglia in raggiunte
                val vinto = if (raggiunta) premiVinti[soglia] else null
                StellaTraguardo(
                    soglia = soglia,
                    raggiunta = raggiunta,
                    punti = puntiPerSoglia.getOrNull(indice) ?: 0,
                    premio = vinto?.let { premioDa(it.id) },
                    mangiato = vinto?.mangiato == true,
                    onTocca = { if (raggiunta) bigliettoAperto = soglia },
                )
            }
        }

        Nota(
            "Ogni stellina accesa dà anche un premio: resta evidenziata con 🎁 finché non la tocchi " +
                "e gratti il biglietto. Dopo il gratta puoi dire «Mangiato !!!» e il premio si spegne."
        )
    }

    bigliettoAperto?.let { soglia ->
        DialogoGrattaEVinci(
            soglia = soglia,
            vinto = premiVinti[soglia],
            onRivelato = { premio -> vm.grattaPremio(soglia, premio) },
            onMangiato = { mangiato -> vm.segnaMangiato(soglia, mangiato) },
            onChiudi = { bigliettoAperto = null },
        )
    }
}

/** Una stellina della barra — raggiunta (⭐, dorata) o no (☆, spenta). */
@Composable
private fun StellaTraguardo(
    soglia: Int,
    raggiunta: Boolean,
    punti: Int,
    premio: PremioCibo?,
    mangiato: Boolean,
    onTocca: () -> Unit,
) {
    val oro = Color(0xFFFFD700)
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (raggiunta) oro.copy(alpha = 0.16f) else Palette.inputBg)
            .border(2.dp, if (raggiunta) oro else Palette.border, RoundedCornerShape(10.dp))
            .clickable(enabled = raggiunta, onClick = onTocca)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(if (raggiunta) "⭐" else "☆", fontSize = 20.sp)
        Text(
            "< $soglia kg",
            fontSize = 9.sp,
            color = if (raggiunta) Color(0xFFB8860B) else Palette.muted,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        if (punti > 0) {
            Text(
                "+$punti",
                fontSize = 10.sp,
                color = if (raggiunta) Color(0xFFC9960C) else Palette.muted,
                fontWeight = FontWeight.Bold,
            )
        }
        if (raggiunta) {
            // Un premio mangiato resta vinto — la stellina non si spegne — ma
            // il cibo si segna col ✓ e sbiadisce: «vinto e mangiato» e «vinto e
            // ancora lì» sono due cose diverse. È lo stesso `.prize-used` del web.
            Text(
                text = if (premio == null) "🎁" else premio.emoji + if (mangiato) "✓" else "",
                fontSize = if (mangiato) 13.sp else 15.sp,
                color = if (mangiato) Palette.muted else Color.Unspecified,
            )
        }
    }
}
