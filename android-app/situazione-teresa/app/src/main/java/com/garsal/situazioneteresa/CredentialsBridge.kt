package com.garsal.situazioneteresa

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject

/**
 * Esposto alla pagina come `window.AndroidCreds`. Le credenziali sono
 * cifrate con una chiave nell'Android Keystore (EncryptedSharedPreferences),
 * così l'app può ri-autenticarsi da sola se la sessione salvata scade,
 * senza dover richiedere di nuovo email+password.
 */
class CredentialsBridge(private val context: Context) {

    private val prefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "situazione_teresa_creds",
            masterKeyAlias,
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
