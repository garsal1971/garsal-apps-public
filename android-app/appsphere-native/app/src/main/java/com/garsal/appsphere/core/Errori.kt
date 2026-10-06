package com.garsal.appsphere.core

/**
 * Il testo di un errore da mostrare a schermo.
 *
 * ⚠️ Il messaggio delle eccezioni di supabase-kt porta in coda l'indirizzo della chiamata e
 * TUTTI gli header, `Authorization: Bearer <token>` compreso: scritto in un avviso metteva a
 * schermo il token di accesso, e per giunta riempiva la finestra coprendo l'errore vero. Si tiene
 * la sola parte prima di «URL:»; il messaggio intero resta nel `Log`.
 */
fun Throwable.messaggioBreve(ripiego: String = "connessione assente"): String {
    val m = message?.trim().orEmpty()
    if (m.isEmpty()) return ripiego
    val taglio = Regex("""\s*\b(URL|Headers|Http Method|Status)\s*:""").find(m)?.range?.first ?: m.length
    return m.substring(0, taglio).trim().ifEmpty { ripiego }
}
