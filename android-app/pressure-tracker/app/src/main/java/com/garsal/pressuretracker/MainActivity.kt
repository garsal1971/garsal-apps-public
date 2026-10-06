package com.garsal.pressuretracker

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled   = true
            domStorageEnabled   = true   // localStorage
            allowFileAccess     = true
            allowContentAccess  = true
            cacheMode           = WebSettings.LOAD_DEFAULT
            useWideViewPort     = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            textZoom             = 100
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

        // Carica l'HTML incluso nel bundle dell'app
        webView.loadUrl("file:///android_asset/pressure-tracker.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else finish()
            }
        })
    }
}
