package com.garsal.appsphere.notifiche

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

/**
 * Le altre scelte del promemoria: i rinvii che non stanno sulla notifica e
 * l'annullamento.
 *
 * ⚠️ Esiste perché Android mostra **tre** pulsanti e basta. Mettendoli tutti
 * sulla notifica si perderebbe ✅ Fatto, che è quello che si preme davvero;
 * mettendone uno solo si perderebbe la parità con Telegram, che questo canale
 * affianca. Qui dentro ci sono le stesse quattro durate del bot.
 *
 * È un'Activity trasparente che apre un dialogo e se ne va: non è una
 * schermata dell'app e non deve comparire fra quelle aperte di recente.
 */
class RinvioActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val queueId = intent.getStringExtra(AzioniNotifica.EXTRA_QUEUE_ID)
        if (queueId == null) { finish(); return }
        val conAnnulla = intent.getBooleanExtra(EXTRA_ANNULLA, true)
        val titolo     = intent.getStringExtra(EXTRA_TITOLO) ?: "Promemoria"

        val voci = Notifiche.RINVII.map { (_, etichetta) -> "⏸ $etichetta" } +
            if (conAnnulla) listOf("❌ Annulla il promemoria") else emptyList()

        // ⚠️ Il tema del dialogo si passa a mano: l'Activity è trasparente
        // (`Theme.Translucent.NoTitleBar`), che non è un tema Material, e senza
        // questo il dialogo verrebbe disegnato con lo stile di dieci anni fa.
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(titolo)
            .setItems(voci.toTypedArray()) { _, quale ->
                if (quale < Notifiche.RINVII.size) {
                    manda(queueId, "snooze", Notifiche.RINVII[quale].first)
                } else {
                    manda(queueId, "cancel", null)
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * L'azione passa dal receiver e non si chiama da qui: è l'unico posto in
     * cui la notifica si chiude e il messaggio si mostra, e duplicarlo
     * vorrebbe dire due comportamenti per lo stesso rinvio.
     */
    private fun manda(queueId: String, azione: String, minuti: Int?) {
        sendBroadcast(
            Intent(this, AzioniNotifica::class.java).apply {
                action = "$azione:$queueId:${minuti ?: 0}"
                putExtra(AzioniNotifica.EXTRA_QUEUE_ID, queueId)
                putExtra(AzioniNotifica.EXTRA_AZIONE, azione)
                if (minuti != null) putExtra(AzioniNotifica.EXTRA_MINUTI, minuti)
            }
        )
    }

    companion object {
        const val EXTRA_ANNULLA = "annulla"
        const val EXTRA_TITOLO  = "titolo"
    }
}
