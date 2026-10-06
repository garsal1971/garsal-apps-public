package com.garsal.appsphere.core

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.parseSessionFromFragment
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Login Google e stato della sessione.
 *
 * Il giro è lo stesso dell'APK WebView (`MainActivity.kt`): **browser di
 * sistema** verso Supabase, che rimanda alla pagina-ponte https, e da lì un tap
 * riporta nell'app con un deep link. L'unica differenza è lo schema —
 * `garsalnative://oauth` invece di `garsalapps://oauth`, perché con lo stesso
 * schema Android chiederebbe ogni volta quale delle due app aprire. Perché
 * browser e non Custom Tab, e perché una pagina di mezzo: vedi
 * `loginConGoogle` e `Supabase.OAUTH_REDIRECT`.
 *
 * ⚠️ **Il rientro dal browser non passa più da `parseFragmentAndImportSession`.**
 * Quella funzione fa il lavoro dentro `authScope`, lo scope interno della
 * libreria, che è un `CoroutineScope(dispatcher)` — quindi con un `Job()`
 * normale, non un `SupervisorJob`. La prima cosa che fa lì dentro è una
 * chiamata di rete (`retrieveUser`), fatta nell'istante esatto in cui l'app
 * sta rientrando in primo piano dal browser: se quella tira un'eccezione,
 * l'eccezione risale al Job dello scope e **cancella `authScope` per tutta la
 * vita del processo**. Da quel momento nessun import di sessione, nessun
 * rinnovo del JWT e nessuna rilettura dall'archivio vanno più a segno, e non
 * viene stampato niente: l'app resta sul pulsante di login e ripremerlo non
 * cambia nulla, perché il token torna e non lo raccoglie più nessuno.
 *
 * Qui il fragment si legge con `parseSessionFromFragment` (che è pubblica e non
 * tocca la rete) e la sessione si importa dallo scope di questo oggetto, dentro
 * un try/catch. Al peggio si vede un messaggio; l'auth della libreria non può
 * più morire in silenzio.
 */
object AuthRepo {

    private const val TAG = "AppSphereAuth"

    enum class State { CARICAMENTO, DENTRO, FUORI }

    /**
     * Cosa è arrivato dall'ultimo rientro dal browser.
     *
     * Senza questa traccia un login che non va a buon fine è indistinguibile da
     * un login mai tentato — l'app torna in primo piano e non succede niente.
     * Qui si annota cosa si è visto, e la schermata di login lo mostra invece
     * di lasciare l'utente a guardare una rotella.
     */
    val ultimoRientro = MutableStateFlow<String?>(null)

    fun annotaRientro(descrizione: String?) {
        Log.i(TAG, "rientro dal browser: ${descrizione ?: "entrato"}")
        ultimoRientro.value = descrizione
    }

    /**
     * Scope proprio: il rientro può arrivare quando nessuna schermata è viva.
     * `SupervisorJob` più un handler perché un errore di rete su una chiamata
     * non porti giù le successive — è il difetto che si sta togliendo di mezzo,
     * e sarebbe ridicolo riprodurlo qui.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "errore non gestito", e) }
    )

    private val _state = MutableStateFlow(State.CARICAMENTO)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Ultimi id ed email conosciuti.
     *
     * `currentUserOrNull()` risponde solo mentre lo stato è `Authenticated` e
     * solo se la sessione porta con sé l'anagrafica dell'utente — due cose che
     * qui non si possono dare per scontate, visto che la sessione arriva dal
     * fragment (dove l'utente non c'è) e che `/user` è una chiamata di rete che
     * può fallire. `SpuntiamolaRepository` invece su `userId()` nullo lancia.
     * Quindi si tiene da parte quello che si è visto passare.
     */
    @Volatile
    private var idUtente: String? = null

    @Volatile
    private var emailUtente: String? = null

    init {
        scope.launch {
            Supabase.client().auth.sessionStatus.collect { status ->
                _state.value = when (status) {
                    is SessionStatus.Authenticated -> {
                        // Se `/user` non ha risposto l'anagrafica non c'è, ma
                        // l'id sta comunque nel JWT: è il claim `sub`, ed è
                        // esattamente quello che le RLS leggono come auth.uid().
                        val sessione = status.session
                        idUtente = sessione.user?.id ?: Jwt.claim(sessione.accessToken, "sub")
                        emailUtente = sessione.user?.email ?: Jwt.claim(sessione.accessToken, "email")
                        State.DENTRO
                    }
                    is SessionStatus.Initializing -> State.CARICAMENTO
                    is SessionStatus.NotAuthenticated -> {
                        idUtente = null
                        emailUtente = null
                        State.FUORI
                    }
                    // Il refresh non è riuscito (rete assente o refresh token
                    // revocato). La pagina web in questo caso pulisce i token e
                    // torna al login senza rumore: stessa scelta qui.
                    is SessionStatus.RefreshFailure -> {
                        Log.w(TAG, "refresh del JWT fallito: ${status.cause}")
                        State.FUORI
                    }
                }
            }
        }
    }

    /**
     * Rientro col flusso implicito: i token sono nel fragment del deep link.
     * È la forma in cui risponde questo progetto Supabase.
     */
    fun completaConFragment(fragment: String) {
        if (fragment.contains("error")) {
            annotaRientro("il fragment contiene un errore: $fragment")
            return
        }
        scope.launch {
            val auth = Supabase.client().auth
            val sessione = try {
                auth.parseSessionFromFragment(fragment)
            } catch (e: Exception) {
                Log.w(TAG, "fragment non leggibile", e)
                annotaRientro("token ricevuti ma non leggibili: ${e.message}")
                return@launch
            }
            try {
                // Da qui in poi si è dentro: `importSession` scrive la sessione
                // nell'archivio, la mette in `sessionStatus` e avvia il rinnovo.
                auth.importSession(sessione)
                annotaRientro(null)
            } catch (e: Exception) {
                Log.w(TAG, "sessione non importata", e)
                annotaRientro("sessione non importata: ${e.message}")
                return@launch
            }
            // L'anagrafica dell'utente è un di più: serve alle etichette, non a
            // entrare. Si chiede dopo, e se non risponde non succede niente —
            // id ed email li abbiamo già letti dal JWT.
            try {
                auth.retrieveUserForCurrentSession(updateSession = true)
            } catch (e: Exception) {
                Log.w(TAG, "anagrafica utente non letta (non è un problema): ${e.message}")
            }
        }
    }

    /**
     * Rientro col flusso PKCE: c'è un codice da scambiare col server.
     * Oggi non è la strada che il progetto usa, ma accoglierla costa poche
     * righe e toglie di mezzo la dipendenza da come il server decide di
     * rispondere — che è esattamente ciò che ha rotto la 1.0.2.
     */
    fun completaConCodice(codice: String) {
        scope.launch {
            try {
                Supabase.client().auth.exchangeCodeForSession(codice)
                annotaRientro(null)
            } catch (e: Exception) {
                Log.w(TAG, "scambio del codice fallito", e)
                annotaRientro("scambio del codice fallito: ${e.message}")
            }
        }
    }

    /**
     * Rilettura della sessione dall'archivio, chiesta da noi.
     *
     * Serve a non restare mai appesi su `CARICAMENTO`: la si chiama quando lo
     * stato non si è mosso entro qualche secondo. Gira **in questo** scope, non
     * in quello della libreria, quindi funziona anche nel caso in cui quello sia
     * stato cancellato da un'eccezione altrui — che è precisamente lo scenario
     * da cui non si tornava più indietro.
     */
    fun ricaricaDaArchivio() {
        scope.launch {
            try {
                val trovata = Supabase.client().auth.loadFromStorage()
                Log.i(TAG, "rilettura dall'archivio: ${if (trovata) "sessione trovata" else "nessuna sessione"}")
                if (!trovata && _state.value == State.CARICAMENTO) _state.value = State.FUORI
            } catch (e: Exception) {
                Log.w(TAG, "rilettura dall'archivio fallita", e)
                if (_state.value == State.CARICAMENTO) _state.value = State.FUORI
            }
        }
    }

    /** Email dell'utente collegato, usata per le etichette e per i log. */
    fun email(): String? = Supabase.client().auth.currentUserOrNull()?.email ?: emailUtente

    /** Id dell'utente: è lo stesso `auth.uid()` con cui filtrano tutte le RLS. */
    fun userId(): String? = Supabase.client().auth.currentUserOrNull()?.id ?: idUtente

    /**
     * Apre il login Google nel **browser completo**, non in una Custom Tab.
     *
     * `signInWith(Google)` apriva una Custom Tab, ed è la metà del giro che
     * qui non funziona: la pagina-ponte di ritorno rilancia l'app con un tap
     * su `garsalnative://oauth`, e quel tap **da dentro una Custom Tab aperta
     * dall'app non rilancia niente** — il login resta nel browser. Dal browser
     * di sistema sì. È la stessa cosa che fa `MainActivity` dell'APK WebView,
     * dove sta scritta da quando è costata lo stesso pomeriggio.
     *
     * L'altra metà è il `redirect_to`, che dev'essere una pagina https e non
     * lo schema custom: vedi `Supabase.OAUTH_REDIRECT`.
     */
    fun loginConGoogle(context: Context) {
        val url = Supabase.oauthUrl()
        Log.i(TAG, "apro il login nel browser: $url")
        // Nessun browser che risponda a un http:// — su un telefono non
        // succede, ma se succede va detto invece di lasciare il pulsante
        // premuto senza effetto.
        if (!apriNelBrowser(context, url)) {
            annotaRientro("nessun browser disponibile per aprire il login")
        }
    }

    suspend fun logout() {
        idUtente = null
        emailUtente = null
        Supabase.client().auth.signOut()
    }
}
