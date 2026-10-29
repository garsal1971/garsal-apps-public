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

/** I tre controlli severi del `Giudizio`, con la soglia di partenza per le
 *  frasi e per le parole singole (0 = spento). Su una parola sola il suono
 *  peggiore è il solo che dica qualcosa: la parola peggiore è lei stessa, e il
 *  ritmo di una parola non c'è. */
enum class Controllo(val chiave: String, val etichetta: String, val frase: Int, val parola: Int) {
    PAROLA("parola", "Parola peggiore", 70, 0),
    SUONO("suono", "Suono peggiore", 60, 50),
    RITMO("ritmo", "Ritmo e intonazione", 70, 0),
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

    /** I tre controlli severi (vedi `Giudizio`), con soglie diverse per le
     *  frasi e per le parole singole: la soglia, o `null` = spento.
     *  ⚠️ Le chiavi delle frasi sono quelle della v1.8.0: chi le aveva già
     *  cambiate le ritrova. Le parole hanno il suffisso `_parole`. */
    fun soglia(c: Controllo, parole: Boolean): Int? =
        p.getInt(chiave(c, parole), if (parole) c.parola else c.frase).takeIf { it > 0 }

    fun impostaSoglia(c: Controllo, parole: Boolean, v: Int?) =
        p.edit().putInt(chiave(c, parole), v ?: 0).apply()

    private fun chiave(c: Controllo, parole: Boolean) = "soglia_${c.chiave}" + if (parole) "_parole" else ""

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

    /** Mani libere: dopo il tentativo l'app fa riascoltare la registrazione,
     *  poi dà il voto. Letta a ogni tentativo: cambiarla a ciclo acceso vale subito. */
    var riascoltoCiclo: Boolean
        get() = p.getBoolean("riascolto_ciclo", true)
        set(v) = p.edit().putBoolean("riascolto_ciclo", v).apply()

    fun esci() = p.edit().remove("access_token").remove("refresh_token").apply()
}
