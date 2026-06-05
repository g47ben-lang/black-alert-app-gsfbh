package com.blackalert.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blackalert.app.R
import com.blackalert.app.data.AlertEvent
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.Prefs
import com.blackalert.app.service.NavTarget
import com.blackalert.app.ui.AlertActivity
import com.blackalert.app.ui.MainActivity
import com.blackalert.app.ui.NotificationActionReceiver

/**
 * בונה את כל ההתראות:
 *  - foreground: התראה מתמשכת לשירות (סטטוס חיבור)
 *  - alert: התראת אירוע — heads-up + צליל + full-screen intent (מצלצל ומדליק מסך גם נעול)
 */
class NotificationHelper(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun buildForeground(connected: Boolean): Notification {
        ensureForegroundChannel()
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (connected) context.getString(R.string.fg_connected)
        else context.getString(R.string.fg_disconnected)
        return NotificationCompat.Builder(context, CH_FOREGROUND)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(context.getString(R.string.fg_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateForeground(connected: Boolean) {
        nm.notify(FOREGROUND_ID, buildForeground(connected))
    }

    fun showAlert(event: AlertEvent, withSound: Boolean, target: NavTarget?, prefs: Prefs) {
        ensureAlertChannels(prefs)
        val repo = CitiesRepository.get(context)
        val title = eventTypeTitle(event.eventType)
        val cityNames = event.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        val body = buildString {
            append(cityNames)
            if (event.address.isNotBlank()) append("\n📍 ${event.address}")
            if (event.note.isNotBlank()) append("\n${event.note}")
        }

        val notifId = event.notificationId.hashCode() and 0x7fffffff

        // Intent לפתיחת מסך ההתראה המלא
        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlertActivity.EXTRA_TITLE, title)
            putExtra(AlertActivity.EXTRA_CITIES, cityNames)
            putExtra(AlertActivity.EXTRA_ADDRESS, event.address)
            putExtra(AlertActivity.EXTRA_NOTE, event.note)
            putExtra(AlertActivity.EXTRA_EVENT_TYPE, event.eventType)
            putExtra(AlertActivity.EXTRA_WITH_SOUND, withSound)
            target?.let {
                putExtra(AlertActivity.EXTRA_LAT, it.lat)
                putExtra(AlertActivity.EXTRA_LNG, it.lng)
                putExtra(AlertActivity.EXTRA_LABEL, it.label)
            }
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, notifId, fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channel = if (withSound) CH_ALERT_SOUND else CH_ALERT_SILENT

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText(cityNames)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPi)
            .setColor(0xFFD32F2F.toInt())

        // full-screen intent — מקפיץ את מסך ההתראה ומדליק את המסך גם כשהוא נעול
        if (prefs.fullScreenAlert) {
            builder.setFullScreenIntent(fullScreenPi, true)
        }

        // כפתור ניווט ישירות מההתראה
        if (target != null) {
            val navIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_NAVIGATE
                putExtra(NotificationActionReceiver.EXTRA_LAT, target.lat)
                putExtra(NotificationActionReceiver.EXTRA_LNG, target.lng)
                putExtra(NotificationActionReceiver.EXTRA_LABEL, target.label)
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            val navPi = PendingIntent.getBroadcast(
                context, notifId, navIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_navigate, context.getString(R.string.action_navigate), navPi)
        }

        nm.notify(notifId, builder.build())
    }

    fun cancelAlert(event: AlertEvent) {
        nm.cancel(event.notificationId.hashCode() and 0x7fffffff)
    }

    private fun eventTypeTitle(type: Int): String = when (type) {
        0 -> context.getString(R.string.event_type_0)
        2 -> context.getString(R.string.event_type_2)
        3 -> context.getString(R.string.event_type_3)
        else -> context.getString(R.string.event_type_8)
    }

    // --- ערוצי התראה ---
    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CH_FOREGROUND) == null) {
            val ch = NotificationChannel(CH_FOREGROUND, context.getString(R.string.ch_foreground), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    /** ערוץ הצליל נבנה עם הצליל הנבחר. שינוי צליל דורש ערוץ חדש (אנדרואיד לא מאפשר לשנות צליל קיים). */
    private fun ensureAlertChannels(prefs: Prefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // ערוץ שקט
        if (nm.getNotificationChannel(CH_ALERT_SILENT) == null) {
            val ch = NotificationChannel(CH_ALERT_SILENT, context.getString(R.string.ch_alert_silent), NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            ch.enableVibration(prefs.vibrate)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        // ערוץ צליל — מזהה כולל את שם הצליל כדי לאפשר החלפה
        val soundChannelId = CH_ALERT_SOUND
        val desiredSound = prefs.soundName
        val existing = nm.getNotificationChannel(soundChannelId)
        val storedSound = context.getSharedPreferences("ba_channel", Context.MODE_PRIVATE).getString("sound", null)
        if (existing == null || storedSound != desiredSound) {
            existing?.let { nm.deleteNotificationChannel(soundChannelId) }
            val uri = soundUri(desiredSound)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val ch = NotificationChannel(soundChannelId, context.getString(R.string.ch_alert_sound), NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(uri, attrs)
            ch.enableVibration(prefs.vibrate)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
            context.getSharedPreferences("ba_channel", Context.MODE_PRIVATE).edit().putString("sound", desiredSound).apply()
        }
    }

    private fun soundUri(name: String): Uri {
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        val safe = if (resId != 0) resId else R.raw.bell2
        return Uri.parse("android.resource://${context.packageName}/$safe")
    }

    companion object {
        const val CH_FOREGROUND = "foreground_status"
        const val CH_ALERT_SOUND = "alert_sound"
        const val CH_ALERT_SILENT = "alert_silent"
        const val FOREGROUND_ID = 1001
    }
}
