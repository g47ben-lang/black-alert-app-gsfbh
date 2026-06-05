package com.blackalert.app.ui

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blackalert.app.service.NavTarget
import com.blackalert.app.util.NavigationLauncher

/** מטפל בכפתור "נווט" שעל ההתראה — פותח את בורר אפליקציות הניווט. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NAVIGATE) return
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""

        // סגירת מגירת ההתראות לפני פתיחת הניווט
        context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        NavigationLauncher.launch(context, NavTarget(lat, lng, label))

        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId)
        }
    }

    companion object {
        const val ACTION_NAVIGATE = "com.blackalert.app.NAVIGATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LABEL = "label"
        const val EXTRA_NOTIF_ID = "notifId"
    }
}
