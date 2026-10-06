package com.garsal.appsphere.premi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.GarsalTopBar
import com.garsal.appsphere.core.Palette
import com.garsal.appsphere.core.Tendina

/**
 * Il premio che si sta scrivendo. Costo e punti per uso restano testo finché
 * non si salva: un campo numerico che si azzera da sé mentre lo si svuota per
 * riscriverlo è il modo più veloce per far sbagliare la cifra.
 */
data class BozzaPremio(
    val nome: String = "",
    val tipo: String = Premio.UNA_TANTUM,
    val costo: String = "",
    val puntiPerUso: String = "",
) {
    val ripetibile: Boolean get() = tipo == Premio.RIPETIBILE

    val costoValido: Int? get() = costo.trim().toIntOrNull()?.takeIf { it >= 1 }

    /**
     * Solo i ripetibili lo usano: su un «una tantum» resta nullo.
     *
     * ⚠️ Lo zero è ammesso, ed è la differenza fra «non rincara» e «non l'ho
     * scritto»: un premio ripetibile che costa sempre uguale è una cosa che si
     * vuole davvero, mentre il campo lasciato in bianco resta non valido
     * (toIntOrNull su stringa vuota torna null). Stessa regola in `saveReward()`.
     */
    val puntiPerUsoValidi: Int? get() =
        if (ripetibile) puntiPerUso.trim().toIntOrNull()?.takeIf { it >= 0 } else null

    /** Le stesse tre condizioni di `saveReward()` nel web. */
    val valida: Boolean get() = nome.isNotBlank() &&
        costoValido != null &&
        (!ripetibile || puntiPerUsoValidi != null)

    companion object {
        fun da(premio: Premio) = BozzaPremio(
            nome = premio.nome,
            tipo = premio.tipo,
            costo = premio.costo.toString(),
            puntiPerUso = premio.puntiPerUso?.toString().orEmpty(),
        )
    }
}

/** Creazione e modifica di un premio — il modale «Nuovo Premio» di `index.html`. */
@Composable
fun PremiForm(
    bozzaIniziale: BozzaPremio,
    id: String?,
    onAnnulla: () -> Unit,
    onSalva: (BozzaPremio) -> Unit,
) {
    var b by remember { mutableStateOf(bozzaIniziale) }

    Scaffold(
        topBar = {
            GarsalTopBar(
                titolo = if (id == null) "🎁 Nuovo premio" else "✏️ Modifica premio",
                onIndietro = onAnnulla,
                azioni = {
                    Text(
                        text = "Salva",
                        color = if (b.valida) Palette.light else Palette.light.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = b.valida) { onSalva(b) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = b.nome,
                onValueChange = { b = b.copy(nome = it) },
                label = { Text("Nome del premio") },
                placeholder = { Text("es. Film al cinema") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Tendina(
                etichetta = "Tipo",
                scelto = if (b.ripetibile) "🔄 Ripetibile" else "⭐ Una tantum",
                voci = listOf(
                    Premio.UNA_TANTUM to "⭐ Una tantum",
                    Premio.RIPETIBILE to "🔄 Ripetibile",
                ),
            ) { scelta -> b = b.copy(tipo = scelta) }
            Text(
                text = if (b.ripetibile)
                    "Resta nel catalogo e rincara di quanto scrivi qui sotto."
                else
                    "Si ritira una volta sola, poi esce dal catalogo.",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = b.costo,
                onValueChange = { b = b.copy(costo = it.filter(Char::isDigit)) },
                label = { Text("Punti necessari") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (b.ripetibile) {
                OutlinedTextField(
                    value = b.puntiPerUso,
                    onValueChange = { b = b.copy(puntiPerUso = it.filter(Char::isDigit)) },
                    label = { Text("Punti in più per ogni utilizzo") },
                    supportingText = { Text("0 = costa sempre uguale") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
