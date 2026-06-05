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

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        binding.switchService.isChecked = prefs.serviceEnabled
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
        binding.statusText.text = buildString {
            append(if (prefs.serviceEnabled) "● השירות פעיל\n" else "○ השירות כבוי\n")
            append(if (notifOk) "● הרשאת התראות: תקין" else "✗ חסרה הרשאת התראות")
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
