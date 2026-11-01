package com.garsal.appsphere.abituati

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Pillola
import com.garsal.appsphere.core.RigaScorrevole
import com.garsal.appsphere.core.Supabase
import com.garsal.appsphere.core.Tendina
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 🪥 Lo spazzolino Oral-B che spunta da sé un'abitudine.
 *
 * Gli spazzolini Oral-B «smart» (serie iO, Genius) trasmettono di continuo via
 * Bluetooth un annuncio coi dati del costruttore (Procter & Gamble, id 220):
 * stato (3 = in funzione), e il tempo del lavaggio in corso in minuti e secondi.
 * Non serve abbinarli: si ascolta e basta, come fa Home Assistant.
 *
 * | byte | cosa |
 * |---|---|
 * | 3 | stato — 3 = in funzione, 2 = fermo |
 * | 5, 6 | minuti e secondi del lavaggio |
 *
 * ⚠️ **L'ascolto lo fa il chip Bluetooth, non l'app**: [registra] consegna ad
 * Android un filtro con un `PendingIntent`, e il sistema sveglia
 * [SpazzolinoReceiver] solo quando vede quello spazzolino. Niente servizio e
 * niente notifica fissa. Il filtro non sopravvive al riavvio del telefono né
 * all'aggiornamento dell'app: lo rifanno il receiver (BOOT_COMPLETED,
 * MY_PACKAGE_REPLACED) e l'apertura di AppSphere.
 *
 * ⚠️ **Le regole che decidono la spunta** (stanno tutte qui, in [decidi] e
 * [scegliOrario]):
 * - conta un lavaggio di almeno [MINIMO_SECONDI];
 * - l'orario è quello **più vicino** all'ora del lavaggio; fra mezzanotte e le
 *   3 il lavaggio vale per il giorno prima (la sera tardi);
 * - un orario che ha già una riga — ✓, ✕ o mancato — **non si tocca**: una ✕
 *   messa a mano non si sovrascrive;
 * - si scrive solo da `hb_set_completion`, come ogni altra spunta.
 */
object Spazzolino {

    private const val TAG = "AppSphereSpazzolino"
    private const val PREFS = "spazzolino"

    /** Il costruttore negli annunci Bluetooth: Procter & Gamble. */
    const val ORAL_B = 220

    /** Quanto deve durare un lavaggio per contare. */
    const val MINIMO_SECONDI = 120

    /** Stato «in funzione» nell'annuncio. */
    private const val IN_FUNZIONE = 3

    /** Un annuncio «fermo» col tempo pieno vale se il lavaggio era in corso da poco. */
    private const val SESSIONE_MS = 10 * 60 * 1000L

    const val AZIONE_SCANSIONE = "com.garsal.appsphere.SPAZZOLINO_SCANSIONE"

    private const val K_INDIRIZZO = "indirizzo"
    private const val K_ABITUDINE = "abitudine_id"
    private const val K_IN_CORSO = "in_corso_ms"
    private const val K_ULTIMA_CHIAVE = "ultima_chiave"
    private const val K_ULTIMO_SEGNALE = "ultimo_segnale"
    private const val K_ULTIMO_ESITO = "ultimo_esito"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ora = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun indirizzo(ctx: Context): String? = prefs(ctx).getString(K_INDIRIZZO, null)
    fun abitudineId(ctx: Context): String? = prefs(ctx).getString(K_ABITUDINE, null)
    fun ultimoSegnale(ctx: Context): String? = prefs(ctx).getString(K_ULTIMO_SEGNALE, null)
    fun ultimoEsito(ctx: Context): String? = prefs(ctx).getString(K_ULTIMO_ESITO, null)

    fun scegliAbitudine(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(K_ABITUDINE, id).apply()
    }

    fun abbina(ctx: Context, indirizzo: String?) {
        prefs(ctx).edit().putString(K_INDIRIZZO, indirizzo).remove(K_ULTIMA_CHIAVE).apply()
        registra(ctx)
    }

    // ── Permessi e Bluetooth ────────────────────────────────────────────

    /** Il permesso che serve per ascoltare: «Dispositivi nelle vicinanze» da Android 12. */
    val permessi: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun permessoConcesso(ctx: Context): Boolean = permessi.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun scanner(ctx: Context): BluetoothLeScanner? {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeScanner
    }

    fun bluetoothAcceso(ctx: Context): Boolean =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private fun filtro(indirizzo: String?): List<ScanFilter> = listOf(
        ScanFilter.Builder()
            .setManufacturerData(ORAL_B, ByteArray(0))
            .apply { if (indirizzo != null) setDeviceAddress(indirizzo) }
            .build()
    )

    private fun intentoScansione(ctx: Context): PendingIntent {
        val i = Intent(ctx, SpazzolinoReceiver::class.java).setAction(AZIONE_SCANSIONE)
        // MUTABLE: è il sistema a riempirlo coi risultati della scansione.
        return PendingIntent.getBroadcast(
            ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * (Ri)consegna ad Android il filtro dello spazzolino abbinato. Si può
     * chiamare quante volte si vuole: prima ferma quello vecchio.
     * Senza spazzolino abbinato, abitudine scelta o permesso non fa niente.
     */
    @SuppressLint("MissingPermission")
    fun registra(ctx: Context) {
        val app = ctx.applicationContext
        val pi = intentoScansione(app)
        val sc = scanner(app)
        if (sc == null || !permessoConcesso(app)) return
        runCatching { sc.stopScan(pi) }
        val indirizzo = indirizzo(app) ?: return
        if (abitudineId(app) == null) return
        val impostazioni = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val esito = runCatching { sc.startScan(filtro(indirizzo), impostazioni, pi) }
        Log.i(TAG, "ascolto registrato su $indirizzo: ${esito.getOrNull() ?: esito.exceptionOrNull()}")
    }

    // ── Lettura dell'annuncio ───────────────────────────────────────────

    data class Segnale(val indirizzo: String, val stato: Int, val secondi: Int, val grezzo: String) {
        val inFunzione get() = stato == IN_FUNZIONE
        val testoStato get() = when (stato) {
            2 -> "fermo"; 3 -> "in funzione"; 4 -> "in carica"; 9 -> "spento"; 115 -> "a riposo"
            else -> "stato $stato"
        }
        val tempo get() = "%d:%02d".format(secondi / 60, secondi % 60)
    }

    @SuppressLint("MissingPermission")
    fun leggi(r: ScanResult): Segnale? {
        val d = r.scanRecord?.getManufacturerSpecificData(ORAL_B) ?: return null
        if (d.size < 7) return null
        val b = { i: Int -> d[i].toInt() and 0xFF }
        return Segnale(
            indirizzo = r.device.address,
            stato = b(3),
            secondi = b(5) * 60 + b(6),
            grezzo = d.joinToString(" ") { "%02X".format(it) },
        )
    }

    // ── La decisione ────────────────────────────────────────────────────

    /** Chiamata dal receiver con i risultati della scansione. */
    suspend fun gestisci(ctx: Context, risultati: List<ScanResult>) {
        val p = prefs(ctx)
        val mio = indirizzo(ctx) ?: return
        val segnali = risultati.mapNotNull { leggi(it) }.filter { it.indirizzo.equals(mio, true) }
        if (segnali.isEmpty()) return
        val adesso = System.currentTimeMillis()
        // Il più lungo fra quelli arrivati insieme.
        val s = segnali.maxBy { it.secondi }
        p.edit().putString(
            K_ULTIMO_SEGNALE,
            "${LocalDateTime.now().format(ora)} · ${s.testoStato} · ${s.tempo}"
        ).apply()
        if (s.inFunzione) p.edit().putLong(K_IN_CORSO, adesso).apply()
        val inSessione = s.inFunzione || adesso - p.getLong(K_IN_CORSO, 0) < SESSIONE_MS
        if (s.secondi >= MINIMO_SECONDI && inSessione) segnaLavaggio(ctx, LocalDateTime.now())
    }

    /**
     * Il giorno e l'orario a cui va il lavaggio: l'orario più vicino, e fra
     * mezzanotte e le 3 il giorno prima. `null` come orario sulle giornaliere.
     */
    fun scegliOrario(ab: HbAbitudine, quando: LocalDateTime): Pair<LocalDate, String?> {
        var giorno = quando.toLocalDate()
        var minuti = quando.hour * 60 + quando.minute
        if (quando.hour < 3) {
            giorno = giorno.minusDays(1)
            minuti += 24 * 60
        }
        if (!ab.aPiuOrari) return giorno to null
        val slot = ab.orari.minBy { o ->
            val m = (o.take(2).toIntOrNull() ?: 0) * 60 + (o.drop(3).take(2).toIntOrNull() ?: 0)
            abs(m - minuti)
        }
        return giorno to slot
    }

    private fun chiave(ab: HbAbitudine, giorno: LocalDate, orario: String?) =
        if (ab.frequenza == "daily_multiple") "$giorno-${orario.orEmpty()}" else giorno.toString()

    private fun esito(ctx: Context, testo: String) {
        Log.i(TAG, testo)
        prefs(ctx).edit()
            .putString(K_ULTIMO_ESITO, "${LocalDateTime.now().format(ora)} · $testo").apply()
    }

    private suspend fun segnaLavaggio(ctx: Context, quando: LocalDateTime) {
        val p = prefs(ctx)
        val id = abitudineId(ctx) ?: return
        try {
            val auth = Supabase.client().auth
            if (auth.currentSessionOrNull() == null) auth.loadFromStorage()
            if (auth.currentSessionOrNull() == null) return esito(ctx, "non collegato: apri AppSphere")
            AuthRepo.assicuraSessione()

            val db = Supabase.client().postgrest
            val riga = db.from("hb_habits").select(Columns.ALL) { filter { eq("id", id) } }
                .decodeList<JsonObject>().firstOrNull()
            val ab = riga?.let { HbAbitudine.da(it) } ?: return esito(ctx, "abitudine non trovata")
            if (ab.stato != "active") return esito(ctx, "«${ab.nome}» non è attiva")

            val (giorno, orario) = scegliOrario(ab, quando)
            val k = chiave(ab, giorno, orario)
            if (p.getString(K_ULTIMA_CHIAVE, null) == "$id/$k") return

            val esistenti = db.from("hb_completions").select(Columns.list("id", "status")) {
                filter { eq("habit_id", id); eq("period_key", k) }
            }.decodeList<JsonObject>()
            if (esistenti.isNotEmpty()) {
                p.edit().putString(K_ULTIMA_CHIAVE, "$id/$k").apply()
                return esito(ctx, "$giorno ${orario.orEmpty()} già segnato: lasciato com'è")
            }

            val r = db.rpc("hb_set_completion", buildJsonObject {
                put("p_habit_id", id)
                put("p_giorno", giorno.toString())
                put("p_stato", "completed")
                if (orario != null) put("p_orario", orario)
                put("p_oggi", LocalDate.now().toString())
            }).decodeAs<JsonObject>()
            if (r["ok"]?.toString() == "false") return esito(ctx, "rifiutato: ${r["error"]}")
            p.edit().putString(K_ULTIMA_CHIAVE, "$id/$k").apply()
            esito(ctx, "✓ segnato $giorno ${orario.orEmpty()}")
        } catch (e: Exception) {
            esito(ctx, "errore: ${e.message?.lineSequence()?.firstOrNull()?.take(80)}")
        }
    }

    /** Per il receiver: lavora fuori dal thread principale entro il tempo concesso. */
    fun inBackground(blocco: suspend () -> Unit, fine: () -> Unit) {
        scope.launch {
            try { withTimeoutOrNull(9_000) { blocco() } } finally { fine() }
        }
    }
}

/**
 * Svegliato da Android quando vede lo spazzolino, e al riavvio o
 * all'aggiornamento dell'app per riconsegnare il filtro.
 */
class SpazzolinoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Spazzolino.AZIONE_SCANSIONE -> {
                @Suppress("DEPRECATION")
                val risultati: List<ScanResult> =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableArrayListExtra(
                            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
                        ).orEmpty()
                    else
                        intent.getParcelableArrayListExtra<ScanResult>(
                            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
                        ).orEmpty()
                if (risultati.isEmpty()) return
                val attesa = goAsync()
                Spazzolino.inBackground({ Spazzolino.gestisci(context, risultati) }) { attesa.finish() }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                Spazzolino.registra(context)
        }
    }
}

// ── ⚙️ Il riquadro nelle Impostazioni di Abituati ───────────────────────────

@SuppressLint("MissingPermission")
@Composable
internal fun RiquadroSpazzolino(abitudini: List<HbAbitudine>) {
    val ctx = LocalContext.current
    var abitudineId by remember { mutableStateOf(Spazzolino.abitudineId(ctx)) }
    var indirizzo by remember { mutableStateOf(Spazzolino.indirizzo(ctx)) }
    var permesso by remember { mutableStateOf(Spazzolino.permessoConcesso(ctx)) }
    var cerca by remember { mutableStateOf(false) }
    var giro by remember { mutableStateOf(0) }
    val trovati = remember { mutableStateMapOf<String, Spazzolino.Segnale>() }

    val chiedi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permesso = Spazzolino.permessoConcesso(ctx)
        if (permesso) Spazzolino.registra(ctx)
    }

    // La ricerca in primo piano, per l'abbinamento: 30 secondi e si ferma da sé.
    if (cerca) DisposableEffect(Unit) {
        val sc = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Spazzolino.leggi(result)?.let { trovati[it.indirizzo] = it }
            }
        }
        val filtri = listOf(ScanFilter.Builder().setManufacturerData(Spazzolino.ORAL_B, ByteArray(0)).build())
        val ok = runCatching {
            sc!!.startScan(filtri, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }.isSuccess
        val stop = CoroutineScope(Dispatchers.Main).launch { delay(30_000); cerca = false }
        if (!ok) cerca = false
        onDispose { stop.cancel(); runCatching { sc?.stopScan(cb) } }
    }

    val scegliibili = abitudini.filter {
        it.stato == "active" && (it.frequenza == "daily" || it.frequenza == "daily_multiple")
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Un lavaggio di almeno ${Spazzolino.MINIMO_SECONDI / 60} minuti spunta l'abitudine, " +
                "sull'orario più vicino. Il telefono dev'essere a pochi metri.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
        )

        Tendina(
            etichetta = "Abitudine da spuntare",
            scelto = scegliibili.firstOrNull { it.id == abitudineId }?.nome ?: "— nessuna —",
            voci = listOf("" to "— nessuna —") + scegliibili.map { it.id to it.nome },
        ) { scelta ->
            abitudineId = scelta.ifEmpty { null }
            Spazzolino.scegliAbitudine(ctx, abitudineId)
            Spazzolino.registra(ctx)
        }

        if (!Spazzolino.bluetoothAcceso(ctx)) {
            Text("⚠️ Il Bluetooth è spento: finché resta così i lavaggi non si vedono.", color = Palette.danger)
        }

        if (!permesso) {
            Text("Serve il permesso «Dispositivi nelle vicinanze».", style = MaterialTheme.typography.bodySmall)
            RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                Pillola("🔓 Concedi", Palette.secondary) { chiedi.launch(Spazzolino.permessi) }
            }
        } else {
            Text(
                "Spazzolino: " + (indirizzo ?: "non abbinato"),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
            )
            RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                Pillola(if (cerca) "⏳ Cerco…" else "🔍 Cerca", Palette.secondary) {
                    trovati.clear(); cerca = true
                }
                if (indirizzo != null) Pillola("✕ Scollega", Palette.danger) {
                    indirizzo = null; Spazzolino.abbina(ctx, null)
                }
            }
            if (cerca) Text("Accendi lo spazzolino vicino al telefono…", style = MaterialTheme.typography.bodySmall)
            trovati.values.forEach { s ->
                RigaScorrevole(Arrangement.spacedBy(8.dp)) {
                    Pillola("🪥 ${s.indirizzo} · ${s.testoStato}", Palette.success) {
                        indirizzo = s.indirizzo; cerca = false
                        Spazzolino.abbina(ctx, s.indirizzo)
                    }
                }
            }
        }

        // Il controllo che funziona: cosa ha visto e cosa ha fatto l'ultima volta.
        key(giro) {
            Text("Ultimo segnale: " + (Spazzolino.ultimoSegnale(ctx) ?: "—"), style = MaterialTheme.typography.bodySmall)
            Text("Ultimo esito: " + (Spazzolino.ultimoEsito(ctx) ?: "—"), style = MaterialTheme.typography.bodySmall)
        }
        RigaScorrevole(Arrangement.spacedBy(8.dp)) {
            Pillola("🔄 Rileggi", Palette.muted) { giro++ }
            Pillola("🔋 Batteria", Palette.accent) {
                // Xiaomi: «Nessuna restrizione» e Avvio automatico stanno nelle info dell'app.
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        Text(
            "Su Xiaomi: Avvio automatico acceso, Risparmio batteria «Nessuna restrizione», " +
                "e AppSphere bloccata nelle app recenti.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
        )
    }
}
