package com.blackalert.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.Prefs
import com.blackalert.app.net.BlackAlertApi
import com.blackalert.app.util.NotificationHelper
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * שירות Foreground שמריץ את לולאת ה-polling — מקביל ללולאת ה-Offscreen Document בתוסף,
 * אך כאן הוא שורד את הריגת המערכת כי הוא Foreground Service עם התראה מתמשכת.
 *
 * Poll כל ~10ש' (jitter ±15%), backoff מעריכי בכשל (עד 60ש'), watchdog 125ש' → "אין חיבור".
 */
class PollingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var processor: AlertProcessor
    private lateinit var notifications: NotificationHelper
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastOkTime = 0L
    private var pollFailures = 0
    private var connected = true
    private var lastListsCheck = 0L

    @Volatile private var screenOn = true
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        BlackAlertApi.base = prefs.sourceBaseUrl
        processor = AlertProcessor(this)
        notifications = NotificationHelper(this)
        lastOkTime = System.currentTimeMillis()
        // הפעלת push אם זמין (failover); הרשמה למצב מסך לחיסכון סוללה
        PushManager.applyDelivery(this)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        screenOn = pm.isInteractive
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationHelper.FOREGROUND_ID, notifications.buildForeground(connected))
        if (loopJob == null || loopJob?.isActive != true) {
            loopJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private var loopJob: Job? = null

    private suspend fun pollLoop() {
        // בדיקת גרסת רשימות פעם ראשונה (רענון cities.json אם השרת קידם גרסה)
        runCatching { checkLists(force = true) }
        while (currentCoroutineContext().isActive) {
            val ok = runOnce()
            updateForegroundConnectivity()
            val pushMode = PushManager.effectiveMode(this) == "push"
            val delayMs: Long = if (pushMode) {
                // push פעיל — בדיקה רק כ-safety-net (מניעת כשל). 0 = כבוי → מרווח ארוך מאוד.
                val m = prefs.safetyPollMinutes
                val baseMs = if (m <= 0) 6L * 60 * 60 * 1000 else m * 60_000L
                (baseMs * (0.85 + Random.nextDouble() * 0.30)).toLong()
            } else {
                // polling ידני: מסך דולק → מהיר; מסך כבוי → ארוך (חיסכון). backoff מעריכי בכשל.
                val baseMs = (if (screenOn) prefs.pollOnSec else prefs.pollOffSec) * 1000L
                val base = if (pollFailures > 0)
                    min(baseMs * 2.0.pow(pollFailures - 1).toLong(), 60_000L)
                else baseMs
                (base * (0.85 + Random.nextDouble() * 0.30)).toLong()
            }
            delay(delayMs)
            if (System.currentTimeMillis() - lastListsCheck > LISTS_CHECK_MS) {
                runCatching { checkLists(force = false) }
                runCatching { maybeNotifyUpdate() }
            }
        }
    }

    private fun runOnce(): Boolean {
        return try {
            val events = BlackAlertApi.fetchActiveNotifications()
            lastOkTime = System.currentTimeMillis()
            pollFailures = 0
            connected = true
            val decisions = processor.process(events)
            for (d in decisions) {
                when (d) {
                    is AlertDecision.Alert -> notifications.showAlert(d.event, d.withSound, d.target, prefs)
                    is AlertDecision.Close -> notifications.cancelAlert(d.event)
                    AlertDecision.Ignore -> {}
                }
            }
            true
        } catch (e: Exception) {
            pollFailures++
            if (System.currentTimeMillis() - lastOkTime > WATCHDOG_MS) connected = false
            false
        }
    }

    private fun checkLists(force: Boolean) {
        lastListsCheck = System.currentTimeMillis()
        val versions = BlackAlertApi.fetchListsVersions()
        val current = getSharedPreferences("ba_lists", Context.MODE_PRIVATE)
        val have = current.getInt("citiesVersion", -1)
        if (force && have == versions.cities && have != -1) return
        if (versions.cities > have || (force && have == -1)) {
            val json = BlackAlertApi.fetchCitiesJson(versions.cities)
            CitiesRepository.updateFromServer(this, json)
            current.edit().putInt("citiesVersion", versions.cities).apply()
        }
    }

    private fun updateForegroundConnectivity() {
        notifications.updateForeground(connected)
    }

    /** בדיקת עדכון יומית (ברקע) → התראה אם קיימת גרסה חדשה שלא נדחתה. */
    private fun maybeNotifyUpdate() {
        val u = com.blackalert.app.net.UpdateChecker.checkForUpdate() ?: return
        if (u.tag == prefs.dismissedUpdateTag) return
        notifications.showUpdate(u.tag, u.pageUrl)
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        // ניסיון החייאה: אם המשתמש לא כיבה ידנית — בקש מהמערכת להפעיל מחדש
        if (Prefs(this).serviceEnabled) {
            sendBroadcast(Intent(this, BootReceiver::class.java).setAction(BootReceiver.ACTION_RESTART))
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val POLL_INTERVAL_MS = 10_000L
        const val WATCHDOG_MS = 125_000L
        const val LISTS_CHECK_MS = 24L * 60 * 60 * 1000

        fun start(context: Context) {
            val i = Intent(context, PollingService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PollingService::class.java))
        }
    }
}
