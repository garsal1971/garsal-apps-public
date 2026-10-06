package com.garsal.speseingiro

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Il solo modo in cui l'APK parla col database: le RPC `vg_*`, più la Edge
 * Function `spese-in-giro-foto` per gli scontrini.
 *
 * ⚠️ Qui dentro non c'è **nessuna regola**. Chi può confermare, chi può
 * modificare, come si cancella una voce e soprattutto **quanto deve l'uno
 * all'altro** vivono nel database. Due telefoni con la stessa app che si
 * calcolano il saldo per conto proprio sono due debiti diversi il giorno che
 * uno dei due si aggiorna e l'altro no — ed è la stessa scelta di
 * `task_complete` e `sos_session_finish`.
 */
object Api {

    private const val TAG = "SpeseApi"

    data class Esito(val ok: Boolean, val body: JSONObject?, val error: String?) {
        val messaggio: String get() = error ?: "Qualcosa non ha funzionato"
    }

    private fun chiama(
        url: String,
        corpo: String,
        timeout: Int = Config.HTTP_TIMEOUT_MS,
    ): Esito = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeout
            readTimeout = timeout
            doOutput = true
            setRequestProperty("apikey", Config.ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${Config.ANON_KEY}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(corpo) }

        val code = conn.responseCode
        val testo = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()

        val obj = runCatching { JSONObject(testo) }.getOrNull()
        when {
            obj == null -> Esito(false, null, "Risposta illeggibile (HTTP $code)")
            // Le RPC rispondono sempre {ok, …}: un `ok:false` porta già il suo
            // messaggio in italiano, ed è quello che va mostrato.
            !obj.optBoolean("ok", false) ->
                Esito(false, obj, obj.optString("error").ifBlank { "HTTP $code" })
            else -> Esito(true, obj, null)
        }
    } catch (e: Exception) {
        Log.w(TAG, "chiamata fallita: ${e.message}")
        Esito(false, null, e.message ?: "Rete assente")
    }

    private suspend fun rpc(nome: String, params: JSONObject): Esito = withContext(Dispatchers.IO) {
        chiama("${Config.SUPABASE_URL}/rest/v1/rpc/$nome", params.toString())
    }

    private suspend fun foto(params: JSONObject, timeout: Int = Config.UPLOAD_TIMEOUT_MS): Esito =
        withContext(Dispatchers.IO) {
            chiama("${Config.SUPABASE_URL}/functions/v1/spese-in-giro-foto", params.toString(), timeout)
        }

    /* ── accoppiamento ──────────────────────────────────────────────────── */

    suspend fun crea(viaggio: String, io: String, inizio: String?, fine: String?) = rpc(
        "vg_crea",
        JSONObject().apply {
            put("p_viaggio", viaggio); put("p_nome", io)
            put("p_inizio", inizio ?: JSONObject.NULL)
            put("p_fine", fine ?: JSONObject.NULL)
        }
    )

    suspend fun entra(codice: String, io: String) = rpc(
        "vg_entra",
        JSONObject().apply { put("p_codice", codice); put("p_nome", io) }
    )

    /* ── lettura ────────────────────────────────────────────────────────── */

    suspend fun stato(token: String) = rpc("vg_stato", JSONObject().apply { put("p_token", token) })

    /* ── scrittura ──────────────────────────────────────────────────────── */

    suspend fun salvaVoce(
        token: String,
        voceId: String?,
        tipo: String,
        importo: Double,
        data: String,
        descrizione: String,
        categoria: String,
        daId: String,
        perChi: String?,
        beneficiario: String?,
        scontrino: String?,
        letto: Double?,
    ) = rpc(
        "vg_voce_salva",
        JSONObject().apply {
            put("p_token", token)
            put("p_voce_id", voceId ?: JSONObject.NULL)
            put("p_tipo", tipo)
            put("p_importo", importo)
            put("p_data", data)
            put("p_descrizione", descrizione)
            put("p_categoria", categoria)
            put("p_da_id", daId)
            put("p_per_chi", perChi ?: JSONObject.NULL)
            put("p_beneficiario", beneficiario ?: JSONObject.NULL)
            put("p_scontrino", scontrino ?: JSONObject.NULL)
            put("p_letto", letto ?: JSONObject.NULL)
        }
    )

    /* ── categorie ──────────────────────────────────────────────────────── */

    /** Aggiunge una categoria (`id` nullo) o ne cambia emoji e nome.
     *  ⚠️ La **chiave** non si manda e non si cambia: è quella scritta nelle
     *  voci già segnate, e seguirebbe il nome lasciandole tutte agganciate a
     *  una categoria che non esiste più. */
    suspend fun salvaCategoria(token: String, id: String?, emoji: String, nome: String) = rpc(
        "vg_categoria_salva",
        JSONObject().apply {
            put("p_token", token)
            put("p_id", id ?: JSONObject.NULL)
            put("p_emoji", emoji)
            put("p_nome", nome)
        }
    )

    /** Toglie una categoria che nessuna voce usa. Chi decide se è usata è il
     *  server, che le voci le ha tutte: questo telefono no. */
    suspend fun eliminaCategoria(token: String, id: String) =
        rpc("vg_categoria_elimina", JSONObject().apply { put("p_token", token); put("p_id", id) })

    suspend fun conferma(token: String, voceId: String) =
        rpc("vg_voce_conferma", JSONObject().apply { put("p_token", token); put("p_voce_id", voceId) })

    suspend fun elimina(token: String, voceId: String) =
        rpc("vg_voce_elimina", JSONObject().apply { put("p_token", token); put("p_voce_id", voceId) })

    suspend fun chiediCancellazione(token: String, voceId: String, motivo: String) = rpc(
        "vg_voce_chiedi_cancellazione",
        JSONObject().apply { put("p_token", token); put("p_voce_id", voceId); put("p_motivo", motivo) }
    )

    suspend fun risolviCancellazione(token: String, voceId: String, approva: Boolean) = rpc(
        "vg_voce_risolvi_cancellazione",
        JSONObject().apply { put("p_token", token); put("p_voce_id", voceId); put("p_approva", approva) }
    )

    /* ── scontrini ──────────────────────────────────────────────────────── */

    /** Carica la foto e restituisce il percorso da attaccare alla voce.
     *  ⚠️ Il percorso lo decide la Edge Function, dentro la cartella del
     *  viaggio: un percorso scelto dal telefono sarebbe il modo di scrivere
     *  nella cartella di un altro. */
    suspend fun caricaScontrino(token: String, jpeg: ByteArray): Esito = foto(
        JSONObject().apply {
            put("azione", "carica")
            put("token", token)
            put("tipo", "image/jpeg")
            put("dati", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        }
    )

    /** L'URL firmato per guardare lo scontrino di una voce (un'ora). Si passa
     *  l'id della voce e non il percorso: è il database a dire dove sta quella
     *  foto e a rifiutare una voce di un altro viaggio. */
    suspend fun urlScontrino(token: String, voceId: String): String? {
        val e = foto(
            JSONObject().apply { put("azione", "leggi"); put("token", token); put("voce_id", voceId) },
            Config.HTTP_TIMEOUT_MS,
        )
        return if (e.ok) e.body?.optString("url")?.ifBlank { null } else null
    }

    /** Toglie una foto caricata e poi rifatta prima di salvare la voce: senza,
     *  resterebbe nel bucket senza nessuna riga che dica cos'è. */
    suspend fun cancellaScontrino(token: String, path: String) = foto(
        JSONObject().apply { put("azione", "cancella"); put("token", token); put("path", path) },
        Config.HTTP_TIMEOUT_MS,
    )

    /** I byte di un'immagine già firmata: il visore la mostra dentro l'app. */
    suspend fun scarica(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { URL(url).openStream().use { it.readBytes() } }.getOrNull()
    }
}
