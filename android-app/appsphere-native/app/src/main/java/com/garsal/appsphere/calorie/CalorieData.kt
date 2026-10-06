package com.garsal.appsphere.calorie

import android.util.Log
import com.garsal.appsphere.core.Supabase
import com.garsal.appsphere.peso.Obiettivo
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/* ═══════════════════════════════════════════════════════════════════════
   I PASTI
   ═══════════════════════════════════════════════════════════════════════ */

/** Un momento della giornata in cui si mangia. `tolto` = spento, ma quel giorno ha delle righe. */
data class Pasto(
    val id: String,
    val etichetta: String,
    val oraFino: Int,
    val tolto: Boolean = false,
)

/**
 * I sei momenti della giornata, in ordine di orario.
 *
 * ⚠️ Gli `id` sono **fissi** e non si inventano: `al_log.meal` ha un CHECK con
 * esattamente questi sei valori, e una riga con un id diverso viene rifiutata
 * dal database. Quello che si configura — da `calorie.html` → ⚙️ Impostazioni,
 * che qui non c'è — è **quali** dei sei si usano e **come** si chiamano, non
 * l'insieme dei valori possibili. Per un settimo serve una migration.
 */
object Pasti {

    /** La chiave in `cm_settings`: la stessa configurazione dal PC e dal telefono. */
    const val CHIAVE = "al_pasti"

    val BASE = listOf(
        Pasto("colazione", "🌅 Colazione", 10),
        Pasto("spuntino_mattina", "🍎 Spuntino", 12),
        Pasto("pranzo", "🍝 Pranzo", 15),
        Pasto("spuntino_pomeriggio", "☕ Merenda", 18),
        Pasto("cena", "🌙 Cena", 22),
        Pasto("fuori_pasto", "🍫 Fuori pasto", 24),
    )

    fun base(id: String?): Pasto? = BASE.firstOrNull { it.id == id }

    /** I pasti configurati, in ordine di orario; senza configurazione valgono tutti e sei. */
    fun attivi(configurati: List<Pasto>?): List<Pasto> {
        if (configurati.isNullOrEmpty()) return BASE
        return BASE.mapNotNull { b ->
            configurati.firstOrNull { it.id == b.id }
                ?.let { b.copy(etichetta = it.etichetta.ifBlank { b.etichetta }) }
        }
    }

    /**
     * I pasti da disegnare per un giorno: quelli attivi, **più** quelli spenti
     * che però quel giorno hanno delle righe.
     *
     * ⚠️ Spegnere un pasto non cancella quel che ci si era segnato dentro: le
     * righe restano, contano nel totale e si vedono marcate «pasto tolto» — è
     * la stessa regola della *misura tolta* nei diari di Memo. Farle sparire
     * dallo schermo lasciandole nel conto sarebbe il modo peggiore di
     * nasconderle.
     */
    fun delGiorno(configurati: List<Pasto>?, usati: Set<String>): List<Pasto> {
        val attivi = attivi(configurati)
        return BASE.mapNotNull { b ->
            attivi.firstOrNull { it.id == b.id }
                ?: b.copy(tolto = true).takeIf { usati.contains(b.id) }
        }
    }

    /**
     * Il pasto proposto in base all'ora: si segna quel che si è appena
     * mangiato, e alle 13 è il pranzo nove volte su dieci. Resta cambiabile —
     * è una proposta, non una regola. Fra i soli pasti **attivi**: proporne
     * uno spento vorrebbe dire aprire la finestra su una voce che nella
     * tendina non c'è.
     */
    fun dellOra(configurati: List<Pasto>?, ora: Int): String {
        val attivi = attivi(configurati)
        return (attivi.firstOrNull { ora < it.oraFino } ?: attivi.last()).id
    }

    /**
     * Le voci della tendina. Il pasto già scelto compare **sempre**, anche se
     * nel frattempo è stato spento: aprire una riga vecchia e trovare la
     * tendina su un pasto diverso da quello in cui la riga sta la sposterebbe
     * al primo salvataggio, senza che nessuno l'abbia chiesto.
     */
    fun opzioni(configurati: List<Pasto>?, scelto: String?): List<Pasto> {
        val voci = attivi(configurati)
        if (scelto != null && voci.none { it.id == scelto }) {
            base(scelto)?.let { return voci + it.copy(etichetta = it.etichetta + " (pasto tolto)") }
        }
        return voci
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   LE RIGHE
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * Un alimento: una riga di `al_foods` (`id` valorizzato) **oppure** un
 * prodotto appena arrivato dalla rete (`id` nullo), che ha la stessa forma
 * perché la normalizzazione la fa una volta sola la Edge Function
 * `al-food-search`.
 *
 * I valori sono **sempre per 100**, in archivio come in rete: per 100 g su un
 * solido, per 100 ml su un liquido, che è quel che dice `unit`.
 */
@Serializable
data class Alimento(
    val id: String? = null,
    val name: String = "",
    val brand: String? = null,
    val barcode: String? = null,
    val source: String = "manuale",
    @SerialName("kcal_100g") val kcal: Double? = null,
    @SerialName("proteins_100g") val proteine: Double? = null,
    @SerialName("fat_100g") val grassi: Double? = null,
    @SerialName("sat_fat_100g") val saturi: Double? = null,
    @SerialName("carbs_100g") val carboidrati: Double? = null,
    @SerialName("sugars_100g") val zuccheri: Double? = null,
    @SerialName("fiber_100g") val fibre: Double? = null,
    @SerialName("salt_100g") val sale: Double? = null,
    @SerialName("default_grams") val grammiPorzione: Double? = null,
    @SerialName("portion_label") val nomePorzione: String? = null,
    @SerialName("portion_label_plural") val nomePorzioniPlurale: String? = null,
    val favorite: Boolean = false,
    @SerialName("times_used") val volteUsato: Int = 0,
    val verified: Boolean = false,
    /**
     * ⚠️ `"g"` o `"ml"`: su che base sono scritti i valori qui sopra — 100 g per
     * un solido, 100 ml per un liquido, che è come li scrive l'etichetta. **Non
     * è una densità e non converte niente**: il conto resta quantità × valore /
     * 100. Si sceglie da 🍎 Alimenti nella pagina, che di qua non c'è: qui si
     * legge soltanto, come i pasti e il fattore di attività.
     */
    val unit: String = "g",
    /** Solo per i risultati di rete: la pezzatura scritta sulla confezione. */
    val quantita: String? = null,
) {
    /** L'unità, letta da un posto solo: quel che non è "ml" sono grammi. */
    val misura: String get() = if (unit == "ml") "ml" else "g"

    /** Vero se sta già nel catalogo: è un dato tuo, già guardato e correggibile. */
    val inCatalogo: Boolean get() = id != null

    /**
     * ⚠️ Le fonti che il CHECK di `al_foods.source` ammette per un prodotto
     * arrivato dalla rete. È lo specchio del vincolo e va tenuto uguale: una
     * fonte che il database non ammette non entra in archivio, e l'insert lo
     * rifiuta il database. La costante decide **insieme** il messaggio e il
     * salvataggio — due condizioni scritte separatamente sono la finestra che
     * promette una cosa e il pulsante che ne fa un'altra.
     */
    val entraInCatalogo: Boolean get() = !inCatalogo && source in FONTI_SALVABILI

    companion object {
        val FONTI_SALVABILI = setOf("off", "usda")
    }
}

/** Una riga del diario: i valori per 100 (g o ml) sono **congelati sulla riga**. */
@Serializable
data class RigaDiario(
    val id: String,
    val day: String,
    val meal: String,
    @SerialName("food_id") val alimentoId: String? = null,
    val name: String = "",
    val brand: String? = null,
    val grams: Double = 0.0,
    @SerialName("kcal_100g") val kcal: Double? = null,
    @SerialName("proteins_100g") val proteine: Double? = null,
    @SerialName("fat_100g") val grassi: Double? = null,
    @SerialName("sat_fat_100g") val saturi: Double? = null,
    @SerialName("carbs_100g") val carboidrati: Double? = null,
    @SerialName("sugars_100g") val zuccheri: Double? = null,
    @SerialName("fiber_100g") val fibre: Double? = null,
    @SerialName("salt_100g") val sale: Double? = null,
    /**
     * ⚠️ Congelata sulla riga come i valori nutrizionali, e non letta
     * dall'alimento: cambiare un alimento da ml a g — o cancellarlo, che porta
     * `food_id` a NULL — riscriverebbe all'indietro l'etichetta di righe già
     * segnate, o gliela toglierebbe del tutto.
     */
    val unit: String = "g",
) {
    /** L'unità, letta da un posto solo: quel che non è "ml" sono grammi. */
    val misura: String get() = if (unit == "ml") "ml" else "g"

    /** Il valore di un nutriente per la quantità di **questa** riga. */
    private fun per(valore: Double?): Double? = valore?.let { it * grams / 100.0 }

    val kcalRiga: Double get() = per(kcal) ?: 0.0
    val proteineRiga: Double get() = per(proteine) ?: 0.0
    val grassiRiga: Double get() = per(grassi) ?: 0.0
    val saturiRiga: Double get() = per(saturi) ?: 0.0
    val carboidratiRiga: Double get() = per(carboidrati) ?: 0.0
    val zuccheriRiga: Double get() = per(zuccheri) ?: 0.0
    val fibreRiga: Double get() = per(fibre) ?: 0.0
    val saleRiga: Double get() = per(sale) ?: 0.0
}

/**
 * Il profilo che serve al conto: i dati anagrafici da `cm_profile`, il fattore
 * di attività da `al_profile`.
 *
 * ⚠️ **I dati anagrafici stanno in `cm_profile` e da qui si leggono soltanto.**
 * Chiederli di nuovo in ogni app che ne ha bisogno vuol dire due altezze
 * diverse il giorno che una delle due si corregge, e nessun modo di sapere
 * quale sia quella giusta. Si compilano da AppSphere → ☰ → 👤 Profilo (sul web:
 * `/#profilo`); `al_profile.activity` resta di là perché è una scelta di
 * Calorie e in `cm_profile` non c'è — e si cambia in ⚙️ Impostazioni, che è
 * una delle due pagine rimaste sul web.
 */
data class ProfiloCalorie(
    val dataNascita: String? = null,
    val altezzaCm: Double? = null,
    val sesso: String? = null,
    val attivita: Double = 1.375,
) {
    val completo: Boolean
        get() = !dataNascita.isNullOrBlank() && altezzaCm != null && !sesso.isNullOrBlank()
}

/* ═══════════════════════════════════════════════════════════════════════
   IL DATABASE
   ═══════════════════════════════════════════════════════════════════════ */

/** Quanto indietro si guarda: la stessa finestra di `GIORNI_STORICO` nella pagina. */
const val GIORNI_STORICO = 120L

private const val TAG = "AppSphereCalorie"

private const val COLONNE_ALIMENTO =
    "id,name,brand,barcode,source,kcal_100g,proteins_100g,fat_100g,sat_fat_100g," +
        "carbs_100g,sugars_100g,fiber_100g,salt_100g,default_grams," +
        "portion_label,portion_label_plural,favorite,times_used,verified,unit"

private const val COLONNE_RIGA =
    "id,day,meal,food_id,name,brand,grams,kcal_100g,proteins_100g,fat_100g," +
        "sat_fat_100g,carbs_100g,sugars_100g,fiber_100g,salt_100g,unit"

/** I nutrienti che si copiano **sulla riga** del diario, in un posto solo. */
private fun nutrientiDi(a: Alimento): Map<String, Double?> = mapOf(
    "kcal_100g" to a.kcal,
    "proteins_100g" to a.proteine,
    "fat_100g" to a.grassi,
    "sat_fat_100g" to a.saturi,
    "carbs_100g" to a.carboidrati,
    "sugars_100g" to a.zuccheri,
    "fiber_100g" to a.fibre,
    "salt_100g" to a.sale,
)

object CalorieRepository {

    private val db get() = Supabase.client().postgrest

    // ── Lettura ─────────────────────────────────────────────────────────

    /**
     * Il profilo, da **due righe**: `cm_profile` per anagrafica e `al_profile`
     * per il solo `activity`. Se `cm_profile` manca il profilo resta
     * incompleto e la pagina lo dice, invece di inventare un basale.
     *
     * `cm_profile` non sta in nessuna migration: si legge come `JsonObject` e
     * non come data class serializzata, la stessa scelta di `ts_tasks` e per la
     * stessa ragione — una colonna del tipo inatteso darebbe la schermata
     * vuota invece di un campo storto.
     */
    suspend fun profilo(): ProfiloCalorie = withContext(Dispatchers.IO) {
        val comune = runCatching {
            db.from("cm_profile")
                .select(Columns.raw("data_nascita,altezza_cm,sesso")) { limit(1) }
                .decodeList<JsonObject>()
                .firstOrNull()
        }.onFailure { Log.w(TAG, "cm_profile non letto: ${it.message}") }.getOrNull()

        val mio = runCatching {
            db.from("al_profile")
                .select(Columns.raw("activity")) { limit(1) }
                .decodeList<JsonObject>()
                .firstOrNull()
        }.onFailure { Log.w(TAG, "al_profile non letto: ${it.message}") }.getOrNull()

        ProfiloCalorie(
            dataNascita = comune?.let { stringa(it, "data_nascita") },
            altezzaCm = comune?.let { numero(it, "altezza_cm") },
            sesso = comune?.let { stringa(it, "sesso") },
            attivita = mio?.let { numero(it, "activity") } ?: 1.375,
        )
    }

    /** Il catalogo, nell'ordine della pagina: preferiti, più usati, poi per nome. */
    suspend fun alimenti(): List<Alimento> = withContext(Dispatchers.IO) {
        db.from("al_foods")
            .select(Columns.raw(COLONNE_ALIMENTO)) {
                order("favorite", Order.DESCENDING)
                order("times_used", Order.DESCENDING)
                order("name", Order.ASCENDING)
                limit(2000L)
            }
            .decodeList<Alimento>()
    }

    suspend fun righe(da: LocalDate): List<RigaDiario> = withContext(Dispatchers.IO) {
        db.from("al_log")
            .select(Columns.raw(COLONNE_RIGA)) {
                filter { gte("day", da.toString()) }
                order("day", Order.DESCENDING)
                order("created_at", Order.ASCENDING)
                limit(5000L)
            }
            .decodeList<RigaDiario>()
    }

    /**
     * L'ultimo obiettivo **attivo** di «Ti pisasti?»: è quello su cui si sta
     * lavorando adesso. Gli obiettivi chiusi restano in archivio ma non devono
     * dettare il target di oggi.
     *
     * La riga si decodifica con [Obiettivo.da] del modulo `peso`, che è già
     * l'unico posto in cui `ps_objectives` e le sue milestone si leggono: una
     * seconda copia sarebbe due letture diverse della stessa curva.
     */
    suspend fun obiettivoAttivo(): Obiettivo? = withContext(Dispatchers.IO) {
        db.from("ps_objectives")
            .select(Columns.ALL) {
                filter { eq("status", "active") }
                order("created_at", Order.DESCENDING)
                limit(1)
            }
            .decodeList<JsonObject>()
            .firstNotNullOfOrNull { Obiettivo.da(it) }
    }

    /**
     * I pasti configurati, da `cm_settings`. Si scartano gli id che non sono
     * fra i sei di [Pasti.BASE]: un valore scritto a mano finirebbe in
     * `al_log.meal` e il CHECK del database rifiuterebbe la riga — cioè lo si
     * scoprirebbe salvando, non aprendo. Una configurazione vuota o illeggibile
     * vale come «non configurato», e allora si usano tutti e sei.
     */
    suspend fun pasti(): List<Pasto>? = withContext(Dispatchers.IO) {
        try {
            val riga = db.from("cm_settings")
                .select(Columns.raw("value")) {
                    filter { eq("key", Pasti.CHIAVE) }
                    limit(1)
                }
                .decodeList<JsonObject>()
                .firstOrNull() ?: return@withContext null
            val grezzo = stringa(riga, "value") ?: return@withContext null
            val buoni = Json.parseToJsonElement(grezzo).jsonArray.mapNotNull { voce ->
                val o = voce as? JsonObject ?: return@mapNotNull null
                val base = Pasti.base(stringa(o, "id")) ?: return@mapNotNull null
                base.copy(etichetta = stringa(o, "label")?.trim().orEmpty().ifBlank { base.etichetta })
            }
            buoni.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "pasti non configurabili: ${e.message}")
            null
        }
    }

    // ── Scrittura ───────────────────────────────────────────────────────

    /**
     * Il conto delle calorie di tutta la finestra — `al_conto`, la stessa RPC
     * che chiama `weight-quest.html`. È lei a leggersi pesate, profilo,
     * obiettivo e giorni congelati.
     */
    suspend fun conto(oggi: LocalDate): CalorieRegole.Conto = withContext(Dispatchers.IO) {
        CalorieRegole.Conto.da(
            db.rpc("al_conto", buildJsonObject { put("p_oggi", oggi.toString()) }).decodeAs<JsonObject>()
        )
    }

    /**
     * Congela il target del giorno la prima volta che ci si scrive qualcosa.
     *
     * ⚠️ La riga di `al_days` la scrive `al_congela_giorno` nel database, che
     * non la tocca se c'è già: il numero congelato nasce dalla stessa regola
     * che lo mostra. Un target non congelato non impedisce di segnare quel che
     * si è mangiato: la riga del diario è il dato, il target è il commento.
     */
    suspend fun congelaGiorno(giorno: String) = withContext(Dispatchers.IO) {
        runCatching {
            db.rpc("al_congela_giorno", buildJsonObject { put("p_giorno", giorno) })
        }.onFailure { Log.w(TAG, "congelaGiorno: ${it.message}") }
        Unit
    }

    /**
     * Ricalcola il target di **un** giorno, su richiesta esplicita
     * (`al_ricalcola_giorno`). Torna il target nuovo, o quello col motivo per
     * cui non si può.
     */
    suspend fun ricalcolaGiorno(giorno: String): CalorieRegole.Target = withContext(Dispatchers.IO) {
        CalorieRegole.Conto.target(
            db.rpc("al_ricalcola_giorno", buildJsonObject { put("p_giorno", giorno) }).decodeAs<JsonObject>()
        )
    }

    /**
     * Aggiunge una riga al diario. I valori per 100 si copiano **sulla
     * riga**: correggere domani le calorie di un alimento non deve riscrivere
     * quel che si è mangiato oggi, e cancellarlo non fa sparire le calorie già
     * contate (`food_id` va a NULL, la riga resta).
     */
    suspend fun aggiungiAlDiario(
        giorno: String,
        pasto: String,
        alimento: Alimento,
        grammi: Double,
        alimentoId: String?,
    ) = withContext(Dispatchers.IO) {
        val riga = buildJsonObject {
            put("day", giorno)
            put("meal", pasto)
            put("food_id", alimentoId)
            put("name", alimento.name)
            put("brand", alimento.brand)
            put("grams", grammi)
            // ⚠️ L'unità si congela con i valori: senza, cambiare l'alimento da
            // ml a g riscriverebbe all'indietro righe già segnate.
            put("unit", alimento.misura)
            nutrientiDi(alimento).forEach { (chiave, valore) -> put(chiave, valore) }
        }
        db.from("al_log").insert(riga)

        // `times_used` ordina i «più usati» nella ricerca: si alza solo quando
        // l'alimento viene davvero mangiato.
        if (alimentoId != null) {
            runCatching {
                db.from("al_foods").update(
                    buildJsonObject { put("times_used", volteUsato(alimentoId) + 1) }
                ) { filter { eq("id", alimentoId) } }
            }
        }
        Unit
    }

    private suspend fun volteUsato(id: String): Int =
        runCatching {
            db.from("al_foods")
                .select(Columns.raw("times_used")) { filter { eq("id", id) } }
                .decodeList<JsonObject>()
                .firstOrNull()
                ?.let { numero(it, "times_used")?.toInt() }
        }.getOrNull() ?: 0

    suspend fun cambiaGrammi(id: String, grammi: Double) = withContext(Dispatchers.IO) {
        db.from("al_log").update(buildJsonObject { put("grams", grammi) }) {
            filter { eq("id", id) }
        }
        Unit
    }

    suspend fun eliminaRiga(id: String) = withContext(Dispatchers.IO) {
        db.from("al_log").delete { filter { eq("id", id) } }
        Unit
    }

    /**
     * Ricopia in un giorno tutte le righe del giorno prima.
     *
     * ⚠️ Si copiano i valori **congelati sulla riga di partenza**, non quelli
     * di `al_foods` di adesso: la riga è il dato, l'alimento è solo da dove
     * veniva. Così la copia funziona anche per un alimento cancellato nel
     * frattempo, e non cambia di nascosto perché qualcuno ha corretto una
     * scheda. `times_used` non si tocca: è il contatore che ordina i «più
     * usati», e un tocco che lo alza di cinque lo farebbe diventare la
     * classifica di chi ricopia invece di chi mangia.
     */
    suspend fun ricopia(giorno: String, righe: List<RigaDiario>) = withContext(Dispatchers.IO) {
        if (righe.isEmpty()) return@withContext
        val nuove = righe.map { r ->
            buildJsonObject {
                put("day", giorno)
                put("meal", r.meal)
                put("food_id", r.alimentoId)
                put("name", r.name)
                put("brand", r.brand)
                put("grams", r.grams)
                put("unit", r.misura)
                put("kcal_100g", r.kcal)
                put("proteins_100g", r.proteine)
                put("fat_100g", r.grassi)
                put("sat_fat_100g", r.saturi)
                put("carbs_100g", r.carboidrati)
                put("sugars_100g", r.zuccheri)
                put("fiber_100g", r.fibre)
                put("salt_100g", r.sale)
            }
        }
        db.from("al_log").insert(nuove)
        Unit
    }

    /**
     * Salva nel catalogo un alimento trovato in rete, così la seconda volta è
     * già lì. Se il codice a barre c'è già si aggiorna quella riga: lo stesso
     * prodotto non deve comparire due volte nell'elenco.
     *
     * ⚠️ La fonte si archivia **com'è arrivata** e non riscritta a `'off'`: un
     * prodotto USDA dichiarato Open Food Facts sarebbe un dato falso proprio
     * nella colonna che esiste per dire da dove viene un numero.
     */
    suspend fun salvaAlimentoDiRete(a: Alimento, esistente: Alimento?): String? =
        withContext(Dispatchers.IO) {
            val riga = buildJsonObject {
                put("name", a.name)
                put("brand", a.brand)
                put("barcode", a.barcode)
                put("source", a.source)
                nutrientiDi(a).forEach { (chiave, valore) -> put(chiave, valore) }
                put("default_grams", a.grammiPorzione)
                // L'unità arriva dalla Edge Function, che l'ha letta dalla
                // confezione: mezzo litro entra in catalogo già in ml.
                put("unit", a.misura)
                put("updated_at", java.time.Instant.now().toString())
            }
            if (esistente?.id != null) {
                db.from("al_foods").update(riga) { filter { eq("id", esistente.id) } }
                esistente.id
            } else {
                db.from("al_foods").insert(riga) { select(Columns.raw("id")) }
                    .decodeList<JsonObject>()
                    .firstOrNull()
                    ?.let { stringa(it, "id") }
            }
        }

    // ── La rete ─────────────────────────────────────────────────────────

    /**
     * Cerca un alimento nelle banche dati pubbliche.
     *
     * ⚠️ **La pagina non parla con le banche dati: passa tutto dalla Edge
     * Function `al-food-search`**, e qui vale identico. La normalizzazione vive
     * solo di là — due implementazioni della stessa conversione sono due valori
     * diversi per lo stesso prodotto il giorno che una delle due cambia — e
     * ogni fonte torna col suo esito, così «il servizio è giù» e «quel prodotto
     * non c'è» smettono di sembrare la stessa cosa.
     *
     * Il corpo si scrive e si rilegge a mano come testo JSON: non dipende dalla
     * negoziazione del contenuto di Ktor, che qui non abbiamo configurato noi.
     */
    suspend fun cercaInRete(testoCercato: String): RisultatoRete = withContext(Dispatchers.IO) {
        val richiesta = buildJsonObject { put("q", testoCercato) }
        val risposta = Supabase.client().functions.invoke("al-food-search") {
            contentType(ContentType.Application.Json)
            setBody(richiesta.toString())
        }
        val corpo = Json.parseToJsonElement(risposta.bodyAsText()).jsonObject
        stringa(corpo, "error")?.let { error(it) }

        val alimenti = (corpo["alimenti"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { voce ->
                runCatching { jsonPermissivo.decodeFromJsonElement(Alimento.serializer(), voce) }
                    .getOrNull()
                    ?.takeIf { it.name.isNotBlank() && it.kcal != null }
            }
            .orEmpty()

        RisultatoRete(alimenti = alimenti, esiti = riassuntoEsiti(corpo))
    }

    /**
     * Il riassunto in una riga di cosa hanno risposto le fonti: è quel che si
     * mostra quando una ricerca torna vuota, al posto di un «niente trovato»
     * che non spiega niente.
     */
    private fun riassuntoEsiti(corpo: JsonObject): String {
        val esiti = corpo["esiti"] as? kotlinx.serialization.json.JsonArray ?: return ""
        return esiti.mapNotNull { voce ->
            val o = voce as? JsonObject ?: return@mapNotNull null
            val grezza = stringa(o, "fonte") ?: "?"
            val fonte = if (grezza.startsWith("openfoodfacts/")) "OFF " + grezza.removePrefix("openfoodfacts/") else grezza
            when {
                stringa(o, "errore") != null -> "$fonte: non raggiungibile"
                (o["ok"] as? JsonPrimitive)?.content == "false" -> "$fonte: HTTP ${numero(o, "http")?.toInt()}"
                else -> "$fonte: ${numero(o, "trovati")?.toInt() ?: 0} risultati"
            }
        }.joinToString(" · ")
    }

    private val jsonPermissivo = Json { ignoreUnknownKeys = true; isLenient = true }
}

/** Quel che una ricerca in rete ha prodotto, e cosa hanno risposto le fonti. */
data class RisultatoRete(val alimenti: List<Alimento>, val esiti: String)

// ── Lettura dei campi JSON ───────────────────────────────────────────────

private fun campo(o: JsonObject, chiave: String) = o[chiave]?.takeIf { it !is JsonNull }

internal fun stringa(o: JsonObject, chiave: String): String? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

internal fun numero(o: JsonObject, chiave: String): Double? =
    (campo(o, chiave) as? JsonPrimitive)?.content?.toDoubleOrNull()
