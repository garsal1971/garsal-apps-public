package com.garsal.pronuncia

import com.microsoft.cognitiveservices.speech.CancellationDetails
import com.microsoft.cognitiveservices.speech.PhraseListGrammar
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentConfig
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGradingSystem
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGranularity
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Una parola valutata. `errore` è il codice di Azure: None, Mispronunciation,
 *  Omission, Insertion (più altri che con questa configurazione non arrivano). */
data class Parola(val testo: String, val accuratezza: Double, val errore: String, val fonemi: List<Pair<String, Double>>)

data class Esito(
    val riconosciuto: String,
    val pronuncia: Double,
    val accuratezza: Double,
    val fluidita: Double,
    val completezza: Double,
    val parole: List<Parola>,
) {
    /** Le parole peggiori fra quelle dette: un'omissione non si «ripronuncia
     *  meglio», si dice e basta, ma resta la cosa da far ripetere. Le inserite
     *  no — non sono nella frase. */
    fun peggiori(n: Int = 2): List<Parola> = parole
        .filter { it.errore != "Insertion" && (it.errore != "None" || it.accuratezza < 80) }
        .sortedBy { if (it.errore == "Omission") -1.0 else it.accuratezza }
        .take(n)
}

/** Nessuna voce sentita entro il silenzio iniziale: non è un tentativo fallito,
 *  è un tentativo che non c'è stato — dalla fase 3 non conterà fra i tre. */
class NessunaVoce : Exception("Non ho sentito niente: riprova parlando dopo il tocco.")

/**
 * Pronunciation Assessment di Azure su una frase: un riconoscimento solo, con
 * l'audio passato da [Ascolto] — che lo manda solo dall'inizio della voce a
 * poco dopo la fine, per non pagare i silenzi.
 *
 * ⚠️ `EnableMiscue = true`: le parole saltate tornano come `Omission` e quelle in
 * più come `Insertion`. Senza, una frase detta a metà prenderebbe un'accuratezza
 * alta sulle sole parole dette.
 *
 * ⚠️ I punteggi per parola si leggono dal **JSON** della risposta e non dalle
 * classi dell'SDK: lì ci sono anche i fonemi, ed è la stessa forma che restituisce
 * il servizio REST — un formato solo da conoscere.
 */
class Valutatore(private val azure: AzureToken) {

    /** La voce dell'ultimo tentativo, per «Riascoltami». Solo in memoria:
     *  il tentativo dopo la sostituisce, e non si salva da nessuna parte. */
    @Volatile var ultimaVoce: ByteArray? = null
        private set

    suspend fun valuta(riferimento: String): Esito = withContext(Dispatchers.IO) {
        Ascolto(Servizi.tts.comunicazione).use { mic ->
            // Il token prima della voce: se va rinnovato, il ritardo cade qui
            // e non mentre si sta parlando.
            val t = azure.prendi()
            // ⚠️ Un silenzio intero non apre nemmeno il riconoscimento: è il
            // punto di Ascolto, che i secondi di Azure li conta sull'audio inviato.
            ultimaVoce = null
            val inizio = mic.aspettaVoce() ?: throw NessunaVoce()
            SpeechConfig.fromAuthorizationToken(t.valore, t.regione).use { cfg ->
                cfg.speechRecognitionLanguage = "en-GB"
                // L'audio arriva già dall'inizio della voce: tre secondi di
                // silenzio iniziale sono una rete, non un'attesa.
                cfg.setProperty(PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs, "3000")
                // ⚠️ La fine la decide Ascolto chiudendo lo stream. Una
                // segmentazione più corta taglierebbe la frase alla prima
                // virgola, e il resto conterebbe come parole saltate.
                cfg.setProperty(PropertyId.Speech_SegmentationSilenceTimeoutMs, "1000")
                val push = AudioInputStream.createPushStream(AudioStreamFormat.getWaveFormatPCM(Ascolto.HZ.toLong(), 16, 1))
                var chiuso = false
                try {
                    AudioConfig.fromStreamInput(push).use { audio ->
                        SpeechRecognizer(cfg, audio).use { rec ->
                            PronunciationAssessmentConfig(
                                riferimento,
                                PronunciationAssessmentGradingSystem.HundredMark,
                                PronunciationAssessmentGranularity.Phoneme,
                                true,
                            ).use { pa ->
                                pa.setPhonemeAlphabet("IPA")
                                pa.applyTo(rec)
                                // I comandi a voce sono parole corte e fuori contesto:
                                // la lista le rende più facili da riconoscere.
                                // ⚠️ Non va in un `use`: chiusa prima del riconoscimento
                                // le sue parole sparirebbero. Vive quanto il recognizer.
                                val lista = PhraseListGrammar.fromRecognizer(rec)
                                Comando.PAROLE.forEach(lista::addPhrase)
                                val futuro = rec.recognizeOnceAsync()
                                inizio.forEach { mic.invia(push, it) }
                                mic.inviaFinoAlSilenzio(push) { futuro.isDone }
                                ultimaVoce = mic.registrazione()
                                // Fine dello stream = fine della frase per Azure.
                                push.close(); chiuso = true
                                // ⚠️ Interrompibile: una pausa (chiamata, tasto delle
                                // cuffie) annulla la coroutine — `get()` da solo non
                                // si annulla.
                                runInterruptible { futuro.get() }.use { r ->
                                    when (r.reason) {
                                        ResultReason.RecognizedSpeech ->
                                            leggi(r.properties.getProperty(PropertyId.SpeechServiceResponse_JsonResult), r.text)
                                        ResultReason.NoMatch -> throw NessunaVoce()
                                        ResultReason.Canceled -> {
                                            val d = CancellationDetails.fromResult(r)
                                            throw IllegalStateException("Valutazione annullata: ${d.reason} — ${d.errorDetails}")
                                        }
                                        else -> throw IllegalStateException("Risposta inattesa: ${r.reason}")
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    if (!chiuso) runCatching { push.close() }
                }
            }
        }
    }

    private fun leggi(json: String, testo: String): Esito {
        val best = JSONObject(json).getJSONArray("NBest").getJSONObject(0)
        val pa = best.optJSONObject("PronunciationAssessment") ?: JSONObject()
        val parole = best.optJSONArray("Words")
        val elenco = (0 until (parole?.length() ?: 0)).map { i ->
            val w = parole!!.getJSONObject(i)
            val wpa = w.optJSONObject("PronunciationAssessment") ?: JSONObject()
            val fonemi = w.optJSONArray("Phonemes")
            Parola(
                testo = w.optString("Word"),
                accuratezza = wpa.optDouble("AccuracyScore", 0.0),
                errore = wpa.optString("ErrorType", "None"),
                fonemi = (0 until (fonemi?.length() ?: 0)).map { j ->
                    val f = fonemi!!.getJSONObject(j)
                    f.optString("Phoneme") to (f.optJSONObject("PronunciationAssessment")?.optDouble("AccuracyScore", 0.0) ?: 0.0)
                },
            )
        }
        return Esito(
            riconosciuto = testo,
            pronuncia = pa.optDouble("PronScore", 0.0),
            accuratezza = pa.optDouble("AccuracyScore", 0.0),
            fluidita = pa.optDouble("FluencyScore", 0.0),
            completezza = pa.optDouble("CompletenessScore", 0.0),
            parole = elenco,
        )
    }
}
