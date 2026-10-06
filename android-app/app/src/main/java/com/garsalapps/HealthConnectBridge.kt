package com.garsalapps

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bridge JS ↔ Health Connect.
 *
 * I permessi vengono richiesti tramite PermissionController.createRequestPermissionResultContract()
 * in MainActivity — questo è il SOLO modo per cui HC Service riceva la notifica del grant
 * e sblocchi le successive chiamate readRecords().
 *
 * Timeout: ExecutorService + Future.get(25s). IBinder.transact() non è interrompibile
 * via Thread.interrupt(), quindi il timeout è "best effort" — se HC è completamente
 * bloccato il thread rimane, ma il callback JS arriva comunque entro 25s.
 */
class HealthConnectBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {
    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = Executors.newCachedThreadPool()

    // Guard anti-loop: apre la schermata permessi solo una volta per sessione
    private var permissionsRequested = false

    @JavascriptInterface
    fun isSdkAvailable(): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(activity) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun requestWeightSync(callbackId: String) {
        scope.launch(Dispatchers.IO) {
            notifyJS("requestWeightSync avviato")
            val json = syncWithHardTimeout(25_000L)
            Log.d("HCBridge", "Invio callback: ${json.take(80)}")
            notifyJS("callback in arrivo: ${json.take(60)}")
            callback(callbackId, json)
        }
    }

    private fun syncWithHardTimeout(timeoutMs: Long): String {
        notifyJS("Future submit...")
        val future = executor.submit<String> { doSync() }
        notifyJS("Future.get(${timeoutMs / 1000}s) attesa...")
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            notifyJS("Future completato OK")
            result
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            Log.e("HCBridge", "Timeout hard ${timeoutMs / 1000}s")
            notifyJS("Future TIMEOUT dopo ${timeoutMs / 1000}s!")
            """{"ok":false,"error":"HC non risponde (${timeoutMs / 1000}s). Riprova tra qualche secondo."}"""
        } catch (e: Exception) {
            val cause = e.cause ?: e
            Log.e("HCBridge", "Errore future: ${cause.javaClass.name}: ${cause.message}")
            notifyJS("Future eccezione: ${cause.javaClass.simpleName}")
            val cls = cause.javaClass.simpleName
            val msg = (cause.message ?: "Errore sconosciuto").take(150)
                .replace("\\", "/").replace("\"", "'").replace("\n", " ")
            """{"ok":false,"error":"[$cls] $msg"}"""
        }
    }

    private fun doSync(): String {
        return try {
            notifyJS("doSync: getSdkStatus...")
            val sdkStatus = HealthConnectClient.getSdkStatus(activity)
            Log.d("HCBridge", "SDK status: $sdkStatus")
            notifyJS("doSync: sdkStatus=$sdkStatus")
            if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
                val msg = when (sdkStatus) {
                    HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect non installato"
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Aggiorna Health Connect dal Play Store"
                    else -> "HC non disponibile (status $sdkStatus)"
                }
                return """{"ok":false,"error":"$msg"}"""
            }

            Log.d("HCBridge", "Creazione client HC...")
            notifyJS("doSync: getOrCreate...")
            val client = HealthConnectClient.getOrCreate(activity.applicationContext)
            notifyJS("doSync: client OK, avvio readRecords...")

            val end   = Instant.now().plus(25, ChronoUnit.HOURS)
            val start = Instant.now().minus(90, ChronoUnit.DAYS)
            Log.d("HCBridge", "readRecords: avvio (90 gg)...")

            // ⚠️ Si seguono le pagine: readRecords ne restituisce al massimo
            // PAGE_SIZE per volta e le altre stanno dietro un pageToken.
            // Fermarsi alla prima non darebbe un errore — darebbe qualche
            // pesata in meno, che è il modo peggiore di sbagliare.
            val records = runBlocking {
                val tutti = mutableListOf<WeightRecord>()
                var page: String? = null
                var giri = 0
                do {
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            recordType = WeightRecord::class,
                            timeRangeFilter = TimeRangeFilter(startTime = start, endTime = end),
                            pageSize = PAGE_SIZE,
                            pageToken = page
                        )
                    )
                    tutti += response.records
                    page = response.pageToken
                    giri++
                } while (page != null && giri < MAX_PAGES)
                tutti
            }

            Log.d("HCBridge", "readRecords: ${records.size} record")
            notifyJS("doSync: readRecords OK, ${records.size} record")

            // ── Il grasso corporeo, per «Ti pisasti?» ─────────────────────
            // ⚠️ Letto a parte e MAI fatale: senza il permesso del grasso (o
            // con una bilancia che non lo misura) le pesate devono arrivare
            // lo stesso, solo senza "fat". La pagina dice cosa manca.
            // Renpho scrive in Health Connect peso e grasso, NON l'acqua: per
            // questo l'obiettivo conta sulla massa grassa e non sul peso a secco.
            var grassoErr: String? = null
            val grassi: List<BodyFatRecord> = try {
                runBlocking {
                    val tutti = mutableListOf<BodyFatRecord>()
                    var page: String? = null
                    var giri = 0
                    do {
                        val response = client.readRecords(
                            ReadRecordsRequest(
                                recordType = BodyFatRecord::class,
                                timeRangeFilter = TimeRangeFilter(startTime = start, endTime = end),
                                pageSize = PAGE_SIZE,
                                pageToken = page
                            )
                        )
                        tutti += response.records
                        page = response.pageToken
                        giri++
                    } while (page != null && giri < MAX_PAGES)
                    tutti
                }
            } catch (e: SecurityException) {
                grassoErr = "PERMISSION"
                if (!permissionsRequested) {
                    permissionsRequested = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        activity.requestHealthConnectPermissions()
                    }
                }
                emptyList()
            } catch (e: Exception) {
                grassoErr = (e.message ?: e.javaClass.simpleName).take(80)
                    .replace("\\", "/").replace("\"", "'").replace("\n", " ")
                emptyList()
            }
            notifyJS("doSync: grasso ${grassi.size} record" + (grassoErr?.let { " ($it)" } ?: ""))

            // Il grasso di una pesata è la misura della bilancia nello stesso
            // istante: Renpho li scrive insieme. Si accetta il più vicino
            // entro GRASSO_TOLLERANZA_MS; più lontano sarebbe un'altra pesata.
            val grassoMs = grassi.map { it.time.toEpochMilli() to it.percentage.value }
            fun grassoPer(ts: Long): Double? =
                grassoMs.filter { kotlin.math.abs(it.first - ts) <= GRASSO_TOLLERANZA_MS }
                    .minByOrNull { kotlin.math.abs(it.first - ts) }?.second

            val points = records.joinToString(",") { r ->
                val ts = r.time.toEpochMilli()
                val f = grassoPer(ts)
                val grassoJson = if (f != null && f > 0 && f < 100) ""","fat":${String.format(Locale.US, "%.2f", f)}""" else ""
                """{"timestamp":$ts,"weight":${String.format(Locale.US, "%.2f", r.weight.inKilograms)}$grassoJson}"""
            }
            val errJson = grassoErr?.let { ""","fatError":"$it"""" } ?: ""
            """{"ok":true,"points":[$points],"fatCount":${grassi.size}$errJson}"""

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w("HCBridge", "Thread interrotto (timeout hard)")
            notifyJS("doSync: InterruptedException")
            """{"ok":false,"error":"HC non ha risposto in tempo. Riprova."}"""

        } catch (e: SecurityException) {
            Log.w("HCBridge", "SecurityException: ${e.message}")
            notifyJS("doSync: SecurityException — richiesta permessi")
            if (!permissionsRequested) {
                permissionsRequested = true
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    activity.requestHealthConnectPermissions()
                }
                """{"ok":false,"error":"PERMISSION_REQUESTED","retry":true}"""
            } else {
                permissionsRequested = false
                """{"ok":false,"error":"Permessi HC non attivi. Vai in Impostazioni → Connessione Salute → AppSfera Web e abilita la lettura del Peso."}"""
            }

        } catch (e: Exception) {
            Log.e("HCBridge", "Errore: ${e.javaClass.name}: ${e.message}", e)
            notifyJS("doSync: eccezione ${e.javaClass.simpleName}: ${(e.message ?: "").take(80)}")
            val cls = e.javaClass.simpleName
            val msg = (e.message ?: "Errore sconosciuto").take(150)
                .replace("\\", "/").replace("\"", "'").replace("\n", " ")
            """{"ok":false,"error":"[$cls] $msg"}"""
        }
    }

    /**
     * Invia un messaggio di diagnostica direttamente al log JS dell'app.
     * Permette di vedere l'esecuzione Kotlin senza accesso a logcat.
     */
    private fun notifyJS(msg: String) {
        val safe = msg.replace("\\", "/").replace("'", " ").replace("\n", " ").take(120)
        webView.post {
            webView.evaluateJavascript(
                "try{if(typeof log==='function')log('[KOTLIN] $safe');}catch(_e){}",
                null
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val MAX_PAGES = 20
        // Dieci minuti: Renpho scrive peso e grasso della stessa pesata a pochi
        // secondi di distanza, e due pesate vere non stanno mai così vicine.
        const val GRASSO_TOLLERANZA_MS = 10 * 60 * 1000L
    }

    private fun callback(callbackId: String, json: String) {
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        webView.post {
            webView.evaluateJavascript(
                "try{var _d=JSON.parse(atob('$b64'));if(window.__hcCallback_$callbackId)window.__hcCallback_$callbackId(_d);}catch(_e){if(typeof log==='function')log('[HC-ERR] '+(_e&&_e.message||_e));}",
                null
            )
        }
    }
}
