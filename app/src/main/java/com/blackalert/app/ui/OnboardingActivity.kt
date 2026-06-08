package com.blackalert.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.blackalert.app.data.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * מסך הקדמה — מוצג רק בהתקנה הראשונה.
 * מאפשר הגדרת קרבה + בחירת ערים לפני כניסה לאפליקציה, או דילוג.
 */
class OnboardingActivity : AppCompatActivity() {

    private val bg       = 0xFF0E0F14.toInt()
    private val surface  = 0xFF1A1B22.toInt()
    private val surface2 = 0xFF23242C.toInt()
    private val primary  = 0xFFFFD500.toInt()
    private val onSurface = 0xFFE7E7EC.toInt()
    private val onVar     = 0xFFA8AAB5.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val scroll = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(40), dp(28), dp(24))
        }

        // לוגו + כותרת
        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        try {
            val img = android.widget.ImageView(this).apply {
                setImageResource(com.blackalert.app.R.drawable.logo)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            logoRow.addView(img)
        } catch (_: Exception) {}
        logoRow.addView(tv(getString(com.blackalert.app.R.string.app_name), 26f, primary, Typeface.BOLD).apply {
            setPadding(dp(16), 0, 0, 0)
        })
        inner.addView(logoRow)

        inner.addView(tv("ברוכים הבאים", 22f, onSurface, Typeface.BOLD))
        inner.addView(tv(
            "לפני שמתחילים, כדאי להגדיר אילו התראות תקבל.\nניתן לשנות בכל עת בהגדרות.",
            15f, onVar
        ).apply { setPadding(0, dp(8), 0, dp(32)) })

        // קרטיס הגדרות קרבה
        inner.addView(sectionCard {
            addView(tv("סינון לפי מיקום", 16f, onSurface, Typeface.BOLD))
            addView(tv("קבל התראות רק על אירועים קרובים אליך", 13f, onVar).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
            val proximitySwitch = MaterialSwitch(this@OnboardingActivity).apply {
                text = "הפעל סינון לפי קרבה"
                setTextColor(onSurface)
                isChecked = prefs.proximityEnabled
                setOnCheckedChangeListener { _, c -> prefs.proximityEnabled = c }
            }
            addView(proximitySwitch)
        })

        inner.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(16)) })

        // כרטיס בחירת ערים
        inner.addView(sectionCard {
            addView(tv("ערים ואזורים להתראה", 16f, onSurface, Typeface.BOLD))
            addView(tv(
                "ברירת המחדל: כל הארץ.\nניתן לצמצם לאזורים ספציפיים.",
                13f, onVar
            ).apply { setPadding(0, dp(4), 0, dp(14)) })
            val btnCities = MaterialButton(this@OnboardingActivity).apply {
                text = "בחירת ערים ואזורים"
                setBackgroundColor(surface2)
                setTextColor(primary)
                strokeColor = android.content.res.ColorStateList.valueOf(primary)
                strokeWidth = dp(1)
                cornerRadius = dp(12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    startActivity(Intent(this@OnboardingActivity, CitiesSelectActivity::class.java))
                }
            }
            addView(btnCities)
        })

        scroll.addView(inner)
        root.addView(scroll)

        // כפתורי תחתית
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surface)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val btnSkip = MaterialButton(this).apply {
            text = "דלג"
            setTextColor(onVar)
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        }

        val btnStart = MaterialButton(this).apply {
            text = "סיום והתחל"
            setTextColor(0xFF1A1B00.toInt())
            setBackgroundColor(primary)
            cornerRadius = dp(14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply {
                setMargins(dp(12), 0, 0, 0)
            }
            setOnClickListener { finish() }
        }

        bottomRow.addView(btnSkip)
        bottomRow.addView(btnStart)
        root.addView(bottomRow)

        setContentView(root)
    }

    private fun tv(text: String, size: Float, color: Int, style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); setTypeface(typeface, style)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun sectionCard(block: LinearLayout.() -> Unit): View {
        val card = androidx.cardview.widget.CardView(this).apply {
            setCardBackgroundColor(surface)
            radius = dp(16).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            block()
        }
        card.addView(inner)
        return card
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
