package com.garsal.appsphere.home

import android.util.Log
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class CmApp(
    val title: String = "",
    val description: String? = null,
    @SerialName("score_query") val scoreQuery: String? = null,
    @SerialName("html_file") val htmlFile: String? = null,
    val color: String? = null,
    val riservato: Boolean? = null,
    /** Il numero della `score_query` è un punteggio? Vedi [Bolla.contaPunti]. */
    @SerialName("conta_punti") val contaPunti: Boolean? = null,
)

@Serializable
private data class CmSetting(val value: String? = null)

/** Una bolla pronta da disegnare: il dato del DB più la rotta del registro. */
data class Bolla(
    val htmlFile: String,
    val nome: String,
    val descrizione: String,
    val punteggio: Int,
    val colore: String,
    val riservata: Boolean,
    val route: String,
    /**
     * Il numero è un punteggio, e quindi si scrive sotto il nome e si somma al
     * totale che paga i premi. Lo dice `cm_apps.conta_punti`
     * (`20260924130000_cm_apps_conta_punti.sql`); fino alla v1.0.91 era
     * l'elenco `AppSenzaPunti`, scritto a mano qui, in index.html e in
     * backup-report.mjs. ⚠️ Conta solo un `true` esplicito: vedi
     * [HomeRepository.carica].
     */
    val contaPunti: Boolean = false,
)

data class Avviso(val testo: String, val route: String)

@Serializable
private data class PvNome(val name: String = "", val archived: Boolean? = null, val emoji: String? = null)

@Serializable
private data class PvAzioneAvviso(
    val title: String = "",
    @SerialName("next_occurrence_date") val nextOccurrenceDate: String? = null,
    @SerialName("pv_plants") val pianta: PvNome? = null,
    @SerialName("pv_groups") val gruppo: PvNome? = null,
)

/**
 * Quello che serve per disegnare la home: le bolle e il punteggio **lordo**.
 *
 * I due numeri non coincidono, ed è voluto: le bolle sono le sole app portate
 * in nativo, il lordo è la somma dei punteggi di **tutte** le app attive, come
 * il pannello del web. Con quella cifra si comprano i premi, che costano lo
 * stesso da qui e dall'app WebView: sommare solo le cinque bolle darebbe un
 * saldo più basso a seconda di che APK si è aperto.
 *
 * Attive sì, ma non tutte: il numero di alcune app è un conteggio e non un
 * punteggio, e quelle restano fuori dal totale — l'elenco è in
 * [Bolla.contaPunti].
 */
data class DatiHome(
    val bolle: List<Bolla>,
    val totaleLordo: Int,
    /**
     * La somma dei numeri delle app **riservate**, quelle che si vedono solo in
     * modalità nascosta. Sta fuori da [totaleLordo] e non paga nessun premio:
     * oggi sono tutti conteggi — le transazioni di Finanza, i file del
     * Forziere — e un conteggio sommato ai punti è un saldo che nessuno può
     * rifare a mano.
     *
     * ⚠️ **La divisione è per VISIBILITÀ e non per «sono punti o no»**: quello
     * che si sta separando è il numero delle app che si vedono sempre da
     * quello delle app che compaiono col codice a colori. Se un giorno
     * un'app riservata portasse punti veri, quei punti starebbero qui e non
     * nel totale — così il saldo spendibile non cambia più a seconda che la
     * modalità nascosta sia accesa o spenta, che è il difetto per cui questo
     * riquadro esiste.
     */
    val totaleRiservate: Int,
    /**
     * Quante app riservate ci sono. Serve solo a decidere se disegnare il
     * riquadro: senza nessuna riservata, un box che dice 0 è rumore.
     */
    val appRiservate: Int,
)

@Serializable
private data class SpSettingsAvviso(
    val goal: String = "",
    val emoji: String = "🎯",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    @SerialName("skip_weekend") val skipWeekend: Boolean = false,
)

@Serializable
private data class SpGiorno(val day: String = "", val label: String? = null)

@Serializable
private data class SfSfidaAvviso(
    val title: String = "",
    @SerialName("checkin_time") val checkinTime: String = "",
)

object HomeRepository {

    private const val TAG = "AppSphereHome"

    /**
     * La sequenza di colori che accende la modalità nascosta, dalla stessa riga
     * di `cm_settings` che legge `loadHiddenSequence()` nel launcher: `value` è
     * un array JSON di colori esadecimali, uno per bolla da toccare.
     *
     * Torna vuota se la riga non c'è o non si legge — e con la sequenza vuota
     * il codice non si può indovinare, esattamente come sul web, dove
     * `checkSequence()` esce subito se non l'ha caricata.
     */
    suspend fun sequenzaNascosta(): List<String> = withContext(Dispatchers.IO) {
        try {
            val righe = Supabase.client().postgrest
                .from("cm_settings")
                .select(Columns.raw("value")) {
                    filter { eq("key", "hidden_mode_sequence") }
                    limit(1)
                }
                .decodeList<CmSetting>()
            val grezzo = righe.firstOrNull()?.value ?: return@withContext emptyList()
            Json.parseToJsonElement(grezzo).jsonArray.mapNotNull {
                (it as? JsonPrimitive)?.content?.trim()?.takeIf { c -> c.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sequenza della modalità nascosta non disponibile: ${e.message}")
            emptyList()
        }
    }

    /**
     * Le app da mostrare, con il punteggio, e il totale lordo.
     *
     * Stessa lettura di `loadApps()` in index.html — `cm_apps` attive, ordinate
     * per id, punteggio dalla RPC `run_score_query` con l'SQL scritto nella
     * riga — con in più l'incrocio col registro delle app portate.
     *
     * ⚠️ **Il punteggio si calcola per tutte le app attive, non solo per quelle
     * portate**: le altre non hanno una bolla da disegnare qui, ma i loro punti
     * entrano nel totale in basso esattamente come sul web, ed è quel totale
     * che paga i premi. Sono una manciata di RPC in più, tutte in parallelo.
     * Nel totale entrano però i soli numeri che **sono** punti: quali lo dice
     * `cm_apps.conta_punti`, la stessa colonna che legge `index.html`.
     * ⚠️ Solo un `true` esplicito conta: una riga senza la colonna (migration
     * non ancora applicata) dà un totale più basso, mai più alto.
     *
     * Il filtro su `riservato` è fatto qui e non nella query: le righe in gioco
     * sono poche, e la pagina web ha già la stessa strada come ripiego per
     * quando la colonna non c'è.
     */
    suspend fun carica(modalitaNascosta: Boolean): DatiHome = withContext(Dispatchers.IO) {
        val righe = Supabase.client().postgrest
            .from("cm_apps")
            .select(
                Columns.raw("title,description,score_query,html_file,color,riservato,conta_punti")
            ) {
                filter { eq("active", true) }
                order("id", Order.ASCENDING)
            }
            .decodeList<CmApp>()

        val visibili = righe.filter { modalitaNascosta || it.riservato != true }

        val punteggi = coroutineScope {
            visibili.map { riga -> async { punteggio(riga.scoreQuery) } }.awaitAll()
        }

        // Le bolle nell'ordine di `cm_apps`, come sul web.
        val dalDatabase = visibili.mapIndexedNotNull { i, riga ->
            val portata = PortedApps.perHtmlFile[riga.htmlFile] ?: return@mapIndexedNotNull null
            Bolla(
                htmlFile = riga.htmlFile ?: return@mapIndexedNotNull null,
                nome = riga.title.takeIf { it.isNotBlank() } ?: portata.titoloDiRipiego,
                descrizione = riga.description?.takeIf { it.isNotBlank() }
                    ?: portata.descrizioneDiRipiego,
                punteggio = punteggi[i],
                colore = riga.color?.takeIf { it.isNotBlank() } ?: portata.coloreDiRipiego,
                riservata = riga.riservato == true,
                route = portata.route,
                contaPunti = riga.contaPunti == true,
            )
        }

        // Le app portate che in `cm_apps` non hanno una riga (events-log.html
        // non compare in nessuna migration): si disegnano coi valori di
        // ripiego invece di sparire in silenzio. Il confronto è su `righe` e
        // non su `visibili`, o una riservata rispunterebbe da qui a modalità
        // nascosta spenta.
        val conRiga = righe.mapNotNull { it.htmlFile }.toSet()
        val senzaRiga = PortedApps.perHtmlFile
            .filterKeys { it !in conRiga }
            .map { (file, portata) ->
                Bolla(
                    htmlFile = file,
                    nome = portata.titoloDiRipiego,
                    descrizione = portata.descrizioneDiRipiego,
                    punteggio = 0,
                    colore = portata.coloreDiRipiego,
                    riservata = false,
                    route = portata.route,
                )
            }

        // ⚠️ Il lordo somma **solo i numeri che sono punti**: quello di
        // Spuntiamola sono i giorni che mancano, quello di Obiettivi gli
        // obiettivi attivi (vedi [Bolla.contaPunti]). Sommarli darebbe un saldo
        // che cala spuntando un giorno, cioè premi che vanno e vengono da sé.
        //
        // ⚠️ E somma le sole app **sempre visibili**: quelle riservate hanno
        // il loro riquadro (vedi [DatiHome.totaleRiservate]). Fuori dalla
        // modalità nascosta `visibili` non ne contiene nessuna, quindi il
        // filtro non toglie niente a nessuno e il totale resta quello di
        // sempre — cambia solo che adesso non si gonfia più accendendo il
        // codice a colori.
        val lordo = visibili.indices.sumOf { i ->
            if (visibili[i].riservato != true && visibili[i].contaPunti == true)
                punteggi[i] else 0
        }

        val lordoRiservate = visibili.indices.sumOf { i ->
            if (visibili[i].riservato == true) punteggi[i] else 0
        }

        DatiHome(
            bolle = dalDatabase + senzaRiga,
            totaleLordo = lordo,
            totaleRiservate = lordoRiservate,
            appRiservate = visibili.count { it.riservato == true },
        )
    }

    /**
     * `run_score_query` è blindata su un solo account
     * (`20260611160000_rosa_readonly_access.sql`): per chiunque altro la
     * chiamata fallisce. Non è un errore da mostrare — il web fa lo stesso e
     * lascia la bolla al minimo — quindi si torna 0 e si annota nel log.
     */
    private suspend fun punteggio(sql: String?): Int {
        if (sql.isNullOrBlank()) return 0
        return try {
            // decodeAs pretende un tipo non nullable: se la funzione tornasse
            // null il decode lancia e si finisce nel catch qui sotto, che è
            // esattamente l'esito voluto (punteggio 0).
            Supabase.client().postgrest
                .rpc("run_score_query", buildJsonObject { put("query", sql) })
                .decodeAs<Int>()
        } catch (e: Exception) {
            Log.w(TAG, "punteggio non calcolato: ${e.message}")
            0
        }
    }

    /**
     * Gli avvisi della home.
     *
     * Sul web ne convergono sei (decisioni, task urgenti, totale portafogli,
     * Ta Firi, Spuntiamola, abitudini). Qui ce ne sono tre, Spuntiamola, Ta
     * Firi? più le azioni delle Piante, che sono quelle che portano a una schermata che esiste: un avviso
     * che non apre niente è peggio di nessun avviso. Le altre si aggiungono
     * quando le rispettive app diventano native.
     */
    suspend fun avvisi(): List<Avviso> = withContext(Dispatchers.IO) {
        listOfNotNull(avvisoSpuntiamola()) + avvisiTaFiri() + avvisiPiante()
    }

    /**
     * Le azioni di cura delle Piante da fare oggi più quelle rimaste indietro
     * (⚠️), come `loadHomeAlertPiante` in `index.html`: le prossime non
     * entrano, e le «quando capita» restano fuori perché non hanno una data.
     * ⚠️ Le azioni di una pianta **archiviata** non si mostrano; quelle di un
     * gruppo sì, col nome del gruppo al posto della pianta. Il giorno si legge
     * in ora locale, o un'azione di mezzanotte finirebbe fra le arretrate.
     */
    private suspend fun avvisiPiante(): List<Avviso> = try {
        val zona = ZoneId.systemDefault()
        val oggi = LocalDate.now(zona)
        val domani = oggi.plusDays(1).atStartOfDay(zona).toInstant().toString()
        Supabase.client().postgrest
            .from("pv_actions")
            .select(Columns.raw("title,next_occurrence_date,pv_plants(name,archived),pv_groups(name,emoji)")) {
                filter {
                    neq("status", "terminated")
                    lt("next_occurrence_date", domani)
                }
                order("next_occurrence_date", Order.ASCENDING)
            }
            .decodeList<PvAzioneAvviso>()
            .filter { it.pianta?.archived != true }
            .map { a ->
                val giorno = a.nextOccurrenceDate
                    ?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zona).toLocalDate() }.getOrNull() }
                    ?: oggi
                val di = a.pianta?.name ?: a.gruppo?.let { g -> listOfNotNull(g.emoji, g.name).joinToString(" ") }
                val testo = (if (giorno < oggi) "⚠️ " else "") + "🌱 " + a.title + (di?.let { " — $it" } ?: "")
                Avviso(testo, Route.PIANTE)
            }
    } catch (e: Exception) {
        Log.w(TAG, "avvisi Piante non disponibili: ${e.message}")
        emptyList()
    }

    /**
     * Le sfide di Ta Firi? in corso oggi, una riga per sfida col suo orario di
     * check-in — le stesse righe e lo stesso testo di `loadHomeAlertChallenges`
     * in `index.html`.
     *
     * Come nel web l'avviso compare **anche se la sfida di oggi è già
     * spuntata**: qui è un promemoria di cosa c'è in ballo, e la domanda vera
     * («l'hai fatta?») la fa il banner dentro l'app.
     */
    private suspend fun avvisiTaFiri(): List<Avviso> = try {
        val oggi = LocalDate.now().toString()
        Supabase.client().postgrest
            .from("sf_challenges")
            .select(Columns.raw("title,checkin_time")) {
                filter {
                    eq("status", "active")
                    lte("start_date", oggi)
                    gte("end_date", oggi)
                }
                order("checkin_time", Order.ASCENDING)
            }
            .decodeList<SfSfidaAvviso>()
            .map { Avviso("${it.title} · ${it.checkinTime.take(5)}", Route.TA_FIRI) }
    } catch (e: Exception) {
        Log.w(TAG, "avvisi Ta Firi? non disponibili: ${e.message}")
        emptyList()
    }

    private suspend fun avvisoSpuntiamola(): Avviso? = try {
        val db = Supabase.client().postgrest
        // Giorno locale, non UTC: alle undici di sera `toISOString()` darebbe
        // già il giorno dopo e l'avviso sparirebbe con la spunta ancora da fare.
        val oggi = LocalDate.now()
        val oggiStr = oggi.toString()

        val impostazioni = db.from("sp_settings")
            .select(Columns.raw("goal,emoji,start_date,end_date,skip_weekend")) {
                limit(1)
            }
            .decodeList<SpSettingsAvviso>()
            .firstOrNull()

        val fuoriPeriodo = impostazioni == null ||
            oggiStr < impostazioni.startDate ||
            oggiStr > impostazioni.endDate
        val weekendSaltato = impostazioni?.skipWeekend == true &&
            (oggi.dayOfWeek == DayOfWeek.SATURDAY || oggi.dayOfWeek == DayOfWeek.SUNDAY)

        when {
            impostazioni == null || fuoriPeriodo || weekendSaltato -> null
            else -> {
                val giaSpuntato = db.from("sp_checks")
                    .select(Columns.raw("day")) {
                        filter { eq("day", oggiStr) }
                        limit(1)
                    }
                    .decodeList<SpGiorno>()
                    .isNotEmpty()

                if (giaSpuntato) null else {
                    val chiave = db.from("sp_key_days")
                        .select(Columns.raw("day,label")) {
                            filter { eq("day", oggiStr) }
                            limit(1)
                        }
                        .decodeList<SpGiorno>()
                        .firstOrNull()

                    val testo = if (chiave != null) {
                        val etichetta = chiave.label?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                        "⭐ Oggi è una giornata chiave$etichetta e non l'hai spuntata!"
                    } else {
                        "Oggi non l'hai ancora spuntato — ${impostazioni.emoji} ${impostazioni.goal}"
                    }
                    Avviso(testo, Route.SPUNTIAMOLA)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "avviso Spuntiamola non disponibile: ${e.message}")
        null
    }
}
