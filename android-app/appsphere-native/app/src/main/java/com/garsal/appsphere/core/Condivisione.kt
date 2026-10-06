package com.garsal.appsphere.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quel che un'altra app ha mandato col tasto «Condividi».
 *
 * `oggetto` è `EXTRA_SUBJECT`, che YouTube riempie col titolo del video: è già
 * pronto, non costa nessuna chiamata di rete e funziona anche offline.
 */
data class TestoCondiviso(val testo: String, val oggetto: String)

/**
 * La condivisione in arrivo, in attesa che qualcuno se la prenda.
 *
 * ⚠️ **L'intent-filter `ACTION_SEND` + `text/plain` lo dichiara UNA sola APK.**
 * I due APK (`com.garsalapps` e `com.garsal.appsphere`) convivono sullo stesso
 * telefono con lo stesso logo, distinti solo dal fondo bianco/nero:
 * dichiarandolo in tutt'e due, nel menù «Condividi» comparirebbero **due voci
 * indistinguibili** e toccherebbe indovinare ogni volta. È la stessa ragione
 * per cui hanno due schemi OAuth diversi. Fino alla v1.1.0 lo dichiarava
 * l'APK WebView: la voce si è **spostata** qui, non aggiunta.
 *
 * Sta in memoria e basta, come [ModalitaNascosta]: è un gesto in corso, non un
 * dato. Scriverla nelle preferenze la farebbe ritrovare al risveglio dell'app,
 * che riaprirebbe una scheda già compilata giorni dopo, senza che nessuno abbia
 * condiviso niente.
 *
 * Deve però sopravvivere al **login e alla biometrica**: fra il tocco su
 * «Salva in Memo» e la schermata di Memo ci possono stare uno sblocco con
 * l'impronta e, la prima volta, un giro nel browser — e la condivisione non si
 * può tenere nell'intent, che l'Activity si ritroverebbe fra le mani a ogni
 * ricreazione.
 */
object Condivisione {

    private val _inArrivo = MutableStateFlow<TestoCondiviso?>(null)

    /** Chi la aspetta la guarda da qui; [consuma] la toglie di mezzo. */
    val inArrivo: StateFlow<TestoCondiviso?> = _inArrivo.asStateFlow()

    fun ricevi(testo: String, oggetto: String) {
        _inArrivo.value = TestoCondiviso(testo = testo, oggetto = oggetto)
    }

    /** La toglie: una condivisione si apre una volta sola. */
    fun consuma() { _inArrivo.value = null }
}
