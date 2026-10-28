package com.garsal.pronuncia

import android.content.Context
import com.garsal.pronuncia.ui.SOGLIA_BUONA
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select

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
    val inPausa: Boolean = false,
    /** Un avviso che vale mentre il ciclo gira (es. cuffie non trovate). */
    val avviso: String? = null,
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
 * Fase 4: al posto della frase si può dire un [Comando] («repeat», «next»,
 * «slower», «stop»), e lo stesso arriva dai tasti delle cuffie ([comanda]).
 * [pausa] la tengono il tasto play/pausa e l'audio focus (una chiamata, il
 * navigatore).
 *
 * ⚠️ Pausa e tasti INTERROMPONO il passo in corso (voce o ascolto) invece di
 * aspettarne la fine: in una chiamata il microfono va lasciato subito. Dopo una
 * pausa la frase si rilegge da capo — a metà non vorrebbe dire niente — e i
 * tentativi già fatti restano.
 *
 * Gira dentro `CicloService`, non nella schermata: deve continuare a schermo
 * spento.
 */
object Ciclo {
    const val TENTATIVI = 3
    const val SILENZI = 3

    val stato = MutableStateFlow(StatoCiclo())
    val pausa = MutableStateFlow(false)
    private val esterno = MutableStateFlow<Comando?>(null)

    /** Un comando che non viene dalla voce (tasti delle cuffie). */
    fun comanda(c: Comando) { esterno.value = c }

    private sealed interface Fine {
        data object Avanti : Fine
        data object Pausa : Fine
        data object Silenzio : Fine
        data class Comandato(val c: Comando) : Fine
    }

    private fun cosa(c: String) = stato.update { it.copy(cosa = c) }

    /** Esegue [blocco], ma lo annulla appena arriva una pausa o un tasto. */
    private suspend fun interrompibile(blocco: suspend () -> Fine): Fine = coroutineScope {
        val lavoro = async { blocco() }
        val guardia = async { combine(pausa, esterno) { p, c -> p to c }.first { it.first || it.second != null } }
        select {
            lavoro.onAwait { guardia.cancel(); it }
            guardia.onAwait { (p, c) ->
                lavoro.cancelAndJoin()
                if (p) Fine.Pausa else { esterno.value = null; Fine.Comandato(c!!) }
            }
        }
    }

    suspend fun esegui(ctx: Context) {
        val p = Servizi.prefs
        val voce = p.voce
        val lingua = p.linguaFeedback
        val (voceFb, linguaFb) = Feedback.voce(lingua, voce)
        suspend fun di(c: Feedback.Cosa) = Servizi.tts.parla(Feedback.testo(lingua, c), voceFb, linguaFb)

        var precedente: String? = null
        var silenzi = 0
        var tentativo = 0

        /** Una frase, dalla lettura fino a un esito. */
        suspend fun giro(frase: Frase, lenta: Boolean): Fine {
            cosa(if (lenta) "🐢 Ascolta, piano" else "🔊 Ascolta")
            Servizi.tts.pronuncia(frase.testo, voce, lenta)
            while (true) {
                cosa("🎙 Tocca a te")
                val esito = try { Servizi.valutatore.valuta(frase.testo) } catch (e: NessunaVoce) { null }
                if (esito == null) {
                    if (++silenzi >= SILENZI) return Fine.Silenzio
                    di(Feedback.Cosa.SILENZIO)
                    continue
                }
                silenzi = 0
                Comando.da(esito.riconosciuto, frase.testo)?.let { return Fine.Comandato(it) }
                Storico.registra(ctx, frase, esito, "mani_libere")
                tentativo++
                stato.update { it.copy(tentativo = tentativo, ultimo = esito.pronuncia) }
                if (esito.pronuncia >= SOGLIA_BUONA) {
                    stato.update { it.copy(buone = it.buone + 1) }
                    di(Feedback.Cosa.BRAVO)
                    return Fine.Avanti
                }
                if (tentativo >= TENTATIVI) { di(Feedback.Cosa.AVANTI); return Fine.Avanti }
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

        esterno.value = null
        di(Feedback.Cosa.INIZIO)
        var frase: Frase? = null
        var lenta = false
        while (true) {
            val f = frase ?: (Frasi.aCaso(ctx, p.livello, p.tema, precedente)
                ?: run { stato.update { it.copy(nota = "Nessuna frase per questo filtro.") }; return })
            if (frase == null) {
                frase = f
                precedente = f.id
                tentativo = 0
                stato.update { it.copy(frase = f, tentativo = 0, ultimo = null, frasi = it.frasi + 1) }
            }
            val fine = interrompibile { giro(f, lenta) }
            lenta = false
            when (fine) {
                Fine.Avanti -> frase = null
                Fine.Silenzio -> {
                    stato.update { it.copy(nota = "Fermato: nessuna risposta per $SILENZI volte di fila.") }
                    di(Feedback.Cosa.PAUSA)
                    return
                }
                Fine.Pausa -> {
                    cosa("⏸ In pausa")
                    pausa.first { !it }
                }
                is Fine.Comandato -> when (fine.c) {
                    Comando.AVANTI -> frase = null
                    Comando.RIPETI -> Unit
                    Comando.LENTO -> lenta = true
                    Comando.FERMA -> {
                        stato.update { it.copy(nota = "Fermato a voce.") }
                        di(Feedback.Cosa.PAUSA)
                        return
                    }
                }
            }
        }
    }
}
