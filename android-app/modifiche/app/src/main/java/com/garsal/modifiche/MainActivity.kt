package com.garsal.modifiche

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * «Modifiche» — la WebView che apre `garsal.men/modifiche.html`.
 *
 * Due cose sole la distinguono da un WebView qualunque, e sono tutt'e due
 * necessarie perché la pagina funzioni:
 *
 *  1. **il selettore dei file** (`onShowFileChooser`): senza, un
 *     `<input type="file">` dentro una WebView **non fa niente** — nessun
 *     errore, nessun selettore, il tocco cade nel vuoto. E le schermate sono
 *     metà di quel che questa app esiste per raccogliere;
 *  2. **i due ponti JavaScript**: `AndroidBridge` per il tasto Condividi di
 *     sistema, `AndroidCreds` per rientrare da soli senza rifare il login.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        private const val APP_URL = "https://garsal.men/modifiche.html"

        /**
         * Lo schema con cui `oauth-callback-modifiche.html` rilancia l'app dopo
         * il login Google. È **proprio di quest'app**: con `garsalapps://` o
         * `garsalnative://` Android chiederebbe a ogni login quale delle tre
         * aprire. Sta anche nell'intent-filter del manifest — se si cambia
         * qui, si cambia lì e nella pagina-ponte.
         */
        private const val SCHEMA_OAUTH = "garsalmodifiche"
    }

    /**
     * ⚠️ La callback del selettore va sempre chiamata, anche annullando: se
     * resta appesa, l'`<input type="file">` della pagina è **morto per sempre**
     * e ogni tocco successivo non apre più niente. Un `null` è esattamente quel
     * che va rimandato indietro su un annullamento vero.
     */
    private var attesaFile: ValueCallback<Array<Uri>>? = null

    /** Quando è partito il selettore che stiamo aspettando (vedi la guardia sotto). */
    private var quandoChiesto = 0L

    /**
     * ⚠️ IL PHOTO PICKER DI SISTEMA, e non l'Intent che la WebView propone.
     *
     * `FileChooserParams.createIntent()` produce un `ACTION_GET_CONTENT`, e quale
     * app lo apra lo decide il telefono: una galleria qualunque, che può
     * restituire le foto scelte con un `resultCode` diverso da `RESULT_OK`.
     * `FileChooserParams.parseResult` a quel punto **butta via tutto senza
     * guardarlo** — le immagini sono dentro l'Intent e noi rispondiamo `null`.
     * Da fuori è indistinguibile da un annullamento: si scelgono le schermate, si
     * preme Fine, si torna nell'app e non c'è niente, senza nessun errore da
     * nessuna parte. È il difetto del 14 settembre 2026.
     *
     * Il Photo Picker restituisce invece direttamente la lista degli URI: niente
     * Intent da costruire, niente `resultCode` da indovinare, niente `parseResult`.
     */
    private val scegliFoto =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
            consegna(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    /**
     * La strada vecchia, per quel che immagini non è (o dove il Photo Picker non
     * c'è). ⚠️ **Si guarda l'Intent PRIMA di `parseResult`**: se porta degli URI
     * quelle sono le foto scelte, comunque sia andato il `resultCode`.
     */
    private val scegliFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { esito ->
            consegna(
                uriDa(esito.data)
                    ?: WebChromeClient.FileChooserParams.parseResult(esito.resultCode, esito.data)
            )
        }

    private fun consegna(uris: Array<Uri>?) {
        val callback = attesaFile
        attesaFile = null
        quandoChiesto = 0L
        callback?.onReceiveValue(uris)
    }

    /** Gli URI dentro un Intent di ritorno: prima la selezione multipla, poi la singola. */
    private fun uriDa(data: Intent?): Array<Uri>? {
        if (data == null) return null
        data.clipData?.let { clip ->
            val presi = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (presi.isNotEmpty()) return presi.toTypedArray()
        }
        data.data?.let { return arrayOf(it) }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled    = true
            // localStorage: qui vive la sessione Supabase. Senza, ogni apertura
            // dell'app chiederebbe di nuovo di accedere.
            domStorageEnabled    = true
            allowFileAccess      = false
            // ⚠️ `allowContentAccess` DEVE restare acceso, ed è l'opposto di
            // quel che fanno le altre WebView della repo: le schermate scelte
            // col selettore di sistema arrivano come `content://`, e con
            // l'accesso spento la pagina se le ritrova vuote — cioè si
            // scelgono le foto e non compare niente, senza nessun errore.
            // `allowFileAccess` resta spento: quello è `file://`, che qui non
            // serve a nessuno.
            allowContentAccess   = true
            cacheMode            = WebSettings.LOAD_DEFAULT
            useWideViewPort      = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            textZoom             = 100
            userAgentString      = "$userAgentString ModificheApp/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Rimuove user-scalable=no: le schermate si guardano ingrandendole.
                view?.evaluateJavascript(
                    """
                    (function(){
                        var m=document.querySelector('meta[name="viewport"]');
                        if(m){m.setAttribute('content',m.content
                            .replace(/user-scalable\s*=\s*(no|0)/gi,'user-scalable=yes')
                            .replace(/maximum-scale\s*=\s*[0-9.]+/gi,'maximum-scale=5.0'));}
                    })();
                    """.trimIndent(), null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                // ⚠️ Una seconda richiesta a ridosso della prima non la annulla.
                // Un tocco può arrivare doppio, e il secondo giro chiuderebbe la
                // callback del primo: il selettore che si vede a schermo sarebbe
                // quello la cui risposta non aspetta più nessuno.
                if (attesaFile != null && SystemClock.elapsedRealtime() - quandoChiesto < 1000) {
                    return false
                }
                // Un selettore rimasto appeso da prima, invece, va chiuso: se resta
                // lì l'input della pagina è morto per il resto della sessione.
                attesaFile?.onReceiveValue(null)
                attesaFile = filePathCallback
                quandoChiesto = SystemClock.elapsedRealtime()
                return try {
                    if (soloImmagini(params) &&
                        ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this@MainActivity)
                    ) {
                        scegliFoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    } else {
                        scegliFile.launch(params!!.createIntent())
                    }
                    true
                } catch (e: Exception) {
                    attesaFile = null
                    quandoChiesto = 0L
                    filePathCallback?.onReceiveValue(null)
                    false
                }
            }

            /** Il campo chiede solo immagini? Allora il Photo Picker basta e avanza. */
            private fun soloImmagini(params: WebChromeClient.FileChooserParams?): Boolean {
                val tipi = params?.acceptTypes?.filter { it.isNotBlank() } ?: return false
                return tipi.isNotEmpty() && tipi.all { it.trim().startsWith("image/") }
            }
        }

        webView.addJavascriptInterface(CondivisioneBridge(this), "AndroidBridge")
        webView.addJavascriptInterface(CredentialsBridge(this), "AndroidCreds")
        webView.addJavascriptInterface(AppBridge(this), "AndroidApp")

        // Aperta dal link ricevuto via email, o dal rientro del login Google:
        // in tutt'e due i casi l'indirizzo porta il token che la pagina legge
        // per salvare la sessione.
        webView.loadUrl(indirizzoDa(intent) ?: APP_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // La pagina spinge una voce di cronologia quando apre un popup
                // (`guardiaIndietroPopup`): l'indietro chiude quello, e solo
                // quando non c'è più niente da chiudere esce dall'app.
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        indirizzoDa(intent)?.let { webView.loadUrl(it) }
    }

    /**
     * Che indirizzo caricare per l'intent con cui l'app è stata aperta.
     *
     * Il **link via email** è già un `https` e si carica com'è: punta a
     * `auth/v1/verify` di Supabase, che poi reindirizza alla pagina, e la
     * WebView segue il redirect da sé.
     *
     * Il **rientro dal login Google** arriva invece su uno schema proprio,
     * `garsalmodifiche://oauth#access_token=…`, che una WebView non sa
     * caricare: quel che serve è il **fragment**, che si riattacca all'app.
     * Da lì la sessione la raccoglie supabase-js, come farebbe nel browser.
     *
     * ⚠️ Il token NON passa da `evaluateJavascript`: là finirebbe dentro una
     * stringa di codice sorgente. Sta nell'indirizzo, che è dove la libreria
     * lo cerca già di suo.
     *
     * ⚠️ L'intent si **consuma**: `getIntent()` continua a restituire quello
     * di partenza per tutta la vita dell'Activity, quindi a ogni ricreazione
     * `onCreate` si ritroverebbe lo stesso `access_token`, ormai scaduto o
     * già speso, e lo rimetterebbe al posto di una sessione buona. È lo stesso
     * inciampo già pagato da `gestisciDeepLink` dell'APK nativa.
     */
    private fun indirizzoDa(intent: Intent?): String? {
        val dati = intent?.data ?: return null
        intent.data = null
        if (dati.scheme != SCHEMA_OAUTH) return dati.toString()
        val frammento = dati.fragment
        if (frammento.isNullOrBlank()) return APP_URL
        return "$APP_URL#$frammento"
    }
}
