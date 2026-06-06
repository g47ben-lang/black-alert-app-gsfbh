package com.blackalert.app.service

import android.content.Context
import android.util.Log
import com.blackalert.app.data.AlertEvent
import com.blackalert.app.data.Prefs
import com.blackalert.app.util.NotificationHelper
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject

/**
 * ערוץ push למכשירים ללא Google Play — חיבור MQTT מתמשך יחיד, מנוהל ע"י ה-Foreground Service.
 *
 * חסכוני בסוללה: keepalive ארוך (ההודעות מגיעות מיידית כל עוד החיבור פתוח; ה-keepalive רק
 * מזהה ניתוק). session מתמשך + QoS 1 → הודעה שנשלחה בזמן ניתוק קצר תימסר בחיבור מחדש.
 * חיבור מחדש אוטומטי עם backoff. ההודעה data-only ועוברת את *אותו* AlertProcessor כמו FCM/polling.
 */
class MqttChannel(private val context: Context) {
    private val prefs = Prefs(context)
    private var client: MqttAsyncClient? = null
    @Volatile private var stopped = false

    fun start() {
        val broker = prefs.mqttBrokerUrl.trim()
        if (broker.isEmpty()) return
        stopped = false
        try {
            // clientId יציב לכל מכשיר → session מתמשך אצל ה-broker
            val clientId = "ba-" + (android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "anon").take(20)
            val c = MqttAsyncClient(broker, clientId, MemoryPersistence())
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    runCatching { c.subscribe(prefs.mqttTopic, 1) }
                    Log.i(TAG, "MQTT connected (reconnect=$reconnect), subscribed ${prefs.mqttTopic}")
                }
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message ?: return
                    handlePayload(String(message.payload, Charsets.UTF_8))
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true          // backoff מובנה (1ש'→2דק')
                isCleanSession = false               // session מתמשך — לא מאבדים הודעות בניתוק קצר
                keepAliveInterval = 180              // 3 דק' — חסכוני בסוללה
                connectionTimeout = 20
                maxInflight = 20
                val u = prefs.mqttUsername; val p = prefs.mqttPassword
                if (u.isNotEmpty()) { userName = u; if (p.isNotEmpty()) password = p.toCharArray() }
            }
            client = c
            c.connect(opts)
        } catch (e: Exception) {
            Log.w(TAG, "MQTT start failed: ${e.message}")
        }
    }

    fun stop() {
        stopped = true
        runCatching { client?.disconnectForcibly(500, 500) }
        runCatching { client?.close() }
        client = null
    }

    val isConnected: Boolean get() = client?.isConnected == true

    private fun handlePayload(payload: String) {
        val event = runCatching { parse(payload) }.getOrNull() ?: return
        val processor = AlertProcessor(context)
        val notifications = NotificationHelper(context)
        processor.process(listOf(event)).forEach { d ->
            when (d) {
                is AlertDecision.Alert -> notifications.showAlert(d.event, d.withSound, d.target, prefs)
                is AlertDecision.Close -> notifications.cancelAlert(d.event)
                AlertDecision.Ignore -> {}
            }
        }
    }

    /** מקבל JSON של אירוע יחיד (אותו מבנה כמו /notifications). */
    private fun parse(payload: String): AlertEvent {
        val o = JSONObject(payload)
        // תמיכה ב-cities כמערך JSON או כמחרוזת CSV
        if (o.has("cities") && o.get("cities") is String) {
            val arr = JSONArray()
            (o.getString("cities")).split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
            o.put("cities", arr)
        }
        return AlertEvent.fromJson(o)
    }

    companion object { private const val TAG = "MqttChannel" }
}
