package com.garsal.modifiche

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File

/**
 * Esposto alla pagina come `window.AndroidBridge`. È il tasto «Condividi» di
 * sistema: il prompt esce di qui e va dove si vuole — in una chat con Claude,
 * in una mail, in un appunto.
 *
 * ⚠️ Le schermate viaggiano **insieme** al testo. Condividere il solo prompt e
 * poi andarsi a cercare gli screenshot nella galleria è esattamente l'attrito
 * che questa app esiste per togliere.
 */
class CondivisioneBridge(private val activity: Activity) {

    /** Solo testo. */
    @JavascriptInterface
    fun condividi(testo: String) {
        activity.runOnUiThread { manda(testo, emptyList()) }
    }

    /**
     * Testo più immagini, che arrivano in base64 dentro un JSON
     * `[{nome, dati}, …]`. I metodi di un `@JavascriptInterface` girano su un
     * thread a parte, quindi la decodifica e la scrittura su disco stanno
     * bene qui; a dover tornare sul thread grafico è solo `startActivity`.
     */
    @JavascriptInterface
    fun condividiConImmagini(testo: String, immaginiJson: String) {
        val uris = try {
            scriviInCache(immaginiJson)
        } catch (e: Exception) {
            emptyList()
        }
        // ⚠️ Senza nemmeno un'immagine scritta si condivide comunque il testo:
        // un tasto che non fa niente perché una foto non si è salvata è peggio
        // di un tasto che fa metà del lavoro e lo dice.
        activity.runOnUiThread { manda(testo, uris) }
    }

    /**
     * ⚠️ La cartella si SVUOTA a ogni condivisione invece di accumulare: sono
     * copie in chiaro di schermate che possono contenere qualunque cosa, e non
     * c'è ragione perché restino nella cache dopo essere state consegnate.
     */
    private fun scriviInCache(immaginiJson: String): List<Uri> {
        val cartella = File(activity.cacheDir, "condivise")
        cartella.listFiles()?.forEach { it.delete() }
        cartella.mkdirs()

        val json = JSONArray(immaginiJson)
        val uris = mutableListOf<Uri>()
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            val nome = o.optString("nome", "schermata-${i + 1}.jpg")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val contenuto = Base64.decode(o.getString("dati"), Base64.DEFAULT)
            val f = File(cartella, nome)
            f.writeBytes(contenuto)
            uris += FileProvider.getUriForFile(
                activity, "${activity.packageName}.condivisione", f
            )
        }
        return uris
    }

    private fun manda(testo: String, uris: List<Uri>) {
        val intent = when {
            uris.isEmpty() -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, testo)
            }
            uris.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_TEXT, testo)
            }
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(Intent.EXTRA_TEXT, testo)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra(Intent.EXTRA_SUBJECT, "Richiesta di modifica")

        // ⚠️ Il prompt finisce ANCHE negli appunti quando si condividono delle
        // immagini, e non è un doppione: parecchie app che ricevono un
        // `ACTION_SEND` di tipo immagine tengono i file e **buttano via**
        // l'EXTRA_TEXT. Quando succede, il testo è a un incolla di distanza
        // invece di essere da riscrivere — e lo si dice, invece di lasciarlo
        // scoprire.
        if (uris.isNotEmpty()) {
            try {
                val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Richiesta di modifica", testo))
                Toast.makeText(
                    activity,
                    "Prompt anche negli appunti: se l’app non lo porta con le immagini, incollalo.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) { /* gli appunti sono una comodità, non una condizione */ }
        }

        try {
            activity.startActivity(Intent.createChooser(intent, "Condividi la richiesta"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Non c’è niente con cui condividere.", Toast.LENGTH_LONG).show()
        }
    }
}
