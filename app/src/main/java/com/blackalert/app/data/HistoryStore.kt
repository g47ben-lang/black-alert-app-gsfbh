package com.blackalert.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * היסטוריה מקומית append-only.
 *
 * כל גרסה שנצפתה ב-polling נשמרת כשורת JSON (JSONL). מכיוון שהשרת *עורך* פרסומים
 * (version עולה, note מתעדכן, status הופך ל-closed), שמירה לפי notificationId:version
 * מנציחה את שרשרת העריכות המלאה — גם אם השרת ידרוס/ימחק את הגרסה הקודמת.
 *
 * append-only ⇒ עמיד, ללא תלות (אין צורך ב-Room/SQLite).
 */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val maxLines = 5000

    /** שומר גרסה אם היא חדשה (notificationId:version שלא נראה). מחזיר true אם נכתבה. */
    @Synchronized
    fun record(event: AlertEvent): Boolean {
        if (seenKeys.contains(event.dedupKey)) return false
        val stamped = event.copy(observedAt = System.currentTimeMillis() / 1000)
        file.appendText(stamped.toJson().toString() + "\n", Charsets.UTF_8)
        seenKeys.add(event.dedupKey)
        return true
    }

    /** כל הגרסאות שנשמרו, מהחדש לישן. */
    @Synchronized
    fun all(): List<AlertEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null else runCatching { AlertEvent.fromJson(JSONObject(line)) }.getOrNull()
        }.reversed()
    }

    /**
     * מקובץ לפי אירוע (notificationId) — לכל אירוע רשימת גרסאותיו לפי הסדר,
     * כך שמסך ההיסטוריה יכול להציג "נערך 3 פעמים" ואת ההבדלים.
     */
    @Synchronized
    fun groupedByEvent(): List<EventHistory> {
        val byId = LinkedHashMap<String, MutableList<AlertEvent>>()
        all().forEach { ev ->
            byId.getOrPut(ev.notificationId) { mutableListOf() }.add(ev)
        }
        return byId.map { (id, versions) ->
            val sorted = versions.sortedBy { it.version }
            EventHistory(
                notificationId = id,
                versions = sorted,
                latest = sorted.last()
            )
        }.sortedByDescending { it.latest.observedAt }
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
        seenKeys.clear()
    }

    private val seenKeys: MutableSet<String> by lazy {
        val s = HashSet<String>()
        if (file.exists()) {
            file.forEachLine { line ->
                runCatching {
                    val o = JSONObject(line)
                    s.add(o.optString("notificationId") + ":" + o.optInt("version", 1))
                }
            }
        }
        s
    }

    companion object {
        private const val FILE_NAME = "history.jsonl"
    }
}

/** אירוע אחד עם כל גרסאותיו (שרשרת עריכות). */
data class EventHistory(
    val notificationId: String,
    val versions: List<AlertEvent>,
    val latest: AlertEvent
) {
    val wasEdited: Boolean get() = versions.size > 1
    val wasClosed: Boolean get() = versions.any { it.isClosed }
}
