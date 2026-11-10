package com.garsal.pronuncia

import android.content.Context
import com.garsal.pronuncia.ui.SOGLIA_BUONA
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    /** La frase in corso è già stata fatta: la media dei suoi ultimi due voti
     *  (v1.33.0; fino alla v1.32.0 era il voto più basso mai preso). */
    val recente: Double? = null,
    /** La frase in corso è stata scelta per questo suono debole (`ProfiloSuoni`). */
    val suono: String? = null,
    /** Un avviso che vale mentre il ciclo gira (es. cuffie non trovate). */
    val avviso: String? = null,
    /** Il giudizio dell'ultimo tentativo, per dire a schermo perché è da ripetere. */
    val verdetto: Verdetto? = null,
    /** La sessione (v1.16.0): quanti elementi, quanti già provati, il voto
     *  finale da 1 a 10 e com'è andato l'aggiornamento di Obiettivi. */
    val quante: Int = 0,
    val fatte: Int = 0,
    val media: Double? = null,
    val voto: Int? = null,
    val esitoObiettivo: String? = null,
)

/**
 * Il ciclo a mani libere: legge una frase, ascolta, commenta, e avanti.
 *
 * - se il [Giudizio] la promuove (voto ≥ [SOGLIA_BUONA] più i controlli
 *   accesi nelle impostazioni) si dice «85. Good!» e si passa alla prossima;
 * - sotto, se acceso (`Prefs.riascoltoCiclo`), si riascolta la propria
 *   registrazione; poi «62. Try again. Listen.», il difetto in breve (le
 *   lettere del suono su una parola, la parola peggiore su una frase), le 1-2
 *   parole peggiori lente e la frase di nuovo, fino a [TENTATIVI]; all'ultimo
 *   «62. Let's move on.»;
 * - ⚠️ un silenzio NON è un tentativo (vedi `NessunaVoce`), ma [SILENZI] di fila
 *   fermano la sessione: un microfono aperto a vuoto consuma i minuti gratuiti
 *   di riconoscimento, e se nessuno risponde da tre frasi non c'è nessuno.
 *
 * Fase 4: al posto della frase si può dire un [Comando] («repeat», «next»,
 * «slower», «stop»), e lo stesso arriva dai tasti delle cuffie ([comanda]).
 * v1.23.0: anche «spell» (lo spelling in inglese, sulle sole parole: su una
 * frase lo dice) e «translate» (la traduzione di `pr_traduzioni` con la voce
 * italiana). Dopo, come dopo «repeat», la frase si rilegge e si aspetta il
 * tentativo: ⚠️ nessuno dei due è un tentativo, e i tentativi fatti restano.
 * [pausa] la tengono il tasto play/pausa e l'audio focus (una chiamata, il
 * navigatore).
 *
 * ⚠️ Pausa e tasti INTERROMPONO il passo in corso (voce o ascolto) invece di
 * aspettarne la fine: in una chiamata il microfono va lasciato subito. Dopo una
 * pausa la frase si rilegge da capo — a metà non vorrebbe dire niente — e i
 * tentativi già fatti restano.
 *
 * Gli elementi si scelgono tutti all'avvio (`Sessione.scegli`, v1.22.0): metà
 * mai fatti e metà già fatti, secondo `Ripasso` (v1.33.0: ultimi voti e
 * intervalli). «next» su uno mai provato lo manda in fondo alla coda, così la
 * sessione resta di N.
 *
 * ⚠️ v1.33.0, **la rete che cade**: un errore durante il giro (sintesi,
 * token, valutazione) non chiude più la sessione in silenzio. Si dice a voce
 * «Problema di rete, riprovo», si aspetta e si rifà il giro sulla stessa frase
 * — i tentativi fatti restano — fino a [GUASTI] volte di fila; poi ci si ferma
 * dicendolo. Gli avvisi si sintetizzano all'avvio, finché la rete c'è; se non
 * sono in cache restano due bip (`Bip.errore`). Una sessione scaduta non si
 * riprova: lì serve rientrare con Google.
 *
 * Una sessione è di `Prefs.quanteCiclo` elementi, letta all'avvio (v1.16.0):
 * conta un elemento con almeno un tentativo — un «next» su uno mai provato non
 * lo consuma. Finito l'ultimo si dice il voto da 1 a 10 (`votoSessione`) e si
 * completa l'azione di Obiettivi (`Obiettivo.aggiorna`). ⚠️ Fermato prima (a
 * voce, col tasto o dai silenzi) non c'è voto: una sessione a metà non è
 * quella che si era chiesta, e il voto finirebbe nello storico di Obiettivi.
 *
 * ⚠️ v1.34.0, **meno attese**: (1) l'audio si prepara in sottofondo
 * ([sottofondo]) — la frase dopo mentre si fa quella in corso, il commento e
 * le parole lente mentre si riascolta la propria voce — così quando serve è
 * già in cache; (2) voto e commento sono una frase sola; (3) il riascolto c'è
 * solo sui tentativi da ripetere. Un errore in sottofondo non conta: la
 * stessa sintesi si rifà quando serve davvero, e lì l'errore si vede.
 *
 * Gira dentro `CicloService`, non nella schermata: deve continuare a schermo
 * spento.
 */
object Ciclo {
    const val TENTATIVI = 3
    const val SILENZI = 3
    /** Errori di fila (rete, quasi sempre) prima di fermare la sessione. */
    const val GUASTI = 3
    /** L'attesa prima di riprovare: questa per il numero dell'errore. */
    private const val ATTESA_GUASTO_MS = 5_000L

    val stato = MutableStateFlow(StatoCiclo())
    val pausa = MutableStateFlow(false)
    private val esterno = MutableStateFlow<Comando?>(null)

    /** Un comando che non viene dalla voce (tasti delle cuffie). */
    fun comanda(c: Comando) { esterno.value = c }

    private sealed interface Fine {
        data object Avanti : Fine
        data object Pausa : Fine
        data object Silenzio : Fine
        /** Dopo un errore: si rifà il giro sulla stessa frase. */
        data object Riprova : Fine
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
     * Il difetto detto a voce, in breve (v1.18.0): su una parola singola le sole
     * lettere del suono, dette all'italiana con la voce italiana («ti acca»); su
     * una frase la sola parola peggiore, con la voce inglese. Niente punteggio:
     * il voto si è appena sentito, e poi viene la ripetizione.
     * ⚠️ Lettere non trovate (`grafema` null) = non si dice niente: meglio
     * niente che una lettera sbagliata, e la parola la rilegge la ripetizione.
     */
    private suspend fun diciDifetto(d: Difetto, v: Verdetto, parola: Boolean, voce: Voce, accento: Accento) {
        if (parola) {
            val s = d as? Difetto.Suono ?: return
            val lettere = s.grafema?.let { Grafema.lettere(s.parola.testo.substring(it)) }
            if (lettere.isNullOrBlank()) return
            val (vIt, lIt) = Feedback.voce(LinguaFeedback.ITALIANO, voce, accento)
            Servizi.tts.parla(lettere, vIt, lIt)
        } else {
            val p = when (d) {
                is Difetto.Suono -> d.parola
                is Difetto.ParolaBassa -> d.parola
                is Difetto.Saltata -> d.parola
                else -> v.daCurare.firstOrNull()
            } ?: return
            val w = p.testo.trim { c -> !c.isLetterOrDigit() && c != '\'' }
            if (w.isNotEmpty()) Servizi.tts.pronuncia(w, voce, accento)
        }
    }

    /** Lo spelling o la traduzione chiesti a voce. Torna [Fine.Pausa] se una
     *  pausa lo interrompe, altrimenti [Fine.Avanti] (cioè: si va avanti col giro). */
    private suspend fun aiuto(c: Comando, frase: Frase, voce: Voce, accento: Accento, di: suspend (Feedback.Cosa) -> Unit): Fine =
        interrompibile {
            when (c) {
                Comando.SPELLING -> if (frase.parola) {
                    cosa("🔤 Spelling")
                    Servizi.tts.compita(frase.testo, voce, accento)
                } else di(Feedback.Cosa.SOLO_PAROLE)
                Comando.TRADUCI -> {
                    cosa("🇮🇹 Traduzione")
                    val tr = Servizi.traduzioni.mappa.value[frase.id]
                    val (vIt, lIt) = Feedback.voce(LinguaFeedback.ITALIANO, voce, accento)
                    Servizi.tts.parla(tr ?: "Traduzione non disponibile.", vIt, lIt)
                }
                else -> Unit
            }
            Fine.Avanti
        }

    /** Quanti «N. Good!» non ancora in cache si preparano a ogni frase: pochi,
     *  perché il livello gratuito di Azure limita le sintesi al minuto. */
    private const val BRAVI_PER_FRASE = 2

    suspend fun esegui(ctx: Context) {
        // Il lavoro in sottofondo vive quanto il ciclo, e muore con lui.
        val sfondo = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        try {
            esegui(ctx, sfondo)
        } finally {
            sfondo.cancel()
        }
    }

    private suspend fun esegui(ctx: Context, sfondo: CoroutineScope) {
        val p = Servizi.prefs
        val voce = p.voce
        val accento = p.accento
        val lingua = p.linguaFeedback
        val (voceFb, linguaFb) = Feedback.voce(lingua, voce, accento)
        suspend fun di(c: Feedback.Cosa) = Servizi.tts.parla(Feedback.testo(lingua, c), voceFb, linguaFb)

        /** Un avviso che deve arrivare anche senza rete: la frase se è in
         *  cache, altrimenti due bip. ⚠️ Non lancia (a parte l'annullamento):
         *  sta dentro la gestione di un errore, e un secondo errore lì la
         *  farebbe saltare. */
        suspend fun avvisa(c: Feedback.Cosa) {
            try {
                di(c)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Bip.errore(Servizi.tts.comunicazione)
            }
        }

        /** Prepara qualcosa senza far aspettare il giro. ⚠️ Gli errori si
         *  buttano: qui si anticipa soltanto, e chi ne ha bisogno rifà la
         *  stessa richiesta e l'errore lo vede lì. */
        fun sottofondo(blocco: suspend () -> Unit) {
            sfondo.launch {
                try {
                    blocco()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // vedi sopra
                }
            }
        }

        /** Quel che servirà per [f], pronto prima che serva: il token, la sua
         *  lettura normale e — per una parola — quella lenta del «riprova». */
        fun anticipa(f: Frase) = sottofondo {
            Servizi.azure.prendi()
            Servizi.tts.preparaFrase(f.testo, voce, accento)
            if (f.parola) Servizi.tts.preparaFrase(f.testo, voce, accento, lenta = true)
        }

        /** I «N. Good!» che mancano in cache, pochi per volta dal 100 in giù. */
        fun anticipaBravi() = sottofondo {
            var fatti = 0
            for (n in 100 downTo SOGLIA_BUONA.toInt()) {
                val t = Feedback.votoE(lingua, n.toDouble(), Feedback.Cosa.BRAVO)
                if (Servizi.tts.inCache(t, voceFb, linguaFb)) continue
                Servizi.tts.prepara(t, voceFb, linguaFb)
                if (++fatti >= BRAVI_PER_FRASE) break
            }
        }

        val quante = p.quanteCiclo
        val migliori = mutableMapOf<String, Double>()
        stato.update { it.copy(quante = quante) }

        /** L'ultimo elemento è fatto: voto, a voce e a schermo, e Obiettivi. */
        suspend fun finisci() {
            val v = votoSessione(migliori.values) ?: return
            val media = migliori.values.average()
            stato.update { it.copy(voto = v, media = media, cosa = "🏁 Sessione finita", nota = null) }
            // ⚠️ Se la voce non riesce (rete caduta proprio alla fine) il voto
            // è già a schermo, e Obiettivi va aggiornato lo stesso.
            try {
                Servizi.tts.parla(Feedback.fine(lingua, v), voceFb, linguaFb)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SessioneScaduta) {
                throw e
            } catch (e: Exception) {
                Bip.errore(Servizi.tts.comunicazione)
            }
            val esito = try {
                Obiettivo.aggiorna(v, "Mani libere: ${migliori.size} ${if (p.paroleCiclo) "parole" else "frasi"}, media ${Math.round(media)}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "⚠️ Obiettivi non aggiornato: ${e.message ?: e.javaClass.simpleName}"
            }
            stato.update { it.copy(esitoObiettivo = esito) }
        }

        val coda = ArrayDeque(Sessione.scegli(ctx, p.livelloCiclo, p.temaCiclo, p.paroleCiclo, quante))
        if (coda.isEmpty()) { stato.update { it.copy(nota = "Nessuna frase per questo filtro.") }; return }
        var silenzi = 0
        var tentativo = 0
        var guasti = 0

        /** Una frase, dalla lettura fino a un esito. */
        suspend fun giro(frase: Frase, lenta: Boolean): Fine {
            cosa(if (lenta) "🐢 Ascolta, piano" else "🔊 Ascolta")
            Servizi.tts.pronuncia(frase.testo, voce, accento, lenta)
            while (true) {
                cosa("🎙 Tocca a te")
                val esito = try { Servizi.valutatore.valuta(frase.testo, accento, p.soglia(Controllo.RITMO, frase.parola) != null, comandi = true) } catch (e: NessunaVoce) { null }
                if (esito == null) {
                    if (++silenzi >= SILENZI) return Fine.Silenzio
                    di(Feedback.Cosa.SILENZIO)
                    continue
                }
                silenzi = 0
                Comando.da(esito.riconosciuto, frase.testo)?.let { c ->
                    // v1.32.0: il comando riconosciuto si dice, a voce e a schermo.
                    // Senza, una parola presa per comando («fen» per «then») si
                    // leggeva come un tentativo sparito senza voto.
                    val nome = ComandiVoce.Voce.entries.firstOrNull { it.comando == c }?.parola ?: c.name
                    cosa("🎙 Comando: $nome")
                    val (vIt, lIt) = Feedback.voce(LinguaFeedback.ITALIANO, voce, accento)
                    Servizi.tts.parla("Comando: $nome.", vIt, lIt)
                    return Fine.Comandato(c)
                }
                Storico.registra(ctx, frase, esito, "mani_libere")
                migliori[frase.id] = maxOf(migliori[frase.id] ?: 0.0, esito.pronuncia)
                stato.update { it.copy(fatte = migliori.size) }
                tentativo++
                stato.update { it.copy(tentativo = tentativo, ultimo = esito.pronuncia) }
                val verdetto = Giudizio.di(esito, p, frase.parola)
                stato.update { it.copy(verdetto = verdetto) }
                if (verdetto.buona) {
                    stato.update { it.copy(buone = it.buone + 1) }
                    cosa("📊 Il voto")
                    Servizi.tts.parla(Feedback.votoE(lingua, esito.pronuncia, Feedback.Cosa.BRAVO), voceFb, linguaFb)
                    return Fine.Avanti
                }
                val ultimo = tentativo >= TENTATIVI
                // Perché è da ripetere, se non è il solo voto basso (quello si
                // sente comunque): il primo controllo che ha bocciato. Su una
                // frase bocciata dal solo voto si dice comunque la parola
                // peggiore (diciDifetto ripiega su `daCurare`).
                val difetto = verdetto.difetti.firstOrNull { it !is Difetto.Voto }
                    ?: verdetto.difetti.firstOrNull().takeIf { !frase.parola }
                // All'ultimo tentativo col difetto da dire restano tre pezzi
                // («62.», il difetto, «Let's move on.»): detto dopo il saluto
                // il difetto non si capirebbe più a cosa si riferisce.
                val commento = when {
                    !ultimo -> Feedback.votoE(lingua, esito.pronuncia, Feedback.Cosa.RIPROVA)
                    difetto == null -> Feedback.votoE(lingua, esito.pronuncia, Feedback.Cosa.AVANTI)
                    else -> Feedback.votoBreve(esito.pronuncia)
                }
                val daCurare = if (frase.parola) emptyList() else verdetto.daCurare
                    .map { it.testo.trim { c -> !c.isLetterOrDigit() && c != '\'' } }.filter { it.isNotEmpty() }
                // Commento e parole lente si preparano mentre si riascolta la voce.
                sottofondo {
                    Servizi.tts.prepara(commento, voceFb, linguaFb)
                    if (!ultimo) daCurare.forEach { Servizi.tts.preparaFrase(it, voce, accento, lenta = true) }
                }
                // Il riascolto: la propria voce prima del giudizio, così il
                // voto si confronta con quel che si è appena sentito.
                // ⚠️ v1.34.0: solo quando è da ripetere — riuscita, si va avanti.
                if (p.riascoltoCiclo) Servizi.valutatore.ultimaVoce?.let { v ->
                    cosa("🔁 La tua voce")
                    Riascolto.suona(v, Servizi.tts.comunicazione)
                }
                cosa("📊 Il voto")
                Servizi.tts.parla(commento, voceFb, linguaFb)
                difetto?.let { d ->
                    cosa("🔎 Cosa curare")
                    diciDifetto(d, verdetto, frase.parola, voce, accento)
                }
                if (ultimo) {
                    if (difetto != null) di(Feedback.Cosa.AVANTI)
                    return Fine.Avanti
                }
                if (frase.parola) {
                    // Una parola sola: le «parole da curare» sarebbero lei,
                    // quindi la si rilegge piano e basta.
                    cosa("🐢 Ascolta, piano")
                    Servizi.tts.pronuncia(frase.testo, voce, accento, lenta = true)
                } else {
                    cosa("🐢 Le parole da curare")
                    daCurare.forEach { Servizi.tts.pronuncia(it, voce, accento, lenta = true) }
                    cosa("🔊 Ascolta")
                    Servizi.tts.pronuncia(frase.testo, voce, accento)
                }
            }
        }

        esterno.value = null
        // Le traduzioni per «translate»: la schermata le scarica già, ma il
        // ciclo può partire prima che lo abbia fatto. Senza rete resta la cache
        // (gli errori di rete li assorbe già `aggiorna`). In sottofondo dalla
        // v1.34.0: prima si aspettava lo scarico per cominciare.
        sottofondo { Servizi.traduzioni.aggiorna() }
        // La prima frase si prepara mentre si dice «Let's start».
        anticipa(coda.first().frase)
        di(Feedback.Cosa.INIZIO)
        // Gli avvisi di rete in cache adesso che la rete c'è (vedi `avvisa`).
        listOf(Feedback.Cosa.RETE, Feedback.Cosa.RETE_FERMA).forEach { c ->
            try {
                Servizi.tts.prepara(Feedback.testo(lingua, c), voceFb, linguaFb)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pazienza: al bisogno suoneranno i due bip.
            }
        }
        var frase: Frase? = null
        var lenta = false
        while (true) {
            if (frase == null) {
                val n = migliori.size + 1
                val scelta = coda.first()
                val nuova = scelta.frase
                frase = nuova
                tentativo = 0
                // Mentre si fa questa si prepara la prossima (e i «N. Good!»).
                anticipa(nuova)
                coda.getOrNull(1)?.let { anticipa(it.frase) }
                anticipaBravi()
                stato.update {
                    it.copy(
                        frase = nuova, tentativo = 0, ultimo = null, frasi = n,
                        recente = scelta.recente, suono = scelta.suono, verdetto = null,
                    )
                }
            }
            val f = frase!!
            val fine: Fine = try {
                val esito = interrompibile { giro(f, lenta) }
                guasti = 0
                esito
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SessioneScaduta) {
                throw e
            } catch (e: Exception) {
                val perche = e.message ?: e.javaClass.simpleName
                if (++guasti > GUASTI) {
                    stato.update { it.copy(nota = "Fermato: $GUASTI errori di fila, probabilmente la rete. L'ultimo: $perche") }
                    avvisa(Feedback.Cosa.RETE_FERMA)
                    return
                }
                cosa("📡 Problema di rete: riprovo ($guasti/$GUASTI)")
                if (guasti == 1) avvisa(Feedback.Cosa.RETE)
                val attesa = ATTESA_GUASTO_MS * guasti
                // Interrompibile come il giro: pausa, tasti e Ferma valgono anche qui.
                interrompibile { delay(attesa); Fine.Riprova }
            }
            lenta = false
            // L'ultimo elemento della sessione, provato almeno una volta: basta.
            if ((fine == Fine.Avanti || (fine is Fine.Comandato && fine.c == Comando.AVANTI)) &&
                migliori.size >= quante) { finisci(); return }
            when (fine) {
                Fine.Avanti -> {
                    coda.removeFirst()
                    if (coda.isEmpty()) { finisci(); return }
                    frase = null
                }
                Fine.Silenzio -> {
                    stato.update { it.copy(nota = "Fermato: nessuna risposta per $SILENZI volte di fila.") }
                    di(Feedback.Cosa.PAUSA)
                    return
                }
                Fine.Pausa -> {
                    cosa("⏸ In pausa")
                    pausa.first { !it }
                }
                // Il giro riparte sulla stessa frase: la si rilegge e si ascolta.
                Fine.Riprova -> Unit
                is Fine.Comandato -> when (fine.c) {
                    // Mai provata: in fondo alla coda, non fuori dalla sessione.
                    Comando.AVANTI -> {
                        val s0 = coda.removeFirst()
                        if (s0.frase.id !in migliori) coda.addLast(s0)
                        if (coda.isEmpty()) { finisci(); return }
                        frase = null
                    }
                    Comando.RIPETI -> Unit
                    Comando.LENTO -> lenta = true
                    // Spelling o traduzione, poi il giro rilegge la frase e ascolta.
                    Comando.SPELLING, Comando.TRADUCI ->
                        if (aiuto(fine.c, f, voce, accento) { di(it) } == Fine.Pausa) {
                            cosa("⏸ In pausa")
                            pausa.first { !it }
                        }
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
