package com.blackalert.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.blackalert.app.data.Prefs

/**
 * נגן ההתראה המרכזי — אחראי על *כל* הצליל והרטט של אירוע, ממקום אחד שניתן לעצור.
 *
 * למה לא להשאיר את הצליל לערוץ ההתראה של המערכת? כי צליל-ערוץ הוא "ירה ושכח": ביטול
 * ההתראה אינו עוצר אותו. כך "סגור"/"השתקה"/"נווט" — כולם קוראים ל-stop() ומשתיקים מיד.
 *
 * רץ בתהליך האפליקציה (שירות-הרקע מחזיק אותו חי). מצלצל בלולאה עד stop() או עד תקרת בטיחות.
 */
object AlertRinger {
    private const val SAFETY_MS = 60_000L

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    /**
     * מתחיל צליל+רטט לפי ההגדרות. קריאה חוזרת מאתחלת (עוצרת קודם).
     * @param audible האם להשמיע צליל (כבר מנוכה שקט/רטט-בלבד ע"י הקורא).
     */
    @Synchronized
    fun start(context: Context, prefs: Prefs, audible: Boolean) {
        stop()
        val app = context.applicationContext
        if (prefs.vibrate || prefs.vibrateOnly) startVibration(app)
        if (audible) startSound(app, prefs)
        // תקרת בטיחות — שלא יצלצל לנצח אם איש לא הגיב.
        handler.postDelayed(autoStop, SAFETY_MS)
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(autoStop)
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startSound(context: Context, prefs: Prefs) {
        val uri = NotificationHelper(context).soundUri(prefs)
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun startVibration(context: Context) {
        vibrator = (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        val pattern = longArrayOf(0, 600, 400, 600, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        }
    }
}
