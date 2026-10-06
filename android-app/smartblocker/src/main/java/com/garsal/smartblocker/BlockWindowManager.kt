package com.garsal.smartblocker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Mostra la schermata di blocco tramite WindowManager (TYPE_APPLICATION_OVERLAY).
 * Richiede solo il permesso SYSTEM_ALERT_WINDOW (overlay), già concesso dall'utente.
 * Non richiede POST_NOTIFICATIONS né SCHEDULE_EXACT_ALARM.
 */
class BlockWindowManager(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: View? = null
    private var pinBuffer = ""

    private lateinit var tvClock: TextView
    private lateinit var tvSnoozeInfo: TextView
    private lateinit var tvPinHint: TextView
    private lateinit var tvPinError: TextView
    private lateinit var btnSnooze: Button
    private val pinDots = arrayOfNulls<TextView>(4)

    // ── Categorizzazione interattiva Analisi Costi (Smart Block "cost_analysis") ────────────
    private var isCaPickerView = false
    private var caQueueId = ""
    private var caMetadata: JSONObject? = null          // metadata grezzo (device_token + cost_analysis)
    private var caTransactions = mutableListOf<JSONObject>()
    private var caCategories = listOf<JSONObject>()
    private var caIndex = 0                              // transazione mostrata (navigabile con ◀ ▶)
    private var caNewSubParentId: String? = null          // categoria principale sotto cui si sta creando una nuova sotto-categoria (null = lista normale)
    private lateinit var tvCaProgress: TextView
    private lateinit var tvCaTransaction: TextView
    private lateinit var etCaSearch: EditText
    private lateinit var llCaCategories: LinearLayout
    private lateinit var btnCaPrev: Button
    private lateinit var btnCaNext: Button

    private val clockTick = object : Runnable {
        override fun run() {
            if (rootView != null) {
                tvClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        }
    }

    fun isShowing() = rootView != null

    @Suppress("DEPRECATION")
    private fun buildOverlayParams() = WindowManager.LayoutParams(
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

    /** true se l'overlay è davvero a schermo. Restituire l'esito è indispensabile: senza il
        permesso "Visualizza sopra altre app" questa funzione non può fare nulla, e chi chiama
        deve accorgersene — altrimenti lo stato resta "bloccato" con niente a schermo e la coda
        non viene più interrogata (vedi BlockerService.triggerSupabaseCheck). */
    fun show(): Boolean {
        if (rootView != null) return true
        if (!Settings.canDrawOverlays(ctx)) {
            AppLogger.log(ctx, "WINDOW", "show() saltato: manca il permesso overlay (SYSTEM_ALERT_WINDOW)")
            return false
        }

        val view = buildView()
        rootView = view
        overlayVisible = true
        return try {
            wm.addView(view, buildOverlayParams())
            handler.post(clockTick)
            refreshUI()
            true
        } catch (e: Exception) {
            AppLogger.log(ctx, "WINDOW", "errore show(): ${e.message}")
            rootView = null
            overlayVisible = false
            false
        }
    }

    fun dismiss() {
        handler.removeCallbacks(clockTick)
        rootView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            rootView = null
        }
        overlayVisible = false
        pinBuffer = ""
    }

    private fun refreshUI() {
        // Il picker categorie ha i suoi bottoni dedicati (nessun rinvio/PIN standard) e non
        // inizializza btnSnooze/tvSnoozeInfo/tvPinHint — uscire subito evita un crash per
        // lateinit non inizializzato.
        if (isCaPickerView) return
        val state = Prefs.getState(ctx)
        val count = Prefs.getSnoozeCount(ctx)
        btnSnooze.visibility =
            if (state == Prefs.STATE_TRIGGERED && count < Config.MAX_SNOOZES) View.VISIBLE else View.GONE
        tvSnoozeInfo.text = when {
            count == 0 -> "Puoi rinviare ancora ${Config.MAX_SNOOZES} volt${if (Config.MAX_SNOOZES == 1) "a" else "e"}"
            count < Config.MAX_SNOOZES -> "Rinvii rimasti: ${Config.MAX_SNOOZES - count}"
            else -> ""
        }
        if (!Prefs.isChallengeOnlyBlock(ctx) && !Prefs.isInfoOnlyBlock(ctx)) {
            tvPinHint.text = if (state == Prefs.STATE_LOCKED)
                "⚠️ Rinvii esauriti — solo PIN"
            else
                "Oppure sblocca con PIN"
        }
    }

    private fun onSnooze() {
        // Stessa ragione di unblockAndDismiss(): lo stato va azzerato prima di togliere la view.
        // Solo se il rinvio verrà davvero concesso — a rinvii esauriti handleSnooze() lascia il
        // blocco attivo e azzerare qui lo stato lo farebbe sparire senza alcun alarm.
        if (Prefs.getSnoozeCount(ctx) < Config.MAX_SNOOZES) Prefs.setState(ctx, Prefs.STATE_NONE)
        ctx.startService(Intent(ctx, BlockerService::class.java).apply {
            action = BlockerService.ACTION_SNOOZE
        })
        dismiss()
    }

    private fun onDigit(d: String) {
        when (d) {
            "C"  -> { pinBuffer = ""; tvPinError.text = "" }
            "⌫" -> if (pinBuffer.isNotEmpty()) pinBuffer = pinBuffer.dropLast(1)
            else -> if (pinBuffer.length < 4) pinBuffer += d
        }
        updateDots()
        if (pinBuffer.length == 4) handler.postDelayed({ checkPin() }, 150)
    }

    /** Chiude il blocco. Prefs.setState(STATE_NONE) va fatto **prima** di togliere la view, non
        solo dentro handleUnblock(): startService() consegna ACTION_UNBLOCK sul main thread, quindi
        il servizio azzera lo stato solo dopo il ritorno da questo metodo. Nel frattempo la view è
        già stata rimossa e l'app sotto torna in primo piano generando un TYPE_WINDOW_STATE_CHANGED
        che BlockerAccessibilityService leggerebbe con lo stato ancora "bloccato", rilanciando la
        schermata PIN appena sbloccato. */
    private fun unblockAndDismiss() {
        Prefs.setState(ctx, Prefs.STATE_NONE)
        ctx.startService(Intent(ctx, BlockerService::class.java).apply {
            action = BlockerService.ACTION_UNBLOCK
        })
        dismiss()
    }

    private fun checkPin() {
        if (pinBuffer == Config.PIN) {
            val entities = Prefs.getBlockEntities(ctx)
            if (entities.isNotEmpty()) {
                Thread {
                    val api = SupabaseApi(ctx)
                    entities.forEach { api.completeEntity(it.app, it.entityId) }
                    api.triggerFillQueue()
                }.start()
            }
            unblockAndDismiss()
        } else {
            tvPinError.text = "PIN errato, riprova"
            pinBuffer = ""
            updateDots()
            handler.postDelayed({ tvPinError.text = "" }, 2500)
        }
    }

    private fun updateDots() {
        pinDots.forEachIndexed { i, tv ->
            tv?.text = if (i < pinBuffer.length) "●" else "○"
        }
    }

    /** Dismiss semplice per blocchi informativi (es. cost_analysis): nessuna RPC, nessun PIN —
        la riga cm_notification_queue è già passata a 'sent' nel momento in cui l'overlay è stato
        mostrato (BlockerService → markSent), quindi non serve completare/confermare nulla lato
        server, si chiude e basta. */
    private fun onInfoDismiss() {
        unblockAndDismiss()
    }

    /** Invia la risposta SÌ/NO della sfida Ta Firi? (tenuta premuta) e sblocca. */
    private fun onChallengeResponse(status: String) {
        val entities = Prefs.getBlockEntities(ctx)
        if (entities.isNotEmpty()) {
            Thread {
                val api = SupabaseApi(ctx)
                entities.forEach { api.completeChallengeCheckin(it.entityId, status) }
                api.triggerFillQueue()
            }.start()
        }
        unblockAndDismiss()
    }

    /** Legge da Prefs il metadata del blocco cost_analysis corrente e lo scompone in
        caTransactions/caCategories. caTransactions vuoto o caCategories vuoto → niente picker,
        si ricade sul blocco informativo semplice ("Ho capito"). */
    private fun loadCaDataFromPrefs() {
        caQueueId = Prefs.getBlockCaQueueId(ctx)
        caMetadata = null
        caTransactions = mutableListOf()
        caCategories = listOf()
        caIndex = 0
        caNewSubParentId = null
        val raw = Prefs.getBlockCaData(ctx)
        if (raw.isBlank()) return
        try {
            val meta = JSONObject(raw)
            caMetadata = meta
            val ca = meta.optJSONObject("cost_analysis") ?: return
            val txArr = ca.optJSONArray("transactions") ?: JSONArray()
            val txList = mutableListOf<JSONObject>()
            for (i in 0 until txArr.length()) txList.add(txArr.getJSONObject(i))
            caTransactions = txList
            val catArr = ca.optJSONArray("categories") ?: JSONArray()
            val catList = mutableListOf<JSONObject>()
            for (i in 0 until catArr.length()) catList.add(catArr.getJSONObject(i))
            caCategories = catList
        } catch (e: Exception) {
            AppLogger.log(ctx, "CA_CATEGORY", "parse metadata fallito: ${e.message}")
            caMetadata = null
            caTransactions = mutableListOf()
            caCategories = listOf()
        }
    }

    private fun buildCategoryPickerView(): View {
        isCaPickerView = true
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG_INFO))
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setPadding(48, 72, 48, 40)
        }
        fun lp(w: Int = MP, h: Int = WC, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = top }

        root.addView(TextView(ctx).apply {
            text = "🔒"; textSize = 36f; gravity = Gravity.CENTER
        }, lp(top = 0))

        tvClock = TextView(ctx).apply {
            textSize = 36f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(tvClock, lp(top = 8))

        root.addView(TextView(ctx).apply {
            text = "Categorizza le transazioni Revolut"
            textSize = 16f; setTextColor(Color.parseColor(ACCENT_INFO))
            gravity = Gravity.CENTER
        }, lp(top = 8))

        val caNavRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnCaPrev = Button(ctx).apply {
            text = "◀"
            textSize = 16f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(28, 8, 28, 8)
            setOnClickListener { onCaPrev() }
        }
        caNavRow.addView(btnCaPrev, LinearLayout.LayoutParams(WC, WC))
        tvCaProgress = TextView(ctx).apply {
            textSize = 13f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
        }
        caNavRow.addView(tvCaProgress, LinearLayout.LayoutParams(0, WC, 1f))
        btnCaNext = Button(ctx).apply {
            text = "▶"
            textSize = 16f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(28, 8, 28, 8)
            setOnClickListener { onCaNext() }
        }
        caNavRow.addView(btnCaNext, LinearLayout.LayoutParams(WC, WC))
        root.addView(caNavRow, lp(top = 10))

        tvCaTransaction = TextView(ctx).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(16, 16, 16, 16)
        }
        root.addView(tvCaTransaction, lp(top = 16))

        // Stesso pattern del filtro categoria in cost-analysis.html (renderTxCategorySuggestions):
        // campo di ricerca + lista raggruppata (principale in grassetto, sottocategorie indentate
        // subito sotto), entrambe toccabili, filtrate live per nome man mano che si scrive.
        etCaSearch = EditText(ctx).apply {
            hint = "Cerca categoria…"
            textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundColor(Color.parseColor("#0F172A"))
            setSingleLine(true)
            setPadding(24, 20, 24, 20)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { renderCaCategoryList(s?.toString() ?: "") }
            })
        }
        root.addView(etCaSearch, lp(top = 14))

        llCaCategories = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(llCaCategories) }
        root.addView(scroll, LinearLayout.LayoutParams(MP, 0, 1f).apply { topMargin = 12 })

        val btnSkip = Button(ctx).apply {
            text = "Rinvia — decido dopo"
            textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(BTN_INFO))
            setPadding(32, 20, 32, 20)
            // Niente ACTION_SNOOZE/alarm locale: la riga cm_notification_queue è già 'sent'
            // (BlockerService → markSent), quindi non riapparirebbe comunque da una nuova query
            // Supabase con lo stesso id — riusare l'alarm di rinvio la riproporrebbe solo dalla
            // cache locale, con dati potenzialmente superati. Si chiude e basta (come
            // onInfoDismiss/finishCaPickerAndMaybeUnblock): ricomparirà da sola al prossimo giro
            // del cron revolut-auto-categorize, se restano ancora transazioni senza categoria.
            setOnClickListener { finishCaPickerAndMaybeUnblock() }
        }
        root.addView(btnSkip, lp(top = 16))

        renderCaCurrentTransaction()
        return root
    }

    /** true se la categoria non ha un parent_id (categoria principale). */
    private fun isCaTopCategory(cat: JSONObject): Boolean =
        cat.isNull("parent_id") || cat.optString("parent_id", "").isBlank()

    /** id della categoria principale attualmente "aperta" (sottocategorie visibili) quando non
        c'è una ricerca in corso — solo una alla volta (fisarmonica), nessuna aperta di default. */
    private var caExpandedTopId: String? = null

    /** Campo stringa di una transazione del payload. NON usare optString() da solo: su un null
        JSON (org.json.JSONObject.NULL) restituisce la stringa "null", che finirebbe a schermo —
        e spender/time/description sono nulli tutte le volte che il dato non c'è. */
    private fun caTxString(tx: JSONObject, key: String): String =
        if (tx.isNull(key)) "" else tx.optString(key, "").trim()

    /** 'YYYY-MM-DD' → 'DD/MM/YYYY' (formato europeo, come nel resto delle app). Qualsiasi altro
        formato viene lasciato com'è. */
    private fun formatCaDate(date: String): String {
        val p = date.split("-")
        return if (p.size == 3 && p[0].length == 4) "${p[2]}/${p[1]}/${p[0]}" else date
    }

    /** Mostra la transazione alla posizione caIndex; se la lista è vuota (tutte categorizzate)
        chiude il blocco come per un blocco informativo normale. Altrimenti azzera ricerca/apertura
        e ridisegna la lista categorie (principali chiuse). */
    private fun renderCaCurrentTransaction() {
        if (caTransactions.isEmpty()) {
            caIndex = 0
            tvCaProgress.text = ""
            tvCaTransaction.text = "✅ Fatto! Nessuna transazione da categorizzare."
            etCaSearch.visibility = View.GONE
            llCaCategories.removeAllViews()
            btnCaPrev.visibility = View.GONE
            btnCaNext.visibility = View.GONE
            handler.postDelayed({ if (rootView != null) finishCaPickerAndMaybeUnblock() }, 1200)
            return
        }
        if (caIndex >= caTransactions.size) caIndex = caTransactions.size - 1
        if (caIndex < 0) caIndex = 0
        val tx = caTransactions[caIndex]
        val desc = caTxString(tx, "description").ifBlank { "Transazione" }
        val amount = tx.optDouble("amount", 0.0)
        val currency = caTxString(tx, "currency").ifBlank { "EUR" }
        val date = caTxString(tx, "date")
        // spender/time arrivano da revolut-auto-categorize (v1.4): chi ha pagato e a che ora.
        // Restano vuoti sulle notifiche accodate da versioni precedenti della Edge Function e
        // quando il dato manca (transazione non ancora attribuita a una persona, o riga grezza
        // senza orario) — in quel caso la riga corrispondente semplicemente non compare.
        val time = caTxString(tx, "time")
        val spender = caTxString(tx, "spender")
        tvCaProgress.text = "${caIndex + 1} di ${caTransactions.size}" +
            if (date.isNotBlank()) " — ${formatCaDate(date)}" else ""
        val detail = listOfNotNull(
            spender.ifBlank { null }?.let { "👤 $it" },
            time.ifBlank { null }?.let { "🕒 $it" }
        ).joinToString("   ")
        tvCaTransaction.text = buildString {
            append(desc)
            append("\n").append(String.format(Locale.ITALY, "%.2f", amount)).append(" ").append(currency)
            if (detail.isNotBlank()) append("\n").append(detail)
        }
        etCaSearch.visibility = View.VISIBLE
        etCaSearch.setText("")
        caExpandedTopId = null
        renderCaCategoryList("")
        btnCaPrev.visibility = View.VISIBLE
        btnCaNext.visibility = View.VISIBLE
        btnCaPrev.isEnabled = caIndex > 0
        btnCaPrev.alpha = if (caIndex > 0) 1f else 0.4f
        btnCaNext.isEnabled = caIndex < caTransactions.size - 1
        btnCaNext.alpha = if (caIndex < caTransactions.size - 1) 1f else 0.4f
    }

    /** Naviga alla transazione precedente/successiva senza modificarne la categoria — a
        differenza di onCategoryChosen(), che avanza automaticamente rimuovendo quella appena
        fatta, qui si scorre soltanto la lista per rivedere o saltare le altre transazioni. */
    private fun onCaPrev() {
        if (caIndex > 0) { caIndex--; caNewSubParentId = null; renderCaCurrentTransaction() }
    }
    private fun onCaNext() {
        if (caIndex < caTransactions.size - 1) { caIndex++; caNewSubParentId = null; renderCaCurrentTransaction() }
    }

    /** Ridisegna la lista categorie. Senza ricerca: solo le principali, chiuse — toccarne una
        la apre/chiude mostrando le sottocategorie subito sotto (fisarmonica, una alla volta) più
        una riga per assegnare la principale stessa. Con una ricerca in corso: ignora lo stato
        aperto/chiuso e mostra tutte le principali+sottocategorie che matchano, come prima
        (comportamento identico a renderTxCategorySuggestions in cost-analysis.html). */
    private fun renderCaCategoryList(query: String) {
        llCaCategories.removeAllViews()
        if (caTransactions.isEmpty()) return
        val tx = caTransactions[caIndex.coerceIn(0, caTransactions.size - 1)]
        val parentId = caNewSubParentId
        if (parentId != null) {
            renderCaNewSubcategoryForm(tx, parentId)
            return
        }
        etCaSearch.visibility = View.VISIBLE
        val q = query.trim().lowercase(Locale.getDefault())
        val searching = q.isNotBlank()

        val topCats = caCategories.filter { isCaTopCategory(it) && it.optString("id", "").isNotBlank() }
        val kidsByParent = caCategories.filter { !isCaTopCategory(it) }
            .groupBy { it.optString("parent_id", "") }

        fun addCategoryButton(label: String, sub: Boolean, onClick: () -> Unit) {
            llCaCategories.addView(Button(ctx).apply {
                text = label
                textSize = if (sub) 14f else 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(if (sub) "#1F2937" else "#334155"))
                setAllCaps(false)
                setPadding(24, if (sub) 18 else 22, 24, if (sub) 18 else 22)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(MP, WC).apply {
                topMargin = if (sub) 6 else 10
                if (sub) marginStart = 32
            })
        }

        var shown = 0
        topCats.forEach { top ->
            val topId = top.optString("id", "")
            val topName = top.optString("name", "")
            val kids = kidsByParent[topId].orEmpty()
            val topMatches = !searching || topName.lowercase(Locale.getDefault()).contains(q)
            val matchingKids = kids.filter { topMatches || it.optString("name", "").lowercase(Locale.getDefault()).contains(q) }
            if (searching && !topMatches && matchingKids.isEmpty()) return@forEach
            shown++

            val topIcon = top.optString("icon", "")
            val isExpanded = searching || caExpandedTopId == topId
            // Durante la ricerca il tap seleziona subito la principale (niente fisarmonica):
            // la freccia si mostra solo fuori ricerca, dove indica davvero apri/chiudi.
            val arrow = if (kids.isEmpty() || searching) "" else if (isExpanded) " ▾" else " ▸"
            val topLabel = (if (topIcon.isNotBlank()) "$topIcon " else "") + topName + arrow
            addCategoryButton(topLabel, sub = false) {
                if (searching || kids.isEmpty()) {
                    onCategoryChosen(tx, topId)
                } else if (caExpandedTopId == topId) {
                    caExpandedTopId = null
                    renderCaCategoryList("")
                } else {
                    caExpandedTopId = topId
                    renderCaCategoryList("")
                }
            }

            if (!isExpanded) return@forEach
            if (!searching && kids.isNotEmpty()) {
                addCategoryButton("→ usa \"$topName\"", sub = true) { onCategoryChosen(tx, topId) }
            }
            (if (searching) matchingKids else kids).forEach { kid ->
                val kidId = kid.optString("id", "")
                if (kidId.isBlank()) return@forEach
                val kidIcon = kid.optString("icon", "")
                val kidLabel = (if (kidIcon.isNotBlank()) "$kidIcon " else "") + kid.optString("name", "")
                addCategoryButton(kidLabel, sub = true) { onCategoryChosen(tx, kidId) }
            }
            if (!searching) {
                llCaCategories.addView(Button(ctx).apply {
                    text = "➕ Nuova sotto-categoria"
                    textSize = 13f; setTextColor(Color.parseColor(ACCENT_INFO))
                    setBackgroundColor(Color.parseColor("#0F172A"))
                    setAllCaps(false)
                    setPadding(24, 16, 24, 16)
                    setOnClickListener { caNewSubParentId = topId; renderCaCategoryList("") }
                }, LinearLayout.LayoutParams(MP, WC).apply { topMargin = 6; marginStart = 32 })
            }
        }
        if (shown == 0) {
            llCaCategories.addView(TextView(ctx).apply {
                text = "Nessuna categoria trovata."
                textSize = 13f; setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            })
        }
    }

    /** Modulo inline (al posto della lista categorie) per creare una nuova sotto-categoria di
        parentId: nasconde il campo ricerca per evitare che digitare lì ridisegni la lista sopra
        il modulo. "Crea e assegna" chiama la RPC e, se ok, assegna subito la nuova sotto-categoria
        alla transazione corrente (stesso comportamento del "+" in cost-analysis.html). */
    private fun renderCaNewSubcategoryForm(tx: JSONObject, parentId: String) {
        etCaSearch.visibility = View.GONE
        val parentName = caCategories.find { it.optString("id", "") == parentId }?.optString("name", "") ?: ""

        llCaCategories.addView(TextView(ctx).apply {
            text = "Nuova sotto-categoria di \"$parentName\""
            textSize = 13f; setTextColor(Color.parseColor("#94A3B8"))
        }, LinearLayout.LayoutParams(MP, WC).apply { bottomMargin = 10 })

        val etName = EditText(ctx).apply {
            hint = "Nome sotto-categoria…"
            textSize = 15f; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundColor(Color.parseColor("#0F172A"))
            setSingleLine(true)
            setPadding(24, 20, 24, 20)
        }
        llCaCategories.addView(etName, LinearLayout.LayoutParams(MP, WC))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val btnCancel = Button(ctx).apply {
            text = "Annulla"
            textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setAllCaps(false)
            setOnClickListener { caNewSubParentId = null; renderCaCategoryList("") }
        }
        val btnCreate = Button(ctx).apply {
            text = "Crea e assegna"
            textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(BTN_INFO))
            setAllCaps(false)
            setOnClickListener {
                val name = etName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) { etName.error = "Obbligatorio"; return@setOnClickListener }
                createCaSubcategory(tx, parentId, name)
            }
        }
        row.addView(btnCancel, LinearLayout.LayoutParams(0, WC, 1f).apply { marginEnd = 8 })
        row.addView(btnCreate, LinearLayout.LayoutParams(0, WC, 1f).apply { marginStart = 8 })
        llCaCategories.addView(row, LinearLayout.LayoutParams(MP, WC).apply { topMargin = 14 })
    }

    /** Crea la sotto-categoria via RPC e, se riesce, la assegna subito alla transazione corrente
        (stesso ramo di onCategoryChosen: rimuove la transazione, persiste, avanza). */
    private fun createCaSubcategory(tx: JSONObject, parentId: String, name: String) {
        llCaCategories.removeAllViews()
        tvCaTransaction.text = "Creazione categoria…"
        Thread {
            val newId = SupabaseApi(ctx).createSubcategory(tx.optString("id", ""), parentId, name)
            handler.post {
                if (rootView == null) return@post
                caNewSubParentId = null
                if (newId != null) {
                    caCategories = caCategories + JSONObject().apply {
                        put("id", newId); put("name", name); put("icon", ""); put("parent_id", parentId)
                    }
                    onCategoryChosen(tx, newId)
                } else {
                    tvCaTransaction.text = "Errore creazione categoria, riprova"
                    handler.postDelayed({ if (rootView != null) renderCaCurrentTransaction() }, 1500)
                }
            }
        }.start()
    }

    /** Chiama la RPC in background, poi rimuove la transazione dalla lista locale e da
        metadata.cost_analysis.transactions (PATCH) così un eventuale rinvio non la ripropone. */
    private fun onCategoryChosen(tx: JSONObject, categoryId: String) {
        val txId = tx.optString("id", "")
        if (txId.isBlank()) return
        llCaCategories.removeAllViews()
        tvCaTransaction.text = "Salvataggio…"
        Thread {
            val ok = SupabaseApi(ctx).setTransactionCategory(txId, categoryId)
            handler.post {
                if (rootView == null) return@post // blocco chiuso nel frattempo (es. rinvio)
                if (ok) {
                    caTransactions.removeAll { it.optString("id") == txId }
                    persistCaData()
                    renderCaCurrentTransaction()
                } else {
                    tvCaTransaction.text = "Errore di salvataggio, riprovo…"
                    handler.postDelayed({ if (rootView != null) renderCaCurrentTransaction() }, 1500)
                }
            }
        }.start()
    }

    /** Salva la lista transazioni residue sia in Prefs (per un eventuale rebuild della view)
        sia su Supabase (PATCH dell'intero metadata, device_token incluso — vedi
        SupabaseApi.patchQueueMetadata). */
    private fun persistCaData() {
        val meta = caMetadata ?: return
        val ca = meta.optJSONObject("cost_analysis") ?: JSONObject().also { meta.put("cost_analysis", it) }
        val arr = JSONArray()
        caTransactions.forEach { arr.put(it) }
        ca.put("transactions", arr)
        Prefs.setBlockCaData(ctx, meta.toString())
        if (caQueueId.isNotBlank()) {
            val metaSnapshot = meta.toString()
            Thread { SupabaseApi(ctx).patchQueueMetadata(caQueueId, metaSnapshot) }.start()
        }
    }

    /** Fine del picker (tutte categorizzate o "Rinvia — decido dopo"): il blocco si chiude e
        basta. Il picker non passa più alla schermata PIN: BlockerService isola le righe
        cost_analysis in un blocco tutto loro (vedi triggerSupabaseCheck) e lascia pending le
        eventuali righe task/sfida dovute nello stesso momento, che ricompaiono da sole al giro
        successivo con la loro schermata. Prima invece finire di categorizzare portava dritti
        alla richiesta di sblocco con PIN. */
    private fun finishCaPickerAndMaybeUnblock() {
        Prefs.clearBlockCaData(ctx)
        onInfoDismiss()
    }

    /** Il picker ha priorità ogni volta che ci sono dati cost_analysis pendenti, anche se nello
        stesso blocco sono dovute anche altre entità (task/sfide) — prima non veniva mostrato in
        quel caso (serviva isInfoOnlyBlock, cioè TUTTE le entità dovevano essere cost_analysis),
        facendo apparire per errore la schermata PIN rossa standard invece del picker. */
    private fun buildView(): View {
        isCaPickerView = false
        loadCaDataFromPrefs()
        if (caTransactions.isNotEmpty() && caCategories.isNotEmpty()) {
            return buildCategoryPickerView()
        }
        return buildStandardView()
    }

    private fun buildStandardView(): View {
        // Verde per le sfide Ta Firi?, giallo per le notifiche informative (es. cost_analysis),
        // rosso per i task (comportamento invariato).
        val isInfo = Prefs.isInfoOnlyBlock(ctx)
        val bgColor = when {
            Prefs.isChallengeOnlyBlock(ctx) -> BG_CHALLENGE
            isInfo                           -> BG_INFO
            else                             -> BG_TASK
        }
        val accentColor = if (isInfo) ACCENT_INFO else ACCENT_DEFAULT

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(bgColor))
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setPadding(48, 80, 48, 80)
        }

        fun lp(w: Int = WC, h: Int = WC, top: Int = 0) =
            LinearLayout.LayoutParams(w, h).apply { topMargin = top }

        root.addView(TextView(ctx).apply {
            text = "🔒"; textSize = 56f; gravity = Gravity.CENTER
        }, lp(MP))

        tvClock = TextView(ctx).apply {
            textSize = 64f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(tvClock, lp(MP, top = 16))

        root.addView(TextView(ctx).apply {
            text = if (isInfo) "Analisi Costi" else "Telefono bloccato"; textSize = 22f
            setTextColor(Color.parseColor(accentColor))
            gravity = Gravity.CENTER; letterSpacing = 0.05f
        }, lp(MP, top = 8))

        val blockTitle = Prefs.getBlockTitle(ctx)
        if (blockTitle.isNotBlank()) {
            root.addView(TextView(ctx).apply {
                text = blockTitle
                textSize = 15f
                setTextColor(Color.parseColor("#CBD5E1"))
                gravity = Gravity.CENTER
                setPadding(24, 0, 24, 0)
            }, lp(MP, top = 6))
        }

        tvSnoozeInfo = TextView(ctx).apply {
            textSize = 14f; setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        }
        root.addView(tvSnoozeInfo, lp(MP, top = 24))

        btnSnooze = Button(ctx).apply {
            text = "Rinvia ${Config.SNOOZE_DURATION_MS / 60000} min"
            textSize = 16f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(if (isInfo) BTN_INFO else "#7C3AED"))
            setPadding(48, 24, 48, 24)
            setOnClickListener { onSnooze() }
        }
        root.addView(btnSnooze, lp(MP, top = 16))

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#334155"))
        }, LinearLayout.LayoutParams(MP, 1).apply { topMargin = 32 })

        if (Prefs.isChallengeOnlyBlock(ctx)) {
            root.addView(TextView(ctx).apply {
                text = "Hai portato a termine la sfida oggi?"
                textSize = 15f; setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
            }, lp(MP, top = 24))

            root.addView(TextView(ctx).apply {
                text = "Tieni premuto per confermare"
                textSize = 12f; setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
            }, lp(MP, top = 4))

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            }
            val btnSi = buildHoldConfirmButton(ctx, handler, "SÌ", "#16A34A", Config.CHALLENGE_HOLD_MS) {
                onChallengeResponse("done")
            }
            val btnNo = buildHoldConfirmButton(ctx, handler, "NO", "#DC2626", Config.CHALLENGE_HOLD_MS) {
                onChallengeResponse("not_done")
            }
            row.addView(btnSi, LinearLayout.LayoutParams(0, HOLD_BTN_HEIGHT, 1f).apply { marginEnd = 12 })
            row.addView(btnNo, LinearLayout.LayoutParams(0, HOLD_BTN_HEIGHT, 1f).apply { marginStart = 12 })
            root.addView(row, lp(MP, top = 20))
        } else if (Prefs.isInfoOnlyBlock(ctx)) {
            root.addView(TextView(ctx).apply {
                text = "Informazione"
                textSize = 15f; setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
            }, lp(MP, top = 24))

            val btnOk = Button(ctx).apply {
                text = "Ho capito"
                textSize = 16f; setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(BTN_INFO))
                setPadding(48, 24, 48, 24)
                setOnClickListener { onInfoDismiss() }
            }
            root.addView(btnOk, lp(MP, top = 20))
        } else {
            tvPinHint = TextView(ctx).apply {
                textSize = 13f; setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
            }
            root.addView(tvPinHint, lp(MP, top = 24))

            val dotsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            }
            for (i in 0..3) {
                val dot = TextView(ctx).apply {
                    text = "○"; textSize = 28f
                    setTextColor(Color.parseColor("#A78BFA"))
                    setPadding(16, 0, 16, 0)
                }
                pinDots[i] = dot; dotsRow.addView(dot)
            }
            root.addView(dotsRow, lp(MP, top = 16))

            tvPinError = TextView(ctx).apply {
                textSize = 13f; setTextColor(Color.parseColor("#F87171"))
                gravity = Gravity.CENTER
            }
            root.addView(tvPinError, lp(MP, top = 8))

            val keys = listOf("1","2","3","4","5","6","7","8","9","C","0","⌫")
            val grid = GridLayout(ctx).apply { columnCount = 3; rowCount = 4 }
            keys.forEach { k ->
                val btn = Button(ctx).apply {
                    text = k; textSize = 20f
                    setTextColor(if (k == "C") Color.parseColor("#F87171") else Color.WHITE)
                    setBackgroundColor(Color.parseColor("#0F172A"))
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { onDigit(k) }
                }
                grid.addView(btn, GridLayout.LayoutParams().apply {
                    width = 0; height = WC
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                })
            }
            root.addView(grid, lp(MP, top = 16))
        }

        return root
    }

    companion object {
        private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val HOLD_BTN_HEIGHT = 170

        /** Sfondi delle schermate di blocco: verde per le sfide Ta Firi?, giallo per le notifiche
            informative di Analisi Costi (blocco informativo e picker categorie), rosso per i task. */
        const val BG_CHALLENGE = "#0F3D24"
        const val BG_INFO      = "#4A3A0F"   // ambra scura: giallo leggibile con testo bianco
        const val BG_TASK      = "#4A1414"
        /** Accento (sottotitoli, testi secondari) abbinato allo sfondo. */
        const val ACCENT_INFO    = "#FCD34D" // giallo chiaro
        const val ACCENT_DEFAULT = "#A78BFA" // viola, invariato per task/sfide
        /** Bottoni d'azione delle schermate Analisi Costi. */
        const val BTN_INFO = "#B45309"       // ambra scura, coerente con lo sfondo giallo

        /** true mentre l'overlay WindowManager è a schermo. Letto da BlockerAccessibilityService
            per non rilanciare BlockOverlayActivity (la vecchia schermata PIN) sopra un blocco già
            gestito da qui — vedi onAccessibilityEvent(). */
        @Volatile
        private var overlayVisible = false
        fun isOverlayVisible() = overlayVisible
    }
}
