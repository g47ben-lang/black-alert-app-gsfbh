package com.blackalert.app.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** זמני הגעה ליעד לפי אופן תנועה. דקות; -1 = לא ידוע. */
data class TravelInfo(
    val driveMin: Int,
    val walkMin: Int,
    val bikeMin: Int,
    val km: Double
) {
    val hasAny: Boolean get() = driveMin >= 0 || walkMin >= 0 || bikeMin >= 0
    // הליכה/אופניים רלוונטיים רק אם קרוב — מוצגים רק מתחת לחצי שעה.
    val showWalk: Boolean get() = walkMin in 0 until SHORT_MAX_MIN
    val showBike: Boolean get() = bikeMin in 0 until SHORT_MAX_MIN

    companion object { const val SHORT_MAX_MIN = 30 }
}

/**
 * חישוב זמני הגעה דרך Valhalla (מנוע ניתוב OSM — אותו שירות שהתוסף המקורי השתמש בו).
 * 3 בקשות מקבילות (auto/pedestrian/bicycle). בכשל/timeout — נפילה לאומדן לפי מרחק אווירי ומהירות.
 *
 * הקריאה חוסמת — יש להריץ מחוץ ל-main thread.
 */
object TravelTime {
    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"
    private const val TIMEOUT_MS = 6000

    fun compute(originLat: Double, originLng: Double, destLat: Double, destLng: Double): TravelInfo {
        val results = HashMap<String, Pair<Int, Double>?>()  // costing → (minutes, km)
        val modes = listOf("auto", "pedestrian", "bicycle")
        val threads = modes.map { mode ->
            Thread {
                val r = runCatching { valhalla(mode, originLat, originLng, destLat, destLng) }.getOrNull()
                synchronized(results) { results[mode] = r }
            }.apply { start() }
        }
        threads.forEach { runCatching { it.join((TIMEOUT_MS + 500).toLong()) } }

        // אומדן גיבוי אם מנוע הניתוב לא ענה
        val air = haversineKm(originLat, originLng, destLat, destLng)
        fun pick(mode: String, kmh: Double): Pair<Int, Double> {
            results[mode]?.let { return it }
            val km = air * 1.3 // תיקון מקורב מאווירי לכביש
            return Math.max(1, Math.round(km / kmh * 60).toInt()) to km
        }
        val drive = pick("auto", 30.0)
        val walk = pick("pedestrian", 5.0)
        val bike = pick("bicycle", 16.0)
        return TravelInfo(drive.first, walk.first, bike.first, drive.second)
    }

    private fun valhalla(costing: String, oLat: Double, oLng: Double, dLat: Double, dLng: Double): Pair<Int, Double>? {
        val body = JSONObject().apply {
            put("locations", org.json.JSONArray().apply {
                put(JSONObject().put("lat", oLat).put("lon", oLng))
                put(JSONObject().put("lat", dLat).put("lon", dLng))
            })
            put("costing", costing)
            put("directions_options", JSONObject().put("units", "kilometers"))
        }.toString()

        val conn = (URL(VALHALLA).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "BlackAlertApp/1.0")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val summary = JSONObject(resp).optJSONObject("trip")?.optJSONObject("summary") ?: return null
            val seconds = summary.optDouble("time", -1.0)
            val km = summary.optDouble("length", -1.0)
            if (seconds < 0) return null
            return Math.max(1, Math.round(seconds / 60.0).toInt()) to km
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
