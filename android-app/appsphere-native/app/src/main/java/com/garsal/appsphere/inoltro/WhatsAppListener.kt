package com.garsal.appsphere.inoltro

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * 📨 I messaggi WhatsApp, letti dalle notifiche di `com.whatsapp` e
 * `com.whatsapp.w4b`. Nessuna libreria WhatsApp: solo quel che Android mostra.
 *
 * ⚠️ Si leggono i singoli messaggi da MessagingStyle, che porta mittente e ora
 * di ciascuno: una notifica WhatsApp li ripete tutti a ogni messaggio nuovo, e
 * l'impronta (con l'ora del messaggio, che non cambia) li fa partire una volta sola.
 * ⚠️ Si scartano le notifiche riepilogative (FLAG_GROUP_SUMMARY, «3 nuovi
 * messaggi»), quelle in corso (backup, «controllo nuovi messaggi») e i messaggi
 * miei, che in MessagingStyle hanno il mittente nullo.
 * ⚠️ Una chat silenziata o archiviata, o aperta a schermo, non manda notifica:
 * quei messaggi qui non arrivano.
 */
class WhatsAppListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACCHETTI) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return

        val ctx = applicationContext
        val stile = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }
            .getOrNull()

        if (stile != null && stile.messages.isNotEmpty()) {
            val gruppo = stile.isGroupConversation
            val chat = if (gruppo) pulisciTitolo(stile.conversationTitle?.toString()) else null
            stile.messages.forEach { m ->
                val mittente = m.person?.name?.toString() ?: return@forEach  // null = mio
                val testo = m.text?.toString().orEmpty()
                if (testo.isBlank() || RIEPILOGO.containsMatchIn(testo)) return@forEach
                Inoltro.ricevuto(
                    ctx,
                    Inoltro.Messaggio(
                        Inoltro.FONTE_WA, mittente, chat, testo,
                        m.timestamp.takeIf { it > 0 } ?: sbn.postTime,
                    ),
                )
            }
            return
        }

        // Ripiego senza MessagingStyle: titolo = chi scrive, testo = il messaggio.
        val titolo = pulisciTitolo(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val testo = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (titolo.isNullOrBlank() || testo.isBlank() || RIEPILOGO.containsMatchIn(testo)) return
        if (titolo.equals("WhatsApp", ignoreCase = true)) return
        Inoltro.ricevuto(ctx, Inoltro.Messaggio(Inoltro.FONTE_WA, titolo, null, testo, sbn.postTime))
    }

    companion object {
        val PACCHETTI = setOf("com.whatsapp", "com.whatsapp.w4b")

        private val RIEPILOGO = Regex(
            "^\\s*\\d+\\s+(nuovi\\s+)?messaggi|^\\s*\\d+\\s+(new\\s+)?messages|messaggi da \\d+ chat|messages from \\d+ chats",
            RegexOption.IGNORE_CASE,
        )

        /** «Famiglia (3 messaggi)» → «Famiglia». */
        fun pulisciTitolo(t: String?): String? =
            t?.replace(Regex("\\s*\\(\\d+[^)]*\\)\\s*$"), "")?.trim()?.ifEmpty { null }
    }
}
