package com.garsal.sos

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.Locale

/**
 * «Che versione ho, e ce n'è una più nuova?» — più il link per scaricarla.
 *
 * ⚠️ Gemello riga per riga di `Aggiornamento.kt` di Smart Blocker e dell'APK
 * WebView (`com.garsalapps`), e di `core/Aggiornamento.kt` del nativo: stessa
 * scheda `-latest.json` scritta dal workflow accanto all'APK, stesse sette
 * chiavi, stesso dialogo. Se cambia la forma della scheda in un workflow,
 * cambia negli altri e in `mostraVersione()` di `comandi.html`.
 *
 * Serve perché il nome dell'APK è fisso (`-latest.apk`): da fuori una build
 * vale l'altra, e scaricare quella di ieri al posto di quella appena
 * pubblicata è indistinguibile da un aggiornamento riuscito — finché l'app non
 * rifà lo stesso difetto che era appena stato chiuso.
 *
 * ⚠️ Qui l'APK è uno solo (nessun flavor), quindi il nome della scheda è una
 * costante e non un `BuildConfig`: in Smart Blocker sono due perché sono due
 * pacchetti con `applicationId` diversi.
 */
object Rilascio {

    private const val SITO = "https://garsal.men"
    // ⚠️ DUE indirizzi, e non è un doppione: la **scheda** sta sul sito, il
    // **pacchetto** su R2. Cloudflare Pages rifiuta i file oltre i 25 MiB e il
    // file troppo grosso non fallisce da solo — fallisce l'intero deploy — così
    // le APK sono tutte fuori dal sito. La scheda invece è qualche centinaio di
    // byte e resta dov'era, col suo `no-store`.
    private const val APK = "https://apk.garsal.men"
    private const val BASE = "Sos-latest"

    /** Il `?v=` non serve al server: impedisce al browser di riproporre il
     *  pacchetto già scaricato quando l'indirizzo è identico. */
    fun apk(versione: String): String = "$APK/$BASE.apk?v=$versione"

    data class Scheda(val version: String, val versionCode: Int, val bytes: Long, val sha256: String)

    suspend fun scheda(): Scheda = withContext(Dispatchers.IO) {
        // `?t=` per la stessa ragione del `?v=`: senza, una scheda in cache
        // racconterebbe la build di ieri.
        val url = "$SITO/releases/$BASE.json?t=${System.currentTimeMillis()}"
        val o = JSONObject(URL(url).readText())
        Scheda(
            version = o.optString("version"),
            versionCode = o.optInt("versionCode"),
            bytes = o.optLong("bytes"),
            sha256 = o.optString("sha256"),
        )
    }
}

/** Versione installata, letta dal **pacchetto** e non da `BuildConfig`: è
 *  quella vera, non quella che il codice credeva di essere. */
fun versioneInstallata(activity: AppCompatActivity): Pair<String, Int> {
    val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
    return (info.versionName ?: "?") to info.longVersionCode.toInt()
}

/**
 * Il dialogo: dice cosa c'è installato, cosa c'è pubblicato e apre il download
 * nel **browser di sistema** — è l'unico posto dove l'utente ritrova il file
 * fra i suoi scaricamenti, e dove il gestore pacchetti lo può installare sopra.
 */
fun mostraDialogoAggiornamento(activity: AppCompatActivity) {
    val (nome, codice) = versioneInstallata(activity)

    val testo = TextView(activity).apply {
        val p = (16 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, 0)
        text = "Installata: v$nome (build $codice)\n\nControllo cosa c'è pubblicato…"
    }

    // La versione da mettere nel link si aggiorna quando la scheda arriva: se
    // non arriva resta quella installata, così il pulsante scarica comunque.
    var versioneLink = nome

    val dialogo = AlertDialog.Builder(activity)
        .setTitle("Versione")
        .setView(testo)
        .setPositiveButton("⬇ Scarica APK") { _, _ -> apriNelBrowser(activity, Rilascio.apk(versioneLink)) }
        .setNegativeButton("Chiudi", null)
        .create()
    dialogo.show()

    CoroutineScope(Dispatchers.Main).launch {
        val esito = runCatching { Rilascio.scheda() }
        val pubblicata = esito.getOrNull()
        val piuNuova = pubblicata != null && pubblicata.versionCode > codice
        if (pubblicata != null) versioneLink = pubblicata.version

        val stato = when {
            pubblicata == null ->
                "Versione pubblicata non leggibile: " +
                    (esito.exceptionOrNull()?.message ?: "non raggiungibile") +
                    "\nIl pulsante qui sotto scarica lo stesso."
            piuNuova -> "Pubblicata: v${pubblicata.version} (build ${pubblicata.versionCode}) · " +
                mb(pubblicata.bytes)
            pubblicata.versionCode == codice -> "Pubblicata: la stessa. Sei aggiornato."
            // Succede provando una build fatta a mano prima che il workflow
            // pubblichi la sua: dirlo è meglio che tacere e far scaricare
            // all'indietro senza spiegare perché.
            else -> "Pubblicata: v${pubblicata.version} (build ${pubblicata.versionCode}), " +
                "più vecchia di questa."
        }

        dialogo.setTitle(if (piuNuova) "C'è una versione nuova" else "Versione")
        testo.text = "Installata: v$nome (build $codice)\n\n$stato\n\n" +
            "Si scarica dal browser: a fine download tocca il file per installarlo sopra a questa."
    }
}

/**
 * Apre un indirizzo nel browser di sistema — mai una Custom Tab: per un
 * download è l'unico posto dove il file finisce fra gli scaricamenti.
 */
private fun apriNelBrowser(activity: AppCompatActivity, url: String) {
    try {
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (e: Exception) {
        Log.e("MainActivity", "nessun browser per aprire $url", e)
    }
}

private fun mb(byte: Long): String = String.format(Locale.ITALY, "%.1f MB", byte / 1048576.0)
