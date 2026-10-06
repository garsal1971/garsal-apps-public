# minifyEnabled è false: queste regole servono solo se un giorno si accende.
-keepattributes *Annotation*, InnerClasses, Signature

# kotlinx.serialization — i serializer sono generati e raggiunti per riflessione
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
