package com.garsal.appsphere.calorie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.larghezzaPulsanti
import kotlin.math.roundToInt

/**
 * Il ➕: segnare un alimento nella giornata aperta.
 *
 * Due passi, come nella pagina — si cerca, e poi si dice quanto — perché quel
 * che si fa dopo aver trovato l'alimento non cambia a seconda di dove lo si è
 * trovato.
 *
 * ⚠️ **La distinzione che conta è una sola**: quella voce è già nel tuo
 * catalogo, oppure sta arrivando adesso da una banca dati pubblica? Il primo è
 * un dato tuo, già guardato e correggibile; il secondo l'ha scritto qualcun
 * altro, può mancare, può essere sbagliato, e quando lo scegli **entra in
 * archivio**. La porta l'icona in testa alla riga (📗 catalogo / 🌐 in rete),
 * che è dove cade l'occhio per primo, e le due provenienze stanno sotto **due
 * intestazioni** e non concatenate: una fila unica in cui il catalogo sfuma
 * nella rete le fa sembrare la stessa cosa.
 *
 * Restano sulla pagina web il **codice a barre** (qui non c'è né
 * `BarcodeDetector` né una fotocamera da accendere per questo) e lo
 * **scrivilo a mano**, che è il form di 🍎 Alimenti: sono le due strade per
 * mettere in dispensa, e la dispensa si cura da seduti.
 */
@Composable
internal fun AggiungiAlimentoScreen(
    stato: CalorieState,
    vm: CalorieViewModel,
    onChiudi: () -> Unit,
) {
    var scelto by remember { mutableStateOf<Alimento?>(null) }
    var pasto by remember { mutableStateOf(vm.pastoDellOra()) }

    scelto?.let { alimento ->
        SceltaPorzione(
            alimento = alimento,
            pasto = pasto,
            stato = stato,
            onPasto = { pasto = it },
            onIndietro = { scelto = null },
            onConferma = { grammi ->
                vm.aggiungi(alimento, grammi, pasto)
                onChiudi()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Aggiungi al ${dataLunga(stato.giorno)}",
                onIndietro = onChiudi,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            val ricerca = stato.ricerca

            OutlinedTextField(
                value = ricerca.testo,
                onValueChange = { vm.cerca(it) },
                label = { Text("Alimento o prodotto") },
                placeholder = { Text("pasta, yogurt greco, Nutella…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (ricerca.testo.isBlank()) {
                    // A campo vuoto si propongono i preferiti e i più usati:
                    // nove volte su dieci quel che si sta per segnare è già lì,
                    // e cercarlo sarebbe scrivere per niente.
                    val proposti = vm.proposti()
                    if (proposti.isNotEmpty()) {
                        item { Intestazione("📗 Nel tuo catalogo", "preferiti e più usati") }
                        items(proposti, key = { it.id ?: it.name }) { a ->
                            RigaRisultato(a) { scelto = a }
                        }
                        item {
                            Text(
                                "Scrivi per cercare fra i tuoi alimenti e in rete.",
                                color = Palette.muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                } else {
                    if (ricerca.locali.isNotEmpty()) {
                        item { Intestazione("📗 Nel tuo catalogo", "già in archivio") }
                        items(ricerca.locali, key = { it.id ?: it.name }) { a ->
                            RigaRisultato(a) { scelto = a }
                        }
                    }
                    if (ricerca.rete.isNotEmpty()) {
                        item {
                            Intestazione("🌐 Trovati in rete", "entrano in catalogo quando li scegli")
                        }
                        items(ricerca.rete) { a -> RigaRisultato(a) { scelto = a } }
                    }
                    if (ricerca.locali.isEmpty() && ricerca.rete.isEmpty()) {
                        item {
                            Text(
                                if (ricerca.inCorso) "🔎 Cerco…"
                                else ricerca.stato.ifBlank { "Niente trovato." },
                                color = Palette.muted,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    } else if (ricerca.inCorso) {
                        item {
                            Text(
                                "🔎 Cerco anche in rete…",
                                color = Palette.muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    } else if (ricerca.stato.isNotBlank()) {
                        item {
                            Text(
                                ricerca.stato,
                                color = Palette.muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Intestazione(titolo: String, nota: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(titolo, fontWeight = FontWeight.Bold, color = Palette.dark)
        Text(
            " — $nota",
            color = Palette.muted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** L'icona dice da dove viene, il badge dice quale fonte: due livelli, in quest'ordine. */
private fun iconaFonte(a: Alimento) = if (a.inCatalogo) "📗" else "🌐"

private fun etichettaFonte(a: Alimento): String = when {
    a.inCatalogo && a.source == "base" -> "Catalogo · base"
    a.inCatalogo && a.source == "manuale" -> "Catalogo · a mano"
    a.inCatalogo && a.source == "usda" -> "Catalogo · USDA"
    a.inCatalogo -> "Catalogo · Open Food Facts"
    a.source == "usda" -> "In rete · USDA"
    else -> "In rete · Open Food Facts"
}

@Composable
private fun RigaRisultato(a: Alimento, onScegli: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.inputBg)
            .clickable(onClick = onScegli)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(iconaFonte(a))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontWeight = FontWeight.SemiBold, color = Palette.dark)
            a.brand?.let {
                Text(it, color = Palette.muted, style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    etichettaFonte(a),
                    // L'ambra è l'unico colore che nessuna fonte interna usa: il
                    // colore ripete quel che dice l'icona, per chi guarda in fretta.
                    color = if (a.inCatalogo) Palette.muted else AmbraCalorie,
                    style = MaterialTheme.typography.labelMedium,
                )
                // ⚠️ La spunta si mostra **solo sulle righe del catalogo**: un
                // risultato di rete non è ancora in archivio, quindi nessuno ha
                // potuto verificarlo e un ✅ lì direbbe il falso. E solo quando
                // c'è: «non verificato» è lo stato normale di quasi tutto.
                if (a.inCatalogo && a.verified) {
                    Text("✅ verificato", color = VerdeCalorie, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "${kcalIt(a.kcal)} kcal/100 ${a.misura}",
                color = Palette.muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (a.favorite) Text("⭐")
    }
}

/** La scaletta fissa dei grammi, come nella pagina. */
private val GRAMMI_RAPIDI = listOf(30, 50, 100, 125, 150, 200, 250)

/**
 * ⚠️ I tagli dei liquidi non sono quelli dei solidi: un bicchiere è 200 ml, una
 * lattina 330, una bottiglietta 500. Sono gli stessi numeri della scaletta dei
 * grammi, ma non sono le stesse quantità — e va tenuta uguale a `ML_RAPIDI`
 * nella pagina.
 */
private val ML_RAPIDI = listOf(50, 100, 150, 200, 250, 330, 500)

private fun rapidiDi(u: String) = if (u == "ml") ML_RAPIDI else GRAMMI_RAPIDI

/** «250 ml» / «250 g». L'unità non entra mai nel conto: dice come si chiama. */
private fun qtaIt(n: Double?, u: String) = "${kgIt(n)} $u"

/**
 * I multipli della porzione di **questo** alimento. Vuota se non ne ha una: una
 * porzione inventata è peggio di nessuna porzione — chi la vede scritta le
 * crede, e sono le calorie della giornata.
 *
 * ⚠️ Singolare e plurale sono due colonne e non una parola declinata qui: in
 * italiano il plurale non si ricava a regola proprio dove serve di più — uovo →
 * uova — e una parola sbagliata a schermo si legge come un difetto dell'app. Il
 * plurale ripiega sul singolare, e senza etichetta si torna a «porzione /
 * porzioni», che è vero per qualunque cosa. Il mezzo si scrive **½** perché il
 * simbolo non ha genere: vale per l'uovo come per la pizza.
 */
private data class Porzione(val etichetta: String, val grammi: Int)

private fun porzioniDi(a: Alimento): List<Porzione> {
    val base = a.grammiPorzione ?: return emptyList()
    if (base <= 0) return emptyList()
    val uno = a.nomePorzione?.trim().orEmpty().ifBlank { "porzione" }
    val tanti = a.nomePorzioniPlurale?.trim().orEmpty().ifBlank {
        a.nomePorzione?.trim().orEmpty().ifBlank { "porzioni" }
    }
    return listOf(
        Porzione("½ $uno", (base / 2).roundToInt()),
        Porzione("1 $uno", base.roundToInt()),
        Porzione("2 $tanti", (base * 2).roundToInt()),
    ).filter { it.grammi >= 1 }
}

/**
 * Quanto ne hai mangiato.
 *
 * ⚠️ **Ogni pulsante si SOMMA a quel che c'è nel campo, non lo sostituisce**, e
 * accanto c'è un ↺ per ricominciare da zero: tre uova si segnano toccando «1
 * uovo» tre volte, e non esiste un pulsante per ogni quantità che si possa
 * voler mangiare. Sostituendo, il secondo tocco non farebbe niente di visibile
 * — si leggerebbe come un tocco non passato. Il campo parte comunque dalla
 * porzione abituale, che è il caso di gran lunga più frequente e non costa
 * nessun tocco.
 */
@Composable
private fun SceltaPorzione(
    alimento: Alimento,
    pasto: String,
    stato: CalorieState,
    onPasto: (String) -> Unit,
    onIndietro: () -> Unit,
    onConferma: (Double) -> Unit,
) {
    /* Grammi o millilitri lo dice l'alimento, non questa schermata: la scelta si
       fa da 🍎 Alimenti sul web, una volta sola, e da qui si legge soltanto.

       ⚠️ I due pulsanti g/ml QUI sono già stati provati (v1.0.63) e tolti subito:
       questa schermata risponde a una domanda sola — «quanto ne hai mangiato?» —
       e una scelta che riguarda com'è fatto l'alimento, con la riga che la
       spiega, si prendeva mezzo schermo per una cosa che non si stava
       chiedendo. Coi caratteri di sistema grandi il campo della quantità
       finiva sotto la piega. Se un giorno servirà cambiarla dal telefono, il
       posto è una schermata degli alimenti, non il caricamento del pasto. */
    val u = alimento.misura
    val porzioni = remember(alimento) { porzioniDi(alimento) }
    var testo by remember(alimento) {
        mutableStateOf((porzioni.getOrNull(1)?.grammi ?: 100).toString())
    }
    val grammi = testo.replace(',', '.').toDoubleOrNull()

    fun somma(quanti: Int) {
        val ora = testo.replace(',', '.').toDoubleOrNull() ?: 0.0
        // Un decimale: gli scalini sono interi, ma il campo si scrive anche a
        // mano (12,5 g d'olio) e senza arrotondare verrebbero fuori le code
        // binarie.
        val nuovo = Math.round((ora + quanti) * 10.0) / 10.0
        testo = if (nuovo == nuovo.roundToInt().toDouble()) nuovo.roundToInt().toString()
        else nuovo.toString()
    }

    Scaffold(
        topBar = { GarsalTopBar(titolo = "Quanto ne hai mangiato?", onIndietro = onIndietro) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Riquadro {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(iconaFonte(alimento))
                        Text(alimento.name, fontWeight = FontWeight.Bold, color = Palette.dark)
                    }
                    alimento.brand?.let { Text(it, color = Palette.muted) }
                    Text(
                        etichettaFonte(alimento) + " · ${kcalIt(alimento.kcal)} kcal per 100 $u" +
                            (alimento.quantita?.let { " · confezione $it" } ?: ""),
                        color = if (alimento.inCatalogo) Palette.muted else AmbraCalorie,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!alimento.inCatalogo) {
                        Nota(
                            if (alimento.entraInCatalogo)
                                "Non è ancora fra i tuoi alimenti: lo aggiungo al catalogo insieme " +
                                    "alla riga del diario, così la prossima volta è già qui."
                            else
                                "I valori restano su questa riga del diario, ma il prodotto non entra " +
                                    "nel catalogo: dovrai ricercarlo la prossima volta."
                        )
                    }
                }
            }

            item {
                Riquadro {
                    OutlinedTextField(
                        value = testo,
                        onValueChange = { testo = it },
                        label = { Text(if (u == "ml") "Quantità (millilitri)" else "Peso (grammi)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        if (porzioni.isEmpty())
                            "Per questo alimento non è scritta nessuna porzione abituale: parto da " +
                                "100 $u. La si scrive una volta sola da 🍎 Alimenti sul web, e da lì in " +
                                "poi la trovi qui. Ogni pulsante si somma a quel che c'è scritto; ↺ azzera."
                        else
                            "Porzione abituale: ${porzioni[1].etichetta} · ${porzioni[1].grammi} $u — il " +
                                "campo parte da lì. Ogni pulsante si somma a quel che c'è scritto; ↺ azzera.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    // ⚠️ La scaletta fissa si toglie i valori che le porzioni già
                    // coprono: due pulsanti «150 g» uno accanto all'altro sembrano
                    // due scelte diverse e non lo sono.
                    val rapidi = rapidiDi(u).filterNot { v -> porzioni.any { it.grammi == v } }
                    val etichette = porzioni.map { "${it.etichetta} · ${it.grammi} $u" } +
                        rapidi.map { "$it $u" } + "↺"
                    val larghezza = larghezzaPulsanti(etichette)

                    if (porzioni.isNotEmpty()) {
                        RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                            porzioni.forEach { p ->
                                Pillola("${p.etichetta} · ${p.grammi} $u", VerdeCalorie, larghezza) {
                                    somma(p.grammi)
                                }
                            }
                        }
                    }
                    RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                        rapidi.forEach { v ->
                            Pillola("$v $u", Palette.muted, larghezza) { somma(v) }
                        }
                        Pillola("↺", Palette.dark, larghezza) { testo = "" }
                    }
                }
            }

            item {
                Riquadro {
                    Tendina(
                        etichetta = "Pasto",
                        scelto = Pasti.opzioni(stato.pastiConfigurati, pasto)
                            .firstOrNull { it.id == pasto }?.etichetta ?: pasto,
                        voci = Pasti.opzioni(stato.pastiConfigurati, pasto).map { it.id to it.etichetta },
                        onScegli = onPasto,
                    )
                    Text(
                        "Va nel ${dataLunga(stato.giorno)}.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Anteprima(alimento, grammi, u)
            }

            item {
                RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                    val larghezza = larghezzaPulsanti(listOf("➕ Aggiungi", "Annulla"))
                    Pillola(
                        "➕ Aggiungi",
                        if (grammi != null && grammi > 0) VerdeCalorie else Palette.muted,
                        larghezza,
                    ) {
                        if (grammi != null && grammi > 0) onConferma(grammi)
                    }
                    Pillola("Annulla", Palette.muted, larghezza, onClick = onIndietro)
                }
            }
        }
    }
}

@Composable
private fun Anteprima(alimento: Alimento, grammi: Double?, u: String) {
    val g = grammi ?: 0.0
    fun per(valore: Double?): Double? = valore?.let { it * g / 100 }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VerdeCalorie.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Column {
            Text(
                "${kcalIt(per(alimento.kcal))} kcal per ${qtaIt(g, u)}",
                fontWeight = FontWeight.Bold,
                color = Palette.dark,
            )
            Text(
                "grassi ${kgIt(per(alimento.grassi))} g · zuccheri ${kgIt(per(alimento.zuccheri))} g " +
                    "· proteine ${kgIt(per(alimento.proteine))} g",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
