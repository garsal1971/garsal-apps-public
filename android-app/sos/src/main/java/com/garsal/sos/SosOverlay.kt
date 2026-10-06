package com.garsal.sos

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * La schermata che blocca il telefono per la durata del countdown.
 *
 * È una finestra TYPE_APPLICATION_OVERLAY, come quella di Smart Blocker: sta
 * sopra qualsiasi app — launcher compreso — e non se ne va col tasto Home,
 * perché non è un'Activity nello stack ma una finestra di sistema. Il tasto
 * Indietro non la raggiunge (non è focusabile per la navigazione) e i tocchi
 * si fermano qui: sotto non passa niente.
 *
 * Chi la fa vivere è SosSessionService, che è in primo piano: senza quel
 * servizio Android potrebbe fermare il processo e la finestra sparirebbe a
 * metà giro — cioè esattamente nel momento in cui deve esserci.
 *
 * L'overlay non decide niente: conta i secondi che gli vengono passati e
 * riporta indietro la risposta scelta. Punti e durata del giro dopo li calcola
 * il server.
 */
class SosOverlay(
    private val ctx: Context,
    private val tipo: SosType,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** L'utente ha risposto a «com'è andata?». */
        fun onRisposta(outcome: SosOutcome, completato: Boolean)
        /** Il countdown è stato interrotto con «mi arrendo»: chi conta i secondi
            deve smettere, o arrivando a zero rimpiazzerebbe la domanda già a schermo. */
        fun onInterrotto()
        /** L'overlay è stato chiuso: il servizio può fermarsi. */
        fun onChiuso()
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var contenuto: LinearLayout? = null

    private var tvTimer: TextView? = null
    private var barra: ProgressBar? = null

    private val coloreSos = colore(tipo.color)
    private val sfondo = scurisci(coloreSos, 0.22f)

    // ── Ciclo di vita della finestra ─────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        PixelFormat.OPAQUE
    )

    /** true se la finestra è davvero a schermo. L'esito va restituito: senza il
        permesso «Visualizza sopra altre app» qui non si può fare niente, e chi
        chiama deve accorgersene invece di far partire un countdown invisibile. */
    fun show(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "show() saltato: manca il permesso overlay")
            return false
        }
        val r = FrameLayout(ctx).apply {
            setBackgroundColor(sfondo)
            // Il tocco si ferma qui anche dove non c'è nulla da premere.
            isClickable = true
            isFocusable = true
        }
        val sv = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(24), ctx.dp(28), ctx.dp(24), ctx.dp(28))
        }
        sv.addView(c, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(sv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root = r
        contenuto = c
        return try {
            wm.addView(r, params())
            true
        } catch (e: Exception) {
            Log.w(TAG, "errore show(): ${e.message}")
            root = null; contenuto = null
            false
        }
    }

    fun isShowing() = root != null

    fun dismiss() {
        fermaTicker()
        handler.removeCallbacksAndMessages(null)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        contenuto = null
        tvTimer = null
        barra = null
    }

    // ── Fase 1 · il countdown ────────────────────────────────────────────────

    fun mostraCountdown(rimasti: Int, totali: Int) {
        val c = contenuto ?: return
        c.removeAllViews()
        fermaTicker()

        c.aggiungi(testo(ctx, tipo.emoji, 46f), 0, ctx)
        c.aggiungi(testo(ctx, tipo.name, 24f, Color.WHITE, bold = true), 6, ctx)
        if (tipo.description.isNotBlank())
            c.aggiungi(testo(ctx, tipo.description, 16f, Color.WHITE, alpha = 0.75f), 2, ctx)

        tvTimer = testo(ctx, Model.mmss(rimasti), 68f, Color.WHITE, bold = true).apply {
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        c.aggiungi(tvTimer!!, 18, ctx)

        val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = progressoDi(rimasti, totali)
        }
        barra = pb
        val lpBarra = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(6))
        lpBarra.topMargin = ctx.dp(14)
        c.addView(pb, lpBarra)

        c.aggiungi(costruisciTicker(), 26, ctx)

        c.aggiungi(testo(ctx, "Il telefono resta bloccato fino alla fine.", 13f,
            Color.WHITE, alpha = 0.55f), 26, ctx)

        c.aggiungi(
            buildHoldButton(
                ctx, handler,
                "✋ Tieni premuto per arrenderti",
                "Rilascia per arrenderti",
                Color.WHITE, Config.GIVE_UP_HOLD_MS
            ) { callbacks.onInterrotto(); chiediComeEAndata(completato = false) },
            12, ctx
        )
    }

    private fun progressoDi(rimasti: Int, totali: Int): Int =
        if (totali <= 0) 0 else (1000L * (totali - rimasti) / totali).toInt().coerceIn(0, 1000)

    /** Chiamata ogni secondo dal servizio. */
    fun aggiornaCountdown(rimasti: Int, totali: Int) {
        tvTimer?.text = Model.mmss(rimasti)
        barra?.progress = progressoDi(rimasti, totali)
    }

    // ── Il testo motivante che scorre ───────────────────────────────────────

    private var tickerBox: FrameLayout? = null
    private var tickerTv: TextView? = null
    private var tickerIdx = 0
    private var pxPerFrame = 0f
    private var tickerLarghezza = 0

    private val tickerStep = object : Runnable {
        override fun run() {
            val tv = tickerTv ?: return
            tv.translationX -= pxPerFrame
            // La larghezza si legge da tickerLarghezza e non da tv.width: quest'ultima
            // vale ancora quella della frase precedente finché non passa un layout,
            // e nei primi frame farebbe saltare subito al messaggio dopo.
            if (tv.translationX + tickerLarghezza <= 0f) {
                prossimoMessaggio()   // riprogramma da sé: qui si esce, o le catene diventano due
                return
            }
            handler.postDelayed(this, 16)
        }
    }

    /**
     * Una riga sola che scorre da destra a sinistra, non un paragrafo che va a capo:
     * con l'ingrandimento dei caratteri alto un testo lungo mangerebbe lo schermo e
     * spingerebbe fuori il countdown, che è la cosa che si sta guardando.
     *
     * La larghezza del TextView si **misura** (`paint.measureText`) invece di lasciarla
     * a WRAP_CONTENT: dentro un contenitore largo quanto lo schermo, wrap_content si
     * ferma al bordo e manda il testo a capo — cioè proprio quello che non deve fare.
     */
    private fun costruisciTicker(): View {
        val box = FrameLayout(ctx).apply { clipChildren = true; clipToPadding = true }
        val messaggi = tipo.messages.ifEmpty {
            listOf("Sta passando. Aspetta ancora un momento.")
        }
        val tv = testo(ctx, messaggi[0], 18f, Color.WHITE, bold = true, center = false).apply {
            maxLines = 1
            setSingleLine(true)
        }
        box.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tickerBox = box
        tickerTv = tv
        tickerIdx = 0
        pxPerFrame = Config.TICKER_DP_PER_SEC * ctx.resources.displayMetrics.density * 16f / 1000f

        // La larghezza del contenitore esiste solo dopo il primo posizionamento:
        // prima di allora non c'è nessun punto "fuori dallo schermo" da cui partire.
        box.post { avviaMessaggio(messaggi[tickerIdx]) }
        return box
    }

    private fun avviaMessaggio(txt: String) {
        val tv = tickerTv ?: return
        val box = tickerBox ?: return
        tv.text = txt
        val larghezza = (tv.paint.measureText(txt) + ctx.dp(8)).toInt()
        tickerLarghezza = larghezza
        tv.layoutParams = FrameLayout.LayoutParams(larghezza, ViewGroup.LayoutParams.WRAP_CONTENT)
        tv.requestLayout()
        tv.translationX = box.width.toFloat().coerceAtLeast(1f)
        handler.removeCallbacks(tickerStep)
        handler.postDelayed(tickerStep, 16)
    }

    private fun prossimoMessaggio() {
        val messaggi = tipo.messages.ifEmpty {
            listOf("Sta passando. Aspetta ancora un momento.")
        }
        tickerIdx = (tickerIdx + 1) % messaggi.size
        avviaMessaggio(messaggi[tickerIdx])
    }

    private fun fermaTicker() {
        handler.removeCallbacks(tickerStep)
        tickerBox = null
        tickerTv = null
    }

    // ── Fase 2 · «com'è andata?» ────────────────────────────────────────────

    fun chiediComeEAndata(completato: Boolean) {
        val c = contenuto ?: return
        completatoCorrente = completato
        c.removeAllViews()
        fermaTicker()

        c.aggiungi(testo(ctx, if (completato) "⏱️" else "✋", 42f), 0, ctx)
        c.aggiungi(testo(ctx, "Com'è andata?", 28f, Color.WHITE, bold = true), 8, ctx)
        c.aggiungi(
            testo(ctx,
                if (completato) "${tipo.name} · countdown finito"
                else "${tipo.name} · countdown interrotto",
                15f, Color.WHITE, alpha = 0.7f),
            4, ctx
        )

        if (tipo.outcomes.isEmpty()) {
            // Un SOS senza risposte non è configurato: non si inventa un esito,
            // si dice cosa manca e dove si sistema.
            c.aggiungi(testo(ctx,
                "Questo SOS non ha ancora nessuna risposta configurata.\n" +
                "Aprilo in SOS su Garsal Apps e aggiungile.", 16f, Color.WHITE, alpha = 0.8f), 22, ctx)
            c.aggiungi(bottone("Chiudi", Color.WHITE, coloreSos) { chiudi() }, 20, ctx)
            return
        }

        tipo.outcomes.forEachIndexed { i, o ->
            c.aggiungi(bottoneEsito(o), if (i == 0) 22 else 10, ctx)
        }
    }

    private fun bottoneEsito(o: SosOutcome): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = sfondoTondo(Color.argb(38, 255, 255, 255), ctx.dp(16),
                ctx.dp(1), Color.argb(70, 255, 255, 255))
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            isClickable = true
            minimumHeight = ctx.dp(60)
            setOnClickListener {
                isEnabled = false
                callbacks.onRisposta(o, completatoCorrente)
            }
        }
        val titolo = (if (o.emoji.isNotBlank()) "${o.emoji}  " else "") + o.label
        box.aggiungi(testo(ctx, titolo, 19f, Color.WHITE, bold = true, center = false), 0, ctx)
        box.aggiungi(testo(ctx, o.sottotitolo(), 14f, Color.WHITE, center = false, alpha = 0.72f), 3, ctx)
        return box
    }

    /** Il countdown era arrivato in fondo o è stato interrotto: lo si legge al
        momento della risposta, che è l'unico posto dove serve. */
    private var completatoCorrente = true

    // ── Fase 3 · l'esito ────────────────────────────────────────────────────

    fun mostraAttesa() {
        val c = contenuto ?: return
        c.removeAllViews()
        c.aggiungi(testo(ctx, "⏳", 40f), 0, ctx)
        c.aggiungi(testo(ctx, "Segno il giro…", 20f, Color.WHITE, bold = true), 12, ctx)
    }

    fun mostraEsito(punti: Int, secondiProssimi: Int, inCoda: Boolean, errore: String?) {
        val c = contenuto ?: return
        c.removeAllViews()

        val faccia = when {
            errore != null && !inCoda -> "⚠️"
            punti > 0 -> "🎉"
            punti < 0 -> "😔"
            else      -> "👍"
        }
        c.aggiungi(testo(ctx, faccia, 44f), 0, ctx)

        val riga = when {
            punti > 0 -> "+$punti punti"
            punti < 0 -> "−${-punti} punti"
            else      -> "Nessun punto"
        }
        c.aggiungi(testo(ctx, riga, 30f, Color.WHITE, bold = true), 10, ctx)
        c.aggiungi(testo(ctx,
            "Il prossimo ${tipo.name} durerà ${Model.durata(secondiProssimi)}",
            17f, Color.WHITE, alpha = 0.85f), 10, ctx)

        if (inCoda) {
            c.aggiungi(testo(ctx,
                "Niente rete: il giro è salvato sul telefono e verrà spedito alla prossima apertura.",
                14f, Color.WHITE, alpha = 0.7f), 14, ctx)
        } else if (errore != null) {
            c.aggiungi(testo(ctx, "Non sono riuscito a registrare il giro: $errore",
                14f, Color.WHITE, alpha = 0.7f), 14, ctx)
        }

        c.aggiungi(bottone("Chiudi", Color.WHITE, coloreSos) { chiudi() }, 24, ctx)
        handler.postDelayed({ chiudi() }, Config.RESULT_LINGER_MS)
    }

    private var giaChiuso = false

    /** Sia il pulsante sia il rientro automatico dopo qualche secondo passano di
        qui: senza la guardia il servizio riceverebbe due volte «ho finito», e il
        secondo giro arriverebbe su un servizio già fermo. */
    private fun chiudi() {
        if (giaChiuso) return
        giaChiuso = true
        dismiss()
        callbacks.onChiuso()
    }

    private fun bottone(label: String, sfondoCol: Int, testoCol: Int, onClick: () -> Unit): View =
        testo(ctx, label, 18f, testoCol, bold = true).apply {
            background = sfondoTondo(sfondoCol, ctx.dp(14))
            setPadding(ctx.dp(24), ctx.dp(14), ctx.dp(24), ctx.dp(14))
            minimumHeight = ctx.dp(52)
            isClickable = true
            setOnClickListener { onClick() }
        }

    companion object { private const val TAG = "SosOverlay" }
}
