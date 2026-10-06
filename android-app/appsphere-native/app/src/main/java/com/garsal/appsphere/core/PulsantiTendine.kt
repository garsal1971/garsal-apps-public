package com.garsal.appsphere.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Una riga che **non va mai a capo**: quello che non entra si raggiunge
 * scorrendo col dito verso sinistra.
 *
 * È la scelta opposta al solito consiglio sui caratteri di sistema grandi, ed
 * è voluta: andare a capo tiene tutto leggibile ma con l'ingrandimento alto
 * allunga la scheda che la contiene finché non ce ne sta più una sola per
 * schermo. Una riga sola resta compatta e non nasconde niente — il testo
 * tagliato dal bordo non è perso, basta trascinarlo — a patto che quello che
 * conta di più stia **a sinistra**. `Arrangement` decide l'ordine, non lo
 * scorrimento.
 *
 * Nata in `tasks/Panoramica.kt` (dove restano i dettagli sulle schede task) e
 * `tasks/Gestione.kt` come due copie identiche (`RigaScorrevole` e
 * `RigaOrizzontale`); qui è una sola, per ogni riga di pulsanti dell'app —
 * non solo i task.
 */
@Composable
fun RigaScorrevole(
    disposizione: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
    contenuto: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = disposizione,
        verticalAlignment = Alignment.CenterVertically,
        content = contenuto,
    )
}

/**
 * La larghezza comune di un gruppo di pulsanti: quella che serve all'etichetta
 * più lunga fra `testi`, misurata **con lo stile e l'ingrandimento di
 * adesso** — mai una costante in `dp`, che coi caratteri di sistema grandi
 * taglierebbe la parola più lunga o lascerebbe le altre troppo larghe.
 *
 * `testi` è la lista completa delle etichette che *potrebbero* comparire in
 * quella riga (non solo quelle mostrate in un dato momento): un pulsante che
 * appare solo a volte non deve cambiare la larghezza di quelli accanto.
 */
@Composable
fun larghezzaPulsanti(testi: List<String>): Dp {
    val misuratore = rememberTextMeasurer()
    val stile = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val piuLarga = testi.maxOf { misuratore.measure(it, stile).size.width }
    return with(LocalDensity.current) { piuLarga.toDp() } + 28.dp
}

/**
 * Il pulsante "pillola" usato in ogni riga di azioni dell'app. Senza
 * `larghezza` si allarga solo quanto serve al suo testo (va bene per un
 * pulsante da solo); con `larghezza` — di norma quella di [larghezzaPulsanti]
 * — tutti i pulsanti della stessa riga risultano larghi uguale.
 */
@Composable
fun Pillola(testo: String, sfondo: Color, larghezza: Dp? = null, onClick: () -> Unit) {
    Text(
        text = testo,
        color = Palette.light,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .then(larghezza?.let { Modifier.width(it) } ?: Modifier)
            .clip(RoundedCornerShape(4.dp))
            .background(sfondo)
            .clickable(onClick = onClick)
            .padding(horizontal = if (larghezza == null) 16.dp else 0.dp, vertical = 10.dp),
    )
}

/**
 * Una tendina. Non `ExposedDropdownMenuBox`: quello vuole l'opt-in su un'API
 * sperimentale e in cambio dà un campo di testo che qui non serve, perché non
 * si scrive niente — si sceglie da un elenco chiuso.
 */
@Composable
fun Tendina(
    etichetta: String,
    scelto: String,
    voci: List<Pair<String, String>>,
    abilitata: Boolean = true,
    onScegli: (String) -> Unit,
) {
    var aperta by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            text = etichetta.uppercase(),
            color = Palette.muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (abilitata) Palette.inputBg else Palette.inputBg.copy(alpha = 0.6f))
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                    .clickable(enabled = abilitata) { aperta = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(scelto, color = Palette.dark, modifier = Modifier.weight(1f))
                Text("⌄", color = Palette.muted)
            }
            DropdownMenu(expanded = aperta, onDismissRequest = { aperta = false }) {
                voci.forEach { (valore, testo) ->
                    DropdownMenuItem(
                        text = { Text(testo) },
                        onClick = {
                            aperta = false
                            onScegli(valore)
                        },
                    )
                }
            }
        }
    }
}

private const val TENDINA_TUTTE = "__tutte__"

/** Una tendina che ha in cima un «tutte»: la scelta può non esserci. */
@Composable
fun TendinaFacoltativa(
    etichetta: String,
    tutte: String,
    scelto: String?,
    voci: List<Pair<String, String>>,
    onScegli: (String?) -> Unit,
) {
    Tendina(
        etichetta = etichetta,
        scelto = voci.firstOrNull { it.first == scelto }?.second ?: tutte,
        voci = listOf(TENDINA_TUTTE to tutte) + voci,
    ) { scelta -> onScegli(scelta.takeIf { it != TENDINA_TUTTE }) }
}
