package com.garsal.pronuncia

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Il bip che dice «tocca a te» (v1.18.0), prima di ogni ascolto,
 * nell'esercizio e a mani libere. Con le cuffie Bluetooth suona sul canale
 * della chiamata, come la voce dell'app, o nelle cuffie non arriverebbe.
 * ⚠️ Si aspetta che finisca prima di tornare: chi chiama apre il microfono
 * subito dopo, e il bip non deve finire nella registrazione.
 *
 * ⚠️ v1.33.2: il tono si GENERA (una sinusoide di [MS] millisecondi) e non
 * passa più da `ToneGenerator.TONE_PROP_BEEP`. Quel tono dura 40 ms qualunque
 * durata gli si chieda — la durata è solo un tetto — quindi portarlo a 300 ms
 * (v1.22.0) e poi a 500 (v1.33.1) allungava l'attesa, non il suono. Ed esce
 * attenuato di suo: una sinusoide quasi a fondo scala è molto più forte.
 * Si suona con `Riascolto.suona`, che sa già uscire sul canale giusto e
 * aspettare la fine.
 */
object Bip {
    private const val MS = 500
    private const val HZ_TONO = 1000
    /** Frazione del fondo scala: 0,9 è dove `Riascolto.normalizza` porta
     *  comunque il picco, quindi il tono esce com'è scritto qui. */
    private const val AMPIEZZA = 0.9
    /** Attacco e rilascio sfumati: tagliato netto, un tono fa «clic». */
    private const val SFUMO_MS = 10

    /** Il tono, PCM 16 bit mono alla frequenza di `Ascolto` (quella che
     *  `Riascolto` si aspetta). Si calcola una volta sola. */
    private val tono: ByteArray by lazy {
        val n = Ascolto.HZ * MS / 1000
        val sfumo = Ascolto.HZ * SFUMO_MS / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val inviluppo = minOf(1.0, i.toDouble() / sfumo, (n - 1 - i).toDouble() / sfumo)
            val v = (sin(2 * PI * HZ_TONO * i / Ascolto.HZ) * inviluppo * AMPIEZZA * Short.MAX_VALUE).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        out
    }

    suspend fun suona(comunicazione: Boolean) {
        // ⚠️ Un bip che non suona non deve fermare l'ascolto: si prosegue.
        try {
            Riascolto.suona(tono, comunicazione)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        // La coda dell'altoparlante: senza, finirebbe nella registrazione.
        delay(80L)
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
