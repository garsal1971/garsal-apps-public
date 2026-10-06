package com.garsal.speseingiro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import com.garsal.speseingiro.ui.SchermataHome
import com.garsal.speseingiro.ui.SchermataImpostazioni
import com.garsal.speseingiro.ui.SchermataIngresso
import com.garsal.speseingiro.ui.SchermataRegistro
import com.garsal.speseingiro.ui.SchermataVoce
import com.garsal.speseingiro.ui.TemaSpeseInGiro

/**
 * Spese in giro: una schermata per volta, senza libreria di navigazione.
 *
 * Le pagine sono cinque e la loro storia è una pila di uno — non c'è niente da
 * far navigare, e `navigation-compose` sarebbe una dipendenza per un `when`.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TemaSpeseInGiro { App() } }
    }
}

private enum class Pagina { INGRESSO, HOME, VOCE, REGISTRO, IMPOSTAZIONI }

@Composable
private fun App(vm: SpeseViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var pagina by remember { mutableStateOf(Pagina.HOME) }
    var tipoNuova by remember { mutableStateOf("spesa") }
    var inModifica by remember { mutableStateOf<Voce?>(null) }
    val avvisi = remember { SnackbarHostState() }

    // Senza nessun viaggio su questo telefono non c'è niente da mostrare:
    // l'ingresso non è una schermata che si sceglie, è l'unica che esiste.
    val vero = if (ui.attivo == null) Pagina.INGRESSO else pagina

    // ⚠️ L'indietro di Android è il gesto con cui si chiude quel che si è
    // aperto: da una schermata figlia riporta alla home invece di far uscire
    // dall'app buttando via quel che si stava scrivendo. È la stessa regola
    // della `guardiaIndietroPopup` delle pagine web.
    BackHandler(enabled = vero != Pagina.HOME && ui.attivo != null) {
        inModifica = null
        pagina = Pagina.HOME
    }

    ui.messaggio?.let { m ->
        LaunchedEffect(m) {
            avvisi.showSnackbar(m)
            vm.messaggioVisto()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(avvisi) }) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (vero) {
                Pagina.INGRESSO -> SchermataIngresso(
                    ui = ui,
                    onCrea = { v, io, i, f -> vm.crea(v, io, i, f) { pagina = Pagina.HOME } },
                    onEntra = { c, io -> vm.entra(c, io) { pagina = Pagina.HOME } },
                    onScegli = { vm.scegli(it.token); pagina = Pagina.HOME },
                )

                Pagina.HOME -> SchermataHome(
                    ui = ui,
                    onNuova = { t -> tipoNuova = t; inModifica = null; pagina = Pagina.VOCE },
                    onModifica = { v -> inModifica = v; tipoNuova = v.tipo; pagina = Pagina.VOCE },
                    onConferma = vm::conferma,
                    onElimina = vm::elimina,
                    onChiediCanc = vm::chiediCancellazione,
                    onRisolvi = vm::risolviCancellazione,
                    onRegistro = { pagina = Pagina.REGISTRO },
                    onImpostazioni = { pagina = Pagina.IMPOSTAZIONI },
                    onRicarica = vm::ricarica,
                    scontrino = vm::scontrinoDi,
                )

                Pagina.VOCE -> {
                    val s = ui.stato
                    // Lo stato può mancare (prima lettura, o rete assente): si
                    // torna alla home con un effetto e non assegnando dentro la
                    // composizione, che rifarebbe partire il disegno da capo.
                    if (s == null) LaunchedEffect(Unit) { pagina = Pagina.HOME }
                    else SchermataVoce(
                        stato = s,
                        tipo = tipoNuova,
                        esistente = inModifica,
                        inCorso = ui.caricamento,
                        onSalva = { importo, data, descr, cat, daId, perChi, benef, foto, letto ->
                            vm.salvaVoce(
                                voceId = inModifica?.id, tipo = tipoNuova, importo = importo,
                                data = data, descrizione = descr, categoria = cat, daId = daId,
                                perChi = perChi, beneficiario = benef, foto = foto, letto = letto,
                            ) { inModifica = null; pagina = Pagina.HOME }
                        },
                        onIndietro = { inModifica = null; pagina = Pagina.HOME },
                    )
                }

                Pagina.REGISTRO -> {
                    val s = ui.stato
                    if (s == null) LaunchedEffect(Unit) { pagina = Pagina.HOME }
                    else SchermataRegistro(s) { pagina = Pagina.HOME }
                }

                Pagina.IMPOSTAZIONI -> SchermataImpostazioni(
                    ui = ui,
                    onScegli = { vm.scegli(it.token); pagina = Pagina.HOME },
                    onNuovoViaggio = { pagina = Pagina.INGRESSO },
                    onSalvaCategoria = vm::salvaCategoria,
                    onEliminaCategoria = vm::eliminaCategoria,
                    onEsci = { id -> vm.esci(id); pagina = Pagina.HOME },
                    onIndietro = { pagina = Pagina.HOME },
                )
            }
        }
    }
}
