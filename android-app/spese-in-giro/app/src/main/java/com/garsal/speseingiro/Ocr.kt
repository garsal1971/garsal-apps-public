package com.garsal.speseingiro

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Legge lo scontrino e prova a dire quant'è il totale.
 *
 * ⚠️ Il numero che esce di qui è una **proposta**, mai un dato: finisce nella
 * casella dell'importo già scritta e modificabile, e resta segnato a parte
 * (`vg_voci.scontrino_letto`) accanto a quello confermato a mano — così quando
 * la lettura sbaglia si vede che ha sbagliato. Un importo letto da una foto e
 * dato per buono è un debito fra due persone deciso da un OCR.
 *
 * ⚠️ ML Kit legge del TESTO: il totale non lo sa riconoscere. La regola qui
 * sotto è euristica e sta scritta apposta in un posto solo.
 */
object Ocr {

    /** Un importo con i centesimi: `12,50` o `12.50`. I decimali sono
     *  obbligatori — senza, un «3» di «3 x 1,20» diventerebbe un totale. */
    private val IMPORTO = Regex("""(?<![\d.,])(\d{1,5})[.,](\d{2})(?![\d])""")

    /** Le parole che di norma stanno accanto al totale. */
    private val CHIAVI = listOf("TOTALE COMPLESSIVO", "TOTALE EURO", "TOTALE", "TOT.", "IMPORTO")

    /** Righe che portano un numero ma non il totale: prenderle è l'errore più
     *  frequente, perché stanno proprio lì attorno. */
    private val ESCLUSE = listOf("SUBTOTALE", "IVA", "RESTO", "SCONTO", "SCONTRINO", "NON FISCALE",
                                 "ACCONTO", "PAGATO CONTANTE", "CONTANTE", "CARTA")

    suspend fun leggi(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        val riconoscitore = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        riconoscitore.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { t -> if (cont.isActive) cont.resume(t.text) }
            // Fallisce in silenzio, come `titoloYouTube` in Memo: l'OCR è una
            // comodità, non una condizione perché la spesa si possa segnare.
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    fun totale(testo: String?): Double? {
        if (testo.isNullOrBlank()) return null
        val righe = testo.uppercase().lines().map { it.trim() }.filter { it.isNotEmpty() }

        fun importiIn(riga: String): List<Double> =
            IMPORTO.findAll(riga).mapNotNull { "${it.groupValues[1]}.${it.groupValues[2]}".toDoubleOrNull() }.toList()

        val candidati = mutableListOf<Double>()
        righe.forEachIndexed { i, riga ->
            if (ESCLUSE.any { riga.contains(it) }) return@forEachIndexed
            if (CHIAVI.none { riga.contains(it) }) return@forEachIndexed
            // Sulla riga del totale l'importo è l'ultimo; se lì non c'è (il
            // valore è andato a capo, che sugli scontrini stretti capita
            // sempre) si guarda la riga dopo.
            val qui = importiIn(riga)
            if (qui.isNotEmpty()) candidati += qui.last()
            else righe.getOrNull(i + 1)?.let { dopo -> importiIn(dopo).firstOrNull()?.let { candidati += it } }
        }

        // Fra più «totale» (spesso ce ne sono due: totale e totale pagato) si
        // prende il più alto: è quello che comprende tutto.
        candidati.maxOrNull()?.let { return it }

        // Nessuna parola chiave: resta l'importo più grande dello scontrino, che
        // sul totale ci azzecca quasi sempre — e comunque è una proposta.
        return righe.flatMap { importiIn(it) }.maxOrNull()
    }
}
