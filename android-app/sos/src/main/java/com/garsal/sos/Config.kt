package com.garsal.sos

object Config {
    const val SUPABASE_URL = "https://jajlmmdsjlvzgcxiiypk.supabase.co"

    /** Anon key pubblica: da sola non apre nulla — le tre RPC dell'APK sono
        SECURITY DEFINER e riconoscono il telefono dal codice di accoppiamento,
        le tabelle sos_* restano dietro la RLS del proprietario. */
    const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImphamxtbWRzamx2emdjeGlpeXBrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njk5NTU0NjYsImV4cCI6MjA4NTUzMTQ2Nn0.ikaipwxOvIn43epayQ4mSZQkXtin3aaGEPouafwJFxU"

    /** Quanto va tenuto premuto «mi arrendo» per interrompere il countdown.
        Una via d'uscita ci deve essere — un blocco senza uscita, su un telefono
        che è anche il modo per chiamare qualcuno, è un rischio e non una
        funzionalità — ma deve costare un gesto deliberato, non un tocco. */
    const val GIVE_UP_HOLD_MS = 3000L

    /** Velocità del testo motivante che scorre sotto il countdown. */
    const val TICKER_DP_PER_SEC = 55f

    /** Quanto resta a schermo il riepilogo (punti, tempo del prossimo giro). */
    const val RESULT_LINGER_MS = 6000L

    /** Timeout delle chiamate: il countdown non le aspetta mai, ma neanche
        deve restare appeso a una rete che non risponde. */
    const val HTTP_TIMEOUT_MS = 12000
}
