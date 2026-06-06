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

    fun isMqttConfigured(context: Context): Boolean = Prefs(context).mqttBrokerUrl.isNotBlank()

    /**
     * הערוץ האפקטיבי: "fcm" / "mqtt" / "poll".
     * auto: FCM אם זמין (Play+Firebase) → אחרת MQTT אם מוגדר → אחרת polling.
     * push: כופה ערוץ push (fcm→mqtt) אם קיים, אחרת poll. poll: תמיד polling.
     */
    fun effectiveMode(context: Context): String {
        val fcm = isPushAvailable(context)
        val mqtt = isMqttConfigured(context)
        return when (Prefs(context).deliveryMode) {
            "poll" -> "poll"
            "push", "auto", null -> if (fcm) "fcm" else if (mqtt) "mqtt" else "poll"
            else -> if (fcm) "fcm" else if (mqtt) "mqtt" else "poll"
        }
    }

    /** האם ערוץ push כלשהו פעיל (להחלטת safety-poll מול polling מהיר). */
    fun isPushActive(context: Context): Boolean = effectiveMode(context) != "poll"

    /** מפעיל FCM אם רלוונטי (MQTT מנוהל ע"י ה-Foreground Service). בטוח לקרוא תמיד. */
    fun applyDelivery(context: Context) {
        if (effectiveMode(context) == "fcm") {
            ensureFirebase(context)
            subscribe(context)
        } else {
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
