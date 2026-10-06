package com.garsal.appsphere.peso

import android.content.Context
import androidx.annotation.DrawableRes
import com.garsal.appsphere.R
import com.garsal.appsphere.core.AuthRepo
import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Un premio cibo — gemello di `PRIZES` in `weight-quest.html`. Le foto sono
 * le stesse cinque di `risorse/premi`, bundlate come drawable perché qui non
 * c'è una pagina statica da cui caricarle.
 *
 * ⚠️ Gli `id` sono la chiave archiviata in `ps_milestone_prizes.prize_id` e
 * devono restare **identici** a quelli del web: cambiandone uno di qua, il
 * premio grattato sul telefono si riaprirebbe sul PC come «premio ignoto».
 */
data class PremioCibo(
    val id: String,
    val emoji: String,
    val nome: String,
    @DrawableRes val immagine: Int,
    val messaggio: String,
)

val PREMI_CIBO = listOf(
    PremioCibo(
        "torta_savoia", "🍰", "Torta Savoia", R.drawable.torta,
        "Sette strati di gloria, e te li sei sudati tutti.",
    ),
    PremioCibo(
        "cannolo", "🥐", "Cannolo", R.drawable.cannolo,
        "Ricotta e scorza croccante. Uno, però — non tre.",
    ),
    PremioCibo(
        "pizza", "🍕", "Pizza", R.drawable.pizza,
        "Stasera si esce: la pizza è già pagata dalla bilancia.",
    ),
    PremioCibo(
        "cioccolata", "🍫", "Tavoletta di cioccolata", R.drawable.cioccolata,
        "Una tavoletta intera, quadretto dopo quadretto.",
    ),
    PremioCibo(
        "biscotti", "🍪", "Tazza di biscotti", R.drawable.biscotti,
        "Tazza piena e biscotti a volontà: inzuppa senza fretta.",
    ),
)

fun premioDa(id: String): PremioCibo? = PREMI_CIBO.firstOrNull { it.id == id }

/**
 * Un premio già grattato. [mangiatoIl] è **l'unica verità** sul «Mangiato !!!»:
 * non c'è un booleano accanto alla data, che sarebbe un secondo modo di dire
 * la stessa cosa. Nullo = premio ancora da godersi.
 */
data class PremioVinto(val id: String, val data: String, val mangiatoIl: String? = null) {
    val mangiato: Boolean get() = mangiatoIl != null
}

/**
 * Premi cibo e punti dei traguardi intermedi — il «gratta e vinci» delle
 * soglie di peso.
 *
 * ⚠️ **Stanno su Supabase**, in `ps_milestone_prizes` e `ps_milestone_points`
 * (migration `20260824100000`), e non più nelle preferenze del telefono: sono
 * le stesse due tabelle che legge e scrive `weight-quest.html`, così il premio
 * grattato qui si ritrova sul PC e i punti sotto la stellina sono un numero
 * solo. Le vecchie righe rimaste nelle preferenze si portano su una volta sola
 * ([premi], migrazione silenziosa) — senza, aggiornare l'app avrebbe fatto
 * sparire premi già vinti.
 *
 * Qui si scrive in `upsert` perché l'app conosce il proprio `user_id`
 * ([AuthRepo]); il web, che non lo conosce, fa PATCH-e-poi-INSERT. Due
 * meccaniche diverse, la stessa riga sul database.
 */
object PesoPremi {

    private const val PREFS = "peso_premi"
    private val db get() = Supabase.client().postgrest

    private fun utente(): String = AuthRepo.userId() ?: error("Nessun utente collegato")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Premi ───────────────────────────────────────────────────────────

    /**
     * I premi di un obiettivo, per soglia. Al primo giro porta sul database
     * quelli che stavano solo nelle preferenze di questo telefono.
     */
    suspend fun premi(context: Context, obiettivoId: String): Map<Int, PremioVinto> =
        withContext(Dispatchers.IO) {
            val dalDb = db.from("ps_milestone_prizes")
                .select(Columns.raw("threshold,prize_id,won_on,consumed_on")) {
                    filter { eq("objective_id", obiettivoId) }
                }
                .decodeList<JsonObject>()
                .mapNotNull { riga ->
                    val soglia = intero(riga, "threshold")?.toInt() ?: return@mapNotNull null
                    val premio = testo(riga, "prize_id") ?: return@mapNotNull null
                    soglia to PremioVinto(
                        id = premio,
                        data = testo(riga, "won_on").orEmpty(),
                        mangiatoIl = testo(riga, "consumed_on"),
                    )
                }
                .toMap()

            val soloLocali = premiNellePreferenze(context, obiettivoId)
                .filterKeys { it !in dalDb }
            if (soloLocali.isEmpty()) return@withContext dalDb

            val righe = soloLocali.map { (soglia, premio) ->
                rigaPremio(obiettivoId, soglia, premio.id, premio.data.ifBlank { oggi() })
            }
            runCatching {
                db.from("ps_milestone_prizes").upsert(righe) {
                    onConflict = "user_id,objective_id,threshold"
                }
            }
            dalDb + soloLocali
        }

    /** Il premio appena grattato. */
    suspend fun salvaPremio(obiettivoId: String, soglia: Int, premioId: String): PremioVinto =
        withContext(Dispatchers.IO) {
            val quando = oggi()
            db.from("ps_milestone_prizes").upsert(rigaPremio(obiettivoId, soglia, premioId, quando)) {
                onConflict = "user_id,objective_id,threshold"
            }
            PremioVinto(premioId, quando)
        }

    /**
     * «Mangiato !!!» — e il suo contrario. Reversibile di proposito: un
     * tocco per sbaglio non deve costare un cannolo.
     */
    suspend fun segnaMangiato(obiettivoId: String, soglia: Int, mangiato: Boolean): String? =
        withContext(Dispatchers.IO) {
            val quando = if (mangiato) oggi() else null
            db.from("ps_milestone_prizes").update(
                buildJsonObject { put("consumed_on", quando) }
            ) {
                filter {
                    eq("objective_id", obiettivoId)
                    eq("threshold", soglia)
                }
            }
            quando
        }

    private fun rigaPremio(obiettivoId: String, soglia: Int, premioId: String, quando: String) =
        buildJsonObject {
            put("user_id", utente())
            put("objective_id", obiettivoId)
            put("threshold", soglia)
            put("prize_id", premioId)
            put("won_on", quando)
        }

    // ── Punti totali dei traguardi ──────────────────────────────────────

    /** `⭐ Punti Totali Traguardi Intermedi`, uno per obiettivo. */
    suspend fun puntiTotali(context: Context, obiettivoId: String): Int = withContext(Dispatchers.IO) {
        val riga = db.from("ps_milestone_points")
            .select(Columns.raw("total_points")) { filter { eq("objective_id", obiettivoId) } }
            .decodeList<JsonObject>()
            .firstOrNull()
        if (riga != null) return@withContext intero(riga, "total_points")?.toInt() ?: 0

        // Nessuna riga: se il valore c'era nelle preferenze è quello di prima
        // della migration, e va portato su.
        val locale = prefs(context).getInt("wq_mpts_$obiettivoId", 0)
        if (locale > 0) runCatching { salvaPuntiTotali(obiettivoId, locale) }
        locale
    }

    suspend fun salvaPuntiTotali(obiettivoId: String, punti: Int) = withContext(Dispatchers.IO) {
        db.from("ps_milestone_points").upsert(
            buildJsonObject {
                put("user_id", utente())
                put("objective_id", obiettivoId)
                put("total_points", punti)
            }
        ) { onConflict = "user_id,objective_id" }
        Unit
    }

    // ── Quel che resta delle preferenze: solo la migrazione ─────────────

    /**
     * I premi come li scriveva la versione per-dispositivo
     * (`wq_prizes_<id>`, la stessa forma di `localStorage` nel web). Si legge
     * soltanto: da qui in poi nessuno scrive più in queste preferenze.
     */
    private fun premiNellePreferenze(context: Context, obiettivoId: String): Map<Int, PremioVinto> {
        val testo = prefs(context).getString("wq_prizes_$obiettivoId", null) ?: return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(testo).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.entries.mapNotNull { (chiave, valore) ->
            val soglia = chiave.toIntOrNull() ?: return@mapNotNull null
            val voce = valore as? JsonObject ?: return@mapNotNull null
            val id = voce["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: return@mapNotNull null
            val data = voce["date"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content.orEmpty()
            soglia to PremioVinto(id, data)
        }.toMap()
    }

    private fun oggi() = LocalDate.now().toString()
}
