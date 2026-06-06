package com.blackalert.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.blackalert.app.data.Prefs

/**
 * ספק מיקום חזק — ללא Play Services (LocationManager בלבד).
 *
 * עקרונות (לבקשת המשתמש — "ללא זיופים ונפילות"):
 *  • Anti-spoof: מתעלם ממיקומי mock (isFromMockProvider) כברירת מחדל.
 *  • טריות: בוחר את המיקום הכי עדכני/מדויק מכל הספקים; דוחה ישן מדי.
 *  • עמידות: כל קריאה ב-try/catch; חוסר הרשאה/ספק → null (לעולם לא קורס).
 */
object LocationProvider {

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Float, val ageMs: Long, val mock: Boolean)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** המיקום הטוב ביותר (טרי, מדויק, לא-mock) או null. maxAgeMs=0 → ללא הגבלת גיל. */
    fun best(context: Context, maxAgeMs: Long = 10 * 60 * 1000L): Fix? {
        if (!hasPermission(context)) return null
        val rejectMock = Prefs(context).ignoreMockLocation
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var bestLoc: Location? = null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (p in providers) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { return null } catch (_: Exception) { null }
            loc ?: continue
            if (rejectMock && isMock(loc)) continue
            if (bestLoc == null || isBetter(loc, bestLoc!!)) bestLoc = loc
        }
        val b = bestLoc ?: return null
        val age = ageMs(b)
        if (maxAgeMs > 0 && age > maxAgeMs) return null  // ישן מדי — לא אמין
        return Fix(b.latitude, b.longitude, if (b.hasAccuracy()) b.accuracy else 9999f, age, isMock(b))
    }

    /** תאימות לאחור — Pair<lat,lng> או null. */
    fun lastKnown(context: Context): Pair<Double, Double>? = best(context, 0L)?.let { it.lat to it.lng }

    private fun isMock(loc: Location): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock
        else @Suppress("DEPRECATION") loc.isFromMockProvider
    } catch (_: Exception) { false }

    private fun ageMs(loc: Location): Long {
        return try {
            val elapsedNanos = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos
            elapsedNanos / 1_000_000L
        } catch (_: Exception) { Long.MAX_VALUE }
    }

    /** עדכני יותר משמעותית, או מדויק יותר באותו טווח זמן. */
    private fun isBetter(candidate: Location, current: Location): Boolean {
        val dt = ageMs(current) - ageMs(candidate) // חיובי = candidate חדש יותר
        if (dt > 2 * 60 * 1000) return true
        if (dt < -2 * 60 * 1000) return false
        val accDelta = (if (candidate.hasAccuracy()) candidate.accuracy else 9999f) -
            (if (current.hasAccuracy()) current.accuracy else 9999f)
        return accDelta < 0 // candidate מדויק יותר
    }
}
