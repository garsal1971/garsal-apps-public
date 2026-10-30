package com.garsal.pronuncia

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.garsal.pronuncia.ui.SchermataPrincipale
import com.garsal.pronuncia.ui.TemaPronuncia
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Gli oggetti che vivono quanto il processo: una sessione sola, un token Azure
 * solo. Dalla fase 3 li userà anche il servizio in primo piano, ed è la
 * ragione per cui non stanno dentro l'Activity.
 */
object Servizi {
    lateinit var prefs: Prefs
    lateinit var auth: Auth
    lateinit var azure: AzureToken
    lateinit var tts: Tts
    lateinit var valutatore: Valutatore
    lateinit var traduzioni: Traduzioni

    /** Cambia quando la sessione cambia (rientro dal login, uscita): la
     *  schermata la osserva invece di rileggere le preferenze a ogni frame. */
    val connesso = MutableStateFlow(false)

    fun avvia(ctx: android.content.Context) {
        if (::prefs.isInitialized) return
        prefs = Prefs(ctx.applicationContext)
        auth = Auth(prefs)
        azure = AzureToken(auth)
        tts = Tts(ctx.applicationContext, azure)
        valutatore = Valutatore(azure)
        traduzioni = Traduzioni(ctx.applicationContext, auth)
        connesso.value = auth.connesso
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Servizi.avvia(this)
        enableEdgeToEdge()
        // ⚠️ Solo al primo avvio: una ricreazione del sistema ripresenterebbe
        // lo stesso intent, cioè un token già speso al posto di una sessione buona.
        if (savedInstanceState == null) gestisciRientro(intent)
        setContent { TemaPronuncia { SchermataPrincipale() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gestisciRientro(intent)
    }

    private fun gestisciRientro(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "garsalpronuncia" || uri.host != "oauth") return
        Servizi.auth.completa(uri)
        Servizi.connesso.value = Servizi.auth.connesso
        // Il deep link si consuma una volta sola: `getIntent()` lo
        // restituirebbe a ogni ricreazione dell'Activity.
        intent.data = null
    }
}
