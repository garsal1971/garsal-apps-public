package com.garsal.appsphere.peso

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Il ponte con Health Connect e con la bilancia Renpho — `HealthConnectBridge.kt`
 * e `openRenpho()` di `weight-quest.html`, qui senza passare da un
 * `JavascriptInterface`: l'app nativa chiama l'SDK direttamente.
 *
 * ⚠️ La finestra di lettura (90 giorni, +25 ore di margine) e il fatto che sia
 * la sola lettura del peso a servire sono le stesse del web — cambiando una
 * delle due cose va cambiata anche l'altra.
 */

/** Il pacchetto della bilancia, aperta da qui come `openRenpho()`. */
private const val PACCHETTO_RENPHO = "com.renpho.health"

/**
 * I permessi che servono: leggere il peso — mai scriverlo — e leggere lo
 * **storico**.
 *
 * ⚠️ Il secondo non è un di più. Senza, Health Connect lascia leggere solo i
 * dati scritti nei **30 giorni prima della concessione**: la finestra di 90
 * giorni che chiediamo qui sotto torna tagliata **senza nessun errore**, e la
 * sincronizzazione sembra funzionare benissimo su un terzo dei dati. È la
 * ragione per cui il 24 agosto 2026 la stessa bilancia ha reso 290 pesate
 * dall'APK WebView (permesso concesso mesi prima) e 37 da qui (concesso da
 * poco). Su un dispositivo dove il permesso non esiste resta semplicemente non
 * concesso, e si torna al comportamento di prima.
 */
internal val PERMESSI_SALUTE = setOf(
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    // Il grasso corporeo, per la massa grassa. ⚠️ Mai fatale: negato, le
    // pesate arrivano lo stesso, solo senza grasso — vedi [SaluteRepository.leggiPeso].
    HealthPermission.getReadPermission(BodyFatRecord::class),
)

/** Una pesata letta da Health Connect: istante, peso in kg e grasso in % (se c'è). */
data class PuntoSalute(val timestamp: Long, val pesoKg: Double, val grasso: Double? = null)

/**
 * La data locale della pesata — la stessa che finisce nella riga scritta
 * ([com.garsal.appsphere.peso.PesoRepository.rigaPuntoSalute]) e quella con
 * cui si riconoscono le pesate manuali diventate ridondanti: un solo posto
 * dove questo calcolo vive, invece di due che potrebbero disallinearsi.
 */
fun PuntoSalute.giorno(): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()

sealed class EsitoSalute {
    /**
     * [grassoNegato]: il permesso del grasso manca — le pesate sono arrivate
     * lo stesso, e la schermata lo dice invece di richiederlo in un giro senza fine.
     */
    data class Ok(val punti: List<PuntoSalute>, val grassoNegato: Boolean = false) : EsitoSalute()
    data class Errore(val messaggio: String) : EsitoSalute()
    /** Il permesso di lettura non è (ancora) concesso: il chiamante deve chiederlo e ritentare. */
    object PermessiRichiesti : EsitoSalute()
}

object SaluteRepository {

    fun sdkDisponibile(context: Context): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }
            .getOrDefault(false)

    /** Apre Renpho, o lo Store se non è installata — `openRenpho()` nel web. */
    fun apriRenpho(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PACCHETTO_RENPHO)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$PACCHETTO_RENPHO"),
                )
            )
        }
    }

    /**
     * Le pesate degli ultimi 90 giorni — `doSync()` nel web. Il margine di 25
     * ore sul limite superiore è lo stesso: copre pesate di oggi scritte con
     * un orologio del telefono leggermente avanti.
     *
     * ⚠️ Si seguono le pagine: `readRecords` ne restituisce al massimo
     * [PAGINA] per volta e le altre stanno dietro un `pageToken`. Fermarsi
     * alla prima pagina non darebbe un errore — darebbe qualche pesata in
     * meno, che è il modo peggiore di sbagliare.
     */
    suspend fun leggiPeso(context: Context): EsitoSalute = withContext(Dispatchers.IO) {
        if (!sdkDisponibile(context)) {
            return@withContext EsitoSalute.Errore(
                "Health Connect non è installato. Installalo dal Play Store e riprova."
            )
        }
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val fine = Instant.now().plus(25, ChronoUnit.HOURS)
            val inizio = Instant.now().minus(90, ChronoUnit.DAYS)

            val punti = mutableListOf<PuntoSalute>()
            var pagina: String? = null
            var giri = 0
            do {
                val risposta = client.readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = TimeRangeFilter(startTime = inizio, endTime = fine),
                        pageSize = PAGINA,
                        pageToken = pagina,
                    )
                )
                punti += risposta.records.map {
                    PuntoSalute(it.time.toEpochMilli(), it.weight.inKilograms)
                }
                pagina = risposta.pageToken
                giri++
                // Rete di sicurezza: un provider che restituisse sempre lo
                // stesso token ci terrebbe qui per sempre.
            } while (pagina != null && giri < MAX_PAGINE)

            // ── Il grasso corporeo, gemello di `HealthConnectBridge.doSync` ──
            // Letto a parte e MAI fatale: senza il permesso (o con una bilancia
            // che non lo misura) le pesate devono arrivare lo stesso.
            var grassoNegato = false
            val grassi = mutableListOf<Pair<Long, Double>>()
            try {
                var pg: String? = null
                var g = 0
                do {
                    val risposta = client.readRecords(
                        ReadRecordsRequest(
                            recordType = BodyFatRecord::class,
                            timeRangeFilter = TimeRangeFilter(startTime = inizio, endTime = fine),
                            pageSize = PAGINA,
                            pageToken = pg,
                        )
                    )
                    grassi += risposta.records.map { it.time.toEpochMilli() to it.percentage.value }
                    pg = risposta.pageToken
                    g++
                } while (pg != null && g < MAX_PAGINE)
            } catch (e: SecurityException) {
                grassoNegato = true
            } catch (e: Exception) {
                // Una lettura del grasso storta non deve fermare le pesate.
            }

            // Il grasso di una pesata è la misura della bilancia nello stesso
            // istante: Renpho li scrive insieme. Il più vicino entro dieci minuti;
            // più lontano sarebbe un'altra pesata.
            fun grassoPer(ts: Long): Double? =
                grassi.filter { kotlin.math.abs(it.first - ts) <= GRASSO_TOLLERANZA_MS }
                    .minByOrNull { kotlin.math.abs(it.first - ts) }?.second
                    ?.takeIf { it > 0.0 && it < 100.0 }

            EsitoSalute.Ok(punti.map { it.copy(grasso = grassoPer(it.timestamp)) }, grassoNegato)
        } catch (e: SecurityException) {
            EsitoSalute.PermessiRichiesti
        } catch (e: Exception) {
            EsitoSalute.Errore(e.message ?: "Errore Health Connect")
        }
    }

    private const val PAGINA = 1000
    private const val MAX_PAGINE = 20
    private const val GRASSO_TOLLERANZA_MS = 10 * 60 * 1000L
}
