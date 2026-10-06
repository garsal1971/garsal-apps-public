package com.garsal.sos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * L'accoppiamento, una volta sola.
 *
 * L'APK non fa il login Google: si digita il codice generato da SOS su Garsal
 * Apps e da lì in poi il telefono parla con quattro RPC che lo riconoscono da
 * quel codice. Il perché è nella schermata stessa: il bottone SOS si preme in
 * un momento di crisi, e in quel momento non si può inciampare in una sessione
 * scaduta o in una schermata di Google che chiede di riautenticarsi.
 */
class PairingActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var campo: EditText
    private lateinit var stato: TextView
    private lateinit var azione: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#12121A"))
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(32))
        }

        col.aggiungi(testo(this, "🆘", 52f), 0, this)
        col.aggiungi(testo(this, "SOS", 30f, Color.WHITE, bold = true), 10, this)
        col.aggiungi(testo(this,
            "Apri SOS su Garsal Apps, vai in 📱 Telefoni e genera un codice. " +
            "Poi scrivilo qui: serve una volta sola.",
            16f, Color.WHITE, alpha = 0.75f), 14, this)

        campo = EditText(this).apply {
            hint = "ABCD-EFGH-JKMN"
            setHintTextColor(Color.argb(90, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(14))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = sfondoTondo(Color.argb(30, 255, 255, 255), dp(14),
                dp(1), Color.argb(80, 255, 255, 255))
        }
        col.aggiungi(campo, 26, this)

        stato = testo(this, "", 15f, Color.parseColor("#F39C12"), bold = true).apply {
            visibility = View.GONE
        }
        col.aggiungi(stato, 12, this)

        azione = testo(this, "Collega", 19f, Color.WHITE, bold = true).apply {
            background = sfondoTondo(Color.parseColor("#EE334E"), dp(14))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            minimumHeight = dp(56)
            isClickable = true
            setOnClickListener { collega() }
        }
        col.aggiungi(azione, 18, this)

        sv.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)
    }

    private fun collega() {
        val codice = campo.text.toString().trim().uppercase()
        if (codice.length < 8) {
            mostra("Il codice è fatto di dodici lettere e numeri, a gruppi di quattro.")
            return
        }
        azione.isEnabled = false
        mostra("Verifico…")
        Thread {
            val e = SosApi(this).provaCodice(codice)
            handler.post {
                azione.isEnabled = true
                if (e.ok) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    // Si distingue «codice sbagliato» da «rete assente»: sono due
                    // problemi diversi e si risolvono in due posti diversi.
                    mostra(if (e.body != null) "Codice non valido o revocato."
                           else "Non riesco a raggiungere il server: controlla la connessione.")
                }
            }
        }.start()
    }

    private fun mostra(msg: String) {
        stato.text = msg
        stato.visibility = View.VISIBLE
    }
}
