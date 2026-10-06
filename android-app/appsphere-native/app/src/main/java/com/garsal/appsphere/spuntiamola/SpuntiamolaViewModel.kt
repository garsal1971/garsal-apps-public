package com.garsal.appsphere.spuntiamola

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garsal.appsphere.core.AuthRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Il messaggio che compare per un paio di secondi in fondo allo schermo. */
data class Toast(
    val emoji: String,
    val testo: String,
    val durataMs: Long = 2500,
    val tono: TonoToast = TonoToast.NORMALE,
    val id: Long = System.nanoTime(),
)

/**
 * Un'esplosione di allegria da disegnare.
 * `id` cambia a ogni richiesta perché due feste identiche di fila si vedano
 * come due feste e non come una sola.
 */
data class Festa(
    val coriandoli: Int = 0,
    val scoppi: Int = 0,
    val granFinale: Boolean = false,
    val id: Long = System.nanoTime(),
)

data class SpuntiamolaState(
    val impostazioni: SpSettings? = null,
    val spunte: Map<String, String> = emptyMap(),
    val giornateChiave: Map<String, String> = emptyMap(),
    val stecche: List<SpStecca> = emptyList(),
    val caricamento: Boolean = true,
    val errore: String? = null,
    val toast: Toast? = null,
    val festa: Festa? = null,
    val chiusuraInCorso: Boolean = false,
) {
    val giorni: List<LocalDate> get() = giorniDelPeriodo(impostazioni)
    val configurato: Boolean get() = impostazioni != null
    /** La voce con cui l'app commenta questa stecca. Senza stecca, l'attesa. */
    val mood: Mood get() = moodDi(impostazioni?.mood)
    val spuntati: Int get() = giorni.count { spunte.containsKey(it.toString()) }
    val totali: Int get() = giorni.size
    val percentuale: Int get() = if (totali == 0) 0 else (spuntati * 100f / totali).roundToInt()
    val serie: Int get() = calcolaSerie(giorni, spunte)
    val mancano: Int
        get() {
            val oggi = LocalDate.now()
            return giorni.count { !spunte.containsKey(it.toString()) && it >= oggi }
        }
    val daChiudere: Boolean
        get() = steccaDaChiudere(giorni, spunte, impostazioni?.endDate)
}

class SpuntiamolaViewModel : ViewModel() {

    private val _state = MutableStateFlow(SpuntiamolaState())
    val state: StateFlow<SpuntiamolaState> = _state.asStateFlow()

    init { carica() }

    fun carica() {
        viewModelScope.launch {
            _state.value = _state.value.copy(caricamento = true, errore = null)
            try {
                val impostazioni = SpuntiamolaRepository.impostazioni()
                _state.value = _state.value.copy(
                    impostazioni = impostazioni,
                    spunte = SpuntiamolaRepository.spunte(),
                    giornateChiave = SpuntiamolaRepository.giornateChiave(),
                    stecche = SpuntiamolaRepository.stecche(),
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
     * Spunta o de-spunta un giorno.
     *
     * La scrittura è **ottimistica con rollback**, come sul web: la casella si
     * riempie subito e, se il database rifiuta, si svuota di nuovo con un
     * avviso. Una spunta finta che sparisce al riavvio sarebbe peggio di un
     * errore visibile.
     */
    fun togglaGiorno(giorno: LocalDate) {
        val iso = giorno.toString()
        val stato = _state.value

        if (stato.spunte.containsKey(iso)) {
            val precedente = stato.spunte.getValue(iso)
            _state.value = stato.copy(
                spunte = stato.spunte - iso,
                toast = Toast("🤔", stato.mood.toastDespunta, durataMs = 2000),
            )
            viewModelScope.launch {
                try {
                    SpuntiamolaRepository.togliSpunta(iso)
                } catch (e: Exception) {
                    Log.w(TAG, "rimozione non sincronizzata", e)
                    _state.value = _state.value.copy(
                        spunte = _state.value.spunte + (iso to precedente),
                        toast = Toast("⚠️", "Non sono riuscito a togliere la spunta. Riprova.", 3500),
                    )
                }
            }
            return
        }

        val eChiave = stato.giornateChiave.containsKey(iso)
        val emoji = if (eChiave) "🌟" else stato.mood.emojiSpunta.random()
        val spunteNuove = stato.spunte + (iso to emoji)

        _state.value = if (eChiave) {
            val etichetta = stato.giornateChiave[iso].orEmpty()
            stato.copy(
                spunte = spunteNuove,
                toast = Toast(
                    emoji = "🎆",
                    testo = if (etichetta.isNotBlank()) "GIORNATA CHIAVE — $etichetta!"
                            else "GIORNATA CHIAVE spuntata!",
                    durataMs = 5000,
                    tono = TonoToast.CHIAVE,
                ),
                festa = Festa(coriandoli = 150, scoppi = 6),
            )
        } else {
            stato.copy(
                spunte = spunteNuove,
                toast = Toast(emoji, stato.mood.frasi.random()),
                festa = Festa(coriandoli = 45),
            )
        }

        // Il messaggio del traguardo aspetta che quello della spunta abbia finito.
        controllaTraguardi(
            spuntati = _state.value.spuntati,
            totali = _state.value.totali,
            ritardoMs = if (eChiave) 5200 else 2600,
        )

        viewModelScope.launch {
            try {
                SpuntiamolaRepository.aggiungiSpunta(iso, emoji)
            } catch (e: Exception) {
                Log.w(TAG, "spunta non sincronizzata", e)
                _state.value = _state.value.copy(
                    spunte = _state.value.spunte - iso,
                    toast = Toast("⚠️", "Spunta non salvata: controlla la connessione.", 3500),
                )
            }
        }
    }

    /**
     * 25 / 50 / 75 / 100 %: scatta solo il giro in cui la soglia viene passata.
     * I testi vengono dall'umore, perché metà strada vuol dire due cose
     * opposte a seconda della stecca: «da qui si vede il traguardo» oppure
     * «da qui comincia a finire».
     */
    private fun controllaTraguardi(spuntati: Int, totali: Int, ritardoMs: Long) {
        if (totali == 0) return
        val mood = _state.value.mood
        val ora = spuntati * 100f / totali
        val prima = (spuntati - 1) * 100f / totali

        mood.traguardi.filter { prima < it.perc && ora >= it.perc }.forEach { traguardo ->
            viewModelScope.launch {
                delay(ritardoMs)
                // ⚠️ Gli scoppi al 100 % dipendono dall'umore: su una stecca di
                // bei giorni l'ultimo giorno è un addio, e festeggiarlo coi
                // fuochi suonerebbe come una presa in giro. I coriandoli
                // restano, che sono un saluto.
                val finita = traguardo.perc == 100
                _state.value = _state.value.copy(
                    toast = Toast(traguardo.emoji, traguardo.testo, 4500, TonoToast.GRANDE),
                    festa = Festa(
                        coriandoli = if (finita) 160 else 110,
                        scoppi = if (finita && mood.fuochiAl100) 6 else 0,
                    ),
                )
            }
        }
    }

    fun salvaImpostazioni(
        traguardo: String,
        emoji: String,
        mood: String,
        inizio: String,
        fine: String,
        saltaWeekend: Boolean,
    ) {
        viewModelScope.launch {
            val nuove = SpSettings(
                goal = traguardo.ifBlank { "Il traguardo" },
                emoji = emoji.ifBlank { "🎯" },
                mood = moodDi(mood).id,
                startDate = inizio,
                endDate = fine,
                skipWeekend = saltaWeekend,
            )
            try {
                SpuntiamolaRepository.salvaImpostazioni(nuove)
                _state.value = _state.value.copy(impostazioni = nuove)
            } catch (e: Exception) {
                Log.w(TAG, "impostazioni non salvate", e)
                _state.value = _state.value.copy(
                    toast = Toast("⚠️", "Impostazioni non salvate: ${e.message}", 3500)
                )
            }
        }
    }

    /**
     * Le giornate chiave si modificano su una copia e si applicano tutte
     * insieme: così "Annulla" butta via davvero tutto, come sul web.
     */
    fun salvaGiornateChiave(nuove: Map<String, String>) {
        viewModelScope.launch {
            val vecchie = _state.value.giornateChiave
            try {
                SpuntiamolaRepository.allineaGiornateChiave(nuove, vecchie)
                _state.value = _state.value.copy(giornateChiave = nuove)
            } catch (e: Exception) {
                Log.w(TAG, "giornate chiave non salvate", e)
                _state.value = _state.value.copy(
                    toast = Toast("⚠️", "Giornate chiave non salvate: ${e.message}", 3500)
                )
            }
        }
    }

    /**
     * Archivia la stecca. Qui niente ottimismo: si aspetta l'esito, perché il
     * passo successivo dell'archiviazione è cancellare il periodo — vedi il
     * commento su [SpuntiamolaRepository.chiudiStecca].
     */
    fun chiudiStecca(soddisfazione: Int, nota: String, emojiFinale: String, quando: String?) {
        val stato = _state.value
        val impostazioni = stato.impostazioni ?: return
        val utente = AuthRepo.userId()
        if (utente == null) {
            _state.value = stato.copy(
                toast = Toast("⚠️", "Non sei connesso: la stecca non si può archiviare.", 3500)
            )
            return
        }

        val giorni = stato.giorni
        // ⚠️ La voce si prende ADESSO, finché lo stato descrive ancora questa
        // stecca: più giù `impostazioni` va a null e moodDi() tornerebbe
        // all'attesa — salutare dei bei giorni con la voce dell'attesa sarebbe
        // il modo peggiore di finirli. È la stessa ragione per cui nel web
        // festeggiaChiusura() riceve l'umore come parametro.
        val voce = stato.mood
        val riga = SpSteccaNuova(
            userId = utente,
            goal = impostazioni.goal,
            emoji = impostazioni.emoji,
            mood = voce.id,
            startDate = impostazioni.startDate,
            endDate = impostazioni.endDate,
            skipWeekend = impostazioni.skipWeekend,
            totalDays = giorni.size,
            doneDays = stato.spuntati,
            finalEmoji = emojiFinale,
            finalCheckAt = quando ?: OffsetDateTime.now(ZoneOffset.UTC).toString(),
            satisfaction = soddisfazione,
            note = nota.trim(),
            checks = SpuntiamolaRepository.mappaJson(stato.spunte),
            keyDays = SpuntiamolaRepository.mappaJson(stato.giornateChiave),
        )

        viewModelScope.launch {
            _state.value = _state.value.copy(chiusuraInCorso = true)
            try {
                SpuntiamolaRepository.chiudiStecca(riga)
            } catch (e: Exception) {
                Log.w(TAG, "stecca non archiviata", e)
                _state.value = _state.value.copy(
                    chiusuraInCorso = false,
                    toast = Toast(
                        "⚠️",
                        "La stecca non è stata archiviata (${e.message ?: "connessione assente"}). Riprova.",
                        4000,
                    ),
                )
                return@launch
            }

            val messaggio = messaggioChiusura(soddisfazione, voce)
            // Da 8 a 24 scoppi secondo la soddisfazione: qualche fuoco parte
            // comunque, una stecca chiusa è una stecca chiusa.
            val scoppi = 8 + (soddisfazione / 100f * 16).roundToInt()

            _state.value = _state.value.copy(
                impostazioni = null,      // campo libero: si riparte quando si vuole
                spunte = emptyMap(),
                giornateChiave = emptyMap(),
                chiusuraInCorso = false,
                toast = Toast(messaggio.emoji, messaggio.testo, 7000, messaggio.tono),
                festa = Festa(coriandoli = 120, scoppi = scoppi, granFinale = true),
            )
            // L'archivio si rilegge dal database: così la riga mostrata è
            // quella salvata davvero, con il suo closed_at.
            try {
                _state.value = _state.value.copy(stecche = SpuntiamolaRepository.stecche())
            } catch (e: Exception) {
                Log.w(TAG, "archivio non ricaricato", e)
            }
        }
    }

    /**
     * 🗑️ Cancella il traguardo in corso. ⚠️ Prima il database e solo dopo lo
     * stato: sparito solo dall'app, il traguardo tornerebbe da sé al prossimo
     * caricamento e sembrerebbe che la cancellazione non funzioni. Per la
     * stessa ragione qui niente ottimismo con rollback.
     */
    fun cancellaTraguardo() {
        if (_state.value.impostazioni == null) return
        viewModelScope.launch {
            try {
                SpuntiamolaRepository.cancellaTraguardo()
            } catch (e: Exception) {
                Log.w(TAG, "traguardo non cancellato", e)
                _state.value = _state.value.copy(
                    toast = Toast("⚠️", "Il traguardo non è stato cancellato (${e.message ?: "connessione assente"}). Riprova.", 4000)
                )
                return@launch
            }
            // Campo libero, come dopo una stecca chiusa: le impostazioni si
            // riaprono su un foglio bianco invece di riproporre quello buttato.
            _state.value = _state.value.copy(
                impostazioni = null,
                spunte = emptyMap(),
                giornateChiave = emptyMap(),
                toast = Toast("🗑️", "Traguardo cancellato. Quando vuoi se ne imposta un altro.", 3500),
            )
        }
    }

    /** 🗑️ Toglie una stecca dall'archivio: prima il database, poi l'elenco. */
    fun cancellaStecca(id: String?) {
        if (id == null) {
            // Senza id non si sa quale cancellare, e toglierla solo di qui la
            // farebbe tornare al prossimo caricamento.
            _state.value = _state.value.copy(
                toast = Toast("⚠️", "Questa stecca non è ancora sincronizzata: ricarica e riprova.", 3500)
            )
            return
        }
        viewModelScope.launch {
            try {
                SpuntiamolaRepository.cancellaStecca(id)
            } catch (e: Exception) {
                Log.w(TAG, "stecca non cancellata", e)
                _state.value = _state.value.copy(
                    toast = Toast("⚠️", "Non sono riuscito a cancellare la stecca. Riprova.", 3500)
                )
                return@launch
            }
            _state.value = _state.value.copy(
                stecche = _state.value.stecche.filterNot { it.id == id },
                toast = Toast("🗑️", "Stecca cancellata."),
            )
        }
    }

    fun toastMostrato(id: Long) {
        if (_state.value.toast?.id == id) _state.value = _state.value.copy(toast = null)
    }

    fun festaMostrata(id: Long) {
        if (_state.value.festa?.id == id) _state.value = _state.value.copy(festa = null)
    }

    private companion object {
        const val TAG = "Spuntiamola"
    }
}
