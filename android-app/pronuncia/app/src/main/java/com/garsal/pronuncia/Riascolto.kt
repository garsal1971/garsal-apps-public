package com.garsal.pronuncia

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * «▶ Riascoltami»: suona la voce dell'ultimo tentativo, cioè proprio l'audio
 * che è andato ad Azure (PCM 16 kHz mono 16 bit, vedi `Ascolto`).
 *
 * ⚠️ Non si salva niente su file: la registrazione vive in memoria e il
 * tentativo dopo la sostituisce. Annullare la coroutine ferma il suono.
 *
 * Il volume si porta a pieno prima di suonare (v1.19.0, [normalizza]): il
 * microfono registra spesso basso. ⚠️ Solo la copia che si ascolta: l'audio
 * mandato ad Azure resta com'è.
 */
object Riascolto {
    /** [comunicazione]: con le cuffie Bluetooth come microfono il collegamento
     *  è quello delle chiamate, e l'audio deve uscire di lì (come in `Tts`). */
    suspend fun suona(pcm: ByteArray, comunicazione: Boolean = false) = withContext(Dispatchers.IO) {
        if (pcm.size < 2) return@withContext
        @Suppress("NAME_SHADOWING") val pcm = normalizza(pcm)
        val traccia = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (comunicazione) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Ascolto.HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        try {
            traccia.write(pcm, 0, pcm.size)
            traccia.play()
            val campioni = pcm.size / 2
            // Si aspetta che la testina arrivi in fondo; il tetto evita di restare
            // appesi se il dispositivo smette di avanzare.
            val tetto = System.currentTimeMillis() + campioni * 1000L / Ascolto.HZ + 2000
            while (traccia.playbackHeadPosition < campioni && System.currentTimeMillis() < tetto) delay(50)
        } finally {
            runCatching { traccia.stop() }
            traccia.release()
        }
    }

    /** Il picco più forte portato al 90 % del massimo, con lo stesso guadagno su
     *  tutta la registrazione: niente distorsione. ⚠️ Il guadagno ha un tetto
     *  ([MAX_GUADAGNO]): su una registrazione quasi muta si alzerebbe solo il
     *  fruscio. Già forte (guadagno ≤ 1) resta com'è. */
    private fun normalizza(pcm: ByteArray): ByteArray {
        var picco = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            picco = maxOf(picco, kotlin.math.abs(v))
            i += 2
        }
        if (picco == 0) return pcm
        val g = minOf(32767 * 0.9 / picco, MAX_GUADAGNO)
        if (g <= 1.0) return pcm
        val out = pcm.copyOf()
        i = 0
        while (i + 1 < out.size) {
            val v = ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xFF)).toShort().toInt()
            val n = (v * g).toInt().coerceIn(-32768, 32767)
            out[i] = (n and 0xFF).toByte()
            out[i + 1] = ((n shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private const val MAX_GUADAGNO = 8.0
}
