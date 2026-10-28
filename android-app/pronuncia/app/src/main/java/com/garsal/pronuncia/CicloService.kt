package com.garsal.pronuncia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Il servizio in primo piano che tiene vivo il ciclo a schermo spento.
 *
 * ⚠️ Tipo `microphone`: da Android 14 lo si può avviare solo con RECORD_AUDIO
 * già concesso e con l'app visibile — cioè dal tocco su «Avvia», che è l'unico
 * posto da cui parte. Il permesso si controlla prima, nella schermata.
 *
 * ⚠️ Il wake lock parziale non è prudenza: a schermo spento la CPU si addormenta
 * anche con un servizio in primo piano, e il ciclo si fermerebbe fra una frase e
 * l'altra. Ha un tetto (4 ore) perché un lock dimenticato scarica il telefono.
 */
class CicloService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lavoro: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FERMA) { ferma(); return START_NOT_STICKY }
        if (lavoro?.isActive == true) return START_NOT_STICKY
        Servizi.avvia(this)
        canale()
        try {
            ServiceCompat.startForeground(
                this, ID, notifica(StatoCiclo(attivo = true, cosa = "Avvio…")),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            Ciclo.stato.value = StatoCiclo(nota = "Non posso usare il microfono in secondo piano: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pronuncia:ciclo")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        Ciclo.stato.value = StatoCiclo(attivo = true)
        lavoro = scope.launch {
            val osserva = launch { Ciclo.stato.collect { aggiornaNotifica(it) } }
            val nota = try {
                Ciclo.esegui(this@CicloService); null
            } catch (e: CancellationException) {
                null
            } catch (e: SessioneScaduta) {
                Servizi.connesso.value = false
                "Sessione scaduta: rientra con Google."
            } catch (e: Exception) {
                "⚠️ ${e.message ?: e.javaClass.simpleName}"
            }
            osserva.cancel()
            Ciclo.stato.update { it.copy(attivo = false, cosa = "", nota = nota ?: it.nota) }
            fine()
        }
        return START_NOT_STICKY
    }

    private fun ferma() {
        lavoro?.cancel()
        Ciclo.stato.update { it.copy(attivo = false, cosa = "") }
        fine()
    }

    private fun fine() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lavoro?.cancel()
        scope.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        Ciclo.stato.update { it.copy(attivo = false, cosa = "") }
        super.onDestroy()
    }

    private fun canale() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANALE, "Mani libere", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Il ciclo di pronuncia in corso" },
            )
        }
    }

    private fun aggiornaNotifica(s: StatoCiclo) {
        if (!s.attivo) return
        getSystemService(NotificationManager::class.java).notify(ID, notifica(s))
    }

    private fun notifica(s: StatoCiclo): Notification {
        val apri = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CicloService::class.java).setAction(FERMA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val titolo = listOf(s.cosa.ifBlank { "Mani libere" }, s.tentativo.takeIf { it > 0 }?.let { "tentativo $it/${Ciclo.TENTATIVI}" })
            .filterNotNull().joinToString(" · ")
        return NotificationCompat.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titolo)
            .setContentText(s.frase?.testo ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.frase?.testo ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(apri)
            .addAction(0, "⏹ Ferma", stop)
            .build()
    }

    companion object {
        const val FERMA = "com.garsal.pronuncia.FERMA"
        private const val CANALE = "ciclo"
        private const val ID = 7
    }
}
