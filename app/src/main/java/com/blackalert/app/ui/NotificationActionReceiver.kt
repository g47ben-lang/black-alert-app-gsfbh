package com.blackalert.app.ui

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackalert.app.service.NavTarget
import com.blackalert.app.util.AlertRinger
import com.blackalert.app.util.NavigationLauncher

/** מטפל בכפתורי ההתראה — "נווט" (פתיחת ניווט) ו"התעלם" (ביטול ההתראה). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // "סגור" — משתיק את הצליל/רטט ומבטל את ההתראה.
        // הערה: אין לשדר ACTION_CLOSE_SYSTEM_DIALOGS — ב-Android 12+ זה דורש הרשאת מערכת
        // (BROADCAST_CLOSE_SYSTEM_DIALOGS) וזורק SecurityException שמקריס את האפליקציה.
        if (intent.action == ACTION_DISMISS) {
            AlertRinger.stop()
            val id = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
            if (id != -1) {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
            }
            return
        }
        if (intent.action != ACTION_NAVIGATE) return
        AlertRinger.stop()   // פתיחת ניווט משתיקה גם את הצליל
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""

        // פתיחת הניווט (האפליקציה שתיפתח ממילא מכסה את מגירת ההתראות)
        NavigationLauncher.launch(context, NavTarget(lat, lng, label))

        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId)
        }
    }

    companion object {
        const val ACTION_NAVIGATE = "com.blackalert.app.NAVIGATE"
        const val ACTION_DISMISS = "com.blackalert.app.DISMISS"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LABEL = "label"
        const val EXTRA_NOTIF_ID = "notifId"
    }
}
