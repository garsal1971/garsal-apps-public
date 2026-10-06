package com.garsal.situazionerosa

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        private const val APP_URL = "https://garsal.men/situazione-rosa.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled   = true
            domStorageEnabled   = true   // localStorage: qui vive la sessione (garsal_session)
            allowFileAccess     = false
            allowContentAccess  = false
            cacheMode           = WebSettings.LOAD_DEFAULT
            useWideViewPort     = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            textZoom             = 100
            // Marcatore per far sapere alla pagina che gira dentro l'app
            // (nasconde il pulsante "Scarica APK", inutile qui dentro).
            userAgentString = userAgentString + " SituazioneRosaApp/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Rimuove user-scalable=no per abilitare pinch-to-zoom
                view?.evaluateJavascript("""
                    (function(){
                        var m=document.querySelector('meta[name="viewport"]');
                        if(m){m.setAttribute('content',m.content
                            .replace(/user-scalable\s*=\s*(no|0)/gi,'user-scalable=yes')
                            .replace(/maximum-scale\s*=\s*[0-9.]+/gi,'maximum-scale=5.0'));}
                    })();
                """.trimIndent(), null)
            }
        }

        // Bridge per le credenziali salvate in modo cifrato (Android Keystore):
        // la pagina lo usa per rientrare da sola se la sessione è scaduta.
        webView.addJavascriptInterface(CredentialsBridge(this), "AndroidCreds")

        // Se l'app è stata aperta dal link "magic link" ricevuto via email
        // (intent-filter VIEW su garsal.men/situazione-rosa.html),
        // carica quell'URL così com'è: contiene #access_token=... che la
        // pagina legge per salvare la sessione nel suo localStorage.
        // Altrimenti carica normalmente la pagina (che userà la sessione
        // già salvata da un login precedente, senza richiedere nulla).
        webView.loadUrl(intent?.data?.toString() ?: APP_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App già in esecuzione (launchMode singleTask) e link magic-link
        // aperto di nuovo: ricarica con il nuovo token nell'hash.
        intent.data?.toString()?.let { webView.loadUrl(it) }
    }
}
