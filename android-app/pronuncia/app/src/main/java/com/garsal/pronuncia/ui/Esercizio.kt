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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.garsal.pronuncia.Difetto
import com.garsal.pronuncia.Verdetto
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
import com.garsal.pronuncia.Riascolto
import com.garsal.pronuncia.Frasi
import com.garsal.pronuncia.Controllo
import com.garsal.pronuncia.Giudizio
import com.garsal.pronuncia.NessunaVoce
import com.garsal.pronuncia.Parola
import com.garsal.pronuncia.Servizi
import com.garsal.pronuncia.SessioneScaduta
import kotlinx.coroutines.launch
import java.util.Locale

/** Il voto complessivo minimo per «Good»: è la stessa del ciclo a mani libere.
 *  Sopra di lei decidono i controlli accesi (vedi `Giudizio`). */
const val SOGLIA_BUONA = 80.0

private enum class Fase { FERMO, PARLA, ASCOLTA }

/**
 * Fase 2: una frase, ascoltarla, ripeterla, vedere i punteggi. Il ciclo a mani
 * libere (`Ciclo`) usa le stesse due chiamate — `Tts.pronuncia` e
 * `Valutatore.valuta` — in un servizio, senza questa schermata.
 *
 * Con [parole] si esercitano le parole singole invece delle frasi intere.
 * Livello e tema si scelgono in ⚙️ Impostazioni → Davanti allo schermo, e chi
 * chiama rifà la scheda (`key`) quando cambiano.
 */
@Composable
fun Esercizio(parole: Boolean, livello: String?, tema: String?) {
    val ctx = LocalContext.current
    val prefs = Servizi.prefs
    val scope = rememberCoroutineScope()

    var frase by remember { mutableStateOf<Frase?>(Frasi.aCaso(ctx, livello, tema, soloParole = parole)) }
    var fase by remember { mutableStateOf(Fase.FERMO) }
    var esito by remember { mutableStateOf<Esito?>(null) }
    var nota by remember { mutableStateOf<String?>(null) }
    var ripasso by remember { mutableStateOf(false) }
    // La mia voce dell'ultimo tentativo, per «Riascoltami». Solo in memoria.
    var miaVoce by remember { mutableStateOf<ByteArray?>(null) }
    // Il microfono è uno: col ciclo a mani libere acceso, l'esercizio a mano aspetta.
    val ciclo by Ciclo.stato.collectAsState()
    val libero = fase == Fase.FERMO && !ciclo.attivo

    fun nuova() {
        frase = Frasi.aCaso(ctx, livello, tema, frase?.id, parole)
        esito = null
        miaVoce = null
        nota = null
        ripasso = false
    }

    // Fase 6: una frase dallo storico, fra quelle da ripassare.
    fun ripassa() {
        scope.launch {
            val r = runCatching { Ripasso.prossima(ctx, livello, tema, frase?.id, parole) }.getOrNull()
            if (r == null) {
                nota = if (parole) "Niente da ripassare per questo filtro: le parole andate male tornano qui da sole."
                else "Niente da ripassare per questo filtro: le frasi andate male tornano qui da sole."
            } else {
                frase = r; esito = null; nota = null; ripasso = true; miaVoce = null
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
                Servizi.tts.pronuncia(f.testo, prefs.voce, prefs.accento, lenta)
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
        miaVoce = null
        scope.launch {
            try {
                val e = Servizi.valutatore.valuta(f.testo, prefs.accento, prefs.soglia(Controllo.RITMO, f.parola) != null)
                esito = e
                miaVoce = Servizi.valutatore.ultimaVoce
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

    fun riascolta() {
        val v = miaVoce ?: return
        if (fase != Fase.FERMO) return
        fase = Fase.PARLA
        scope.launch {
            try {
                Riascolto.suona(v)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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

            Text(
                "${if (parole) "🔤 Parole" else "💬 Frasi"} · livello ${livello ?: "tutti"} · tema ${tema?.let { Frasi.TEMI[it] } ?: "tutti"} — si cambiano in ⚙️ Impostazioni",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val f = frase
            if (f == null) {
                Text(if (parole) "Nessuna parola per questo filtro." else "Nessuna frase per questo filtro.")
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
                        Fase.FERMO -> if (parole) "🎙 Ripeti la parola" else "🎙 Ripeti la frase"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // Dopo la valutazione: la propria voce accanto alla voce dell'app,
            // che è il confronto per cui il pulsante esiste.
            if (esito != null && miaVoce != null) {
                OutlinedButton(
                    onClick = { riascolta() },
                    enabled = libero,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("▶ Riascoltami") }
            }

            nota?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            esito?.let { Risultato(it, frase?.parola == true) }
        }
    }
}

/** Perché è da ripetere, un motivo per riga. Sul suono, la parola con le
 *  lettere che lo scrivono in grassetto e sottolineate: **th**ink. */
@Composable
fun MotiviVerdetto(v: Verdetto) {
    v.motivi.forEachIndexed { i, m ->
        val d = v.difetti.getOrNull(i)
        val testo = if (d is Difetto.Suono && d.grafema != null) buildAnnotatedString {
            append("✗ ${Math.round(d.voto)} in «")
            val w = d.parola.testo
            append(w.substring(0, d.grafema.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) {
                append(w.substring(d.grafema))
            }
            append(w.substring(d.grafema.last + 1))
            append("» · suono /${d.fonema}/, sotto ${d.soglia}")
        } else AnnotatedString("✗ $m")
        Text(testo, color = Color(0xFFC62828), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Risultato(e: Esito, parola: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val verdetto = remember(e) { Giudizio.di(e, Servizi.prefs, parola) }
        val buona = verdetto.buona
        Text(
            "${if (buona) "✅ Good" else "🔁 Da ripetere"} · ${uno(e.pronuncia)}/100",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (buona) colore(e.pronuncia) else Color(0xFFC62828),
        )
        // Perché no: un «Da ripetere» con 85 sotto si legge come un errore,
        // se non si dice quale controllo l'ha fermato.
        MotiviVerdetto(verdetto)
        verdetto.note.forEach {
            Text("ℹ️ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RigaPunteggio("Accuratezza", e.accuratezza)
        RigaPunteggio("Fluidità", e.fluidita)
        RigaPunteggio("Completezza", e.completezza)
        e.ritmo?.let { RigaPunteggio("Ritmo e intonazione", it) }
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
        val peggiori = if (buona) e.peggiori() else verdetto.daCurare
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
