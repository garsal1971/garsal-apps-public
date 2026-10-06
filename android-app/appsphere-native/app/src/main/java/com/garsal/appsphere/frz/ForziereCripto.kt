package com.garsal.appsphere.frz

import android.util.Base64
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * I metadati del Forziere: **AES-256-GCM sotto la chiave dell'indice**, e non
 * OpenPGP.
 *
 * ⚠️ La ragione è nella pagina e vale uguale qui: ogni apertura OpenPGP con una
 * password rifà la derivazione lenta, e per trecento nomi di file vorrebbe dire
 * trecento derivazioni, cioè minuti. La chiave dell'indice è 32 byte casuali —
 * non si indovina, quindi non serve renderla lenta — e sta su Drive dentro
 * `indice.gpg`, chiusa con le 24 parole.
 *
 * ⚠️ **Il formato sul filo è `base64(iv[12] ‖ cifrato‖tag)`**, esattamente
 * quello che scrive `cifraMeta()` in `forziere.html`. Le due implementazioni
 * leggono le stesse righe di `frz_files.meta_enc`, `frz_boxes.meta_enc` e
 * `frz_thumbs.thumb_enc`: un byte di differenza qui e il telefono mostrerebbe
 * «nome illeggibile» su tutto quello che ha scritto il PC. Provato in tutt'e
 * due i sensi prima di scrivere questo file.
 *
 * ⚠️ Sul web la chiave è una `CryptoKey` con `extractable: false`: esiste nel
 * browser ma da JavaScript non se ne leggono i byte. Qui una cosa del genere
 * non c'è per una chiave che nasce ogni volta da `indice.gpg`, e non
 * cambierebbe niente comunque: in questa stessa memoria ci sono già **le 24
 * parole**, che aprono molto di più. È il motivo per cui il forziere si chiude
 * da sé, ed è quella la difesa.
 */
object ForziereCripto {

    private const val TAG_BIT = 128
    private const val IV_BYTE = 12

    // ── Le 24 parole ─────────────────────────────────────────────────────────

    /**
     * La forma canonica delle 24 parole: **tutto minuscolo, una parola dopo
     * l'altra separate da uno spazio solo**.
     *
     * ⚠️ Dev'essere rifacibile a mente davanti a un terminale, perché le stesse
     * parole si riscrivono dentro `gpg`, che non normalizza niente e prende i
     * byte che digiti. Qualsiasi regola più furba sarebbe irripetibile — ed è
     * per questo che è così semplice, non per pigrizia.
     *
     * ⚠️ **Gemella di `normParole()` in `forziere.html`, e va cambiata
     * insieme.** L'unica sottigliezza è la classe degli spazi: `\s` in
     * JavaScript comprende anche lo spazio unificatore e i vari spazi
     * tipografici, in Java no — scritta `\\s+` e basta, una parola separata da
     * uno spazio unificatore darebbe qui una stringa diversa, cioè un forziere
     * che dal telefono non si apre.
     */
    private val SPAZI = Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

    fun normParole(s: String?): String =
        Normalizer.normalize(s ?: "", Normalizer.Form.NFC)
            .replace(SPAZI, " ")
            .trim()
            .lowercase()

    // ── base64, come `btoa` ──────────────────────────────────────────────────

    /** ⚠️ `NO_WRAP`: `btoa` non manda a capo, e una riga spezzata non si rilegge. */
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun daB64(s: String): ByteArray = Base64.decode(s.trim(), Base64.DEFAULT)

    // ── La chiave dell'indice ────────────────────────────────────────────────

    /** I 32 byte che escono da `indice.gpg`, pronti per AES-GCM. */
    fun chiaveIndice(bytes: ByteArray): SecretKeySpec {
        require(bytes.size == 32) { "la chiave dell'indice non è di 32 byte" }
        return SecretKeySpec(bytes, "AES")
    }

    // ── Metadati ─────────────────────────────────────────────────────────────

    fun cifraBytes(chiave: SecretKeySpec, dati: ByteArray): String {
        val iv = ByteArray(IV_BYTE).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chiave, GCMParameterSpec(TAG_BIT, iv))
        val ct = c.doFinal(dati)
        val out = ByteArray(IV_BYTE + ct.size)
        System.arraycopy(iv, 0, out, 0, IV_BYTE)
        System.arraycopy(ct, 0, out, IV_BYTE, ct.size)
        return b64(out)
    }

    fun decifraBytes(chiave: SecretKeySpec, s: String): ByteArray {
        val raw = daB64(s)
        require(raw.size > IV_BYTE) { "blob troppo corto" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chiave, GCMParameterSpec(TAG_BIT, raw.copyOfRange(0, IV_BYTE)))
        return c.doFinal(raw, IV_BYTE, raw.size - IV_BYTE)
    }

    fun cifraTesto(chiave: SecretKeySpec, testo: String): String =
        cifraBytes(chiave, testo.toByteArray(Charsets.UTF_8))

    fun decifraTesto(chiave: SecretKeySpec, s: String): String =
        String(decifraBytes(chiave, s), Charsets.UTF_8)
}
