package com.garsal.appsphere.finanza

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.jsonObject
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
                )
            },
        )
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
    suspend fun aggiornaSnapshot(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = Supabase.client().functions.invoke("save-snapshot") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            val corpo = runCatching { Json.parseToJsonElement(r.bodyAsText()).jsonObject }.getOrNull()
            corpo?.get("error").testo()
        }.getOrElse { it.message ?: "save-snapshot non raggiungibile" }
    }

    /** Le colonne di testa di tutti gli snapshot, dal più vecchio. A pagine da 1000. */
    suspend fun storico(): List<Snapshot> = withContext(Dispatchers.IO) {
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
    }

    /** L'ultimo snapshot e quello prima, col dettaglio: servono a Dashboard e Portafogli. */
    suspend fun ultimiDue(): List<Snapshot> = withContext(Dispatchers.IO) {
        db.from("fnz_dashboard_snapshots").select(Columns.raw("$TESTA,details")) {
            order("snapshot_date", Order.DESCENDING)
            limit(2)
        }.decodeList<JsonObject>().map { Snapshot.da(it) }
    }

    /**
     * Gli snapshot di un periodo col dettaglio, per l'andamento di un portafoglio.
     * ⚠️ Solo aprendo quella vista e solo sul periodo scelto: `details` porta tutte le
     * posizioni di tutti i portafogli, e un anno sono megabyte (come `caricaPtfSnapshots`).
     */
    suspend fun conDettaglio(periodo: Periodo): List<Snapshot> = withContext(Dispatchers.IO) {
        val inizio = periodo.da()
        db.from("fnz_dashboard_snapshots").select(Columns.raw("snapshot_date,patrimonio_netto,portafogli_totali,asset_totali,debiti_totali,details")) {
            if (inizio != null) filter { gte("snapshot_date", inizio.toString()) }
            order("snapshot_date", Order.ASCENDING)
        }.decodeList<JsonObject>().map { Snapshot.da(it) }
    }
}

// ── ViewModel ───────────────────────────────────────────────────────────────

enum class VistaFinanza(val etichetta: String) {
    DASHBOARD("📊 Dashboard"), SVILUPPO("📈 Sviluppo"), PORTAFOGLI("💼 Portafogli")
}

data class FinanzaState(
    val caricamento: Boolean = true,
    val aggiornamento: Boolean = false,
    val errore: String? = null,
    /** Perché lo snapshot di oggi non si è potuto rifare: si mostra quello in archivio. */
    val avvisoSnapshot: String? = null,
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
) {
    /** Il valore di un portafoglio nello snapshot precedente, per la variazione. */
    fun valorePrecedente(id: String): Double? =
        precedente?.dettaglioPortafogli?.firstOrNull { it.id == id }?.valore

    /** Uno per mese: l'ultimo di ogni mese, come «📅 Uno per mese» nel web. */
    val storicoMostrato: List<Snapshot>
        get() {
            val tutti = storico.asReversed()
            return if (!soloMensili) tutti else tutti.distinctBy { it.data.take(7) }
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
    fun carica(rifaiSnapshot: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(aggiornamento = true, errore = null)
            leggi()
            if (rifaiSnapshot) {
                val errore = FinanzaRepository.aggiornaSnapshot()
                _state.value = _state.value.copy(avvisoSnapshot = errore)
                if (errore == null) leggi()
            }
            _state.value = _state.value.copy(aggiornamento = false, caricamento = false, serie = null)
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
        } catch (e: Exception) {
            Log.w(TAG, "lettura snapshot fallita", e)
            _state.value = _state.value.copy(caricamento = false, errore = e.message ?: "Lettura non riuscita")
        }
    }

    fun vista(v: VistaFinanza) { _state.value = _state.value.copy(vista = v, portafoglioAperto = null) }

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
                    _state.value = _state.value.copy(serie = emptyList(), serieErrore = e.message ?: "Non letto")
            }
        }
    }

    private companion object { const val TAG = "Finanza" }
}
