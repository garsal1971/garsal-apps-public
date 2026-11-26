package com.garsal.appsphere.inoltro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.garsal.appsphere.MainActivity
import com.garsal.appsphere.R
import com.garsal.appsphere.core.Destinazione
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
 * Il giro: [SmsReceiver] / [WhatsAppListener] → [Inoltro.ricevuto] → una delle tre strade:
 * - **scartato** (fonte spenta, mittente/chat fra i filtrati);
 * - **inviato**: mittente/chat fra gli inoltrati → coda su file → [InvioWorker] → msg-ingest;
 * - **⏳ da decidere**: mittente/chat mai visto → resta sul telefono finché nella schermata
 *   non si sceglie «Inoltra sempre» o «Filtra sempre» ([decidi]), con una notifica che lo dice.
 *
 * ⚠️ I FILTRI STANNO QUI E SOLO QUI: un messaggio scartato o in attesa non lascia il telefono.
 * Del messaggio scartato resta nel registro locale il motivo e il mittente, mai il testo.
 * ⚠️ Nessun filtro automatico dei codici (tolto nella 1.0.165): un codice segue le regole
 * di ogni altro messaggio, e chi non lo vuole filtra il mittente.
 * ⚠️ La coda è un file JSON e non Room: sono poche righe, e Room vorrebbe KSP
 * agganciato a Kotlin 2.1.20 per niente.
 */
object Inoltro {

    private const val TAG = "Inoltro"
    const val FONTE_SMS = "sms"
    const val FONTE_WA = "whatsapp"

    /** Quanto un messaggio resta in «da decidere» prima di essere buttato. */
    private const val GIORNI_ATTESA = 30L

    data class Messaggio(
        val fonte: String,
        val mittente: String?,
        val chat: String?,
        val testo: String,
        val ricevutoIl: Long,
        /** Quando è entrato in «da decidere» (0 = mai): l'attesa si conta da qui, non dall'arrivo. */
        val dal: Long = 0,
    ) {
        /** SHA-256 di fonte, mittente, chat, testo e minuto: la chiave dei doppioni. */
        val impronta: String by lazy {
            val base = listOf(fonte, mittente.orEmpty(), chat.orEmpty(), testo, (ricevutoIl / 60_000).toString())
                .joinToString("|")
            MessageDigest.getInstance("SHA-256").digest(base.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        /** Chi si decide: il mittente per un SMS, la chat (o la persona) per WhatsApp. */
        val chiave: String get() = (if (fonte == FONTE_WA) chat ?: mittente else mittente) ?: "(sconosciuto)"

        fun json(): JSONObject = JSONObject()
            .put("fonte", fonte)
            .put("mittente", mittente ?: JSONObject.NULL)
            .put("chat", chat ?: JSONObject.NULL)
            .put("testo", testo)
            .put("ricevuto_il", java.time.Instant.ofEpochMilli(ricevutoIl).toString())
            .put("impronta", impronta)

        companion object {
            fun da(o: JSONObject): Messaggio = Messaggio(
                fonte = o.getString("fonte"),
                mittente = if (o.isNull("mittente")) null else o.optString("mittente"),
                chat = if (o.isNull("chat")) null else o.optString("chat"),
                testo = o.optString("testo"),
                ricevutoIl = o.optLong("ms").takeIf { it > 0 }
                    ?: runCatching { java.time.Instant.parse(o.getString("ricevuto_il")).toEpochMilli() }
                        .getOrDefault(System.currentTimeMillis()),
                dal = o.optLong("dal"),
            )
        }
    }

    // ---------------------------------------------------------------------
    // Impostazioni (sul telefono): interruttori ed elenchi
    // ---------------------------------------------------------------------

    private const val K_SMS = "sms_acceso"
    private const val K_WA = "wa_acceso"

    /** SMS: mittenti inoltrati sempre. ⚠️ La chiave resta `sms_solo` della v1.0.161. */
    const val L_SMS_INOLTRATI = "sms_solo"
    const val L_SMS_FILTRATI = "sms_esclusi"
    const val L_WA_INOLTRATE = "wa_chat"
    const val L_WA_FILTRATE = "wa_filtrate"
    private const val K_REGISTRO = "registro"
    private const val K_VISTI = "impronte_viste"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("inoltro", Context.MODE_PRIVATE)

    fun smsAcceso(ctx: Context) = prefs(ctx).getBoolean(K_SMS, true)
    fun setSms(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_SMS, v).apply()

    /** ⚠️ WhatsApp: SPENTO di partenza. */
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

    /** Gli elenchi di una fonte: (inoltrati, filtrati). */
    fun elenchi(fonte: String) =
        if (fonte == FONTE_WA) L_WA_INOLTRATE to L_WA_FILTRATE else L_SMS_INOLTRATI to L_SMS_FILTRATI

    /**
     * Mette [voce] nell'elenco giusto e la toglie dall'altro: un mittente non può essere
     * insieme inoltrato e filtrato, o quale dei due vince sarebbe una scelta del codice.
     */
    fun metti(ctx: Context, fonte: String, voce: String, inoltra: Boolean) {
        val (inoltrati, filtrati) = elenchi(fonte)
        val (dove, altro) = if (inoltra) inoltrati to filtrati else filtrati to inoltrati
        setLista(ctx, altro, lista(ctx, altro).filter { norm(it) != norm(voce) })
        setLista(ctx, dove, lista(ctx, dove) + voce)
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
    // Ricezione: le tre strade
    // ---------------------------------------------------------------------

    sealed interface Esito {
        object Invia : Esito
        object DaDecidere : Esito
        data class Scarta(val motivo: String) : Esito
    }

    fun esito(ctx: Context, m: Messaggio): Esito {
        if (m.testo.isBlank()) return Esito.Scarta("vuoto")
        if (m.fonte == FONTE_SMS && !smsAcceso(ctx)) return Esito.Scarta("SMS spenti")
        if (m.fonte == FONTE_WA && !waAcceso(ctx)) return Esito.Scarta("WhatsApp spento")
        val (inoltrati, filtrati) = elenchi(m.fonte)
        if (inLista(m.chiave, lista(ctx, filtrati))) return Esito.Scarta("filtrato")
        if (inLista(m.chiave, lista(ctx, inoltrati))) return Esito.Invia
        return Esito.DaDecidere
    }

    /** Il punto d'ingresso dei due acquisitori. */
    fun ricevuto(ctx: Context, m: Messaggio) {
        val chi = listOfNotNull(m.chat, m.mittente).joinToString(" · ").ifEmpty { "?" }
        when (val e = esito(ctx, m)) {
            is Esito.Scarta -> annota(ctx, "✗ ${m.fonte} $chi: ${e.motivo}")
            Esito.Invia -> {
                if (!primaVolta(ctx, m.impronta)) return  // la stessa notifica ripresentata
                accoda(ctx, listOf(m))
                annota(ctx, "→ ${m.fonte} $chi: in coda")
                avviaInvio(ctx)
            }
            Esito.DaDecidere -> {
                if (!primaVolta(ctx, m.impronta)) return
                mettiInAttesa(ctx, m)
                annota(ctx, "⏳ ${m.fonte} $chi: da decidere")
                notificaAttesa(ctx)
            }
        }
    }

    /** Le ultime 500 impronte già viste: WhatsApp ripresenta la stessa notifica. */
    @Synchronized
    private fun primaVolta(ctx: Context, impronta: String): Boolean {
        val viste = lista(ctx, K_VISTI)
        if (impronta in viste) return false
        val v = (listOf(impronta) + viste).take(500)
        prefs(ctx).edit().putString(K_VISTI, JSONArray(v).toString()).apply()
        return true
    }

    // ---------------------------------------------------------------------
    // ⏳ Da decidere (file sul telefono)
    // ---------------------------------------------------------------------

    private fun fileAttesa(ctx: Context) = File(ctx.applicationContext.filesDir, "inoltro_attesa.json")

    /**
     * I messaggi da decidere, dal più recente. Quelli in attesa da oltre [GIORNI_ATTESA] si
     * buttano qui. ⚠️ Si conta da quando sono ENTRATI in attesa ([Messaggio.dal]), non da
     * quando sono arrivati: un SMS di un anno fa importato oggi si butterebbe sul posto.
     */
    @Synchronized
    fun attesa(ctx: Context): List<Messaggio> {
        val tutti = leggiFile(fileAttesa(ctx)).map { Messaggio.da(it) }
        val limite = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(GIORNI_ATTESA)
        val vivi = tutti.filter { (it.dal.takeIf { d -> d > 0 } ?: it.ricevutoIl) >= limite }
        if (vivi.size != tutti.size) scriviAttesa(ctx, vivi)
        return vivi.sortedByDescending { it.ricevutoIl }
    }

    @Synchronized
    private fun mettiInAttesa(ctx: Context, m: Messaggio) = mettiInAttesa(ctx, listOf(m))

    /** Aggiunge quelli che non ci sono già; torna quanti ne ha aggiunti. */
    @Synchronized
    private fun mettiInAttesa(ctx: Context, msgs: List<Messaggio>): Int {
        val presenti = attesa(ctx)
        val gia = presenti.map { it.impronta }.toHashSet()
        val ora = System.currentTimeMillis()
        val nuovi = msgs.filter { gia.add(it.impronta) }.map { it.copy(dal = ora) }
        if (nuovi.isNotEmpty()) scriviAttesa(ctx, presenti + nuovi)
        return nuovi.size
    }

    private fun scriviAttesa(ctx: Context, msgs: List<Messaggio>) =
        scriviFile(fileAttesa(ctx), msgs.map { it.json().put("ms", it.ricevutoIl).put("dal", it.dal) })

    data class Smistati(val inviati: Int, val filtrati: Int, val gia: Int)

    /**
     * 📥 L'IMPIANTO: gli SMS importati vanno tutti in tabella, senza passare da «⏳ Da
     * decidere». Restano fuori solo fonte spenta e mittenti filtrati ([esito] che scarta).
     * ⚠️ Gli elenchi non si toccano: da dopo l'impianto un mittente mai deciso torna a
     * passare dalla scelta. ⚠️ Non passa da [primaVolta]: sono troppi per le sue 500
     * impronte, e i doppioni li fermano già la coda e l'impronta unica di `msg_messaggi`.
     */
    @Synchronized
    fun smista(ctx: Context, msgs: List<Messaggio>, periodo: String = "tutti"): Smistati {
        val invia = msgs.filter { esito(ctx, it) !is Esito.Scarta }
        val inCoda = coda(ctx).map { it.optString("impronta") }.toSet()
        val daInviare = invia.filter { it.impronta !in inCoda }
        if (daInviare.isNotEmpty()) { accoda(ctx, daInviare); avviaInvio(ctx) }
        val r = Smistati(daInviare.size, msgs.size - invia.size, invia.size - daInviare.size)
        annota(ctx, "📥 impianto di ${msgs.size} SMS ($periodo): ${r.inviati} in coda, " +
            "${r.filtrati} filtrati, ${r.gia} già in coda")
        return r
    }

    /**
     * La scelta su un mittente/chat: entra nell'elenco giusto e i suoi messaggi in attesa
     * partono («inoltra») o si buttano («filtra»). Torna quanti messaggi ha toccato.
     */
    @Synchronized
    fun decidi(ctx: Context, fonte: String, chiave: String, inoltra: Boolean): Int {
        metti(ctx, fonte, chiave, inoltra)
        val (suoi, altri) = attesa(ctx).partition { it.fonte == fonte && norm(it.chiave) == norm(chiave) }
        scriviAttesa(ctx, altri)
        if (inoltra && suoi.isNotEmpty()) {
            accoda(ctx, suoi)
            avviaInvio(ctx)
        }
        annota(ctx, "${if (inoltra) "✓ inoltra" else "✗ filtra"} sempre $fonte $chiave (${suoi.size} in attesa)")
        notificaAttesa(ctx)
        return suoi.size
    }

    // ---------------------------------------------------------------------
    // La notifica «N messaggi da decidere»
    // ---------------------------------------------------------------------

    private const val CANALE = "inoltro"
    private const val ID_NOTIFICA = 7301

    /** Mostra (o aggiorna, o toglie se non resta niente) la notifica dei messaggi da decidere. */
    fun notificaAttesa(ctx: Context) {
        val app = ctx.applicationContext
        val n = attesa(app).size
        val nm = NotificationManagerCompat.from(app)
        if (n == 0) { runCatching { nm.cancel(ID_NOTIFICA) }; return }
        app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CANALE, "Inoltro messaggi", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val mittenti = attesa(app).map { it.chiave }.distinct()
        val apri = PendingIntent.getActivity(
            app, ID_NOTIFICA,
            Intent(app, MainActivity::class.java)
                .setAction(Destinazione.INOLTRO.azione)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val testo = "Da: " + mittenti.take(4).joinToString(", ") + if (mittenti.size > 4) "…" else ""
        val notifica = NotificationCompat.Builder(app, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(if (n == 1) "📨 1 messaggio da decidere" else "📨 $n messaggi da decidere")
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(apri)
            .build()
        runCatching { nm.notify(ID_NOTIFICA, notifica) }
    }

    // ---------------------------------------------------------------------
    // Coda d'invio su file
    // ---------------------------------------------------------------------

    private fun fileCoda(ctx: Context) = File(ctx.applicationContext.filesDir, "inoltro_coda.json")

    private fun leggiFile(f: File): List<JSONObject> {
        if (!f.exists()) return emptyList()
        return runCatching { JSONArray(f.readText()).let { a -> List(a.length()) { a.getJSONObject(it) } } }
            .getOrElse { Log.w(TAG, "${f.name} illeggibile, riparto vuoto", it); emptyList() }
    }

    private fun scriviFile(f: File, righe: List<JSONObject>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONArray(righe).toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun coda(ctx: Context): List<JSONObject> = leggiFile(fileCoda(ctx))

    @Synchronized
    fun accoda(ctx: Context, msgs: List<Messaggio>) {
        val presenti = coda(ctx)
        val gia = presenti.map { it.optString("impronta") }.toSet()
        scriviFile(fileCoda(ctx), presenti + msgs.filter { it.impronta !in gia }.map { it.json() })
    }

    @Synchronized
    fun togli(ctx: Context, impronte: Set<String>) {
        scriviFile(fileCoda(ctx), coda(ctx).filter { it.optString("impronta") !in impronte })
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
