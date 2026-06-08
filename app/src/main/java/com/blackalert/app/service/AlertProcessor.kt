package com.blackalert.app.service

import android.content.Context
import android.location.Location
import com.blackalert.app.data.AlertEvent
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.HistoryStore
import com.blackalert.app.data.Prefs
import java.util.Calendar

/** ההחלטה מה לעשות עם אירוע שהגיע מה-polling. */
sealed class AlertDecision {
    /** מתריע — withSound קובע אם לצלצל (אחרת חיווי שקט). */
    data class Alert(val event: AlertEvent, val withSound: Boolean, val target: NavTarget?) : AlertDecision()
    /** האירוע נסגר ע"י החמ"ל — לבטל את ההתראה הפעילה. */
    data class Close(val event: AlertEvent) : AlertDecision()
    /** מסונן/כפול/לא רלוונטי — להתעלם. */
    object Ignore : AlertDecision()
}

/** יעד ניווט מחושב (מיקום מדויק או מרכז-עיר). */
data class NavTarget(val lat: Double, val lng: Double, val label: String)

/**
 * הלב הלוגי — מקביל ל-getAlerts() ב-background.js:
 * dedup לפי notificationId:version, טיפול בסגירה, סינון לפי ערים/אזורים/סוגים/קרבה/שעות-שקטות,
 * ותיעוד היסטוריה מקומית (כל גרסה, כולל עריכות).
 */
class AlertProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val cities = CitiesRepository.get(appContext)
    private val history = HistoryStore(appContext)

    // dedup חוצה-סבבים (notificationId:version), חלון אחרון כמו בתוסף
    private val seen = LinkedHashSet<String>()
    private val maxSeen = 200

    /** מעבד מערך אירועים פעילים מסבב poll יחיד. */
    fun process(events: List<AlertEvent>): List<AlertDecision> =
        events.map { decide(it) }.filterNot { it is AlertDecision.Ignore }

    private fun decide(event: AlertEvent): AlertDecision {
        // היסטוריה מקומית — נרשמת לפני הסינון: גם אירוע שלא מצלצל אצלי נשמר עם עריכותיו.
        if (prefs.localHistoryEnabled) history.record(event)

        if (seen.contains(event.dedupKey)) return AlertDecision.Ignore
        seen.add(event.dedupKey)
        if (seen.size > maxSeen) seen.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }

        if (event.isClosed) return AlertDecision.Close(event)

        // --- סינון לפי בחירת המשתמש (מקביל ל-alertCities.filter בתוסף) ---
        val selCities = prefs.selectedCityIds
        val selAreas = prefs.selectedAreaIds
        val selTypes = prefs.selectedEventTypes
        // "כל הארץ": אין בחירה כלל — או שהמשתמש סימן את *כל* האזורים הזמינים
        // (כפתור "סמן הכל" מאחסן את כל מזהי האזורים). בלי זה, אירוע בעיר שאינה
        // במאגר המקומי / ללא מיפוי אזור היה נופל למרות ש"כל האזורים" מסומנים.
        val allAreaIds = cities.areas.keys
        val pickedAllAreas = allAreaIds.isNotEmpty() && selAreas.containsAll(allAreaIds)
        val selectAll = (selCities.isEmpty() && selAreas.isEmpty()) || pickedAllAreas

        if (selTypes.isNotEmpty() && !selTypes.contains(event.eventType)) return AlertDecision.Ignore

        // האם אחת מערי האירוע נכללת בבחירה המפורשת של המשתמש
        val matchedSelection = event.cities.any { cityKey ->
            val info = cities.cityByKey(cityKey)
            info != null && (selCities.contains(info.id) || selAreas.contains(info.areaId))
        }

        // האם *כל* ערי האירוע אינן מזוהות במאגר (עיר חדשה/חופשית/הודעת מערכת).
        // fail-open: מתריעים בכל מקרה — עדיף התראה מיותרת מאשר לפספס התראת אמת.
        val allCitiesUnknown = event.cities.isNotEmpty() && event.cities.all { cityKey ->
            cities.cityByKey(cityKey) == null
        }
        // אירוע ללא ערים כלל (הודעת מערכת/טקסט חופשי) — גם הוא תמיד עובר.
        val noCities = event.cities.isEmpty()

        val target = resolveTarget(event)

        // סינון לפי קרבה (מצב אופציונלי) — גובר על בחירת ערים אם הופעל
        if (prefs.proximityEnabled) {
            val near = isWithinRadius(target)
            if (near == false) return AlertDecision.Ignore
            // near==null (אין מיקום/קואורדינטות) ⇒ לא מסננים, מתריעים ליתר ביטחון
        } else {
            // עיר לא מזוהה / ללא עיר ⇒ תמיד עובר (fail-open). אחרת — לפי בחירה.
            if (!selectAll && !matchedSelection && !allCitiesUnknown && !noCities) {
                if (!prefs.silentNotSelected) return AlertDecision.Ignore
            }
        }

        // --- האם לצלצל ---
        val cal = Calendar.getInstance()
        val quiet = prefs.isWithinQuietHours(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val silentBecauseNotSelected = prefs.silentNotSelected && !matchedSelection && !selectAll &&
            !allCitiesUnknown && !noCities && !prefs.proximityEnabled
        val withSound = !event.silent && !quiet && !silentBecauseNotSelected

        return AlertDecision.Alert(event, withSound, target)
    }

    /** מיקום מדויק מהאירוע, אחרת מרכז-העיר הראשונה מ-cities.json. */
    private fun resolveTarget(event: AlertEvent): NavTarget? {
        val label = buildString {
            append(event.cities.firstOrNull() ?: "")
            if (event.address.isNotBlank()) { if (isNotEmpty()) append(", "); append(event.address) }
        }.ifBlank { event.cities.joinToString(", ") }

        if (event.lat != null && event.lng != null && (event.lat != 0.0 || event.lng != 0.0)) {
            return NavTarget(event.lat, event.lng, label)
        }
        for (cityKey in event.cities) {
            val info = cities.cityByKey(cityKey) ?: continue
            if (info.lat != 0.0 || info.lng != 0.0) return NavTarget(info.lat, info.lng, label.ifBlank { info.he })
        }
        return null
    }

    /**
     * האם האירוע "קרוב" לפי הגדרת המשתמש: מרחק אווירי (ק"מ) או זמן נסיעה (דקות).
     * מחזיר true/false, או null אם אי-אפשר לקבוע (אין מיקום) → המסנן לא חוסם (fail-open).
     */
    private fun isWithinRadius(target: NavTarget?): Boolean? {
        if (target == null) return null
        val last = LocationProvider.lastKnown(appContext) ?: return null
        val results = FloatArray(1)
        Location.distanceBetween(last.first, last.second, target.lat, target.lng, results)
        val airKm = results[0] / 1000.0

        if (prefs.proximityMode == "time") {
            // קדם-סינון אווירי כדי לא לבזבז ניתוב על אירוע רחוק בעליל (~80 קמ"ש מקס')
            if (airKm > prefs.proximityTimeMin * 2.0) return false
            val info = com.blackalert.app.util.TravelTime.compute(last.first, last.second, target.lat, target.lng)
            if (info.driveMin < 0) return null // ניתוב נכשל → fail-open (עדיף להתריע מאשר לפספס)
            return info.driveMin <= prefs.proximityTimeMin
        }
        return airKm <= prefs.proximityRadiusKm
    }
}
