package com.garsal.pronuncia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import kotlinx.coroutines.delay
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
 *
 * Fase 4:
 * - **audio focus**: una chiamata o il navigatore lo prendono, e il ciclo va in
 *   pausa; quando lo restituiscono riparte da sé. ⚠️ Una perdita DEFINITIVA
 *   (un'altra app di musica) non riparte da sola: resta in pausa finché non si
 *   preme Riprendi — ripartire sopra la musica di un altro sarebbe peggio.
 *   `setWillPauseWhenDucked`: il navigatore ci abbasserebbe il volume mentre
 *   ascoltiamo, e la sua voce finirebbe nella valutazione.
 * - **MediaSession** per i tasti delle cuffie: play/pausa = pausa/riprendi,
 *   avanti = «next», indietro = «repeat».
 * - **cuffie Bluetooth come microfono** (`Prefs.microfono`): il collegamento
 *   delle chiamate (SCO). ⚠️ Anche la voce passa di lì, in qualità telefonica:
 *   è l'unico modo affidabile su Android, ed è stato scelto sapendolo. Senza
 *   cuffie collegate si ascolta col telefono e lo si dice.
 */
class CicloService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lavoro: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var sessione: MediaSession? = null
    private var richiestaFocus: AudioFocusRequest? = null
    private var instradato = false
    /** La pausa l'ha messa il focus (e quindi la toglie il focus), non l'utente. */
    private var pausaDaFocus = false

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val focus = AudioManager.OnAudioFocusChangeListener { c ->
        when (c) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (!Ciclo.pausa.value) {
                pausaDaFocus = true; Ciclo.pausa.value = true
            }
            AudioManager.AUDIOFOCUS_LOSS -> { pausaDaFocus = false; Ciclo.pausa.value = true }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausaDaFocus) { pausaDaFocus = false; Ciclo.pausa.value = false }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FERMA) { ferma(); return START_NOT_STICKY }
        if (intent?.action == PAUSA) {
            if (lavoro?.isActive != true) { stopSelf(); return START_NOT_STICKY }
            if (Ciclo.pausa.value) riprendi() else metti(); return START_NOT_STICKY }
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

        Ciclo.pausa.value = false
        pausaDaFocus = false
        val avviso = instrada()
        Ciclo.stato.value = StatoCiclo(attivo = true, avviso = avviso)
        if (!chiediFocus()) {
            Ciclo.pausa.value = true
            Ciclo.stato.update { it.copy(avviso = "In pausa: l'audio è occupato (una chiamata?). Premi Riprendi.") }
        }
        sessione()
        lavoro = scope.launch {
            val osserva = launch { Ciclo.stato.collect { aggiornaNotifica(it) } }
            val pause = launch {
                Ciclo.pausa.collect { p ->
                    Ciclo.stato.update { it.copy(inPausa = p) }
                    statoSessione(p)
                }
            }
            val nota = try {
                // Il collegamento SCO non è istantaneo: senza, la prima frase
                // si perderebbe metà nell'altoparlante.
                if (instradato) delay(1500)
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
            pause.cancel()
            Ciclo.stato.update { it.copy(attivo = false, cosa = "", nota = nota ?: it.nota) }
            fine()
        }
        return START_NOT_STICKY
    }

    private fun ferma() {
        lavoro?.cancel()
        Ciclo.stato.update { it.copy(attivo = false, cosa = "", inPausa = false) }
        fine()
    }

    private fun metti() { pausaDaFocus = false; Ciclo.pausa.value = true }

    /** Riprendere vuol dire riprendersi l'audio: se è ancora occupato si resta
     *  in pausa e lo si dice, invece di parlare sopra una chiamata. */
    private fun riprendi() {
        if (chiediFocus()) {
            pausaDaFocus = false
            Ciclo.stato.update { it.copy(avviso = it.avviso?.takeUnless { a -> a.startsWith("In pausa") }) }
            Ciclo.pausa.value = false
        } else {
            Ciclo.stato.update { it.copy(avviso = "In pausa: l'audio è ancora occupato.") }
        }
    }

    private fun attributi(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (instradato) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun chiediFocus(): Boolean {
        val r = richiestaFocus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributi())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focus)
            .build().also { richiestaFocus = it }
        return audio.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun sessione() {
        sessione = MediaSession(this, "pronuncia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = riprendi()
                override fun onPause() = metti()
                override fun onSkipToNext() = Ciclo.comanda(Comando.AVANTI)
                override fun onSkipToPrevious() = Ciclo.comanda(Comando.RIPETI)
                override fun onStop() = ferma()
            })
            isActive = true
        }
        statoSessione(Ciclo.pausa.value)
    }

    /** ⚠️ Lo stato va tenuto vero: con «playing» il tasto play/pausa chiama
     *  onPause, con «paused» onPlay — è lui a decidere quale dei due. */
    private fun statoSessione(inPausa: Boolean) {
        sessione?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(if (inPausa) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
    }

    /** Porta il microfono sulle cuffie se è la scelta. Torna un avviso se non
     *  si può, `null` se è tutto a posto (o non era richiesto). */
    @Suppress("DEPRECATION")
    private fun instrada(): String? {
        if (Servizi.prefs.microfono != Microfono.CUFFIE) return null
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val d = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: return@runCatching false
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.setCommunicationDevice(d)
            } else {
                val ce = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ||
                    audio.isBluetoothScoAvailableOffCall && audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                if (!ce) return@runCatching false
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
                true
            }
        }.getOrDefault(false)
        if (!ok) {
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
            return "Cuffie Bluetooth non trovate: ascolto col microfono del telefono."
        }
        instradato = true
        Servizi.tts.comunicazione = true
        return null
    }

    @Suppress("DEPRECATION")
    private fun rilascia() {
        richiestaFocus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        richiestaFocus = null
        sessione?.let { runCatching { it.isActive = false; it.release() } }
        sessione = null
        if (instradato) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                else { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
                audio.mode = AudioManager.MODE_NORMAL
            }
            instradato = false
            Servizi.tts.comunicazione = false
        }
        Ciclo.pausa.value = false
    }

    private fun fine() {
        rilascia()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lavoro?.cancel()
        scope.cancel()
        rilascia()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        Ciclo.stato.update { it.copy(attivo = false, cosa = "", inPausa = false) }
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
        val pausaPi = PendingIntent.getService(
            this, 2, Intent(this, CicloService::class.java).setAction(PAUSA),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val titolo = if (s.inPausa) "⏸ In pausa" else listOf(s.cosa.ifBlank { "Mani libere" }, s.tentativo.takeIf { it > 0 }?.let { "tentativo $it/${Ciclo.TENTATIVI}" })
            .filterNotNull().joinToString(" · ")
        return NotificationCompat.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titolo)
            .setContentText(s.frase?.testo ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.frase?.testo ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(apri)
            .addAction(0, if (s.inPausa) "▶ Riprendi" else "⏸ Pausa", pausaPi)
            .addAction(0, "⏹ Ferma", stop)
            .build()
    }

    companion object {
        const val FERMA = "com.garsal.pronuncia.FERMA"
        const val PAUSA = "com.garsal.pronuncia.PAUSA"
        private const val CANALE = "ciclo"
        private const val ID = 7
    }
}
