package com.garsal.appsphere.inoltro

import android.app.Notification
import android.content.ComponentName
import android.content.Context
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

    /** Android ha collegato il servizio: da qui le notifiche arrivano. */
    override fun onListenerConnected() {
        Inoltro.annota(applicationContext, "👂 notifiche: in ascolto")
    }

    /**
     * ⚠️ Android scollega il servizio (dopo un aggiornamento dell'APK, o lo ferma il
     * sistema) lasciando il permesso concesso: senza ricollegarlo resta muto per sempre.
     */
    override fun onListenerDisconnected() {
        Inoltro.annota(applicationContext, "🔌 notifiche: scollegato, chiedo di ricollegare")
        ricollega(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACCHETTI) return
        val ctx = applicationContext
        val n = sbn.notification ?: return
        // Diagnostica: cosa è arrivato e perché si scarta, mai il testo.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            Inoltro.annota(ctx, "· WA notifica riepilogativa: saltata"); return
        }
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            Inoltro.annota(ctx, "· WA notifica in corso: saltata"); return
        }

        val stile = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }
            .getOrNull()

        if (stile != null && stile.messages.isNotEmpty()) {
            val miei = stile.messages.count { it.person?.name == null }
            Inoltro.annota(ctx, "· WA notifica: ${stile.messages.size} messaggi (${miei} miei o senza mittente)")
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
        val perche = when {
            titolo.isNullOrBlank() -> "senza titolo"
            testo.isBlank() -> "senza testo"
            RIEPILOGO.containsMatchIn(testo) -> "riepilogo"
            titolo.equals("WhatsApp", ignoreCase = true) -> "di sistema"
            else -> null
        }
        if (perche != null) {
            Inoltro.annota(ctx, "· WA notifica senza MessagingStyle: saltata ($perche)"); return
        }
        Inoltro.ricevuto(ctx, Inoltro.Messaggio(Inoltro.FONTE_WA, titolo!!, null, testo, sbn.postTime))
    }

    companion object {
        /** Chiede ad Android di ricollegare il servizio. Innocuo se è già collegato. */
        fun ricollega(ctx: Context) {
            runCatching {
                requestRebind(ComponentName(ctx.applicationContext, WhatsAppListener::class.java))
            }.onFailure { Inoltro.annota(ctx, "⚠️ ricollegamento non riuscito: ${it.javaClass.simpleName}") }
        }

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
