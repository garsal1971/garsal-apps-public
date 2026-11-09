package com.garsal.pronuncia

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay

/**
 * Il bip che dice «tocca a te» (v1.18.0; 300 ms dalla v1.22.0), prima di ogni ascolto,
 * nell'esercizio e a mani libere. Con le cuffie Bluetooth suona sul canale
 * della chiamata, come la voce dell'app, o nelle cuffie non arriverebbe.
 * ⚠️ Si aspetta che finisca prima di tornare: chi chiama apre il microfono
 * subito dopo, e il bip non deve finire nella registrazione.
 */
object Bip {
    private const val MS = 300

    suspend fun suona(comunicazione: Boolean) {
        val tg = runCatching {
            ToneGenerator(if (comunicazione) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 80)
        }.getOrNull() ?: return
        try {
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, MS)
            delay(MS + 80L)
        } finally {
            tg.release()
        }
    }

    /** Due bip bassi (v1.33.0): l'avviso che resta quando non si può nemmeno
     *  parlare — la rete è caduta e la frase d'avviso non era in cache. Non
     *  passa dalla rete, quindi suona sempre. */
    suspend fun errore(comunicazione: Boolean) {
        val tg = runCatching {
            ToneGenerator(if (comunicazione) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 80)
        }.getOrNull() ?: return
        try {
            repeat(2) {
                tg.startTone(ToneGenerator.TONE_PROP_NACK, 250)
                delay(400L)
            }
        } finally {
            tg.release()
        }
    }
}
