package com.blackalert.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * הגדרות המשתמש — מקבילה ל-Preferences.js בתוסף.
 * סינון לפי ערים/אזורים/סוגי-אירוע, צליל, שעות שקטות, והיסטוריה מקומית.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("black_alert_prefs", Context.MODE_PRIVATE)

    // --- סינון ---
    var selectedCityIds: Set<Int>
        get() = readIntSet("selectedCities")
        set(v) = writeIntSet("selectedCities", v)

    var selectedAreaIds: Set<Int>
        get() = readIntSet("selectedAreas")
        set(v) = writeIntSet("selectedAreas", v)

    /** ריק = כל הסוגים. אחרת רק הסוגים שנבחרו (0,2,3,8). */
    var selectedEventTypes: Set<Int>
        get() = readIntSet("selectedEventTypes")
        set(v) = writeIntSet("selectedEventTypes", v)

    /** אם true: מציג גם אזורים שלא נבחרו אך ללא צליל (כמו silentNotSelected בתוסף). */
    var silentNotSelected: Boolean
        get() = sp.getBoolean("silentNotSelected", false)
        set(v) = sp.edit { putBoolean("silentNotSelected", v) }

    /** מצב "התראה לפי קרבה" — מצלצל רק על אירוע במרחק radiusKm מהמיקום הנוכחי. */
    var proximityEnabled: Boolean
        get() = sp.getBoolean("proximityEnabled", false)
        set(v) = sp.edit { putBoolean("proximityEnabled", v) }

    var proximityRadiusKm: Int
        get() = sp.getInt("proximityRadiusKm", 25)
        set(v) = sp.edit { putInt("proximityRadiusKm", v) }

    // --- צליל / חיווי ---
    var soundName: String
        get() = sp.getString("soundName", "bell2") ?: "bell2"
        set(v) = sp.edit { putString("soundName", v) }

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit { putBoolean("vibrate", v) }

    /** חלון מסך-מלא שמדליק את המסך ומציג מעל המסך הנעול. */
    var fullScreenAlert: Boolean
        get() = sp.getBoolean("fullScreenAlert", true)
        set(v) = sp.edit { putBoolean("fullScreenAlert", v) }

    // --- שעות שקטות ---
    var quietEnabled: Boolean
        get() = sp.getBoolean("quietEnabled", false)
        set(v) = sp.edit { putBoolean("quietEnabled", v) }
    var quietFrom: String
        get() = sp.getString("quietFrom", "23:00") ?: "23:00"
        set(v) = sp.edit { putString("quietFrom", v) }
    var quietTo: String
        get() = sp.getString("quietTo", "07:00") ?: "07:00"
        set(v) = sp.edit { putString("quietTo", v) }

    // --- היסטוריה מקומית ---
    /** האם לתעד מקומית כל גרסה שנצפתה (לשמירת עריכות שהשרת דורס). */
    var localHistoryEnabled: Boolean
        get() = sp.getBoolean("localHistoryEnabled", true)
        set(v) = sp.edit { putBoolean("localHistoryEnabled", v) }

    // --- מצב שירות ---
    var serviceEnabled: Boolean
        get() = sp.getBoolean("serviceEnabled", true)
        set(v) = sp.edit { putBoolean("serviceEnabled", v) }

    // --- בדיקת עדכונים ---
    var lastUpdateCheckMs: Long
        get() = sp.getLong("lastUpdateCheckMs", 0L)
        set(v) = sp.edit { putLong("lastUpdateCheckMs", v) }
    /** ה-tag שכבר הוצג למשתמש (כדי לא להציק על אותה גרסה שוב ושוב). */
    var dismissedUpdateTag: String
        get() = sp.getString("dismissedUpdateTag", "") ?: ""
        set(v) = sp.edit { putString("dismissedUpdateTag", v) }

    // --- מצב מסירה (failover: push בגוגל / בדיקה ידנית) ---
    // "auto" = push אם זמין (Play Services + Firebase מוגדר), אחרת polling.
    // "push" = כפה push.  "poll" = כפה בדיקה ידנית (תמיד עובד, גם בלי Play Services).
    var deliveryMode: String
        get() = sp.getString("deliveryMode", "auto") ?: "auto"
        set(v) = sp.edit { putString("deliveryMode", v) }

    /** במצב push — כל כמה דקות בכל זאת לבדוק כ-safety-net (מניעת כשל אם push לא הגיע). 0=כבוי. */
    var safetyPollMinutes: Int
        get() = sp.getInt("safetyPollMinutes", 15).coerceIn(0, 240)
        set(v) = sp.edit { putInt("safetyPollMinutes", v.coerceIn(0, 240)) }

    // --- תדירות בדיקה אדפטיבית (חיסכון סוללה) ---
    // מסך דולק = תגובה מהירה; מסך כבוי = מרווח ארוך יותר (ו-Doze ממילא מאט בשינה עמוקה).
    var pollOnSec: Int
        get() = sp.getInt("pollOnSec", 10).coerceIn(5, 120)
        set(v) = sp.edit { putInt("pollOnSec", v.coerceIn(5, 120)) }
    var pollOffSec: Int
        get() = sp.getInt("pollOffSec", 30).coerceIn(10, 300)
        set(v) = sp.edit { putInt("pollOffSec", v.coerceIn(10, 300)) }

    // --- מקור נתונים (מעבר מסננים: NetFree עלול לחסום פנייה ישירה ל-black-alert) ---
    // ברירת מחדל: פנייה ישירה. אפשר להפנות ל-proxy/Cloud Function שמשקף את אותו JSON
    // (למשל firestore.googleapis.com ברשימה לבנה) במכשירים מסוננים.
    var sourceBaseUrl: String
        get() = sp.getString("sourceBaseUrl", "https://black-alert.com") ?: "https://black-alert.com"
        set(v) = sp.edit { putString("sourceBaseUrl", v.trim().trimEnd('/')) }

    /** האם הזמן הנוכחי נמצא בטווח ההשתקה (תומך בטווח שחוצה חצות). */
    fun isWithinQuietHours(hour: Int, minute: Int): Boolean {
        if (!quietEnabled) return false
        fun toMin(hhmm: String): Int {
            val parts = hhmm.split(":")
            return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        val from = toMin(quietFrom); val to = toMin(quietTo); val cur = hour * 60 + minute
        if (from == to) return false
        return if (from < to) cur in from until to else cur >= from || cur < to
    }

    private fun readIntSet(key: String): Set<Int> {
        val raw = sp.getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw); (0 until arr.length()).map { arr.getInt(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun writeIntSet(key: String, value: Set<Int>) {
        val arr = JSONArray(); value.forEach { arr.put(it) }
        sp.edit { putString(key, arr.toString()) }
    }
}
