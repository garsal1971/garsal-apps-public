package com.garsal.appsphere.premi

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PremiState(
    val premi: List<Premio> = emptyList(),
    val spesi: Int = 0,
    val cronologia: List<Riscatto> = emptyList(),
    val caricamento: Boolean = true,
    val errore: String? = null,
) {
    /** Quelli ancora nel catalogo: i «una tantum» già ritirati escono di scena. */
    val daRitirare: List<Premio> get() = premi.filterNot { it.ritirato }

    /** I punti che restano da spendere, dato il punteggio lordo della home. */
    fun puntiDisponibili(lordo: Int): Int = lordo - spesi
}

class PremiViewModel : ViewModel() {

    private val _state = MutableStateFlow(PremiState())
    val state: StateFlow<PremiState> = _state.asStateFlow()

    // Niente `init { carica() }`: il ViewModel resta in vita anche quando il
    // catalogo si chiude, e alla riapertura mostrerebbe i premi di prima. Il
    // caricamento lo chiede la schermata a ogni apertura.

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val premi = PremiRepository.premi()
                val spesi = PremiRepository.puntiSpesi()
                _state.value = _state.value.copy(premi = premi, spesi = spesi, caricamento = false)
            } catch (e: Exception) {
                Log.w(TAG, "catalogo premi non caricato", e)
                _state.value = _state.value.copy(
                    caricamento = false,
                    errore = e.message ?: "Caricamento non riuscito",
                )
            }
        }
    }

    fun caricaCronologia() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(cronologia = PremiRepository.cronologia())
            } catch (e: Exception) {
                Log.w(TAG, "cronologia non caricata", e)
                _state.value = _state.value.copy(errore = e.message ?: "Cronologia non disponibile")
            }
        }
    }

    fun riscatta(premio: Premio) {
        viewModelScope.launch {
            try {
                PremiRepository.riscatta(premio)
            } catch (e: Exception) {
                Log.w(TAG, "riscatto non riuscito", e)
                _state.value = _state.value.copy(errore = e.message ?: "Riscatto non riuscito")
            }
            // Si rilegge in ogni caso: se il log è passato e l'aggiornamento
            // del premio no, i punti sono già spesi e il catalogo a schermo
            // deve dirlo.
            carica()
        }
    }

    fun salva(id: String?, nome: String, tipo: String, costo: Int, puntiPerUso: Int?) {
        viewModelScope.launch {
            try {
                PremiRepository.salva(id, nome, tipo, costo, puntiPerUso)
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "premio non salvato", e)
                _state.value = _state.value.copy(errore = e.message ?: "Salvataggio non riuscito")
            }
        }
    }

    fun elimina(id: String) {
        viewModelScope.launch {
            try {
                PremiRepository.elimina(id)
                carica()
            } catch (e: Exception) {
                Log.w(TAG, "premio non eliminato", e)
                _state.value = _state.value.copy(errore = e.message ?: "Eliminazione non riuscita")
            }
        }
    }

    fun scartaErrore() {
        _state.value = _state.value.copy(errore = null)
    }

    private companion object {
        const val TAG = "AppSpherePremi"
    }
}
