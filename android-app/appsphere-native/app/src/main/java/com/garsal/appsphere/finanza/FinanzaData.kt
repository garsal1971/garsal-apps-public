package com.garsal.appsphere.finanza

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import com.garsal.appsphere.BuildConfig
import com.garsal.appsphere.core.Jwt
import com.garsal.appsphere.core.messaggioBreve
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.net.URL
import java.time.LocalDate

// ═══════════════════════════════════════════════════════════════════════════
// 💰 Finanza nativa — SOLO VISUALIZZAZIONE (APK 1.0.121)
//
// ⚠️ **Qui non si calcola niente: si leggono gli snapshot.** Il patrimonio dal vivo
// in `finanza.html` passa da `portfolioStats` / `computeLoanValue` /
// `computePricesFromHistory`, che esistono già in due copie (la pagina e la Edge
// Function `save-snapshot`). Una terza in Kotlin sarebbe un terzo patrimonio il giorno
// che una delle tre cambia. Il nativo quindi:
//
//  1. all'apertura chiama `save-snapshot` col JWT dell'utente — la stessa chiamata di
//     `autoSaveSnapshot()` / 💾 nel web — che scrive lo snapshot di OGGI coi prezzi in
//     cache (aggiornati ogni ora dal job di `get-prices`);
//  2. legge `fnz_dashboard_snapshots` e disegna quello.
//
// Ne discende che i numeri sono quelli dello snapshot: **lordi** (niente valore netto
// delle tasse, che lo snapshot non conosce — ed è stato chiesto così) e ai prezzi
// dell'ultimo aggiornamento orario. Se `save-snapshot` non risponde si mostra l'ultimo
// snapshot in archivio, e la pagina dice di che giorno è.
//
// ✏️ L'UNICA SCRITTURA (APK 1.0.128): le quantità dei portafogli a saldi
// (`fnz_portfolios.gestione = 'saldi'`, il CRYPTO). Si aggiunge una riga a
// `fnz_saldi_crypto` — mai riscritta — e si rifà lo snapshot, così i numeri a schermo
// restano quelli dello snapshot. Gemella di `openSaldoModal` / `registroSaldiHtml` in
// finanza.html: cambiarle insieme.
// ═══════════════════════════════════════════════════════════════════════════

// ── Lettura permissiva del JSON ─────────────────────────────────────────────
// `details` è jsonb scritto dalla Edge Function, e i numeri delle colonne `numeric`
// arrivano da PostgREST anche come stringhe: si accetta l'uno e l'altro.

private fun JsonElement?.numero(): Double? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content.toDoubleOrNull()
    else -> null
}

private fun JsonElement?.testo(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

private fun JsonElement?.oggetti(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

// ── Errori di rete ──────────────────────────────────────────────────────────
// ⚠️ Un telefono che passa dal Wi-Fi al 4G durante un'attesa lunga perde il nome del
// server per qualche secondo («Unable to resolve host»): è la rete, non un difetto, e
// mostrarlo con l'indirizzo intero della chiamata riempiva lo schermo senza dire niente.

/** È un errore di rete che vale la pena riprovare? Solo quelli che falliscono SUBITO. */
private fun Throwable.diRete(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is UnknownHostException || t is ConnectException || t is NoRouteToHostException) return true
        val m = t.message.orEmpty()
        if ("Unable to resolve host" in m || "Failed to connect" in m) return true
        t = t.cause
    }
    return false
}

/** Il testo breve di un errore: la rete si dice a parole, il resto senza URL né header. */
fun Throwable.testoFinanza(): String =
    if (diRete()) "rete non raggiungibile, riprova col ⟳"
    else messaggioBreve().replace(Regex("^HTTP request to \\S+ \\(\\w+\\) failed with message:\\s*"), "")

/** Prova; su un errore di rete aspetta 3 secondi e riprova UNA volta. */
private suspend fun <T> conRiprova(blocco: suspend () -> T): T =
    try { blocco() } catch (e: Exception) {
        if (!e.diRete()) throw e
        delay(3_000)
        blocco()
    }

// ── Modello ─────────────────────────────────────────────────────────────────

data class Posizione(
    val simbolo: String,
    val nome: String,
    val tipo: String?,
    val quantita: Double?,
    val costoMedio: Double?,
    val prezzo: Double?,
    /** Valore in quota (× possesso), come il KPI del portafoglio. */
    val valoreQuota: Double?,
    val pnl: Double?,
    val pnlPct: Double?,
    /** Valore per intero (`current_value`), per il Σ cumulativo. */
    val valorePieno: Double? = null,
    /** Costo per intero (`cost_basis`): 0 su una moneta a saldi, che un costo non ce l'ha. */
    val costoBase: Double = 0.0,
)

data class PortafoglioSnap(
    val id: String,
    val nome: String,
    val colore: String?,
    val possessoPct: Double,
    /** Valore in quota, liquidità compresa: è `total_value` dello snapshot. */
    val valore: Double,
    val liquidita: Double?,
    val costo: Double?,
    val pnl: Double?,
    val pnlPct: Double?,
    val posizioni: List<Posizione>,
) {
    companion object {
        fun da(o: JsonObject) = PortafoglioSnap(
            id = o["id"].testo().orEmpty(),
            nome = o["name"].testo() ?: "Portafoglio",
            colore = o["color"].testo(),
            possessoPct = o["ownership_pct"].numero() ?: 100.0,
            valore = o["total_value"].numero() ?: 0.0,
            liquidita = o["cash"].numero(),
            costo = o["total_cost"].numero(),
            pnl = o["pnl"].numero(),
            pnlPct = o["pnl_pct"].numero(),
            posizioni = o["holdings"].oggetti().map { h ->
                Posizione(
                    simbolo = h["symbol"].testo().orEmpty(),
                    nome = h["name"].testo() ?: h["symbol"].testo().orEmpty(),
                    tipo = h["asset_type"].testo(),
                    quantita = h["qty"].numero(),
                    costoMedio = h["avg_cost"].numero(),
                    prezzo = h["current_price"].numero(),
                    valoreQuota = h["current_value_quota"].numero(),
                    pnl = h["pnl"].numero(),
                    pnlPct = h["pnl_pct"].numero(),
                    valorePieno = h["current_value"].numero(),
                    costoBase = h["cost_basis"].numero() ?: 0.0,
                )
            },
        )
    }
}

/** Un prodotto di `fnz_products`, per scegliere la moneta da aggiornare. */
data class Moneta(val id: String, val simbolo: String, val nome: String, val tipo: String?)

/** Una riga del registro `fnz_saldi_crypto`: la quantità scritta a mano quel momento. */
data class SaldoRiga(
    val id: String, val portafoglio: String, val prodotto: String,
    val quantita: Double, val quando: String, val nota: String?,
)

/**
 * Il saldo del Conto Spese Personali: `cm_settings.sal_saldo`, scritto da
 * spese-personali.html. Gemello di `leggiSaldoSal()` in finanza.html.
 */
data class SalSaldo(val importo: Double, val data: String?)

/** Un titolo del Σ cumulativo: lo stesso prodotto sommato fra tutti i portafogli, per intero. */
data class TitoloCumul(
    val simbolo: String,
    val nome: String,
    val tipo: String?,
    val quantita: Double,
    val prezzo: Double?,
    val valore: Double,
    /** Costo per intero; senza senso se `senzaCosto`. */
    val costo: Double,
    /** Almeno una parte viene da un portafoglio a saldi: niente P&L per titolo. */
    val senzaCosto: Boolean,
    val dettaglio: List<Triple<String, Double, Double>>, // (portafoglio, quantità, valore)
) {
    val pnl: Double? get() = if (senzaCosto) null else valore - costo
    val pnlPct: Double? get() = if (senzaCosto || costo <= 0) null else (valore - costo) / costo * 100
    val costoMedio: Double? get() = if (senzaCosto || quantita <= 0) null else costo / quantita
}

/**
 * Σ CUMULATIVO, gemello di `cumulativoStats()` in finanza.html: tutti i portafogli per
 * intero (il possesso % non si applica), dai numeri dell'ultimo snapshot. ⚠️ Non si rifà
 * nessun prezzo: si riaggregano le posizioni che lo snapshot ha già valorizzato.
 */
data class Cumulativo(
    val titoli: List<TitoloCumul>,
    val liquiditaFondi: Double,
    val contoSal: Double,
    val investito: Double,
    val pnl: Double,
) {
    val totTitoli: Double get() = titoli.sumOf { it.valore }
    val liquidita: Double get() = liquiditaFondi + contoSal
    val totale: Double get() = totTitoli + liquidita
    val pnlPct: Double? get() = if (investito > 0) pnl / investito * 100 else null

    companion object {
        fun da(portafogli: List<PortafoglioSnap>, salSaldo: SalSaldo?): Cumulativo {
            data class Acc(val h: Posizione, var qta: Double = 0.0, var valore: Double = 0.0, var costo: Double = 0.0,
                           var senzaCosto: Boolean = false, val det: MutableList<Triple<String, Double, Double>> = mutableListOf())
            val righe = LinkedHashMap<String, Acc>()
            var cash = 0.0; var versato = 0.0; var escluso = 0.0
            for (pf in portafogli) {
                val own = pf.possessoPct / 100.0
                if (own <= 0) continue
                cash += (pf.liquidita ?: 0.0) / own
                // Un portafoglio a saldi ha l'investito = versato nel fondo: lo snapshot lo
                // mette in total_cost (in quota) insieme al costo dei titoli, qui zero.
                val costoTitoli = pf.posizioni.sumOf { it.costoBase }
                val haVersato = pf.posizioni.any { it.costoMedio == null } &&
                    (pf.costo ?: 0.0) / own - costoTitoli > 0.5
                if (haVersato) versato += (pf.costo ?: 0.0) / own - costoTitoli
                for (h in pf.posizioni) {
                    val a = righe.getOrPut(h.simbolo) { Acc(h) }
                    val senza = h.costoMedio == null
                    val v = h.valorePieno ?: h.costoBase
                    a.qta += h.quantita ?: 0.0
                    a.valore += v
                    if (senza) { a.senzaCosto = true; if (!haVersato) escluso += v } else a.costo += h.costoBase
                    a.det += Triple(pf.nome, h.quantita ?: 0.0, v)
                }
            }
            val titoli = righe.values.map {
                TitoloCumul(it.h.simbolo, it.h.nome, it.h.tipo, it.qta, it.h.prezzo, it.valore, it.costo, it.senzaCosto, it.det)
            }.sortedByDescending { it.valore }
            val totCosto = titoli.sumOf { it.costo }
            val investito = totCosto + versato
            val pnl = titoli.sumOf { it.valore } - escluso - investito
            return Cumulativo(titoli, cash, salSaldo?.importo ?: 0.0, investito, pnl)
        }
    }
}

data class VoceSemplice(val id: String, val nome: String, val valore: Double?)

/** Uno snapshot: le colonne di testa, più il dettaglio se è stato letto. */
data class Snapshot(
    val data: String,
    val patrimonioNetto: Double,
    val portafogli: Double,
    val asset: Double,
    val debiti: Double,
    val aggiornato: String?,
    val dettaglioPortafogli: List<PortafoglioSnap> = emptyList(),
    val prestiti: List<VoceSemplice> = emptyList(),
    val altriAsset: List<VoceSemplice> = emptyList(),
) {
    /** Asset senza portafogli: è `totalOther` della dashboard web. */
    val altriAssetTotale: Double get() = asset - portafogli

    companion object {
        fun da(o: JsonObject): Snapshot {
            val det = o["details"] as? JsonObject
            return Snapshot(
                data = o["snapshot_date"].testo().orEmpty(),
                patrimonioNetto = o["patrimonio_netto"].numero() ?: 0.0,
                portafogli = o["portafogli_totali"].numero() ?: 0.0,
                asset = o["asset_totali"].numero() ?: 0.0,
                debiti = o["debiti_totali"].numero() ?: 0.0,
                aggiornato = o["updated_at"].testo(),
                dettaglioPortafogli = det?.get("portfolios").oggetti().map { PortafoglioSnap.da(it) },
                prestiti = det?.get("loans").oggetti().map {
                    VoceSemplice(it["id"].testo().orEmpty(), it["name"].testo() ?: "Prestito", it["residual_value"].numero())
                },
                altriAsset = det?.get("other_assets").oggetti().map {
                    VoceSemplice(it["id"].testo().orEmpty(), it["title"].testo() ?: "Asset", it["ownership_value"].numero())
                },
            )
        }
    }
}

/** I periodi dei grafici: gli stessi di `TIMEFRAME_OPTS` in `finanza.html`. */
enum class Periodo(val etichetta: String, val giorni: Long?) {
    SETTIMANA("1 Settimana", 7), MESE("1 Mese", 30), TRE_MESI("3 Mesi", 90),
    SEI_MESI("6 Mesi", 180), ANNO("1 Anno", 365), DUE_ANNI("2 Anni", 730), TUTTO("Da inizio", null);

    fun da(): LocalDate? = giorni?.let { LocalDate.now().minusDays(it) }
}

// ── Repository ──────────────────────────────────────────────────────────────

object FinanzaRepository {

    private val db get() = Supabase.client().postgrest
    private const val TESTA = "snapshot_date,patrimonio_netto,portafogli_totali,asset_totali,debiti_totali,updated_at"

    /**
     * Rifà lo snapshot di oggi lato server (`save-snapshot` col JWT: scrive il **solo**
     * snapshot di chi chiama, senza aggiornare i prezzi). Torna l'errore, se c'è.
     */
    suspend fun aggiornaSnapshot(): String? = chiamaFunzione("save-snapshot", 90_000)

    /**
     * Aggiorna i prezzi in cache (`get-prices`, la stessa chiamata di «⟳ Aggiorna prezzi»
     * in `finanza.html`). ⚠️ Può metterci fino a ~130 s — dopo 105 non comincia più
     * nessun simbolo e a 130 chiude — quindi il tetto di lettura è a 170 s. Torna
     * l'errore, se c'è; i prezzi non aggiornati restano quelli di prima.
     */
    suspend fun aggiornaPrezzi(): String? = chiamaFunzione("get-prices", 170_000)

    /**
     * ⚠️ HttpURLConnection e non `functions.invoke`: il client di supabase-kt ha un
     * timeout più corto di quel che `get-prices` può durare (stessa scelta di `pv-ai`).
     * Il token si controlla prima di mandarlo, e un 401 si riprova una volta dopo un rinnovo.
     */
    private suspend fun chiamaFunzione(nome: String, attesaMs: Int): String? = withContext(Dispatchers.IO) {
        runCatching { conRiprova {
            fun manda(token: String): HttpURLConnection =
                (URL(BuildConfig.SUPABASE_URL + "/functions/v1/$nome").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = attesaMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    outputStream.use { it.write("{}".toByteArray()) }
                }
            var c = manda(token())
            if (c.responseCode == 401) {
                c.disconnect()
                c = manda(rinnova() ?: error("Sessione scaduta: rientra e riprova."))
            }
            val codice = c.responseCode
            val testo = (if (codice in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            val corpo = runCatching { Json.parseToJsonElement(testo).jsonObject }.getOrNull()
            corpo?.get("error").testo() ?: if (codice !in 200..299) "HTTP $codice" else null
        } }.getOrElse { it.testoFinanza() }
    }

    private suspend fun token(): String {
        val corrente = Supabase.client().auth.currentSessionOrNull()?.accessToken
        if (corrente != null && Jwt.vivo(corrente)) return corrente
        return rinnova() ?: error("Sessione scaduta: rientra e riprova.")
    }

    private suspend fun rinnova(): String? {
        val auth = Supabase.client().auth
        if (auth.currentSessionOrNull() == null) return null
        return runCatching { auth.refreshCurrentSession(); auth.currentSessionOrNull()?.accessToken }.getOrNull()
    }

    /**
     * I portafogli a saldi, il loro registro e i prodotti. ⚠️ Se la colonna `gestione` non c'è
     * ancora (migration non applicata) torna vuoto: la pagina resta in sola lettura come prima.
     */
    suspend fun saldi(): Triple<Set<String>, List<SaldoRiga>, List<Moneta>> = withContext(Dispatchers.IO) {
        val pf = runCatching {
            db.from("fnz_portfolios").select(Columns.raw("id")) { filter { eq("gestione", "saldi") } }
                .decodeList<JsonObject>().mapNotNull { it["id"].testo() }.toSet()
        }.getOrElse { Log.w("Finanza", "gestione non letta", it); emptySet() }
        if (pf.isEmpty()) return@withContext Triple(emptySet(), emptyList(), emptyList())
        val registro = db.from("fnz_saldi_crypto").select(Columns.raw("id,portfolio_id,product_id,qty,recorded_at,note")) {
            order("recorded_at", Order.DESCENDING)
            order("created_at", Order.DESCENDING)
        }.decodeList<JsonObject>().mapNotNull { o ->
            SaldoRiga(
                id = o["id"].testo() ?: return@mapNotNull null,
                portafoglio = o["portfolio_id"].testo() ?: return@mapNotNull null,
                prodotto = o["product_id"].testo() ?: return@mapNotNull null,
                quantita = o["qty"].numero() ?: 0.0,
                quando = o["recorded_at"].testo().orEmpty(),
                nota = o["note"].testo(),
            )
        }
        val monete = db.from("fnz_products").select(Columns.raw("id,symbol,name,asset_type")) {
            order("symbol", Order.ASCENDING)
        }.decodeList<JsonObject>().mapNotNull { o ->
            Moneta(o["id"].testo() ?: return@mapNotNull null, o["symbol"].testo().orEmpty(),
                o["name"].testo().orEmpty(), o["asset_type"].testo())
        }
        Triple(pf, registro, monete)
    }

    /** Una riga nuova nel registro: il registro si scrive e non si riscrive. */
    suspend fun scriviSaldo(portafoglio: String, prodotto: String, quantita: Double, nota: String?) = withContext(Dispatchers.IO) {
        val utente = AuthRepo.userId() ?: error("Sessione scaduta: rientra e riprova.")
        db.from("fnz_saldi_crypto").insert(buildJsonObject {
            put("user_id", utente)
            put("portfolio_id", portafoglio)
            put("product_id", prodotto)
            put("qty", quantita)
            if (!nota.isNullOrBlank()) put("note", nota.trim())
        })
        Unit
    }

    /** `cm_settings.sal_saldo`; null se non c'è o non si legge. */
    suspend fun salSaldo(): SalSaldo? = withContext(Dispatchers.IO) {
        runCatching {
            val riga = db.from("cm_settings").select(Columns.raw("value")) { filter { eq("key", "sal_saldo") } }
                .decodeList<JsonObject>().firstOrNull() ?: return@runCatching null
            val v = riga["value"]
            val o = (v as? JsonObject) ?: (v as? JsonPrimitive)?.content?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?: return@runCatching null
            val imp = o["importo"].numero() ?: return@runCatching null
            SalSaldo(imp, o["data"].testo())
        }.getOrElse { Log.w("Finanza", "sal_saldo non letto", it); null }
    }

    suspend fun eliminaSaldo(id: String) = withContext(Dispatchers.IO) {
        db.from("fnz_saldi_crypto").delete { filter { eq("id", id) } }
        Unit
    }

    /** Le colonne di testa di tutti gli snapshot, dal più vecchio. A pagine da 1000. */
    suspend fun storico(): List<Snapshot> = withContext(Dispatchers.IO) { conRiprova {
        val out = mutableListOf<Snapshot>()
        var da = 0L
        while (true) {
            val pagina = db.from("fnz_dashboard_snapshots").select(Columns.raw(TESTA)) {
                order("snapshot_date", Order.ASCENDING)
                range(da, da + 999)
            }.decodeList<JsonObject>().map { Snapshot.da(it) }
            out += pagina
            if (pagina.size < 1000) break
            da += 1000
        }
        out
    } }

    /** L'ultimo snapshot e quello prima, col dettaglio: servono a Dashboard e Portafogli. */
    suspend fun ultimiDue(): List<Snapshot> = withContext(Dispatchers.IO) { conRiprova {
        db.from("fnz_dashboard_snapshots").select(Columns.raw("$TESTA,details")) {
            order("snapshot_date", Order.DESCENDING)
            limit(2)
        }.decodeList<JsonObject>().map { Snapshot.da(it) }
    } }

    /**
     * Gli snapshot di un periodo col dettaglio, per l'andamento di un portafoglio.
     * ⚠️ Solo aprendo quella vista e solo sul periodo scelto: `details` porta tutte le
     * posizioni di tutti i portafogli, e un anno sono megabyte (come `caricaPtfSnapshots`).
     */
    suspend fun conDettaglio(periodo: Periodo): List<Snapshot> = withContext(Dispatchers.IO) { conRiprova {
        val inizio = periodo.da()
        db.from("fnz_dashboard_snapshots").select(Columns.raw("snapshot_date,patrimonio_netto,portafogli_totali,asset_totali,debiti_totali,details")) {
            if (inizio != null) filter { gte("snapshot_date", inizio.toString()) }
            order("snapshot_date", Order.ASCENDING)
        }.decodeList<JsonObject>().map { Snapshot.da(it) }
    } }
}

// ── ViewModel ───────────────────────────────────────────────────────────────

enum class VistaFinanza(val etichetta: String) {
    DASHBOARD("📊 Dashboard"), SVILUPPO("📈 Sviluppo"), PORTAFOGLI("💼 Portafogli")
}

data class Migliori(val patrimonio: Double?, val portafogli: Double?, val asset: Double?, val debiti: Double?)

data class FinanzaState(
    val caricamento: Boolean = true,
    val aggiornamento: Boolean = false,
    /** Cosa si sta aggiornando, scritto sotto la barra: «Aggiorno i prezzi…» può durare minuti. */
    val fase: String? = null,
    val errore: String? = null,
    /**
     * Cosa non si è potuto aggiornare (prezzi, snapshot), uno per riga. ⚠️ Un elenco e non
     * un campo solo: l'errore dello snapshot copriva quello dei prezzi che lo precedeva.
     */
    val avvisi: List<String> = emptyList(),
    val vista: VistaFinanza = VistaFinanza.DASHBOARD,
    val ultimo: Snapshot? = null,
    val precedente: Snapshot? = null,
    val storico: List<Snapshot> = emptyList(),
    val periodoSviluppo: Periodo = Periodo.TRE_MESI,
    val soloMensili: Boolean = true,
    val portafoglioAperto: String? = null,
    val periodoPortafoglio: Periodo = Periodo.TRE_MESI,
    val serie: List<Snapshot>? = null,
    val serieErrore: String? = null,
    /** Portafogli con `gestione = 'saldi'`: le loro quantità si scrivono a mano. */
    val saldi: Set<String> = emptySet(),
    val registro: List<SaldoRiga> = emptyList(),
    val monete: List<Moneta> = emptyList(),
    val salvataggio: Boolean = false,
    /** Il saldo del Conto Spese Personali (cm_settings.sal_saldo). */
    val salSaldo: SalSaldo? = null,
    /** Σ CUMULATIVO aperto, e il titolo aperto dentro di lui (per simbolo). */
    val cumulAperto: Boolean = false,
    val titoloCumul: String? = null,
    /** Filtro Tipo del cumulativo: in memoria, come `S.cumulTipo`. */
    val cumulTipo: String? = null,
) {
    val cumulativo: Cumulativo get() = Cumulativo.da(ultimo?.dettaglioPortafogli.orEmpty(), salSaldo)

    /** La liquidità dei portafogli (in quota) nello snapshot: si sposta nella scheda 💶. */
    fun liquiditaDi(sn: Snapshot?): Double? = sn?.dettaglioPortafogli?.let { l ->
        if (l.any { it.liquidita == null }) null else l.sumOf { it.liquidita ?: 0.0 }
    }

    /** La quantità in vigore di una moneta in un portafoglio a saldi: l'ultima del registro. */
    fun quantitaInVigore(portafoglio: String, prodotto: String): Double? =
        registro.firstOrNull { it.portafoglio == portafoglio && it.prodotto == prodotto }?.quantita

    /** Il valore di un portafoglio nello snapshot precedente, per la variazione. */
    fun valorePrecedente(id: String): Double? =
        precedente?.dettaglioPortafogli?.firstOrNull { it.id == id }?.valore

    /**
     * «📅 Uno per mese» come nel web: per ogni mese lo snapshot coi **portafogli più alti**
     * (non l'ultimo del mese), dal più recente.
     */
    val storicoMostrato: List<Snapshot>
        get() {
            val tutti = storico.asReversed()
            if (!soloMensili) return tutti
            return tutti.groupBy { it.data.take(7) }.values.map { mese -> mese.maxBy { it.portafogli } }
        }

    /**
     * Il migliore di ogni colonna **fra le righe mostrate** (`evid` in `finanza.html`): il
     * più alto, tranne i debiti dove è il più basso. Si evidenzia in un box verde.
     */
    val migliori: Migliori
        get() {
            val r = storicoMostrato
            return Migliori(
                patrimonio = r.maxOfOrNull { it.patrimonioNetto },
                portafogli = r.maxOfOrNull { it.portafogli },
                asset = r.maxOfOrNull { it.asset },
                debiti = r.minOfOrNull { it.debiti },
            )
        }

    val storicoDelPeriodo: List<Snapshot>
        get() {
            val da = periodoSviluppo.da()?.toString() ?: return storico
            return storico.filter { it.data >= da }
        }
}

class FinanzaViewModel : ViewModel() {

    private val _state = MutableStateFlow(FinanzaState())
    val state: StateFlow<FinanzaState> = _state.asStateFlow()

    init { carica(rifaiSnapshot = true) }

    /**
     * Prima si legge quel che c'è (la pagina si apre subito), poi si rifà lo snapshot
     * di oggi e si rilegge. Se il rifacimento non riesce resta l'ultimo in archivio.
     */
    fun carica(rifaiSnapshot: Boolean, conPrezzi: Boolean = false) {
        if (_state.value.aggiornamento && conPrezzi) return
        viewModelScope.launch {
            _state.value = _state.value.copy(aggiornamento = true, errore = null, avvisi = emptyList())
            if (!conPrezzi) leggi()
            if (conPrezzi) {
                _state.value = _state.value.copy(fase = "Aggiorno i prezzi… può volerci qualche minuto")
                FinanzaRepository.aggiornaPrezzi()?.let {
                    _state.value = _state.value.copy(avvisi = _state.value.avvisi + "Prezzi non aggiornati: $it")
                }
            }
            if (rifaiSnapshot) {
                _state.value = _state.value.copy(fase = "Rifaccio lo snapshot di oggi…")
                val errore = FinanzaRepository.aggiornaSnapshot()
                if (errore != null) _state.value = _state.value.copy(avvisi = _state.value.avvisi + "Snapshot non rifatto: $errore")
                if (errore == null || conPrezzi) leggi()
            }
            _state.value = _state.value.copy(aggiornamento = false, fase = null, caricamento = false, serie = null)
            _state.value.portafoglioAperto?.let { caricaSerie() }
        }
    }

    private suspend fun leggi() {
        try {
            val due = FinanzaRepository.ultimiDue()
            val storico = FinanzaRepository.storico()
            _state.value = _state.value.copy(
                ultimo = due.getOrNull(0),
                precedente = due.getOrNull(1),
                storico = storico,
                caricamento = false,
            )
            _state.value = _state.value.copy(salSaldo = FinanzaRepository.salSaldo())
            runCatching { FinanzaRepository.saldi() }
                .onSuccess { (pf, reg, mon) -> _state.value = _state.value.copy(saldi = pf, registro = reg, monete = mon) }
                .onFailure { Log.w(TAG, "registro saldi non letto", it) }
        } catch (e: Exception) {
            Log.w(TAG, "lettura snapshot fallita", e)
            _state.value = _state.value.copy(caricamento = false, errore = "Snapshot non letti: " + e.testoFinanza())
        }
    }

    /**
     * ✏️ Aggiorna la quantità di una moneta, poi rifà lo snapshot e rilegge: i numeri a
     * schermo sono quelli dello snapshot, e senza rifarlo la quantità nuova non si vedrebbe.
     */
    fun salvaSaldo(portafoglio: String, prodotto: String, quantita: Double, nota: String?, fatto: () -> Unit) =
        scriviERileggi(fatto) { FinanzaRepository.scriviSaldo(portafoglio, prodotto, quantita, nota) }

    fun eliminaSaldo(id: String) = scriviERileggi({}) { FinanzaRepository.eliminaSaldo(id) }

    private fun scriviERileggi(fatto: () -> Unit, scrivi: suspend () -> Unit) {
        if (_state.value.salvataggio) return
        viewModelScope.launch {
            _state.value = _state.value.copy(salvataggio = true, errore = null, avvisi = emptyList())
            try {
                scrivi()
                fatto()
                _state.value = _state.value.copy(fase = "Rifaccio lo snapshot di oggi…")
                FinanzaRepository.aggiornaSnapshot()?.let {
                    _state.value = _state.value.copy(avvisi = listOf("Quantità salvata, ma snapshot non rifatto: $it"))
                }
                leggi()
                _state.value = _state.value.copy(serie = null)
                _state.value.portafoglioAperto?.let { caricaSerie() }
            } catch (e: Exception) {
                Log.w(TAG, "saldo non scritto", e)
                _state.value = _state.value.copy(errore = "Non salvato: " + e.testoFinanza())
            } finally {
                _state.value = _state.value.copy(salvataggio = false, fase = null)
            }
        }
    }

    fun vista(v: VistaFinanza) { _state.value = _state.value.copy(vista = v, portafoglioAperto = null, cumulAperto = false, titoloCumul = null) }

    fun apriCumulativo(aperto: Boolean) { _state.value = _state.value.copy(cumulAperto = aperto, titoloCumul = null) }

    fun apriTitoloCumul(simbolo: String?) { _state.value = _state.value.copy(titoloCumul = simbolo) }

    fun cumulTipo(t: String?) { _state.value = _state.value.copy(cumulTipo = t) }

    fun periodoSviluppo(p: Periodo) { _state.value = _state.value.copy(periodoSviluppo = p) }

    fun soloMensili(v: Boolean) { _state.value = _state.value.copy(soloMensili = v) }

    fun apriPortafoglio(id: String?) {
        _state.value = _state.value.copy(portafoglioAperto = id)
        if (id != null && _state.value.serie == null) caricaSerie()
    }

    fun periodoPortafoglio(p: Periodo) {
        _state.value = _state.value.copy(periodoPortafoglio = p, serie = null)
        caricaSerie()
    }

    private fun caricaSerie() {
        val periodo = _state.value.periodoPortafoglio
        viewModelScope.launch {
            _state.value = _state.value.copy(serieErrore = null)
            try {
                val righe = FinanzaRepository.conDettaglio(periodo)
                // Il periodo può essere cambiato mentre questa leggeva.
                if (_state.value.periodoPortafoglio == periodo) _state.value = _state.value.copy(serie = righe)
            } catch (e: Exception) {
                Log.w(TAG, "andamento non letto", e)
                if (_state.value.periodoPortafoglio == periodo)
                    _state.value = _state.value.copy(serie = emptyList(), serieErrore = e.testoFinanza())
            }
        }
    }

    private companion object { const val TAG = "Finanza" }
}
