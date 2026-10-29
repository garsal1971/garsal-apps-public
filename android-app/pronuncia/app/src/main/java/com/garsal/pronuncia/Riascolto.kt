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
 */
object Riascolto {
    suspend fun suona(pcm: ByteArray) = withContext(Dispatchers.IO) {
        if (pcm.size < 2) return@withContext
        val traccia = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
}
