package com.garsal.pronuncia

import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La voce dell'app: Azure TTS con le voci neurali en-GB.
 *
 * Si passa da **SSML** e non dal testo semplice perché «slower» è una
 * `<prosody rate>`: con il testo semplice la velocità non si sceglie.
 *
 * Fase 1: la sintesi suona dall'altoparlante di default, ogni volta da capo.
 * ⚠️ Dalla fase 3 l'audio di ogni frase si salva in un file e si riproduce da
 * lì: il livello gratuito conta i caratteri sintetizzati, e una frase ripetuta
 * cento volte non deve costarne cento.
 */
class Tts(private val azure: AzureToken) {

    /** Pronuncia [testo] e torna quando la sintesi è finita. Lancia con un
     *  messaggio leggibile se Azure rifiuta. */
    suspend fun pronuncia(testo: String, voce: Voce, lenta: Boolean = false) = withContext(Dispatchers.IO) {
        val t = azure.prendi()
        SpeechConfig.fromAuthorizationToken(t.valore, t.regione).use { cfg ->
            SpeechSynthesizer(cfg).use { sintesi ->
                sintesi.SpeakSsml(ssml(testo, voce, lenta)).use { r ->
                    if (r.reason == ResultReason.Canceled) {
                        val d = SpeechSynthesisCancellationDetails.fromResult(r)
                        throw IllegalStateException("Sintesi annullata: ${d.reason} — ${d.errorDetails}")
                    }
                }
            }
        }
    }

    private fun ssml(testo: String, voce: Voce, lenta: Boolean): String {
        // ⚠️ Il testo va escapato: un «&» in una frase («Q&A») renderebbe l'SSML
        // non valido, e Azure annullerebbe la sintesi con un errore che non
        // nomina il carattere.
        val sicuro = testo
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        val corpo = if (lenta) "<prosody rate=\"-30%\">$sicuro</prosody>" else sicuro
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-GB\">" +
            "<voice name=\"${voce.id}\">$corpo</voice></speak>"
    }
}
