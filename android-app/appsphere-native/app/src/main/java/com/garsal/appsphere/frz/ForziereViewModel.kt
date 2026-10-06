package com.garsal.appsphere.frz

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher

/** Dov'è il forziere adesso. */
enum class FaseForziere { CARICO, ASSENTE, CHIUSO, APERTO }

/**
 * Il filtro degli scomparti, con gli stessi tre stati della barra di pillole
 * nella pagina: tutti · uno scomparto · quelli senza.
 */
sealed interface Filtro {
    data object Tutti : Filtro
    data object Senza : Filtro
    data class Uno(val id: String) : Filtro
}

/** Il documento aperto nel visore, già decifrato. */
data class Visione(
    val doc: FrzDocumento,
    val immagine: ByteArray? = null,
    val testo: String? = null,
    /** ⚠️ Un file **temporaneo in cache**: vedi la nota su `apriDocumento`. */
    val pdf: File? = null,
    /** Video o audio, anch'esso temporaneo in cache. */
    val media: File? = null,
    val video: Boolean = false,
    val nonSiApre: String? = null,
) {
    /** Il temporaneo da cancellare chiudendo, se ce n'è uno. */
    val temporaneo: File? get() = pdf ?: media
}

data class ForziereState(
    val fase: FaseForziere = FaseForziere.CARICO,
    val errore: String? = null,
    val stato: String? = null,
    val avanzamento: Float? = null,
    val scomparti: List<FrzScomparto> = emptyList(),
    val documenti: List<FrzDocumento> = emptyList(),
    val miniature: Map<String, ByteArray> = emptyMap(),
    val filtro: Filtro = Filtro.Tutti,
    val cerca: String = "",
    val improntaDisponibile: Boolean = false,
    val improntaRegistrata: Boolean = false,
    val indiciRotti: Boolean = false,
    val motivoChiusura: String? = null,
    val visione: Visione? = null,
    /**
     * I documenti spuntati nella griglia.
     *
     * ⚠️ **Si azzera cambiando scomparto o ricerca** (`filtra`, `cerca`): una
     * selezione che sopravvive al filtro è un 🗑 che butta via documenti che in
     * quel momento non si vedono — cioè esattamente quel che una conferma non
     * riesce a raccontare.
     */
    val selezione: Set<String> = emptySet(),
    /** I documenti di cui si sta chiedendo conferma, e nient'altro. */
    val daCancellare: List<FrzDocumento>? = null,
    val cancellaErrore: String? = null,
) {
    val inSelezione: Boolean get() = selezione.isNotEmpty()

    /**
     * ⚠️ **La ricerca è lato client e non può essere altrimenti**: il server i
     * nomi non li può leggere. È il prezzo dell'E2EE ed è giusto pagarlo qui.
     */
    val visibili: List<FrzDocumento>
        get() {
            val q = cerca.trim().lowercase()
            return documenti.filter { d ->
                when (filtro) {
                    Filtro.Tutti -> true
                    Filtro.Senza -> d.boxId == null
                    is Filtro.Uno -> d.boxId == filtro.id
                }
            }.filter { q.isEmpty() || it.meta.nome.lowercase().contains(q) }
        }

    fun scomparto(id: String?): FrzScomparto? = scomparti.firstOrNull { it.id == id }
}

/**
 * Il Forziere sul telefono: **sbloccare, sfogliare, aprire, mettere dentro,
 * buttare via**.
 *
 * ⚠️ **Restano sul PC** la creazione del forziere, il collaudo delle 24 parole,
 * l'export `.7z` e il cambio della passphrase. Non è una mancanza da colmare
 * col tempo: sono le operazioni che si fanno una volta e vanno fatte bene, con
 * le parole davanti e senza fretta, e il telefono è il posto sbagliato per
 * ciascuna. Un forziere che non esiste ancora, da qui, lo si dice — non lo si
 * crea a metà.
 *
 * ⚠️ **Le 24 parole e la chiave dell'indice vivono in memoria e basta.** Niente
 * preferenze, niente file, mai: ritrovare il forziere aperto al risveglio
 * dell'app sarebbe l'opposto di quel che il forziere fa. Da qui discendono il
 * blocco a tempo e la chiusura quando l'app passa in secondo piano.
 */
class ForziereViewModel : ViewModel() {

    /** Gli stessi dieci minuti della pagina (`MINUTI_BLOCCO`). */
    private val minutiBlocco = 10

    private val _stato = MutableStateFlow(ForziereState())
    val stato: StateFlow<ForziereState> = _stato.asStateFlow()

    private var vault: FrzVault? = null
    private var aperto: ForziereAperto? = null

    /**
     * `scorciatoia.gpg` così com'è su Drive, tenuta da parte per la verifica
     * della passphrase.
     *
     * ⚠️ **Non è un segreto**: su Drive sta esattamente così, e si apre solo
     * con la passphrase. Tenerla qui evita che la conferma di una cancellazione
     * dipenda dalla rete — col Drive irraggiungibile si vedrebbe la finestra e
     * non si potrebbe rispondere. Stessa scelta di `S.scorc` nella pagina.
     */
    private var scorc: ByteArray? = null

    /**
     * ⚠️ Un'operazione lunga in corso non si interrompe a metà: chiudendo il
     * forziere mentre un file sta salendo, le parole sparirebbero a caricamento
     * avviato — il file resterebbe su Drive **senza la sua riga**, cioè un
     * pacchetto cifrato che nessuno sa più cos'è e che dall'app non si può più
     * togliere. Stessa guardia di `S.occupato` nella pagina.
     */
    private var occupato = 0

    private var timer: Job? = null

    /**
     * ⚠️ **Il selettore dei file è un'altra Activity**, quindi aprirlo manda
     * questa in `ON_STOP` — cioè, senza questa scusa, chiuderebbe il forziere
     * proprio nel gesto con cui si sta per metterci dentro qualcosa, e al
     * ritorno il file non entrerebbe. Vale **una volta sola**: il ritorno la
     * consuma, e un'app messa via davvero si chiude come sempre.
     *
     * La biometria invece non c'entra: `BiometricPrompt` è una finestra di
     * questa stessa Activity e `ON_STOP` non lo fa scattare.
     */
    private var scusaSistema = false

    fun apriUnaFinestraDiSistema() { scusaSistema = true }

    // ── Avvio ────────────────────────────────────────────────────────────────

    fun avvia(ctx: Context) {
        pulisciCache(ctx)
        _stato.value = _stato.value.copy(
            improntaDisponibile = ForziereBiometria.disponibile(ctx),
            improntaRegistrata = ForziereBiometria.registrato(ctx),
        )
        viewModelScope.launch {
            runCatching { ForziereRepository.vault() }
                .onSuccess { v ->
                    vault = v
                    _stato.value = _stato.value.copy(
                        fase = if (v != null && v.completo) FaseForziere.CHIUSO else FaseForziere.ASSENTE,
                        errore = null,
                    )
                }
                .onFailure { e ->
                    _stato.value = _stato.value.copy(
                        fase = FaseForziere.ASSENTE,
                        errore = e.message ?: "non riesco a leggere il forziere",
                    )
                }
        }
    }

    // ── Aprire ───────────────────────────────────────────────────────────────

    fun apriConPassphrase(pw: String) {
        if (pw.isEmpty()) return
        val v = vault ?: return
        viewModelScope.launch {
            _stato.value = _stato.value.copy(stato = "Apro…", errore = null)
            try {
                val cifrata = scorc ?: ForziereDrive.scaricaBytes(v.scorciatoiaId!!).also { scorc = it }
                val parole = try {
                    withContext(Dispatchers.Default) { ForzierePgp.decifraTesto(cifrata, pw) }
                } catch (e: Exception) {
                    throw IllegalStateException("passphrase sbagliata")
                }
                apri(ForziereCripto.normParole(parole))
            } catch (e: Exception) {
                _stato.value = _stato.value.copy(stato = null, errore = e.message ?: "non si apre")
            }
        }
    }

    fun apriConParole(parole: String) {
        if (parole.isBlank()) return
        viewModelScope.launch {
            _stato.value = _stato.value.copy(stato = "Provo…", errore = null)
            try {
                apri(ForziereCripto.normParole(parole))
            } catch (e: Exception) {
                _stato.value = _stato.value.copy(stato = null, errore = e.message ?: "non si apre")
            }
        }
    }

    /**
     * L'impronta ha detto che sei tu, e le parole erano avvolte nel Keystore.
     *
     * ⚠️ Si passa comunque da [apri], che le prova su `indice.gpg`: il Keystore
     * dice **chi sei**, non che quelle parole aprano questo forziere — se ne è
     * stato rifatto uno nel frattempo, le parole avvolte sono quelle di prima.
     */
    fun apriConImpronta(ctx: Context, cipher: Cipher) {
        viewModelScope.launch {
            _stato.value = _stato.value.copy(stato = "Apro…", errore = null)
            try {
                val parole = withContext(Dispatchers.IO) { ForziereBiometria.svolgi(ctx, cipher) }
                apri(ForziereCripto.normParole(parole))
            } catch (e: Exception) {
                _stato.value = _stato.value.copy(stato = null, errore = e.message ?: "non si apre")
            }
        }
    }

    /**
     * Il cuore: le parole aprono `indice.gpg`, e se non lo aprono **non sono
     * quelle di questo forziere**.
     *
     * ⚠️ `indice.gpg` fa da prova senza essere un secondo oggetto: se si
     * decifra, le parole sono giuste; se no, no. Un verificatore a parte sarebbe
     * una seconda verità sulla stessa domanda — e un oracolo in più per chi
     * prova a indovinare.
     */
    private suspend fun apri(parole: String) {
        val v = vault ?: error("questo forziere non c'è ancora")
        val raw = ForziereDrive.scaricaBytes(v.indiceId!!)
        val chiaveB64 = try {
            withContext(Dispatchers.Default) { ForzierePgp.decifraTesto(raw, parole) }
        } catch (e: Exception) {
            throw IllegalStateException("queste non sono le parole di questo forziere")
        }
        aperto = ForziereAperto(parole, ForziereCripto.chiaveIndice(ForziereCripto.daB64(chiaveB64.trim())))
        _stato.value = _stato.value.copy(fase = FaseForziere.APERTO, stato = null, errore = null, motivoChiusura = null)
        rinviaBlocco()
        ricarica()
    }

    /** Registra l'impronta con le parole che sono **già** in memoria. */
    fun registraImpronta(ctx: Context, cipher: Cipher) {
        val a = aperto ?: return
        runCatching { ForziereBiometria.avvolgi(ctx, cipher, a.parole) }
        _stato.value = _stato.value.copy(improntaRegistrata = ForziereBiometria.registrato(ctx))
    }

    fun togliImpronta(ctx: Context) {
        ForziereBiometria.dimentica(ctx)
        _stato.value = _stato.value.copy(improntaRegistrata = false)
    }

    // ── Chiudere ─────────────────────────────────────────────────────────────

    /**
     * ⚠️ Chi sta caricando o aprendo qualcosa non si interrompe: il forziere si
     * chiude alla fine. Il ritorno dice se è successo davvero.
     */
    fun chiudi(ctx: Context, motivo: String?): Boolean {
        if (aperto == null) return true
        if (occupato > 0) return false
        if (scusaSistema) { scusaSistema = false; return false }
        timer?.cancel(); timer = null
        aperto = null
        pulisciCache(ctx)
        _stato.value = ForziereState(
            fase = if (vault?.completo == true) FaseForziere.CHIUSO else FaseForziere.ASSENTE,
            improntaDisponibile = _stato.value.improntaDisponibile,
            improntaRegistrata = _stato.value.improntaRegistrata,
            motivoChiusura = motivo,
        )
        return true
    }

    /** Ogni tocco rimanda il blocco, come nella pagina. */
    fun tocca() { if (aperto != null) rinviaBlocco() }

    private fun rinviaBlocco() {
        timer?.cancel()
        timer = viewModelScope.launch {
            delay(minutiBlocco * 60_000L)
            if (occupato == 0 && aperto != null) {
                aperto = null
                _stato.value = ForziereState(
                    fase = FaseForziere.CHIUSO,
                    improntaDisponibile = _stato.value.improntaDisponibile,
                    improntaRegistrata = _stato.value.improntaRegistrata,
                    motivoChiusura = "sono passati $minutiBlocco minuti senza toccare niente",
                )
            }
        }
    }

    // ── L'elenco ─────────────────────────────────────────────────────────────

    fun ricaricaOra() { viewModelScope.launch { ricarica() } }

    private suspend fun ricarica() {
        val a = aperto ?: return
        try {
            val boxes = ForziereRepository.scomparti(a.chiave)
            val file = ForziereRepository.documenti(a.chiave)
            val mini = ForziereRepository.miniature(file.map { it.id }, a.chiave)
            _stato.value = _stato.value.copy(
                scomparti = boxes, documenti = file, miniature = mini, errore = null,
            )
        } catch (e: Exception) {
            _stato.value = _stato.value.copy(errore = e.message ?: "non riesco a leggere l'elenco")
        }
    }

    fun filtra(f: Filtro) {
        _stato.value = _stato.value.copy(filtro = f, selezione = emptySet()); tocca()
    }

    fun cerca(q: String) {
        _stato.value = _stato.value.copy(cerca = q, selezione = emptySet()); tocca()
    }

    // ── Sceglierne più d'uno ─────────────────────────────────────────────────

    /**
     * La pressione lunga apre la selezione, il tocco poi spunta e despunta.
     *
     * ⚠️ **Non c'è una casella su ogni scheda**: il caso normale è aprire un
     * documento, e una spunta permanente su ogni riquadro sarebbe rumore su
     * ogni apertura per un gesto che si fa di rado. Tolta l'ultima spunta si
     * esce da sé — un elenco che resta «in selezione» senza niente di
     * selezionato è uno stato che non si capisce come si chiude.
     */
    fun seleziona(id: String) {
        val ora = _stato.value.selezione
        _stato.value = _stato.value.copy(
            selezione = if (id in ora) ora - id else ora + id,
        )
        tocca()
    }

    fun azzeraSelezione() { _stato.value = _stato.value.copy(selezione = emptySet()); tocca() }

    // ── Buttare via ──────────────────────────────────────────────────────────

    fun chiediCancella(docs: List<FrzDocumento>) {
        if (docs.isEmpty()) return
        _stato.value = _stato.value.copy(daCancellare = docs, cancellaErrore = null)
        tocca()
    }

    fun chiediCancellaSelezione() {
        val scelti = _stato.value.selezione
        chiediCancella(_stato.value.documenti.filter { it.id in scelti })
    }

    fun annullaCancella() {
        _stato.value = _stato.value.copy(daCancellare = null, cancellaErrore = null)
        tocca()
    }

    /**
     * ⚠️ **Costa la passphrase, e non è prudenza generica**: qui il forziere è
     * già aperto — passphrase o impronta le ha già superate chi è arrivato a
     * questa schermata — e senza questa domanda una scrivania lasciata un
     * momento basta a far sparire trenta documenti con due tocchi. È la stessa
     * scelta del 🧹 Svuota e della 💣 zona rossa nella pagina.
     *
     * ⚠️ **Le 24 parole restano una via**: chi ha aperto con loro e la
     * passphrase non se la ricorda più non deve trovarsi la cancellazione
     * chiusa per sempre senza che niente glielo spieghi.
     */
    fun confermaCancella(ctx: Context, scritto: String, conParole: Boolean) {
        val docs = _stato.value.daCancellare ?: return
        if (scritto.isBlank()) return
        viewModelScope.launch {
            _stato.value = _stato.value.copy(cancellaErrore = null, stato = "Controllo…")
            val esito = runCatching { verifica(scritto, conParole) }
            _stato.value = _stato.value.copy(stato = null)
            // ⚠️ «Non ho potuto controllare» e «è sbagliata» sono due cose
            // diverse: col Drive irraggiungibile, scrivere «passphrase
            // sbagliata» manderebbe a riscrivere all'infinito quella giusta.
            if (esito.isFailure) {
                _stato.value = _stato.value.copy(
                    cancellaErrore = "non riesco a controllare: " +
                        (esito.exceptionOrNull()?.message ?: "errore"),
                )
                return@launch
            }
            if (!esito.getOrDefault(false)) {
                _stato.value = _stato.value.copy(
                    cancellaErrore = if (conParole) "non sono le parole di questo forziere"
                    else "passphrase sbagliata",
                )
                return@launch
            }
            _stato.value = _stato.value.copy(daCancellare = null, selezione = emptySet())
            cancella(ctx, docs)
        }
    }

    /**
     * ⚠️ **Si verifica aprendo davvero la scorciatoia**, non confrontando con
     * qualcosa che sta in memoria: in memoria la passphrase non c'è — allo
     * sblocco serve ad aprire `scorciatoia.gpg` e viene buttata subito. Se ne
     * escono **esattamente le parole che questo forziere sta usando**, è quella.
     * Un confronto con una variabile sarebbe un controllo che passa sempre.
     */
    private suspend fun verifica(scritto: String, conParole: Boolean): Boolean {
        val a = aperto ?: return false
        if (conParole) return ForziereCripto.normParole(scritto) == a.parole
        val v = vault ?: return false
        val cifrata = scorc ?: ForziereDrive.scaricaBytes(v.scorciatoiaId!!).also { scorc = it }
        // Una passphrase che non apre la scorciatoia è sbagliata, e basta: è
        // l'unico errore che qui torna `false` invece di risalire.
        val parole = try {
            withContext(Dispatchers.Default) { ForzierePgp.decifraTesto(cifrata, scritto) }
        } catch (e: Exception) {
            return false
        }
        return ForziereCripto.normParole(parole) == a.parole
    }

    /**
     * ⚠️ **Uno per volta, prima Drive e poi la riga.** Nel verso opposto una
     * rete che cade lascia su Drive un `.gpg` che nessuno sa più cos'è e che
     * dall'app non si può più togliere. Uno per volta perché quel che è andato
     * è andato: l'elenco a schermo è già quel che resta, e ripremendo si
     * riprende invece di ricominciare. È l'ordine di `elimina()` nella pagina.
     */
    private fun cancella(ctx: Context, docs: List<FrzDocumento>) {
        viewModelScope.launch {
            occupato++
            val tocchi = mutableSetOf<String?>()
            var fatti = 0
            try {
                for (d in docs) {
                    _stato.value = _stato.value.copy(
                        stato = "Butto via " + d.meta.nome.ifBlank { "un documento" } + "…",
                        avanzamento = fatti.toFloat() / docs.size,
                        errore = null,
                    )
                    ForziereDrive.elimina(d.driveFileId)
                    ForziereRepository.cancella(d.id)
                    tocchi += d.boxId
                    fatti++
                    _stato.value = _stato.value.copy(
                        documenti = _stato.value.documenti.filter { it.id != d.id },
                        miniature = _stato.value.miniature - d.id,
                    )
                    // Il documento aperto nel visore non deve restare a schermo
                    // dopo essere stato buttato via.
                    if (_stato.value.visione?.doc?.id == d.id) chiudiVisore(ctx)
                }
                _stato.value = _stato.value.copy(stato = null, avanzamento = null)
            } catch (e: Exception) {
                _stato.value = _stato.value.copy(
                    stato = null, avanzamento = null,
                    errore = (if (fatti > 0) "ne ho buttati $fatti su ${docs.size}: " else "") +
                        (e.message ?: "errore"),
                )
            } finally {
                // Gli indici si riscrivono per i soli scomparti toccati, e anche
                // se qualcosa è andato storto: quel che è sparito è sparito, e
                // l'indice deve dire quel che c'è adesso.
                if (fatti > 0) scriviIndici(tocchi)
                occupato--; rinviaBlocco()
            }
        }
    }

    // ── Guardare un documento ────────────────────────────────────────────────

    /**
     * ⚠️ **Aprire non è scaricare, ed è il punto.** Un documento scaricato esce
     * dal forziere: si posa in chiaro in una cartella dove resta finché qualcuno
     * non se ne ricorda. Qui si guarda **dentro l'app** e alla chiusura non
     * resta niente.
     *
     * ⚠️ Immagini e testo non toccano il disco: stanno in memoria come il blob
     * della pagina. **PDF, video e audio** invece hanno bisogno di un
     * descrittore di file — né `PdfRenderer` né `MediaPlayer` sanno prendere i
     * byte dalla memoria — quindi si scrive un temporaneo nella cache
     * **privata dell'app**, e si cancella chiudendo il visore. Non esce mai di
     * lì: nessun `Intent`, nessun altro programma, e quindi nessuna copia in
     * un'app che non è questa. La cache si ripulisce anche all'avvio e alla
     * chiusura del forziere, per quel che fosse rimasto da un arresto anomalo.
     *
     * ⚠️ **Video e audio si guardano qui**, con `MediaPlayer` — il player del
     * sistema, dentro l'app, senza nessuna libreria in più: siamo a 43 MiB
     * contro i 50 oltre i quali l'APK committato dà noia, e media3/ExoPlayer
     * costerebbe qualche MiB per fare la stessa cosa su un file locale.
     *
     * ⚠️ Quel che resta fuori (archivi, fogli, documenti Office) **lo dice**,
     * invece di restare un riquadro nero o di aprirsi altrove: si guarda dal PC.
     * Un visore che finge di aver aperto qualcosa è peggio di uno che ammette di
     * non saperlo fare.
     */
    fun apriDocumento(ctx: Context, doc: FrzDocumento) {
        val a = aperto ?: return
        viewModelScope.launch {
            occupato++
            _stato.value = _stato.value.copy(stato = "Apro «${doc.meta.nome}»…", avanzamento = 0.1f, errore = null)
            var scaricato: File? = null
            try {
                val tipo = doc.meta.tipo.lowercase()
                if (!sappiamoMostrare(tipo)) {
                    // ⚠️ Non si scarica nemmeno: sarebbero minuti di rete e mezzo
                    // giga di cache per finire su un riquadro che dice «qui non
                    // si apre».
                    _stato.value = _stato.value.copy(
                        visione = Visione(doc, nonSiApre = tipoLeggibile(doc.meta.tipo)),
                        stato = null, avanzamento = null,
                    )
                    return@launch
                }

                // ⚠️ **Il cifrato scende su un file, non in memoria.** Un video
                // di mezzo giga letto in un `ByteArray` e poi decifrato in un
                // secondo `ByteArray` sono un giga di heap: l'app muore prima di
                // mostrare qualcosa. In flusso invece la dimensione non conta.
                scaricato = withContext(Dispatchers.IO) {
                    val f = File(cartellaCache(ctx), UUID.randomUUID().toString() + ".gpg")
                    f.outputStream().use { out -> ForziereDrive.scarica(doc.driveFileId, out) }
                    f
                }
                _stato.value = _stato.value.copy(avanzamento = 0.6f, stato = "Decifro…")

                val cifrato = scaricato!!
                val visione = when {
                    // Immagini e testo restano in memoria: sono l'unica cosa che
                    // si può mostrare senza passare da un file, e non passarci è
                    // sempre meglio.
                    tipo.startsWith("image/") ->
                        Visione(doc, immagine = decifraInMemoria(cifrato, a.parole))
                    tipo.startsWith("text/") || tipo == "application/json" ->
                        Visione(doc, testo = String(decifraInMemoria(cifrato, a.parole), Charsets.UTF_8))
                    tipo == "application/pdf" ->
                        Visione(doc, pdf = decifraSuFile(ctx, cifrato, a.parole, ".pdf"))
                    else ->
                        Visione(
                            doc,
                            media = decifraSuFile(ctx, cifrato, a.parole, estensioneDi(tipo)),
                            video = tipo.startsWith("video/"),
                        )
                }
                _stato.value = _stato.value.copy(visione = visione, stato = null, avanzamento = null)
            } catch (e: Exception) {
                _stato.value = _stato.value.copy(
                    stato = null, avanzamento = null,
                    errore = "«${doc.meta.nome}» non si apre: " + (e.message ?: "errore"),
                )
            } finally {
                // ⚠️ Il `.gpg` temporaneo se ne va SEMPRE: è già stato
                // decifrato, e lasciarlo lì sarebbe una copia in più di un
                // documento che nessuno ripulisce.
                scaricato?.delete()
                occupato--; rinviaBlocco()
            }
        }
    }

    private suspend fun decifraInMemoria(cifrato: File, parole: String): ByteArray =
        withContext(Dispatchers.Default) {
            val out = ByteArrayOutputStream(cifrato.length().toInt().coerceAtLeast(32))
            cifrato.inputStream().use { ForzierePgp.decifra(it, parole, out) }
            out.toByteArray()
        }

    private suspend fun decifraSuFile(
        ctx: Context,
        cifrato: File,
        parole: String,
        estensione: String,
    ): File = withContext(Dispatchers.Default) {
        val f = File(cartellaCache(ctx), UUID.randomUUID().toString() + estensione)
        cifrato.inputStream().use { ins -> f.outputStream().use { out ->
            ForzierePgp.decifra(ins, parole, out)
        } }
        f
    }

    fun chiudiVisore(ctx: Context) {
        _stato.value.visione?.temporaneo?.delete()
        _stato.value = _stato.value.copy(visione = null)
        pulisciCache(ctx)
        tocca()
    }

    /**
     * Quel che il telefono sa mostrare **dentro l'app**: immagini, testo, PDF,
     * video e audio — gli stessi del visore della pagina.
     *
     * ⚠️ Quel che non c'è (archivi, fogli di calcolo, documenti Office) **si
     * dice**, invece di aprirsi in un altro programma: un `Intent` ne farebbe
     * una copia in chiaro in un'app che non è questa, e da lì il forziere non la
     * riprende più.
     */
    private fun sappiamoMostrare(tipo: String): Boolean =
        tipo.startsWith("image/") || tipo.startsWith("text/") || tipo == "application/json" ||
            tipo == "application/pdf" || tipo.startsWith("video/") || tipo.startsWith("audio/")

    /**
     * ⚠️ **`MediaPlayer` guarda anche l'estensione**, non solo i byte: un file
     * senza estensione lo apre lo stesso quasi sempre, ma su alcuni telefoni
     * (e su alcuni contenitori) sbaglia l'estrattore e resta nero. Il tipo lo
     * sappiamo dai metadati, quindi gliela diamo.
     */
    private fun estensioneDi(tipo: String): String = when (tipo) {
        "video/mp4", "video/quicktime" -> ".mp4"
        "video/webm" -> ".webm"
        "video/3gpp" -> ".3gp"
        "video/x-matroska" -> ".mkv"
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4", "audio/aac" -> ".m4a"
        "audio/ogg", "audio/opus" -> ".ogg"
        "audio/wav", "audio/x-wav" -> ".wav"
        "audio/flac" -> ".flac"
        else -> if (tipo.startsWith("video/")) ".mp4" else ".m4a"
    }

    private fun tipoLeggibile(tipo: String): String =
        if (tipo.isBlank()) "di questo file non si sa il tipo" else tipo

    // ── Mettere dentro un documento ──────────────────────────────────────────

    /**
     * Cifra e carica. L'ordine è quello della pagina, e conta: **prima il file
     * su Drive, poi la riga** — al contrario, una rete che cade lascerebbe una
     * riga che punta a un file che non c'è.
     */
    fun metti(ctx: Context, uri: Uri) {
        val a = aperto ?: return
        viewModelScope.launch {
            occupato++
            var origine: File? = null
            var cifrato: File? = null
            try {
                val d = descrivi(ctx.contentResolver, uri)
                val nome = d.nome
                val tipo = d.tipo
                _stato.value = _stato.value.copy(stato = "$nome — leggo…", avanzamento = 0.05f, errore = null)
                origine = withContext(Dispatchers.IO) {
                    val f = File(cartellaCache(ctx), UUID.randomUUID().toString())
                    ctx.contentResolver.openInputStream(uri).use { ins ->
                        f.outputStream().use { out -> ins!!.copyTo(out, 1 shl 16) }
                    }
                    f
                }
                val letto = origine!!
                val quanti = letto.length()

                // ⚠️ La miniatura si fa PRIMA di cifrare, dall'originale: dopo
                // non c'è più niente da guardare. Solo per le immagini.
                val mini = if (tipo.startsWith("image/")) {
                    withContext(Dispatchers.Default) { runCatching { miniatura(letto) }.getOrNull() }
                } else null

                _stato.value = _stato.value.copy(stato = "$nome — cifro…", avanzamento = 0.2f)
                val chiuso = withContext(Dispatchers.Default) {
                    val f = File(cartellaCache(ctx), UUID.randomUUID().toString() + ".gpg")
                    letto.inputStream().use { ins ->
                        f.outputStream().use { out -> ForzierePgp.cifra(ins, quanti, nome, a.parole, out) }
                    }
                    f
                }
                cifrato = chiuso
                letto.delete(); origine = null

                // Il nome su Drive è l'id e basta: né la cartella né il nome del
                // file devono raccontare niente a chi li guarda dall'esterno.
                val box = _stato.value.scomparto(boxCorrente())
                val cartella = ForziereRepository.assicuraCartella(box)
                val idRiga = UUID.randomUUID().toString()
                val driveId = ForziereDrive.carica(
                    "$idRiga.gpg", chiuso, cartella,
                ) { q ->
                    _stato.value = _stato.value.copy(
                        avanzamento = 0.25f + q * 0.65f,
                        stato = "$nome — carico… ${(q * 100).toInt()}%",
                    )
                }

                _stato.value = _stato.value.copy(stato = "$nome — registro…", avanzamento = 0.95f)
                val meta = FrzMeta(nome, tipo, d.size ?: quanti, Instant.now().toString())
                val doc = ForziereRepository.inserisci(
                    idRiga, driveId, meta, chiuso.length(), boxCorrente(), a.chiave,
                )
                // Una miniatura che non entra non fa fallire un caricamento
                // riuscito: è una comodità, e l'elenco la sa mostrare assente.
                if (mini != null) runCatching { ForziereRepository.inserisciMiniatura(doc.id, mini, a.chiave) }
                chiuso.delete(); cifrato = null

                _stato.value = _stato.value.copy(
                    documenti = listOf(doc) + _stato.value.documenti,
                    miniature = if (mini != null) _stato.value.miniature + (doc.id to mini) else _stato.value.miniature,
                    stato = null, avanzamento = null,
                )
                scriviIndici(box)
            } catch (e: Exception) {
                _stato.value = _stato.value.copy(
                    stato = null, avanzamento = null,
                    errore = "il file non è entrato: " + (e.message ?: "errore"),
                )
            } finally {
                origine?.delete(); cifrato?.delete()
                occupato--; rinviaBlocco()
            }
        }
    }

    private fun boxCorrente(): String? = when (val f = _stato.value.filtro) {
        is Filtro.Uno -> f.id
        // ⚠️ Da «Tutti» il file nasce **fuori** da ogni scomparto invece che nel
        // primo della lista: una scelta presa dall'app al posto di chi carica si
        // scopre cercando il file altrove.
        else -> null
    }

    /**
     * ⚠️ Gli indici **non bloccano e non fanno fallire il caricamento**: il file
     * è già su Drive e la riga già scritta. Se la riscrittura non passa lo si
     * dice, e si rifà dal PC (Impostazioni → 🔄 Rifai gli indici).
     */
    private fun scriviIndici(box: FrzScomparto?) {
        val a = aperto ?: return
        viewModelScope.launch {
            val esito = runCatching {
                ForziereRepository.scriviIndiceContenuto(box, _stato.value.documenti, a.parole)
            }
            _stato.value = _stato.value.copy(indiciRotti = esito.isFailure)
        }
    }

    /**
     * Gli indici dei **soli scomparti toccati**: `contenuto.gpg` è l'elenco di
     * una cartella, quindi una cancellazione che attraversa tre scomparti ne
     * riscrive tre e non tutti. `null` nell'insieme è la radice, che è uno
     * scomparto come gli altri per questo conto.
     */
    private fun scriviIndici(boxIds: Set<String?>) {
        val a = aperto ?: return
        viewModelScope.launch {
            val esiti = boxIds.map { id ->
                runCatching {
                    ForziereRepository.scriviIndiceContenuto(
                        _stato.value.scomparto(id), _stato.value.documenti, a.parole,
                    )
                }
            }
            _stato.value = _stato.value.copy(indiciRotti = esiti.any { it.isFailure })
        }
    }

    // ── Utilità ──────────────────────────────────────────────────────────────

    private class Descrizione(val nome: String, val size: Long?, val tipo: String)

    private fun descrivi(cr: ContentResolver, uri: Uri): Descrizione {
        var nome = uri.lastPathSegment ?: "documento"
        var size: Long? = null
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) nome = c.getString(i)
                    val j = c.getColumnIndex(OpenableColumns.SIZE)
                    if (j >= 0 && !c.isNull(j)) size = c.getLong(j)
                }
            }
        }
        return Descrizione(nome, size, cr.getType(uri) ?: "")
    }

    /** 320 px di lato e JPEG 0,72: gli stessi numeri di `miniatura()` nella pagina. */
    private fun miniatura(f: File): ByteArray? {
        val misura = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, misura)
        if (misura.outWidth <= 0 || misura.outHeight <= 0) return null
        var campione = 1
        while (misura.outWidth / (campione * 2) >= 320 || misura.outHeight / (campione * 2) >= 320) campione *= 2
        val bmp = BitmapFactory.decodeFile(
            f.absolutePath, BitmapFactory.Options().apply { inSampleSize = campione }
        ) ?: return null
        val s = minOf(320f / bmp.width, 320f / bmp.height, 1f)
        val scalata = Bitmap.createScaledBitmap(
            bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true
        )
        val out = ByteArrayOutputStream()
        scalata.compress(Bitmap.CompressFormat.JPEG, 72, out)
        if (scalata !== bmp) scalata.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    private fun cartellaCache(ctx: Context): File =
        File(ctx.cacheDir, "forziere").apply { mkdirs() }

    /** Quel che fosse rimasto in cache da un arresto anomalo. */
    private fun pulisciCache(ctx: Context) {
        runCatching { cartellaCache(ctx).listFiles()?.forEach { it.delete() } }
    }
}
