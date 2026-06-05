package com.blackalert.app.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.Prefs

/**
 * בחירת ערים ואזורים לסינון. ריק = כל הארץ (כמו selectAll בתוסף).
 * נבנה דינמית מ-cities.json (41 ערים/אזורים).
 */
class CitiesSelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "בחירת ערים ואזורים"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = Prefs(this)
        val repo = CitiesRepository.get(this)
        val selectedCities = prefs.selectedCityIds.toMutableSet()
        val selectedAreas = prefs.selectedAreaIds.toMutableSet()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        root.addView(TextView(this).apply {
            text = "אזורים"; textSize = 18f; setPadding(0, 8, 0, 8)
        })
        repo.allAreasSorted().forEach { area ->
            root.addView(CheckBox(this).apply {
                text = area.he
                isChecked = selectedAreas.contains(area.id)
                setOnCheckedChangeListener { _, c ->
                    if (c) selectedAreas.add(area.id) else selectedAreas.remove(area.id)
                    prefs.selectedAreaIds = selectedAreas
                }
            })
        }

        root.addView(TextView(this).apply {
            text = "ערים"; textSize = 18f; setPadding(0, 24, 0, 8)
        })
        repo.allCitiesSorted().forEach { city ->
            root.addView(CheckBox(this).apply {
                text = city.he
                isChecked = selectedCities.contains(city.id)
                setOnCheckedChangeListener { _, c ->
                    if (c) selectedCities.add(city.id) else selectedCities.remove(city.id)
                    prefs.selectedCityIds = selectedCities
                }
            })
        }

        setContentView(NestedScrollView(this).apply { addView(root) })
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
