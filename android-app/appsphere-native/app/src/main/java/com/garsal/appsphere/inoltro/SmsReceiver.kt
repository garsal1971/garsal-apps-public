package com.garsal.appsphere.inoltro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 📨 Gli SMS in arrivo. ⚠️ Un SMS lungo arriva in più pezzi (PDU) nello stesso
 * broadcast: si ricompongono per mittente, o diventerebbero più messaggi tagliati.
 * Il mittente è il numero o la sigla del servizio («UniCredit»): niente rubrica,
 * per non chiedere anche READ_CONTACTS.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parti = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        parti.filterNotNull()
            .groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
            .forEach { (mittente, pezzi) ->
                val testo = pezzi.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                val quando = pezzi.minOf { it.timestampMillis }.takeIf { it > 0 } ?: System.currentTimeMillis()
                Inoltro.ricevuto(
                    context,
                    Inoltro.Messaggio(Inoltro.FONTE_SMS, mittente.ifBlank { null }, null, testo, quando),
                )
            }
    }
}
