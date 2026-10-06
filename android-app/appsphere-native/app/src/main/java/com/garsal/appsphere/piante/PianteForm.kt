package com.garsal.appsphere.piante

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.TendinaFacoltativa
import com.garsal.appsphere.memo.MemoFoto
import com.garsal.appsphere.obiettivi.dataItalianaDa
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

// I form delle Piante prendono lo schermo intero: coi caratteri di sistema
// grandi un dialogo sarebbe una feritoia. Ogni campo lascia andare a capo il
// testo — nessuna altezza fissa attorno a una scritta.

@Composable
private fun Pagina(
    titolo: String,
    occupato: Boolean,
    onAnnulla: () -> Unit,
    onSalva: () -> Unit,
    errore: String?,
    extra: @Composable () -> Unit = {},
    contenuto: @Composable () -> Unit,
) {
    Scaffold(topBar = { GarsalTopBar(titolo = titolo, onIndietro = onAnnulla) }) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            contenuto()
            errore?.let { Text("⚠️ $it", color = Palette.danger, fontWeight = FontWeight.Bold) }
            RigaScorrevole(Arrangement.spacedBy(10.dp), Modifier.padding(top = 8.dp, bottom = 40.dp)) {
                Pillola(if (occupato) "Salvo…" else "💾 Salva", if (occupato) Palette.muted else VerdePiante) {
                    if (!occupato) onSalva()
                }
                Pillola("Annulla", Palette.muted) { onAnnulla() }
                extra()
            }
        }
    }
}

@Composable
private fun Campo(etichetta: String, valore: String, righe: Int = 1, onCambia: (String) -> Unit) {
    OutlinedTextField(
        value = valore, onValueChange = onCambia,
        label = { Text(etichetta) },
        singleLine = righe == 1,
        minLines = righe,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Titoletto(testo: String) {
    Text(testo, fontWeight = FontWeight.Bold, color = Palette.dark, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Nota(testo: String) {
    Text(testo, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
}

/** Un pulsante-campo che apre un selettore di sistema (data o ora). */
@Composable
private fun CampoScelta(etichetta: String, valore: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.inputBg)
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(etichetta.uppercase(), color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        Text(valore, color = Palette.dark)
    }
}

// Quelli di Android e non i `DatePicker` di Material3, come in TaskForm: sono
// già tradotti e seguono l'ingrandimento dei caratteri del telefono.
private fun scegliData(context: Context, iniziale: LocalDate, poi: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, anno, mese, giorno -> poi(LocalDate.of(anno, mese + 1, giorno)) },
        iniziale.year, iniziale.monthValue - 1, iniziale.dayOfMonth,
    ).show()
}

private fun scegliOra(context: Context, iniziale: String, poi: (String) -> Unit) {
    TimePickerDialog(
        context,
        { _, h, m -> poi("%02d:%02d".format(h, m)) },
        iniziale.take(2).toIntOrNull() ?: 9, iniziale.drop(3).toIntOrNull() ?: 0, true,
    ).show()
}

/**
 * Le foto di un form: quelle già caricate (con la ✕ che le toglie al
 * salvataggio) e quelle nuove, dalla galleria o dalla fotocamera.
 *
 * ⚠️ Il selettore è il **Photo Picker** di sistema: restituisce direttamente
 * gli URI, senza permessi e senza l'Intent che una galleria qualunque può
 * rimandare indietro vuoto (è il difetto pagato in Modifiche).
 */
@Composable
private fun FotoForm(
    esistenti: List<PvFoto>,
    nuove: List<Uri>,
    copertina: Boolean,
    onTogliEsistente: (PvFoto) -> Unit,
    onTogliNuova: (Uri) -> Unit,
    onAggiungi: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var uriScatto by remember { mutableStateOf<Uri?>(null) }
    val galleria = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { onAggiungi(it) }
    val fotocamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) uriScatto?.let { onAggiungi(listOf(it)) }
    }
    Titoletto("📷 Foto")
    if (copertina) Nota("La prima è la copertina.")
    val tutte: List<Pair<Any, Any>> =
        esistenti.map<PvFoto, Pair<Any, Any>> { it to it.url } + nuove.map<Uri, Pair<Any, Any>> { it to it }
    if (tutte.isNotEmpty()) {
        RigaScorrevole(Arrangement.spacedBy(6.dp)) {
            tutte.forEach { (chiave, modello) ->
                Box(Modifier.size(96.dp)) {
                    AsyncImage(model = modello, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                    Text(
                        "✕", color = Palette.light,
                        modifier = Modifier.align(Alignment.TopEnd).background(Palette.dark.copy(alpha = 0.7f))
                            .clickable {
                                when (chiave) {
                                    is PvFoto -> onTogliEsistente(chiave)
                                    is Uri -> onTogliNuova(chiave)
                                }
                            }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
    RigaScorrevole(Arrangement.spacedBy(8.dp)) {
        Pillola("🖼 Galleria", Palette.accent) {
            galleria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        Pillola("📷 Fotocamera", Palette.accent) {
            val uri = MemoFoto.uriPerScatto(context)
            uriScatto = uri
            fotocamera.launch(uri)
        }
    }
}

// ── pianta ──────────────────────────────────────────────────────────────────

@Composable
fun FormPianta(
    stato: PianteState,
    pianta: PvPianta?,
    daDesiderio: PvDesiderio?,
    onAnnulla: () -> Unit,
    onSalva: (Context, JsonObject, List<Uri>, List<PvFoto>, Set<String>) -> Unit,
    onElimina: (String) -> Unit,
    onCreaGruppo: (String, (String) -> Unit) -> Unit,
    clonaDa: PvPianta? = null,
) {
    val context = LocalContext.current
    // «📋 Clona» copia la sola scheda: niente foto, diario né azioni. Gemello di
    // `clonaPianta()` in piante.html — nome «(copia)», arrivata oggi, stessa madre.
    val base = pianta ?: clonaDa
    var nome by remember { mutableStateOf(pianta?.nome ?: clonaDa?.let { it.nome + " (copia)" } ?: daDesiderio?.nome ?: "") }
    var specie by remember { mutableStateOf(base?.specie ?: daDesiderio?.specie ?: "") }
    var posizione by remember { mutableStateOf(base?.posizione ?: "") }
    var madre by remember { mutableStateOf(base?.madreId) }
    var arrivata by remember {
        mutableStateOf(pianta?.arrivataIl?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            ?: if (pianta == null) LocalDate.now() else null)
    }
    var luce by remember { mutableStateOf(base?.luce ?: "") }
    var acqua by remember { mutableStateOf(base?.annaffiatura ?: "") }
    var terriccio by remember { mutableStateOf(base?.terriccio ?: "") }
    var concime by remember { mutableStateOf(base?.concime ?: "") }
    var note by remember { mutableStateOf(base?.note ?: daDesiderio?.note ?: "") }
    var archiviata by remember { mutableStateOf(pianta?.archiviata ?: false) }
    // 👥 In modifica i gruppi di adesso, in un clone quelli della pianta copiata.
    var gruppi by remember { mutableStateOf(base?.let { b -> stato.gruppiDi(b.id).map { it.id }.toSet() }.orEmpty()) }
    var nuovoGruppo by remember { mutableStateOf("") }
    var tolte by remember { mutableStateOf(listOf<PvFoto>()) }
    var nuove by remember { mutableStateOf(listOf<Uri>()) }
    var errore by remember { mutableStateOf<String?>(null) }
    var chiediElimina by remember { mutableStateOf(false) }
    val esistenti = pianta?.let { stato.fotoScheda(it.id) }.orEmpty().filter { f -> tolte.none { it.id == f.id } }

    Pagina(
        titolo = when { pianta != null -> "✏️ ${pianta.nome}"; clonaDa != null -> "📋 Copia di ${clonaDa.nome}"; else -> "🌱 Nuova pianta" },
        occupato = stato.occupato, onAnnulla = onAnnulla, errore = errore,
        onSalva = {
            if (nome.isBlank()) { errore = "Serve un nome."; return@Pagina }
            errore = null
            val v = { s: String -> s.trim().ifBlank { null } }
            onSalva(context, buildJsonObject {
                put("name", nome.trim()); put("species", v(specie)); put("location", v(posizione))
                put("acquired_on", arrivata?.toString())
                put("light", v(luce)); put("watering", v(acqua)); put("soil", v(terriccio))
                put("fertilizer", v(concime)); put("notes", v(note)); put("archived", archiviata)
                put("parent_id", madre)
            }, nuove, tolte, gruppi)
        },
        extra = { if (pianta != null) Pillola("🗑 Elimina", Palette.danger) { chiediElimina = true } },
    ) {
        Campo("Nome *", nome) { nome = it }
        Campo("Specie", specie) { specie = it }
        Campo("📍 Posizione", posizione) { posizione = it }
        CampoScelta("📅 Arrivata il", arrivata?.let { dataItalianaDa(it) } ?: "—") {
            scegliData(context, arrivata ?: LocalDate.now()) { arrivata = it }
        }
        val fuori = pianta?.let { stato.discendenti(it.id) }.orEmpty()
        TendinaFacoltativa(
            "🌿 Discende da", "— nessuna —", madre,
            stato.piante.filter { it.id !in fuori }.map { it.id to (it.nome + if (it.archiviata) " 📦" else "") },
        ) { madre = it }
        Titoletto("👥 Gruppi")
        if (stato.gruppi.isEmpty()) Nota("Nessun gruppo ancora: scrivine uno qui sotto.")
        else RigaScorrevole(Arrangement.spacedBy(6.dp)) {
            stato.gruppi.forEach { g ->
                Chip(g.etichetta, g.id in gruppi) { gruppi = if (g.id in gruppi) gruppi - g.id else gruppi + g.id }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Campo("Nuovo gruppo, es. Balcone", nuovoGruppo) { nuovoGruppo = it } }
            Pillola("＋ Crea", VerdePiante) {
                onCreaGruppo(nuovoGruppo) { id -> gruppi = gruppi + id; nuovoGruppo = "" }
            }
        }
        Nota("Tocca un gruppo per metterla o toglierla. Le azioni del gruppo valgono anche per lei.")
        Campo("☀️ Luce", luce, 2) { luce = it }
        Campo("💧 Annaffiatura", acqua, 2) { acqua = it }
        Campo("🪴 Terriccio e vaso", terriccio, 2) { terriccio = it }
        Campo("🧪 Concime", concime, 2) { concime = it }
        Campo("📝 Note", note, 3) { note = it }
        FotoForm(esistenti, nuove, copertina = true,
            onTogliEsistente = { tolte = tolte + it }, onTogliNuova = { u -> nuove = nuove - u },
            onAggiungi = { nuove = nuove + it })
        if (pianta != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(if (archiviata) "📦 Archiviata" else "📦 Archivia (il diario resta)", archiviata) { archiviata = !archiviata }
            }
        }
    }

    if (chiediElimina && pianta != null) {
        val nv = stato.voci.count { it.piantaId == pianta.id }
        val nf = stato.foto.count { it.piantaId == pianta.id }
        Conferma(
            "Eliminare «${pianta.nome}»?\n\nSe ne vanno anche $nv voci di diario, $nf foto e le sue azioni coi promemoria.\n" +
                "Se la pianta non c'è più ma vuoi tenerne il diario, archiviala.",
            onNo = { chiediElimina = false },
        ) { chiediElimina = false; onElimina(pianta.id); onAnnulla() }
    }
}

// ── voce di diario ──────────────────────────────────────────────────────────

@Composable
fun FormVoce(
    stato: PianteState,
    voce: PvVoce?,
    onAnnulla: () -> Unit,
    onSalva: (Context, LocalDate, String?, List<Uri>, List<PvFoto>) -> Unit,
) {
    val context = LocalContext.current
    var giorno by remember { mutableStateOf(voce?.giorno?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()) }
    var commento by remember { mutableStateOf(voce?.commento ?: "") }
    var tolte by remember { mutableStateOf(listOf<PvFoto>()) }
    var nuove by remember { mutableStateOf(listOf<Uri>()) }
    var errore by remember { mutableStateOf<String?>(null) }
    val esistenti = voce?.let { stato.fotoVoce(it.id) }.orEmpty().filter { f -> tolte.none { it.id == f.id } }

    Pagina(
        titolo = if (voce != null) "✏️ Voce del ${dataItalianaDa(giorno)}" else "📔 Nuova voce",
        occupato = stato.occupato, onAnnulla = onAnnulla, errore = errore,
        onSalva = {
            // Una voce vale se ha il commento OPPURE una foto: una foto sola è già un ricordo.
            if (commento.isBlank() && esistenti.isEmpty() && nuove.isEmpty()) {
                errore = "Scrivi un commento o aggiungi una foto."; return@Pagina
            }
            errore = null
            onSalva(context, giorno, commento.trim().ifBlank { null }, nuove, tolte)
        },
    ) {
        CampoScelta("Data", dataItalianaDa(giorno)) { scegliData(context, giorno) { giorno = it } }
        Campo("Commento", commento, 5) { commento = it }
        FotoForm(esistenti, nuove, copertina = false,
            onTogliEsistente = { tolte = tolte + it }, onTogliNuova = { u -> nuove = nuove - u },
            onAggiungi = { nuove = nuove + it })
    }
}

// ── azione ──────────────────────────────────────────────────────────────────

private val FREQUENZE = listOf("daily" to "Giornaliera", "weekly" to "Settimanale", "monthly" to "Mensile", "yearly" to "Annuale")

/** Numerazione di Postgres (0 = domenica), ordine di lettura italiano. */
private val GIORNI = listOf(1 to "Lun", 2 to "Mar", 3 to "Mer", 4 to "Gio", 5 to "Ven", 6 to "Sab", 0 to "Dom")

@Composable
fun FormAzione(
    stato: PianteState,
    iniziale: BozzaAzione,
    onAnnulla: () -> Unit,
    onSalva: (BozzaAzione) -> Unit,
) {
    val context = LocalContext.current
    var b by remember { mutableStateOf(iniziale) }
    var errore by remember { mutableStateOf<String?>(null) }
    val nuova = iniziale.id == null
    val pianta = stato.pianta(b.piantaId)
    val gruppo = stato.gruppo(b.gruppoId)

    Pagina(
        titolo = when {
            nuova -> "✅ Nuova azione · " + (gruppo?.etichetta ?: pianta?.nome ?: "")
            else -> "✏️ ${iniziale.titolo}" + (gruppo?.let { " · " + it.etichetta } ?: "")
        },
        occupato = stato.occupato, onAnnulla = onAnnulla, errore = errore,
        onSalva = {
            errore = when {
                b.titolo.isBlank() -> "Scrivi cosa fare."
                b.tipo == "recurring" && b.frequenza == "weekly" && b.giorniSettimana.isEmpty() -> "Scegli almeno un giorno della settimana."
                b.tipo == "recurring" && b.frequenza == "yearly" && dateAnno(b.dateAnno).isEmpty() -> "Scrivi almeno una data dell'anno nel formato GG-MM."
                b.tipo == "multiple" && b.dateMultiple.isEmpty() -> "Aggiungi almeno una data."
                else -> null
            }
            if (errore == null) onSalva(b)
        },
    ) {
        // In una nuova azione la pianta si sceglie; in una che c'è già no.
        // Un'azione di gruppo non ha pianta: vale per tutte quelle del gruppo.
        if (gruppo != null) {
            Nota("Vale per tutte le ${stato.pianteDi(gruppo.id).size} piante del gruppo, anche per quelle che ci entreranno dopo.")
        } else if (nuova && stato.piante.count { !it.archiviata } > 1) {
            Tendina("Pianta", pianta?.nome ?: "—",
                stato.piante.filter { !it.archiviata }.map { it.id to it.nome }) { b = b.copy(piantaId = it) }
        }
        Campo("Cosa fare *", b.titolo) { b = b.copy(titolo = it) }
        if (b.titolo.isBlank()) {
            RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                stato.titoliProposti.forEach { t -> Chip(t, false) { b = b.copy(titolo = t) } }
            }
        }
        Campo("Note", b.descrizione, 2) { b = b.copy(descrizione = it) }
        // ⚠️ Il tipo di un'azione che esiste già non si cambia: decide quali
        // colonne ha, e resterebbero dietro quelle del tipo di prima.
        Tendina("Tipo", PvAzione.TIPI.firstOrNull { it.first == b.tipo }?.second ?: b.tipo, PvAzione.TIPI, abilitata = nuova) {
            b = b.copy(tipo = it)
        }
        if (!nuova) Nota("Il tipo non si cambia: per un tipo diverso crea un'azione nuova.")

        if (b.tipo != "free_repeat" && b.tipo != "multiple") {
            CampoScelta(if (nuova) (if (b.tipo == "single") "Quando" else "Prima volta") else "Prossima volta", dataItalianaDa(b.giorno)) {
                scegliData(context, b.giorno) { b = b.copy(giorno = it) }
            }
        }
        if (b.tipo != "free_repeat") {
            CampoScelta("Ora", b.ora) { scegliOra(context, b.ora) { b = b.copy(ora = it) } }
        }
        when (b.tipo) {
            "single" -> {
                CampoScelta("Scadenza (facoltativa)", b.scadenza?.let { dataItalianaDa(it) } ?: "nessuna") {
                    scegliData(context, b.scadenza ?: b.giorno) { b = b.copy(scadenza = it) }
                }
                if (b.scadenza != null) Chip("✕ Togli la scadenza", false) { b = b.copy(scadenza = null) }
                Nota("Fatta dopo la scadenza vale i punti «in ritardo».")
            }
            "simple_recurring" -> CampoIntero("Ogni quanti giorni", b.ogniGiorni) { b = b.copy(ogniGiorni = maxOf(1, it)) }
            "recurring" -> {
                Tendina("Frequenza", FREQUENZE.first { it.first == b.frequenza }.second, FREQUENZE) { b = b.copy(frequenza = it) }
                CampoIntero("Ogni quanti (${mapOf("daily" to "giorni", "weekly" to "settimane", "monthly" to "mesi", "yearly" to "anni")[b.frequenza]})", b.intervallo) {
                    b = b.copy(intervallo = maxOf(1, it))
                }
                when (b.frequenza) {
                    "weekly" -> RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                        GIORNI.forEach { (d, nome) ->
                            Chip(nome, d in b.giorniSettimana) {
                                b = b.copy(giorniSettimana = if (d in b.giorniSettimana) b.giorniSettimana - d else b.giorniSettimana + d)
                            }
                        }
                    }
                    "monthly" -> Campo("Giorni del mese (es. 1, 15)", b.giorniMese) { b = b.copy(giorniMese = it) }
                    "yearly" -> Campo("Date dell'anno GG-MM (es. 15-03, 15-09)", b.dateAnno) { b = b.copy(dateAnno = it) }
                }
            }
            "multiple" -> {
                b.dateMultiple.sorted().forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconaTesto("🗑") { b = b.copy(dateMultiple = b.dateMultiple - d) }
                        Text(dataItalianaDa(d))
                    }
                }
                Pillola("＋ Aggiungi una data", Palette.accent) {
                    scegliData(context, LocalDate.now()) { d -> if (d !in b.dateMultiple) b = b.copy(dateMultiple = b.dateMultiple + d) }
                }
            }
        }

        Titoletto("🏅 Punti")
        CampoIntero("Fatta", b.puntiOk, conSegno = true) { b = b.copy(puntiOk = it) }
        CampoIntero("Saltata", b.puntiSalto, conSegno = true) { b = b.copy(puntiSalto = it) }
        CampoIntero("In ritardo", b.puntiRitardo, conSegno = true) { b = b.copy(puntiRitardo = it) }

        Titoletto("🔔 Promemoria")
        if (b.tipo == "free_repeat") {
            Nota("Un'azione «quando capita» non ha una data: non c'è niente a cui far suonare un promemoria.")
        } else {
            Nota("Ogni canale si accende da sé, coi suoi anticipi.")
            Chip(if (b.telegram) "📱 Telegram: acceso" else "📱 Telegram: spento", b.telegram) { b = b.copy(telegram = !b.telegram) }
            if (b.telegram) Anticipi(stato.preset, b.anticipiTelegram) { b = b.copy(anticipiTelegram = it) }
            Chip(if (b.telefono) "📲 Telefono: acceso" else "📲 Telefono: spento", b.telefono) { b = b.copy(telefono = !b.telefono) }
            if (b.telefono) Anticipi(stato.preset, b.anticipiTelefono) { b = b.copy(anticipiTelefono = it) }
            Chip(if (b.smartBlock) "🔐 Smart Block: acceso" else "🔐 Smart Block: spento", b.smartBlock) { b = b.copy(smartBlock = !b.smartBlock) }
            if (b.smartBlock) Nota("Blocca il telefono all'ora dell'azione.")
        }
    }
}

@Composable
private fun Anticipi(preset: List<PvPreset>, scelti: Set<Int>, onCambia: (Set<Int>) -> Unit) {
    if (preset.isEmpty()) { Nota("Anticipi dei promemoria non disponibili."); return }
    RigaScorrevole(Arrangement.spacedBy(6.dp)) {
        preset.forEach { p ->
            Chip(p.etichetta, p.id in scelti) { onCambia(if (p.id in scelti) scelti - p.id else scelti + p.id) }
        }
    }
    if (scelti.isEmpty()) Nota("⚠️ Senza anticipi questo canale resta spento.")
}

/**
 * Un numero tenuto come testo mentre lo si scrive: convertendolo a ogni
 * battuta, cancellare l'ultima cifra lo farebbe tornare 0 e non si riuscirebbe
 * più a svuotarlo. Stessa scelta di `CampoNumero` in TaskForm.
 */
@Composable
private fun CampoIntero(etichetta: String, valore: Int, conSegno: Boolean = false, onCambia: (Int) -> Unit) {
    var testo by remember(valore) { mutableStateOf(valore.toString()) }
    OutlinedTextField(
        value = testo,
        onValueChange = { nuovo ->
            val pulito = nuovo.filterIndexed { i, c -> c.isDigit() || (conSegno && c == '-' && i == 0) }.take(5)
            testo = pulito
            pulito.toIntOrNull()?.let(onCambia)
        },
        label = { Text(etichetta) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    // ⚠️ Molti tastierini numerici il meno non ce l'hanno: un malus non è un errore di battitura.
    if (conSegno) {
        Chip("± cambia segno", false) {
            val n = -(testo.toIntOrNull() ?: valore)
            testo = n.toString(); onCambia(n)
        }
    }
}

// ── desiderio ───────────────────────────────────────────────────────────────

@Composable
fun FormDesiderio(
    stato: PianteState,
    desiderio: PvDesiderio?,
    onAnnulla: () -> Unit,
    onSalva: (String, String?, String?, Int) -> Unit,
    onElimina: (String) -> Unit,
) {
    var nome by remember { mutableStateOf(desiderio?.nome ?: "") }
    var specie by remember { mutableStateOf(desiderio?.specie ?: "") }
    var note by remember { mutableStateOf(desiderio?.note ?: "") }
    var priorita by remember { mutableStateOf(desiderio?.priorita ?: 2) }
    var errore by remember { mutableStateOf<String?>(null) }
    var chiediElimina by remember { mutableStateOf(false) }
    val prio = listOf("1" to "Alta", "2" to "Media", "3" to "Bassa")

    Pagina(
        titolo = if (desiderio != null) "✏️ ${desiderio.nome}" else "💚 Pianta che vorrei",
        occupato = stato.occupato, onAnnulla = onAnnulla, errore = errore,
        onSalva = {
            if (nome.isBlank()) { errore = "Serve un nome."; return@Pagina }
            onSalva(nome.trim(), specie.trim().ifBlank { null }, note.trim().ifBlank { null }, priorita)
        },
        extra = { if (desiderio != null) Pillola("🗑 Elimina", Palette.danger) { chiediElimina = true } },
    ) {
        Campo("Nome *", nome) { nome = it }
        Campo("Specie", specie) { specie = it }
        Tendina("Priorità", prio.first { it.first == priorita.toString() }.second, prio) { priorita = it.toInt() }
        Campo("Note", note, 3) { note = it }
    }

    if (chiediElimina && desiderio != null) {
        Conferma(
            "Togliere «${desiderio.nome}» dalla lista?\n\nSe hai deciso di non prenderla, «🚫 Scarta» la lascia visibile fra le scartate.",
            onNo = { chiediElimina = false },
        ) { chiediElimina = false; onElimina(desiderio.id) }
    }
}

// ── gruppo ──────────────────────────────────────────────────────────────────

/** Gemello del modale `mGruppo` di `piante.html`: emoji, nome e le sue piante. */
@Composable
fun FormGruppo(
    stato: PianteState,
    gruppo: PvGruppo?,
    onAnnulla: () -> Unit,
    onSalva: (String, String?, Set<String>) -> Unit,
    onElimina: (String) -> Unit,
) {
    var nome by remember { mutableStateOf(gruppo?.nome ?: "") }
    var emoji by remember { mutableStateOf(gruppo?.emoji ?: "") }
    var piante by remember { mutableStateOf(gruppo?.let { g -> stato.pianteDi(g.id).map { it.id }.toSet() }.orEmpty()) }
    var errore by remember { mutableStateOf<String?>(null) }
    var chiediElimina by remember { mutableStateOf(false) }

    Pagina(
        titolo = if (gruppo != null) "✏️ ${gruppo.etichetta}" else "👥 Nuovo gruppo",
        occupato = stato.occupato, onAnnulla = onAnnulla, errore = errore,
        onSalva = {
            if (nome.isBlank()) { errore = "Serve un nome."; return@Pagina }
            errore = null
            onSalva(nome.trim(), emoji.trim().ifBlank { null }, piante)
        },
        extra = { if (gruppo != null) Pillola("🗑 Elimina", Palette.danger) { chiediElimina = true } },
    ) {
        Campo("Emoji", emoji) { emoji = it.take(8) }
        Campo("Nome *", nome) { nome = it }
        Titoletto("🪴 Piante del gruppo")
        // Le archiviate si offrono solo se sono già dentro: toglierle deve restare possibile.
        val offerte = stato.piante.filter { !it.archiviata || it.id in piante }
        if (offerte.isEmpty()) Nota("Nessuna pianta ancora.")
        else RigaScorrevole(Arrangement.spacedBy(6.dp)) {
            offerte.forEach { p ->
                Chip(p.nome + if (p.archiviata) " 📦" else "", p.id in piante) {
                    piante = if (p.id in piante) piante - p.id else piante + p.id
                }
            }
        }
        Nota("Tocca una pianta per metterla o toglierla.")
    }

    if (chiediElimina && gruppo != null) {
        val na = stato.azioni.count { it.gruppoId == gruppo.id }
        val np = stato.pianteDi(gruppo.id).size
        Conferma(
            "Eliminare il gruppo «${gruppo.nome}»?\n\nSe ne vanno anche le sue $na azioni coi promemoria.\n" +
                "Le $np piante restano, solo fuori dal gruppo; i punti già presi restano.",
            onNo = { chiediElimina = false },
        ) { chiediElimina = false; onElimina(gruppo.id) }
    }
}
