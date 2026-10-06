package com.garsal.smartblocker

object Config {
    const val PIN = "2059"
    const val MAX_SNOOZES = 2
    const val SNOOZE_DURATION_MS = 15 * 60 * 1000L   // 15 minuti
    const val CHECK_INTERVAL_MS = 30_000L              // controllo ogni 30s
    const val CHALLENGE_HOLD_MS = 2000L                // tenuta SÌ/NO sfide Ta Firi?

    // ── Profilo compilato (product flavor Gradle) ────────────────────────────────────────────
    // Il perché di ognuno di questi campi sta nel commento di build.gradle.

    /** 'salvatore' (app completa) | 'teresa' (solo notifiche Revolut della sua carta). */
    val PROFILE: String = BuildConfig.PROFILE

    /** Token fisso del profilo: se valorizzato si usa questo e non si chiama la RPC
        get_smart_block_token(), che restituisce sempre e solo il token di Salvatore. */
    val FIXED_DEVICE_TOKEN: String = BuildConfig.FIXED_DEVICE_TOKEN

    /** Se valorizzato, le righe della coda con `app` diverso da questo vengono ignorate. */
    val ONLY_APP: String = BuildConfig.ONLY_APP

    /** true sul telefono di Teresa: niente PIN, niente sfide, solo transazioni Revolut. */
    val IS_NOTIFY_ONLY: Boolean = ONLY_APP.isNotBlank()
}
