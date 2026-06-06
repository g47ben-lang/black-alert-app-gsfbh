package com.blackalert.app.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.blackalert.app.R
import com.blackalert.app.data.Prefs
import com.blackalert.app.databinding.ActivityAlertBinding
import com.blackalert.app.service.NavTarget
import com.blackalert.app.util.NavigationLauncher

/**
 * מסך ההתראה במסך מלא — נפתח דרך full-screen intent, מוצג מעל המסך הנעול ומדליק את המסך.
 * מצלצל בלולאה עד שהמשתמש מגיב, ומציע ניווט ליעד דרך בורר אפליקציות הניווט.
 */
class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
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
        stopRinging()
        bind(intent)
    }

    private fun bind(intent: Intent) {
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
                stopRinging(); finish()
            }
        }
        binding.btnClose.setOnClickListener { stopRinging(); finish() }

        startAlerting(audible = withSound)
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

    private fun startAlerting(audible: Boolean) {
        val prefs = Prefs(this)
        // רטט אם מופעל רטט או רטט-בלבד; צליל רק אם audible (כבר מנוכה רטט-בלבד/שקט)
        if (prefs.vibrate || prefs.vibrateOnly) startVibration()
        if (audible) startSound(prefs)
        // עצירה אוטומטית אחרי 60ש' אם המשתמש לא הגיב
        binding.root.postDelayed({ stopRinging() }, 60_000)
    }

    private fun startSound(prefs: Prefs) {
        val uri = com.blackalert.app.util.NotificationHelper(this).soundUri(prefs)
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlertActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun startVibration() {
        vibrator = (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        val pattern = longArrayOf(0, 600, 400, 600, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopRinging() {
        runCatching { player?.stop(); player?.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        stopRinging()
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
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LABEL = "label"
    }
}
