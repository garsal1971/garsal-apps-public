package com.garsal.pronuncia

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Il microfono letto dal telefono, con un rilevatore di voce (VAD) suo.
 *
 * ⚠️ Esiste per i minuti gratuiti di Azure: il riconoscimento si paga a secondi
 * di **audio inviato**, e col microfono dell'SDK si inviavano anche il silenzio
 * prima di parlare (fino a 8 s) e quello dopo. Qui l'audio parte verso Azure solo
 * quando comincia la voce — con [PRE_MS] di coda prima, o la prima sillaba
 * verrebbe tagliata — e si chiude dopo [FINE_MS] di silenzio. Un silenzio intero
 * non costa niente: non si apre nemmeno il riconoscimento.
 *
 * La soglia si adatta al rumore di fondo ([rumore], media lenta dei frame
 * silenziosi): una soglia fissa scatterebbe da sola in auto e non scatterebbe mai
 * in una stanza silenziosa con una voce bassa. ⚠️ In un posto rumoroso il rumore
 * può comunque passare per voce: le costanti vanno tarate sul telefono.
 *
 * ⚠️ Con le cuffie Bluetooth (`comunicazione`) la sorgente è
 * `VOICE_COMMUNICATION`: è quella che segue il dispositivo di comunicazione
 * scelto da `CicloService.instrada()`. Con `VOICE_RECOGNITION` si registrerebbe
 * dal microfono del telefono anche a cuffie collegate.
 */
@SuppressLint("MissingPermission") // il permesso lo chiedono Esercizio e ManiLibere prima di valutare
class Ascolto(comunicazione: Boolean) : AutoCloseable {

    private val rec: AudioRecord

    init {
        val minimo = AudioRecord.getMinBufferSize(HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        rec = AudioRecord(
            if (comunicazione) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            // Due secondi di riserva: se l'apertura del riconoscimento ritarda,
            // l'audio aspetta qui invece di andare perso.
            max(minimo, HZ * 2 * 2),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Il microfono non si apre: forse lo sta usando un'altra app." }
        rec.startRecording()
    }

    private var rumore = -1.0
    private val coda = ArrayDeque<ByteArray>()

    /**
     * Ascolta finché non comincia la voce, al massimo [attesaMs]. Torna i frame da
     * inviare per primi (la coda prima della voce compresa), o `null` se non ha
     * sentito niente — e allora ad Azure non è andato un byte.
     */
    suspend fun aspettaVoce(attesaMs: Int = ATTESA_MS): List<ByteArray>? {
        var sopra = 0
        repeat(attesaMs / FRAME_MS) {
            currentCoroutineContext().ensureActive()
            val f = leggi() ?: return null
            val e = energia(f)
            if (rumore < 0) rumore = e
            coda.addLast(f)
            if (coda.size > PRE_MS / FRAME_MS) coda.removeFirst()
            if (e > sogliaVoce()) {
                if (++sopra >= FRAME_INIZIO) return coda.toList().also { coda.clear() }
            } else {
                sopra = 0
                rumore = rumore * 0.9 + e * 0.1
            }
        }
        return null
    }

    /**
     * Manda i frame a [push] finché la voce non si ferma per [FINE_MS], o finché
     * Azure non ha già chiuso da sé ([finito]), o al tetto di [MAX_MS].
     */
    suspend fun inviaFinoAlSilenzio(push: PushAudioInputStream, finito: () -> Boolean) {
        var zitto = 0
        repeat(MAX_MS / FRAME_MS) {
            currentCoroutineContext().ensureActive()
            if (finito()) return
            val f = leggi() ?: return
            push.write(f)
            if (energia(f) > sogliaSilenzio()) zitto = 0
            else if (++zitto >= FINE_MS / FRAME_MS) return
        }
    }

    private fun sogliaVoce() = max(rumore * 3.0, MIN_VOCE)
    private fun sogliaSilenzio() = max(rumore * 2.0, MIN_VOCE * 0.6)

    private fun leggi(): ByteArray? {
        val b = ByteArray(BYTE_FRAME)
        var letti = 0
        while (letti < b.size) {
            val n = rec.read(b, letti, b.size - letti)
            if (n <= 0) return null
            letti += n
        }
        return b
    }

    /** RMS del frame, PCM 16 bit little-endian. */
    private fun energia(b: ByteArray): Double {
        var s = 0.0
        var i = 0
        while (i < b.size) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toDouble()
            s += v * v
            i += 2
        }
        return sqrt(s / (b.size / 2))
    }

    override fun close() {
        runCatching { rec.stop() }
        rec.release()
    }

    companion object {
        const val HZ = 16000
        const val FRAME_MS = 20
        private const val BYTE_FRAME = HZ / 1000 * FRAME_MS * 2

        /** Quanto si aspetta che cominci la voce: in locale, quindi gratis. */
        const val ATTESA_MS = 8000
        /** Audio tenuto prima della voce, per non tagliare la prima sillaba. */
        const val PRE_MS = 300
        /** Frame di fila sopra soglia per dire «voce» (60 ms): un colpo secco no. */
        const val FRAME_INIZIO = 3
        /** Silenzio dopo la voce che chiude l'invio. */
        const val FINE_MS = 600
        /** Tetto di una frase: oltre, si chiude comunque. */
        const val MAX_MS = 20000
        /** Soglia minima assoluta (RMS su 16 bit, circa −40 dBFS). */
        const val MIN_VOCE = 330.0
    }
}
