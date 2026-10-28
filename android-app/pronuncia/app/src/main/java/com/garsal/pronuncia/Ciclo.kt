package com.garsal.pronuncia

import android.content.Context
import com.garsal.pronuncia.ui.SOGLIA_BUONA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Quel che il ciclo sta facendo, per la schermata e per la notifica. */
data class StatoCiclo(
    val attivo: Boolean = false,
    val frase: Frase? = null,
    val tentativo: Int = 0,
    val cosa: String = "",
    val ultimo: Double? = null,
    val frasi: Int = 0,
    val buone: Int = 0,
    val nota: String? = null,
)

/**
 * Il ciclo a mani libere: legge una frase, ascolta, commenta, e avanti.
 *
 * - sopra [SOGLIA_BUONA] si dice «Good» e si passa alla prossima;
 * - sotto, «Try again», le 1-2 parole peggiori lente e la frase di nuovo,
 *   fino a [TENTATIVI]; poi «Let's move on»;
 * - ⚠️ un silenzio NON è un tentativo (vedi `NessunaVoce`), ma [SILENZI] di fila
 *   fermano la sessione: un microfono aperto a vuoto consuma i minuti gratuiti
 *   di riconoscimento, e se nessuno risponde da tre frasi non c'è nessuno.
 *
 * Gira dentro `CicloService`, non nella schermata: deve continuare a schermo
 * spento. Le due chiamate sono le stesse dell'esercizio a mano.
 */
object Ciclo {
    const val TENTATIVI = 3
    const val SILENZI = 3

    val stato = MutableStateFlow(StatoCiclo())

    private fun cosa(c: String) = stato.update { it.copy(cosa = c) }

    suspend fun esegui(ctx: Context) {
        val p = Servizi.prefs
        val voce = p.voce
        val lingua = p.linguaFeedback
        val (voceFb, linguaFb) = Feedback.voce(lingua, voce)
        suspend fun di(c: Feedback.Cosa) = Servizi.tts.parla(Feedback.testo(lingua, c), voceFb, linguaFb)

        var precedente: String? = null
        var silenzi = 0
        di(Feedback.Cosa.INIZIO)
        while (true) {
            val frase = Frasi.aCaso(ctx, p.livello, p.tema, precedente)
                ?: run { stato.update { it.copy(nota = "Nessuna frase per questo filtro.") }; return }
            precedente = frase.id
            stato.update { it.copy(frase = frase, tentativo = 0, ultimo = null, frasi = it.frasi + 1) }
            var tentativo = 0
            cosa("🔊 Ascolta")
            Servizi.tts.pronuncia(frase.testo, voce)
            while (true) {
                cosa("🎙 Tocca a te")
                val esito = try { Servizi.valutatore.valuta(frase.testo) } catch (e: NessunaVoce) { null }
                if (esito == null) {
                    silenzi++
                    if (silenzi >= SILENZI) {
                        stato.update { it.copy(nota = "Fermato: nessuna risposta per $SILENZI volte di fila.") }
                        di(Feedback.Cosa.PAUSA)
                        return
                    }
                    di(Feedback.Cosa.SILENZIO)
                    continue
                }
                silenzi = 0
                tentativo++
                stato.update { it.copy(tentativo = tentativo, ultimo = esito.pronuncia) }
                if (esito.pronuncia >= SOGLIA_BUONA) {
                    stato.update { it.copy(buone = it.buone + 1) }
                    di(Feedback.Cosa.BRAVO)
                    break
                }
                if (tentativo >= TENTATIVI) { di(Feedback.Cosa.AVANTI); break }
                di(Feedback.Cosa.RIPROVA)
                cosa("🐢 Le parole da curare")
                esito.peggiori().forEach {
                    val parola = it.testo.trim { c -> !c.isLetterOrDigit() && c != '\'' }
                    if (parola.isNotEmpty()) Servizi.tts.pronuncia(parola, voce, lenta = true)
                }
                cosa("🔊 Ascolta")
                Servizi.tts.pronuncia(frase.testo, voce)
            }
        }
    }
}
