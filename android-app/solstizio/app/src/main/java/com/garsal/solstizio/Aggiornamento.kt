package com.garsal.solstizio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
    private const val BASE = "Solstizio-latest"

    /** Il `?v=` non serve al server: impedisce al browser di riproporre il
     *  pacchetto già scaricato quando l'indirizzo è identico. */
    // ⚠️ Col numero nel nome (`<Nome>-<versione>.apk`): il file scaricato dice quale
    // versione è, e un indirizzo nuovo non può servire la build di prima dalla cache.
    // Senza scheda (`versione` null) resta `-latest.apk`, che è transitorio: serve
    // finché tutti i telefoni non hanno questa versione. Li carica `scripts/apk-su-r2.sh`.
    fun apk(versione: String?): String =
        if (versione == null) "$APK/$BASE.apk" else "$APK/Solstizio-$versione.apk"

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

    /** Android lascia installare a quest'app? Va chiesto una volta, dalle impostazioni. */
    fun puoInstallare(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Apre la pagina di sistema «Installa app sconosciute» per Solstizio. */
    fun chiediPermesso(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Scarica l'APK nella cache dell'app, ne controlla l'impronta contro la
     * scheda (se c'è) e lo passa all'installatore di Android.
     *
     * ⚠️ Si scarica in un file `.part` e si rinomina solo a download finito e
     * verificato: un APK troncato passato all'installatore risponde «pacchetto
     * non valido», che si legge come un guasto dell'app invece che della rete.
     */
    suspend fun scaricaEInstalla(
        ctx: Context,
        versione: String?,
        sha256: String?,
        onProgresso: (Float?) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cartella = File(ctx.cacheDir, "aggiornamento").apply { mkdirs() }
            val parte = File(cartella, "Solstizio.apk.part")
            val apk = File(cartella, "Solstizio.apk")
            val c = URL(apk(versione)).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            try {
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val totale = c.contentLengthLong
                val md = MessageDigest.getInstance("SHA-256")
                var letti = 0L
                c.inputStream.use { inp ->
                    parte.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            letti += n
                            onProgresso(if (totale > 0) letti.toFloat() / totale else null)
                        }
                    }
                }
                val impronta = md.digest().joinToString("") { "%02x".format(it) }
                if (!sha256.isNullOrBlank() && !impronta.equals(sha256, ignoreCase = true)) {
                    parte.delete()
                    error("il file scaricato non corrisponde alla versione pubblicata, riprova")
                }
            } finally {
                c.disconnect()
            }
            apk.delete()
            if (!parte.renameTo(apk)) error("non riesco a salvare il file scaricato")
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.aggiornamento", apk)
            withContext(Dispatchers.Main) {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

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
    val scope = rememberCoroutineScope()
    val (nome, codice) = remember { Rilascio.installata(ctx) }
    var scheda by remember { mutableStateOf<Rilascio.Scheda?>(null) }
    var stato by remember { mutableStateOf("Controllo cosa c'è pubblicato…") }
    var progresso by remember { mutableStateOf<Float?>(null) }
    var scarico by remember { mutableStateOf(false) }

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
            else -> "Pubblicata la v${s.version} (build ${s.versionCode}), più vecchia di questa."
        }
    }

    fun avvia() {
        if (!Rilascio.puoInstallare(ctx)) {
            stato = "Prima consenti a Solstizio di installare app: attiva l'interruttore nella " +
                "pagina che si apre, poi torna qui e tocca di nuovo «Aggiorna»."
            Rilascio.chiediPermesso(ctx)
            return
        }
        val s = scheda
        scarico = true
        progresso = null
        stato = "Scarico…"
        scope.launch {
            val r = Rilascio.scaricaEInstalla(ctx, s?.version, s?.sha256) { p ->
                scope.launch { progresso = p }
            }
            scarico = false
            stato = r.fold(
                { "Scaricato: conferma l'installazione nella schermata di Android." },
                { "Download non riuscito: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!scarico) onChiudi() },
        title = { Text("📱 Versione app") },
        text = {
            Column {
                Text("Installata: v$nome (build $codice)\n\n$stato", style = MaterialTheme.typography.bodyMedium)
                if (scarico) {
                    Spacer(Modifier.height(12.dp))
                    val p = progresso
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            // ⚠️ Il pulsante c'è SEMPRE, anche senza scheda: la versione nel link è
            // allora quella installata. Senza scheda non si controlla l'impronta.
            val s = scheda
            TextButton(onClick = { avvia() }, enabled = !scarico) {
                Text(
                    when {
                        s == null -> "⬇ Scarica e installa"
                        s.versionCode > codice -> "⬇ Aggiorna alla v${s.version}"
                        else -> "⬇ Reinstalla"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                // Ripiego: il browser, come le altre app, se l'installazione da qui non va.
                if (!Rilascio.apriNelBrowser(ctx, Rilascio.apk(scheda?.version))) {
                    stato = "Non ho trovato un browser da aprire su questo telefono."
                }
            }, enabled = !scarico) { Text("Dal browser") }
            TextButton(onClick = onChiudi, enabled = !scarico) { Text("Chiudi") }
        },
    )
}
