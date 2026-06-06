package com.blackalert.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blackalert.app.R
import com.blackalert.app.data.AlertEvent
import com.blackalert.app.data.Prefs
import com.blackalert.app.databinding.ActivityMainBinding
import com.blackalert.app.service.AlertProcessor
import com.blackalert.app.service.NavTarget
import com.blackalert.app.service.PollingService
import com.blackalert.app.util.NotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* רענון מצב */ refreshUi() }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* זמני הגעה/קרבה ישתמשו במיקום אם אושר */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchService.setOnCheckedChangeListener { _, checked ->
            prefs.serviceEnabled = checked
            if (checked) PollingService.start(this) else PollingService.stop(this)
            refreshUi()
        }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.btnTest.setOnClickListener { fireTestAlert() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnReport.setOnClickListener { confirmReportArrest() }

        requestNotificationPermission()
        requestLocationPermission()

        // הפעלה ראשונה אחרי התקנה → הפניה לבחירת אזורי התראה
        if (!prefs.firstRunDone) {
            prefs.firstRunDone = true
            startActivity(Intent(this, CitiesSelectActivity::class.java))
        }
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        checkUpdateAsync()
        loadRecentAlerts()
    }

    /** טוען התראות אחרונות מהשרת ומציג כרטיסים לחיצים במסך הראשי. */
    private fun loadRecentAlerts() {
        val container = binding.recentContainer
        if (container.childCount == 0) {
            container.addView(android.widget.TextView(this).apply {
                text = "טוען…"; setTextColor(0xFFA8AAB5.toInt()); setPadding(8, 8, 8, 8)
            })
        }
        kotlin.concurrent.thread {
            val result = runCatching { com.blackalert.app.net.BlackAlertApi.fetchHistory() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                container.removeAllViews()
                result.onSuccess { events ->
                    val recent = events.sortedByDescending { it.time }.take(8)
                    if (recent.isEmpty()) {
                        container.addView(infoText("אין התראות אחרונות."))
                    } else {
                        val repo = com.blackalert.app.data.CitiesRepository.get(this)
                        recent.forEach { container.addView(recentCard(it, repo)) }
                    }
                }.onFailure {
                    container.addView(infoText("לא ניתן לטעון התראות מהשרת כרגע."))
                }
            }
        }
    }

    private fun infoText(s: String) = android.widget.TextView(this).apply {
        text = s; setTextColor(0xFFA8AAB5.toInt()); setPadding(8, 12, 8, 12); textSize = 14f
    }

    private fun recentCard(ev: com.blackalert.app.data.AlertEvent, repo: com.blackalert.app.data.CitiesRepository): android.view.View {
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            layoutParams = lp
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF23242C.toInt()); cornerRadius = 28f
            }
            isClickable = true
            setOnClickListener { openDetail(ev, repo) }
        }
        val type = when (ev.eventType) { 0 -> "נסיון מעצר מ.צ."; 2 -> "התרעת מחסומים"; 3 -> "נסיון הסגרה"; else -> "התראה" }
        val color = when (ev.eventType) { 3 -> 0xFFFF8000.toInt(); 0, 2 -> 0xFFFFD500.toInt(); else -> 0xFFC9CBD6.toInt() }
        val cityNames = ev.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        card.addView(android.widget.TextView(this).apply {
            text = "$type — $cityNames"; setTextColor(color); textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(android.widget.TextView(this).apply {
            text = android.text.format.DateUtils.getRelativeTimeSpanString(ev.time * 1000).toString() +
                (if (ev.address.isNotBlank()) "  ·  📍 ${ev.address}" else "")
            setTextColor(0xFFA8AAB5.toInt()); textSize = 12f; setPadding(0, 4, 0, 0)
        })
        return card
    }

    private fun openDetail(ev: com.blackalert.app.data.AlertEvent, repo: com.blackalert.app.data.CitiesRepository) {
        val cityNames = ev.cities.joinToString(", ") { repo.cityByKey(it)?.he ?: it }
        val title = when (ev.eventType) { 0 -> "נסיון מעצר מ.צ."; 2 -> "התרעת מחסומים"; 3 -> "נסיון הסגרה"; else -> "התראה" }
        var lat = ev.lat; var lng = ev.lng
        if (lat == null || lng == null) {
            ev.cities.firstNotNullOfOrNull { repo.cityByKey(it) }?.let { lat = it.lat; lng = it.lng }
        }
        val i = Intent(this, AlertActivity::class.java).apply {
            putExtra(AlertActivity.EXTRA_VIEW_ONLY, true)
            putExtra(AlertActivity.EXTRA_WITH_SOUND, false)
            putExtra(AlertActivity.EXTRA_TITLE, title)
            putExtra(AlertActivity.EXTRA_CITIES, cityNames)
            putExtra(AlertActivity.EXTRA_ADDRESS, ev.address)
            putExtra(AlertActivity.EXTRA_NOTE, ev.note)
            putExtra(AlertActivity.EXTRA_EVENT_TYPE, ev.eventType)
            val la = lat; val ln = lng
            if (la != null && ln != null && (la != 0.0 || ln != 0.0)) {
                putExtra(AlertActivity.EXTRA_LAT, la); putExtra(AlertActivity.EXTRA_LNG, ln)
                putExtra(AlertActivity.EXTRA_LABEL, if (ev.address.isNotBlank()) "$cityNames, ${ev.address}" else cityNames)
            }
        }
        startActivity(i)
    }

    private fun checkUpdateAsync() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheckMs < 6L * 60 * 60 * 1000) return
        kotlin.concurrent.thread {
            val u = com.blackalert.app.net.UpdateChecker.checkForUpdate()
            prefs.lastUpdateCheckMs = System.currentTimeMillis()
            if (u != null && u.tag != prefs.dismissedUpdateTag) runOnUiThread {
                if (!isFinishing) showUpdateDialog(u)
            }
        }
    }

    private fun showUpdateDialog(u: com.blackalert.app.net.UpdateChecker.Update) {
        val notes = if (u.notes.isBlank()) "" else "\n\n${u.notes.take(400)}"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("עדכון זמין — ${u.tag}")
            .setMessage("גרסה חדשה זמינה להורדה מגיטהאב.$notes")
            .setPositiveButton("עדכן") { _, _ ->
                val url = u.apkUrl ?: u.pageUrl
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton("אחר כך", null)
            .setNeutralButton("דלג על גרסה זו") { _, _ -> prefs.dismissedUpdateTag = u.tag }
            .show()
    }

    private fun refreshUi() {
        binding.switchService.isChecked = prefs.serviceEnabled
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
        binding.statusText.text = when {
            !prefs.serviceEnabled -> "○ השירות כבוי"
            !notifOk -> "✗ חסרה הרשאת התראות"
            else -> "● מאזין להתראות"
        }
        // הצעת פטור מאופטימיזציית סוללה (קריטי לאמינות רקע)
        binding.btnBattery.visibility = if (isIgnoringBattery()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** התראת בדיקה — מדמה אירוע מקומי דרך אותו צינור (כולל מסך מלא+צליל+ניווט). */
    private fun fireTestAlert() {
        val now = System.currentTimeMillis() / 1000
        val test = AlertEvent(
            notificationId = "test-$now", cities = listOf("בני ברק"), eventType = 3,
            time = now, expireAt = now + 120, version = 1, status = null, silent = false,
            isDrill = false, note = "התראת בדיקה — לחיצה על 'נווט' תפתח את בורר אפליקציות הניווט.",
            address = "רחוב רבי עקיבא", lat = 32.0874, lng = 34.8324
        )
        NotificationHelper(this).showAlert(
            test, withSound = true,
            target = NavTarget(32.0874, 34.8324, "בני ברק, רחוב רבי עקיבא"), prefs = prefs
        )
    }

    private fun confirmReportArrest() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🚨 דיווח על מעצר")
            .setMessage(
                "פעולה זו תחייג ישירות למוקד \"צבע שחור\" ותעביר אותך לשלוחת הדיווח על מעצר.\n\n" +
                "מספר: ${com.blackalert.app.util.ReportArrest.NUMBER} · שלוחה ${com.blackalert.app.util.ReportArrest.EXTENSION}\n" +
                "השלוחה תישלח אוטומטית לאחר המענה.\n\nלחייג עכשיו?"
            )
            .setPositiveButton("📞 חייג למוקד") { _, _ -> com.blackalert.app.util.ReportArrest.call(this) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun showAbout() {
        val v = com.blackalert.app.BuildConfig.VERSION_NAME
        val msg = android.text.Html.fromHtml(
            "<b>צבע שחור</b> — גרסה $v<br><br>" +
            "<b>דיווחים</b> - מערכת צבע שחור<br>טלפון <a href=\"tel:0738881241\">0738881241</a><br><br>" +
            "<b>פיתוח</b> - <a href=\"https://github.com/613avi\">github.com/613avi</a><br><br>" +
            "<small>הדיווחים נלקחים ממערכת \"צבע שחור\". היישום עצמאי ואינו מתופעל על ידה.</small>",
            android.text.Html.FROM_HTML_MODE_COMPACT
        )
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("אודות")
            .setMessage(msg)
            .setPositiveButton("סגור", null)
            .show()
        (dialog.findViewById<android.widget.TextView>(android.R.id.message))?.movementMethod =
            android.text.method.LinkMovementMethod.getInstance()
    }

    private fun isIgnoringBattery(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
