package com.garsal.sos

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Il solo modo in cui l'APK parla col database: quattro RPC, nessuna tabella.
 *
 * ⚠️ Punti e durata del giro successivo non si calcolano qui. Li decide
 * sos_session_finish() lato server — è la stessa regola di task_complete /
 * sf_finalize_challenge, e per la stessa ragione: la pagina web e il telefono
 * darebbero due numeri diversi il giorno che una delle due copie cambia.
 */
class SosApi(private val ctx: Context) {

    data class Esito(val ok: Boolean, val body: JSONObject?, val error: String?)

    private fun rpc(name: String, params: JSONObject): Esito {
        return try {
            val conn = (URL("${Config.SUPABASE_URL}/rest/v1/rpc/$name").openConnection()
                    as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = Config.HTTP_TIMEOUT_MS
                readTimeout = Config.HTTP_TIMEOUT_MS
                doOutput = true
                setRequestProperty("apikey", Config.ANON_KEY)
                setRequestProperty("Authorization", "Bearer ${Config.ANON_KEY}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(params.toString()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                Log.w(TAG, "$name HTTP $code: $err")
                return Esito(false, null, "HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val obj = JSONObject(text)
            if (!obj.optBoolean("ok", false)) {
                Esito(false, obj, obj.optString("error", "risposta non valida"))
            } else {
                Esito(true, obj, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$name fallita: ${e.message}")
            Esito(false, null, e.message ?: "rete assente")
        }
    }

    private fun token() = Prefs.getToken(ctx)

    // ── Configurazione ───────────────────────────────────────────────────────

    /** Rilegge i SOS e li mette in cache. Torna la lista, o null se non è andata. */
    fun caricaConfig(): List<SosType>? {
        val t = token()
        if (t.isBlank()) return null
        val e = rpc("sos_config", JSONObject().put("p_token", t))
        if (!e.ok || e.body == null) return null
        Prefs.setConfig(ctx, e.body.toString())
        Prefs.setConfigAt(ctx, System.currentTimeMillis())
        return Model.parseTypes(e.body)
    }

    /** I SOS come li conosce il telefono adesso, senza toccare la rete. */
    fun configInCache(): List<SosType> {
        val raw = Prefs.getConfig(ctx)
        if (raw.isBlank()) return emptyList()
        return try { Model.parseTypes(JSONObject(raw)) } catch (_: Exception) { emptyList() }
    }

    /** Verifica un codice appena digitato: se risponde, è buono e la config è già scaricata. */
    fun provaCodice(codice: String): Esito {
        val e = rpc("sos_config", JSONObject().put("p_token", codice.trim().uppercase()))
        if (e.ok && e.body != null) {
            Prefs.setToken(ctx, codice)
            Prefs.setConfig(ctx, e.body.toString())
            Prefs.setConfigAt(ctx, System.currentTimeMillis())
        }
        return e
    }

    // ── Il giro ──────────────────────────────────────────────────────────────

    data class Avvio(val sessionId: String?, val seconds: Int?)

    /** Apre il giro. Il countdown è già partito quando questa viene chiamata:
        se non risponde si va avanti lo stesso e il giro si spedisce alla fine
        con sos_session_log. */
    fun avviaGiro(typeId: String): Avvio {
        val t = token()
        if (t.isBlank()) return Avvio(null, null)
        val e = rpc("sos_session_start", JSONObject()
            .put("p_token", t).put("p_type_id", typeId).put("p_source", "android"))
        if (!e.ok || e.body == null) return Avvio(null, null)
        return Avvio(e.body.optString("session_id", "").ifBlank { null },
                     if (e.body.has("seconds")) e.body.optInt("seconds") else null)
    }

    data class Chiusura(val ok: Boolean, val points: Int, val secondsNext: Int, val inCoda: Boolean)

    /**
     * Chiude il giro con la risposta scelta. Se la chiamata non passa il giro
     * finisce in coda e viene rispedito alla prossima apertura dell'app: i punti
     * di una serata difficile non si perdono perché mancava la linea.
     */
    fun chiudiGiro(
        sessionId: String?,
        typeId: String,
        outcomeId: String,
        completato: Boolean,
        trascorsi: Int,
        previsti: Int,
        avvioMs: Long
    ): Chiusura {
        val t = token()
        val params: JSONObject
        val nome: String
        if (sessionId != null) {
            nome = "sos_session_finish"
            params = JSONObject()
                .put("p_token", t)
                .put("p_session_id", sessionId)
                .put("p_outcome_id", outcomeId)
                .put("p_completed", completato)
                .put("p_elapsed", trascorsi)
        } else {
            nome = "sos_session_log"
            params = JSONObject()
                .put("p_token", t)
                .put("p_type_id", typeId)
                .put("p_outcome_id", outcomeId)
                .put("p_started_at", Instant.ofEpochMilli(avvioMs).toString())
                .put("p_planned", previsti)
                .put("p_elapsed", trascorsi)
                .put("p_completed", completato)
        }

        val e = rpc(nome, params)
        if (e.ok && e.body != null) {
            return Chiusura(true, e.body.optInt("points", 0),
                            e.body.optInt("seconds_next", previsti), false)
        }

        // ⚠️ In coda va solo ciò che può ancora andare a buon fine: un errore di
        // merito (codice revocato, risposta cancellata dalla configurazione)
        // fallirebbe identico ad ogni rinvio e resterebbe lì per sempre. Si
        // riprova solo quando è la rete a non aver risposto — cioè quando il
        // server non ha detto niente (body nullo).
        if (e.body == null) {
            Prefs.addPending(ctx, JSONObject().put("rpc", nome).put("params", params))
            return Chiusura(false, 0, previsti, true)
        }
        return Chiusura(false, 0, previsti, false)
    }

    // ── Coda ────────────────────────────────────────────────────────────────

    /** Rispedisce i giri rimasti indietro. Va chiamata fuori dal thread principale. */
    fun svuotaCoda(): Int {
        val arr = Prefs.getPending(ctx)
        if (arr.length() == 0) return 0
        val rimasti = JSONArray()
        var spediti = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val nome = item.optString("rpc", "")
            val params = item.optJSONObject("params") ?: continue
            // Il codice può essere cambiato da quando il giro è stato accodato.
            params.put("p_token", token())
            val e = rpc(nome, params)
            when {
                e.ok            -> spediti++
                e.body != null  -> { /* rifiutato nel merito: si butta, vedi chiudiGiro */ }
                else            -> rimasti.put(item)   // rete ancora assente: si riprova
            }
        }
        Prefs.setPending(ctx, rimasti)
        return spediti
    }

    companion object { private const val TAG = "SosApi" }
}
