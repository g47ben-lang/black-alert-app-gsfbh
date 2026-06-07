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

    /** אופן סינון הקרבה: "radius" = מרחק אווירי (ק"מ), "time" = זמן הגעה (דקות נסיעה). */
    var proximityMode: String
        get() = sp.getString("proximityMode", "radius") ?: "radius"
        set(v) = sp.edit { putString("proximityMode", v) }
    /** סף זמן נסיעה (דקות) במצב proximity לפי זמן. */
    var proximityTimeMin: Int
        get() = sp.getInt("proximityTimeMin", 15).coerceIn(1, 240)
        set(v) = sp.edit { putInt("proximityTimeMin", v.coerceIn(1, 240)) }

    /** הצגת זמני הגעה (נסיעה/הליכה/אופניים) בהתראה — דורש מיקום. */
    var travelTimesEnabled: Boolean
        get() = sp.getBoolean("travelTimesEnabled", true)
        set(v) = sp.edit { putBoolean("travelTimesEnabled", v) }

    /** Anti-spoof: התעלמות ממיקומי mock (מזויפים). */
    var ignoreMockLocation: Boolean
        get() = sp.getBoolean("ignoreMockLocation", true)
        set(v) = sp.edit { putBoolean("ignoreMockLocation", v) }

    // --- צליל / חיווי ---
    // soundName: שם raw מצורף (bell2/…) או "custom" כשנבחר צליל מהמכשיר.
    var soundName: String
        get() = sp.getString("soundName", "bell2") ?: "bell2"
        set(v) = sp.edit { putString("soundName", v) }

    /** URI של צליל מותאם אישית (כשנבחר מבורר הצלילים). ריק = אין. */
    var customSoundUri: String
        get() = sp.getString("customSoundUri", "") ?: ""
        set(v) = sp.edit { putString("customSoundUri", v) }

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit { putBoolean("vibrate", v) }

    /** רטט בלבד — ללא צליל. */
    var vibrateOnly: Boolean
        get() = sp.getBoolean("vibrateOnly", false)
        set(v) = sp.edit { putBoolean("vibrateOnly", v) }

    /** צלצול גם במצב "נא לא להפריע" (דורש גישת מדיניות התראות). ברירת מחדל כבוי. */
    var overrideDnd: Boolean
        get() = sp.getBoolean("overrideDnd", false)
        set(v) = sp.edit { putBoolean("overrideDnd", v) }

    /** האם המשתמש כבר עבר את מסך הבחירה הראשוני (אזורים) אחרי התקנה. */
    var firstRunDone: Boolean
        get() = sp.getBoolean("firstRunDone", false)
        set(v) = sp.edit { putBoolean("firstRunDone", v) }

    /** חלון מסך-מלא שמדליק את המסך ומציג מעל המסך הנעול. */
    var fullScreenAlert: Boolean
        get() = sp.getBoolean("fullScreenAlert", true)
        set(v) = sp.edit { putBoolean("fullScreenAlert", v) }

    /** האם כבר ביקשנו מהמשתמש את הרשאת המסך-המלא (כדי לא להציק שוב ושוב). */
    var fullScreenPermAsked: Boolean
        get() = sp.getBoolean("fullScreenPermAsked", false)
        set(v) = sp.edit { putBoolean("fullScreenPermAsked", v) }

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

    // --- MQTT (ערוץ push למכשירים ללא Google Play) ---
    // broker ריק = MQTT כבוי. דוגמה: "ssl://broker.example.com:8883" או "tcp://10.0.0.5:1883"
    var mqttBrokerUrl: String
        get() = sp.getString("mqttBrokerUrl", "") ?: ""
        set(v) = sp.edit { putString("mqttBrokerUrl", v.trim()) }
    var mqttTopic: String
        get() = sp.getString("mqttTopic", "alerts") ?: "alerts"
        set(v) = sp.edit { putString("mqttTopic", v.trim().ifEmpty { "alerts" }) }
    var mqttUsername: String
        get() = sp.getString("mqttUsername", "") ?: ""
        set(v) = sp.edit { putString("mqttUsername", v) }
    var mqttPassword: String
        get() = sp.getString("mqttPassword", "") ?: ""
        set(v) = sp.edit { putString("mqttPassword", v) }

    // --- ספירת התקנות/פעילים (heartbeat אנונימי) ---
    /** מזהה התקנה אקראי ואנונימי (ללא קשר לחומרה) — נוצר פעם אחת. */
    val installId: String
        get() {
            var id = sp.getString("installId", null)
            if (id.isNullOrEmpty()) {
                id = java.util.UUID.randomUUID().toString()
                sp.edit { putString("installId", id) }
            }
            return id
        }
    var lastHeartbeatMs: Long
        get() = sp.getLong("lastHeartbeatMs", 0L)
        set(v) = sp.edit { putLong("lastHeartbeatMs", v) }
    /** override ל-endpoint הספירה (לבדיקה). ריק = משתמשים בקבוע ב-Heartbeat. */
    var analyticsUrl: String
        get() = sp.getString("analyticsUrl", "") ?: ""
        set(v) = sp.edit { putString("analyticsUrl", v.trim()) }

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
