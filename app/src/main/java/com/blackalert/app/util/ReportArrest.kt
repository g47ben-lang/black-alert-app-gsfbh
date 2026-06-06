package com.blackalert.app.util

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * "דיווח על מעצר" — חיוג למוקד "צבע שחור" עם שלוחת הדיווח שנשלחת אוטומטית אחרי מענה.
 *
 * מחרוזת החיוג: מספר + השהייה (פסיקים) + שלוחה. הפסיקים גורמים לשליחת ה-DTMF אוטומטית
 * אחרי שהשיחה נענית — כך שהמשתמש לא צריך ללחוץ ידנית על השלוחה.
 *
 * אם יש הרשאת CALL_PHONE → חיוג ישיר (צעד אחד). אחרת → פתיחת ה-dialer מוכן ללחיצת חיוג.
 */
object ReportArrest {
    // מוקד "צבע שחור" + שלוחת דיווח. (ניתן לעדכון במקום אחד.)
    const val NUMBER = "0738881241"
    const val EXTENSION = "*"
    private const val PERM_REQ = 7311

    /** מחרוזת החיוג: מספר, השהייה ~4ש', ואז השלוחה. */
    fun dialString(): String = "$NUMBER,,$EXTENSION"

    private fun telUri(): Uri = Uri.parse("tel:" + Uri.encode(dialString()))

    /** מבצע את החיוג. מחזיר true אם בוצע חיוג ישיר, false אם נפתח dialer (או בקשת הרשאה). */
    fun call(activity: Activity): Boolean {
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            return try {
                activity.startActivity(Intent(Intent.ACTION_CALL, telUri()))
                true
            } catch (_: Exception) { openDialer(activity); false }
        }
        // אין הרשאה — מבקשים אותה לפעם הבאה, ובינתיים פותחים dialer מוכן
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), PERM_REQ)
        openDialer(activity)
        return false
    }

    private fun openDialer(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, telUri()))
        } catch (_: Exception) { }
    }
}
