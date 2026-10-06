package com.garsal.appsphere.premi

import android.util.Log
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Il catalogo premi e i riscatti — le stesse due tabelle e le stesse regole di
 * `index.html` (blocco `── PREMI ──`).
 *
 * ⚠️ **Le regole sono duplicate fra qui e la pagina web**, come per Spuntiamola
 * o per lo snapshot del patrimonio. Le tre che *sono* la funzionalità:
 *
 * - **i punti disponibili sono i punti guadagnati meno quelli spesi**
 *   (`max(0, lordo − speso)`), e il lordo è la somma dei punteggi di **tutte**
 *   le app della home, non delle sole app portate in nativo: un premio costa lo
 *   stesso da qui e da là, quindi deve essere confrontato con lo stesso saldo;
 * - **il riscatto è in due passi**: prima la riga in `cm_rewards_log` — che è
 *   quella che scala i punti — e poi l'aggiornamento del premio. Nell'ordine
 *   inverso un errore di rete lascerebbe un premio segnato come ritirato senza
 *   che nessun punto sia stato speso;
 * - **una tantum e ripetibile finiscono diversamente**: il primo esce dal
 *   catalogo (`is_redeemed`), il secondo resta e **rincara** di
 *   `points_per_use` a ogni uso, contandoli in `use_count`.
 */
object PremiRepository {

    private const val TAG = "AppSpherePremi"

    private val db get() = Supabase.client().postgrest

    /** Il catalogo intero, dal più economico — come `loadRewards()`. */
    suspend fun premi(): List<Premio> = withContext(Dispatchers.IO) {
        db.from("cm_rewards")
            .select { order("points_cost", Order.ASCENDING) }
            .decodeList<JsonObject>()
            .mapNotNull { Premio.da(it) }
    }

    /**
     * I punti già spesi, sommando il log.
     *
     * Come `fetchPtsSpent()` nel web, un errore vale 0 invece di propagarsi: il
     * riquadro del totale sta in home e non deve poterla far fallire. La
     * conseguenza — un totale più alto del vero finché la lettura non riesce —
     * è la stessa di là, e nel log resta scritto perché.
     */
    suspend fun puntiSpesi(): Int = withContext(Dispatchers.IO) {
        try {
            db.from("cm_rewards_log")
                .select(Columns.raw("points_spent"))
                .decodeList<JsonObject>()
                .sumOf { numero(it, "points_spent") ?: 0 }
        } catch (e: Exception) {
            Log.w(TAG, "punti spesi non letti: ${e.message}")
            0
        }
    }

    /** La cronologia dei riscatti, dal più recente. */
    suspend fun cronologia(): List<Riscatto> = withContext(Dispatchers.IO) {
        db.from("cm_rewards_log")
            .select(Columns.raw("reward_name,points_spent,redeemed_at")) {
                order("redeemed_at", Order.DESCENDING)
            }
            .decodeList<JsonObject>()
            .map { Riscatto.da(it) }
    }

    /**
     * Riscatta un premio: registra la spesa e poi aggiorna il premio, nello
     * stesso ordine di `redeemReward()`.
     */
    suspend fun riscatta(premio: Premio) = withContext(Dispatchers.IO) {
        db.from("cm_rewards_log").insert(
            buildJsonObject {
                put("reward_id", premio.id)
                put("reward_name", premio.nome)
                put("points_spent", premio.costo)
            }
        )

        val aggiornamento = if (premio.ripetibile) {
            buildJsonObject {
                put("points_cost", premio.costo + (premio.puntiPerUso ?: 0))
                put("use_count", premio.usi + 1)
            }
        } else {
            buildJsonObject { put("is_redeemed", true) }
        }
        db.from("cm_rewards").update(aggiornamento) { filter { eq("id", premio.id) } }
        Unit
    }

    /** Crea un premio nuovo (`id` nullo) o ne aggiorna uno che esiste già. */
    suspend fun salva(
        id: String?,
        nome: String,
        tipo: String,
        costo: Int,
        puntiPerUso: Int?,
    ) = withContext(Dispatchers.IO) {
        val riga = buildJsonObject {
            put("name", nome)
            put("type", tipo)
            put("points_cost", costo)
            // Passando da ripetibile a una tantum il valore va azzerato, non
            // lasciato lì: resterebbe a rincarare un premio che non si ripete.
            put(
                "points_per_use",
                if (puntiPerUso == null) JsonNull else JsonPrimitive(puntiPerUso),
            )
        }
        if (id == null) db.from("cm_rewards").insert(riga)
        else db.from("cm_rewards").update(riga) { filter { eq("id", id) } }
        Unit
    }

    /**
     * Elimina un premio dal catalogo. La cronologia non si tocca: le righe di
     * `cm_rewards_log` portano già `reward_name` e `points_spent`, quindi il
     * riscatto resta leggibile e i punti restano spesi.
     */
    suspend fun elimina(id: String) = withContext(Dispatchers.IO) {
        db.from("cm_rewards").delete { filter { eq("id", id) } }
        Unit
    }
}
