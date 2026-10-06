package com.garsal.appsphere.memo

import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

/**
 * Le schede di Memo, le loro categorie e le loro foto — le stesse tabelle di
 * `memo.html` e lo stesso ordine di operazioni.
 *
 * ⚠️ Due cose che **sono** la funzionalità e vanno cambiate nelle due
 * implementazioni insieme:
 *
 * - **le categorie si riscrivono da capo a ogni salvataggio** (cancella tutte
 *   le righe di `mm_card_categories` della scheda, poi reinserisce quelle
 *   scelte). È quello che fa `saveCard()`: senza la cancellazione una
 *   categoria tolta resterebbe attaccata alla scheda per sempre;
 * - **cancellando una scheda si cancellano prima i file dal bucket**, e solo
 *   dopo la riga. `mm_images` sparisce da sé (`ON DELETE CASCADE`), ma il
 *   bucket non sa niente di quel vincolo: nell'ordine inverso i file
 *   resterebbero lì senza più nessuna riga che dica dove sono.
 */
object MemoRepository {

    private const val BUCKET = "mm-images"

    private val db get() = Supabase.client().postgrest
    private val bucket get() = Supabase.client().storage.from(BUCKET)

    /**
     * Le schede con le categorie e i **conteggi** — foto, voci di lista,
     * registrazioni di diario — in un colpo solo come `loadCards()`: fissate
     * prima, poi le più recenti.
     *
     * ⚠️ **Il filtro sulle riservate sta nella query, non nel disegno.** Fuori
     * dalla modalità nascosta la riga non si legge proprio: nasconderla solo a
     * schermo la lascerebbe in chiaro a chiunque guardi il traffico o la
     * memoria dell'app, che è esattamente quello da cui la modalità protegge.
     *
     * @param modalitaNascosta la modalità è accesa: si può vedere tutto
     * @param soloRiservate il filtro 👁 è alzato: **solo** le riservate
     */
    suspend fun schede(
        modalitaNascosta: Boolean,
        soloRiservate: Boolean,
    ): List<MmScheda> = withContext(Dispatchers.IO) {
        db.from("mm_cards")
            .select(
                Columns.raw(
                    "*,mm_card_categories(category_id),mm_images(id)," +
                        "mm_list_items(id,done),mm_diary_entries(id,entry_date)," +
                        "mm_attachments(id,url,tipo)"
                )
            ) {
                filter {
                    when {
                        soloRiservate -> eq("riservato", true)
                        // Il web scrive `riservato.eq.false,riservato.is.null`:
                        // il ramo dei NULL è prudenza, perché la colonna nasce
                        // `NOT NULL DEFAULT false` e una riga senza valore non
                        // può esistere. Qui basta l'uguaglianza.
                        !modalitaNascosta -> eq("riservato", false)
                        else -> Unit
                    }
                }
                order("pinned", Order.DESCENDING)
                order("updated_at", Order.DESCENDING)
            }
            .decodeList<JsonObject>()
            .mapNotNull { MmScheda.da(it) }
    }

    // ── Voci di lista ───────────────────────────────────────────────────────

    suspend fun voci(schedaId: String): List<MmVoce> = withContext(Dispatchers.IO) {
        db.from("mm_list_items")
            .select {
                filter { eq("card_id", schedaId) }
                order("position", Order.ASCENDING)
                order("created_at", Order.ASCENDING)
            }
            .decodeList<JsonObject>()
            .mapNotNull { MmVoce.da(it) }
    }

    /** La spunta di una voce, col suo `done_at` — è l'unica cosa che cambia. */
    suspend fun spuntaVoce(voceId: String, fatta: Boolean) = withContext(Dispatchers.IO) {
        db.from("mm_list_items").update(
            buildJsonObject {
                put("done", fatta)
                if (fatta) put("done_at", Instant.now().toString()) else put("done_at", JsonNull)
            }
        ) { filter { eq("id", voceId) } }
        Unit
    }

    suspend fun aggiungiVoce(schedaId: String, testo: String, posizione: Int): MmVoce =
        withContext(Dispatchers.IO) {
            val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
            db.from("mm_list_items").insert(
                buildJsonObject {
                    put("card_id", schedaId)
                    put("user_id", utente)
                    put("text", testo)
                    put("position", posizione)
                }
            ) { select(Columns.raw("*")) }
                .decodeList<JsonObject>()
                .firstNotNullOfOrNull { MmVoce.da(it) }
                ?: error("La voce non è stata creata.")
        }

    suspend fun eliminaVoce(voceId: String) = withContext(Dispatchers.IO) {
        db.from("mm_list_items").delete { filter { eq("id", voceId) } }
        Unit
    }

    /**
     * Riscrive le voci di una scheda dall'editor: cancella quelle tolte,
     * aggiorna riga per riga quelle che c'erano, inserisce le nuove.
     *
     * ⚠️ Non si cancella tutto per ricreare: le voci portano `created_at` e
     * `done_at`, e ricreandole si perderebbe quando una cosa è stata fatta.
     */
    suspend fun salvaVoci(
        schedaId: String,
        voci: List<VoceInModifica>,
        daCancellare: List<String>,
    ) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
        if (daCancellare.isNotEmpty()) {
            db.from("mm_list_items").delete { filter { isIn("id", daCancellare) } }
        }
        voci.forEachIndexed { i, voce ->
            val testoPulito = voce.testo.trim()
            val riga = buildJsonObject {
                put("card_id", schedaId)
                put("user_id", utente)
                put("text", testoPulito)
                put("done", voce.fatta)
                put("position", i)
                voce.fattaIl?.let { put("done_at", it) } ?: put("done_at", JsonNull)
            }
            val id = voce.id
            if (id != null) {
                db.from("mm_list_items").update(riga) { filter { eq("id", id) } }
            } else if (testoPulito.isNotBlank()) {
                db.from("mm_list_items").insert(riga)
            }
        }
        Unit
    }

    // ── Misure e registrazioni di un diario ─────────────────────────────────

    suspend fun misure(schedaId: String): List<MmMisura> = withContext(Dispatchers.IO) {
        db.from("mm_diary_metrics")
            .select {
                filter { eq("card_id", schedaId) }
                order("position", Order.ASCENDING)
                order("created_at", Order.ASCENDING)
            }
            .decodeList<JsonObject>()
            .mapNotNull { MmMisura.da(it) }
    }

    suspend fun registrazioni(schedaId: String): List<MmRegistrazione> =
        withContext(Dispatchers.IO) {
            db.from("mm_diary_entries")
                .select {
                    filter { eq("card_id", schedaId) }
                    order("entry_date", Order.DESCENDING)
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<JsonObject>()
                .mapNotNull { MmRegistrazione.da(it) }
        }

    /**
     * Riscrive le misure di un diario, **riga per riga**.
     *
     * ⚠️ È la regola di `syncDiaryMetrics()` e non è un dettaglio: cancellare e
     * ricreare cambierebbe gli id, e ogni registrazione passata — che quegli id
     * li cita dentro `measures` — resterebbe senza il nome della misura.
     */
    suspend fun salvaMisure(
        schedaId: String,
        misure: List<MisuraInModifica>,
        daCancellare: List<String>,
    ) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
        if (daCancellare.isNotEmpty()) {
            db.from("mm_diary_metrics").delete { filter { isIn("id", daCancellare) } }
        }
        misure.forEachIndexed { i, m ->
            val riga = buildJsonObject {
                put("card_id", schedaId)
                put("user_id", utente)
                put("name", m.nome.trim())
                put("kind", m.tipo.chiave)
                if (m.tipo == TipoMisura.SCALA) {
                    put("min_value", m.minimo)
                    put("max_value", m.massimo)
                } else {
                    put("min_value", JsonNull)
                    put("max_value", JsonNull)
                }
                put("unit", if (m.tipo == TipoMisura.NUMERO) m.unita.trim() else "")
                put("hint", m.nota.trim())
                put("options", buildJsonArray {
                    if (m.tipo == TipoMisura.SCELTA) {
                        m.opzioni.forEach { o ->
                            add(buildJsonObject {
                                put("id", o.id)
                                put("label", o.etichetta.trim())
                            })
                        }
                    }
                })
                put("position", i)
            }
            val id = m.id
            if (id != null) {
                db.from("mm_diary_metrics").update(riga) { filter { eq("id", id) } }
            } else {
                db.from("mm_diary_metrics").insert(riga)
            }
        }
        Unit
    }

    /**
     * Scrive una registrazione. `misure` contiene **solo** quello che è stato
     * misurato: una misura non toccata non ha una chiave, e non ne prende una
     * a zero.
     */
    suspend fun salvaRegistrazione(
        id: String?,
        schedaId: String,
        titolo: String,
        data: String,
        nota: String,
        misure: Map<String, JsonPrimitive>,
    ) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
        val riga = buildJsonObject {
            put("card_id", schedaId)
            put("user_id", utente)
            put("title", titolo)
            put("entry_date", data)
            put("note", nota)
            put("measures", JsonObject(misure))
        }
        if (id != null) {
            db.from("mm_diary_entries").update(riga) { filter { eq("id", id) } }
        } else {
            db.from("mm_diary_entries").insert(riga)
        }
        Unit
    }

    suspend fun eliminaRegistrazione(id: String) = withContext(Dispatchers.IO) {
        db.from("mm_diary_entries").delete { filter { eq("id", id) } }
        Unit
    }

    /** Le categorie condivise, le stesse che usano Tasks e Habit Tracker. */
    suspend fun categorie(): List<CmCategoria> = withContext(Dispatchers.IO) {
        db.from("cm_categories")
            .select(Columns.raw("id,name,icon,color")) { order("name", Order.ASCENDING) }
            .decodeList<JsonObject>()
            .mapNotNull { CmCategoria.da(it) }
    }

    /**
     * Le foto di una scheda, ciascuna col suo URL firmato: il bucket è
     * privato, un URL diretto darebbe 400 a chiunque.
     *
     * Le due ore di validità sono quelle del web. Una firma scaduta non è un
     * dramma — si riapre la scheda e se ne chiede un'altra — ma vale la pena
     * ricordarsene se un giorno le foto smettono di comparire dopo che l'app è
     * rimasta aperta mezza giornata.
     */
    suspend fun immagini(schedaId: String): List<MmImmagine> = withContext(Dispatchers.IO) {
        db.from("mm_images")
            .select(Columns.raw("id,storage_path,file_name,mime_type")) {
                filter { eq("card_id", schedaId) }
                order("created_at", Order.ASCENDING)
            }
            .decodeList<JsonObject>()
            .mapNotNull { MmImmagine.da(it) }
            .map { img ->
                val url = runCatching { bucket.createSignedUrl(img.percorso, 2.hours) }
                    .getOrDefault("")
                img.copy(url = url)
            }
    }

    /**
     * Crea o aggiorna una scheda e riscrive le sue categorie. Torna l'id, che
     * per una scheda nuova serve subito dopo per caricarci le foto.
     */
    suspend fun salva(
        id: String?,
        titolo: String,
        contenutoHtml: String,
        tipo: TipoScheda,
        riservato: Boolean,
        fissata: Boolean,
        colore: String,
        categorie: List<String>,
        punteggio: Int,
    ): String = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
        val adesso = Instant.now().toString()

        val riga = buildJsonObject {
            put("title", titolo)
            put("content", contenutoHtml)
            put("kind", tipo.chiave)
            // ⚠️ La spunta si legge **solo dove si vede**: su una nota o una
            // lista il campo non c'è, e il form manda sempre `false`. Vale la
            // stessa regola di `saveCard()`, che la considera solo per i diari.
            put("riservato", riservato)
            put("pinned", fissata)
            put("color", colore)
            // ⚠️ Si scrive SEMPRE, e vale 0 fuori dai 🏅 premiati: l'update
            // riscrive la riga intera, e una scheda che cambiasse tipo si
            // porterebbe dietro punti che nessuno vede più — ma che nella
            // bolla di AppSphere continuerebbero a contare.
            put("punteggio", punteggio)
            put("updated_at", adesso)
            put("user_id", utente)
        }

        val schedaId = if (id != null) {
            db.from("mm_cards").update(riga) { filter { eq("id", id) } }
            id
        } else {
            val creata = buildJsonObject {
                riga.forEach { (k, v) -> put(k, v) }
                put("created_at", adesso)
            }
            db.from("mm_cards").insert(creata) { select(Columns.raw("id")) }
                .decodeList<JsonObject>()
                .firstOrNull()
                ?.let { testo(it, "id") }
                ?: error("La scheda non ha restituito un id.")
        }

        db.from("mm_card_categories").delete { filter { eq("card_id", schedaId) } }
        if (categorie.isNotEmpty()) {
            db.from("mm_card_categories").insert(
                categorie.map { catId ->
                    buildJsonObject {
                        put("card_id", schedaId)
                        put("category_id", catId)
                    }
                }
            )
        }

        schedaId
    }

    /**
     * L'indirizzo di un 🔗 Link: una scheda, un allegato.
     *
     * ⚠️ Gemella di `syncLinkAttachment()` in `memo.html`. Aggiorna la riga se
     * c'è e la crea se non c'è — **non cancella e ricrea**, perché il giorno
     * che gli allegati saranno più d'uno l'ordine (`position`) dovrà
     * sopravvivere al salvataggio.
     */
    suspend fun salvaLink(schedaId: String, url: String) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
        val esistente = db.from("mm_attachments")
            .select(Columns.raw("id")) {
                filter {
                    eq("card_id", schedaId)
                    eq("tipo", "link")
                }
                limit(1)
            }
            .decodeList<JsonObject>()
            .firstOrNull()
            ?.let { testo(it, "id") }

        if (esistente != null) {
            db.from("mm_attachments").update(buildJsonObject { put("url", url) }) {
                filter { eq("id", esistente) }
            }
        } else {
            db.from("mm_attachments").insert(buildJsonObject {
                put("card_id", schedaId)
                put("user_id", utente)
                put("tipo", "link")
                put("url", url)
                put("position", 0)
            })
        }
        Unit
    }

    /** Cancella la scheda: prima i file nel bucket, poi la riga. */
    suspend fun elimina(schedaId: String) = withContext(Dispatchers.IO) {
        val percorsi = immagini(schedaId).map { it.percorso }.filter { it.isNotBlank() }
        if (percorsi.isNotEmpty()) runCatching { bucket.delete(percorsi) }
        db.from("mm_cards").delete { filter { eq("id", schedaId) } }
        Unit
    }

    /**
     * Carica una foto nel bucket e ne registra la riga.
     *
     * Il percorso è quello del web — `utente/scheda/istante_casuale.est` —
     * perché le policy dello Storage guardano la prima cartella per capire di
     * chi è il file.
     */
    suspend fun caricaImmagine(
        schedaId: String,
        dati: ByteArray,
        nomeFile: String,
        mime: String,
    ) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
        val estensione = nomeFile.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() && it.length <= 5 }
            ?: mime.substringAfter('/', "jpg")
        val nome = "${System.currentTimeMillis()}_${Random.nextInt(0, 1_000_000)}.$estensione"
        val percorso = "$utente/$schedaId/$nome"

        bucket.upload(percorso, dati) { upsert = false }

        db.from("mm_images").insert(
            buildJsonObject {
                put("card_id", schedaId)
                put("user_id", utente)
                put("storage_path", percorso)
                put("file_name", nome)
                put("mime_type", mime)
            }
        )
        Unit
    }

    /** Toglie una foto: prima il file, poi la riga, come per la scheda. */
    suspend fun eliminaImmagine(immagine: MmImmagine) = withContext(Dispatchers.IO) {
        if (immagine.percorso.isNotBlank()) runCatching { bucket.delete(listOf(immagine.percorso)) }
        db.from("mm_images").delete { filter { eq("id", immagine.id) } }
        Unit
    }
}
