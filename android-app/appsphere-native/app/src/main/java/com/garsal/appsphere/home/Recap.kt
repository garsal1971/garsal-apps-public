package com.garsal.appsphere.home

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ── 📋 RECAP DEL GIORNO (APK 1.0.126) ──────────────────────────────────────
//
// Il gemello del recap di `index.html` (v1.13.0), che ha preso il posto del
// fumetto «Avvisi». Si apre da solo alla PRIMA apertura della giornata su
// questo telefono (preferenza `recap_giorno`) e quando si vuole dal 📋 in alto
// a sinistra. Tre sezioni, come sul web:
//   ℹ️ Info      — 💶 Finanza (totale portafogli) e ⚖️ Peso e Calorie, un box solo;
//   ✅ Compiti   — 📅 Oggi e ⚠️ Scadute, sempre separate;
//   🔔 Promemoria aperti.
// ⚠️ Le fonti sono le STESSE del web, query per query: cambiando una di là va
// cambiata anche qui (recapTasks, recapObiettivi, recapPiante, recapTaFiri,
// recapDecisioni, loadHomeAlertSpuntiamola, loadHomeAlertHabits, recapPeso,
// recapPromemoria, loadHomeAlertPortfolioTotal).
// ⚠️ Un compito di un'app che in nativo non c'è (Decisioni) si mostra lo
// stesso ma il tocco non apre niente: `route` è null.
// ⚠️ Ogni box si toglie trascinandolo a destra, fino a domani e solo su questo
// telefono (`recap_tolti`): la stessa regola di `appsphere_recap_tolti` nel web,
// con chiavi che non contengono i numeri mostrati.

data class RecapVoce(
    val app: String,
    val titolo: String,
    val riservata: Boolean,
    val route: String?,
    /** Giorno locale `YYYY-MM-DD`: decide fra Oggi e Scadute. */
    val giorno: String,
    val ora: String = "",
) {
    val chiave: String get() = "s|$app|$titolo|$giorno|$ora"
}

data class RecapPromemoria(
    val titolo: String,
    val fireAt: String,
    /** Giorno LOCALE (yyyy-MM-dd): con il titolo è la chiave dell'accorpamento. */
    val giorno: String,
    /** Gli orari locali (HH:mm) di quel giorno, in ordine. */
    val orari: List<String>,
    val canali: List<String>,
    val riservata: Boolean,
) {
    // Titolo + giorno, come recapChiavePromemoria del web: togliere il box toglie
    // tutti gli orari di quel giorno.
    val chiave: String get() = "p|$titolo|$giorno"
}

data class DatiRecap(
    val finanza: String?,
    val peso: List<String>,
    val scadenze: List<RecapVoce>,
    val promemoria: List<RecapPromemoria>,
    val letto: String,
)

// ── Lettura dei JSON: le tabelle si leggono come JsonObject, come ts_tasks ──

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.n(k: String): Double? = s(k)?.toDoubleOrNull()
private fun JsonObject.b(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.a(k: String): List<String> =
    (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private val ITA = Locale.ITALY

private fun dataBreve(giorno: String): String =
    if (giorno.length >= 10) giorno.substring(8, 10) + "/" + giorno.substring(5, 7) else giorno

private fun euroInt(v: Double): String {
    val r = v.roundToLong()
    return (if (r < 0) "-" else "") + String.format(ITA, "%,d", abs(r)) + " €"
}

private fun kg(v: Double): String =
    DecimalFormat("#,##0.0#", DecimalFormatSymbols(ITA)).format(v) + " kg"

private fun pctVar(attuale: Double, prima: Double?, etichetta: String): String? {
    if (prima == null || prima == 0.0) return null
    val p = (attuale - prima) / abs(prima) * 100
    return (if (p >= 0) "+" else "") + String.format(ITA, "%.2f", p) + "% " + etichetta
}

/** Giorno locale di un istante: ⚠️ non si taglia la stringa (azioni di mezzanotte). */
private fun giornoDi(v: String?, zona: ZoneId): String? {
    if (v.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(v.replace(' ', 'T')).atZoneSameInstant(zona).toLocalDate().toString() }
        .getOrNull() ?: v.take(10).takeIf { it.length == 10 }
}

object RecapRepository {

    private const val TAG = "AppSphereRecap"

    private suspend fun righe(
        tabella: String,
        colonne: String,
        richiesta: PostgrestRequestBuilder.() -> Unit = {},
    ): List<JsonObject> = try {
        Supabase.client().postgrest.from(tabella).select(Columns.raw(colonne), richiesta).decodeList<JsonObject>()
    } catch (e: Exception) {
        Log.w(TAG, "$tabella non letta: ${e.message}")
        emptyList()
    }

    private suspend fun rpc(nome: String, parametri: JsonObject = buildJsonObject { }): kotlinx.serialization.json.JsonElement? = try {
        Supabase.client().postgrest.rpc(nome, parametri).decodeAs<kotlinx.serialization.json.JsonElement>()
    } catch (e: Exception) {
        Log.w(TAG, "$nome non letta: ${e.message}")
        null
    }

    suspend fun carica(): DatiRecap = withContext(Dispatchers.IO) {
        coroutineScope {
            val zona = ZoneId.systemDefault()
            val oggi = LocalDate.now(zona)
            val finanza = async { finanza(oggi) }
            val decisioni = async { decisioni(oggi) }
            val tasks = async { tasks(oggi) }
            val obiettivi = async { azioni("ob_actions", "title,next_occurrence_date", "🎯 Obiettivi", Route.OBIETTIVI, oggi, zona) }
            val piante = async { piante(oggi, zona) }
            val tafiri = async { taFiri(oggi) }
            val spunt = async { spuntiamola(oggi) }
            val abitudini = async { abitudini(oggi) }
            val peso = async { peso(oggi, zona) }
            val riservati = async {
                righe("ts_tasks", "id") { filter { eq("riservato", true) } }.mapNotNull { it.s("id") }.toSet()
            }
            val prom = promemoria(riservati.await())
            val (infoPeso, compitoPeso) = peso.await()
            DatiRecap(
                finanza = finanza.await(),
                peso = infoPeso,
                scadenze = decisioni.await() + tasks.await() + obiettivi.await() + piante.await() +
                    tafiri.await() + spunt.await() + abitudini.await() + compitoPeso,
                promemoria = prom,
                letto = oggi.toString(),
            )
        }
    }

    // ── 💶 Finanza: totale portafogli coi prezzi correnti (fetchPortfolioLiveValue) ──

    private suspend fun valoreLive(): Double? {
        val portafogli = righe("fnz_portfolios", "id,ownership_percentage")
        if (portafogli.isEmpty()) return null
        val prodotti = righe("fnz_products", "id,symbol")
        val storico = righe("fnz_price_history", "symbol,price,price_date") {
            order("price_date", Order.DESCENDING)
            order("id", Order.DESCENDING)
        }
        // Senza posizioni il totale sarebbe la sola liquidità: niente è meglio di un numero falso.
        val posizioni = (rpc("fnz_posizioni") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val liquidita = (rpc("fnz_liquidita") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        val prezzo = HashMap<String, Double>()
        for (r in storico) {
            val sym = r.s("symbol") ?: continue
            val p = r.n("price") ?: continue
            if (sym !in prezzo) prezzo[sym] = p
        }
        val simbolo = prodotti.associate { (it.s("id") ?: "") to it.s("symbol") }
        val cassa = liquidita.associate { (it.s("portfolio_id") ?: "") to (it.n("cash") ?: 0.0) }

        var totale = 0.0
        for (p in portafogli) {
            val id = p.s("id") ?: continue
            val quota = (p.n("ownership_percentage")?.takeIf { it != 0.0 } ?: 100.0) / 100
            var valore = 0.0
            for (r in posizioni) {
                if (r.s("scope") != "portfolio" || r.s("scope_id") != id) continue
                val pid = r.s("product_id") ?: continue
                if (pid !in simbolo) continue
                val px = simbolo[pid]?.let { prezzo[it] }
                valore += if (px != null) (r.n("qty") ?: 0.0) * px else (r.n("cost_basis") ?: 0.0)
            }
            totale += (valore + (cassa[id] ?: 0.0)) * quota
        }
        return totale
    }

    private suspend fun snapshot(richiesta: PostgrestRequestBuilder.() -> Unit): Pair<Double, String>? =
        righe("fnz_dashboard_snapshots", "portafogli_totali,snapshot_date") {
            richiesta()
            order("snapshot_date", Order.DESCENDING)
            limit(1)
        }.firstOrNull()?.let { r ->
            val v = r.n("portafogli_totali") ?: return@let null
            v to (r.s("snapshot_date") ?: "").take(10)
        }

    private suspend fun finanza(oggi: LocalDate): String? = try {
        val inizioAnno = "${oggi.year}-01-01"
        val dueGiorni = oggi.minusDays(1).toString()
        // Se i prezzi non sono calcolabili si ripiega sull'ultimo snapshot salvato.
        val live = valoreLive()
        val ripiego = if (live == null) snapshot { } ?: return null else null
        val attuale: Double = live ?: ripiego!!.first
        val giorno: String = if (live != null) oggi.toString() else ripiego!!.second
        val annoPrima = snapshot { filter { lt("snapshot_date", inizioAnno) } }
        val giornoPrima = snapshot { filter { lt("snapshot_date", dueGiorni) } }
        val parti = mutableListOf<String>()
        if (annoPrima != null && annoPrima.second < giorno) pctVar(attuale, annoPrima.first, "da fine anno scorso")?.let(parti::add)
        if (giornoPrima != null && giornoPrima.second < giorno) {
            val etichetta = if (giornoPrima.second == oggi.minusDays(2).toString()) "da due giorni" else "dal " + dataBreve(giornoPrima.second)
            pctVar(attuale, giornoPrima.first, etichetta)?.let(parti::add)
        }
        "Totale portafogli: " + euroInt(attuale) + (if (parti.isEmpty()) "" else " (" + parti.joinToString(" · ") + ")")
    } catch (e: Exception) {
        Log.w(TAG, "finanza: ${e.message}"); null
    }

    // ── Compiti ──

    private suspend fun decisioni(oggi: LocalDate): List<RecapVoce> {
        val uid = AuthRepo.userId() ?: return emptyList()
        return righe("dc_decisions", "id,title,is_hidden") {
            filter { eq("user_id", uid); eq("show_home_alert", true); eq("status", "active") }
            order("created_at", Order.DESCENDING)
        }.map { RecapVoce("🗳️ Decisioni in sospeso", it.s("title") ?: "", it.b("is_hidden"), null, oggi.toString()) }
    }

    /** ⚠️ Le date dei task si TAGLIANO: tasks.html le scrive già in ora locale. */
    private suspend fun tasks(oggi: LocalDate): List<RecapVoce> {
        val o = oggi.toString()
        return righe("ts_tasks", "*") {
            filter { isIn("status", listOf("started", "completed", "failed", "skipped")); neq("type", "free_repeat") }
        }.mapNotNull { t ->
            val raw = (if (t.s("type") == "single") t.s("start_date") else (t.s("next_occurrence_date") ?: t.s("start_date"))) ?: ""
            val giorno = raw.take(10)
            if (giorno.length < 10 || giorno > o) return@mapNotNull null
            val nascosto = t.s("nascosto_fino")?.take(10)
            if (giorno < o && nascosto != null && nascosto >= o) return@mapNotNull null
            val ora = if (raw.length > 15) raw.substring(11, 16) else ""
            RecapVoce("📋 Tasks", t.s("title") ?: "", t.b("riservato"), Route.TASKS, giorno, if (ora == "00:00") "" else ora)
        }
    }

    private fun domaniIso(oggi: LocalDate, zona: ZoneId): String = oggi.plusDays(1).atStartOfDay(zona).toInstant().toString()

    private suspend fun azioni(
        tabella: String, colonne: String, app: String, route: String, oggi: LocalDate, zona: ZoneId,
    ): List<RecapVoce> = righe(tabella, colonne) {
        filter { neq("status", "terminated"); lt("next_occurrence_date", domaniIso(oggi, zona)) }
        order("next_occurrence_date", Order.ASCENDING)
    }.map { RecapVoce(app, it.s("title") ?: "", false, route, giornoDi(it.s("next_occurrence_date"), zona) ?: oggi.toString()) }

    private suspend fun piante(oggi: LocalDate, zona: ZoneId): List<RecapVoce> =
        righe("pv_actions", "title,next_occurrence_date,pv_plants(name,archived),pv_groups(name,emoji)") {
            filter { neq("status", "terminated"); lt("next_occurrence_date", domaniIso(oggi, zona)) }
            order("next_occurrence_date", Order.ASCENDING)
        }.filter { it.o("pv_plants")?.b("archived") != true }.map { a ->
            val di = a.o("pv_plants")?.s("name")
                ?: a.o("pv_groups")?.let { g -> listOfNotNull(g.s("emoji"), g.s("name")).joinToString(" ") }
            val titolo = (a.s("title") ?: "") + (if (!di.isNullOrBlank()) " — $di" else "")
            RecapVoce("🌱 Piante", titolo, false, Route.PIANTE, giornoDi(a.s("next_occurrence_date"), zona) ?: oggi.toString())
        }

    private suspend fun taFiri(oggi: LocalDate): List<RecapVoce> {
        val o = oggi.toString()
        val sfide = righe("sf_challenges", "id,title,checkin_time") {
            filter { eq("status", "active"); lte("start_date", o) }
        }
        if (sfide.isEmpty()) return emptyList()
        val perId = sfide.associateBy { it.s("id") ?: "" }
        return righe("sf_checkins", "challenge_id,day_date,status") {
            filter { eq("status", "pending"); lte("day_date", o); isIn("challenge_id", perId.keys.toList()) }
            order("day_date", Order.ASCENDING)
        }.mapNotNull { k ->
            val c = perId[k.s("challenge_id")] ?: return@mapNotNull null
            RecapVoce("🔥 Ta Firi?", c.s("title") ?: "", false, Route.TA_FIRI,
                (k.s("day_date") ?: o).take(10), (c.s("checkin_time") ?: "").take(5))
        }
    }

    private suspend fun spuntiamola(oggi: LocalDate): List<RecapVoce> {
        val o = oggi.toString()
        val s = righe("sp_settings", "goal,emoji,start_date,end_date,skip_weekend") { limit(1) }.firstOrNull() ?: return emptyList()
        if (o < (s.s("start_date") ?: "") || o > (s.s("end_date") ?: "")) return emptyList()
        val dow = oggi.dayOfWeek.value % 7
        if (s.b("skip_weekend") && (dow == 0 || dow == 6)) return emptyList()
        if (righe("sp_checks", "day") { filter { eq("day", o) }; limit(1) }.isNotEmpty()) return emptyList()
        val chiave = righe("sp_key_days", "day,label") { filter { eq("day", o) }; limit(1) }.firstOrNull()
        val titolo = if (chiave != null) {
            "⭐ Oggi è una giornata chiave" + (chiave.s("label")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") + " e non l'hai spuntata!"
        } else "Oggi non l'hai ancora spuntato — " + (s.s("emoji") ?: "🎯") + " " + (s.s("goal") ?: "")
        return listOf(RecapVoce("✅ Spuntiamola", titolo, false, Route.SPUNTIAMOLA, o))
    }

    /** Gemella di loadHomeAlertHabits: le attività previste oggi e senza nessuna riga. */
    private suspend fun abitudini(oggi: LocalDate): List<RecapVoce> {
        val o = oggi.toString()
        val abitudini = righe("hb_habits", "id,name,frequency,weekdays,daily_times,started_at,created_at,riservato") {
            filter { eq("status", "active") }
        }
        if (abitudini.isEmpty()) return emptyList()
        val fatte = righe("hb_completions", "habit_id,completed_at,period_key,status") {
            filter { gte("completed_at", oggi.minusDays(1).toString()); lt("completed_at", oggi.plusDays(2).toString()) }
        }.filter { (it.s("completed_at") ?: "").startsWith(o) }
        val dow = oggi.dayOfWeek.value % 7
        val out = mutableListOf<RecapVoce>()
        for (h in abitudini) {
            val inizio = (h.s("started_at") ?: h.s("created_at") ?: "").take(10)
            if (inizio.isNotEmpty() && inizio > o) continue
            val freq = h.s("frequency")
            val giorni = h.a("weekdays").mapNotNull { it.toIntOrNull() }
            if (freq == "weekly" && giorni.isNotEmpty() && dow !in giorni) continue
            if (freq == "count_window") continue
            val id = h.s("id")
            val mie = fatte.filter { it.s("habit_id") == id }
            val nome = h.s("name") ?: ""
            val orari = h.a("daily_times")
            if (freq == "daily_multiple" && orari.isNotEmpty()) {
                for (raw in orari) {
                    val t = raw.take(5)
                    val chiusa = mie.any { (it.s("period_key") ?: "") == "$o-$t" || (it.s("completed_at") ?: "").let { c -> c.length >= 16 && c.substring(11, 16) == t } }
                    if (!chiusa) out += RecapVoce("🔁 Abituati", "$nome · $t", h.b("riservato"), Route.ABITUATI, o)
                }
            } else if (mie.isEmpty()) {
                out += RecapVoce("🔁 Abituati", nome, h.b("riservato"), Route.ABITUATI, o)
            }
        }
        return out
    }

    // ── ⚖️ Peso e Calorie (recapPeso): le regole di pesata e interpolazione sono
    // copie di quelle di weight-quest.html, come quelle di index.html ──

    private fun giorniFra(a: String, b: String): Long =
        ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b))

    private fun giornoDiPesata(ob: JsonObject, giorno: String): Boolean {
        val n = ob.s("weigh_every_days")?.toDoubleOrNull()?.toInt()
        val inizio = (ob.s("start_date") ?: "").take(10)
        if (n == null || n < 1) return true
        if (inizio.isEmpty() || giorno < inizio) return false
        return giorniFra(inizio, giorno) % n == 0L
    }

    private fun prossimaPesata(ob: JsonObject, dal: String): String? {
        val inizio = (ob.s("start_date") ?: "").take(10)
        val fine = (ob.s("end_date") ?: "").take(10)
        if (inizio.isEmpty()) return null
        var d = LocalDate.parse(if (dal > inizio) dal else inizio)
        repeat(400) {
            val s = d.toString()
            if (fine.isNotEmpty() && s > fine) return null
            if (giornoDiPesata(ob, s)) return s
            d = d.plusDays(1)
        }
        return null
    }

    private fun pesoPianoAl(giorno: String, ob: JsonObject): Double? {
        val grezzo = ob["milestones"]
        val arr: JsonArray? = when (grezzo) {
            is JsonArray -> grezzo
            is JsonPrimitive -> grezzo.contentOrNull?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            else -> null
        }
        val ms = arr.orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val d = m.s("date")?.take(10) ?: return@mapNotNull null
            val w = m.n("weight") ?: return@mapNotNull null
            d to w
        }.sortedBy { it.first }
        if (ms.size < 2) return null
        if (giorno <= ms.first().first) return ms.first().second
        if (giorno >= ms.last().first) return ms.last().second
        for (i in 0 until ms.size - 1) {
            val (da, wa) = ms[i]
            val (a, wb) = ms[i + 1]
            if (giorno in da..a) {
                val tot = giorniFra(da, a)
                if (tot == 0L) return wa
                return ((wa + (wb - wa) * giorniFra(da, giorno) / tot) * 100).roundToInt() / 100.0
            }
        }
        return null
    }

    private suspend fun peso(oggi: LocalDate, zona: ZoneId): Pair<List<String>, List<RecapVoce>> = try {
        coroutineScope {
            val o = oggi.toString()
            val da = oggi.atStartOfDay(zona).toInstant().toEpochMilli()
            val a = oggi.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()
            val obj = async {
                righe("ps_objectives", "*") {
                    filter { eq("status", "active") }
                    order("created_at", Order.DESCENDING)
                    limit(1)
                }.firstOrNull()
            }
            val ultima = async {
                righe("ps_weight_tracking", "weight,timestamp") { order("timestamp", Order.DESCENDING); limit(1) }.firstOrNull()
            }
            val diOggi = async {
                righe("ps_weight_tracking", "timestamp") { filter { gte("timestamp", da); lt("timestamp", a) }; limit(1) }
            }
            val ema = async {
                righe("ps_daily_ema", "day,ema_weight") {
                    filter { lte("day", o) }
                    order("day", Order.DESCENDING)
                    limit(10)
                }.firstOrNull { it.n("ema_weight") != null }
            }
            val conto = async { rpc("al_conto", buildJsonObject { put("p_oggi", o) }) as? JsonObject }

            val ob = obj.await()
            val pesato = diOggi.await().isNotEmpty()
            val info = mutableListOf<String>()

            ultima.await()?.let { u ->
                val w = u.n("weight") ?: return@let
                val quando = u.n("timestamp")?.let {
                    " (" + Instant.ofEpochMilli(it.toLong()).atZone(zona).format(DateTimeFormatter.ofPattern("dd/MM")) + ")"
                } ?: ""
                info += "Ultimo peso: " + kg(w) + quando
            }
            ema.await()?.let { m ->
                val giorno = (m.s("day") ?: "").take(10)
                info += "Media mobile: " + kg(m.n("ema_weight")!!) + (if (giorno != o) " (" + dataBreve(giorno) + ")" else "")
            }

            val fine = ob?.s("end_date")?.take(10)
            val diPesata = if (ob != null) giornoDiPesata(ob, o) && (fine == null || o <= fine) else true
            if (ob != null) {
                val prossima = if (diPesata && !pesato) prossimaPesata(ob, o) else prossimaPesata(ob, oggi.plusDays(1).toString())
                if (prossima != null) {
                    val n = giorniFra(o, prossima)
                    val fra = when (n) { 0L -> "oggi"; 1L -> "domani"; else -> "fra $n giorni" }
                    val target = pesoPianoAl(prossima, ob)
                    info += "Prossima pesata: " + dataBreve(prossima) + " ($fra)" + (target?.let { " · target " + kg(it) } ?: "")
                }
            }

            val tgt = conto.await()?.o("giorni")?.o(o)?.o("target")
            if (tgt != null && tgt.b("ok")) {
                tgt.n("kcal")?.let { info += "Calorie di oggi: " + String.format(ITA, "%,d", it.roundToLong()) + " kcal" }
            }

            info += if (pesato) "✅ Pesato oggi" else if (diPesata) "❌ Non ancora pesato oggi" else "➖ Oggi non è giorno di pesata"

            val inizio = ob?.s("start_date")?.take(10)
            val compito = if (ob != null && diPesata && !pesato && (inizio == null || o >= inizio)) {
                listOf(RecapVoce("⚖️ Peso", "Pesata di oggi" + (ob.s("objective_name")?.let { " — $it" } ?: ""), false, Route.PESO, o))
            } else emptyList()
            info to compito
        }
    } catch (e: Exception) {
        Log.w(TAG, "peso: ${e.message}")
        emptyList<String>() to emptyList()
    }

    // ── 🔔 Promemoria: accorpati per TITOLO + GIORNO LOCALE ──
    // Gemello di recapPromemoria in index.html: la stessa occorrenza su due canali
    // e lo stesso promemoria a più orari dello stesso giorno sono un box solo.
    // Il giorno è locale e non UTC, o un promemoria dell'01:59 cadrebbe il giorno prima.

    private suspend fun promemoria(riservati: Set<String>): List<RecapPromemoria> {
        val visti = LinkedHashMap<String, RecapPromemoria>()
        val primoGiorno = HashMap<String, String>()
        for (r in righe("cm_notification_queue", "app,entity_id,title,fire_at,channel") {
            filter { isIn("status", listOf("pending", "snoozed")) }
            order("fire_at", Order.ASCENDING)
            limit(500)
        }) {
            val fire = r.s("fire_at") ?: ""
            val titolo = r.s("title")?.takeIf { it.isNotEmpty() } ?: "Promemoria"
            val locale = runCatching {
                OffsetDateTime.parse(fire.replace(' ', 'T')).atZoneSameInstant(ZoneId.systemDefault())
            }.getOrNull()
            val giorno = locale?.toLocalDate()?.toString() ?: fire.take(10)
            val ora = locale?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""
            // Della stessa entità solo la PROSSIMA scadenza (le righe arrivano
            // in ordine di fire_at): gemello di recapPromemoria() nel web, v1.15.2.
            val entita = (r.s("app") ?: "") + "|" + (r.s("entity_id") ?: titolo)
            val primo = primoGiorno.getOrPut(entita) { giorno }
            if (primo != giorno) continue
            val k = "$titolo|$giorno"
            val canale = r.s("channel") ?: ""
            val nascosto = (r.s("entity_id") ?: "") in riservati
            val c = visti[k]
            if (c != null) {
                visti[k] = c.copy(
                    canali = if (canale in c.canali) c.canali else c.canali + canale,
                    orari = if (ora.isEmpty() || ora in c.orari) c.orari else c.orari + ora,
                    riservata = c.riservata || nascosto,
                )
                continue
            }
            // fireAt resta il primo istante (le righe arrivano in ordine).
            visti[k] = RecapPromemoria(titolo, fire, giorno, if (ora.isEmpty()) emptyList() else listOf(ora), listOf(canale), nascosto)
        }
        return visti.values.toList()
    }
}

// ── Il dialogo ─────────────────────────────────────────────────────────────

private val VerdeSfondo = Color(0xFFECFDF5)
private val VerdeTesto = Color(0xFF065F46)
private val VerdeBordo = Color(0xFFA7F3D0)
private val BluSfondo = Color(0xFFEFF6FF)
private val BluTesto = Color(0xFF1E3A8A)
private val BluBordo = Color(0xFFBFDBFE)
private val RosaSfondo = Color(0xFFFFF1F3)
private val RosaTesto = Color(0xFF9B1C3A)
private val RosaBordo = Color(0xFFF7B9C4)
private val GrigioSfondo = Color(0xFFF9FAFB)
private val GrigioTesto = Color(0xFF374151)
private val GrigioBordo = Color(0xFFE5E7EB)

@Composable
fun DialogoRecap(
    dati: DatiRecap?,
    caricamento: Boolean,
    modalitaNascosta: Boolean,
    tolti: Set<String>,
    onTogli: (String) -> Unit,
    onRimetti: () -> Unit,
    onApri: (String) -> Unit,
    onChiudi: () -> Unit,
) {
    Dialog(onDismissRequest = onChiudi, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(18.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    "📋 Recap di " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", ITA)),
                    fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Palette.dark,
                    modifier = Modifier.padding(end = 32.dp),
                )
                Icon(
                    Icons.Default.Close, contentDescription = "Chiudi recap", tint = Palette.muted,
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp).clickable(onClick = onChiudi),
                )
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    dati == null && caricamento -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(color = Palette.topBar)
                    }
                    dati == null -> Vuoto("Non sono riuscito a leggere i dati. Riprova fra poco.")
                    else -> CorpoRecap(dati, modalitaNascosta, tolti, onTogli, onRimetti, onApri)
                }
            }
        }
    }
}

@Composable
private fun CorpoRecap(
    dati: DatiRecap,
    modalitaNascosta: Boolean,
    tolti: Set<String>,
    onTogli: (String) -> Unit,
    onRimetti: () -> Unit,
    onApri: (String) -> Unit,
) {
    val oggi = LocalDate.now().toString()
    val scad = dati.scadenze.filter { (modalitaNascosta || !it.riservata) && it.chiave !in tolti }
    val diOggi = scad.filter { it.giorno == oggi }
    val scadute = scad.filter { it.giorno < oggi }
    val prom = dati.promemoria.filter { (modalitaNascosta || !it.riservata) && it.chiave !in tolti }

    // Un blocco tolto o vuoto non si disegna, e una sezione senza blocchi
    // perde anche il titolo: gemello di disegnaRecap() nel web (v1.15.1).
    val finanza = dati.finanza.takeIf { "finanza" !in tolti }
    val peso = dati.peso.takeIf { "peso" !in tolti }.orEmpty()
    val info = finanza != null || peso.isNotEmpty()
    val compiti = diOggi.isNotEmpty() || scadute.isNotEmpty()
    var primo = true

    // ℹ️ Info
    if (info) {
        TitoloSezione("ℹ️ Info", primo = primo); primo = false
        if (finanza != null) {
            Sotto("💶 Finanza")
            BoxRecap("finanza", onTogli, VerdeSfondo, VerdeBordo, { onApri(Route.FINANZA) }) {
                Text(finanza, color = VerdeTesto, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        if (peso.isNotEmpty()) {
            Sotto("⚖️ Peso e Calorie")
            // Un box solo: le righe una sotto l'altra, un tocco apre il peso.
            BoxRecap("peso", onTogli, VerdeSfondo, VerdeBordo, { onApri(Route.PESO) }) {
                peso.forEach { Text(it, color = VerdeTesto, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }

    // ✅ Compiti: Oggi e Scadute, sempre separate.
    if (compiti) {
        TitoloSezione("✅ Compiti", primo = primo); primo = false
        if (diOggi.isNotEmpty()) {
            Sotto("📅 Oggi · ${diOggi.size}")
            Gruppi(diOggi, false, onTogli, onApri)
        }
        if (scadute.isNotEmpty()) {
            Sotto("⚠️ Scadute · ${scadute.size}")
            Gruppi(scadute, true, onTogli, onApri)
        }
    }

    // 🔔 Promemoria
    if (prom.isNotEmpty()) {
        TitoloSezione("🔔 Promemoria aperti · ${prom.size}", primo = primo); primo = false
        val icona = mapOf("telegram" to "📱", "android" to "📲", "smart_block" to "🔐")
        val zona = ZoneId.systemDefault()
        prom.forEach { p ->
            val quando = runCatching {
                OffsetDateTime.parse(p.fireAt.replace(' ', 'T')).atZoneSameInstant(zona).format(DateTimeFormatter.ofPattern("dd/MM"))
            }.getOrDefault("") + " " + p.orari.joinToString(" · ")
            BoxRecap(p.chiave, onTogli, GrigioSfondo, GrigioBordo, null) {
                Text(quando + " " + p.canali.joinToString("") { icona[it] ?: "" }, color = GrigioTesto, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(p.titolo, color = GrigioTesto, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }

    if (!info && !compiti && prom.isEmpty()) Vuoto("Niente da mostrare oggi.")

    // Un trascinamento per sbaglio non deve costare il box fino a domani.
    if (tolti.isNotEmpty()) {
        Text(
            "↺ Rimetti i box tolti oggi (${tolti.size})",
            color = Palette.accent, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp).clickable(onClick = onRimetti),
        )
    }
}

@Composable
private fun Gruppi(voci: List<RecapVoce>, scadute: Boolean, onTogli: (String) -> Unit, onApri: (String) -> Unit) {
    // Raggruppate per app nell'ordine in cui le fonti le danno, come recapGruppi().
    voci.groupBy { it.app }.forEach { (app, lista) ->
        val ordinata = if (scadute) lista.sortedBy { it.giorno } else lista.sortedBy { it.ora.ifEmpty { "99" } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(app, fontWeight = FontWeight.ExtraBold, color = GrigioTesto, fontSize = 14.sp)
            Text(
                " ${lista.size} ",
                fontSize = 12.sp, color = GrigioTesto,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(GrigioBordo).padding(horizontal = 4.dp),
            )
        }
        ordinata.forEach { v ->
            val (sf, bo, tx) = if (scadute) Triple(RosaSfondo, RosaBordo, RosaTesto) else Triple(BluSfondo, BluBordo, BluTesto)
            BoxRecap(v.chiave, onTogli, sf, bo, v.route?.let { r -> { onApri(r) } }) {
                val quando = if (scadute) "dal " + dataBreve(v.giorno) else v.ora
                if (quando.isNotEmpty()) Text(quando, color = tx, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(v.titolo, color = tx, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TitoloSezione(testo: String, primo: Boolean = false) {
    if (!primo) HorizontalDivider(Modifier.padding(top = 14.dp), thickness = 2.dp, color = GrigioBordo)
    Text(testo.uppercase(ITA), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color(0xFF111111),
        modifier = Modifier.padding(top = if (primo) 0.dp else 8.dp))
}

@Composable
private fun Sotto(testo: String) {
    Text(testo, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color(0xFF111111), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Vuoto(testo: String) {
    Text(testo, color = Palette.muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp))
}

/**
 * Un box del recap: si tocca per aprire l'app (se c'è) e si trascina verso
 * destra per toglierlo fino a domani. Oltre circa un terzo della larghezza al
 * rilascio esce; prima torna al suo posto. Il trascinamento orizzontale non
 * contende niente allo scorrimento verticale del dialogo.
 */
@Composable
private fun BoxRecap(
    chiave: String,
    onTogli: (String) -> Unit,
    sfondo: Color,
    bordo: Color,
    onClick: (() -> Unit)?,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dx = remember(chiave) { Animatable(0f) }
    var larghezza by remember { mutableIntStateOf(1) }
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { larghezza = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = dx.value
                alpha = (1f - dx.value / (larghezza * 0.9f)).coerceIn(0.2f, 1f)
            }
            .pointerInput(chiave) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dx.value > min(120.dp.toPx(), larghezza * 0.35f)) {
                                dx.animateTo(larghezza * 1.1f, tween(200))
                                onTogli(chiave)
                            } else dx.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f) } },
                ) { change, quanto ->
                    change.consume()
                    scope.launch { dx.snapTo((dx.value + quanto).coerceAtLeast(0f)) }
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .background(sfondo)
            .border(2.dp, bordo, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = contenuto,
    )
}
