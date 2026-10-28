package com.garsal.pronuncia

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * La voce dell'app: Azure TTS con le voci neurali, en-GB per le frasi e it-IT
 * per il feedback in italiano.
 *
 * Si passa da **SSML** e non dal testo semplice perché «slower» è una
 * `<prosody rate>`: con il testo semplice la velocità non si sceglie.
 *
 * ⚠️ L'audio si sintetizza UNA volta e si salva in `cacheDir/tts`, col nome
 * ricavato dall'SSML: il livello gratuito F0 conta i caratteri sintetizzati, e
 * una frase ripetuta cento volte non deve costarne cento. La chiave è l'SSML
 * intero, quindi voce, lingua e velocità diverse sono file diversi. Se il
 * sistema svuota la cache, si risintetizza e basta.
 */
class Tts(ctx: Context, private val azure: AzureToken) {

    private val cartella = File(ctx.cacheDir, "tts").apply { mkdirs() }

    /** Una frase in inglese con la voce scelta. */
    suspend fun pronuncia(testo: String, voce: Voce, lenta: Boolean = false) =
        parla(testo, voce.id, "en-GB", lenta)

    /** Pronuncia [testo] e torna quando l'audio è finito. Lancia con un
     *  messaggio leggibile se Azure rifiuta. */
    suspend fun parla(testo: String, voceId: String, lingua: String, lenta: Boolean = false) {
        suona(audio(ssml(testo, voceId, lingua, lenta)))
    }

    private suspend fun audio(ssml: String): File = withContext(Dispatchers.IO) {
        val f = File(cartella, sha(ssml) + ".mp3")
        if (f.length() > 0) return@withContext f
        val t = azure.prendi()
        SpeechConfig.fromAuthorizationToken(t.valore, t.regione).use { cfg ->
            cfg.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Audio24Khz48KBitRateMonoMp3)
            // AudioConfig nullo: l'SDK non suona niente, restituisce i byte.
            SpeechSynthesizer(cfg, null as AudioConfig?).use { sintesi ->
                sintesi.SpeakSsml(ssml).use { r ->
                    if (r.reason == ResultReason.Canceled) {
                        val d = SpeechSynthesisCancellationDetails.fromResult(r)
                        throw IllegalStateException("Sintesi annullata: ${d.reason} — ${d.errorDetails}")
                    }
                    // ⚠️ Prima un file temporaneo e poi il nome vero: un file
                    // scritto a metà col nome definitivo si suonerebbe rotto per sempre.
                    val tmp = File(cartella, f.name + ".tmp")
                    tmp.writeBytes(r.audioData)
                    if (!tmp.renameTo(f)) { tmp.delete(); throw IllegalStateException("Non riesco a salvare l'audio") }
                }
            }
        }
        f
    }

    /** Suona il file e torna alla fine. Annullare la coroutine ferma l'audio. */
    private suspend fun suona(f: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer()
            cont.invokeOnCancellation { runCatching { mp.release() } }
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(f.path)
                mp.setOnCompletionListener { runCatching { it.release() }; if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { p, cosa, extra ->
                    runCatching { p.release() }
                    f.delete() // un file che non si suona non deve restare in cache
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Riproduzione non riuscita ($cosa/$extra)"))
                    true
                }
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                runCatching { mp.release() }
                f.delete()
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    private fun ssml(testo: String, voceId: String, lingua: String, lenta: Boolean): String {
        // ⚠️ Il testo va escapato: un «&» in una frase («Q&A») renderebbe l'SSML
        // non valido, e Azure annullerebbe la sintesi con un errore che non
        // nomina il carattere.
        val sicuro = testo
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        val corpo = if (lenta) "<prosody rate=\"-30%\">$sicuro</prosody>" else sicuro
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$lingua\">" +
            "<voice name=\"$voceId\">$corpo</voice></speak>"
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
