package com.blackalert.app.net

import com.blackalert.app.data.AlertEvent
import java.net.HttpURLConnection
import java.net.URL

/**
 * לקוח קריאה-בלבד לשרת "צבע שחור". HttpURLConnection בלבד (ללא תלות חיצונית) —
 * חשוב למכשירים מנוהלים ללא Google Play Services.
 *
 * צורכים *רק* את ה-endpoints הציבוריים (ללא אימות): /notifications, /alerts-history,
 * /lists-versions, /static/cities.json. שום פנייה לצד הכתיבה/חמ"ל.
 */
object BlackAlertApi {
    const val DEFAULT_BASE = "https://black-alert.com"

    /**
     * בסיס ה-URL לפנייה. ניתן להחלפה בזמן ריצה (App/PollingService מגדירים מ-Prefs) כדי
     * לעקוף סינון תוכן: במכשיר מסונן מפנים ל-proxy/Cloud Function שמשקף את אותו JSON.
     * חייב לחשוף את אותם נתיבים: /notifications, /alerts-history, /lists-versions, /static/cities.json
     */
    @Volatile var base: String = DEFAULT_BASE
        set(value) { field = value.trim().trimEnd('/').ifEmpty { DEFAULT_BASE } }

    private const val TIMEOUT_MS = 12000

    data class ListsVersions(val cities: Int, val polygons: Int)

    /** GET /notifications → אירועים פעילים. זורק חריגה בכשל (ה-service מטפל ב-backoff). */
    fun fetchActiveNotifications(): List<AlertEvent> {
        val body = httpGet("$base/notifications")
        return AlertEvent.listFromResponse(body)
    }

    fun fetchHistory(): List<AlertEvent> {
        val body = httpGet("$base/alerts-history")
        return AlertEvent.listFromResponse(body)
    }

    fun fetchListsVersions(): ListsVersions {
        val o = org.json.JSONObject(httpGet("$base/lists-versions"))
        return ListsVersions(o.optInt("cities", 0), o.optInt("polygons", 0))
    }

    fun fetchCitiesJson(version: Int): String = httpGet("$base/static/cities.json?v=$version")

    /**
     * POST /api/arrived — דיווח הגעה לזירה. הצוות רואה את זה בממשק הניהול.
     * זורק חריגה בכשל (הקורא אחראי על toast/לוג).
     */
    fun reportArrival(eventType: Int, cities: List<String>, address: String, lat: Double?, lng: Double?) {
        val body = org.json.JSONObject().apply {
            put("event_type", eventType)
            put("cities", org.json.JSONArray(cities))
            put("address", address)
            if (lat != null && lng != null) { put("lat", lat); put("lng", lng) }
            put("timestamp", System.currentTimeMillis() / 1000)
        }.toString()
        httpPost("$base/api/arrived", body)
    }

    private fun httpPost(urlStr: String, jsonBody: String) {
        val conn = (java.net.URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "BlackAlertApp/1.0 (Android)")
        }
        try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code for $urlStr")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(urlStr: String): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "BlackAlertApp/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code for $urlStr")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
