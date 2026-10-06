package com.garsal.appsphere.piante

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.obiettivi.giornoLocale
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** Un gruppo di azioni, come la panoramica dei task. */
data class GruppoAzioni(val chiave: String, val titolo: String, val azioni: List<PvAzione>)

private val TITOLI_FISSI = listOf(
    "💧 Annaffiare", "🧪 Concimare", "🪴 Rinvasare", "✂️ Potare", "🐛 Controllare parassiti",
    "💦 Nebulizzare le foglie", "🧽 Pulire le foglie", "🔄 Girare il vaso",
)

data class PianteState(
    val piante: List<PvPianta> = emptyList(),
    val voci: List<PvVoce> = emptyList(),
    val foto: List<PvFoto> = emptyList(),
    val azioni: List<PvAzione> = emptyList(),
    val storia: List<PvStoria> = emptyList(),
    val desideri: List<PvDesiderio> = emptyList(),
    val risposte: List<PvRisposta> = emptyList(),
    val regole: List<PvRegola> = emptyList(),
    val preset: List<PvPreset> = emptyList(),
    val gruppi: List<PvGruppo> = emptyList(),
    val legami: List<PvLegame> = emptyList(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
    /** Un salvataggio in corso: i form restano aperti e il pulsante spento. */
    val occupato: Boolean = false,
    /** Un form che si è chiuso bene: la schermata lo richiude. */
    val salvato: Int = 0,
    val iaInCorso: Boolean = false,
    val erroreIA: String? = null,
    /** Le IA con la chiave nei Secrets; null finché pv-ai non ha risposto. */
    val fornitori: List<Pair<String, String>>? = null,
    /** L'IA scelta nella tendina; null = la prima disponibile. */
    val fornitore: String? = null,
) {
    val fornitoriOfferti: List<Pair<String, String>>
        get() = fornitori?.takeIf { it.isNotEmpty() } ?: FORNITORI_TUTTI
    val fornitoreScelto: String
        get() = fornitore?.takeIf { f -> fornitoriOfferti.any { it.first == f } } ?: fornitoriOfferti.first().first

    fun pianta(id: String?) = piante.firstOrNull { it.id == id }
    fun gruppo(id: String?) = gruppi.firstOrNull { it.id == id }

    // ── 👥 gruppi: gemelli di gruppiDi / pianteDi / azioniDellaPianta del web ──
    fun gruppiDi(piantaId: String): List<PvGruppo> {
        val ids = legami.filter { it.piantaId == piantaId }.map { it.gruppoId }.toSet()
        return gruppi.filter { it.id in ids }
    }
    fun pianteDi(gruppoId: String): List<PvPianta> {
        val ids = legami.filter { it.gruppoId == gruppoId }.map { it.piantaId }.toSet()
        return piante.filter { it.id in ids }
    }

    /**
     * Le azioni che valgono per una pianta: le sue più quelle dei suoi gruppi.
     * ⚠️ Un'azione di gruppo è UNA riga: compare in ogni pianta del gruppo ma
     * si chiude una volta sola, per tutte.
     */
    fun azioniDellaPianta(piantaId: String): List<PvAzione> {
        val gids = gruppiDi(piantaId).map { it.id }.toSet()
        return azioni.filter { it.piantaId == piantaId || (it.gruppoId != null && it.gruppoId in gids) }
    }

    /** Le ultime volte di una pianta: le righe di gruppo hanno plant_id NULL e si ritrovano da action_id. */
    fun storiaDellaPianta(piantaId: String): List<PvStoria> {
        val diGruppo = azioniDellaPianta(piantaId).filter { it.gruppoId != null }.map { it.id }.toSet()
        return storia.filter { (it.piantaId == piantaId || it.azioneId in diGruppo) && it.azione != "terminated" }.take(15)
    }

    /**
     * Le voci proposte per «Cosa fare»: le fisse più i titoli delle azioni già
     * create, senza doppioni (maiuscole e spazi non contano). Gemella di
     * `titoliProposti()` in `piante.html`.
     */
    val titoliProposti: List<String>
        get() = (TITOLI_FISSI + azioni.map { it.titolo.trim() })
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase().replace(Regex("\\s+"), " ") }

    /**
     * La pianta e le sue discendenti a ogni livello: nella tendina «Discende da»
     * non si offrono, o una pianta potrebbe finire nonna di sé stessa.
     * Gemella di `discendentiDi()` in `piante.html`.
     */
    fun discendenti(id: String): Set<String> {
        val fuori = mutableSetOf(id)
        var cresce = true
        while (cresce) {
            cresce = false
            for (x in piante) if (x.madreId != null && x.madreId in fuori && fuori.add(x.id)) cresce = true
        }
        return fuori
    }
    val puntiTotali: Int get() = storia.sumOf { it.punti }

    /** Le foto della scheda (non del diario), la prima è la copertina. */
    fun fotoScheda(piantaId: String) = foto.filter { it.piantaId == piantaId && it.voceId == null }.sortedBy { it.posizione }
    fun fotoVoce(voceId: String) = foto.filter { it.voceId == voceId }.sortedBy { it.posizione }

    /**
     * La copertina: la prima foto della scheda, o la più recente del diario —
     * una pianta con un diario pieno di foto e un riquadro vuoto sembrerebbe
     * una pianta senza foto. Stessa regola di `copertinaDi()`.
     */
    fun copertina(piantaId: String): String? =
        fotoScheda(piantaId).firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: foto.filter { it.piantaId == piantaId && it.voceId != null }
                .maxByOrNull { it.creata }?.url?.takeIf { it.isNotBlank() }

    fun prossimaDi(piantaId: String): PvAzione? =
        azioniDellaPianta(piantaId).filter { it.viva && it.giorno != null }.minByOrNull { it.giorno!! }

    fun canali(azioneId: String): String {
        val c = regole.filter { it.azioneId == azioneId }.map { it.canale }
        return listOfNotNull(
            "📱".takeIf { "telegram" in c }, "📲".takeIf { "android" in c }, "🔐".takeIf { "smart_block" in c },
        ).joinToString("")
    }

    /** Scadute, oggi, prossime, quando capita — e le concluse se richieste. */
    fun gruppi(elenco: List<PvAzione>, oggi: LocalDate, conConcluse: Boolean): List<GruppoAzioni> {
        val vive = elenco.filter { it.viva }
        val ordine = compareBy<PvAzione>({ it.giorno }, { it.ora })
        return listOfNotNull(
            GruppoAzioni("late", "⚠️ In ritardo", vive.filter { it.giorno?.isBefore(oggi) == true }.sortedWith(ordine)),
            GruppoAzioni("today", "🎯 Oggi", vive.filter { it.giorno == oggi }.sortedWith(ordine)),
            GruppoAzioni("next", "📅 Prossime", vive.filter { it.giorno?.isAfter(oggi) == true }.sortedWith(ordine)),
            GruppoAzioni("free", "🔄 Quando capita", vive.filter { it.giorno == null }),
            if (conConcluse) GruppoAzioni("done", "🏁 Concluse", elenco.filter { !it.viva }) else null,
        ).filter { it.azioni.isNotEmpty() }
    }
}

class PianteViewModel : ViewModel() {

    private val _state = MutableStateFlow(PianteState())
    val state: StateFlow<PianteState> = _state.asStateFlow()

    init {
        carica()
        viewModelScope.launch {
            val f = PianteRepository.fornitori()
            _state.value = _state.value.copy(fornitori = f)
        }
    }

    fun scegliFornitore(id: String) {
        _state.value = _state.value.copy(fornitore = id)
    }

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val piante = async { PianteRepository.piante() }
                val voci = async { PianteRepository.voci() }
                val foto = async { PianteRepository.foto() }
                val azioni = async { PianteRepository.azioni() }
                val storia = async { PianteRepository.storia() }
                val desideri = async { PianteRepository.desideri() }
                val risposte = async { runCatching { PianteRepository.risposte() }.getOrDefault(emptyList()) }
                val regole = async { PianteRepository.regole() }
                val preset = async { PianteRepository.preset() }
                val gruppi = async { PianteRepository.gruppi() }
                val legami = async { PianteRepository.legami() }
                _state.value = _state.value.copy(
                    piante = piante.await(), voci = voci.await(), foto = foto.await(),
                    azioni = azioni.await(), storia = storia.await(), desideri = desideri.await(),
                    risposte = risposte.await(), regole = regole.await(), preset = preset.await(),
                    gruppi = gruppi.await(), legami = legami.await(),
                    caricamento = false,
                )
            } catch (e: Exception) {
                Log.w(TAG, "caricamento fallito", e)
                _state.value = _state.value.copy(caricamento = false, errore = e.message ?: "Caricamento non riuscito")
            }
        }
    }

    /** Un salvataggio: occupato mentre gira, messaggio alla fine, e si rilegge tutto. */
    private fun esegui(fatto: String, chiudeForm: Boolean = true, blocco: suspend () -> Unit) {
        if (_state.value.occupato) return
        viewModelScope.launch {
            _state.value = _state.value.copy(occupato = true)
            try {
                blocco()
                _state.value = _state.value.copy(
                    occupato = false, messaggio = fatto,
                    salvato = if (chiudeForm) _state.value.salvato + 1 else _state.value.salvato,
                )
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "operazione non riuscita", e)
                _state.value = _state.value.copy(occupato = false, messaggio = "❌ " + (e.message ?: "errore"))
            }
        }
    }

    // ── piante ──────────────────────────────────────────────────────────────

    fun salvaPianta(
        context: Context,
        id: String?,
        campi: JsonObject,
        nuove: List<Uri>,
        tolte: List<PvFoto>,
        daDesiderio: String?,
        gruppi: Set<String>,
        dopo: (String) -> Unit,
    ) = esegui("✅ Pianta salvata") {
        val piantaId = PianteRepository.salvaPianta(id, campi, daDesiderio)
        val prima = _state.value.legami.filter { it.piantaId == piantaId }.map { it.gruppoId }.toSet()
        PianteRepository.sincronizzaGruppiPianta(piantaId, prima, gruppi)
        tolte.forEach { PianteRepository.eliminaFoto(it) }
        val gia = _state.value.fotoScheda(piantaId).count { f -> tolte.none { it.id == f.id } }
        nuove.forEachIndexed { i, uri -> PianteRepository.caricaFoto(context, uri, piantaId, null, gia + i) }
        dopo(piantaId)
    }

    fun eliminaPianta(id: String) = esegui("🗑 Pianta eliminata") {
        PianteRepository.eliminaPianta(id, _state.value.foto)
    }

    // ── diario ──────────────────────────────────────────────────────────────

    fun salvaVoce(
        context: Context,
        id: String?,
        piantaId: String,
        giorno: LocalDate,
        commento: String?,
        nuove: List<Uri>,
        tolte: List<PvFoto>,
    ) = esegui("✅ Voce salvata") {
        val voceId = PianteRepository.salvaVoce(id, piantaId, giorno, commento)
        tolte.forEach { PianteRepository.eliminaFoto(it) }
        val gia = _state.value.fotoVoce(voceId).count { f -> tolte.none { it.id == f.id } }
        nuove.forEachIndexed { i, uri -> PianteRepository.caricaFoto(context, uri, piantaId, voceId, gia + i) }
    }

    fun eliminaVoce(id: String) = esegui("🗑 Voce eliminata", chiudeForm = false) {
        PianteRepository.eliminaVoce(id, _state.value.foto)
    }

    // ── azioni ──────────────────────────────────────────────────────────────

    fun salvaAzione(b: BozzaAzione) {
        val pianta = b.gruppoId?.let { _state.value.gruppo(it)?.nome } ?: _state.value.pianta(b.piantaId)?.nome ?: ""
        val finita = _state.value.azioni.firstOrNull { it.id == b.id }?.viva == false
        esegui("✅ Azione salvata") { PianteRepository.salvaAzione(b, pianta, finita) }
    }

    fun eliminaAzione(id: String) = esegui("🗑 Azione eliminata", chiudeForm = false) {
        PianteRepository.eliminaAzione(id)
    }

    /** ⚠️ Solo RPC: la prossima occorrenza e i promemoria li sposta il database. */
    fun completa(azione: PvAzione) = rpc("✅ Fatto") { PianteRepository.completa(azione.id, LocalDate.now()) }

    fun salta(azione: PvAzione, giorni: Int) = rpc("⏭ Saltata") { PianteRepository.salta(azione.id, giorni) }

    private fun rpc(verbo: String, chiamata: suspend () -> PianteRepository.Esito) {
        viewModelScope.launch {
            try {
                val e = chiamata()
                if (!e.ok) {
                    _state.value = _state.value.copy(messaggio = "❌ " + (e.errore ?: "errore"))
                    return@launch
                }
                val punti = e.punti?.let { " · ${if (it > 0) "+" else ""}$it pt" }.orEmpty()
                val prossima = giornoLocale(e.prossima)?.let { " · prossima ${quando(it, LocalDate.now())}" }.orEmpty()
                _state.value = _state.value.copy(messaggio = verbo + punti + prossima)
                carica()
            } catch (ex: Exception) {
                _state.value = _state.value.copy(messaggio = "❌ " + (ex.message ?: "errore"))
            }
        }
    }

    // ── gruppi ──────────────────────────────────────────────────────────────

    fun salvaGruppo(id: String?, nome: String, emoji: String?, piante: Set<String>, dopo: (String) -> Unit) =
        esegui("✅ Gruppo salvato") {
            val prima = id?.let { g -> _state.value.legami.filter { it.gruppoId == g }.map { it.piantaId }.toSet() }.orEmpty()
            dopo(PianteRepository.salvaGruppo(id, nome, emoji, prima, piante))
        }

    fun eliminaGruppo(id: String) = esegui("🗑 Gruppo eliminato") { PianteRepository.eliminaGruppo(id) }

    /**
     * Dal form di una pianta: il gruppo nasce subito (serve il suo id) e il
     * collegamento si scrive col Salva della pianta, come nel web. Un nome che
     * c'è già (maiuscole e spazi non contano) riusa quel gruppo.
     */
    fun creaGruppo(nome: String, dopo: (String) -> Unit) {
        val n = nome.trim()
        if (n.isEmpty()) return
        _state.value.gruppi.firstOrNull { it.nome.trim().equals(n, ignoreCase = true) }?.let { dopo(it.id); return }
        viewModelScope.launch {
            try {
                val g = PianteRepository.creaGruppo(n)
                _state.value = _state.value.copy(gruppi = (_state.value.gruppi + g).sortedBy { it.nome.lowercase() })
                dopo(g.id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(messaggio = "❌ " + (e.message ?: "errore"))
            }
        }
    }

    // ── desideri ────────────────────────────────────────────────────────────

    fun salvaDesiderio(id: String?, nome: String, specie: String?, note: String?, priorita: Int) =
        esegui("💚 Salvata") { PianteRepository.salvaDesiderio(id, nome, specie, note, priorita) }

    fun statoDesiderio(id: String, stato: String) =
        esegui("✅ Fatto", chiudeForm = false) { PianteRepository.statoDesiderio(id, stato) }

    fun eliminaDesiderio(id: String) = esegui("🗑 Tolta") { PianteRepository.eliminaDesiderio(id) }

    // ── IA ──────────────────────────────────────────────────────────────────

    fun chiedi(piantaId: String?, domanda: String, dopo: () -> Unit) {
        if (_state.value.iaInCorso || domanda.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(iaInCorso = true, erroreIA = null)
            try {
                PianteRepository.chiediIA(piantaId, domanda.trim(), _state.value.fornitoreScelto)
                val risposte = runCatching { PianteRepository.risposte() }.getOrDefault(_state.value.risposte)
                _state.value = _state.value.copy(iaInCorso = false, risposte = risposte)
                dopo()
            } catch (e: Exception) {
                _state.value = _state.value.copy(iaInCorso = false, erroreIA = e.message ?: "errore")
            }
        }
    }

    fun eliminaRisposta(id: String) = esegui("🗑 Risposta eliminata", chiudeForm = false) {
        PianteRepository.eliminaRisposta(id)
    }

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    private companion object {
        const val TAG = "Piante"
    }
}

val FORNITORI_TUTTI = listOf("gemini" to "Gemini", "qwen" to "Qwen", "groq" to "Groq", "claude" to "Claude")
/** Le IA che si pagano: si chiede conferma a ogni domanda. Gemello di AVVISO_COSTO in piante.html. */
val AVVISO_COSTO = mapOf(
    "claude" to "Claude si paga a consumo: questa domanda viene addebitata sulla chiave Anthropic.",
    "qwen" to "Qwen è gratuito solo finché dura il credito iniziale di Alibaba: dopo, questa domanda si paga.",
)
fun nomeIA(id: String?): String = FORNITORI_TUTTI.firstOrNull { it.first == (id ?: "claude") }?.second ?: (id ?: "Claude")

/** «oggi», «domani», «ieri», «3 giorni fa» o la data: come `quando()` nel web. */
fun quando(giorno: LocalDate, oggi: LocalDate): String {
    val n = java.time.temporal.ChronoUnit.DAYS.between(oggi, giorno)
    return when {
        n == 0L -> "oggi"
        n == 1L -> "domani"
        n == -1L -> "ieri"
        n < 0 -> "${-n} giorni fa"
        else -> "%02d/%02d/%d".format(giorno.dayOfMonth, giorno.monthValue, giorno.year)
    }
}
