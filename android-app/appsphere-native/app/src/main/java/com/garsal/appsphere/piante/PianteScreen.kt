package com.garsal.appsphere.piante

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.larghezzaPulsanti
import com.garsal.appsphere.obiettivi.dataItalianaDa
import com.garsal.appsphere.obiettivi.giornoLocale
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Il verde delle Piante, lo stesso `--primary` di `piante.html`. */
val VerdePiante = Color(0xFF2E7D32)
private val VerdeChiaro = Color(0xFFE8F5E9)
private val ViolaIA = Color(0xFF6C5CE7)

private enum class Sezione(val etichetta: String) {
    PIANTE("🌱 Piante"), GRUPPI("👥 Gruppi"), DAFARE("📆 Da fare"), DESIDERI("💚 Desideri")
}

private enum class Scheda(val etichetta: String) {
    SCHEDA("📋 Scheda"), DIARIO("📔 Diario"), AZIONI("✅ Azioni"), IA("🤖 Chiedi all'IA")
}

/** Quale form sta prendendo lo schermo. */
private sealed interface Form {
    data class Pianta(val id: String?, val daDesiderio: PvDesiderio? = null, val clonaDa: PvPianta? = null) : Form
    data class Voce(val piantaId: String, val id: String?) : Form
    data class Azione(val bozza: BozzaAzione) : Form
    data class Desiderio(val id: String?) : Form
    data class Gruppo(val id: String?) : Form
}

/**
 * 🌱 **Piante** — il gemello nativo di `piante.html`.
 *
 * Quattro sezioni (le piante, i 👥 gruppi, le azioni da fare di tutte, la lista
 * dei desideri) e,
 * aprendo una pianta, le sue quattro schede: la scheda con le foto, il diario,
 * le azioni coi promemoria e «Chiedi all'IA».
 *
 * ⚠️ I form **prendono lo schermo intero** e non aprono un dialogo: coi
 * caratteri di sistema grandi un dialogo sarebbe una feritoia. L'indietro di
 * Android chiude prima il form, poi la pianta, poi l'app — la stessa regola di
 * `guardiaIndietroPopup` nelle pagine web.
 */
@Composable
fun PianteScreen(onIndietro: () -> Unit, vm: PianteViewModel = viewModel()) {
    val stato by vm.state.collectAsStateWithLifecycle()
    var sezione by rememberSaveable { mutableStateOf(Sezione.PIANTE) }
    var piantaAperta by rememberSaveable { mutableStateOf<String?>(null) }
    // 👥 Il gruppo aperto nella sua pagina. Da lì si apre una pianta, e
    // l'indietro da quella pianta torna al gruppo.
    var gruppoAperto by rememberSaveable { mutableStateOf<String?>(null) }
    var scheda by rememberSaveable { mutableStateOf(Scheda.SCHEDA) }
    var form by remember { mutableStateOf<Form?>(null) }
    var fotoGrande by remember { mutableStateOf<String?>(null) }
    val avvisi = remember { SnackbarHostState() }
    val oggi = LocalDate.now()

    stato.messaggio?.let { testo ->
        LaunchedEffect(testo) {
            avvisi.showSnackbar(testo)
            vm.messaggioMostrato()
        }
    }
    // Un salvataggio riuscito chiude il form da cui è partito.
    LaunchedEffect(stato.salvato) { if (stato.salvato > 0) form = null }

    BackHandler(enabled = form != null) { form = null }
    BackHandler(enabled = form == null && piantaAperta != null) { piantaAperta = null }
    BackHandler(enabled = form == null && piantaAperta == null && gruppoAperto != null) { gruppoAperto = null }

    val pianta = stato.pianta(piantaAperta)
    val gruppo = if (pianta == null) stato.gruppo(gruppoAperto) else null
    val apriGruppo: (String) -> Unit = { piantaAperta = null; gruppoAperto = it }

    val f = form
    if (f != null) {
        when (f) {
            is Form.Pianta -> FormPianta(
                stato = stato, pianta = stato.pianta(f.id), daDesiderio = f.daDesiderio,
                onAnnulla = { form = null },
                onSalva = { ctx, campi, nuove, tolte, gruppi ->
                    vm.salvaPianta(ctx, f.id, campi, nuove, tolte, f.daDesiderio?.id, gruppi) { id ->
                        if (f.id == null) { piantaAperta = id; scheda = Scheda.SCHEDA }
                    }
                },
                onElimina = { id -> vm.eliminaPianta(id); piantaAperta = null },
                onCreaGruppo = vm::creaGruppo,
                clonaDa = f.clonaDa,
            )
            is Form.Voce -> FormVoce(
                stato = stato, voce = stato.voci.firstOrNull { it.id == f.id },
                onAnnulla = { form = null },
                onSalva = { ctx, giorno, commento, nuove, tolte ->
                    vm.salvaVoce(ctx, f.id, f.piantaId, giorno, commento, nuove, tolte)
                },
            )
            is Form.Azione -> FormAzione(
                stato = stato, iniziale = f.bozza,
                onAnnulla = { form = null },
                onSalva = { vm.salvaAzione(it) },
            )
            is Form.Desiderio -> FormDesiderio(
                stato = stato, desiderio = stato.desideri.firstOrNull { it.id == f.id },
                onAnnulla = { form = null },
                onSalva = { nome, specie, note, prio -> vm.salvaDesiderio(f.id, nome, specie, note, prio) },
                onElimina = { vm.eliminaDesiderio(it) },
            )
            is Form.Gruppo -> FormGruppo(
                stato = stato, gruppo = stato.gruppo(f.id),
                onAnnulla = { form = null },
                onSalva = { nome, emoji, piante -> vm.salvaGruppo(f.id, nome, emoji, piante) { gruppoAperto = it } },
                onElimina = { vm.eliminaGruppo(it); gruppoAperto = null },
            )
        }
        return
    }

    Scaffold(
        topBar = {
            val scala = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
            GarsalTopBar(
                titolo = pianta?.nome ?: gruppo?.etichetta ?: "Piante",
                onIndietro = {
                    when {
                        piantaAperta != null -> piantaAperta = null
                        gruppoAperto != null -> gruppoAperto = null
                        else -> onIndietro()
                    }
                },
                azioni = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Ricarica",
                        tint = Palette.light,
                        modifier = Modifier.size(26.dp * scala).clickable { vm.carica() },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(avvisi) },
        floatingActionButton = {
            FloatingActionButton(
                containerColor = VerdePiante,
                contentColor = Palette.light,
                onClick = {
                    form = when {
                        pianta != null -> when (scheda) {
                            Scheda.DIARIO -> Form.Voce(pianta.id, null)
                            Scheda.AZIONI -> Form.Azione(nuovaBozza(pianta.id, stato))
                            else -> Form.Pianta(pianta.id)
                        }
                        gruppo != null -> Form.Azione(nuovaBozza("", stato, gruppo.id))
                        sezione == Sezione.GRUPPI -> Form.Gruppo(null)
                        sezione == Sezione.DESIDERI -> Form.Desiderio(null)
                        sezione == Sezione.DAFARE ->
                            stato.piante.firstOrNull { !it.archiviata }?.let { Form.Azione(nuovaBozza(it.id, stato)) }
                        else -> Form.Pianta(null)
                    }
                },
            ) { Text("+", fontSize = 26.sp) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ⚠️ La riga delle sezioni (e quella delle schede) scorre di lato e
            // non va a capo: coi caratteri grandi tre voci su 360 dp non ci stanno.
            if (gruppo != null) {
                // La pagina di un gruppo non ha sezioni né schede: si torna con l'indietro.
            } else if (pianta == null) {
                RigaScorrevole(Arrangement.spacedBy(8.dp), Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Sezione.entries.forEach { s ->
                        Chip(s.etichetta, s == sezione) { sezione = s }
                    }
                }
            } else {
                RigaScorrevole(Arrangement.spacedBy(8.dp), Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Scheda.entries.forEach { s -> Chip(s.etichetta, s == scheda) { scheda = s } }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    stato.caricamento && stato.piante.isEmpty() ->
                        CircularProgressIndicator(color = VerdePiante, modifier = Modifier.align(Alignment.Center))
                    stato.errore != null ->
                        Text(stato.errore ?: "", color = Palette.danger, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    pianta != null -> when (scheda) {
                        Scheda.SCHEDA -> SchedaPianta(stato, pianta, onFoto = { fotoGrande = it }, onModifica = { form = Form.Pianta(pianta.id) },
                            onClona = { form = Form.Pianta(null, clonaDa = pianta) },
                            onApri = { piantaAperta = it; scheda = Scheda.SCHEDA },
                            onApriGruppo = apriGruppo)
                        Scheda.DIARIO -> DiarioPianta(stato, pianta, onFoto = { fotoGrande = it },
                            onNuova = { form = Form.Voce(pianta.id, null) },
                            onModifica = { form = Form.Voce(pianta.id, it) },
                            onElimina = vm::eliminaVoce)
                        Scheda.AZIONI -> ElencoAzioni(
                            stato = stato, elenco = stato.azioniDellaPianta(pianta.id),
                            oggi = oggi, conPianta = false, conConcluse = true,
                            intestazione = { Pillola("＋ Nuova azione", VerdePiante) { form = Form.Azione(nuovaBozza(pianta.id, stato)) } },
                            vm = vm,
                            onModifica = { form = Form.Azione(bozzaDa(it, stato)) },
                            onApriPianta = {},
                            onApriGruppo = apriGruppo,
                            storico = stato.storiaDellaPianta(pianta.id),
                        )
                        Scheda.IA -> ChiediIA(stato, pianta.id, vm)
                    }
                    gruppo != null -> ElencoAzioni(
                        stato = stato, elenco = stato.azioni.filter { it.gruppoId == gruppo.id },
                        oggi = oggi, conPianta = false, conConcluse = true,
                        intestazione = {
                            TestaGruppo(stato, gruppo,
                                onNuovaAzione = { form = Form.Azione(nuovaBozza("", stato, gruppo.id)) },
                                onModifica = { form = Form.Gruppo(gruppo.id) },
                                onApriPianta = { piantaAperta = it; scheda = Scheda.SCHEDA })
                        },
                        vm = vm,
                        onModifica = { form = Form.Azione(bozzaDa(it, stato)) },
                        onApriPianta = {},
                        onApriGruppo = {},
                        vuotoGruppo = true,
                    )
                    sezione == Sezione.PIANTE -> ElencoPiante(stato, oggi) { piantaAperta = it; scheda = Scheda.SCHEDA }
                    sezione == Sezione.GRUPPI -> ElencoGruppi(stato) { gruppoAperto = it }
                    sezione == Sezione.DAFARE -> {
                        val vive = stato.piante.filter { !it.archiviata }.map { it.id }.toSet()
                        // Un'azione di gruppo conta se nel gruppo c'è almeno una pianta viva.
                        ElencoAzioni(
                            stato = stato,
                            elenco = stato.azioni.filter { a ->
                                a.piantaId in vive || (a.gruppoId != null && stato.pianteDi(a.gruppoId).any { !it.archiviata })
                            },
                            oggi = oggi, conPianta = true, conConcluse = false,
                            intestazione = {}, vm = vm,
                            onModifica = { form = Form.Azione(bozzaDa(it, stato)) },
                            onApriPianta = { piantaAperta = it; scheda = Scheda.AZIONI },
                            onApriGruppo = apriGruppo,
                        )
                    }
                    else -> Desideri(
                        stato = stato, vm = vm,
                        onModifica = { form = Form.Desiderio(it) },
                        onPresa = { form = Form.Pianta(null, it) },
                        onApriPianta = { piantaAperta = it; scheda = Scheda.SCHEDA },
                    )
                }
            }
        }
    }

    fotoGrande?.let { url ->
        Dialog(onDismissRequest = { fotoGrande = null }) {
            AsyncImage(
                model = url, contentDescription = "Foto",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clickable { fotoGrande = null },
            )
        }
    }
}

// ── elenco delle piante ─────────────────────────────────────────────────────

@Composable
private fun ElencoPiante(stato: PianteState, oggi: LocalDate, onApri: (String) -> Unit) {
    var cerca by rememberSaveable { mutableStateOf("") }
    var archiviate by rememberSaveable { mutableStateOf(false) }
    val q = cerca.trim().lowercase()
    val elenco = stato.piante.filter { p ->
        (archiviate || !p.archiviata) &&
            (q.isEmpty() || listOfNotNull(p.nome, p.specie, p.posizione).any { it.lowercase().contains(q) })
    }
    LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("🏅 ${stato.puntiTotali} punti", color = VerdePiante, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = cerca, onValueChange = { cerca = it },
                label = { Text("🔎 Cerca per nome, specie, posizione") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Chip(if (archiviate) "📦 Archiviate visibili" else "📦 Mostra anche le archiviate", archiviate) { archiviate = !archiviate }
            }
        }
        if (stato.piante.isEmpty()) {
            item { Vuoto("🪴", "Nessuna pianta ancora", "Tocca + per aggiungere la prima.") }
        } else if (elenco.isEmpty()) {
            item { Vuoto("🔎", "Nessuna pianta trovata", "") }
        }
        items(elenco, key = { it.id }) { p ->
            val url = stato.copertina(p.id)
            val prossima = stato.prossimaDi(p.id)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, Palette.border, RoundedCornerShape(8.dp))
                    .background(if (p.archiviata) Palette.inputBg else Palette.cardBg)
                    .clickable { onApri(p.id) },
            ) {
                Copertina(url, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Column(Modifier.padding(12.dp)) {
                    Text(p.nome + if (p.archiviata) " 📦" else "", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                    p.specie?.let { Text(it, fontStyle = FontStyle.Italic, color = Palette.muted) }
                    p.posizione?.let { Text("📍 $it", color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
                    val gr = stato.gruppiDi(p.id)
                    if (gr.isNotEmpty()) Text(gr.joinToString(" · ") { it.etichetta }, color = Palette.accent,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    val g = prossima?.giorno
                    if (prossima != null && g != null) {
                        Text(
                            "${prossima.titolo} · ${quando(g, oggi)}",
                            color = if (g.isBefore(oggi)) Palette.danger else Palette.dark,
                            fontWeight = if (g.isBefore(oggi)) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Copertina(url: String?, modifier: Modifier) {
    Box(modifier.background(VerdeChiaro), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) Text("🪴", fontSize = 48.sp)
        else AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

// ── scheda ──────────────────────────────────────────────────────────────────

@Composable
private fun SchedaPianta(
    stato: PianteState, p: PvPianta, onFoto: (String) -> Unit, onModifica: () -> Unit,
    onClona: () -> Unit, onApri: (String) -> Unit, onApriGruppo: (String) -> Unit,
) {
    val foto = stato.fotoScheda(p.id)
    LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Copertina(stato.copertina(p.id), Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .clickable { stato.copertina(p.id)?.let(onFoto) })
            p.specie?.let { Text(it, fontStyle = FontStyle.Italic, color = Palette.muted, modifier = Modifier.padding(top = 6.dp)) }
        }
        val campi = listOf(
            "📍 Posizione" to p.posizione,
            "📅 Arrivata il" to p.arrivataIl?.let { dataItalianaDa(giornoLocale(it)) },
            "☀️ Luce" to p.luce,
            "💧 Annaffiatura" to p.annaffiatura,
            "🪴 Terriccio e vaso" to p.terriccio,
            "🧪 Concime" to p.concime,
            "📝 Note" to p.note,
        )
        items(campi, key = { it.first }) { (etichetta, valore) -> Riquadro(etichetta, valore) }
        // 🌿 La madre e 🌱 le figlie si toccano per aprirle. Compaiono solo se ci sono:
        // «da scrivere» su una parentela che non esiste sarebbe un invito sbagliato.
        p.madreId?.let { mid ->
            item {
                val madre = stato.pianta(mid)
                Parentela("🌿 Discende da", listOfNotNull(madre), if (madre == null) "pianta non più in elenco" else null, onApri)
            }
        }
        val gruppi = stato.gruppiDi(p.id)
        if (gruppi.isNotEmpty()) item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.inputBg)
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(10.dp),
            ) {
                Text("👥 GRUPPI", color = Palette.muted, style = MaterialTheme.typography.labelMedium)
                gruppi.forEach { g ->
                    Text(g.etichetta, color = Palette.accent, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clickable { onApriGruppo(g.id) }.padding(vertical = 4.dp))
                }
            }
        }
        val figlie = stato.piante.filter { it.madreId == p.id }
        if (figlie.isNotEmpty()) item { Parentela("🌱 Figlie", figlie, null, onApri) }
        if (foto.size > 1) {
            item {
                Text("📷 Foto della pianta", fontWeight = FontWeight.Bold)
                Galleria(foto.map { it.url }, onFoto)
            }
        }
        item {
            val etichette = listOf("✏️ Modifica la scheda", "📋 Clona")
            val w = larghezzaPulsanti(etichette)
            RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                Pillola(etichette[0], VerdePiante, w) { onModifica() }
                Pillola(etichette[1], Palette.muted, w) { onClona() }
            }
        }
    }
}

@Composable
private fun Parentela(etichetta: String, piante: List<PvPianta>, vuoto: String?, onApri: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.inputBg)
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(10.dp),
    ) {
        Text(etichetta.uppercase(), color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        vuoto?.let { Text(it, color = Palette.muted, fontStyle = FontStyle.Italic) }
        piante.forEach { x ->
            Text(x.nome, color = VerdePiante, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable { onApri(x.id) }.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun Riquadro(etichetta: String, valore: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.inputBg)
            .border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(10.dp),
    ) {
        Text(etichetta.uppercase(), color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        Text(
            valore ?: "da scrivere",
            color = if (valore == null) Palette.muted else Palette.dark,
            fontStyle = if (valore == null) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

/**
 * Le miniature in file che **scorrono di lato**: una griglia di celle quadrate
 * in `dp` non cresce col testo e non ne ha bisogno — sono foto, non scritte.
 */
@Composable
private fun Galleria(urls: List<String>, onFoto: (String) -> Unit) {
    RigaScorrevole(Arrangement.spacedBy(6.dp), Modifier.padding(top = 6.dp)) {
        urls.filter { it.isNotBlank() }.forEach { url ->
            AsyncImage(
                model = url, contentDescription = "Foto", contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).clickable { onFoto(url) },
            )
        }
    }
}

// ── diario ──────────────────────────────────────────────────────────────────

@Composable
private fun DiarioPianta(
    stato: PianteState,
    p: PvPianta,
    onFoto: (String) -> Unit,
    onNuova: () -> Unit,
    onModifica: (String) -> Unit,
    onElimina: (String) -> Unit,
) {
    val voci = stato.voci.filter { it.piantaId == p.id }
    var daEliminare by remember { mutableStateOf<PvVoce?>(null) }
    val oggi = LocalDate.now()
    LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Pillola("＋ Nuova voce", VerdePiante) { onNuova() } }
        if (voci.isEmpty()) item { Vuoto("📔", "Il diario è vuoto", "Scrivi com'è oggi e aggiungi una foto: fra qualche mese sarà bello rileggerlo.") }
        items(voci, key = { it.id }) { v ->
            val giorno = runCatching { LocalDate.parse(v.giorno) }.getOrNull()
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(10.dp),
            ) {
                // ✏️ e 🗑 in testa alla riga: l'ultima colonna finisce oltre il bordo.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconaTesto("✏️") { onModifica(v.id) }
                    IconaTesto("🗑") { daEliminare = v }
                    Column(Modifier.weight(1f)) {
                        Text(dataItalianaDa(giorno), fontWeight = FontWeight.Bold, color = VerdePiante)
                        giorno?.let { Text(quando(it, oggi), color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                v.commento?.let { Text(it, modifier = Modifier.padding(top = 6.dp)) }
                val foto = stato.fotoVoce(v.id)
                if (foto.isNotEmpty()) Galleria(foto.map { it.url }, onFoto)
            }
        }
    }
    daEliminare?.let { v ->
        val n = stato.fotoVoce(v.id).size
        Conferma(
            testo = "Eliminare la voce del ${dataItalianaDa(runCatching { LocalDate.parse(v.giorno) }.getOrNull())}" +
                (if (n > 0) " e le sue $n foto?" else "?"),
            onNo = { daEliminare = null },
            onSi = { daEliminare = null; onElimina(v.id) },
        )
    }
}

// ── azioni ──────────────────────────────────────────────────────────────────

private const val FATTO = "✅ Fatto"
private const val SALTA = "⏭ Salta"

@Composable
private fun ElencoAzioni(
    stato: PianteState,
    elenco: List<PvAzione>,
    oggi: LocalDate,
    conPianta: Boolean,
    conConcluse: Boolean,
    intestazione: @Composable () -> Unit,
    vm: PianteViewModel,
    onModifica: (PvAzione) -> Unit,
    onApriPianta: (String) -> Unit,
    onApriGruppo: (String) -> Unit,
    /** Le «📜 Ultime volte» sotto le azioni di una pianta; vuoto altrove. */
    storico: List<PvStoria> = emptyList(),
    /** Nella pagina di un gruppo il vuoto si dice in un altro modo. */
    vuotoGruppo: Boolean = false,
) {
    val gruppi = stato.gruppi(elenco, oggi, conConcluse)
    var daSaltare by remember { mutableStateOf<PvAzione?>(null) }
    var daEliminare by remember { mutableStateOf<PvAzione?>(null) }
    // ⚠️ `larghezzaPulsanti` vuole tutte le etichette possibili della riga.
    val larghezza = larghezzaPulsanti(listOf(FATTO, SALTA))
    LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { intestazione() }
        if (gruppi.none { it.chiave != "done" }) {
            item {
                Vuoto(
                    "🌿", if (conPianta) "Niente da fare" else "Nessuna azione in corso",
                    when {
                        conPianta -> "Le azioni si aggiungono dalla scheda di ogni pianta."
                        vuotoGruppo -> "Quelle che aggiungi qui valgono per tutte le piante del gruppo."
                        else -> "Annaffiare, concimare, rinvasare… coi promemoria che vuoi."
                    },
                )
            }
        }
        gruppi.forEach { g ->
            item(key = "g-" + g.chiave) {
                val colore = when (g.chiave) {
                    "late" -> Palette.danger; "today" -> Palette.warning; "next" -> Palette.accent
                    "free" -> ViolaIA; else -> Palette.muted
                }
                Text("${g.titolo} (${g.azioni.size})", color = colore, fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleMedium)
            }
            items(g.azioni, key = { "a-" + it.id }) { a ->
                SchedaAzione(stato, a, oggi, conPianta, larghezza,
                    onFatto = { vm.completa(a) }, onSalta = { daSaltare = a },
                    onModifica = { onModifica(a) }, onElimina = { daEliminare = a }, onApriPianta = onApriPianta,
                    onApriGruppo = onApriGruppo)
            }
        }
        run {
            val righe = storico
            if (righe.isNotEmpty()) {
                item {
                    Text("📜 Ultime volte", fontWeight = FontWeight.Black, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
                    val et = mapOf("completed" to "✅ fatta", "completed_late" to "✅ in ritardo", "skipped" to "⏭ saltata")
                    righe.forEach { r ->
                        Text(
                            "${dataItalianaDa(giornoLocale(r.quando))} · ${r.titolo} · ${et[r.azione] ?: r.azione} · ${if (r.punti > 0) "+" else ""}${r.punti} pt",
                            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    daSaltare?.let { a ->
        DialogoSalta(a, onAnnulla = { daSaltare = null }) { giorni -> daSaltare = null; vm.salta(a, giorni) }
    }
    daEliminare?.let { a ->
        Conferma(
            testo = "Eliminare l'azione «${a.titolo}»?" +
                (stato.gruppo(a.gruppoId)?.let { "\n\nÈ del gruppo «${it.nome}»: sparisce da tutte le sue piante." } ?: "") +
                "\n\nI suoi promemoria si spengono; i punti già presi restano.",
            onNo = { daEliminare = null },
            onSi = { daEliminare = null; vm.eliminaAzione(a.id) },
        )
    }
}

@Composable
private fun SchedaAzione(
    stato: PianteState,
    a: PvAzione,
    oggi: LocalDate,
    conPianta: Boolean,
    larghezza: Dp,
    onFatto: () -> Unit,
    onSalta: () -> Unit,
    onModifica: () -> Unit,
    onElimina: () -> Unit,
    onApriPianta: (String) -> Unit,
    onApriGruppo: (String) -> Unit,
) {
    val inRitardo = a.giorno?.isBefore(oggi) == true
    val quandoTxt = when {
        !a.viva -> "conclusa" + (a.ultimaVolta?.let { " il " + dataItalianaDa(giornoLocale(it)) } ?: "")
        a.giorno == null -> a.ultimaVolta?.let { "ultima volta " + quando(giornoLocale(it) ?: oggi, oggi) } ?: "mai fatta"
        else -> quando(a.giorno!!, oggi) + a.ora.takeIf { it.isNotBlank() && it != "00:00" }?.let { " · $it" }.orEmpty()
    }
    val canali = stato.canali(a.id)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Palette.cardBg)
            .border(1.dp, if (inRitardo && a.viva) Palette.danger.copy(alpha = 0.5f) else Palette.border, RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconaTesto("✏️", onModifica)
            IconaTesto("🗑", onElimina)
            Column(Modifier.weight(1f)) {
                Text(a.titolo, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                if (conPianta) stato.pianta(a.piantaId)?.let { p ->
                    Text("🪴 ${p.nome}", color = VerdePiante, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onApriPianta(p.id) })
                }
                stato.gruppo(a.gruppoId)?.let { g ->
                    Text("${g.etichetta} · vale per ${stato.pianteDi(g.id).size} piante", color = Palette.accent,
                        fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onApriGruppo(g.id) })
                }
                Text(
                    listOf(quandoTxt, descriviRicorrenza(a), canali).filter { it.isNotBlank() }.joinToString(" · "),
                    color = if (inRitardo && a.viva) Palette.danger else Palette.muted,
                    fontWeight = if (inRitardo && a.viva) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall,
                )
                a.descrizione?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (a.viva) {
            RigaScorrevole(Arrangement.spacedBy(8.dp), Modifier.padding(top = 8.dp)) {
                if (a.tipo != "workflow") Pillola(FATTO, Palette.success, larghezza) { onFatto() }
                if (a.tipo in PvAzione.PUO_SALTARE) Pillola(SALTA, Palette.warning, larghezza) { onSalta() }
            }
        }
    }
}

/** Come `descriviRicorrenza()` in `piante.html`. */
fun descriviRicorrenza(a: PvAzione): String {
    val n = a.intervallo
    return when (a.tipo) {
        "simple_recurring" -> "ogni ${a.ogniGiorni ?: 7} giorni"
        "recurring" -> when (a.frequenza) {
            "daily" -> if (n == 1) "ogni giorno" else "ogni $n giorni"
            "weekly" -> {
                val nomi = mapOf(0 to "dom", 1 to "lun", 2 to "mar", 3 to "mer", 4 to "gio", 5 to "ven", 6 to "sab")
                val gg = a.giorniSettimana.mapNotNull { nomi[it] }.joinToString(", ")
                (if (n == 1) "ogni settimana" else "ogni $n settimane") + if (gg.isNotBlank()) " ($gg)" else ""
            }
            "monthly" -> (if (n == 1) "ogni mese" else "ogni $n mesi") +
                if (a.giorniMese.isNotEmpty()) " il ${a.giorniMese.joinToString(", ")}" else ""
            "yearly" -> "ogni anno" + if (a.dateAnno.isNotEmpty()) " il ${a.dateAnno.joinToString(", ")}" else ""
            else -> "ricorrente"
        }
        "multiple" -> "${a.dateMultiple.size} date"
        "free_repeat" -> "quando capita"
        "workflow" -> "a passi"
        else -> "una volta"
    }
}

/**
 * Di quanti giorni rimandare: lo chiede **solo** per una «una volta». Per gli
 * altri tipi la RPC ignora `p_days`, e chiedere un numero che il server butta
 * via sarebbe una bugia.
 */
@Composable
private fun DialogoSalta(a: PvAzione, onAnnulla: () -> Unit, onConferma: (Int) -> Unit) {
    if (a.tipo != "single") {
        AlertDialog(
            onDismissRequest = onAnnulla,
            title = { Text("Saltare?") },
            text = { Text("«${a.titolo}» va alla sua prossima volta.") },
            confirmButton = { TextButton(onClick = { onConferma(1) }) { Text("Salta") } },
            dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
        )
        return
    }
    var giorni by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text("⏭ Rimanda") },
        text = {
            OutlinedTextField(
                value = giorni, onValueChange = { giorni = it.filter(Char::isDigit).take(3) },
                label = { Text("Di quanti giorni") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { onConferma(maxOf(1, giorni.toIntOrNull() ?: 1)) }) { Text("Rimanda") } },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}

// ── IA ──────────────────────────────────────────────────────────────────────

private val SUGGERIMENTI = listOf(
    "Come sta la mia pianta, dalle ultime foto?",
    "Sto annaffiando nel modo giusto?",
    "Quando conviene rinvasarla?",
    "Perché le foglie cambiano colore?",
    "Che azioni di cura mi consigli di programmare?",
)
private val SUGGERIMENTI_GENERALI = listOf(
    "Quale mi consigli di prendere per prima?",
    "Quali sono le più facili da curare?",
    "Suggeriscimi altre piante simili a quelle che ho",
)

@Composable
private fun ChiediIA(stato: PianteState, piantaId: String?, vm: PianteViewModel) {
    // Il prompt sta qui e non dentro BoxIA: il ⤴️ di una risposta dello storico lo deve poter riscrivere.
    var domanda by rememberSaveable(piantaId) { mutableStateOf("") }
    val lista = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(state = lista, contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BoxIA(stato, piantaId, vm, domanda) { domanda = it } }
        storicoIA(stato, piantaId, vm) { domanda = it; scope.launch { lista.animateScrollToItem(0) } }
    }
}

@Composable
private fun BoxIA(stato: PianteState, piantaId: String?, vm: PianteViewModel, domanda: String, onDomanda: (String) -> Unit) {
    var costo by remember { mutableStateOf<String?>(null) }
    // L'ultima IA scelta è una comodità di questo telefono: preferenze, non database.
    val prefs = LocalContext.current.getSharedPreferences("piante", android.content.Context.MODE_PRIVATE)
    LaunchedEffect(Unit) {
        if (stato.fornitore == null) prefs.getString("ia_fornitore", null)?.let { vm.scegliFornitore(it) }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFFF5F3FF))
            .border(2.dp, ViolaIA, RoundedCornerShape(6.dp)).padding(12.dp),
    ) {
        Text("🤖 Chiedi all'IA", fontWeight = FontWeight.Black)
        Text(
            if (piantaId != null) "Le mando la scheda, il diario, le azioni e fino a quattro foto recenti."
            else "Le do l'elenco delle piante che hai e di quelle che vorresti.",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = domanda, onValueChange = onDomanda,
            label = { Text("Cosa vuoi sapere?") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(top = 6.dp),
        )
        // I suggerimenti vanno a capo (sono frasi, non pulsanti d'azione).
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (if (piantaId != null) SUGGERIMENTI else SUGGERIMENTI_GENERALI).forEach { s ->
                Text(
                    s, modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                        .clickable { onDomanda(s) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            val offerti = stato.fornitoriOfferti
            Tendina(
                etichetta = "Quale IA",
                scelto = "🤖 " + (offerti.firstOrNull { it.first == stato.fornitoreScelto }?.second ?: nomeIA(stato.fornitoreScelto)),
                voci = offerti.map { it.first to "🤖 " + it.second },
                abilitata = !stato.iaInCorso,
            ) { v ->
                vm.scegliFornitore(v)
                prefs.edit().putString("ia_fornitore", v).apply()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stato.iaInCorso) {
                CircularProgressIndicator(color = ViolaIA, modifier = Modifier.size(24.dp))
                Text("  ${nomeIA(stato.fornitoreScelto)} ci sta pensando… (anche un minuto, con le foto)", color = ViolaIA, fontWeight = FontWeight.Bold)
            } else {
                Pillola("🤖 Chiedi", ViolaIA) {
                    if (domanda.isBlank()) return@Pillola
                    val avviso = AVVISO_COSTO[stato.fornitoreScelto]
                    if (avviso != null) costo = avviso else vm.chiedi(piantaId, domanda) { onDomanda("") }
                }
            }
        }
        costo?.let { testo ->
            AlertDialog(
                onDismissRequest = { costo = null },
                title = { Text("💶 Questa IA si paga") },
                text = { Text("$testo\n\nGemini e Groq sono gratuiti.") },
                confirmButton = { TextButton(onClick = { costo = null; vm.chiedi(piantaId, domanda) { onDomanda("") } }) { Text("Chiedi lo stesso") } },
                dismissButton = { TextButton(onClick = { costo = null }) { Text("Annulla") } },
            )
        }
        stato.erroreIA?.let { Text("❌ $it", color = Palette.danger, modifier = Modifier.padding(top = 6.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.storicoIA(
    stato: PianteState, piantaId: String?, vm: PianteViewModel,
    // ⤴️ La risposta prende il posto del prompt, per correggerla e rilanciarla (gemello di rispostaNelPrompt in piante.html).
    onNelPrompt: (String) -> Unit,
) {
    val elenco = stato.risposte.filter { it.piantaId == piantaId }.sortedByDescending { it.creata }
    items(elenco, key = { "r-" + it.id }) { r ->
        var chiedi by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.border, RoundedCornerShape(6.dp)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconaTesto("🗑") { chiedi = true }
                IconaTesto("⤴️") { onNelPrompt(r.risposta) }
                Column(Modifier.weight(1f)) {
                    Text("❓ ${r.domanda}", fontWeight = FontWeight.Black)
                    Text(dataItalianaDa(giornoLocale(r.creata)) + " · " + nomeIA(r.fornitore), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(markdown(r.risposta), modifier = Modifier.padding(top = 6.dp))
        }
        if (chiedi) Conferma("Eliminare questa risposta?", onNo = { chiedi = false }) { chiedi = false; vm.eliminaRisposta(r.id) }
    }
}

/** Quel poco di Markdown che una risposta usa: titoli, grassetto, elenchi. */
fun markdown(testo: String): AnnotatedString = buildAnnotatedString {
    testo.lines().forEachIndexed { i, riga ->
        if (i > 0) append("\n")
        var t = riga.trimEnd()
        val titolo = Regex("^#{1,6}\\s+").find(t)
        if (titolo != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append(t.substring(titolo.range.last + 1)) }
            return@forEachIndexed
        }
        Regex("^\\s*[-*•]\\s+").find(t)?.let { t = "• " + t.substring(it.range.last + 1) }
        // **grassetto**
        var resto = t
        while (true) {
            val inizio = resto.indexOf("**")
            val fine = if (inizio >= 0) resto.indexOf("**", inizio + 2) else -1
            if (inizio < 0 || fine < 0) { append(resto); break }
            append(resto.substring(0, inizio))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(resto.substring(inizio + 2, fine)) }
            resto = resto.substring(fine + 2)
        }
    }
}

// ── desideri ────────────────────────────────────────────────────────────────

@Composable
private fun Desideri(
    stato: PianteState,
    vm: PianteViewModel,
    onModifica: (String) -> Unit,
    onPresa: (PvDesiderio) -> Unit,
    onApriPianta: (String) -> Unit,
) {
    var filtro by rememberSaveable { mutableStateOf("desiderata") }
    var domanda by rememberSaveable { mutableStateOf("") }
    val lista = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val filtri = listOf("desiderata" to "💚 Da prendere", "presa" to "🌱 Prese", "scartata" to "🚫 Scartate", "tutte" to "Tutte")
    val elenco = stato.desideri.filter { filtro == "tutte" || it.stato == filtro }
    val larghezza = larghezzaPulsanti(listOf("🌱 L'ho presa", "🚫 Scarta", "↺ Rimetti fra i desideri"))
    LazyColumn(state = lista, contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BoxIA(stato, null, vm, domanda) { domanda = it } }
        item {
            RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                filtri.forEach { (k, t) ->
                    val n = if (k == "tutte") stato.desideri.size else stato.desideri.count { it.stato == k }
                    Chip("$t ($n)", filtro == k) { filtro = k }
                }
            }
        }
        if (elenco.isEmpty()) item { Vuoto("💚", "Niente qui", "Tocca + per aggiungere una pianta che ti piacerebbe curare.") }
        items(elenco, key = { "d-" + it.id }) { d ->
            val scartata = d.stato == "scartata"
            val pianta = stato.pianta(d.piantaId)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(2.dp, Palette.border, RoundedCornerShape(6.dp))
                    .background(if (scartata) Palette.inputBg else Palette.cardBg).padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconaTesto("✏️") { onModifica(d.id) }
                    Column(Modifier.weight(1f)) {
                        Text(d.nome, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium,
                            color = if (scartata) Palette.muted else Palette.dark)
                        Text(
                            mapOf(1 to "priorità alta", 2 to "priorità media", 3 to "priorità bassa")[d.priorita] ?: "",
                            color = when (d.priorita) { 1 -> Palette.danger; 2 -> Palette.warning; else -> Palette.muted },
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        )
                        d.specie?.let { Text(it, fontStyle = FontStyle.Italic, color = Palette.muted) }
                        d.note?.let { Text(it, color = Palette.muted, style = MaterialTheme.typography.bodySmall) }
                        pianta?.let { p ->
                            Text("🪴 È diventata «${p.nome}»", color = VerdePiante, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onApriPianta(p.id) })
                        }
                    }
                }
                RigaScorrevole(Arrangement.spacedBy(8.dp), Modifier.padding(top = 8.dp)) {
                    if (d.stato == "desiderata") {
                        Pillola("🌱 L'ho presa", Palette.success, larghezza) { onPresa(d) }
                        Pillola("🚫 Scarta", Palette.muted, larghezza) { vm.statoDesiderio(d.id, "scartata") }
                    } else {
                        Pillola("↺ Rimetti fra i desideri", Palette.muted, larghezza) { vm.statoDesiderio(d.id, "desiderata") }
                    }
                }
            }
        }
        storicoIA(stato, null, vm) { domanda = it; scope.launch { lista.animateScrollToItem(0) } }
    }
}

// ── 👥 gruppi ───────────────────────────────────────────────────────────────

/** L'elenco dei gruppi, come `renderGruppi()` nel web. */
@Composable
private fun ElencoGruppi(stato: PianteState, onApri: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Un'azione messa su un gruppo vale per tutte le sue piante: un promemoria e un ✅ Fatto per tutte, " +
                    "anche per quelle che entrano nel gruppo dopo.",
                color = Palette.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (stato.gruppi.isEmpty()) {
            item { Vuoto("👥", "Nessun gruppo ancora", "Tocca + per crearne uno, o scrivilo nella scheda di una pianta.") }
        }
        items(stato.gruppi, key = { "gr-" + it.id }) { g ->
            val piante = stato.pianteDi(g.id)
            val az = stato.azioni.count { it.gruppoId == g.id && it.viva }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .border(2.dp, Palette.border, RoundedCornerShape(8.dp)).background(Palette.cardBg)
                    .clickable { onApri(g.id) }.padding(12.dp),
            ) {
                Text(g.etichetta, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${piante.size} ${if (piante.size == 1) "pianta" else "piante"} · $az ${if (az == 1) "azione" else "azioni"}",
                    color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                )
                if (piante.isNotEmpty()) Text(piante.joinToString(", ") { it.nome }, color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** La testa della pagina di un gruppo, come `renderGruppo()`: pulsanti e piante. */
@Composable
private fun TestaGruppo(
    stato: PianteState, g: PvGruppo,
    onNuovaAzione: () -> Unit, onModifica: () -> Unit, onApriPianta: (String) -> Unit,
) {
    val piante = stato.pianteDi(g.id)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val etichette = listOf("＋ Nuova azione per il gruppo", "✏️ Modifica il gruppo")
        val w = larghezzaPulsanti(etichette)
        RigaScorrevole(Arrangement.spacedBy(8.dp)) {
            Pillola(etichette[0], VerdePiante, w) { onNuovaAzione() }
            Pillola(etichette[1], Palette.muted, w) { onModifica() }
        }
        Text("🪴 Piante (${piante.size})", fontWeight = FontWeight.Bold)
        if (piante.isEmpty()) {
            Text("Nessuna pianta: aggiungile da ✏️ Modifica il gruppo.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                piante.forEach { p -> Chip(p.nome + if (p.archiviata) " 📦" else "", false) { onApriPianta(p.id) } }
            }
        }
        Text("✅ Azioni del gruppo", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

// ── pezzi ───────────────────────────────────────────────────────────────────

@Composable
fun Chip(testo: String, scelto: Boolean, onClick: () -> Unit) {
    Text(
        testo,
        color = if (scelto) Palette.light else Palette.dark,
        fontWeight = FontWeight.Bold,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (scelto) VerdePiante else Palette.inputBg)
            .border(1.dp, if (scelto) VerdePiante else Palette.border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Un'icona toccabile con un minimo in `dp`: il dito è grande uguale a ogni ingrandimento. */
@Composable
fun IconaTesto(icona: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).heightIn(min = 40.dp).width(44.dp),
        contentAlignment = Alignment.Center,
    ) { Text(icona) }
}

@Composable
private fun Vuoto(icona: String, titolo: String, testo: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icona, fontSize = 42.sp)
        Text(titolo, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        if (testo.isNotBlank()) Text(testo, color = Palette.muted)
    }
}

@Composable
fun Conferma(testo: String, onNo: () -> Unit, onSi: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        text = { Text(testo) },
        confirmButton = { TextButton(onClick = onSi) { Text("Elimina", color = Palette.danger) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Annulla") } },
    )
}

/** Una bozza nuova: anticipi proposti = il più vicino all'ora, come nel web. */
private fun nuovaBozza(piantaId: String, stato: PianteState, gruppoId: String? = null): BozzaAzione {
    val primo = stato.preset.minByOrNull { kotlin.math.abs(it.minuti) }?.id
    return BozzaAzione(
        piantaId = piantaId,
        gruppoId = gruppoId,
        anticipiTelegram = setOfNotNull(primo),
        anticipiTelefono = setOfNotNull(primo),
    )
}

/** Riapre un'azione nel form, coi suoi promemoria letti dalle regole. */
private fun bozzaDa(a: PvAzione, stato: PianteState): BozzaAzione {
    val regola = { canale: String -> stato.regole.firstOrNull { it.azioneId == a.id && it.canale == canale } }
    val primo = stato.preset.minByOrNull { kotlin.math.abs(it.minuti) }?.id
    val tg = regola("telegram")
    val an = regola("android")
    return BozzaAzione(
        id = a.id,
        piantaId = a.piantaId,
        gruppoId = a.gruppoId,
        titolo = a.titolo,
        descrizione = a.descrizione.orEmpty(),
        tipo = if (a.tipo == "workflow") "single" else a.tipo,
        giorno = a.giorno ?: LocalDate.now(),
        ora = a.ora.ifBlank { "09:00" },
        scadenza = a.scadenza?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
        ogniGiorni = a.ogniGiorni ?: 7,
        frequenza = a.frequenza ?: "weekly",
        intervallo = a.intervallo,
        giorniSettimana = a.giorniSettimana,
        giorniMese = a.giorniMese.joinToString(", "),
        dateAnno = a.dateAnno.joinToString(", "),
        dateMultiple = a.dateMultiple.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() },
        puntiOk = a.puntiOk, puntiSalto = a.puntiSalto, puntiRitardo = a.puntiRitardo,
        telegram = tg != null,
        anticipiTelegram = tg?.anticipi?.toSet() ?: setOfNotNull(primo),
        telefono = an != null,
        anticipiTelefono = an?.anticipi?.toSet() ?: setOfNotNull(primo),
        smartBlock = regola("smart_block") != null,
        riaperta = !a.viva,
    )
}
