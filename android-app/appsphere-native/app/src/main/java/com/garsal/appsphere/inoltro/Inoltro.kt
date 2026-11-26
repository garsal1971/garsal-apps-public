package com.garsal.appsphere.inoltro

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 📨 Inoltro messaggi: gli SMS e i messaggi WhatsApp ricevuti finiscono in
 * `msg_messaggi` passando dalla Edge Function `msg-ingest`, perché Claude li
 * legga dal connettore Supabase.
 *
 * Il giro: [SmsReceiver] / [WhatsAppListener] → [Inoltro.ricevuto] (filtri sul
 * telefono) → coda su file → [InvioWorker] (WorkManager, con la rete) → msg-ingest.
 *
 * ⚠️ I FILTRI STANNO QUI E SOLO QUI: un messaggio scartato non lascia il telefono
 * e non entra nemmeno nella coda. Del messaggio scartato resta nel registro
 * locale il motivo e il mittente, mai il testo.
 * ⚠️ La coda è un file JSON e non Room: sono poche righe, e Room vorrebbe KSP
 * agganciato a Kotlin 2.1.20 per niente.
 */
object Inoltro {

    private const val TAG = "Inoltro"
    const val FONTE_SMS = "sms"
    const val FONTE_WA = "whatsapp"

    data class Messaggio(
        val fonte: String,
        val mittente: String?,
        val chat: String?,
        val testo: String,
        val ricevutoIl: Long,
    ) {
        /** SHA-256 di fonte, mittente, chat, testo e minuto: la chiave dei doppioni. */
        val impronta: String by lazy {
            val base = listOf(fonte, mittente.orEmpty(), chat.orEmpty(), testo, (ricevutoIl / 60_000).toString())
                .joinToString("|")
            MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        fun json(): JSONObject = JSONObject()
            .put("fonte", fonte)
            .put("mittente", mittente ?: JSONObject.NULL)
            .put("chat", chat ?: JSONObject.NULL)
            .put("testo", testo)
            .put("ricevuto_il", java.time.Instant.ofEpochMilli(ricevutoIl).toString())
            .put("impronta", impronta)
    }

    // ---------------------------------------------------------------------
    // Impostazioni (sul telefono): interruttori e liste
    // ---------------------------------------------------------------------

    private const val K_SMS = "sms_acceso"
    private const val K_WA = "wa_acceso"
    const val L_SMS_ESCLUSI = "sms_esclusi"
    const val L_SMS_SOLO = "sms_solo"
    const val L_WA_CHAT = "wa_chat"
    private const val K_CHAT_VISTE = "wa_chat_viste"
    private const val K_REGISTRO = "registro"
    private const val K_VISTI = "impronte_viste"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("inoltro", Context.MODE_PRIVATE)

    /** SMS: acceso di partenza. */
    fun smsAcceso(ctx: Context) = prefs(ctx).getBoolean(K_SMS, true)
    fun setSms(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_SMS, v).apply()

    /** ⚠️ WhatsApp: SPENTO di partenza, finché non si scelgono le chat. */
    fun waAcceso(ctx: Context) = prefs(ctx).getBoolean(K_WA, false)
    fun setWa(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_WA, v).apply()

    fun lista(ctx: Context, chiave: String): List<String> =
        prefs(ctx).getString(chiave, null)?.let { s ->
            runCatching { JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull()
        } ?: emptyList()

    fun setLista(ctx: Context, chiave: String, voci: List<String>) {
        val pulite = voci.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { norm(it) }
        prefs(ctx).edit().putString(chiave, JSONArray(pulite).toString()).apply()
    }

    /** Le chat WhatsApp viste di recente (anche scartate): servono a sceglierle. Solo nomi. */
    fun chatViste(ctx: Context): List<String> = lista(ctx, K_CHAT_VISTE)

    private fun ricordaChat(ctx: Context, nome: String) {
        val v = (listOf(nome) + chatViste(ctx).filter { norm(it) != norm(nome) }).take(30)
        prefs(ctx).edit().putString(K_CHAT_VISTE, JSONArray(v).toString()).apply()
    }

    /** Registro locale degli ultimi eventi (motivo + mittente, mai il testo). */
    fun registro(ctx: Context): List<String> = lista(ctx, K_REGISTRO)

    fun annota(ctx: Context, riga: String) {
        Log.i(TAG, riga)
        val r = "${LocalDateTime.now().format(ORA)} · $riga"
        val v = (listOf(r) + registro(ctx)).take(30)
        prefs(ctx).edit().putString(K_REGISTRO, JSONArray(v).toString()).apply()
    }

    private val ORA = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    // ---------------------------------------------------------------------
    // Confronto dei nomi
    // ---------------------------------------------------------------------

    /** Minuscolo, senza spazi né punteggiatura. Un numero vale per le ultime 9 cifre. */
    fun norm(s: String): String {
        val cifre = s.filter { it.isDigit() }
        val soloNumero = s.all { it.isDigit() || it in "+ -()./" }
        if (soloNumero && cifre.length >= 6) return cifre.takeLast(9)
        return s.lowercase().filter { it.isLetterOrDigit() }
    }

    fun inLista(nome: String?, lista: List<String>): Boolean {
        if (nome.isNullOrBlank()) return false
        val n = norm(nome)
        return n.isNotEmpty() && lista.any { norm(it) == n }
    }

    // ---------------------------------------------------------------------
    // Filtro OTP
    // ---------------------------------------------------------------------

    private val PAROLE_OTP = Regex(
        "\\b(codice|codici|otp|password|pin|verifica|verification|code|passcode)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Un numero di 4-8 cifre ISOLATO: niente cifre, `:` `/` `-` `+` attacco, niente
     * «12,» / «12.» prima, niente «,5» / «.5» dopo, niente gruppo di cifre a uno
     * spazio di distanza.
     * ⚠️ Ristretto di proposito: «ore 09:30», «12,50 €», «03/12/2026», «1.250,00»
     * e «+39 333 1234567» passano; «Usa 482913.» no.
     * ⚠️ E un anno da 4 cifre (1900-2099) da solo non basta a scartare: «il 3
     * dicembre 2026» è proprio il messaggio dell'appuntamento che si cerca.
     */
    private val NUMERO_ISOLATO =
        Regex("(?<![\\d:/+\\-]|\\d[.,]|\\d )\\d{4,8}(?![\\d:/\\-]|[.,]\\d| \\d)")

    /** Il motivo per cui il testo sembra un codice, o null se passa. */
    fun motivoOtp(testo: String): String? {
        PAROLE_OTP.find(testo)?.let { return "parola «${it.value.lowercase()}»" }
        val numero = NUMERO_ISOLATO.findAll(testo).map { it.value }
            .firstOrNull { !(it.length == 4 && it.toInt() in 1900..2099) }
        return numero?.let { "numero di ${it.length} cifre" }
    }

    // ---------------------------------------------------------------------
    // Ricezione: filtri e coda
    // ---------------------------------------------------------------------

    /** Il motivo per cui il messaggio NON parte, o null se va in coda. */
    fun motivoScarto(ctx: Context, m: Messaggio): String? {
        if (m.testo.isBlank()) return "vuoto"
        when (m.fonte) {
            FONTE_SMS -> {
                if (!smsAcceso(ctx)) return "SMS spenti"
                if (inLista(m.mittente, lista(ctx, L_SMS_ESCLUSI))) return "mittente escluso"
                val solo = lista(ctx, L_SMS_SOLO)
                if (solo.isNotEmpty() && !inLista(m.mittente, solo)) return "mittente non in elenco"
            }
            FONTE_WA -> {
                if (!waAcceso(ctx)) return "WhatsApp spento"
                val chat = m.chat ?: m.mittente
                if (!inLista(chat, lista(ctx, L_WA_CHAT))) return "chat non scelta"
            }
        }
        return motivoOtp(m.testo)?.let { "sembra un codice ($it)" }
    }

    /** Il punto d'ingresso dei due acquisitori. */
    fun ricevuto(ctx: Context, m: Messaggio) {
        if (m.fonte == FONTE_WA) (m.chat ?: m.mittente)?.let { ricordaChat(ctx, it) }
        val chi = listOfNotNull(m.chat, m.mittente).joinToString(" · ").ifEmpty { "?" }
        motivoScarto(ctx, m)?.let { annota(ctx, "✗ ${m.fonte} $chi: $it"); return }
        if (!primaVolta(ctx, m.impronta)) return  // la stessa notifica ripresentata
        accoda(ctx, listOf(m))
        annota(ctx, "→ ${m.fonte} $chi: in coda")
        avviaInvio(ctx)
    }

    /** Le ultime 500 impronte già messe in coda: WhatsApp ripresenta la stessa notifica. */
    @Synchronized
    private fun primaVolta(ctx: Context, impronta: String): Boolean {
        val viste = lista(ctx, K_VISTI)
        if (impronta in viste) return false
        val v = (listOf(impronta) + viste).take(500)
        prefs(ctx).edit().putString(K_VISTI, JSONArray(v).toString()).apply()
        return true
    }

    // ---------------------------------------------------------------------
    // Coda su file
    // ---------------------------------------------------------------------

    private fun fileCoda(ctx: Context) = File(ctx.applicationContext.filesDir, "inoltro_coda.json")

    @Synchronized
    fun coda(ctx: Context): List<JSONObject> {
        val f = fileCoda(ctx)
        if (!f.exists()) return emptyList()
        return runCatching { JSONArray(f.readText()).let { a -> List(a.length()) { a.getJSONObject(it) } } }
            .getOrElse { Log.w(TAG, "coda illeggibile, la riparto vuota", it); emptyList() }
    }

    @Synchronized
    private fun scriviCoda(ctx: Context, righe: List<JSONObject>) {
        val f = fileCoda(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONArray(righe).toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun accoda(ctx: Context, msgs: List<Messaggio>) {
        val presenti = coda(ctx)
        val gia = presenti.map { it.optString("impronta") }.toSet()
        scriviCoda(ctx, presenti + msgs.filter { it.impronta !in gia }.map { it.json() })
    }

    @Synchronized
    fun togli(ctx: Context, impronte: Set<String>) {
        scriviCoda(ctx, coda(ctx).filter { it.optString("impronta") !in impronte })
    }

    /**
     * Mette in fila l'invio, appena c'è rete. ⚠️ APPEND_OR_REPLACE: un messaggio
     * arrivato mentre il worker sta già inviando non deve aspettare il prossimo.
     */
    fun avviaInvio(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<InvioWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork("inoltro-invio", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}
