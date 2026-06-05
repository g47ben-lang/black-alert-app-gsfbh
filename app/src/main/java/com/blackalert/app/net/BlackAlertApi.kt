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
