package com.garsal.speseingiro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * La foto dello scontrino: si scatta (o si pesca dalla galleria), si riduce, si
 * legge con ML Kit e si carica.
 *
 * ⚠️ Il file dello scatto sta nella **cache privata** dell'app e non nella
 * galleria: quello che conta finisce nel bucket, e una copia in chiaro fra le
 * foto del telefono è una cosa che nessuno ha chiesto. La cache si ripulisce a
 * ogni avvio.
 */
object Foto {

    private fun cartella(ctx: Context) = File(ctx.cacheDir, "scontrini").apply { mkdirs() }

    fun nuovoFile(ctx: Context): File = File(cartella(ctx), "scatto_${System.currentTimeMillis()}.jpg")

    fun uri(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.foto", f)

    fun pulisci(ctx: Context) {
        runCatching { cartella(ctx).listFiles()?.forEach { it.delete() } }
    }

    /**
     * Legge l'immagine riducendola al volo.
     *
     * ⚠️ Si passa da `inSampleSize` e non si decodifica a piena risoluzione per
     * poi ridurre: la foto di un telefono di oggi sono decine di megabyte in
     * memoria, cioè l'app che muore prima di mostrare qualcosa. È la stessa
     * ragione per cui nel Forziere il cifrato scende su un file e non in un
     * `ByteArray`.
     */
    suspend fun ridotta(ctx: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val misura = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, misura) }
            var passo = 1
            while (misura.outWidth / passo > Config.FOTO_LATO_MAX * 2 &&
                   misura.outHeight / passo > Config.FOTO_LATO_MAX * 2) passo *= 2

            val opzioni = BitmapFactory.Options().apply { inSampleSize = passo }
            val grezza = ctx.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, opzioni) } ?: return@runCatching null

            val lato = maxOf(grezza.width, grezza.height)
            val scalata = if (lato <= Config.FOTO_LATO_MAX) grezza else {
                val f = Config.FOTO_LATO_MAX.toFloat() / lato
                Bitmap.createScaledBitmap(grezza, (grezza.width * f).toInt(), (grezza.height * f).toInt(), true)
            }
            raddrizza(ctx, uri, scalata)
        }.getOrNull()
    }

    /** ⚠️ Senza l'EXIF una foto scattata in verticale arriva coricata: ML Kit su
     *  un testo ruotato di 90° non legge quasi niente, e la si crederebbe una
     *  lettura fallita invece che una foto storta. */
    private fun raddrizza(ctx: Context, uri: Uri, b: Bitmap): Bitmap = runCatching {
        val gradi = ctx.contentResolver.openInputStream(uri)?.use { flusso ->
            when (ExifInterface(flusso).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (gradi == 0f) b
        else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(gradi) }, true)
    }.getOrDefault(b)

    fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also {
        b.compress(Bitmap.CompressFormat.JPEG, Config.FOTO_QUALITA, it)
    }.toByteArray()
}
