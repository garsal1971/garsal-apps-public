# Modifiche — app WebView, nessuna regola specifica necessaria.
# ⚠️ I due ponti JavaScript vanno tenuti: senza, la pagina non condivide più
# niente e non ritrova le credenziali salvate.
-keepattributes *Annotation*
-keepclassmembers class com.garsal.modifiche.** {
    @android.webkit.JavascriptInterface <methods>;
}
