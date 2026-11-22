package com.garsal.pronuncia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * v1.38.0: i comandi a voce in italiano, riconosciuti SUL TELEFONO da Vosk
 * (modello piccolo italiano, offline) con una grammatica chiusa: i sei comandi
 * più `[unk]`, «parola sconosciuta».
 *
 * ⚠️ Ha preso il posto delle registrazioni della propria voce (v1.30.0-v1.37.0,
 * MFCC + DTW su tre campioni): nella Prova funzionavano, nel ciclo il comando
 * restava spesso appena fuori soglia, finiva ad Azure come tentativo e Azure lo
 * forzava sulla parola attesa («sword» a 5). Le vecchie registrazioni si
 * cancellano all'avvio.
 *
 * ⚠️ `[unk]` nella grammatica è la metà che conta: senza, Vosk è costretto a
 * scegliere uno dei sei comandi per qualunque suono, e ogni parola inglese
 * diventerebbe un comando. Un comando vale solo se Vosk sente QUELLA parola e
 * nient'altro, con una sicurezza almeno [Prefs.sicurezzaComandi].
 *
 * Il modello (~48 MB zip, ~90 MB scompattato) non sta nell'APK: si scarica una
 * volta da R2 ([URL_MODELLO], caricato da `modello-vosk-r2.yml`) e resta in
 * `filesDir/vosk-it`. Senza modello niente comandi a voce: restano i tasti
 * delle cuffie.
 */
class ComandiVoce(ctx: Context) {

    /** I comandi, con la parola italiana che Vosk deve sentire. */
    enum class Voce(val comando: Comando, val parola: String) {
        RIPETI(Comando.RIPETI, "Ripeti"),
        AVANTI(Comando.AVANTI, "Avanti"),
        LENTO(Comando.LENTO, "Lento"),
        FERMA(Comando.FERMA, "Ferma"),
        ORTOGRAFIA(Comando.SPELLING, "Ortografia"),
        TRADUCI(Comando.TRADUCI, "Traduci");

        val chiave get() = parola.lowercase()
    }

    sealed interface StatoModello {
        data object Assente : StatoModello
        data class Scarico(val percento: Int) : StatoModello
        data object Pronto : StatoModello
        data class Errore(val messaggio: String) : StatoModello
    }

    /** Quel che Vosk ha sentito, anche per la 🧪 Prova in Impostazioni. */
    data class Esito(val voce: Voce?, val sentito: String, val sicurezza: Double)

    private val cartella = File(ctx.filesDir, "vosk-it")
    private val temporanea = File(ctx.filesDir, "vosk-it.tmp")
    private val zip = File(ctx.cacheDir, "vosk-it.zip")

    val stato = MutableStateFlow<StatoModello>(if (presente()) StatoModello.Pronto else StatoModello.Assente)

    @Volatile private var modello: Model? = null

    init {
        // Le registrazioni della v1.30.0: sono la voce di una persona, e ora
        // non servono a niente.
        File(ctx.filesDir, "comandi").deleteRecursively()
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
    }

    private fun presente() = File(cartella, "am/final.mdl").exists() && File(cartella, "conf/model.conf").exists()

    /** Il modello c'è: i comandi a voce sono accesi. */
    fun pronti(): Boolean = presente()

    /** Carica il modello (un paio di secondi la prima volta), poi lo tiene. */
    @Synchronized private fun modello(): Model? {
        modello?.let { return it }
        if (!presente()) return null
        return Model(cartella.absolutePath).also { modello = it }
    }

    /** Il modello in memoria prima che serva: si chiama all'avvio del ciclo. */
    suspend fun precarica() = withContext(Dispatchers.IO) { runCatching { modello() }; Unit }

    /** Il comando detto in [pcm], o `null` se non è un comando. */
    fun riconosci(pcm: ByteArray, sicurezza: Double): Comando? =
        runCatching { confronta(pcm, sicurezza).voce?.comando }.getOrNull()

    fun confronta(pcm: ByteArray, sicurezza: Double): Esito {
        val m = modello() ?: return Esito(null, "", 0.0)
        val json = Recognizer(m, Ascolto.HZ.toFloat(), GRAMMATICA).use { r ->
            r.setWords(true)
            r.acceptWaveForm(pcm, pcm.size)
            r.finalResult
        }
        return leggi(json, sicurezza)
    }

    /** ⚠️ Lo scarico vive qui e non nella schermata: le pagine non visibili si
     *  smontano, e uscendo dalle Impostazioni si annullerebbe a metà. */
    private val giro = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Avvia lo scarico, se non è già in corso. */
    fun avviaScarico() {
        if (stato.value is StatoModello.Scarico) return
        stato.value = StatoModello.Scarico(0)
        giro.launch { scarica() }
    }

    /** Scarica e scompatta il modello, con l'avanzamento in [stato]. */
    private suspend fun scarica() = withContext(Dispatchers.IO) {
        try {
            stato.value = StatoModello.Scarico(0)
            val c = URL(URL_MODELLO).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            try {
                if (c.responseCode != 200) error("il server risponde ${c.responseCode}")
                val totale = c.contentLengthLong
                c.inputStream.use { inp ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var letti = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); letti += n
                            if (totale > 0) stato.value = StatoModello.Scarico((letti * 90 / totale).toInt())
                        }
                    }
                }
            } finally { c.disconnect() }
            stato.value = StatoModello.Scarico(92)
            scompatta()
            stato.value = if (presente()) StatoModello.Pronto else StatoModello.Errore("il modello scaricato è incompleto")
        } catch (e: kotlinx.coroutines.CancellationException) {
            stato.value = if (presente()) StatoModello.Pronto else StatoModello.Assente
            throw e
        } catch (e: Exception) {
            stato.value = StatoModello.Errore(e.message ?: e.javaClass.simpleName)
        } finally {
            zip.delete()
            temporanea.deleteRecursively()
        }
        Unit
    }

    /** Lo zip ha una cartella in testa (`vosk-model-small-it-0.22/`): si toglie.
     *  ⚠️ Si scompatta in una cartella temporanea e si rinomina solo alla fine:
     *  un modello a metà non deve sembrare pronto. */
    private fun scompatta() {
        temporanea.deleteRecursively(); temporanea.mkdirs()
        val base = temporanea.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val nome = e.name.substringAfter('/', "")
                if (nome.isEmpty()) continue
                val f = File(temporanea, nome)
                // Niente percorsi che escono dalla cartella («zip slip»).
                if (!f.canonicalPath.startsWith(base)) error("percorso non valido nello zip: ${e.name}")
                if (e.isDirectory) f.mkdirs()
                else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        synchronized(this) {
            modello?.close(); modello = null
            cartella.deleteRecursively()
            if (!temporanea.renameTo(cartella)) error("non riesco a spostare il modello")
        }
    }

    @Synchronized fun cancella() {
        modello?.close(); modello = null
        cartella.deleteRecursively()
        stato.value = StatoModello.Assente
    }

    companion object {
        const val URL_MODELLO = "https://apk.garsal.men/vosk-model-small-it-0.22.zip"

        /** La grammatica: i sei comandi e la parola sconosciuta. */
        private val GRAMMATICA = org.json.JSONArray(Voce.entries.map { it.chiave } + "[unk]").toString()

        /** È un comando solo se Vosk ha sentito UNA parola, che è un comando,
         *  con sicurezza almeno [sicurezza]. Un `[unk]` accanto vuol dire che
         *  c'era dell'altro: una parola inglese, non un comando. */
        fun leggi(json: String, sicurezza: Double): Esito {
            val o = JSONObject(json)
            val parole = o.optJSONArray("result")
            val sentite = (0 until (parole?.length() ?: 0)).map { i ->
                val w = parole!!.getJSONObject(i)
                w.optString("word") to w.optDouble("conf", 0.0)
            }
            val testo = o.optString("text").trim()
            if (sentite.size != 1) return Esito(null, testo, sentite.minOfOrNull { it.second } ?: 0.0)
            val (parola, conf) = sentite[0]
            val voce = Voce.entries.firstOrNull { it.chiave == parola }
            return Esito(voce.takeIf { conf >= sicurezza }, parola, conf)
        }
    }
}
