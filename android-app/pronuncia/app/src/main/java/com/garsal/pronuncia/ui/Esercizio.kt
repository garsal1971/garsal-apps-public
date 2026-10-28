package com.garsal.pronuncia.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.garsal.pronuncia.Ciclo
import com.garsal.pronuncia.Esito
import com.garsal.pronuncia.Frase
import com.garsal.pronuncia.Storico
import com.garsal.pronuncia.Ripasso
import com.garsal.pronuncia.Frasi
import com.garsal.pronuncia.NessunaVoce
import com.garsal.pronuncia.Parola
import com.garsal.pronuncia.Servizi
import com.garsal.pronuncia.SessioneScaduta
import kotlinx.coroutines.launch
import java.util.Locale

/** Sopra questa soglia la frase è «Good»: è la stessa del ciclo a mani libere. */
const val SOGLIA_BUONA = 80.0

private enum class Fase { FERMO, PARLA, ASCOLTA }

/**
 * Fase 2: una frase, ascoltarla, ripeterla, vedere i punteggi. Il ciclo a mani
 * libere (`Ciclo`) usa le stesse due chiamate — `Tts.pronuncia` e
 * `Valutatore.valuta` — in un servizio, senza questa schermata.
 */
@Composable
fun Esercizio() {
    val ctx = LocalContext.current
    val prefs = Servizi.prefs
    val scope = rememberCoroutineScope()

    var livello by remember { mutableStateOf(prefs.livello) }
    var tema by remember { mutableStateOf(prefs.tema) }
    var frase by remember { mutableStateOf<Frase?>(Frasi.aCaso(ctx, livello, tema)) }
    var fase by remember { mutableStateOf(Fase.FERMO) }
    var esito by remember { mutableStateOf<Esito?>(null) }
    var nota by remember { mutableStateOf<String?>(null) }
    var ripasso by remember { mutableStateOf(false) }
    // Il microfono è uno: col ciclo a mani libere acceso, l'esercizio a mano aspetta.
    val ciclo by Ciclo.stato.collectAsState()
    val libero = fase == Fase.FERMO && !ciclo.attivo

    fun nuova() {
        frase = Frasi.aCaso(ctx, livello, tema, frase?.id)
        esito = null
        nota = null
        ripasso = false
    }

    // Fase 6: una frase dallo storico, fra quelle da ripassare.
    fun ripassa() {
        scope.launch {
            val r = runCatching { Ripasso.prossima(ctx, livello, tema, frase?.id) }.getOrNull()
            if (r == null) {
                nota = "Niente da ripassare per questo filtro: le frasi andate male tornano qui da sole."
            } else {
                frase = r; esito = null; nota = null; ripasso = true
            }
        }
    }

    fun ascolta(lenta: Boolean) {
        val f = frase ?: return
        if (fase != Fase.FERMO) return
        fase = Fase.PARLA
        nota = null
        scope.launch {
            try {
                Servizi.tts.pronuncia(f.testo, prefs.voce, lenta)
            } catch (e: SessioneScaduta) {
                Servizi.connesso.value = false
            } catch (e: Exception) {
                nota = "⚠️ ${e.message ?: e.javaClass.simpleName}"
            }
            fase = Fase.FERMO
        }
    }

    fun valuta() {
        val f = frase ?: return
        if (fase != Fase.FERMO) return
        fase = Fase.ASCOLTA
        nota = null
        esito = null
        scope.launch {
            try {
                val e = Servizi.valutatore.valuta(f.testo)
                esito = e
                Storico.registra(ctx, f, e, "esercizio")
            } catch (e: SessioneScaduta) {
                Servizi.connesso.value = false
            } catch (e: NessunaVoce) {
                nota = e.message
            } catch (e: Exception) {
                nota = "⚠️ ${e.message ?: e.javaClass.simpleName}"
            }
            fase = Fase.FERMO
        }
    }

    // Il microfono si chiede al primo tocco su 🎙, non all'apertura: una
    // richiesta senza il gesto che la spiega si legge come un'app invadente.
    val permesso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) valuta() else nota = "Senza il permesso del microfono non posso ascoltarti."
    }
    fun ripeti() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) valuta()
        else permesso.launch(Manifest.permission.RECORD_AUDIO)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🎯 Esercizio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Tendina("Livello", livello, listOf(null) + Frasi.LIVELLI, { it ?: "Tutti" }) {
                livello = it; prefs.livello = it; nuova()
            }
            Tendina("Tema", tema, listOf(null) + Frasi.TEMI.keys, { it?.let { k -> Frasi.TEMI[k] } ?: "Tutti" }) {
                tema = it; prefs.tema = it; nuova()
            }

            val f = frase
            if (f == null) {
                Text("Nessuna frase per questo filtro.")
            } else {
                Text(
                    "${f.livello} · ${Frasi.TEMI[f.tema] ?: f.tema}" + if (ripasso) " · 🔁 da ripassare" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(f.testo, style = MaterialTheme.typography.headlineSmall)
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { ascolta(false) }, enabled = libero && f != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("▶ Ascolta")
                }
                OutlinedButton(onClick = { ascolta(true) }, enabled = libero && f != null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("🐢 Lenta")
                }
                OutlinedButton(onClick = { nuova() }, enabled = libero, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("⏭ Altra")
                }
                OutlinedButton(onClick = { ripassa() }, enabled = libero, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("🔁 Ripasso")
                }
            }

            Button(
                onClick = { ripeti() },
                enabled = libero && f != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    when (fase) {
                        Fase.ASCOLTA -> "🎙 Ti ascolto… parla adesso"
                        Fase.PARLA -> "🔊 Sta parlando…"
                        Fase.FERMO -> "🎙 Ripeti la frase"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            nota?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            esito?.let { Risultato(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Risultato(e: Esito) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val buona = e.pronuncia >= SOGLIA_BUONA
        Text(
            "${if (buona) "✅ Good" else "🔁 Da ripetere"} · ${uno(e.pronuncia)}/100",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colore(e.pronuncia),
        )
        RigaPunteggio("Accuratezza", e.accuratezza)
        RigaPunteggio("Fluidità", e.fluidita)
        RigaPunteggio("Completezza", e.completezza)
        if (e.riconosciuto.isNotBlank()) {
            Text(
                "Ho sentito: «${e.riconosciuto}»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⚠️ Le parole sono etichette, non pulsanti: qui andare a capo è giusto
        // — una riga che scorre di lato nasconderebbe proprio le parole sbagliate.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            e.parole.forEach { ParolaChip(it) }
        }
        val peggiori = e.peggiori()
        if (peggiori.isNotEmpty()) {
            Text("Da curare:", fontWeight = FontWeight.Bold)
            peggiori.forEach { p ->
                val fonemi = p.fonemi.filter { it.second < SOGLIA_BUONA }.joinToString(" ") { "/${it.first}/ ${uno(it.second)}" }
                Text(
                    "• ${p.testo} — ${descrivi(p)}" + if (fonemi.isNotEmpty()) "\n   suoni deboli: $fonemi" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RigaPunteggio(nome: String, valore: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(nome)
        Text(uno(valore), fontWeight = FontWeight.Bold, color = colore(valore))
    }
}

@Composable
private fun ParolaChip(p: Parola) {
    val fondo = when (p.errore) {
        "Omission" -> Color(0xFF9E9E9E)
        "Insertion" -> Color(0xFF7E57C2)
        else -> colore(p.accuratezza)
    }
    Text(
        if (p.errore == "Omission") "${p.testo} ∅" else "${p.testo} ${uno(p.accuratezza)}",
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .background(fondo, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun descrivi(p: Parola): String = when (p.errore) {
    "Omission" -> "saltata"
    "Mispronunciation" -> "pronuncia errata (${uno(p.accuratezza)})"
    else -> "da migliorare (${uno(p.accuratezza)})"
}

/** Verde dalla soglia in su, ambra fra 60 e la soglia, rosso sotto. Sempre con
 *  la cifra accanto: il colore da solo non dice niente a chi non lo distingue. */
internal fun colore(v: Double): Color = when {
    v >= SOGLIA_BUONA -> Color(0xFF00796B)
    v >= 60 -> Color(0xFFB26A00)
    else -> Color(0xFFC62828)
}

internal fun uno(v: Double) = String.format(Locale.ITALY, "%.0f", v)
