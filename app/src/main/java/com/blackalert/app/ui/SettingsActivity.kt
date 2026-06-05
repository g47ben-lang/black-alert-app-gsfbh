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

        // צליל
        binding.soundSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sounds)
        binding.soundSpinner.setSelection(sounds.indexOf(prefs.soundName).coerceAtLeast(0))
        binding.soundSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.soundName = sounds[pos]
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // מתגים
        binding.switchVibrate.isChecked = prefs.vibrate
        binding.switchVibrate.setOnCheckedChangeListener { _, c -> prefs.vibrate = c }

        binding.switchFullscreen.isChecked = prefs.fullScreenAlert
        binding.switchFullscreen.setOnCheckedChangeListener { _, c -> prefs.fullScreenAlert = c }

        binding.switchSilentNotSelected.isChecked = prefs.silentNotSelected
        binding.switchSilentNotSelected.setOnCheckedChangeListener { _, c -> prefs.silentNotSelected = c }

        binding.switchLocalHistory.isChecked = prefs.localHistoryEnabled
        binding.switchLocalHistory.setOnCheckedChangeListener { _, c -> prefs.localHistoryEnabled = c }

        binding.switchProximity.isChecked = prefs.proximityEnabled
        binding.switchProximity.setOnCheckedChangeListener { _, c -> prefs.proximityEnabled = c }

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
