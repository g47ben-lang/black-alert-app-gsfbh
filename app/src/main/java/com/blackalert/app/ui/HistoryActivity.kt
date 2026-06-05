package com.blackalert.app.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.EventHistory
import com.blackalert.app.data.HistoryStore

/**
 * מסך היסטוריה מקומית — מציג אירועים מקובצים, וכשאירוע נערך בשרת מציג את *שרשרת הגרסאות*
 * (כל עריכה שנשמרה מקומית), כך שעריכות שהשרת דרס עדיין נראות.
 */
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "היסטוריית התראות (מקומית)"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val repo = CitiesRepository.get(this)
        val groups = HistoryStore(this).groupedByEvent()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 40)
        }

        if (groups.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "אין עדיין היסטוריה מקומית.\nהתיעוד מתחיל אוטומטית כשמגיעות התראות (אם האפשרות מופעלת בהגדרות)."
                setPadding(0, 40, 0, 0)
            })
        }

        groups.forEach { g -> root.addView(buildCard(g, repo)) }

        setContentView(NestedScrollView(this).apply { addView(root) })
    }

    private fun buildCard(g: EventHistory, repo: CitiesRepository): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 24); layoutParams = lp
            setBackgroundColor(0xFFF3F3F3.toInt())
        }
        val latest = g.latest
        val cityNames = latest.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        val type = when (latest.eventType) {
            0 -> "נסיון מעצר מ.צ."; 2 -> "התרעת מחסומים"; 3 -> "נסיון הסגרה"; else -> "התראה"
        }

        card.addView(TextView(this).apply {
            text = "$type — $cityNames"; textSize = 17f
            setTextColor(0xFFB71C1C.toInt())
        })
        card.addView(TextView(this).apply {
            val rel = DateUtils.getRelativeTimeSpanString(latest.time * 1000)
            text = rel.toString() + (if (latest.address.isNotBlank()) "  ·  📍 ${latest.address}" else "")
            setPadding(0, 6, 0, 6)
        })

        val badges = buildString {
            if (g.wasEdited) append("✏ נערך ${g.versions.size} פעמים   ")
            if (g.wasClosed) append("✓ הסתיים")
        }
        if (badges.isNotBlank()) card.addView(TextView(this).apply {
            text = badges; setTextColor(0xFF555555.toInt()); textSize = 13f
        })

        // שרשרת הגרסאות (העריכות) — מה שהשרת עלול לדרוס
        if (g.wasEdited || g.versions.any { it.note.isNotBlank() }) {
            g.versions.forEach { v ->
                if (v.note.isBlank() && !v.isClosed) return@forEach
                card.addView(TextView(this).apply {
                    val tag = if (v.isClosed) "[סגירה]" else "v${v.version}"
                    text = "• $tag: ${v.note.ifBlank { "(ללא טקסט)" }}"
                    setPadding(16, 8, 0, 0); textSize = 14f
                    setTextColor(0xFF333333.toInt())
                })
            }
        }
        return card
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
