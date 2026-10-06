package com.garsal.appsphere.frz

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBEDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * OpenPGP simmetrico: quello che `forziere.html` fa con OpenPGP.js, qui con
 * BouncyCastle.
 *
 * ⚠️ **Il formato non è nostro e non deve diventarlo.** La procedura di
 * recupero del Forziere dev'essere ricordabile a mente — `gpg -d
 * documento.gpg`, poi le 24 parole — e ogni byte che esce di qui deve aprirsi
 * con un `gpg` qualunque degli ultimi vent'anni, oggi e fra dieci anni. Quindi
 * niente ricette locali: si scrive OpenPGP e basta, e il file si porta dentro
 * algoritmo, sale e giri perché chi lo apre li legga da lui.
 *
 * ⚠️ **`setWithIntegrityPacket(true)` e non l'AEAD.** Sono i due modi che
 * OpenPGP ha di proteggere l'integrità: il vecchio MDC (SEIPD **v1**) e l'AEAD
 * nuovo (SEIPD v2, RFC 9580). GnuPG legge il secondo solo dalla **2.5** in poi,
 * che oggi non è la versione installata sulla gran parte dei computer — ed è la
 * stessa ragione per cui la pagina scrive `openpgp.config.aeadProtect = false`.
 * Le due implementazioni devono restare d'accordo su questo, o un file scritto
 * dal telefono si aprirebbe solo dal telefono.
 *
 * ⚠️ **`S2K_GIRI = 224` è lo stesso byte di OpenPGP.js**
 * (`s2kIterationCountByte: 224`), cioè 16 MiB di SHA-256 per ogni tentativo.
 * Non è una preferenza estetica: è quanto costa a chi prova a indovinare le
 * parole. Un numero più basso di qua renderebbe più economico attaccare i file
 * scritti dal telefono che quelli scritti dal PC, senza che niente lo dica.
 *
 * ⚠️ **Provato, non dedotto**: prima di scrivere questo file i due sensi sono
 * stati verificati su una JVM — OpenPGP.js scrive e BouncyCastle rilegge, poi
 * il contrario — e per soprammercato **GnuPG 2.4.4** apre tutt'e due,
 * ritrovando il nome originale col pacchetto *literal*. In un forziere un
 * errore di formato non si recupera: non è il posto dove fidarsi della
 * documentazione.
 */
object ForzierePgp {

    /** Lo stesso byte di codifica S2K di OpenPGP.js: 16 777 216 giri. */
    private const val S2K_GIRI = 224

    private const val TAMPONE = 1 shl 16

    /** Quel che un pacchetto aperto sa dire di sé: il nome che portava dentro. */
    data class Aperto(val nome: String)

    // ── Cifrare ──────────────────────────────────────────────────────────────

    /**
     * Cifra [quanti] byte letti da [sorgente] e li scrive su [dest], col [nome]
     * originale dentro il pacchetto *literal* — è quello che permette a
     * `gpg -d --use-embedded-filename` di ritrovare il file col suo nome anche
     * senza il database.
     *
     * La lunghezza si passa perché il pacchetto la dichiari invece di andare a
     * pezzi: la sappiamo comunque, visto che finisce nei metadati.
     */
    fun cifra(sorgente: InputStream, quanti: Long, nome: String, parole: String, dest: OutputStream) {
        val gen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
        )
        gen.addMethod(
            BcPBEKeyEncryptionMethodGenerator(
                parole.toCharArray(),
                BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256),
                S2K_GIRI
            )
        )
        gen.open(dest, ByteArray(TAMPONE)).use { cOut ->
            val lit = PGPLiteralDataGenerator()
            lit.open(cOut, PGPLiteralData.BINARY, nome, quanti, Date()).use { lOut ->
                sorgente.copyTo(lOut, TAMPONE)
            }
        }
    }

    fun cifraTesto(testo: String, parole: String): ByteArray {
        val dati = testo.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(dati.size + 512)
        cifra(ByteArrayInputStream(dati), dati.size.toLong(), "", parole, out)
        return out.toByteArray()
    }

    // ── Decifrare ────────────────────────────────────────────────────────────

    /**
     * Apre [sorgente] su [dest] e restituisce il nome che il pacchetto portava.
     *
     * ⚠️ **`verify()` si chiama DOPO aver letto tutti i byte**, non prima: l'MDC
     * sta in coda al pacchetto, e chiamandolo su un flusso non ancora consumato
     * risponderebbe su qualcosa che non ha visto. È il controllo che dice se
     * quel file è stato manomesso, quindi va fatto quando può davvero dirlo.
     */
    fun decifra(sorgente: InputStream, parole: String, dest: OutputStream): Aperto {
        val f = PGPObjectFactory(sorgente, BcKeyFingerprintCalculator())
        var o = f.nextObject()
        if (o is PGPMarker) o = f.nextObject()
        val lista = o as? PGPEncryptedDataList
            ?: throw PGPException("non è un pacchetto OpenPGP cifrato")
        val pbe = lista.get(0) as? PGPPBEEncryptedData
            ?: throw PGPException("questo pacchetto non si apre con una password")

        val chiaro = pbe.getDataStream(
            BcPBEDataDecryptorFactory(parole.toCharArray(), BcPGPDigestCalculatorProvider())
        )
        var pf = PGPObjectFactory(chiaro, BcKeyFingerprintCalculator())
        var m = pf.nextObject()
        // Noi non comprimiamo e OpenPGP.js nemmeno, ma un `.gpg` fatto altrove
        // (gpg da riga di comando comprime di suo) deve aprirsi lo stesso.
        if (m is PGPCompressedData) {
            pf = PGPObjectFactory(m.dataStream, BcKeyFingerprintCalculator())
            m = pf.nextObject()
        }
        val ld = m as? PGPLiteralData ?: throw PGPException("dentro non c'è un file")
        ld.inputStream.copyTo(dest, TAMPONE)
        if (!pbe.isIntegrityProtected || !pbe.verify()) {
            throw PGPException("il pacchetto non supera il controllo d'integrità")
        }
        return Aperto(ld.fileName ?: "")
    }

    fun decifraTesto(gpg: ByteArray, parole: String): String {
        val out = ByteArrayOutputStream()
        decifra(ByteArrayInputStream(gpg), parole, out)
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
