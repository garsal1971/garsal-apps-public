package com.garsal.appsphere.abituati

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.coloreDaHex
import com.garsal.appsphere.core.larghezzaPulsanti
import java.time.LocalDate

/** Il nero della bolla di Abituati (`#1A1A1A`, uno dei cerchi olimpici). */
internal val NeroAbituati = Color(0xFF1A1A1A)

private enum class Vista(val etichetta: String) {
    OGGI("🎯 Oggi"),
    TUTTE("📋 Tutte"),
    ARCHIVIO("📦 Archivio"),
    STATISTICHE("📊 Statistiche"),
    CATEGORIE("🏷️ Categorie"),
    IMPOSTAZIONI("⚙️ Impostazioni"),
}

/**
 * Il filtro sullo stato di 📋 Tutte, le stesse tre voci della tendina del web.
 *
 * Parte da «Attive» come là: le interrotte sono memoria, non lavoro di oggi, e
 * un elenco che le mescola alle vive fa sembrare da spuntare qualcosa che è
 * fermo. Una scelta fra poche opzioni fisse è **sempre una tendina**, mai una
 * fila di pillole: coi caratteri di sistema grandi quelle andrebbero a capo o
 * si accorcerebbero (vedi `core/PulsantiTendine.kt`).
 */
private enum class FiltroStato(val etichetta: String, val vuoto: String) {
    ATTIVE("Attive", "Nessuna abitudine attiva. Creane una col +"),
    INTERROTTE("Interrotte", "Nessuna abitudine interrotta."),
    TUTTE("Tutte", "Nessuna abitudine. Creane una col +");

    fun tiene(a: HbAbitudine): Boolean = when (this) {
        ATTIVE -> a.stato == "active"
        INTERROTTE -> a.stato == "stopped"
        TUTTE -> true
    }
}

/**
 * Abituati in nativo: le abitudini di oggi da spuntare, l'elenco completo e
 * l'archivio degli stack finiti.
 *
 * ⚠️ **Nessuna regola vive qui.** Streak, jolly, chiusura degli stack e giorni
 * mancati stanno nelle RPC `hb_*` (`20260815120000_hb_regole_rpc.sql`), che
 * `habit-tracker.html` chiama esattamente come questa schermata: una regola
 * sola per tutt'e due, che è la ragione per cui quelle funzioni esistono. Qui
 * si disegna e si chiede.
 */
@Composable
fun AbituatiScreen(
    onIndietro: () -> Unit,
    vm: AbituatiViewModel = viewModel(),
) {
    val stato by vm.state.collectAsStateWithLifecycle()
    val oggi = LocalDate.now()

    var vista by remember { mutableStateOf(Vista.OGGI) }
    var filtro by remember { mutableStateOf(FiltroStato.ATTIVE) }
    var inCompilazione by remember { mutableStateOf<Pair<BozzaAbitudine, String?>?>(null) }
    var daEliminare by remember { mutableStateOf<HbAbitudine?>(null) }
    var daInterrompere by remember { mutableStateOf<HbAbitudine?>(null) }
    var daRiprendere by remember { mutableStateOf<HbAbitudine?>(null) }
    // La categoria nel form: (categoria o null per una nuova). Null = form chiuso.
    var categoriaInForm by remember { mutableStateOf<Pair<HbCategoria?, Boolean>?>(null) }
    var categoriaDaEliminare by remember { mutableStateOf<HbCategoria?>(null) }

    inCompilazione?.let { (bozza, id) ->
        AbituatiForm(
            bozzaIniziale = bozza,
            id = id,
            categorie = stato.categorie,
            preset = stato.preset,
            onAnnulla = { inCompilazione = null },
            onSalva = { compilata -> vm.salva(id, compilata) { inCompilazione = null } },
        )
        return
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = "Abituati",
                onIndietro = onIndietro,
            )
        },
        floatingActionButton = {
            if (vista != Vista.STATISTICHE && vista != Vista.IMPOSTAZIONI) FloatingActionButton(
                onClick = {
                    if (vista == Vista.CATEGORIE) categoriaInForm = null to true
                    else inCompilazione = BozzaAbitudine() to null
                },
                containerColor = NeroAbituati,
                contentColor = Palette.light,
            ) { Text("+", fontSize = 26.sp) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            SelettoreVista(vista) { vista = it }

            stato.errore?.let { messaggio ->
                Text(
                    text = messaggio,
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { vm.scartaMessaggi() },
                )
            }

            // Un avviso che non è un errore — «la finestra è già piena», «oggi
            // hai già segnato N volte». Senza questa riga quei tre casi
            // resterebbero muti: il ＋ è spento apposta perché non capitino, ma
            // uno stato che si scrive e non si vede è peggio di uno che non si
            // scrive affatto.
            stato.messaggio?.let { avviso ->
                Text(
                    text = avviso,
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { vm.scartaMessaggi() },
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    stato.caricamento && stato.abitudini.isEmpty() ->
                        CircularProgressIndicator(
                            color = NeroAbituati,
                            modifier = Modifier.align(Alignment.Center),
                        )

                    vista == Vista.ARCHIVIO -> Archivio(stato.archivio)
                    vista == Vista.STATISTICHE -> Statistiche(stato)
                    vista == Vista.CATEGORIE -> Categorie(
                        stato,
                        onModifica = { categoriaInForm = it to true },
                        onElimina = { categoriaDaEliminare = it },
                    )
                    vista == Vista.IMPOSTAZIONI -> Impostazioni(
                        stato,
                        onAggiorna = { vm.carica() },
                        onSvuota = { vm.svuotaRegistro() },
                    )

                    else -> {
                        val elenco = if (vista == Vista.OGGI) stato.diOggi(oggi)
                        else stato.abitudini.filter { filtro.tiene(it) }

                        Column(Modifier.fillMaxSize()) {
                            if (vista == Vista.TUTTE) {
                                Box(Modifier.padding(horizontal = 12.dp)) {
                                    Tendina(
                                        etichetta = "Stato",
                                        scelto = filtro.etichetta,
                                        voci = FiltroStato.entries.map { it.name to it.etichetta },
                                    ) { scelta -> filtro = FiltroStato.valueOf(scelta) }
                                }
                            }

                            if (elenco.isEmpty()) {
                                Vuoto(
                                    icona = if (vista == Vista.OGGI) "🎯" else "📋",
                                    testo = if (vista == Vista.OGGI)
                                        "Oggi non c'è niente da spuntare."
                                    else
                                        filtro.vuoto,
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(elenco, key = { it.id }) { abitudine ->
                                        SchedaAbitudine(
                                            abitudine = abitudine,
                                            stato = stato,
                                            oggi = oggi,
                                            conSpunte = vista == Vista.OGGI,
                                            onSegna = { giorno, nuovo, orario ->
                                                vm.segna(abitudine, giorno, nuovo, orario, oggi)
                                            },
                                            onPiu = { vm.aggiungi(abitudine, oggi) },
                                            onMeno = { vm.togli(abitudine, oggi) },
                                            onModifica = {
                                                inCompilazione = BozzaAbitudine.da(abitudine, stato.regole) to abitudine.id
                                            },
                                            onInterrompi = { daInterrompere = abitudine },
                                            onRiprendi = { daRiprendere = abitudine },
                                            onElimina = { daEliminare = abitudine },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Le cerimonie ────────────────────────────────────────────────────
    stato.daFesteggiare.firstOrNull()?.let { esito ->
        Festa(
            esito = esito,
            onRicomincia = { inizio -> vm.riparti(esito, inizio, oggi) },
            onChiudi = { vm.scartaFesta(esito) },
        )
    }

    stato.gameOver.firstOrNull()?.let { esito ->
        GameOver(
            esito = esito,
            onRicomincia = { inizio -> vm.chiudiStack(esito, inizio, oggi) },
            onInterrompi = { vm.chiudiStack(esito, null, oggi) },
        )
    }

    daInterrompere?.let { abitudine ->
        AlertDialog(
            onDismissRequest = { daInterrompere = null },
            title = { Text("Interrompere l'abitudine?") },
            text = {
                Text(
                    "«${abitudine.nome}» non viene eliminata e il punteggio non cambia: resta " +
                        "con tutte le sue spunte, ma esce da 🎯 Oggi.\n\n" +
                        "Da qui in poi non conta più giorni mancati, non consuma jolly e non può " +
                        "né vincere né fallire, finché non la riprendi.\n\n" +
                        "🔔 Il promemoria si cancella, e riprendendola andrà riscritto."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.interrompi(abitudine, oggi)
                    daInterrompere = null
                }) { Text("Interrompi", color = Palette.warning, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { daInterrompere = null }) {
                    Text("Annulla", color = Palette.muted)
                }
            },
        )
    }

    daRiprendere?.let { abitudine ->
        Riprendi(
            abitudine = abitudine,
            proposta = stato.ripartenzaSuggerita(abitudine.id, oggi),
            ultimaSpunta = stato.ultimaSpunta(abitudine.id),
            contaDaRecuperare = { da -> vm.giorniDaRecuperare(abitudine, da, oggi) },
            onRiprendi = { inizio ->
                vm.riprendi(abitudine, inizio, oggi)
                daRiprendere = null
            },
            onChiudi = { daRiprendere = null },
        )
    }

    categoriaInForm?.let { (cat, _) ->
        FormCategoria(
            categoria = cat,
            onAnnulla = { categoriaInForm = null },
            onSalva = { nome, icona, colore ->
                vm.salvaCategoria(cat?.id, nome, icona, colore) { categoriaInForm = null }
            },
        )
    }

    categoriaDaEliminare?.let { cat ->
        AlertDialog(
            onDismissRequest = { categoriaDaEliminare = null },
            title = { Text("Eliminare la categoria?") },
            text = { Text("«${cat.nome}» sparisce anche da Tasks, perché le categorie sono condivise.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.eliminaCategoria(cat)
                    categoriaDaEliminare = null
                }) { Text("Elimina", color = Palette.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { categoriaDaEliminare = null }) { Text("Annulla", color = Palette.muted) }
            },
        )
    }

    daEliminare?.let { abitudine ->
        AlertDialog(
            onDismissRequest = { daEliminare = null },
            title = { Text("Eliminare l'abitudine?") },
            text = {
                Text(
                    "«${abitudine.nome}» e le sue spunte spariscono. " +
                        "Gli stack già archiviati restano."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.elimina(abitudine.id) { daEliminare = null }
                }) { Text("Elimina", color = Palette.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { daEliminare = null }) {
                    Text("Annulla", color = Palette.muted)
                }
            },
        )
    }
}

@Composable
private fun SelettoreVista(scelta: Vista, onScegli: (Vista) -> Unit) {
    // ⚠️ Sei voci non stanno su 360 dp coi caratteri grandi: la riga scorre di
    // lato invece di andare a capo o schiacciarle.
    RigaScorrevole(
        Arrangement.spacedBy(8.dp),
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Vista.entries.forEach { v ->
            val attiva = v == scelta
            Text(
                text = v.etichetta,
                color = if (attiva) Palette.light else Palette.dark,
                fontWeight = if (attiva) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (attiva) NeroAbituati else Palette.inputBg)
                    .clickable { onScegli(v) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * La scheda di un'abitudine: nome, categoria, avanzamento verso l'obiettivo,
 * jolly rimasti e — nella vista di oggi — i pulsanti per segnare.
 *
 * Le abitudini a più orari hanno **una riga per orario**, come la timeline del
 * web: il jolly però lo conta il giorno, non la sessione, e quel conto lo fa la
 * RPC.
 */
@Composable
private fun SchedaAbitudine(
    abitudine: HbAbitudine,
    stato: AbituatiState,
    oggi: LocalDate,
    conSpunte: Boolean,
    onSegna: (LocalDate, String, String?) -> Unit,
    onPiu: () -> Unit,
    onMeno: () -> Unit,
    onModifica: () -> Unit,
    onInterrompi: () -> Unit,
    onRiprendi: () -> Unit,
    onElimina: () -> Unit,
) {
    val categoria = stato.categoria(abitudine.categoriaId)
    val streak = stato.streakDi(abitudine)
    val obiettivo = abitudine.obiettivo.coerceAtLeast(1)
    val jollyRimasti = (abitudine.jollyMassimi - abitudine.jollyUsati).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.cardBg),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = abitudine.nome,
                color = Palette.dark,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )

            categoria?.let { cat ->
                Text(
                    text = cat.etichetta,
                    color = Palette.light,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(coloreDaHex(cat.colore) ?: Palette.muted)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            if (abitudine.stato == "stopped") {
                Text(
                    text = "⏹ Interrotta",
                    color = Palette.dark,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Palette.warning.copy(alpha = 0.25f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            LinearProgressIndicator(
                progress = { (streak.toFloat() / obiettivo).coerceIn(0f, 1f) },
                color = NeroAbituati,
                trackColor = Palette.border,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "🔥 $streak di ${abitudine.obiettivo}" +
                    (if (abitudine.aFinestre) " finestre" else "") +
                    " · 🃏 $jollyRimasti jolly" +
                    stato.canali(abitudine.id).let { if (it.isNotEmpty()) " · 🔔$it" else "" },
                color = Palette.muted,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (conSpunte) {
                if (abitudine.aFinestre) {
                    Finestre(abitudine, stato, oggi, onPiu, onMeno)
                } else if (abitudine.aPiuOrari) {
                    // Un riquadro per orario, con l'ora al posto di
                    // «FINESTRA 1»: sono periodi diversi dello stesso giorno,
                    // e in un riquadro solo le due righe di pulsanti si
                    // leggerebbero come quattro pulsanti di una cosa sola.
                    abitudine.orari.sorted().forEach { orario ->
                        val statoOra = stato.statoDi(abitudine.id, oggi, orario)
                        RiquadroSpunte(bordoDiStato(statoOra)) {
                            Text(
                                text = "🕑 $orario",
                                color = Palette.muted,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spunte(statoOra) { nuovo -> onSegna(oggi, nuovo, orario) }
                        }
                    }
                } else {
                    val statoOggi = stato.statoDi(abitudine.id, oggi)
                    RiquadroSpunte(bordoDiStato(statoOggi)) {
                        Spunte(statoOggi) { nuovo -> onSegna(oggi, nuovo, null) }
                        // I pulsanti prima e il riepilogo dopo, come nella
                        // scheda della settimana nel web: si agisce su oggi,
                        // poi si guarda com'è messa la settimana.
                        if (abitudine.settimanale) SettimanaPallini(abitudine, stato, oggi)
                    }
                }
            } else {
                // 📜 In 📋 Tutte si sfogliano TUTTE le finestre e le settimane,
                // coi rossi delle chiuse; in 🎯 Oggi resta la sola corrente.
                if (abitudine.aFinestre) StoricoFinestre(abitudine, stato, oggi)
                else if (abitudine.settimanale) StoricoSettimane(abitudine, stato, oggi)
                // Una riga sola che scorre col dito, con le larghezze misurate
                // su **tutte** le etichette possibili — anche quelle che questa
                // scheda non mostra — o un pulsante condizionale farebbe
                // traballare la larghezza degli altri (`core/PulsantiTendine.kt`).
                val etichette = listOf("✏️ Modifica", "⏹ Interrompi", "▶️ Riprendi", "🗑 Elimina")
                val larghezza = larghezzaPulsanti(etichette)
                RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                    if (abitudine.stato == "stopped") {
                        // Su un'abitudine ferma la modifica non si offre, come
                        // nel web: quello che serve è rimetterla in moto.
                        Pillola("▶️ Riprendi", Palette.success, larghezza, onRiprendi)
                    } else {
                        Pillola("✏️ Modifica", NeroAbituati, larghezza, onModifica)
                        Pillola("⏹ Interrompi", Palette.warning, larghezza, onInterrompi)
                    }
                    Pillola("🗑 Elimina", Palette.danger, larghezza, onElimina)
                }
            }
        }
    }
}

/**
 * «N volte in M giorni»: **la scheda della FINESTRA IN CORSO, e solo quella.**
 *
 * Dentro ci sono `max(N, M)` pallini — uno per giorno, ma mai meno del
 * traguardo — bianchi col bordo. Il ＋ ne colora uno di verde, il − riporta a
 * bianco l'ultimo, e le regole sono quelle di `renderWindowCard()` nel web: il
 * ＋ si spegne al tetto giornaliero (`max_per_day`) e a finestra piena, e la
 * scheda scrive perché.
 *
 * ⚠️ **Fino alla v1.0.85 le finestre erano TUTTE**: una riga di schede che
 * scorreva di lato e si apriva su quella in corso. Ma quella in corso è
 * l'unica su cui si possa fare qualcosa, e con un obiettivo di trenta
 * finestre le altre ventinove erano trenta schede da trascinare per tornare
 * dov'eri. È la stessa scelta dei sette pallini della settimanale, che qui
 * mostrano la **sola settimana in corso** mentre il web le sfoglia tutte:
 * 🎯 Oggi risponde a «cosa devo fare adesso», non a «com'è andata finora».
 *
 * ⚠️ **Il prezzo è dichiarato**: pallini rossi, bordo rosso e la nota «N non
 * fatte» vivono sulle finestre **chiuse**, quindi da qui non si vedono più —
 * quante se ne siano perse lo dice il `🔥 N di M finestre` sopra, e il
 * dettaglio resta nel web. Per questo [Finestra] non disegna più nessun rosso:
 * la finestra in corso non è mai una finestra persa.
 *
 * ⚠️ **Oggi può cadere fuori da ogni finestra** — prima che l'abitudine
 * cominci, o dopo l'ultima della stecca — e allora si scrive perché invece di
 * non disegnare niente: un riquadro che sparisce si legge come un difetto.
 */
@Composable
private fun Finestre(
    abitudine: HbAbitudine,
    stato: AbituatiState,
    oggi: LocalDate,
    onPiu: () -> Unit,
    onMeno: () -> Unit,
) {
    val quante = abitudine.obiettivo.coerceAtLeast(1)
    val k = abitudine.finestraDi(oggi)
    if (k !in 0 until quante) {
        val inizio = abitudine.giornoInizio
        Text(
            text = when {
                k < 0 && inizio != null -> "Comincia il ${giornoMese(inizio)}"
                k < 0 -> "Non è ancora cominciata"
                else -> "Le $quante finestre sono finite"
            },
            color = Palette.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Finestra(abitudine, stato, oggi, k, onPiu, onMeno)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Finestra(
    abitudine: HbAbitudine,
    stato: AbituatiState,
    oggi: LocalDate,
    k: Int,
    onPiu: () -> Unit,
    onMeno: () -> Unit,
) {
    val inizio = abitudine.inizioFinestra(k) ?: return
    val fine = inizio.plusDays((abitudine.m - 1).toLong())

    val fatte = stato.fatteNellaFinestra(abitudine, inizio)
    val fatteOggi = stato.fatteOggi(abitudine, oggi)

    val piena = fatte >= abitudine.n
    val puoPiu = fatte < abitudine.caselle && fatteOggi < abitudine.p
    val puoMeno = fatte > 0

    RiquadroSpunte(if (piena) Palette.success else NeroAbituati) {
        Text(
            text = "FINESTRA ${k + 1}",
            color = Palette.muted,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "${giornoMese(inizio)} → ${giornoMese(fine)} · $fatte/${abitudine.n}",
            color = Palette.dark,
            style = MaterialTheme.typography.bodyMedium,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(abitudine.caselle) { i ->
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (i < fatte) Palette.success else Palette.light)
                        .border(1.dp, Palette.border, CircleShape)
                )
            }
        }

        RigaSimboli {
            PulsanteSimbolo(
                simbolo = "−",
                descrizione = "Togli una spunta",
                sfondo = if (puoMeno) Palette.warning else Palette.border,
                testo = if (puoMeno) Palette.light else Palette.muted,
                onClick = if (puoMeno) onMeno else null,
            )
            PulsanteSimbolo(
                simbolo = "＋",
                descrizione = "Segna una volta",
                sfondo = if (puoPiu) Palette.success else Palette.border,
                testo = if (puoPiu) Palette.light else Palette.muted,
                onClick = if (puoPiu) onPiu else null,
            )
        }

        val nota = when {
            fatteOggi >= abitudine.p ->
                "Oggi hai già segnato ${abitudine.p}" +
                    (if (abitudine.p == 1) " volta" else " volte")
            fatte >= abitudine.caselle -> "Finestra piena"
            else -> null
        }
        nota?.let {
            Text(text = it, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Il riquadro in cui si spunta, **uguale per tutte le frequenze**: bordo,
 * angoli e spaziature sono quelli nati con la scheda di una 🪟 finestra, e da
 * lì valgono anche per una giornaliera, una a più orari e una settimanale.
 *
 * ⚠️ **Il bordo non è decorazione, dice com'è andata oggi** — verde fatto,
 * rosso mancato, nero da fare — ed è lo stesso segno del «piena → verde» di
 * una finestra. Senza il contorno le due schede si leggevano come due app
 * diverse: là un riquadro, qui due pulsanti appoggiati sul fondo bianco.
 */
@Composable
private fun RiquadroSpunte(bordo: Color, contenuto: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, bordo, RoundedCornerShape(10.dp))
            .background(Palette.inputBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = contenuto,
    )
}

/** Il colore del bordo di un riquadro, dallo stato del periodo che contiene. */
private fun bordoDiStato(statoOggi: String?): Color = when (statoOggi) {
    "completed" -> Palette.success
    "failed", "missed" -> Palette.danger
    else -> NeroAbituati
}

/**
 * La riga dei due simboli, centrata.
 *
 * ⚠️ Un `Row` normale e non una `RigaScorrevole`: là il contenuto si misura a
 * larghezza illimitata, quindi la riga risulta larga quanto quel che porta e
 * `Arrangement.Center` non ha spazio da distribuire — non centrerebbe niente.
 * È lo stesso motivo per cui lì dentro non si può usare `weight(1f)`. Due
 * simboli ci stanno su qualunque schermo, quindi non c'è niente da nascondere
 * e lo scorrimento non serve.
 */
@Composable
private fun RigaSimboli(contenuto: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = contenuto,
    )
}

/**
 * Il bersaglio minimo di un pulsante fatto di un **simbolo solo**.
 *
 * ⚠️ È in `dp` e non misurato sul testo, ed è l'eccezione alla regola di
 * `larghezzaPulsanti`: un glifo darebbe una pillola da una quarantina di dp,
 * cioè sotto il minimo toccabile. Il dito è grande uguale a qualunque
 * ingrandimento dei caratteri, quindi questo è una misura **fisica** — è la
 * stessa ragione per cui il pavimento delle bolle in home è in `dp`. Sopra il
 * minimo comanda comunque il testo misurato, così a caratteri molto grandi il
 * simbolo non viene tagliato.
 */
private val TOCCABILE: Dp = 64.dp

/**
 * **L'unico pulsante a simbolo di Abituati**: ✓, ✕, − e ＋ hanno la stessa
 * forma, la stessa taglia e lo stesso carattere, e a cambiare sono i soli
 * colori — che dicono *cosa* fa quel tocco, non *dove* ci si trova.
 *
 * ⚠️ **I simboli sono GLIFI e non emoji** (`✓ ✕ − ＋`): un'emoji si disegna
 * coi suoi colori, quindi ✅ e ❌ accanto a un − e a un ＋ monocromi si
 * leggevano come due famiglie di pulsanti diverse — ed è precisamente la
 * disomogeneità che questo composable esiste per togliere. Sono gli stessi
 * ✓ e ✕ che il web usa nella riga d'azione.
 *
 * ⚠️ **Senza etichetta serve il `contentDescription`**: un simbolo da solo,
 * letto da TalkBack, non dice cosa fa il pulsante.
 *
 * ⚠️ **Spento resta a schermo, sbiadito** (`onClick` null): farlo sparire
 * cambierebbe l'altezza della scheda a ogni spunta, e un pulsante che scompare
 * si legge come un difetto — è la stessa scelta del pulsante «a zero» di
 * Finanza.
 *
 * ⚠️ **Il carattere è quello con cui la larghezza è stata MISURATA**
 * (`bodyMedium`, come in `larghezzaPulsanti`): rendendolo più grande della
 * misura, a caratteri di sistema molto grandi il glifo uscirebbe dal pulsante.
 */
@Composable
private fun PulsanteSimbolo(
    simbolo: String,
    descrizione: String,
    sfondo: Color,
    testo: Color,
    onClick: (() -> Unit)?,
) {
    val larghezza = larghezzaPulsanti(listOf(simbolo)).coerceAtLeast(TOCCABILE)
    Text(
        text = simbolo,
        color = testo,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .width(larghezza)
            .clip(RoundedCornerShape(10.dp))
            .background(sfondo)
            // ⚠️ Il bordo serve al pulsante SPENTO: dentro un riquadro già
            // chiaro un fondo bianco quasi non si distingue, e un pulsante
            // che non si vede è un pulsante che non si preme. Sotto un fondo
            // pieno scompare da sé.
            .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp)
            .semantics { contentDescription = descrizione },
    )
}

/** «16/9», per l'intestazione di una finestra. */
internal fun giornoMese(g: LocalDate): String = "${g.dayOfMonth}/${g.monthValue}"

/**
 * I due stati di un periodo: ✓ fatto e ✕ fallito, **gli stessi pulsanti a
 * simbolo di una finestra** ([PulsanteSimbolo]). Toccare quello già segnato lo
 * toglie — è il `newState === 'none'` del web, e serve per correggere un tocco
 * sbagliato (togliendo un `failed` la RPC restituisce anche il jolly).
 *
 * ⚠️ **Il colore dice se quel pulsante è SCELTO**, non se si può premere:
 * acceso col suo colore quando è lo stato di adesso, spento sul fondo neutro
 * quando non lo è — e restano premibili tutt'e due. È la differenza con
 * − e ＋ di una finestra, che sono azioni e non stati: lì il grigio vuol dire
 * «non si può».
 *
 * ⚠️ **I pulsanti sono DUE e non tre, come nel web**: là la riga d'azione ha
 * il solo ✓ e ✕, e quel ✕ si chiama «Saltato (Jolly)» — cioè saltare un
 * giorno *è* `failed`, ed è il jolly a pagarlo.
 *
 * ⚠️ Fino alla v1.0.84 ce n'era un terzo, `⏭ Saltato`, che scriveva
 * `status = 'skipped'`: il CHECK `hb_completions_status_check` non ammette
 * quel valore — la tabella è nata a mano e conosce `completed`, `failed` e
 * `missed` — quindi il pulsante **falliva sempre**, su ogni frequenza, con
 * l'errore del database a schermo. E non era solo un valore da aggiungere:
 * nelle RPC `skipped` non conta niente — non è un jolly (`hb_fallimenti`
 * somma `failed` e `missed`) e non è un giorno fatto — ma rende il giorno
 * «risolto», quindi `hb_reconcile` non ci scrive sopra `missed`: un giorno
 * che passa liscio senza costare un jolly, cioè il buco che il jolly esiste
 * per chiudere.
 */
@Composable
private fun Spunte(statoOggi: String?, onSegna: (String) -> Unit) {
    RigaSimboli {
        val fatto = statoOggi == "completed"
        val fallito = statoOggi == "failed"
        PulsanteSimbolo(
            simbolo = "✓",
            descrizione = "Fatto",
            sfondo = if (fatto) Palette.success else Palette.light,
            testo = if (fatto) Palette.light else Palette.dark,
            onClick = { onSegna(if (fatto) "none" else "completed") },
        )
        PulsanteSimbolo(
            simbolo = "✕",
            descrizione = "Fallito",
            sfondo = if (fallito) Palette.danger else Palette.light,
            testo = if (fallito) Palette.light else Palette.dark,
            onClick = { onSegna(if (fallito) "none" else "failed") },
        )
    }
    if (statoOggi == "missed") {
        Text(
            text = "Segnato come mancato dal controllo dei giorni passati.",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Il blu del jolly: lo stesso `#2196F3` dei pallini della settimana nel web. */
internal val BluJolly = Color(0xFF2196F3)

/** Il grigio di un giorno che l'abitudine non prevede (`#d1d5db` nel web). */
internal val GrigioNonPrevisto = Color(0xFFD1D5DB)

/**
 * I sette pallini della settimana in corso — lunedì → domenica — sotto i
 * pulsanti di una **settimanale**. È il gemello di `dotsHtml` in
 * `renderWeekCard()` del web, con gli stessi colori, e **va cambiato insieme
 * a lui**.
 *
 * ⚠️ **Qui c'è la SOLA settimana di oggi**, mentre il web le sfoglia tutte:
 * 🎯 Oggi risponde a «cosa devo fare adesso», e una striscia di settimane
 * dentro la scheda di una lista che scorre in verticale sarebbe uno
 * scorrimento dentro l'altro. Le settimane passate si guardano di là.
 *
 * ⚠️ **`off` non è «non fatto»**: è un giorno che quell'abitudine non prevede,
 * o precedente al suo inizio, ed è grigio **pieno**; il bianco col bordo è il
 * giorno dovuto e ancora da fare. Senza quella distinzione una settimanale di
 * tre giorni si leggerebbe come quattro giorni saltati.
 *
 * ⚠️ **I rossi sono immediati**, al contrario di `count_window`: là la
 * finestra si può ancora riempire in un altro giorno, qui il giorno dovuto è
 * quello e basta. Il rosso però lo dice la riga `missed` scritta da
 * `hb_reconcile` e non un conto di qua — un giorno passato che la
 * riconciliazione non ha ancora toccato resta bianco, ed è quel che è.
 *
 * ⚠️ **Gli stati sono gli stessi del web perché lo sono anche i pulsanti**:
 * dalla v1.0.85 la riga sotto ha il solo ✅ Fatto e ❌ Fallito, e `skipped`
 * non si può più comporre — il database non l'ha mai ammesso (vedi `Spunte`).
 *
 * ⚠️ I pallini sono **22.dp come quelli delle finestre** e la riga **scorre**
 * invece di andare a capo: coi caratteri di sistema grandi le sette lettere
 * sotto i pallini sono più larghe dei pallini stessi, e andando a capo la
 * settimana si spezzerebbe in due righe.
 */
@Composable
private fun SettimanaPallini(abitudine: HbAbitudine, stato: AbituatiState, oggi: LocalDate) {
    // `dayOfWeek.value` va da 1 (lunedì) a 7 (domenica): il lunedì di questa
    // settimana è oggi meno i giorni già passati.
    val lunedi = oggi.minusDays((oggi.dayOfWeek.value - 1).toLong())
    val inizio = abitudine.giornoInizio
    val iniziali = listOf("L", "M", "M", "G", "V", "S", "D")

    RigaScorrevole(Arrangement.spacedBy(8.dp)) {
        repeat(7) { i ->
            val giorno = lunedi.plusDays(i.toLong())
            // I giorni salvati sono numerati alla JavaScript (0 = domenica),
            // come in `cadeIl()`.
            val previsto = (giorno.dayOfWeek.value % 7) in abitudine.giorniSettimana
            val primaDellInizio = inizio != null && giorno.isBefore(inizio)

            val pallino = if (!previsto || primaDellInizio) "off"
            else (stato.statoDi(abitudine.id, giorno) ?: "todo")

            val riempimento = when (pallino) {
                "off" -> GrigioNonPrevisto
                "completed" -> Palette.success
                "failed" -> BluJolly
                "missed" -> Palette.danger
                else -> Palette.light // «todo», e qualunque stato che non conosciamo
            }
            val bordo = if (riempimento == Palette.light) Palette.muted else riempimento

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(riempimento)
                        .border(2.dp, bordo, CircleShape)
                )
                Text(
                    text = iniziali[i],
                    color = if (giorno == oggi) Palette.dark else Palette.muted,
                    fontWeight = if (giorno == oggi) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Archivio(righe: List<HbArchiviato>) {
    if (righe.isEmpty()) {
        Vuoto("📦", "Nessuno stack in archivio.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(righe, key = { it.id }) { riga ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Palette.cardBg),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = riga.nome, color = Palette.dark, fontWeight = FontWeight.Bold)
                    Text(
                        text = riga.esito,
                        color = if (riga.punti >= 0) Palette.success else Palette.danger,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${dataItaliana(riga.inizio)} → ${dataItaliana(riga.fine)} · " +
                            "🔥 ${riga.streak} · ✅ ${riga.completamenti} · ❌ ${riga.fallimenti}",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = if (riga.punti >= 0) "+${riga.punti} punti" else "${riga.punti} punti",
                        color = if (riga.punti >= 0) Palette.success else Palette.danger,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Lo stack vinto: il modale del web, con la stessa scelta — riparti o basta. */
@Composable
private fun Festa(esito: HbEsito, onRicomincia: (LocalDate) -> Unit, onChiudi: () -> Unit) {
    val context = LocalContext.current
    // Il nuovo ciclo parte da **domani**, come nel web: la stecca appena
    // chiusa si è presa oggi, e un ciclo che ripartisse dallo stesso giorno
    // nascerebbe con la prima giornata già spesa. Resta una proposta.
    var inizio by remember(esito.abitudineId) { mutableStateOf(LocalDate.now().plusDays(1)) }

    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("🏆 Stack completato!") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("«${esito.nome}» — 🔥 ${esito.streak} di fila, +${esito.punti} punti.")
                Text(
                    text = "Nuovo ciclo dal ${dataItaliana(inizio)}",
                    color = Palette.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.inputBg)
                        .clickable { scegliData(context, inizio) { inizio = it } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRicomincia(inizio) }) {
                Text("Ricomincia", color = Palette.success, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onChiudi) { Text("Per ora basta", color = Palette.muted) }
        },
    )
}

/** Lo stack perso: jolly finiti o scadenza. */
@Composable
private fun GameOver(
    esito: HbEsito,
    onRicomincia: (LocalDate) -> Unit,
    onInterrompi: () -> Unit,
) {
    val context = LocalContext.current
    // Da domani, per la stessa ragione della `Festa` qui sopra.
    var inizio by remember(esito.abitudineId) { mutableStateOf(LocalDate.now().plusDays(1)) }

    AlertDialog(
        onDismissRequest = onInterrompi,
        title = {
            Text(
                if (esito.motivo == "scadenza_calendario") "⏰ Stack scaduto"
                else "💀 Jolly esauriti"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("«${esito.nome}» — 🔥 ${esito.streak}, ${esito.mancati} mancati.")
                Text(
                    text = "Nuovo ciclo dal ${dataItaliana(inizio)}",
                    color = Palette.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.inputBg)
                        .clickable { scegliData(context, inizio) { inizio = it } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRicomincia(inizio) }) {
                Text("Ricomincia", color = Palette.dark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onInterrompi) { Text("Interrompi", color = Palette.danger) }
        },
    )
}

/**
 * RIPRENDI: rimette in moto un'abitudine interrotta, **da una data scelta**.
 *
 * Si apre sul **giorno dopo l'ultima spunta** (`ripartenzaSuggerita`), che è
 * dove l'abitudine si era fermata davvero; senza spunte, e mai oltre, oggi.
 *
 * ⚠️ La data è il punto della finestra. Riprendendo con `started_at` fermo a
 * quando l'abitudine era partita, il primo giro di `hb_reconcile` marcherebbe
 * `missed` ogni giorno passato dall'interruzione: i jolly finirebbero sul posto
 * e il game over scatterebbe prima ancora di rivedere la scheda. Quanto costi
 * la data scelta lo dice `hb_giorni_da_recuperare` — la stessa RPC che usa
 * `habit-tracker.html`, così l'avviso è lo stesso da tutt'e due le parti — e si
 * rilegge a ogni cambio di data.
 *
 * Quando i jolly non bastano il pulsante **non si blocca**: cambia scritta. È
 * una scelta legittima — si può voler ricominciare da lontano sapendo di
 * perdere — ma non deve succedere per distrazione.
 */
@Composable
private fun Riprendi(
    abitudine: HbAbitudine,
    proposta: LocalDate,
    ultimaSpunta: LocalDate?,
    contaDaRecuperare: suspend (LocalDate) -> Int?,
    onRiprendi: (LocalDate) -> Unit,
    onChiudi: () -> Unit,
) {
    val context = LocalContext.current
    val jolly = abitudine.jollyMassimi.coerceAtLeast(1)
    var inizio by remember(abitudine.id) { mutableStateOf(proposta) }
    var mancati by remember(abitudine.id) { mutableStateOf<Int?>(null) }
    var inCorso by remember(abitudine.id) { mutableStateOf(true) }

    // Il conto si rifà a ogni data scelta; `LaunchedEffect` annulla da sé
    // quello di prima, quindi vince l'ultima scelta e non l'ultima risposta.
    LaunchedEffect(abitudine.id, inizio) {
        inCorso = true
        mancati = contaDaRecuperare(inizio)
        inCorso = false
    }

    val quanti = mancati
    val avviso = when {
        inCorso -> "⏳ Conto i giorni da recuperare…"
        quanti == null -> "⚠️ Non riesco a contare i giorni da recuperare: controlla la connessione."
        quanti == 0 -> "✅ Nessun giorno da recuperare: riparte pulita."
        quanti >= jolly ->
            "💀 Da questa data a oggi ci sono $quanti giorni senza spunta e i jolly sono $jolly: " +
                "riaperta così finisce subito in game over."
        else -> "⚠️ Da questa data a oggi ci sono $quanti giorni senza spunta: " +
            "consumerebbero $quanti jolly su $jolly."
    }
    val perso = quanti != null && quanti >= jolly

    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("▶️ Riprendere l'abitudine?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("«${abitudine.nome}»")
                Text(
                    text = "Riparti dal ${dataItaliana(inizio)}",
                    color = Palette.dark,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.inputBg)
                        .clickable { scegliData(context, inizio) { inizio = it } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                // Le due date che spiegano la proposta: da dove era partita e
                // fin dove era arrivata.
                Text(
                    text = listOfNotNull(
                        abitudine.giornoInizio?.let { "Era partita il ${dataItaliana(it)}" },
                        ultimaSpunta?.let { "ultima spunta il ${dataItaliana(it)}" }
                            ?: "nessuna spunta finora",
                    ).joinToString(" · "),
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = avviso,
                    color = if (perso) Palette.danger else Palette.dark,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "I giorni prima di questa data non contano più: le spunte già fatte " +
                        "restano in archivio, ma streak, giorni fatti e jolly ripartono da qui.",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "🔔 Il promemoria era stato cancellato all'interruzione: se lo rivuoi, " +
                        "riscrivilo da ✏️ Modifica.",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRiprendi(inizio) }) {
                Text(
                    text = if (perso) "Riprendi lo stesso" else "Riprendi",
                    color = if (perso) Palette.danger else Palette.success,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onChiudi) { Text("Annulla", color = Palette.muted) }
        },
    )
}

@Composable
private fun Vuoto(icona: String, testo: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = icona, fontSize = 40.sp)
        Text(text = testo, color = Palette.muted, modifier = Modifier.padding(top = 12.dp))
    }
}
