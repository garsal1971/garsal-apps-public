package com.garsal.smartblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat

class BlockAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.log(context, "ALARM", "onReceive — acquisto WakeLock e avvio servizio")
        acquireWakeLock(context)
        ContextCompat.startForegroundService(context,
            Intent(context, BlockerService::class.java).apply {
                action = BlockerService.ACTION_CHECK_NOW
            })
    }

    companion object {
        @Volatile private var wl: PowerManager.WakeLock? = null

        fun acquireWakeLock(context: Context) {
            val pm = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as PowerManager
            synchronized(this) {
                wl?.let { if (it.isHeld) it.release() }
                wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SmartBlocker::AlarmWakeLock"
                ).also { it.acquire(60_000L) }
            }
        }

        fun releaseWakeLock() {
            synchronized(this) {
                wl?.let { if (it.isHeld) it.release() }
                wl = null
            }
        }
    }
}
