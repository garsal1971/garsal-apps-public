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
    /** La frase in corso viene dal ripasso (fase 6). */
    val ripasso: Boolean = false,
    /** Un avviso che vale mentre il ciclo gira (es. cuffie non trovate). */
    val avviso: String? = null,
    /** Il giudizio dell'ultimo tentativo, per dire a schermo perché è da ripetere. */
    val verdetto: Verdetto? = null,
)

/**
 * Il ciclo a mani libere: legge una frase, ascolta, commenta, e avanti.
 *
 * - dopo il tentativo, se acceso (`Prefs.riascoltoCiclo`), si riascolta la
 *   propria registrazione; poi si dice il voto;
 * - se il [Giudizio] la promuove (voto ≥ [SOGLIA_BUONA] più i controlli
 *   accesi nelle impostazioni) si dice «Good» e si passa alla prossima;
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
 * Fase 6: una frase ogni [Ripasso.OGNI] viene dal ripasso (vedi `Ripasso`).
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

    /**
     * Il difetto detto a voce: le parti in italiano con la voce italiana (le
     * lettere si dicono all'italiana, «ti acca», come chiesto) e la parola con
     * la voce inglese, che la pronuncia come va pronunciata.
     */
    private suspend fun diciDifetto(d: Difetto, voce: Voce, accento: Accento) {
        val (vIt, lIt) = Feedback.voce(LinguaFeedback.ITALIANO, voce, accento)
        suspend fun it(t: String) = Servizi.tts.parla(t, vIt, lIt)
        suspend fun en(p: Parola) {
            val w = p.testo.trim { c -> !c.isLetterOrDigit() && c != '\'' }
            if (w.isNotEmpty()) Servizi.tts.pronuncia(w, voce, accento)
        }
        fun n(v: Double) = "${Math.round(v)}."
        when (d) {
            is Difetto.Suono -> {
                val lettere = d.grafema?.let { Grafema.lettere(d.parola.testo.substring(it)) }
                it(if (lettere.isNullOrBlank()) "Un suono in" else "$lettere, in")
                en(d.parola); it(n(d.voto))
            }
            is Difetto.ParolaBassa -> { it("La parola"); en(d.parola); it(n(d.voto)) }
            is Difetto.Saltata -> { it("Hai saltato"); en(d.parola) }
            is Difetto.Ritmo -> it("Ritmo e intonazione: ${n(d.voto)}")
            is Difetto.Voto -> Unit
        }
    }

    suspend fun esegui(ctx: Context) {
        val p = Servizi.prefs
        val voce = p.voce
        val accento = p.accento
        val lingua = p.linguaFeedback
        val (voceFb, linguaFb) = Feedback.voce(lingua, voce, accento)
        suspend fun di(c: Feedback.Cosa) = Servizi.tts.parla(Feedback.testo(lingua, c), voceFb, linguaFb)

        var precedente: String? = null
        var silenzi = 0
        var tentativo = 0

        /** Una frase, dalla lettura fino a un esito. */
        suspend fun giro(frase: Frase, lenta: Boolean): Fine {
            cosa(if (lenta) "🐢 Ascolta, piano" else "🔊 Ascolta")
            Servizi.tts.pronuncia(frase.testo, voce, accento, lenta)
            while (true) {
                cosa("🎙 Tocca a te")
                val esito = try { Servizi.valutatore.valuta(frase.testo, accento, p.soglia(Controllo.RITMO, frase.parola) != null) } catch (e: NessunaVoce) { null }
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
                // Il riascolto: la propria voce prima del giudizio, così il
                // voto si confronta con quel che si è appena sentito.
                if (p.riascoltoCiclo) Servizi.valutatore.ultimaVoce?.let { v ->
                    cosa("🔁 La tua voce")
                    Riascolto.suona(v, Servizi.tts.comunicazione)
                }
                val verdetto = Giudizio.di(esito, p, frase.parola)
                stato.update { it.copy(verdetto = verdetto) }
                cosa("📊 Il voto")
                Servizi.tts.parla(Feedback.voto(lingua, esito.pronuncia), voceFb, linguaFb)
                // Perché è da ripetere, se non è il solo voto basso (quello si è
                // appena sentito): il primo controllo che ha bocciato.
                verdetto.difetti.firstOrNull { it !is Difetto.Voto }?.let { d ->
                    cosa("🔎 Cosa curare")
                    diciDifetto(d, voce, accento)
                }
                if (verdetto.buona) {
                    stato.update { it.copy(buone = it.buone + 1) }
                    di(Feedback.Cosa.BRAVO)
                    return Fine.Avanti
                }
                if (tentativo >= TENTATIVI) { di(Feedback.Cosa.AVANTI); return Fine.Avanti }
                di(Feedback.Cosa.RIPROVA)
                if (frase.parola) {
                    // Una parola sola: le «parole da curare» sarebbero lei,
                    // quindi la si rilegge piano e basta.
                    cosa("🐢 Ascolta, piano")
                    Servizi.tts.pronuncia(frase.testo, voce, accento, lenta = true)
                } else {
                    cosa("🐢 Le parole da curare")
                    verdetto.daCurare.forEach {
                        val parola = it.testo.trim { c -> !c.isLetterOrDigit() && c != '\'' }
                        if (parola.isNotEmpty()) Servizi.tts.pronuncia(parola, voce, accento, lenta = true)
                    }
                    cosa("🔊 Ascolta")
                    Servizi.tts.pronuncia(frase.testo, voce, accento)
                }
            }
        }

        esterno.value = null
        di(Feedback.Cosa.INIZIO)
        var frase: Frase? = null
        var lenta = false
        while (true) {
            if (frase == null) {
                // Fase 6: una frase ogni Ripasso.OGNI è da ripassare, se ce n'è;
                // altrimenti si resta sulle frasi a caso.
                // Frasi o parole si rilegge qui, a ogni elemento nuovo: la
                // scelta cambiata a ciclo acceso vale dal prossimo.
                val soloParole = p.paroleCiclo
                val n = stato.value.frasi + 1
                val daRipassare = if (n % Ripasso.OGNI == 0)
                    runCatching { Ripasso.prossima(ctx, p.livelloCiclo, p.temaCiclo, precedente, soloParole) }.getOrNull() else null
                val nuova = daRipassare ?: Frasi.aCaso(ctx, p.livelloCiclo, p.temaCiclo, precedente, soloParole)
                    ?: run { stato.update { it.copy(nota = "Nessuna frase per questo filtro.") }; return }
                frase = nuova
                precedente = nuova.id
                tentativo = 0
                stato.update {
                    it.copy(frase = nuova, tentativo = 0, ultimo = null, frasi = n, ripasso = daRipassare != null, verdetto = null)
                }
            }
            val f = frase!!
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
