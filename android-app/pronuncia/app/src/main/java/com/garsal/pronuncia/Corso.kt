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
 * ⚠️ Si tengono in cache su file: senza rete resta buona l'ultima copia.
 * v1.39.0: si riscaricano a ogni ritorno in primo piano (al più ogni
 * `Traduzioni.CORSO_OGNI_MS`) e prima di scegliere una sessione — fino alla
 * v1.38.0 una volta sola per processo, e le voci inserite con l'app già aperta
 * non arrivavano finché Android non la chiudeva.
 * ⚠️ Nei tentativi le voci del corso si riconoscono da `tema = 'corso'` e
 * `frase_id = 'c-<uuid>'`; `fonte` resta la modalità (mani libere / esercizio).
 */
object Corso {
    const val TEMA = "corso"
    const val LIVELLO = "—"
    const val PREFISSO = "c-"
    private const val FILE = "corso.json"

    @Volatile private var voci: List<Frase>? = null
    @Volatile private var traduzioni: Map<String, String> = emptyMap()

    fun passaLivello(f: Frase, livello: String?) = livello == null || f.tema == TEMA || f.livello == livello

    /** Il filtro di una sessione: livello (che le voci del corso passano
     *  sempre) e tema (`null` = tutti). Una regola sola per `Sessione` e `Frasi.aCaso`. */
    fun filtra(voci: List<Frase>, livello: String?, tema: String?): List<Frase> =
        voci.filter { passaLivello(it, livello) && (tema == null || it.tema == tema) }

    fun voci(ctx: Context): List<Frase> = voci ?: synchronized(this) {
        voci ?: (runCatching { File(ctx.filesDir, FILE).readText() }.getOrNull()?.let { applica(leggi(it)) }
            ?: emptyList()).also { voci = it }
    }

    fun traduzioni(ctx: Context): Map<String, String> { voci(ctx); return traduzioni }

    /** Quel che si legge dal JSON di `pr_parole_corso`. */
    data class Lettura(val voci: List<Frase>, val traduzioni: Map<String, String>)

    /**
     * Legge la risposta di PostgREST. v1.39.0: una riga malformata (testo vuoto,
     * campo mancante) si SALTA, invece di far sparire tutto il corso come prima
     * (`getOrDefault(emptyList())` sull'intero elenco). Un JSON che non è un
     * elenco torna `null`: per chi scarica vuol dire «tieni la copia di prima».
     * ⚠️ Senza Android: la provano i test sulla JVM (`CorsoTest`).
     */
    fun leggi(testo: String): Lettura? {
        val a = runCatching { JSONArray(testo) }.getOrNull() ?: return null
        val tr = LinkedHashMap<String, String>()
        val l = (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                val id = PREFISSO + o.getString("id").trim()
                val t = o.getString("testo").trim()
                if (id == PREFISSO || t.isEmpty()) return@runCatching null
                o.optString("traduzione").trim().takeIf { it.isNotEmpty() }?.let { tr[id] = it }
                Frase(id, LIVELLO, TEMA, t, o.optString("tipo") == "parola")
            }.getOrNull()
        }
        return Lettura(l, tr)
    }

    private fun applica(l: Lettura): List<Frase> { traduzioni = l.traduzioni; return l.voci }

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
        val lettura = leggi(testo) ?: error("risposta non valida")
        synchronized(this@Corso) { voci = applica(lettura) }
        runCatching {
            val f = File(ctx.filesDir, FILE)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(testo)
            tmp.renameTo(f)
        }
    }
}
