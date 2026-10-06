package com.garsal.speseingiro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * «Che versione ho, e ce n'è una più nuova?» — più il link per scaricarla.
 *
 * ⚠️ Gemello di `Aggiornamento.kt` di SOS, di Smart Blocker, dell'APK WebView e
 * di `core/Aggiornamento.kt` del nativo: stessa scheda `-latest.json` scritta
 * dal workflow accanto all'APK, stesse sette chiavi, stesso dialogo. Se cambia
 * la forma della scheda in un workflow, cambia negli altri e in
 * `mostraVersione()` di `comandi.html`. Qui la differenza è solo di forma —
 * Compose invece di un AlertDialog di AppCompat.
 *
 * Serve perché il nome dell'APK è fisso (`-latest.apk`): da fuori una build
 * vale l'altra, e scaricare quella di ieri al posto di quella appena pubblicata
 * è indistinguibile da un aggiornamento riuscito.
 */
object Rilascio {

    private const val SITO = "https://garsal.men"
    // ⚠️ DUE indirizzi, e non è un doppione: la **scheda** sta sul sito, il
    // **pacchetto** su R2. Cloudflare Pages rifiuta i file oltre i 25 MiB e il
    // file troppo grosso non fallisce da solo — fallisce l'intero deploy — così
    // le APK sono tutte fuori dal sito. La scheda invece è qualche centinaio di
    // byte e resta dov'era, col suo `no-store`.
    private const val APK = "https://apk.garsal.men"
    private const val BASE = "SpeseInGiro-latest"

    /** Il `?v=` non serve al server: impedisce al browser di riproporre il
     *  pacchetto già scaricato quando l'indirizzo è identico. */
    fun apk(versione: String): String = "$APK/$BASE.apk?v=$versione"

    data class Scheda(val version: String, val versionCode: Int, val bytes: Long, val sha256: String)

    /** ⚠️ Torna un `Result` e non un `Scheda?`: il **perché** non si è potuta
     *  leggere è quel che serve a chi guarda il dialogo — «non raggiungibile»
     *  e «l'ho letta e dice un'altra cosa» sono due guasti diversi, e un
     *  `getOrNull()` li schiaccia tutt'e due in un silenzio. */
    suspend fun scheda(): Result<Scheda> = withContext(Dispatchers.IO) {
        runCatching {
            // `?t=` per la stessa ragione del `?v=`: senza, una scheda in cache
            // racconterebbe la build di ieri.
            val o = JSONObject(URL("$SITO/releases/$BASE.json?t=${System.currentTimeMillis()}").readText())
            Scheda(o.optString("version"), o.optInt("versionCode"), o.optLong("bytes"), o.optString("sha256"))
        }
    }

    /**
     * Apre un indirizzo nel **browser di sistema**: è l'unico posto dove il
     * file finisce fra gli scaricamenti e dove il gestore pacchetti lo può
     * installare sopra a questo.
     *
     * ⚠️ `CATEGORY_BROWSABLE` e il try/catch non sono prudenza generica, sono
     * il gemello di `apriNelBrowser` in SOS: senza la categoria l'intent può
     * non agganciare nessun browser, e senza il catch quel caso non è un
     * download mancato ma **l'app che si chiude in faccia**.
     */
    fun apriNelBrowser(ctx: Context, url: String): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    }.getOrDefault(false)

    /** La versione **installata**, letta dal pacchetto e non da BuildConfig: è
     *  quella vera, non quella che il codice credeva di essere.
     *
     *  ⚠️ `longVersionCode` è arrivato con Android 9 e qui il minimo è Android 8:
     *  senza il ramo vecchio la build non passa il lint, e su un telefono
     *  dell'8 sarebbe un errore in faccia proprio aprendo le impostazioni. */
    @Suppress("DEPRECATION")
    fun installata(ctx: Context): Pair<String, Int> {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val codice =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) i.longVersionCode.toInt()
            else i.versionCode
        return (i.versionName ?: "?") to codice
    }
}

@Composable
fun DialogoAggiornamento(onChiudi: () -> Unit) {
    val ctx = LocalContext.current
    val (nome, codice) = remember { Rilascio.installata(ctx) }
    var scheda by remember { mutableStateOf<Rilascio.Scheda?>(null) }
    var stato by remember { mutableStateOf("Controllo cosa c'è pubblicato…") }

    LaunchedEffect(Unit) {
        val esito = Rilascio.scheda()
        val s = esito.getOrNull()
        scheda = s
        stato = when {
            s == null ->
                "Versione pubblicata non leggibile: " +
                    (esito.exceptionOrNull()?.message ?: "non raggiungibile") +
                    "\nIl pulsante qui sotto scarica lo stesso."
            s.versionCode > codice ->
                "C'è la v${s.version} (build ${s.versionCode}), " +
                    "${"%.1f".format(s.bytes / 1048576.0)} MB."
            s.versionCode == codice -> "Sei aggiornato: pubblicata la v${s.version} (build ${s.versionCode})."
            // Capita provando una build fatta a mano prima che il workflow
            // pubblichi la sua: dirlo è meglio che far scaricare all'indietro
            // senza spiegare perché.
            else -> "Pubblicata la v${s.version} (build ${s.versionCode}), più vecchia di questa."
        }
    }

    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text("📱 Versione app") },
        text = {
            Text(
                "Installata: v$nome (build $codice)\n\n$stato\n\n" +
                    "Si scarica dal browser: a fine download tocca il file per " +
                    "installarlo sopra a questo.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            // ⚠️ Il pulsante c'è SEMPRE, anche senza scheda: la versione nel
            // link è allora quella installata. Legandolo alla scheda — com'era
            // fino alla v1.0.2 — una rete lenta o un 404 sulla scheda toglievano
            // di mezzo **il download**, cioè la sola cosa per cui questo dialogo
            // esiste, e senza che niente dicesse perché.
            val s = scheda
            TextButton(onClick = {
                if (Rilascio.apriNelBrowser(ctx, Rilascio.apk(s?.version ?: nome))) onChiudi()
                else stato = "Non ho trovato un browser da aprire su questo telefono."
            }) {
                Text(
                    when {
                        s == null -> "⬇ Scarica l'APK"
                        s.versionCode > codice -> "⬇ Scarica la v${s.version}"
                        else -> "⬇ Riscarica"
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onChiudi) { Text("Chiudi") } },
    )
}
