package com.garsal.smartblocker

import android.content.Context
import android.content.SharedPreferences

/** Entità in blocco: app di provenienza ('tasks' | 'ta_firi') + id dell'entità (task o sfida). */
data class BlockedEntity(val app: String, val entityId: String)

object Prefs {
    private const val NAME = "smartblocker_prefs"

    const val STATE_NONE      = 0  // nessun blocco
    const val STATE_TRIGGERED = 1  // bloccato, rinvio disponibile
    const val STATE_LOCKED    = 2  // bloccato, solo PIN

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getState(ctx: Context): Int        = sp(ctx).getInt("block_state", STATE_NONE)
    fun setState(ctx: Context, s: Int)     { sp(ctx).edit().putInt("block_state", s).apply() }

    fun getSnoozeCount(ctx: Context): Int  = sp(ctx).getInt("snooze_count", 0)
    fun setSnoozeCount(ctx: Context, n: Int) { sp(ctx).edit().putInt("snooze_count", n).apply() }

    fun getSnoozeUntil(ctx: Context): Long = sp(ctx).getLong("snooze_until", 0L)
    fun setSnoozeUntil(ctx: Context, ms: Long) { sp(ctx).edit().putLong("snooze_until", ms).apply() }

    fun getLastTrigger(ctx: Context): String = sp(ctx).getString("last_trigger", "") ?: ""
    fun setLastTrigger(ctx: Context, k: String) { sp(ctx).edit().putString("last_trigger", k).apply() }

    /** Entità (app|entityId) da completare quando l'utente sblocca con PIN (separate da virgola). */
    fun getBlockEntities(ctx: Context): List<BlockedEntity> {
        val raw = sp(ctx).getString("block_entities", "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split("|", limit = 2)
            if (parts.size == 2) BlockedEntity(parts[0], parts[1]) else null
        }
    }
    fun setBlockEntities(ctx: Context, entities: List<BlockedEntity>) {
        sp(ctx).edit().putString("block_entities", entities.joinToString(",") { "${it.app}|${it.entityId}" }).apply()
    }
    fun clearBlockEntities(ctx: Context) { sp(ctx).edit().remove("block_entities").apply() }

    /** App di provenienza delle righe cm_notification_queue che hanno generato il blocco corrente.
        È indipendente da block_entities: una riga senza entity_id valido non finisce tra le entità
        da completare, ma la sua app conta lo stesso per decidere colore e comportamento della
        schermata (altrimenti un blocco cost_analysis con entity_id vuoto cadrebbe sul rosso/PIN). */
    fun getBlockApps(ctx: Context): List<String> =
        (sp(ctx).getString("block_apps", "") ?: "").split(",").filter { it.isNotBlank() }
    fun setBlockApps(ctx: Context, apps: List<String>) {
        sp(ctx).edit().putString("block_apps", apps.filter { it.isNotBlank() }.distinct().joinToString(",")).apply()
    }
    fun clearBlockApps(ctx: Context) { sp(ctx).edit().remove("block_apps").apply() }

    /** App del blocco corrente: block_apps se presente, altrimenti (prefs scritte da una versione
        precedente dell'app) si ricade sulle app delle entità. */
    private fun blockApps(ctx: Context): List<String> =
        getBlockApps(ctx).ifEmpty { getBlockEntities(ctx).map { it.app } }

    /** true se il blocco corrente riguarda solo sfide Ta Firi (sfondo verde), false altrimenti (sfondo rosso). */
    fun isChallengeOnlyBlock(ctx: Context): Boolean {
        if (Config.IS_NOTIFY_ONLY) return false   // le sfide non arrivano su quel profilo
        val apps = blockApps(ctx)
        return apps.isNotEmpty() && apps.all { it == "ta_firi" }
    }

    /** true se il blocco corrente riguarda solo notifiche informative (es. cost_analysis: sync
        Revolut da completare) — sfondo giallo, dismiss con un solo bottone, nessun PIN e nessuna
        RPC di completamento (non c'è un task/sfida da "completare" server-side). */
    fun isInfoOnlyBlock(ctx: Context): Boolean {
        // Rete di sicurezza del profilo "solo notifiche": su quel telefono nessuna schermata
        // deve mai chiedere il PIN, che è di Salvatore. Senza questo, un blocco con prefs
        // incomplete (block_apps vuoto, es. rimasto da un aggiornamento) cadrebbe sul rosso e
        // resterebbe lì, insbloccabile da chi ha il telefono in mano.
        if (Config.IS_NOTIFY_ONLY) return true
        val apps = blockApps(ctx)
        return apps.isNotEmpty() && apps.all { it == "cost_analysis" }
    }

    /** Titolo del blocco attivo (join dei titoli dei task pendenti). */
    fun getBlockTitle(ctx: Context): String = sp(ctx).getString("block_title", "") ?: ""
    fun setBlockTitle(ctx: Context, title: String) { sp(ctx).edit().putString("block_title", title).apply() }
    fun clearBlockTitle(ctx: Context) { sp(ctx).edit().remove("block_title").apply() }

    /** Data del blocco (fireAt ISO, es. "2026-05-04") — usata come p_today in task_complete. */
    fun getBlockDate(ctx: Context): String = sp(ctx).getString("block_date", "") ?: ""
    fun setBlockDate(ctx: Context, date: String) { sp(ctx).edit().putString("block_date", date).apply() }
    fun clearBlockDate(ctx: Context) { sp(ctx).edit().remove("block_date").apply() }

    /** Token dispositivo — impostato da Supabase via get_smart_block_token() RPC. */
    fun getDeviceToken(ctx: Context): String = sp(ctx).getString("device_token", "") ?: ""
    fun setDeviceToken(ctx: Context, token: String) { sp(ctx).edit().putString("device_token", token).apply() }

    /** id della riga cm_notification_queue del blocco cost_analysis corrente (per il PATCH di
        metadata.cost_analysis.transactions dopo ogni categoria scelta) e il suo metadata JSON
        grezzo (device_token + cost_analysis.transactions/categories). Vuoto se non è un blocco
        di categorizzazione interattiva. */
    fun getBlockCaQueueId(ctx: Context): String = sp(ctx).getString("block_ca_queue_id", "") ?: ""
    fun setBlockCaQueueId(ctx: Context, id: String) { sp(ctx).edit().putString("block_ca_queue_id", id).apply() }

    fun getBlockCaData(ctx: Context): String = sp(ctx).getString("block_ca_data", "") ?: ""
    fun setBlockCaData(ctx: Context, json: String) { sp(ctx).edit().putString("block_ca_data", json).apply() }

    fun clearBlockCaData(ctx: Context) {
        sp(ctx).edit().remove("block_ca_queue_id").remove("block_ca_data").apply()
    }

    /** Avvia il servizio automaticamente al boot del dispositivo. Default: true. */
    fun getAutoStart(ctx: Context): Boolean = sp(ctx).getBoolean("auto_start", true)
    fun setAutoStart(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("auto_start", v).apply() }
}
