package com.garsal.appsphere.memo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Perché qui non ci sono @Serializable data class ──────────────────────────
//
// `mm_cards`, `mm_card_categories` e `mm_images` non stanno in nessuna
// migration: nascono dal SQL che `memo.html` mostra in Impostazioni, da
// incollare a mano nella dashboard di Supabase. Come per `ts_tasks`, le colonne
// si conoscono solo da come la pagina le scrive, e una colonna del tipo
// inatteso con una data class farebbe fallire la decodifica dell'intera lista —
// cioè la schermata vuota invece di una scheda storta.

/**
 * Il tipo di una scheda (`mm_cards.kind`).
 *
 * È una colonna e non tre tabelle, quindi ricerca, categorie, colore, 📌 e foto
 * valgono uguale per note, liste e diari. Una scheda di tipo sconosciuto — o
 * nata prima della colonna — è una [NOTA], che è il `DEFAULT` del database.
 */
enum class TipoScheda(val chiave: String, val icona: String, val etichetta: String) {
    NOTA("nota", "📄", "Nota"),
    LISTA("lista", "☑️", "Lista"),
    DIARIO("diario", "📊", "Diario"),
    LINK("link", "🔗", "Link"),
    PREMIATO("premiato", "🏅", "Premiato");

    /** Come `KIND_TITLES` nel web: il nome della Tab. */
    val plurale: String
        get() = when (this) {
            NOTA -> "Note"
            LISTA -> "Liste"
            DIARIO -> "Diari"
            LINK -> "Link"
            PREMIATO -> "Premiati"
        }

    /** «Nuova nota», «Nuova lista», «Nuovo diario». */
    val nuova: String
        get() = when (this) {
            NOTA -> "Nuova nota"
            LISTA -> "Nuova lista"
            DIARIO -> "Nuovo diario"
            LINK -> "Nuovo link"
            PREMIATO -> "Nuovo premiato"
        }

    /**
     * Il complemento del placeholder della ricerca: «Cerca fra **le note**»,
     * «Cerca fra **i premiati**». Gemello di `KIND_NOMI[…].cerca` nel web, e
     * scritto qui per la stessa ragione: l'articolo cambia col tipo, e
     * ricavarlo dal plurale dava «Cerca fra le link».
     */
    val cerca: String
        get() = when (this) {
            NOTA -> "le note"
            LISTA -> "le liste"
            DIARIO -> "i diari"
            LINK -> "i link"
            PREMIATO -> "i premiati"
        }

    /** L'elenco vuoto: `KIND_NOMI[…].nessuno` del web. */
    val nessuna: String
        get() = when (this) {
            NOTA -> "Nessuna nota"
            LISTA -> "Nessuna lista"
            DIARIO -> "Nessun diario"
            LINK -> "Nessun link"
            PREMIATO -> "Nessun premiato"
        }

    companion object {
        fun da(valore: String?): TipoScheda =
            entries.firstOrNull { it.chiave == valore } ?: NOTA

        /**
         * Come [da], ma distingue «tipo che non conosco» da «tipo che non c'è».
         *
         * ⚠️ Serve a non far collassare su [NOTA] un tipo che il web ha e qui
         * non c'è ancora. Mostrata come nota, quella scheda perderebbe quel che
         * la rende sé stessa, e **risalvandola il form riscriverebbe il `kind`
         * sbagliato**: diventerebbe una nota in silenzio, con i suoi dati
         * agganciati a una riga che non li mostra più. È quello che sarebbe
         * successo alle schede `'link'` prima che questa implementazione le
         * conoscesse — oggi nessun tipo è in quello stato, e la guardia resta
         * per il prossimo.
         *
         * Torna `null` in quel caso, e la scheda non viene proprio caricata:
         * non vederla è meglio che vederla sbagliata e poterla rovinare.
         * Chiave assente o vuota resta [NOTA] — è il `DEFAULT` del database,
         * cioè le schede nate prima della colonna.
         */
        fun daONull(valore: String?): TipoScheda? =
            if (valore.isNullOrBlank()) NOTA
            else entries.firstOrNull { it.chiave == valore }
    }
}

/**
 * Quel che si ricava dall'indirizzo di un 🔗 Link.
 *
 * ⚠️ Gemello degli helper `ytIdDa` / `thumbDa` / `hostDa` di `memo.html`, e va
 * cambiato insieme a loro. In `mm_attachments` c'è **l'url e basta**: id del
 * video, copertina e nome del sito si ricavano da lui ogni volta, perché un
 * dato calcolato *e* archiviato sono due verità sullo stesso dato, che
 * divergono il giorno che una delle due cambia.
 */
object Link {
    /** Le forme in cui YouTube scrive i suoi link. L'id è sempre 11 caratteri. */
    private val YT =
        Regex("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})")

    fun idYouTube(url: String): String? = YT.find(url)?.groupValues?.get(1)

    /**
     * La copertina del video: un url pubblico di `img.youtube.com`, quindi
     * nessuna API, nessuna chiave e **nessun byte nel bucket**.
     *
     * Si usa `hqdefault`, che c'è su ogni video: `maxresdefault` manca sui
     * video vecchi e darebbe un riquadro rotto. `null` per tutto il resto —
     * un link che una copertina non ce l'ha mostra il nome del sito, non
     * un'anteprima inventata.
     */
    fun copertina(url: String): String? =
        idYouTube(url)?.let { id -> "https://img.youtube.com/vi/$id/hqdefault.jpg" }

    /** Il nome del sito, senza `www.`: quel che si legge sotto il titolo. */
    fun sito(url: String): String = runCatching {
        java.net.URI(url).host.orEmpty().removePrefix("www.")
    }.getOrDefault("")

    // ── L'indirizzo prima che sia un indirizzo ───────────────────────────
    //
    // Un link condiviso arriva dentro una frase e senza schema: prima di
    // ricavarne qualunque cosa va tirato fuori e normalizzato.

    /** `normalizzaUrl()` del web: senza schema si intende `https://`. */
    fun normalizza(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return ""
        return if (u.startsWith("http://", true) || u.startsWith("https://", true)) u
        else "https://$u"
    }

    /**
     * ⚠️ **Il punto nel nome del sito è il controllo che conta.** Senza, `URI`
     * accetta «https://ciao» come indirizzo validissimo con host «ciao»: una
     * parola secca condivisa da un'altra app diventerebbe una scheda link che
     * non porta da nessuna parte, invece della nota che è. Stessa regola di
     * `urlValido` nel web.
     */
    fun valido(url: String): Boolean = runCatching {
        val host = java.net.URI(normalizza(url)).host.orEmpty()
        host.contains('.') || host == "localhost"
    }.getOrDefault(false)

    /**
     * Il pezzo di testo condiviso che è un url: le app ci mettono spesso una
     * frase attorno («Guarda questo video: https://…»). È `primoUrlIn` del web.
     */
    fun primoUrl(testo: String): String =
        Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(testo)?.value.orEmpty()

    // ── Il titolo, quando non lo manda nessuno ───────────────────────────
    //
    // Condividendo da YouTube il titolo arriva in `EXTRA_SUBJECT` ed è esatto.
    // Ma non tutte le app lo mandano, e senza queste due funzioni ogni video
    // condiviso da lì si chiamerebbe «youtu.be».

    /** Segmenti che non dicono niente: si risale a quello prima. */
    private val SEGMENTI_MUTI =
        Regex("^(index|home|default|amp|it|en|article|articolo|video|watch|page|p|s)$", RegexOption.IGNORE_CASE)

    private val ESTENSIONE = Regex("\\.[a-z0-9]{2,5}$", RegexOption.IGNORE_CASE)
    private val CODA_NUMERICA = Regex("[-_]\\d{3,}$")
    private val CODA_ESADECIMALE = Regex("[-_][0-9a-f]{8,}$", RegexOption.IGNORE_CASE)

    // Token misto lettere+cifre in coda (…-abc123), ma solo con **almeno tre
    // cifre**: senza quella soglia «Covid-19» diventerebbe «Covid».
    private val CODA_MISTA =
        Regex("[-_](?=[a-z0-9]{6,}$)(?=[a-z]*\\d[a-z0-9]*\\d[a-z0-9]*\\d)[a-z0-9]+$", RegexOption.IGNORE_CASE)

    private val PAROLA = Regex("[a-zà-ÿ]{2,}", RegexOption.IGNORE_CASE)

    /**
     * L'ultimo pezzo del percorso ripulito: da
     * «/news/manovra_governo_conti-424193/» esce «Manovra governo conti».
     *
     * ⚠️ **Uno slug non è un titolo**: è quel che il sito ha scritto
     * nell'indirizzo per i motori di ricerca, spesso troncato. È meglio del
     * nome del sito e peggio del titolo vero — per questo viene dopo
     * `EXTRA_SUBJECT` e dopo l'oEmbed, mai prima. Su YouTube non si usa
     * affatto: lì lo slug è l'id del video, che come titolo è peggio del nome
     * del sito.
     *
     * Gemella di `titoloDaSlug()` in `memo.html`, riga per riga.
     */
    fun titoloDaSlug(url: String): String {
        if (idYouTube(url) != null) return ""
        val pezzi = runCatching {
            java.net.URI(normalizza(url)).path.orEmpty().split('/').filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        // si risale dal fondo: l'ultimo segmento è spesso un id nudo
        for (pezzo in pezzi.asReversed()) {
            var s = runCatching { java.net.URLDecoder.decode(pezzo, "UTF-8") }.getOrDefault(pezzo)
            s = s.replace(ESTENSIONE, "")        // .html, .shtml, .php…
            s = s.replace(CODA_NUMERICA, "")     // …-424193
            s = s.replace(CODA_ESADECIMALE, "")  // …-3f9a2b11
            s = s.replace(CODA_MISTA, "")
            if (SEGMENTI_MUTI.matches(s)) continue
            s = s.replace(Regex("[-_+]+"), " ").replace(Regex("\\s+"), " ").trim()
            if (s.length < 3) continue           // troppo corto per dire qualcosa
            if (!PAROLA.containsMatchIn(s)) continue  // solo cifre: è un id
            return s.replaceFirstChar { it.uppercase() }
        }
        return ""
    }

    /**
     * Il titolo vero di un video, dall'oEmbed di YouTube: nessuna chiave,
     * nessuna API da abilitare.
     *
     * ⚠️ **Fallisce in silenzio e torna stringa vuota**: senza rete, o il
     * giorno che quell'endpoint chiudesse, la scheda nasce lo stesso col
     * ripiego. Un titolo è una comodità, non una condizione — e per la stessa
     * ragione c'è un tetto di attesa: la finestra della scheda non deve restare
     * appesa a un server che non risponde.
     */
    suspend fun titoloYouTube(url: String): String {
        val id = idYouTube(url) ?: return ""
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(ATTESA_OEMBED_MS) {
                runCatching {
                    // sempre la forma canonica: l'oEmbed non accetta /shorts/ né youtu.be
                    val q = java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
                    // ⚠️ I tetti stanno sulla connessione e non solo su
                    // `withTimeoutOrNull`: una lettura bloccante non si annulla,
                    // e senza di loro l'attesa la deciderebbe il socket.
                    val conn = java.net.URL("https://www.youtube.com/oembed?format=json&url=$q")
                        .openConnection()
                        .apply {
                            connectTimeout = ATTESA_OEMBED_MS.toInt()
                            readTimeout = ATTESA_OEMBED_MS.toInt()
                        }
                    val risposta = conn.getInputStream().bufferedReader().use { it.readText() }
                    (Json.parseToJsonElement(risposta) as? JsonObject)
                        ?.get("title")?.let { (it as? JsonPrimitive)?.content }
                        .orEmpty().trim()
                }.getOrDefault("")
            }.orEmpty()
        }
    }

    private const val ATTESA_OEMBED_MS = 4_000L

    /** Nessun titolo è più lungo di così: è il limite del campo, come nel web. */
    private const val MAX_TITOLO = 200

    /**
     * Il titolo di una scheda 🔗 Link nata da una condivisione, nei quattro
     * gradini del web: `EXTRA_SUBJECT` → oEmbed di YouTube → slug → nome del
     * sito.
     *
     * ⚠️ **Non sovrascrive mai un titolo che c'è**: `oggetto` è quel che ha
     * mandato l'app che condivide, ed è esatto — un ripiego che ne prendesse il
     * posto sarebbe un peggioramento silenzioso.
     *
     * Differenza di forma dal web, non di risultato: là i gradini sono in due
     * tempi (prima lo slug, poi l'oEmbed che rimpiazza il provvisorio) perché
     * il campo è già a schermo mentre si scrive l'url. Qui la scheda si apre
     * **già compilata**, quindi si aspetta la risposta prima di aprirla — e
     * solo nel caso raro in cui l'oggetto manchi *e* il link sia di YouTube.
     */
    suspend fun titolo(url: String, oggetto: String): String {
        val dato = oggetto.trim()
        if (dato.isNotEmpty()) return dato.take(MAX_TITOLO)
        val vero = titoloYouTube(url)
        if (vero.isNotEmpty()) return vero.take(MAX_TITOLO)
        return titoloDaSlug(url).ifBlank { sito(normalizza(url)) }.take(MAX_TITOLO)
    }
}

/** Una scheda di Memo. `contenuto` è **HTML**, come lo scrive il web. */
data class MmScheda(
    val id: String,
    val titolo: String,
    val contenuto: String,
    val tipo: TipoScheda,
    val riservato: Boolean,
    val fissata: Boolean,
    val colore: String,
    val creata: String?,
    val aggiornata: String?,
    val categorie: List<String>,
    val immagini: Int,
    /** Voci di una lista: quante in tutto e quante spuntate. */
    val vociTotali: Int = 0,
    val vociFatte: Int = 0,
    /** Registrazioni di un diario, e la data dell'ultima. */
    val registrazioni: Int = 0,
    val ultimaRegistrazione: String? = null,
    /** L'indirizzo di un 🔗 Link: una scheda, un allegato. Vuoto sugli altri tipi. */
    val linkUrl: String = "",
    /**
     * I punti di un 🏅 Premiato, **0 su ogni altro tipo**.
     *
     * ⚠️ La somma di questi numeri è quel che la `score_query` di `cm_apps`
     * calcola sul database per dimensionare la bolla di Memo in AppSphere, e
     * da lì entra nel totale che paga i premi: è l'unico numero di Memo che
     * sia un punteggio. `NOT NULL DEFAULT 0` in tabella, quindi qui «non
     * l'ho ancora deciso» e «vale zero» sono la stessa cosa — a differenza
     * di `amount` in `fnz_income` e delle misure non registrate.
     */
    val punteggio: Int = 0,
) {
    /** Il testo senza tag, per l'anteprima nella scheda e per la ricerca. */
    val anteprima: String get() = MemoHtml.aTestoSemplice(contenuto)

    val dataItaliana: String get() = dataOra(aggiornata ?: creata)

    /** La barra di avanzamento della lista, 0..1. Zero voci = niente barra. */
    val avanzamento: Float?
        get() = if (tipo == TipoScheda.LISTA && vociTotali > 0)
            vociFatte.toFloat() / vociTotali else null

    companion object {
        fun da(o: JsonObject): MmScheda? {
            val id = testo(o, "id") ?: return null
            val voci = (o["mm_list_items"] as? JsonArray).orEmpty()
            val registrazioni = (o["mm_diary_entries"] as? JsonArray).orEmpty()
            val allegati = (o["mm_attachments"] as? JsonArray).orEmpty()
            return MmScheda(
                id = id,
                titolo = testo(o, "title").orEmpty(),
                contenuto = testo(o, "content").orEmpty(),
                // un tipo che questa implementazione non conosce (oggi 'link')
                // si salta invece di diventare una nota — vedi daONull
                tipo = TipoScheda.daONull(testo(o, "kind")) ?: return null,
                riservato = testo(o, "riservato")?.toBooleanStrictOrNull() ?: false,
                fissata = testo(o, "pinned")?.toBooleanStrictOrNull() ?: false,
                colore = testo(o, "color")?.takeIf { it.isNotBlank() } ?: BIANCO,
                creata = testo(o, "created_at"),
                aggiornata = testo(o, "updated_at"),
                categorie = (o["mm_card_categories"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let { r -> testo(r, "category_id") } }
                    .orEmpty(),
                immagini = (o["mm_images"] as? JsonArray)?.size ?: 0,
                vociTotali = voci.size,
                vociFatte = voci.count {
                    (it as? JsonObject)?.let { r -> testo(r, "done")?.toBooleanStrictOrNull() } == true
                },
                linkUrl = allegati
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { testo(it, "tipo") == "link" }
                    ?.let { testo(it, "url") }
                    .orEmpty(),
                punteggio = (o["punteggio"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                registrazioni = registrazioni.size,
                ultimaRegistrazione = registrazioni
                    .mapNotNull { (it as? JsonObject)?.let { r -> testo(r, "entry_date") } }
                    .maxOrNull(),
            )
        }

        const val BIANCO = "#FFFFFF"

        /** Gli stessi sette campioni della tavolozza di `memo.html`. */
        val COLORI = listOf(
            BIANCO to "Bianco",
            "#FEF9C3" to "Giallo",
            "#DCFCE7" to "Verde",
            "#DBEAFE" to "Blu",
            "#FAE8FF" to "Viola",
            "#FFE4E6" to "Rosa",
            "#FED7AA" to "Arancione",
        )
    }
}

/** Una voce di lista (`mm_list_items`). */
data class MmVoce(
    val id: String,
    val testo: String,
    val fatta: Boolean,
    val posizione: Int,
    val fattaIl: String?,
) {
    companion object {
        fun da(o: JsonObject): MmVoce? {
            val id = testo(o, "id") ?: return null
            return MmVoce(
                id = id,
                testo = testo(o, "text").orEmpty(),
                fatta = testo(o, "done")?.toBooleanStrictOrNull() ?: false,
                posizione = testo(o, "position")?.toIntOrNull() ?: 0,
                fattaIl = testo(o, "done_at"),
            )
        }
    }
}

/** Che cosa chiede una misura di diario (`mm_diary_metrics.kind`). */
enum class TipoMisura(val chiave: String, val etichetta: String) {
    SCALA("scala", "Scala"),
    NUMERO("numero", "Numero"),
    BOOL("bool", "Sì/No"),
    SCELTA("scelta", "Scelta");

    companion object {
        fun da(valore: String?): TipoMisura =
            entries.firstOrNull { it.chiave == valore } ?: SCALA
    }
}

/** Un'opzione di una misura a scelta: l'**id** è quello che si archivia. */
data class MmOpzione(val id: String, val etichetta: String)

/**
 * Una misura di un diario (`mm_diary_metrics`).
 *
 * ⚠️ Le misure sono righe vere e i valori no, ed è il motivo per cui non si
 * cancellano per ricrearle: le registrazioni le citano per id dentro
 * `measures`, e ricreandole tutto lo storico resterebbe senza nome.
 */
data class MmMisura(
    val id: String,
    val nome: String,
    val tipo: TipoMisura,
    val minimo: Double?,
    val massimo: Double?,
    val unita: String,
    val opzioni: List<MmOpzione>,
    val nota: String,
    val posizione: Int,
) {
    /** La riga sotto il nome nel riepilogo: «scala 1-20», «numero in kg»… */
    val descrizione: String
        get() = when (tipo) {
            TipoMisura.SCALA -> "scala ${numero(minimo)}-${numero(massimo)}"
            TipoMisura.NUMERO -> if (unita.isBlank()) "numero" else "numero in $unita"
            TipoMisura.BOOL -> "sì / no"
            TipoMisura.SCELTA -> "scelta fra ${opzioni.size} opzioni"
        }

    /**
     * Il valore archiviato come numero — i sì/no valgono 1 e 0.
     *
     * ⚠️ Una **scelta torna `null`**, come `numericValue()` nel web: l'ordine
     * delle opzioni è un elenco, non una scala, e farne una media o una
     * spezzata vorrebbe dire trattare la quarta opzione come il doppio della
     * seconda.
     */
    fun numerico(grezzo: JsonPrimitive?): Double? {
        if (grezzo == null || grezzo is JsonNull) return null
        if (tipo == TipoMisura.SCELTA) return null
        grezzo.content.toBooleanStrictOrNull()?.let { return if (it) 1.0 else 0.0 }
        return grezzo.content.toDoubleOrNull()
    }

    /**
     * Il nome dell'opzione scelta. Un'opzione tolta dopo lascia il suo id nelle
     * registrazioni: si dice che è stata tolta invece di far sparire il valore.
     */
    fun etichettaOpzione(id: String?): String =
        opzioni.firstOrNull { it.id == id }?.etichetta ?: "opzione tolta"

    /** Che cosa scrivere per un valore archiviato, qualunque sia il tipo. */
    fun etichettaValore(grezzo: JsonPrimitive?): String {
        if (grezzo == null || grezzo is JsonNull) return "—"
        if (tipo == TipoMisura.SCELTA) return etichettaOpzione(grezzo.content)
        return mostra(numerico(grezzo))
    }

    /** Come `displayValue()`: «7/10», «sì», «72,5 kg». */
    fun mostra(valore: Double?): String {
        if (valore == null) return "—"
        return when (tipo) {
            TipoMisura.BOOL -> if (valore != 0.0) "sì" else "no"
            TipoMisura.SCALA -> "${numero(valore)}/${numero(massimo)}"
            TipoMisura.SCELTA -> "—"
            TipoMisura.NUMERO -> numero(valore) + if (unita.isBlank()) "" else " $unita"
        }
    }

    companion object {
        fun da(o: JsonObject): MmMisura? {
            val id = testo(o, "id") ?: return null
            return MmMisura(
                id = id,
                nome = testo(o, "name").orEmpty(),
                tipo = TipoMisura.da(testo(o, "kind")),
                minimo = testo(o, "min_value")?.toDoubleOrNull(),
                massimo = testo(o, "max_value")?.toDoubleOrNull(),
                unita = testo(o, "unit").orEmpty(),
                opzioni = (o["options"] as? JsonArray).orEmpty().mapNotNull { voce ->
                    val riga = voce as? JsonObject ?: return@mapNotNull null
                    val oid = testo(riga, "id") ?: return@mapNotNull null
                    MmOpzione(oid, testo(riga, "label").orEmpty())
                },
                nota = testo(o, "hint").orEmpty(),
                posizione = testo(o, "position")?.toIntOrNull() ?: 0,
            )
        }
    }
}

/**
 * Una registrazione di diario (`mm_diary_entries`).
 *
 * ⚠️ In `misure` c'è **solo quello che è stato misurato davvero**: una misura
 * non toccata non compare come zero, la sua chiave non c'è proprio. «Non l'ho
 * misurata» e «vale zero» sono due cose diverse.
 */
data class MmRegistrazione(
    val id: String,
    val titolo: String,
    val data: String,
    val nota: String,
    val misure: Map<String, JsonPrimitive>,
) {
    companion object {
        fun da(o: JsonObject): MmRegistrazione? {
            val id = testo(o, "id") ?: return null
            return MmRegistrazione(
                id = id,
                titolo = testo(o, "title").orEmpty(),
                data = testo(o, "entry_date").orEmpty(),
                nota = testo(o, "note").orEmpty(),
                misure = (o["measures"] as? JsonObject).orEmpty()
                    .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it } }
                    .toMap(),
            )
        }
    }
}

/** Una foto allegata a una scheda (`mm_images` + il file nel bucket). */
data class MmImmagine(
    val id: String,
    val percorso: String,
    val nome: String,
    val mime: String?,
    /** URL firmato, riempito al caricamento: il bucket è privato. */
    val url: String = "",
) {
    companion object {
        fun da(o: JsonObject): MmImmagine? {
            val id = testo(o, "id") ?: return null
            return MmImmagine(
                id = id,
                percorso = testo(o, "storage_path").orEmpty(),
                nome = testo(o, "file_name").orEmpty(),
                mime = testo(o, "mime_type"),
            )
        }
    }
}

/** Una categoria condivisa (`cm_categories`), la stessa tabella di Tasks. */
data class CmCategoria(
    val id: String,
    val nome: String,
    val icona: String,
    val colore: String,
) {
    val etichetta: String get() = if (icona.isBlank()) nome else "$icona $nome"

    companion object {
        fun da(o: JsonObject): CmCategoria? {
            val id = testo(o, "id") ?: return null
            return CmCategoria(
                id = id,
                nome = testo(o, "name").orEmpty(),
                icona = testo(o, "icon").orEmpty(),
                colore = testo(o, "color")?.takeIf { it.isNotBlank() } ?: "#6B7280",
            )
        }
    }
}

internal fun testo(o: JsonObject, chiave: String): String? =
    (o[chiave] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * Un numero come lo scrive `fmtNum()` nel web: intero senza decimali, altrimenti
 * arrotondato al centesimo. Serve perché una scala 1-10 non si legga «7.0».
 */
internal fun numero(n: Double?): String {
    if (n == null || !n.isFinite()) return "—"
    return if (n == Math.floor(n)) n.toLong().toString()
    else (Math.round(n * 100) / 100.0).toString()
}

/**
 * I punti di un premiato come li scrive `fmtPunti()` nel web: col **segno**
 * quando sono positivi — una cifra nuda accanto a un'altra si legge come un
 * conteggio — e col singolare su ±1.
 */
internal fun puntiTesto(n: Int): String =
    (if (n > 0) "+" else "") + n + (if (Math.abs(n) == 1) " punto" else " punti")

/** Una data `YYYY-MM-DD` in europeo, come `fmtDay()`. */
internal fun giorno(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val p = iso.take(10).split('-')
    return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
}

/**
 * Data e ora nel fuso del telefono, come `fmtDate()` nel web.
 *
 * Se non si legge si mostra il valore grezzo: una data storta è meglio di una
 * scheda che non compare.
 */
private fun dataOra(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    return runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(formato)
    }.recoverCatching {
        LocalDateTime.parse(iso).format(formato)
    }.getOrDefault(iso)
}
