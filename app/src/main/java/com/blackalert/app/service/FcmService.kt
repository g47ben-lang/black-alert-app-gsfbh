package com.blackalert.app.service

import android.util.Log
import com.blackalert.app.data.AlertEvent
import com.blackalert.app.data.Prefs
import com.blackalert.app.util.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * קבלת push (FCM) במכשירים תומכים. ה-relay דוחף הודעת data עם אותם שדות אירוע,
 * וכאן הם עוברים *אותו* צינור כמו ב-polling (AlertProcessor → NotificationHelper),
 * כך שהצלצול/הניווט/ההיסטוריה זהים בשתי דרכי המסירה.
 *
 * הצפוי ב-data: notificationId, cities (JSON array או CSV), eventType, time, expireAt,
 * version, status, note, address, lat, lng. (payload גמיש — שדות חסרים מקבלים ברירת מחדל.)
 */
class FcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        if (d.isEmpty()) return
        val event = runCatching { parse(d) }.getOrNull() ?: return

        val prefs = Prefs(this)
        val processor = AlertProcessor(this)
        val notifications = NotificationHelper(this)
        processor.process(listOf(event)).forEach { decision ->
            when (decision) {
                is AlertDecision.Alert -> notifications.showAlert(decision.event, decision.withSound, decision.target, prefs)
                is AlertDecision.Close -> notifications.cancelAlert(decision.event)
                AlertDecision.Ignore -> {}
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.i("FcmService", "FCM token refreshed")
        // נרשמים ל-topic מחדש ליתר ביטחון
        PushManager.applyDelivery(applicationContext)
    }

    private fun parse(d: Map<String, String>): AlertEvent {
        val o = JSONObject()
        d["notificationId"]?.let { o.put("notificationId", it) }
        d["eventType"]?.toIntOrNull()?.let { o.put("eventType", it) }
        d["time"]?.toLongOrNull()?.let { o.put("time", it) }
        d["expireAt"]?.toLongOrNull()?.let { o.put("expireAt", it) }
        d["version"]?.toIntOrNull()?.let { o.put("version", it) }
        d["status"]?.let { o.put("status", it) }
        d["silent"]?.let { o.put("silent", it.toBoolean()) }
        d["note"]?.let { o.put("note", it) }
        d["address"]?.let { o.put("address", it) }
        d["lat"]?.toDoubleOrNull()?.let { o.put("lat", it) }
        d["lng"]?.toDoubleOrNull()?.let { o.put("lng", it) }
        // cities — תומך גם ב-JSON array וגם ב-CSV
        val citiesRaw = d["cities"] ?: ""
        val arr = runCatching { JSONArray(citiesRaw) }.getOrElse {
            JSONArray().apply { citiesRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) } }
        }
        o.put("cities", arr)
        return AlertEvent.fromJson(o)
    }
}
