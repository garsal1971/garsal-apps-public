package com.garsal.appsphere.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dove porta una scorciatoia: la voce del menù dell'icona (`res/xml/shortcuts.xml`, riconosciuta
 * dall'[azione]) o la seconda icona nel cassetto delle app (l'`activity-alias` del manifest,
 * riconosciuta dal nome del componente, [alias]).
 */
enum class Destinazione(val azione: String, val alias: String) {
    /** 🍽️ Segna un pasto: Calorie col ➕ «Aggiungi alimento» già aperto. */
    PASTO("com.garsal.appsphere.SCORCIATOIA_PASTO", "com.garsal.appsphere.SegnaPasto");

    companion object {
        fun daAzione(azione: String?): Destinazione? = entries.firstOrNull { it.azione == azione }
        fun daAlias(componente: String?): Destinazione? = entries.firstOrNull { it.alias == componente }
    }
}

/**
 * La scorciatoia premuta, in attesa che la navigazione la porti a destinazione.
 *
 * È il gemello di [Condivisione], per le stesse ragioni:
 *
 * - ⚠️ **sta in memoria e basta.** Nelle preferenze sopravviverebbe al riavvio,
 *   e riaprendo l'app dalla sua icona giorni dopo ci si ritroverebbe sul ➕ di
 *   un pasto che nessuno ha chiesto di segnare;
 * - ⚠️ **deve sopravvivere allo sblocco con l'impronta**: fra il tocco
 *   sull'icona e la schermata di Calorie c'è la biometria, e non si può tenere
 *   nell'intent, che l'Activity si ritroverebbe fra le mani a ogni ricreazione;
 * - **si consuma una volta sola**: chi la esegue la toglie con [consuma].
 */
object Scorciatoia {

    private val _inArrivo = MutableStateFlow<Destinazione?>(null)

    val inArrivo: StateFlow<Destinazione?> = _inArrivo.asStateFlow()

    fun ricevi(destinazione: Destinazione) { _inArrivo.value = destinazione }

    fun consuma() { _inArrivo.value = null }
}
