package com.garsal.appsphere.notifiche

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Dove arrivano le push.
 *
 * Il messaggio è di **soli dati** (`send-notifications` lo manda così apposta),
 * quindi Android non disegna niente da sé e questo servizio viene svegliato
 * anche ad app chiusa: la notifica, coi suoi pulsanti, la costruisce
 * [Notifiche].
 */
class PushService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * FCM rigenera il token da sé — reinstallazione, ripristino da backup,
     * dati svuotati — e quando succede la riga in `cm_push_devices` diventa un
     * indirizzo che non risponde più. Va riscritta subito: aspettare il
     * prossimo avvio dell'app vuol dire perdere le notifiche fino ad allora.
     */
    override fun onNewToken(token: String) {
        Log.i(TAG, "token nuovo (…${token.takeLast(6)})")
        scope.launch { Push.registra(applicationContext) }
    }

    override fun onMessageReceived(messaggio: RemoteMessage) {
        val dati = messaggio.data
        val queueId = dati["queue_id"]
        if (queueId.isNullOrBlank()) {
            Log.w(TAG, "push senza queue_id: non si può fare niente coi pulsanti")
            return
        }
        Notifiche.mostra(
            context  = applicationContext,
            queueId  = queueId,
            titolo   = dati["title"].orEmpty().ifBlank { "Promemoria" },
            testo    = dati["body"].orEmpty(),
            // FCM ammette solo stringhe nel blocco data: i booleani viaggiano
            // come "true"/"false" e si rileggono così.
            completa = dati["completa"] == "true",
            annulla  = dati["annulla"] != "false",
        )
    }

    companion object { private const val TAG = "PushService" }
}
