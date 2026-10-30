package com.garsal.pronuncia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.garsal.pronuncia.Ciclo
import com.garsal.pronuncia.CicloService
import com.garsal.pronuncia.Frasi
import com.garsal.pronuncia.Ripasso
import com.garsal.pronuncia.Servizi
import java.util.Locale

/**
 * Fase 3: il ciclo a mani libere. Fase 4: pausa, comandi a voce e tasti delle cuffie. La schermata lo avvia, lo ferma e lo
 * racconta; il lavoro lo fa `CicloService`, che continua a schermo spento.
 * Frasi/parole, livello e tema sono i suoi, in cima alla scheda (`Prefs.paroleCiclo`…);
 * microfono, feedback e riascolto stanno in ⚙️ Impostazioni → A mani libere.
 */
@Composable
fun ManiLibere(parole: Boolean) {
    val ctx = LocalContext.current
    val s by Ciclo.stato.collectAsState()
    var nota by remember { mutableStateOf<String?>(null) }

    fun avvia() {
        nota = null
        ContextCompat.startForegroundService(ctx, Intent(ctx, CicloService::class.java))
    }

    // Il microfono serve per forza; le notifiche no — senza, il ciclo gira
    // lo stesso, solo che il pulsante Ferma sta nell'app e basta.
    val permessi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { esiti ->
        if (esiti[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) avvia() else nota = "Senza il permesso del microfono non posso ascoltarti."
    }

    fun premi() {
        val mancano = buildList {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (mancano.isEmpty()) avvia() else permessi.launch(mancano.toTypedArray())
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.attivo) {
                s.frase?.let { f ->
                    if (s.ripasso) Text(
                        "🔁 da ripassare",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TestoConTraduzione(f)
                }
                Text(
                    listOfNotNull(
                        s.cosa.ifBlank { null },
                        s.tentativo.takeIf { it > 0 }?.let { "tentativo $it/${Ciclo.TENTATIVI}" },
                        s.ultimo?.let { "ultimo ${String.format(Locale.ITALY, "%.0f", it)}/100" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Perché te la fa ripetere: il voto da solo (92) non lo dice.
                s.verdetto?.takeIf { !it.buona }?.let { MotiviVerdetto(it) }
                s.avviso?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                Button(
                    onClick = { ctx.startService(Intent(ctx, CicloService::class.java).setAction(CicloService.PAUSA)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(if (s.inPausa) "▶ Riprendi" else "⏸ Pausa", style = MaterialTheme.typography.titleMedium) }
                Button(
                    onClick = { ctx.startService(Intent(ctx, CicloService::class.java).setAction(CicloService.FERMA)) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("⏹ Ferma", style = MaterialTheme.typography.titleMedium) }
            } else {
                Text(
                    (if (parole)
                        "Legge una parola, tu la ripeti, ti fa riascoltare la tua voce (se acceso in ⚙️ Impostazioni) e ti dice il voto. Sotto $SOGLIA_INTERA te la rilegge piano " +
                            "e te la fa ridire, fino a ${Ciclo.TENTATIVI} volte. Una parola ogni ${Ripasso.OGNI} è da ripassare, presa dallo storico. Continua finché "
                    else
                        "Legge una frase, tu la ripeti, ti fa riascoltare la tua voce (se acceso in ⚙️ Impostazioni) e ti dice il voto. Sotto $SOGLIA_INTERA ripete le " +
                            "parole da curare e te la fa ridire, fino a ${Ciclo.TENTATIVI} volte. Una frase ogni ${Ripasso.OGNI} è da ripassare, presa dallo storico. Continua finché ") +
                        "non premi Ferma, anche a schermo spento.\n\n" +
                        "Quando tocca a te, al posto della frase puoi dire «repeat», «next», «slower» o «stop». " +
                        "Tasti delle cuffie: play/pausa mette in pausa e riprende, avanti = next, indietro = repeat. " +
                        "Una chiamata o il navigatore lo mettono in pausa, e riparte da sé.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { premi() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("▶ Avvia", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (s.frasi > 0) {
                Text(
                    "Questa sessione: ${s.frasi} ${if (s.frase?.parola == true) "parole" else "frasi"}, ${s.buone} riuscite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            (nota ?: s.nota.takeIf { !s.attivo })?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private val SOGLIA_INTERA = SOGLIA_BUONA.toInt()
