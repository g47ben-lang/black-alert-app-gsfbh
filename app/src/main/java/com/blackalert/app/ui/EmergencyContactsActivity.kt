package com.blackalert.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton

/**
 * קווי חירום וארגוני סיוע — כל הטלפונים החשובים במקום אחד.
 */
class EmergencyContactsActivity : AppCompatActivity() {

    private val bg       = 0xFF0E0F14.toInt()
    private val surface  = 0xFF1A1B22.toInt()
    private val surface2 = 0xFF23242C.toInt()
    private val primary  = 0xFFFFD500.toInt()
    private val red      = 0xFFD32F2F.toInt()
    private val onSurface = 0xFFE7E7EC.toInt()
    private val onVar     = 0xFFA8AAB5.toInt()

    data class Contact(val name: String, val number: String, val desc: String = "")

    private val emergencyLines = listOf(
        Contact("צבע שחור",         "0738881250", "מוקד ראשי — צבע שחור"),
        Contact("החוטפים הגיעו",    "028008080",  "קו חירום למקרי חטיפה"),
    )

    private val supportOrgs = listOf(
        Contact("עם קדוש",              "*5172",      "ארגון סיוע לחיילים"),
        Contact("עזרם ומגינם",          "025000110",  "סיוע משפטי וביטחוני"),
        Contact("הפקדתי שומרים",        "093132142",  "ארגון הגנה על לומדי תורה"),
        Contact("אגודת בני הישיבות",   "029940030",  "סיוע להרחקת גיוס"),
        Contact("הצלה לאחים",          "025023231",  "סיוע וחילוץ"),
        Contact("נותנים גב",            "043132000",  "ארגון תמיכה"),
        Contact("אחים אנחנו",          "025795252",  "סיוע לנפגעים"),
        Contact("מגן ומושיע",          "*9273",      "ארגון הגנה"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "קווי חירום וארגוני סיוע"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val scroll = NestedScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        inner.addView(sectionHeader("קווי חירום", red))
        emergencyLines.forEach { inner.addView(contactCard(it, isEmergency = true)) }

        inner.addView(sectionHeader("ארגוני סיוע", primary))
        supportOrgs.forEach { inner.addView(contactCard(it, isEmergency = false)) }

        scroll.addView(inner)
        root.addView(scroll)
        setContentView(root)
    }

    private fun sectionHeader(title: String, color: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(12)) }
        }
        row.addView(View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(22)).apply {
                setMargins(0, 0, dp(12), 0)
            }
        })
        row.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(onSurface)
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun contactCard(contact: Contact, isEmergency: Boolean): View {
        val card = androidx.cardview.widget.CardView(this).apply {
            setCardBackgroundColor(surface)
            radius = dp(14).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = contact.name; textSize = 16f; setTextColor(onSurface)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (contact.desc.isNotBlank()) {
            textCol.addView(TextView(this).apply {
                text = contact.desc; textSize = 12f; setTextColor(onVar)
                setPadding(0, dp(3), 0, 0)
            })
        }
        textCol.addView(TextView(this).apply {
            text = formatNumber(contact.number)
            textSize = 14f
            setTextColor(if (isEmergency) 0xFFFF6B6B.toInt() else primary)
            setPadding(0, dp(4), 0, 0)
        })

        val btnCall = MaterialButton(this).apply {
            text = "חייג"
            textSize = 14f
            setTextColor(if (isEmergency) 0xFFFFFFFF.toInt() else 0xFF1A1B00.toInt())
            setBackgroundColor(if (isEmergency) red else primary)
            cornerRadius = dp(10)
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(44))
            setOnClickListener { dial(contact.number) }
        }

        row.addView(textCol)
        row.addView(btnCall)
        card.addView(row)
        return card
    }

    private fun formatNumber(raw: String): String {
        if (raw.startsWith("*")) return raw
        val digits = raw.filter { it.isDigit() }
        return when (digits.length) {
            9 -> "${digits.substring(0,2)}-${digits.substring(2,5)}-${digits.substring(5)}"
            10 -> "${digits.substring(0,3)}-${digits.substring(3,6)}-${digits.substring(6)}"
            else -> raw
        }
    }

    private fun dial(number: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
