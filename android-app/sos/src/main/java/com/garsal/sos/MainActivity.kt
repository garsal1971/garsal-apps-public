package com.garsal.sos

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * La home: un SOS per pagina, si sfoglia con lo swipe destra/sinistra, e in ogni
 * pagina c'è un bottone rosso grande quanto lo schermo lo consente.
 *
 * Il bottone è grande di proposito: lo si preme in un momento in cui non si ha
 * voglia di cercare niente. Tutto il resto della schermata — nome, durata del
 * prossimo giro, punti — sta attorno, e nessuno di quei numeri si calcola qui.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var api: SosApi

    private lateinit var pager: ViewPager2
    private lateinit var puntini: LinearLayout
    private lateinit var avviso: TextView
    private lateinit var vuoto: TextView

    private var tipi: List<SosType> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = SosApi(this)

        if (Prefs.getToken(this).isBlank()) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }

        setContentView(costruisciSchermata())
        tipi = api.configInCache()
        aggiornaPagine()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.getToken(this).isBlank()) { finish(); return }
        aggiornaAvviso()
        // La configurazione si rilegge ad ogni ritorno in primo piano: è anche il
        // momento in cui rientra chi ha appena finito un giro, e la durata del
        // prossimo è cambiata un istante fa.
        Thread {
            api.svuotaCoda()
            val nuovi = api.caricaConfig()
            if (nuovi != null) handler.post {
                tipi = nuovi
                aggiornaPagine()
            }
        }.start()
    }

    // ── Struttura della schermata ───────────────────────────────────────────

    private fun costruisciSchermata(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12121A"))
        }

        // Barra: titolo a sinistra, ingranaggio a destra. Nessuna altezza fissa:
        // con i caratteri di sistema grandi una barra da 56dp taglierebbe il titolo.
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(10), dp(6))
            minimumHeight = dp(56)
        }
        barra.addView(testo(this, "SOS", 20f, Color.WHITE, bold = true, center = false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(testo(this, "⚙", 22f, Color.WHITE, alpha = 0.8f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { apriImpostazioni() }
        })
        root.addView(barra, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        avviso = testo(this, "", 14f, Color.WHITE, bold = true, center = false).apply {
            background = sfondoTondo(Color.parseColor("#F39C12"), dp(12))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { chiediPermessoOverlay() }
        }
        val lpAvviso = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpAvviso.setMargins(dp(16), dp(4), dp(16), dp(4))
        root.addView(avviso, lpAvviso)

        vuoto = testo(this,
            "Nessun SOS configurato.\n\nApri SOS su Garsal Apps e creane uno: " +
            "nome, durata del countdown e le risposte alla domanda «com'è andata?».",
            17f, Color.WHITE, alpha = 0.8f).apply {
            setPadding(dp(28), dp(40), dp(28), dp(28))
            visibility = View.GONE
        }
        root.addView(vuoto, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pager = ViewPager2(this).apply { adapter = Pagine() }
        root.addView(pager, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        puntini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(18))
        }
        root.addView(puntini, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                Prefs.setLastIndex(this@MainActivity, position)
                disegnaPuntini(position)
            }
        })
        return root
    }

    private fun aggiornaPagine() {
        vuoto.visibility = if (tipi.isEmpty()) View.VISIBLE else View.GONE
        pager.visibility = if (tipi.isEmpty()) View.GONE else View.VISIBLE
        pager.adapter?.notifyDataSetChanged()
        val i = Prefs.getLastIndex(this).coerceIn(0, (tipi.size - 1).coerceAtLeast(0))
        if (tipi.isNotEmpty()) pager.setCurrentItem(i, false)
        disegnaPuntini(i)
        aggiornaAvviso()
    }

    /** I puntini dello swipe: senza, con un SOS solo non si saprebbe che ce ne
        sono altri, e con quattro non si saprebbe a che punto si è. */
    private fun disegnaPuntini(attivo: Int) {
        puntini.removeAllViews()
        if (tipi.size < 2) return
        for (i in tipi.indices) {
            val v = View(this).apply {
                background = sfondoTondo(
                    if (i == attivo) Color.WHITE else Color.argb(70, 255, 255, 255), dp(4))
            }
            val lp = LinearLayout.LayoutParams(dp(if (i == attivo) 22 else 8), dp(8))
            lp.setMargins(dp(4), 0, dp(4), 0)
            puntini.addView(v, lp)
        }
    }

    private fun aggiornaAvviso() {
        if (!::avviso.isInitialized) return
        if (!Settings.canDrawOverlays(this)) {
            avviso.text = "⚠️ Manca il permesso «Visualizza sopra altre app»: senza, " +
                          "il countdown non può bloccare il telefono. Tocca per concederlo."
            avviso.visibility = View.VISIBLE
        } else {
            avviso.visibility = View.GONE
        }
    }

    // ── Una pagina = un SOS ─────────────────────────────────────────────────

    private inner class Pagine : RecyclerView.Adapter<Pagine.VH>() {
        inner class VH(val box: FrameLayout) : RecyclerView.ViewHolder(box)

        override fun getItemCount() = tipi.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(FrameLayout(this@MainActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.box.removeAllViews()
            holder.box.addView(costruisciPagina(tipi[position]), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun costruisciPagina(t: SosType): View {
        val sv = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(6), dp(22), dp(18))
        }

        col.aggiungi(testo(this, t.emoji, 36f), 0, this)
        col.aggiungi(testo(this, t.name, 26f, Color.WHITE, bold = true), 4, this)
        if (t.description.isNotBlank())
            col.aggiungi(testo(this, t.description, 16f, Color.WHITE, alpha = 0.72f), 2, this)

        val d = diametroBottone()
        col.addView(bottoneSos(t), LinearLayout.LayoutParams(d, d).apply {
            topMargin = dp(24); gravity = Gravity.CENTER_HORIZONTAL
        })

        col.aggiungi(testo(this,
            "Countdown: ${Model.durata(t.seconds)}", 18f, Color.WHITE, bold = true), 22, this)
        col.aggiungi(testo(this,
            if (t.roundsTotal == 0) "Nessun giro ancora"
            else "${t.pointsTotal} punti · ${t.roundsTotal} gir${if (t.roundsTotal == 1) "o" else "i"}",
            15f, Color.WHITE, alpha = 0.7f), 4, this)

        if (tipi.size > 1)
            col.aggiungi(testo(this, "‹  scorri per cambiare SOS  ›", 13f, Color.WHITE, alpha = 0.45f), 18, this)

        sv.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    /**
     * Il bottone. Il diametro si ricava dallo schermo (72 % della larghezza, con un
     * tetto), non da una costante in dp: su un telefono stretto una costante
     * sborderebbe, su uno largo sembrerebbe un bottone qualunque. La scritta dentro
     * si ridimensiona da sola (autosize), così l'ingrandimento dei caratteri di
     * sistema non la fa uscire dal cerchio.
     */
    private fun diametroBottone(): Int =
        minOf((resources.displayMetrics.widthPixels * 0.72f).toInt(), dp(300))

    private fun bottoneSos(t: SosType): View {
        val rosso = colore(t.color)

        val tv = AppCompatTextView(this).apply {
            text = "SOS"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(rosso)
                setStroke(dp(6), scurisci(rosso, 0.7f))
            }
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isClickable = true
            elevation = dp(10).toFloat()
            setOnClickListener { premuto(t) }
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            tv, 24, 120, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        return tv
    }

    private fun premuto(t: SosType) {
        if (!Settings.canDrawOverlays(this)) { chiediPermessoOverlay(); return }
        if (t.outcomes.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(t.name)
                .setMessage("Questo SOS non ha nessuna risposta configurata: alla fine del " +
                            "countdown non ci sarebbe niente da rispondere.\n\n" +
                            "Aggiungile in SOS su Garsal Apps.")
                .setPositiveButton("Ho capito", null)
                .show()
            return
        }
        SosSessionService.avvia(this, t.id)
    }

    private fun chiediPermessoOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            Toast.makeText(this, "Apri Impostazioni → App → SOS → Visualizza sopra altre app",
                Toast.LENGTH_LONG).show()
        }
    }

    // ── Impostazioni ────────────────────────────────────────────────────────

    private fun apriImpostazioni() {
        val quando = Prefs.getConfigAt(this)
        val inCoda = Prefs.getPending(this).length()

        // Riepilogo e comandi nella stessa finestra: AlertDialog mostra il messaggio
        // oppure la lista di voci, mai tutti e due, quindi la finestra si costruisce
        // a mano. Con i caratteri di sistema grandi è anche l'unico modo per essere
        // sicuri che le voci vadano a capo invece di essere tagliate.
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(8))
        }
        val info = buildString {
            append("Codice: ").append(mascheraCodice(Prefs.getToken(this@MainActivity)))
            append("\nSOS configurati: ").append(tipi.size)
            append("\nUltima lettura: ").append(
                if (quando == 0L) "mai"
                else android.text.format.DateFormat.format("dd/MM HH:mm", quando))
            if (inCoda > 0) append("\nGiri da spedire: ").append(inCoda)
        }
        // ⚠️ Bianco, non un grigio scuro: `Theme.AppCompat` è il tema **scuro**, quindi
        // la finestra ha il fondo grigio antracite e un testo scritto per il bianco lì
        // dentro non si legge. Il riepilogo è secondario e lo dice con l'alpha — che è
        // la stessa cosa che fa il ⚙ in barra — non con un colore più scuro: sul fondo
        // scuro «più scuro» vuol dire «meno leggibile», non «meno importante».
        col.aggiungi(testo(this, info, 15f, Color.WHITE, center = false, alpha = 0.75f), 0, this)

        val dlg = AlertDialog.Builder(this).setTitle("Impostazioni").setView(
            ScrollView(this).apply { addView(col) }
        ).setNegativeButton("Chiudi", null).create()

        fun voce(label: String, azione: () -> Unit) {
            col.aggiungi(testo(this, label, 17f, Color.WHITE,
                bold = true, center = false).apply {
                setPadding(dp(4), dp(16), dp(4), dp(16))
                minimumHeight = dp(52)
                isClickable = true
                setOnClickListener { dlg.dismiss(); azione() }
            }, 6, this)
        }

        voce("🔄  Aggiorna la configurazione") {
            Thread {
                api.svuotaCoda()
                val n = api.caricaConfig()
                handler.post {
                    if (n == null) Toast.makeText(this, "Non riesco a leggere la configurazione", Toast.LENGTH_LONG).show()
                    else { tipi = n; aggiornaPagine(); Toast.makeText(this, "Aggiornato", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }
        voce("🛡️  Permesso «sopra altre app»") { chiediPermessoOverlay() }
        // Il nome dell'APK pubblicato è fisso (`Sos-latest.apk`), quindi da
        // fuori una build vale l'altra: senza questa voce non c'è nessun posto
        // in cui sapere *prima* di scaricare se c'è davvero qualcosa di nuovo.
        voce("📱  Versione app") { mostraDialogoAggiornamento(this@MainActivity) }
        voce("🔌  Scollega questo telefono") { confermaScollega() }

        dlg.show()
    }

    private fun confermaScollega() {
        val inCoda = Prefs.getPending(this).length()
        AlertDialog.Builder(this)
            .setTitle("Scollegare il telefono?")
            .setMessage(
                if (inCoda > 0)
                    "Ci sono ancora $inCoda gir${if (inCoda == 1) "o" else "i"} da spedire: " +
                    "scollegando ora quei punti si perdono. Vuoi continuare?"
                else
                    "Servirà di nuovo un codice, che si genera da SOS su Garsal Apps."
            )
            .setPositiveButton("Scollega") { _, _ ->
                Prefs.clearToken(this)
                Prefs.setPending(this, org.json.JSONArray())
                startActivity(Intent(this, PairingActivity::class.java))
                finish()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    /** Il codice non si rimostra per intero: chi ha il telefono in mano lo ha già,
        e chi ci guarda sopra le spalle no. Bastano le ultime quattro per capire
        quale codice è, che è la sola domanda a cui serve rispondere. */
    private fun mascheraCodice(t: String): String =
        if (t.length <= 4) t else "····-····-" + t.takeLast(4)
}
