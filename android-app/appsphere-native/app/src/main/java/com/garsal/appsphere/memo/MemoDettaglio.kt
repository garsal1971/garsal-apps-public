package com.garsal.appsphere.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.coloreDaHex

/**
 * Una scheda aperta in lettura: il testo **come l'ha scritto il web** — cioè
 * l'HTML reso a schermo, non i marcatori dell'editor — le categorie, le foto e
 * le tre azioni (modifica, fissa, elimina).
 *
 * Sul web toccare una scheda apre direttamente il modale di modifica. Qui no,
 * ed è voluto: da telefono una scheda si apre per leggerla, e mostrare i
 * marcatori a chi voleva solo controllare un indirizzo sarebbe rumore.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoDettaglio(
    scheda: MmScheda,
    immagini: List<MmImmagine>,
    categorie: List<CmCategoria>,
    onIndietro: () -> Unit,
    onModifica: () -> Unit,
    onFissa: () -> Unit,
    onElimina: () -> Unit,
    onChiediImmagini: () -> Unit,
) {
    var daEliminare by remember { mutableStateOf(false) }
    var ingrandita by remember { mutableStateOf<MmImmagine?>(null) }

    LaunchedEffect(scheda.id, scheda.immagini) {
        if (scheda.immagini > 0 && immagini.isEmpty()) onChiediImmagini()
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = scheda.titolo.ifBlank { "Senza titolo" },
                onIndietro = onIndietro,
                azioni = {
                    Text(
                        text = if (scheda.fissata) "📌" else "📍",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onFissa)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        text = "✏️",
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onModifica)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(coloreDaHex(scheda.colore) ?: Palette.cardBg)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (scheda.categorie.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scheda.categorie.forEach { id ->
                        categorie.firstOrNull { it.id == id }?.let { cat ->
                            Text(
                                text = cat.etichetta,
                                color = Palette.light,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(coloreDaHex(cat.colore) ?: Palette.muted)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // I punti stanno **sopra** il testo: su un premiato sono la cosa
            // per cui la scheda esiste, e sotto un contenuto lungo si
            // troverebbero solo scorrendo. A zero restano scritti, in grigio:
            // una scheda che non muove il totale deve vedersi.
            if (scheda.tipo == TipoScheda.PREMIATO) {
                Text(
                    text = puntiTesto(scheda.punteggio),
                    color = if (scheda.punteggio != 0) Palette.light else Palette.muted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (scheda.punteggio != 0) Palette.warning else Palette.inputBg
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            Text(
                text = testoFormattato(scheda.contenuto),
                color = Palette.dark,
                style = MaterialTheme.typography.bodyLarge,
            )

            if (immagini.isNotEmpty()) {
                Text(
                    text = "📷 Foto",
                    color = Palette.muted,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    immagini.forEach { foto ->
                        AsyncImage(
                            model = foto.url,
                            contentDescription = foto.nome,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Palette.inputBg)
                                .clickable { ingrandita = foto },
                        )
                    }
                }
            } else if (scheda.immagini > 0) {
                Text(
                    text = "Caricamento delle foto…",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = "Ultima modifica: ${scheda.dataItaliana}",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = "🗑 Elimina la scheda",
                color = Palette.light,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.danger)
                    .clickable { daEliminare = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    if (daEliminare) {
        AlertDialog(
            onDismissRequest = { daEliminare = false },
            title = { Text("Eliminare la scheda?") },
            text = {
                Text(
                    "«${scheda.titolo.ifBlank { "Senza titolo" }}» e le sue foto " +
                        "vengono eliminate. Non si torna indietro."
                )
            },
            confirmButton = {
                TextButton(onClick = { daEliminare = false; onElimina() }) {
                    Text("Elimina", color = Palette.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { daEliminare = false }) {
                    Text("Annulla", color = Palette.muted)
                }
            },
        )
    }

    // La foto a tutto schermo: si chiude toccandola, come il visualizzatore del
    // web. Niente zoom — per quello si apre la foto dalla galleria.
    ingrandita?.let { foto ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6111111))
                .clickable { ingrandita = null },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = foto.url,
                contentDescription = foto.nome,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp),
            )
        }
    }
}

/**
 * L'HTML della scheda reso a schermo.
 *
 * `AnnotatedString.fromHtml` copre quello che la barra del web sa scrivere
 * (grassetto, corsivo, sottolineato, barrato, link, elenchi). Se un giorno
 * incontrasse qualcosa che non digerisce si ripiega sul testo nudo: una
 * formattazione persa è meno grave di una scheda che non si apre.
 */
@Composable
private fun testoFormattato(html: String): AnnotatedString =
    remember(html) {
        runCatching { AnnotatedString.fromHtml(html) }
            .getOrElse { AnnotatedString(MemoHtml.aTestoSemplice(html)) }
    }
