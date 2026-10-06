package com.garsal.appsphere.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.TendinaFacoltativa
import com.garsal.appsphere.core.coloreDaHex
import com.garsal.appsphere.core.larghezzaPulsanti
import java.time.LocalDate

/**
 * Creazione e modifica di un task.
 *
 * **Il tipo di un task che esiste già non si cambia** (`id != null`): decide
 * quali colonne quel task ha, e passare da `multiple` a `recurring`
 * lascerebbe dietro `multiple_dates` che nessuno ripulisce.
 *
 * ⚠️ **I `workflow` non passano da qui.** I loro step hanno dipendenze fra
 * loro (`depends_on`), e lo stato di ognuno — `locked`, `active`, `completed`
 * — si ricalcola da quel grafo a ogni salvataggio: un form che lo semplificasse
 * in un elenco ordinato riscriverebbe `workflow_steps` in una forma che
 * `tasks.html` non sa più leggere. Si creano e si modificano di là;
 * completarli, saltarli e farli fallire funziona anche da qui, perché quella
 * parte la fa la RPC.
 */
@Composable
fun TaskForm(
    bozzaIniziale: BozzaTask,
    id: String?,
    categorie: List<CmCategoria>,
    priorita: List<CmPriorita>,
    onAnnulla: () -> Unit,
    onSalva: (BozzaTask) -> Unit,
) {
    var b by remember { mutableStateOf(bozzaIniziale) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = if (id == null) "Nuovo task" else "Modifica task",
                onIndietro = onAnnulla,
                azioni = {
                    Text(
                        text = "Salva",
                        color = if (b.valida) Palette.light else Palette.light.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = b.valida) { onSalva(b) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = b.titolo,
                onValueChange = { b = b.copy(titolo = it) },
                label = { Text("Titolo") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = b.descrizione,
                onValueChange = { b = b.copy(descrizione = it) },
                label = { Text("Descrizione") },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Tipo ────────────────────────────────────────────────────
            // I `workflow` non passano da qui, e il tipo di un task che
            // esiste già non si tocca: vedi il commento in cima — la tendina
            // resta quindi disabilitata (mostra solo il tipo attuale) quando
            // si sta modificando un task esistente.
            Tendina(
                etichetta = "Tipo",
                scelto = TsTask.etichettaTipo(b.tipo),
                voci = TsTask.TIPI.filter { it.first != "workflow" },
                abilitata = id == null,
            ) { scelta -> b = b.copy(tipo = scelta) }
            if (id != null) {
                Nota("Il tipo di un task che esiste già non si cambia da qui.")
            }

            // ── Quando ──────────────────────────────────────────────────
            Titoletto(if (b.tipo == "multiple") "Orario delle occorrenze" else "Data e ora")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (b.tipo != "multiple") {
                    Bottone(dataItaliana(b.giorno.toString())) {
                        scegliData(context, b.giorno) { b = b.copy(giorno = it) }
                    }
                }
                Bottone(b.ora) { scegliOra(context, b.ora) { b = b.copy(ora = it) } }
            }

            when (b.tipo) {
                "single" -> {
                    Titoletto("Scadenza (facoltativa)")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Bottone(b.scadenza?.let { dataItaliana(it.toString()) } ?: "Nessuna") {
                            scegliData(context, b.scadenza ?: b.giorno) { b = b.copy(scadenza = it) }
                        }
                        if (b.scadenza != null) {
                            Bottone("Togli") { b = b.copy(scadenza = null) }
                        }
                    }
                }

                "simple_recurring" -> {
                    Titoletto("Si ripete dopo")
                    CampoNumero(b.ripetiDopoGiorni, "Giorni") {
                        b = b.copy(ripetiDopoGiorni = it.coerceAtLeast(1))
                    }
                    Nota("Il conto riparte da quando lo completi, non dalla data fissa.")
                }

                "recurring" -> {
                    Tendina(
                        etichetta = "Frequenza",
                        scelto = FREQUENZE.first { it.first == b.frequenza }.second,
                        voci = FREQUENZE,
                    ) { scelta -> b = b.copy(frequenza = scelta) }
                    CampoNumero(b.intervallo, "Ogni quante volte") {
                        b = b.copy(intervallo = it.coerceAtLeast(1))
                    }

                    when (b.frequenza) {
                        "weekly" -> {
                            Titoletto("Giorni della settimana")
                            // Mostrati da lunedì, che è come si legge una
                            // settimana qui; salvati con la numerazione di
                            // Postgres (`extract(dow)`, 0 = domenica), che
                            // è quella che `task_next_recurring_date`
                            // confronta.
                            val larghezzaGiorni = larghezzaPulsanti(GIORNI_SETTIMANA.map { it.second })
                            RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                                GIORNI_SETTIMANA.forEach { (numero, etichetta) ->
                                    Pillola(
                                        testo = etichetta,
                                        scelta = numero in b.giorniSettimana,
                                        attiva = true,
                                        colore = Palette.secondary,
                                        larghezza = larghezzaGiorni,
                                    ) {
                                        b = b.copy(
                                            giorniSettimana = b.giorniSettimana.alterna(numero)
                                        )
                                    }
                                }
                            }
                        }

                        "monthly" -> {
                            Titoletto("Giorni del mese")
                            val larghezzaGiorni = larghezzaPulsanti((1..31).map { "$it" })
                            RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                                (1..31).forEach { giorno ->
                                    Pillola(
                                        testo = "$giorno",
                                        scelta = giorno in b.giorniMese,
                                        attiva = true,
                                        colore = Palette.secondary,
                                        larghezza = larghezzaGiorni,
                                    ) { b = b.copy(giorniMese = b.giorniMese.alterna(giorno)) }
                                }
                            }
                        }

                        "yearly" -> {
                            Titoletto("Ricorrenze nell'anno")
                            b.dateAnnuali.sorted().forEach { gm ->
                                RigaData(giornoMeseItaliano(gm)) {
                                    b = b.copy(dateAnnuali = b.dateAnnuali - gm)
                                }
                            }
                            Bottone("+ Aggiungi ricorrenza") {
                                scegliData(context, LocalDate.now()) { d ->
                                    val gm = "%02d-%02d".format(d.dayOfMonth, d.monthValue)
                                    if (gm !in b.dateAnnuali) {
                                        b = b.copy(dateAnnuali = b.dateAnnuali + gm)
                                    }
                                }
                            }
                            Nota("Di ogni data contano solo giorno e mese: l'anno lo mette la ricorrenza.")
                        }
                    }
                }

                "multiple" -> {
                    Titoletto("Date")
                    b.dateMultiple.sorted().forEach { data ->
                        RigaData(dataItaliana(data)) {
                            b = b.copy(dateMultiple = b.dateMultiple - data)
                        }
                    }
                    Bottone("+ Aggiungi data") {
                        scegliData(context, LocalDate.now()) { d ->
                            val iso = d.toString()
                            if (iso !in b.dateMultiple) {
                                b = b.copy(dateMultiple = b.dateMultiple + iso)
                            }
                        }
                    }
                    if (b.dateMultiple.isEmpty()) Nota("Serve almeno una data.")
                }

                "free_repeat" ->
                    Nota("Non ha una prossima occorrenza: si completa quando capita, e resta lì pronto per la volta dopo.")
            }

            // ── Punti ───────────────────────────────────────────────────
            Titoletto("Punti")
            CampoNumero(b.puntiSuccesso, "Completato", true) { b = b.copy(puntiSuccesso = it) }
            CampoNumero(b.puntiFallimento, "Fallito", true) { b = b.copy(puntiFallimento = it) }
            CampoNumero(b.puntiSalto, "Saltato", true) { b = b.copy(puntiSalto = it) }
            CampoNumero(b.puntiRitardo, "In ritardo", true) { b = b.copy(puntiRitardo = it) }

            // ── Priorità e categorie ────────────────────────────────────
            if (priorita.isNotEmpty()) {
                TendinaFacoltativa(
                    etichetta = "Priorità",
                    tutte = "Nessuna priorità",
                    scelto = b.prioritaId,
                    voci = priorita.map { it.id to it.nome },
                ) { scelta -> b = b.copy(prioritaId = scelta) }
            }

            if (categorie.isNotEmpty()) {
                Titoletto("Categorie")
                val larghezzaCategorie = larghezzaPulsanti(
                    categorie.map { "${it.icona.orEmpty()} ${it.nome}".trim() }
                )
                RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                    categorie.forEach { c ->
                        Pillola(
                            testo = "${c.icona.orEmpty()} ${c.nome}".trim(),
                            scelta = c.id in b.categorie,
                            attiva = true,
                            colore = coloreDaHex(c.colore) ?: Palette.accent,
                            larghezza = larghezzaCategorie,
                        ) { b = b.copy(categorie = b.categorie.alterna(c.id)) }
                    }
                }
            }

            if (!b.valida) {
                Nota(
                    when {
                        b.titolo.isBlank() -> "Manca il titolo."
                        else -> "Manca qualcosa fra le date o i giorni scelti."
                    }
                )
            }
        }
    }
}

private val FREQUENZE = listOf(
    "daily" to "Ogni giorno",
    "weekly" to "Ogni settimana",
    "monthly" to "Ogni mese",
    "yearly" to "Ogni anno",
)

/** Numerazione di Postgres (0 = domenica), ordine di lettura italiano. */
private val GIORNI_SETTIMANA = listOf(
    1 to "Lun", 2 to "Mar", 3 to "Mer", 4 to "Gio",
    5 to "Ven", 6 to "Sab", 0 to "Dom",
)

private fun <T> List<T>.alterna(voce: T): List<T> =
    if (voce in this) this - voce else this + voce

private fun giornoMeseItaliano(gm: String): String {
    val pezzi = gm.split("-")
    val g = pezzi.getOrNull(0) ?: return gm
    val m = pezzi.getOrNull(1) ?: return gm
    return "$g/$m"
}

// ── Selettori di sistema ─────────────────────────────────────────────────
//
// Quelli di Android e non i `DatePicker` di Material3: sono già tradotti,
// seguono l'ingrandimento dei caratteri del telefono senza che nessuno se ne
// occupi, e sono i selettori che l'utente conosce dalle altre app.

private fun scegliData(context: Context, iniziale: LocalDate, poi: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, anno, meseZeroBased, giorno -> poi(LocalDate.of(anno, meseZeroBased + 1, giorno)) },
        iniziale.year,
        iniziale.monthValue - 1,
        iniziale.dayOfMonth,
    ).show()
}

private fun scegliOra(context: Context, iniziale: String, poi: (String) -> Unit) {
    val ore = iniziale.take(2).toIntOrNull() ?: 9
    val minuti = iniziale.drop(3).toIntOrNull() ?: 0
    TimePickerDialog(
        context,
        { _, h, m -> poi("%02d:%02d".format(h, m)) },
        ore,
        minuti,
        true,
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
    Text(
        text = testo,
        color = Palette.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Bottone(testo: String, onClick: () -> Unit) {
    Text(
        text = testo,
        color = Palette.light,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.topBar)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun RigaData(testo: String, onTogli: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.inputBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(testo, color = Palette.dark)
        Text("🗑", modifier = Modifier.clickable(onClick = onTogli))
    }
}

/**
 * Un numero, tenuto come testo mentre lo si scrive.
 *
 * Se si convertisse a ogni battuta, cancellare l'ultima cifra darebbe una
 * stringa vuota che diventa 0 e ricompare nel campo: non si riuscirebbe più a
 * svuotarlo per riscriverlo.
 */
@Composable
private fun CampoNumero(
    valore: Int,
    etichetta: String,
    conSegno: Boolean = false,
    onCambia: (Int) -> Unit,
) {
    var testo by remember(valore) { mutableStateOf(valore.toString()) }
    OutlinedTextField(
        value = testo,
        onValueChange = { nuovo ->
            val pulito = nuovo.filterIndexed { i, c ->
                c.isDigit() || (conSegno && c == '-' && i == 0)
            }.take(6)
            testo = pulito
            pulito.toIntOrNull()?.let(onCambia)
        },
        label = { Text(etichetta) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Pillola(
    testo: String,
    scelta: Boolean,
    attiva: Boolean,
    colore: androidx.compose.ui.graphics.Color,
    larghezza: Dp? = null,
    onClick: () -> Unit,
) {
    Text(
        text = testo,
        color = when {
            scelta -> Palette.light
            attiva -> Palette.dark
            else -> Palette.muted.copy(alpha = 0.4f)
        },
        fontWeight = if (scelta) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .then(larghezza?.let { Modifier.width(it) } ?: Modifier)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (scelta) colore else Palette.inputBg)
            .clickable(enabled = attiva, onClick = onClick)
            .padding(horizontal = if (larghezza == null) 14.dp else 0.dp, vertical = 10.dp),
    )
}
