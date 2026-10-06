package com.garsal.appsphere.obiettivi

import com.garsal.appsphere.core.Supabase
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Letture e scritture di Obiettivi.
 *
 * ⚠️ Regola del CLAUDE.md rispettata alla lettera: **il calcolo del progresso
 * vive nelle RPC, non qui**. Il client legge `ob_objective_progress` e scrive
 * solo passando da `ob_record_measurement`, che è anche l'unica a decidere se
 * un voto sta dentro la scala della metrica.
 */
object ObiettiviRepository {

    private val db get() = Supabase.client().postgrest

    suspend fun obiettivi(): List<ObObiettivo> = withContext(Dispatchers.IO) {
        db.from("ob_objectives")
            .select(Columns.raw("id,parent_id,title,why,horizon,start_date,due_date,status,color,sort_order")) {
                order("sort_order", Order.ASCENDING)
                order("due_date", Order.ASCENDING)
            }
            .decodeList<ObObiettivo>()
    }

    suspend fun metriche(): List<ObMetrica> = withContext(Dispatchers.IO) {
        db.from("ob_metrics")
            .select(
                Columns.raw(
                    "id,objective_id,name,role,kind,min_value,max_value," +
                        "baseline,target,unit,descrizione,sort_order"
                )
            ) { order("sort_order", Order.ASCENDING) }
            .decodeList<ObMetrica>()
    }

    suspend fun milestone(): List<ObMilestone> = withContext(Dispatchers.IO) {
        db.from("ob_milestones")
            .select(Columns.raw("id,objective_id,label,due_date,expected_value,status")) {
                order("due_date", Order.ASCENDING)
            }
            .decodeList<ObMilestone>()
    }

    /** L'ultima rilevazione di ogni metrica, per mostrare "dove siamo". */
    suspend fun ultimeRilevazioni(): Map<String, ObRilevazione> = withContext(Dispatchers.IO) {
        db.from("ob_measurements")
            .select(Columns.raw("metric_id,measured_on,value,note")) {
                order("measured_on", Order.DESCENDING)
            }
            .decodeList<ObRilevazione>()
            // la lista arriva già ordinata dal più recente: la prima di ogni
            // metrica è quella che interessa
            .groupBy { it.metricId }
            .mapValues { (_, righe) -> righe.first() }
    }

    /** Un avanzamento per obiettivo, calcolato dal server. */
    suspend fun avanzamenti(ids: List<String>): Map<String, ObAvanzamento> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                ids.map { id ->
                    async {
                        runCatching {
                            id to db.rpc(
                                "ob_objective_progress",
                                buildJsonObject { put("p_objective_id", id) },
                            ).decodeAs<ObAvanzamento>()
                        }.getOrNull()
                    }
                }.mapNotNull { it.await() }.toMap()
            }
        }

    /**
     * Registra una rilevazione.
     *
     * Un solo valore per tutt'e due i tipi: il voto dell'autovalutazione e il
     * numero dell'automisurazione finiscono nella stessa colonna. È la RPC a
     * rifiutare un voto fuori scala — qui un secondo controllo sarebbe una
     * seconda regola da tenere allineata.
     */
    suspend fun registraRilevazione(
        metricaId: String,
        valore: Double,
        giorno: LocalDate,
        nota: String,
    ): ObEsitoRilevazione = withContext(Dispatchers.IO) {
        db.rpc(
            "ob_record_measurement",
            buildJsonObject {
                put("p_metric_id", metricaId)
                put("p_value", valore)
                put("p_measured_on", giorno.toString())
                put("p_note", nota)
            },
        ).decodeAs<ObEsitoRilevazione>()
    }
}
