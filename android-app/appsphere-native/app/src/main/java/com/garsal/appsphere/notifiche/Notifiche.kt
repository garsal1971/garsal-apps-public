package com.garsal.appsphere.notifiche

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.garsal.appsphere.MainActivity
import com.garsal.appsphere.R
import org.json.JSONObject

/**
 * La notifica di un promemoria: come si disegna e quali pulsanti porta.
 *
 * ⚠️ Le push arrivano come messaggi di **soli dati** e non come `notification`
 * di FCM: la notifica la disegna l'app perché una notification payload non può
 * portare pulsanti, e senza ✅ Fatto e ⏸ Rinvia il telefono direbbe meno di
 * Telegram — che è il canale che questo affianca, non sostituisce.
 */
object Notifiche {

    /** Un canale solo: sono tutti promemoria, e distinguerli non aiuterebbe a
     *  silenziarne uno senza silenziare gli altri. */
    const val CANALE = "promemoria"

    /** Le durate del rinvio, le stesse dei bottoni di Telegram. */
    val RINVII = listOf(
        30   to "30 min",
        60   to "1 ora",
        180  to "3 ore",
        1440 to "Domani",
    )

    /**
     * L'id della notifica si ricava dal `queue_id`, che è unico per riga di
     * coda: così una push rimandata due volte non impila due notifiche per lo
     * stesso promemoria, e chiudendone una si chiude quella giusta.
     */
    fun idDa(queueId: String): Int = queueId.hashCode()

    fun creaCanale(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canale = NotificationChannel(
            CANALE,
            "Promemoria",
            // HIGH e non DEFAULT: un promemoria che non compare in cima allo
            // schermo è un promemoria che si legge tre ore dopo.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "I promemoria di task, abitudini e notifiche al volo"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(canale)
    }

    private fun intentAzione(
        context: Context,
        queueId: String,
        azione: String,
        minuti: Int? = null,
    ): PendingIntent {
        val intent = Intent(context, AzioniNotifica::class.java).apply {
            action = "$azione:$queueId:${minuti ?: 0}"   // univoco: vedi sotto
            putExtra(AzioniNotifica.EXTRA_QUEUE_ID, queueId)
            putExtra(AzioniNotifica.EXTRA_AZIONE, azione)
            if (minuti != null) putExtra(AzioniNotifica.EXTRA_MINUTI, minuti)
        }
        // ⚠️ L'`action` dell'intent è diversa per ogni pulsante, e non è un
        // vezzo: due PendingIntent con lo stesso requestCode e intent
        // "uguali" (gli extra non contano nel confronto) sono lo **stesso**
        // PendingIntent, e con FLAG_UPDATE_CURRENT il primo si prenderebbe gli
        // extra del secondo — cioè «Fatto» che rimanda di un'ora.
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Disegna e mostra la notifica.
     *
     * @param completa la riga porta un completamento: si può chiudere da qui
     */
    fun mostra(
        context: Context,
        queueId: String,
        titolo: String,
        testo: String,
        completa: Boolean,
        annulla: Boolean,
        silenziosa: Boolean = false,
    ) {
        creaCanale(context)
        archivia(context, queueId, titolo, testo, completa, annulla)

        val apri = PendingIntent.getActivity(
            context,
            idDa(queueId),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val b = NotificationCompat.Builder(context, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titolo)
            .setContentText(testo)
            // Il testo di un promemoria è spesso più lungo di una riga: senza
            // questo si legge «Chiama il denti…» e si è punto e a capo.
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // ⚠️ Il tocco apre l'app e la notifica RESTA: se ne va solo con
            // un'azione (✅, ⏸, ❌) o col 🗑 Cancella dentro «⋯ Altro».
            .setAutoCancel(false)
            .setContentIntent(apri)
            // Rimostrata all'apertura dell'app: torna nella tendina senza
            // suonare, o ogni apertura sarebbe una raffica di squilli.
            .setSilent(silenziosa)

        if (completa) {
            b.addAction(0, "✅ Fatto", intentAzione(context, queueId, "complete"))
        }
        // ⚠️ Un rinvio rapido e uno schermo per gli altri: Android ne mostra
        // tre di pulsanti, e mettere qui 30 min / 1 h / 3 h / domani vorrebbe
        // dire perdere ✅ Fatto, che è quello che si preme davvero.
        b.addAction(0, "⏸ 1 ora", intentAzione(context, queueId, "snooze", 60))
        b.addAction(
            0,
            if (annulla) "⋯ Altro" else "⏸ Altro",
            PendingIntent.getActivity(
                context,
                idDa(queueId),
                Intent(context, RinvioActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(AzioniNotifica.EXTRA_QUEUE_ID, queueId)
                    putExtra(RinvioActivity.EXTRA_ANNULLA, annulla)
                    putExtra(RinvioActivity.EXTRA_TITOLO, titolo)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        mostraNotifica(context, idDa(queueId), b.build())
    }

    /**
     * Il permesso di notificare può mancare (Android 13+): senza, `notify`
     * solleva `SecurityException` e si porterebbe dietro il servizio che ha
     * ricevuto la push.
     */
    fun mostraNotifica(context: Context, id: Int, notifica: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notifica) }
    }

    fun chiudi(context: Context, queueId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(idDa(queueId)) }
    }

    // ── L'archivio delle notifiche aperte ────────────────────────────────
    //
    // Ogni notifica ricevuta resta scritta nelle preferenze finché non si
    // risponde con un'azione andata a buon fine o col 🗑 Cancella. Serve a
    // rimostrarle all'apertura dell'app: scorrerla via col dito (che da
    // Android 14 non si può impedire), un riavvio o il processo ucciso non la
    // fanno sparire per sempre. ⚠️ Sta solo su questo telefono, di proposito.

    private const val PREFS = "notifiche_aperte"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun archivia(
        context: Context, queueId: String, titolo: String, testo: String,
        completa: Boolean, annulla: Boolean,
    ) {
        val voce = JSONObject()
            .put("titolo", titolo).put("testo", testo)
            .put("completa", completa).put("annulla", annulla)
        prefs(context).edit().putString(queueId, voce.toString()).apply()
    }

    /** Toglie la notifica dall'archivio: non verrà più rimostrata. */
    fun dimentica(context: Context, queueId: String) {
        prefs(context).edit().remove(queueId).apply()
    }

    /**
     * Rimette nella tendina tutte le notifiche archiviate. Riscrivere una
     * notifica già a schermo con lo stesso id la aggiorna e basta, quindi non
     * se ne impilano due.
     */
    fun rimostraTutte(context: Context) {
        for ((queueId, valore) in prefs(context).all) {
            val voce = runCatching { JSONObject(valore as String) }.getOrNull()
            if (voce == null) { dimentica(context, queueId); continue }
            mostra(
                context  = context,
                queueId  = queueId,
                titolo   = voce.optString("titolo", "Promemoria"),
                testo    = voce.optString("testo", ""),
                completa = voce.optBoolean("completa", false),
                annulla  = voce.optBoolean("annulla", true),
                silenziosa = true,
            )
        }
    }
}
