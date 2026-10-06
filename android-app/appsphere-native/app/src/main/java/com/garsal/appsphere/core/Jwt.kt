package com.garsal.appsphere.core

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lettura dei claim di un JWT, senza verificarne la firma.
 *
 * Serve a una cosa sola: sapere chi è l'utente (`sub`, cioè lo stesso valore
 * che le RLS leggono come `auth.uid()`) anche quando la chiamata a `/user` non
 * ha risposto. Il token arriva dal server nel fragment del deep link e viene
 * comunque riverificato da PostgREST a ogni richiesta: qui non si sta decidendo
 * niente sulla base della firma, si sta solo leggendo un'etichetta.
 */
object Jwt {

    private val json = Json { ignoreUnknownKeys = true }

    fun claim(token: String, nome: String): String? = try {
        val payload = token.split(".").getOrNull(1)
        payload?.let {
            val grezzo = Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            json.parseToJsonElement(String(grezzo, Charsets.UTF_8))
                .jsonObject[nome]
                ?.jsonPrimitive
                ?.content
        }
    } catch (e: Exception) {
        Log.w("AppSphereAuth", "claim '$nome' non leggibile dal JWT: ${e.message}")
        null
    }

    /**
     * Il token è ancora buono fra [margineMs] millisecondi?
     *
     * ⚠️ **Un token che c'è non è un token che vale**: un access token di
     * Supabase dura **un'ora**, e chi lo legge da `currentSessionOrNull()` per
     * mandarlo a mano — oggi il solo `ForziereDrive` — si trova in mano quello
     * scaduto ogni volta che il rinnovo automatico non è arrivato in tempo. Su
     * un telefono succede: il job di rinnovo dorme fino all'80 % della scadenza,
     * e un `delay` non sveglia un telefono che dorme.
     *
     * ⚠️ **Il margine non è prudenza generica**: senza, si parte con un token
     * che scade nel mezzo dell'operazione — cioè con le 24 parole già scritte e
     * un file già a metà. È lo stesso mezzo minuto di `tokenVivo()` in
     * `forziere.html`, che decide la stessa cosa allo stesso modo: leggendo
     * `exp` dal token che si sta per mandare, e non da un campo accanto che il
     * giorno che i due divergono direbbe un'altra cosa.
     *
     * Senza `exp` leggibile torna `false`: si rinnova, che è la direzione
     * innocua — un rinnovo in più costa un viaggio di rete, un token morto
     * costa l'operazione.
     */
    fun vivo(token: String, margineMs: Long = 30_000): Boolean {
        val exp = claim(token, "exp")?.toLongOrNull() ?: return false
        return exp * 1000 > System.currentTimeMillis() + margineMs
    }
}
