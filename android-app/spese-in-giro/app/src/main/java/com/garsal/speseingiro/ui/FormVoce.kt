package com.garsal.speseingiro.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.garsal.speseingiro.Categoria
import com.garsal.speseingiro.Foto
import com.garsal.speseingiro.Ocr
import com.garsal.speseingiro.Persona
import com.garsal.speseingiro.StatoViaggio
import com.garsal.speseingiro.Voce
import com.garsal.speseingiro.categoriaDi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Il giorno di oggi in ISO, letto in ora **locale**: `toISOString()` di un
 *  fuso più a est sposta la spesa delle undici di sera al giorno dopo. */
fun oggiIso(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(java.util.Date())

/** ⚠️ Il DatePicker restituisce la mezzanotte **UTC** del giorno scelto: letta
 *  col fuso locale diventa il giorno prima a ogni ora negativa. */
private fun isoDaMillis(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ITALY)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(ms))

/** ISO → millis di mezzanotte UTC, che è quello che il DatePicker si aspetta:
 *  passandogli l'ora locale, il giorno preselezionato è quello prima. */
private fun millisDaIso(iso: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd", Locale.ITALY)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(iso)?.time
}.getOrNull()

private fun importoDa(testo: String): Double? =
    testo.trim().replace("€", "").replace(" ", "").replace(",", ".").toDoubleOrNull()

/**
 * Il form di una voce: una spesa o una restituzione.
 *
 * ⚠️ **Chi ha pagato** parte dal proprietario del telefono e **per chi** da
 * «tutti e due»: sono i due casi di gran lunga più frequenti, e ogni tocco
 * risparmiato davanti a una cassa è una spesa che si segna invece di
 * rimandarla. Ma sono **default, non valori fissi**: «l'ha pagata» offre
 * chiunque sia nel viaggio, e «per chi» offre *per tutti e due* più ciascuno
 * per suo conto — una spesa la può aver fatta l'altro, e non tutto quel che si
 * compra è di tutt'e due.
 *
 * ⚠️ **Finché si è da soli le due tendine hanno una voce sola**, e non è un
 * difetto del form: nel viaggio c'è una persona. Lo si dice lì accanto col
 * codice da dettare — una tendina che non si apre, senza una riga che spieghi
 * perché, si legge come una scelta negata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchermataVoce(
    stato: StatoViaggio,
    tipo: String,
    esistente: Voce?,
    inCorso: Boolean,
    onSalva: (importo: Double, data: String, descrizione: String, categoria: String,
              daId: String, perChi: String?, beneficiario: String?, foto: Bitmap?, letto: Double?) -> Unit,
    onIndietro: () -> Unit,
) {
    val ctx = LocalContext.current
    val ambito = rememberCoroutineScope()
    val spesa = tipo == "spesa"
    val persone = listOfNotNull(stato.io, stato.altro)

    var importo by remember { mutableStateOf(esistente?.importo?.let { String.format(Locale.ITALY, "%.2f", it) } ?: "") }
    var data by remember { mutableStateOf(esistente?.data?.take(10) ?: oggiIso()) }
    var descrizione by remember { mutableStateOf(esistente?.descrizione ?: "") }
    // ⚠️ Su una voce che c'è già si riparte dalla **sua** categoria anche se
    // nel frattempo è stata tolta dall'elenco: `categoriaDi` la restituisce
    // com'è scritta, e salvando la correzione resta quella. Prendendo la prima
    // dell'elenco, correggere l'importo cambierebbe di nascosto la categoria.
    var categoria by remember {
        mutableStateOf(
            if (esistente != null) categoriaDi(esistente.categoria, stato.categorie)
            else stato.categorie.firstOrNull { it.chiave == "cibo" }
                ?: stato.categorie.firstOrNull()
                ?: categoriaDi("varie", stato.categorie)
        )
    }
    var da by remember { mutableStateOf(persone.firstOrNull { it.id == esistente?.daId } ?: stato.io) }
    var perTuttiEDue by remember { mutableStateOf(esistente?.perChi?.let { it == "entrambi" } ?: true) }
    var beneficiario by remember {
        mutableStateOf(persone.firstOrNull { it.id == esistente?.beneficiarioId } ?: stato.io)
    }

    var foto by remember { mutableStateOf<Bitmap?>(null) }
    var letto by remember { mutableStateOf<Double?>(esistente?.scontrinoLetto) }
    var leggendo by remember { mutableStateOf(false) }
    var calendario by remember { mutableStateOf(false) }
    var fileScatto by remember { mutableStateOf<java.io.File?>(null) }

    // Dopo la foto: si riduce, si legge, e l'importo si **propone**. Non si
    // sovrascrive mai una cifra già scritta a mano — un ripiego che prende il
    // posto del dato esatto è un peggioramento silenzioso.
    fun elabora(uri: android.net.Uri?) {
        if (uri == null) return
        ambito.launch {
            leggendo = true
            val b = Foto.ridotta(ctx, uri)
            foto = b
            if (b != null) {
                val t = Ocr.totale(Ocr.leggi(b))
                letto = t
                if (t != null && importo.isBlank()) importo = String.format(Locale.ITALY, "%.2f", t)
            }
            leggendo = false
        }
    }

    val scatta = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) fileScatto?.let { elabora(Foto.uri(ctx, it)) }
    }
    val scegli = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> elabora(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (spesa) "Una spesa" else "Una restituzione") },
                navigationIcon = { TextButton(onClick = onIndietro) { Text("‹ Indietro") } },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = importo, onValueChange = { importo = it },
                label = { Text("Quanto") },
                suffix = { Text("€") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = { calendario = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("📅 ${dataIt(data)}") }

            OutlinedTextField(
                value = descrizione, onValueChange = { descrizione = it },
                label = { Text(if (spesa) "Cos'era" else "Nota") },
                placeholder = { Text(if (spesa) "Pranzo alla trattoria" else "contanti al bar") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            if (spesa) {
                // Una categoria non più in elenco resta fra le opzioni finché
                // quella voce la porta: un valore che la tendina non contiene
                // si legge come una scelta sparita.
                val opzioniCat = stato.categorie.let { elenco ->
                    if (elenco.any { it.chiave == categoria.chiave }) elenco else elenco + categoria
                }
                Tendina(
                    etichetta = "Categoria", valore = categoria, opzioni = opzioniCat,
                    testo = { c: Categoria -> c.etichetta },
                    modifier = Modifier.fillMaxWidth(),
                ) { categoria = it }
            }

            Tendina(
                etichetta = if (spesa) "L'ha pagata" else "Restituisce",
                valore = da, opzioni = persone,
                testo = { p: Persona -> p.nome + if (p.id == stato.io.id) " (tu)" else "" },
                modifier = Modifier.fillMaxWidth(),
            ) { da = it }

            if (spesa) {
                Tendina(
                    etichetta = "Per chi",
                    valore = if (perTuttiEDue) "entrambi" else beneficiario.id,
                    opzioni = listOf("entrambi") + persone.map { it.id },
                    testo = { v: String ->
                        if (v == "entrambi") "Per tutti e due"
                        else "Solo per " + (persone.firstOrNull { it.id == v }
                            ?.let { p -> if (p.id == stato.io.id) "te" else p.nome } ?: "?")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { scelto ->
                    perTuttiEDue = scelto == "entrambi"
                    persone.firstOrNull { it.id == scelto }?.let { beneficiario = it }
                }
            } else {
                Text(
                    "Va all'altro: in un viaggio in due «a chi» non è una domanda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (stato.altro == null) {
                Text(
                    "Sei ancora da solo nel viaggio, quindi qui c'è solo il tuo nome: " +
                        "detta il codice ${stato.viaggio.codice} all'altro e le due scelte " +
                        "si aprono da sé.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (spesa) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("🧾 Lo scontrino", fontWeight = FontWeight.SemiBold)
                        RigaScorrevole {
                            val l = larghezzaPulsanti("📷 Scatta", "🖼 Dalla galleria")
                            PulsanteVuoto("📷 Scatta", l) {
                                val f = Foto.nuovoFile(ctx)
                                fileScatto = f
                                scatta.launch(Foto.uri(ctx, f))
                            }
                            PulsanteVuoto("🖼 Dalla galleria", l) {
                                scegli.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        }
                        when {
                            leggendo -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Leggo lo scontrino…", style = MaterialTheme.typography.bodySmall)
                            }
                            foto != null -> {
                                Image(foto!!.asImageBitmap(), contentDescription = "Scontrino",
                                      modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
                                Text(
                                    letto?.let {
                                        "Ci ho letto ${euro(it)}. Controlla: è una lettura, non un dato."
                                    } ?: "Non sono riuscito a leggerci un totale: scrivilo tu.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            esistente?.haScontrino == true ->
                                Text("Ce n'è già uno. Scattandone un altro prende il suo posto.",
                                     style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val v = importoDa(importo) ?: return@Button
                    onSalva(
                        v, data, descrizione.trim(), categoria.chiave, da.id,
                        if (spesa) (if (perTuttiEDue) "entrambi" else "uno") else null,
                        if (spesa && !perTuttiEDue) beneficiario.id else null,
                        foto, letto,
                    )
                },
                enabled = !inCorso && !leggendo && (importoDa(importo) ?: 0.0) > 0.0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) { Text(if (esistente == null) "Segna" else "Salva la correzione") }

            Text(
                if (esistente == null)
                    "Vale quando ${stato.altro?.nome ?: "l'altro"} la conferma. Fino ad allora la puoi correggere."
                else
                    "Finché non è confermata la puoi ancora correggere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (calendario) {
        val statoData = rememberDatePickerState(initialSelectedDateMillis = millisDaIso(data))
        DatePickerDialog(
            onDismissRequest = { calendario = false },
            confirmButton = {
                TextButton(onClick = {
                    statoData.selectedDateMillis?.let { data = isoDaMillis(it) }
                    calendario = false
                }) { Text("Va bene") }
            },
            dismissButton = { TextButton(onClick = { calendario = false }) { Text("Annulla") } },
        ) { DatePicker(state = statoData) }
    }
}
