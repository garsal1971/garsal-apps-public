package com.garsal.modifiche

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject

/**
 * Esposto alla pagina come `window.AndroidCreds`.
 *
 * ⚠️ È il gemello di quello di «Situazione Rosa», riga per riga a meno del nome
 * dell'archivio: sono due progetti Gradle separati e non condividono sorgenti.
 * Se lo correggi qui, guarda anche l'altro.
 *
 * Le credenziali sono cifrate con una chiave dell'Android Keystore, così l'app
 * rientra da sé quando la sessione scade — senza, ogni volta toccherebbe
 * aspettare un link via email, che è il modo più sicuro di non usare più l'app.
 */
class CredentialsBridge(private val context: Context) {

    private val prefs by lazy {
        val chiaveMadre = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "modifiche_creds",
            chiaveMadre,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @JavascriptInterface
    fun saveCredentials(email: String, password: String) {
        prefs.edit().putString("email", email).putString("password", password).apply()
    }

    @JavascriptInterface
    fun getCredentials(): String {
        val email = prefs.getString("email", null)
        val password = prefs.getString("password", null)
        if (email == null || password == null) return "{}"
        return JSONObject().apply {
            put("email", email)
            put("password", password)
        }.toString()
    }

    @JavascriptInterface
    fun clearCredentials() {
        prefs.edit().clear().apply()
    }
}
