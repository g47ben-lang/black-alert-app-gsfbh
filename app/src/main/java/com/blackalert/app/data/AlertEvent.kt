package com.blackalert.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * אירוע "צבע שחור" — תואם למבנה שמגיע מ-GET /notifications ו-GET /alerts-history.
 * שדות אופציונליים (note/address/lat/lng/version/status) עשויים לחסר באירוע ישן.
 *
 * דוגמה:
 * {"id":"evt-...","cities":["קרית יערים - טלזסטון"],"eventType":3,"time":1780607849,
 *  "note":"...","address":"ירושלים","lat":31.81,"lng":35.02,"version":1,"status":"closed"}
 */
data class AlertEvent(
    val notificationId: String,
    val cities: List<String>,
    val eventType: Int,
    val time: Long,          // unix seconds
    val expireAt: Long,      // unix seconds (TTL). ברירת מחדל time+5400
    val version: Int,
    val status: String?,     // "closed" => סיום אירוע
    val silent: Boolean,
    val isDrill: Boolean,
    val note: String,
    val address: String,
    val lat: Double?,        // מיקום מדויק; דורס מרכז-עיר אם קיים
    val lng: Double?,
    /** הזמן שבו המכשיר ראה את הגרסה הזו (להיסטוריה מקומית) */
    val observedAt: Long = 0L
) {
    val isClosed: Boolean get() = status?.equals("closed", true) == true

    /** מפתח dedup — עליית version של אותו אירוע = עריכה => מתריע/נשמר מחדש */
    val dedupKey: String get() = "$notificationId:$version"

    fun toJson(): JSONObject = JSONObject().apply {
        put("notificationId", notificationId)
        put("cities", JSONArray(cities))
        put("eventType", eventType)
        put("time", time)
        put("expireAt", expireAt)
        put("version", version)
        put("status", status ?: JSONObject.NULL)
        put("silent", silent)
        put("isDrill", isDrill)
        put("note", note)
        put("address", address)
        put("lat", lat ?: JSONObject.NULL)
        put("lng", lng ?: JSONObject.NULL)
        put("observedAt", observedAt)
    }

    companion object {
        /** ברירת מחדל ל-TTL כשהשרת לא שולח expireAt (כמו בתוסף: time+5400 = שעה וחצי) */
        const val DEFAULT_TTL_SECONDS = 5400L

        fun fromJson(o: JSONObject): AlertEvent {
            // השרת משתמש ב-"id" בהיסטוריה וב-"notificationId" בהתראות פעילות — תומכים בשניהם
            val id = o.optString("notificationId", o.optString("id", ""))
                .ifEmpty { "evt-" + o.optLong("time", 0L) + "-" + o.optInt("eventType", 8) }

            val cities = mutableListOf<String>()
            o.optJSONArray("cities")?.let { arr ->
                for (i in 0 until arr.length()) cities.add(arr.optString(i))
            }

            val eventTypeRaw = if (o.has("eventType")) o.optInt("eventType", 8)
            else o.optInt("threat", 8)
            val eventType = if (eventTypeRaw in 0..9) eventTypeRaw else 8

            val time = o.optLong("time", System.currentTimeMillis() / 1000)
            val expireAt = if (o.has("expireAt") && !o.isNull("expireAt"))
                o.optLong("expireAt") else time + DEFAULT_TTL_SECONDS

            return AlertEvent(
                notificationId = id,
                cities = cities,
                eventType = eventType,
                time = time,
                expireAt = expireAt,
                version = o.optInt("version", 1),
                status = if (o.has("status") && !o.isNull("status")) o.optString("status") else null,
                silent = o.optBoolean("silent", false),
                isDrill = o.optBoolean("isDrill", false),
                note = o.optString("note", "").let { if (it == "null") "" else it },
                address = o.optString("address", "").let { if (it == "null") "" else it },
                lat = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
                lng = if (o.has("lng") && !o.isNull("lng")) o.optDouble("lng") else null,
                observedAt = o.optLong("observedAt", 0L)
            )
        }

        /** מפענח גם את מבנה ה-/alerts-history המקונן: {id, alerts:[{...}]} → אירוע אחד או יותר */
        fun listFromResponse(json: String): List<AlertEvent> {
            val out = mutableListOf<AlertEvent>()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val nested = o.optJSONArray("alerts")
                if (nested != null) {
                    // מבנה היסטוריה: עוטף id/description עם רשימת alerts פנימית
                    val baseId = o.optString("id", "")
                    for (j in 0 until nested.length()) {
                        val a = nested.optJSONObject(j) ?: continue
                        if (!a.has("notificationId") && baseId.isNotEmpty()) a.put("notificationId", baseId)
                        out.add(fromJson(a))
                    }
                } else {
                    out.add(fromJson(o))
                }
            }
            return out
        }
    }
}
