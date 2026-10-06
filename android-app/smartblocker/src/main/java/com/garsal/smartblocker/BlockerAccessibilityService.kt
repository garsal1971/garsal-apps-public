package com.garsal.smartblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class BlockerAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.also {
            it.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            it.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            it.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            it.notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val state = Prefs.getState(this)
        if (state == Prefs.STATE_NONE) return

        // L'overlay WindowManager (BlockWindowManager) è TYPE_APPLICATION_OVERLAY: sta già sopra
        // qualsiasi app, quindi non serve rilanciare nulla mentre è a schermo. Rilanciarlo faceva
        // danni concreti sul picker categorie Analisi Costi: aprendo la tastiera per cercare una
        // categoria arriva un TYPE_WINDOW_STATE_CHANGED con il package dell'IME, che qui veniva
        // scambiato per "l'utente ha aperto un'altra app" → BlockOverlayActivity partiva dietro
        // l'overlay (invisibile) e riaffiorava, come schermata PIN rossa, appena il picker si
        // chiudeva a categorizzazione finita.
        if (BlockWindowManager.isOverlayVisible()) return

        // I blocchi informativi (cost_analysis) non inseguono l'utente tra le app: sono avvisi,
        // non hanno un PIN da digitare né entità da completare, e BlockOverlayActivity non sa
        // rappresentarli (mostrerebbe comunque la schermata PIN rossa).
        if (Prefs.isInfoOnlyBlock(this)) return

        val pkg = event.packageName?.toString() ?: return
        // Se l'utente apre un'app diversa mentre il blocco è attivo, ri-lancia l'overlay
        if (pkg != packageName && pkg != "android" && pkg != "com.android.systemui") {
            startActivity(
                Intent(this, BlockOverlayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }
    }

    override fun onInterrupt() {}
}
