package com.blackalert.app.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.blackalert.app.data.AlertEvent
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.EventHistory
import com.blackalert.app.data.HistoryStore
import com.blackalert.app.data.ServerStateCache
import com.blackalert.app.net.BlackAlertApi
import kotlin.concurrent.thread

/**
 * היסטוריה — שתי לשוניות:
 * 1. "מקומי" — log append-only מקומי, כולל עריכות וסגירות, לעולם לא נמחק
 * 2. "שרת"   — מצב השרת כרגע; כשאין חיבור — מציג את המטמון מהחיבור האחרון
 */
class HistoryActivity : AppCompatActivity() {

    private val bg      = 0xFF121216.toInt()
    private val surface = 0xFF23242C.toInt()
    private val onSurface = 0xFFE7E7EC.toInt()
    private val onVar   = 0xFFA8AAB5.toInt()
    private val primary = 0xFFFFD500.toInt()

    private lateinit var container: LinearLayout
    private lateinit var repo: CitiesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "היסטוריית התראות"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = CitiesRepository.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // לשוניות
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 20, 24, 8)
        }
        val btnLocal  = tabButton("מקומי (כולל עריכות)")
        val btnServer = tabButton("שרת (כולל מחוקים)")
        tabs.addView(btnLocal)
        tabs.addView(btnServer)
        root.addView(tabs)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 40)
        }
        root.addView(NestedScrollView(this).apply {
            addView(container)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        })

        btnLocal.setOnClickListener  { selectTab(btnLocal, btnServer); showLocal()  }
        btnServer.setOnClickListener { selectTab(btnServer, btnLocal); showServer() }

        selectTab(btnLocal, btnServer)
        showLocal()
        setContentView(root)
    }

    private fun tabButton(label: String) = Button(this).apply {
        text = label; isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun selectTab(active: Button, other: Button) {
        active.setBackgroundColor(primary); active.setTextColor(0xFF1F1C00.toInt())
        other.setBackgroundColor(surface);  other.setTextColor(onSurface)
    }

    // ── מקומי ──────────────────────────────────────────────────────────────
    private fun showLocal() {
        container.removeAllViews()
        val groups = HistoryStore(this).groupedByEvent()
        if (groups.isEmpty()) {
            container.addView(emptyText(
                "אין עדיין היסטוריה מקומית.\n" +
                "התיעוד מתחיל אוטומטית כשמגיעות התראות (אם מופעל בהגדרות)."
            ))
            return
        }
        groups.forEach { container.addView(localCard(it)) }
    }

    // ── שרת ────────────────────────────────────────────────────────────────
    // מנסה לטעון מהשרת → שומר במטמון → אם נכשל, טוען מהמטמון
    private fun showServer() {
        container.removeAllViews()
        val spinner = ProgressBar(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = 60 }
            layoutParams = lp
            indeterminateTintList = android.content.res.ColorStateList.valueOf(primary)
        }
        container.addView(spinner)
        thread {
            var fromCache = false
            val events = try {
                val fetched = BlackAlertApi.fetchHistory()
                ServerStateCache.save(this, fetched)
                fetched
            } catch (e: Exception) {
                fromCache = true
                ServerStateCache.load(this)
            }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                container.removeAllViews()
                if (fromCache) {
                    container.addView(infoText("⚠ אין חיבור לשרת — מציג נתונים מהחיבור האחרון"))
                }
                if (events.isEmpty()) {
                    container.addView(emptyText(
                        if (fromCache) "אין נתונים שמורים מחיבור קודם."
                        else "אין היסטוריה מהשרת."
                    ))
                    return@runOnUiThread
                }
                events.sortedByDescending { it.time }.forEach { container.addView(serverCard(it)) }
            }
        }
    }

    private fun emptyText(msg: String) = TextView(this).apply {
        text = msg; setTextColor(onVar); setPadding(8, 50, 8, 0); textSize = 15f
    }

    private fun infoText(msg: String) = TextView(this).apply {
        text = msg; setTextColor(0xFFFFAA00.toInt()); setPadding(8, 16, 8, 8); textSize = 13f
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 22, 28, 22)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 0, 0, 20) }
        layoutParams = lp
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(surface); cornerRadius = 32f
        }
    }

    private fun typeLabel(t: Int) = when (t) {
        0 -> "נסיון מעצר מ.צ."; 2 -> "התרעת מחסומים"; 3 -> "נסיון הסגרה"; else -> "התראה"
    }

    private fun typeColor(t: Int) = when (t) {
        3 -> 0xFFFF8000.toInt(); 0, 2 -> primary; else -> 0xFFC9CBD6.toInt()
    }

    private fun header(c: LinearLayout, ev: AlertEvent) {
        val cityNames = ev.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        c.addView(TextView(this).apply {
            text = "${typeLabel(ev.eventType)} — $cityNames"; textSize = 17f
            setTextColor(typeColor(ev.eventType))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        c.addView(TextView(this).apply {
            text = DateUtils.getRelativeTimeSpanString(ev.time * 1000).toString() +
                (if (ev.address.isNotBlank()) "  ·  📍 ${ev.address}" else "")
            setTextColor(onVar); setPadding(0, 6, 0, 4); textSize = 13f
        })
    }

    private fun openDetail(ev: AlertEvent) {
        val cityNames = ev.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        var lat = ev.lat; var lng = ev.lng
        if (lat == null || lng == null) {
            ev.cities.firstNotNullOfOrNull { repo.cityByKey(it) }?.let { lat = it.lat; lng = it.lng }
        }
        val i = android.content.Intent(this, AlertActivity::class.java).apply {
            putExtra(AlertActivity.EXTRA_VIEW_ONLY, true)
            putExtra(AlertActivity.EXTRA_WITH_SOUND, false)
            putExtra(AlertActivity.EXTRA_TITLE, typeLabel(ev.eventType))
            putExtra(AlertActivity.EXTRA_CITIES, cityNames)
            putExtra(AlertActivity.EXTRA_ADDRESS, ev.address)
            putExtra(AlertActivity.EXTRA_NOTE, ev.note)
            putExtra(AlertActivity.EXTRA_EVENT_TYPE, ev.eventType)
            if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
                putExtra(AlertActivity.EXTRA_LAT, lat!!)
                putExtra(AlertActivity.EXTRA_LNG, lng!!)
                putExtra(AlertActivity.EXTRA_LABEL, if (ev.address.isNotBlank()) "$cityNames, ${ev.address}" else cityNames)
            }
        }
        startActivity(i)
    }

    private fun localCard(g: EventHistory): View {
        val c = card(); header(c, g.latest)
        c.setOnClickListener { openDetail(g.latest) }
        val badges = buildString {
            if (g.wasEdited) append("✏ נערך ${g.versions.size} פעמים   ")
            if (g.wasClosed) append("✓ הסתיים")
        }
        if (badges.isNotBlank()) c.addView(TextView(this).apply {
            text = badges; setTextColor(onVar); textSize = 12f
        })
        g.versions.forEach { v ->
            if (v.note.isBlank() && !v.isClosed) return@forEach
            c.addView(TextView(this).apply {
                val tag = if (v.isClosed) "[סגירה]" else "v${v.version}"
                text = "• $tag: ${v.note.ifBlank { "(ללא טקסט)" }}"
                setPadding(16, 8, 0, 0); textSize = 14f; setTextColor(onSurface)
            })
        }
        return c
    }

    private fun serverCard(ev: AlertEvent): View {
        val c = card(); header(c, ev)
        c.setOnClickListener { openDetail(ev) }
        if (ev.note.isNotBlank()) c.addView(TextView(this).apply {
            text = ev.note; setPadding(0, 6, 0, 0); textSize = 14f; setTextColor(onSurface)
            setLineSpacing(0f, 1.3f)
        })
        return c
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
