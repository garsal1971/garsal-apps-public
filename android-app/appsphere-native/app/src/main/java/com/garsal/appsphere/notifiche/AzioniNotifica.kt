package com.garsal.appsphere.notifiche

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Il pulsante premuto sulla notifica.
 *
 * ⚠️ Qui non si decide niente: si chiama `notification-action`, che è la stessa
 * Edge Function che serve i bottoni di Telegram. Completamento, punti e
 * archivi hanno un esito solo, qualunque schermo lo abbia chiesto.
 *
 * ⚠️ La notifica si chiude **prima** della risposta del server: il tocco deve
 * avere un effetto immediato, o si preme una seconda volta credendo che non
 * sia passato — e due «Fatto» sullo stesso promemoria sono due chiusure. Se poi
 * il server rifiuta, il messaggio lo dice.
 */
class AzioniNotifica : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val queueId = intent.getStringExtra(EXTRA_QUEUE_ID) ?: return
        val azione  = intent.getStringExtra(EXTRA_AZIONE) ?: return
        val minuti  = intent.getIntExtra(EXTRA_MINUTI, 0).takeIf { it > 0 }

        Notifiche.chiudi(context, queueId)

        // Il receiver muore appena onReceive esce: `goAsync` tiene vivo il
        // processo il tempo della chiamata (una decina di secondi al massimo,
        // che è molto più di quanto serve).
        val pending = goAsync()
        val ctx = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val esito = Push.azione(queueId, azione, minuti)
                Log.i(TAG, "azione=$azione minuti=$minuti esito=${esito ?: "ko"}")
                mostraMessaggio(ctx, esito ?: "❌ Non è riuscito, riprova dall'app")
            } finally {
                pending.finish()
            }
        }
    }

    private fun mostraMessaggio(context: Context, testo: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, testo, Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        private const val TAG = "AzioniNotifica"
        const val EXTRA_QUEUE_ID = "queue_id"
        const val EXTRA_AZIONE   = "azione"
        const val EXTRA_MINUTI   = "minuti"
    }
}
