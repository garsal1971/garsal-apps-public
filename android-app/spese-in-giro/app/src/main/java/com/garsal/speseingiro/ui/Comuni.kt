package com.garsal.speseingiro.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * I due componenti che servono coi caratteri di sistema grandi.
 *
 * ⚠️ Sono ricalcati da `core/PulsantiTendine.kt` di appsphere-native e non
 * importati: sono due progetti Gradle separati, che non condividono sorgenti —
 * è la stessa duplicazione dichiarata di `ForziereBiometria` / `ForziereKeystore`.
 * Se la regola cambia là, va cambiata anche qui.
 */

/** Una riga di pulsanti non va MAI a capo: scorre col dito. Andando a capo,
 *  con l'ingrandimento alto tre pulsanti diventano tre righe e in uno schermo
 *  ci sta una voce e mezza. Niente è nascosto — quel che è tagliato dal bordo
 *  si trascina — **a patto che quel che conta di più stia a sinistra**. */
@Composable
fun RigaScorrevole(
    modifier: Modifier = Modifier,
    spazio: Dp = 8.dp,
    contenuto: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spazio),
    ) { contenuto() }
}

/**
 * La larghezza comune dei pulsanti di una riga: si **misura** sullo stile e
 * sull'ingrandimento correnti, mai una costante in `dp` — che o taglia
 * l'etichetta più lunga, o lascia le altre in un pulsante largo il doppio.
 *
 * ⚠️ Vanno passate **tutte** le etichette che possono comparire in quella riga,
 * comprese quelle che in questo momento non si vedono: altrimenti un pulsante
 * condizionale fa traballare la larghezza degli altri quando compare.
 */
@Composable
fun larghezzaPulsanti(vararg etichette: String, stile: TextStyle = MaterialTheme.typography.labelLarge): Dp {
    val misuratore = rememberTextMeasurer()
    val densita = LocalDensity.current
    val px = etichette.maxOfOrNull { misuratore.measure(AnnotatedString(it), stile).size.width } ?: 0
    return with(densita) { px.toDp() } + 40.dp
}

/**
 * Una scelta fra poche opzioni fisse è **sempre** una tendina, mai una fila di
 * pillole che va a capo o si accorcia.
 *
 * ⚠️ **Passa da `ExposedDropdownMenuBox`, e non è un dettaglio di stile.** Fino
 * alla v1.0.1 era un `OutlinedTextField(enabled = false)` con un `clickable`
 * appeso al modifier: due difetti che si sommavano — il campo **non portava
 * nessuna freccia**, quindi non si leggeva affatto come una cosa da toccare (e
 * «chi ha pagato» sembrava un dato scritto, non una scelta), e l'apertura
 * dipendeva da un clic su un campo disabilitato, che è il modo più fragile di
 * chiedere un tocco. Qui l'ancora è `menuAnchor`, cioè il gesto che Material 3
 * garantisce, e la ▾ dice da sé che c'è dell'altro sotto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Tendina(
    etichetta: String,
    valore: T,
    opzioni: List<T>,
    testo: (T) -> String,
    modifier: Modifier = Modifier,
    abilitata: Boolean = true,
    onScelta: (T) -> Unit,
) {
    var aperta by remember { mutableStateOf(false) }
    val apribile = abilitata && opzioni.size > 1
    ExposedDropdownMenuBox(
        expanded = aperta && apribile,
        onExpandedChange = { if (apribile) aperta = !aperta },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = testo(valore),
            onValueChange = {},
            readOnly = true,
            enabled = abilitata,
            label = { Text(etichetta) },
            // ⚠️ La freccia c'è **solo se c'è davvero qualcosa da scegliere**: con
            // una voce sola sarebbe un invito a un menù che si apre su sé stesso.
            trailingIcon = {
                if (apribile) ExposedDropdownMenuDefaults.TrailingIcon(expanded = aperta)
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = apribile)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = aperta && apribile, onDismissRequest = { aperta = false }) {
            opzioni.forEach { o ->
                DropdownMenuItem(
                    text = { Text(testo(o)) },
                    onClick = { onScelta(o); aperta = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Un'etichetta di stato: colore + parola, mai il colore da solo. */
@Composable
fun Etichetta(testo: String, fondo: Color, davanti: Color, modifier: Modifier = Modifier) {
    Surface(color = fondo, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            testo,
            color = davanti,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Il pulsante pieno di una riga: altezza **minima** e non fissa, o al primo
 *  ingrandimento dei caratteri l'etichetta esce dal contenitore. */
@Composable
fun PulsantePieno(testo: String, larghezza: Dp?, abilitato: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = abilitato,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.then(if (larghezza != null) Modifier.width(larghezza) else Modifier).heightIn(min = 44.dp),
    ) { Text(testo, textAlign = TextAlign.Center, maxLines = 2) }
}

@Composable
fun PulsanteVuoto(
    testo: String,
    larghezza: Dp?,
    colore: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colore),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.then(if (larghezza != null) Modifier.width(larghezza) else Modifier).heightIn(min = 44.dp),
    ) { Text(testo, textAlign = TextAlign.Center, maxLines = 2) }
}
