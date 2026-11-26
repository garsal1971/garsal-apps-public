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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Tendina
import com.garsal.appsphere.core.Supabase
import com.garsal.appsphere.core.larghezzaPulsanti
import com.garsal.appsphere.core.messaggioBreve
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Una riga di `msg_messaggi`, letta col JWT (policy SELECT del proprietario). */
private data class Archiviato(
    val fonte: String, val mittente: String?, val chat: String?, val testo: String, val quando: String,
) {
    /** Come in [Inoltro.Messaggio.chiave]: chi si filtra. */
    val chiave: String get() = (if (fonte == Inoltro.FONTE_WA) chat ?: mittente else mittente) ?: "(sconosciuto)"
}

private val FORMATO = DateTimeFormatter.ofPattern("dd/MM HH:mm")

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun icona(fonte: String) = if (fonte == Inoltro.FONTE_SMS) "📩" else "💬"

private const val INOLTRA = "✓ Inoltra sempre"

/** Le scelte di «Mesi da importare»; null = tutti. */
private val MESI_IMPORTA = listOf(1 to "Ultimo mese", 3 to "Ultimi 3 mesi", 6 to "Ultimi 6 mesi",
    12 to "Ultimi 12 mesi", null to "Tutti")
private const val FILTRA = "✗ Filtra sempre"

/**
 * ⚠️ Cancella dall'archivio i messaggi di un mittente/chat (policy DELETE del proprietario,
 * `20261126120000_msg_messaggi_delete.sql`). Per WhatsApp la chiave è la chat; una chat
 * singola ha `chat` NULL e la chiave è il mittente.
 */
private suspend fun cancellaDaArchivio(r: Archiviato) = withContext(Dispatchers.IO) {
    Supabase.client().postgrest.from("msg_messaggi").delete {
        filter {
            eq("fonte", r.fonte)
            when {
                r.fonte == Inoltro.FONTE_WA && r.chat != null -> eq("chat", r.chat)
                r.fonte == Inoltro.FONTE_WA -> { exact("chat", null); eq("mittente", r.mittente ?: "") }
                else -> eq("mittente", r.mittente ?: "")
            }
        }
    }
}

/**
 * 📨 Inoltro messaggi: permessi, interruttori, ⏳ messaggi da decidere, elenchi, coda,
 * prova e l'archivio letto dal DB (con «✗ Filtra» per riga).
 * Si apre dal ⚙️ della home → «📨 Inoltro messaggi», o dalla notifica dei messaggi da decidere.
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
    var filtroFonte by remember { mutableStateOf<String?>(null) }
    var cerca by remember { mutableStateOf("") }
    var daFiltrare by remember { mutableStateOf<Archiviato?>(null) }
    var avviso by remember { mutableStateOf<String?>(null) }
    var importando by remember { mutableStateOf(false) }
    var mesi by remember { mutableStateOf<Int?>(3) }   // null = tutti

    fun importa() {
        importando = true
        scope.launch {
            runCatching { ImportaSms.importa(ctx, mesi) }
                .onSuccess { r ->
                    avviso = "📥 Impianto: ${r.inviati} SMS in invio, ${r.filtrati} filtrati, ${r.gia} già in coda. " +
                        "Quelli già in archivio non si raddoppiano."
                }
                .onFailure { avviso = "Importazione non riuscita: ${it.messaggioBreve()}" }
            importando = false
            rifai()
        }
    }
    val chiediLettura = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) importa() else avviso = "Senza il permesso di leggere gli SMS non posso importarli."
    }

    fun caricaArchivio() {
        caricando = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    Supabase.client().postgrest.from("msg_messaggi")
                        .select(Columns.raw("fonte,mittente,chat,testo,ricevuto_il")) {
                            order("ricevuto_il", Order.DESCENDING)
                            limit(200)
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

    daFiltrare?.let { r ->
        val quanti = archivio.count { it.fonte == r.fonte && Inoltro.norm(it.chiave) == Inoltro.norm(r.chiave) }
        AlertDialog(
            onDismissRequest = { daFiltrare = null },
            title = { Text("Filtrare «${r.chiave}»?") },
            text = {
                Text(
                    "Da ora i messaggi ${if (r.fonte == Inoltro.FONTE_SMS) "SMS di questo mittente" else "di questa chat WhatsApp"} " +
                        "non partono più. I suoi messaggi già in archivio ($quanti fra quelli caricati) " +
                        "vengono cancellati: da qui non si torna indietro.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    daFiltrare = null
                    Inoltro.metti(ctx, r.fonte, r.chiave, inoltra = false)
                    Inoltro.annota(ctx, "✗ filtra sempre ${r.fonte} ${r.chiave} (dall'archivio)")
                    rifai()
                    scope.launch {
                        runCatching { cancellaDaArchivio(r) }
                            .onSuccess { avviso = "«${r.chiave}» filtrato e cancellato dall'archivio." }
                            .onFailure { avviso = "Filtrato, ma l'archivio non è stato cancellato: ${it.messaggioBreve()}" }
                        caricaArchivio()
                    }
                }) { Text("✗ Filtra e cancella") }
            },
            dismissButton = { TextButton(onClick = { daFiltrare = null }) { Text("Annulla") } },
        )
    }

    Scaffold(topBar = { GarsalTopBar(titolo = "📨 Inoltro messaggi", onIndietro = onIndietro) }) { p ->
        // Si rilegge tutto a ogni `giro`: sono preferenze e file piccoli.
        key(giro) { Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            avviso?.let { Text(it, color = Palette.accent, style = MaterialTheme.typography.bodyMedium) }

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

            Riquadro("🎚 Fonti") {
                Interruttore("Inoltra gli SMS", Inoltro.smsAcceso(ctx)) { Inoltro.setSms(ctx, it); rifai() }
                Interruttore("Inoltra WhatsApp", Inoltro.waAcceso(ctx)) { Inoltro.setWa(ctx, it); rifai() }
                Nota(
                    "Nessun filtro automatico dei codici: filtra il mittente. Un mittente o una chat mai visti finiscono in " +
                        "«⏳ Da decidere» e non partono finché non scegli.",
                )
            }

            Riquadro("📥 SMS già sul telefono") {
                Nota(
                    "L'impianto: tutti gli SMS ricevuti del periodo vanno nella tabella, senza passare da " +
                        "«⏳ Da decidere». Restano fuori solo i mittenti filtrati. Gli elenchi non cambiano: " +
                        "da dopo, un mittente mai deciso torna a passare dalla scelta. " +
                        "Quelli già in archivio non si raddoppiano.",
                )
                Tendina(
                    etichetta = "Mesi da importare",
                    scelto = MESI_IMPORTA.first { it.first == mesi }.second,
                    voci = MESI_IMPORTA.map { (it.first?.toString() ?: "tutti") to it.second },
                ) { k -> mesi = k.toIntOrNull() }
                if (mesi == null || mesi!! > 3) {
                    Nota(
                        "⚠️ La pulizia notturna cancella dalla tabella i messaggi più vecchi di 90 giorni: " +
                            "quelli oltre i 3 mesi spariranno la notte stessa.",
                    )
                }
                Pillola(if (importando) "Importo…" else "📥 Importa SMS dal telefono", Palette.accent) {
                    if (importando) return@Pillola
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
                        PackageManager.PERMISSION_GRANTED) importa()
                    else chiediLettura.launch(Manifest.permission.READ_SMS)
                }
            }

            DaDecidere(ctx, ::rifai)

            Riquadro("📋 Elenchi") {
                EditorLista(ctx, "📩 SMS inoltrati", Inoltro.FONTE_SMS, true, "numero o sigla (es. UniCredit)", ::rifai)
                EditorLista(ctx, "📩 SMS filtrati", Inoltro.FONTE_SMS, false, "numero o sigla", ::rifai)
                EditorLista(ctx, "💬 Chat WhatsApp inoltrate", Inoltro.FONTE_WA, true, "nome come in WhatsApp", ::rifai)
                EditorLista(ctx, "💬 Chat WhatsApp filtrate", Inoltro.FONTE_WA, false, "nome come in WhatsApp", ::rifai)
            }

            Riquadro("📤 Invio") {
                val inCoda = Inoltro.coda(ctx).size
                Text(if (inCoda == 0) "Coda vuota" else "$inCoda in attesa di rete o di login")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pillola("Invia ora", Palette.accent) { Inoltro.avviaInvio(ctx); rifai() }
                    Pillola("🧪 Test", Palette.secondary) {
                        // ⚠️ La prova salta i filtri: deve arrivare anche con gli SMS spenti.
                        Inoltro.accoda(
                            ctx,
                            listOf(Inoltro.Messaggio(Inoltro.FONTE_SMS, "Prova AppSphere", null,
                                "Messaggio di prova del ${java.time.LocalDateTime.now().format(FORMATO)}",
                                System.currentTimeMillis())),
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

            Riquadro("🗂 In archivio") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pillola(if (caricando) "Carico…" else "↻ Rileggi", Palette.accent) { if (!caricando) caricaArchivio() }
                }
                Pillole(listOf("Tutti", "📩 SMS", "💬 WhatsApp"),
                    scelta = when (filtroFonte) { null -> 0; Inoltro.FONTE_SMS -> 1; else -> 2 }) { i ->
                    filtroFonte = when (i) { 0 -> null; 1 -> Inoltro.FONTE_SMS; else -> Inoltro.FONTE_WA }
                }
                OutlinedTextField(
                    value = cerca, onValueChange = { cerca = it }, singleLine = true,
                    placeholder = { Text("Cerca in mittente, chat o testo") }, modifier = Modifier.fillMaxWidth(),
                )
                erroreArchivio?.let { Text("Non letto: $it", color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
                val q = cerca.trim().lowercase()
                val mostrati = archivio.filter { r ->
                    (filtroFonte == null || r.fonte == filtroFonte) &&
                        (q.isEmpty() || listOfNotNull(r.mittente, r.chat, r.testo).any { it.lowercase().contains(q) })
                }
                if (mostrati.isEmpty() && erroreArchivio == null && !caricando) Nota("Niente da mostrare.")
                Text("${mostrati.size} su ${archivio.size} caricati (gli ultimi 200)",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                mostrati.forEach { m ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val chi = listOfNotNull(m.chat, m.mittente).joinToString(" · ")
                        Text("${icona(m.fonte)} $chi · ${m.quando}",
                            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(m.testo, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                        Text("✗ Filtra «${m.chiave}»", color = Palette.danger, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable { daFiltrare = m }.padding(vertical = 4.dp))
                    }
                }
                Nota("I messaggi più vecchi di 90 giorni si cancellano da soli.")
            }
        } }
    }
}

/** ⏳ I messaggi da decidere, un riquadro per mittente o chat. */
@Composable
private fun DaDecidere(ctx: Context, onCambio: () -> Unit) {
    val attesa = Inoltro.attesa(ctx)
    val gruppi = attesa.groupBy { it.fonte to Inoltro.norm(it.chiave) }.values.toList()
    val larghezza = larghezzaPulsanti(listOf(INOLTRA, FILTRA))
    Riquadro("⏳ Da decidere (${attesa.size})") {
        if (gruppi.isEmpty()) {
            Nota("Nessun messaggio in attesa. Quelli di mittenti o chat nuovi compaiono qui, con una notifica.")
            return@Riquadro
        }
        Nota("Restano sul telefono e non partono finché non scegli. Dopo 30 giorni si buttano.")
        gruppi.forEach { msgs ->
            val primo = msgs.first()
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${icona(primo.fonte)} ${primo.chiave} · ${msgs.size}",
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                msgs.take(3).forEach { m ->
                    val quando = Instant.ofEpochMilli(m.ricevutoIl).atZone(ZoneId.systemDefault()).format(FORMATO)
                    val chi = if (m.chat != null && m.mittente != null) "${m.mittente}: " else ""
                    Text("$quando · $chi${m.testo}", style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                if (msgs.size > 3) Nota("…e altri ${msgs.size - 3}")
                RigaScorrevole(disposizione = Arrangement.spacedBy(8.dp)) {
                    Pillola(INOLTRA, Palette.success, larghezza) {
                        Inoltro.decidi(ctx, primo.fonte, primo.chiave, inoltra = true); onCambio()
                    }
                    Pillola(FILTRA, Palette.danger, larghezza) {
                        Inoltro.decidi(ctx, primo.fonte, primo.chiave, inoltra = false); onCambio()
                    }
                }
            }
        }
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
private fun Pillole(testi: List<String>, scelta: Int? = null, onTocco: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        testi.forEachIndexed { i, t ->
            val accesa = i == scelta
            Text(
                t,
                Modifier
                    .background(if (accesa) Palette.accent else Palette.cardBg, RoundedCornerShape(16.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
                    .clickable { onTocco(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = if (accesa) Palette.light else Palette.dark,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Un elenco di una fonte; aggiungere passa da [Inoltro.metti], che toglie la voce dall'elenco opposto. */
@Composable
private fun EditorLista(ctx: Context, titolo: String, fonte: String, inoltrati: Boolean, suggerimento: String, onCambio: () -> Unit) {
    val (lInoltrati, lFiltrati) = Inoltro.elenchi(fonte)
    val chiave = if (inoltrati) lInoltrati else lFiltrati
    val voci = Inoltro.lista(ctx, chiave)
    var nuovo by remember(chiave) { mutableStateOf("") }
    Text(titolo, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    if (voci.isNotEmpty()) {
        Pillole(voci.map { "$it  ✕" }) { i -> Inoltro.setLista(ctx, chiave, voci - voci[i]); onCambio() }
    } else {
        Nota("Vuoto.")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = nuovo, onValueChange = { nuovo = it }, singleLine = true,
            placeholder = { Text(suggerimento) }, modifier = Modifier.weight(1f),
        )
        Pillola("＋", Palette.accent) {
            if (nuovo.isNotBlank()) { Inoltro.metti(ctx, fonte, nuovo, inoltrati); nuovo = ""; onCambio() }
        }
    }
}
