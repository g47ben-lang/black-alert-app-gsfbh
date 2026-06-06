package com.blackalert.app.ui

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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

        // מסירה ותדירות (failover push / בדיקה ידנית)
        val deliveryModes = listOf("auto" to "אוטומטי", "push" to "push (גוגל)", "poll" to "בדיקה ידנית")
        binding.deliverySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deliveryModes.map { it.second })
        binding.deliverySpinner.setSelection(deliveryModes.indexOfFirst { it.first == prefs.deliveryMode }.coerceAtLeast(0))
        binding.deliverySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.deliveryMode = deliveryModes[pos].first
                updateDeliveryStatus()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        binding.pollOn.setText(prefs.pollOnSec.toString())
        binding.pollOff.setText(prefs.pollOffSec.toString())
        binding.safetyPoll.setText(prefs.safetyPollMinutes.toString())
        updateDeliveryStatus()
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
            eff == "push" -> "✓ פעיל כעת: push (גוגל)"
            available -> "פעיל כעת: בדיקה ידנית (push זמין — ניתן לעבור)"
            else -> "פעיל כעת: בדיקה ידנית (push לא זמין במכשיר זה)"
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
