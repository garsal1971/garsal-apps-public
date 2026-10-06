package com.garsal.speseingiro

/**
 * Spese in giro — le spese di un viaggio in bici, divise fra due persone.
 *
 * ⚠️ L'APK non fa il login. Ci si accoppia una volta col **codice del viaggio**
 * e da lì in poi si parla con le sole RPC `vg_*`, che riconoscono il telefono
 * dal token ricevuto allora. È la stessa scelta di SOS, e per la stessa
 * ragione: l'app si apre in mezzo alla strada, spesso con poca rete e poca
 * pazienza, e inciampare in una sessione scaduta o in una schermata di Google
 * che chiede di riautenticarsi è il modo peggiore di segnare uno scontrino.
 */
object Config {
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL
    val ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY

    const val HTTP_TIMEOUT_MS = 20000
    /** Il caricamento di una foto vuole più respiro di una RPC. */
    const val UPLOAD_TIMEOUT_MS = 45000

    /** Il lato lungo a cui si riduce la foto prima di caricarla: oltre non si
     *  legge niente di più e si pagano megabyte di rete su una strada di
     *  montagna. Sotto, l'OCR comincia a sbagliare le cifre piccole. */
    const val FOTO_LATO_MAX = 1600
    const val FOTO_QUALITA = 82
}
