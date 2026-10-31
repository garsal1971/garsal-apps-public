package com.garsal.pronuncia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * La traduzione italiana di frasi e parole, per il fumetto che compare tenendo
 * premuto il testo inglese. Sta nella tabella `pr_traduzioni` (id → it), che si
 * corregge dalla dashboard di Supabase senza rifare l'APK.
 *
 * ⚠️ Si legge UNA volta per avvio e si tiene in cache su file: il fumetto deve
 * comparire al tocco, non dopo una chiamata di rete, e senza rete resta buona
 * l'ultima copia. Se non c'è nemmeno quella il fumetto lo dice.
 */
class Traduzioni(ctx: Context, private val auth: Auth) {

    private val file = File(ctx.filesDir, "traduzioni.json")
    private val mutex = Mutex()
    private var lette = false

    private val _mappa = MutableStateFlow(leggiCache())
    val mappa: StateFlow<Map<String, String>> = _mappa

    /** Chiamata dalle schermate che mostrano una frase: la prima volta scarica. */
    suspend fun aggiorna() = mutex.withLock {
        if (lette || !auth.connesso) return@withLock
        val nuova = runCatching { scarica(auth.token()) }.getOrNull() ?: return@withLock
        lette = true
        if (nuova.isEmpty()) return@withLock
        _mappa.value = nuova
        runCatching {
            val o = JSONObject()
            nuova.forEach { (k, v) -> o.put(k, v) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    private fun leggiCache(): Map<String, String> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** ⚠️ A pagine da 1000: PostgREST di Supabase non ne restituisce di più
     *  per chiamata, e dalla v1.20.0 le traduzioni sono più di duemila. */
    private suspend fun scarica(jwt: String): Map<String, String> = withContext(Dispatchers.IO) {
        val tutte = HashMap<String, String>()
        var da = 0
        while (true) {
            val c = URL(BuildConfig.SUPABASE_URL + "/rest/v1/pr_traduzioni?select=id,it&order=id")
                .openConnection() as HttpURLConnection
            val n = try {
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("Authorization", "Bearer $jwt")
                c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                c.setRequestProperty("Range", "$da-${da + PAGINA - 1}")
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val a = JSONArray(c.inputStream.bufferedReader().readText())
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    tutte[r.getString("id")] = r.getString("it")
                }
                a.length()
            } finally {
                c.disconnect()
            }
            if (n < PAGINA) break
            da += PAGINA
        }
        tutte
    }

    private companion object { const val PAGINA = 1000 }
}
