package com.blackalert.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * שומר את המצב האחרון שהגיע מהשרת (alerts-history).
 * כשהמכשיר לא מחובר — מחזיר את הגרסה השמורה.
 * כשהשרת מוחק התראה — היא נעלמת מהמטמון בעדכון הבא.
 */
object ServerStateCache {
    private const val FILE = "server_state.json"

    fun save(context: Context, events: List<AlertEvent>) {
        val arr = JSONArray()
        events.forEach { arr.put(it.toJson()) }
        File(context.filesDir, FILE).writeText(arr.toString(), Charsets.UTF_8)
    }

    fun load(context: Context): List<AlertEvent> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull {
                runCatching { AlertEvent.fromJson(arr.getJSONObject(it)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
