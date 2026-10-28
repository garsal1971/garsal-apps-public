package com.garsal.pronuncia

import android.content.Context
import android.content.SharedPreferences

/** Le voci en-GB fra cui si sceglie. L'id è quello che Azure vuole nel campo voce. */
enum class Voce(val id: String, val etichetta: String) {
    SONIA("en-GB-SoniaNeural", "Sonia (donna)"),
    RYAN("en-GB-RyanNeural", "Ryan (uomo)");

    companion object { fun da(id: String?) = entries.firstOrNull { it.id == id } ?: SONIA }
}

/** Da dove si ascolta. Si usa dalla fase 4: oggi si sceglie e si ricorda. */
enum class Microfono(val etichetta: String) {
    TELEFONO("📱 Telefono"),
    CUFFIE("🎧 Cuffie Bluetooth");

    companion object { fun da(n: String?) = entries.firstOrNull { it.name == n } ?: TELEFONO }
}

/** In che lingua l'app commenta i tentativi. Si usa dalla fase 3. */
enum class LinguaFeedback(val etichetta: String) {
    INGLESE("🇬🇧 Inglese"),
    ITALIANO("🇮🇹 Italiano");

    companion object { fun da(n: String?) = entries.firstOrNull { it.name == n } ?: INGLESE }
}

/**
 * Preferenze e sessione, nelle preferenze di **questo** telefono.
 *
 * ⚠️ I token Supabase stanno qui e non in un archivio cifrato: è lo stesso
 * livello di protezione della sessione di supabase-kt nell'APK nativa, e il
 * token da solo apre una cosa sola — la Edge Function del token Azure — perché
 * l'app non legge nessuna tabella.
 */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("pronuncia", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = p.getString("access_token", null)
        set(v) = p.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = p.getString("refresh_token", null)
        set(v) = p.edit().putString("refresh_token", v).apply()

    var voce: Voce
        get() = Voce.da(p.getString("voce", null))
        set(v) = p.edit().putString("voce", v.id).apply()

    var microfono: Microfono
        get() = Microfono.da(p.getString("microfono", null))
        set(v) = p.edit().putString("microfono", v.name).apply()

    var linguaFeedback: LinguaFeedback
        get() = LinguaFeedback.da(p.getString("feedback", null))
        set(v) = p.edit().putString("feedback", v.name).apply()

    /** Il filtro dell'esercizio; `null` = tutti. */
    var livello: String?
        get() = p.getString("livello", null)
        set(v) = p.edit().putString("livello", v).apply()

    var tema: String?
        get() = p.getString("tema", null)
        set(v) = p.edit().putString("tema", v).apply()

    fun esci() = p.edit().remove("access_token").remove("refresh_token").apply()
}
