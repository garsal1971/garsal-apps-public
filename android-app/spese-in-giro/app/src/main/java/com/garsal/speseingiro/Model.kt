package com.garsal.speseingiro

import org.json.JSONArray
import org.json.JSONObject

/**
 * Una categoria di spesa. **Del viaggio e non dell'app**: sta in
 * `vg_categorie`, si gestisce da ⚙️ Impostazioni, e i due telefoni ne vedono
 * lo stesso elenco. Tenuta nelle preferenze di ciascuno, una categoria
 * inventata qui sull'altro telefono comparirebbe come chiave grezza — e le
 * voci sono condivise.
 *
 * ⚠️ **`chiave` è quello che sta scritto nella voce, `nome` è quel che si
 * legge**, e sono due cose diverse apposta: rinominare una categoria tocca una
 * riga sola e tutte le voci si rileggono col nome nuovo. Seguendo il nome, la
 * chiave lascerebbe ogni voce già segnata agganciata a qualcosa che non esiste
 * più. È la stessa scelta delle opzioni di una combo in Memo, che archiviano
 * l'id e mai l'etichetta.
 *
 * `id` è la riga: senza (categoria non più in elenco, o server che non le manda
 * ancora) si mostra e basta, non si modifica e non si toglie.
 */
data class Categoria(
    val id: String,
    val chiave: String,
    val emoji: String,
    val nome: String,
    /** Quante voci del viaggio la citano. Lo conta il server: i due telefoni
     *  vedono le stesse voci, ma non nello stesso momento. */
    val usi: Int = 0,
) {
    val gestibile get() = id.isNotBlank()
    val etichetta get() = "$emoji  $nome"
}

/**
 * Le sette di partenza, che il database semina in ogni viaggio.
 *
 * ⚠️ Qui restano come **ripiego**, per un `vg_stato` che non mandi ancora
 * `categorie`: un elenco vuoto lascerebbe il form di una spesa senza niente da
 * scegliere. Chiave per chiave sono le stesse di `vg_categorie_semina`, e
 * cambiandone una va cambiata anche là. Non hanno `id`, quindi da qui non si
 * gestiscono: è quello che sono, un ripiego.
 */
val CATEGORIE_DI_PARTENZA = listOf(
    Categoria("", "cibo", "🍝", "Mangiare"),
    Categoria("", "bar", "☕", "Bar e caffè"),
    Categoria("", "alloggio", "🛏️", "Dormire"),
    Categoria("", "trasporto", "🚆", "Treni e trasporti"),
    Categoria("", "bici", "🔧", "Bici e officina"),
    Categoria("", "visite", "🎟️", "Visite e ingressi"),
    Categoria("", "varie", "🛒", "Varie"),
)

/** Una categoria che non è più in elenco non fa sparire la voce che la cita:
 *  si mostra com'è scritta, come la *misura tolta* dei diari di Memo. */
fun categoriaDi(chiave: String, elenco: List<Categoria>): Categoria =
    elenco.firstOrNull { it.chiave == chiave } ?: Categoria("", chiave, "🏷️", chiave)

object Stati {
    const val IN_ATTESA = "in_attesa"
    const val CONFERMATA = "confermata"
    const val CANC_RICHIESTA = "cancellazione_richiesta"
    const val CANCELLATA = "cancellata"
}

data class Persona(val id: String, val nome: String)

data class ViaggioInfo(
    val id: String,
    val nome: String,
    val codice: String,
    val dataInizio: String?,
    val dataFine: String?,
    val valuta: String,
)

data class Voce(
    val id: String,
    val tipo: String,               // 'spesa' | 'restituzione'
    val importo: Double,
    val data: String,               // yyyy-MM-dd
    val descrizione: String,
    val categoria: String,
    val daId: String,
    val daNome: String,
    val perChi: String?,            // 'entrambi' | 'uno' | null (restituzione)
    val beneficiarioId: String?,
    val beneficiarioNome: String?,
    val haScontrino: Boolean,
    val scontrinoLetto: Double?,
    val stato: String,
    val creataDa: String,
    val creataDaNome: String,
    val cancChiestaDa: String?,
    val cancMotivo: String?,
) {
    val spesa get() = tipo == "spesa"
    val viva get() = stato != Stati.CANCELLATA
}

data class RigaLog(
    val id: Long,
    val azione: String,
    val chi: String,
    val voceTesto: String,
    val voceImporto: Double?,
    val dettaglio: String,
    val quando: String,
)

data class StatoViaggio(
    val viaggio: ViaggioInfo,
    val io: Persona,
    val altro: Persona?,
    val voci: List<Voce>,
    val categorie: List<Categoria>,
    val log: List<RigaLog>,
    val saldo: Double,
    val saldoAtteso: Double,
    val totaleViaggio: Double,
    /** Lo stesso totale comprese le voci ancora in attesa — il gemello di
     *  `saldoAtteso`, e per la stessa ragione: le due misure stanno accanto e
     *  si dichiarano. */
    val totaleAtteso: Double,
    val daConfermare: Int,
)

/* ───────────────────────────────────────────────────────────────────────────
   La lettura del JSON.

   ⚠️ Si legge campo per campo con `opt*` e non con una data class serializzata:
   una colonna di forma inattesa non deve dare una voce storta ma **la schermata
   vuota**. È la stessa scelta di `ts_tasks` e `ps_weight_tracking` nel nativo.
   ─────────────────────────────────────────────────────────────────────────── */

private fun JSONObject.stringaONull(k: String): String? =
    if (isNull(k)) null else optString(k).ifBlank { null }

private fun JSONObject.numeroONull(k: String): Double? =
    if (isNull(k)) null else optString(k).toDoubleOrNull() ?: optDouble(k).takeIf { !it.isNaN() }

fun personaDa(o: JSONObject?): Persona? =
    if (o == null) null else Persona(o.optString("id"), o.optString("nome"))

fun voceDa(o: JSONObject) = Voce(
    id = o.optString("id"),
    tipo = o.optString("tipo", "spesa"),
    importo = o.numeroONull("importo") ?: 0.0,
    data = o.optString("data"),
    descrizione = o.optString("descrizione", ""),
    categoria = o.optString("categoria", "varie"),
    daId = o.optString("da_id"),
    daNome = o.optString("da_nome", ""),
    perChi = o.stringaONull("per_chi"),
    beneficiarioId = o.stringaONull("beneficiario_id"),
    beneficiarioNome = o.stringaONull("beneficiario_nome"),
    haScontrino = o.stringaONull("scontrino_path") != null,
    scontrinoLetto = o.numeroONull("scontrino_letto"),
    stato = o.optString("stato", Stati.IN_ATTESA),
    creataDa = o.optString("creata_da"),
    creataDaNome = o.optString("creata_da_nome", ""),
    cancChiestaDa = o.stringaONull("canc_chiesta_da"),
    cancMotivo = o.stringaONull("canc_motivo"),
)

fun categoriaDa(o: JSONObject) = Categoria(
    id = o.optString("id"),
    chiave = o.optString("chiave"),
    emoji = o.optString("emoji", "🏷️").ifBlank { "🏷️" },
    nome = o.optString("nome").ifBlank { o.optString("chiave") },
    usi = o.optInt("usi", 0),
)

fun logDa(o: JSONObject) = RigaLog(
    id = o.optLong("id"),
    azione = o.optString("azione"),
    chi = o.optString("chi_nome", ""),
    voceTesto = o.optString("voce_testo", ""),
    voceImporto = o.numeroONull("voce_importo"),
    dettaglio = o.optString("dettaglio", ""),
    quando = o.optString("at"),
)

fun statoDa(o: JSONObject): StatoViaggio? {
    val v = o.optJSONObject("viaggio") ?: return null
    val io = personaDa(o.optJSONObject("io")) ?: return null
    fun <T> lista(nome: String, f: (JSONObject) -> T): List<T> {
        val arr: JSONArray = o.optJSONArray(nome) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(f) }
    }
    return StatoViaggio(
        viaggio = ViaggioInfo(
            id = v.optString("id"), nome = v.optString("nome"), codice = v.optString("codice"),
            dataInizio = v.stringaONull("data_inizio"), dataFine = v.stringaONull("data_fine"),
            valuta = v.optString("valuta", "EUR"),
        ),
        io = io,
        altro = personaDa(o.optJSONObject("altro")),
        voci = lista("voci", ::voceDa),
        // ⚠️ Chiave assente ≠ elenco vuoto: contro un server che non le manda
        // ancora valgono le sette di partenza, che sono quelle che quel server
        // ha comunque nelle voci. Un elenco vuoto sarebbe un form di spesa
        // senza niente da scegliere, cioè rotto.
        categorie = lista("categorie", ::categoriaDa)
            .ifEmpty { CATEGORIE_DI_PARTENZA },
        log = lista("log", ::logDa),
        saldo = o.numeroONull("saldo") ?: 0.0,
        saldoAtteso = o.numeroONull("saldo_atteso") ?: 0.0,
        totaleViaggio = o.numeroONull("totale_viaggio") ?: 0.0,
        // ⚠️ Chiave assente ≠ zero: contro un server che non la manda ancora
        // (`vg_stato` prima della migration del totale atteso) vale il totale
        // confermato, così le due misure coincidono e la seconda non compare.
        // Uno zero direbbe «col non confermato 0,00 €», che è il falso.
        totaleAtteso = o.numeroONull("totale_atteso")
            ?: (o.numeroONull("totale_viaggio") ?: 0.0),
        daConfermare = o.optInt("da_confermare", 0),
    )
}
