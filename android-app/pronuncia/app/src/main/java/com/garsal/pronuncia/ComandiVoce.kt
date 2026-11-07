package com.garsal.pronuncia

import android.content.Context
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v1.30.0: i comandi a voce in italiano, riconosciuti SUL TELEFONO confrontando
 * l'audio con le registrazioni della voce di chi usa l'app («Ripeti», «Traduci»…).
 *
 * ⚠️ Esiste perché Azure, con la parola attesa come riferimento, forzava il
 * comando sulla parola («spell» → «please» a 5): qui il confronto avviene
 * PRIMA di Azure, e solo se non somiglia a nessun comando l'audio va ad Azure.
 * Un comando riconosciuto in locale non costa niente.
 *
 * Il confronto: coefficienti MFCC per frame (25 ms, passo 10 ms), silenzi in
 * testa e in coda tolti, media sottratta (CMN), e distanza DTW normalizzata
 * contro ogni registrazione. Vince il comando più vicino, se sta sotto la sua
 * soglia: la distanza più grande fra le sue registrazioni × [Prefs.sensibilitaComandi].
 *
 * ⚠️ Le registrazioni stanno SOLO sul telefono (`filesDir/comandi`), in PCM
 * 16 kHz mono: sono la voce di una persona, e non vanno né su Supabase né nella repo.
 */
class ComandiVoce(ctx: Context) {

    /** I comandi da registrare, con la parola italiana da dire. */
    enum class Voce(val comando: Comando, val parola: String) {
        RIPETI(Comando.RIPETI, "Ripeti"),
        AVANTI(Comando.AVANTI, "Avanti"),
        LENTO(Comando.LENTO, "Lento"),
        FERMA(Comando.FERMA, "Ferma"),
        ORTOGRAFIA(Comando.SPELLING, "Ortografia"),
        TRADUCI(Comando.TRADUCI, "Traduci"),
    }

    /** L'esito di un confronto, anche per la prova in Impostazioni. */
    data class Esito(
        val voce: Voce?, val migliore: Voce?, val distanza: Double, val soglia: Double,
        /** v1.32.0: il secondo comando più vicino e la sua distanza (NaN se non c'è). */
        val secondo: Voce? = null, val distanzaSecondo: Double = Double.NaN,
    )

    private val cartella = File(ctx.filesDir, "comandi").apply { mkdirs() }
    private val cache = mutableMapOf<Voce, List<Array<DoubleArray>>>()

    private fun file(v: Voce, i: Int) = File(cartella, "${v.name}_$i.pcm")

    fun quante(v: Voce): Int = (0 until MAX).count { file(v, it).exists() }

    /** Almeno un comando con almeno una registrazione. */
    fun pronti(): Boolean = Voce.entries.any { quante(it) > 0 }

    /** Aggiunge una registrazione; piene le [MAX], si ricomincia dalla prima. */
    @Synchronized fun aggiungi(v: Voce, pcm: ByteArray) {
        val n = quante(v)
        if (n >= MAX) (0 until MAX).forEach { file(v, it).delete() }
        file(v, if (n >= MAX) 0 else n).writeBytes(pcm)
        cache.remove(v)
    }

    @Synchronized fun cancella(v: Voce) {
        (0 until MAX).forEach { file(v, it).delete() }
        cache.remove(v)
    }

    fun ultima(v: Voce): ByteArray? = (MAX - 1 downTo 0).map { file(v, it) }.firstOrNull { it.exists() }?.readBytes()

    @Synchronized private fun modelli(v: Voce): List<Array<DoubleArray>> = cache.getOrPut(v) {
        (0 until MAX).map { file(v, it) }.filter { it.exists() }
            .mapNotNull { runCatching { caratteristiche(it.readBytes()) }.getOrNull() }
            .filter { it.size >= MIN_FRAME }
    }

    /** Il comando detto in [pcm], o `null` se non somiglia abbastanza a nessuno. */
    fun riconosci(pcm: ByteArray, sensibilita: Double): Comando? = confronta(pcm, sensibilita).voce?.comando

    fun confronta(pcm: ByteArray, sensibilita: Double): Esito {
        val x = runCatching { caratteristiche(pcm) }.getOrNull()
        if (x == null || x.size < MIN_FRAME) return Esito(null, null, Double.NaN, Double.NaN)
        // Distanza da ogni comando registrato. ⚠️ Una durata troppo diversa non
        // esclude più il comando dal confronto: conta solo per la vittoria.
        val distanze = Voce.entries.mapNotNull { v ->
            val m = modelli(v)
            if (m.isEmpty()) null
            else Triple(v, m.minOf { dtw(x, it) }, m)
        }.sortedBy { it.second }
        val primo = distanze.firstOrNull() ?: return Esito(null, null, Double.NaN, Double.NaN)
        val secondo = distanze.getOrNull(1)
        val (meglio, dMeglio, m) = primo
        val sMeglio = soglia(m) * sensibilita
        val durataOk = m.any { x.size.toDouble() / it.size in 0.5..2.0 }
        // v1.32.0: vince solo se sta sotto la soglia E se è nettamente più vicino a
        // quel comando che al secondo: una parola qualunque («fen») sta a metà
        // strada fra tutti, un comando vero no.
        val netto = secondo == null || dMeglio <= MARGINE * secondo.second
        val vince = dMeglio <= sMeglio && durataOk && netto
        return Esito(meglio.takeIf { vince }, meglio, dMeglio, sMeglio, secondo?.first, secondo?.second ?: Double.NaN)
    }

    /** La distanza più grande fra le registrazioni di un comando: quanto varia
     *  la stessa parola detta più volte. Con una sola, un valore di partenza. */
    private fun soglia(m: List<Array<DoubleArray>>): Double {
        if (m.size < 2) return SOGLIA_UNA
        var s = 0.0
        for (i in m.indices) for (j in i + 1 until m.size) s = max(s, dtw(m[i], m[j]))
        return max(s, SOGLIA_MIN)
    }

    companion object {
        const val MAX = 3
        /** Il primo comando deve stare almeno a questa frazione della distanza dal secondo. */
        private const val MARGINE = 0.8
        private const val MIN_FRAME = 15
        private const val SOGLIA_UNA = 14.0
        private const val SOGLIA_MIN = 8.0

        private const val HZ = 16000
        private const val N = 400
        private const val HOP = 160
        private const val NFFT = 512
        private const val MEL = 26
        private const val CEP = 13

        private val finestra = DoubleArray(N) { 0.54 - 0.46 * cos(2 * PI * it / (N - 1)) }
        private val filtri: Array<DoubleArray> = run {
            fun mel(f: Double) = 2595 * log10(1 + f / 700)
            fun hz(m: Double) = 700 * (Math.pow(10.0, m / 2595) - 1)
            val punti = (0..MEL + 1).map { hz(mel(80.0) + (mel(7600.0) - mel(80.0)) * it / (MEL + 1)) }
                .map { (it / HZ * NFFT).toInt() }
            Array(MEL) { m ->
                DoubleArray(NFFT / 2 + 1) { k ->
                    val (a, b, c) = Triple(punti[m], punti[m + 1], punti[m + 2])
                    when {
                        k in a until b && b > a -> (k - a).toDouble() / (b - a)
                        k in b..c && c > b -> (c - k).toDouble() / (c - b)
                        else -> 0.0
                    }
                }
            }
        }

        /** I vettori MFCC (c1..c12) dei frame con voce, a media sottratta. */
        fun caratteristiche(pcm: ByteArray): Array<DoubleArray> {
            val n = pcm.size / 2
            val s = DoubleArray(n) { ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF)).toShort().toDouble() }
            for (i in n - 1 downTo 1) s[i] -= 0.97 * s[i - 1]
            val frame = mutableListOf<DoubleArray>()
            val energia = mutableListOf<Double>()
            var inizio = 0
            val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
            while (inizio + N <= n) {
                re.fill(0.0); im.fill(0.0)
                var e = 0.0
                for (i in 0 until N) { val v = s[inizio + i]; e += v * v; re[i] = v * finestra[i] }
                fft(re, im)
                val pot = DoubleArray(NFFT / 2 + 1) { (re[it] * re[it] + im[it] * im[it]) / NFFT }
                val logMel = DoubleArray(MEL) { m ->
                    var t = 0.0; val f = filtri[m]
                    for (k in pot.indices) t += f[k] * pot[k]
                    ln(max(t, 1e-10))
                }
                frame += DoubleArray(CEP - 1) { c ->
                    var t = 0.0
                    for (m in 0 until MEL) t += logMel[m] * cos(PI * (c + 1) * (m + 0.5) / MEL)
                    t
                }
                energia += 10 * log10(max(e / N, 1e-10))
                inizio += HOP
            }
            if (frame.isEmpty()) return emptyArray()
            // Via i silenzi in testa e in coda: 30 dB sotto il frame più forte.
            val tetto = energia.max() - 30
            val primo = energia.indexOfFirst { it > tetto }
            val ultimo = energia.indexOfLast { it > tetto }
            val voce = frame.subList(primo, ultimo + 1)
            val media = DoubleArray(CEP - 1) { c -> voce.sumOf { it[c] } / voce.size }
            return Array(voce.size) { i -> DoubleArray(CEP - 1) { c -> voce[i][c] - media[c] } }
        }

        /** DTW con passi (1,0) (0,1) (1,1), normalizzata sulla somma delle lunghezze. */
        fun dtw(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
            val inf = Double.MAX_VALUE / 4
            var prec = DoubleArray(b.size + 1) { inf }.also { it[0] = 0.0 }
            var cor = DoubleArray(b.size + 1)
            for (i in 1..a.size) {
                cor[0] = inf
                for (j in 1..b.size) {
                    var d = 0.0
                    val x = a[i - 1]; val y = b[j - 1]
                    for (k in x.indices) { val t = x[k] - y[k]; d += t * t }
                    cor[j] = sqrt(d) + min(prec[j - 1], min(prec[j], cor[j - 1]))
                }
                val t = prec; prec = cor; cor = t
            }
            return prec[b.size] / (a.size + b.size)
        }

        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                for (i in 0 until n step len) for (k in 0 until len / 2) {
                    val wr = cos(ang * k); val wi = kotlin.math.sin(ang * k)
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                }
                len = len shl 1
            }
        }
    }
}
