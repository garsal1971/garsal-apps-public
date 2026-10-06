package com.garsal.appsphere.frz

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.crypto.spec.SecretKeySpec

// ── Perché qui non ci sono @Serializable data class ──────────────────────────
//
// Le `frz_*` una migration ce l'hanno, ma la scelta resta quella di `ts_tasks`
// e di Memo: si leggono come `JsonObject`. Il motivo qui è più stretto — quasi
// tutto quel che conta sta **dentro `meta_enc`**, cioè in un blob cifrato che
// nessun decoder può guardare, e le colonne in chiaro sono quattro id e un
// numero. Una data class avrebbe fatto fallire l'intera lista per una colonna
// aggiunta domani, in cambio di niente.

internal fun testo(o: JsonObject, chiave: String): String? =
    (o[chiave] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
        ?.takeIf { it != "null" }

internal fun intero(o: JsonObject, chiave: String): Long =
    (o[chiave] as? JsonPrimitive)?.longOrNull ?: 0L

/**
 * La riga di `frz_vault`: dov'è la cartella su Drive e i suoi due oggetti di
 * servizio.
 *
 * ⚠️ Sono **id, non credenziali**: senza il login Google non aprono niente, e
 * senza le 24 parole non direbbero niente comunque.
 */
data class FrzVault(
    val cartella: String?,
    val indiceId: String?,
    val scorciatoiaId: String?,
) {
    /** Un forziere si apre solo se i due oggetti di servizio ci sono. */
    val completo: Boolean get() = !indiceId.isNullOrBlank() && !scorciatoiaId.isNullOrBlank()

    companion object {
        fun da(o: JsonObject) = FrzVault(
            cartella = testo(o, "drive_folder_id"),
            indiceId = testo(o, "drive_indice_id"),
            scorciatoiaId = testo(o, "drive_scorciatoia_id"),
        )
    }
}

/**
 * Quel che `frz_files.meta_enc` porta dentro: **nome, tipo, dimensione vera e
 * data**, tutto cifrato.
 *
 * ⚠️ Le chiavi sono quelle che scrive `caricaFile()` in `forziere.html`
 * (`{ nome, tipo, size, il }`) e non si toccano da una parte sola: un nome
 * scritto sotto un'altra chiave sarebbe un file che dall'altra implementazione
 * si legge «senza nome», senza nessun errore.
 */
data class FrzMeta(
    val nome: String,
    val tipo: String,
    val size: Long?,
    val il: String?,
) {
    fun json(): JsonObject = buildJsonObject {
        put("nome", nome)
        put("tipo", tipo)
        if (size != null) put("size", size)
        if (il != null) put("il", il)
    }

    companion object {
        val ILLEGGIBILE = FrzMeta("(nome illeggibile)", "", null, null)

        fun da(o: JsonObject) = FrzMeta(
            nome = testo(o, "nome") ?: "",
            tipo = testo(o, "tipo") ?: "",
            size = (o["size"] as? JsonPrimitive)?.longOrNull,
            il = testo(o, "il"),
        )
    }
}

/** Uno scomparto: `frz_boxes` più il suo nome, che si legge solo a forziere aperto. */
data class FrzScomparto(
    val id: String,
    val nome: String,
    val emoji: String,
    val cartella: String?,
    val position: Long,
)

/** Un documento: la riga di `frz_files` più i metadati aperti. */
data class FrzDocumento(
    val id: String,
    val driveFileId: String,
    val pesoCifrato: Long,
    val boxId: String?,
    val creatoIl: String?,
    val meta: FrzMeta,
) {
    val leggibile: Boolean get() = meta !== FrzMeta.ILLEGGIBILE
}

/**
 * La chiave dell'indice e le 24 parole: quel che tiene aperto il forziere.
 *
 * ⚠️ Vive **solo in memoria**, come sul web. Niente preferenze, niente file:
 * ritrovarlo aperto al risveglio dell'app sarebbe l'opposto di quel che il
 * forziere fa.
 */
class ForziereAperto(val parole: String, val chiave: SecretKeySpec)
