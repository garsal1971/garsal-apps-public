package com.garsal.solstizio

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/** Il luogo è fisso: Bologna. Cambiarlo è cambiare queste tre righe. */
object Luogo {
    const val NOME = "Bologna"
    const val LAT = 44.4949
    const val LON = 11.3426
    val ZONA: ZoneId = ZoneId.of("Europe/Rome")
}

enum class TipoEvento(val nome: String, val emoji: String, val al: String) {
    EQUINOZIO_PRIMAVERA("Equinozio di primavera", "🌱", "all'equinozio"),
    SOLSTIZIO_ESTATE("Solstizio d'estate", "☀️", "al solstizio"),
    EQUINOZIO_AUTUNNO("Equinozio d'autunno", "🍂", "all'equinozio"),
    SOLSTIZIO_INVERNO("Solstizio d'inverno", "❄️", "al solstizio"),
}

/** «9 h 07 min»: le ore di luce scritte allo stesso modo dappertutto. */
fun oreLuce(minuti: Int): String = "${minuti / 60} h ${"%02d".format(minuti % 60)} min"

data class Evento(val tipo: TipoEvento, val istante: ZonedDateTime) {
    val giorno: LocalDate get() = istante.toLocalDate()
}

/**
 * La stecca: dal giorno dopo l'evento precedente fino al giorno del prossimo,
 * compreso. Il giorno dell'evento è l'ultima casella; il giorno dopo la stecca
 * riparte da sola verso l'evento successivo.
 */
data class Periodo(val precedente: Evento, val prossimo: Evento) {
    val primo: LocalDate = precedente.giorno.plusDays(1)
    val ultimo: LocalDate = prossimo.giorno
    val giorni: List<LocalDate> =
        generateSequence(primo) { if (it < ultimo) it.plusDays(1) else null }.toList()
}

data class Sole(val alba: LocalTime, val tramonto: LocalTime, val minutiLuce: Int)

object Astro {

    // Meeus, «Astronomical Algorithms», cap. 27: equinozi e solstizi per gli anni
    // 2000-3000, coi 24 termini periodici. Precisione di un paio di minuti —
    // per decidere in che GIORNO cade l'evento è più che abbastanza.
    private val BASE = arrayOf(
        doubleArrayOf(2451623.80984, 365242.37404, 0.05169, -0.00411, -0.00057),
        doubleArrayOf(2451716.56767, 365241.62603, 0.00325, 0.00888, -0.00030),
        doubleArrayOf(2451810.21715, 365242.01767, -0.11575, 0.00337, 0.00078),
        doubleArrayOf(2451900.05952, 365242.74049, -0.06223, -0.00823, 0.00032),
    )
    private val TERMINI = arrayOf(
        doubleArrayOf(485.0, 324.96, 1934.136), doubleArrayOf(203.0, 337.23, 32964.467),
        doubleArrayOf(199.0, 342.08, 20.186), doubleArrayOf(182.0, 27.85, 445267.112),
        doubleArrayOf(156.0, 73.14, 45036.886), doubleArrayOf(136.0, 171.52, 22518.443),
        doubleArrayOf(77.0, 222.54, 65928.934), doubleArrayOf(74.0, 296.72, 3034.906),
        doubleArrayOf(70.0, 243.58, 9037.513), doubleArrayOf(58.0, 119.81, 33718.147),
        doubleArrayOf(52.0, 297.17, 150.678), doubleArrayOf(50.0, 21.02, 2281.226),
        doubleArrayOf(45.0, 247.54, 29929.562), doubleArrayOf(44.0, 325.15, 31555.956),
        doubleArrayOf(29.0, 60.93, 4443.417), doubleArrayOf(18.0, 155.12, 67555.328),
        doubleArrayOf(17.0, 288.79, 4562.452), doubleArrayOf(16.0, 198.04, 62894.029),
        doubleArrayOf(14.0, 199.76, 31436.921), doubleArrayOf(12.0, 95.39, 14577.848),
        doubleArrayOf(12.0, 287.11, 31931.756), doubleArrayOf(12.0, 320.81, 34777.259),
        doubleArrayOf(9.0, 227.73, 1222.114), doubleArrayOf(8.0, 15.45, 16859.074),
    )

    fun evento(anno: Int, tipo: TipoEvento): Evento {
        val y = (anno - 2000) / 1000.0
        val c = BASE[tipo.ordinal]
        val jde0 = c[0] + c[1] * y + c[2] * y * y + c[3] * y * y * y + c[4] * y * y * y * y
        val t = (jde0 - 2451545.0) / 36525.0
        val w = Math.toRadians(35999.373 * t - 2.47)
        val dl = 1 + 0.0334 * cos(w) + 0.0007 * cos(2 * w)
        var s = 0.0
        for (r in TERMINI) s += r[0] * cos(Math.toRadians(r[1] + r[2] * t))
        val jde = jde0 + 0.00001 * s / dl
        // Il risultato è in Tempo Terrestre: ~69 s avanti rispetto all'UTC.
        val millis = ((jde - 2440587.5) * 86_400_000.0).toLong() - 69_000L
        return Evento(tipo, Instant.ofEpochMilli(millis).atZone(Luogo.ZONA))
    }

    /** Il periodo in cui cade [oggi]: il prossimo evento è il primo che non è
     *  già passato, quindi il giorno stesso dell'evento conta ancora. */
    fun periodo(oggi: LocalDate): Periodo {
        val tutti = (oggi.year - 1..oggi.year + 1)
            .flatMap { a -> TipoEvento.entries.map { evento(a, it) } }
            .sortedBy { it.istante }
        val i = tutti.indexOfFirst { !it.giorno.isBefore(oggi) }
        return Periodo(tutti[i - 1], tutti[i])
    }

    /**
     * Alba e tramonto col metodo NOAA (equazione del tempo e declinazione in
     * serie di Fourier), con la rifrazione standard a 90,833°. Precisione di
     * circa un minuto, e si calcola senza rete.
     */
    fun sole(giorno: LocalDate): Sole {
        val n = if (giorno.isLeapYear) 366 else 365
        val g = 2 * PI / n * (giorno.dayOfYear - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) -
            0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val lat = Math.toRadians(Luogo.LAT)
        val cosH = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        val h = Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0)))
        val albaUtc = 720 - 4 * (Luogo.LON + h) - eq
        val tramontoUtc = 720 - 4 * (Luogo.LON - h) - eq
        fun locale(minuti: Double): LocalTime =
            giorno.atStartOfDay(ZoneOffset.UTC)
                .plusSeconds((minuti * 60).roundToLong())
                .withZoneSameInstant(Luogo.ZONA)
                .toLocalTime()
        return Sole(locale(albaUtc), locale(tramontoUtc), (tramontoUtc - albaUtc).roundToLong().toInt())
    }
}
