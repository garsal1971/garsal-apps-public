package com.garsal.appsphere.frz

import android.util.Log
import com.garsal.appsphere.BuildConfig
import com.garsal.appsphere.core.Jwt
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Il ponte col Drive del Forziere: la stessa Edge Function `forziere-drive` che
 * chiama `forziere.html`, con le stesse azioni.
 *
 * ⚠️ **Da qui non passa mai niente in chiaro.** Tutto quel che sale è già
 * chiuso con OpenPGP dalle 24 parole, che alla funzione non arrivano né adesso
 * né mai: la funzione trasporta byte e non li può leggere. È la proprietà che
 * regge il Forziere, e vale identica di qua.
 *
 * ⚠️ **HttpURLConnection e non un client HTTP in più.** Serve una cosa sola —
 * POST di JSON e un PUT in flusso verso Google — e ogni libreria aggiunta a
 * questo APK va nel DEX intera: è la stessa ragione per cui
 * `material-icons-extended` non si reintroduce. Il JDK ce l'ha già.
 */
object ForziereDrive {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * ⚠️ Il ripiego vale fino a qui, come nel web: sopra, un file che non passa
     * dalla strada diretta fallisce invece di riempire la memoria della Edge
     * Function. I byte sono già cifrati, quindi passare di là non cambia niente
     * di quel che qualcuno vedrebbe — cambia solo chi li trasporta.
     */
    private const val RIPIEGO_MAX = 8L * 1024 * 1024

    private const val TAG = "ForziereDrive"

    /**
     * ⚠️ **Questo messaggio non nomina il Drive**, ed è il punto: il 10 settembre
     * 2026 un token scaduto si è letto come ❌ *«Drive: … serve un login valido»*
     * e ha mandato a cercare il guasto dalla parte sbagliata. Il Drive non
     * c'entra: non ci si è nemmeno arrivati.
     */
    private const val SESSIONE_SCADUTA =
        "la sessione è scaduta: rientra in AppSphere e riapri il Forziere"

    private fun indirizzo() = BuildConfig.SUPABASE_URL + "/functions/v1/forziere-drive"

    /**
     * Il token da mandare, **vivo**.
     *
     * ⚠️ Fino alla v1.0.77 qui c'era `currentSessionOrNull()?.accessToken` e
     * basta: si guardava se un token *c'era*, non se valeva ancora qualcosa. Un
     * access token di Supabase dura **un'ora**, questo è l'unico posto dell'app
     * che lo legge a mano — tutto il resto passa da supabase-kt, che il rinnovo
     * se lo fa da sé — e con l'app rimasta aperta si finiva a mandare un token
     * morto. La Edge Function rispondeva 401, e l'app scriveva il JSON grezzo.
     *
     * ⚠️ **Dopo un rinnovo il token si usa e non si ricontrolla**: `Jwt.vivo`
     * guarda l'orologio del telefono, e su un telefono con l'ora sbagliata un
     * secondo controllo direbbe «scaduto» anche su un token appena nato — cioè
     * il Forziere chiuso per un orologio storto. Al più si rinnova una volta di
     * troppo, che costa un viaggio di rete; a decidere se quel token vale resta
     * il server, che è l'unico che lo sa.
     */
    private suspend fun token(): String {
        val corrente = Supabase.client().auth.currentSessionOrNull()?.accessToken
        if (corrente != null && Jwt.vivo(corrente)) return corrente
        return rinnova() ?: throw IllegalStateException(SESSIONE_SCADUTA)
    }

    /** Rinnova la sessione e restituisce il token nuovo, o `null` se non si può. */
    private suspend fun rinnova(): String? {
        val auth = Supabase.client().auth
        if (auth.currentSessionOrNull() == null) return null
        try {
            auth.refreshCurrentSession()
        } catch (e: Exception) {
            Log.w(TAG, "rinnovo della sessione fallito: ${e.message}")
            return null
        }
        return auth.currentSessionOrNull()?.accessToken
    }

    private fun apri(url: String, metodo: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo
            connectTimeout = 30_000
            readTimeout = 120_000
            doInput = true
        }

    private fun corpoDi(azione: String, dentro: JsonObjectBuilder.() -> Unit): ByteArray =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("azione", azione); dentro() }
        ).toByteArray(Charsets.UTF_8)

    private fun spedisci(corpo: ByteArray, token: String): HttpURLConnection {
        val c = apri(indirizzo(), "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer " + token)
        c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
        c.outputStream.use { it.write(corpo) }
        return c
    }

    /**
     * La POST alla funzione, con **un solo** nuovo tentativo sul 401.
     *
     * ⚠️ Il 401 arriva da due posti diversi e vale la stessa cosa: la funzione
     * quando `chiChiama()` non riconosce l'utente, e il **cancello** di Supabase
     * quando rifiuta il JWT prima ancora che la funzione parta
     * (`UNAUTHORIZED_ASYMMETRIC_JWT`). In tutt'e due i casi il token non vale
     * più — e il secondo capita anche con un `exp` che a noi sembra buonissimo,
     * per esempio dopo un cambio delle chiavi di firma del progetto: `token()`
     * da solo non se ne accorgerebbe mai. Un rinnovo dà un token firmato con la
     * chiave di adesso, quindi si riprova.
     *
     * ⚠️ **Una volta e non in ciclo**: se il secondo giro torna 401 il problema
     * non è il token, e riprovare vorrebbe dire tenere il Forziere fermo su una
     * rotella invece di dire cos'è successo. Il corpo si rimanda tale e quale —
     * è un `ByteArray` che abbiamo già in mano — e la prima connessione si
     * chiude prima di aprire la seconda.
     */
    private suspend fun post(corpo: ByteArray): HttpURLConnection {
        val primo = spedisci(corpo, token())
        if (primo.responseCode != 401) return primo
        primo.disconnect()
        Log.w(TAG, "401 dalla funzione: rinnovo la sessione e riprovo una volta")
        val fresco = rinnova() ?: throw IllegalStateException(SESSIONE_SCADUTA)
        val secondo = spedisci(corpo, fresco)
        if (secondo.responseCode == 401) {
            secondo.disconnect()
            throw IllegalStateException(SESSIONE_SCADUTA)
        }
        return secondo
    }

    /** Una chiamata che risponde JSON. Solleva col messaggio della funzione. */
    suspend fun chiama(azione: String, dentro: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        withContext(Dispatchers.IO) {
            val c = post(corpoDi(azione, dentro))
            val codice = c.responseCode
            val risposta = (if (codice in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            val d = runCatching { json.parseToJsonElement(risposta) as JsonObject }.getOrNull()
                ?: throw IllegalStateException("Drive: risposta illeggibile (HTTP $codice)")
            if ((d["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                throw IllegalStateException(testo(d, "error") ?: "Drive: HTTP $codice")
            }
            d
        }

    /**
     * Scarica un `.gpg` su [dest] **in flusso**: Drive non ha indirizzi firmati,
     * quindi i byte passano dalla funzione, ma non ci si fermano dentro — e su
     * un telefono non stanno nemmeno tutti in memoria qui.
     */
    suspend fun scarica(id: String, dest: OutputStream) = withContext(Dispatchers.IO) {
        val c = post(corpoDi("get") { put("id", id) })
        if (c.responseCode !in 200..299) {
            val e = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            throw IllegalStateException(motivo(e) ?: "Drive: " + e.take(200))
        }
        c.inputStream.use { it.copyTo(dest, 1 shl 16) }
        c.disconnect()
    }

    /**
     * ⚠️ **Il prefisso «Drive: » è nostro, non di Google**, e appiccicato a un
     * errore che il Drive non ha mai visto manda a cercare il guasto dalla parte
     * sbagliata — è successo il 10 settembre 2026. Quando la risposta porta il
     * suo `error`, si scrive quello e basta.
     */
    private fun motivo(risposta: String): String? =
        runCatching { testo(json.parseToJsonElement(risposta) as JsonObject, "error") }.getOrNull()

    /** Comodità: un oggetto piccolo (gli indici, `indice.gpg`) tutto in memoria. */
    suspend fun scaricaBytes(id: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        scarica(id, out)
        return out.toByteArray()
    }

    /**
     * Carica un file già cifrato. Prova la strada diretta — i byte vanno dal
     * telefono a Google senza passare dalla funzione — e se non passa ripiega
     * su `put`, per i file abbastanza piccoli.
     */
    suspend fun carica(
        nome: String,
        file: File,
        cartella: String,
        onProgresso: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        try {
            caricaDiretto(nome, file, cartella, onProgresso)
        } catch (e: Exception) {
            if (file.length() > RIPIEGO_MAX) throw e
            onProgresso(0.5f)
            val r = put(nome, file.readBytes(), cartella)
            onProgresso(1f)
            r
        }
    }

    private suspend fun caricaDiretto(
        nome: String,
        file: File,
        cartella: String,
        onProgresso: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val d = chiama("upload-url") {
            put("nome", nome); put("bytes", file.length()); put("cartella", cartella)
        }
        val url = testo(d, "url") ?: throw IllegalStateException("Google non ha dato l'indirizzo")
        val c = apri(url, "PUT")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(file.length())
        c.outputStream.use { out ->
            file.inputStream().use { ins -> copiaConAvanzamento(ins, out, file.length(), onProgresso) }
        }
        val codice = c.responseCode
        val risposta = (if (codice in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (codice !in 200..299) throw IllegalStateException("Google ha rifiutato il caricamento (HTTP $codice)")
        testo(json.parseToJsonElement(risposta) as JsonObject, "id")
            ?: throw IllegalStateException("Google non ha detto l'id del file")
    }

    /**
     * `put`: i byte passano dalla funzione. Lo usano gli **indici** — poche
     * centinaia di byte, per cui un caricamento ripristinabile sarebbe tre
     * viaggi di rete per niente — e fa da ripiego per i documenti piccoli.
     *
     * ⚠️ `perNome` lo chiedono **solo gli indici**: là il file è «il contenuto di
     * questa cartella», ce n'è uno solo per definizione e il suo id può essersi
     * perso col database. Sui documenti, dove il nome è un uuid, sarebbe solo un
     * modo in più di sovrascriverne uno per sbaglio.
     */
    suspend fun put(
        nome: String,
        bytes: ByteArray,
        cartella: String = "",
        perNome: Boolean = false,
        id: String? = null,
    ): String {
        val d = chiama("put") {
            put("nome", nome)
            put("base64", ForziereCripto.b64(bytes))
            put("cartella", cartella)
            if (perNome) put("perNome", true)
            if (!id.isNullOrBlank()) put("id", id)
        }
        return testo(d, "id") ?: throw IllegalStateException("Drive non ha detto l'id")
    }

    /**
     * Butta via un oggetto su Drive.
     *
     * ⚠️ **Prima questo, poi la riga** (vedi `ForziereViewModel.cancella`): nel
     * verso opposto una rete che cade lascia su Drive un `.gpg` che nessuno sa
     * più cos'è e che dall'app non si può più togliere. È lo stesso ordine di
     * `elimina()` in `forziere.html` e delle foto di Memo.
     */
    suspend fun elimina(driveFileId: String) {
        chiama("delete") { put("id", driveFileId) }
    }

    suspend fun mkdir(nome: String): String =
        testo(chiama("mkdir") { put("nome", nome) }, "id")
            ?: throw IllegalStateException("Drive non ha detto l'id della cartella")

    private fun copiaConAvanzamento(
        ins: InputStream,
        out: OutputStream,
        quanti: Long,
        onProgresso: (Float) -> Unit,
    ) {
        val buf = ByteArray(1 shl 16)
        var fatti = 0L
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            fatti += n
            if (quanti > 0) onProgresso((fatti.toDouble() / quanti).toFloat().coerceIn(0f, 1f))
        }
    }
}
