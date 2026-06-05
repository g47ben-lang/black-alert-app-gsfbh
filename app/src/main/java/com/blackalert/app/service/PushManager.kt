package com.blackalert.app.service

import android.content.Context
import android.util.Log
import com.blackalert.app.R
import com.blackalert.app.data.Prefs
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * ניהול failover של מסירת התראות:
 *   - מכשיר עם Google Play Services + Firebase מוגדר  → push (FCM), נרשם ל-topic.
 *   - אחרת (כולל טלפונים מנוהלים/כשרים ללא Play)        → polling ידני (PollingService).
 *
 * אתחול Firebase ידני (ללא google-services plugin) — כך שהבנייה והאפליקציה עובדות גם
 * ללא הגדרת Firebase. ערכי ה-client מגיעים מ-res/values/firebase_config.xml.
 */
object PushManager {
    private const val TAG = "PushManager"
    private var initialized = false

    /** האם המכשיר תומך ב-push בפועל (Play Services זמין + Firebase מוגדר). */
    fun isPushAvailable(context: Context): Boolean {
        if (!isFirebaseConfigured(context)) return false
        val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return code == ConnectionResult.SUCCESS
    }

    /** מצב המסירה האפקטיבי בהינתן בחירת המשתמש והזמינות בפועל. */
    fun effectiveMode(context: Context): String {
        return when (Prefs(context).deliveryMode) {
            "push" -> if (isPushAvailable(context)) "push" else "poll" // כפיית push אך אין זמינות → נופל ל-poll
            "poll" -> "poll"
            else -> if (isPushAvailable(context)) "push" else "poll"   // auto
        }
    }

    /** מפעיל push אם רלוונטי: מאתחל Firebase ונרשם ל-topic. בטוח לקרוא תמיד. */
    fun applyDelivery(context: Context) {
        if (effectiveMode(context) == "push") {
            ensureFirebase(context)
            subscribe(context)
        } else {
            // אם אנחנו ב-poll אך אותחל קודם — ביטול הרשמה (best-effort)
            if (initialized) runCatching {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(topic(context))
            }
        }
    }

    private fun subscribe(context: Context) {
        runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(topic(context))
                .addOnCompleteListener { t ->
                    Log.i(TAG, "subscribe ${topic(context)} success=${t.isSuccessful}")
                }
        }
    }

    private fun ensureFirebase(context: Context) {
        if (initialized) return
        if (!isFirebaseConfigured(context)) return
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(context.getString(R.string.fb_app_id))
                    .setApiKey(context.getString(R.string.fb_api_key))
                    .setProjectId(context.getString(R.string.fb_project_id))
                    .setGcmSenderId(context.getString(R.string.fb_sender_id))
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            initialized = true
        }.onFailure { Log.w(TAG, "Firebase init failed: ${it.message}") }
    }

    private fun isFirebaseConfigured(context: Context): Boolean {
        val appId = context.getString(R.string.fb_app_id)
        val key = context.getString(R.string.fb_api_key)
        return appId != "UNSET" && key != "UNSET" && appId.isNotBlank() && key.isNotBlank()
    }

    private fun topic(context: Context) = context.getString(R.string.fb_topic)
}
