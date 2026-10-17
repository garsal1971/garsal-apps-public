package com.garsal.appsphere.notifiche

import android.app.Activity
import android.app.AlertDialog
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
            (if (conAnnulla) listOf("❌ Annulla il promemoria") else emptyList()) +
            listOf("🗑 Cancella la notifica")

        // ⚠️ Il tema del dialogo si passa a mano: l'Activity è trasparente
        // (`Theme.Translucent.NoTitleBar`), che non è un tema Material, e senza
        // questo il dialogo verrebbe disegnato con lo stile di dieci anni fa.
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(titolo)
            .setItems(voci.toTypedArray()) { _, quale ->
                when {
                    quale < Notifiche.RINVII.size ->
                        manda(queueId, "snooze", Notifiche.RINVII[quale].first)
                    quale == voci.lastIndex ->
                        manda(queueId, AzioniNotifica.AZIONE_CANCELLA, null)
                    else -> manda(queueId, "cancel", null)
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
    private fun manda(queueId: String, azione: String, minuti: Int?) =
        Notifiche.esegui(this, queueId, azione, minuti)

    companion object {
        const val EXTRA_ANNULLA = "annulla"
        const val EXTRA_TITOLO  = "titolo"
    }
}
