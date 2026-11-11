package com.garsalapps

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ClasseViva (Spaggiari) letto dal TELEFONO, per da-classeviva.html.
 *
 * ⚠️ PERCHÉ QUI E NON SUL SERVER. Spaggiari sta dietro Akamai, che risponde 403
 * «Access Denied» a ogni richiesta dai server cloud — provato da pg_net e da una
 * Edge Function, anche sulla sola pagina di login. Dal telefono la richiesta parte
 * da una rete di casa o mobile e passa. Il browser invece non può: CORS.
 *
 * ⚠️ LE API NON SONO DOCUMENTATE: sono quelle dell'app ClasseViva. Base, User-Agent
 * e chiave si possono sostituire dalla pagina (`opzioni`), così quando Spaggiari le
 * cambia non serve un APK nuovo per ripartire.
 *
 * ⚠️ Le credenziali stanno SOLO qui, cifrate con una chiave AES del Keystore che
 * non esce dal telefono. Nessuna impronta: la sincronizzazione deve partire con un
 * tocco, e chi ha il telefono sbloccato in mano vede comunque i voti nella pagina.
 * La password non torna mai al JavaScript.
 */
object ClasseViva {

    private const val ALIAS = "classeviva_v1"
    private const val PREFS = "classeviva"
    private const val BLOB = "credenziali"

    const val BASE = "https://web.spaggiari.eu/rest/v1"
    const val UA = "CVVS/std/4.1.7 Android/10"
    const val APIKEY = "Tg1NWEwNGIgIC0K"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun chiave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun salva(ctx: Context, uid: String, pass: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chiave()) }
        val testo = JSONObject().put("uid", uid.trim()).put("pass", pass).toString().toByteArray(Charsets.UTF_8)
        val blob = c.iv + c.doFinal(testo)
        prefs(ctx).edit().putString(BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun dimentica(ctx: Context) { prefs(ctx).edit().remove(BLOB).apply() }

    fun registrato(ctx: Context) = prefs(ctx).contains(BLOB)

    /** Il codice utente salvato (non la password), per mostrarlo nella pagina. */
    fun utente(ctx: Context): String = credenziali(ctx)?.first ?: ""

    private fun credenziali(ctx: Context): Pair<String, String>? {
        val b64 = prefs(ctx).getString(BLOB, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            val j = JSONObject(String(c.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8))
            j.getString("uid") to j.getString("pass")
        } catch (e: Exception) { null }
    }

    private class Risposta(val stato: Int, val corpo: String)

    private fun chiama(url: String, metodo: String, intest: Map<String, String>, corpo: String? = null): Risposta {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = metodo
        con.connectTimeout = 15_000
        con.readTimeout = 30_000
        intest.forEach { (k, v) -> con.setRequestProperty(k, v) }
        if (corpo != null) { con.doOutput = true; con.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) } }
        val stato = con.responseCode
        val testo = (if (stato in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        con.disconnect()
        return Risposta(stato, testo)
    }

    private fun breve(s: String) = s.replace(Regex("\\s+"), " ").take(240)

    /**
     * Login e lettura. Torna sempre un JSON: `{ok:true, studente, voti, agenda,
     * lezioni, errori}` oppure `{ok:false, errore}`. Le tre letture sono
     * indipendenti: una che fallisce finisce in `errori` e le altre arrivano.
     */
    fun sincronizza(ctx: Context, opzioniJson: String?): String {
        val (uid, pass) = credenziali(ctx)
            ?: return JSONObject().put("ok", false).put("errore", "Credenziali ClasseViva non salvate su questo telefono.").toString()
        val opz = try { JSONObject(opzioniJson ?: "{}") } catch (e: Exception) { JSONObject() }
        val base = opz.optString("base").ifBlank { BASE }.trimEnd('/')
        val ua = opz.optString("ua").ifBlank { UA }
        val key = opz.optString("apikey").ifBlank { APIKEY }
        val giorniAgenda = opz.optInt("giorniAgenda", 45)
        val giorniLezioni = opz.optInt("giorniLezioni", 14)
        val base0 = mapOf("Content-Type" to "application/json", "User-Agent" to ua, "Z-Dev-Apikey" to key)

        return try {
            fun login(ident: String?): Risposta = chiama("$base/auth/login", "POST", base0,
                JSONObject().put("ident", ident ?: JSONObject.NULL).put("pass", pass).put("uid", uid).toString())

            var r = login(null)
            if (r.stato !in 200..299) return errLogin(r)
            var j = JSONObject(r.corpo)
            // Un account con più profili (genitore con più figli) risponde con `choices`:
            // si rifà il login sul profilo studente.
            j.optJSONArray("choices")?.let { scelte ->
                var ident: String? = null
                for (i in 0 until scelte.length()) {
                    val s = scelte.getJSONObject(i).optString("ident")
                    if (ident == null || s.startsWith("S")) ident = s
                }
                r = login(ident)
                if (r.stato !in 200..299) return errLogin(r)
                j = JSONObject(r.corpo)
            }
            val token = j.optString("token")
            val ident = j.optString("ident")
            if (token.isBlank() || ident.isBlank()) return JSONObject().put("ok", false)
                .put("errore", "Login senza token: ${breve(r.corpo)}").toString()
            val id = ident.replace(Regex("\\D"), "")
            val h = base0 + ("Z-Auth-Token" to token)

            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            val oggi = Calendar.getInstance()
            fun giorno(delta: Int) = (oggi.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, delta) }.let { fmt.format(it.time) }

            val errori = JSONObject()
            fun leggi(nome: String, path: String, chiaveLista: String): JSONArray {
                return try {
                    val x = chiama("$base$path", "GET", h)
                    if (x.stato !in 200..299) { errori.put(nome, "HTTP ${x.stato} — ${breve(x.corpo)}"); JSONArray() }
                    else JSONObject(x.corpo).optJSONArray(chiaveLista) ?: JSONArray()
                } catch (e: Exception) { errori.put(nome, e.message ?: e.toString()); JSONArray() }
            }
            val voti = leggi("voti", "/students/$id/grades", "grades")
            val agenda = leggi("agenda", "/students/$id/agenda/all/${giorno(-7)}/${giorno(giorniAgenda)}", "agenda")
            val lezioni = leggi("lezioni", "/students/$id/lessons/${giorno(-giorniLezioni)}/${giorno(0)}", "lessons")
            val insegnanti = leggi("insegnanti", "/students/$id/subjects", "subjects")
            // I ricevimenti non stanno nelle API: si legge la pagina web dei colloqui.
            var ricevimenti: JSONObject? = null
            try { ricevimenti = paginaColloqui(uid, pass, ua, opz) }
            catch (e: Exception) { errori.put("ricevimenti", e.message ?: e.toString()) }

            JSONObject().put("ok", true)
                .put("studente", "${j.optString("firstName")} ${j.optString("lastName")}".trim())
                .put("voti", voti).put("agenda", agenda).put("lezioni", lezioni)
                .put("insegnanti", insegnanti).put("ricevimenti", ricevimenti ?: JSONObject.NULL)
                .put("errori", errori).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("errore", "Rete o risposta illeggibile: ${e.message ?: e}").toString()
        }
    }

    /** L'indirizzo di accesso del sito e le pagine dei colloqui da provare, in ordine. */
    const val WEB_LOGIN = "https://web.spaggiari.eu/auth-p7/app/default/AuthApi4.php?a=aLoginPwd"
    val PAGINE_COLLOQUI = listOf(
        "https://web.spaggiari.eu/fml/app/default/colloqui.php",
        "https://web.spaggiari.eu/cvv/app/default/colloqui.php",
        "https://web.spaggiari.eu/tic/app/default/colloqui.php")

    /**
     * ⚠️ NON È UN'API: è il sito di ClasseViva, letto come lo leggerebbe il browser.
     * Login del sito (cookie PHPSESSID), poi la pagina dei colloqui. Torna l'HTML
     * (tagliato) e l'indirizzo che ha risposto; a capire cosa c'è dentro è la pagina.
     * L'indirizzo si può imporre dalla pagina (`opzioni.colloqui`).
     */
    private fun paginaColloqui(uid: String, pass: String, ua: String, opz: JSONObject): JSONObject {
        val form = "cid=&uid=${java.net.URLEncoder.encode(uid, "UTF-8")}&pwd=${java.net.URLEncoder.encode(pass, "UTF-8")}&pin=&target="
        val con = URL(WEB_LOGIN).openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.connectTimeout = 15_000; con.readTimeout = 30_000
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
        con.doOutput = true; con.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val stato = con.responseCode
        val corpo = (if (stato in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val cookie = (con.headerFields["Set-Cookie"] ?: con.headerFields["set-cookie"] ?: emptyList())
            .map { it.substringBefore(';') }.filter { it.contains('=') }.joinToString("; ")
        con.disconnect()
        if (stato !in 200..299 || !corpo.contains("\"loggedIn\":true")) throw Exception("login del sito non riuscito (HTTP $stato): ${breve(corpo)}")
        if (cookie.isBlank()) throw Exception("login del sito senza cookie di sessione")

        val pagine = opz.optString("colloqui").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: PAGINE_COLLOQUI
        val tentativi = mutableListOf<String>()
        for (url in pagine) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Cookie", cookie)
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            val st = c.responseCode
            val html = (if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val finale = c.url.toString(); c.disconnect()
            // Rimandati al login = pagina che non esiste per questo profilo.
            if (st in 200..299 && !finale.contains("/auth") && html.length > 500)
                return JSONObject().put("url", finale).put("html", html.take(400_000))
            tentativi.add("$url → HTTP $st")
        }
        throw Exception("nessuna pagina dei colloqui ha risposto: ${tentativi.joinToString("; ")}")
    }

    private fun errLogin(r: Risposta): String {
        val motivo = when {
            r.stato == 422 -> "utente o password sbagliati"
            r.stato == 403 && r.corpo.contains("Access Denied") -> "Spaggiari ha bloccato la richiesta (Akamai)"
            r.corpo.contains("apikey", ignoreCase = true) -> "la chiave delle API non vale più: va aggiornata nelle Impostazioni della pagina"
            else -> "HTTP ${r.stato}"
        }
        return JSONObject().put("ok", false).put("errore", "Login ClasseViva fallito: $motivo — ${breve(r.corpo)}").toString()
    }
}
