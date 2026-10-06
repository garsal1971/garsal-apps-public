package com.garsal.appsphere.peso

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.garsal.appsphere.core.messaggioBreve
import com.garsal.appsphere.calorie.CalorieRegole
import com.garsal.appsphere.calorie.CalorieRepository
import java.time.LocalDate
import java.time.LocalTime

/** Le due schede in cima: oggi, la precedente o la prossima pesata. */
enum class TipoScheda { OGGI, PRIMA, DOPO }

/**
 * Le calorie di un giorno di pesata — lette da `al_conto`, mai ricalcolate
 * qui: il conto è quello del 📓 Diario. `motivo` non nullo = niente numeri, e
 * la ragione da scrivere al loro posto.
 */
data class CaloriePesata(
    val fabbisogno: Double? = null,
    val daPiano: Double? = null,
    val conAndamento: Double? = null,
    val congelato: Boolean = false,
    val motivo: String? = null,
)

/**
 * Una scheda delle pesate — `schedaPesata()` in `weight-quest.html`: minimo,
 * target e quanto manca per un giorno di pesata, più le calorie.
 */
data class SchedaPesata(
    val tipo: TipoScheda,
    val giorno: String,
    val peso: Double?,
    val nota: String?,
    val target: Double?,
    val calorie: CaloriePesata?,
    /** Sulla massa grassa: «totale X kg · grasso Y %» del peso mostrato. */
    val info: String? = null,
    /** Sulla massa grassa: «di X kg · Y %» sotto il target. */
    val infoTarget: String? = null,
) {
    val manca: Double? get() = if (peso != null && target != null) peso - target else null
}

data class PesoState(
    val pesate: List<Pesata> = emptyList(),
    val obiettivi: List<Obiettivo> = emptyList(),
    /** Quello che si sta guardando: di partenza il più recente. */
    val obiettivoId: String? = null,
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
    /** Un tocco: Health Connect ha rifiutato la lettura, va richiesto il permesso. */
    val permessiSaluteRichiesti: Boolean = false,
    /**
     * I premi già grattati dell'obiettivo che si sta guardando, per soglia in
     * kg — da `ps_milestone_prizes`, la stessa tabella del web.
     */
    val premi: Map<Int, PremioVinto> = emptyMap(),
    /** `⭐ Punti Totali Traguardi Intermedi` dell'obiettivo che si sta guardando. */
    val puntiTraguardi: Int = 0,
    /**
     * Cresce di uno **solo** quando premi e punti arrivano dal database, mai
     * mentre si digita: è la chiave con cui il campo dei punti si riscrive una
     * volta caricato, senza azzerarsi ad ogni cifra battuta.
     */
    val premiVersione: Int = 0,
    /**
     * Il conto del diario alimentare (`al_conto`), per le calorie delle schede.
     * `null` finché non arriva — o se non arriva: le schede restano senza
     * calorie invece di fermare la pagina.
     */
    val contoCalorie: CalorieRegole.Conto? = null,
    /**
     * I punti dell'obiettivo che si sta guardando, dalla RPC `ps_punti` — la
     * stessa del web. `null` finché non arrivano, o se non arrivano: la
     * tabella resta senza punti invece di inventarseli.
     */
    val punti: PuntiServer? = null,
) {
    val obiettivo: Obiettivo?
        get() = obiettivi.firstOrNull { it.id == obiettivoId }

    /** L'obiettivo che fa testo per i badge: solo se è ancora aperto. */
    private val inCorso: Obiettivo?
        get() = obiettivo?.takeIf { it.attivo && it.traguardi.size >= 2 }

    /**
     * La tabella giorno per giorno. `by lazy` e non un getter: la leggono la
     * scheda della tabella, il grafico e il badge dei punti, e ricalcolarla a
     * ogni lettura vorrebbe dire rifare l'interpolazione di un anno di giorni
     * a ogni ridisegno.
     */
    val righe: List<PesoRegole.RigaGiorno> by lazy { PesoRegole.tabella(vista, obiettivo, contoPunti) }

    /**
     * Le pesate come le vede l'obiettivo guardato — `pesiInVista()` del web:
     * sulla massa grassa il peso è la massa grassa e le pesate senza grasso non
     * ci sono. Le leggono tabella, schede e grafico; [pesate] resta il dato vero.
     */
    val vista: List<Pesata> by lazy { PesoRegole.pesiVista(pesate, obiettivo) }

    /** Sull'obiettivo guardato si conta la massa grassa? */
    val massaMagra: Double? get() = PesoRegole.massaMagra(obiettivo)

    /** I punti del server, solo se sono dell'obiettivo che si sta guardando. */
    val puntiDelServer: PuntiServer?
        get() = punti?.takeIf { it.obiettivoId == obiettivoId && it.errore == null }

    private val contoPunti: List<PesoRegole.RigaPunti>
        get() = puntiDelServer?.righe.orEmpty()

    /** Il minimo di oggi, o l'ultima pesata nota — per «Kg alla fine». */
    val minimoOggi: Double? get() = PesoRegole.minimoDiOggi(vista)

    // ── Le due schede delle pesate (web v4.2.0) ─────────────────────────
    //
    // ⚠️ Hanno preso il posto dei riquadri «Minimo oggi / Target oggi /
    // Mancano al target», come nel web: oggi + la prossima se oggi è giorno di
    // pesata, altrimenti la precedente + la prossima. Gemelle di
    // `renderPesate()` / `schedaPesata()` / `righeCaloriePesata()`.

    val schede: List<SchedaPesata> by lazy { calcolaSchede() }

    /** La prossima pesata dovuta dopo oggi, per quando non ce n'è nessuna. */
    val finePiano: String? get() = inCorso?.fine

    private fun calcolaSchede(): List<SchedaPesata> {
        val obj = inCorso ?: return emptyList()
        val oggi = LocalDate.now().toString()
        val schede = mutableListOf<SchedaPesata>()
        if (oggi >= obj.inizio && oggi <= obj.fine && PesoRegole.giornoDiPesata(obj, oggi)) {
            schede += scheda(obj, oggi, TipoScheda.OGGI)
        } else {
            // A dieta finita la «precedente» è l'ultima del piano: si parte dal
            // giorno dopo la fine.
            val da = if (oggi > obj.fine) (PesoRegole.giornoDa(obj.fine)?.plusDays(1)?.toString() ?: oggi) else oggi
            PesoRegole.pesataPrecedente(obj, da)?.let { schede += scheda(obj, it, TipoScheda.PRIMA) }
        }
        val domani = LocalDate.now().plusDays(1).toString()
        PesoRegole.prossimaPesata(obj, domani)?.let { schede += scheda(obj, it, TipoScheda.DOPO) }
        return schede
    }

    private fun scheda(obj: Obiettivo, giorno: String, tipo: TipoScheda): SchedaPesata {
        val oggi = LocalDate.now().toString()
        var target = PesoRegole.aGrasso(PesoRegole.targetInterpolato(obj.traguardi, giorno), obj)
        var peso: Double? = null
        var nota: String? = null
        var info: String? = null
        if (tipo == TipoScheda.DOPO) {
            ultimoPesoFinoA(oggi)?.let { p ->
                peso = p.peso
                nota = "ultimo peso, del ${ddmm(p.giorno)}"
                info = PesoRegole.notaTotale(p)
            }
        } else {
            val minima = vista.filter { it.giorno == giorno }.minByOrNull { it.peso }
            if (minima != null) {
                peso = minima.peso
                info = PesoRegole.notaTotale(minima)
                // Il target congelato sulla riga, come nei punti.
                minima.target?.let { target = it }
            } else {
                val prima = ultimoPesoFinoA(giorno)
                val lab = if (tipo == TipoScheda.OGGI) "non ancora pesato" else "non pesato"
                if (prima != null) {
                    peso = prima.peso
                    info = PesoRegole.notaTotale(prima)
                    nota = "$lab: ultimo peso del ${ddmm(prima.giorno)}"
                } else {
                    nota = lab
                }
            }
        }
        return SchedaPesata(
            tipo, giorno, peso, nota, target, caloriePer(obj, giorno, tipo),
            info = info,
            infoTarget = PesoRegole.notaTargetTotale(target, obj),
        )
    }

    /** Il minimo dell'ultimo giorno pesato fino a [giorno] compreso. */
    private fun ultimoPesoFinoA(giorno: String): Pesata? {
        val ultimo = vista.filter { it.giorno <= giorno }.maxOfOrNull { it.giorno } ?: return null
        return vista.filter { it.giorno == ultimo }.minByOrNull { it.peso }
    }

    /**
     * Le calorie della scheda. ⚠️ Solo se l'obiettivo guardato è quello
     * **attivo** del diario (`obiettivoAttivo()`, l'ultimo `active`): è lui a
     * dettare il target di `al_conto`, e un altro obiettivo darebbe numeri
     * che il diario non conosce. Sulla prossima pesata il peso non si conosce:
     * `calcolaTarget` ripiega sull'ultimo noto, com'è la riga «Minimo».
     */
    private fun caloriePer(obj: Obiettivo, giorno: String, tipo: TipoScheda): CaloriePesata? {
        val conto = contoCalorie ?: return null
        val attivo = obiettivi.firstOrNull { it.stato == "active" } ?: return null
        if (attivo.id != obj.id) return null
        val t = if (tipo == TipoScheda.DOPO) conto.calcolato(giorno) else conto.target(giorno)
        if (!t.ok) {
            return CaloriePesata(
                motivo = when (t.motivo) {
                    CalorieRegole.Motivo.PROFILO -> "mancano data di nascita, altezza o sesso nel profilo"
                    CalorieRegole.Motivo.PESO -> "nessun peso registrato"
                    else -> "dati non disponibili"
                }
            )
        }
        return CaloriePesata(
            fabbisogno = t.tdee,
            daPiano = conto.tratto(giorno),
            conAndamento = t.kcal,
            congelato = t.congelato,
        )
    }

    private fun ddmm(iso: String): String = "${iso.substring(8, 10)}/${iso.substring(5, 7)}"

    /** Kg alla fine — la distanza dal peso previsto all'ultimo giorno. */
    val kgAllaFine: Double?
        get() {
            val obiettivo = inCorso ?: return null
            val peso = minimoOggi ?: return null
            val finale = PesoRegole.aGrasso(PesoRegole.targetInterpolato(obiettivo.traguardi, obiettivo.fine), obiettivo)
                ?: return null
            return peso - finale
        }

    /**
     * Punteggio — la somma dei punteggi finali degli obiettivi **chiusi**,
     * come `updateDashboardStats()`: quelli ancora aperti non hanno un
     * punteggio finale e non ci entrano.
     */
    val punteggio: Int
        get() = obiettivi.filterNot { it.attivo }.sumOf { it.punteggioFinale ?: 0 }

    /**
     * Punti oggi — il cumulativo dell'obiettivo in corso fino a oggi.
     *
     * Il nome è quello del web e vuol dire «a che punto sei», non «quanto hai
     * guadagnato oggi»: è lo stesso numero dell'ultima riga della tabella.
     */
    val puntiOggi: Int?
        get() {
            if (inCorso == null) return null
            return righe.firstOrNull { it.cumulativo != null }?.cumulativo
        }

    /**
     * I punti delle soglie già raggiunte dell'obiettivo che si sta guardando,
     * come li conta `ps_punti`. Li usa la casella «Punti oggi».
     */
    val puntiTraguardiRaggiunti: Int
        get() = puntiDelServer?.puntiTraguardi ?: 0

    /**
     * Quello che si legge nella casella «Punti oggi»: i giornalieri **più** i
     * traguardi già raggiunti — cioè quel che si porterebbe a casa chiudendo
     * bene, al netto del bonus finale.
     *
     * ⚠️ La chiusura **non** legge questo numero: somma i due addendi per conto
     * suo, o i traguardi conterebbero due volte.
     */
    val puntiOggiMostrati: Int?
        get() = puntiOggi?.plus(puntiTraguardiRaggiunti)

    /** Ci si è già pesati oggi? È la domanda che dà il nome all'app. */
    val pesatoOggi: Boolean
        get() = pesate.any { it.giorno == LocalDate.now().toString() }
}

/**
 * Cosa risponde [PesoViewModel.preparaChiusura] — `closeObjective()` nel web,
 * dove è un `alert()` che blocca o un `confirm()` col conto dei punti.
 */
sealed class EsitoChiusura {
    data class Bloccata(val motivo: String) : EsitoChiusura()
    data class DaConfermare(
        val puntiGiornalieri: Int,
        val traguardiRaggiunti: Int,
        val traguardiTotali: Int,
        /** Le soglie già raggiunte: valgono solo chiudendo con successo. */
        val puntiTraguardi: Int,
        val puntiChiusura: Int,
        val totale: Int,
    ) : EsitoChiusura()
}

class PesoViewModel : ViewModel() {

    private val _state = MutableStateFlow(PesoState())
    val state: StateFlow<PesoState> = _state.asStateFlow()

    init { carica() }

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val obiettivi = PesoRepository.obiettivi()
                // L'obiettivo scelto si tiene fra un caricamento e l'altro; al
                // primo giro si parte dal più recente — `obiettivi()` è già
                // ordinata per `created_at` decrescente, quindi è il primo
                // della lista, chiuso o no.
                val scelto = _state.value.obiettivoId?.takeIf { id -> obiettivi.any { it.id == id } }
                    ?: obiettivi.firstOrNull()?.id
                val da = daQuando(obiettivi, scelto)

                _state.value = _state.value.copy(
                    pesate = PesoRepository.pesate(da = da),
                    obiettivi = obiettivi,
                    obiettivoId = scelto,
                    caricamento = false,
                )
                caricaPunti()
                // Le calorie delle schede: dopo, e senza fermare niente — se il
                // diario non si legge, le schede restano senza calorie.
                caricaContoCalorie()
            } catch (e: Exception) {
                Log.w(TAG, "caricamento fallito", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.messaggioBreve("Caricamento non riuscito"),
                )
            }
        }
    }

    /**
     * Da dove far partire la lettura delle pesate: l'inizio dell'obiettivo che
     * si sta guardando, meno un mese di respiro per la prima interpolazione.
     * Senza obiettivo si guarda indietro un anno e mezzo.
     *
     * Non è un dettaglio di prestazioni: la tabella e il cumulativo devono
     * poter vedere **tutti** i giorni dell'obiettivo, o il conto dei punti
     * comincerebbe a metà strada e non tornerebbe con quello del web.
     */
    private fun daQuando(obiettivi: List<Obiettivo>, scelto: String?): LocalDate {
        val obiettivo = obiettivi.firstOrNull { it.id == scelto }
        val inizio = PesoRegole.giornoDa(obiettivo?.inizio)
        return inizio?.minusMonths(1) ?: LocalDate.now().minusMonths(18)
    }

    fun scegliObiettivo(id: String) {
        // Premi e punti sono di **quell'** obiettivo: lasciare i vecchi a
        // schermo mentre arrivano i nuovi mostrerebbe le stelline sbagliate.
        _state.value = _state.value.copy(obiettivoId = id, premi = emptyMap(), puntiTraguardi = 0)
        // Un altro obiettivo è un altro periodo: le pesate vanno rilette, o la
        // sua tabella comincerebbe dal giorno in cui comincia quella di prima.
        carica()
    }

    /**
     * Segna la pesata.
     *
     * Il target che finisce nella riga è quello interpolato **oggi**, come fa
     * il web: si congela nella pesata e non si ricalcola più, così spostare i
     * traguardi domani non riscrive il giudizio sui giorni già passati.
     */
    fun pesati(giorno: LocalDate, ora: LocalTime, peso: Double, grasso: Double? = null) {
        viewModelScope.launch {
            val obiettivo = _state.value.obiettivo
            val target = obiettivo?.let { PesoRegole.targetInterpolato(it.traguardi, giorno.toString()) }
            try {
                PesoRepository.salvaPesata(giorno, ora, peso, target, grasso)
                _state.value = _state.value.copy(
                    messaggio = "⚖️ ${kg(peso)} kg segnati per il ${dataItaliana(giorno.toString())}"
                )
                ricaricaPesate()
            } catch (e: Exception) {
                Log.w(TAG, "pesata non salvata", e)
                _state.value = _state.value.copy(
                    messaggio = "Non salvata: ${e.messaggioBreve()}"
                )
            }
        }
    }

    /**
     * Cancella una pesata scritta a mano. Ottimistica con rollback, come le
     * spunte di Spuntiamola: la riga sparisce subito e torna se il database
     * dice di no.
     */
    fun elimina(pesata: Pesata) {
        viewModelScope.launch {
            val prima = _state.value.pesate
            _state.value = _state.value.copy(
                pesate = prima.filterNot { it.timestamp == pesata.timestamp }
            )
            try {
                PesoRepository.eliminaPesata(pesata.timestamp)
                _state.value = _state.value.copy(messaggio = "🗑 Pesata eliminata")
                caricaPunti()
            } catch (e: Exception) {
                Log.w(TAG, "eliminazione non riuscita", e)
                _state.value = _state.value.copy(
                    pesate = prima,
                    messaggio = "Non eliminata: ${e.messaggioBreve()}",
                )
            }
        }
    }

    /** Le pesate di un giorno, per il dettaglio: la più leggera per prima. */
    fun pesateDel(giorno: String): List<Pesata> =
        _state.value.pesate.filter { it.giorno == giorno }.sortedBy { it.peso }

    private suspend fun ricaricaPesate() {
        val stato = _state.value
        runCatching { PesoRepository.pesate(da = daQuando(stato.obiettivi, stato.obiettivoId)) }
            .onSuccess { _state.value = _state.value.copy(pesate = it) }
            .onFailure { Log.w(TAG, "ricarica non riuscita", it) }
        caricaPunti()
        // Una pesata nuova cambia il conto delle calorie: le schede lo rileggono.
        caricaContoCalorie()
    }

    /** Il conto delle calorie (`al_conto`) per le schede. Non fallisce mai. */
    private suspend fun caricaContoCalorie() {
        runCatching { CalorieRepository.conto(LocalDate.now()) }
            .onSuccess { _state.value = _state.value.copy(contoCalorie = it) }
            .onFailure { Log.w(TAG, "conto calorie non letto", it) }
    }

    /**
     * I punti dell'obiettivo scelto, dalla RPC `ps_punti`. Non fallisce mai:
     * senza risposta i punti restano vuoti e il log lo dice.
     */
    private suspend fun caricaPunti() {
        val id = _state.value.obiettivoId ?: run {
            _state.value = _state.value.copy(punti = null)
            return
        }
        runCatching { PesoRepository.punti(id, LocalDate.now()) }
            .onSuccess { punti ->
                if (punti.errore != null) Log.w(TAG, "ps_punti: ${punti.errore}")
                // Se nel frattempo si è cambiato obiettivo, è una risposta a
                // una domanda che non è più quella aperta.
                if (_state.value.obiettivoId == id) _state.value = _state.value.copy(punti = punti)
            }
            .onFailure { Log.w(TAG, "punti non letti", it) }
    }

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    // ── Gestione obiettivi ────────────────────────────────────────────────

    /**
     * Crea o aggiorna un obiettivo — `saveObjective()`. Dopo il salvataggio lo
     * riseleziona: è quello che si vuole vedere subito, nuovo o appena
     * modificato che sia.
     */
    fun salvaObiettivo(id: String?, bozza: BozzaObiettivo) {
        viewModelScope.launch {
            try {
                val riga = PesoRepository.rigaObiettivo(bozza)
                val nuovoId = PesoRepository.salvaObiettivo(id, riga)
                sincronizzaPromemoria(nuovoId, bozza, riga)
                _state.value = _state.value.copy(
                    obiettivoId = nuovoId,
                    messaggio = "💾 Obiettivo «${bozza.nome.trim()}» ${if (id == null) "creato" else "aggiornato"}",
                )
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "obiettivo non salvato", e)
                _state.value = _state.value.copy(messaggio = "Non salvato: ${e.messaggioBreve()}")
            }
        }
    }

    /**
     * Le regole della notifica della pesata. Un errore qui non disfa il
     * salvataggio — l'obiettivo è scritto — ma si dice, come l'`alert` del web.
     */
    private suspend fun sincronizzaPromemoria(id: String, bozza: BozzaObiettivo, riga: kotlinx.serialization.json.JsonObject) {
        try {
            PesoRepository.sincronizzaPromemoria(
                id = id,
                nome = bozza.nome.trim().ifBlank { "Ti pisasti?" },
                inizio = testo(riga, "start_date").orEmpty(),
                fine = testo(riga, "end_date").orEmpty(),
                ogniGiorni = bozza.ogniGiorni,
                attivo = bozza.promemoriaAttivo,
                ora = bozza.promemoriaOra,
                canali = bozza.promemoriaCanali,
            )
        } catch (e: Exception) {
            Log.w(TAG, "promemoria pesata non salvato", e)
            _state.value = _state.value.copy(
                messaggio = "Obiettivo salvato, ma il promemoria no: ${e.messaggioBreve()}"
            )
        }
    }

    /** Chiuso o eliminato, l'obiettivo non chiede più pesate: le regole si tolgono. */
    private suspend fun togliPromemoria(obiettivo: Obiettivo) {
        runCatching {
            PesoRepository.sincronizzaPromemoria(
                obiettivo.id, obiettivo.nome, obiettivo.inizio, obiettivo.fine,
                obiettivo.ogniGiorni, attivo = false, ora = null, canali = emptyList(),
            )
        }.onFailure { Log.w(TAG, "promemoria pesata non tolto", it) }
    }

    /**
     * I controlli di chiusura e il conto dei punti, prima della conferma.
     *
     * ⚠️ Non stanno più qui: li fa la RPC `ps_chiudi_obiettivo` con
     * `p_conferma = false`, la stessa che chiama `closeObjective()` nel web.
     * Fino all'APK 1.0.92 erano scritti qui e là, e il punteggio che finisce in
     * `total_score` poteva dipendere da quale app chiudeva l'obiettivo.
     */
    fun preparaChiusura(obiettivo: Obiettivo, nuovoStato: String, risposta: (EsitoChiusura) -> Unit) {
        viewModelScope.launch {
            val esito = try {
                val r = PesoRepository.chiusura(obiettivo.id, nuovoStato, LocalDate.now(), conferma = false)
                if (testo(r, "ok") != "true") {
                    EsitoChiusura.Bloccata(testo(r, "error") ?: "Chiusura non possibile.")
                } else {
                    EsitoChiusura.DaConfermare(
                        puntiGiornalieri = intero(r, "punti_giornalieri")?.toInt() ?: 0,
                        traguardiRaggiunti = intero(r, "traguardi_raggiunti")?.toInt() ?: 0,
                        traguardiTotali = intero(r, "traguardi_totali")?.toInt() ?: 0,
                        puntiTraguardi = intero(r, "punti_traguardi")?.toInt() ?: 0,
                        puntiChiusura = intero(r, "punti_chiusura")?.toInt() ?: 0,
                        totale = intero(r, "totale")?.toInt() ?: 0,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "conto della chiusura non riuscito", e)
                EsitoChiusura.Bloccata("Non ho potuto fare il conto: ${e.messaggioBreve()}")
            }
            risposta(esito)
        }
    }

    /**
     * Chiude l'obiettivo: la RPC rifà controlli e conto — fra la conferma e il
     * tocco può essere arrivata una pesata — e scrive stato e punteggio.
     */
    fun chiudiObiettivo(obiettivo: Obiettivo, nuovoStato: String) {
        viewModelScope.launch {
            try {
                val r = PesoRepository.chiusura(obiettivo.id, nuovoStato, LocalDate.now(), conferma = true)
                if (testo(r, "ok") != "true") {
                    _state.value = _state.value.copy(messaggio = "Non chiuso: ${testo(r, "error") ?: "risposta non valida"}")
                    return@launch
                }
                val totale = intero(r, "totale")?.toInt() ?: 0
                togliPromemoria(obiettivo)
                _state.value = _state.value.copy(messaggio = "Obiettivo chiuso! Punteggio finale: $totale")
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "chiusura non riuscita", e)
                _state.value = _state.value.copy(messaggio = "Non chiuso: ${e.messaggioBreve()}")
            }
        }
    }

    /**
     * Riapre un obiettivo chiuso: la regola sta nella RPC `ps_riapri_obiettivo`
     * (gemella di `reopenObjective()` nel web). Alla chiusura i promemoria della
     * pesata erano stati tolti: si rimettono come sono scritti sulla riga.
     */
    fun riapriObiettivo(obiettivo: Obiettivo) {
        viewModelScope.launch {
            try {
                val r = PesoRepository.riapertura(obiettivo.id)
                if (testo(r, "ok") != "true") {
                    _state.value = _state.value.copy(messaggio = "Non riaperto: ${testo(r, "error") ?: "risposta non valida"}")
                    return@launch
                }
                runCatching {
                    PesoRepository.sincronizzaPromemoria(
                        obiettivo.id, obiettivo.nome.ifBlank { "Ti pisasti?" }, obiettivo.inizio, obiettivo.fine,
                        obiettivo.ogniGiorni, obiettivo.promemoriaAttivo, obiettivo.promemoriaOra,
                        obiettivo.promemoriaCanali,
                    )
                }.onFailure {
                    Log.w(TAG, "promemoria pesata non ricreato", it)
                }
                val tolti = obiettivo.punteggioFinale ?: 0
                _state.value = _state.value.copy(
                    obiettivoId = obiettivo.id,
                    messaggio = "↩️ Obiettivo riaperto" + if (tolti != 0) " · $tolti punti tolti dal totale" else "",
                )
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "riapertura non riuscita", e)
                _state.value = _state.value.copy(messaggio = "Non riaperto: ${e.messaggioBreve()}")
            }
        }
    }

    /**
     * Cancella un obiettivo — sempre permesso, anche chiuso: `deleteObjective()`
     * non lo blocca mai, a differenza di Salva/Successo/Fallito.
     */
    fun eliminaObiettivo(obiettivo: Obiettivo) {
        viewModelScope.launch {
            try {
                PesoRepository.eliminaObiettivo(obiettivo.id)
                togliPromemoria(obiettivo)
                _state.value = _state.value.copy(
                    obiettivoId = _state.value.obiettivoId.takeUnless { it == obiettivo.id },
                    messaggio = "🗑 Obiettivo «${obiettivo.nome}» eliminato",
                )
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "eliminazione obiettivo non riuscita", e)
                _state.value = _state.value.copy(messaggio = "Non eliminato: ${e.messaggioBreve()}")
            }
        }
    }

    // ── Sincronizzazione con la bilancia (Health Connect) ──────────────────

    /**
     * Legge le pesate da Health Connect e le scrive — `syncFitNative()` +
     * `processWeights()` nel web. Se il permesso non è ancora concesso lo
     * dice con [PesoState.permessiSaluteRichiesti]: tocca alla schermata
     * aprire la richiesta di sistema e richiamare questa funzione dopo la
     * concessione, esattamente come il `retry` del bridge Kotlin nel web.
     */
    fun sincronizzaSalute(context: Context) {
        viewModelScope.launch {
            _state.value = _state.value.copy(messaggio = "🏃 Sincronizzazione con Salute...")
            when (val esito = SaluteRepository.leggiPeso(context)) {
                is EsitoSalute.PermessiRichiesti ->
                    _state.value = _state.value.copy(messaggio = null, permessiSaluteRichiesti = true)
                is EsitoSalute.Errore ->
                    _state.value = _state.value.copy(messaggio = "Salute: ${esito.messaggio}")
                is EsitoSalute.Ok -> salvaPuntiSalute(esito.punti, esito.grassoNegato)
            }
        }
    }

    /** Il permesso del grasso si è già chiesto in questa sessione. */
    private var grassoChiesto = false

    fun permessiSaluteMostrati() {
        _state.value = _state.value.copy(permessiSaluteRichiesti = false)
    }

    private suspend fun salvaPuntiSalute(punti: List<PuntoSalute>, grassoNegato: Boolean = false) {
        if (punti.isEmpty()) {
            _state.value = _state.value.copy(
                messaggio = "Nessuna pesata trovata in Health Connect (ultimi 90 giorni)."
            )
            return
        }
        // Health Connect non dovrebbe mai duplicare un istante, ma il web si
        // guarda comunque dai doppioni sullo stesso timestamp.
        val unici = punti.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        val obiettivo = _state.value.obiettivo
        val righe = unici.map { PesoRepository.rigaPuntoSalute(it, obiettivo) }

        // Le pesate manuali degli stessi giorni diventano ridondanti appena
        // arriva il dato vero dalla bilancia — si tolgono prima di scrivere.
        val giorniSincronizzati = unici.mapTo(mutableSetOf()) { it.giorno() }
        val manualiDaRimuovere = _state.value.pesate
            .filter { it.manuale && it.giorno in giorniSincronizzati }
            .map { it.timestamp }

        try {
            PesoRepository.sincronizzaPunti(righe, manualiDaRimuovere)
            // Lo stesso riepilogo del `showSyncResult()` del web — quante, da
            // quando a quando e l'ultima pesata: il solo conteggio non dice se
            // la finestra letta è quella che ci si aspetta, ed è proprio la
            // domanda che il 24 agosto 2026 non aveva risposta da nessuna parte.
            val primo = unici.first().giorno()
            val ultimo = unici.last()
            val peso = Math.floor(ultimo.pesoKg * 10.0) / 10.0
            _state.value = _state.value.copy(
                messaggio = "✅ ${unici.size} pesate da Salute · dal ${dataIt(primo)} al " +
                    "${dataIt(ultimo.giorno())} · ultima ${"%.1f".format(peso)} kg" +
                    // Lo stesso avviso del web: senza grasso la massa grassa non si conta.
                    if (grassoNegato) " · 🧈 manca il permesso «Grasso corporeo» in Connessione " +
                        "Salute: concedilo e risincronizza per la massa grassa"
                    else " · 🧈 grasso su ${unici.count { it.grasso != null }}"
            )
            carica()
            // Chi aveva concesso il solo peso prima della massa grassa il
            // permesso del grasso non l'ha mai visto: si chiede UNA volta per
            // sessione, come `permissionsRequested` del bridge — negato di
            // nuovo, il giro si ferma qui invece di ricominciare all'infinito.
            if (grassoNegato && !grassoChiesto) {
                grassoChiesto = true
                _state.value = _state.value.copy(permessiSaluteRichiesti = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sincronizzazione Salute non riuscita", e)
            _state.value = _state.value.copy(messaggio = "Non sincronizzato: ${e.messaggioBreve()}")
        }
    }

    // ── Premi dei traguardi intermedi ───────────────────────────────────
    //
    // ⚠️ Vivono su Supabase e non più nelle preferenze del telefono: sono le
    // stesse righe che scrive `weight-quest.html`, quindi un premio grattato
    // qui si ritrova sul PC. Le regole (quali soglie esistono, quanti punti
    // valgono) restano in [PesoRegole], una copia sola per app.

    /**
     * Premi e punti dell'obiettivo aperto. Il [context] serve solo alla
     * migrazione una-tantum di quel che era rimasto nelle preferenze.
     */
    fun caricaPremi(context: Context) {
        val id = _state.value.obiettivoId ?: return
        viewModelScope.launch {
            try {
                val premi = PesoPremi.premi(context, id)
                val punti = PesoPremi.puntiTotali(context, id)
                // Se nel frattempo si è cambiato obiettivo, questa risposta
                // risponde a una domanda che non è più quella aperta.
                if (_state.value.obiettivoId != id) return@launch
                _state.value = _state.value.copy(
                    premi = premi,
                    puntiTraguardi = punti,
                    premiVersione = _state.value.premiVersione + 1,
                )
            } catch (e: Exception) {
                Log.w(TAG, "premi non caricati", e)
            }
        }
    }

    /**
     * Il premio appena grattato. Scrittura ottimistica come le spunte di
     * Spuntiamola: la stellina mostra subito il cibo, e se il database rifiuta
     * lo si dice invece di lasciare a schermo un premio che non esiste.
     */
    fun grattaPremio(soglia: Int, premio: PremioCibo) {
        val id = _state.value.obiettivoId ?: return
        val prima = _state.value.premi
        _state.value = _state.value.copy(
            premi = prima + (soglia to PremioVinto(premio.id, LocalDate.now().toString()))
        )
        viewModelScope.launch {
            try {
                PesoPremi.salvaPremio(id, soglia, premio.id)
            } catch (e: Exception) {
                Log.w(TAG, "premio non salvato", e)
                _state.value = _state.value.copy(
                    premi = prima,
                    messaggio = "Premio non salvato: ${e.messaggioBreve("database non raggiungibile")}",
                )
            }
        }
    }

    /** «Mangiato !!!», e il suo contrario — reversibile di proposito. */
    fun segnaMangiato(soglia: Int, mangiato: Boolean) {
        val id = _state.value.obiettivoId ?: return
        val premio = _state.value.premi[soglia] ?: return
        val quando = if (mangiato) LocalDate.now().toString() else null
        _state.value = _state.value.copy(
            premi = _state.value.premi + (soglia to premio.copy(mangiatoIl = quando))
        )
        viewModelScope.launch {
            try {
                PesoPremi.segnaMangiato(id, soglia, mangiato)
            } catch (e: Exception) {
                Log.w(TAG, "«mangiato» non salvato", e)
                _state.value = _state.value.copy(
                    premi = _state.value.premi + (soglia to premio),
                    messaggio = "Non salvato: ${e.messaggioBreve("database non raggiungibile")}",
                )
            }
        }
    }

    /**
     * I punti totali dei traguardi. Si scrive con un ritardo perché il campo
     * chiama qui ad ogni cifra digitata, e una scrittura per tasto sarebbe un
     * colpo al database per niente.
     */
    fun salvaPuntiTraguardi(punti: Int) {
        val id = _state.value.obiettivoId ?: return
        _state.value = _state.value.copy(puntiTraguardi = punti)
        salvataggioPunti?.cancel()
        salvataggioPunti = viewModelScope.launch {
            delay(700)
            try {
                PesoPremi.salvaPuntiTotali(id, punti)
            } catch (e: Exception) {
                Log.w(TAG, "punti dei traguardi non salvati", e)
            }
        }
    }

    private var salvataggioPunti: Job? = null

    /** «2026-08-24» → «24/08», per il riepilogo della sincronizzazione. */
    private fun dataIt(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}" else iso
    }

    private companion object {
        const val TAG = "Peso"
    }
}
