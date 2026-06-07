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
        binding.btnTest.setOnClickListener { showTestOptions() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnReport.setOnClickListener { confirmReportArrest() }

        requestNotificationPermission()
        requestLocationPermission()
        ensureFullScreenPermission()   // ← בקשת הרשאת מסך-מלא (אנדרואיד 14+)

        // הפעלה ראשונה אחרי התקנה → הפניה לבחירת אזורי התראה
        if (!prefs.firstRunDone) {
            prefs.firstRunDone = true
            startActivity(Intent(this, CitiesSelectActivity::class.java))
        }
    }

    /**
     * מאנדרואיד 14 ומעלה — הרשאת USE_FULL_SCREEN_INTENT אינה ניתנת אוטומטית
     * לאפליקציות מחוץ ל-Google Play. בלעדיה ההתראה תופיע רק כ-heads-up בסטטוס בר.
     * כאן בודקים, ואם חסר — מסבירים ומפנים ישירות למסך האישור.
     */
    private fun ensureFullScreenPermission() {
        if (Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.canUseFullScreenIntent()) return
        if (prefs.fullScreenPermAsked) return
        prefs.fullScreenPermAsked = true

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("הרשאת התראה במסך מלא")
            .setMessage(
                "כדי שהתראות יקפצו במסך מלא וידליקו את המסך (גם כשהוא נעול), " +
                "יש לאשר \"התראות במסך מלא\" עבור צבע שחור.\n\n" +
                "ייפתח מסך הגדרות — הפעל את המתג עבור צבע שחור וחזור לאפליקציה."
            )
            .setPositiveButton("פתח הגדרות") { _, _ -> openFullScreenSettings() }
            .setNegativeButton("אחר כך", null)
            .show()
    }

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
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
        if (com.blackalert.app.BuildConfig.PLAY_STORE) return // ב-Play העדכונים דרך החנות
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
            .setPositiveButton("עדכן עכשיו") { _, _ -> doInAppUpdate(u) }
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
        binding.btnBattery.visibility = if (isIgnoringBattery()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showTestOptions() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("בדיקת התראה")
            .setMessage("כדי לראות את מסך ההתראה המלא — בחר \"בעוד 10 שניות\", נעל/כבה את המסך, והמתן.")
            .setPositiveButton("עכשיו") { _, _ -> fireTestAlert() }
            .setNeutralButton("בעוד 10 שניות") { _, _ ->
                android.widget.Toast.makeText(this, "נעל את המסך — הבדיקה תופיע בעוד 10 שניות", android.widget.Toast.LENGTH_LONG).show()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ fireTestAlert() }, 10_000)
            }
            .setNegativeButton("ביטול", null)
            .show()
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

    /** עדכון מתוך היישום: הורדת ה-APK והפעלת המתקין. */
    private fun doInAppUpdate(u: com.blackalert.app.net.UpdateChecker.Update) {
        val apkUrl = u.apkUrl
        if (apkUrl == null) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.pageUrl))); return }
        if (!com.blackalert.app.net.ApkUpdater.canInstall(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("הרשאת התקנה")
                .setMessage("כדי לעדכן מתוך היישום יש לאשר \"התקנה ממקור זה\". ייפתח מסך הגדרות — הפעל את ההרשאה ולחץ עדכן שוב.")
                .setPositiveButton("פתח הגדרות") { _, _ -> com.blackalert.app.net.ApkUpdater.requestInstallPermission(this) }
                .setNegativeButton("ביטול", null)
                .show()
            return
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false; setPadding(48, 40, 48, 24)
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("מוריד עדכון ${u.tag}…").setView(bar).setCancelable(false).create()
        dlg.show()
        kotlin.concurrent.thread {
            val file = com.blackalert.app.net.ApkUpdater.download(this, apkUrl) { p ->
                runOnUiThread { if (p < 0) bar.isIndeterminate = true else bar.progress = p }
            }
            runOnUiThread {
                runCatching { dlg.dismiss() }
                if (isFinishing) return@runOnUiThread
                if (file != null) {
                    if (!com.blackalert.app.net.ApkUpdater.installApk(this, file)) {
                        android.widget.Toast.makeText(this, "פתיחת המתקין נכשלה", android.widget.Toast.LENGTH_SHORT).show()
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.pageUrl)))
                    }
                } else {
                    android.widget.Toast.makeText(this, "ההורדה נכשלה — נסה דרך הדפדפן", android.widget.Toast.LENGTH_LONG).show()
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.pageUrl)))
                }
            }
        }
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
