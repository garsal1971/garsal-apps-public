package com.garsal.pronuncia

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Il login Google via Supabase, senza supabase-kt.
 *
 * Il giro è quello di AppSphere nativa, e i tre pezzi sono gli stessi:
 * 1. `redirect_to` è una pagina **https** (`oauth-callback-pronuncia.html`),
 *    non lo schema custom: Chrome blocca il salto automatico da una pagina di
 *    login a uno schema custom;
 * 2. la pagina-ponte rilancia l'app solo col **tap** dell'utente;
 * 3. il login si apre nel **browser completo**, non in una Custom Tab: da una
 *    Custom Tab aperta dall'app quel tap non rilancia niente.
 *
 * Qui non c'è supabase-kt perché del progetto servono due chiamate HTTP, e
 * con lei non ci sono nemmeno i suoi inciampi sul rientro (vedi CLAUDE.md,
 * «Il rientro dal login non passa da parseFragmentAndImportSession»).
 */
class Auth(private val prefs: Prefs) {

    companion object {
        const val REDIRECT = "https://garsal.men/oauth-callback-pronuncia.html"
    }

    private val mutex = Mutex()

    val connesso: Boolean get() = prefs.refreshToken != null

    fun email(): String? = prefs.accessToken?.let { claim(it, "email") }

    fun apriLogin(ctx: Context): Boolean = runCatching {
        val url = BuildConfig.SUPABASE_URL + "/auth/v1/authorize?provider=google&redirect_to=" +
            URLEncoder.encode(REDIRECT, "UTF-8")
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)

    /** `garsalpronuncia://oauth#access_token=…&refresh_token=…`. Torna `true` se
     *  la sessione è stata salvata. */
    fun completa(uri: Uri): Boolean {
        val frammento = uri.fragment ?: return false
        val v = frammento.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to Uri.decode(it.substring(i + 1))
        }.toMap()
        val access = v["access_token"] ?: return false
        val refresh = v["refresh_token"] ?: return false
        prefs.accessToken = access
        prefs.refreshToken = refresh
        return true
    }

    fun esci() = prefs.esci()

    /**
     * Un access token **vivo**, rinnovandolo se serve.
     *
     * ⚠️ Un token che c'è non è un token che vale: dura un'ora, e un telefono
     * che dorme non rinnova niente. Si guarda `exp` col margine di 30 secondi,
     * come `Jwt.vivo` nell'APK nativa. Il `Mutex` non è prudenza generica: due
     * rinnovi in parallelo spenderebbero due volte lo stesso refresh token, e
     * il secondo verrebbe rifiutato — cioè la sessione persa.
     */
    suspend fun token(forza: Boolean = false): String = mutex.withLock {
        val attuale = prefs.accessToken
        if (!forza && attuale != null && vivo(attuale)) return attuale
        rinnova()
    }

    private suspend fun rinnova(): String = withContext(Dispatchers.IO) {
        val refresh = prefs.refreshToken ?: throw SessioneScaduta()
        val c = URL(BuildConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("refresh_token", refresh).toString().toByteArray()) }
            val codice = c.responseCode
            // ⚠️ Un 400/401 qui vuol dire refresh token speso o revocato: si
            // butta la sessione e si torna al login. Un errore di rete invece
            // no — buttarla per un tunnel senza campo costerebbe un login.
            if (codice == 400 || codice == 401) { prefs.esci(); throw SessioneScaduta() }
            if (codice !in 200..299) throw IllegalStateException("rinnovo della sessione: HTTP $codice")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            prefs.accessToken = o.getString("access_token")
            prefs.refreshToken = o.getString("refresh_token")
            o.getString("access_token")
        } finally {
            c.disconnect()
        }
    }

    private fun vivo(token: String): Boolean {
        val exp = claim(token, "exp")?.toLongOrNull() ?: return false
        return exp * 1000 > System.currentTimeMillis() + 30_000
    }

    /** Legge un claim senza verificare la firma: è un'etichetta, la verifica la
     *  fa il server a ogni chiamata. */
    private fun claim(token: String, nome: String): String? = runCatching {
        val payload = token.split(".")[1]
        val grezzo = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(grezzo, Charsets.UTF_8)).optString(nome).ifEmpty { null }
    }.getOrNull()
}

class SessioneScaduta : Exception("Sessione scaduta: rifai l'accesso con Google.")
