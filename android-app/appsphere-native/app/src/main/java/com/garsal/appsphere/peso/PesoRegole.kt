package com.garsal.appsphere.peso

import java.time.LocalDate

/**
 * Le regole di Weight Quest, ricalcate da `weight-quest.html`.
 *
 * ⚠️ **Sono la funzionalità, non un dettaglio di presentazione**: il target di
 * un giorno, i punti che quel giorno vale e il cumulativo devono dare lo stesso
 * numero nelle due app, o la stessa giornata risulta guadagnata da una parte e
 * persa dall'altra. Se cambia una di queste funzioni, cambia anche la gemella
 * in `weight-quest.html` (`getInterpolatedTarget`,
 * `getInterpolatedWeightFromSeries`, `updateWeightTable`).
 *
 * ✅ **I punti e la chiusura NON sono più qui** (APK 1.0.93): il conto giorno
 * per giorno, i traguardi raggiunti e il punteggio di chiusura li danno le RPC
 * `ps_punti` / `ps_chiudi_obiettivo` (`20260925120000_ps_punti_rpc.sql`), le
 * stesse che chiama il web. Una modifica a quelle regole si fa nel SQL e basta.
 */
object PesoRegole {

    /**
     * Il target del giorno, interpolato fra i traguardi — `getInterpolatedTarget`.
     *
     * Con meno di due traguardi non c'è nessuna curva e il target non esiste:
     * si torna `null`, che a schermo diventa un trattino. Prima del primo
     * traguardo e dopo l'ultimo vale il valore estremo, senza estrapolare.
     */
    fun targetInterpolato(traguardi: List<Traguardo>, giorno: String): Double? {
        if (traguardi.size < 2) return null
        val data = giornoDa(giorno) ?: return null
        val primo = traguardi.first()
        val ultimo = traguardi.last()
        val dataPrimo = giornoDa(primo.giorno) ?: return null
        val dataUltimo = giornoDa(ultimo.giorno) ?: return null

        if (data.isBefore(dataPrimo)) return primo.peso
        if (data.isAfter(dataUltimo)) return ultimo.peso

        for (i in 0 until traguardi.size - 1) {
            val a = traguardi[i]
            val b = traguardi[i + 1]
            val da = giornoDa(a.giorno) ?: continue
            val ab = giornoDa(b.giorno) ?: continue
            if (!data.isBefore(da) && !data.isAfter(ab)) {
                val giorniTotali = (ab.toEpochDay() - da.toEpochDay()).toDouble()
                if (giorniTotali <= 0.0) return a.peso
                val trascorsi = (data.toEpochDay() - da.toEpochDay()).toDouble()
                val quota = trascorsi / giorniTotali
                return arrotonda(a.peso + (b.peso - a.peso) * quota, 2)
            }
        }
        return null
    }

    /**
     * Il peso di un giorno **senza pesate**, interpolato fra la pesata prima e
     * quella dopo — `getInterpolatedWeightFromSeries`. Non è il target: è la
     * stima di quanto si pesava quel giorno, e serve per non lasciare buchi nel
     * conto dei punti quando ci si dimentica di salire sulla bilancia.
     *
     * Fuori dalla serie non si estrapola: prima della prima pesata vale la
     * prima, dopo l'ultima vale l'ultima.
     */
    fun pesoInterpolato(minimi: List<Pair<String, Double>>, giorno: String): Double? {
        if (minimi.isEmpty()) return null
        if (minimi.size == 1) return minimi.first().second

        var prima: Pair<String, Double>? = null
        var dopo: Pair<String, Double>? = null
        for (m in minimi) {
            if (m.first < giorno) prima = m
            else if (m.first > giorno && dopo == null) { dopo = m; break }
        }

        return when {
            prima != null && dopo != null -> {
                val da = giornoDa(prima.first) ?: return prima.second
                val a = giornoDa(dopo.first) ?: return prima.second
                val totale = (a.toEpochDay() - da.toEpochDay()).toDouble()
                if (totale <= 0.0) return prima.second
                val data = giornoDa(giorno) ?: return prima.second
                val quota = (data.toEpochDay() - da.toEpochDay()) / totale
                arrotonda(prima.second + (dopo.second - prima.second) * quota, 2)
            }
            prima != null -> prima.second
            else -> dopo?.second
        }
    }

    /**
     * I punti di una giornata: bonus se si è sotto (o pari) al target, malus
     * se si è sopra. **Il confronto si fa a un decimale**, come nel web
     * (`Math.round(x * 10) / 10`): 74,04 contro un target di 74,0 è una
     * giornata vinta, e con un confronto a piena precisione sarebbe persa.
     */
    fun punti(peso: Double, target: Double, bonus: Int, malus: Int): Int =
        if (arrotonda(peso, 1) <= arrotonda(target, 1)) bonus else -malus

    // ── La massa grassa ─────────────────────────────────────────────────
    //
    // ⚠️ Gemelle di `massaMagra()` / `aGrasso()` / `massaGrassa()` /
    // `pesiVista()` / `notaTotale()` in `weight-quest.html`, e di
    // `ps_massa_magra` / `ps_grasso_previsto` nel database (che fanno i punti):
    // cambiandone una vanno cambiate tutte. I traguardi si scrivono in peso
    // TOTALE; `fat_pct` è la % al peso del primo traguardo, da lì la massa magra
    // che resta FERMA, e il grasso previsto a un peso W è W − massa magra.

    /** La massa magra dell'obiettivo; `null` se non conta sulla massa grassa. */
    fun massaMagra(obiettivo: Obiettivo?): Double? {
        if (obiettivo == null) return null
        return massaMagra(
            obiettivo.usaGrasso,
            obiettivo.percGrasso,
            obiettivo.traguardi.firstOrNull()?.peso?.takeIf { it > 0.0 }
                ?: obiettivo.pesoIniziale?.takeIf { it > 0.0 }
                ?: obiettivo.pesoFinale,
        )
    }

    /** La stessa, dai campi del form ancora da salvare. */
    fun massaMagra(usaGrasso: Boolean, perc: Double?, pesoPrimoTraguardo: Double?): Double? {
        if (!usaGrasso) return null
        val p = perc ?: return null
        if (p <= 0.0 || p >= 100.0) return null
        val w = pesoPrimoTraguardo?.takeIf { it > 0.0 } ?: return null
        return arrotonda(w * (1 - p / 100.0), 2)
    }

    /** Un peso totale (target) letto come l'obiettivo lo vede: in grasso se serve. */
    fun aGrasso(valore: Double?, obiettivo: Obiettivo?): Double? {
        if (valore == null) return null
        val m = massaMagra(obiettivo) ?: return valore
        return arrotonda(maxOf(valore - m, 0.0), 2)
    }

    /** Peso × %grasso: `null` se la pesata il grasso non ce l'ha. */
    fun massaGrassa(p: Pesata): Double? {
        val g = p.grasso ?: return null
        if (g <= 0.0 || g >= 100.0) return null
        return Math.round(p.peso * g) / 100.0
    }

    /**
     * Le pesate come le vede l'obiettivo: su uno sulla massa grassa [Pesata.peso]
     * diventa la massa grassa e il totale finisce in [Pesata.totale]; le pesate
     * senza grasso NON entrano — per i punti è come non essersi pesati, la
     * stessa regola della RPC. ⚠️ Sono COPIE: le righe vere restano quelle del
     * database, perché il dettaglio del giorno e la chiusura parlano di totale.
     */
    fun pesiVista(pesate: List<Pesata>, obiettivo: Obiettivo?): List<Pesata> {
        if (massaMagra(obiettivo) == null) return pesate
        return pesate.mapNotNull { r ->
            val mg = massaGrassa(r) ?: return@mapNotNull null
            r.copy(peso = mg, totale = r.peso, target = aGrasso(r.target, obiettivo))
        }
    }

    /** «totale 92,3 kg · grasso 28 %» sotto una massa grassa; `null` altrove. */
    fun notaTotale(p: Pesata?): String? {
        val t = p?.totale ?: return null
        return "totale ${kg(t)} kg" + (p.grasso?.let { " · grasso ${pct(it)} %" } ?: "")
    }

    /** Il grasso previsto a un traguardo: «grasso 22,4 kg (24,9 %)»; `null` senza massa grassa. */
    fun notaGrassoPrevisto(peso: Double, obiettivo: Obiettivo?): String? =
        notaGrassoPrevisto(peso, massaMagra(obiettivo))

    fun notaGrassoPrevisto(peso: Double, magra: Double?): String? {
        val m = magra ?: return null
        if (peso <= 0.0) return null
        val g = maxOf(peso - m, 0.0)
        return "grasso ${kg(g)} kg (${kg(g / peso * 100.0)} %)"
    }

    /** Sotto un target in grasso: «di 88,0 kg · 25,6 %»; `null` altrove. */
    fun notaTargetTotale(target: Double?, obiettivo: Obiettivo?): String? {
        val m = massaMagra(obiettivo) ?: return null
        if (target == null || target <= 0.0) return null
        val tot = target + m
        return "di ${kg(tot)} kg · ${kg(target / tot * 100.0)} %"
    }

    private fun pct(v: Double): String {
        val r = arrotonda(v, 1)
        return if (r == Math.floor(r)) r.toLong().toString() else kg(r)
    }

    /** Il minimo di ogni giornata, in ordine di data: la pesata del mattino. */
    fun minimiGiornalieri(pesate: List<Pesata>): List<Pair<String, Double>> =
        pesate.groupBy { it.giorno }
            .map { (giorno, righe) -> giorno to righe.minOf { it.peso } }
            .sortedBy { it.first }

    // ── Pesarsi ogni N giorni ───────────────────────────────────────────
    //
    // ⚠️ Gemelle di `ogniQuantiGiorni()`, `giornoDiPesata()`, `prossimaPesata()`
    // e `pesataPrecedente()` in `weight-quest.html`: con N i punti si danno solo
    // nei giorni fissati — inizio, inizio+N, … — e cambiando la regola di qua
    // o di là lo stesso obiettivo darebbe due punteggi.

    /** Il giorno è uno di quelli in cui ci si deve pesare? Senza N, sempre. */
    fun giornoDiPesata(obiettivo: Obiettivo, giorno: String): Boolean {
        val n = obiettivo.ogniGiorni ?: return true
        if (obiettivo.inizio.isBlank() || giorno < obiettivo.inizio) return false
        val a = giornoDa(obiettivo.inizio) ?: return false
        val b = giornoDa(giorno) ?: return false
        return (b.toEpochDay() - a.toEpochDay()) % n == 0L
    }

    /** La prossima pesata dovuta da [dalGiorno] compreso; `null` a piano finito. */
    fun prossimaPesata(obiettivo: Obiettivo, dalGiorno: String): String? {
        if (obiettivo.inizio.isBlank()) return null
        var d = giornoDa(maxOf(dalGiorno, obiettivo.inizio)) ?: return null
        repeat(400) {
            val s = d.toString()
            if (obiettivo.fine.isNotBlank() && s > obiettivo.fine) return null
            if (giornoDiPesata(obiettivo, s)) return s
            d = d.plusDays(1)
        }
        return null
    }

    /** L'ultima pesata dovuta **prima** di [giorno]; `null` prima dell'inizio. */
    fun pesataPrecedente(obiettivo: Obiettivo, giorno: String): String? {
        if (obiettivo.inizio.isBlank()) return null
        var d = giornoDa(giorno) ?: return null
        repeat(400) {
            d = d.minusDays(1)
            val s = d.toString()
            if (s < obiettivo.inizio) return null
            if (giornoDiPesata(obiettivo, s)) return s
        }
        return null
    }

    /** Com'è una riga della tabella — le quattro etichette della pagina. */
    enum class StatoRiga {
        /** Ci si è pesati. */
        PESATA,
        /** Senza N: giorno passato senza pesata, ricostruito per interpolazione. */
        RICOSTRUITO,
        /** Con N: giorno di pesata passato senza pesarsi — vale il malus. */
        NON_PESATO,
        /** Giorno di pesata ancora da venire (oggi compreso, finché non ci si pesa). */
        DA_FARE,
        /**
         * Con N: giorno passato fuori calendario senza nessuna pesata. Ha la sua
         * riga lo stesso, con le medie stimate — web v4.13.2.
         */
        SENZA_PESATA,
    }

    /**
     * Una riga della tabella: una giornata.
     *
     * `interpolata` distingue i giorni in cui si è saliti sulla bilancia da
     * quelli ricostruiti: valgono punti come gli altri — è la scelta del web,
     * e toglierla cambierebbe il punteggio — ma vanno detti, altrimenti sembra
     * che ci sia una pesata che non c'è.
     */
    data class RigaGiorno(
        val giorno: String,
        val target: Double?,
        val minimo: Double?,
        val massimo: Double?,
        val oraMinimo: String?,
        val oraMassimo: String?,
        val punti: Int?,
        val cumulativo: Int?,
        val interpolata: Boolean,
        val stato: StatoRiga = if (interpolata) StatoRiga.RICOSTRUITO else StatoRiga.PESATA,
        /** Una pesata vera in un giorno che il piano (con N) non prevede: niente punti. */
        val fuoriCalendario: Boolean = false,
        /** Sulla massa grassa: «totale X kg · grasso Y %» sotto il minimo e il massimo. */
        val notaMinimo: String? = null,
        val notaMassimo: String? = null,
        /** Sulla massa grassa: «di X kg · Y %» sotto il previsto. */
        val notaTarget: String? = null,
        // ── La riga come la tabella del web v4.13 ───────────────────────
        /** La PRIMA pesata del giorno (peso totale), quella che entra nella media. */
        val primaPeso: Double? = null,
        /** Grasso (kg e %) della prima pesata che il grasso ce l'ha. */
        val primaGrassoKg: Double? = null,
        val primaGrassoPct: Double? = null,
        /** Le medie mobili del giorno (`ps_daily_ema`), se ci sono. */
        val media: MediaGiorno? = null,
        /** Giorno passato senza nessuna pesata: fondo rosa, medie stimate. */
        val senzaPesata: Boolean = false,
        /** Giorno in cui il piano dà punti: fondo azzurro chiaro. */
        val giornoPremio: Boolean = false,
        /** Giorno futuro, o oggi ancora da pesare: fondo bianco. */
        val futuro: Boolean = false,
    )

    /** Una giornata del conto dei punti — una riga di `ps_punti`. */
    data class RigaPunti(
        val giorno: String,
        val ricostruita: Boolean,
        val nonPesato: Boolean,
        val peso: Double?,
        val target: Double?,
        val punti: Int,
        val cumulativo: Int,
    )

    /**
     * La tabella giorno per giorno, dalla più recente — `updateWeightTable`.
     *
     * ⚠️ È il **calendario del piano** (web v4.2.0): una riga per ogni giorno di
     * pesata dall'inizio alla **fine** della dieta — senza N, uno al giorno —
     * col peso previsto. I giorni futuri (e oggi, finché non ci si pesa) sono
     * «da fare»; con N un giorno passato senza pesata è «non pesato»; una
     * pesata vera in un giorno che il piano non prevede compare lo stesso,
     * marcata «fuori calendario» e senza punti. I punti vengono da
     * [conto], cioè dalla RPC `ps_punti`: la stessa regola del web.
     */
    fun tabella(
        pesate: List<Pesata>,
        obiettivo: Obiettivo?,
        conto: List<RigaPunti>,
        oggi: LocalDate = LocalDate.now(),
        /** Le pesate vere (peso totale), per la prima pesata e i giorni senza. */
        tutteLePesate: List<Pesata> = pesate,
        medie: Map<String, MediaGiorno> = emptyMap(),
    ): List<RigaGiorno> {
        val perGiorno = pesate.groupBy { it.giorno }
        val veriPerGiorno = tutteLePesate.groupBy { it.giorno }
        val minimi = minimiGiornalieri(pesate)
        val oggiStr = oggi.toString()

        val ricostruiti = giorniInterpolati(perGiorno.keys, obiettivo, oggiStr).toMutableSet()
        val saltati = mutableSetOf<String>()
        val daFare = mutableSetOf<String>()
        val inizio = giornoDa(obiettivo?.inizio)
        if (obiettivo != null && inizio != null && obiettivo.fine.isNotBlank()) {
            var cursore: LocalDate = inizio
            while (cursore.toString() <= obiettivo.fine) {
                val g = cursore.toString()
                if (g !in perGiorno && giornoDiPesata(obiettivo, g)) {
                    if (g >= oggiStr) { daFare += g; ricostruiti -= g }
                    else if (obiettivo.ogniGiorni != null) { saltati += g; ricostruiti -= g }
                }
                cursore = cursore.plusDays(1)
            }
            // Con N i giorni ricostruiti non esistono: fuori calendario non si conta.
            if (obiettivo.ogniGiorni != null) ricostruiti.clear()
        }

        // Ogni giorno passato dell'obiettivo senza nessuna pesata ha la sua
        // riga (web v4.13.2): medie stimate col trend e target, su fondo rosa.
        // Oggi no finché non ci si pesa: la giornata non è finita.
        val senza = mutableSetOf<String>()
        if (obiettivo != null && inizio != null) {
            val fineS = if (obiettivo.fine.isNotBlank() && obiettivo.fine < oggiStr) obiettivo.fine else oggiStr
            var c: LocalDate = inizio
            while (true) {
                val g = c.toString()
                if (g >= oggiStr || g > fineS) break
                if (veriPerGiorno[g].isNullOrEmpty()) senza += g
                c = c.plusDays(1)
            }
        }

        val perConto = conto.associateBy { it.giorno }

        return (perGiorno.keys + ricostruiti + saltati + daFare + senza).sortedDescending().map { giorno ->
            val righe = perGiorno[giorno]
            val minima = righe?.minByOrNull { it.peso }
            val massima = righe?.maxByOrNull { it.peso }
            val interpolato = giorno in ricostruiti
            val stato = when {
                giorno in saltati -> StatoRiga.NON_PESATO
                giorno in daFare -> StatoRiga.DA_FARE
                interpolato -> StatoRiga.RICOSTRUITO
                perGiorno[giorno] == null && giorno in senza -> StatoRiga.SENZA_PESATA
                else -> StatoRiga.PESATA
            }
            // La prima pesata del giorno: la stessa che entra nella media.
            val veri = veriPerGiorno[giorno].orEmpty()
            val prima = veri.minByOrNull { it.timestamp }
            val primaG = veri.filter { it.grasso != null }.minByOrNull { it.timestamp }
            val dentro = obiettivo != null && giorno >= obiettivo.inizio && giorno <= obiettivo.fine
            // ⚠️ [pesate] sono quelle di [pesiVista]: sulla massa grassa il target
            // congelato è già in grasso, e quello interpolato va convertito.
            val target = minima?.target
                ?: obiettivo?.let { aGrasso(targetInterpolato(it.traguardi, giorno), it) }
            RigaGiorno(
                giorno = giorno,
                target = target,
                // Il peso ricostruito è quello su cui la RPC ha dato i punti, se c'è.
                minimo = if (interpolato) perConto[giorno]?.peso ?: pesoInterpolato(minimi, giorno) else minima?.peso,
                massimo = if (interpolato) null else massima?.peso,
                oraMinimo = minima?.ora,
                oraMassimo = massima?.ora,
                punti = perConto[giorno]?.punti,
                cumulativo = perConto[giorno]?.cumulativo,
                interpolata = interpolato,
                stato = stato,
                fuoriCalendario = righe != null && dentro && !giornoDiPesata(obiettivo!!, giorno),
                notaMinimo = if (interpolato) null else notaTotale(minima),
                notaMassimo = if (interpolato) null else notaTotale(massima),
                notaTarget = notaTargetTotale(target, obiettivo),
                primaPeso = prima?.peso,
                primaGrassoKg = primaG?.let { massaGrassa(it) },
                primaGrassoPct = primaG?.grasso,
                media = medie[giorno],
                senzaPesata = giorno in senza,
                giornoPremio = dentro && giornoDiPesata(obiettivo!!, giorno),
                futuro = giorno > oggiStr || (giorno in daFare && veri.isEmpty()),
            )
        }
    }

    /**
     * Lo storico come si legge nella scheda 📊 Tabella: le sole giornate
     * **dentro l'obiettivo** (dall'inizio alla fine), **dalla data d'inizio in
     * cima** — gemello di `tableFilteredDays` / `tableAllDays` del web.
     *
     * ⚠️ È un filtro di lettura e non tocca [tabella]: quella la leggono anche
     * il grafico e il badge dei punti, che vogliono l'ordine di sempre (dalla
     * più recente). Senza obiettivo resta tutto, dal più vecchio.
     */
    fun storico(righe: List<RigaGiorno>, obiettivo: Obiettivo?): List<RigaGiorno> {
        val da = obiettivo?.inizio?.take(10).orEmpty()
        val a = obiettivo?.fine?.take(10).orEmpty()
        return righe
            .filter { (da.isBlank() || it.giorno >= da) && (a.isBlank() || it.giorno <= a) }
            .sortedBy { it.giorno }
    }

    /**
     * I giorni dell'obiettivo senza nessuna pesata, fino a oggi: quelli che il
     * web ricostruisce per interpolazione. **Fino a oggi e non fino alla fine
     * dell'obiettivo**: i giorni che devono ancora arrivare non si giudicano.
     */
    private fun giorniInterpolati(
        conPesate: Set<String>,
        obiettivo: Obiettivo?,
        oggiStr: String,
    ): Set<String> {
        if (obiettivo == null) return emptySet()
        val inizio = giornoDa(obiettivo.inizio) ?: return emptySet()
        val ultimo = giornoDa(minOf(obiettivo.fine, oggiStr)) ?: return emptySet()
        if (ultimo.isBefore(inizio)) return emptySet()
        if (obiettivo.traguardi.size < 2) return emptySet()

        val giorni = mutableSetOf<String>()
        var cursore = inizio
        while (!cursore.isAfter(ultimo)) {
            val giorno = cursore.toString()
            if (giorno !in conPesate) giorni += giorno
            cursore = cursore.plusDays(1)
        }
        return giorni
    }

    /**
     * Il minimo di oggi; se oggi non ci si è pesati, **l'ultima pesata nota**.
     *
     * Il ripiego è quello di `getTodayMinWeight`, che scorre `data` e prende il
     * primo peso valido: là la lista arriva ordinata per data **discendente**
     * (`fetchAllWeights('date', false)`), quindi «il primo» è il più recente.
     * Qui le pesate sono in ordine crescente, e il più recente è l'ultimo — la
     * stessa pesata, presa dal capo opposto della lista.
     */
    fun minimoDiOggi(pesate: List<Pesata>, oggi: LocalDate = LocalDate.now()): Double? {
        val oggiStr = oggi.toString()
        val diOggi = pesate.filter { it.giorno == oggiStr }
        if (diOggi.isNotEmpty()) return diOggi.minOf { it.peso }
        return pesate.lastOrNull()?.peso
    }

    /**
     * Il minimo di **oggi**, `null` se non ci si è pesati oggi — a differenza
     * di [minimoDiOggi] non ripiega sull'ultima pesata nota. È il controllo
     * di `closeObjective('success')` per un obiettivo «perdere»: chiudere con
     * successo vuole una pesata di oggi, non una vecchia.
     */
    fun minimoStrettoOggi(pesate: List<Pesata>, oggi: LocalDate = LocalDate.now()): Double? {
        val diOggi = pesate.filter { it.giorno == oggi.toString() }
        return if (diOggi.isEmpty()) null else diOggi.minOf { it.peso }
    }

    /**
     * Il massimo peso registrato nel periodo `[inizio, fine]`, estremi
     * compresi — il controllo di `closeObjective('success')` per un
     * obiettivo «mantenere»: il peggiore del periodo deve stare sotto il
     * peso stabilito.
     */
    fun massimoNelPeriodo(pesate: List<Pesata>, inizio: String, fine: String): Double? =
        pesate.filter { it.giorno >= inizio && it.giorno <= fine }.maxOfOrNull { it.peso }

    fun arrotonda(valore: Double, decimali: Int): Double {
        val fattore = Math.pow(10.0, decimali.toDouble())
        return Math.round(valore * fattore) / fattore
    }

    /**
     * Le soglie intere dei traguardi premio, dalla più facile alla più
     * difficile — `getMilestoneThresholds` nel web. Con `pesoIniziale` non
     * sopra `pesoFinale` (o uno dei due mancante) non c'è nessuna soglia.
     */
    fun sogliePremio(pesoIniziale: Double?, pesoFinale: Double?): List<Int> {
        if (pesoIniziale == null || pesoFinale == null || pesoIniziale <= pesoFinale) return emptyList()
        val top = Math.floor(pesoIniziale).toInt() - 1
        val bot = Math.ceil(pesoFinale).toInt()
        if (top < bot) return emptyList()
        return (top downTo bot).toList()
    }

    /**
     * Distribuzione crescente dei punti fra le soglie —
     * `getMilestonePtsDistribution`. ⚠️ Serve alla sola barra di stelline, che
     * mostra dal vivo il totale mentre lo si scrive: i punti che VALGONO li
     * conta `ps_punti`. La soglia più lontana (l'ultima, la più
     * difficile) vale di più. L'ultima prende il residuo, per non perdere
     * punti negli arrotondamenti.
     */
    fun distribuzionePunti(totalPts: Int, n: Int): List<Int> {
        if (n <= 0 || totalPts <= 0) return emptyList()
        val sommaFattori = n * (n + 1) / 2.0
        val punti = mutableListOf<Int>()
        var assegnati = 0
        for (i in 0 until n) {
            val p = if (i < n - 1) Math.round(totalPts * (i + 1) / sommaFattori).toInt()
                    else totalPts - assegnati
            punti += p
            assegnati += p
        }
        return punti
    }

    /** `2026-08-14` → data; qualunque altra cosa → null, senza sollevare. */
    fun giornoDa(iso: String?): LocalDate? =
        iso?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/** `2026-08-14` → `14/08/2026`, come in tutte le app di casa. */
internal fun dataItaliana(iso: String): String {
    val pezzi = iso.take(10).split("-")
    if (pezzi.size != 3) return iso
    return "${pezzi[2]}/${pezzi[1]}/${pezzi[0]}"
}

/** Un peso come lo scrive la pagina: un decimale, virgola italiana. */
internal fun kg(valore: Double?): String =
    valore?.let { String.format(java.util.Locale.ITALY, "%.1f", it) } ?: "–"
