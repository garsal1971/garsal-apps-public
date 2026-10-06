package com.garsal.appsphere.frz

import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * Le quattro tabelle del Forziere, lette esattamente come le scrive
 * `forziere.html`.
 *
 * ⚠️ **Quel che conta non sta nelle colonne.** Nome, tipo, dimensione vera e
 * note di un documento vivono tutti dentro `meta_enc`, cifrato; il nome di uno
 * scomparto pure. Da questa parte, quindi, un elenco si legge in due tempi: le
 * righe dal database e i nomi dalla chiave dell'indice — che esiste solo a
 * forziere aperto.
 *
 * ⚠️ **Una riga che non si decifra non fa saltare l'elenco**: si mostra e lo
 * dice, come nella pagina. Sparire sarebbe il modo peggiore di segnalare un
 * problema — un file in meno e nessuno che spieghi perché.
 */
object ForziereRepository {

    private val db get() = Supabase.client().postgrest
    private val json = Json { ignoreUnknownKeys = true }

    private fun utente(): String =
        AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")

    // ── Il forziere ──────────────────────────────────────────────────────────

    suspend fun vault(): FrzVault? = withContext(Dispatchers.IO) {
        db.from("frz_vault").select { filter { eq("user_id", utente()) } }
            .decodeList<JsonObject>()
            .firstOrNull()
            ?.let { FrzVault.da(it) }
    }

    // ── Scomparti e documenti ────────────────────────────────────────────────

    suspend fun scomparti(chiave: SecretKeySpec): List<FrzScomparto> = withContext(Dispatchers.IO) {
        db.from("frz_boxes").select {
            filter { eq("user_id", utente()) }
            order("position", Order.ASCENDING)
            order("creato_il", Order.ASCENDING)
        }.decodeList<JsonObject>().map { riga ->
            val meta = runCatching {
                json.parseToJsonElement(
                    ForziereCripto.decifraTesto(chiave, testo(riga, "meta_enc") ?: "")
                ) as JsonObject
            }.getOrNull()
            FrzScomparto(
                id = testo(riga, "id") ?: "",
                nome = meta?.let { testo(it, "nome") } ?: "(nome illeggibile)",
                emoji = meta?.let { testo(it, "emoji") } ?: "⚠️",
                cartella = testo(riga, "drive_folder_id"),
                position = intero(riga, "position"),
            )
        }
    }

    suspend fun documenti(chiave: SecretKeySpec): List<FrzDocumento> = withContext(Dispatchers.IO) {
        db.from("frz_files").select {
            filter { eq("user_id", utente()) }
            order("creato_il", Order.DESCENDING)
        }.decodeList<JsonObject>().map { riga -> documento(riga, chiave) }
    }

    private fun documento(riga: JsonObject, chiave: SecretKeySpec): FrzDocumento {
        val meta = runCatching {
            FrzMeta.da(
                json.parseToJsonElement(
                    ForziereCripto.decifraTesto(chiave, testo(riga, "meta_enc") ?: "")
                ) as JsonObject
            )
        }.getOrDefault(FrzMeta.ILLEGGIBILE)
        return FrzDocumento(
            id = testo(riga, "id") ?: "",
            driveFileId = testo(riga, "drive_file_id") ?: "",
            pesoCifrato = intero(riga, "size_bytes"),
            boxId = testo(riga, "box_id"),
            creatoIl = testo(riga, "creato_il"),
            meta = meta,
        )
    }

    /**
     * Le miniature, cifrate come i metadati. ⚠️ Una miniatura illeggibile si
     * salta e basta: è una comodità, non un dato — e l'elenco non si ferma per
     * lei, esattamente come nella pagina.
     */
    suspend fun miniature(ids: List<String>, chiave: SecretKeySpec): Map<String, ByteArray> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyMap()
            db.from("frz_thumbs").select(Columns.raw("file_id,thumb_enc")) {
                filter { isIn("file_id", ids) }
            }.decodeList<JsonObject>().mapNotNull { riga ->
                val id = testo(riga, "file_id") ?: return@mapNotNull null
                val enc = testo(riga, "thumb_enc") ?: return@mapNotNull null
                runCatching { id to ForziereCripto.decifraBytes(chiave, enc) }.getOrNull()
            }.toMap()
        }

    // ── Mettere dentro un documento ──────────────────────────────────────────

    /**
     * La cartella su Drive di uno scomparto, creandola se non c'è.
     *
     * ⚠️ È qui che `drive_folder_id` passa da NULL a un id, e NULL non è un dato
     * mancante: è «la cartella non l'ha ancora fatta nessuno». Il nome della
     * cartella è un **uuid** e non quello dello scomparto — quello resta cifrato
     * in `scomparti.gpg`, o il Drive racconterebbe da solo la storia che
     * `meta_enc` esiste per non raccontare.
     */
    suspend fun assicuraCartella(box: FrzScomparto?): String = withContext(Dispatchers.IO) {
        if (box == null) return@withContext ""
        if (!box.cartella.isNullOrBlank()) return@withContext box.cartella
        val id = ForziereDrive.mkdir(UUID.randomUUID().toString())
        db.from("frz_boxes").update(buildJsonObject { put("drive_folder_id", id) }) {
            filter { eq("id", box.id) }
        }
        id
    }

    /** La riga del documento appena caricato. */
    suspend fun inserisci(
        id: String,
        driveFileId: String,
        meta: FrzMeta,
        pesoCifrato: Long,
        boxId: String?,
        chiave: SecretKeySpec,
    ): FrzDocumento = withContext(Dispatchers.IO) {
        val riga = buildJsonObject {
            put("id", id)
            put("user_id", utente())
            put("drive_file_id", driveFileId)
            put("meta_enc", ForziereCripto.cifraTesto(chiave, meta.json().toString()))
            put("size_bytes", pesoCifrato)
            if (boxId != null) put("box_id", boxId) else put("box_id", JsonNull)
        }
        db.from("frz_files").insert(riga) { select(Columns.raw("*")) }
            .decodeList<JsonObject>()
            .firstOrNull()
            ?.let { documento(it, chiave) }
            ?: error("La riga del documento non è stata creata.")
    }

    /**
     * La miniatura di un'immagine appena caricata.
     *
     * ⚠️ Tabella a parte perché l'elenco si legge a ogni apertura e deve restare
     * leggero: `frz_thumbs` si carica dopo, e una miniatura che non entra non è
     * un motivo per far fallire un caricamento riuscito.
     */
    suspend fun inserisciMiniatura(fileId: String, mini: ByteArray, chiave: SecretKeySpec) =
        withContext(Dispatchers.IO) {
            db.from("frz_thumbs").insert(
                buildJsonObject {
                    put("file_id", fileId)
                    put("user_id", utente())
                    put("thumb_enc", ForziereCripto.cifraBytes(chiave, mini))
                }
            )
            Unit
        }

    // ── Gli indici su Drive ──────────────────────────────────────────────────
    //
    // ⚠️ **La verità resta il DATABASE.** Gli indici sono una copia, riscritta
    // dopo ogni caricamento, perché il Drive da solo basti a orientarsi il
    // giorno che il database non ci fosse più. Sono una comodità: senza, i
    // documenti si aprono lo stesso e ciascuno porta dentro il proprio nome.
    //
    // ⚠️ E per la stessa ragione **non bloccano e non fanno fallire
    // l'operazione vera**: quando arriva qui il file è già caricato e la riga
    // già scritta. Un indice che non si riscrive è una comodità in meno, non un
    // dato perso — quindi si segnala e basta, come `scriviIndici()` nella
    // pagina.

    private const val IDX_SCOMPARTI = "scomparti.gpg"
    private const val IDX_CONTENUTO = "contenuto.gpg"

    // ── Buttare via un documento ─────────────────────────────────────────────

    /**
     * Toglie la riga di `frz_files`.
     *
     * ⚠️ **La miniatura se ne va da sé**: `frz_thumbs.file_id` è
     * `ON DELETE CASCADE`, quindi cancellarla a parte sarebbe una seconda
     * scrittura che può fallire per conto suo — ed è la stessa ragione per cui
     * `elimina()` nella pagina non la nomina.
     *
     * ⚠️ Il filtro porta anche `user_id`, ridondante rispetto alla RLS e voluto:
     * una DELETE che si regge su una sola condizione è una DELETE che il giorno
     * che una policy cambia cancella più di quel che dice. È la stessa scelta
     * dei DELETE di Obiettivi.
     */
    suspend fun cancella(id: String) = withContext(Dispatchers.IO) {
        db.from("frz_files").delete { filter { eq("id", id); eq("user_id", utente()) } }
        Unit
    }

    suspend fun scriviIndiceContenuto(
        box: FrzScomparto?,
        documenti: List<FrzDocumento>,
        parole: String,
    ) = withContext(Dispatchers.IO) {
        val cart = box?.cartella ?: ""
        if (box != null && cart.isBlank()) return@withContext  // scomparto senza cartella
        val payload = buildJsonObject {
            put("v", 1)
            put("il", Instant.now().toString())
            if (box == null) put("scomparto", JsonNull) else put("scomparto", buildJsonObject {
                put("id", box.id); put("nome", box.nome); put("emoji", box.emoji)
            })
            // ⚠️ `file` è il NOME su Drive, non l'id di Drive: chi recupera ha in
            // mano una cartella di file, non l'API di Google.
            put("file", buildJsonArray {
                documenti.filter { it.boxId == box?.id }.forEach { d ->
                    add(buildJsonObject {
                        put("file", d.id + ".gpg")
                        put("nome", d.meta.nome)
                        put("tipo", d.meta.tipo)
                        if (d.meta.size == null) put("size", JsonNull) else put("size", d.meta.size)
                        put("il", d.meta.il ?: d.creatoIl ?: "")
                    })
                }
            })
        }
        ForziereDrive.put(
            IDX_CONTENUTO,
            ForzierePgp.cifraTesto(payload.toString(), parole),
            cartella = cart,
            perNome = true,
        )
        Unit
    }

    suspend fun scriviIndiceScomparti(scomparti: List<FrzScomparto>, parole: String) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("v", 1)
                put("il", Instant.now().toString())
                put("scomparti", buildJsonArray {
                    scomparti.forEach { b ->
                        add(buildJsonObject {
                            put("id", b.id); put("nome", b.nome); put("emoji", b.emoji)
                            if (b.cartella == null) put("cartella", JsonNull) else put("cartella", b.cartella)
                        })
                    }
                })
            }
            ForziereDrive.put(
                IDX_SCOMPARTI,
                ForzierePgp.cifraTesto(payload.toString(), parole),
                perNome = true,
            )
            Unit
        }
}
