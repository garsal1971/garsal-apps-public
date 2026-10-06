package com.garsal.sos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast

/**
 * Il giro di countdown, dalla pressione del bottone alla risposta.
 *
 * È un servizio in primo piano e non un pezzo di MainActivity per una ragione
 * sola: il countdown deve continuare anche se l'Activity viene distrutta —
 * schermo spento, telefono ruotato, memoria che scarseggia. Un countdown che
 * finisce perché il sistema ha chiuso una schermata sarebbe un blocco che si
 * apre da sé nel momento peggiore.
 *
 * Qui dentro non c'è nessuna regola: la durata la dice il server (o la cache,
 * se la rete manca) e punti e durata successiva li calcola sos_session_finish.
 */
class SosSessionService : Service(), SosOverlay.Callbacks {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var api: SosApi

    private var overlay: SosOverlay? = null
    private var tipo: SosType? = null

    private var sessionId: String? = null
    private var avvioMs = 0L
    private var totali = 0          // durata del giro, in secondi
    private var rimasti = 0
    private var finito = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        api = SosApi(this)
        avviaInPrimoPiano()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tipo != null) return START_NOT_STICKY   // giro già in corso: non se ne apre un secondo

        val typeId = intent?.getStringExtra(EXTRA_TYPE_ID)
        val t = api.configInCache().firstOrNull { it.id == typeId }
        if (t == null) {
            Toast.makeText(this, "SOS non trovato: riapri l'app per aggiornare", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        tipo = t
        totali = t.seconds
        rimasti = t.seconds
        avvioMs = System.currentTimeMillis()

        val ov = SosOverlay(this, t, this)
        if (!ov.show()) {
            Toast.makeText(this,
                "Serve il permesso «Visualizza sopra altre app» per bloccare il telefono",
                Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        overlay = ov
        ov.mostraCountdown(rimasti, totali)
        handler.postDelayed(tick, 1000)

        // Il countdown è già partito: la sessione si apre in parallelo, perché
        // nel momento della crisi non si aspetta il giro di una chiamata HTTP.
        Thread {
            val avvio = api.avviaGiro(t.id)
            handler.post {
                sessionId = avvio.sessionId
                val s = avvio.seconds
                // La durata buona è quella del server. La si adotta solo nei primi
                // secondi: più avanti, allungare o accorciare quello che si sta
                // già guardando sembrerebbe un difetto, non una correzione.
                if (s != null && s != totali && System.currentTimeMillis() - avvioMs < 4000) {
                    totali = s
                    rimasti = s
                    overlay?.mostraCountdown(rimasti, totali)
                }
            }
        }.start()

        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (finito) return
            rimasti -= 1
            if (rimasti <= 0) {
                rimasti = 0
                finito = true
                vibra()
                overlay?.chiediComeEAndata(completato = true)
                return
            }
            overlay?.aggiornaCountdown(rimasti, totali)
            handler.postDelayed(this, 1000)
        }
    }

    // ── Risposta ────────────────────────────────────────────────────────────

    override fun onRisposta(outcome: SosOutcome, completato: Boolean) {
        val t = tipo ?: return
        finito = true
        handler.removeCallbacks(tick)
        overlay?.mostraAttesa()

        val trascorsi = ((System.currentTimeMillis() - avvioMs) / 1000).toInt()
        Thread {
            val esito = api.chiudiGiro(
                sessionId = sessionId,
                typeId = t.id,
                outcomeId = outcome.id,
                completato = completato,
                trascorsi = trascorsi,
                previsti = totali,
                avvioMs = avvioMs
            )
            // La configurazione va riletta subito: la durata del prossimo giro è
            // appena cambiata, e la home deve mostrare quella nuova.
            if (esito.ok) api.caricaConfig()
            handler.post {
                overlay?.mostraEsito(
                    punti = if (esito.ok) esito.points else outcome.points,
                    secondiProssimi = esito.secondsNext,
                    inCoda = esito.inCoda,
                    errore = if (esito.ok || esito.inCoda) null else "il server ha rifiutato il giro"
                )
            }
        }.start()
    }

    override fun onInterrotto() {
        finito = true
        handler.removeCallbacks(tick)
    }

    override fun onChiuso() {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.dismiss()
        overlay = null
        super.onDestroy()
    }

    // ── Servizio in primo piano ─────────────────────────────────────────────

    private fun avviaInPrimoPiano() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CANALE, "SOS in corso", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CANALE)
            .setContentTitle("SOS in corso")
            .setContentText("Il countdown sta girando.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground fallito: ${e.message}")
        }
    }

    private fun vibra() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 140, 220), -1))
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "SosSessionService"
        private const val CANALE = "sos_sessione"
        private const val NOTIF_ID = 4301
        const val EXTRA_TYPE_ID = "type_id"

        fun avvia(ctx: Context, typeId: String) {
            val i = Intent(ctx, SosSessionService::class.java).putExtra(EXTRA_TYPE_ID, typeId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
