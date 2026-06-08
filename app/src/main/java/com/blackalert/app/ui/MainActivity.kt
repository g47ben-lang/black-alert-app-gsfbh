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
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.Prefs
import com.blackalert.app.data.ServerStateCache
import com.blackalert.app.databinding.ActivityMainBinding
import com.blackalert.app.service.PollingService
import com.blackalert.app.util.NotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUi() }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

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
        binding.btnCallExtradition.setOnClickListener { confirmCallExtradition() }
        binding.btnEmergencyContacts.setOnClickListener { startActivity(Intent(this, EmergencyContactsActivity::class.java)) }

        requestNotificationPermission()
        requestLocationPermission()
        ensureFullScreenPermission()

        if (!prefs.firstRunDone) {
            prefs.firstRunDone = true
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

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
        loadServerAlerts()
    }

    /**
     * טוען את ההתראות הנוכחיות מהשרת ושומר במטמון.
     * אם השרת לא זמין — מציג את המטמון האחרון (ללא התראות שנמחקו מהשרת).
     * לחיצה על "היסטוריה מלאה" פותחת את HistoryActivity עם כל ההיסטוריה המקומית.
     */
    private fun loadServerAlerts() {
        val container = binding.recentContainer
        container.removeAllViews()
        container.addView(infoText("טוען…"))

        kotlin.concurrent.thread {
            var fromCache = false
            var fetchError: String? = null
            val events = try {
                val fetched = com.blackalert.app.net.BlackAlertApi.fetchHistory()
                ServerStateCache.save(this, fetched)
                fetched
            } catch (e: Exception) {
                fromCache = true
                fetchError = e.message ?: e.javaClass.simpleName
                android.util.Log.w("BlackAlert", "fetchHistory failed: $fetchError")
                ServerStateCache.load(this)
            }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                container.removeAllViews()
                if (fromCache && events.isEmpty()) {
                    container.addView(infoText("⚠ אין חיבור לשרת ואין נתונים שמורים.\n($fetchError)"))
                    return@runOnUiThread
                }
                if (events.isEmpty()) {
                    container.addView(infoText("אין התראות פעילות כרגע בשרת."))
                    return@runOnUiThread
                }
                if (fromCache) {
                    container.addView(infoText("⚠ אין חיבור לשרת — מציג נתונים שמורים\n($fetchError)"))
                }
                val repo = CitiesRepository.get(this)
                events.sortedByDescending { it.time }.forEach { container.addView(alertCard(it, repo)) }
            }
        }
    }

    private fun infoText(s: String) = android.widget.TextView(this).apply {
        text = s; setTextColor(0xFFA8AAB5.toInt()); setPadding(8, 12, 8, 12); textSize = 14f
    }

    private fun alertCard(ev: AlertEvent, repo: CitiesRepository): android.view.View {
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

    private fun openDetail(ev: AlertEvent, repo: CitiesRepository) {
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

    private fun confirmCallExtradition() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🚨 דיווח על הסגרה")
            .setMessage(
                "פעולה זו תחייג ישירות למוקד \"צבע שחור\" ותעביר אותך לשלוחת הדיווח על הסגרה.\n\n" +
                "מספר: ${com.blackalert.app.util.ReportArrest.NUMBER} · שלוחה ${com.blackalert.app.util.ReportArrest.EXTENSION}\n" +
                "השלוחה תישלח אוטומטית לאחר המענה.\n\nלחייג עכשיו?"
            )
            .setPositiveButton("📞 חייג") { _, _ -> com.blackalert.app.util.ReportArrest.call(this) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun checkUpdateAsync() {
        if (com.blackalert.app.BuildConfig.PLAY_STORE) return
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
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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
}
