package com.garsal.pronuncia

import android.content.Context
import android.content.SharedPreferences

/** L'inglese su cui ci si esercita: decide la lingua della valutazione di
 *  Azure e le voci che leggono le frasi. Le frasi restano scritte all'inglese
 *  (colour, centre): la pronuncia cambia, l'ortografia no. */
enum class Accento(val lingua: String, val etichetta: String, val donna: String, val uomo: String) {
    BRITANNICO("en-GB", "🇬🇧 Britannico", "en-GB-SoniaNeural", "en-GB-RyanNeural"),
    AMERICANO("en-US", "🇺🇸 Americano", "en-US-AvaNeural", "en-US-AndrewNeural");

    companion object { fun da(n: String?) = entries.firstOrNull { it.name == n } ?: BRITANNICO }
}

/** La voce si sceglie per genere: il nome vero lo decide l'accento. */
enum class Voce {
    DONNA, UOMO;

    /** L'id che Azure vuole nel campo voce. */
    fun id(a: Accento) = if (this == UOMO) a.uomo else a.donna
    fun etichetta(a: Accento) = id(a).split('-').last().removeSuffix("Neural") +
        if (this == UOMO) " (uomo)" else " (donna)"

    companion object {
        /** ⚠️ Fino alla v1.7.0 si archiviava l'id della voce («en-GB-RyanNeural»):
         *  le preferenze vecchie si rileggono da lì. */
        fun da(s: String?) = entries.firstOrNull { it.name == s } ?: if (s?.contains("Ryan") == true) UOMO else DONNA
    }
}

/** Da dove si ascolta nel ciclo a mani libere (`CicloService.instrada`). */
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
        set(v) = p.edit().putString("voce", v.name).apply()

    var accento: Accento
        get() = Accento.da(p.getString("accento", null))
        set(v) = p.edit().putString("accento", v.name).apply()

    /** I tre controlli severi (vedi `Giudizio`): la soglia, o `null` = spento. */
    var sogliaParola: Int?
        get() = p.getInt("soglia_parola", Giudizio.PAROLA).takeIf { it > 0 }
        set(v) = p.edit().putInt("soglia_parola", v ?: 0).apply()

    var sogliaSuono: Int?
        get() = p.getInt("soglia_suono", Giudizio.SUONO).takeIf { it > 0 }
        set(v) = p.edit().putInt("soglia_suono", v ?: 0).apply()

    var sogliaRitmo: Int?
        get() = p.getInt("soglia_ritmo", Giudizio.RITMO).takeIf { it > 0 }
        set(v) = p.edit().putInt("soglia_ritmo", v ?: 0).apply()

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

    /** Cosa si esercita: le frasi intere o le parole sole. Vale per
     *  l'esercizio e per il ciclo a mani libere. */
    var parole: Boolean
        get() = p.getBoolean("parole", false)
        set(v) = p.edit().putBoolean("parole", v).apply()

    fun esci() = p.edit().remove("access_token").remove("refresh_token").apply()
}
