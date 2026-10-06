package com.garsal.speseingiro.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garsal.speseingiro.Categoria
import com.garsal.speseingiro.DialogoAggiornamento
import com.garsal.speseingiro.Prefs
import com.garsal.speseingiro.Rilascio
import com.garsal.speseingiro.UiState

/**
 * Impostazioni: il codice da dettare all'altro, le categorie di spesa, i viaggi
 * di questo telefono, la versione dell'app e l'uscita.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchermataImpostazioni(
    ui: UiState,
    onScegli: (Prefs.Viaggio) -> Unit,
    onNuovoViaggio: () -> Unit,
    onSalvaCategoria: (id: String?, emoji: String, nome: String) -> Unit,
    onEliminaCategoria: (String) -> Unit,
    onEsci: (String) -> Unit,
    onIndietro: () -> Unit,
) {
    val ctx = LocalContext.current
    var aggiornamento by remember { mutableStateOf(false) }
    var confermaUscita by remember { mutableStateOf(false) }
    // `null` = nessun form aperto; una Categoria col nome vuoto = ne sto
    // aggiungendo una. Uno stato solo per le due strade, che sono lo stesso form.
    var inModifica by remember { mutableStateOf<Categoria?>(null) }
    var daTogliere by remember { mutableStateOf<Categoria?>(null) }
    val attivo = ui.attivo
    val versione = remember { Rilascio.installata(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙️ Impostazioni") },
                navigationIcon = { TextButton(onClick = onIndietro) { Text("‹ Indietro") } },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            attivo?.let { v ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔑 Il codice del viaggio", fontWeight = FontWeight.SemiBold)
                        Text(v.codice, style = MaterialTheme.typography.headlineSmall,
                             fontWeight = FontWeight.Bold)
                        Text(
                            "Dettalo all'altro: gli serve per entrare, e a te per rientrare se " +
                                "cambi telefono. Chi ce l'ha entra — trattalo come si tratta il conto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Entra nel viaggio «${v.nome}» su Spese in giro col codice ${v.codice}"
                                    )
                                }
                                ctx.startActivity(Intent.createChooser(i, "Manda il codice"))
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("📤 Manda il codice") }
                    }
                }
            }

            ui.stato?.let { st ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("🏷️ Le categorie di spesa",
                             fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Text(
                            "Sono del viaggio, non di questo telefono: quel che cambi qui lo " +
                                "vede anche ${st.altro?.nome ?: "chi entrerà"}. Cambiare il nome " +
                                "a una categoria non tocca le voci già segnate — si rileggono " +
                                "col nome nuovo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        st.categorie.forEach { c ->
                            // ⚠️ ✏️ e 🗑 stanno a SINISTRA, ✏️ per prima: in coda
                            // finirebbero oltre il bordo destro appena il nome è
                            // lungo o i caratteri di sistema sono grandi.
                            // Su una categoria senza riga (il ripiego di
                            // `CATEGORIE_DI_PARTENZA`) non c'è niente da toccare.
                            ListItem(
                                leadingContent = {
                                    if (c.gestibile) Row {
                                        IconButton(onClick = { inModifica = c }) { Text("✏️") }
                                        IconButton(onClick = { daTogliere = c }) { Text("🗑") }
                                    }
                                },
                                headlineContent = { Text(c.etichetta) },
                                supportingContent = {
                                    Text(
                                        when (c.usi) {
                                            0 -> "non la usa nessuna voce"
                                            1 -> "usata in 1 voce"
                                            else -> "usata in ${c.usi} voci"
                                        }
                                    )
                                },
                            )
                        }
                        TextButton(
                            onClick = { inModifica = Categoria("", "", "🏷️", "") },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text("➕ Aggiungi una categoria") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("🚲 I viaggi di questo telefono",
                         fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    ui.viaggi.forEach { v ->
                        ListItem(
                            headlineContent = { Text(v.nome) },
                            supportingContent = { Text("codice ${v.codice} · tu sei ${v.io}") },
                            trailingContent = { if (v.token == attivo?.token) Text("✓") },
                            modifier = Modifier.clickable { onScegli(v) },
                        )
                    }
                    TextButton(
                        onClick = onNuovoViaggio,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("➕ Apri o entra in un altro viaggio") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("📱 Versione app") },
                    supportingContent = { Text("v${versione.first} (build ${versione.second}) · qui si scarica la nuova") },
                    modifier = Modifier.clickable { aggiornamento = true },
                )
            }

            attivo?.let { v ->
                OutlinedButton(
                    onClick = { confermaUscita = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Esci da «${v.nome}» su questo telefono") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    inModifica?.let { c ->
        DialogoCategoria(
            categoria = c,
            onAnnulla = { inModifica = null },
            onSalva = { emoji, nome ->
                inModifica = null
                onSalvaCategoria(c.id.ifBlank { null }, emoji, nome)
            },
        )
    }

    daTogliere?.let { c ->
        AlertDialog(
            onDismissRequest = { daTogliere = null },
            title = { Text(if (c.usi > 0) "Non si può togliere" else "Togliere «${c.nome}»?") },
            // ⚠️ Con delle voci che la citano si dice il perché e non si offre
            // il pulsante: chi decide resta il server — il conteggio qui è
            // quello dell'ultima lettura — ma un 🗑 che risponde «non si può»
            // solo dopo essere stato premuto è un pulsante che prende in giro.
            text = {
                Text(
                    if (c.usi > 0)
                        "«${c.nome}» è usata in ${c.usi} " +
                            (if (c.usi == 1) "voce" else "voci") +
                            ": cambia categoria a quelle e poi si toglie. Le voci non si toccano."
                    else
                        "Non la usa nessuna voce, quindi non cambia niente di quel che è " +
                            "già segnato. Se ti serve di nuovo, la riaggiungi."
                )
            },
            confirmButton = {
                if (c.usi > 0) {
                    TextButton(onClick = { daTogliere = null }) { Text("Va bene") }
                } else {
                    TextButton(onClick = {
                        daTogliere = null
                        onEliminaCategoria(c.id)
                    }) { Text("Togli") }
                }
            },
            dismissButton = {
                if (c.usi == 0) TextButton(onClick = { daTogliere = null }) { Text("Annulla") }
            },
        )
    }

    if (aggiornamento) DialogoAggiornamento { aggiornamento = false }

    if (confermaUscita && attivo != null) {
        AlertDialog(
            onDismissRequest = { confermaUscita = false },
            title = { Text("Uscire dal viaggio?") },
            // ⚠️ La finestra dice **cosa non succede**: davanti a un viaggio
            // intero di spese, «esci» senza spiegazioni si legge come «cancella».
            text = {
                Text(
                    "Il viaggio resta dov'è: non si cancella niente, e l'altro continua a " +
                        "vedere tutto. Sparisce da questo telefono, e col codice ${attivo.codice} " +
                        "ci rientri quando vuoi."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confermaUscita = false
                    onEsci(attivo.viaggioId)
                }) { Text("Esci") }
            },
            dismissButton = { TextButton(onClick = { confermaUscita = false }) { Text("Resto") } },
        )
    }
}

/**
 * Il form di una categoria: **emoji e nome, e nient'altro**.
 *
 * ⚠️ La chiave con cui la categoria sta scritta nelle voci non compare: non si
 * può cambiare — è il perno per cui rinominare non riscrive lo storico — e un
 * campo che non si può cambiare è un campo che non si mette.
 */
@Composable
private fun DialogoCategoria(
    categoria: Categoria,
    onAnnulla: () -> Unit,
    onSalva: (emoji: String, nome: String) -> Unit,
) {
    val nuova = !categoria.gestibile
    // ⚠️ La chiave del `remember` è la riga: senza, aprendo un'altra categoria
    // mentre questo form è già a schermo resterebbero i campi di prima.
    var emoji by remember(categoria.id) { mutableStateOf(categoria.emoji.ifBlank { "🏷️" }) }
    var nome by remember(categoria.id) { mutableStateOf(categoria.nome) }

    AlertDialog(
        onDismissRequest = onAnnulla,
        title = { Text(if (nuova) "Una categoria nuova" else "Cambia «${categoria.nome}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ⚠️ I due campi sono uno sotto l'altro e non affiancati: un
                // campo di testo largo quanto un'emoji ha una larghezza fissa,
                // e coi caratteri di sistema grandi la sua etichetta ci finisce
                // tagliata. Una riga sola è un'ipotesi, non un dato.
                OutlinedTextField(
                    value = emoji, onValueChange = { emoji = it },
                    label = { Text("Il segno") },
                    placeholder = { Text("🛣️") },
                    supportingText = { Text("Un'emoji sola: sta accanto al nome, in elenco.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nome, onValueChange = { nome = it },
                    label = { Text("Come si chiama") },
                    placeholder = { Text("Pedaggi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!nuova) {
                    Text(
                        "Le voci già segnate con questa categoria restano dove sono e si " +
                            "rileggono col nome nuovo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSalva(emoji.trim(), nome.trim()) },
                enabled = nome.isNotBlank(),
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onAnnulla) { Text("Annulla") } },
    )
}
