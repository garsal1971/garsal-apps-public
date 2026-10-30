package com.garsal.pronuncia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/** Le sessioni: quanti elementi (frasi o parole) prima del voto. */
val QUANTE = listOf(5, 10, 20)

/**
 * Il voto di una sessione, da 1 a 10: per ogni elemento il punteggio migliore
 * fra i suoi tentativi, la media, diviso 10 e arrotondato. Il migliore e non
 * l'ultimo: si sta misurando dove si arriva, e un tentativo andato male prima
 * di quello buono non deve abbassarlo. ⚠️ Sotto 1 non si scende: la metrica
 * di Obiettivi è una scala 1-10 e `ob_record_measurement` rifiuterebbe lo zero.
 */
fun votoSessione(migliori: Collection<Double>): Int? =
    if (migliori.isEmpty()) null else Math.round(migliori.average() / 10).toInt().coerceIn(1, 10)

/**
 * Il ponte con Obiettivi: a fine sessione si completa l'azione scelta in
 * ⚙️ Impostazioni e si registra il voto sulle sue metriche di autovalutazione.
 *
 * ⚠️ Passa dalle RPC di Obiettivi e non scrive le tabelle a mano, come
 * Autoformazione: `ob_action_complete` decide punti e prossima occorrenza,
 * `ob_record_measurement` è l'unico punto di scrittura di una rilevazione (e
 * rifiuta un voto fuori scala). ⚠️ L'id dell'azione sta nelle preferenze del
 * telefono e non nel codice: la repo è pubblica.
 *
 * ⚠️ Una rilevazione è una per metrica e per giorno: due sessioni nello stesso
 * giorno lasciano il voto della seconda, mentre l'azione si completa due volte
 * (i punti si prendono due volte, come chiudendola due volte da Obiettivi).
 */
object Obiettivo {

    data class Azione(val id: String, val titolo: String)

    /** Le azioni che si possono collegare: non concluse e non workflow (quelle
     *  si chiudono dai loro step). */
    suspend fun azioni(): List<Azione> {
        val a = JSONArray(get("ob_actions?select=id,title,type,status&status=neq.terminated&type=neq.workflow&order=title"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Azione(it.getString("id"), it.optString("title")) }
    }

    /** Completa l'azione e registra il voto. Torna la frase da mostrare. */
    suspend fun aggiorna(voto: Int, nota: String): String {
        val id = Servizi.prefs.azioneObiettivo
            ?: return "Nessuna azione di Obiettivi collegata: sceglila in ⚙️ Impostazioni."
        val oggi = LocalDate.now().toString()
        val esito = JSONObject(rpc("ob_action_complete", JSONObject().put("p_action_id", id).put("p_today", oggi)))
        if (!esito.optBoolean("ok")) return "⚠️ Obiettivi: " + esito.optString("error", "azione non completata")

        // Le metriche di autovalutazione collegate, la cui scala contiene il voto.
        val m = JSONArray(get("ob_action_metrics?select=ob_metrics(id,kind,min_value,max_value)&action_id=eq." + enc(id)))
        val metriche = (0 until m.length()).mapNotNull { m.getJSONObject(it).optJSONObject("ob_metrics") }
            .filter { it.optString("kind") == "autovalutazione" &&
                it.optDouble("min_value", 1.0) <= voto && voto <= it.optDouble("max_value", 10.0) }
        if (metriche.isEmpty()) return "✅ Azione completata (+${esito.optInt("points")} punti). Nessuna metrica 1-10 collegata: il voto non è registrato."
        var registrate = 0
        metriche.forEach { mt ->
            val r = JSONObject(rpc("ob_record_measurement", JSONObject()
                .put("p_metric_id", mt.getString("id")).put("p_value", voto)
                .put("p_measured_on", oggi).put("p_note", nota)))
            if (r.optBoolean("ok", true)) registrate++
        }
        return if (registrate == metriche.size) "✅ Obiettivo aggiornato: azione completata (+${esito.optInt("points")} punti) e voto $voto registrato."
        else "⚠️ Azione completata, ma il voto non è stato registrato su ${metriche.size - registrate} metriche."
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(percorso: String) = chiama("GET", "/rest/v1/$percorso", null)
    private suspend fun rpc(nome: String, corpo: JSONObject) = chiama("POST", "/rest/v1/rpc/$nome", corpo.toString())

    /** ⚠️ Un 401 si riprova UNA volta dopo un rinnovo forzato: il token può
     *  sembrare vivo e non esserlo più per il server. */
    private suspend fun chiama(metodo: String, percorso: String, corpo: String?): String {
        val (codice, testo) = http(metodo, percorso, corpo, Servizi.auth.token())
        val (c2, t2) = if (codice == 401) http(metodo, percorso, corpo, Servizi.auth.token(forza = true)) else codice to testo
        if (c2 !in 200..299) error("HTTP $c2: ${t2.take(200)}")
        return t2
    }

    private suspend fun http(metodo: String, percorso: String, corpo: String?, jwt: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = URL(BuildConfig.SUPABASE_URL + percorso).openConnection() as HttpURLConnection
            try {
                c.requestMethod = metodo
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("Authorization", "Bearer $jwt")
                c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                if (corpo != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(corpo.toByteArray()) }
                }
                val codice = c.responseCode
                val s = (if (codice in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                codice to s
            } finally {
                c.disconnect()
            }
        }
}
