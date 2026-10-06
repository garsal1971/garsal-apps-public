package com.garsal.appsphere.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La modalità nascosta, condivisa fra le schermate — l'equivalente di
 * `sessionStorage.hidden_mode` sul web.
 *
 * ⚠️ **Sta in memoria e basta, di proposito.** Sul web vive in `sessionStorage`
 * e muore con la scheda del browser; qui muore col processo. Scriverla nelle
 * preferenze la farebbe ritrovare accesa al risveglio dell'app — cioè le schede
 * riservate a schermo senza che nessuno abbia rifatto il codice, che è
 * esattamente ciò che la modalità esiste per impedire.
 *
 * L'accende **solo la home**, col codice a colori; le altre
 * schermate la leggono e, quando è accesa, possono alzare o abbassare il
 * proprio filtro — come fa `memo.html` col pulsante 🙈/👁.
 */
object ModalitaNascosta {

    private val _attiva = MutableStateFlow(false)
    val attiva: StateFlow<Boolean> = _attiva.asStateFlow()

    fun cambia() { _attiva.value = !_attiva.value }

    fun spegni() { _attiva.value = false }
}
