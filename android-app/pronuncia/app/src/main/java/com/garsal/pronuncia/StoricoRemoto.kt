package com.garsal.pronuncia

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Lo storico nel database (v1.20.0): la tabella `pr_tentativi`, una riga per
 * tentativo. Il telefono resta la prima copia — si scrive lì e si legge da lì —
 * e questa è quella che sopravvive a una reinstallazione o a un telefono nuovo.
 *
 * Tre cose:
 * - **invia** le righe locali con `inviato` falso, a blocchi. Il `client_id` è
 *   installazione + id locale e la tabella lo vuole unico: una risposta persa
 *   per strada e un rinvio non fanno un doppione (`ignore-duplicates`);
 * - **riprende**, una volta per installazione, le righe nate su un'ALTRA
 *   installazione (quelle che cominciano col nostro `installazione` sono già
 *   qui): su un telefono nuovo lo storico ricompare da sé;
 * - **svuota** prima il database e poi il telefono: al contrario, un errore di
 *   rete lascerebbe nel database uno storico che il telefono crede cancellato.
 *
 * ⚠️ Non lancia mai, a parte `svuota`: uno storico che non parte non deve
 * fermare il ciclo, e i tentativi aspettano il giro dopo.
 */
object StoricoRemoto {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val BLOCCO = 100
    private const val PAGINA = 1000

    fun inviaPiuTardi(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { invia(app) } }
    }

    /** All'avvio e dopo il login: prima si riprende, poi si manda. */
    fun sincronizzaPiuTardi(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { riprendi(app) }; runCatching { invia(app) } }
    }

    private fun pronto(ctx: Context): Boolean {
        Servizi.avvia(ctx)
        return Servizi.auth.connesso
    }

    suspend fun invia(ctx: Context) = mutex.withLock {
        if (!pronto(ctx)) return@withLock
        val dao = Storico.dao(ctx)
        val inst = Servizi.prefs.installazione
        while (true) {
            val righe = dao.daInviare(BLOCCO)
            if (righe.isEmpty()) break
            val arr = JSONArray()
            righe.forEach { t -> arr.put(json(t, inst, dao.paroleDi(t.id))) }
            val (codice, _) = chiama("POST", "pr_tentativi?on_conflict=user_id,client_id", arr.toString(),
                "resolution=ignore-duplicates,return=minimal")
            if (codice !in 200..299) break
            dao.segnaInviati(righe.map { it.id })
            if (righe.size < BLOCCO) break
        }
    }

    suspend fun riprendi(ctx: Context) = mutex.withLock {
        if (!pronto(ctx)) return@withLock
        val prefs = Servizi.prefs
        if (prefs.storicoRipreso) return@withLock
        val dao = Storico.dao(ctx)
        val mio = prefs.installazione + ":"
        var da = 0
        while (true) {
            val (codice, corpo) = chiama("GET", "pr_tentativi?select=*&order=quando.asc", null, null,
                range = "$da-${da + PAGINA - 1}")
            if (codice !in 200..299) return@withLock // ci si riprova al prossimo avvio
            val a = JSONArray(corpo)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.getString("client_id").startsWith(mio)) continue
                val (t, parole) = daJson(o)
                dao.registra(t, parole)
            }
            if (a.length() < PAGINA) break
            da += PAGINA
        }
        prefs.storicoRipreso = true
    }

    /** Lancia se il database non risponde: la schermata lo dice e il telefono
     *  resta com'era. Senza login si svuota il solo telefono. */
    suspend fun svuota(ctx: Context) = mutex.withLock {
        if (pronto(ctx)) {
            val (codice, _) = chiama("DELETE", "pr_tentativi?id=not.is.null", null, "return=minimal")
            if (codice !in 200..299) throw IllegalStateException("il database ha risposto HTTP $codice")
        }
        Storico.dao(ctx).svuota()
    }

    private fun json(t: Tentativo, inst: String, parole: List<ParolaTentativo>) = JSONObject()
        .put("client_id", "$inst:${t.id}")
        .put("quando", Instant.ofEpochMilli(t.quando).toString())
        .put("giorno", t.giorno)
        .put("frase_id", t.fraseId)
        .put("testo", t.testo)
        .put("parola", t.fraseId.startsWith("w-"))
        .put("livello", t.livello)
        .put("tema", t.tema)
        .put("fonte", t.fonte)
        .put("pronuncia", t.pronuncia)
        .put("accuratezza", t.accuratezza)
        .put("fluidita", t.fluidita)
        .put("completezza", t.completezza)
        .put("riconosciuto", t.riconosciuto)
        .put("dettaglio", JSONArray().apply {
            parole.forEach { put(JSONObject().put("p", it.parola).put("a", it.accuratezza).put("e", it.errore)) }
        })

    private fun daJson(o: JSONObject): Pair<Tentativo, List<ParolaTentativo>> {
        val t = Tentativo(
            quando = OffsetDateTime.parse(o.getString("quando")).toInstant().toEpochMilli(),
            giorno = o.getString("giorno"),
            fraseId = o.getString("frase_id"), testo = o.getString("testo"),
            livello = o.getString("livello"), tema = o.getString("tema"), fonte = o.getString("fonte"),
            pronuncia = o.getDouble("pronuncia"), accuratezza = o.getDouble("accuratezza"),
            fluidita = o.getDouble("fluidita"), completezza = o.getDouble("completezza"),
            riconosciuto = o.optString("riconosciuto"),
            inviato = true,
        )
        val d = o.optJSONArray("dettaglio") ?: JSONArray()
        val parole = (0 until d.length()).map {
            val p = d.getJSONObject(it)
            ParolaTentativo(tentativoId = 0, parola = p.getString("p"), accuratezza = p.getDouble("a"), errore = p.getString("e"))
        }
        return t to parole
    }

    /** ⚠️ Un 401 si riprova UNA volta con un token rinnovato: il token che
     *  sembrava vivo può essere stato rifiutato dal server. */
    private suspend fun chiama(metodo: String, percorso: String, corpo: String?, prefer: String?, range: String? = null): Pair<Int, String> {
        var risposta = una(metodo, percorso, corpo, prefer, range, Servizi.auth.token())
        if (risposta.first == 401) risposta = una(metodo, percorso, corpo, prefer, range, Servizi.auth.token(forza = true))
        return risposta
    }

    private suspend fun una(metodo: String, percorso: String, corpo: String?, prefer: String?, range: String?, jwt: String) =
        withContext(Dispatchers.IO) {
            val c = URL(BuildConfig.SUPABASE_URL + "/rest/v1/" + percorso).openConnection() as HttpURLConnection
            try {
                c.requestMethod = metodo
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Authorization", "Bearer $jwt")
                c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                prefer?.let { c.setRequestProperty("Prefer", it) }
                range?.let { c.setRequestProperty("Range", it) }
                if (corpo != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(corpo.toByteArray()) }
                }
                val codice = c.responseCode
                val testo = (if (codice in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                codice to testo
            } finally {
                c.disconnect()
            }
        }
}
