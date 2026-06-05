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
        val withSound = intent.getBooleanExtra(EXTRA_WITH_SOUND, true)

        binding.alertTitle.text = title
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

        binding.btnNavigate.isEnabled = target != null
        binding.btnNavigate.alpha = if (target != null) 1f else 0.4f
        binding.btnNavigate.setOnClickListener {
            target?.let {
                startActivity(NavigationLauncher.buildChooser(it))
                stopRinging(); finish()
            }
        }
        binding.btnClose.setOnClickListener { stopRinging(); finish() }

        if (withSound) startRinging()
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

    private fun startRinging() {
        val prefs = Prefs(this)
        val resId = resources.getIdentifier(prefs.soundName, "raw", packageName).let {
            if (it != 0) it else R.raw.bell2
        }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlertActivity, android.net.Uri.parse("android.resource://$packageName/$resId"))
                isLooping = true
                prepare()
                start()
            }
        }
        if (prefs.vibrate) startVibration()
        // עצירה אוטומטית אחרי 60ש' אם המשתמש לא הגיב — לא לצלצל לנצח
        binding.root.postDelayed({ stopRinging() }, 60_000)
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
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LABEL = "label"
    }
}
