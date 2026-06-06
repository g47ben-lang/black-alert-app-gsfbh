package com.blackalert.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.blackalert.app.data.CitiesRepository
import com.blackalert.app.data.Prefs

/**
 * בחירת ערים ואזורים לסינון — עם חיפוש וסימון הכל. ריק = כל הארץ (selectAll).
 */
class CitiesSelectActivity : AppCompatActivity() {

    private val areaBoxes = mutableListOf<Pair<Int, CheckBox>>()
    private val cityBoxes = mutableListOf<Pair<String, CheckBox>>()
    private lateinit var areasHeader: TextView
    private lateinit var citiesHeader: TextView

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
            setPadding(28, 16, 28, 24)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // חיפוש
        val search = EditText(this).apply {
            hint = "🔍 חיפוש עיר או אזור…"
            isSingleLine = true
        }
        root.addView(search)

        root.addView(TextView(this).apply {
            text = "ריק = התראות מכל הארץ"
            textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, 4, 0, 8)
        })

        // ===== אזורים =====
        areasHeader = TextView(this).apply { text = "אזורים"; textSize = 18f; setPadding(0, 12, 0, 4) }
        root.addView(areasHeader)
        root.addView(selectRow(
            onAll = {
                selectedAreas.clear(); selectedAreas.addAll(repo.allAreasSorted().map { it.id })
                prefs.selectedAreaIds = selectedAreas; areaBoxes.forEach { it.second.isChecked = true }
            },
            onClear = {
                selectedAreas.clear(); prefs.selectedAreaIds = selectedAreas
                areaBoxes.forEach { it.second.isChecked = false }
            }
        ))
        repo.allAreasSorted().forEach { area ->
            val cb = CheckBox(this).apply {
                text = area.he
                isChecked = selectedAreas.contains(area.id)
                setOnCheckedChangeListener { _, c ->
                    if (c) selectedAreas.add(area.id) else selectedAreas.remove(area.id)
                    prefs.selectedAreaIds = selectedAreas
                }
            }
            areaBoxes.add(area.id to cb); root.addView(cb)
        }

        // ===== ערים =====
        citiesHeader = TextView(this).apply { text = "ערים"; textSize = 18f; setPadding(0, 24, 0, 4) }
        root.addView(citiesHeader)
        root.addView(selectRow(
            onAll = {
                selectedCities.clear(); selectedCities.addAll(repo.allCitiesSorted().map { it.id })
                prefs.selectedCityIds = selectedCities; cityBoxes.forEach { it.second.isChecked = true }
            },
            onClear = {
                selectedCities.clear(); prefs.selectedCityIds = selectedCities
                cityBoxes.forEach { it.second.isChecked = false }
            }
        ))
        repo.allCitiesSorted().forEach { city ->
            val cb = CheckBox(this).apply {
                text = city.he
                isChecked = selectedCities.contains(city.id)
                setOnCheckedChangeListener { _, c ->
                    if (c) selectedCities.add(city.id) else selectedCities.remove(city.id)
                    prefs.selectedCityIds = selectedCities
                }
            }
            cityBoxes.add(city.he to cb); root.addView(cb)
        }

        // סינון לפי חיפוש
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString()?.trim() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        setContentView(NestedScrollView(this).apply { addView(root) })
    }

    private fun selectRow(onAll: () -> Unit, onClear: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Button(this@CitiesSelectActivity).apply {
            text = "סמן הכל"; isAllCaps = false; setOnClickListener { onAll() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(Button(this@CitiesSelectActivity).apply {
            text = "נקה"; isAllCaps = false; setOnClickListener { onClear() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun applyFilter(q: String) {
        var areaMatches = 0; var cityMatches = 0
        areaBoxes.forEach { (_, cb) ->
            val show = q.isEmpty() || cb.text.contains(q)
            cb.visibility = if (show) View.VISIBLE else View.GONE
            if (show) areaMatches++
        }
        cityBoxes.forEach { (name, cb) ->
            val show = q.isEmpty() || name.contains(q)
            cb.visibility = if (show) View.VISIBLE else View.GONE
            if (show) cityMatches++
        }
        areasHeader.visibility = if (areaMatches > 0) View.VISIBLE else View.GONE
        citiesHeader.visibility = if (cityMatches > 0) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
