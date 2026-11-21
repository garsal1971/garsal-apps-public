package com.garsal.pronuncia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Le voci del corso (v1.37.0): parole e frasi viste a lezione, nella tabella
 * `pr_parole_corso`, che Claude riempie a fine lezione. Entrano nel catalogo
 * col tema «📘 Corso» e passano dallo stesso ripasso del resto (`Ripasso.componi`).
 *
 * ⚠️ L'id nell'app è `c-<uuid>`: lo storico lo cita, quindi non cambia.
 * ⚠️ Non hanno un livello A1–B2: il filtro Livello le lascia passare sempre
 * ([passaLivello]).
 * ⚠️ Si scaricano una volta per avvio (da `Traduzioni.aggiorna`) e si tengono
 * in cache su file: senza rete resta buona l'ultima copia.
 */
object Corso {
    const val TEMA = "corso"
    const val LIVELLO = "—"
    private const val FILE = "corso.json"

    @Volatile private var voci: List<Frase>? = null
    @Volatile private var traduzioni: Map<String, String> = emptyMap()

    fun passaLivello(f: Frase, livello: String?) = livello == null || f.tema == TEMA || f.livello == livello

    fun voci(ctx: Context): List<Frase> = voci ?: synchronized(this) {
        voci ?: leggi(runCatching { File(ctx.filesDir, FILE).readText() }.getOrNull()).also { voci = it }
    }

    fun traduzioni(ctx: Context): Map<String, String> { voci(ctx); return traduzioni }

    private fun leggi(testo: String?): List<Frase> {
        if (testo == null) return emptyList()
        return runCatching {
            val a = JSONArray(testo)
            val tr = HashMap<String, String>()
            val l = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val id = "c-" + o.getString("id")
                tr[id] = o.getString("traduzione")
                Frase(id, LIVELLO, TEMA, o.getString("testo").trim(), o.getString("tipo") == "parola")
            }
            traduzioni = tr
            l
        }.getOrDefault(emptyList())
    }

    /** Scarica, scrive la cache e rimpiazza le voci in memoria. */
    suspend fun aggiorna(ctx: Context, jwt: String) = withContext(Dispatchers.IO) {
        val c = URL(BuildConfig.SUPABASE_URL +
            "/rest/v1/pr_parole_corso?select=id,testo,traduzione,tipo&order=inserita_il")
            .openConnection() as HttpURLConnection
        val testo = try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $jwt")
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
        val nuove = leggi(testo)
        synchronized(this@Corso) { voci = nuove }
        runCatching {
            val f = File(ctx.filesDir, FILE)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(testo)
            tmp.renameTo(f)
        }
    }
}
