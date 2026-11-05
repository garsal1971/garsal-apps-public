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

    fun messaggioMostrato() {
        _state.value = _state.value.copy(messaggio = null)
    }

    private companion object {
        const val TAG = "Obiettivi"
    }
}
