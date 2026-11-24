package com.garsal.solstizio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Le temperature di un giorno. Un campo `null` è un dato che manca, non zero. */
data class Temperature(
    val min: Double?,
    val max: Double?,
    val media: Double?,
    /** La media dello stesso giorno negli ultimi dieci anni. */
    val storica: Double?,
) {
    fun json() = JSONObject().apply {
        put("min", min ?: JSONObject.NULL); put("max", max ?: JSONObject.NULL)
        put("media", media ?: JSONObject.NULL); put("storica", storica ?: JSONObject.NULL)
    }

    companion object {
        fun da(o: JSONObject) = Temperature(o.numero("min"), o.numero("max"), o.numero("media"), o.numero("storica"))
    }
}

private fun JSONObject.numero(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

/**
 * Open-Meteo: gratuito e senza chiave. Due letture diverse:
 *  - le **previsioni** (min, max e media del giorno), tenute in cache 3 ore;
 *  - la **media storica** di ogni giorno dell'anno, ricavata una volta sola
 *    dall'archivio 2015-2024 e poi tenuta per sempre: non cambia.
 *
 * ⚠️ Nessuna delle due è necessaria alla carta: senza rete la carta si apre lo
 * stesso con alba, tramonto e ore di luce, e dice che il meteo manca.
 */
object Meteo {

    private const val PREVISIONI =
        "https://api.open-meteo.com/v1/forecast?latitude=${Luogo.LAT}&longitude=${Luogo.LON}" +
            "&daily=temperature_2m_max,temperature_2m_min,temperature_2m_mean" +
            "&timezone=Europe%2FRome&past_days=2&forecast_days=16"
    private const val ARCHIVIO =
        "https://archive-api.open-meteo.com/v1/archive?latitude=${Luogo.LAT}&longitude=${Luogo.LON}" +
            "&start_date=2015-01-01&end_date=2024-12-31&daily=temperature_2m_mean&timezone=Europe%2FRome"
    private const val ORE_CACHE = 3

    private fun leggi(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Media storica per «MM-DD». Una volta letta resta nelle preferenze. */
    private suspend fun clima(ctx: Context): JSONObject? = withContext(Dispatchers.IO) {
        val p = Archivio.prefs(ctx)
        p.getString("clima", null)?.let { return@withContext JSONObject(it) }
        runCatching {
            val d = leggi(ARCHIVIO).getJSONObject("daily")
            val giorni = d.getJSONArray("time")
            val medie = d.getJSONArray("temperature_2m_mean")
            val somme = HashMap<String, Pair<Double, Int>>()
            for (i in 0 until giorni.length()) {
                if (medie.isNull(i)) continue
                val k = giorni.getString(i).substring(5)
                val (s, n) = somme[k] ?: (0.0 to 0)
                somme[k] = (s + medie.getDouble(i)) to (n + 1)
            }
            val o = JSONObject()
            somme.forEach { (k, v) -> o.put(k, v.first / v.second) }
            p.edit().putString("clima", o.toString()).apply()
            o
        }.getOrNull()
    }

    /** Le previsioni, e il motivo quando non si sono potute leggere. */
    private suspend fun previsioni(ctx: Context, forza: Boolean): Pair<JSONObject?, String?> = withContext(Dispatchers.IO) {
        val p = Archivio.prefs(ctx)
        val letto = p.getLong("previsioni_letto", 0)
        val fresca = System.currentTimeMillis() - letto < ORE_CACHE * 3_600_000L
        val cache = p.getString("previsioni", null)
        if (!forza && fresca && cache != null) return@withContext JSONObject(cache) to null
        runCatching {
            val d = leggi(PREVISIONI).getJSONObject("daily")
            p.edit().putString("previsioni", d.toString())
                .putLong("previsioni_letto", System.currentTimeMillis()).apply()
            d
        }.fold(
            { it to null },
            // Senza rete resta buona l'ultima lettura, se c'è: per oggi vale ancora.
            { e -> cache?.let { JSONObject(it) } to motivo(e) },
        )
    }

    private fun motivo(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "nessuna connessione a internet"
        is java.net.SocketTimeoutException -> "il servizio non ha risposto in tempo"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    /** Esito di una lettura: le temperature (o `null`) e, se qualcosa è andato
     *  storto, il perché — da scrivere sulla carta invece di tacerlo. */
    data class Lettura(val t: Temperature?, val errore: String?)

    suspend fun temperature(ctx: Context, giorno: LocalDate, forza: Boolean = false): Lettura {
        val (d, errore) = previsioni(ctx, forza)
        val clima = clima(ctx)
        val storica = clima?.let { c ->
            val k = giorno.toString().substring(5)
            if (c.has(k)) c.getDouble(k) else null
        }
        var min: Double? = null
        var max: Double? = null
        var media: Double? = null
        if (d != null) {
            val giorni = d.getJSONArray("time")
            for (i in 0 until giorni.length()) {
                if (giorni.getString(i) != giorno.toString()) continue
                fun v(k: String) = d.optJSONArray(k)?.let { a -> if (a.isNull(i)) null else a.getDouble(i) }
                min = v("temperature_2m_min"); max = v("temperature_2m_max"); media = v("temperature_2m_mean")
            }
        }
        if (min == null && max == null && media == null && storica == null) {
            return Lettura(null, errore ?: if (d != null) "previsioni senza questo giorno" else "dati non disponibili")
        }
        return Lettura(Temperature(min, max, media, storica), errore)
    }
}
