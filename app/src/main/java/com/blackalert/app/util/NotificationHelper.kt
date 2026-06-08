package com.blackalert.app.util

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    fun updateForeground(connected: Boolean) {
        nm.notify(FOREGROUND_ID, buildForeground(connected))
    }

    fun showAlert(event: AlertEvent, withSound: Boolean, target: NavTarget?, prefs: Prefs) {
        ensureAlertChannels(prefs)
        // רטט-בלבד → ללא צליל (אבל עדיין רטט, דרך הערוץ השקט)
        val audible = withSound && !prefs.vibrateOnly
        val repo = CitiesRepository.get(context)
        val title = eventTypeTitle(event.eventType)
        val cityNames = event.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        val baseBody = buildString {
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
            putExtra(AlertActivity.EXTRA_WITH_SOUND, audible)
            putExtra(AlertActivity.EXTRA_NOTIF_ID, notifId)
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

        val channel = if (audible) CH_ALERT_SOUND else CH_ALERT_SILENT

        val navPi: PendingIntent? = if (target != null) {
            val navIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_NAVIGATE
                putExtra(NotificationActionReceiver.EXTRA_LAT, target.lat)
                putExtra(NotificationActionReceiver.EXTRA_LNG, target.lng)
                putExtra(NotificationActionReceiver.EXTRA_LABEL, target.label)
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            PendingIntent.getBroadcast(
                context, notifId, navIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        // כפתור "התעלם" — מבטל את ההתראה. requestCode נפרד (notifId xor) כדי לא להתנגש ב-navPi.
        val dismissPi: PendingIntent = run {
            val dismissIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_DISMISS
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            PendingIntent.getBroadcast(
                context, notifId xor 0x44, dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        // בנייה+פרסום. onlyAlertOnce → עדכון (מפה/זמני הגעה) לא מצלצל שוב. fullScreen רק בפרסום הראשון.
        // mapBitmap != null → תצוגת BigPicture עם מפה; אחרת BigText.
        fun post(bodyText: String, collapsed: String, withFullScreen: Boolean, mapBitmap: Bitmap?) {
            val b = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_alert)
                .setContentTitle(title)
                .setContentText(collapsed)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(fullScreenPi)
                .setColor(0xFFD32F2F.toInt())
            if (event.cities.size > 1) b.setSubText("${event.cities.size} יישובים")
            if (mapBitmap != null) {
                b.setLargeIcon(mapBitmap)
                b.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(mapBitmap)
                        .bigLargeIcon(null as Bitmap?)   // לא לכפול את התמונה כשמורחב
                        .setBigContentTitle(title)
                        .setSummaryText(bodyText)
                )
            } else {
                b.setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            }
            if (navPi != null) b.addAction(R.drawable.ic_navigate, context.getString(R.string.action_navigate), navPi)
            b.addAction(R.drawable.ic_close, context.getString(R.string.action_close), dismissPi)
            b.setDeleteIntent(dismissPi)   // החלקה להסרת ההתראה → משתיק גם את הצליל
            if (withFullScreen && prefs.fullScreenAlert) b.setFullScreenIntent(fullScreenPi, true)
            nm.notify(notifId, b.build())
        }

        post(baseBody, cityNames, withFullScreen = true, mapBitmap = null)

        // הצליל+רטט מנוגנים ע"י האפליקציה (לא ע"י ערוץ ההתראה) כדי שכל סגירה/השתקה תעצור אותם.
        AlertRinger.start(context, prefs, audible)

        // כפיית מסך-מלא גם כשהמכשיר בשימוש פעיל (אנדרואיד מציג full-screen-intent כבאנר בלבד אז).
        if (prefs.fullScreenAlert && prefs.forceFullScreen && canForceFullScreen()) {
            runCatching { context.startActivity(fullScreenIntent) }
        }

        // העשרה אסינכרונית: מפה סטטית בבאנר + זמני הגעה ממיקום המשתמש. לא מעכב, לא מצלצל שוב.
        val wantMap = prefs.mapInNotification && target != null
        val wantTravel = prefs.travelTimesEnabled && target != null
        if (target != null && (wantMap || wantTravel)) Thread {
            val map = if (wantMap) StaticMap.build(target.lat, target.lng, event.eventType) else null
            val fix = if (wantTravel) com.blackalert.app.service.LocationProvider.best(context) else null
            var body = baseBody
            var collapsed = cityNames
            if (fix != null) {
                val info = com.blackalert.app.util.TravelTime.compute(fix.lat, fix.lng, target.lat, target.lng)
                fun m(v: Int) = if (v < 0) "—" else "$v"
                // נסיעה תמיד; הליכה/אופניים רק אם פחות מחצי שעה (קרוב).
                val sub = mutableListOf<String>()
                if (info.showWalk) sub.add("🚶 ${info.walkMin} דק' הליכה")
                if (info.showBike) sub.add("🛴 ${info.bikeMin} דק' אופניים/קורקינט")
                val travel = "🚗 ${m(info.driveMin)} דק' נסיעה ממיקומך" + if (sub.isEmpty()) "" else "\n" + sub.joinToString(" · ")
                body = "$baseBody\n$travel"
                collapsed = "$cityNames · 🚗 ${m(info.driveMin)} דק' נסיעה"
            }
            // נפרסם מחדש רק אם יש מה להוסיף (מפה או זמני נסיעה)
            if (map != null || fix != null) post(body, collapsed, withFullScreen = false, mapBitmap = map)
        }.start()
    }

    /** האם מותר ובכלל צריך לכפות מסך-מלא: רק כשהמסך דולק ולא נעול, ויש הרשאת הצגה-מעל. */
    private fun canForceFullScreen(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val inUse = pm.isInteractive && !km.isKeyguardLocked
        // כשהמסך כבוי/נעול — full-screen-intent כבר מטפל בזה; כופים רק במצב שימוש פעיל.
        return inUse && Settings.canDrawOverlays(context)
    }

    fun cancelAlert(event: AlertEvent) {
        nm.cancel(event.notificationId.hashCode() and 0x7fffffff)
    }

    /** התראת עדכון זמין — לחיצה פותחת את דף ה-Release בגיטהאב. */
    fun showUpdate(tag: String, pageUrl: String) {
        ensureUpdateChannel()
        val pi = PendingIntent.getActivity(
            context, 9001,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("עדכון זמין — $tag")
            .setContentText("גרסה חדשה של צבע שחור זמינה להורדה. הקש לעדכון.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(9001, n)
    }

    private fun ensureUpdateChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CH_UPDATE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_UPDATE, "עדכונים", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
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
            val ch = NotificationChannel(CH_FOREGROUND, context.getString(R.string.ch_foreground), NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    /**
     * ערוצי ההתראה — *שקטים* מבחינת המערכת (ללא צליל וללא רטט): הצליל והרטט מנוגנים ע"י
     * AlertRinger כדי שניתן יהיה לעצור אותם ("סגור"/"השתקה"/"נווט"). הערוץ נשאר IMPORTANCE_HIGH
     * כדי להציג heads-up. בקרת ה-DND דורשת גישת מדיניות התראות.
     */
    private fun ensureAlertChannels(prefs: Prefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val dndOk = prefs.overrideDnd && nm.isNotificationPolicyAccessGranted
        val sig = "silent|dnd=$dndOk"
        val store = context.getSharedPreferences("ba_channel", Context.MODE_PRIVATE)
        val changed = store.getString("sig", null) != sig

        for (id in listOf(CH_ALERT_SILENT, CH_ALERT_SOUND)) {
            if (changed || nm.getNotificationChannel(id) == null) {
                nm.deleteNotificationChannel(id)
                val name = if (id == CH_ALERT_SOUND) R.string.ch_alert_sound else R.string.ch_alert_silent
                val ch = NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_HIGH)
                ch.setSound(null, null)          // הצליל מנוגן ע"י AlertRinger, לא ע"י הערוץ
                ch.enableVibration(false)        // הרטט מנוגן ע"י AlertRinger
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (dndOk) ch.setBypassDnd(true)
                nm.createNotificationChannel(ch)
            }
        }
        if (changed) store.edit().putString("sig", sig).apply()
    }

    /** URI הצליל — מותאם אישית אם נבחר, אחרת raw מצורף. */
    fun soundUri(prefs: Prefs): Uri {
        if (prefs.soundName == "custom" && prefs.customSoundUri.isNotEmpty()) {
            return runCatching { Uri.parse(prefs.customSoundUri) }.getOrDefault(defaultRawUri())
        }
        val resId = context.resources.getIdentifier(prefs.soundName, "raw", context.packageName)
        val safe = if (resId != 0) resId else R.raw.bell2
        return Uri.parse("android.resource://${context.packageName}/$safe")
    }

    private fun defaultRawUri(): Uri = Uri.parse("android.resource://${context.packageName}/${R.raw.bell2}")

    companion object {
        const val CH_FOREGROUND = "foreground_status"
        const val CH_ALERT_SOUND = "alert_sound"
        const val CH_ALERT_SILENT = "alert_silent"
        const val CH_UPDATE = "updates"
        const val FOREGROUND_ID = 1001
    }
}
