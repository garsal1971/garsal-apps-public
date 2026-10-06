package com.garsal.appsphere.calorie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.larghezzaPulsanti

/**
 * 📓 Il Diario: un giorno per volta, coi pasti configurati.
 *
 * ⚠️ **I riquadri dei pasti ci sono in ogni giorno, anche vuoti e anche nel
 * passato.** Un pasto senza righe non è solo un riepilogo che si può
 * nascondere: porta il ➕ con cui si aggiunge a *quel* pasto. In un giorno
 * passato ancora tutto da segnare — cioè proprio il caso in cui un giorno
 * passato si apre — non ne resterebbe nemmeno uno, e la pagina finirebbe dopo
 * la scritta «I pasti», che si legge come «qui non si può scrivere». Segnare
 * ieri quel che si è mangiato ieri è il caso normale, non quello strano.
 */
@Composable
internal fun VistaDiario(stato: CalorieState, vm: CalorieViewModel) {
    val giorno = stato.giorno
    val target = stato.target(giorno)
    val righe = stato.righeDelGiorno
    val totali = CalorieRegole.totali(righe)
    val mangiate = totali.kcal
    val restano = target.kcal?.let { it - mangiate }
    val sforato = restano != null && restano < 0
    val quota = percentuale(mangiate, target.kcal ?: 0.0)
    val colore = when {
        !target.ok -> Palette.muted
        sforato -> RossoCalorie
        quota > 85 -> Palette.warning
        else -> VerdeCalorie
    }

    var rigaDaCambiare by remember { mutableStateOf<RigaDiario?>(null) }
    var rigaDaTogliere by remember { mutableStateOf<RigaDiario?>(null) }
    var chiedeRicopia by remember { mutableStateOf(false) }
    var chiedeRicalcolo by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { NavigazioneGiorno(stato, vm) }

        item {
            Riquadro {
                Valore(
                    etichetta = "Target",
                    valore = kcalIt(target.kcal),
                    nota = if (target.ok) "kcal" + (if (target.congelato) " · congelato" else "")
                    else "da calcolare",
                )
                Valore(etichetta = "Mangiate", valore = kcalIt(mangiate), nota = "kcal · ${righe.size} " +
                    (if (righe.size == 1) "riga" else "righe"))
                Valore(
                    etichetta = if (sforato) "Sforate" else "Restano",
                    valore = restano?.let { kcalIt(kotlin.math.abs(it)) } ?: "—",
                    nota = if (restano == null) null else "kcal",
                    colore = colore,
                )
                Valore(
                    etichetta = "Peso",
                    valore = kgIt(target.peso),
                    nota = if (target.peso == null) "nessuna pesata"
                    else "kg" + (target.pesoPiano?.let { " · piano ${kgIt(it)}" } ?: ""),
                )
                BarraTarget(quota, colore)
                SpiegazioneTarget(target, stato, onRicalcola = { chiedeRicalcolo = true })
            }
        }

        // ⚠️ «Ricopia da ieri» compare **solo se ieri ha davvero qualcosa**, col
        // conteggio sul pulsante: uno che c'è sempre e quasi mai fa qualcosa si
        // smette di guardarlo. Resta offerto anche se oggi ha già delle righe —
        // mangiare due volte la stessa cosa capita — ma allora AGGIUNGE, e la
        // conferma lo dice invece di lasciarlo scoprire dal totale.
        val ieri = vm.righeDiIeri()
        if (ieri.isNotEmpty()) {
            item {
                RigaScorrevole(Arrangement.Start) {
                    Pillola("📋 Ricopia da ieri (${ieri.size})", Palette.accent) { chiedeRicopia = true }
                }
            }
        }

        items(stato.pastiDelGiorno(), key = { it.id }) { pasto ->
            val delPasto = righe.filter { it.meal == pasto.id }
            RiquadroPasto(
                pasto = pasto,
                righe = delPasto,
                onCambia = { rigaDaCambiare = it },
                onTogli = { rigaDaTogliere = it },
            )
        }

        if (righe.isNotEmpty()) {
            item { RiquadroMacro(totali) }
        }
    }

    rigaDaCambiare?.let { riga ->
        DialogoGrammi(
            riga = riga,
            onAnnulla = { rigaDaCambiare = null },
            onConferma = { grammi ->
                vm.cambiaGrammi(riga, grammi)
                rigaDaCambiare = null
            },
        )
    }

    rigaDaTogliere?.let { riga ->
        AlertDialog(
            onDismissRequest = { rigaDaTogliere = null },
            title = { Text("Togliere dal diario?") },
            text = { Text("«${riga.name}» — ${kgIt(riga.grams)} ${riga.misura}, ${kcalIt(riga.kcalRiga)} kcal.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.elimina(riga)
                    rigaDaTogliere = null
                }) { Text("Togli", color = Palette.danger) }
            },
            dismissButton = {
                TextButton(onClick = { rigaDaTogliere = null }) { Text("Annulla") }
            },
        )
    }

    if (chiedeRicopia) {
        val ieri = vm.righeDiIeri()
        AlertDialog(
            onDismissRequest = { chiedeRicopia = false },
            title = { Text("Ricopiare da ieri?") },
            text = {
                Text(
                    "Le ${ieri.size} righe del ${dataLunga(CalorieRegole.piuGiorni(giorno, -1))} " +
                        "finiscono nel ${dataLunga(giorno)}." +
                        (if (righe.isNotEmpty())
                            "\n\nLe ${righe.size} già segnate restano dove sono: queste si aggiungono."
                        else "")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.ricopiaDaIeri()
                    chiedeRicopia = false
                }) { Text("Ricopia") }
            },
            dismissButton = { TextButton(onClick = { chiedeRicopia = false }) { Text("Annulla") } },
        )
    }

    if (chiedeRicalcolo) {
        AlertDialog(
            onDismissRequest = { chiedeRicalcolo = false },
            title = { Text("Ricalcolare il target?") },
            text = {
                Text(
                    "Il valore congelato di questa giornata verrà sostituito con quello " +
                        "calcolato sui dati di adesso: se la giornata è passata, cambia il " +
                        "giudizio già dato."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.ricalcola(giorno)
                    chiedeRicalcolo = false
                }) { Text("Ricalcola") }
            },
            dismissButton = { TextButton(onClick = { chiedeRicalcolo = false }) { Text("Annulla") } },
        )
    }
}

/**
 * ‹ oggi ›, con il fondo del diario rispettato: il ‹ si spegne al **primo
 * giorno della dieta**, perché più indietro le righe non vengono caricate e una
 * giornata vuota direbbe «non hai segnato niente» invece di «non lo so».
 */
@Composable
private fun NavigazioneGiorno(stato: CalorieState, vm: CalorieViewModel) {
    val giorno = stato.giorno
    val larghezza = larghezzaPulsanti(listOf("‹", "›", "Oggi"))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            dataLunga(giorno) + (if (giorno == stato.oggi) " · oggi" else ""),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.dark,
        )
        RigaScorrevole(Arrangement.spacedBy(8.dp)) {
            Pillola(
                "‹",
                if (giorno > stato.primoGiorno) Palette.dark else Palette.muted,
                larghezza,
            ) { vm.giornoPrecedente() }
            Pillola(
                "›",
                if (giorno < stato.oggi) Palette.dark else Palette.muted,
                larghezza,
            ) { vm.giornoSuccessivo() }
            if (giorno != stato.oggi) {
                Pillola("Oggi", VerdeCalorie, larghezza) { vm.vaiAOggi() }
            }
        }
    }
}

/**
 * Il riquadro che spiega da dove esce il target. Non è decorazione: un numero
 * calcolato da quattro grandezze che nessuno vede è un numero di cui non ci si
 * fida, e alla prima sorpresa si smette di seguirlo.
 */
@Composable
private fun SpiegazioneTarget(
    target: CalorieRegole.Target,
    stato: CalorieState,
    onRicalcola: () -> Unit,
) {
    if (!target.ok) {
        Nota("Il target di oggi non si può calcolare: ${spiegaMotivo(target.motivo)}.", Palette.warning)
        return
    }

    val testo = when (target.motivo) {
        CalorieRegole.Motivo.MANTENIMENTO ->
            "Nessun obiettivo attivo in «Ti pisasti?»: il target è il mantenimento — " +
                "basale ${kcalIt(target.bmr)} × attività = ${kcalIt(target.tdee)} kcal."
        CalorieRegole.Motivo.RAGGIUNTO ->
            "Il peso finale è già raggiunto: il target torna al mantenimento, ${kcalIt(target.tdee)} kcal."
        CalorieRegole.Motivo.CONGELATO ->
            "Target congelato per questa giornata: ${kcalIt(target.kcal)} kcal" +
                (target.tdee?.let { " (consumo stimato ${kcalIt(it)} − deficit ${kcalIt(target.deficit)})" } ?: "") +
                (target.peso?.let { ", sul peso di ${kgIt(it)} kg." } ?: ".")
        else ->
            "Consumo stimato ${kcalIt(target.tdee)} kcal (basale ${kcalIt(target.bmr)} × attività " +
                "${ritmoIt(stato.profilo.attivita)}) − ${kcalIt(target.deficit)} kcal di deficit " +
                "= ${kcalIt(target.kcal)} kcal."
    }
    Nota(testo + if (target.motivo == CalorieRegole.Motivo.OBIETTIVO) spiegaDeficit(target) else "")

    target.avvisi.forEach { Nota("⚠️ $it", RossoCalorie) }

    // Il ricalcolo si offre solo dove c'è davvero qualcosa di congelato da
    // sostituire, e passa da una conferma: di qui si riscrive un giudizio già dato.
    if (target.congelato) {
        RigaScorrevole(Arrangement.Start) {
            Pillola("🔄 Ricalcola questa giornata", Palette.muted, onClick = onRicalcola)
        }
    }
}

/**
 * Da dove viene il deficit: il ritmo del tratto e il recupero dello scarto. Due
 * frasi e non un numero solo, perché «oggi il target è più stretto» ha due
 * cause diverse — il piano che corre in questo tratto, e l'essere rimasti
 * indietro — e sapere quale delle due è cambia cosa si fa.
 */
private fun spiegaDeficit(t: CalorieRegole.Target): String {
    val seg = t.segmento
    if (seg == null || t.deficitPiano == null) {
        return " Il deficit sono i ${kgIt(t.kgDaPerdere)} kg che mancano al peso finale " +
            "(${kgIt(t.pesoFinale)} kg) spalmati sui ${t.giorniRimasti} giorni che restano."
    }
    val settimana = seg.kgAlGiorno * 7
    var testo = "\n\nIl deficit viene da due cose. Il tratto ${seg.numero} di ${seg.quanti} del piano " +
        "(${dataBreve(seg.inizio)} → ${dataBreve(seg.fine)}) chiede ${ritmoIt(settimana)} kg a settimana, " +
        "cioè ${kcalIt(t.deficitPiano)} kcal al giorno"
    val scarto = t.scartoKg
    testo += if (scarto == null || kotlin.math.abs(scarto) < 0.05) {
        "; sei esattamente sulla curva del piano, quindi non c'è niente da recuperare."
    } else {
        val indietro = scarto > 0
        ". Sei ${if (indietro) "indietro" else "avanti"} di ${kgIt(kotlin.math.abs(scarto))} kg sulla curva: " +
            "${if (indietro) "+" else "−"}${kcalIt(kotlin.math.abs(t.recupero ?: 0.0))} kcal per " +
            "${if (indietro) "recuperarli" else "tenerne conto"}, spalmati sui ${t.giorniRimasti} giorni " +
            "che restano fino alla fine — non su quelli del tratto, o a ridosso di un traguardo il " +
            "recupero diventerebbe feroce."
    }
    if (t.deficit == 0.0) testo += " Con questo vantaggio il deficit si ferma a zero: oggi basta il mantenimento."
    return testo
}

@Composable
private fun RiquadroPasto(
    pasto: Pasto,
    righe: List<RigaDiario>,
    onCambia: (RigaDiario) -> Unit,
    onTogli: (RigaDiario) -> Unit,
) {
    val kcal = righe.sumOf { it.kcalRiga }
    Riquadro {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(pasto.etichetta, fontWeight = FontWeight.Bold, color = Palette.dark)
                if (pasto.tolto) {
                    Text(
                        "pasto tolto",
                        color = Palette.warning,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                "${kcalIt(kcal)} kcal",
                color = Palette.muted,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (righe.isEmpty()) {
            Text("Niente ancora.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            righe.forEach { riga ->
                RigaAlimento(riga, onCambia = { onCambia(riga) }, onTogli = { onTogli(riga) })
            }
        }
    }
}

/**
 * ⚠️ **✏️ e 🗑 stanno a sinistra del record**, mai in coda: in una riga che
 * scorre — cioè ogni riga su un telefono — quel che sta a destra è oltre il
 * bordo, e per raggiungerlo bisogna già sapere che c'è dell'altro. Dentro il
 * gruppo la più usata per prima, e la distruttiva non sul bordo.
 */
@Composable
private fun RigaAlimento(riga: RigaDiario, onCambia: () -> Unit, onTogli: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.inputBg)
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("✏️", modifier = Modifier.clickable(onClick = onCambia).padding(4.dp))
        Text("🗑", modifier = Modifier.clickable(onClick = onTogli).padding(4.dp))
        Column(Modifier.weight(1f)) {
            Text(riga.name, color = Palette.dark, fontWeight = FontWeight.SemiBold)
            riga.brand?.let {
                Text(it, color = Palette.muted, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${kgIt(riga.grams)} ${riga.misura} · ${kcalIt(riga.kcal)} kcal/100 ${riga.misura}",
                color = Palette.muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            kcalIt(riga.kcalRiga),
            color = Palette.dark,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * I macro della giornata.
 *
 * ⚠️ I grammi si contano solo dove l'alimento li dichiara: un prodotto senza
 * fibre nell'etichetta conta zero fibre, che non vuol dire che non ne abbia —
 * è la stessa distinzione fra «non lo so» e «zero» che governa tutta l'app.
 */
@Composable
private fun RiquadroMacro(totali: CalorieRegole.Totali) {
    Riquadro(titolo = "Cosa c'era dentro") {
        Valore("Proteine", "${kgIt(totali.proteine)} g")
        Valore("Grassi", "${kgIt(totali.grassi)} g")
        Valore("di cui saturi", "${kgIt(totali.saturi)} g")
        Valore("Carboidrati", "${kgIt(totali.carboidrati)} g")
        Valore("di cui zuccheri", "${kgIt(totali.zuccheri)} g")
        Valore("Fibre", "${kgIt(totali.fibre)} g")
        Valore("Sale", "${kgIt(totali.sale)} g")
        Nota(
            "Proteine e carboidrati valgono 4 kcal al grammo, i grassi 9: è per questo che un " +
                "cucchiaio d'olio pesa poco e conta tanto. I grammi si contano solo dove " +
                "l'alimento li dichiara."
        )
    }
}

@Composable
private fun DialogoGrammi(riga: RigaDiario, onAnnulla: () -> Unit, onConferma: (Double) -> Unit) {
    var testo by remember { mutableStateOf(kgIt(riga.grams)) }
    val grammi = testo.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(riga.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = testo,
                    onValueChange = { testo = it },
                    // ⚠️ L'unità è quella congelata sulla RIGA, non quella
                    // dell'alimento di adesso: correggere la quantità di una riga
                    // vecchia non deve cambiare in che unità era stata segnata.
                    label = { Text(if (riga.misura == "ml") "Quanti millilitri?" else "Quanti grammi?") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                if (grammi != null && grammi > 0) {
                    Text(
                        "${kcalIt(riga.kcal?.times(grammi)?.div(100))} kcal",
                        color = Palette.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = grammi != null && grammi > 0,
                onClick = { grammi?.let(onConferma) },
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}
