package com.garsal.pronuncia.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.garsal.pronuncia.Riascolto
import com.garsal.pronuncia.Servizi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.30.0: 🎙 I miei comandi. Per ogni comando si registrano fino a tre volte
 * la parola italiana, che a mani libere si confronta sul telefono con quel che
 * si dice (`ComandiVoce`). La 🧪 Prova dice cosa riconoscerebbe, con la
 * distanza e la soglia: è il modo di tarare la sensibilità.
 */
@Composable
fun MieiComandi() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cmd = Servizi.comandi
    val ciclo by Ciclo.stato.collectAsState()
    var occupato by remember { mutableStateOf(false) }
    var nota by remember { mutableStateOf<String?>(null) }
    var giro by remember { mutableIntStateOf(0) } // ridisegna i conteggi
    var sens by remember { mutableStateOf(Servizi.prefs.sensibilitaComandi) }
    var dopoPermesso by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) dopoPermesso?.invoke() else nota = "Senza il permesso del microfono non posso registrare."
        dopoPermesso = null
    }
    fun colMicrofono(azione: () -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) azione()
        else { dopoPermesso = azione; permesso.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** Una voce dal microfono del telefono, o `null` se non si è sentito niente. */
    suspend fun ascolta(): ByteArray? = withContext(Dispatchers.IO) {
        Bip.suona(false)
        Ascolto(false).use { m ->
            val inizio = m.aspettaVoce(5000) ?: return@use null
            m.tieniFinoAlSilenzio(inizio)
            m.registrazione()
        }
    }

    fun registra(v: ComandiVoce.Voce) = colMicrofono {
        scope.launch {
            occupato = true; nota = "🎙 Di' «${v.parola}» dopo il bip"
            try {
                val pcm = ascolta()
                if (pcm == null) nota = "Non ho sentito niente: riprova."
                else { withContext(Dispatchers.IO) { cmd.aggiungi(v, pcm) }; nota = "✅ «${v.parola}» registrato"; giro++ }
            } catch (e: Exception) { nota = "⚠️ ${e.message}" }
            occupato = false
        }
    }

    fun prova() = colMicrofono {
        scope.launch {
            occupato = true; nota = "🧪 Di' un comando, o una parola inglese, dopo il bip"
            try {
                val pcm = ascolta()
                nota = if (pcm == null) "Non ho sentito niente: riprova." else {
                    val e = withContext(Dispatchers.Default) { cmd.confronta(pcm, sens) }
                    val sec = e.secondo?.let { " · poi «${it.parola}» %.1f".format(e.distanzaSecondo) } ?: ""
                    when {
                        e.migliore == null -> "Nessun comando registrato, o voce troppo corta."
                        e.voce != null -> "✅ «${e.voce.parola}» — distanza %.1f, soglia %.1f$sec".format(e.distanza, e.soglia)
                        else -> "❌ Non è un comando (più vicino «${e.migliore.parola}»: distanza %.1f, soglia %.1f$sec)".format(e.distanza, e.soglia)
                    }
                }
            } catch (e: Exception) { nota = "⚠️ ${e.message}" }
            occupato = false
        }
    }

    val libero = !occupato && !ciclo.attivo
    Text("🎙 I miei comandi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Nota("A mani libere, al posto della parola puoi dire un comando in italiano. Registra ognuno tre volte con la tua voce: il telefono confronta quel che dici con le registrazioni, e solo se non è un comando lo manda ad Azure. Le registrazioni restano sul telefono.")
    if (ciclo.attivo) Nota("⏸ Col ciclo a mani libere acceso il microfono è occupato.")
    giro.let { _ ->
        ComandiVoce.Voce.entries.forEach { v ->
            val n = cmd.quante(v)
            Column {
                Text("«${v.parola}» · $n/${ComandiVoce.MAX}", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { registra(v) }, enabled = libero, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (n >= ComandiVoce.MAX) "🎙 Rifai" else "🎙 Registra")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { cmd.ultima(v)?.let { Riascolto.suona(it) } } },
                        enabled = libero && n > 0, modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("▶ Ascolta") }
                    TextButton(
                        onClick = { cmd.cancella(v); giro++ },
                        enabled = libero && n > 0, modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("🗑") }
                }
            }
        }
    }
    Tendina("Sensibilità", sens, listOf(1.1, 1.25, 1.4, 1.6, 1.8, 2.0), {
        when (it) { 1.1 -> "1,1 · severa"; 1.25 -> "1,25 · normale"; 2.0 -> "2,0 · larga"; else -> "%.2f".format(it).replace('.', ',') }
    }) { sens = it; Servizi.prefs.sensibilitaComandi = it }
    OutlinedButton(onClick = { prova() }, enabled = libero && cmd.pronti(), modifier = Modifier.heightIn(min = 48.dp)) {
        Text("🧪 Prova un comando")
    }
    nota?.let { Nota(it) }
}
