package com.garsal.appsphere.abituati

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.FlowRow
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
import com.garsal.appsphere.core.larghezzaPulsanti
import java.time.LocalDate

/** L'abitudine che si sta scrivendo. */
data class BozzaAbitudine(
    val nome: String = "",
    val descrizione: String = "",
    val categoriaId: String? = null,
    val frequenza: String = "daily",
    val giorniSettimana: List<Int> = emptyList(),
    val orari: List<String> = emptyList(),
    val inizio: LocalDate = LocalDate.now(),
    val obiettivo: Int = 30,
    val jolly: Int = 3,
    val puntiPremio: Int = 0,
    val puntiPenalita: Int = 0,
    val volte: Int = 3,
    val giorniFinestra: Int = 7,
    val maxAlGiorno: Int = 1,
    // ── 🔔 Promemoria: gli stessi campi del form web ──
    /** L'orario di riferimento (HH:MM); su una a più orari valgono gli orari. */
    val orarioPromemoria: String = "",
    /** I due canali si accendono uno per uno; un'abitudine nuova parte con tutt'e due accesi. */
    val telegram: Boolean = true,
    val anticipiTelegram: Set<Int> = emptySet(),
    val bottoneFatto: Boolean = false,
    val telefono: Boolean = true,
    val anticipiTelefono: Set<Int> = emptySet(),
) {
    val valida: Boolean get() = nome.isNotBlank() && obiettivo >= 1 &&
        (frequenza != "weekly" || giorniSettimana.isNotEmpty()) &&
        (frequenza != "daily_multiple" || orari.isNotEmpty()) &&
        (frequenza != "count_window" || finestraValida)

    /**
     * ⚠️ **N può superare M**: col tetto giornaliero a 1 le volte non possono
     * superare i giorni, ma a 3 al giorno «10 volte in 5 giorni» è una
     * richiesta legittima. Il vincolo vero è quindi **N ≤ M × P** — la stessa
     * condizione del form del web.
     */
    val finestraValida: Boolean get() =
        volte >= 1 && giorniFinestra >= 1 && maxAlGiorno >= 1 &&
            volte <= giorniFinestra * maxAlGiorno

    /** Perché la finestra non va bene, per scriverlo invece di lasciarlo indovinare. */
    val motivoFinestra: String? get() = when {
        frequenza != "count_window" -> null
        maxAlGiorno < 1 -> "Indica quante volte al massimo ogni giorno (almeno 1)"
        volte < 1 || giorniFinestra < 1 -> "Indica quante volte e in quanti giorni (almeno 1)"
        volte > giorniFinestra * maxAlGiorno ->
            "In $giorniFinestra giorni a $maxAlGiorno al giorno ci stanno al massimo " +
                "${giorniFinestra * maxAlGiorno} volte"
        else -> null
    }

    companion object {
        /**
         * Riapre un'abitudine nel form, coi promemoria letti dalle sue regole.
         * ⚠️ La riga BASTA a dire che il canale è acceso; un'abitudine senza
         * nessuna regola parte con tutt'e due accesi, come in `showEditHabitModal`.
         */
        fun da(a: HbAbitudine, regole: List<HbRegola> = emptyList()): BozzaAbitudine {
            val tg = regole.firstOrNull { it.abitudineId == a.id && it.canale == "telegram" }
            val an = regole.firstOrNull { it.abitudineId == a.id && it.canale == "android" }
            val senza = tg == null && an == null
            return base(a).copy(
                orarioPromemoria = if (a.frequenza == "daily_multiple") "" else (tg ?: an)?.orari?.firstOrNull().orEmpty(),
                telegram = senza || tg != null,
                anticipiTelegram = tg?.anticipi?.toSet().orEmpty(),
                bottoneFatto = tg?.bottoneFatto ?: false,
                telefono = senza || an != null,
                anticipiTelefono = an?.anticipi?.toSet().orEmpty(),
            )
        }

        private fun base(a: HbAbitudine) = BozzaAbitudine(
            nome = a.nome,
            descrizione = a.descrizione.orEmpty(),
            categoriaId = a.categoriaId,
            frequenza = a.frequenza,
            giorniSettimana = a.giorniSettimana,
            orari = a.orari,
            inizio = a.giornoInizio ?: LocalDate.now(),
            obiettivo = a.obiettivo,
            jolly = a.jollyMassimi,
            puntiPremio = a.puntiPremio,
            puntiPenalita = a.puntiPenalita,
            volte = a.volte.takeIf { it > 0 } ?: 3,
            giorniFinestra = a.giorniFinestra.takeIf { it > 0 } ?: 7,
            maxAlGiorno = a.maxAlGiorno.takeIf { it > 0 } ?: 1,
        )
    }
}

private val GIORNI = listOf(
    1 to "Lun", 2 to "Mar", 3 to "Mer", 4 to "Gio", 5 to "Ven", 6 to "Sab", 0 to "Dom",
)

private val FREQUENZE = listOf(
    "daily" to "Ogni giorno",
    "daily_multiple" to "Più volte al giorno",
    "weekly" to "Giorni della settimana",
    "count_window" to "N volte in M giorni",
)

/**
 * Creazione e modifica di un'abitudine.
 *
 * ⚠️ **La frequenza non si cambia** su un'abitudine che esiste già, come il
 * tipo di un task: decide quali colonne quella riga usa — `daily_times` su una
 * settimanale non la pulisce nessuno — e soprattutto i giorni già spuntati sono
 * stati contati con la regola di prima. Stessa ragione per la **data di
 * inizio**: da lì partono lo streak, i giorni mancati e la scadenza dello
 * stack, e spostarla riscriverebbe il giudizio su giorni già passati.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AbituatiForm(
    bozzaIniziale: BozzaAbitudine,
    id: String?,
    categorie: List<HbCategoria>,
    preset: List<HbPreset>,
    onAnnulla: () -> Unit,
    onSalva: (BozzaAbitudine) -> Unit,
) {
    var b by remember { mutableStateOf(bozzaIniziale) }
    val context = LocalContext.current
    val nuova = id == null

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = if (nuova) "🎯 Nuova abitudine" else "✏️ Modifica abitudine",
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = b.nome,
                onValueChange = { b = b.copy(nome = it) },
                label = { Text("Nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = b.descrizione,
                onValueChange = { b = b.copy(descrizione = it) },
                label = { Text("Descrizione") },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Categoria ───────────────────────────────────────────────
            if (categorie.isNotEmpty()) {
                TendinaFacoltativa(
                    etichetta = "Categoria",
                    tutte = "— nessuna —",
                    scelto = b.categoriaId,
                    voci = categorie.map { it.id to it.etichetta },
                ) { scelta -> b = b.copy(categoriaId = scelta) }
            }

            // ── Frequenza ───────────────────────────────────────────────
            if (nuova) {
                Tendina(
                    etichetta = "Frequenza",
                    scelto = FREQUENZE.first { it.first == b.frequenza }.second,
                    voci = FREQUENZE,
                ) { scelta -> b = b.copy(frequenza = scelta) }
            } else {
                Etichetta("Frequenza")
                Text(
                    text = when (b.frequenza) {
                        "daily_multiple" -> "Più volte al giorno — non si cambia"
                        "weekly" -> "Giorni della settimana — non si cambia"
                        "count_window" -> "N volte in M giorni — non si cambia"
                        else -> "Ogni giorno — non si cambia"
                    },
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (b.frequenza == "weekly") {
                val larghezzaGiorni = larghezzaPulsanti(GIORNI.map { it.second })
                RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                    GIORNI.forEach { (numero, nome) ->
                        val scelto = numero in b.giorniSettimana
                        Scelta(nome, scelto, larghezzaGiorni) {
                            b = b.copy(
                                giorniSettimana = if (scelto) b.giorniSettimana - numero
                                else b.giorniSettimana + numero
                            )
                        }
                    }
                }
            }

            if (b.frequenza == "daily_multiple") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.orari.sorted().forEach { orario ->
                        Scelta("$orario ✕", true) { b = b.copy(orari = b.orari - orario) }
                    }
                    Scelta("＋ orario", false) {
                        val adesso = java.time.LocalTime.now()
                        TimePickerDialog(
                            context,
                            { _, ora, minuti ->
                                val o = "%02d:%02d".format(ora, minuti)
                                if (o !in b.orari) b = b.copy(orari = b.orari + o)
                            },
                            adesso.hour, 0, true,
                        ).show()
                    }
                }
            }

            if (b.frequenza == "count_window") {
                Numero("Quante volte", b.volte) { b = b.copy(volte = it) }
                Numero("In quanti giorni", b.giorniFinestra) { b = b.copy(giorniFinestra = it) }
                // ⚠️ Senza un tetto le N volte si potrebbero fare tutte in un
                // pomeriggio, che è l'opposto di quel che questa frequenza
                // chiede. Di partenza 1.
                Numero("Al massimo ogni giorno", b.maxAlGiorno) { b = b.copy(maxAlGiorno = it) }
                b.motivoFinestra?.let {
                    Text(
                        text = "⚠️ $it",
                        color = Palette.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ── Numeri ──────────────────────────────────────────────────
            // ⚠️ Per `count_window` l'obiettivo sono **finestre**, non giorni:
            // «3» vuol dire tre finestre da M giorni. L'etichetta lo dice,
            // perché un numero che cambia unità senza avvisare è un numero
            // sbagliato che sembra giusto.
            Numero(
                if (b.frequenza == "count_window") "Obiettivo (finestre)" else "Obiettivo (giorni)",
                b.obiettivo,
            ) { b = b.copy(obiettivo = it) }
            Numero("Jolly a disposizione", b.jolly) { b = b.copy(jolly = it) }
            Numero("Punti se lo completi", b.puntiPremio) { b = b.copy(puntiPremio = it) }
            Numero("Punti se lo fallisci", b.puntiPenalita) { b = b.copy(puntiPenalita = it) }

            // ── 🔔 Promemoria ───────────────────────────────────────────
            Promemoria(b, preset) { b = it }

            // ── Inizio ──────────────────────────────────────────────────
            Etichetta("Inizio")
            if (nuova) {
                Scelta(dataItaliana(b.inizio), false) {
                    scegliData(context, b.inizio) { b = b.copy(inizio = it) }
                }
            } else {
                Text(
                    text = "${dataItaliana(b.inizio)} — non si cambia: da lì partono streak, " +
                        "giorni mancati e scadenza dello stack.",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 🔔 I promemoria, come il blocco «Promemoria» del form web: l'orario di
 * riferimento e due Tab — 📱 Telegram e 📲 Telefono — ciascuna con la sua
 * spunta e i **suoi** anticipi. I due canali si scelgono uno per uno: spegnere
 * il bot non zittisce il telefono.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Promemoria(b: BozzaAbitudine, preset: List<HbPreset>, onCambia: (BozzaAbitudine) -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf("telegram") }

    Etichetta("🔔 Promemoria")
    if (b.frequenza == "daily_multiple") {
        Text(
            "ℹ️ I promemoria si applicano agli orari configurati per questa abitudine",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scelta(
                if (b.orarioPromemoria.isBlank()) "⏰ Orario: nessuno" else "⏰ Orario ${b.orarioPromemoria}",
                b.orarioPromemoria.isNotBlank(),
            ) {
                val adesso = java.time.LocalTime.now()
                TimePickerDialog(
                    context,
                    { _, ora, minuti -> onCambia(b.copy(orarioPromemoria = "%02d:%02d".format(ora, minuti))) },
                    adesso.hour, 0, true,
                ).show()
            }
            if (b.orarioPromemoria.isNotBlank()) Scelta("✕", false) { onCambia(b.copy(orarioPromemoria = "")) }
        }
        Text(
            "Orario di riferimento per i promemoria anticipati",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
        )
    }

    // ⚠️ La barra delle Tab scorre di lato e non va a capo, come nel web.
    RigaScorrevole(Arrangement.spacedBy(6.dp)) {
        Scelta("📱 Telegram" + if (b.telegram) " ●" else "", tab == "telegram") { tab = "telegram" }
        Scelta("📲 Telefono" + if (b.telefono) " ●" else "", tab == "android") { tab = "android" }
    }

    val telegram = tab == "telegram"
    val acceso = if (telegram) b.telegram else b.telefono
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = acceso, onCheckedChange = {
            onCambia(if (telegram) b.copy(telegram = it) else b.copy(telefono = it))
        })
        Text(if (telegram) "Attiva notifiche Telegram" else "Attiva notifiche sul telefono", fontWeight = FontWeight.SemiBold)
    }
    if (acceso) {
        val scelti = if (telegram) b.anticipiTelegram else b.anticipiTelefono
        if (preset.isEmpty()) {
            Text("Nessun preset disponibile", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                preset.forEach { p ->
                    val on = p.id in scelti
                    Scelta(p.etichetta + if (on) " ✕" else "", on) {
                        val nuovi = if (on) scelti - p.id else scelti + p.id
                        onCambia(
                            if (telegram) b.copy(anticipiTelegram = nuovi, bottoneFatto = b.bottoneFatto && nuovi.isNotEmpty())
                            else b.copy(anticipiTelefono = nuovi)
                        )
                    }
                }
            }
        }
        if (telegram && b.anticipiTelegram.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = b.bottoneFatto, onCheckedChange = { onCambia(b.copy(bottoneFatto = it)) })
                Text("🤖 Aggiungi pulsante Fatto ✅ nel messaggio Telegram")
            }
        }
        if (!telegram) {
            Text(
                "Push sull'app AppSphere, coi pulsanti ✅ Fatto e ⏸ Rinvia. Indipendente da Telegram: puoi tenere acceso solo questo.",
                color = Palette.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Etichetta(testo: String) {
    Text(text = testo, color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Numero(etichetta: String, valore: Int, onCambia: (Int) -> Unit) {
    var testo by remember(etichetta) { mutableStateOf(valore.toString()) }
    OutlinedTextField(
        value = testo,
        onValueChange = {
            testo = it.filter(Char::isDigit)
            onCambia(testo.toIntOrNull() ?: 0)
        },
        label = { Text(etichetta) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Scelta(testo: String, scelto: Boolean, larghezza: Dp? = null, onTocca: () -> Unit) {
    Text(
        text = testo,
        color = if (scelto) Palette.light else Palette.dark,
        fontWeight = if (scelto) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .then(larghezza?.let { Modifier.width(it) } ?: Modifier)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (scelto) NeroAbituati else Palette.inputBg)
            .clickable(onClick = onTocca)
            .padding(horizontal = if (larghezza == null) 14.dp else 0.dp, vertical = 10.dp),
    )
}

internal fun scegliData(context: Context, iniziale: LocalDate, onScelta: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, anno, mese, giorno -> onScelta(LocalDate.of(anno, mese + 1, giorno)) },
        iniziale.year, iniziale.monthValue - 1, iniziale.dayOfMonth,
    ).show()
}

/** `2026-08-15` → `15/08/2026`, come ovunque nelle app di casa. */
internal fun dataItaliana(giorno: LocalDate): String =
    "%02d/%02d/%d".format(giorno.dayOfMonth, giorno.monthValue, giorno.year)

internal fun dataItaliana(iso: String?): String {
    val pezzi = iso?.take(10)?.split("-") ?: return ""
    return if (pezzi.size == 3) "${pezzi[2]}/${pezzi[1]}/${pezzi[0]}" else iso
}
