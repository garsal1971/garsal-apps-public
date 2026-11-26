package com.garsal.appsphere.inoltro

import android.content.Context
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 📥 Gli SMS già sul telefono, solo i RICEVUTI (`Telephony.Sms.Inbox`), passati da
 * [Inoltro.smista]: codici e filtrati si scartano, i mittenti noti partono, quelli nuovi
 * vanno in «⏳ Da decidere». Serve READ_SMS.
 *
 * ⚠️ L'ora è `date_sent` (l'ora del centro servizi), con `date` come ripiego: è la stessa
 * che [SmsReceiver] legge dal PDU, quindi un SMS già inoltrato all'arrivo ha la stessa
 * impronta e `msg_messaggi` non lo raddoppia.
 * ⚠️ Il mittente è `address` com'è, come all'arrivo: niente rubrica.
 * [mesi] = quanti mesi indietro da adesso (sulla colonna `date`, l'ora di arrivo sul telefono);
 * null = tutti.
 */
object ImportaSms {
    suspend fun importa(ctx: Context, mesi: Int?): Inoltro.Smistati = withContext(Dispatchers.IO) {
        val msgs = mutableListOf<Inoltro.Messaggio>()
        val dal = mesi?.let {
            java.time.ZonedDateTime.now().minusMonths(it.toLong()).toInstant().toEpochMilli()
        }
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            dal?.let { "${Telephony.Sms.DATE} >= ?" },
            dal?.let { arrayOf(it.toString()) },
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iS = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            while (c.moveToNext()) {
                val testo = c.getString(iB).orEmpty()
                val quando = c.getLong(iS).takeIf { it > 0 } ?: c.getLong(iD)
                val mittente = c.getString(iA)?.takeIf { it.isNotBlank() }
                msgs += Inoltro.Messaggio(Inoltro.FONTE_SMS, mittente, null, testo, quando)
            }
        } ?: error("archivio SMS non leggibile")
        Inoltro.smista(ctx, msgs, mesi?.let { "ultimi $it mesi" } ?: "tutti")
    }
}
