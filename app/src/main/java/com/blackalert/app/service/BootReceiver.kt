package com.blackalert.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackalert.app.data.Prefs

/** מפעיל מחדש את שירות ה-polling אחרי אתחול המכשיר, עדכון האפליקציה, או הריגת השירות. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs(context).serviceEnabled) return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_RESTART -> PollingService.start(context)
        }
    }

    companion object {
        const val ACTION_RESTART = "com.blackalert.app.RESTART_SERVICE"
    }
}
