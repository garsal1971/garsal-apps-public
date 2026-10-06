package com.garsal.appsphere.memo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Una foto scelta o scattata, ancora da caricare. */
data class FotoInAttesa(
    val uri: Uri,
    val nome: String,
    val mime: String,
)

/**
 * Le foto delle schede: leggerle dal telefono, riconoscerne il testo, e il file
 * temporaneo dove finisce lo scatto della fotocamera.
 */
object MemoFoto {

    /** Lo stesso tetto del web (`addPendingImage`): 5 MB a foto. */
    const val MASSIMO_BYTE = 5 * 1024 * 1024

    /**
     * Nome, tipo e peso di un uri scelto dal telefono.
     *
     * Torna `null` se la foto è più grande del tetto: caricarla vorrebbe dire
     * tenere in memoria un file enorme e farlo rimbalzare dal bucket a metà
     * strada.
     */
    fun descrivi(context: Context, uri: Uri): FotoInAttesa? {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        var nome = "foto." + mime.substringAfter('/', "jpg")
        var peso = -1L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iNome = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iPeso = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iNome >= 0 && !c.isNull(iNome)) nome = c.getString(iNome)
                    if (iPeso >= 0 && !c.isNull(iPeso)) peso = c.getLong(iPeso)
                }
            }
        }

        if (peso > MASSIMO_BYTE) return null
        return FotoInAttesa(uri, nome, mime)
    }

    /** I byte del file, letti fuori dal thread principale. */
    suspend fun byte(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Foto non leggibile")
    }

    /**
     * Dove la fotocamera scrive lo scatto: un file in `cache/foto`, esposto
     * col FileProvider dichiarato nel manifest. Da Android 7 un `file://`
     * passato a un'altra app la fa crashare, quindi l'uri deve venire da lì.
     */
    fun uriPerScatto(context: Context): Uri {
        val cartella = File(context.cacheDir, "foto").apply { mkdirs() }
        val file = File(cartella, "scatto_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Il testo dentro una foto, con ML Kit — la stessa libreria che l'APK
     * WebView usa per l'OCR di `memo.html` (`AndroidBridge.performOcr`).
     *
     * Qui non c'è il ripiego su Tesseract: quello nel web serve al browser
     * desktop, che ML Kit non ce l'ha.
     */
    suspend fun testoDaFoto(context: Context, uri: Uri): String =
        suspendCancellableCoroutine { cont ->
            runCatching {
                val immagine = InputImage.fromFilePath(context, uri)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(immagine)
                    .addOnSuccessListener { esito -> if (cont.isActive) cont.resume(esito.text) }
                    .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
            }.onFailure { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
}
