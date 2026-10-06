package com.garsal.appsphere.frz

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Le 24 parole avvolte nel Keystore di Android, per aprire il Forziere con
 * l'impronta invece che con la passphrase.
 *
 * ⚠️ **QUESTO NON È `BiometricGate`, e riusarlo sarebbe un buco.** Quello è un
 * *cancello*: chiede l'impronta e poi chiama `onSbloccato()`, e su un telefono
 * senza impronta né PIN **apre lo stesso**, di proposito — un'app che non si
 * lascia più usare sarebbe peggio. Qui invece non c'è niente da chiamare: la
 * chiave nasce con `setUserAuthenticationRequired(true)`, quindi senza
 * un'autenticazione fresca il `Cipher` non produce un byte. Fallisce CHIUSO.
 *
 * ⚠️ **È il gemello di `ForziereKeystore` dell'APK WebView** (`com.garsalapps`),
 * riga per riga: due progetti Gradle separati non condividono sorgenti, e una
 * differenza fra i due sarebbe un forziere che si apre con l'impronta da una
 * parte e non dall'altra senza che niente lo dica. **Cambiando uno, cambia
 * anche l'altro.** Il blob però è di questo APK: ogni installazione ha la sua
 * chiave nel TEE, quindi le due registrazioni sono indipendenti — e va bene
 * così, perché sono due app diverse sullo stesso telefono.
 *
 * ⚠️ Solo `BIOMETRIC_STRONG`, niente `DEVICE_CREDENTIAL`: legare una chiave al
 * PIN del telefono vorrebbe dire che il forziere si apre con quello che si
 * digita davanti a chiunque in autobus.
 *
 * ⚠️ `setInvalidatedByBiometricEnrollment(true)`: registrando un'impronta nuova
 * sul telefono la chiave **muore**, e la registrazione va rifatta con le 24
 * parole o la passphrase. Senza, chi si fa aggiungere il proprio dito da un
 * telefono lasciato sbloccato si porterebbe via il forziere.
 *
 * ⚠️ Il blob avvolto vive **solo su questo telefono**, nelle preferenze: la
 * chiave che lo apre non esce mai dal TEE, quindi altrove è rumore. Non va né
 * su Drive né nel database — sarebbe un secondo bersaglio che qui non serve.
 */
object ForziereBiometria {

    private const val ALIAS = "forziere_parole_v1"
    private const val PREFS = "forziere"
    private const val BLOB = "parole_avvolte"
    private const val TRASFORMAZIONE = "AES/GCM/NoPadding"
    private const val TAG_BIT = 128

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keystore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** L'impronta c'è ed è utilizzabile su questo telefono. */
    fun disponibile(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * C'è una registrazione valida?
     *
     * ⚠️ Servono TUTT'E DUE le cose — il blob nelle preferenze e la chiave nel
     * Keystore — perché possono sparire una senza l'altra: i dati svuotati si
     * portano via il blob, un'impronta nuova si porta via la chiave. Con una
     * sola delle due si offrirebbe uno sblocco che poi non apre niente.
     */
    fun registrato(ctx: Context): Boolean {
        val haBlob = prefs(ctx).getString(BLOB, null) != null
        val haChiave = try { keystore().containsAlias(ALIAS) } catch (e: Exception) { false }
        if (haBlob != haChiave) { dimentica(ctx); return false }
        return haBlob
    }

    fun dimentica(ctx: Context) {
        prefs(ctx).edit().remove(BLOB).apply()
        try { keystore().deleteEntry(ALIAS) } catch (e: Exception) { /* non c'era */ }
    }

    private fun creaChiave(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // Zero secondi = ogni uso vuole la sua autenticazione, cioè il
                // CryptoObject. Il metodo è arrivato con API 30; sotto, la
                // stessa cosa la dice `-1` sul metodo deprecato.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun chiave(): SecretKey? =
        try { keystore().getKey(ALIAS, null) as? SecretKey } catch (e: Exception) { null }

    /**
     * Il `Cipher` per avvolgere. La chiave si rifà da capo ogni volta:
     * registrare di nuovo **sostituisce**, non affianca, e una chiave vecchia
     * lasciata lì aprirebbe un blob che non c'è più.
     */
    fun cifratore(): Cipher {
        try { keystore().deleteEntry(ALIAS) } catch (e: Exception) { /* non c'era */ }
        val c = Cipher.getInstance(TRASFORMAZIONE)
        c.init(Cipher.ENCRYPT_MODE, creaChiave())
        return c
    }

    /**
     * Il `Cipher` per riaprire, con l'IV che sta nel blob.
     *
     * Torna `null` quando la registrazione non vale più — chiave invalidata da
     * un'impronta nuova, o blob senza chiave — **e in quel caso ripulisce**: una
     * registrazione morta lasciata in giro è un pulsante che promette uno
     * sblocco e non lo dà.
     */
    fun decifratore(ctx: Context): Cipher? {
        val salvato = prefs(ctx).getString(BLOB, null) ?: return null
        val pezzi = salvato.split(":")
        if (pezzi.size != 2) { dimentica(ctx); return null }
        val k = chiave() ?: run { dimentica(ctx); return null }
        return try {
            val iv = Base64.decode(pezzi[0], Base64.NO_WRAP)
            Cipher.getInstance(TRASFORMAZIONE).apply {
                init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BIT, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            dimentica(ctx); null
        } catch (e: Exception) {
            dimentica(ctx); null
        }
    }

    /** Chiude le parole col `Cipher` già autenticato e le archivia. */
    fun avvolgi(ctx: Context, c: Cipher, parole: String) {
        val ct = c.doFinal(parole.toByteArray(Charsets.UTF_8))
        val riga = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs(ctx).edit().putString(BLOB, riga).apply()
    }

    /** Riapre le parole col `Cipher` già autenticato. */
    fun svolgi(ctx: Context, c: Cipher): String {
        val salvato = prefs(ctx).getString(BLOB, null)
            ?: throw IllegalStateException("niente da aprire")
        val ct = Base64.decode(salvato.split(":")[1], Base64.NO_WRAP)
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}
