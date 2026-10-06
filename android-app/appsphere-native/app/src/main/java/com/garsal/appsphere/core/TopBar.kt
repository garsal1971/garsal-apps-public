package com.garsal.appsphere.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La barra blu col logo di AppSphere, uguale al `#garsal-top-bar` che tutte le
 * pagine web hanno in cima.
 *
 * ⚠️ **Due cose che sembrano dettagli e invece tagliavano il titolo.**
 *
 * 1. **Gli insets della status bar.** Con `targetSdk 36` Android disegna
 *    l'app a tutto schermo di sua iniziativa — `android:statusBarColor` nel
 *    tema non lo evita, da API 35 è ignorato — quindi questa barra finiva
 *    *sotto* l'orologio di sistema e il titolo si vedeva mozzato in alto.
 *    Il fondo blu si dipinge **prima** del padding, così il colore arriva fino
 *    in cima dietro la status bar e solo il contenuto scende sotto: la barra si
 *    vede intera com'era pensata, non con una striscia vuota sopra.
 *    `windowInsetsPadding` vale zero quando è il sistema a scostare la
 *    finestra, quindi qui non si somma mai due volte.
 *
 * 2. **L'altezza non è più fissa.** Erano 56 dp esatti, come nel CSS delle
 *    pagine: ma 56 dp è la misura giusta solo con i caratteri di sistema
 *    normali. **Su questo telefono i caratteri sono molto grandi**, e un
 *    titolo da 18 sp diventa più alto della barra che dovrebbe contenerlo — e
 *    quel che avanza viene tagliato. Ora 56 dp sono il *minimo*: se il testo
 *    chiede più spazio la barra cresce. Vale per ogni scritta dell'app, non
 *    solo per questa: **un'altezza fissa attorno a del testo è un ritaglio che
 *    aspetta il momento giusto per succedere.**
 *
 * Anche le icone seguono l'ingrandimento, con un tetto: a caratteri molto
 * grandi una freccia da 28 dp accanto a un titolo alto il doppio sembra un
 * refuso, ma lasciarla crescere senza limite si mangerebbe la riga.
 */
@Composable
fun GarsalTopBar(
    titolo: String,
    modifier: Modifier = Modifier,
    onIndietro: (() -> Unit)? = null,
    azioni: @Composable () -> Unit = {},
) {
    val scala = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.topBar)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onIndietro != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Indietro",
                tint = Palette.light,
                modifier = Modifier
                    .size(28.dp * scala)
                    .clickable(onClick = onIndietro),
            )
        } else {
            // ⚠️ Il marchio è quello dell'icona di lancio — i cinque cerchi —
            // e non più i cerchi olimpici, che qui erano rimasti indietro
            // rispetto al launcher. Il disco bianco serve: la barra è blu e il
            // cerchio centrale del marchio è blu pure lui.
            LogoAppSphere(Modifier.size(34.dp * scala), disco = true)
        }

        Text(
            text = titolo,
            color = Palette.light,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            // Due righe e poi i puntini: il titolo può crescere in altezza, ma
            // non fino a spingere fuori dalla barra quello che c'è a destra —
            // il ⚙️ di Spuntiamola, il ✏️ di Memo, il «Salva» dei form.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { azioni() }
    }
}
