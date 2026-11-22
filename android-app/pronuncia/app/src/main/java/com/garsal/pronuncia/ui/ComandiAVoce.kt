package com.garsal.pronuncia.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.garsal.pronuncia.Ascolto
import com.garsal.pronuncia.Bip
import com.garsal.pronuncia.Ciclo
import com.garsal.pronuncia.ComandiVoce
import com.garsal.pronuncia.Servizi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.38.0: 🎙 Comandi a voce. Il modello italiano di Vosk si scarica una volta
 * da R2 e da lì i comandi si riconoscono sul telefono (`ComandiVoce`). La
 * 🧪 Prova dice cosa ha sentito Vosk e con che sicurezza: è il modo di tarare
 * la «Sicurezza minima».
 *
 * Ha preso il posto di «🎙 I miei comandi» (v1.30.0-v1.37.0), dove si
 * registrava la propria voce.
 */
@Composable
fun ComandiAVoce() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cmd = Servizi.comandi
    val modello by cmd.stato.collectAsState()
    val ciclo by Ciclo.stato.collectAsState()
    var occupato by remember { mutableStateOf(false) }
    var nota by remember { mutableStateOf<String?>(null) }
    var sicurezza by remember { mutableStateOf(Servizi.prefs.sicurezzaComandi) }
    var dopoPermesso by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) dopoPermesso?.invoke() else nota = "Senza il permesso del microfono non posso ascoltare."
        dopoPermesso = null
    }
    fun colMicrofono(azione: () -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) azione()
        else { dopoPermesso = azione; permesso.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** Una voce dal microfono (le cuffie USB se scelte), o `null` se non si è sentito niente. */
    suspend fun ascolta(): ByteArray? = withContext(Dispatchers.IO) {
        Bip.suona(false)
        Ascolto(false).use { m ->
            val inizio = m.aspettaVoce(5000) ?: return@use null
            m.tieniFinoAlSilenzio(inizio)
            m.registrazione()
        }
    }

    fun prova() = colMicrofono {
        scope.launch {
            occupato = true; nota = "🧪 Di' un comando, o una parola inglese, dopo il bip"
            try {
                val pcm = ascolta()
                nota = if (pcm == null) "Non ho sentito niente: riprova." else {
                    val e = withContext(Dispatchers.Default) { cmd.confronta(pcm, sicurezza) }
                    val sic = "%.2f".format(e.sicurezza).replace('.', ',')
                    when {
                        e.voce != null -> "✅ Comando «${e.voce.parola}» (sicurezza $sic)"
                        e.sentito.isBlank() -> "❌ Non ho riconosciuto niente: non è un comando."
                        else -> "❌ Non è un comando: ho sentito «${e.sentito}» (sicurezza $sic)"
                    }
                }
            } catch (e: Exception) { nota = "⚠️ ${e.message}" }
            occupato = false
        }
    }

    val libero = !occupato && !ciclo.attivo
    Text("🎙 Comandi a voce", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Nota(
        "A mani libere, al posto della parola puoi dire un comando in italiano: " +
            ComandiVoce.Voce.entries.joinToString(", ") { "«${it.parola}»" } +
            ". Li riconosce il telefono, senza rete, con un modello vocale italiano da scaricare una volta (circa 50 MB).",
    )
    when (val m = modello) {
        ComandiVoce.StatoModello.Assente -> {
            Nota("Modello non scaricato: i comandi a voce sono spenti (restano i tasti delle cuffie).")
            Button(onClick = { cmd.avviaScarico() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("⬇️ Scarica il modello dei comandi")
            }
        }
        is ComandiVoce.StatoModello.Scarico -> Nota(if (m.percento < 92) "⬇️ Scarico il modello… ${m.percento} %" else "📦 Lo preparo…")
        is ComandiVoce.StatoModello.Errore -> {
            Nota("⚠️ Modello non scaricato: ${m.messaggio}")
            Button(onClick = { cmd.avviaScarico() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("⬇️ Riprova a scaricare")
            }
        }
        ComandiVoce.StatoModello.Pronto -> {
            Nota("✅ Modello pronto.")
            Tendina("Sicurezza minima", sicurezza, listOf(0.5, 0.6, 0.7, 0.8, 0.9), {
                when (it) { 0.5 -> "0,5 · larga"; 0.7 -> "0,7 · normale"; 0.9 -> "0,9 · severa"; else -> "%.1f".format(it).replace('.', ',') }
            }) { sicurezza = it; Servizi.prefs.sicurezzaComandi = it }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { prova() }, enabled = libero, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("🧪 Prova un comando")
                }
                TextButton(
                    onClick = { cmd.cancella(); nota = null },
                    enabled = libero, modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("🗑 Togli il modello") }
            }
        }
    }
    if (ciclo.attivo) Nota("⏸ Col ciclo a mani libere acceso il microfono è occupato.")
    nota?.let { Nota(it) }
}
