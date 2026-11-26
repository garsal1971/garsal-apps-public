package com.garsal.appsphere.inoltro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.Supabase
import com.garsal.appsphere.core.messaggioBreve
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Una riga di `msg_messaggi`, letta col JWT (policy SELECT del proprietario). */
private data class Archiviato(
    val fonte: String, val mittente: String?, val chat: String?, val testo: String, val quando: String,
)

private val FORMATO = DateTimeFormatter.ofPattern("dd/MM HH:mm")

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 📨 Inoltro messaggi: permessi, interruttori, elenchi, coda, prova e gli
 * ultimi messaggi già in archivio (dal DB, non dal telefono).
 * Si apre dal ⚙️ della home → «📨 Inoltro messaggi».
 */
@SuppressLint("BatteryLife")
@Composable
fun InoltroScreen(onIndietro: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var giro by remember { mutableIntStateOf(0) }   // ridisegno dopo ogni modifica
    fun rifai() { giro++ }

    var permSms by remember { mutableStateOf(false) }
    var permNotifiche by remember { mutableStateOf(false) }
    var permBatteria by remember { mutableStateOf(false) }
    fun leggiPermessi() {
        permSms = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        permNotifiche = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
        permBatteria = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }
    LifecycleResumeEffect(Unit) { leggiPermessi(); rifai(); onPauseOrDispose { } }
    val chiediSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        leggiPermessi()
    }

    var archivio by remember { mutableStateOf<List<Archiviato>>(emptyList()) }
    var erroreArchivio by remember { mutableStateOf<String?>(null) }
    var caricando by remember { mutableStateOf(false) }
    fun caricaArchivio() {
        caricando = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    Supabase.client().postgrest.from("msg_messaggi")
                        .select(Columns.raw("fonte,mittente,chat,testo,ricevuto_il")) {
                            order("ricevuto_il", Order.DESCENDING)
                            limit(30)
                        }.decodeList<JsonObject>()
                }
            }.onSuccess { righe ->
                erroreArchivio = null
                archivio = righe.map {
                    val q = it.s("ricevuto_il")?.let { t ->
                        runCatching {
                            OffsetDateTime.parse(t).atZoneSameInstant(ZoneId.systemDefault()).format(FORMATO)
                        }.getOrNull()
                    }
                    Archiviato(it.s("fonte").orEmpty(), it.s("mittente"), it.s("chat"), it.s("testo").orEmpty(), q ?: "")
                }
            }.onFailure { erroreArchivio = it.messaggioBreve() }
            caricando = false
        }
    }
    LaunchedEffect(Unit) { caricaArchivio() }

    Scaffold(topBar = { GarsalTopBar(titolo = "📨 Inoltro messaggi", onIndietro = onIndietro) }) { p ->
        // Si rilegge tutto a ogni `giro`: sono preferenze e un file piccolo.
        key(giro) { Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Riquadro("🔐 Permessi") {
                RigaPermesso("Ricezione SMS", permSms) { chiediSms.launch(Manifest.permission.RECEIVE_SMS) }
                RigaPermesso("Accesso alle notifiche (WhatsApp)", permNotifiche) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                RigaPermesso("Nessuna ottimizzazione batteria", permBatteria) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    )
                }
                if (!permSms || !permNotifiche) {
                    Nota(
                        "Se Android dice «impostazione con restrizioni»: Impostazioni → App → AppSphere → " +
                            "⋮ → «Consenti impostazioni con restrizioni», poi riprova.",
                    )
                }
            }

            Riquadro("📩 SMS") {
                Interruttore("Inoltra gli SMS", Inoltro.smsAcceso(ctx)) { Inoltro.setSms(ctx, it); rifai() }
                Nota("Vanno tutti, tranne i codici e i mittenti esclusi. Con «solo questi» pieno, vanno solo quelli.")
                EditorLista(ctx, "Mittenti esclusi", Inoltro.L_SMS_ESCLUSI, "numero o sigla (es. UniCredit)", ::rifai)
                EditorLista(ctx, "Solo questi mittenti (vuoto = tutti)", Inoltro.L_SMS_SOLO, "numero o sigla", ::rifai)
            }

            Riquadro("💬 WhatsApp") {
                val chat = Inoltro.lista(ctx, Inoltro.L_WA_CHAT)
                Interruttore("Inoltra WhatsApp", Inoltro.waAcceso(ctx)) { Inoltro.setWa(ctx, it); rifai() }
                if (chat.isEmpty()) Nota("Nessuna chat scelta: non parte niente finché non ne aggiungi una.")
                EditorLista(ctx, "Chat inoltrate (gruppo o persona)", Inoltro.L_WA_CHAT, "nome come in WhatsApp", ::rifai)
                val viste = Inoltro.chatViste(ctx).filter { !Inoltro.inLista(it, chat) }
                if (viste.isNotEmpty()) {
                    Text("Viste di recente — tocca per aggiungere:", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    Pillole(viste.map { "＋ $it" }) { i ->
                        Inoltro.setLista(ctx, Inoltro.L_WA_CHAT, chat + viste[i]); rifai()
                    }
                }
            }

            Riquadro("📤 Invio") {
                val inCoda = Inoltro.coda(ctx).size
                Text(if (inCoda == 0) "Coda vuota" else "$inCoda in attesa di rete o di login")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pillola("Invia ora", Palette.accent) { Inoltro.avviaInvio(ctx); rifai() }
                    Pillola("🧪 Test", Palette.secondary) {
                        val ora = System.currentTimeMillis()
                        // ⚠️ La prova salta i filtri: deve arrivare anche con gli SMS spenti.
                        Inoltro.accoda(
                            ctx,
                            listOf(Inoltro.Messaggio(Inoltro.FONTE_SMS, "Prova AppSphere", null,
                                "Messaggio di prova del ${java.time.LocalDateTime.now().format(FORMATO)}", ora)),
                        )
                        Inoltro.annota(ctx, "→ prova in coda")
                        Inoltro.avviaInvio(ctx)
                        rifai()
                    }
                }
                val reg = Inoltro.registro(ctx)
                if (reg.isNotEmpty()) {
                    Text("Registro (sul telefono, senza testi):", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    reg.take(15).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            Riquadro("🗂 Ultimi in archivio") {
                Pillola(if (caricando) "Carico…" else "↻ Rileggi", Palette.accent) { if (!caricando) caricaArchivio() }
                erroreArchivio?.let { Text("Non letto: $it", color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
                if (archivio.isEmpty() && erroreArchivio == null && !caricando) Nota("Ancora nessun messaggio in archivio.")
                archivio.forEach { m ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        val chi = listOfNotNull(m.chat, m.mittente).joinToString(" · ")
                        Text(
                            "${if (m.fonte == "sms") "📩" else "💬"} $chi · ${m.quando}",
                            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                        )
                        Text(m.testo, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    }
                }
                Nota("I messaggi più vecchi di 90 giorni si cancellano da soli.")
            }
        } }
    }
}

@Composable
private fun Riquadro(titolo: String, contenuto: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
            .background(Palette.cardBg, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(titolo, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        contenuto()
    }
}

@Composable
private fun Nota(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = Palette.muted)

@Composable
private fun RigaPermesso(nome: String, ok: Boolean, onConcedi: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${if (ok) "✅" else "❌"} $nome", Modifier.weight(1f))
        if (!ok) Pillola("Concedi", Palette.warning, onClick = onConcedi)
    }
}

@Composable
private fun Interruttore(nome: String, acceso: Boolean, onCambia: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(nome, Modifier.weight(1f))
        Switch(checked = acceso, onCheckedChange = onCambia)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pillole(testi: List<String>, onTocco: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        testi.forEachIndexed { i, t ->
            Text(
                t,
                Modifier.border(1.dp, Palette.border, RoundedCornerShape(16.dp))
                    .clickable { onTocco(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EditorLista(ctx: Context, titolo: String, chiave: String, suggerimento: String, onCambio: () -> Unit) {
    val voci = Inoltro.lista(ctx, chiave)
    var nuovo by remember(chiave) { mutableStateOf("") }
    Text(titolo, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    if (voci.isNotEmpty()) {
        Pillole(voci.map { "$it  ✕" }) { i -> Inoltro.setLista(ctx, chiave, voci - voci[i]); onCambio() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = nuovo, onValueChange = { nuovo = it }, singleLine = true,
            placeholder = { Text(suggerimento) }, modifier = Modifier.weight(1f),
        )
        Pillola("＋", Palette.accent) {
            if (nuovo.isNotBlank()) { Inoltro.setLista(ctx, chiave, voci + nuovo); nuovo = ""; onCambio() }
        }
    }
}
