package com.garsal.sos

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val NAME = "sos_prefs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ── Accoppiamento ────────────────────────────────────────────────────────
    fun getToken(ctx: Context): String = sp(ctx).getString("token", "") ?: ""
    fun setToken(ctx: Context, t: String) { sp(ctx).edit().putString("token", t.trim().uppercase()).apply() }
    fun clearToken(ctx: Context) { sp(ctx).edit().remove("token").remove("config").apply() }

    // ── Configurazione in cache ──────────────────────────────────────────────
    /** L'ultima risposta di sos_config, così il bottone parte anche senza rete:
        nel momento della crisi non si aspetta il giro di una chiamata HTTP. */
    fun getConfig(ctx: Context): String = sp(ctx).getString("config", "") ?: ""
    fun setConfig(ctx: Context, json: String) { sp(ctx).edit().putString("config", json).apply() }

    fun getConfigAt(ctx: Context): Long = sp(ctx).getLong("config_at", 0L)
    fun setConfigAt(ctx: Context, ms: Long) { sp(ctx).edit().putLong("config_at", ms).apply() }

    /** Pagina dello swipe aperta l'ultima volta: riaprire sempre sul primo SOS
        vorrebbe dire scorrere ogni volta fino al proprio. */
    fun getLastIndex(ctx: Context): Int = sp(ctx).getInt("last_index", 0)
    fun setLastIndex(ctx: Context, i: Int) { sp(ctx).edit().putInt("last_index", i).apply() }

    // ── Coda dei giri non ancora spediti ─────────────────────────────────────
    /** Un giro chiuso senza rete resta qui finché non passa. Ogni voce è già
        la chiamata da rifare: `rpc` più i suoi parametri, così al rinvio non
        c'è niente da ricostruire (e niente da sbagliare ricostruendolo). */
    fun getPending(ctx: Context): JSONArray =
        try { JSONArray(sp(ctx).getString("pending", "[]")) } catch (_: Exception) { JSONArray() }

    fun setPending(ctx: Context, arr: JSONArray) {
        sp(ctx).edit().putString("pending", arr.toString()).apply()
    }

    fun addPending(ctx: Context, item: JSONObject) {
        val arr = getPending(ctx)
        arr.put(item)
        // Un tetto c'è: se la rete manca da settimane la coda non deve diventare
        // il posto dove muore la memoria del telefono. Si buttano le più vecchie.
        while (arr.length() > 50) arr.remove(0)
        setPending(ctx, arr)
    }
}
