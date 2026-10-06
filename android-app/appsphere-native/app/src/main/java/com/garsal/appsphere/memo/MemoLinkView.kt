package com.garsal.appsphere.memo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette

/** Il fondo scuro su cui sta la copertina, e il riquadro dei link che non ce l'hanno. */
private val FondoCopertina = Color(0xFF111111)

/**
 * Un 🔗 Link aperto: la copertina, l'indirizzo, la nota e il pulsante che lo apre.
 *
 * ⚠️ Gemella della vista link di `memo.html`. Come lì, il tocco sulla scheda
 * **non porta dritto al video**: la nota — «perché lo tieni» — è metà del
 * motivo per cui la scheda esiste, e saltarla la renderebbe illeggibile.
 *
 * ⚠️ Non c'è niente da caricare aprendola: l'url arriva già con l'elenco
 * (`mm_attachments` sta nella query delle schede), a differenza delle voci di
 * una lista e delle registrazioni di un diario.
 */
@Composable
fun MemoLinkView(
    scheda: MmScheda,
    onIndietro: () -> Unit,
    onModifica: () -> Unit,
    onFissa: () -> Unit,
) {
    val context = LocalContext.current
    val url = scheda.linkUrl

    val apri = {
        if (url.isBlank()) Unit
        else try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            // Nessuna app sa aprire quell'indirizzo: non c'è niente da fare, e
            // far cadere l'app sarebbe il modo peggiore di dirlo.
            Unit
        }
    }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = scheda.titolo.ifBlank { "Link senza titolo" },
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Copertina(url, grande = true, onTocca = apri)

            if (url.isNotBlank()) {
                Text(
                    text = url,
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (scheda.anteprima.isNotBlank()) {
                Text(
                    text = runCatching { AnnotatedString.fromHtml(scheda.contenuto) }
                        .getOrElse { AnnotatedString(scheda.anteprima) },
                    color = Palette.dark,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (url.isBlank()) {
                // Un link senza indirizzo è rotto, non vuoto: lo si dice invece
                // di mostrare un pulsante che non porta da nessuna parte.
                Text(
                    text = "Questa scheda non ha un indirizzo.",
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Button(
                    onClick = apri,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BluMemo,
                        contentColor = Palette.light,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("▶️  Apri", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * La copertina di un link: quella di YouTube quando c'è, altrimenti un
 * riquadro col 🔗.
 *
 * ⚠️ L'immagine viene da `img.youtube.com` e non dal bucket: non costa spazio,
 * ma **può non arrivare** (offline, video tolto). In quel caso resta il fondo
 * scuro col segno, che è quello che si vedrebbe senza copertina — non un
 * riquadro rotto.
 */
@Composable
internal fun Copertina(url: String, grande: Boolean, onTocca: (() -> Unit)? = null) {
    if (url.isBlank()) return
    val copertina = Link.copertina(url)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .then(if (grande) Modifier.clip(RoundedCornerShape(10.dp)) else Modifier)
            .background(FondoCopertina)
            .then(onTocca?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (copertina != null) {
            AsyncImage(
                model = copertina,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Text("▶️", fontSize = if (grande) 44.sp else 34.sp)
        } else {
            Text("🔗", fontSize = if (grande) 40.sp else 30.sp)
        }
    }
}
