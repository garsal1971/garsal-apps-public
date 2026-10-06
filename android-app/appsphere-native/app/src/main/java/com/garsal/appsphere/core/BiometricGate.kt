package com.garsal.appsphere.core

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Impronta all'avvio, con il PIN del telefono come ripiego — le stesse due
 * strade dell'APK WebView (`MainActivity.kt`, `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`).
 *
 * Se il telefono non ha né impronta né PIN registrati non si blocca niente:
 * chiedere una credenziale che non esiste lascerebbe l'app inutilizzabile.
 */
object BiometricGate {

    private const val CONSENTITI = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * Un prompt per volta.
     *
     * Chiedere di nuovo mentre uno è già a schermo non ne apre un secondo:
     * annulla il primo, che risponde `ERROR_CANCELED` — cioè "l'utente ha
     * rinunciato" — e chi ascolta si vede tornare una rinuncia che nessuno ha
     * fatto. Col PIN del telefono, che è una schermata a parte e quindi manda
     * l'app in background, il giro si richiude su sé stesso e non ne esce più.
     */
    private var inCorso = false

    fun disponibile(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(CONSENTITI) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun chiedi(
        activity: FragmentActivity,
        onSbloccato: () -> Unit,
        onRinuncia: () -> Unit,
    ) {
        if (!disponibile(activity)) {
            onSbloccato()
            return
        }
        if (inCorso) return
        inCorso = true

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    inCorso = false
                    onSbloccato()
                }

                // Errore ≠ tentativo sbagliato: qui ci finiscono l'annullamento
                // e il "troppi tentativi". In entrambi i casi l'app resta chiusa
                // e si riprova dal pulsante, senza sbloccare niente.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    inCorso = false
                    onRinuncia()
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("AppSphere")
                .setSubtitle("Sblocca per continuare")
                .setAllowedAuthenticators(CONSENTITI)
                .build()
        )
    }
}
