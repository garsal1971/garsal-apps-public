package com.garsal.modifiche

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject

/**
 * Esposto alla pagina come `window.AndroidApp`. Due cose sole, tutt'e due per
 * ⚙️ → 📱 Versione app: dire quale versione è **installata**, e aprire il
 * download nel browser di sistema.
 *
 * ⚠️ La versione la dà il `PackageManager` e non una costante scritta nel
 * codice: è quella vera, non quella che il codice credeva di essere. È la
 * stessa scelta di `Aggiornamento.kt` nell'APK WebView.
 *
 * ⚠️ **Il download NON può restare nella WebView**: un APK scaricato qui
 * dentro non si installa: non c'è nessun gestore di download e nessun modo di
 * lanciare l'installer. Va passato al browser di sistema, che quel giro lo sa
 * fare.
 */
class AppBridge(private val activity: Activity) {

    @JavascriptInterface
    fun versione(): String {
        return try {
            val p = activity.packageManager.getPackageInfo(activity.packageName, 0)
            JSONObject().apply {
                put("version", p.versionName ?: "")
                @Suppress("DEPRECATION")
                put("versionCode", p.versionCode)
            }.toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    /**
     * ⚠️ `CATEGORY_BROWSABLE` e il try/catch non sono prudenza generica, sono
     * il gemello di `apriNelBrowser` in SOS: senza la categoria l'intent può
     * non agganciare nessun browser, e senza il catch quel caso non è un
     * download mancato ma **l'app che si chiude in faccia**.
     */
    @JavascriptInterface
    fun apriNelBrowser(url: String) {
        activity.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(activity, "Non ho trovato un browser.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
