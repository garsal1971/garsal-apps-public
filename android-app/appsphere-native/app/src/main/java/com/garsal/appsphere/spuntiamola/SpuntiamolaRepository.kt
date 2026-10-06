package com.garsal.appsphere.spuntiamola

import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Le scritture di Spuntiamola sul database.
 *
 * Stesse tabelle e stessi campi di `spuntiamola.html`: le due implementazioni
 * lavorano sulle stesse righe e devono restare interscambiabili — si spunta un
 * giorno da qui e lo si ritrova sul web, con la sua emoji.
 */
object SpuntiamolaRepository {

    private val db get() = Supabase.client().postgrest

    private fun utente(): String =
        AuthRepo.userId() ?: error("Nessun utente collegato")

    suspend fun impostazioni(): SpSettings? = withContext(Dispatchers.IO) {
        db.from("sp_settings")
            .select(Columns.raw("user_id,goal,emoji,mood,start_date,end_date,skip_weekend")) { limit(1) }
            .decodeList<SpSettings>()
            .firstOrNull()
    }

    /** Le spunte come mappa giorno → emoji, la forma in cui le usa la griglia. */
    suspend fun spunte(): Map<String, String> = withContext(Dispatchers.IO) {
        db.from("sp_checks")
            .select(Columns.raw("day,emoji")) { order("day", Order.ASCENDING) }
            .decodeList<SpCheck>()
            .associate { it.day to it.emoji }
    }

    suspend fun giornateChiave(): Map<String, String> = withContext(Dispatchers.IO) {
        db.from("sp_key_days")
            .select(Columns.raw("day,label")) { order("day", Order.ASCENDING) }
            .decodeList<SpKeyDay>()
            .associate { it.day to it.label }
    }

    suspend fun stecche(): List<SpStecca> = withContext(Dispatchers.IO) {
        db.from("sp_stecche")
            .select(Columns.ALL) { order("closed_at", Order.DESCENDING) }
            .decodeList<SpStecca>()
    }

    suspend fun salvaImpostazioni(impostazioni: SpSettings) = withContext(Dispatchers.IO) {
        db.from("sp_settings")
            .upsert(impostazioni.copy(userId = utente())) { onConflict = "user_id" }
        Unit
    }

    suspend fun aggiungiSpunta(giorno: String, emoji: String) = withContext(Dispatchers.IO) {
        db.from("sp_checks")
            .upsert(SpCheck(userId = utente(), day = giorno, emoji = emoji)) {
                onConflict = "user_id,day"
            }
        Unit
    }

    suspend fun togliSpunta(giorno: String) = withContext(Dispatchers.IO) {
        db.from("sp_checks").delete { filter { eq("day", giorno) } }
        Unit
    }

    /**
     * Allinea `sp_key_days` a quanto scelto nelle impostazioni: aggiunge le
     * nuove, toglie quelle rimosse, lascia stare il resto. Una cancellazione
     * totale seguita da un reinserimento farebbe lo stesso effetto ma
     * perderebbe le righe se la seconda metà non arrivasse.
     */
    suspend fun allineaGiornateChiave(
        nuove: Map<String, String>,
        vecchie: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val daAggiungere = nuove.filter { (giorno, etichetta) -> vecchie[giorno] != etichetta }
        val daTogliere = vecchie.keys.filterNot { nuove.containsKey(it) }

        if (daAggiungere.isNotEmpty()) {
            val righe = daAggiungere.map { (giorno, etichetta) ->
                SpKeyDay(userId = utente(), day = giorno, label = etichetta)
            }
            db.from("sp_key_days").upsert(righe) { onConflict = "user_id,day" }
        }
        if (daTogliere.isNotEmpty()) {
            db.from("sp_key_days").delete { filter { isIn("day", daTogliere) } }
        }
        Unit
    }

    /**
     * Archivia la stecca e libera il campo per la prossima.
     *
     * ⚠️ **L'ordine non è negoziabile**: prima l'inserimento in `sp_stecche`, e
     * solo se riesce le cancellazioni. All'inverso una rete che cade
     * cancellerebbe il periodo senza averne salvato la memoria, cioè
     * esattamente il difetto che questa funzione esiste per togliere. Per la
     * stessa ragione qui non si usa l'ottimismo con rollback delle spunte.
     */
    suspend fun chiudiStecca(riga: SpSteccaNuova) = withContext(Dispatchers.IO) {
        db.from("sp_stecche").insert(riga)

        val io = utente()
        db.from("sp_checks").delete { filter { eq("user_id", io) } }
        db.from("sp_key_days").delete { filter { eq("user_id", io) } }
        db.from("sp_settings").delete { filter { eq("user_id", io) } }
        Unit
    }

    /**
     * Butta via il traguardo in corso **senza archiviarlo**: spunte, giornate
     * chiave e periodo. È l'uscita per un traguardo sbagliato o abbandonato;
     * chi vuole conservarne la memoria passa da [chiudiStecca]. Gemella di
     * `dbDeleteGoal()` nel web, nello stesso ordine.
     */
    suspend fun cancellaTraguardo() = withContext(Dispatchers.IO) {
        val io = utente()
        db.from("sp_checks").delete { filter { eq("user_id", io) } }
        db.from("sp_key_days").delete { filter { eq("user_id", io) } }
        db.from("sp_settings").delete { filter { eq("user_id", io) } }
        Unit
    }

    /** Toglie dall'archivio una stecca già chiusa. Gemella di `dbDeleteStecca()`. */
    suspend fun cancellaStecca(id: String) = withContext(Dispatchers.IO) {
        db.from("sp_stecche").delete { filter { eq("id", id) } }
        Unit
    }

    /** Le mappe giorno→testo diventano il jsonb della fotografia. */
    fun mappaJson(mappa: Map<String, String>): JsonObject =
        JsonObject(mappa.mapValues { JsonPrimitive(it.value) })
}
