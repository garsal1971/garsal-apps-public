package com.garsal.appsphere.inoltro

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.garsal.appsphere.BuildConfig
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Jwt
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 📨 Svuota la coda verso `msg-ingest`, a blocchi da 100, col JWT dell'utente.
 *
 * ⚠️ Gira a schermo spento, quando l'app è chiusa da ore: il JWT dura un'ora e
 * nessuno l'ha rinnovato. Prima di ogni invio si ricarica la sessione
 * dall'archivio e la si rinnova se serve (lo stesso giro dello 🪥 spazzolino).
 * ⚠️ Una riga si toglie dalla coda solo quando il server ha risposto ok: un
 * doppione in un rinvio lo scarta l'impronta unica, una riga persa no.
 * Senza login (refresh token perso) la coda resta e si riprova più tardi:
 * riaprendo AppSphere riparte da sé.
 */
class InvioWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (Inoltro.coda(ctx).isEmpty()) return@withContext Result.success()

        val token = runCatching { token() }.getOrNull()
        if (token == null) {
            Inoltro.annota(ctx, "⚠ invio sospeso: non collegato, apri AppSphere")
            return@withContext Result.retry()
        }

        while (true) {
            val blocco = Inoltro.coda(ctx).take(100)
            if (blocco.isEmpty()) break
            val esito = runCatching { invia(token, blocco) }
            val r = esito.getOrElse {
                Inoltro.annota(ctx, "⚠ invio fallito: ${it.message ?: "rete"} — riprovo")
                return@withContext Result.retry()
            }
            if (r.first !in 200..299) {
                Inoltro.annota(ctx, "⚠ msg-ingest HTTP ${r.first}: ${r.second.take(120)}")
                return@withContext Result.retry()
            }
            Inoltro.togli(ctx, blocco.map { it.optString("impronta") }.toSet())
            val scritti = runCatching { JSONObject(r.second).optInt("scritti") }.getOrDefault(0)
            Inoltro.annota(ctx, "✓ inviati ${blocco.size} (nuovi in archivio: $scritti)")
        }
        Result.success()
    }

    private suspend fun token(): String? {
        val auth = Supabase.client().auth
        if (auth.currentSessionOrNull() == null) auth.loadFromStorage()
        if (auth.currentSessionOrNull() == null) return null
        AuthRepo.assicuraSessione()
        return auth.currentSessionOrNull()?.accessToken?.takeIf { Jwt.vivo(it) }
    }

    private fun invia(token: String, blocco: List<JSONObject>): Pair<Int, String> {
        val c = URL(BuildConfig.SUPABASE_URL + "/functions/v1/msg-ingest").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            c.outputStream.use { it.write(JSONObject().put("messaggi", JSONArray(blocco)).toString().toByteArray()) }
            val codice = c.responseCode
            val testo = (if (codice in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return codice to testo
        } finally {
            c.disconnect()
        }
    }
}
