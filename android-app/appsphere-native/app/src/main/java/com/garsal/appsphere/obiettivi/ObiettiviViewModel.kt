package com.garsal.appsphere.obiettivi

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ObiettiviState(
    val obiettivi: List<ObObiettivo> = emptyList(),
    val metriche: List<ObMetrica> = emptyList(),
    val milestone: List<ObMilestone> = emptyList(),
    val ultimeRilevazioni: Map<String, ObRilevazione> = emptyMap(),
    val avanzamenti: Map<String, ObAvanzamento> = emptyMap(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val messaggio: String? = null,
) {
    /** Solo i due livelli previsti: annuali in testa, trimestrali sotto al padre. */
    val annuali: List<ObObiettivo> get() = obiettivi.filter { it.parentId == null }

    fun figliDi(id: String): List<ObObiettivo> = obiettivi.filter { it.parentId == id }

    fun metricheDi(id: String): List<ObMetrica> = metriche.filter { it.objectiveId == id }

    fun milestoneDi(id: String): List<ObMilestone> = milestone.filter { it.objectiveId == id }
}

class ObiettiviViewModel : ViewModel() {

    private val _state = MutableStateFlow(ObiettiviState())
    val state: StateFlow<ObiettiviState> = _state.asStateFlow()

    init { carica() }

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val obiettivi = ObiettiviRepository.obiettivi()
                _state.value = _state.value.copy(
                    obiettivi = obiettivi,
                    metriche = ObiettiviRepository.metriche(),
                    milestone = ObiettiviRepository.milestone(),
                    ultimeRilevazioni = ObiettiviRepository.ultimeRilevazioni(),
                    avanzamenti = ObiettiviRepository.avanzamenti(obiettivi.map { it.id }),
                    caricamento = false,
                )
            } catch (e: Exception) {
                Log.w(TAG, "caricamento fallito", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    /**
     * Registra una rilevazione e ricarica gli avanzamenti.
     *
     * Il valore è uno solo — il voto o il numero misurato — e la validità
     * rispetto alla scala la decide `ob_record_measurement`, non questo
     * ViewModel: due controlli sono due regole che prima o poi divergono.
     */
    fun registra(
        metrica: ObMetrica,
        valore: Double,
        giorno: LocalDate,
        nota: String,
    ) {
        viewModelScope.launch {
            try {
                val esito = ObiettiviRepository.registraRilevazione(
                    metricaId = metrica.id,
                    valore = valore,
                    giorno = giorno,
                    nota = nota,
                )
                if (!esito.ok) {
                    _state.value = _state.value.copy(
                        messaggio = esito.error ?: "Rilevazione non registrata"
                    )
                    return@launch
                }
                _state.value = _state.value.copy(
                    messaggio = "Rilevato: ${esito.value}",
                    ultimeRilevazioni = ObiettiviRepository.ultimeRilevazioni(),
                    avanzamenti = ObiettiviRepository.avanzamenti(
                        _state.value.obiettivi.map { it.id }
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "rilevazione non registrata", e)
                _state.value = _state.value.copy(
                    messaggio = e.message ?: "Rilevazione non registrata"
                )
            }
        }
    }

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    private companion object {
        const val TAG = "Obiettivi"
    }
}
