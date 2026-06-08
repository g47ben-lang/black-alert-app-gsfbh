package com.blackalert.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.blackalert.app.data.Prefs
import com.blackalert.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    // בורר צלילי המכשיר — מחזיר URI של הצליל שנבחר
    private val ringtonePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            prefs.soundName = "custom"
            prefs.customSoundUri = uri.toString()
            updateCustomSoundLabel()
        }
    }

    // סוגי אירוע: id → תווית
    private val eventTypes = listOf(
        0 to "נסיון מעצר מ.צ.",
        2 to "התרעת מחסומים",
        3 to "נסיון הסגרה",
        8 to "התראה כללית"
    )
    private val sounds = listOf("bell2", "bell", "ding", "alarm", "siren", "phone")

    // שתי אפשרויות מסירה פשוטות בלבד
    private val deliveryModes = listOf(
        "auto" to "מהיר וחסכוני (Firebase — מומלץ)",
        "poll" to "עצמאי — סריקה ישירה (ללא Google)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // חלק המסירה מוצג תמיד (לא רק בדיבאג)
        binding.advancedSection.visibility = android.view.View.VISIBLE

        binding.btnSelectCities.setOnClickListener {
            startActivity(Intent(this, CitiesSelectActivity::class.java))
        }

        // סוגי אירוע (checkbox-ים)
        val selectedTypes = prefs.selectedEventTypes.toMutableSet()
        eventTypes.forEach { (id, label) ->
            val cb = android.widget.CheckBox(this).apply {
                text = label
                isChecked = selectedTypes.contains(id)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedTypes.add(id) else selectedTypes.remove(id)
                    prefs.selectedEventTypes = selectedTypes
                }
            }
            binding.eventTypesContainer.addView(cb)
        }

        // צליל (raw מצורף). בחירה מהספינר מבטלת צליל מותאם.
        binding.soundSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sounds)
        binding.soundSpinner.setSelection(sounds.indexOf(prefs.soundName).coerceAtLeast(0))
        binding.soundSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.soundName = sounds[pos]; updateCustomSoundLabel()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        binding.btnPickSound.setOnClickListener { openRingtonePicker() }
        updateCustomSoundLabel()

        // מתגים
        binding.switchVibrate.isChecked = prefs.vibrate
        binding.switchVibrate.setOnCheckedChangeListener { _, c -> prefs.vibrate = c }

        binding.switchVibrateOnly.isChecked = prefs.vibrateOnly
        binding.switchVibrateOnly.setOnCheckedChangeListener { _, c -> prefs.vibrateOnly = c }

        binding.switchDnd.isChecked = prefs.overrideDnd
        binding.switchDnd.setOnCheckedChangeListener { _, c ->
            prefs.overrideDnd = c
            if (c) requestDndAccessIfNeeded()
        }

        binding.switchFullscreen.isChecked = prefs.fullScreenAlert
        binding.switchFullscreen.setOnCheckedChangeListener { _, c -> prefs.fullScreenAlert = c }

        binding.switchSilentNotSelected.isChecked = prefs.silentNotSelected
        binding.switchSilentNotSelected.setOnCheckedChangeListener { _, c -> prefs.silentNotSelected = c }

        binding.switchLocalHistory.isChecked = prefs.localHistoryEnabled
        binding.switchLocalHistory.setOnCheckedChangeListener { _, c -> prefs.localHistoryEnabled = c }

        binding.switchProximity.isChecked = prefs.proximityEnabled
        binding.switchProximity.setOnCheckedChangeListener { _, c ->
            prefs.proximityEnabled = c
            if (c) ensureBackgroundLocationForProximity()
        }

        // קרבה: מצב מרחק/זמן + ערכים
        binding.switchProximityTime.isChecked = prefs.proximityMode == "time"
        binding.switchProximityTime.setOnCheckedChangeListener { _, c -> prefs.proximityMode = if (c) "time" else "radius" }
        binding.proximityRadius.setText(prefs.proximityRadiusKm.toString())
        binding.proximityTime.setText(prefs.proximityTimeMin.toString())
        binding.btnSaveProximity.setOnClickListener {
            prefs.proximityRadiusKm = binding.proximityRadius.text.toString().toIntOrNull() ?: prefs.proximityRadiusKm
            prefs.proximityTimeMin = binding.proximityTime.text.toString().toIntOrNull() ?: prefs.proximityTimeMin
            binding.proximityRadius.setText(prefs.proximityRadiusKm.toString())
            binding.proximityTime.setText(prefs.proximityTimeMin.toString())
            android.widget.Toast.makeText(this, "נשמר", android.widget.Toast.LENGTH_SHORT).show()
        }

        // שעות שקטות
        binding.switchQuiet.isChecked = prefs.quietEnabled
        binding.switchQuiet.setOnCheckedChangeListener { _, c -> prefs.quietEnabled = c }
        binding.quietFrom.setText(prefs.quietFrom)
        binding.quietTo.setText(prefs.quietTo)
        binding.btnSaveQuiet.setOnClickListener {
            prefs.quietFrom = binding.quietFrom.text.toString().ifBlank { "23:00" }
            prefs.quietTo = binding.quietTo.text.toString().ifBlank { "07:00" }
        }

        // מסירה: שתי אפשרויות פשוטות בלבד
        // "auto" = Firebase אם זמין; "poll" = סריקה ישירה ללא Google
        val deliveryIdx = deliveryModes.indexOfFirst { it.first == prefs.deliveryMode }
            .let { if (it < 0) 0 else it }
        binding.deliverySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deliveryModes.map { it.second })
        binding.deliverySpinner.setSelection(deliveryIdx)
        binding.deliverySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.deliveryMode = deliveryModes[pos].first
                updateDeliveryStatus()
                updatePollFieldsVisibility()
                if (deliveryModes[pos].first == "poll") requestIgnoreBatteryForPolling()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        binding.pollOn.setText(prefs.pollOnSec.toString())
        binding.pollOff.setText(prefs.pollOffSec.toString())
        binding.safetyPoll.setText(prefs.safetyPollMinutes.toString())
        updateDeliveryStatus()
        updatePollFieldsVisibility()
        binding.btnSaveDelivery.setOnClickListener {
            prefs.pollOnSec = binding.pollOn.text.toString().toIntOrNull() ?: prefs.pollOnSec
            prefs.pollOffSec = binding.pollOff.text.toString().toIntOrNull() ?: prefs.pollOffSec
            prefs.safetyPollMinutes = binding.safetyPoll.text.toString().toIntOrNull() ?: prefs.safetyPollMinutes
            binding.pollOn.setText(prefs.pollOnSec.toString())
            binding.pollOff.setText(prefs.pollOffSec.toString())
            binding.safetyPoll.setText(prefs.safetyPollMinutes.toString())
            com.blackalert.app.service.PushManager.applyDelivery(this)
            com.blackalert.app.service.PollingService.stop(this)
            if (prefs.serviceEnabled) com.blackalert.app.service.PollingService.start(this)
            updateDeliveryStatus()
            android.widget.Toast.makeText(this, "נשמר", android.widget.Toast.LENGTH_SHORT).show()
        }

        // מקור נתונים (מתקדם) — שינוי דורש הפעלה מחדש של השירות כדי שייכנס לתוקף
        binding.sourceUrl.setText(prefs.sourceBaseUrl)
        binding.btnSaveSource.setOnClickListener {
            val url = binding.sourceUrl.text.toString().trim()
            prefs.sourceBaseUrl = url.ifBlank { "https://black-alert.com" }
            com.blackalert.app.net.BlackAlertApi.base = prefs.sourceBaseUrl
            // הפעלה מחדש של השירות עם המקור החדש
            com.blackalert.app.service.PollingService.stop(this)
            if (prefs.serviceEnabled) com.blackalert.app.service.PollingService.start(this)
            android.widget.Toast.makeText(this, "מקור עודכן: ${prefs.sourceBaseUrl}", android.widget.Toast.LENGTH_SHORT).show()
        }

        // MQTT (push למכשירים ללא Google) — שינוי מפעיל מחדש את השירות
        binding.mqttBroker.setText(prefs.mqttBrokerUrl)
        binding.mqttTopic.setText(prefs.mqttTopic)
        binding.btnSaveMqtt.setOnClickListener {
            prefs.mqttBrokerUrl = binding.mqttBroker.text.toString()
            prefs.mqttTopic = binding.mqttTopic.text.toString()
            com.blackalert.app.service.PollingService.stop(this)
            if (prefs.serviceEnabled) com.blackalert.app.service.PollingService.start(this)
            val mode = com.blackalert.app.service.PushManager.effectiveMode(this)
            android.widget.Toast.makeText(this, "MQTT נשמר · ערוץ פעיל: $mode", android.widget.Toast.LENGTH_SHORT).show()
        }

        // כללי: בדיקה, סוללה, אודות
        binding.btnTest.setOnClickListener { showTestOptions() }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        refreshBatteryButton()
    }

    private fun refreshBatteryButton() {
        binding.btnBattery.visibility = if (isIgnoringBattery()) android.view.View.GONE else android.view.View.VISIBLE
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

    private fun fireTestAlert() {
        val now = System.currentTimeMillis() / 1000
        val test = com.blackalert.app.data.AlertEvent(
            notificationId = "test-$now", cities = listOf("בני ברק"), eventType = 3,
            time = now, expireAt = now + 120, version = 1, status = null, silent = false,
            isDrill = false, note = "התראת בדיקה — לחיצה על 'נווט' תפתח את בורר אפליקציות הניווט.",
            address = "רחוב רבי עקיבא", lat = 32.0874, lng = 34.8324
        )
        com.blackalert.app.util.NotificationHelper(this).showAlert(
            test, withSound = true,
            target = com.blackalert.app.service.NavTarget(32.0874, 34.8324, "בני ברק, רחוב רבי עקיבא"), prefs = prefs
        )
    }

    private fun showAbout() {
        val v = com.blackalert.app.BuildConfig.VERSION_NAME
        val msg = android.text.Html.fromHtml(
            "<b>צבע שחור</b> — גרסה $v<br><br>" +
            "<b>דיווחים</b> - מערכת צבע שחור<br>טלפון <a href=\"tel:0738881241\">0738881241</a><br><br>" +
            "<b>פיתוח</b> - <a href=\"https://github.com/613avi\">github.com/613avi</a><br><br>" +
            "<b>תרומה לפיתוח</b> - חברונר מועד ב<br><br>" +
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

    private fun openRingtonePicker() {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "בחר צליל התראה")
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            val cur = prefs.customSoundUri
            if (cur.isNotEmpty()) putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.net.Uri.parse(cur))
        }
        runCatching { ringtonePicker.launch(intent) }
            .onFailure { android.widget.Toast.makeText(this, "אין בורר צלילים במכשיר", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun updateCustomSoundLabel() {
        if (prefs.soundName == "custom" && prefs.customSoundUri.isNotEmpty()) {
            val title = runCatching {
                android.media.RingtoneManager.getRingtone(this, android.net.Uri.parse(prefs.customSoundUri))?.getTitle(this)
            }.getOrNull() ?: "צליל מותאם אישית"
            binding.customSoundLabel.visibility = android.view.View.VISIBLE
            binding.customSoundLabel.text = "✓ צליל נבחר: $title (גובר על הרשימה)"
        } else {
            binding.customSoundLabel.visibility = android.view.View.GONE
        }
    }

    /** סינון קרבה רץ ברקע → צריך מיקום "אפשר תמיד". אם חסר, מסביר ומפנה להגדרות. */
    private fun ensureBackgroundLocationForProximity() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return // <10: אין הרשאת רקע נפרדת
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("סינון לפי קרבה")
            .setMessage(
                "סינון לפי קרבה מתבצע ברקע, ולכן דורש הרשאת מיקום \"אפשר תמיד\".\n\n" +
                "בלעדיה — ההתראות עדיין יתקבלו (הליבה לא תלויה במיקום), אך הסינון לפי קרבה לא יפעל ברקע.\n\n" +
                "במסך שייפתח: הרשאות → מיקום → \"אפשר תמיד\"."
            )
            .setPositiveButton("פתח הגדרות") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("דלג", null)
            .show()
    }

    /** מציג שדות תדירות סריקה רק במצב עצמאי (poll); במצב Firebase הם מיותרים. */
    private fun updatePollFieldsVisibility() {
        val isPoll = prefs.deliveryMode == "poll"
        val vis = if (isPoll) android.view.View.VISIBLE else android.view.View.GONE
        binding.pollOn.visibility = vis
        binding.pollOff.visibility = vis
    }

    /** מצב עצמאי דורש רקע רציף → מבקש פטור מאופטימיזציית סוללה. */
    private fun requestIgnoreBatteryForPolling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("מצב עצמאי — חשוב!")
            .setMessage(
                "במצב עצמאי האפליקציה סורקת את השרת ברציפות.\n\n" +
                "בלי ביטול אופטימיזציית סוללה — אנדרואיד ירדים את האפליקציה ותפספס התראות.\n\n" +
                "ייפתח מסך — אשר \"אל תבצע אופטימיזציה\" עבור צבע שחור."
            )
            .setPositiveButton("פתח הגדרות") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }.onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
            .setNegativeButton("אחר כך", null)
            .show()
    }

    private fun requestDndAccessIfNeeded() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            android.widget.Toast.makeText(this, "אשר גישת 'נא לא להפריע' לאפליקציה", android.widget.Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
    }

    private fun updateDeliveryStatus() {
        val eff = com.blackalert.app.service.PushManager.effectiveMode(this)
        val available = com.blackalert.app.service.PushManager.isPushAvailable(this)
        binding.deliveryStatus.text = when {
            eff == "push" || eff == "fcm" -> "✓ פעיל כעת: מהיר (Firebase)"
            eff == "mqtt" -> "✓ פעיל כעת: MQTT"
            available -> "פעיל כעת: סריקה ישירה (Firebase זמין — ניתן לעבור למצב מהיר)"
            else -> "פעיל כעת: סריקה ישירה (Firebase לא זמין במכשיר זה)"
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
