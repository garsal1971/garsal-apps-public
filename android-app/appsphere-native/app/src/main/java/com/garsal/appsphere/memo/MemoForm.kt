package com.garsal.appsphere.memo

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.TestoCondiviso
import com.garsal.appsphere.core.coloreDaHex
import com.garsal.appsphere.core.larghezzaPulsanti
import kotlinx.coroutines.launch

/** Una voce di lista mentre la si scrive: senza `id` è nuova. */
data class VoceInModifica(
    val id: String? = null,
    val testo: String = "",
    val fatta: Boolean = false,
    val fattaIl: String? = null,
)

/** Una misura di diario mentre la si scrive: senza `id` è nuova. */
data class MisuraInModifica(
    val id: String? = null,
    val nome: String = "",
    val tipo: TipoMisura = TipoMisura.SCALA,
    val minimo: Double? = 1.0,
    val massimo: Double? = 10.0,
    val unita: String = "",
    val opzioni: List<MmOpzione> = emptyList(),
    val nota: String = "",
) {
    companion object {
        fun da(m: MmMisura) = MisuraInModifica(
            id = m.id,
            nome = m.nome,
            tipo = m.tipo,
            minimo = m.minimo,
            massimo = m.massimo,
            unita = m.unita,
            opzioni = m.opzioni,
            nota = m.nota,
        )
    }
}

/**
 * L'id di un'opzione di combo: stabile, perché la registrazione archivia
 * **quello** e non l'etichetta. Rinominare un'opzione deve rileggere anche lo
 * storico col nome nuovo, mentre archiviando la parola una rinomina
 * spaccherebbe la stessa origine in due categorie che ai conteggi sembrano
 * diverse. È `optionId()` del web.
 */
internal fun nuovoIdOpzione(): String =
    "o" + System.currentTimeMillis().toString(36) +
        (100_000..999_999).random().toString(36)

/**
 * La scheda che si sta scrivendo. `testo` è nei **marcatori** di [MemoHtml],
 * non in HTML: la conversione avviene aprendo il form e salvando.
 */
data class BozzaScheda(
    val titolo: String = "",
    val testo: String = "",
    val tipo: TipoScheda = TipoScheda.NOTA,
    val riservato: Boolean = false,
    val fissata: Boolean = false,
    val colore: String = MmScheda.BIANCO,
    val categorie: List<String> = emptyList(),
    val voci: List<VoceInModifica> = emptyList(),
    val vociTolte: List<String> = emptyList(),
    val misure: List<MisuraInModifica> = emptyList(),
    val misureTolte: List<String> = emptyList(),
    /** L'indirizzo di un 🔗 Link. Ignorato dagli altri tipi. */
    val linkUrl: String = "",
    /**
     * I punti di un 🏅 Premiato, **come sono scritti nella casella**: testo e
     * non `Int`, o svuotarla sarebbe impossibile e ogni carattere a metà
     * digitazione («-», «1e») diventerebbe uno zero. Si legge con [punti].
     */
    val punteggio: String = "0",
) {
    /**
     * Come `saveCard()`: basta il titolo **oppure** il contenuto — e per una
     * lista o un diario bastano anche le sole voci o le sole misure, perché lì
     * il contenuto è facoltativo.
     */
    val valida: Boolean
        get() = if (tipo == TipoScheda.LINK) linkValido
        else titolo.isNotBlank() || testo.isNotBlank() ||
            (tipo == TipoScheda.LISTA && voci.isNotEmpty()) ||
            (tipo == TipoScheda.DIARIO && misure.isNotEmpty()) ||
            (tipo == TipoScheda.PREMIATO && (punti ?: 0) != 0)

    /**
     * I punti scritti nella casella, o `null` se quel che c'è dentro non è un
     * numero intero. La casella **vuota vale 0**, che è il `DEFAULT` della
     * colonna; una parola scritta lì dentro invece **non diventa zero in
     * silenzio** — un punteggio sbagliato finisce nel totale dei premi.
     */
    val punti: Int?
        get() = punteggio.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() }

    /**
     * Quel che va davvero in archivio: i punti di un premiato, **0 su ogni
     * altro tipo**. Il salvataggio riscrive la riga intera, e una scheda che
     * cambiasse tipo si porterebbe dietro punti che nessuno vede più.
     */
    val puntiDaSalvare: Int
        get() = if (tipo == TipoScheda.PREMIATO) punti ?: 0 else 0

    /**
     * ⚠️ Un link è il suo indirizzo: senza, la scheda non porta da nessuna
     * parte — è rotta, non vuota. È l'unico tipo con un campo obbligatorio,
     * come sul web.
     *
     * Il punto nel nome del sito è il controllo che conta: senza, «ciao»
     * passerebbe per un indirizzo validissimo con host «ciao».
     */
    val linkValido: Boolean
        get() = linkUrl.isNotBlank() && Link.valido(linkUrl)

    /**
     * Che cosa impedisce di salvare un diario, o `null` se va bene: gli stessi
     * tre controlli di `saveCard()`.
     */
    val problema: String?
        get() = when {
            tipo == TipoScheda.LINK && linkUrl.isBlank() ->
                "Scrivi l'indirizzo del link."
            tipo == TipoScheda.LINK && !linkValido ->
                "Quell'indirizzo non è valido."
            tipo == TipoScheda.PREMIATO && punti == null ->
                "Il punteggio dev'essere un numero intero."
            tipo != TipoScheda.DIARIO -> null
            misure.any { it.nome.isBlank() } -> "Dai un nome a ogni misura."
            misure.any {
                it.tipo == TipoMisura.SCALA &&
                    (it.minimo == null || it.massimo == null || it.massimo <= it.minimo)
            } -> "Ogni scala vuole un massimo maggiore del minimo."
            misure.any {
                it.tipo == TipoMisura.SCELTA && it.opzioni.none { o -> o.etichetta.isNotBlank() }
            } -> "Una misura a scelta vuole almeno un'opzione."
            else -> null
        }

    /** Le opzioni lasciate in bianco si scartano da sé al salvataggio. */
    fun ripulita(): BozzaScheda = copy(
        misure = misure.map { m ->
            if (m.tipo == TipoMisura.SCELTA)
                m.copy(opzioni = m.opzioni.filter { it.etichetta.isNotBlank() })
            else m
        }
    )

    companion object {
        fun da(
            scheda: MmScheda,
            voci: List<MmVoce> = emptyList(),
            misure: List<MmMisura> = emptyList(),
        ) = BozzaScheda(
            titolo = scheda.titolo,
            testo = MemoHtml.aMarcatori(scheda.contenuto),
            tipo = scheda.tipo,
            riservato = scheda.riservato,
            fissata = scheda.fissata,
            colore = scheda.colore,
            categorie = scheda.categorie,
            voci = voci.map { VoceInModifica(it.id, it.testo, it.fatta, it.fattaIl) },
            misure = misure.map { MisuraInModifica.da(it) },
            linkUrl = scheda.linkUrl,
            punteggio = scheda.punteggio.toString(),
        )

        /**
         * La scheda che nasce dal tasto «Condividi» di un'altra app.
         *
         * ⚠️ **La scheda non si salva da sola**: qui si prepara e basta, poi si
         * apre l'editor già compilato e il salvataggio resta un gesto. Il tasto
         * «Condividi» sta accanto a mille altri, e una condivisione per sbaglio
         * non deve lasciare una riga che poi qualcuno deve andare a cercare per
         * cancellarla. Stessa scelta di `openMemoFromSharedLink()` nel web,
         * che questa funzione sostituisce.
         *
         * ⚠️ **Un testo senza url non si butta via**: diventa una nota, e il
         * form lo dice invece di lasciarlo scoprire.
         *
         * È `suspend` per un caso solo: un link di YouTube condiviso da un'app
         * che non manda `EXTRA_SUBJECT`, dove il titolo lo sa solo l'oEmbed.
         * Vedi [Link.titolo].
         */
        suspend fun daCondivisione(condiviso: TestoCondiviso): BozzaScheda {
            val testo = condiviso.testo.trim()
            val url = Link.normalizza(Link.primoUrl(testo).ifBlank { testo })
            if (!Link.valido(url)) return BozzaScheda(
                tipo = TipoScheda.NOTA,
                titolo = condiviso.oggetto.trim().take(200),
                testo = testo,
            )
            return BozzaScheda(
                tipo = TipoScheda.LINK,
                linkUrl = url,
                titolo = Link.titolo(url, condiviso.oggetto),
            )
        }
    }
}

/**
 * Creazione e modifica di una scheda: titolo, testo con la barra di
 * formattazione, colore, categorie, evidenza e foto (con OCR).
 *
 * ⚠️ Il testo si scrive **con i marcatori** (`**grassetto**`, `# titolo`,
 * `- elenco`…), che i pulsanti della barra infilano attorno a quello che si è
 * selezionato; l'occhio 👁 mostra come verrà. È la scelta spiegata in
 * [MemoHtml]: così quello che il web ha scritto in HTML torna in HTML uguale
 * anche passando da qui.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoForm(
    bozzaIniziale: BozzaScheda,
    id: String?,
    immaginiEsistenti: List<MmImmagine>,
    categorie: List<CmCategoria>,
    onAnnulla: () -> Unit,
    onSalva: (BozzaScheda, List<FotoInAttesa>, List<MmImmagine>) -> Unit,
    /**
     * Una riga da mostrare appena il form si apre — oggi la usa la
     * condivisione, quando un testo senza indirizzo diventa una nota invece
     * di un link: buttarlo via senza dire niente sarebbe la cosa peggiore.
     */
    avvisoIniziale: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var b by remember { mutableStateOf(bozzaIniziale) }
    // Il testo passa da TextFieldValue e non da String perché i pulsanti della
    // barra devono sapere **cosa è selezionato**: senza la selezione
    // «grassetto» non saprebbe attorno a cosa mettersi.
    var testo by remember { mutableStateOf(TextFieldValue(bozzaIniziale.testo)) }
    var anteprima by remember { mutableStateOf(false) }

    var nuove by remember { mutableStateOf(listOf<FotoInAttesa>()) }
    var daTogliere by remember { mutableStateOf(listOf<MmImmagine>()) }
    var conOcr by remember { mutableStateOf(false) }
    var avviso by remember { mutableStateOf(avvisoIniziale) }
    var uriScatto by remember { mutableStateOf<Uri?>(null) }

    fun accodaTesto(estratto: String) {
        val pulito = estratto.trim()
        if (pulito.isEmpty()) {
            avviso = "⚠️ Nessun testo trovato nella foto"
            return
        }
        // Stessa regola del web: se c'era già del testo, il pezzo nuovo arriva
        // dopo una linea di separazione invece di attaccarsi a quello di prima.
        val prima = testo.text.trimEnd()
        val unito = if (prima.isBlank()) pulito else "$prima\n\n---\n\n$pulito"
        testo = TextFieldValue(unito, TextRange(unito.length))
        avviso = "✅ Testo estratto (${pulito.length} caratteri)"
    }

    fun aggiungiFoto(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val descritte = uris.mapNotNull { MemoFoto.descrivi(context, it) }
        if (descritte.size < uris.size) {
            avviso = "⚠️ Qualche foto supera i 5 MB e non è stata aggiunta"
        }
        nuove = nuove + descritte
        if (conOcr && descritte.isNotEmpty()) {
            avviso = "🔍 OCR in corso…"
            scope.launch {
                descritte.forEach { foto ->
                    runCatching { MemoFoto.testoDaFoto(context, foto.uri) }
                        .onSuccess { accodaTesto(it) }
                        .onFailure { avviso = "❌ OCR non riuscito: ${it.message ?: "errore"}" }
                }
            }
        }
    }

    val galleria = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris -> aggiungiFoto(uris) }

    val fotocamera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { scattata -> if (scattata) uriScatto?.let { aggiungiFoto(listOf(it)) } }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = if (id == null) "📝 ${b.tipo.nuova}"
                else "✏️ Modifica ${b.tipo.etichetta.lowercase()}",
                onIndietro = onAnnulla,
                azioni = {
                    val compilata = b.copy(testo = testo.text)
                    val pronta = compilata.valida
                    Text(
                        text = "Salva",
                        color = if (pronta) Palette.light else Palette.light.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = pronta) {
                                val pulita = compilata.ripulita()
                                val problema = pulita.problema
                                if (problema != null) {
                                    // Le opzioni scartate vanno mostrate: chi ha
                                    // lasciato una combo in bianco deve vedere
                                    // perché il salvataggio non parte.
                                    b = pulita
                                    avviso = problema
                                } else {
                                    onSalva(pulita, nuove, daTogliere)
                                }
                            }
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
            avviso?.let { messaggio ->
                Text(
                    text = messaggio,
                    color = Palette.dark,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.inputBg)
                        .clickable { avviso = null }
                        .padding(10.dp),
                )
            }

            OutlinedTextField(
                value = b.titolo,
                onValueChange = { b = b.copy(titolo = it) },
                label = { Text("Titolo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Indirizzo del link ──────────────────────────────────────
            // Sta subito sotto il titolo, prima del testo: è la cosa che
            // rende link questa scheda, e senza non porta da nessuna parte.
            if (b.tipo == TipoScheda.LINK) {
                OutlinedTextField(
                    value = b.linkUrl,
                    onValueChange = { b = b.copy(linkUrl = it) },
                    label = { Text("Indirizzo") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    isError = b.linkUrl.isNotBlank() && !b.linkValido,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                // L'anteprima si vede PRIMA di salvare: un url incollato storto
                // (o il video sbagliato) si riconosce dalla copertina, non
                // rileggendo carattere per carattere una riga lunga.
                if (b.linkValido) {
                    Copertina(b.linkUrl.trim(), grande = true)
                    Text(
                        text = Link.sito(
                            if (b.linkUrl.trim().startsWith("http")) b.linkUrl.trim()
                            else "https://" + b.linkUrl.trim()
                        ),
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (b.linkUrl.isNotBlank()) {
                    Text(
                        text = "Non sembra un indirizzo valido.",
                        color = Palette.danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ── Punteggio del premiato ──────────────────────────────────
            // Come l'indirizzo di un link sta subito sotto il titolo: è la
            // cosa che distingue un premiato da una nota, e in fondo al form
            // — dopo il testo, le foto e le categorie — si troverebbe solo
            // scorrendo.
            if (b.tipo == TipoScheda.PREMIATO) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = b.punteggio,
                        onValueChange = { b = b.copy(punteggio = it) },
                        label = { Text("Punteggio") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        isError = b.punti == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    // ⚠️ Il ± non è un vezzo: `KeyboardType.Number` apre il
                    // tastierino numerico, che su parecchi telefoni **il segno
                    // meno non ce l'ha** — e un punteggio negativo è una cosa
                    // che esiste (un malus), non un errore. Senza questo tasto
                    // sarebbe un valore impossibile da scrivere.
                    TextButton(
                        onClick = {
                            val n = b.punti
                            if (n != null) b = b.copy(punteggio = (-n).toString())
                        },
                        enabled = b.punti != null,
                    ) { Text("±", style = MaterialTheme.typography.titleLarge) }
                }
                Text(
                    text = if (b.punti == null)
                        "Dev'essere un numero intero."
                    else
                        "La somma dei punti di tutti i premiati è il numero della bolla " +
                            "di Memo in AppSphere, e si aggiunge al totale che paga i premi.",
                    color = if (b.punti == null) Palette.danger else Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ── Barra di formattazione ──────────────────────────────────
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tasto("B", "Grassetto") { testo = avvolgi(testo, "**") }
                Tasto("I", "Corsivo") { testo = avvolgi(testo, "*") }
                Tasto("U", "Sottolineato") { testo = avvolgi(testo, "__") }
                Tasto("S", "Barrato") { testo = avvolgi(testo, "~~") }
                Tasto("H1", "Titolo grande") { testo = prefissa(testo, "# ") }
                Tasto("H2", "Titolo piccolo") { testo = prefissa(testo, "## ") }
                Tasto("•", "Elenco puntato") { testo = prefissa(testo, "- ") }
                Tasto("1.", "Elenco numerato") { testo = prefissa(testo, "1. ") }
                Tasto("🔗", "Collegamento") { testo = collegamento(testo) }
                Tasto("—", "Linea") { testo = inserisci(testo, "\n---\n") }
                Tasto(
                    testo = if (anteprima) "✏️" else "👁",
                    descrizione = if (anteprima) "Torna a scrivere" else "Anteprima",
                    attivo = anteprima,
                ) { anteprima = !anteprima }
            }

            if (anteprima) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(coloreDaHex(b.colore) ?: Palette.inputBg)
                        .padding(12.dp)
                ) {
                    val html = MemoHtml.aHtml(testo.text)
                    Text(
                        text = remember(html) {
                            runCatching { AnnotatedString.fromHtml(html) }
                                .getOrElse { AnnotatedString(testo.text) }
                        },
                        color = Palette.dark,
                    )
                }
            } else {
                OutlinedTextField(
                    value = testo,
                    onValueChange = { testo = it },
                    // Per un diario il «contenuto» è lo **scopo**, ed è quello
                    // che verrà rimostrato a ogni registrazione: l'etichetta lo
                    // dice, come `applyEditorKind()`.
                    label = {
                        Text(
                            when (b.tipo) {
                                TipoScheda.DIARIO -> "Scopo del diario"
                                TipoScheda.LISTA -> "Nota della lista"
                                TipoScheda.LINK -> "Perché lo tieni"
                                TipoScheda.PREMIATO -> "Per cosa"
                                TipoScheda.NOTA -> "Contenuto"
                            }
                        )
                    },
                    // Niente altezza fissa: è un minimo, e col testo lungo o coi
                    // caratteri di sistema grandi il campo cresce.
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                )
                when (b.tipo) {
                    TipoScheda.DIARIO -> Text(
                        text = "Perché stai misurando, e come vanno lette le misure. " +
                            "Te lo rimostro a ogni registrazione.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TipoScheda.LISTA -> Text(
                        text = "Facoltativa: a cosa serve questa lista.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TipoScheda.LINK -> Text(
                        text = "Facoltativo: cosa volevi ricordarti di questo link.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TipoScheda.PREMIATO -> Text(
                        text = "Facoltativo: che cosa è stato premiato, e perché vale quei punti.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TipoScheda.NOTA -> Unit
                }
            }

            // ── Voci della lista / misure del diario ────────────────────
            if (b.tipo == TipoScheda.LISTA) {
                SezioneVoci(
                    voci = b.voci,
                    onCambia = { b = b.copy(voci = it) },
                    onTogli = { i ->
                        val voce = b.voci.getOrNull(i) ?: return@SezioneVoci
                        b = b.copy(
                            voci = b.voci.filterIndexed { j, _ -> j != i },
                            vociTolte = if (voce.id != null) b.vociTolte + voce.id else b.vociTolte,
                        )
                    },
                )
            }

            if (b.tipo == TipoScheda.DIARIO) {
                SezioneMisure(
                    misure = b.misure,
                    onCambia = { b = b.copy(misure = it) },
                    onTogli = { i ->
                        val misura = b.misure.getOrNull(i) ?: return@SezioneMisure
                        b = b.copy(
                            misure = b.misure.filterIndexed { j, _ -> j != i },
                            misureTolte = if (misura.id != null) b.misureTolte + misura.id
                            else b.misureTolte,
                        )
                    },
                )
            }

            // ── Colore ──────────────────────────────────────────────────
            Text("Colore", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MmScheda.COLORI.forEach { (hex, nome) ->
                    val scelto = b.colore.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(coloreDaHex(hex) ?: Palette.cardBg)
                            .clickable { b = b.copy(colore = hex) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Il bianco su fondo bianco sparirebbe: il segno di
                        // spunta e un bordo dicono qual è quello scelto.
                        Text(
                            text = if (scelto) "✓" else "",
                            color = Palette.dark,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // ── Categorie ───────────────────────────────────────────────
            Text("Categorie", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            if (categorie.isEmpty()) {
                Text(
                    text = "Nessuna categoria: si creano da AppSphere → Dati Comuni.",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val larghezzaCategorie = larghezzaPulsanti(categorie.map { it.etichetta })
                RigaScorrevole(Arrangement.spacedBy(6.dp)) {
                    categorie.forEach { cat ->
                        val scelta = cat.id in b.categorie
                        Text(
                            text = cat.etichetta,
                            color = if (scelta) Palette.light else Palette.dark,
                            fontWeight = if (scelta) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .width(larghezzaCategorie)
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (scelta) coloreDaHex(cat.colore) ?: BluMemo
                                    else Palette.inputBg
                                )
                                .clickable {
                                    b = b.copy(
                                        categorie = if (scelta) b.categorie - cat.id
                                        else b.categorie + cat.id
                                    )
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }

            // ── Evidenza ────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = b.fissata, onCheckedChange = { b = b.copy(fissata = it) })
                Text(
                    text = "📌 Tienila in evidenza",
                    color = Palette.dark,
                    modifier = Modifier.clickable { b = b.copy(fissata = !b.fissata) },
                )
            }

            // ── Riservato ───────────────────────────────────────────────
            //
            // ⚠️ La spunta si vede **solo sui diari**, come sul web: sulle note
            // e sulle liste il campo non c'è, e il salvataggio manda `false`.
            // La colonna è però su `mm_cards` e il filtro vale per tutti i
            // tipi, quindi estenderla è una riga.
            if (b.tipo == TipoScheda.DIARIO) {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = b.riservato,
                        onCheckedChange = { b = b.copy(riservato = it) },
                    )
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "🙈 Riservato",
                            color = Palette.dark,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { b = b.copy(riservato = !b.riservato) },
                        )
                        Text(
                            text = "Si vede solo a modalità nascosta accesa. Fuori di lì la " +
                                "scheda non viene proprio letta — non è nascosta a schermo, " +
                                "è assente.",
                            color = Palette.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // ── Foto ────────────────────────────────────────────────────
            Text("Foto", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = conOcr, onCheckedChange = { conOcr = it })
                Text(
                    text = "🔍 Estrai il testo dalla foto (OCR)",
                    color = Palette.dark,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tasto("🖼 Galleria", "Scegli dalla galleria") {
                    galleria.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                Tasto("📷 Fotocamera", "Scatta una foto") {
                    val uri = MemoFoto.uriPerScatto(context)
                    uriScatto = uri
                    fotocamera.launch(uri)
                }
            }

            val rimaste = immaginiEsistenti.filterNot { it in daTogliere }
            if (rimaste.isNotEmpty() || nuove.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rimaste.forEach { foto ->
                        Miniatura(modello = foto.url) { daTogliere = daTogliere + foto }
                    }
                    nuove.forEach { foto ->
                        Miniatura(modello = foto.uri) { nuove = nuove - foto }
                    }
                }
                if (daTogliere.isNotEmpty() || nuove.isNotEmpty()) {
                    Text(
                        text = "Le foto si caricano — e quelle tolte si cancellano — al salvataggio.",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Le voci di una lista nell'editor: si scrivono, si spuntano e si tolgono.
 *
 * Una voce **tolta qui non si cancella subito**: finisce in `vociTolte` e sparisce
 * al salvataggio, come `deletedItemIds` nel web. Chi cambia idea esce senza
 * salvare e la ritrova.
 */
@Composable
private fun SezioneVoci(
    voci: List<VoceInModifica>,
    onCambia: (List<VoceInModifica>) -> Unit,
    onTogli: (Int) -> Unit,
) {
    var nuova by remember { mutableStateOf("") }

    Text("☑️ Voci", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)

    if (voci.isEmpty()) {
        Text(
            text = "Nessuna voce. Scrivila qui sotto e premi ➕.",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    voci.forEachIndexed { i, voce ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = voce.fatta,
                onCheckedChange = { fatta ->
                    onCambia(voci.mapIndexed { j, v ->
                        if (j == i) v.copy(
                            fatta = fatta,
                            fattaIl = if (fatta) java.time.Instant.now().toString() else null,
                        ) else v
                    })
                },
            )
            OutlinedTextField(
                value = voce.testo,
                onValueChange = { testo ->
                    onCambia(voci.mapIndexed { j, v -> if (j == i) v.copy(testo = testo) else v })
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Tasto("✕", "Togli la voce") { onTogli(i) }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = nuova,
            onValueChange = { nuova = it },
            label = { Text("Nuova voce") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Tasto("➕", "Aggiungi la voce") {
            if (nuova.isNotBlank()) {
                onCambia(voci + VoceInModifica(testo = nuova.trim()))
                nuova = ""
            }
        }
    }
}

/** I fondo scala già pronti della scala, come `METRIC_PRESETS`. */
private val SCALE_PRONTE = listOf(1.0 to 5.0, 1.0 to 10.0, 1.0 to 20.0, 0.0 to 100.0, 1.0 to 100.0)

/**
 * Le misure di un diario nell'editor.
 *
 * ⚠️ Togliere una misura **non cancella le registrazioni**: i valori restano
 * scritti in `measures` con una chiave che non ha più una riga, e la vista li
 * mostra come *misura tolta*. L'avviso qui sotto lo dice prima, non dopo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SezioneMisure(
    misure: List<MisuraInModifica>,
    onCambia: (List<MisuraInModifica>) -> Unit,
    onTogli: (Int) -> Unit,
) {
    var daTogliere by remember { mutableStateOf<Int?>(null) }

    fun aggiorna(i: Int, cambio: (MisuraInModifica) -> MisuraInModifica) {
        onCambia(misure.mapIndexed { j, m -> if (j == i) cambio(m) else m })
    }

    Text("📊 Misure", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)

    if (misure.isEmpty()) {
        Text(
            text = "Nessuna misura: aggiungine almeno una, altrimenti una registrazione " +
                "non raccoglie niente.",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    misure.forEachIndexed { i, m ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.inputBg)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = m.nome,
                    onValueChange = { nome -> aggiorna(i) { it.copy(nome = nome) } },
                    label = { Text("Cosa misuri") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Tasto("✕", "Togli la misura") {
                    // Su una misura già salvata si chiede prima: le
                    // registrazioni che la citano restano, ma senza nome.
                    if (m.id != null) daTogliere = i else onTogli(i)
                }
            }

            Tendina(
                etichetta = "Tipo",
                scelto = m.tipo.etichetta,
                voci = TipoMisura.entries.map { it.chiave to it.etichetta },
            ) { scelta ->
                val tipo = TipoMisura.da(scelta)
                aggiorna(i) { misura ->
                    misura.copy(
                        tipo = tipo,
                        minimo = if (tipo == TipoMisura.SCALA) misura.minimo ?: 1.0 else null,
                        massimo = if (tipo == TipoMisura.SCALA) misura.massimo ?: 10.0 else null,
                        unita = if (tipo == TipoMisura.NUMERO) misura.unita else "",
                        // Le opzioni non si buttano passando a un altro
                        // tipo e tornando indietro: sono la cosa più
                        // lunga da riscrivere di tutta la misura.
                        opzioni = if (tipo == TipoMisura.SCELTA && misura.opzioni.isEmpty())
                            listOf(
                                MmOpzione(nuovoIdOpzione(), ""),
                                MmOpzione(nuovoIdOpzione(), ""),
                            )
                        else misura.opzioni,
                    )
                }
            }

            when (m.tipo) {
                TipoMisura.SCALA -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = m.minimo?.let { numero(it) } ?: "",
                            onValueChange = { v ->
                                aggiorna(i) { it.copy(minimo = v.toDoubleOrNull()) }
                            },
                            label = { Text("da") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = m.massimo?.let { numero(it) } ?: "",
                            onValueChange = { v ->
                                aggiorna(i) { it.copy(massimo = v.toDoubleOrNull()) }
                            },
                            label = { Text("a") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SCALE_PRONTE.forEach { (da, a) ->
                            Tasto("${numero(da)}-${numero(a)}", "Scala da $da a $a") {
                                aggiorna(i) { it.copy(minimo = da, massimo = a) }
                            }
                        }
                    }
                }

                TipoMisura.NUMERO -> OutlinedTextField(
                    value = m.unita,
                    onValueChange = { u -> aggiorna(i) { it.copy(unita = u) } },
                    label = { Text("Unità (kg, km, €, min…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                TipoMisura.BOOL -> Text(
                    text = "sì / no — vale 1 o 0 nei riepiloghi",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )

                TipoMisura.SCELTA -> {
                    m.opzioni.forEachIndexed { j, o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${j + 1}.",
                                color = Palette.muted,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            OutlinedTextField(
                                value = o.etichetta,
                                onValueChange = { etichetta ->
                                    aggiorna(i) { misura ->
                                        misura.copy(
                                            opzioni = misura.opzioni.mapIndexed { k, op ->
                                                // L'id non si tocca mai: è quello
                                                // che le registrazioni citano.
                                                if (k == j) op.copy(etichetta = etichetta) else op
                                            }
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Tasto("✕", "Togli l'opzione") {
                                aggiorna(i) { misura ->
                                    misura.copy(
                                        opzioni = misura.opzioni.filterIndexed { k, _ -> k != j }
                                    )
                                }
                            }
                        }
                    }
                    Tasto("➕ Aggiungi opzione", "Aggiungi un'opzione") {
                        aggiorna(i) { it.copy(opzioni = it.opzioni + MmOpzione(nuovoIdOpzione(), "")) }
                    }
                }
            }

            OutlinedTextField(
                value = m.nota,
                onValueChange = { testo -> aggiorna(i) { it.copy(nota = testo) } },
                label = { Text("Cosa vuol dire il punteggio") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Tasto("➕ Aggiungi misura", "Aggiungi una misura") {
        onCambia(misure + MisuraInModifica())
    }

    daTogliere?.let { i ->
        val m = misure.getOrNull(i)
        AlertDialog(
            onDismissRequest = { daTogliere = null },
            title = { Text("Togliere la misura?") },
            text = {
                Text(
                    "«${m?.nome?.ifBlank { "senza nome" } ?: "senza nome"}» sparisce dalle " +
                        "prossime registrazioni. Quelle già fatte restano, ma il valore di " +
                        "questa misura non si vedrà più."
                )
            },
            confirmButton = {
                TextButton(onClick = { daTogliere = null; onTogli(i) }) {
                    Text("Togli", color = Palette.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { daTogliere = null }) { Text("Annulla", color = Palette.muted) }
            },
        )
    }
}

@Composable
private fun Miniatura(modello: Any?, onTogli: () -> Unit) {
    Box {
        AsyncImage(
            model = modello,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.inputBg),
        )
        Text(
            text = "✕",
            color = Palette.light,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color(0xCC111111))
                .clickable(onClick = onTogli)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Tasto(
    testo: String,
    descrizione: String,
    attivo: Boolean = false,
    onTocca: () -> Unit,
) {
    Text(
        text = testo,
        color = if (attivo) Palette.light else Palette.dark,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (attivo) BluMemo else Palette.inputBg)
            .clickable(onClick = onTocca)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// ── I pulsanti della barra: cosa fanno al testo ─────────────────────────────
//
// Sono funzioni pure sul TextFieldValue, che è testo più selezione: infilano i
// marcatori e rimettono il cursore dove uno se lo aspetta — dentro i marcatori
// se non c'era niente di selezionato, dopo il pezzo formattato se c'era.

internal fun avvolgi(v: TextFieldValue, marcatore: String): TextFieldValue {
    val inizio = minOf(v.selection.start, v.selection.end)
    val fine = maxOf(v.selection.start, v.selection.end)
    val nuovo = v.text.substring(0, inizio) + marcatore +
        v.text.substring(inizio, fine) + marcatore + v.text.substring(fine)
    val cursore = if (inizio == fine) inizio + marcatore.length
    else fine + marcatore.length * 2
    return TextFieldValue(nuovo, TextRange(cursore))
}

internal fun prefissa(v: TextFieldValue, prefisso: String): TextFieldValue {
    val inizio = minOf(v.selection.start, v.selection.end)
    val inizioRiga = v.text.lastIndexOf('\n', (inizio - 1).coerceAtLeast(0))
        .let { if (it < 0 || inizio == 0) 0 else it + 1 }
    val nuovo = v.text.substring(0, inizioRiga) + prefisso + v.text.substring(inizioRiga)
    return TextFieldValue(nuovo, TextRange(inizio + prefisso.length))
}

internal fun inserisci(v: TextFieldValue, pezzo: String): TextFieldValue {
    val inizio = minOf(v.selection.start, v.selection.end)
    val fine = maxOf(v.selection.start, v.selection.end)
    val nuovo = v.text.substring(0, inizio) + pezzo + v.text.substring(fine)
    return TextFieldValue(nuovo, TextRange(inizio + pezzo.length))
}

/**
 * Il collegamento: `[quello che era selezionato](https://)`, col cursore
 * pronto sull'indirizzo — che è l'unica cosa che resta da scrivere.
 */
internal fun collegamento(v: TextFieldValue): TextFieldValue {
    val inizio = minOf(v.selection.start, v.selection.end)
    val fine = maxOf(v.selection.start, v.selection.end)
    val etichetta = v.text.substring(inizio, fine).ifBlank { "testo" }
    val pezzo = "[$etichetta](https://)"
    val nuovo = v.text.substring(0, inizio) + pezzo + v.text.substring(fine)
    return TextFieldValue(nuovo, TextRange(inizio + pezzo.length - 1))
}
