package com.garsal.appsphere.obiettivi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ObObiettivo(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    val title: String = "",
    val why: String = "",
    val horizon: String = "annual",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("due_date") val dueDate: String = "",
    val status: String = "active",
    val color: String? = "#6C5CE7",
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class ObMetrica(
    val id: String,
    @SerialName("objective_id") val objectiveId: String,
    val name: String = "",
    /** `primary` = il risultato · `control` = riscontro lagging · `leading` = lo sforzo. */
    val role: String = "primary",
    /**
     * `autovalutazione` — ti dai un voto dentro la scala `min_value`..`max_value`
     * `automisurazione` — misuri un numero, da `baseline` a `target`
     *
     * ⚠️ Le due coppie si escludono a vicenda (vincolo `ob_metrics_scala_per_tipo`):
     * quella del tipo che non è vale sempre null. Per leggere gli estremi
     * comunque sia fatta la metrica c'è [scala], che è la copia della RPC
     * `ob_metric_scale`.
     */
    val kind: String = "autovalutazione",
    @SerialName("min_value") val minValue: Double? = null,
    @SerialName("max_value") val maxValue: Double? = null,
    val baseline: Double? = null,
    val target: Double? = null,
    val unit: String? = "",
    /**
     * Come votare (autovalutazione) o cosa si misura (automisurazione).
     * Va riproposta a ogni rilevazione: se cambia il metro la serie storica
     * non è più confrontabile.
     */
    val descrizione: String? = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
) {
    /** Da dove a dove va la metrica: la copia Kotlin di `ob_metric_scale`. */
    val scala: Pair<Double, Double>
        get() = if (kind == "autovalutazione") {
            (minValue ?: 0.0) to (maxValue ?: 0.0)
        } else {
            (baseline ?: 0.0) to (target ?: 0.0)
        }
}

@Serializable
data class ObMilestone(
    val id: String,
    @SerialName("objective_id") val objectiveId: String,
    val label: String? = "",
    @SerialName("due_date") val dueDate: String = "",
    @SerialName("expected_value") val expectedValue: Double? = null,
    val status: String = "pending",
)

@Serializable
data class ObRilevazione(
    @SerialName("metric_id") val metricId: String,
    @SerialName("measured_on") val measuredOn: String,
    val value: Double,
    val note: String? = "",
)

/**
 * La risposta di `ob_objective_progress`.
 *
 * `result` ed `execution` arrivano già in centesimi e **non vanno mai fusi in
 * una media**: sono due cose diverse — quanto il risultato si è mosso, e quanto
 * del piano è stato eseguito. Vederle divergere è il segnale utile.
 */
@Serializable
data class ObAvanzamento(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("objective_id") val objectiveId: String? = null,
    val result: Int = 0,
    @SerialName("has_result") val hasResult: Boolean = false,
    val execution: Int = 0,
    @SerialName("has_execution") val hasExecution: Boolean = false,
    @SerialName("current_value") val currentValue: Double? = null,
    @SerialName("expected_value") val expectedValue: Double? = null,
    /** `on_track` | `at_risk` | `off_track` | `unknown`. */
    val status: String = "unknown",
    @SerialName("children_total") val childrenTotal: Int = 0,
    @SerialName("children_done") val childrenDone: Int = 0,
    @SerialName("milestones_total") val milestonesTotal: Int = 0,
    @SerialName("milestones_hit") val milestonesHit: Int = 0,
) {
    /**
     * Piano eseguito ma risultato fermo (o il contrario).
     *
     * A 25 punti di distanza l'app lo dice esplicitamente: è il segnale che il
     * metodo non sta funzionando, e una media dei due numeri lo nasconderebbe
     * proprio quando serve vederlo.
     */
    val divergenza: Boolean
        get() = hasResult && hasExecution && kotlin.math.abs(result - execution) >= 25
}

/** L'esito di `ob_record_measurement`. */
@Serializable
data class ObEsitoRilevazione(
    val ok: Boolean = false,
    val error: String? = null,
    val value: Double? = null,
    val kind: String? = null,
    @SerialName("measured_on") val measuredOn: String? = null,
)
