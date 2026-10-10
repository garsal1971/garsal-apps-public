package com.garsal.appsphere.home

/** Destinazioni della navigazione. */
object Route {
    const val HOME = "home"
    const val SPUNTIAMOLA = "spuntiamola"
    const val OBIETTIVI = "obiettivi"

    /** L'elenco degli obiettivi: un passo dentro il piano, non la home. */
    const val OBIETTIVI_ELENCO = "obiettivi-elenco"
    const val EVENTS_LOG = "eventslog"
    const val TASKS = "tasks"
    const val TA_FIRI = "tafiri"
    const val PESO = "peso"
    const val MEMO = "memo"
    const val ABITUATI = "abituati"
    const val CALORIE = "calorie"
    const val FORZIERE = "forziere"
    const val PIANTE = "piante"
    const val FINANZA = "finanza"
}

/**
 * Cosa compare in home.
 *
 * Il web mostra tutte le righe attive di `cm_apps`; qui si mostrano solo le app
 * che esistono davvero in nativo. Le altre non sono nascoste per pudore: non
 * esistono proprio in questo APK, e per usarle si apre quello WebView, che
 * resta installato accanto.
 *
 * Titolo, descrizione, colore e punteggio continuano ad arrivare dal database:
 * questo registro decide soltanto *se* la bolla si disegna e *dove* porta il
 * tap. Portare una quarta app in nativo = una riga qui più le sue schermate.
 *
 * I valori di ripiego servono perché non tutte le righe di `cm_apps` sono nate
 * da una migration — la tabella fu popolata a mano prima che esistesse la
 * cartella `migrations/`, e per esempio `events-log.html` non compare in
 * nessun file SQL della repo. Se la riga manca, la bolla si disegna lo stesso
 * invece di sparire senza che nessuno se ne accorga.
 */
data class AppPortata(
    val route: String,
    val titoloDiRipiego: String,
    val descrizioneDiRipiego: String,
    val coloreDiRipiego: String,
)

// «Quali numeri sono punti» non sta più qui: fino alla v1.0.91 c'era l'oggetto
// `AppSenzaPunti`, gemello a mano di `APP_SENZA_PUNTI` in index.html. Adesso lo
// dice la colonna `cm_apps.conta_punti` (vedi [Bolla.contaPunti]).

object PortedApps {

    val perHtmlFile: Map<String, AppPortata> = mapOf(
        "spuntiamola.html" to AppPortata(
            route = Route.SPUNTIAMOLA,
            titoloDiRipiego = "Spuntiamola",
            descrizioneDiRipiego = "Conto alla rovescia a spunte",
            coloreDiRipiego = "#7C3AED",
        ),
        // ⚠️ La bolla porta al **📆 Piano quotidiano** e non all'elenco degli
        // obiettivi: è la pagina che `obiettivi.html` apre per prima, e l'unica
        // di quelle che si aprono col telefono in mano — le azioni si chiudono
        // nel momento in cui si sono fatte, non la sera al computer. Obiettivi
        // era **sospesa in home** finché il nativo non le aveva: ora ce l'ha.
        "obiettivi.html" to AppPortata(
            route = Route.OBIETTIVI,
            titoloDiRipiego = "Obiettivi",
            descrizioneDiRipiego = "Il piano di oggi",
            coloreDiRipiego = "#0891B2",
        ),
        "tasks.html" to AppPortata(
            route = Route.TASKS,
            titoloDiRipiego = "Tasks",
            descrizioneDiRipiego = "Task e ricorrenze",
            coloreDiRipiego = "#FF3366",
        ),
        "events-log.html" to AppPortata(
            route = Route.EVENTS_LOG,
            titoloDiRipiego = "Events Log",
            descrizioneDiRipiego = "Registro di eventi e attività",
            coloreDiRipiego = "#00A651",
        ),
        "ta-firi.html" to AppPortata(
            route = Route.TA_FIRI,
            titoloDiRipiego = "Ta Firi?",
            descrizioneDiRipiego = "Sfide a tempo — Ta Firi?",
            coloreDiRipiego = "#8E44AD",
        ),
        "habit-tracker.html" to AppPortata(
            route = Route.ABITUATI,
            titoloDiRipiego = "Abituati",
            descrizioneDiRipiego = "Abitudini a stack",
            coloreDiRipiego = "#1A1A1A",
        ),
        "memo.html" to AppPortata(
            route = Route.MEMO,
            titoloDiRipiego = "Memo",
            descrizioneDiRipiego = "Schede e appunti",
            coloreDiRipiego = "#2563EB",
        ),
        // ⚠️ `calorie.html` NON è più qui, e `Route.CALORIE` invece resta.
        // Dal 9 settembre 2026 il peso e il diario alimentare sono un'app sola —
        // sul web una pagina sola, `weight-quest.html` con sette viste — quindi
        // in home c'è una bolla e non due. Al diario si arriva dal 🍽️ nella
        // barra di «Ti pisasti?», che è la stessa distanza che ha di là, dove
        // ⚖️ Peso e 📓 Diario sono due voci della stessa barra.
        //
        // Toglierlo di qui e spegnere la riga in `cm_apps` sono la STESSA
        // modifica e vanno insieme: la riga spenta non arriva più a questo
        // registro, quindi lasciarcelo sarebbe una voce inerte che torna a
        // disegnare una seconda bolla il giorno che qualcuno riaccende la riga.
        // ⚠️ La bolla di Forziere ha `riservato = true` in `cm_apps`, quindi si
        // vede **solo in modalità nascosta** — come Finanza, e per la stessa
        // ragione: un forziere annunciato in home a chiunque guardi lo schermo
        // da sopra la spalla è metà del lavoro buttato. Il filtro lo fa già
        // `HomeRepository`, qui non c'è niente di diverso da dire.
        "forziere.html" to AppPortata(
            route = Route.FORZIERE,
            titoloDiRipiego = "🔐 Forziere",
            descrizioneDiRipiego = "I file al sicuro, cifrati end-to-end",
            coloreDiRipiego = "#1F2937",
        ),
        // 🌱 Il gemello nativo di `piante.html`: stesse tabelle `pv_*`, stesse
        // RPC per il ciclo di vita delle azioni, stessa Edge Function `pv-ai`.
        "piante.html" to AppPortata(
            route = Route.PIANTE,
            titoloDiRipiego = "Piante",
            descrizioneDiRipiego = "Diario di cura delle piante",
            coloreDiRipiego = "#7CB342",
        ),
        // 💰 Finanza in SOLA LETTURA (APK 1.0.121): Dashboard, Sviluppo e Portafogli, letti
        // dagli snapshot — vedi `finanza/FinanzaData.kt`. ⚠️ La riga ha `riservato = true`
        // in `cm_apps`, quindi la bolla compare **solo in modalità nascosta**, come il Forziere.
        "finanza.html" to AppPortata(
            route = Route.FINANZA,
            titoloDiRipiego = "Finanza",
            descrizioneDiRipiego = "Patrimonio e portafogli",
            coloreDiRipiego = "#4f46e5",
        ),
        "weight-quest.html" to AppPortata(
            route = Route.PESO,
            titoloDiRipiego = "Peso e Calorie",
            descrizioneDiRipiego = "Il peso e il diario alimentare",
            coloreDiRipiego = "#00B894",
        ),
    )
}
