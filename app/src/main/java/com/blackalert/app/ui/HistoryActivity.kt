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
import com.blackalert.app.data.Prefs
import com.blackalert.app.data.ServerStateCache
import com.blackalert.app.net.BlackAlertApi
import com.blackalert.app.service.LocationProvider
import kotlin.concurrent.thread
import kotlin.math.*

class HistoryActivity : AppCompatActivity() {

    private val bg      = 0xFF0D0D12.toInt()
    private val surface = 0xFF1C1D25.toInt()
    private val surface2 = 0xFF26273100.toInt()
    private val onSurface = 0xFFE7E7EC.toInt()
    private val onVar   = 0xFF9A9CAA.toInt()
    private val primary = 0xFFFFD500.toInt()
    private val green   = 0xFF2E7D32.toInt()
    private val orange  = 0xFFE65100.toInt()

    private lateinit var container: LinearLayout
    private lateinit var repo: CitiesRepository
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "היסטוריית התראות"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        repo = CitiesRepository.get(this)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // לשוניות
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(4))
            setBackgroundColor(bg)
        }
        val btnLocal  = tabButton("📱 מקומי")
        val btnServer = tabButton("🌐 שרת")
        tabs.addView(btnLocal)
        tabs.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        })
        tabs.addView(btnServer)
        root.addView(tabs)

        // קו הפרדה
        root.addView(android.view.View(this).apply {
            setBackgroundColor(0xFF2A2B35.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        root.addView(NestedScrollView(this).apply {
            addView(container)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
        })

        btnLocal.setOnClickListener  { selectTab(btnLocal, btnServer); showLocal()  }
        btnServer.setOnClickListener { selectTab(btnServer, btnLocal); showServer() }

        selectTab(btnLocal, btnServer)
        showLocal()
        setContentView(root)
    }

    private fun tabButton(label: String) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
        setPadding(dp(20), 0, dp(20), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(surface); cornerRadius = dp(22).toFloat()
        }
        setTextColor(onSurface)
    }

    private fun selectTab(active: Button, other: Button) {
        (active.background as? android.graphics.drawable.GradientDrawable)?.setColor(primary)
        active.setTextColor(0xFF1A1800.toInt())
        (other.background as? android.graphics.drawable.GradientDrawable)?.setColor(surface)
        other.setTextColor(onSurface)
    }

    // ── מקומי ──────────────────────────────────────────────────────────────
    private fun showLocal() {
        container.removeAllViews()
        val groups = HistoryStore(this).groupedByEvent()
        if (groups.isEmpty()) {
            container.addView(emptyText(
                "אין עדיין היסטוריה מקומית.\nהתיעוד מתחיל אוטומטית כשמגיעות התראות."
            ))
            return
        }
        groups.forEach { container.addView(localCard(it)) }
    }

    // ── שרת ────────────────────────────────────────────────────────────────
    private fun showServer() {
        container.removeAllViews()
        val spinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(60) }
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
                if (fromCache) container.addView(infoText("⚠ אין חיבור — מציג נתונים מהחיבור האחרון"))
                if (events.isEmpty()) {
                    container.addView(emptyText(if (fromCache) "אין נתונים שמורים." else "אין היסטוריה מהשרת."))
                    return@runOnUiThread
                }
                events.sortedByDescending { it.time }.forEach { container.addView(serverCard(it)) }
            }
        }
    }

    private fun emptyText(msg: String) = TextView(this).apply {
        text = msg; setTextColor(onVar); gravity = Gravity.CENTER
        setPadding(dp(16), dp(60), dp(16), 0); textSize = 15f; setLineSpacing(0f, 1.4f)
    }

    private fun infoText(msg: String) = TextView(this).apply {
        text = msg; setTextColor(0xFFFFAA00.toInt()); setPadding(dp(8), dp(12), dp(8), dp(4)); textSize = 13f
    }

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun typeLabel(t: Int) = when (t) {
        0 -> "נסיון מעצר מ.צ."; 2 -> "התרעת מחסומים"; 3 -> "נסיון הסגרה"; else -> "התראה"
    }
    private fun typeIcon(t: Int) = when (t) { 3 -> "🔴"; 0 -> "🟡"; 2 -> "🟠"; else -> "⚪" }
    private fun typeColor(t: Int) = when (t) {
        3 -> 0xFFFF8000.toInt(); 0, 2 -> primary; else -> 0xFFC9CBD6.toInt()
    }
    private fun accentColor(t: Int) = when (t) {
        3 -> orange; 0, 2 -> primary; else -> 0xFF404150.toInt()
    }

    /** בונה כרטיס עם אקסנט צבעוני בצד + כפתור "הגעתי לזירה" */
    private fun buildCard(ev: AlertEvent, extraContent: (LinearLayout) -> Unit): View {
        val accent = accentColor(ev.eventType)
        val cityNames = ev.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        var lat = ev.lat; var lng = ev.lng
        if (lat == null || lng == null)
            ev.cities.firstNotNullOfOrNull { repo.cityByKey(it) }?.let { lat = it.lat; lng = it.lng }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(12)) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surface); cornerRadius = dp(16).toFloat()
            }
            elevation = dp(2).toFloat()
        }

        // פס אקסנט שמאלי
        wrapper.addView(android.view.View(this).apply {
            setBackgroundColor(accent)
            layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT).also {
                it.setMargins(0, dp(8), 0, dp(8))
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent); cornerRadius = dp(4).toFloat()
            }
        })

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // כותרת: אייקון + סוג + ערים
        inner.addView(TextView(this).apply {
            text = "${typeIcon(ev.eventType)} ${typeLabel(ev.eventType)}"
            textSize = 13f; setTextColor(typeColor(ev.eventType))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(3))
        })
        inner.addView(TextView(this).apply {
            text = cityNames; textSize = 17f; setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // זמן + כתובת
        val timeStr = DateUtils.getRelativeTimeSpanString(ev.time * 1000).toString()
        inner.addView(TextView(this).apply {
            text = if (ev.address.isNotBlank()) "$timeStr  ·  📍 ${ev.address}" else timeStr
            setTextColor(onVar); setPadding(0, dp(5), 0, 0); textSize = 13f
        })

        // תוכן נוסף (הערות, badges)
        extraContent(inner)

        // כפתור "הגעתי לזירה"
        val arrivedBtn = Button(this).apply {
            text = "📍 הגעתי לזירה"
            isAllCaps = false; textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
            ).also { it.topMargin = dp(10); it.gravity = Gravity.END }
            setPadding(dp(16), 0, dp(16), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(green); cornerRadius = dp(18).toFloat()
            }
            setTextColor(0xFFFFFFFF.toInt())
        }
        arrivedBtn.setOnClickListener { btn ->
            handleArrivalReport(btn as Button, ev, cityNames, lat, lng)
        }
        inner.addView(arrivedBtn)

        wrapper.addView(inner)
        return wrapper
    }

    private fun localCard(g: EventHistory): View {
        val wrapper = buildCard(g.latest) { inner ->
            // badges: עריכות + סגירה
            val badges = buildString {
                if (g.wasEdited) append("✏ נערך  ")
                if (g.wasClosed) append("✓ הסתיים")
            }
            if (badges.isNotBlank()) inner.addView(TextView(this).apply {
                text = badges; setTextColor(onVar); textSize = 12f; setPadding(0, dp(4), 0, 0)
            })
            // גרסאות עם הערות
            g.versions.forEach { v ->
                if (v.note.isBlank() && !v.isClosed) return@forEach
                inner.addView(TextView(this).apply {
                    val tag = if (v.isClosed) "🔒 סגירה" else "📝 ${v.note}"
                    text = tag; setPadding(0, dp(4), 0, 0); textSize = 13f; setTextColor(onSurface)
                    setLineSpacing(0f, 1.3f)
                })
            }
        }
        // לחיצה → פתיחת פרטים
        wrapper.setOnClickListener { openDetail(g.latest) }
        return wrapper
    }

    private fun serverCard(ev: AlertEvent): View {
        val wrapper = buildCard(ev) { inner ->
            if (ev.note.isNotBlank()) inner.addView(TextView(this).apply {
                text = ev.note; setPadding(0, dp(6), 0, 0); textSize = 14f; setTextColor(onSurface)
                setLineSpacing(0f, 1.3f)
            })
        }
        wrapper.setOnClickListener { openDetail(ev) }
        return wrapper
    }

    /** בדיקת מיקום ושליחת דיווח הגעה */
    private fun handleArrivalReport(btn: Button, ev: AlertEvent, cityNames: String, lat: Double?, lng: Double?) {
        btn.isEnabled = false
        btn.text = "בודק מיקום…"

        thread {
            val fix = LocationProvider.best(this)

            // אם אין מיקום לאירוע — שולחים בלי בדיקת מרחק
            if (lat == null || lng == null || lat == 0.0 && lng == 0.0) {
                sendArrivalReport(btn, ev, cityNames, null, null)
                return@thread
            }

            // אם אין GPS למשתמש — שואלים אם להמשיך
            if (fix == null) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    btn.isEnabled = true; btn.text = "📍 הגעתי לזירה"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("לא ניתן לקבוע מיקום")
                        .setMessage("לא הצלחנו לאתר את מיקומך כרגע.\n\nלשלוח דיווח הגעה בכל זאת?")
                        .setPositiveButton("כן, שלח") { _, _ -> sendArrivalReport(btn, ev, cityNames, null, null) }
                        .setNegativeButton("ביטול") { _, _ -> btn.isEnabled = true; btn.text = "📍 הגעתי לזירה" }
                        .show()
                }
                return@thread
            }

            val distKm = haversineKm(fix.lat, fix.lng, lat, lng)
            val maxKm = prefs.proximityRadiusKm.coerceAtLeast(1).toDouble()

            if (distKm <= maxKm) {
                // בתוך הטווח — שולח
                sendArrivalReport(btn, ev, cityNames, fix.lat, fix.lng)
            } else {
                // מחוץ לטווח — שואל
                val distStr = if (distKm < 1) "${(distKm * 1000).toInt()} מ'" else "${"%.1f".format(distKm)} ק\"מ"
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    btn.isEnabled = true; btn.text = "📍 הגעתי לזירה"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("אתה רחוק מהזירה")
                        .setMessage("המיקום שלך רחוק $distStr מהזירה.\n\nלשלוח דיווח הגעה בכל זאת?")
                        .setPositiveButton("כן, שלח") { _, _ -> sendArrivalReport(btn, ev, cityNames, fix.lat, fix.lng) }
                        .setNegativeButton("ביטול") { _, _ -> btn.isEnabled = true; btn.text = "📍 הגעתי לזירה" }
                        .show()
                }
            }
        }
    }

    private fun sendArrivalReport(btn: Button, ev: AlertEvent, cityNames: String, userLat: Double?, userLng: Double?) {
        runOnUiThread { btn.text = "שולח…"; btn.isEnabled = false }
        thread {
            val ok = runCatching {
                BlackAlertApi.reportArrival(
                    gatewayBase = prefs.gatewayUrl,
                    eventType = ev.eventType,
                    cities = ev.cities,
                    address = ev.address,
                    lat = userLat ?: ev.lat,
                    lng = userLng ?: ev.lng
                )
            }.isSuccess
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    btn.text = "✓ נשלח"
                    btn.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF1B5E20.toInt()); cornerRadius = dp(18).toFloat()
                    }
                } else {
                    btn.isEnabled = true; btn.text = "📍 הגעתי לזירה"
                    android.widget.Toast.makeText(this, "שליחה נכשלה — בדוק חיבור", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openDetail(ev: AlertEvent) {
        val cityNames = ev.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        var lat = ev.lat; var lng = ev.lng
        if (lat == null || lng == null)
            ev.cities.firstNotNullOfOrNull { repo.cityByKey(it) }?.let { lat = it.lat; lng = it.lng }
        startActivity(android.content.Intent(this, AlertActivity::class.java).apply {
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
        })
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
