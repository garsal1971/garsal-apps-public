package com.garsal.appsphere.notifiche

import android.content.Context
import android.os.Build
import android.util.Log
import com.garsal.appsphere.BuildConfig
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Le notifiche push: il token di questo telefono e i pulsanti della notifica.
 *
 * ⚠️ **Firebase può non esserci.** `google-services.json` nasce sulla console e
 * il modulo lo applica solo se il file c'è (vedi `app/build.gradle`): senza,
 * l'app si compila e funziona in tutto il resto, e qui `disponibile()` risponde
 * `false`. Ogni chiamata a Firebase è quindi dentro un `runCatching` — una
 * `FirebaseApp` non inizializzata **solleva**, e una notifica mancata non deve
 * poter portarsi dietro l'avvio dell'app.
 *
 * ⚠️ **Le regole dei pulsanti non sono qui.** ✅ Fatto, ⏸ Rinvia e ❌ Annulla
 * passano dalla Edge Function `notification-action`, che è l'unica
 * implementazione e la chiama anche il bot Telegram: completamento, punti e
 * archivi devono avere un esito solo, qualunque sia lo schermo da cui si preme.
 */
object Push {

    private const val TAG = "Push"

    /** C'è una FirebaseApp inizializzata? Senza, le push semplicemente non ci sono. */
    fun disponibile(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /**
     * Il token di questa installazione.
     *
     * Non si passa da `kotlinx-coroutines-play-services` solo per un `await()`:
     * il listener costa tre righe e una dipendenza in meno nell'APK.
     */
    private suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { t -> if (cont.isActive) cont.resume(t) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "token non ottenuto: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }.onFailure {
            Log.w(TAG, "FirebaseMessaging non disponibile: ${it.message}")
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * Scrive questo telefono in `cm_push_devices`.
     *
     * ⚠️ **Si riscrive a ogni avvio, in upsert sul token**: FCM lo rigenera da
     * sé (reinstallazione, ripristino da backup, dati svuotati) e un token
     * vecchio in tabella è una notifica che parte e non arriva. `last_seen_at`
     * dice quando quel telefono si è fatto vivo l'ultima volta.
     *
     * ⚠️ Un token che passa a un ALTRO account resta però attaccato al primo:
     * la RLS non lascia riscrivere la riga di un altro utente, e l'upsert
     * fallisce in silenzio. Su un telefono di famiglia dove si cambia account
     * la riga vecchia va spenta a mano — è il caso che questa app non ha, ma
     * conviene saperlo prima di cercarlo nel codice.
     */
    suspend fun registra(context: Context) = withContext(Dispatchers.IO) {
        if (!disponibile(context)) {
            Log.i(TAG, "Firebase non configurato: nessun token da registrare")
            return@withContext
        }
        val utente = AuthRepo.userId()
        if (utente == null) {
            Log.i(TAG, "nessuna sessione: il token si registra dopo il login")
            return@withContext
        }
        val token = token() ?: return@withContext

        runCatching {
            Supabase.client().postgrest.from("cm_push_devices").upsert(
                buildJsonObject {
                    put("user_id", utente)
                    put("token", token)
                    put("platform", "android")
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("enabled", true)
                    put("last_seen_at", Instant.now().toString())
                }
            ) { onConflict = "token" }
        }.onSuccess {
            Log.i(TAG, "telefono registrato (…${token.takeLast(6)})")
        }.onFailure {
            Log.e(TAG, "registrazione del telefono fallita: ${it.message}")
        }
    }

    /**
     * Preme un pulsante della notifica.
     *
     * @param minuti solo per il rinvio: quanti minuti più avanti.
     * @return il messaggio da mostrare, o `null` se non è andata.
     */
    suspend fun azione(queueId: String, azione: String, minuti: Int? = null): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val richiesta = buildJsonObject {
                    put("queue_id", queueId)
                    put("action", azione)
                    if (minuti != null) put("minutes", minuti)
                }
                // Il corpo va come stringa già serializzata e la risposta si
                // legge come testo: così non si dipende da come è configurata
                // la negoziazione del contenuto dentro il client di supabase-kt,
                // che è roba sua e può cambiare con la libreria.
                val risposta = Supabase.client().functions.invoke("notification-action") {
                    contentType(ContentType.Application.Json)
                    setBody(richiesta.toString())
                }
                val corpo = Json.parseToJsonElement(risposta.bodyAsText()).jsonObject
                val ok = corpo["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!ok) {
                    Log.w(TAG, "azione $azione rifiutata: ${corpo["error"]?.jsonPrimitive?.contentOrNull}")
                    null
                } else {
                    corpo["message"]?.jsonPrimitive?.contentOrNull ?: "Fatto"
                }
            }.getOrElse {
                Log.e(TAG, "azione $azione non riuscita: ${it.message}")
                null
            }
        }
}
