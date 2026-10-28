package com.garsal.pronuncia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Il token di Azure Speech, chiesto alla Edge Function `azure-speech-token`.
 *
 * ⚠️ La chiave Azure non sta nell'app (vedi la funzione): qui arriva un token
 * che dura dieci minuti, e la funzione lo rende per nove. Lo si tiene finché
 * gli resta almeno un minuto, poi se ne chiede un altro — un token che scade a
 * metà frase farebbe fallire proprio il riconoscimento che si sta aspettando.
 */
class AzureToken(private val auth: Auth) {

    data class Token(val valore: String, val regione: String, val scade: Long)

    private val mutex = Mutex()
    private var cache: Token? = null

    suspend fun prendi(): Token = mutex.withLock {
        cache?.takeIf { it.scade > System.currentTimeMillis() + 60_000 }?.let { return it }
        val nuovo = try {
            chiedi(auth.token())
        } catch (e: NonAutorizzato) {
            // ⚠️ Un 401 può arrivare anche con un `exp` che sembra buono (chiavi
            // di firma del progetto cambiate): si rinnova UNA volta e si
            // riprova. In ciclo no — se torna 401 il problema non è il token.
            chiedi(auth.token(forza = true))
        }
        cache = nuovo
        nuovo
    }

    private suspend fun chiedi(jwt: String): Token = withContext(Dispatchers.IO) {
        val c = URL(BuildConfig.SUPABASE_URL + "/functions/v1/azure-speech-token")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $jwt")
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("{}".toByteArray()) }
            val codice = c.responseCode
            val corpo = (if (codice in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (codice == 401) throw NonAutorizzato()
            val o = runCatching { JSONObject(corpo) }.getOrNull()
            if (codice !in 200..299) {
                // Si scrive il motivo della funzione, non un «errore» generico:
                // «mancano i secret» e «non autorizzato» si risolvono in posti diversi.
                throw IllegalStateException(o?.optString("error")?.ifEmpty { null } ?: "token Azure: HTTP $codice")
            }
            val ok = o ?: throw IllegalStateException("token Azure: risposta illeggibile")
            Token(
                valore = ok.getString("token"),
                regione = ok.getString("region"),
                scade = runCatching { Instant.parse(ok.getString("expiresAt")).toEpochMilli() }
                    .getOrDefault(System.currentTimeMillis() + 8 * 60_000),
            )
        } finally {
            c.disconnect()
        }
    }

    private class NonAutorizzato : Exception("non autorizzato")
}
