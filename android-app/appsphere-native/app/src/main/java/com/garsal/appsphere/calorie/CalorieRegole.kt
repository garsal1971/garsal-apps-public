package com.garsal.appsphere.calorie

import com.garsal.appsphere.peso.Pesata
import com.garsal.appsphere.peso.PesoRegole
import com.garsal.appsphere.peso.Traguardo
import com.garsal.appsphere.peso.Obiettivo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Il conto delle calorie **letto**, non fatto.
 *
 * ✅ Dall'APK 1.0.94 il conto non si fa più qui: basale Mifflin-St Jeor, deficit
 * a due addendi, peso di piano, tratti del piano, target congelato e giorno per
 * giorno col saldo spalmato li danno le RPC `al_conto` / `al_congela_giorno` /
 * `al_ricalcola_giorno` (`20260925140000_al_conto_calorie_rpc.sql`), le stesse
 * che chiama `weight-quest.html`. Fino alla 1.0.93 erano ricalcati qui funzione
 * per funzione, e un giorno congelato poteva valere due numeri diversi a
 * seconda di quale app lo apriva per prima. **Una modifica a quelle regole si
 * fa nel SQL e basta.**
 *
 * Qui restano i tipi — la forma di quel che arriva — e la lettura di [Conto].
 */
object CalorieRegole {

    /**
     * Un chilo di grasso corporeo vale circa 7700 kcal. Serve ormai solo a
     * **mostrare** il saldo in chili: il conto lo fa il database.
     */
    const val KCAL_PER_KG = 7700.0

    // ── Date ────────────────────────────────────────────────────────────

    fun giorno(iso: String?): LocalDate? = PesoRegole.giornoDa(iso)

    fun piuGiorni(quando: String, n: Long): String =
        (giorno(quando) ?: LocalDate.now()).plusDays(n).toString()

    fun giorniFra(da: String, a: String): Long {
        val x = giorno(da) ?: return 0
        val y = giorno(a) ?: return 0
        return y.toEpochDay() - x.toEpochDay()
    }

    /**
     * Il peso da **mostrare** per un giorno: il minimo dell'ultimo giorno
     * pesato fino a quello — la stessa regola di `al_peso_al` nel database.
     */
    fun pesoAl(pesate: List<Pesata>, quando: String): Double? {
        val ultimo = pesate.filter { it.giorno <= quando }.maxOfOrNull { it.giorno } ?: return null
        return pesate.filter { it.giorno == ultimo }.minOf { it.peso }
    }

    /** Il peso che il piano chiede per una data — la curva di «Ti pisasti?». */
    fun pesoPianoAl(traguardi: List<Traguardo>, quando: String): Double? =
        PesoRegole.targetInterpolato(traguardi, quando)

    // ── Le forme di quel che arriva dal database ────────────────────────

    /** Il tratto del piano che contiene un giorno: le due milestone e il ritmo. */
    data class Segmento(
        val inizio: String,
        val fine: String,
        val pesoInizio: Double,
        val pesoFine: Double,
        val giorni: Long,
        val numero: Int,
        val quanti: Int,
        /** Positivo = si deve perdere. Il ritmo del **tratto**, non della media. */
        val kgAlGiorno: Double,
    )

    /** Una riga della tabella dei tratti: quanto chiederà ciascun periodo del piano. */
    data class Tratto(
        val numero: Int,
        val inizio: String,
        val fine: String,
        val giorni: Long,
        val pesoInizio: Double,
        val pesoFine: Double,
        val kg: Double,
        val kgAlGiorno: Double,
        val bmr: Double?,
        val tdee: Double?,
        val deficit: Double,
        val target: Double?,
        val corrente: Boolean,
        val passato: Boolean,
    )

    /**
     * Perché il target è quello che è — e perché non c'è, quando non c'è. «Non
     * lo so» e «zero» sono due cose diverse: un target a zero farebbe sembrare
     * sforata ogni giornata.
     */
    enum class Motivo { PROFILO, PESO, MANTENIMENTO, OBIETTIVO, RAGGIUNTO, CONGELATO, FUORI }

    data class Target(
        val ok: Boolean = false,
        val motivo: Motivo? = null,
        val kcal: Double? = null,
        val bmr: Double? = null,
        val tdee: Double? = null,
        val deficit: Double = 0.0,
        val peso: Double? = null,
        val pesoPiano: Double? = null,
        val pesoFinale: Double? = null,
        val giorniRimasti: Long? = null,
        val kgDaPerdere: Double? = null,
        val congelato: Boolean = false,
        val avvisi: List<String> = emptyList(),
        /**
         * Il deficit nei suoi due addendi: il ritmo che il tratto di piano
         * chiede e il recupero dello scarto accumulato. Servono a schermo,
         * perché un numero solo non direbbe se oggi è più stretto perché il
         * piano corre o perché si è rimasti indietro.
         */
        val segmento: Segmento? = null,
        val deficitPiano: Double? = null,
        val scartoKg: Double? = null,
        val recupero: Double? = null,
    )

    data class Arco(
        val primo: String,
        val ultimo: String,
        val primoVero: String,
        val tagliato: Boolean,
    )

    /** Una giornata della dieta, passata o futura. */
    data class GiornoDieta(
        val giorno: String,
        val kcal: Double?,
        val target: Double?,
        val scarto: Double?,
        val futuro: Boolean,
        val oggi: Boolean,
        val riporto: Double?,
        val peso: Double?,
        val pesoPiano: Double?,
        val righe: Int,
    )

    data class Piano(
        val giorni: List<GiornoDieta> = emptyList(),
        val saldo: Double = 0.0,
        val restanti: Int = 0,
        val alGiorno: Double = 0.0,
    )

    /** Un giorno della finestra: il conto di adesso, quello da mostrare e il target del tratto. */
    data class GiornoConto(val calcolato: Target, val target: Target, val tratto: Double?)

    /**
     * Tutto quel che `al_conto` restituisce: il conto di ogni giorno della
     * finestra (120 giorni indietro, due mesi avanti), i tratti e il piano.
     */
    data class Conto(
        val giorni: Map<String, GiornoConto> = emptyMap(),
        val tratti: List<Tratto> = emptyList(),
        val arco: Arco? = null,
        val piano: Piano = Piano(),
    ) {
        /** Il conto di adesso (`calcolaTarget`); fuori dalla finestra non si inventa. */
        fun calcolato(quando: String): Target = giorni[quando]?.calcolato ?: Target(motivo = Motivo.FUORI)

        /** Il target da mostrare: congelato se c'è (`targetDelGiorno`). */
        fun target(quando: String): Target = giorni[quando]?.target ?: Target(motivo = Motivo.FUORI)

        /** Il target «se stai sul piano» del tratto che contiene il giorno. */
        fun tratto(quando: String): Double? = giorni[quando]?.tratto

        companion object {
            fun da(o: JsonObject): Conto {
                val giorni = (o["giorni"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                    val g = v as? JsonObject ?: JsonObject(emptyMap())
                    GiornoConto(
                        calcolato = target(g["calcolato"] as? JsonObject),
                        target = target(g["target"] as? JsonObject),
                        tratto = num(g["tratto"]),
                    )
                }
                val tratti = (o["tratti"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val t = e as? JsonObject ?: return@mapNotNull null
                    Tratto(
                        numero = int(t["numero"]) ?: 0, inizio = str(t["inizio"]).orEmpty(),
                        fine = str(t["fine"]).orEmpty(), giorni = (num(t["giorni"]) ?: 0.0).toLong(),
                        pesoInizio = num(t["pesoInizio"]) ?: 0.0, pesoFine = num(t["pesoFine"]) ?: 0.0,
                        kg = num(t["kg"]) ?: 0.0, kgAlGiorno = num(t["kgAlGiorno"]) ?: 0.0,
                        bmr = num(t["bmr"]), tdee = num(t["tdee"]), deficit = num(t["deficit"]) ?: 0.0,
                        target = num(t["target"]),
                        corrente = bool(t["corrente"]), passato = bool(t["passato"]),
                    )
                }
                val piano = o["piano"] as? JsonObject
                val arco = (piano?.get("arco") as? JsonObject)?.let {
                    Arco(
                        primo = str(it["primo"]).orEmpty(), ultimo = str(it["ultimo"]).orEmpty(),
                        primoVero = str(it["primoVero"]).orEmpty(), tagliato = bool(it["tagliato"]),
                    )
                }
                val righe = (piano?.get("righe") as? JsonArray).orEmpty().mapNotNull { e ->
                    val r = e as? JsonObject ?: return@mapNotNull null
                    GiornoDieta(
                        giorno = str(r["giorno"]).orEmpty(), kcal = num(r["kcal"]),
                        target = num(r["target"]), scarto = num(r["scarto"]),
                        futuro = bool(r["futuro"]), oggi = bool(r["oggi"]),
                        riporto = num(r["riporto"]), peso = num(r["peso"]),
                        pesoPiano = num(r["pesoPiano"]), righe = int(r["righe"]) ?: 0,
                    )
                }
                return Conto(
                    giorni = giorni, tratti = tratti, arco = arco,
                    piano = if (arco == null) Piano() else Piano(
                        giorni = righe, saldo = num(piano?.get("saldo")) ?: 0.0,
                        restanti = int(piano?.get("restanti")) ?: 0, alGiorno = num(piano?.get("alGiorno")) ?: 0.0,
                    ),
                )
            }

            /** Un target come lo scrive il database (`al_calcola_target_ctx`). */
            fun target(o: JsonObject?): Target {
                if (o == null) return Target(motivo = Motivo.FUORI)
                val seg = (o["segmento"] as? JsonObject)?.let {
                    Segmento(
                        inizio = str(it["inizio"]).orEmpty(), fine = str(it["fine"]).orEmpty(),
                        pesoInizio = num(it["pesoInizio"]) ?: 0.0, pesoFine = num(it["pesoFine"]) ?: 0.0,
                        giorni = (num(it["giorni"]) ?: 0.0).toLong(), numero = int(it["numero"]) ?: 0,
                        quanti = int(it["quanti"]) ?: 0, kgAlGiorno = num(it["kgAlGiorno"]) ?: 0.0,
                    )
                }
                val motivo = when (str(o["motivo"])) {
                    "profilo" -> Motivo.PROFILO
                    "peso" -> Motivo.PESO
                    "mantenimento" -> Motivo.MANTENIMENTO
                    "obiettivo" -> Motivo.OBIETTIVO
                    "raggiunto" -> Motivo.RAGGIUNTO
                    "congelato" -> Motivo.CONGELATO
                    else -> Motivo.FUORI
                }
                val t = Target(
                    ok = bool(o["ok"]), motivo = motivo, kcal = num(o["kcal"]), bmr = num(o["bmr"]),
                    tdee = num(o["tdee"]), deficit = num(o["deficit"]) ?: 0.0, peso = num(o["peso"]),
                    pesoPiano = num(o["pesoPiano"]), pesoFinale = num(o["pesoFinale"]),
                    giorniRimasti = num(o["giorniRimasti"])?.toLong(), kgDaPerdere = num(o["kgDaPerdere"]),
                    congelato = bool(o["congelato"]),
                    segmento = seg, deficitPiano = num(o["deficitPiano"]),
                    scartoKg = num(o["scartoKg"]), recupero = num(o["recupero"]),
                )
                return t.copy(avvisi = testiAvviso(t, o["avviso"] as? JsonObject))
            }

            /** Il testo degli avvisi: codice e soglia dal database, le parole qui (gemelle di `testiAvviso()` nel web). */
            private fun testiAvviso(t: Target, a: JsonObject?): List<String> = when (str(a?.get("codice"))) {
                "negativo" -> listOf(
                    "Il piano chiede più di quanto il corpo consuma in un giorno: il target esce " +
                        "negativo (${kcalIt(t.kcal)} kcal). In ${t.giorniRimasti} " +
                        (if (t.giorniRimasti == 1L) "giorno" else "giorni") +
                        " quei ${kgIt(t.kgDaPerdere)} kg non si perdono — la data di fine va spostata in «Ti pisasti?»."
                )
                "sotto_soglia" -> listOf(
                    "Target sotto le ${kcalIt(num(a?.get("soglia")))} kcal: è poco, e quasi sempre vuol dire che i " +
                        "traguardi sono troppo ravvicinati. Il numero non è stato ritoccato — se lo vuoi più " +
                        "largo, allarga il piano in «Ti pisasti?»."
                )
                "deficit_alto" -> listOf(
                    "Il piano chiede un deficit di ${kcalIt(t.deficit)} kcal al giorno, cioè oltre un " +
                        "chilo a settimana. È tanto: se non regge, sposta la data di fine invece di saltare i pasti."
                )
                else -> emptyList()
            }

            private fun prim(e: JsonElement?): JsonPrimitive? =
                (e as? JsonPrimitive)?.takeIf { e !is JsonNull }
            private fun num(e: JsonElement?): Double? = prim(e)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
            private fun int(e: JsonElement?): Int? = num(e)?.roundToInt()
            private fun str(e: JsonElement?): String? = prim(e)?.content?.takeIf { it.isNotBlank() }
            private fun bool(e: JsonElement?): Boolean = prim(e)?.booleanOrNull ?: false
        }
    }

    /**
     * Il giorno più indietro a cui il diario si può spostare: il **primo giorno
     * della dieta**, mai prima della finestra di caricamento.
     *
     * Il `max` non è prudenza: senza, dal 121° giorno indietro ogni giornata
     * comparirebbe vuota — non perché lo sia, ma perché le sue righe non sono
     * state lette. Un archivio che sembra svuotarsi da solo è peggio di un
     * pulsante spento. Senza obiettivo attivo resta la sola finestra, che è
     * tutto quel che si sa.
     */
    fun primoGiornoDiario(obiettivo: Obiettivo?, oggi: String): String {
        val finestra = piuGiorni(oggi, -GIORNI_STORICO)
        val inizio = obiettivo?.inizio?.take(10)?.takeIf { it.isNotBlank() }
        return if (inizio != null && inizio > finestra) inizio else finestra
    }

    // ── I totali di una giornata ────────────────────────────────────────

    data class Totali(
        val kcal: Double = 0.0,
        val proteine: Double = 0.0,
        val grassi: Double = 0.0,
        val saturi: Double = 0.0,
        val carboidrati: Double = 0.0,
        val zuccheri: Double = 0.0,
        val fibre: Double = 0.0,
        val sale: Double = 0.0,
    )

    fun totali(righe: List<RigaDiario>) = Totali(
        kcal = righe.sumOf { it.kcalRiga },
        proteine = righe.sumOf { it.proteineRiga },
        grassi = righe.sumOf { it.grassiRiga },
        saturi = righe.sumOf { it.saturiRiga },
        carboidrati = righe.sumOf { it.carboidratiRiga },
        zuccheri = righe.sumOf { it.zuccheriRiga },
        fibre = righe.sumOf { it.fibreRiga },
        sale = righe.sumOf { it.saleRiga },
    )
}

/* ── Come si scrivono i numeri ─────────────────────────────────────────── */

/** Le calorie: intere, coi separatori all'italiana; `—` quando non ci sono. */
internal fun kcalIt(valore: Double?): String =
    valore?.let { String.format(java.util.Locale.ITALY, "%,d", it.roundToInt()) } ?: "—"

/** Un peso o dei grammi: un decimale, virgola italiana. */
internal fun kgIt(valore: Double?): String =
    valore?.let { String.format(java.util.Locale.ITALY, "%.1f", it) } ?: "—"

/**
 * Il ritmo di un tratto va a **due** decimali: a uno solo, 0,12 kg a settimana
 * diventa «0,1» e un tratto lento è indistinguibile da un tratto fermo.
 */
internal fun ritmoIt(valore: Double?): String =
    valore?.let { String.format(java.util.Locale.ITALY, "%.2f", it) } ?: "—"

/** `2026-08-31` → `31/08`, per le tabelle strette. */
internal fun dataBreve(iso: String): String {
    val pezzi = iso.take(10).split("-")
    return if (pezzi.size == 3) "${pezzi[2]}/${pezzi[1]}" else iso
}

/** `2026-08-31` → `lun 31 agosto`, come l'intestazione del diario nella pagina. */
internal fun dataLunga(iso: String): String {
    val data = CalorieRegole.giorno(iso) ?: return iso
    val giorni = listOf("lun", "mar", "mer", "gio", "ven", "sab", "dom")
    val mesi = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
    )
    return "${giorni[data.dayOfWeek.value - 1]} ${data.dayOfMonth} ${mesi[data.monthValue - 1]}"
}

/** Il segno davanti a uno scarto: `+` di troppo, `−` in meno. */
internal fun conSegno(valore: Double): String =
    (if (valore > 0) "+" else if (valore < 0) "−" else "") + kcalIt(abs(valore))

internal fun percentuale(parte: Double, totale: Double): Double =
    if (totale <= 0) 0.0 else min(100.0, parte / totale * 100.0)
