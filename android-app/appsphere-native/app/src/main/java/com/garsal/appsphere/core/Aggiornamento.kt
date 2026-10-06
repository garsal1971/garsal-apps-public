package com.garsal.appsphere.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

/**
 * Apre un indirizzo nel browser di sistema.
 *
 * Sempre il browser completo, mai una Custom Tab: per il login è l'unica strada
 * che riporta indietro (vedi `AuthRepo.loginConGoogle`), e per un download è
 * l'unico posto dove l'utente ritrova il file fra i suoi scaricamenti.
 */
fun apriNelBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    true
} catch (e: Exception) {
    Log.e("AppSphere", "nessun browser per aprire $url", e)
    false
}

/**
 * Dove sta l'APK pubblicato — lo stesso indirizzo del pulsante in `comandi.html`.
 *
 * Il nome del file è fisso (`-latest.apk`), quindi da solo non dice quale build
 * sia: accanto c'è la scheda `-latest.json` che il workflow scrive insieme
 * all'APK, ed è quella che permette di dire *prima* di scaricare se c'è
 * davvero qualcosa di nuovo. Il `?v=` non serve al server: impedisce al browser
 * di riproporre il pacchetto già scaricato quando l'indirizzo è identico.
 */
object Rilascio {

    private const val SITO = "https://garsal.men"
    // ⚠️ DUE indirizzi, e non è un doppione: la **scheda** sta sul sito, il
    // **pacchetto** su R2. Cloudflare Pages rifiuta i file oltre i 25 MiB e il
    // file troppo grosso non fallisce da solo — fallisce l'intero deploy — così
    // le APK sono tutte fuori dal sito. La scheda invece è qualche centinaio di
    // byte e resta dov'era, col suo `no-store`.
    private const val APK = "https://apk.garsal.men"
    private const val SCHEDA = "$SITO/releases/AppSphereNative-latest.json"

    fun apk(versione: String): String = "$APK/AppSphereNative-latest.apk?v=$versione"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun scheda(): SchedaBuild = withContext(Dispatchers.IO) {
        json.decodeFromString<SchedaBuild>(URL(SCHEDA).readText())
    }
}

@Serializable
data class SchedaBuild(
    val version: String = "",
    val versionCode: Int = 0,
    val builtAt: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
)

/**
 * «Che versione ho, e ce n'è una più nuova?» — più il link per scaricarla.
 *
 * L'11 agosto 2026 la 1.0.4 è stata installata al posto della 1.0.5 pubblicata
 * pochi minuti dopo, e dal comportamento non c'era modo di distinguere «la
 * correzione non funziona» da «la correzione non è a bordo». Da fuori si è
 * risolto mostrando la versione sul pulsante di `comandi.html`; qui la stessa
 * scheda serve a rispondere alla domanda dal telefono, senza aprire il sito.
 */
@Composable
fun DialogoAggiornamento(onChiudi: () -> Unit) {
    val context = LocalContext.current
    var scheda by remember { mutableStateOf<SchedaBuild?>(null) }
    var errore by remember { mutableStateOf<String?>(null) }
    var inLettura by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runCatching { Rilascio.scheda() }
            .onSuccess { scheda = it }
            .onFailure { errore = it.message ?: "non raggiungibile" }
        inLettura = false
    }

    val pubblicata = scheda
    val piuNuova = pubblicata != null && pubblicata.versionCode > BuildConfig.VERSION_CODE

    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(if (piuNuova) "C'è una versione nuova" else "Versione") },
        text = {
            Column {
                Text(
                    "Installata: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val stato = when {
                    inLettura -> "Controllo cosa c'è pubblicato…"
                    pubblicata == null -> "Versione pubblicata non leggibile: $errore\n" +
                        "Il link qui sotto funziona lo stesso."
                    piuNuova -> "Pubblicata: v${pubblicata.version} " +
                        "(build ${pubblicata.versionCode}) · ${mb(pubblicata.bytes)}"
                    pubblicata.versionCode == BuildConfig.VERSION_CODE ->
                        "Pubblicata: la stessa. Sei aggiornato."
                    // Succede provando una build fatta a mano prima che il
                    // workflow pubblichi la sua: dirlo è meglio che tacere e
                    // far scaricare all'indietro senza spiegare perché.
                    else -> "Pubblicata: v${pubblicata.version} " +
                        "(build ${pubblicata.versionCode}), più vecchia di questa."
                }
                Text(
                    text = stato,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (piuNuova) Palette.success else Palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Si scarica dal browser: a fine download tocca il file " +
                        "per installarlo sopra a questa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                apriNelBrowser(context, Rilascio.apk(pubblicata?.version ?: BuildConfig.VERSION_NAME))
                onChiudi()
            }) { Text("⬇ Scarica APK") }
        },
        dismissButton = {
            TextButton(onClick = onChiudi) { Text("Chiudi") }
        },
    )
}

private fun mb(byte: Long): String =
    String.format(java.util.Locale.ITALY, "%.1f MB", byte / 1048576.0)
