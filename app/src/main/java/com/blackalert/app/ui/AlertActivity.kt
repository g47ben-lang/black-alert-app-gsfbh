package com.blackalert.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.blackalert.app.R
import com.blackalert.app.data.Prefs
import com.blackalert.app.databinding.ActivityAlertBinding
import com.blackalert.app.service.NavTarget
import com.blackalert.app.util.AlertRinger
import com.blackalert.app.util.NavigationLauncher

/**
 * מסך ההתראה במסך מלא — נפתח דרך full-screen intent, מוצג מעל המסך הנעול ומדליק את המסך.
 * מצלצל בלולאה (דרך AlertRinger) עד שהמשתמש מגיב, ומציע ניווט ליעד.
 */
class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private var target: NavTarget? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AlertRinger.stop()
        bind(intent)
    }

    private fun bind(intent: Intent) {
        // כשמסך ההתראה המלא מוצג — אין צורך בהתראה כפולה בווילון; מבטלים אותה.
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(notifId)
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.event_type_8)
        val cities = intent.getStringExtra(EXTRA_CITIES) ?: ""
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: ""
        val note = intent.getStringExtra(EXTRA_NOTE) ?: ""
        val eventType = intent.getIntExtra(EXTRA_EVENT_TYPE, 8)
        val viewOnly = intent.getBooleanExtra(EXTRA_VIEW_ONLY, false)
        val withSound = intent.getBooleanExtra(EXTRA_WITH_SOUND, true) && !viewOnly

        binding.alertTitle.text = title
        binding.alertTitle.setTextColor(colorForEventType(eventType))
        binding.alertCities.text = cities
        binding.alertAddress.text = if (address.isBlank()) "" else "📍 $address"
        binding.alertAddress.visibility = if (address.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.alertNote.text = note
        binding.alertNote.visibility = if (note.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        target = if (intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LNG)) {
            NavTarget(
                intent.getDoubleExtra(EXTRA_LAT, 0.0),
                intent.getDoubleExtra(EXTRA_LNG, 0.0),
                intent.getStringExtra(EXTRA_LABEL) ?: cities
            )
        } else null

        loadMap(target, eventType)
        loadTravelTimes(target)

        binding.btnNavigate.isEnabled = target != null
        binding.btnNavigate.alpha = if (target != null) 1f else 0.4f
        binding.btnNavigate.setOnClickListener {
            target?.let {
                NavigationLauncher.launch(this, it)   // Waze כברירת מחדל, נפילה ל-geo:
                AlertRinger.stop(); finish()
            }
        }
        // "השתקה" — עוצר צפצוף+רטט אך משאיר את ההתראה גלויה (המשתמש עדיין רואה את הפרטים והמפה).
        binding.btnMute.text = getString(R.string.action_mute)   // איפוס למצב התראה חדשה (onNewIntent)
        binding.btnMute.isEnabled = true
        binding.btnMute.alpha = 1f
        binding.btnMute.setOnClickListener {
            AlertRinger.stop()
            binding.btnMute.text = getString(R.string.action_muted)
            binding.btnMute.isEnabled = false
            binding.btnMute.alpha = 0.5f
        }
        // X בפינה העליונה — סגירת המסך לגמרי.
        binding.btnCloseTop.setOnClickListener { AlertRinger.stop(); finish() }

        val gateway = com.blackalert.app.data.Prefs(this).gatewayUrl
        binding.btnArrived.visibility = if (viewOnly) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnArrived.setOnClickListener { reportArrival(gateway, title, cities, address) }

        startAlerting(audible = withSound, live = !viewOnly)
    }

    /** מחשב ומציג זמני הגעה ממיקום המשתמש (ברקע — לא חוסם את הצגת ההתראה). */
    private fun loadTravelTimes(target: NavTarget?) {
        if (target == null || !com.blackalert.app.data.Prefs(this).travelTimesEnabled) return
        val fix = com.blackalert.app.service.LocationProvider.best(this) ?: return
        binding.travelPanel.visibility = android.view.View.VISIBLE
        binding.travelMain.text = "מחשב מרחק ממיקומך…"
        binding.travelSub.text = ""
        Thread {
            val info = com.blackalert.app.util.TravelTime.compute(fix.lat, fix.lng, target.lat, target.lng)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                fun m(v: Int) = if (v < 0) "—" else "$v"
                binding.travelMain.text = "🚗 ${m(info.driveMin)} דק' נסיעה ממיקומך"
                val parts = mutableListOf<String>()
                if (info.showWalk) parts.add("🚶 ${info.walkMin} דק' הליכה")
                if (info.showBike) parts.add("🛴 ${info.bikeMin} דק' אופניים/קורקינט")
                if (parts.isEmpty()) {
                    binding.travelSub.visibility = android.view.View.GONE
                } else {
                    binding.travelSub.visibility = android.view.View.VISIBLE
                    binding.travelSub.text = parts.joinToString(" · ")
                }
            }
        }.start()
    }

    private fun colorForEventType(t: Int): Int = when (t) {
        3 -> 0xFFFF8000.toInt()        // נסיון הסגרה
        0, 2 -> 0xFFFFD500.toInt()     // מעצר מ.צ / מחסומים
        else -> 0xFFC9CBD6.toInt()     // כללי
    }

    /**
     * יוצר את מפת ה-WebView ב-runtime בתוך mapContainer. עטוף ב-try/catch כי במכשירים
     * מנוהלים/כשרים ייתכן שאין כלל WebView מותקן (MissingWebViewPackageException) — ואז
     * מציגים נתוני מיקום במקום, בלי לקרוס.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun loadMap(target: NavTarget?, eventType: Int) {
        if (target == null) {
            binding.mapContainer.visibility = android.view.View.GONE
            return
        }
        try {
            val web = android.webkit.WebView(this)
            web.settings.javaScriptEnabled = true
            web.setBackgroundColor(0xFF17181D.toInt())
            binding.mapContainer.addView(
                web,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            web.loadUrl("file:///android_asset/alert_map.html?lat=${target.lat}&lng=${target.lng}&type=$eventType")
        } catch (t: Throwable) {
            // אין WebView במכשיר — fallback לקואורדינטות + רמז לניווט
            binding.mapFallback.visibility = android.view.View.VISIBLE
            binding.mapFallback.text = "📍 ${target.label}\n${"%.5f".format(target.lat)}, ${"%.5f".format(target.lng)}\n\nהקש \"נווט\" לפתיחת המפה באפליקציית הניווט"
        }
    }

    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun startAlerting(audible: Boolean, live: Boolean) {
        // live=false (צפייה בהיסטוריה) → ללא רטט/צליל בכלל
        if (!live) return
        // הצליל+רטט דרך AlertRinger המרכזי — כך שכל סגירה/השתקה/ניווט (גם מההתראה) משתיקים מיד.
        AlertRinger.start(this, Prefs(this), audible)
    }

    private fun reportArrival(gateway: String, title: String, cities: String, address: String) {
        binding.btnArrived.isEnabled = false
        binding.btnArrived.text = "שולח…"
        val cityList = cities.split(", ").filter { it.isNotBlank() }
        val t = target
        Thread {
            val ok = runCatching {
                com.blackalert.app.net.BlackAlertApi.reportArrival(
                    gatewayBase = gateway,
                    eventType = intent.getIntExtra(EXTRA_EVENT_TYPE, 8),
                    cities = cityList,
                    address = address,
                    lat = t?.lat,
                    lng = t?.lng
                )
            }.isSuccess
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    binding.btnArrived.text = "✓ הדיווח נשלח"
                    binding.btnArrived.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1B5E20.toInt())
                } else {
                    binding.btnArrived.isEnabled = true
                    binding.btnArrived.text = "הגעתי לזירה"
                    android.widget.Toast.makeText(this, "שליחת הדיווח נכשלה — בדוק חיבור", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        AlertRinger.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_CITIES = "cities"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NOTE = "note"
        const val EXTRA_EVENT_TYPE = "eventType"
        const val EXTRA_WITH_SOUND = "withSound"
        const val EXTRA_VIEW_ONLY = "viewOnly"
        const val EXTRA_NOTIF_ID = "notifId"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LABEL = "label"
    }
}
